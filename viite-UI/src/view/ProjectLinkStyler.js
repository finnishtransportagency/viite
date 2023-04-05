(function (root) {
  root.ProjectLinkStyler = function () {

    /**
     * ProjectLinkStyler is a styler for road links in project mode. It is used for setting the color, borders and dashes of the road link based on the link data.
     * Each road link may have up to three different styles that are stacked on top of each other.
     * The three styles are:
     * Border: Links with Road number and Administrative class Municipality or Private. zIndex: lowest
     * Stroke: This is the "base color" for the link, for example this determines the base color for under construction links (road links that have "dashes"). zIndex: middle
     * Fill: This is the main color for the road link. zIndex: highest
     */

    var LinkStatus = ViiteEnumerations.LinkStatus;
    var notHandledStatus = LinkStatus.NotHandled.value;
    var unchangedStatus = LinkStatus.Unchanged.value;
    var newRoadAddressStatus = LinkStatus.New.value;
    var transferredStatus = LinkStatus.Transfer.value;
    var numberingStatus = LinkStatus.Numbering.value;
    var terminatedStatus = LinkStatus.Terminated.value;

    var strokeWidthRules = [
      new StyleRule().where('zoomLevel').isIn([5, 6]).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').isIn([7, 8]).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(9).use({stroke: {width: 5}}),
      new StyleRule().where('zoomLevel').is(10).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(11).use({stroke: {width: 7}}),
      new StyleRule().where('zoomLevel').is(12).use({stroke: {width: 8}}),
      new StyleRule().where('zoomLevel').is(13).use({stroke: {width: 11}}),
      new StyleRule().where('zoomLevel').is(14).use({stroke: {width: 13}}),
      new StyleRule().where('zoomLevel').is(15).use({stroke: {width: 15}}),
      new StyleRule().where('zoomLevel').isIn([5, 6]).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(7).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(8).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 5}}),
      new StyleRule().where('zoomLevel').isIn([9, 10]).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(11).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 7}}),
      new StyleRule().where('zoomLevel').is(12).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 8}}),
      new StyleRule().where('zoomLevel').is(13).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 11}}),
      new StyleRule().where('zoomLevel').is(14).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 13}}),
      new StyleRule().where('zoomLevel').is(15).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 15}})
    ];

    const strokeWidthRulesForNotInProjectLinks = [
      new StyleRule().where('zoomLevel').is(5).use({stroke: {width: 1}}),
      new StyleRule().where('zoomLevel').is(6).use({stroke: {width: 2}}),
      new StyleRule().where('zoomLevel').is(7).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(8).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(9).use({stroke: {width: 5}}),
      new StyleRule().where('zoomLevel').isIn([10, 11]).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(12).use({stroke: {width: 7}}),
      new StyleRule().where('zoomLevel').is(13).use({stroke: {width: 10}}),
      new StyleRule().where('zoomLevel').is(14).use({stroke: {width: 12}}),
      new StyleRule().where('zoomLevel').is(15).use({stroke: {width: 13}})
    ];

    const strokeWidthRulesForUnAddressed = [
      new StyleRule().where('zoomLevel').is(5).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 1}}),
      new StyleRule().where('zoomLevel').is(6).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 1}}),
      new StyleRule().where('zoomLevel').is(7).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 1}}),
      new StyleRule().where('zoomLevel').is(8).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 1}}),
      new StyleRule().where('zoomLevel').is(9).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 2}}),
      new StyleRule().where('zoomLevel').is(10).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(11).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(12).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 5}}),
      new StyleRule().where('zoomLevel').is(13).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 8}}),
      new StyleRule().where('zoomLevel').is(14).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 10}}),
      new StyleRule().where('zoomLevel').is(15).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 11}})
    ];

    var fillWidthRules = [
      new StyleRule().where('zoomLevel').isIn([5, 6]).use({stroke: {width: 1}}),
      new StyleRule().where('zoomLevel').isIn([7, 8]).use({stroke: {width: 2}}),
      new StyleRule().where('zoomLevel').is(9).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(10).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(11).use({stroke: {width: 5}}),
      new StyleRule().where('zoomLevel').is(12).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(13).use({stroke: {width: 9}}),
      new StyleRule().where('zoomLevel').is(14).use({stroke: {width: 10}}),
      new StyleRule().where('zoomLevel').is(15).use({stroke: {width: 12}}),
      new StyleRule().where('zoomLevel').isIn([5, 6]).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').isIn([7, 8]).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(9).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 5}}),
      new StyleRule().where('zoomLevel').is(10).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(11).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 7}}),
      new StyleRule().where('zoomLevel').is(12).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 8}}),
      new StyleRule().where('zoomLevel').is(13).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 11}}),
      new StyleRule().where('zoomLevel').is(14).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 13}}),
      new StyleRule().where('zoomLevel').is(15).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 15}}),
      new StyleRule().where('zoomLevel').isIn([5, 6]).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 1}}),
      new StyleRule().where('zoomLevel').isIn([7, 8, 9]).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 2}}),
      new StyleRule().where('zoomLevel').is(10).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(11).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(12).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(13).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 8}}),
      new StyleRule().where('zoomLevel').is(14).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 10}}),
      new StyleRule().where('zoomLevel').is(15).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 12}})
    ];

    const fillWidthRulesForUnAddressed = [
      new StyleRule().where('zoomLevel').isIn([5, 6, 7, 8]).and('roadClass').is(99).use({stroke: {width: 1}}),
      new StyleRule().where('zoomLevel').is(9).and('roadClass').is(99).use({stroke: {width: 2}}),
      new StyleRule().where('zoomLevel').isIn([10, 11]).and('roadClass').is(99).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(12).and('roadClass').is(99).use({stroke: {width: 5}}),
      new StyleRule().where('zoomLevel').is(13).and('roadClass').is(99).use({stroke: {width: 8}}),
      new StyleRule().where('zoomLevel').is(14).and('roadClass').is(99).use({stroke: {width: 10}}),
      new StyleRule().where('zoomLevel').is(15).and('roadClass').is(99).use({stroke: {width: 11}})
    ];

    var strokeRulesForNotInProjectLinks = [
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.HighwayClass.value).use({
        stroke: {
          color: '#FF0000',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NotInProjectRoadLinks.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.MainRoadClass.value).use({
        stroke: {
          color: '#FF6600',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NotInProjectRoadLinks.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.RegionalClass.value).use({
        stroke: {
          color: '#FF9933',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NotInProjectRoadLinks.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.ConnectingClass.value).use({
        stroke: {
          color: '#0011BB',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NotInProjectRoadLinks.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.MinorConnectingClass.value).use({
        stroke: {
          color: '#33CCCC',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NotInProjectRoadLinks.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.StreetClass.value).use({
        stroke: {
          color: '#E01DD9',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NotInProjectRoadLinks.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.RampsAndRoundAboutsClass.value).use({
        stroke: {
          color: '#00CCDD',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NotInProjectRoadLinks.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.PedestrianAndBicyclesClass.value).use({
        stroke: {
          color: '#FC6DA0',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NotInProjectRoadLinks.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.PrivateRoadClass.value).use({
        stroke: {
          color: '#FF55DD',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NotInProjectRoadLinks.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.WinterRoadsClass.value).use({
        stroke: {
          color: '#FF55DD',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NotInProjectRoadLinks.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.PathsClass.value).use({
        stroke: {
          color: '#FF55DD',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NotInProjectRoadLinks.stroke
      })
    ];

    const strokeRulesForUnAddressed = [
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('roadLinkSource').isNot(ViiteEnumerations.LinkGeomSource.ComplementaryLinkInterface.value).use({
        stroke: {
          color: '#D1D1D0',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.UnAddressedOther.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('startAddressM').is(0).and('endAddressM').is(0).and('administrativeClassId').is(ViiteEnumerations.AdministrativeClass.PublicRoad.value).use({
        stroke: {
          color: '#1E1E1E',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.UnAddressedState.stroke
      })
    ];

    const strokeRulesForUnderConstruction = [
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('lifecycleStatus').is(ViiteEnumerations.lifecycleStatus.UnderConstruction.value).and('administrativeClassId').isNot(ViiteEnumerations.AdministrativeClass.PublicRoad.value).use({
        stroke: {
          color: '#B7B7B5',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.UnAddressedUnderConstructionOther.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('startAddressM').is(0).and('endAddressM').is(0).and('administrativeClassId').is(ViiteEnumerations.AdministrativeClass.PublicRoad.value).and('lifecycleStatus').is(ViiteEnumerations.lifecycleStatus.UnderConstruction.value).use({
        stroke: {
          color: '#ff9900',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.UnAddressedUnderConstructionState.stroke
      })
    ];

    var fillRulesForUnderConstruction = [
      new StyleRule().where('lifecycleStatus').is(ViiteEnumerations.lifecycleStatus.UnderConstruction.value).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({
        stroke: {
          color: '#000',
          lineCap: 'butt',
          lineDash: [10, 10],
          opacity: 1
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.UnAddressedUnderConstructionOther.fill
      }),
      new StyleRule().where('lifecycleStatus').is(ViiteEnumerations.lifecycleStatus.UnderConstruction.value).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('administrativeClassId').is(ViiteEnumerations.AdministrativeClass.PublicRoad.value).use({
        stroke: {
          color: '#000',
          lineCap: 'butt',
          lineDash: [10, 10],
          opacity: 1
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.UnAddressedUnderConstructionState.fill
      })
    ];

    const fillRulesForNotHandled = [
      new StyleRule().where('status').is(notHandledStatus).use({
        stroke: {
          color: '#F7FE2E',
          lineCap: 'round',
          opacity: 1
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NotHandledProjectLinks.fill
      })
    ];

    const fillRulesForTerminated = [
      new StyleRule().where('status').is(terminatedStatus).use({
        stroke: {
          color: '#383836',
          lineCap: 'round',
          opacity: 1
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.TerminatedProjectLinks.fill
      })
    ];

    const fillRulesForProjectLinks = [
      new StyleRule().where('status').is(unchangedStatus).use({
        stroke: {
          color: '#0000FF',
          lineCap: 'round',
          opacity: 1
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.UnchangedProjectLinks.fill
      }),
      new StyleRule().where('status').is(newRoadAddressStatus).use({
        stroke: {
          color: '#FF55DD',
          lineCap: 'round',
          opacity: 1
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NewProjectLinks.fill
      }),
      new StyleRule().where('status').is(transferredStatus).use({
        stroke: {
          color: '#FF0000',
          lineCap: 'round',
          opacity: 1
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.TransferredProjectLinks.fill
      }),
      new StyleRule().where('status').is(numberingStatus).use({
        stroke: {
          color: '#8B4513',
          lineCap: 'round',
          opacity: 1
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.NumberingProjectLinks.fill
      })
    ];

    var borderRules = [
      new StyleRule().where('zoomLevel').isIn([5, 6]).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 7}}),
      new StyleRule().where('zoomLevel').isIn([7, 8]).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 8}}),
      new StyleRule().where('zoomLevel').is(9).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 9}}),
      new StyleRule().where('zoomLevel').is(10).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 10}}),
      new StyleRule().where('zoomLevel').is(11).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 11}}),
      new StyleRule().where('zoomLevel').is(12).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 12}}),
      new StyleRule().where('zoomLevel').is(13).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 15}}),
      new StyleRule().where('zoomLevel').is(14).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 17}}),
      new StyleRule().where('zoomLevel').is(15).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 19}})
    ];

    const getUnderConstructionStyles = function (linkData, map) {
      const underConstructionStrokeStyle = new StyleRuleProvider({});
      underConstructionStrokeStyle.addRules(strokeRulesForUnderConstruction);
      underConstructionStrokeStyle.addRules(strokeWidthRulesForUnAddressed);

      const underConstructionFillStyle = new StyleRuleProvider({});
      underConstructionFillStyle.addRules(fillRulesForUnderConstruction);
      underConstructionFillStyle.addRules(fillWidthRulesForUnAddressed);

      return [underConstructionStrokeStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)}),
        underConstructionFillStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)})];
    };

    const getUnAddressedStyles = function (linkData, map) {
      const unAddressedStrokeStyle = new StyleRuleProvider({});
      unAddressedStrokeStyle.addRules(strokeWidthRulesForUnAddressed);
      unAddressedStrokeStyle.addRules(strokeRulesForUnAddressed);

      return [unAddressedStrokeStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)})];
    };

    const getProjectLinkStyles = function (linkData, map) {
      const projectLinkFillStyle = new StyleRuleProvider({});
      projectLinkFillStyle.addRules(fillWidthRules);
      projectLinkFillStyle.addRules(fillRulesForProjectLinks);

      const borderStyle = new StyleRuleProvider({zIndex: ViiteEnumerations.RoadZIndex.VectorLayer.value});
      borderStyle.addRules(borderRules);

      return [projectLinkFillStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)}),
        borderStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)})];
    };

    const getNotInProjectStyles = function (linkData, map) {
      const notInProjectStrokeStyle = new StyleRuleProvider({});
      notInProjectStrokeStyle.addRules(strokeRulesForNotInProjectLinks);
      notInProjectStrokeStyle.addRules(strokeWidthRulesForNotInProjectLinks);

      const borderStyle = new StyleRuleProvider({zIndex: ViiteEnumerations.RoadZIndex.VectorLayer.value});
      borderStyle.addRules(borderRules);

      return [notInProjectStrokeStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)}),
        borderStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)})];
    };

    var getSelectionLinkStyle = function (linkData, map) {
      var selectionLinkStyle = new StyleRuleProvider({
        stroke: {
          lineCap: 'round',
          color: '#00FF00'
        },
        zIndex: ViiteEnumerations.ProjectModeZIndex.SelectedProjectLink.value
      });
      selectionLinkStyle.addRules(strokeWidthRules);
      selectionLinkStyle.addRules(fillWidthRules);
      return [selectionLinkStyle.getStyle(linkData,{zoomLevel: zoomlevels.getViewZoom(map)})];
    };

    const getNotHandledProjectLinksStyle = function (linkData, map) {
      const notHandledProjectLinkFillStyle = new StyleRuleProvider({});
      notHandledProjectLinkFillStyle.addRules(fillRulesForNotHandled);
      notHandledProjectLinkFillStyle.addRules(fillWidthRules);

      return [notHandledProjectLinkFillStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)})];
    };

    const getTerminatedProjectLinksStyle = function (linkData, map) {
      const terminatedProjectLinkFillStyle = new StyleRuleProvider({});
      terminatedProjectLinkFillStyle.addRules(fillRulesForTerminated);
      terminatedProjectLinkFillStyle.addRules(fillWidthRules);

      return [terminatedProjectLinkFillStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)})];
    };

    return {
      getNotInProjectStyles: getNotInProjectStyles,
      getUnderConstructionStyles: getUnderConstructionStyles,
      getUnAddressedStyles: getUnAddressedStyles,
      getProjectLinkStyles: getProjectLinkStyles,
      getSelectionLinkStyle: getSelectionLinkStyle,
      getNotHandledProjectLinksStyle: getNotHandledProjectLinksStyle,
      getTerminatedProjectLinksStyle: getTerminatedProjectLinksStyle
    };
  };
}(this));
