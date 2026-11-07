// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { DashboardService } from '@core/http/dashboard.service';
import { ProductionService } from '@core/http/production.service';
import { UserPermissionsService } from '@core/http/user-permissions.service';
import { Operation, Resource } from '@shared/models/security.models';
import { PageData } from '@shared/models/page/page-data';
import { newProductionDashboard, ProductionScreenEntry } from '@shared/models/production-wallboard.models';

@Component({selector: 'tb-production-screens', standalone: false,
  templateUrl: './production-screens.component.html', styleUrls: ['./production-screens.component.scss']})
export class ProductionScreensComponent implements OnInit, OnDestroy {
  result: PageData<ProductionScreenEntry>;
  search = '';
  page = 0;
  pageSize = 20;
  loading = false;
  creating = false;
  showCreate = false;
  newName = '';
  failed = false;
  private request: Subscription;
  private creation: Subscription;
  constructor(private production: ProductionService, private dashboards: DashboardService,
              private permissions: UserPermissionsService, private router: Router) {}
  get canCreate(): boolean { return this.permissions.hasGenericPermission(Resource.DASHBOARD, Operation.CREATE); }
  ngOnInit() { this.load(); }
  load(reset = false) {
    if (reset) this.page = 0;
    this.request?.unsubscribe();
    this.loading = true;
    this.failed = false;
    this.request = this.production.boards(this.pageSize, this.page, this.search.trim()).subscribe({
      next: result => {
        this.loading = false;
        this.result = result;
        if (this.page > 0 && !result.data.length && result.totalElements > 0) { this.page = 0; this.load(); }
      },
      error: () => { this.loading = false; this.failed = true; this.result = null; }
    });
  }
  create() {
    if (this.creating || !this.canCreate || !this.newName.trim() || this.newName.trim().length > 100) return;
    this.creating = true;
    const dashboard = newProductionDashboard();
    dashboard.title = this.newName.trim();
    Object.values(dashboard.configuration.widgets).forEach(widget => widget.config.settings.title = dashboard.title);
    this.creation = this.dashboards.saveDashboard(dashboard).subscribe({
      next: dashboard => { this.creating = false; this.router.navigate(['/dashboard', dashboard.id.id], {queryParams: {edit: true}}); },
      error: () => { this.creating = false; }
    });
  }
  edit(entry: ProductionScreenEntry) {
    if (!entry.canEdit) return;
    const path = entry.editGroupId ? ['/dashboards', 'groups', entry.editGroupId, entry.id] : ['/dashboard', entry.id];
    this.router.navigate(path, {queryParams: {edit: true}});
  }
  ngOnDestroy() { this.request?.unsubscribe(); this.creation?.unsubscribe(); }
}
