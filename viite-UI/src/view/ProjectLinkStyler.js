(function (root) {
  root.ProjectLinkStyler = function () {

    var notHandledStatus = 0;
    var unchangedStatus = 1;
    var newRoadAddressStatus = 2;
    var transferredStatus = 3;
    var numberingStatus = 4;
    var terminatedStatus = 5;
    var unknownStatus = 99;

    var strokeByZomLevel = function (zoomLevel, style) {
      return new StyleRule().where('zoomLevel').is(zoomLevel).use(style);
    };

    var strokeWidthRule = _.partial(strokeByZomLevel);
    var strokeWidthRules = [
      strokeWidthRule(5, { stroke: {width: 5}}),
      strokeWidthRule(6, { stroke: {width: 5}}),
      strokeWidthRule(7, { stroke: {width: 6}}),
      strokeWidthRule(8, { stroke: {width: 6}}),
      strokeWidthRule(9, { stroke: {width: 6}}),
      strokeWidthRule(10, { stroke: {width: 7}}),
      strokeWidthRule(11, { stroke: {width: 7}}),
      strokeWidthRule(12, { stroke: {width: 9}}),
      strokeWidthRule(13, { stroke: {width: 12}}),
      strokeWidthRule(14, { stroke: {width: 16}}),
      strokeWidthRule(15, { stroke: {width: 16}})
    ];

    var projectLinkRules = [
      new StyleRule().where('status').is(notHandledStatus).use({stroke: {color: '#F7FE2E', width: 8, lineCap: 'round'}}),
      new StyleRule().where('status').is(unchangedStatus).use({stroke: {color: '#0000FF', width: 5, lineCap: 'round'}}),
      new StyleRule().where('status').is(newRoadAddressStatus).use({stroke: {color: '#FF55DD', width: 5, lineCap: 'round'}}),
      new StyleRule().where('status').is(transferredStatus).use({stroke: {color: '#FF0000', width: 3, lineCap: 'round'}}),
      new StyleRule().where('status').is(numberingStatus).use({stroke: {color: '#8B4513', width: 5, lineCap: 'round'}}),
      new StyleRule().where('status').is(terminatedStatus).use({stroke: {color: '#383836', width: 3, lineCap: 'round'}}),
      new StyleRule().where('status').is(unknownStatus).use({stroke: {color: '#383836', width: 3, lineCap: 'round'}}),
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
    projectLinkStyle.addRules(strokeWidthRules);
    projectLinkStyle.addRules(generalLinksRules);

    var selectionLinkStyle = new StyleRuleProvider({stroke: {lineCap: 'round', width: 8, color: '#00FF00'}});
    selectionLinkStyle.addRules(selectionStyleRules);
    selectionLinkStyle.addRules(strokeWidthRules);
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
