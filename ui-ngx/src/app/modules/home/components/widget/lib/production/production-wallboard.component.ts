// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { AfterViewInit, ChangeDetectorRef, Component, ElementRef, Input, NgZone, OnDestroy, ViewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { WidgetContext } from '@home/models/widget-component.models';
import { defaultHttpOptions } from '@core/http/http-utils';
import { WallboardDay, WallboardDevice, WallboardFrame, WallboardSelection, WallboardSnapshot, WallboardType } from '@shared/models/production-wallboard.models';
import { Subscription, timeout } from 'rxjs';

@Component({
  selector: 'tb-production-wallboard', standalone: false,
  templateUrl: './production-wallboard.component.html', styleUrls: ['./production-wallboard.component.scss']
})
export class ProductionWallboardComponent implements AfterViewInit, OnDestroy {
  @Input() ctx: WidgetContext;
  @ViewChild('viewport', {static: true}) viewport: ElementRef<HTMLElement>;
  snapshot: WallboardSnapshot;
  selection: WallboardSelection;
  navigationGroups: Array<{type: WallboardType; devices: WallboardSnapshot['deviceChoices']}> = [];
  expandedTypeId: string | null = null;
  private navigationInitialized = false;
  error = '';
  disconnected = false;
  paused = false;
  clock = new Date();
  lastSuccess: number;
  scale = 1;
  left = 0;
  top = 0;
  countdown = 20;
  isLight = false;
  private themeOverride: 'light' | 'dark';
  private themeStorageKey: string;
  private systemTheme: MediaQueryList;
  private readonly onSystemThemeChange = () => this.zone.run(() => {
    this.updateTheme();
    this.cd.markForCheck();
  });
  private request: Subscription;
  private observer: ResizeObserver;
  private clockTimer: ReturnType<typeof setInterval>;
  private nextRefresh = 0;
  private nextPage = 0;
  private generation = 0;
  private destroyed = false;
  private dashboardId: string;

  constructor(private http: HttpClient, private cd: ChangeDetectorRef, private zone: NgZone) {}

  ngAfterViewInit() {
    this.dashboardId = this.ctx?.dashboard?.stateController?.dashboardCtrl?.dashboardCtx?.getDashboard()?.id?.id;
    this.selection = {widgetId: this.ctx?.widgetId, view: 'ALL', page: 0};
    this.themeStorageKey = `production-wallboard-theme:${this.dashboardId}:${this.selection.widgetId}`;
    this.systemTheme = window.matchMedia('(prefers-color-scheme: light)');
    this.systemTheme.addEventListener('change', this.onSystemThemeChange);
    try {
      const saved = localStorage.getItem(this.themeStorageKey);
      if (saved === 'light' || saved === 'dark') this.themeOverride = saved;
    } catch { /* Appearance remains available when browser storage is disabled. */ }
    this.updateTheme();
    this.observer = new ResizeObserver(() => this.zone.run(() => this.resize()));
    this.observer.observe(this.viewport.nativeElement);
    this.resize();
    if (!this.dashboardId || !this.selection.widgetId) {
      this.error = '请先保存看板，再预览产量大屏。';
      this.cd.detectChanges();
      return;
    }
    this.load();
    this.clockTimer = setInterval(() => {
      this.clock = new Date();
      const now = Date.now();
      this.countdown = Math.max(0, Math.ceil((this.nextPage - now) / 1000));
      if (!this.paused && this.snapshot?.pageCount > 1 && now >= this.nextPage) this.changePage(1, false);
      else if (now >= this.nextRefresh && !this.request) this.load();
      this.cd.markForCheck();
    }, 1000);
  }

  private resize() {
    const {width, height} = this.viewport.nativeElement.getBoundingClientRect();
    this.scale = Math.min(width / 1920, height / 1080);
    this.left = (width - 1920 * this.scale) / 2;
    this.top = (height - 1080 * this.scale) / 2;
    this.cd.detectChanges();
  }

  private load() {
    if (this.destroyed) return;
    const revision = ++this.generation;
    const selection = {...this.selection};
    this.request?.unsubscribe();
    this.nextRefresh = Date.now() + (this.snapshot?.config.display.refreshSeconds || 5) * 1000;
    this.request = this.http.post<WallboardSnapshot>(`/api/production/boards/${this.dashboardId}/snapshot`, selection,
      defaultHttpOptions(true, true)).pipe(timeout(12000)).subscribe({
      next: result => {
        if (revision !== this.generation || this.destroyed) return;
        const scopeChanged = this.snapshot && result.effectiveScopeRevision !== this.snapshot.effectiveScopeRevision;
        this.snapshot = result;
        this.clock = new Date();
        this.updateTheme();
        this.updateNavigation(result);
        this.selection = {widgetId: selection.widgetId, view: result.view, typeId: result.typeId, deviceId: result.deviceId, page: result.page};
        this.lastSuccess = Date.now();
        this.error = '';
        this.disconnected = false;
        this.request = null;
        if (!this.nextPage || scopeChanged) this.resetPageTimer();
        this.cd.markForCheck();
      },
      error: failure => {
        if (revision !== this.generation || this.destroyed) return;
        this.request = null;
        this.disconnected = true;
        if ([401, 403, 404].includes(failure.status)) {
          this.snapshot = null;
          this.clearNavigation();
          this.error = '当前看板或设备范围不可访问，请检查系统权限及公开设置。';
        } else if (failure.status === 400) {
          // A selected device/type can leave the native permission scope between polls.
          this.snapshot = null;
          this.clearNavigation();
          this.error = failure.error?.message || '大屏配置或选择已失效，请返回全部设备。';
        } else this.error = this.snapshot ? '连接中断，正在重连' : '数据暂不可用，正在重试';
        this.cd.markForCheck();
      }
    });
  }

  private updateNavigation(snapshot: WallboardSnapshot) {
    const devices = new Map<string, WallboardSnapshot['deviceChoices']>();
    for (const device of snapshot.deviceChoices) {
      if (!devices.has(device.typeId)) devices.set(device.typeId, []);
      devices.get(device.typeId).push(device);
    }
    this.navigationGroups = snapshot.types.map(type => ({type, devices: devices.get(type.id) || []}));
    if (!this.navigationInitialized) {
      this.expandedTypeId = snapshot.typeId || snapshot.types[0]?.id || null;
      this.navigationInitialized = true;
    } else if (this.expandedTypeId && !snapshot.types.some(type => type.id === this.expandedTypeId)) {
      this.expandedTypeId = snapshot.typeId;
    }
  }

  private clearNavigation() {
    this.navigationGroups = [];
    this.expandedTypeId = null;
    this.navigationInitialized = false;
  }

  toggleType(typeId: string) { this.expandedTypeId = this.expandedTypeId === typeId ? null : typeId; }

  get viewName(): string {
    if (this.selection?.view === 'TYPE') {
      return this.navigationGroups.find(group => group.type.id === this.selection.typeId)?.type.name || '分类产量';
    }
    if (this.selection?.view === 'DEVICE') {
      const group = this.navigationGroups.find(item => item.type.id === this.selection.typeId);
      return group?.devices.find(device => device.id === this.selection.deviceId)?.name || '设备产量';
    }
    return '全类型总览';
  }

  get navigationDeviceCount(): number {
    return this.navigationGroups.reduce((sum, group) => sum + group.devices.length, 0);
  }

  get viewDescription(): string {
    if (this.selection?.view === 'ALL') return `${this.navigationGroups.length} 类 · ${this.navigationDeviceCount} 台设备`;
    const type = this.navigationGroups.find(group => group.type.id === this.selection?.typeId)?.type;
    return this.selection?.view === 'TYPE' ? `${type?.deviceCount || 0} 台设备 · 分类汇总` : `${type?.name || '设备'} · 单设备`;
  }

  select(view: WallboardSelection['view'], id?: string) {
    if (!this.dashboardId || !this.selection?.widgetId) return;
    const typeId = view === 'TYPE' ? id : view === 'DEVICE'
      ? this.navigationGroups.find(group => group.devices.some(device => device.id === id))?.type.id : undefined;
    if (typeId) this.expandedTypeId = typeId;
    this.paused = true;
    this.selection = {widgetId: this.selection.widgetId, view, page: 0,
      ...(typeId ? {typeId} : {}), ...(view === 'DEVICE' ? {deviceId: id} : {})};
    this.snapshot = null;
    this.nextPage = 0;
    this.load();
  }

  changePage(delta: number, manual = true) {
    if (!this.snapshot) return;
    if (manual) this.paused = true;
    this.selection.page = (this.snapshot.page + delta + this.snapshot.pageCount) % this.snapshot.pageCount;
    this.resetPageTimer();
    this.load();
  }

  toggleRotation() { this.paused = !this.paused; this.resetPageTimer(); }
  setTheme(theme: 'light' | 'dark') {
    this.themeOverride = theme;
    this.updateTheme();
    // A viewer preference never writes the dashboard or changes its native permissions.
    if (this.dashboardId && this.selection?.widgetId) {
      try { localStorage.setItem(this.themeStorageKey, this.themeOverride); } catch { /* Use the preference for this visit. */ }
    }
  }
  private updateTheme() {
    const theme = this.themeOverride || this.snapshot?.config.display.theme || this.ctx?.settings?.display?.theme || 'dark';
    this.isLight = theme === 'light' || (theme === 'auto' && this.systemTheme?.matches);
  }
  private resetPageTimer() {
    this.countdown = this.snapshot?.config.display.rotationSeconds || 20;
    this.nextPage = Date.now() + this.countdown * 1000;
  }
  get weekday(): string { return ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'][this.clock.getDay()]; }
  fullscreen() { this.viewport.nativeElement.requestFullscreen?.(); }
  number(value: number | null | undefined, decimals = 0): string {
    return value === null || value === undefined || !Number.isFinite(value) ? '—' : value.toLocaleString('zh-CN', {maximumFractionDigits: decimals});
  }
  compact(value: number | null | undefined): string {
    return value === null || value === undefined ? '—' : value >= 10000 ? this.number(value / 10000, 2) + '万' : this.number(value);
  }
  liveSpeed(device: WallboardDevice): number | null {
    return !this.disconnected && device.speedTs && this.clock.getTime() >= device.speedTs && this.clock.getTime() - device.speedTs <= device.staleAfterSeconds * 1000 ? device.speed : null;
  }
  barWidth(frame: WallboardFrame, value: number | null): number {
    const max = Math.max(1, ...frame.devices.map(d => d.totals.today || 0));
    return value === null ? 0 : Math.max(0, value / max * 100);
  }
  trend(frame: WallboardFrame): WallboardDay[] {
    return frame.trend || [];
  }
  shortDate(date: string): string { return date?.slice(5).replace('-', '/') || ''; }
  hasTodayData(frame: WallboardFrame): boolean { return frame.devices.some(device => device.totals.today !== null); }
  hasTrendData(frame: WallboardFrame): boolean { return this.trend(frame).some(point => point.quantity !== null); }
  hasSpeedData(): boolean { return this.snapshot?.speed.some(point => point.value?.trim() && Number.isFinite(Number(point.value)) && Number(point.value) >= 0); }
  speedMax(): number { return Math.max(0, ...(this.snapshot?.speed || []).map(point => Number(point.value)).filter(value => Number.isFinite(value) && value >= 0)); }
  singleSpeedPoint(): WallboardSnapshot['speed'][number] | undefined {
    const points = (this.snapshot?.speed || []).filter(point => point.value?.trim() && Number.isFinite(Number(point.value)) && Number(point.value) >= 0);
    return points.length === 1 ? points[0] : undefined;
  }
  speedX(ts: number): number { return 50 + Math.max(0, Math.min(1, (ts - (this.snapshot?.computedAsOf || Date.now()) + 3600000) / 3600000)) * 600; }
  trendMax(frame: WallboardFrame): number { return Math.max(0, ...this.trend(frame).map(p => p.quantity || 0)); }
  trendPath(frame: WallboardFrame): string {
    const points = this.trend(frame);
    const max = this.trendMax(frame) || 1;
    let previousKnown = false;
    return points.map((point, index) => {
      if (point.quantity === null) { previousKnown = false; return ''; }
      const prefix = previousKnown ? 'L' : 'M';
      previousKnown = true;
      return `${prefix}${50 + index * 600 / Math.max(1, points.length - 1)},${210 - point.quantity / max * 180}`;
    }).join(' ');
  }
  speedPath(): string {
    const points = this.snapshot?.speed || [];
    const max = this.speedMax() || 1;
    let previousTs: number | null = null;
    const stale = Math.max(120000, (this.snapshot?.frames[0]?.devices[0]?.staleAfterSeconds || 10) * 1000);
    return points.map(p => {
      const value = Number(p.value);
      if (!p.value?.trim() || !Number.isFinite(value) || value < 0) { previousTs = null; return ''; }
      const prefix = previousTs !== null && p.ts - previousTs <= stale ? 'L' : 'M';
      previousTs = p.ts;
      return `${prefix}${this.speedX(p.ts)},${210 - value / max * 180}`;
    }).join(' ');
  }
  ngOnDestroy() {
    this.destroyed = true;
    ++this.generation;
    this.request?.unsubscribe();
    clearInterval(this.clockTimer);
    this.observer?.disconnect();
    this.systemTheme?.removeEventListener('change', this.onSystemThemeChange);
  }
}
