(function(root) {
  root.RoadLayer3 = function(map, roadCollection, styler, selectedLinkProperty) {
    var vectorLayer;

    var vectorSource = new ol.source.Vector({
      loader: function(extent, resolution, projection) {
        var zoom = Math.log(1024/resolution) / Math.log(2);
        console.log("loader");
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
