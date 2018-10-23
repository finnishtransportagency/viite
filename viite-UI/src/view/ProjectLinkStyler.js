(function (root) {
  root.ProjectLinkStyler = function () {

    var LinkStatus = LinkValues.LinkStatus;
    var notHandledStatus = LinkStatus.NotHandled.value;
    var unchangedStatus = LinkStatus.Unchanged.value;
    var newRoadAddressStatus = LinkStatus.New.value;
    var transferredStatus = LinkStatus.Transfer.value;
    var numberingStatus = LinkStatus.Numbering.value;
    var terminatedStatus = LinkStatus.Terminated.value;
    var unknownStatus = LinkStatus.Undefined.value;

    var strokeWidthRules = [
      new StyleRule().where('zoomLevel').is(5).use({stroke: {width: 4 }}),
      new StyleRule().where('zoomLevel').is(6).use({stroke: {width: 4 }}),
      new StyleRule().where('zoomLevel').is(7).use({stroke: {width: 4 }}),
      new StyleRule().where('zoomLevel').is(8).use({stroke: {width: 5 }}),
      new StyleRule().where('zoomLevel').is(9).use({stroke: {width: 5 }}),
      new StyleRule().where('zoomLevel').is(10).use({stroke: {width: 5 }}),
      new StyleRule().where('zoomLevel').is(11).use({stroke: {width: 5 }}),
      new StyleRule().where('zoomLevel').is(12).use({stroke: {width: 8 }}),
      new StyleRule().where('zoomLevel').is(13).use({stroke: {width: 8 }}),
      new StyleRule().where('zoomLevel').is(14).use({stroke: {width: 10 }}),
      new StyleRule().where('zoomLevel').is(15).use({stroke: {width: 10 }})
    ];

    var fillRules = [
      new StyleRule().where('roadClass').is(1).use({stroke: {color: '#FF0000', lineCap: 'round'}}),
      new StyleRule().where('roadClass').is(2).use({stroke: {color: '#FF6600',  lineCap: 'round'}}),
      new StyleRule().where('roadClass').is(3).use({stroke: {color: '#FF9933',  lineCap: 'round'}}),
      new StyleRule().where('roadClass').is(4).use({stroke: {color: '#0011BB',  lineCap: 'round'}}),
      new StyleRule().where('roadClass').is(5).use({stroke: {color: '#33CCCC',  lineCap: 'round'}}),
      new StyleRule().where('roadClass').is(6).use({stroke: {color: '#E01DD9',  lineCap: 'round'}}),
      new StyleRule().where('roadClass').is(7).use({stroke: {color:'#fff', lineCap: 'butt', lineDash: [10, 10]}}),
      new StyleRule().where('roadClass').is(8).use({stroke: {color:'#fff', lineCap: 'butt', lineDash: [10, 10]}}),
      new StyleRule().where('roadClass').is(9).use({stroke: {color:'#fff', lineCap: 'butt', lineDash: [10, 10]}}),
      new StyleRule().where('roadClass').is(10).use({stroke: {color: '#FF55DD', lineCap: 'round'}}),
      new StyleRule().where('roadClass').is(11).use({stroke: {color: '#444444', lineCap: 'round'}}),
      new StyleRule().where('roadClass').is(97).use({stroke: {color: '#1E1E1E', lineCap: 'round'}}),
      new StyleRule().where('roadClass').is(98).use({stroke: {color: '#FAFAFA', lineCap: 'round'}}),
      new StyleRule().where('constructionType').is(LinkValues.ConstructionType.UnderConstruction.value).use({stroke: {color: '#ff9900', lineCap: 'round'}}),
      new StyleRule().where('gapTransfering').is(true).use({stroke: {color: '#00FF00', lineCap: 'round'}}),
      new StyleRule().where('roadClass').is(99).and('roadLinkSource').isNot(3).use({stroke: {color: '#A4A4A2', lineCap: 'round'}}),
      new StyleRule().where('roadClass').is(99).and('anomaly').is(1).use({stroke: {color: '#1E1E1E', lineCap: 'round'}}),
      new StyleRule().where('floating').is(1).use({stroke: {color: '#F7FE2E', lineCap: 'round'}}),
      new StyleRule().where('roadLinkSource').is(3).and('roadClass').is(99).use({stroke: {color: '#D3AFF6', lineCap: 'round'}})
    ];

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

    var projectLinkStyle = new StyleRuleProvider({zIndex: LinkValues.RoadZIndex.ReservedRoadLayer.value});
    projectLinkStyle.addRules(fillRules);
    projectLinkStyle.addRules(projectLinkRules);
    projectLinkStyle.addRules(strokeWidthRules);
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
