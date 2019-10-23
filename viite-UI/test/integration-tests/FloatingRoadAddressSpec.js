/*jshint expr: true*/
define(['chai', 'eventbus', 'TestHelpers'], function (chai, eventbus, testHelpers) {
  console.log = function () {
  };
  var expect = chai.expect;

  var floatingsLinkIds = [1718152, 1718151];
  var unknownRoadLinkId = 500130202;

  describe('when loading application', function () {
    this.timeout(3000000);
    var openLayersMap;
    before(function (done) {
      var backend = testHelpers.fakeBackend(13, testHelpers.selectTestData('roadAddress'), 354810.0, 6676460.0, 'Project Two');

      testHelpers.restartApplication(function (map) {
        openLayersMap = map;
        testHelpers.clickVisibleEditModeButton();
        eventbus.once('roadLayer:featuresLoaded', function () {
          done();
        });
      }, backend);
    });

   /* describe('Selecting the first floating', function () {
      before(function (done) {
        eventbus.once('linkProperties:selected', function () {
          done();
        });
        var ol3Feature = testHelpers.getFeatureByLinkId(openLayersMap, testHelpers.getRoadLayerName(), floatingsLinkIds[0]);
        testHelpers.selectSingleFeatureByInteraction(openLayersMap, ol3Feature, testHelpers.getSingleClickNameLinkPropertyLayer());

      });

      it('check if the form opened for the correct floatings', function () {
        var formLinkIds = $('[id^=VALITUTLINKIT] p');
        expect(formLinkIds.length).to.equals(2);
        var firstLinkId = parseInt($('[id^=VALITUTLINKIT] p').eq(0).html());
        var secondLinkId = parseInt($('[id^=VALITUTLINKIT] p').eq(1).html());
        expect(floatingsLinkIds).to.include.members([firstLinkId, secondLinkId]);
        var isValintaButtonDisabled = $('.link-properties button.continue').is(":disabled");
        expect(isValintaButtonDisabled).to.be.false;
      });
    });

    describe('Clicking the \"Valinta\" button', function () {
      before(function (done) {
        eventbus.once('linkProperties:deactivateDoubleClick', function () {
          done();
        });
        testHelpers.clickValintaButton();
      });

      it('check that the \"Valinta\" was pressed and the unknowns are \"forward\"', function () {
        var isValintaButtonDisabled = $('.link-properties button.continue').is(":disabled");
        expect(isValintaButtonDisabled).to.be.true;
        var pickFeatures = testHelpers.getFeatures(openLayersMap, 'pickRoadsLayer');
        expect(pickFeatures).to.be.not.empty;
      });
    });

    describe('Selecting a unknown road to transfer the floatings', function () {
      before(function (done) {
        var ol3Feature = testHelpers.getFeatureByLinkId(openLayersMap, testHelpers.getPickRoadsLayerName(), unknownRoadLinkId);
        testHelpers.selectSingleFeatureByInteraction(openLayersMap, ol3Feature, testHelpers.getSingleClickNameLinkPropertyLayer());
        setTimeout(function () {
          done();
        }, 2000);
      });

      it('Check if the unknown road was selected via form', function () {
        var expectedLinkIds = [1718138, 1718147];
        var adjacentsButtons = $('[id^=sourceButton]');
        expect(adjacentsButtons.length).to.equals(2);
        var sourceALinkId = parseInt($('[id^=sourceButton]').eq(0).val());
        var sourceBLinkId = parseInt($('[id^=sourceButton]').eq(1).val());
        expect(expectedLinkIds).to.include.members([sourceALinkId, sourceBLinkId]);
        var isMoveButtonDisabled = $('.link-properties button.move').is(":disabled");
        expect(isMoveButtonDisabled).to.be.false;
      });

      it('Check if the unknown road was selected via Layers', function () {
        var unknownFeatureFromPickLayer = testHelpers.getFeatureByLinkId(openLayersMap, testHelpers.getPickRoadsLayerName(), unknownRoadLinkId);
        expect(unknownFeatureFromPickLayer).to.be.undefined;
        var unknownFeatureFromGreenLayer = testHelpers.getFeatureByLinkId(openLayersMap, testHelpers.getGreenRoadLayerName(), unknownRoadLinkId);
        expect(unknownFeatureFromGreenLayer).to.not.be.undefined;
        expect(unknownFeatureFromGreenLayer.linkData.linkId).to.be.equal(unknownRoadLinkId);
      });
    });

    describe('Click the Siirrä button to start the simulation', function () {
      before(function (done) {
        testHelpers.clickEnabledSiirraButton();
        //TODO remove this timeout from here find the right event for
        setTimeout(function () {
          done();
        }, 2000);
      });

      it('Confirm that the form changed', function () {
        expect($('[id^=afterCalculationInfo]:visible').length).to.equals(1);
        expect($('[id^=VALITUTLINKIT] p:visible').length).to.be.above(0);
        expect(floatingsLinkIds.concat(unknownRoadLinkId)).to.include.members([parseInt($('[id^=VALITUTLINKIT] p:visible').html())]);
        expect($('.link-properties button.calculate:disabled').length).to.equals(1);
        expect($('.link-properties button.save:enabled').length).to.equals(1);
      });

      it('Verify that the simulated road addresses are simulated', function () {
        var simulatedFeatures = testHelpers.getFeaturesLinkData(openLayersMap, testHelpers.getSimulatedRoadsLayerName());
        expect(simulatedFeatures.length).to.be.above(0);
        var featuresIds = _.chain(simulatedFeatures).map(function (sf) {
          return sf.id;
        }).uniq().value();
        expect(featuresIds.length).to.equals(1);
        expect(_.first(featuresIds)).to.equals(-1000);
      });
    });

    describe('Click the Tallenna button to save the simulated data', function () {
      before(function (done) {
        eventbus.once('linkProperties:closed', function () {
          done();
        });
        testHelpers.clickEnabledSaveButton();
      });

      it('Verify that the previous unknown link is now no longer unknown and there is only one feature', function () {
        var features = testHelpers.getFeaturesLinkData(openLayersMap, testHelpers.getRoadLayerName());
        var linkData = _.filter(features, function (rld) {
          return rld.linkId === unknownRoadLinkId;
        });
        expect(linkData.length).to.equals(1);
        expect(_.first(linkData).anomaly).to.equals(0);
        expect(_.first(linkData).id).to.not.equals(-1000);
        expect(_.first(linkData).roadLinkType).to.not.equals(-1);
      });
    });*/
  });
});
