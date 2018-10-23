(function (root) {
  root.ProjectLinkStyler = function () {

    var notHandledStatus = 0;
    var unchangedStatus = 1;
    var newRoadAddressStatus = 2;
    var transferredStatus = 3;
    var numberingStatus = 4;
    var terminatedStatus = 5;
    var unknownStatus = 99;

    var projectLinkRules = [
      new StyleRule().where('status').is(notHandledStatus).use({stroke: {color: '#F7FE2E', lineCap: 'round'}}),
      new StyleRule().where('status').is(unchangedStatus).use({stroke: {color: '#0000FF', lineCap: 'round'}}),
      new StyleRule().where('status').is(newRoadAddressStatus).use({stroke: {color: '#FF55DD', lineCap: 'round'}}),
      new StyleRule().where('status').is(transferredStatus).use({stroke: {color: '#FF0000', lineCap: 'round'}}),
      new StyleRule().where('status').is(numberingStatus).use({stroke: {color: '#8B4513', lineCap: 'round'}}),
      new StyleRule().where('status').is(terminatedStatus).use({stroke: {color: '#383836', lineCap: 'round'}}),
      new StyleRule().where('status').is(unknownStatus).and('anomaly').is(1).use({stroke: {color: '#383836', lineCap: 'round'}}),
      new StyleRule().where('roadLinkSource').is(3).and('status').is(unknownStatus).use({stroke: {color: '#D3AFF6'}})
    ];

    var selectionStyleRules = [
      new StyleRule().where('status').is(terminatedStatus).and('connectedLinkId').isDefined().use({stroke: {color: '#C6C00F'}})
    ];

    var cutterStyleRules = [
      new StyleRule().where('type').is('cutter-crosshair').use({icon: {src: 'images/cursor-crosshair.svg'}})
    ];

    var generalLinksRules = [
      new StyleRule().where('floating').is(1).use({stroke: {color: '#F7FE2E', lineCap: 'round'}})
    ];

    var projectLinkStyle = new StyleRuleProvider({});
    projectLinkStyle.addRules(projectLinkRules);
    projectLinkStyle.addRules(generalLinksRules);

    var selectionLinkStyle = new StyleRuleProvider({stroke: {lineCap: 'round', width: 8, color: '#00FF00'}});
    selectionLinkStyle.addRules(selectionStyleRules);
    selectionLinkStyle.addRules(cutterStyleRules);

    var getProjectLinkStyle = function () {
      return projectLinkStyle;
    };

    var getSelectionLinkStyle = function () {
      return selectionLinkStyle;
    };

    return {
      getProjectLinkStyle: getProjectLinkStyle,
      getSelectionLinkStyle: getSelectionLinkStyle
    };
  };
})(this);
