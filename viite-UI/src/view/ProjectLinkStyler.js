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
            new StyleRule().where('zoomLevel').isIn([5, 6]).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').isIn([7, 8]).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(9).use({stroke: {width: 5 }}),
            new StyleRule().where('zoomLevel').is(10).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(11).use({stroke: {width: 7 }}),
            new StyleRule().where('zoomLevel').is(12).use({stroke: {width: 8 }}),
            new StyleRule().where('zoomLevel').is(13).use({stroke: {width: 11 }}),
            new StyleRule().where('zoomLevel').is(14).use({stroke: {width: 13 }}),
            new StyleRule().where('zoomLevel').is(15).use({stroke: {width: 15 }}),
            new StyleRule().where('zoomLevel').isIn([5, 6]).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(7).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(8).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 5 }}),
            new StyleRule().where('zoomLevel').isIn([9, 10]).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(11).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 7 }}),
            new StyleRule().where('zoomLevel').is(12).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 8 }}),
            new StyleRule().where('zoomLevel').is(13).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 11 }}),
            new StyleRule().where('zoomLevel').is(14).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 13 }}),
            new StyleRule().where('zoomLevel').is(15).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 15 }}),
            new StyleRule().where('zoomLevel').is(5).and('roadClass').is(99).use({stroke: {width: 1 }}),
            new StyleRule().where('zoomLevel').is(6).and('roadClass').is(99).use({stroke: {width: 2 }}),
            new StyleRule().where('zoomLevel').is(7).and('roadClass').is(99).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(8).and('roadClass').is(99).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(9).and('roadClass').is(99).use({stroke: {width: 5 }}),
            new StyleRule().where('zoomLevel').isIn([10, 11]).and('roadClass').is(99).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(12).and('roadClass').is(99).use({stroke: {width: 7 }}),
            new StyleRule().where('zoomLevel').is(13).and('roadClass').is(99).use({stroke: {width: 10 }}),
            new StyleRule().where('zoomLevel').is(14).and('roadClass').is(99).use({stroke: {width: 12 }}),
            new StyleRule().where('zoomLevel').is(15).and('roadClass').is(99).use({stroke: {width: 13 }})
        ];

        var fillWidthRules = [
            new StyleRule().where('zoomLevel').isIn([5, 6]).use({stroke: {width: 1 }}),
            new StyleRule().where('zoomLevel').isIn([7, 8]).use({stroke: {width: 2 }}),
            new StyleRule().where('zoomLevel').is(9).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(10).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(11).use({stroke: {width: 5 }}),
            new StyleRule().where('zoomLevel').is(12).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(13).use({stroke: {width: 9 }}),
            new StyleRule().where('zoomLevel').is(14).use({stroke: {width: 10 }}),
            new StyleRule().where('zoomLevel').is(15).use({stroke: {width: 12 }}),
            new StyleRule().where('zoomLevel').isIn([5, 6]).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').isIn([7, 8]).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(9).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 5 }}),
            new StyleRule().where('zoomLevel').is(10).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(11).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 7 }}),
            new StyleRule().where('zoomLevel').is(12).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 8 }}),
            new StyleRule().where('zoomLevel').is(13).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 11 }}),
            new StyleRule().where('zoomLevel').is(14).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 13 }}),
            new StyleRule().where('zoomLevel').is(15).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 15 }}),
            new StyleRule().where('zoomLevel').isIn([5, 6]).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 1 }}),
            new StyleRule().where('zoomLevel').isIn([7, 8, 9]).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 2 }}),
            new StyleRule().where('zoomLevel').is(10).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(11).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(12).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(13).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 8 }}),
            new StyleRule().where('zoomLevel').is(14).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 10 }}),
            new StyleRule().where('zoomLevel').is(15).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 12 }}),
            new StyleRule().where('zoomLevel').isIn([5, 6, 7, 8]).and('roadClass').is(99).use({stroke: {width: 1 }}),
            new StyleRule().where('zoomLevel').is(9).and('roadClass').is(99).use({stroke: {width: 2 }}),
            new StyleRule().where('zoomLevel').isIn([10, 11]).and('roadClass').is(99).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(12).and('roadClass').is(99).use({stroke: {width: 5 }}),
            new StyleRule().where('zoomLevel').is(13).and('roadClass').is(99).use({stroke: {width: 8 }}),
            new StyleRule().where('zoomLevel').is(14).and('roadClass').is(99).use({stroke: {width: 10 }}),
            new StyleRule().where('zoomLevel').is(15).and('roadClass').is(99).use({stroke: {width: 11 }})
        ];

        var strokeRules = [
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.HighwayClass.value).use({stroke: {color: '#FF0000', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.MainRoadClass.value).use({stroke: {color: '#FFD76A',  lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.RegionalClass.value).use({stroke: {color: '#FFD76A',  lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.ConnectingClass.value).use({stroke: {color: '#0011BB',  lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.MinorConnectingClass.value).use({stroke: {color: '#33CCCC',  lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.StreetClass.value).use({stroke: {color: '#E01DD9',  lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.WinterRoadsClass.value).use({stroke: {color: '#FF55DD', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.PathsClass.value).use({stroke: {color: '#FF55DD', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(11).use({stroke: {color: '#444444', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(97).use({stroke: {color: '#1E1E1E', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(98).use({stroke: {color: '#FAFAFA', lineCap: 'round'}}),
            new StyleRule().where('gapTransfering').is(true).use({stroke: {color: '#00FF00', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.NoClass.value).and('roadLinkSource').isNot(LinkValues.LinkGeomSource.ComplementaryLinkInterface.value).use({stroke: {color: '#A4A4A2', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.NoClass.value).and('anomaly').is(LinkValues.Anomaly.NoAddressGiven.value).use({stroke: {color: '#1E1E1E', lineCap: 'round'}}),
            new StyleRule().where('floating').is(1).use({stroke: {color: '#F7FE2E', lineCap: 'round'}}),
            new StyleRule().where('roadLinkSource').is(LinkValues.LinkGeomSource.ComplementaryLinkInterface.value).and('roadClass').is(LinkValues.RoadClass.NoClass.value).use({stroke: {color: '#D3AFF6', lineCap: 'round'}}),
            new StyleRule().where('roadClass').isIn([LinkValues.RoadClass.WinterRoadsClass.value, LinkValues.RoadClass.PathsClass.value, LinkValues.RoadClass.PrivateRoadClass.value]).use({stroke: {color:'#f3f3f2', lineCap: 'butt', lineDash: [10, 10]}}),
            new StyleRule().where('roadClass').isIn([LinkValues.RoadClass.RampsAndRoundAboutsClass.value, LinkValues.RoadClass.PedestrianAndBicyclesClass.value]).use({stroke: {color:'#fff', lineCap: 'butt', lineDash: [10, 10]}}),
            new StyleRule().where('constructionType').is(LinkValues.ConstructionType.UnderConstruction.value).and('roadClass').is(LinkValues.RoadClass.NoClass.value).use({stroke: {color:'#000', lineCap: 'butt', lineDash: [10, 10]}}),
            new StyleRule().where('roadLinkSource').is(LinkValues.LinkGeomSource.ComplementaryLinkInterface.value).and('status').is(unknownStatus).use({stroke: {color: '#D3AFF6', opacity: 1}})

        ];

        var underConstructionRules = [
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.RampsAndRoundAboutsClass.value).use({stroke: {color: '#00CCDD',  lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.PedestrianAndBicyclesClass.value).use({stroke: {color: '#FC6DA0', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.PrivateRoadClass.value).use({stroke: {color: '#FF55DD', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.NoClass.value).and('anomaly').is(LinkValues.Anomaly.None.value).and('roadLinkSource').is(LinkValues.LinkGeomSource.NormalLinkInterface.value).and('constructionType').is(LinkValues.ConstructionType.UnderConstruction.value).use({stroke: {color: 'rgba(238, 238, 235, 0.75)', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(LinkValues.RoadClass.NoClass.value).and('roadLinkSource').is(LinkValues.LinkGeomSource.NormalLinkInterface.value).and('anomaly').is(LinkValues.Anomaly.NoAddressGiven.value).and('constructionType').is(LinkValues.ConstructionType.UnderConstruction.value).use({stroke: {color:'#ff9900', lineCap: 'round'}})
        ];

        var projectLinkRules = [
            new StyleRule().where('status').is(notHandledStatus).use({stroke: {color: '#F7FE2E', lineCap: 'round', opacity: 1}}),
            new StyleRule().where('status').is(unchangedStatus).use({stroke: {color: '#0000FF', lineCap: 'round', opacity: 1}}),
            new StyleRule().where('status').is(newRoadAddressStatus).use({stroke: {color: '#FF55DD', lineCap: 'round', opacity: 1}}),
            new StyleRule().where('status').is(transferredStatus).use({stroke: {color: '#FF0000', lineCap: 'round', opacity: 1}}),
            new StyleRule().where('status').is(numberingStatus).use({stroke: {color: '#8B4513', lineCap: 'round', opacity: 1}}),
            new StyleRule().where('status').is(terminatedStatus).use({stroke: {color: '#383836', lineCap: 'round', opacity: 1}}),
            new StyleRule().where('status').is(unknownStatus).and('anomaly').is(LinkValues.Anomaly.NoAddressGiven.value).and('constructionType').isNot(LinkValues.ConstructionType.UnderConstruction.value).use({stroke: {color: '#383836', lineCap: 'round', opacity: 1}}),
            ];

        var borderRules = [
            new StyleRule().where('zoomLevel').isIn([5, 6]).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 7 }}),
            new StyleRule().where('zoomLevel').isIn([7, 8]).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 8 }}),
            new StyleRule().where('zoomLevel').is(9).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 9 }}),
            new StyleRule().where('zoomLevel').is(10).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 10 }}),
            new StyleRule().where('zoomLevel').is(11).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 11 }}),
            new StyleRule().where('zoomLevel').is(12).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 12 }}),
            new StyleRule().where('zoomLevel').is(13).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 15 }}),
            new StyleRule().where('zoomLevel').is(14).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 17 }}),
            new StyleRule().where('zoomLevel').is(15).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 19 }})
        ];

        // Lower z-index to keep the black lines under the main ones
        var borderStyle = new StyleRuleProvider({zIndex: LinkValues.RoadZIndex.VectorLayer.value});
        borderStyle.addRules(borderRules);

        var selectionStyleRules = [
            new StyleRule().where('status').is(terminatedStatus).and('connectedLinkId').isDefined().use({stroke: {color: '#C6C00F'}})
        ];

        var cutterStyleRules = [
            new StyleRule().where('type').is('cutter-crosshair').use({icon: {src: 'images/cursor-crosshair.svg'}})
        ];

        var underConstructionStyle = new StyleRuleProvider({stroke: {opacity: 1}, zIndex: 2});
        underConstructionStyle.addRules(strokeWidthRules);
        underConstructionStyle.addRules(fillWidthRules);
        underConstructionStyle.addRules(underConstructionRules);

        var projectLinkStyle = new StyleRuleProvider({stroke: {opacity: 1}, zIndex: 3});
        projectLinkStyle.addRules(strokeWidthRules);
        projectLinkStyle.addRules(fillWidthRules);
        projectLinkStyle.addRules(strokeRules);
        projectLinkStyle.addRules(projectLinkRules);

        var selectionLinkStyle = new StyleRuleProvider({stroke: {lineCap: 'round', color: '#00FF00'}});
        selectionLinkStyle.addRules(strokeWidthRules);
        selectionLinkStyle.addRules(selectionStyleRules);
        selectionLinkStyle.addRules(cutterStyleRules);

        var getBorderStyle = function(){
            return borderStyle;
        };

        var getUnderConstructionStyle = function () {
            return underConstructionStyle;
        };

        var getProjectLinkStyle = function () {
            return projectLinkStyle;
        };

        var getSelectionLinkStyle = function () {
            return selectionLinkStyle;
        };

        var getUnderConstructionStyler = function (linkData, zoom) {
            return getUnderConstructionStyle().getStyle(linkData, zoom);
        };

        var getProjectLinkStyler = function (linkData, zoom) {
            return getProjectLinkStyle().getStyle(linkData, zoom);
        };

        return {
            getUnderConstructionStyler: getUnderConstructionStyler,
            getProjectLinkStyler: getProjectLinkStyler,
            getSelectionLinkStyle: getSelectionLinkStyle,
            getBorderStyle: getBorderStyle
        };
    };
})(this);
