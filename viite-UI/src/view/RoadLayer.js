(function (root) {
  root.RoadLayer = function (map, roadCollection, selectedLinkProperty, nodeCollection) {

    Layer.call(this, map);
    var me = this;
    var roadLinkStyler = new RoadLinkStyler();

    var roadVector = new ol.source.Vector({
      loader: function (_extent, _resolution, _projection) {
        eventbus.once('roadLinks:fetched', function () {
          loadFeatures(getFeatures());
        });
        eventbus.once('roadLinks:fetched:wholeRoadPart', function () {
          loadFeatures(getFeatures());
          eventbus.trigger('roadCollection:wholeRoadPartFetched');
        });
      },
      strategy: ol.loadingstrategy.bbox
    });

    var getFeatures = function () {
      return _.map(roadCollection.getAll(), function (roadLink) {
        var points = _.map(roadLink.points, function (point) {
          return [point.x, point.y];
        });
        var feature = new ol.Feature({
          geometry: new ol.geom.LineString(points)
        });
        feature.linkData = roadLink;
        return feature;
      });
    };

    var roadLayer = new ol.layer.Vector({
      source: roadVector,
      style: vectorLayerStyle
    });
    roadLayer.setVisible(true);
    roadLayer.set('name', 'roadLayer');

    function vectorLayerStyle(feature) {
      return [roadLinkStyler.getBorderStyle().getStyle(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)}), roadLinkStyler.getRoadLinkStyle().getStyle(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)}),
        roadLinkStyler.getOverlayStyle().getStyle(feature.linkData, {zoomLevel: zoomlevels.getViewZoom(map)})];
    }

    var loadFeatures = function (features) {
      roadVector.clear(true);
      roadVector.addFeatures(selectedLinkProperty.filterFeaturesAfterSimulation(features));
    };


    var infoContainer = document.getElementById('popup');
    var infoContent = document.getElementById('popup-content');

    var overlay = new ol.Overlay(({
      element: infoContainer
    }));

    map.addOverlay(overlay);

    var displayRoadAddressInfo = function (event, pixel) {
      var featureAtPixel = map.forEachFeatureAtPixel(pixel, function (feature) {
        return feature;
      });
      var coordinate;
      const popupBox = document.getElementById('popup-content').getBoundingClientRect();
      // Prevent update when cursor is in the box
        if (!(event.originalEvent.clientX < popupBox.right &&
              event.originalEvent.clientX > popupBox.left &&
              event.originalEvent.clientY > popupBox.top &&
              event.originalEvent.clientY < popupBox.bottom))
      //Ignore if target feature is marker
      if (!_.isNil(featureAtPixel) && featureAtPixel.linkData) {
        const roadData = featureAtPixel.linkData;
        if (infoContent !== null) {
          if ((roadData.roadNumber !== 0 && roadData.roadPartNumber !== 0)) {
            coordinate = map.getEventCoordinate(event.originalEvent);
            infoContent.innerHTML =
                '<div class="popup-line-div"><div>Tienumero:&nbsp;</div><div class="selectable">'    + roadData.roadNumber + '</div></div>' +
                '<div class="popup-line-div"><div>Tieosanumero:&nbsp;</div><div class="selectable">' + roadData.roadPartNumber + '</div></div>' +
                '<div class="popup-line-div"><div>Ajorata:&nbsp;</div><div class="selectable">'      + roadData.trackCode + '</div></div>' +
                '<div class="popup-line-div"><div>AET:&nbsp;</div><div class="selectable">'          + roadData.startAddressM + '</div></div>' +
                '<div class="popup-line-div"><div>LET:&nbsp;</div><div class="selectable">'          + roadData.endAddressM + '</div></div>' +
                '<div class="popup-line-div"><div>Hall. luokka:&nbsp;</div><div class="selectable">' + displayAdministrativeClass(roadData.administrativeClassId) + '</div></div>';
            const altShiftPressed = event.originalEvent.shiftKey && event.originalEvent.altKey;
            if (altShiftPressed) {
                infoContent.innerHTML += '<hr>';
                if (!_.isUndefined(roadData.municipalityCode)) {
                    infoContent.innerHTML += '<div class="popup-line-div"><div>MunicipalityCode:&nbsp;</div><div class="selectable">' + roadData.municipalityCode + '</div></div>';
                }
                infoContent.innerHTML +=
                    '<div class="popup-line-div"><div>Link&nbsp;id:&nbsp;</div><div class="selectable">'           + roadData.linkId  + '</div></div>' +
                    '<div class="popup-line-div"><div>LinearLocation&nbsp;id:&nbsp;</div><div class="selectable">' + roadData.linearLocationId + '</div></div>' +
                    '<div class="popup-line-div"><div>Roadway&nbsp;id:&nbsp;</div><div class="selectable">'        + roadData.roadwayId + '</div></div>' +
                    '<div class="popup-line-div"><div>RoadwayNumber:&nbsp;</div><div class="selectable">'          + roadData.roadwayNumber + '</div></div>';
            }
          }
        }
      }
       // Keep info box open with altkey
      if (!(event.originalEvent.altKey))
        overlay.setPosition(coordinate);
    };

    var displayAdministrativeClass = function (administrativeClassCode) {
      var administrativeClass;
      switch (administrativeClassCode) {
        case ViiteEnumerations.AdministrativeClassShort.PublicRoad.value:
          administrativeClass = ViiteEnumerations.AdministrativeClassShort.PublicRoad.description;
          break;
        case ViiteEnumerations.AdministrativeClassShort.MunicipalityStreetRoad.value:
          administrativeClass = ViiteEnumerations.AdministrativeClassShort.MunicipalityStreetRoad.description;
          break;
        case ViiteEnumerations.AdministrativeClassShort.PrivateRoad.value:
          administrativeClass = ViiteEnumerations.AdministrativeClassShort.PrivateRoad.description;
          break;
        default:
          break;
      }
      return administrativeClass;
    };

    var displayNodeInfo = function (event, pixel) {
      var featureAtPixel = map.forEachFeatureAtPixel(pixel, function (feature) {
        return feature;
      });
      var coordinate;
      if (!_.isUndefined(featureAtPixel) && !_.isUndefined(featureAtPixel.node)) {
        coordinate = map.getEventCoordinate(event.originalEvent);
        if (infoContent !== null) {
          var nodeName = '';
          var name = featureAtPixel.getProperties().name;
          if (!_.isUndefined(name)) {
            nodeName = 'Nimi: ' + _.escape(name) + '<br>';
          }
          infoContent.innerHTML =
            nodeName +
            'Solmutyyppi: ' + displayNodeType(featureAtPixel.getProperties().type) + '<br>';
        }
        overlay.setPosition(coordinate);
      }

    };

    var displayNodeType = function (nodeTypeCode) {
      var nodeType = _.find(ViiteEnumerations.NodeType, function (type) {
        return type.value === nodeTypeCode;
      });
      return _.isUndefined(nodeType) ? ViiteEnumerations.NodeType.UnknownNodeType.description : nodeType.description;
    };

    var displayJunctionInfo = function (event, pixel) {
      var featureAtPixel = map.forEachFeatureAtPixel(pixel, function (feature) {
        return feature;
      });
      var coordinate;
      if (!_.isUndefined(featureAtPixel) && !_.isUndefined(featureAtPixel.junction) && !_.isUndefined(featureAtPixel.junction.junctionPoints)) {
        var junctionData = featureAtPixel.junction;
        var junctionPointData = featureAtPixel.junction.junctionPoints;
        var node = nodeCollection.getNodeByNodeNumber(junctionData.nodeNumber);
        coordinate = map.getEventCoordinate(event.originalEvent);
        var roadAddressInfo = [];
        _.map(junctionPointData, function (point) {
          roadAddressInfo.push({
            road: point.roadNumber,
            part: point.roadPartNumber,
            track: point.track,
            addr: point.addrM,
            beforeAfter: point.beforeAfter
          });
        });

        var groupedRoadAddresses = _.groupBy(roadAddressInfo, function (row) {
          return [row.road, row.track, row.part, row.addr];
        });

        var roadAddresses = _.partition(groupedRoadAddresses, function (group) {
          return group.length > 1;
        });

        var doubleRows = _.map(roadAddresses[0], function (junctionPoints) {
          var first = _.head(junctionPoints); // TODO VIITE-2028 logic goes here, probably.
          return {road: first.road, track: first.track, part: first.part, addr: first.addr};
        });

        var singleRows = _.map(roadAddresses[1], function (junctionPoint) {
          return {
            road: junctionPoint[0].road,
            track: junctionPoint[0].track,
            part: junctionPoint[0].part,
            addr: junctionPoint[0].addr
          };
        });

        var roadAddressContent = _.sortBy(doubleRows.concat(singleRows), ['road', 'part', 'track', 'addr']);

        if (infoContent !== null) {
          infoContent.innerHTML =
            'Solmun&nbsp;nimi:&nbsp;' + ((node) ? node.name.replace(' ', '&nbsp;') : '') + '<br>' +
            'Tieosoite:<br>' +
            _.map(roadAddressContent, function (junctionPoint) {
              return '&thinsp;' + junctionPoint.road + '&nbsp;/&nbsp;' + junctionPoint.track + '&nbsp;/&nbsp;' + junctionPoint.part + '&nbsp;/&nbsp;' + junctionPoint.addr + '<br>';
            }).join('');
        }
        overlay.setPosition(coordinate);
      }
    };

    //Listen pointerMove and get pixel for displaying roadAddress feature info
    me.eventListener.listenTo(eventbus, 'overlay:update', function (event, pixel) {
      displayRoadAddressInfo(event, pixel);
      displayNodeInfo(event, pixel);
      displayJunctionInfo(event, pixel);
    });

    var handleRoadsVisibility = function () {
      roadLayer.setVisible(applicationModel.getRoadVisibility() && zoomlevels.getViewZoom(map) >= zoomlevels.minZoomForRoadLinks);
    };

    this.refreshMap = function (mapState) {
      if (mapState.zoom < zoomlevels.minZoomForRoadLinks) {
        roadLayer.getSource().clear();
        eventbus.trigger('map:clearLayers');
        applicationModel.removeSpinner();
      } else {
        /*
         This could be implemented also with eventbus.trigger(applicationModel.getSelectedLayer() + ':fetch');
         but this implementation makes it easier to find the eventbus call when needed.
        */
        switch (applicationModel.getSelectedLayer()) {
          case 'linkProperty':
            eventbus.trigger('linkProperty:fetch');
            break;
          case 'roadAddressProject':
            eventbus.trigger('roadAddressProject:fetch');
            break;
          case 'node':
            eventbus.trigger('nodeLayer:fetch');
            break;
          default:
            break;
        }
        handleRoadsVisibility();
      }
    };

    this.eventListener.listenTo(eventbus, 'map:refresh', me.refreshMap, this);

    var clear = function () {
      roadLayer.getSource().clear();
    };

    return {
      layer: roadLayer,
      clear: clear
    };
  };
}(this));
