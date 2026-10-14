// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { CustomDateAdapter } from '@shared/adapter/custom-datatime-adapter';
import moment from 'moment-timezone';

@Injectable()
export class ProductionDatetimeAdapter extends CustomDateAdapter {
  override parse(value: string | number): Date {
    if (typeof value === 'string' && /^\d{1,2}:\d{2}$/.test(value.trim())) {
      const time = moment(value.trim(), ['HH:mm', 'H:mm'], true);
      return time.isValid() && Number(value.trim().split(':')[0]) < 24 ? time.toDate() : new Date(NaN);
    }
    return super.parse(value);
  }
}
