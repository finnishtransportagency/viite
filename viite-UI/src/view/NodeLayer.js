(function(root) {
    root.NodeLayer = function (map, roadLayer, nodeCollection, roadCollection, linkPropertiesModel, applicationModel) {
      Layer.call(this, map);
      var me = this;
      var indicatorVector = new ol.source.Vector({});
      var directionMarkerVector = new ol.source.Vector({});
      var nodeMarkerVector = new ol.source.Vector({});
      var junctionMarkerVector = new ol.source.Vector({});
      var nodePointTemplateVector = new ol.source.Vector({});
      var junctionTemplateVector = new ol.source.Vector({});
      var isActiveLayer = false;
      var cachedMarker = null;

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

      var directionMarkerLayer = new ol.layer.Vector({
        source: directionMarkerVector,
        name: 'directionMarkerLayer',
        zIndex: RoadZIndex.VectorLayer.value
      });
      directionMarkerLayer.set('name', 'directionMarkerLayer');

      var nodeMarkerLayer = new ol.layer.Vector({
        source: nodeMarkerVector,
        name: 'nodeMarkerLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value
      });
      nodeMarkerLayer.set('name', 'nodeMarkerLayer');

      var junctionMarkerLayer = new ol.layer.Vector({
        source: junctionMarkerVector,
        name: 'junctionMarkerLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value + 1
      });
      junctionMarkerLayer.set('name', 'junctionMarkerLayer');

      var nodePointTemplateLayer = new ol.layer.Vector({
        source: nodePointTemplateVector,
        name: 'nodePointTemplateLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value - 1
      });
      nodePointTemplateLayer.set('name', 'nodePointTemplateLayer');

      var junctionTemplateLayer = new ol.layer.Vector({
        source: junctionTemplateVector,
        name: 'junctionTemplateLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value - 1
      });
      junctionTemplateLayer.set('name', 'junctionTemplateLayer');

      var layers = [directionMarkerLayer, nodeMarkerLayer, junctionMarkerLayer, nodePointTemplateLayer, junctionTemplateLayer];

      var setGeneralOpacity = function (opacity) {
        roadLayer.layer.setOpacity(opacity);
        indicatorLayer.setOpacity(opacity);
        directionMarkerLayer.setOpacity(opacity);
        nodeMarkerLayer.setOpacity(opacity);
        junctionMarkerLayer.setOpacity(opacity);
        nodePointTemplateLayer.setOpacity(opacity);
        junctionTemplateLayer.setOpacity(opacity);
      };

      var redraw = function () {
        if(applicationModel.getSelectedLayer() === 'node') {

          cachedMarker = new LinkPropertyMarker();
          var suravageLinks = roadCollection.getSuravageLinks();
          var roadLinks = _.reject(roadCollection.getAll(), function (rl) {
            return _.includes(_.map(suravageLinks, function (sl) {
              return sl.linkId;
            }), rl.linkId);
          });
          me.clearLayers([directionMarkerLayer, nodeMarkerLayer, junctionMarkerLayer]);

          if (zoomlevels.getViewZoom(map) >= zoomlevels.minZoomForRoadNetwork) {

            var directionRoadMarker = _.filter(roadLinks, function (roadLink) {
              return roadLink.floating !== SelectionType.Floating.value && roadLink.anomaly !== Anomaly.NoAddressGiven.value && roadLink.anomaly !== Anomaly.GeometryChanged.value && (roadLink.sideCode === SideCode.AgainstDigitizing.value || roadLink.sideCode === SideCode.TowardsDigitizing.value);
            });
            _.each(directionRoadMarker, function (directionLink) {
              var marker = cachedMarker.createMarker(directionLink);
              if (zoomlevels.getViewZoom(map) > zoomlevels.minZoomForDirectionalMarkers)
                directionMarkerLayer.getSource().addFeature(marker);
            });
          }

          if (applicationModel.getCurrentAction() === -1) {
            applicationModel.removeSpinner();
          }
        }
      };

      this.refreshView = function () {
        //Generalize the zoom levels as the resolutions and zoom levels differ between map tile sources
        roadCollection.reset();
        roadCollection.fetchWithNodes(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map));
        roadLayer.layer.changed();
      };

      this.layerStarted = function (eventListener) {
        eventListener.listenTo(eventbus, 'roadLinks:fetched', function () {
          if(applicationModel.getSelectedLayer() === 'node') {
            redraw();
          }
        });

        eventListener.listenTo(eventbus, 'node:addNodesToMap', function(nodes, zoom){
          if (parseInt(zoom, 10) >= zoomlevels.minZoomForNodes) {
            _.each(nodes, function (node) {
              var nodeMarker = new NodeMarker();
              nodeMarkerLayer.getSource().addFeature(nodeMarker.createNodeMarker(node.node));
            });
          }

          if (parseInt(zoom, 10) >= zoomlevels.minZoomForJunctions){
            var suravageLinks = roadCollection.getSuravageLinks();
            var roadLinksWithValues = _.reject(roadCollection.getAll(), function (rl) {
              return _.includes(_.map(suravageLinks, function (sl) {
                return sl.linkId;
              }), rl.linkId) || rl.roadNumber === 0;
            });
            var junctions = [];
            var junctionPoints = [];
            var junctionPointsWithRoadlinks;
            _.map(nodes, function(node){
              junctions =  junctions.concat(node.junctions);
            });

            _.map(junctions, function (junction) {
              junctionPoints = junctionPoints.concat(junction.junctionPoints);
            });

            junctionPointsWithRoadlinks = _.map(junctionPoints, function (junctionPoint) {
                return {
                  junctionPoint: junctionPoint,
                  roadLinks:_.filter(roadLinksWithValues, function (roadLink) {
                    return (roadLink.startAddressM === junctionPoint.addrM || roadLink.endAddressM === junctionPoint.addrM) && roadLink.roadwayNumber === junctionPoint.roadwayNumber;
                  }),
                  junction: _.find(junctions, function (junction) {
                    return junction.id === junctionPoint.junctionId;
                  })
                };
              }
            );

            _.each(junctionPointsWithRoadlinks, function (junctionPoint) {
              _.each(junctionPoint.roadLinks, function (roadLink) {
               var junctionMarker = new JunctionMarker();
               junctionMarkerLayer.getSource().addFeature(junctionMarker.createJunctionMarker(junctionPoint.junctionPoint, junctionPoint.junction, roadLink));
              });
            });
          }
        });

        eventListener.listenTo(eventbus, 'roadLinks:refreshView', function () {
          me.refreshView();
        });

        eventListener.listenTo(eventbus, 'map:clearLayers', me.clearLayers);
      };

      me.eventListener.listenTo(eventbus, 'nodeLayer:fetch', function () {
        map.getView().setZoom(Math.round(zoomlevels.getViewZoom(map)));
        roadCollection.fetchWithNodes(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1);
      });

      me.eventListener.listenTo(eventbus, 'layer:selected', function (layer, previouslySelectedLayer) {
        isActiveLayer = layer === 'node';
        me.clearLayers();
        if (previouslySelectedLayer === 'node') {
          hideLayer();
        } else if (previouslySelectedLayer === 'linkProperty') {
          setGeneralOpacity(1);
          showLayer();
          eventbus.trigger('nodeLayer:fetch');
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

      return {
        show: showLayer,
        hide: hideLayer,
        minZoomForContent: me.minZoomForContent
      };
    };

  })(this);