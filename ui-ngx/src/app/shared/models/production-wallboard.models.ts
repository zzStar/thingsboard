// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { widgetType } from './widget.models';
import { Dashboard } from './dashboard.models';

export interface ProductionScreenEntry {id: string; title: string; createdTime: number; canEdit: boolean; editGroupId: string | null;}

export interface WallboardConfig {
  configVersion: number;
  title: string;
  source: {mode: 'ALL_VISIBLE' | 'DEVICE_GROUPS' | 'EXPLICIT_DEVICES'; groupIds: string[]; deviceIds: string[]};
  typeAttribute: string;
  typeOrder: string[];
  display: {theme: 'auto' | 'dark' | 'light'; typesPerFrame: number; devicesPerCard: number; detailRowBudget: number;
    rotationSeconds: number; refreshSeconds: number};
}
export const wallboardDefaults: WallboardConfig = {
  configVersion: 1, title: '产量大屏', source: {mode: 'ALL_VISIBLE', groupIds: [], deviceIds: []},
  typeAttribute: 'productionType', typeOrder: ['折页机', '印刷机', '装订机'],
  display: {theme: 'dark', typesPerFrame: 3, devicesPerCard: 7, detailRowBudget: 16, rotationSeconds: 20, refreshSeconds: 5}
};
export interface WallboardSelection {widgetId: string; view: 'ALL' | 'TYPE' | 'DEVICE'; typeId?: string; deviceId?: string; page: number;}
export interface WallboardTotals {today: number | null; week: number | null; month: number | null; averageSpeed: number | null; elapsedMs: number;}
export interface WallboardShift extends WallboardTotals {name: string; start: string; end: string;}
export interface WallboardDay {date: string; quantity: number | null;}
export interface WallboardDevice {
  id: string; name: string; typeId: string; unit: string; timezone: string; productionDate: string;
  totals: WallboardTotals; shifts: WallboardShift[]; trend: WallboardDay[];
  lastSampleTs: number | null; speed: number | null; speedTs: number | null; staleAfterSeconds: number;
}
export interface WallboardType {id: string; name: string; unit: string; timezone: string; deviceCount: number;
  noDataCount: number; totals: WallboardTotals;}
export interface WallboardFrame {type: WallboardType; devices: WallboardDevice[]; shiftTotals: WallboardShift[]; trend: WallboardDay[];
  devicePage: number; devicePages: number;}
export interface WallboardSnapshot {
  config: WallboardConfig; computedAsOf: number; dataAsOf: number | null; configRevision: string;
  effectiveScopeRevision: string; view: 'ALL' | 'TYPE' | 'DEVICE'; typeId: string | null; deviceId: string | null;
  page: number; pageCount: number; types: WallboardType[];
  deviceChoices: Array<{id: string; name: string; typeId: string}>;
  plan: Array<{types: Array<{typeId: string; devicePage: number}>}>;
  frames: WallboardFrame[]; speed: Array<{ts: number; value: string}>; speedUnavailable: boolean;
}
export const newProductionDashboard = (): Dashboard => {
  const id = crypto.randomUUID();
  return {
    title: wallboardDefaults.title,
    configuration: {
      entityAliases: {}, filters: {}, settings: {hideToolbar: true, showTitle: false},
      widgets: {[id]: {id, typeFullFqn: 'system.production_wallboard', type: widgetType.static, row: 0, col: 0, sizeX: 24, sizeY: 12,
        config: {showTitle: false, padding: '0px', backgroundColor: 'transparent', settings: structuredClone(wallboardDefaults)}}},
      states: {default: {name: '设备产量', root: true, layouts: {main: {
        gridSettings: {columns: 24, margin: 0, outerMargin: false, autoFillHeight: true, mobileAutoFillHeight: true},
        widgets: {[id]: {sizeX: 24, sizeY: 12, row: 0, col: 0}}
      }}}}
    }
  };
};
