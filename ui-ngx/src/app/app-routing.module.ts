// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

const routes: Routes = [
  { path: 'production-screen/:deviceId',
    loadChildren: () => import('./modules/home/pages/production/production-screen.module').then(m => m.ProductionScreenModule),
    data: {title: 'production.screen-title', breadcrumb: {skip: true}}
  },
  { path: '',
    redirectTo: 'home',
    pathMatch: 'full',
    data: {
      breadcrumb: {
        skip: true
      }
    }
  }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
