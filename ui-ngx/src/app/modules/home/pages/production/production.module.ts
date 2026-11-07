// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { SharedModule } from '@shared/shared.module';
import { Authority } from '@shared/models/authority.enum';
import { MenuId } from '@core/services/menu.models';
import { ProductionScreensComponent } from './production-screens.component';
import { ProductionComponent } from './production.component';
import { ProductionBoardModule } from './production-board.module';

@NgModule({
  declarations: [ProductionComponent, ProductionScreensComponent],
  imports: [CommonModule, SharedModule, ProductionBoardModule, RouterModule.forChild([{
    path: 'production', component: ProductionComponent,
    data: {auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER], title: 'production.title', breadcrumb: {menuId: MenuId.production}}
  }, {
    path: 'production-screens', component: ProductionScreensComponent,
    data: {auth: [Authority.TENANT_ADMIN, Authority.CUSTOMER_USER], title: 'production.screens-title', breadcrumb: {menuId: MenuId.production_screens}}
  }])]
})
export class ProductionModule {}
