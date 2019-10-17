(function(root) {
    root.NodeLayer = function (map, roadLayer, selectedNodeAndJunctionPoint, nodeCollection, roadCollection, linkPropertiesModel, applicationModel) {
      Layer.call(this, map);
      var me = this;
      var indicatorVector = new ol.source.Vector({});
      var directionMarkerVector = new ol.source.Vector({});
      var dblVector = function () { return { selected: new ol.source.Vector({}), unselected: new ol.source.Vector({}) }; };
      var nodeMarkerVector = dblVector();
      var junctionMarkerVector = dblVector();
      var nodePointTemplateVector = dblVector();
      var junctionTemplateVector = dblVector();
      var cachedMarker = null;

      var SelectionType = LinkValues.SelectionType;
      var Anomaly = LinkValues.Anomaly;
      var SideCode = LinkValues.SideCode;
      var RoadZIndex = LinkValues.RoadZIndex;

      var isActiveLayer = false;

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
        source: nodeMarkerVector.unselected,
        name: 'nodeMarkerLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value,
        selectable: false
      });

      var nodeMarkerSelectedLayer = new ol.layer.Vector({
        source: nodeMarkerVector.selected,
        name: 'nodeMarkerSelectedLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value
      });

      var junctionMarkerLayer = new ol.layer.Vector({
        source: junctionMarkerVector.unselected,
        name: 'junctionMarkerLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value + 1
      });

      var junctionMarkerSelectedLayer = new ol.layer.Vector({
        source: junctionMarkerVector.selected,
        name: 'junctionMarkerSelectedLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value + 1
      });

      var nodePointTemplateLayer = new ol.layer.Vector({
        source: nodePointTemplateVector.unselected,
        name: 'nodePointTemplateLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value - 1,
        selectable: false
      });

      var nodePointTemplateSelectedLayer = new ol.layer.Vector({
        source: nodePointTemplateVector.selected,
        name: 'nodePointTemplateSelectedLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value - 1
      });

      var junctionTemplateLayer = new ol.layer.Vector({
        source: junctionTemplateVector.unselected,
        name: 'junctionTemplateLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value - 1,
        selectable: false
      });

      var junctionTemplateSelectedLayer = new ol.layer.Vector({
        source: junctionTemplateVector.selected,
        name: 'junctionTemplateSelectedLayer',
        zIndex: RoadZIndex.CalibrationPointLayer.value - 1
      });

      var layers = [directionMarkerLayer, nodeMarkerLayer, nodeMarkerSelectedLayer, junctionMarkerLayer, junctionMarkerSelectedLayer, nodePointTemplateLayer, nodePointTemplateSelectedLayer, junctionTemplateLayer, junctionTemplateSelectedLayer];

      var setGeneralOpacity = function (opacity) {
        roadLayer.layer.setOpacity(opacity);
        indicatorLayer.setOpacity(opacity);
        directionMarkerLayer.setOpacity(opacity);
        nodeMarkerLayer.setOpacity(opacity);
        nodeMarkerSelectedLayer.setOpacity(opacity);
        junctionMarkerLayer.setOpacity(opacity);
        junctionMarkerSelectedLayer.setOpacity(opacity);
        nodePointTemplateLayer.setOpacity(opacity);
        nodePointTemplateSelectedLayer.setOpacity(opacity);
        junctionTemplateLayer.setOpacity(opacity);
        junctionTemplateSelectedLayer.setOpacity(opacity);
      };

      var setOpacityFor = function (layers, opacity) {
        _.each(layers, function (layer) {
          layer.setOpacity(opacity);
        });
      };

      var setProperty = function (layers, propertyName, propertyValue) {
        _.each(layers, function (layer) {
          layer.set(propertyName, propertyValue);
        });
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
        nodeAndJunctionPointTemplateClick.setMap(null); // stop openLayers from using internal unmanaged layer
        var selectedNodes = _.filter(event.selected, function (selectionTarget) {
          return !_.isUndefined(selectionTarget.nodeInfo);
        });

        var selectedNodePointTemplates = _.filter(event.selected, function (selectionTarget) {
          return !_.isUndefined(selectionTarget.nodePointTemplateInfo);
        });

        var selectedJunctions = _.filter(event.selected, function (selectionTarget) {
          return !_.isUndefined(selectionTarget.junctionTemplateInfo);
        });

        switch (applicationModel.getSelectedTool()) {
          case LinkValues.Tool.Unknown.value:
            if (!_.isUndefined(selectedJunctions) && selectedJunctions.length > 0) {
              selectJunctionTemplate(_.first(_.uniq(_.map(selectedJunctions, "junctionTemplateInfo"), "junctionId")));
            } else if (!_.isUndefined(selectedNodePointTemplates) && selectedNodePointTemplates.length > 0) {
              selectNodePointTemplate(_.uniq(_.map(selectedNodePointTemplates, "nodePointTemplateInfo"), "id"));
            }
            break;
          case LinkValues.Tool.Select.value:
            if (!_.isUndefined(selectedNodes) && selectedNodes.length > 0) {
              selectNode(_.first(_.uniq(_.map(selectedNodes, "nodeInfo"), "id")));
            }
            break;
        }
      });

      /**
       * This will add all the following interactions from the map:
       * - nodeAndJunctionPointTemplateClick
       */
      var toggleSelectInteractions = function (activate) {
        nodeAndJunctionPointTemplateClick.setActive(activate);
      };

      var addSelectInteractions = function () {
        map.addInteraction(nodeAndJunctionPointTemplateClick);
      };

      var removeSelectInteractions = function () {
        map.removeInteraction(nodeAndJunctionPointTemplateClick);
      };

      // We add the defined interactions to the map.
      addSelectInteractions();

      var selectFeaturesToHighlight = function (vector, featuresToHighlight, otherFeatures) {
        vector.selected.clear();
        vector.selected.addFeatures(featuresToHighlight);
        vector.unselected.clear();
        vector.unselected.addFeatures(otherFeatures);
      };

      var selectNode = function(node) {
        clearHighlights();
        selectedNodeAndJunctionPoint.openNode(node);
        highlightNode(node);
      };

      var selectNodePointTemplate = function(nodePointTemplate) {
        clearHighlights();
        selectedNodeAndJunctionPoint.openNodePointTemplate(nodePointTemplate);
      };

      var selectJunctionTemplate = function (junctionTemplate) {
        clearHighlights();
        selectedNodeAndJunctionPoint.openJunctionTemplate(junctionTemplate);
      };

      var addFeature = function(layer, feature) {
        layer.getSource().addFeature(feature);
      };

      var highlightNode = function (node) {
        var highlightJunctions = function(nodeId) {
          var junctions = _.partition(junctionMarkerLayer.getSource().getFeatures(), function (junctionFeature) {
            return junctionFeature.junction.nodeId === nodeId;
          });
          selectFeaturesToHighlight(junctionMarkerVector, junctions[0], junctions[1]);
        };

        var nodes = _.partition(nodeMarkerLayer.getSource().getFeatures(), function (nodeFeature) {
          return _.isEqual(nodeFeature.nodeInfo, node);
        });

        selectFeaturesToHighlight(nodeMarkerVector, nodes[0], nodes[1]);
        if (!_.isUndefined(node.id)) {
          highlightJunctions(node.id);
        } else {
          selectFeaturesToHighlight(junctionMarkerVector, [], junctionMarkerLayer.getSource().getFeatures());
        }
        clearHighlightsForUnselectedLayers();
      };

      var clearHighlights = function () {
        var nodes = nodeMarkerLayer.getSource().getFeatures().concat(nodeMarkerSelectedLayer.getSource().getFeatures());
        var junctions = junctionMarkerLayer.getSource().getFeatures().concat(junctionMarkerSelectedLayer.getSource().getFeatures());

        selectFeaturesToHighlight(nodeMarkerVector, [], nodes);
        selectFeaturesToHighlight(junctionMarkerVector, [], junctions);

        setGeneralOpacity(1);
      };

      var clearHighlightsForUnselectedLayers = function () {
        setOpacityFor([
          nodeMarkerLayer,
          junctionMarkerLayer,
          nodePointTemplateLayer,
          junctionTemplateLayer
        ], 0.2);
      };

      me.eventListener.listenTo(eventbus, 'node:unselected nodePointTemplate:unselected junctionTemplate:unselected', function (node) {
        removeCurrentNodeMarker(node);
        clearHighlights();
      });

      me.eventListener.listenTo(eventbus, 'tool:changed', function (tool) {
        switch (tool) {
          case LinkValues.Tool.Unknown.value:
            me.eventListener.stopListening(eventbus, 'map:clicked', createNewNodeMarker);
            setProperty([nodeMarkerLayer], 'selectable', false);
            setProperty([nodePointTemplateLayer, junctionTemplateLayer], 'selectable', true);
            break;
          case LinkValues.Tool.Select.value:
            me.eventListener.stopListening(eventbus, 'map:clicked', createNewNodeMarker);
            setProperty([nodeMarkerLayer], 'selectable', true);
            setProperty([nodePointTemplateLayer, junctionTemplateLayer], 'selectable', false);
            break;
          case LinkValues.Tool.Add.value:
            setProperty([nodeMarkerLayer, nodePointTemplateLayer, junctionTemplateLayer], 'selectable', false);
            me.eventListener.listenTo(eventbus, 'map:clicked', createNewNodeMarker);
            break;
        }
      });

      me.eventListener.listenTo(eventbus, 'node:save', function (node) {
        alert(node.name + 'is now saved.');
      });

      var createNewNodeMarker = function (coords) {
        var node = {
          coordX: parseInt(coords.x, 10),
          coordY: parseInt(coords.y, 10),
          type:   LinkValues.NodeType.UnkownNodeType.value
        };
        addFeature(nodeMarkerSelectedLayer, new NodeMarker().createNodeMarker(node));
        applicationModel.setSelectedTool(LinkValues.Tool.Unknown.value);
        selectNode(node);
      };

      var removeCurrentNodeMarker = function (node) {
        if (!_.isUndefined(node)) {
          _.each(nodeMarkerSelectedLayer.getSource().getFeatures(), function (nodeFeature) {
            if (_.isEqual(nodeFeature.nodeInfo, node)) {
              nodeMarkerSelectedLayer.getSource().removeFeature(nodeFeature);
            }
          });
        }
      };

      var updateCurrentNodeMarker = function (node) {
        if (!_.isUndefined(node)) {
          _.each(nodeMarkerSelectedLayer.getSource().getFeatures(), function (nodeFeature) {
            if (_.isEqual(nodeFeature.nodeInfo, node)) {
              nodeFeature.setProperties({type: node.type});
            }
          });
        }
      };

      me.eventListener.listenTo(eventbus, 'nodeLayer:fetch', function () {
        map.getView().setZoom(Math.round(zoomlevels.getViewZoom(map)));
        roadCollection.fetchWithNodes(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1);
      });

      me.eventListener.listenTo(eventbus, 'layer:selected', function (layer, previouslySelectedLayer) {
        toggleSelectInteractions(isActiveLayer);
        if (previouslySelectedLayer === 'node') {
          hideLayer();
          removeSelectInteractions();
        } else if (layer === 'node') {
          setGeneralOpacity(1);
          addSelectInteractions();
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
          me.clearLayers(layers);

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

        eventListener.listenTo(eventbus, 'node:addNodesToMap', function(nodes, nodePointTemplates, junctionTemplates, zoom) {
          var roadLinkForPoint = function (predicate) {
            var roadLinksWithValues = _.reject(roadCollection.getAll(), function (rl) {
              return rl.roadNumber === 0;
            });

            return _.find(roadLinksWithValues, predicate);
          };

          var addJunctionToMap = function (junction) {
            var junctionPoint = {};
            var roadLink = roadLinkForPoint(function (roadLink) {
              junctionPoint = _.find(junction.junctionPoints, function (junctionPoint) {
                return (roadLink.startAddressM === junctionPoint.addrM || roadLink.endAddressM === junctionPoint.addrM) && roadLink.roadwayNumber === junctionPoint.roadwayNumber;
              });
              return !_.isUndefined(junctionPoint);
            });
            if (!_.isUndefined(roadLink) && !_.isUndefined(junctionPoint)) {
              if (!_.isUndefined(junction.nodeId)) {
                addFeature(junctionMarkerLayer, new JunctionMarker().createJunctionMarker(junction, junctionPoint, roadLink));
              } else {
                addFeature(junctionTemplateLayer, new JunctionTemplateMarker().createJunctionTemplateMarker(junction, roadLink));
              }
            }
          };

          var selectedNode = selectedNodeAndJunctionPoint.getCurrentNode();

          if (parseInt(zoom, 10) >= zoomlevels.minZoomForNodes) {
            if (!_.isUndefined(selectedNode) && _.isUndefined(selectedNode.id)) {
              // adds node created by user which isn't saved yet.
              addFeature(nodeMarkerSelectedLayer, new NodeMarker().createNodeMarker(selectedNode));
            }

            _.each(nodes, function (node) {
              addFeature(nodeMarkerLayer, new NodeMarker().createNodeMarker(node));
            });

            _.each(nodePointTemplates, function (nodePointTemplate) {
              var roadLink = roadLinkForPoint(function (roadLink) {
                return (roadLink.startAddressM === nodePointTemplate.addrM || roadLink.endAddressM === nodePointTemplate.addrM) && roadLink.roadwayNumber === nodePointTemplate.roadwayNumber;
              });
              if (!_.isUndefined(roadLink)) {
                addFeature(nodePointTemplateLayer, new NodePointTemplateMarker().createNodePointTemplateMarker(nodePointTemplate, roadLink));
              }
            });
          }

          if (parseInt(zoom, 10) >= zoomlevels.minZoomForJunctions) {
            if (!_.isUndefined(selectedNode) && !_.isUndefined(selectedNode.nodeMarker)) {
              _.each(selectedNode.junctions, function (junction) {
                addJunctionToMap(junction);
              });
            }

            _.each(_.flatten(_.map(nodes, "junctions")), function (junction) {
              addJunctionToMap(junction);
            });

            _.each(junctionTemplates, function (junctionTemplate) {
              addJunctionToMap(junctionTemplate);
            });
          }

          if (!_.isUndefined(selectedNode) && !_.isUndefined(selectedNode.id)) {
            clearHighlights();
            highlightNode(selectedNode);
          }
        });

        eventListener.listenTo(eventbus, 'roadLinks:refreshView', function () {
          me.refreshView();
        });

        eventListener.listenTo(eventbus, 'map:clearLayers', me.clearLayers);

        eventListener.listenTo(eventbus, 'changed:type', function (node) {
          updateCurrentNodeMarker(node);
        });
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