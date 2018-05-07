define(['chai', 'dateutil'], function(chai, dateutil) {
    var assert = chai.assert;

    describe('Date formatting', function() {

        it('ISO8601 to Finnish', function() {
            assert.equal("1.1.2018", dateutil.iso8601toFinnish("2018-01-01"));
            assert.equal("28.2.2018", dateutil.iso8601toFinnish("2018-02-28"));
            assert.equal("29.2.2000", dateutil.iso8601toFinnish("2000-02-29"));
        });

        it('Finnish to ISO8601', function() {
            assert.equal("2018-01-01", dateutil.finnishToIso8601("1.1.2018"));
            assert.equal("2018-01-01", dateutil.finnishToIso8601("01.1.2018"));
            assert.equal("2018-01-01", dateutil.finnishToIso8601("1.01.2018"));
            assert.equal("2018-01-01", dateutil.finnishToIso8601("01.01.2018"));
            assert.equal("2018-02-28", dateutil.finnishToIso8601("28.2.2018"));
            assert.equal("2000-02-29", dateutil.finnishToIso8601("29.2.2000"));
        });

    });

});
