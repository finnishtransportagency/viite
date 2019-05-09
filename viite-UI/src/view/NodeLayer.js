(function(root) {
    root.NodeLayer = function (map, roadLayer, nodeCollection, roadCollection, linkPropertiesModel, applicationModel) {
      Layer.call(this, map);
      var me = this;
      var indicatorVector = new ol.source.Vector({});
      var anomalousMarkerVector = new ol.source.Vector({});
      var directionMarkerVector = new ol.source.Vector({});
      var nodeMarkerVector = new ol.source.Vector({});
      var junctionMarkerVector = new ol.source.Vector({});
      var nodePointTemplateVector = new ol.source.Vector({});
      var junctionTemplateVector = new ol.source.Vector({});
      var isActiveLayer = false;
      var cachedMarker = null;
      var roadLinkStyler = new RoadLinkStyler();

      var SelectionType = LinkValues.SelectionType;
      var Anomaly = LinkValues.Anomaly;
      var SideCode = LinkValues.SideCode;
      var RoadZIndex = LinkValues.RoadZIndex;

      var indicatorLayer = new ol.layer.Vector({
        source: indicatorVector,
        name: 'indicatorLayer',
        zIndex: RoadZIndex.IndicatorLayer.value
      });
      indicatorLayer.set('name', 'indicatorLayer');

      var anomalousMarkerLayer = new ol.layer.Vector({
        source: anomalousMarkerVector,
        name: 'anomalousMarkerLayer',
        zIndex: RoadZIndex.IndicatorLayer.value
      });
      anomalousMarkerLayer.set('name', 'anomalousMarkerLayer');

      var directionMarkerLayer = new ol.layer.Vector({
        source: directionMarkerVector,
        name: 'directionMarkerLayer',
        zIndex: RoadZIndex.DirectionMarkerLayer.value
      });
      directionMarkerLayer.set('name', 'directionMarkerLayer');

      var nodeMarkerLayer = new ol.layer.Vector({
        source: nodeMarkerVector,
        name: 'nodeMarkerLayer',
        zIndex: RoadZIndex.DirectionMarkerLayer.value
      });
      nodeMarkerLayer.set('name', 'nodeMarkerLayer');

      var junctionMarkerLayer = new ol.layer.Vector({
        source: junctionMarkerVector,
        name: 'junctionMarkerLayer',
        zIndex: RoadZIndex.DirectionMarkerLayer.value
      });
      junctionMarkerLayer.set('name', 'junctionMarkerLayer');

      var nodePointTemplateLayer = new ol.layer.Vector({
        source: nodePointTemplateVector,
        name: 'nodePointTemplateLayer',
        zIndex: RoadZIndex.DirectionMarkerLayer.value
      });
      nodePointTemplateLayer.set('name', 'nodePointTemplateLayer');

      var junctionTemplateLayer = new ol.layer.Vector({
        source: junctionTemplateVector,
        name: 'junctionTemplateLayer',
        zIndex: RoadZIndex.DirectionMarkerLayer.value
      });
      junctionTemplateLayer.set('name', 'junctionTemplateLayer');

      var layers = [roadLayer.layer, indicatorLayer, anomalousMarkerLayer, directionMarkerLayer, nodeMarkerLayer, junctionMarkerLayer, nodePointTemplateLayer, junctionTemplateLayer];

      var setGeneralOpacity = function (opacity) {
        roadLayer.layer.setOpacity(opacity);
        indicatorLayer.setOpacity(opacity);
        anomalousMarkerLayer.setOpacity(opacity);
        directionMarkerLayer.setOpacity(opacity);
        nodeMarkerLayer.setOpacity(opacity);
        junctionMarkerLayer.setOpacity(opacity);
        nodePointTemplateLayer.setOpacity(opacity);
        junctionTemplateLayer.setOpacity(opacity);
      };

      var drawIndicators = function (links) {
        var features = [];

        var markerContainer = function (link, position) {
          var style = new ol.style.Style({
            image: new ol.style.Icon({
              src: 'images/center-marker2.svg'
            }),
            text: new ol.style.Text({
              text: link.marker,
              fill: new ol.style.Fill({
                color: '#ffffff'
              }),
              font: '12px sans-serif'
            })
          });
          var marker = new ol.Feature({
            geometry: new ol.geom.Point([position.x, position.y])
          });
          marker.setStyle(style);
          features.push(marker);
        };

        var indicators = function () {
          return me.mapOverLinkMiddlePoints(links, function (link, middlePoint) {
            markerContainer(link, middlePoint);
          });
        };
        indicators();
        indicatorLayer.getSource().addFeatures(features);
      };

      var redraw = function () {
        cachedMarker = new LinkPropertyMarker();
        var suravageLinks = roadCollection.getSuravageLinks();
        var roadLinks = _.reject(roadCollection.getAll(), function (rl) {
          return _.contains(_.map(suravageLinks, function (sl) {
            return sl.linkId;
          }), rl.linkId);
        });
        me.clearLayers([anomalousMarkerLayer, directionMarkerLayer, nodeMarkerLayer, junctionMarkerLayer, nodePointTemplateLayer, junctionTemplateLayer]);

        if (zoomlevels.getViewZoom(map) >= zoomlevels.minZoomForAssets) {

          var directionRoadMarker = _.filter(roadLinks, function (roadLink) {
            return roadLink.floating !== SelectionType.Floating.value && roadLink.anomaly !== Anomaly.NoAddressGiven.value && roadLink.anomaly !== Anomaly.GeometryChanged.value && (roadLink.sideCode === SideCode.AgainstDigitizing.value || roadLink.sideCode === SideCode.TowardsDigitizing.value);
          });
          _.each(directionRoadMarker, function (directionLink) {
            var marker = cachedMarker.createMarker(directionLink);
            if (zoomlevels.getViewZoom(map) > zoomlevels.minZoomForDirectionalMarkers)
              directionMarkerLayer.getSource().addFeature(marker);
          });

          var anomalousRoadMarkers = _.filter(roadLinks, function (roadLink) {
            return roadLink.anomaly !== Anomaly.None.value;
          });
          _.each(anomalousRoadMarkers, function (anomalousLink) {
            var marker = cachedMarker.createMarker(anomalousLink);
            anomalousMarkerLayer.getSource().addFeature(marker);
          });

        }
        if (applicationModel.getCurrentAction() === -1) {
          applicationModel.removeSpinner();
        }
      };

      this.refreshView = function () {
        //Generalize the zoom levels as the resolutions and zoom levels differ between map tile sources
        roadCollection.reset();
        roadCollection.fetch(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map));
        roadLayer.layer.changed();
      };

      this.layerStarted = function (eventListener) {
        eventListener.listenTo(eventbus, 'roadLinks:fetched', function (eventData, reselection, selectedIds) {
          redraw();
        });

        eventListener.listenTo(eventbus, 'linkProperties:clearIndicators', function () {
          clearIndicators();
        });

        eventListener.listenTo(eventbus, 'roadLinks:refreshView', function () {
          me.refreshView();
        });

        var clearIndicators = function () {
          indicatorLayer.getSource().clear();
        };

        eventListener.listenTo(eventListener, 'map:clearLayers', me.clearLayers);
      };

      me.eventListener.listenTo(eventbus, 'linkProperty:fetch', function () {
        map.getView().setZoom(Math.round(zoomlevels.getViewZoom(map)));
        roadCollection.fetch(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1);
      });

      me.eventListener.listenTo(eventbus, 'layer:selected', function (layer, previouslySelectedLayer) {
        isActiveLayer = layer === 'node';
        me.clearLayers();
        if (previouslySelectedLayer === 'node') {
          hideLayer();
        } else if (previouslySelectedLayer === 'roadAddressProject') {
          setGeneralOpacity(1);
          showLayer();
          _.defer(function () {
            roadCollection.fetch(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map));
          });
        }
        me.toggleLayersVisibility(layers, applicationModel.getRoadVisibility());
      });

      var showLayer = function () {
        me.start();
        me.layerStarted(me.eventListener);
      };

      var hideLayer = function () {
        me.clearLayers(layers);
      };

      me.toggleLayersVisibility(layers, true);
      me.addLayers(layers);
      me.layerStarted(me.eventListener);

      return {
        show: showLayer,
        hide: hideLayer,
        minZoomForContent: me.minZoomForContent
      };
    };

  })(this);