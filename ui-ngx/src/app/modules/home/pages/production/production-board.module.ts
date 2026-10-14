// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SharedModule } from '@shared/shared.module';
import { ProductionBoardComponent } from './production-board.component';

@NgModule({declarations: [ProductionBoardComponent], imports: [CommonModule, SharedModule], exports: [ProductionBoardComponent]})
export class ProductionBoardModule {}
