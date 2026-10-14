// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { AfterViewInit, Component, ElementRef, Input, OnChanges, OnDestroy, ViewChild } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { ProductionOverview, ProductionReport } from '@shared/models/production.models';
import * as echarts from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import moment from 'moment-timezone';

echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

@Component({
  selector: 'tb-production-board', templateUrl: './production-board.component.html',
  styleUrls: ['./production-board.component.scss'], standalone: false
})
export class ProductionBoardComponent implements AfterViewInit, OnChanges, OnDestroy {
  @Input() overview: ProductionOverview;
  @Input() speed: Array<{ts: number; value: number | string}> = [];
  @Input() output: ProductionReport;
  @Input() connectionLost = false;
  @Input() screenMode = false;
  @ViewChild('speedChart', {static: true}) speedChart: ElementRef<HTMLElement>;
  @ViewChild('outputChart', {static: true}) outputChart: ElementRef<HTMLElement>;
  private chartSpeed: echarts.ECharts;
  private chartOutput: echarts.ECharts;
  private resize: ResizeObserver;

  constructor(private translate: TranslateService, private host: ElementRef<HTMLElement>) {}

  ngAfterViewInit() {
    this.chartSpeed = echarts.init(this.speedChart.nativeElement);
    this.chartOutput = echarts.init(this.outputChart.nativeElement);
    this.resize = new ResizeObserver(() => this.resizeCharts());
    this.resize.observe(this.host.nativeElement);
    this.paint();
  }

  ngOnChanges() { this.paint(); }

  private paint() {
    const overview = this.overview;
    if (!overview || !this.output || !this.chartSpeed || !this.chartOutput) { return; }
    const unit = overview.config.unit;
    const blue = this.screenMode ? '#20498e' : '#255394';
    const labels = this.screenMode ? {color: '#637894', fontSize: 14} : {};
    const axes = this.screenMode ? {
      axisLine: {lineStyle: {color: '#a7bbd7'}}, axisTick: {show: false},
      splitLine: {lineStyle: {color: '#e3eaf4'}}, nameTextStyle: labels
    } : {};
    this.chartSpeed?.setOption({animation: false, grid: {left: 66, right: 20, top: 45, bottom: 42},
      tooltip: {trigger: 'axis'}, xAxis: {...axes, type: 'time', splitLine: {show: false},
        axisLabel: {...labels, formatter: value => this.displayTime(value, 'HH:mm')}},
      yAxis: {...axes, type: 'value', axisLabel: labels, name: `${unit}/${this.translate.instant('production.hour')}`},
      series: [{name: this.translate.instant('production.speed'), type: 'line', showSymbol: false, connectNulls: false,
        smooth: this.screenMode, lineStyle: {color: blue, width: this.screenMode ? 3 : 2}, itemStyle: {color: blue},
        areaStyle: this.screenMode ? {color: new echarts.graphic.LinearGradient(0, 0, 0, 1,
          [{offset: 0, color: '#477bd32b'}, {offset: 1, color: '#477bd303'}])} : undefined,
        data: this.speed.map(point => [point.ts, point.value])}]}, true);
    const dates = [...new Set(this.output.rows.map(row => row.productionDate))];
    const names = [...new Set(this.output.rows.map(row => `${row.shiftName} (${row.unit})`))];
    this.chartOutput?.setOption({animation: false, color: [blue, '#93a9c9', '#d2a44c'],
      grid: {left: 66, right: 20, top: 55, bottom: 42}, tooltip: {trigger: 'axis', axisPointer: {type: 'shadow'}},
      legend: {data: names, type: 'scroll', textStyle: labels},
      xAxis: {...axes, splitLine: {show: false}, type: 'category', axisLabel: labels, data: dates.map(date => date.slice(5))},
      yAxis: {...axes, type: 'value', axisLabel: labels, name: this.translate.instant('production.quantity')}, series: names.map(name => ({
        name, type: 'bar', stack: 'production', barMaxWidth: this.screenMode ? 52 : undefined, data: dates.map(date => {
          const rows = this.output.rows.filter(row => row.productionDate === date && `${row.shiftName} (${row.unit})` === name);
          return rows.length && rows.some(row => row.status !== 'NO_DATA' && row.status !== 'NOT_STARTED')
            ? rows.reduce((sum, row) => sum + row.quantity, 0) : null;
        })
      }))}, true);
    this.resizeCharts();
  }

  get staleSpeed() {
    return this.connectionLost || !this.overview?.speedTs || this.overview.now - this.overview.speedTs >
      Math.max(10000, this.overview.config.reportIntervalSeconds * 3000);
  }
  status(value: string) { return this.translate.instant(`production.status-${value?.toLowerCase() || 'no_data'}`); }
  displayTime(value: number, format = 'YYYY-MM-DD HH:mm:ss') {
    return value == null ? '—' : moment.tz(value, this.overview?.config.timezone || 'Asia/Shanghai').format(format);
  }
  private resizeCharts() { this.chartSpeed?.resize(); this.chartOutput?.resize(); }
  ngOnDestroy() {
    this.resize?.disconnect();
    this.chartSpeed?.dispose();
    this.chartOutput?.dispose();
  }
}
