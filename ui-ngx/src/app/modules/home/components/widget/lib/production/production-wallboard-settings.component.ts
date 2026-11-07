// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component } from '@angular/core';
import { FormArray, UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { EntityType } from '@shared/models/entity-type.models';
import { WidgetSettings, WidgetSettingsComponent } from '@shared/models/widget.models';
import { wallboardDefaults, WallboardConfig } from '@shared/models/production-wallboard.models';

@Component({selector: 'tb-production-wallboard-settings', standalone: false,
  templateUrl: './production-wallboard-settings.component.html'})
export class ProductionWallboardSettingsComponent extends WidgetSettingsComponent {
  form: UntypedFormGroup;
  readonly deviceType = EntityType.DEVICE;
  constructor(protected store: Store<AppState>, private fb: UntypedFormBuilder) { super(store); }
  protected settingsForm() { return this.form; }
  protected defaultSettings(): WidgetSettings { return structuredClone(wallboardDefaults); }
  get groups(): FormArray { return this.form.get('groupIds') as FormArray; }
  addGroup() { if (this.groups.length < 20) this.groups.push(this.fb.control(null, Validators.required)); }
  removeGroup(index: number) { this.groups.removeAt(index); }
  protected prepareInputSettings(settings: WallboardConfig): any {
    return {configVersion: 1, title: settings.title, mode: settings.source.mode, groupIds: settings.source.groupIds,
      deviceSelection: {entityType: EntityType.DEVICE, ids: settings.source.deviceIds}, typeAttribute: settings.typeAttribute,
      typeOrderText: settings.typeOrder.join('\n'), display: settings.display};
  }
  protected validatorTriggers(): string[] { return ['mode']; }
  protected updateValidators(emitEvent: boolean) {
    const active = this.form.get('mode').value === 'DEVICE_GROUPS';
    if (active && this.groups.disabled) this.groups.enable({emitEvent: false});
    else if (!active && this.groups.enabled) this.groups.disable({emitEvent: false});
    this.form.updateValueAndValidity({emitEvent});
  }
  protected doUpdateSettings(form: UntypedFormGroup, settings: any) {
    while (this.groups.length > settings.groupIds.length) this.groups.removeAt(this.groups.length - 1, {emitEvent: false});
    while (this.groups.length < settings.groupIds.length) this.groups.push(this.fb.control(null, Validators.required), {emitEvent: false});
    this.groups.reset(settings.groupIds, {emitEvent: false});
  }
  protected onSettingsSet(settings: any) {
    this.form = this.fb.group({
      configVersion: [1], title: [settings.title, [Validators.required, Validators.maxLength(100)]],
      mode: [settings.mode], groupIds: this.fb.array(settings.groupIds.map(id => this.fb.control(id, Validators.required))),
      deviceSelection: [settings.deviceSelection],
      typeAttribute: [settings.typeAttribute, [Validators.required, Validators.pattern('[A-Za-z][A-Za-z0-9_]{0,63}')]],
      typeOrderText: [settings.typeOrderText],
      display: this.fb.group({
        theme: [settings.display.theme],
        typesPerFrame: [settings.display.typesPerFrame, [Validators.required, Validators.min(1), Validators.max(3)]],
        devicesPerCard: [settings.display.devicesPerCard, [Validators.required, Validators.min(1), Validators.max(7)]],
        detailRowBudget: [settings.display.detailRowBudget, [Validators.required, Validators.min(6), Validators.max(16)]],
        rotationSeconds: [settings.display.rotationSeconds, [Validators.required, Validators.min(10), Validators.max(300)]],
        refreshSeconds: [settings.display.refreshSeconds, [Validators.required, Validators.min(5), Validators.max(60)]]
      })
    }, {validators: control => {
      const value = control.value;
      if (value.mode === 'DEVICE_GROUPS' && !value.groupIds?.length) return {scopeRequired: true};
      if (value.mode === 'EXPLICIT_DEVICES' && (!value.deviceSelection?.ids?.length || value.deviceSelection.ids.length > 300)) return {scopeRequired: true};
      const order = (value.typeOrderText || '').split('\n').filter((v: string) => v.trim());
      if (order.length > 100 || order.some((v: string) => v.trim().length > 64)) return {typeOrderInvalid: true};
      for (const key of ['typesPerFrame', 'devicesPerCard', 'detailRowBudget', 'rotationSeconds', 'refreshSeconds']) {
        if (!Number.isInteger(value.display[key])) return {integerRequired: true};
      }
      return null;
    }});
  }
  protected prepareOutputSettings(value: any): WallboardConfig {
    return {configVersion: 1, title: value.title, typeAttribute: value.typeAttribute,
      source: {mode: value.mode, groupIds: value.mode === 'DEVICE_GROUPS' ? value.groupIds : [],
        deviceIds: value.mode === 'EXPLICIT_DEVICES' ? value.deviceSelection?.ids || [] : []},
      typeOrder: (value.typeOrderText || '').split('\n').map((v: string) => v.trim()).filter(Boolean), display: value.display};
  }
}
