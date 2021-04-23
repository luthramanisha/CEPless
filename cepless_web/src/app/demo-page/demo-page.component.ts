import {Component, OnInit} from '@angular/core';
import {HttpserviceService} from '../httpservice.service';
import {LocalJSONReaderService} from '../localjsonreader.service';
import { stringify } from 'querystring';

@Component({
  selector: 'app-editor-page',
  templateUrl: './demo-page.component.html',
  styleUrls: ['./demo-page.component.css']
})
export class DemoPageComponent implements OnInit {
  constructor(private http: HttpserviceService, private jsonReader: LocalJSONReaderService) {
  }

  language: string;
  editorOptions: any;
  code: string;
  languages = ['java', 'python', 'go', 'cpp'];
  operators: Array<any>;
  defaultOperators: Array<any>;
  consoleOptions: object;
  demoConsole: string;
  forms: Array<any>;
  feedbacks: Array<any>;
  currentOperator: any;
  canSubmit = false;

  private savedNo = [0, 0];

  ngOnInit(): void {
    this.language = this.languages[0];
    this.operators = this.jsonReader.getTemplates(this.language);
    this.defaultOperators = [];
    this.operators.forEach(val => this.defaultOperators.push(Object.assign({}, val)));
    for (let i = 0; i < sessionStorage.length; i++) {
      let name = sessionStorage.key(i);
      let obj = {
        name,
        code: JSON.parse(sessionStorage.getItem(name))
      };
      this.operators.push(obj);
    }
    this.code = this.operators[0].code;
    this.currentOperator = this.operators[0];
    this.editorOptions = {theme: "vs-dark", language: this.language, fontFamily: "Jetbrains Mono", fontSize: 13};
    this.demoConsole = '';
    this.consoleOptions = {theme: "vs-dark", language: "text/plain", fontFamily: "Jetbrains Mono", fontSize: 13};
    this.forms = this.jsonReader.getFeedbackForms();
    this.feedbacks = Array(this.forms.length + 1);
  }

  /**
   * triggered on change of language drop-down box
   */
  changeLanguage() {
    this.editorOptions = {language: this.language};
    this.operators = this.jsonReader.getTemplates(this.language);
    this.code = this.operators[0].code;
    this.currentOperator = this.operators[0];
  }

  /**
   * triggered on operator submitting
   */
  submitOperator() {
    this.demoConsole += "Submitting code syntax ... Please wait ... \n";
    const operatorName = this.language + 'compilation';
    this.demoConsole += 'Submitting operator\nThis operation can take a few seconds\n';
    this.http.submitOperator(operatorName, this.language, this.code).subscribe((response: any) => {
        console.log(response);
        this.demoConsole += response.message + '\n';
      },
      (error => {
        this.demoConsole += error + '\n';
      }));
    this.saveOperator(1);
  }

  // added by Matheus
  checkOperator() {
    const operatorName = this.language + 'compilation';
    this.demoConsole = 'Checking code syntax ... Please wait ... \n';
    this.http.checkOperator(operatorName, this.language, this.code).toPromise().then((response: any) => {
      console.log(response);
      this.demoConsole += response.message + '\n';
      this.canSubmit = true;
    },
    (error => {
      if (error.status == 400) {
        this.demoConsole += error.error.log + '\n';
      } else {
        console.log(error);
      }
    }));
  }

  /**
   * triggered on feedback submitting
   */
  submitFeedback() {
    for (let i = 0; i < this.feedbacks.length - 1; i++) {
      if (this.feedbacks[i] == null) {
        return;
      }
    }
    for (let i = 0; i < this.feedbacks.length - 1; i++) {
      this.feedbacks[i] = this.forms[i].question + '\n' + this.feedbacks[i];
    }
    if (this.feedbacks[this.feedbacks.length - 1] == null) {
      this.feedbacks[this.feedbacks.length - 1] = '';
    } else {
      this.feedbacks[this.feedbacks.length - 1] = 'Remarks: \n' + this.feedbacks[this.feedbacks.length - 1];
    }

    this.feedbackSubmitted();
    this.http.submitFeedback(this.feedbacks).subscribe((response: any) => {
      },
      (error => {
      }));
    this.feedbacks = Array(this.forms.length + 1);
  }

  /**
   * triggered on successful feedback submission
   */
  feedbackSubmitted() {
    const submissionNoti = document.getElementById('submission-noti');
    if (submissionNoti.firstChild) {
      submissionNoti.removeChild(submissionNoti.firstChild);
    } else {
      const notiNode = document.createElement('p');
      notiNode.innerHTML = 'Feedback submitted. Thanks for your help!';
      notiNode.style.color = 'green';
      submissionNoti.appendChild(notiNode);
    }

    // Uncheck radio buttons in feedback form
    const radioBtns = document.getElementsByTagName('input');
    for (let i = 0; i < radioBtns.length; i++) {
      if (radioBtns[i].type == 'radio') {
        radioBtns[i].checked = false;
      }
    }
  }

  /**
   *
   * @param kind
   */
  saveOperator(kind) {
    let n;
    let str;
    if (kind === 1) {
      this.savedNo[1] += 1;
      n = this.savedNo[1];
      str = 'Submitted Operator ';
    } else if (kind === 0) {
      this.savedNo[0] += 1;
      n = this.savedNo[0];
      str = 'Saved Operator ';
    } else {
      return;
    }
    while (JSON.stringify(this.operators).includes("\"name\":\"" + str + n + "\"")){
      n++;
    };
    const obj = {
      name: str + n,
      code: this.code
    };
    this.operators.push(obj);
    this.demoConsole += 'Code saved in operators list \n';
    sessionStorage.setItem(obj.name, JSON.stringify(obj.code));
    this.currentOperator = obj;
    this.code = this.currentOperator.code;
  }

  /**
   * triggered onClick Delete Operator
   */
  deleteOperator() {
    if (JSON.stringify(this.defaultOperators).includes(JSON.stringify(this.currentOperator))) {
      this.demoConsole += "Default operator \"" + this.currentOperator.name + "\" cannot be deleted.\n";
    } else {
      this.demoConsole += this.currentOperator.name + ' deleted\n';
      sessionStorage.removeItem(this.currentOperator.name);
      this.operators.splice(this.operators.indexOf(this.currentOperator), 1);
      this.currentOperator = this.operators[0];
      this.code = this.currentOperator.code;
    }
  }

  /**
   * trigger onClick Clear Console
   */
  clearConsole() {
    this.demoConsole = '';
  }

  /**
   * Collects chosen value of Radio button
   * @param index
   * @param value
   */
  changeRadio(index, value) {
    this.feedbacks[index] = value;
  }

  /**
   * Collects remarks
   * @param v
   */
  changeRemarks(v) {
    this.feedbacks[this.feedbacks.length - 1] = v.target.value;
  }

  changeOperator() {
    this.code = this.currentOperator.code;
  }
}

