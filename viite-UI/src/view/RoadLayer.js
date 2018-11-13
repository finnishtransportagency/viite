(function(root) {
  root.RoadLayer = function(map, roadCollection, selectedLinkProperty) {

    Layer.call(this, map);
    var me = this;
    var roadLinkStyler = new RoadLinkStyler();

    var roadVector = new ol.source.Vector({
      loader: function(extent, resolution, projection) {
        var zoom = Math.log(1024/resolution) / Math.log(2);
        eventbus.once('roadLinks:fetched', function() {
          var features = _.map(roadCollection.getAll(), function(roadLink) {
            var points = _.map(roadLink.points, function(point) {
              return [point.x, point.y];
            });
            var feature =  new ol.Feature({
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
      return [roadLinkStyler.getRoadLinkStyle().getStyle(feature.linkData, {zoomLevel:map.getView().getZoom()}),
          roadLinkStyler.getOverlayStyle().getStyle(feature.linkData, {zoomLevel:map.getView().getZoom()})];
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
        coordinate = map.getEventCoordinate(event.originalEvent);
        //TODO roadData !== null is there for test having no info ready (race condition where hover often loses) should be somehow resolved
        if (infoContent !== null) {
          if (roadData !== null || (roadData.roadNumber !== 0 && roadData.roadPartNumber !== 0 )) {
            infoContent.innerHTML = '<p>' +
              'Tienumero: ' + roadData.roadNumber + '<br>' +
              'Tieosanumero: ' + roadData.roadPartNumber + '<br>' +
              'Ajorata: ' + roadData.trackCode + '<br>' +
              'AET: ' + roadData.startAddressM + '<br>' +
              'LET: ' + roadData.endAddressM + '<br>' + '</p>';
          } else {
            infoContent.innerHTML = '<p>' +
              'Tuntematon tien segmentti' + '</p>';
          }
        }
      }
      overlay.setPosition(coordinate);
    };

    //Listen pointerMove and get pixel for displaying roadAddress feature info
    me.eventListener.listenTo(eventbus, 'overlay:update', function (event, pixel) {
      displayRoadAddressInfo(event, pixel);
    });

    var handleRoadsVisibility = function () {
      roadLayer.setVisible(applicationModel.getRoadVisibility() && map.getView().getZoom() >= zoomlevels.minZoomForRoadLinks);
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
        switch(applicationModel.getSelectedLayer()) {
          case 'linkProperty':
            eventbus.trigger('linkProperty:fetch');
            break;
          case 'roadAddressProject':
            eventbus.trigger('roadAddressProject:fetch');
        }
        handleRoadsVisibility();
      }
    };

    this.eventListener.listenTo(eventbus, 'map:refresh', me.refreshMap, this);

    var clear = function() {
      roadLayer.getSource().clear();
    };

    return {
      layer: roadLayer,
      clear: clear
    };
  };
})(this);
