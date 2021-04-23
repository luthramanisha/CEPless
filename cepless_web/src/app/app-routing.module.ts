import { NgModule }             from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { AppComponent } from './app.component';
import {CeplessPageComponent} from "./cepless-page/cepless-page.component";
import { DemoPageComponent } from './demo-page/demo-page.component';

const routes: Routes = [
  { path: '', redirectTo: 'demo', pathMatch: 'full' },
  { path: 'cepless', component: CeplessPageComponent },
  { path: 'demo', component: DemoPageComponent }
];

@NgModule({
  imports: [ RouterModule.forRoot(routes) ],
  exports: [ RouterModule ]
})
export class AppRoutingModule {}
