import { TestBed } from '@angular/core/testing';

import { LocalJSONReaderService } from './LocalJSONReader.service';

describe('LocalJSONReaderService', () => {
  let service: LocalJSONReaderService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(LocalJSONReaderService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
