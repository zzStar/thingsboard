// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { defaultHttpOptions } from './http-utils';
import {
  ProductionBackfill, ProductionConfig, ProductionOverview, ProductionReport, ProductionSettings,
  ProductionScreenLink, ProductionScreenView
} from '@shared/models/production.models';

@Injectable({providedIn: 'root'})
export class ProductionService {
  constructor(private http: HttpClient) {}

  screenLink(device: string) {
    return this.http.get<ProductionScreenLink | null>(`/api/production/${device}/screen-link`, defaultHttpOptions(true, true));
  }

  createScreenLink(device: string, rotate = false) {
    return this.http.post<ProductionScreenLink>(`/api/production/${device}/screen-link`, null,
      defaultHttpOptions(true, true, false, {rotate}));
  }

  revokeScreenLink(device: string) {
    return this.http.delete<void>(`/api/production/${device}/screen-link`, defaultHttpOptions(true, true));
  }

  screen(device: string, key: string) {
    const options = defaultHttpOptions(true, true);
    return this.http.get<ProductionScreenView>(`/api/noauth/production/${device}/screen`,
      {...options, headers: options.headers.set('X-Production-Screen-Key', key)});
  }

  settings(device: string) {
    return this.http.get<ProductionSettings>(`/api/production/${device}/settings`, defaultHttpOptions(true, true));
  }

  saveSettings(device: string, config: ProductionConfig, effectiveDate: string) {
    return this.http.post<ProductionSettings>(`/api/production/${device}/settings`, {config, effectiveDate}, defaultHttpOptions(true, true));
  }

  overview(device: string) {
    return this.http.get<ProductionOverview>(`/api/production/${device}/overview`, defaultHttpOptions(true, true));
  }

  backfill(device: string) {
    return this.http.get<ProductionBackfill>(`/api/production/${device}/backfill`, defaultHttpOptions(true, true));
  }

  startBackfill(device: string, startTs: number, endTs: number) {
    return this.http.post<ProductionBackfill>(`/api/production/${device}/backfill`, {startTs, endTs}, defaultHttpOptions(true, true));
  }

  report(device: string, startTs: number, endTs: number, groupBy: string, shift: number | null = null) {
    return this.http.get<ProductionReport>(`/api/production/${device}/report`,
      defaultHttpOptions(true, true, false, {startTs, endTs, groupBy, shift}));
  }

  speed(device: string, startTs: number, endTs: number) {
    return this.http.get<Array<{ts: number; value: number}>>(`/api/production/${device}/speed`,
      defaultHttpOptions(true, true, false, {startTs, endTs}));
  }
}
