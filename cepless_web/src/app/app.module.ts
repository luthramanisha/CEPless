import { BrowserModule } from '@angular/platform-browser';
import { NgModule } from '@angular/core';

import { AppComponent } from './app.component';
import { MonacoEditorModule, NgxMonacoEditorConfig  } from 'ngx-monaco-editor';
import { FormsModule } from '@angular/forms';
import { AppRoutingModule } from './app-routing.module';

import { HttpserviceService } from './httpservice.service';
import { HttpClientModule } from '@angular/common/http';
import { LocalJSONReaderService } from './localjsonreader.service';

import { CeplessPageComponent } from './cepless-page/cepless-page.component';
import { DemoPageComponent } from './demo-page/demo-page.component';
export function onMonacoLoad() {
  console.log((window as any).monaco);
  console.log(monaco.languages);
}

const monacoConfig: NgxMonacoEditorConfig = {
  baseUrl: 'assets',
  defaultOptions: { scrollBeyondLastLine: false },
  onMonacoLoad
};

@NgModule({
  declarations: [
    AppComponent,
    CeplessPageComponent,
    DemoPageComponent
  ],
  imports: [
    BrowserModule,
    MonacoEditorModule.forRoot(monacoConfig),
    FormsModule,
    HttpClientModule,
    AppRoutingModule
  ],
  providers: [HttpserviceService, LocalJSONReaderService],
  bootstrap: [AppComponent]
})
export class AppModule { }
