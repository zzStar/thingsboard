// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
export interface ProductionShift { name: string; start: string; }
export interface ProductionConfig { unit: string; timezone: string; reportIntervalSeconds: number; shifts: ProductionShift[]; }
export interface ProductionVersion { effectiveTs: number; config: ProductionConfig; }
export interface ProductionSettings { versions: ProductionVersion[]; current: ProductionConfig; now: number; }
export interface ProductionBackfill { startTs: number; endTs: number; cursorTs: number; processed: number; status: string; error: string | null; }
export interface ProductionScreenLink { key: string; createdTs: number; }
export interface ProductionScreenView {
  deviceName: string; overview: ProductionOverview; speed: Array<{ts: number; value: string}>; output: ProductionReport;
  customerName?: string | null;
  speedUnavailable?: boolean;
}
export interface ProductionRow {
  productionDate: string; shiftIndex: number; shiftName: string; unit: string; timezone: string;
  startTs: number; endTs: number; quantity: number; observedMs: number; estimatedMs: number;
  resets: number; partial: boolean; status: string;
}
export interface ProductionReport { startTs: number; endTs: number; dataAsOf: number; groupBy: string; rows: ProductionRow[]; }
export interface ProductionOverview {
  config: ProductionConfig; productionDate: string; dayStart: number; dayEnd: number;
  today: number; week: number; month: number; shifts: ProductionRow[];
  firstSampleTs: number | null; lastSampleTs: number | null; speed: number | null; speedTs: number | null;
  status: string; now: number;
}
