import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';

@Injectable({
  providedIn: 'root'
})
export class HttpserviceService {
  constructor(private http: HttpClient) {
  }
  private BASE_URL = 'http://localhost'; // node-manager host address
  private SUBMIT_OP = ':25003/submitOperator'; // submit operator path
  private REQUEST_OP = ':25003/requestOperator'; // request operator path
  private CHECK_OP = ':25003/buildOperator'; // build operator path
  private SUBMIT_FB = ':25003/submitFeedback'; // request operator path
  private options = { // options to send and receive json data
    headers: new HttpHeaders({'Content-Type':  'application/json'}),
    
  };

  checkOperator(operatorName, language, sourceCode){
    const body = {
      operatorName,
      language,
      sourceCode
    };
    console.log(JSON.stringify(body));
    return this.http.post(this.BASE_URL + this.CHECK_OP, JSON.stringify(body), this.options);
  }
  
  submitOperator(operatorName, language, sourceCode){
    const body = {
      operatorName,
      language,
      sourceCode
    };
    console.log(JSON.stringify(body));
    return this.http.post(this.BASE_URL + this.SUBMIT_OP, JSON.stringify(body), this.options);
  }

  submitFeedback(data) {
    let str = '';
    for (const value of data) {
      str += value + '\n';
    }
    const body = {
      qa: str,
      remarks: data[data.length - 1]
    };
    return this.http.post(this.BASE_URL + this.SUBMIT_FB, JSON.stringify(body), this.options);
  }

  checkBuild(operatorName, language, sourceCode){
    const body = {
      operatorName,
      language,
      sourceCode
    };
    console.log(JSON.stringify(body));
    return this.http.post(this.BASE_URL + this.CHECK_OP, JSON.stringify(body), this.options);
  }
}
