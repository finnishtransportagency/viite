(function(root) {
  root.Layer = function(map) {
    var me = this;
    this.eventListener = _.extend({running: false}, eventbus);

    var mapOverLinkMiddlePoints = function(links, transformation) {
      return _.map(links, function(link) {
          var geometry = (_.isUndefined(link.newGeometry) ? link.points : link.newGeometry);
          var points = _.map(geometry, function(point) {
            return [point.x, point.y];
        });
        var lineString = new ol.geom.LineString(points);
        var middlePoint = GeometryUtils.calculateMidpointOfLineString(lineString);
        return transformation(link, middlePoint);
      });
    };

    this.addLayers = function(layers) {
      _.each(layers, function(layer) {
        map.addLayer(layer);
      });
    };

    this.toggleLayersVisibility = function (layers, visibleToggle) {
      _.each(layers, function(layer) {
        layer.setVisible(visibleToggle);
      });
    };

    this.clearLayers = function(layers){
      _.each(layers, function(layer) {
        layer.getSource().clear();
      });
    };

    this.isStarted = function() {
      return me.eventListener.running;
    };

    this.start = function() {
      if (!me.isStarted()) {
        me.eventListener.running = true;
      }
    };

    this.stop = function() {
      if (me.isStarted()) {
        me.eventListener.stopListening(eventbus);
        me.eventListener.running = false;
      }
    };

    this.drawCalibrationMarkers = function(layer, roadLinks) {
      var calibrationPointsWithValue = [];
      _.filter(roadLinks, function (roadLink) {
        return roadLink.calibrationPoints.length > 0;
      }).forEach(function (roadLink) {
        roadLink.calibrationPoints.forEach(function (currentPoint) {
          var point = currentPoint.point;
          if (point)
            calibrationPointsWithValue.push({points: point, calibrationCode: roadLink.calibrationCode});
        });
      });
      return calibrationPointsWithValue;
    };

    this.mapOverLinkMiddlePoints = mapOverLinkMiddlePoints;
  };
})(this);
