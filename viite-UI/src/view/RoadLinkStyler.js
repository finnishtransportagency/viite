(function (root) {
  root.RoadLinkStyler = function () {
    /**
     * RoadLinkstyler is a styler for road links in view mode. It is used for setting the color, borders and dashes of the road link based on the link data.
     * Each road link may have up to three different styles that are stacked on top of each other.
     * The three styles are:
     * Border: Links with Road number and Administrative class Municipality or Private. zIndex: lowest
     * Stroke: This is the "base color" for the link, for example this determines the base color for ramps and under construction links (road links that have "dashes"). zIndex: middle
     * Fill: This is the main color for the road link. zIndex: highest
     */

    const strokeWidthRules = [
      new StyleRule().where('zoomLevel').is(5).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(6).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(7).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(8).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(9).use({stroke: {width: 5}}),
      new StyleRule().where('zoomLevel').is(10).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(11).use({stroke: {width: 7}}),
      new StyleRule().where('zoomLevel').is(12).use({stroke: {width: 8}}),
      new StyleRule().where('zoomLevel').is(13).use({stroke: {width: 11}}),
      new StyleRule().where('zoomLevel').is(14).use({stroke: {width: 13}}),
      new StyleRule().where('zoomLevel').is(15).use({stroke: {width: 15}}),
      new StyleRule().where('zoomLevel').is(5).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(6).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(7).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(8).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 5}}),
      new StyleRule().where('zoomLevel').is(9).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(10).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(11).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 7}}),
      new StyleRule().where('zoomLevel').is(12).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 8}}),
      new StyleRule().where('zoomLevel').is(13).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 11}}),
      new StyleRule().where('zoomLevel').is(14).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 13}}),
      new StyleRule().where('zoomLevel').is(15).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 15}})
    ];

    const strokeWidthRulesForUnAddressed = [
      new StyleRule().where('zoomLevel').is(5).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 1}}),
      new StyleRule().where('zoomLevel').is(6).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 2}}),
      new StyleRule().where('zoomLevel').is(7).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(8).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(9).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 5}}),
      new StyleRule().where('zoomLevel').is(10).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(11).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(12).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 7}}),
      new StyleRule().where('zoomLevel').is(13).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 10}}),
      new StyleRule().where('zoomLevel').is(14).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 12}}),
      new StyleRule().where('zoomLevel').is(15).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({stroke: {width: 13}})
    ];


    const strokeAdministrativeClassRules = [
      new StyleRule().where('zoomLevel').is(5).and('administrativeClass').is('Municipality').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99).or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(6).and('administrativeClass').is('Municipality').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99).or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(7).and('administrativeClass').is('Municipality').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99).or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(8).and('administrativeClass').is('Municipality').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99).or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(9).and('administrativeClass').is('Municipality').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99).or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 5}}),
      new StyleRule().where('zoomLevel').is(10).and('administrativeClass').is('Municipality').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99).or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(11).and('administrativeClass').is('Municipality').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99).or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 7}}),
      new StyleRule().where('zoomLevel').is(12).and('administrativeClass').is('Municipality').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99).or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 8}}),
      new StyleRule().where('zoomLevel').is(13).and('administrativeClass').is('Municipality').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99).or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 11}}),
      new StyleRule().where('zoomLevel').is(14).and('administrativeClass').is('Municipality').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99).or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 12}}),
      new StyleRule().where('zoomLevel').is(15).and('administrativeClass').is('Municipality').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(99).or('administrativeClass').is('Private').and('roadClass').isNot(7).and('roadClass').isNot(8).and('roadClass').isNot(9).and('roadClass').isNot(10).and('roadClass').isNot(12).and('roadClass').isNot(99).use({stroke: {width: 13}})
    ];

    const fillWidthRules = [
      new StyleRule().where('zoomLevel').is(5).use({stroke: {width: 1}}),
      new StyleRule().where('zoomLevel').is(6).use({stroke: {width: 1}}),
      new StyleRule().where('zoomLevel').is(7).use({stroke: {width: 2}}),
      new StyleRule().where('zoomLevel').is(8).use({stroke: {width: 2}}),
      new StyleRule().where('zoomLevel').is(9).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(10).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(11).use({stroke: {width: 5}}),
      new StyleRule().where('zoomLevel').is(12).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(13).use({stroke: {width: 9}}),
      new StyleRule().where('zoomLevel').is(14).use({stroke: {width: 10}}),
      new StyleRule().where('zoomLevel').is(15).use({stroke: {width: 12}}),
      new StyleRule().where('zoomLevel').is(5).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(6).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').is(7).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(8).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(9).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 5}}),
      new StyleRule().where('zoomLevel').is(10).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 6}}),
      new StyleRule().where('zoomLevel').is(11).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 7}}),
      new StyleRule().where('zoomLevel').is(12).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 8}}),
      new StyleRule().where('zoomLevel').is(13).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 11}}),
      new StyleRule().where('zoomLevel').is(14).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 13}}),
      new StyleRule().where('zoomLevel').is(15).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).use({stroke: {width: 15}}),
      new StyleRule().where('zoomLevel').is(5).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 1}}),
      new StyleRule().where('zoomLevel').is(6).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 1}}),
      new StyleRule().where('zoomLevel').is(7).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 2}}),
      new StyleRule().where('zoomLevel').is(8).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 2}}),
      new StyleRule().where('zoomLevel').is(9).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 2}}),
      new StyleRule().where('zoomLevel').is(10).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 3}}),
      new StyleRule().where('zoomLevel').isIn([11, 12]).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 4}}),
      new StyleRule().where('zoomLevel').is(13).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 8}}),
      new StyleRule().where('zoomLevel').is(14).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 10}}),
      new StyleRule().where('zoomLevel').is(15).and('roadClass').isIn([7, 8, 9, 10, 12]).use({stroke: {width: 12}})
    ];

    const fillWidthRulesForUnAddressed = [
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

    const strokeRules = [
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.HighwayClass.value).use({
        stroke: {
          color: '#FF0000',
          opacity: 0.75,
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.Highway.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.MainRoadClass.value).use({
        stroke: {
          color: '#FF6600',
          opacity: 0.75,
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.MainRoad.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.RegionalClass.value).use({
        stroke: {
          color: '#FF9400',
          opacity: 0.75,
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.RegionalRoad.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.ConnectingClass.value).use({
        stroke: {
          color: '#0011BB',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.ConnectingRoad.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.MinorConnectingClass.value).use({
        stroke: {
          color: 'rgba(74, 255, 255, 0.75)',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.ConnectingRoad.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.StreetClass.value).use({
        stroke: {
          color: 'rgba(255, 42, 255, 0.75)',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.Street.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.RampsAndRoundAboutsClass.value).use({
        stroke: {
          color: '#00CCDD',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.Ramp.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.PedestrianAndBicyclesClass.value).use({
        stroke: {
          color: '#FC6DA0',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.PedestrianAndBicycleRoad.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.WinterRoadsClass.value).use({
        stroke: {
          color: '#FF55DD',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.PrivateRoad.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.PathsClass.value).use({
        stroke: {
          color: '#FF55DD',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.PrivateRoad.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.PrivateRoadClass.value).use({
        stroke: {
          color: '#FF55DD',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.PrivateRoad.stroke
      })
    ];

    const strokeRulesForUnAddressed = [
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('roadLinkSource').isNot(ViiteEnumerations.LinkGeomSource.SuravageLinkInterface.value).use({
        stroke: {
          color: 'rgba(238, 238, 235, 0.75)',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.UnAddressedOther.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('startAddressM').is(0).and('endAddressM').is(0).and('administrativeClassId').is(ViiteEnumerations.AdministrativeClass.PublicRoad.value).use({
        stroke: {
          color: '#646461',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.UnAddressedState.stroke
      }),
      new StyleRule().where('roadLinkSource').is(ViiteEnumerations.LinkGeomSource.SuravageLinkInterface.value).and('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).use({
        stroke: {
          color: 'rgba(238, 238, 235, 0.75)',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.UnAddressedOther.stroke
      })
    ];

    const strokeRulesForUnderConstruction = [
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('lifecycleStatus').is(ViiteEnumerations.lifecycleStatus.UnderConstruction.value).and('administrativeClassId').isNot(ViiteEnumerations.AdministrativeClass.PublicRoad.value).use({
        stroke: {
          color: 'rgba(238, 238, 235, 0.75)',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.UnAddressedOtherUnderConstruction.stroke
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('lifecycleStatus').is(ViiteEnumerations.lifecycleStatus.UnderConstruction.value).and('administrativeClassId').is(ViiteEnumerations.AdministrativeClass.PublicRoad.value).use({
        stroke: {
          color: '#646461',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.UnAddressedStateUnderConstruction.stroke
      })
    ];

    const fillRules = [
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.HighwayClass.value).use({
        stroke: {
          color: '#FF0000',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.Highway.fill
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.MainRoadClass.value).use({
        stroke: {
          color: '#FF6600',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.MainRoad.fill
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.RegionalClass.value).use({
        stroke: {
          color: '#FF9933',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.RegionalRoad.fill
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.ConnectingClass.value).use({
        stroke: {
          color: '#0011BB',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.ConnectingRoad.fill
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.MinorConnectingClass.value).use({
        stroke: {
          color: '#33CCCC',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.ConnectingRoad.fill
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.StreetClass.value).use({
        stroke: {
          color: '#E01DD9',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.Street.fill
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.RampsAndRoundAboutsClass.value).use({
        stroke: {
          color: '#fff',
          lineCap: 'butt',
          lineDash: [10, 10]
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.Ramp.fill
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.PedestrianAndBicyclesClass.value).use({
        stroke: {
          color: '#fff',
          lineCap: 'butt',
          lineDash: [10, 10]
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.PedestrianAndBicycleRoad.fill
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.WinterRoadsClass.value).use({
        stroke: {
          color: '#f3f3f2',
          lineCap: 'butt',
          lineDash: [10, 10]
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.PrivateRoad.fill
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.PathsClass.value).use({
        stroke: {
          color: '#f3f3f2',
          lineCap: 'butt',
          lineDash: [10, 10]
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.PrivateRoad.fill
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.PrivateRoadClass.value).use({
        stroke: {
          color: '#f3f3f2',
          lineCap: 'butt',
          lineDash: [10, 10]
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.PrivateRoad.fill
      })
    ];

    const fillRulesForUnAddressed = [
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('startAddressM').is(0).and('endAddressM').is(0).and('administrativeClassId').is(ViiteEnumerations.AdministrativeClass.PublicRoad.value).and('lifecycleStatus').isNot(ViiteEnumerations.lifecycleStatus.UnderConstruction.value).use({
        stroke: {
          color: '#646461',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.UnAddressedState.fill
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('roadLinkSource').is(ViiteEnumerations.LinkGeomSource.ComplementaryLinkInterface.value).use({
        stroke: {
          color: '#D3AFF6',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.UnAddressedOther.fill
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('roadLinkSource').isNot(ViiteEnumerations.LinkGeomSource.SuravageLinkInterface.value).and('lifecycleStatus').isNot(ViiteEnumerations.lifecycleStatus.UnderConstruction.value).use({
        stroke: {
          color: '#A4A4A2',
          lineCap: 'round'
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.UnAddressedOther.fill
      })
    ];

    const fillRulesForUnderConstruction = [
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('roadLinkSource').isNot(ViiteEnumerations.LinkGeomSource.SuravageLinkInterface.value).and('lifecycleStatus').is(ViiteEnumerations.lifecycleStatus.UnderConstruction.value).use({
        stroke: {
          color: '#000',
          lineCap: 'butt',
          lineDash: [10, 10]
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.UnAddressedOtherUnderConstruction.fill
      }),
      new StyleRule().where('roadClass').is(ViiteEnumerations.RoadClass.NoClass.value).and('startAddressM').is(0).and('endAddressM').is(0).and('administrativeClassId').is(ViiteEnumerations.AdministrativeClass.PublicRoad.value).and('lifecycleStatus').is(ViiteEnumerations.lifecycleStatus.UnderConstruction.value).use({
        stroke: {
          color: '#ff9900',
          lineCap: 'butt',
          lineDash: [10, 10]
        },
        zIndex: ViiteEnumerations.ViewModeZIndex.UnAddressedStateUnderConstruction.fill
      })
    ];

    const borderRules = [
      new StyleRule().where('zoomLevel').is(5).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).and('roadClass').isNot(ViiteEnumerations.RoadClass.NoClass.value).use({
        color: '#1E1E1E',
        lineCap: 'round',
        stroke: {width: 7}
      }),
      new StyleRule().where('zoomLevel').is(6).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).and('roadClass').isNot(ViiteEnumerations.RoadClass.NoClass.value).use({
        color: '#1E1E1E',
        lineCap: 'round',
        stroke: {width: 7}
      }),
      new StyleRule().where('zoomLevel').is(7).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).and('roadClass').isNot(ViiteEnumerations.RoadClass.NoClass.value).use({
        color: '#1E1E1E',
        lineCap: 'round',
        stroke: {width: 8}
      }),
      new StyleRule().where('zoomLevel').is(8).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).and('roadClass').isNot(ViiteEnumerations.RoadClass.NoClass.value).use({
        color: '#1E1E1E',
        lineCap: 'round',
        stroke: {width: 8}
      }),
      new StyleRule().where('zoomLevel').is(9).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).and('roadClass').isNot(ViiteEnumerations.RoadClass.NoClass.value).use({
        color: '#1E1E1E',
        lineCap: 'round',
        stroke: {width: 9}
      }),
      new StyleRule().where('zoomLevel').is(10).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).and('roadClass').isNot(ViiteEnumerations.RoadClass.NoClass.value).use({
        color: '#1E1E1E',
        lineCap: 'round',
        stroke: {width: 10}
      }),
      new StyleRule().where('zoomLevel').is(11).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).and('roadClass').isNot(ViiteEnumerations.RoadClass.NoClass.value).use({
        color: '#1E1E1E',
        lineCap: 'round',
        stroke: {width: 11}
      }),
      new StyleRule().where('zoomLevel').is(12).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).and('roadClass').isNot(ViiteEnumerations.RoadClass.NoClass.value).use({
        color: '#1E1E1E',
        lineCap: 'round',
        stroke: {width: 12}
      }),
      new StyleRule().where('zoomLevel').is(13).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).and('roadClass').isNot(ViiteEnumerations.RoadClass.NoClass.value).use({
        color: '#1E1E1E',
        lineCap: 'round',
        stroke: {width: 15}
      }),
      new StyleRule().where('zoomLevel').is(14).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).and('roadClass').isNot(ViiteEnumerations.RoadClass.NoClass.value).use({
        color: '#1E1E1E',
        lineCap: 'round',
        stroke: {width: 17}
      }),
      new StyleRule().where('zoomLevel').is(15).and('administrativeClassId').isIn(ViiteEnumerations.BlackUnderlineAdministrativeClasses).and('roadClass').isNot(ViiteEnumerations.RoadClass.NoClass.value).use({
        color: '#1E1E1E',
        lineCap: 'round',
        stroke: {width: 19}
      })
    ];

    const getRoadLinkStyles = function (linkData, map) {
      const strokeStyle = new StyleRuleProvider({});
      strokeStyle.addRules(strokeRules);
      strokeStyle.addRules(strokeWidthRules);
      strokeStyle.addRules(strokeAdministrativeClassRules);

      const fillStyle = new StyleRuleProvider({});
      fillStyle.addRules(fillRules);
      fillStyle.addRules(fillWidthRules);

      const borderStyle = new StyleRuleProvider({zIndex: ViiteEnumerations.RoadZIndex.VectorLayer.value});
      borderStyle.addRules(borderRules);

      return [strokeStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)}),
        fillStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)}),
        borderStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)})];
    };

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
      unAddressedStrokeStyle.addRules(strokeRulesForUnAddressed);
      unAddressedStrokeStyle.addRules(strokeWidthRulesForUnAddressed);

      const unAddressedFillStyle = new StyleRuleProvider({});
      unAddressedFillStyle.addRules(fillRulesForUnAddressed);
      unAddressedFillStyle.addRules(fillWidthRulesForUnAddressed);

      return [unAddressedStrokeStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)}),
        unAddressedFillStyle.getStyle(linkData, {zoomLevel: zoomlevels.getViewZoom(map)})];
    };

    return {
      getRoadLinkStyles: getRoadLinkStyles,
      getUnAddressedStyles: getUnAddressedStyles,
      getUnderConstructionStyles: getUnderConstructionStyles
    };
  };
}(this));
