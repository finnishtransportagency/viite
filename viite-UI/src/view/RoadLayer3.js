(function(root) {
  root.RoadLayer3 = function(map, roadCollection, styler, selectedLinkProperty) {
    var layerName = 'roadLayer';
    Layer.call(this, map, layerName, undefined, undefined);
    var me = this;
    var vectorLayer;
    var projectLinkStyler = new ProjectLinkStyler();
    var roadLinkStyler = new RoadLinkStyler();
    var linkStatus = LinkValues.LinkStatus;

    var vectorSource = new ol.source.Vector({
      loader: function(extent, resolution, projection) {
        var zoom = Math.log(1024/resolution) / Math.log(2);
        console.log("loader");
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
          console.log("load features ->");
          loadFeatures(features);
        });
        console.log("loader end");
      },
      strategy: ol.loadingstrategy.bbox
    });

    function vectorLayerStyle(feature) {
      return styler.generateStyleByFeature(feature.linkData, map.getView().getZoom());
    }

    var loadFeatures = function (features) {
      console.log("load features");

      vectorSource.clear(true);
      vectorSource.addFeatures(selectedLinkProperty.filterFeaturesAfterSimulation(features));
      eventbus.trigger('roadLayer:featuresLoaded', features); // For testing: tells that the layer is ready to be "clicked"
    };


    var infoContainer = document.getElementById('popup');
    var infoContent = document.getElementById('popup-content');

    var overlay = new ol.Overlay(({
      element: infoContainer
    }));

    applicationModel.debugInfo.set('overlay', overlay);
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
            console.log("display road address info");
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
      //console.log(overlay);
      overlay.setPosition(coordinate);
    };

    //Listen pointerMove and get pixel for displaying roadAddress feature info
    me.eventListener.listenTo(eventbus, 'overlay:update', function (event, pixel) {
      //console.log("update overlay");
      displayRoadAddressInfo(event, pixel);
    });

    var handleRoadsVisibility = function () {
      console.log(applicationModel.getRoadVisibility() && map.getView().getZoom() >= zoomlevels.minZoomForRoadLinks);
      vectorLayer.setVisible(applicationModel.getRoadVisibility() && map.getView().getZoom() >= zoomlevels.minZoomForRoadLinks);
    };

    this.mapMovedHandler = function (mapState) {
      //if ((applicationModel.getSelectedTool() === 'Cut' && selectSingleClick.getFeatures().getArray().length > 0))
        //return;
      if (mapState.zoom < zoomlevels.minZoomForRoadLinks) {
        vectorLayer.getSource().clear();
        eventbus.trigger('map:clearLayers');
      } else {
        eventbus.trigger(applicationModel.getSelectedLayer() + ':fetch');
        handleRoadsVisibility();
      }
    };

    this.eventListener.listenTo(eventbus, 'map:moved', me.mapMovedHandler, this);

    var clear = function(){
      vectorLayer.getSource().clear();
    };

    vectorLayer = new ol.layer.Vector({
      source: vectorSource,
      style: vectorLayerStyle
    });
    vectorLayer.setVisible(true);
    vectorLayer.set('name', 'roadLayer');

    return {
      layer: vectorLayer,
      clear: clear
    };
  };
})(this);
