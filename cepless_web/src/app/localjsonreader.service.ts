import { Injectable } from '@angular/core';

import goTemplates from '../assets/operators/go.json';
import javaTemplates from '../assets/operators/java.json';
import cppTemplates from '../assets/operators/cpp.json';
import pythonTemplates from '../assets/operators/python.json';

import forms from '../assets/forms/forms.json';

@Injectable({
  providedIn: 'root'
})
export class LocalJSONReaderService {
  constructor() {
  }
  getTemplates(language: string) {
    switch (language) {
      case 'java':
        return javaTemplates;
      case 'python':
        return pythonTemplates;
      case 'cpp':
        return cppTemplates;
      case 'go':
        return goTemplates;
      default:
        return null;
    }
  }

  getFeedbackForms() {
    return forms;
  }
}
