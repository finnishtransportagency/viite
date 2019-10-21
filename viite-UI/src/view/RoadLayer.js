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
          var nodeName = "";
          if (!_.isUndefined(nodeData.name)) {
            nodeName = 'Nimi: ' + nodeData.name + '<br>';
          }
          infoContent.innerHTML =
            nodeName +
            'Solmutyyppi: ' + displayNodeType(nodeData.type) + '<br>'
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
      if (!_.isUndefined(featureAtPixel) && !_.isUndefined(featureAtPixel.junction) && !_.isUndefined(featureAtPixel.junctionPoint)) {
        var junctionData = featureAtPixel.junction;
        var junctionPointData = featureAtPixel.junctionPoint;
        var nodes = nodeCollection.getNodesWithAttributes();
        var node = _.find(nodes, function (node) {
          return node.id === junctionData.nodeId;
        });
        var roadLink = featureAtPixel.roadLink;
        coordinate = map.getEventCoordinate(event.originalEvent);
        if (infoContent !== null) {
          infoContent.innerHTML =
            'Tieosoite:&nbsp;' + roadLink.roadNumber + '/'+ roadLink.trackCode + '/' + roadLink.roadPartNumber + '/' + junctionPointData.addrM + '<br>' +
            'Solmun nimi:&nbsp;' + (!_.isUndefined(node) ? node.name : '') + '<br>'
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
