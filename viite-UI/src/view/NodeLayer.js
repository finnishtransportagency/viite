(function(root) {
    root.NodeLayer = function (map, roadLayer, selectedNodeAndJunctionPoint, nodeCollection, roadCollection, linkPropertiesModel, applicationModel) {
      Layer.call(this, map);
      var me = this;
      var indicatorVector = new ol.source.Vector({});
      var directionMarkerVector = new ol.source.Vector({});
      var nodeMarkerVector = new ol.source.Vector({});
      var junctionMarkerVector = new ol.source.Vector({});
      var junctionSelectedMarkerVector = new ol.source.Vector({});
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

      var nodeMarkerLayer = new ol.layer.Vector({
        source: nodeMarkerVector,
        name: 'nodeMarkerLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value,
        selectable: false
      });

      var junctionMarkerLayer = new ol.layer.Vector({
        source: junctionMarkerVector,
        name: 'junctionMarkerLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value + 1,
      });

      var junctionSelectedMarkerLayer = new ol.layer.Vector({
        source: junctionSelectedMarkerVector,
        name: 'junctionSelectedMarkerLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value + 1,
      });

      var nodePointTemplateLayer = new ol.layer.Vector({
        source: nodePointTemplateVector,
        name: 'nodePointTemplateLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value - 1,
        selectable: false
      });

      var junctionTemplateLayer = new ol.layer.Vector({
        source: junctionTemplateVector,
        name: 'junctionTemplateLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value - 1,
        selectable: false
      });

      var layers = [directionMarkerLayer, nodeMarkerLayer, junctionMarkerLayer, junctionSelectedMarkerLayer, nodePointTemplateLayer, junctionTemplateLayer];

      var setGeneralOpacity = function (opacity) {
        roadLayer.layer.setOpacity(opacity);
        indicatorLayer.setOpacity(opacity);
        directionMarkerLayer.setOpacity(opacity);
        nodeMarkerLayer.setOpacity(opacity);
        junctionMarkerLayer.setOpacity(opacity);
        junctionSelectedMarkerLayer.setOpacity(opacity);
        nodePointTemplateLayer.setOpacity(opacity);
        junctionTemplateLayer.setOpacity(opacity);
      };

      /**
       * Type of interactions we want the map to be able to respond.
       * A selected feature is moved to a new/temporary layer out of the default roadLayer.
       * This interaction is restricted to a single click (there is a 250 ms enforced
       * delay between single clicks in order to differentiate from double click).
       * @type {ol.interaction.Select}
       */
      var nodeAndJunctionPointTemplateClick = new ol.interaction.Select({
        // Multi is the one en charge of defining if we select just the feature we clicked or all the overlapping
        multi: true,
        // This will limit the interaction to the specific layer
        layers: function (layer) {
          return layer.get('selectable');
        },
        name: 'nodeAndJunctionPointTemplateClickInteractionNL',
        // Limit this interaction to the singleClick
        condition: ol.events.condition.singleClick
      });

      /**
       * We now declare what kind of custom actions we want when the interaction happens.
       * Note that 'select' is triggered when a feature is either selected or deselected.
       * The event holds the selected features in the events.selected and the deselected in event.deselected.
       *
       * In this particular case we are fetching every node point template marker in view and
       * sending them to the selectedNode.open for further processing.
       */
      nodeAndJunctionPointTemplateClick.on('select', function (event) {
        var selectedNode = _.filter(event.selected, function (selectionTarget) {
          return !_.isUndefined(selectionTarget.nodeInfo);
        });

        var selectedNodePointTemplate = _.filter(event.selected, function (selectionTarget) {
          return !_.isUndefined(selectionTarget.nodePointTemplateInfo);
        });

        var selectedJunction = _.filter(event.selected, function (selectionTarget) {
          return !_.isUndefined(selectionTarget.junctionPointTemplateInfo);
        });

        if (!_.isUndefined(selectedNode) && selectedNode.length === 0 && !_.isUndefined(selectedNodePointTemplate) && selectedNodePointTemplate.length === 0 && !_.isUndefined(selectedJunction) && selectedJunction.length === 0) {
          selectedNodeAndJunctionPoint.close();
          clearJunctionsHighlights();
        } else {
          switch (applicationModel.getSelectedTool()) {
            case LinkValues.Tool.Unknown.value:
              if (!_.isUndefined(selectedJunction) && selectedJunction.length > 0) {
                selectJunctionTemplate(selectedJunction);
              } else if (!_.isUndefined(selectedNodePointTemplate) && selectedNodePointTemplate.length > 0) {
                selectNodePointTemplate(selectedNodePointTemplate);
              }
              break;
            case LinkValues.Tool.Select.value:
              if (!_.isUndefined(selectedNode) && selectedNode.length > 0) { selectNode(selectedNode); }
              break;
            case LinkValues.Tool.Add.value:
              // TODO - Create Node
              break;
          }
        }
      });

      var selectNode = function(selectedNode) {
        nodeMarkerLayer.setMap(null);
        var node = _.first(_.unique(_.map(selectedNode, "nodeInfo"), "id"));
        selectedNodeAndJunctionPoint.openNode(node);
        highlightJunctions(node.id);
      };

      var selectNodePointTemplate = function(selectedNodePoint) {
        selectedNodeAndJunctionPoint.openNodePointTemplates(_.unique(_.map(selectedNodePoint, "nodePointTemplateInfo"), "id"));
      };

      var selectJunctionTemplate = function (selectedJunction) {
        selectedNodeAndJunctionPoint.openJunctionPointTemplates(_.unique(_.map(selectedJunction, "junctionPointTemplateInfo"), "junctionId"));
      };

      var highlightJunctions = function(nodeId) {
        var junctions = _.partition(junctionMarkerLayer.getSource().getFeatures(), function (feature) {
          return feature.junction.nodeId === nodeId;
        });

        junctionSelectedMarkerVector.clear();
        junctionSelectedMarkerVector.addFeatures(junctions[0]);
        junctionMarkerVector.clear();
        junctionMarkerVector.addFeatures(junctions[1]);

        nodeMarkerLayer.setOpacity(0.2);
        junctionMarkerLayer.setOpacity(0.2);
        junctionTemplateLayer.setOpacity(0.2);
      };

      var clearJunctionsHighlights = function () {
        var junctions = junctionMarkerLayer.getSource().getFeatures().concat(junctionSelectedMarkerLayer.getSource().getFeatures());

        junctionSelectedMarkerVector.clear();
        junctionMarkerVector.clear();
        junctionMarkerVector.addFeatures(junctions);

        setGeneralOpacity(1);
      };

      /**
       * This will add all the following interactions from the map:
       * - nodeAndJunctionPointTemplateClick
       */
      var addClickInteractions = function () {
        map.addInteraction(nodeAndJunctionPointTemplateClick);
      };

      // We add the defined interactions to the map.
      addClickInteractions();

      me.eventListener.listenTo(eventbus, 'node:unselected', function () {
        if (nodePointTemplateLayer.getSource().getFeatures().length !== 0) {
          nodePointTemplateLayer.getSource().clear();
        }
      });+

      me.eventListener.listenTo(eventbus, 'junction:unselected', function () {
        if (junctionTemplateLayer.getSource().getFeatures().length !== 0) {
          junctionTemplateLayer.getSource().clear();
        }
      });

      var setProperty = function (layers, propertyName, propertyValue) {
        _.each(layers, function (layer) {
          layer.set(propertyName, propertyValue);
        });
      };

      me.eventListener.listenTo(eventbus, 'tool:changed', function (tool) {
        switch (tool) {
          case LinkValues.Tool.Unknown.value:
            setProperty([nodeMarkerLayer], 'selectable', false);
            setProperty([nodePointTemplateLayer, junctionTemplateLayer], 'selectable', true);
            break;
          case LinkValues.Tool.Select.value:
            setProperty([nodeMarkerLayer], 'selectable', true);
            setProperty([nodePointTemplateLayer, junctionTemplateLayer], 'selectable', false);
            break;
          case LinkValues.Tool.Add.value:
            setProperty([nodeMarkerLayer], 'selectable', false);
            setProperty([nodePointTemplateLayer, junctionTemplateLayer], 'selectable', false);
            break;
        }
      });

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

      var redraw = function () {
        if(applicationModel.getSelectedLayer() === 'node') {

          cachedMarker = new LinkPropertyMarker();
          var underConstructionLinks = roadCollection.getUnderConstructionLinks();
          var roadLinks = _.reject(roadCollection.getAll(), function (rl) {
            return _.contains(_.map(underConstructionLinks, function (sl) {
              return sl.linkId;
            }), rl.linkId);
          });
          me.clearLayers([directionMarkerLayer, nodeMarkerLayer, junctionMarkerLayer, junctionSelectedMarkerLayer, nodePointTemplateLayer, junctionTemplateLayer]);

          if (zoomlevels.getViewZoom(map) >= zoomlevels.minZoomForRoadNetwork) {

            var directionRoadMarker = _.filter(roadLinks, function (roadLink) {
              return roadLink.floating !== SelectionType.Floating.value && roadLink.anomaly !== Anomaly.NoAddressGiven.value && roadLink.anomaly !== Anomaly.GeometryChanged.value && (roadLink.sideCode === SideCode.AgainstDigitizing.value || roadLink.sideCode === SideCode.TowardsDigitizing.value);
            });
            _.each(directionRoadMarker, function (directionLink) {
              cachedMarker.createMarker(directionLink, function (marker) {
                if (zoomlevels.getViewZoom(map) > zoomlevels.minZoomForDirectionalMarkers)
                  directionMarkerLayer.getSource().addFeature(marker);
              });
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

        eventListener.listenTo(eventbus, 'node:addNodesToMap', function(nodes, nodePointTemplates, junctionPointTemplates, zoom){
          var roadLinksWithValues = _.reject(roadCollection.getAll(), function (rl) {
            return rl.roadNumber === 0;
          });
          if (parseInt(zoom, 10) >= zoomlevels.minZoomForNodes) {
            _.each(nodes, function (node) {
              var nodeMarker = new NodeMarker();
              nodeMarkerLayer.getSource().addFeature(nodeMarker.createNodeMarker(node));
            });

            _.each(nodePointTemplates, function (nodePointTemplate) {
              var nodePointTemplateMarker = new NodePointTemplateMarker();
              var roadLinkForPoint = _.find(roadLinksWithValues, function (roadLink) {
                return (roadLink.startAddressM === nodePointTemplate.addrM || roadLink.endAddressM === nodePointTemplate.addrM) && roadLink.roadwayNumber === nodePointTemplate.roadwayNumber;
              });
              if (!_.isUndefined(roadLinkForPoint)) {
                nodePointTemplateLayer.getSource().addFeature(nodePointTemplateMarker.createNodePointTemplateMarker(nodePointTemplate, roadLinkForPoint));
              }
            });
          }

          if (parseInt(zoom, 10) >= zoomlevels.minZoomForJunctions){
            var junctions = [];
            var junctionPoints = [];
            var junctionPointsWithRoadlinks;
            _.map(nodes, function(node){
              junctions = junctions.concat(node.junctions);
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

            _.each(junctionPointTemplates, function (junctionPointTemplate) {
              var junctionPointTemplateMarker = new JunctionPointTemplateMarker();
              var roadLinkForPoint = _.find(roadLinksWithValues, function (roadLink) {
                return (roadLink.startAddressM === junctionPointTemplate.addrM || roadLink.endAddressM === junctionPointTemplate.addrM) && roadLink.roadwayNumber === junctionPointTemplate.roadwayNumber;
              });
              if (!_.isUndefined(roadLinkForPoint)) {
                junctionTemplateLayer.getSource().addFeature(junctionPointTemplateMarker.createJunctionPointTemplateMarker(junctionPointTemplate, roadLinkForPoint));
              }
            });

            var selectedNode = selectedNodeAndJunctionPoint.getCurrentNode();

            if (!_.isUndefined(selectedNode)) {
              highlightJunctions(selectedNode.id);
            }
          }
        });

        eventListener.listenTo(eventbus, 'roadLinks:refreshView', function () {
          me.refreshView();
        });

        eventListener.listenTo(eventbus, 'map:clearLayers', me.clearLayers);
      };

      var showLayer = function () {
        me.start();
        me.layerStarted(me.eventListener);
        $('#projectListButton').prop('disabled', true);
      };

      var hideLayer = function () {
        me.clearLayers(layers);
        $('#projectListButton').prop('disabled', false);
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