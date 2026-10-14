// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { ProductionService } from '@core/http/production.service';
import { ProductionScreenView } from '@shared/models/production.models';
import { catchError, combineLatest, exhaustMap, of, Subscription, timeout, timer } from 'rxjs';
import moment from 'moment-timezone';
import { ProductionScreenCache } from './production-screen-cache';

@Component({
  selector: 'tb-production-screen', templateUrl: './production-screen.component.html',
  styleUrls: ['./production.component.scss', './production-screen.component.scss'], standalone: false
})
export class ProductionScreenComponent implements OnInit, OnDestroy {
  @ViewChild('page', {static: true}) page: ElementRef<HTMLElement>;
  view: ProductionScreenView;
  clock = '';
  date = '';
  error = '';
  connectionLost = false;
  updatedTs: number;
  loading = true;
  unavailable = false;
  private cache = new ProductionScreenCache();
  private cacheKey: string;
  private routeVersion = 0;
  private subscriptions = new Subscription();
  private polling: Subscription;

  constructor(private route: ActivatedRoute, private production: ProductionService, private translate: TranslateService) {}

  ngOnInit() {
    this.subscriptions.add(timer(0, 1000).subscribe(() => {
      const zone = this.view?.overview.config.timezone || 'Asia/Shanghai';
      const now = moment.tz(zone);
      const language = this.translate.currentLang?.replace('_', '-') || document.documentElement.lang || 'zh-CN';
      const weekday = new Intl.DateTimeFormat(language, {weekday: 'long', timeZone: zone}).format(now.toDate());
      this.date = `${now.format('YYYY/MM/DD')} ${weekday}`;
      this.clock = now.format('HH:mm:ss');
    }));
    this.subscriptions.add(combineLatest([this.route.paramMap, this.route.fragment]).subscribe(async ([params, fragment]) => {
      const version = ++this.routeVersion;
      this.polling?.unsubscribe();
      this.view = null;
      this.updatedTs = null;
      this.error = '';
      this.loading = true;
      this.connectionLost = false;
      this.unavailable = false;
      this.cacheKey = null;
      const device = params.get('deviceId');
      const key = new URLSearchParams(fragment || '').get('key');
      if (!device || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(device) ||
        !/^[A-Za-z0-9_-]{43}$/.test(key || '')) {
        this.loading = false;
        this.unavailable = true;
        this.error = 'production.screen-unavailable';
        return;
      }
      const cacheKey = await this.cache.key(device, key);
      if (version !== this.routeVersion) { return; }
      this.cacheKey = cacheKey;
      const cached = this.cache.read(cacheKey);
      if (cached) {
        this.view = cached.view;
        this.updatedTs = cached.updatedTs;
        this.connectionLost = true;
        this.loading = false;
        this.error = 'production.screen-reconnecting';
      }
      this.polling = timer(0, 5000).pipe(exhaustMap(() => this.production.screen(device, key).pipe(
        timeout(35000),
        catchError(error => {
          this.loading = false;
          this.connectionLost = true;
          this.error = error.status === 404 ? 'production.screen-unavailable' : 'production.screen-reconnecting';
          if (error.status === 404) {
            this.polling?.unsubscribe();
            this.cache.clear(this.cacheKey);
            this.view = null;
            this.unavailable = true;
          }
          return of(null);
        })
      ))).subscribe(view => {
        if (!view) { return; }
        this.view = view.speedUnavailable && this.view?.overview.productionDate === view.overview.productionDate
          ? {...view, speed: this.view.speed} : view;
        this.updatedTs = Date.now();
        this.loading = false;
        this.connectionLost = false;
        this.error = '';
        this.cache.write(this.cacheKey, this.view, this.updatedTs);
      });
    }));
  }

  displayTime(value: number) {
    return moment.tz(value, this.view?.overview.config.timezone || 'Asia/Shanghai').format('YYYY-MM-DD HH:mm:ss');
  }

  get message() {
    if (this.error) { return this.error; }
    if (this.view?.speedUnavailable) { return 'production.screen-speed-unavailable'; }
    const overview = this.view?.overview;
    const latest = Math.max(overview?.lastSampleTs || 0, overview?.speedTs || 0);
    return latest && overview.now - latest > Math.max(10000, overview.config.reportIntervalSeconds * 3000)
      ? 'production.screen-device-stale' : '';
  }

  async fullscreen() {
    if (document.fullscreenElement) { await document.exitFullscreen(); }
    else { await this.page.nativeElement.requestFullscreen(); }
  }

  ngOnDestroy() { this.routeVersion++; this.polling?.unsubscribe(); this.subscriptions.unsubscribe(); }
}
