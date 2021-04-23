import {Component, OnInit} from '@angular/core';
import {LocalJSONReaderService} from "../localjsonreader.service";

@Component({
  selector: 'app-cepless-page',
  templateUrl: './cepless-page.component.html',
  styleUrls: ['./cepless-page.component.css']
})
export class CeplessPageComponent implements OnInit {

  constructor(private jsonReader: LocalJSONReaderService) {
  }

  javaTemplates: Array<any>;

  ngOnInit(): void {
    this.javaTemplates = this.jsonReader.getTemplates('java')
  }

}
