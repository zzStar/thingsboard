// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { getCurrentAuthUser } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { EntityType } from '@shared/models/entity-type.models';
import { EntityId } from '@shared/models/id/entity-id';
import { DeviceService } from '@core/http/device.service';
import { ProductionService } from '@core/http/production.service';
import {
  ProductionBackfill, ProductionConfig, ProductionOverview, ProductionReport, ProductionSettings, ProductionScreenLink
} from '@shared/models/production.models';
import { DialogService } from '@core/services/dialog.service';
import { TranslateService } from '@ngx-translate/core';
import { ImportExportService } from '@shared/import-export/import-export.service';
import { ExportRow } from '@shared/models/widget.models';
import { catchError, forkJoin, map, of, Subscription, switchMap, throwError, timer } from 'rxjs';
import moment from 'moment-timezone';
import { DatetimeAdapter } from '@mat-datetimepicker/core';
import { ProductionDatetimeAdapter } from './production-datetime-adapter';

@Component({
  selector: 'tb-production', templateUrl: './production.component.html', styleUrls: ['./production.component.scss'],
  providers: [{provide: DatetimeAdapter, useClass: ProductionDatetimeAdapter}], standalone: false
})
export class ProductionComponent implements OnInit, OnDestroy {
  @ViewChild('page', {static: true}) page: ElementRef<HTMLElement>;
  readonly deviceType = EntityType.DEVICE;
  readonly canConfigure = [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER].includes(getCurrentAuthUser(this.store).authority);
  deviceId: string;
  selectedDevice: EntityId;
  deviceName = '';
  tab = 'overview';
  overview: ProductionOverview;
  settings: ProductionSettings;
  draft: ProductionConfig;
  effectiveDate: string;
  startTime: string;
  endTime: string;
  groupBy = 'day';
  shift: number | null = null;
  report: ProductionReport;
  pageIndex = 0;
  pageSize = 25;
  loading = false;
  querying = false;
  saving = false;
  backfill: ProductionBackfill;
  backfilling = false;
  error = '';
  notice = '';
  screenLink: ProductionScreenLink;
  canShareLink = false;
  linkBusy = false;
  chartSpeedData: Array<{ts: number; value: number}> = [];
  chartOutputData: ProductionReport;
  private subscriptions = new Subscription();
  private loadSubscription: Subscription;
  private chartSubscription: Subscription;
  private querySubscription: Subscription;
  private polling: Subscription;
  private pickerDates = new Map<string, Date>();

  constructor(private production: ProductionService, private devices: DeviceService, private store: Store<AppState>,
              private route: ActivatedRoute, private router: Router, private translate: TranslateService,
              private exportService: ImportExportService, private dialogs: DialogService) {}

  ngOnInit() {
    this.subscriptions.add(this.route.queryParamMap.subscribe(params => {
      const id = params.get('deviceId');
      if (id && id !== this.deviceId) {
        this.selectedDevice = {id, entityType: EntityType.DEVICE};
        this.changeDevice(id);
      } else if (!id && this.deviceId) {
        this.selectedDevice = null;
        this.clearDevice();
      }
    }));
  }

  selectDevice(value: EntityId | string | null) {
    const id = typeof value === 'string' ? value : value?.id;
    if (!id) {
      this.selectedDevice = null;
      this.clearDevice();
      this.router.navigate([], {relativeTo: this.route, queryParams: {deviceId: null}, queryParamsHandling: 'merge'});
      return;
    }
    if (id !== this.deviceId) {
      this.router.navigate([], {relativeTo: this.route, queryParams: {deviceId: id}, queryParamsHandling: 'merge'});
    }
  }

  private clearDevice() {
    this.pickerDates.clear();
    this.polling?.unsubscribe();
    this.loadSubscription?.unsubscribe();
    this.chartSubscription?.unsubscribe();
    this.querySubscription?.unsubscribe();
    this.deviceId = null;
    this.overview = null;
    this.report = null;
    this.chartSpeedData = [];
    this.chartOutputData = null;
    this.settings = null;
    this.draft = null;
    this.error = '';
    this.notice = '';
    this.backfill = null;
    this.backfilling = false;
    this.saving = false;
    this.loading = false;
    this.querying = false;
    this.screenLink = null;
    this.canShareLink = false;
    this.linkBusy = false;
  }

  private changeDevice(id: string) {
    this.clearDevice();
    this.deviceId = id;
    this.loading = true;
    this.loadSubscription = forkJoin({
      device: this.devices.getDevice(id, {ignoreLoading: true, ignoreErrors: true}),
      settings: this.production.settings(id), overview: this.production.overview(id),
      backfill: this.canConfigure ? this.production.backfill(id) : of(null),
      screenLink: this.canConfigure ? this.production.screenLink(id).pipe(
        map(link => ({link, allowed: true})),
        catchError(error => error.status === 403 ? of({link: null, allowed: false}) : throwError(() => error))
      ) : of({link: null, allowed: false})
    }).subscribe({next: result => {
      this.deviceName = result.device.label || result.device.name;
      this.settings = result.settings;
      this.backfill = result.backfill;
      this.screenLink = result.screenLink.link;
      this.canShareLink = result.screenLink.allowed;
      this.resetDraft();
      this.overview = result.overview;
      if (this.settings) { this.settings.current = result.overview.config; this.settings.now = result.overview.now; }
      this.startTime = this.localInput(result.overview.dayStart - 6 * 86400000);
      this.endTime = this.localInput(result.overview.now);
      this.loading = false;
      this.loadCharts();
      this.startPolling();
    }, error: error => this.fail(error)});
  }

  refresh() {
    if (!this.deviceId || this.loading || this.saving) { return; }
    this.loading = true;
    const id = this.deviceId;
    this.loadSubscription = forkJoin({overview: this.production.overview(id),
      backfill: this.canConfigure ? this.production.backfill(id) : of(null)}).subscribe({next: result => {
      if (id !== this.deviceId) { return; }
      const completed = this.backfill?.status === 'RUNNING' && result.backfill?.status === 'COMPLETE';
      this.backfill = result.backfill;
      this.overview = result.overview;
      if (this.settings) { this.settings.current = result.overview.config; this.settings.now = result.overview.now; }
      this.loading = false;
      this.error = '';
      if (this.tab === 'overview') { this.loadCharts(); }
      if (completed && this.tab === 'history') { this.query(); }
    }, error: error => this.fail(error)});
  }

  private startPolling() {
    this.polling?.unsubscribe();
    this.polling = timer(5000, 5000).subscribe(() => this.refresh());
  }

  setTab(tab: string) {
    this.tab = tab;
    if (tab === 'history' && !this.report) { this.query(); }
  }

  get screenUrl(): string {
    if (!this.screenLink || !this.deviceId) { return ''; }
    const url = new URL(`production-screen/${this.deviceId}`, document.baseURI);
    url.hash = new URLSearchParams({key: this.screenLink.key}).toString();
    return url.toString();
  }

  copyScreenLink() { this.notice = this.translate.instant('production.screen-copied'); }

  createScreenLink(rotate = false) {
    const id = this.deviceId;
    this.linkBusy = true;
    this.subscriptions.add(this.production.createScreenLink(id, rotate).subscribe({next: link => {
      if (id !== this.deviceId) { return; }
      this.screenLink = link;
      this.linkBusy = false;
      this.error = '';
      this.notice = this.translate.instant(rotate ? 'production.screen-reset-done' : 'production.screen-created');
    }, error: error => { if (id === this.deviceId) { this.linkBusy = false; this.fail(error); } }}));
  }

  resetScreenLink() {
    const id = this.deviceId;
    this.subscriptions.add(this.dialogs.confirm(this.translate.instant('production.screen-reset'),
      this.translate.instant('production.screen-reset-confirm')).subscribe(confirmed => {
      if (confirmed && id === this.deviceId) { this.createScreenLink(true); }
    }));
  }

  revokeScreenLink() {
    const id = this.deviceId;
    this.subscriptions.add(this.dialogs.confirm(this.translate.instant('production.screen-revoke'),
      this.translate.instant('production.screen-revoke-confirm')).subscribe(confirmed => {
      if (!confirmed || id !== this.deviceId) { return; }
      this.linkBusy = true;
      this.subscriptions.add(this.production.revokeScreenLink(id).subscribe({next: () => {
        if (id !== this.deviceId) { return; }
        this.screenLink = null;
        this.linkBusy = false;
        this.notice = this.translate.instant('production.screen-revoked');
      }, error: error => { if (id === this.deviceId) { this.linkBusy = false; this.fail(error); } }}));
    }));
  }

  private loadCharts() {
    if (!this.overview) { return; }
    this.chartSubscription?.unsubscribe();
    const overview = this.overview;
    this.chartSubscription = forkJoin({
      speed: this.production.speed(this.deviceId, overview.dayStart, overview.now),
      output: this.production.report(this.deviceId, overview.dayStart - 6 * 86400000, overview.now, 'shift')
    }).subscribe({next: data => {
      this.chartSpeedData = data.speed;
      this.chartOutputData = data.output;
    }, error: error => { this.error = error?.error?.message || this.translate.instant('production.load-error'); }});
  }

  query() {
    if (!this.deviceId || !this.overview) { return; }
    const zone = this.overview.config.timezone;
    const start = moment.tz(this.startTime, 'YYYY-MM-DDTHH:mm', true, zone).valueOf();
    const end = moment.tz(this.endTime, 'YYYY-MM-DDTHH:mm', true, zone).valueOf();
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || end - start > 366 * 86400000) {
      this.error = this.translate.instant('production.invalid-range'); return;
    }
    this.querySubscription?.unsubscribe();
    this.querying = true;
    this.error = '';
    this.querySubscription = this.production.report(this.deviceId, start, end, this.groupBy, this.shift).subscribe({next: report => {
      this.report = report;
      this.pageIndex = 0;
      this.querying = false;
    }, error: error => { this.querying = false; this.fail(error); }});
  }

  get visibleRows() { return this.report?.rows.slice(this.pageIndex * this.pageSize, (this.pageIndex + 1) * this.pageSize) || []; }
  get reportTotal() { return this.report?.rows.reduce((sum, row) => sum + row.quantity, 0) || 0; }
  get shiftOptions() {
    const options = new Map<number, Set<string>>();
    const versions = this.settings?.versions.filter(version => version.effectiveTs <= this.overview?.now) || [];
    for (const version of versions) {
      version.config.shifts.forEach((shift, index) => {
        if (!options.has(index)) { options.set(index, new Set()); }
        options.get(index).add(shift.name);
      });
    }
    return [...options].sort(([a], [b]) => a - b).map(([index, names]) => ({index, name: [...names].join(' / ')}));
  }
  get staleSpeed() {
    return !this.overview?.speedTs || this.overview.now - this.overview.speedTs > Math.max(10000, this.overview.config.reportIntervalSeconds * 3000);
  }

  resetDraft() {
    if (!this.settings) { return; }
    const pending = this.settings.versions.find(version => version.effectiveTs > this.settings.now);
    this.draft = JSON.parse(JSON.stringify(pending?.config || this.settings.current));
    this.effectiveDate = pending ? moment.tz(pending.effectiveTs, this.draft.timezone).format('YYYY-MM-DD')
      : moment.tz(this.settings.now, this.draft.timezone).add(1, 'day').format('YYYY-MM-DD');
  }

  changeShiftCount(count: number) {
    const origin = this.draft.shifts[0]?.start || '07:00';
    const [hour, minute] = origin.split(':').map(Number);
    this.draft.shifts = Array.from({length: count}, (_, i) => {
      const time = (hour * 60 + minute + i * 1440 / count) % 1440;
      return {name: this.translate.instant(count === 2 ? i === 0 ? 'production.day-shift' : 'production.night-shift'
        : ['production.early-shift', 'production.middle-shift', 'production.night-shift'][i]),
        start: `${Math.floor(time / 60).toString().padStart(2, '0')}:${(time % 60).toString().padStart(2, '0')}`};
    });
  }

  shiftEnd(index: number) { return this.draft.shifts[(index + 1) % this.draft.shifts.length].start; }

  get scheduleChanged() {
    if (!this.draft || !this.settings) { return false; }
    const pending = this.settings.versions.find(version => version.effectiveTs > this.settings.now);
    const target = pending?.config || this.settings.current;
    return this.draft.timezone !== target.timezone || this.draft.reportIntervalSeconds !== target.reportIntervalSeconds ||
      JSON.stringify(this.draft.shifts) !== JSON.stringify(target.shifts) ||
      !!pending && !!this.effectiveDate && this.effectiveDate !== moment.tz(pending.effectiveTs, this.draft.timezone).format('YYYY-MM-DD');
  }

  saveSettings() {
    const id = this.deviceId;
    this.saving = true;
    this.error = '';
    this.polling?.unsubscribe();
    this.loadSubscription?.unsubscribe();
    this.querySubscription?.unsubscribe();
    this.querying = false;
    this.loading = false;
    this.subscriptions.add(this.production.saveSettings(id, this.draft, this.effectiveDate || null).pipe(
      switchMap(settings => forkJoin({settings: of(settings), overview: this.production.overview(id)}))
    ).subscribe({next: result => {
      if (id !== this.deviceId) { return; }
      this.settings = result.settings;
      this.overview = result.overview;
      this.resetDraft();
      this.loadCharts();
      if (this.report) { this.query(); }
      this.saving = false;
      this.notice = this.translate.instant('production.saved');
      this.startPolling();
    }, error: error => {
      if (id === this.deviceId) { this.saving = false; this.fail(error); this.startPolling(); }
    }}));
  }

  startBackfill() {
    const id = this.deviceId;
    const zone = this.overview.config.timezone;
    const start = moment.tz(this.startTime, 'YYYY-MM-DDTHH:mm', true, zone).valueOf();
    const end = moment.tz(this.endTime, 'YYYY-MM-DDTHH:mm', true, zone).valueOf();
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || end > Date.now() || end - start > 31 * 86400000) {
      this.error = this.translate.instant('production.backfill-range'); return;
    }
    this.backfilling = true;
    this.subscriptions.add(this.production.startBackfill(id, start, end).subscribe({next: result => {
      if (id !== this.deviceId) { return; }
      this.backfill = result;
      this.backfilling = false;
      this.notice = this.translate.instant('production.backfill-started');
    }, error: error => { if (id === this.deviceId) { this.backfilling = false; this.fail(error); } }}));
  }

  export(format: 'csv' | 'xlsx') {
    if (!this.report) { return; }
    const label = (key: string) => this.translate.instant(`production.${key}`);
    const data: ExportRow[] = this.report.rows.map(row => ({
      [label('device')]: this.deviceName, [label('production-date')]: row.productionDate,
      [label('shift')]: row.shiftName, [label('start')]: this.displayTime(row.startTs), [label('end')]: this.displayTime(row.endTs),
      [label('quantity')]: row.quantity, [label('unit')]: row.unit, [label('status')]: this.status(row.status),
      [label('partial')]: row.partial ? label('yes') : label('no'), [label('resets')]: row.resets,
      [label('query-start')]: this.displayTime(this.report.startTs), [label('query-end')]: this.displayTime(this.report.endTs),
      [label('data-as-of')]: this.report.dataAsOf ? this.displayTime(this.report.dataAsOf) : '', [label('timezone')]: row.timezone
    }));
    const filename = `${this.deviceName}_${this.report.groupBy}_${moment(this.report.startTs).format('YYYYMMDD')}`;
    if (format === 'csv') { this.exportService.exportCsv(data, filename, true); }
    else { this.exportService.exportXlsx(data, filename, 'yyyy-MM-dd HH:mm:ss', true); }
  }

  status(value: string) { return this.translate.instant(`production.status-${value?.toLowerCase() || 'no_data'}`); }
  displayTime(value: number, format = 'YYYY-MM-DD HH:mm:ss') {
    return value == null ? '—' : moment.tz(value, this.overview?.config.timezone || 'Asia/Shanghai').format(format);
  }
  private localInput(value: number) { return this.displayTime(value, 'YYYY-MM-DDTHH:mm'); }

  // Picker dates carry wall-clock fields; queries still interpret them in the device timezone.
  pickerDate(value: string, format = 'YYYY-MM-DDTHH:mm'): Date | null {
    if (!value) { return null; }
    const key = `${format}:${value}`;
    if (this.pickerDates.has(key)) { return this.pickerDates.get(key); }
    const date = moment(value, format, true);
    if (!date.isValid()) { return null; }
    // NgModel must receive a stable Date reference across change detection passes.
    const result = date.toDate();
    this.pickerDates.set(key, result);
    return result;
  }

  pickerText(value: Date | null, format = 'YYYY-MM-DDTHH:mm'): string {
    if (!value) { return ''; }
    const date = moment(value);
    return date.isValid() ? date.format(format) : '';
  }

  async fullscreen() {
    if (document.fullscreenElement) { await document.exitFullscreen(); }
    else { await this.page.nativeElement.requestFullscreen(); }
  }

  private fail(error: any) {
    this.loading = false;
    this.error = error?.error?.message || this.translate.instant('production.load-error');
  }
  ngOnDestroy() {
    this.clearDevice();
    this.subscriptions.unsubscribe();
  }
}
