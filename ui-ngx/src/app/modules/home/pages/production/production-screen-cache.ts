// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { ProductionScreenView } from '@shared/models/production.models';

interface ScreenSnapshot { version: number; updatedTs: number; view: ProductionScreenView; }

export class ProductionScreenCache {
  constructor(private storage = () => localStorage) {}

  async key(device: string, accessKey: string): Promise<string | null> {
    try {
      const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(accessKey));
      const hash = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
      return `production-screen:snapshot:${device}:${hash}`;
    } catch { return null; }
  }

  read(key: string | null): ScreenSnapshot | null {
    if (!key) { return null; }
    try {
      const raw = this.storage().getItem(key);
      if (!raw || raw.length > 512_000) { return null; }
      const snapshot = JSON.parse(raw) as ScreenSnapshot;
      const view = snapshot?.view;
      const config = view?.overview?.config;
      return snapshot.version === 1 && Number.isFinite(snapshot.updatedTs) && snapshot.updatedTs > 0 &&
        typeof view?.deviceName === 'string' && typeof config?.unit === 'string' && typeof config?.timezone === 'string' &&
        Number.isFinite(config?.reportIntervalSeconds) && Number.isFinite(view?.overview?.today) &&
        Number.isFinite(view?.overview?.week) && Number.isFinite(view?.overview?.month) &&
        Number.isFinite(view?.overview?.now) && Array.isArray(config?.shifts) && Array.isArray(view?.overview?.shifts) &&
        Array.isArray(view?.speed) && Array.isArray(view?.output?.rows) ? snapshot : null;
    } catch { return null; }
  }

  write(key: string | null, view: ProductionScreenView, updatedTs: number) {
    if (!key) { return; }
    try {
      const storage = this.storage();
      storage.setItem(key, JSON.stringify({version: 1, updatedTs, view}));
      const devicePrefix = key.slice(0, key.lastIndexOf(':') + 1);
      for (let index = storage.length - 1; index >= 0; index--) {
        const previous = storage.key(index);
        if (previous !== key && previous?.startsWith(devicePrefix)) { storage.removeItem(previous); }
      }
    } catch { /* Storage may be unavailable in an iframe; keep the in-memory snapshot. */ }
  }

  clear(key: string | null) {
    if (!key) { return; }
    try { this.storage().removeItem(key); } catch { /* Keep rendering independent of storage access. */ }
  }
}
