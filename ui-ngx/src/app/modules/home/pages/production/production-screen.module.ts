// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { RouterModule } from '@angular/router';
import { SharedModule } from '@shared/shared.module';
import { ProductionBoardModule } from './production-board.module';
import { ProductionScreenComponent } from './production-screen.component';

@NgModule({
  declarations: [ProductionScreenComponent],
  imports: [SharedModule, ProductionBoardModule, RouterModule.forChild([{path: '', component: ProductionScreenComponent}])]
})
export class ProductionScreenModule {}
