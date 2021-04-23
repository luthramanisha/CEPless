# How to run the web interface

This project was generated with [Angular CLI](https://github.com/angular/angular-cli) version 10.0.3.

Make sure that you have Node 10.13 or later installed. See instructions [here](https://nodejs.org/en/download/).

See install instructions from binaries [here](https://medium.com/@tgmarinho/how-to-install-node-js-via-binary-archive-on-linux-ab9bbe1dd0c2)

Install ng by `npm install -g @angular/cli ng`

## Install from package.json
npm install package.json

## Install Monaco Editor Component for Angular

npm install ngx-monaco-editor --save

## Run application

Run `ng serve --host 0.0.0.0 --port 8080`. Navigate to `http://localhost:8080/`

The `--host 0.0.0.0` part makes the web interface available via IP address.

# Structure of the web interface

The web interface is built upon the Angular framework. Therefore, the interface is divided into smaller components and modules, each responsible for a function.

The Angular framework utilizes the MVC concept. The Typescript files (.ts) play the **model** and **controller** roles. The HTML and CSS files plays the **view** role.

## Main app component
The `app.component.ts` and `app.module.ts` are the main classses, which are responsible for loading other components and services.

The templates `app.component.html` and `app.component.css` render the overall view of the whole page.

## Cepless page and demo page component
The `cepless-page.component.ts` and `demo-page.component.ts` are responsible for running javascript code on two pages: the demo page and the cepless overview page.

The templates `cepless-page.component.html`, `cepless-page.component.css`, `demo-page.component.html` and `demo-page.component.css` are used to render the view of each page.

## Services

Since we need to parse operator templates from JSON files and send HTTP requests, we implement the respective services.

The `httpservice.service.ts` and `localjsonreader.service.ts` are responsible for this part.

