(function (root) {
  root.RoadLayer = function (map, roadCollection, selectedLinkProperty, nodeCollection) {

    Layer.call(this, map);
    var me = this;
    var roadLinkStyler = new RoadLinkStyler();

    var roadVector = new ol.source.Vector({
      loader: function (extent, resolution, projection) {
        var zoom = Math.log(1024 / resolution) / Math.log(2);
        eventbus.once('roadLinks:fetched', function () {
          var features = _.map(roadCollection.getAll(), function (roadLink) {
            var points = _.map(roadLink.points, function (point) {
              return [point.x, point.y];
            });
            var feature = new ol.Feature({
              geometry: new ol.geom.LineString(points)
            });
            feature.linkData = roadLink;
            return feature;
          });
          loadFeatures(features);
        });
      },
      strategy: ol.loadingstrategy.bbox
    });

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
      eventbus.trigger('roadLayer:featuresLoaded', features); // For testing: tells that the layer is ready to be "clicked"
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
      //Ignore if target feature is marker
      if (!_.isUndefined(featureAtPixel) && !_.isUndefined(featureAtPixel.linkData)) {
        var roadData = featureAtPixel.linkData;
        if (infoContent !== null) {
          if (roadData !== null && (roadData.roadNumber !== 0 && roadData.roadPartNumber !== 0)) {
            coordinate = map.getEventCoordinate(event.originalEvent);
            infoContent.innerHTML = 'Tienumero:&nbsp;' + roadData.roadNumber + '<br>' +
              'Tieosanumero:&nbsp;' + roadData.roadPartNumber + '<br>' +
              'Ajorata:&nbsp;' + roadData.trackCode + '<br>' +
              'AET:&nbsp;' + roadData.startAddressM + '<br>' +
              'LET:&nbsp;' + roadData.endAddressM + '<br>' +
              'Tietyyppi:&nbsp;' + displayRoadType(roadData.roadTypeId) + '<br>';
          }
        }
      }
      overlay.setPosition(coordinate);
    };

    var displayRoadType = function (roadTypeCode) {
      var roadType;
      switch (roadTypeCode) {
        case LinkValues.RoadTypeShort.PublicRoad.value:
          roadType = LinkValues.RoadTypeShort.PublicRoad.description;
          break;
        case LinkValues.RoadTypeShort.FerryRoad.value:
          roadType = LinkValues.RoadTypeShort.FerryRoad.description;
          break;
        case LinkValues.RoadTypeShort.MunicipalityStreetRoad.value:
          roadType = LinkValues.RoadTypeShort.MunicipalityStreetRoad.description;
          break;
        case LinkValues.RoadTypeShort.PublicUnderConstructionRoad.value:
          roadType = LinkValues.RoadTypeShort.PublicUnderConstructionRoad.description;
          break;
        case LinkValues.RoadTypeShort.PrivateRoadType.value:
          roadType = LinkValues.RoadTypeShort.PrivateRoadType.description;
          break;
        case roadType = LinkValues.RoadTypeShort.UnknownOwnerRoad.value:
          roadType = LinkValues.RoadTypeShort.UnknownOwnerRoad.description;
          break;
      }
      return roadType;
    };

    var displayNodeInfo = function (event, pixel) {
      var featureAtPixel = map.forEachFeatureAtPixel(pixel, function (feature) {
        return feature;
      });
      var coordinate;
      if (!_.isUndefined(featureAtPixel) && !_.isUndefined(featureAtPixel.nodeInfo)) {
        var nodeData = featureAtPixel.nodeInfo;
        coordinate = map.getEventCoordinate(event.originalEvent);
        if (infoContent !== null) {
          infoContent.innerHTML =
            'Nimi:&nbsp;' + nodeData.name + '<br>' +
            'Solmutyyppi:&nbsp;' + displayNodeType(nodeData.type) + '<br>'
          ;
        }
        overlay.setPosition(coordinate);
      }

    };

    var displayNodeType = function (nodeTypeCode) {
      var nodeType = _.find(LinkValues.NodeType, function (type) {
        return type.value === nodeTypeCode;
      });
      return _.isUndefined(nodeType) ? NodeType.UnkownNodeType.description : nodeType.description;
    };

    var displayJunctionInfo = function (event, pixel) {
      var featureAtPixel = map.forEachFeatureAtPixel(pixel, function (feature) {
        return feature;
      });
      var coordinate;
      if (!_.isUndefined(featureAtPixel) && !_.isUndefined(featureAtPixel.junction) && !_.isUndefined(featureAtPixel.junction.junctionPoints)) {
        var junctionData = featureAtPixel.junction;
        var junctionPointData = featureAtPixel.junction.junctionPoints;
        var node = nodeCollection.getNodeById(junctionData.nodeId);
        coordinate = map.getEventCoordinate(event.originalEvent);
        var roadAddressInfo = [];
        _.map(junctionPointData, function(point){
          roadAddressInfo.push({road: point.road, part: point.part, track: point.track, addr: point.addrM, beforeAfter: point.beforeOrAfter});
        });

        var groupedRoadAddresses = _.groupBy(roadAddressInfo, function (row) {
          return [row.road, row.track, row.part, row.addr];
        });

        var roadAddresses = _.partition(groupedRoadAddresses, function (group) {
          return group.length > 1;
        });

        var doubleRows = _.map(roadAddresses[0], function (junctionPoints) {
          var first = _.first(junctionPoints); // TODO VIITE-2028 logic goes here, probably.
          return {road: first.road, track: first.track, part: first.part, addr: first.addr};
        });

        var singleRows = _.map(roadAddresses[1], function(junctionPoint) {
          return {road: junctionPoint[0].road, track: junctionPoint[0].track, part: junctionPoint[0].part, addr: junctionPoint[0].addr};
        });

        var roadAddressContent = _.sortBy(doubleRows.concat(singleRows), ['road', 'part', 'track', 'addr']);

        if (infoContent !== null) {
          infoContent.innerHTML =
            'Solmun&nbsp;nimi:&nbsp;' + (!_.isUndefined(node) ? node.name.replace(' ', '&nbsp;') : '') + '<br>' +
            'Tieosoite:<br>' +
            _.map(roadAddressContent, function (junctionPoint) {
              return '&thinsp;' + junctionPoint.road + '&nbsp;/&nbsp;' + junctionPoint.track + '&nbsp;/&nbsp;' + junctionPoint.part + '&nbsp;/&nbsp;' + junctionPoint.addr + '<br>';
            }).join('')
          ;
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
      //if ((applicationModel.getSelectedTool() === 'Cut' && selectSingleClick.getFeatures().getArray().length > 0))
      //return;
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
})(this);
