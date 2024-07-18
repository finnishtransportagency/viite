var assert = require('assert');
var LocationInputParser = require('../../src/utils/LocationInputParser.js');

describe('Location input parser', function () {

  it('parses coordinate pairs', function () {
    assert.deepEqual(LocationInputParser.parse('123,345'), {type: 'coordinate', lat: 123, lon: 345});
  });

  it('parses linkId', function () {
    assert.equal(LocationInputParser.parse('9aafa85f-0afe-4cc2-9b63-f57090ec6bca:1').type, 'road');
  });

  it('parses street addresses', function () {
    assert.deepEqual(LocationInputParser.parse('Salorankatu, Salo'),{
      type: 'street',
      search: 'Salorankatu, Salo'
    });
    assert.equal(LocationInputParser.parse('Salorankatu 7, Salo').type, 'street');
    assert.equal(LocationInputParser.parse('Salorankatu 7, Salo').type,'street');
    assert.equal(LocationInputParser.parse('Peräkylä, Imatra').type,'street');
    assert.equal(LocationInputParser.parse('Iso Roobertinkatu, Helsinki').type,'street');
    assert.equal(LocationInputParser.parse('Kirkkokatu, Peräseinäjoki').type,'street');
    assert.equal(LocationInputParser.parse('Kirkkokatu').type,'street');
    assert.equal(LocationInputParser.parse('Kirkkokatu 2').type,'street');
  });

  it('parses road addresses', function () {
    assert.deepEqual(LocationInputParser.parse('52 1 100 0'),{
      type: 'road', search: '52 1 100 0'
    });
    assert.equal(LocationInputParser.parse('52 1 100').type,'road');
    assert.equal(LocationInputParser.parse('52\t1 100').type,'road');
    assert.equal(LocationInputParser.parse('52 1').type,'road');
    assert.equal(LocationInputParser.parse('52').type,'road');
    assert.equal(LocationInputParser.parse('52   1').type,'road');
  });

  it('returns validation error on unexpected input', function () {
    assert.deepEqual(LocationInputParser.parse('234, 345 NOT VALID'),{type: 'invalid'});
    assert.deepEqual(LocationInputParser.parse('123.234'),{type: 'invalid'});
    assert.deepEqual(LocationInputParser.parse('123.234,345.123'),{type: 'invalid'});
    assert.deepEqual(LocationInputParser.parse(''),{type: 'invalid'});
  });
});
