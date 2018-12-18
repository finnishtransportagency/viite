(function (root) {
    root.RoadLinkStyler = function () {
        /**
         * RoadLinkstyler is styler for normal roadlinks in projectmode for setting them opacity. Does not include linedashes since we are not sure if those will be included in project mode
         */

        var strokeWidthRules = [
            new StyleRule().where('zoomLevel').is(5).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(6).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(7).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(8).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(9).use({stroke: {width: 5 }}),
            new StyleRule().where('zoomLevel').is(10).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(11).use({stroke: {width: 7 }}),
            new StyleRule().where('zoomLevel').is(12).use({stroke: {width: 8 }}),
            new StyleRule().where('zoomLevel').is(13).use({stroke: {width: 11 }}),
            new StyleRule().where('zoomLevel').is(14).use({stroke: {width: 13 }}),
            new StyleRule().where('zoomLevel').is(15).use({stroke: {width: 15 }}),
            new StyleRule().where('zoomLevel').is(5).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(6).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(7).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(8).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 5 }}),
            new StyleRule().where('zoomLevel').is(9).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(10).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 6 }}),
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
            new StyleRule().where('zoomLevel').is(10).and('roadClass').is(99).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(11).and('roadClass').is(99).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(12).and('roadClass').is(99).use({stroke: {width: 7 }}),
            new StyleRule().where('zoomLevel').is(13).and('roadClass').is(99).use({stroke: {width: 10 }}),
            new StyleRule().where('zoomLevel').is(14).and('roadClass').is(99).use({stroke: {width: 12 }}),
            new StyleRule().where('zoomLevel').is(15).and('roadClass').is(99).use({stroke: {width: 13 }})
        ];

        var strokeAdministrativeClassRules = [
            new StyleRule().where('zoomLevel').is(5).and('administrativeClass').is('Municipality').and('roadClass').isNot(7)
                .and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99)
                .or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9)
                .and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(6).and('administrativeClass').is('Municipality').and('roadClass').isNot(7)
                .and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99)
                .or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9)
                .and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(7).and('administrativeClass').is('Municipality').and('roadClass').isNot(7)
                .and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99)
                .or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9)
                .and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(8).and('administrativeClass').is('Municipality').and('roadClass').isNot(7)
                .and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99)
                .or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9)
                .and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(9).and('administrativeClass').is('Municipality').and('roadClass').isNot(7)
                .and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99)
                .or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9)
                .and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 5 }}),
            new StyleRule().where('zoomLevel').is(10).and('administrativeClass').is('Municipality').and('roadClass').isNot(7)
                .and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99)
                .or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9)
                .and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(11).and('administrativeClass').is('Municipality').and('roadClass').isNot(7)
                .and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99)
                .or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9)
                .and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 7 }}),
            new StyleRule().where('zoomLevel').is(12).and('administrativeClass').is('Municipality').and('roadClass').isNot(7)
                .and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99)
                .or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9)
                .and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 8 }}),
            new StyleRule().where('zoomLevel').is(13).and('administrativeClass').is('Municipality').and('roadClass').isNot(7)
                .and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99)
                .or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9)
                .and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 11 }}),
            new StyleRule().where('zoomLevel').is(14).and('administrativeClass').is('Municipality').and('roadClass').isNot(7)
                .and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99)
                .or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9)
                .and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 12 }}),
            new StyleRule().where('zoomLevel').is(15).and('administrativeClass').is('Municipality').and('roadClass').isNot(7)
                .and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99)
                .or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9)
                .and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 13 }})
        ];

        var fillWidthRules = [
            new StyleRule().where('zoomLevel').is(5).use({stroke: {width: 1 }}),
            new StyleRule().where('zoomLevel').is(6).use({stroke: {width: 1 }}),
            new StyleRule().where('zoomLevel').is(7).use({stroke: {width: 2 }}),
            new StyleRule().where('zoomLevel').is(8).use({stroke: {width: 2 }}),
            new StyleRule().where('zoomLevel').is(9).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(10).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(11).use({stroke: {width: 5 }}),
            new StyleRule().where('zoomLevel').is(12).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(13).use({stroke: {width: 9 }}),
            new StyleRule().where('zoomLevel').is(14).use({stroke: {width: 10 }}),
            new StyleRule().where('zoomLevel').is(15).use({stroke: {width: 12 }}),
            new StyleRule().where('zoomLevel').is(5).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(6).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(7).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(8).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(9).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 5 }}),
            new StyleRule().where('zoomLevel').is(10).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(11).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 7 }}),
            new StyleRule().where('zoomLevel').is(12).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 8 }}),
            new StyleRule().where('zoomLevel').is(13).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 11 }}),
            new StyleRule().where('zoomLevel').is(14).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 13 }}),
            new StyleRule().where('zoomLevel').is(15).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({stroke: {width: 15 }}),
            new StyleRule().where('zoomLevel').is(5).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 1 }}),
            new StyleRule().where('zoomLevel').is(6).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 1 }}),
            new StyleRule().where('zoomLevel').is(7).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 2 }}),
            new StyleRule().where('zoomLevel').is(8).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 2 }}),
            new StyleRule().where('zoomLevel').is(9).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 2 }}),
            new StyleRule().where('zoomLevel').is(10).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(11).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 4 }}),
            new StyleRule().where('zoomLevel').is(12).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 6 }}),
            new StyleRule().where('zoomLevel').is(13).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 8 }}),
            new StyleRule().where('zoomLevel').is(14).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 10 }}),
            new StyleRule().where('zoomLevel').is(15).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 12 }}),
            new StyleRule().where('zoomLevel').is(5).and('roadClass').is(99).use({stroke: {width: 1 }}),
            new StyleRule().where('zoomLevel').is(6).and('roadClass').is(99).use({stroke: {width: 1 }}),
            new StyleRule().where('zoomLevel').is(7).and('roadClass').is(99).use({stroke: {width: 1 }}),
            new StyleRule().where('zoomLevel').is(8).and('roadClass').is(99).use({stroke: {width: 1 }}),
            new StyleRule().where('zoomLevel').is(9).and('roadClass').is(99).use({stroke: {width: 2 }}),
            new StyleRule().where('zoomLevel').is(10).and('roadClass').is(99).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(11).and('roadClass').is(99).use({stroke: {width: 3 }}),
            new StyleRule().where('zoomLevel').is(12).and('roadClass').is(99).use({stroke: {width: 5 }}),
            new StyleRule().where('zoomLevel').is(13).and('roadClass').is(99).use({stroke: {width: 8 }}),
            new StyleRule().where('zoomLevel').is(14).and('roadClass').is(99).use({stroke: {width: 10 }}),
            new StyleRule().where('zoomLevel').is(15).and('roadClass').is(99).use({stroke: {width: 11 }})
        ];

        var strokeRules = [
            new StyleRule().where('roadClass').is(1).use({stroke: {color: 'rgba(255, 0, 0, 0.75)', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(2).use({stroke: {color: 'rgba(255, 148, 0, 0.75)',  lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(3).use({stroke: {color: 'rgba(255, 148, 0, 0.75)',  lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(4).use({stroke: {color: '#0011BB',  lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(5).use({stroke: {color: 'rgba(74, 255, 255, 0.75)',  lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(6).use({stroke: {color: 'rgba(255, 42, 255, 0.75)',  lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(7).use({stroke: {color: '#00CCDD',  lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(8).use({stroke: {color: '#FC6DA0', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(9).use({stroke: {color: '#FF55DD', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(10).use({stroke: {color: '#FF55DD', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(11).use({stroke: {color: '#444444', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(12).use({stroke: {color: '#FF55DD', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(97).use({stroke: {color: '#1E1E1E', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(98).use({stroke: {color: '#FAFAFA', lineCap: 'round'}}),
            new StyleRule().where('gapTransfering').is(true).use({stroke: {color: '#00FF00', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(99).and('roadLinkSource').isNot(3).use({stroke: {color: 'rgba(238, 238, 235, 0.75)', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(99).and('anomaly').is(1).use({stroke: {color: '#1E1E1E', lineCap: 'round'}}),
            new StyleRule().where('floating').is(1).use({stroke: {color: '#F7FE2E', lineCap: 'round'}}),
            new StyleRule().where('roadLinkSource').is(3).and('roadClass').is(99).use({stroke: {color: 'rgba(238, 238, 235, 0.75)', lineCap: 'round'}})
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
            new StyleRule().where('roadClass').is(9).use({stroke: {color:'#f3f3f2', lineCap: 'butt', lineDash: [10, 10]}}),
            new StyleRule().where('roadClass').is(10).use({stroke: {color: '#f3f3f2', lineCap: 'butt', lineDash: [10, 10]}}),
            new StyleRule().where('roadClass').is(11).use({stroke: {color: '#444444', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(12).use({stroke: {color:'#f3f3f2', lineCap: 'butt', lineDash: [10, 10]}}),
            new StyleRule().where('roadClass').is(97).use({stroke: {color: '#1E1E1E', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(98).use({stroke: {color: '#FAFAFA', lineCap: 'round'}}),
            new StyleRule().where('gapTransfering').is(true).use({stroke: {color: '#00FF00', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(99).and('roadLinkSource').isNot(3).and('constructionType').isNot(LinkValues.ConstructionType.UnderConstruction.value).use({stroke: {color: '#A4A4A2', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(99).and('roadLinkSource').isNot(3).and('constructionType').is(LinkValues.ConstructionType.UnderConstruction.value).use({stroke: {color:'#000', lineCap: 'butt', lineDash: [10, 10]}}),
            new StyleRule().where('roadClass').is(99).and('anomaly').is(1).and('constructionType').isNot(LinkValues.ConstructionType.UnderConstruction.value).use({stroke: {color: '#1E1E1E', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(99).and('anomaly').is(1).and('constructionType').is(LinkValues.ConstructionType.UnderConstruction.value).use({stroke: {color:'#ff9900', lineCap: 'butt', lineDash: [10, 10]}}),
            new StyleRule().where('floating').is(1).use({stroke: {color: '#F7FE2E', lineCap: 'round'}}),
            new StyleRule().where('roadClass').is(99).and('roadLinkSource').is(3).use({stroke: {color: '#D3AFF6', lineCap: 'round'}})
        ];

        var borderRules = [
            new StyleRule().where('zoomLevel').is(5).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 7 }}),
            new StyleRule().where('zoomLevel').is(6).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 7 }}),
            new StyleRule().where('zoomLevel').is(7).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 8 }}),
            new StyleRule().where('zoomLevel').is(8).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 8 }}),
            new StyleRule().where('zoomLevel').is(9).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 9 }}),
            new StyleRule().where('zoomLevel').is(10).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 10 }}),
            new StyleRule().where('zoomLevel').is(11).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 11 }}),
            new StyleRule().where('zoomLevel').is(12).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 12 }}),
            new StyleRule().where('zoomLevel').is(13).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 15 }}),
            new StyleRule().where('zoomLevel').is(14).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 17 }}),
            new StyleRule().where('zoomLevel').is(15).and('roadTypeId').isIn(LinkValues.BlackUnderlineRoadTypes).use({color: '#1E1E1E', lineCap: 'round', stroke: {width: 19 }})
        ];

        var roadLinkStyle = new StyleRuleProvider({});
        roadLinkStyle.addRules(strokeRules);
        roadLinkStyle.addRules(strokeWidthRules);
        roadLinkStyle.addRules(strokeAdministrativeClassRules);

        var overlayStyle = new StyleRuleProvider({zIndex: LinkValues.RoadZIndex.CalibrationPointLayer.value});
        overlayStyle.addRules(fillRules);
        overlayStyle.addRules(fillWidthRules);


        var borderStyle = new StyleRuleProvider({});
        borderStyle.addRules(borderRules);

        var getRoadLinkStyle = function () {
            return roadLinkStyle;
        };
        var getOverlayStyle = function () {
            return overlayStyle;
        };

        var getBorderStyle = function(){
            return borderStyle;
        };

        return {
            getOverlayStyle:getOverlayStyle,
            getRoadLinkStyle: getRoadLinkStyle,
            getBorderStyle: getBorderStyle
        };
    };
})(this);