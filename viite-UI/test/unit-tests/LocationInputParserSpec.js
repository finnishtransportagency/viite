define(['chai', 'LocationInputParser'], function (chai, LocationInputParser) {
    var expect = chai.expect;

    describe('Location input parser', function () {

        it('parses coordinate pairs', function () {
            expect(LocationInputParser.parse('123,345')).to.deep.equal({type: 'coordinate', lat: 123, lon: 345});
        });

        it('parses street addresses', function () {
            expect(LocationInputParser.parse('Salorankatu, Salo')).to.deep.equal({
                type: 'street',
                search: 'Salorankatu, Salo'
            });
            expect(LocationInputParser.parse('Salorankatu 7, Salo').type).to.equal('street');
            expect(LocationInputParser.parse('Salorankatu 7, Salo').type).to.equal('street');
            expect(LocationInputParser.parse('Peräkylä, Imatra').type).to.equal('street');
            expect(LocationInputParser.parse('Iso Roobertinkatu, Helsinki').type).to.equal('street');
            expect(LocationInputParser.parse('Kirkkokatu, Peräseinäjoki').type).to.equal('street');
            expect(LocationInputParser.parse('Kirkkokatu').type).to.equal('street');
            expect(LocationInputParser.parse('Kirkkokatu 2').type).to.equal('street');
        });

        it('parses road addresses', function () {
            expect(LocationInputParser.parse('52 1 100 0')).to.deep.equal({
                type: 'road', search: '52 1 100 0'
            });
            expect(LocationInputParser.parse('52 1 100').type).to.equal('road');
            expect(LocationInputParser.parse('52\t1 100').type).to.equal('road');
            expect(LocationInputParser.parse('52 1').type).to.equal('road');
            expect(LocationInputParser.parse('52').type).to.equal('road');
            expect(LocationInputParser.parse('52   1').type).to.equal('road');
        });

        it('returns validation error on unexpected input', function () {
            expect(LocationInputParser.parse('234, 345 NOT VALID')).to.deep.equal({type: 'invalid'});
            expect(LocationInputParser.parse('123.234')).to.deep.equal({type: 'invalid'});
            expect(LocationInputParser.parse('123.234,345.123')).to.deep.equal({type: 'invalid'});
            expect(LocationInputParser.parse('')).to.deep.equal({type: 'invalid'});
        });
    });
});
