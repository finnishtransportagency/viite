(function(root) {
  root.Layer = function(map, layerName, roadCollection, projectCollection) {
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

    this.refreshView = function(event) {};

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

    this.start = function(event) {
      if (!me.isStarted()) {
        me.eventListener.running = true;
        me.layerStarted(me.eventListener);
        me.refreshView(event);
      }
    };

    this.stop = function() {
      if (me.isStarted()) {
        me.eventListener.stopListening(eventbus);
        me.eventListener.running = false;
      }
    };
    /*
    var handleRoadsVisibility = function () {
      if (_.isObject(roadLayer))
        roadLayer.layer.setVisible(applicationModel.getRoadVisibility() && map.getView().getZoom() >= zoomlevels.minZoomForRoadLinks);
    };

    this.mapMovedHandler = function (mapState) {
      if ((applicationModel.getSelectedTool() === 'Cut' && selectSingleClick.getFeatures().getArray().length > 0) || layerName !== mapState.selectedLayer)
        return;
      if (mapState.zoom < zoomlevels.minZoomForRoadLinks) {
        roadLayer.layer.getSource().clear();
        eventbus.trigger('map:clearLayers');
      } else {
        switch(layerName) {
          case 'linkProperty':
            roadCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), map.getView().getZoom() + 1);
            break;
          case 'roadAddressProject':
            var projectId = _.isUndefined(projectCollection.getCurrentProject()) ? undefined : projectCollection.getCurrentProject().project.id;
            projectCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), map.getView().getZoom() + 1, projectId, projectCollection.getPublishableStatus());
        }
        handleRoadsVisibility();
      }
    };
*/
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

    this.eventListener.listenTo(eventbus, 'map:moved', me.mapMovedHandler, this);
    this.mapOverLinkMiddlePoints = mapOverLinkMiddlePoints;
    this.show = function(map) {
      if (map.getView().getZoom() >= me.minZoomForContent) {
        //roadLayer.layer.setVisible(true);
      }
    };
    this.hide = function() {
      this.eventListener.stopListening(eventbus, 'map:moved', me.mapMovedHandler, this);
      //roadLayer.clear();
    };

  };
})(this);
