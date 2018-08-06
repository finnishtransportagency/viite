(function(root) {
  root.RoadLayer3 = function(map, roadCollection, styler, selectedLinkProperty) {
    var vectorLayer;
    var layerMinContentZoomLevels = {};
    var currentZoom = 0;

    var vectorSource = new ol.source.Vector({
      loader: function(extent, resolution, projection) {
        var zoom = Math.log(1024/resolution) / Math.log(2);
        eventbus.once('roadLinks:fetched', function() {
          var features = _.map(roadCollection.getAll(), function(roadLink) {
            var points = _.map(roadLink.points, function(point) {
              return [point.x, point.y];
            });
            var feature =  new ol.Feature({ geometry: new ol.geom.LineString(points)
            });
              feature.linkData = roadLink;
            return feature;
          });
          loadFeatures(features);
        });
      },
      strategy: ol.loadingstrategy.bbox
    });

    function vectorLayerStyle(feature) {
        return styler.generateStyleByFeature(feature.linkData, currentZoom);
    }

    var loadFeatures = function (features) {
      console.log("load features");
      map.getLayers().forEach( function(layer) {
        if (layer.values_.name === "roadAddressProject"){
          layer.setVisible(false);
          //layer.getSource().clear(false);
        }
      });
      vectorSource.clear(true);
      vectorSource.addFeatures(selectedLinkProperty.filterFeaturesAfterSimulation(features));
      eventbus.trigger('roadLayer:featuresLoaded', features); // For testing: tells that the layer is ready to be "clicked"
    };

    var minimumContentZoomLevel = function() {
      if (!_.isUndefined(layerMinContentZoomLevels[applicationModel.getSelectedLayer()])) {
        return layerMinContentZoomLevels[applicationModel.getSelectedLayer()];
      }
      return zoomlevels.minZoomForRoadLinks;
    };

    var handleRoadsVisibility = function() {
      if (_.isObject(vectorLayer)) {
        console.log("set visible");
        console.log("get road visibility");
        vectorLayer.setVisible(applicationModel.getRoadVisibility() && map.getView().getZoom() >= minimumContentZoomLevel());
      }
    };

    var mapMovedHandler = function(mapState) {
      if (mapState.zoom !== currentZoom) {
        currentZoom = mapState.zoom;
      }
      console.log("Road layer 3")
      if (mapState.zoom < minimumContentZoomLevel()) {
        console.log("zoom < minZoom");
        vectorSource.clear();
        eventbus.trigger('map:clearLayers');
      } else if (mapState.selectedLayer === 'linkProperty'){
        console.log("selected layer is linkProperty");
        roadCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), currentZoom + 1);
        //loadFeatures(vectorSource.features);
        //eventbus.trigger('roadLinks:fetched');
        console.log("roadlinks fetched");
        handleRoadsVisibility();

      }
    };

    var clear = function(){
      vectorLayer.getSource().clear();
    };

    vectorLayer = new ol.layer.Vector({
      source: vectorSource,
      style: vectorLayerStyle
    });
    vectorLayer.setVisible(true);
    vectorLayer.set('name', 'roadLayer');
    map.addLayer(vectorLayer);

    eventbus.on('map:moved', mapMovedHandler, this);

    return {
      layer: vectorLayer,
      clear: clear
    };
  };
})(this);
