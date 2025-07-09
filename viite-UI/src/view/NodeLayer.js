(function (root) {
  root.NodeLayer = function (map, roadLayer, selectedNodesAndJunctions, nodeCollection, roadCollection, applicationModel) {
    Layer.call(this, map);
    var me = this;
    var userHasPermissionToEdit = _.includes(applicationModel.getSessionUserRoles(), 'viite');
    var directionMarkerVector = new ol.source.Vector({});
    var dblVector = function () {
      return {selected: new ol.source.Vector({}), unselected: new ol.source.Vector({})};
    };
    var nodeMarkerVector = dblVector();
    var junctionMarkerVector = dblVector();
    var nodePointTemplateVector = dblVector();
    var junctionTemplateVector = dblVector();

    let selectedNodeStartingCoordinates = null;

    var directionMarkerLayer = new ol.layer.Vector({
      source: directionMarkerVector,
      name: 'directionMarkerLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.DirectionMarker.value
    });

    var nodeMarkerLayer = new ol.layer.Vector({
      source: nodeMarkerVector.unselected,
      name: 'nodeMarkerLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.NodeMarker.value,
      selectable: false
    });

    var nodeMarkerSelectedLayer = new ol.layer.Vector({
      source: nodeMarkerVector.selected,
      name: 'nodeMarkerSelectedLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.NodeMarker.selected
    });

    var junctionMarkerLayer = new ol.layer.Vector({
      source: junctionMarkerVector.unselected,
      name: 'junctionMarkerLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.JunctionMarker.value
    });

    var junctionMarkerSelectedLayer = new ol.layer.Vector({
      source: junctionMarkerVector.selected,
      name: 'junctionMarkerSelectedLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.JunctionMarker.selected
    });

    var nodePointTemplateLayer = new ol.layer.Vector({
      source: nodePointTemplateVector.unselected,
      name: 'nodePointTemplateLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.NodePointTemplate.value,
      selectable: false
    });

    var nodePointTemplateSelectedLayer = new ol.layer.Vector({
      source: nodePointTemplateVector.selected,
      name: 'nodePointTemplateSelectedLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.NodePointTemplate.selected
    });

    var junctionTemplateLayer = new ol.layer.Vector({
      source: junctionTemplateVector.unselected,
      name: 'junctionTemplateLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.JunctionTemplate.value,
      selectable: false
    });

    var junctionTemplateSelectedLayer = new ol.layer.Vector({
      source: junctionTemplateVector.selected,
      name: 'junctionTemplateSelectedLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.JunctionTemplate.selected
    });

    var layers = [directionMarkerLayer, nodeMarkerLayer, nodeMarkerSelectedLayer, junctionMarkerLayer, junctionMarkerSelectedLayer, nodePointTemplateLayer, nodePointTemplateSelectedLayer, junctionTemplateLayer, junctionTemplateSelectedLayer];

    var setGeneralOpacity = function (opacity) {
      roadLayer.layer.setOpacity(opacity);
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

    var setProperty = function (propertyLayers, propertyName, propertyValue) {
      _.each(propertyLayers, function (layer) {
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
    var nodeLayerSelectInteraction = new ol.interaction.Select({
      // This will limit the interaction to the specific layer
      layers: function (layer) {
        return layer.get('selectable');
      },
      name: 'nodeLayerSelectInteractionNL',
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
    nodeLayerSelectInteraction.on('select', function (event) {
      var selectedNode = _.find(event.selected, function (selectionTarget) {
        return !_.isUndefined(selectionTarget.node);
      });

      // Update starting coordinates before translate happens for precise coordinates
      selectedNodeStartingCoordinates = selectedNode.node.coordinates;

      // select all node point templates in same place.
      var selectedNodePointTemplate = _.find(event.selected, function (selectionTarget) {
        return !_.isUndefined(selectionTarget.nodePointTemplate);
      });

      var selectedJunctionTemplate = _.find(event.selected, function (selectionTarget) {
        return !_.isUndefined(selectionTarget.junctionTemplate);
      });

      switch (applicationModel.getSelectedTool()) {
        case ViiteEnumerations.Tool.Unknown.value:
          if (!_.isUndefined(selectedJunctionTemplate) && _.has(selectedJunctionTemplate, 'junctionTemplate')) {
            selectJunctionTemplate(selectedJunctionTemplate.junctionTemplate);
          } else if (!_.isUndefined(selectedNodePointTemplate) && _.has(selectedNodePointTemplate, 'nodePointTemplate')) {
            selectNodePointTemplate(selectedNodePointTemplate.nodePointTemplate);
          }
          break;
        case ViiteEnumerations.Tool.Select.value:
          if (!_.isUndefined(selectedNode) && !_.isUndefined(selectedNode.node)) {
            selectNode(selectedNode.node);
          }
          break;
        case ViiteEnumerations.Tool.Attach.value:
          if (!_.isUndefined(selectedNode) && !_.isUndefined(selectedNode.node)) {
            attachNode(selectedNode.node, selectedNodesAndJunctions.getCurrentTemplates());
          }
          break;
        default:
          break;
      }
    });

    /**
     * Type of interactions we want the map to be able to respond to.
     * A translate feature used to move either 'selected' or 'unselected' nodes to a new place,
     * within a maximum of 200m distance.
     * @type {ol.interaction.Translate}
     */
    var nodeTranslate = new ol.interaction.Translate({
      layers: [nodeMarkerSelectedLayer]
    });

    /**
     * Save initial node position for comparison purposes
     */
    nodeTranslate.on('translatestart', function (evt) {
      selectedNodesAndJunctions.setStartingCoordinates(selectedNodeStartingCoordinates);
    });

    /**
     * while translating the new position the 200m limitation need to be verified
     * and stop the node movement when that limitation is not obeyed
     */
    nodeTranslate.on('translating', function (evt) {
      var coordinates = {
        x: parseInt(evt.coordinate[0]),
        y: parseInt(evt.coordinate[1])
      };
      if (GeometryUtils.distanceBetweenPoints(selectedNodesAndJunctions.getStartingCoordinates(), coordinates) < ViiteConstants.MAX_ALLOWED_DISTANCE_FOR_NODES_TO_BE_MOVED) {
        eventbus.trigger('node:displayCoordinates', {
          x: parseInt(evt.coordinate[0]),
          y: parseInt(evt.coordinate[1])
        });
      }
    });

    nodeTranslate.on('translateend', function (evt) {
      var coordinates = {
        x: parseInt(evt.coordinate[0]),
        y: parseInt(evt.coordinate[1])
      };
      if (GeometryUtils.distanceBetweenPoints(selectedNodesAndJunctions.getStartingCoordinates(), coordinates) < ViiteConstants.MAX_ALLOWED_DISTANCE_FOR_NODES_TO_BE_MOVED) {
        selectedNodesAndJunctions.setCoordinates(coordinates);
        selectedNodeStartingCoordinates = coordinates;
      } else {
        var startingCoordinates = selectedNodesAndJunctions.getStartingCoordinates();
        eventbus.trigger('node:displayCoordinates', startingCoordinates);
        eventbus.trigger('node:repositionNode', selectedNodesAndJunctions.getCurrentNode(), startingCoordinates);
      }
    });

    /**
     * This will add all the following interactions from the map:
     * - nodeLayerSelectInteraction
     * - nodeTranslate
     */
    var addInteractions = function () {
      addSelectInteractions();
      if (userHasPermissionToEdit) {
        // only let the user move nodes if the user has permission to edit nodes
        addTranslateInteractions();
      }
    };

    var removeInteractions = function () {
      removeSelectInteractions();
      removeTranslateInteractions();
    };

    var toggleSelectInteractions = function (activate) {
      nodeLayerSelectInteraction.setActive(activate);
    };

    var addSelectInteractions = function () {
      map.addInteraction(nodeLayerSelectInteraction);
    };

    var removeSelectInteractions = function () {
      map.removeInteraction(nodeLayerSelectInteraction);
    };

    var addTranslateInteractions = function () {
      map.addInteraction(nodeTranslate);
    };

    var removeTranslateInteractions = function () {
      map.removeInteraction(nodeTranslate);
    };


    // Add the defined interactions to the map after userData has been fetched
    eventbus.on("userData:fetched", function (userData) {
      userHasPermissionToEdit = _.includes(userData.roles, 'viite');
      addInteractions();
    });


    var selectFeaturesToHighlight = function (vector, featuresToHighlight, otherFeatures) {
      vector.selected.clear();
      vector.selected.addFeatures(featuresToHighlight);
      vector.unselected.clear();
      vector.unselected.addFeatures(otherFeatures);
    };

    var selectNode = function (node) {
      clearHighlights();
      selectedNodesAndJunctions.closeForm();
      selectedNodesAndJunctions.openNode(node);
      highlightNode(node);
      selectedNodeStartingCoordinates = node.coordinates;
    };

    var attachNode = function (node, templates) {
      clearHighlights();
      selectedNodesAndJunctions.openNode(node, templates);
      highlightNode(selectedNodesAndJunctions.getCurrentNode());
      applicationModel.setSelectedTool(ViiteEnumerations.Tool.Select.value);
    };

    var selectNodePointTemplate = function (nodePointTemplate) {
      clearHighlights();
      selectedNodesAndJunctions.closeForm();
      selectedNodesAndJunctions.openNodePointTemplate(nodePointTemplate);
    };

    var selectJunctionTemplate = function (junctionTemplate) {
      clearHighlights();
      selectedNodesAndJunctions.closeForm();
      selectedNodesAndJunctions.openJunctionTemplate(junctionTemplate);
    };

    var addFeature = function (layer, feature, predicate) {
      if (_.isUndefined(_.find(layer.getSource().getFeatures(), predicate))) {
        layer.getSource().addFeature(feature);
      }
    };

    var highlightTemplates = function (templates) {
      if (!_.isUndefined(templates.nodePoints) && !_.isEmpty(templates.nodePoints)) {
        var nodePointTemplates = _.partition(nodePointTemplateLayer.getSource().getFeatures(), function (nodePointTemplateFeature) {
          return _.includes(_.map(templates.nodePoints, 'id'), nodePointTemplateFeature.nodePointTemplate.id);
        });
        selectFeaturesToHighlight(nodePointTemplateVector, nodePointTemplates[0], nodePointTemplates[1]);
      }

      if (!_.isUndefined(templates.junctions) && !_.isEmpty(templates.junctions)) {
        var junctionTemplates = _.partition(junctionTemplateLayer.getSource().getFeatures(), function (junctionTemplateFeature) {
          return _.includes(_.map(templates.junctions, 'id'), junctionTemplateFeature.junctionTemplate.id);
        });
        selectFeaturesToHighlight(junctionTemplateVector, junctionTemplates[0], junctionTemplates[1]);
      }

      nodePointTemplateLayer.setOpacity(0.2);
      junctionTemplateLayer.setOpacity(0.2);
    };

    var highlightNode = function (node) {
      var highlightJunctions = function () {
        var junctions = _.partition(junctionMarkerLayer.getSource().getFeatures(), function (junctionFeature) {
          return node.nodeNumber && junctionFeature.junction.nodeNumber === node.nodeNumber;
        });
        selectFeaturesToHighlight(junctionMarkerVector, junctions[0], junctions[1]);
        junctionMarkerLayer.setOpacity(0.2);
      };

      var nodes = _.partition(nodeMarkerLayer.getSource().getFeatures(), function (nodeFeature) {
        return nodeFeature.node.id === node.id;
      });

      highlightJunctions();
      highlightTemplates({
        nodePoints: _.map(_.filter(nodePointTemplateLayer.getSource().getFeatures(), function (nodePointTemplateFeature) {
          return _.includes(_.map(node.nodePoints, 'id'), nodePointTemplateFeature.nodePointTemplate.id);
        }), 'nodePointTemplate'),
        junctions: _.map(_.filter(junctionTemplateLayer.getSource().getFeatures(), function (junctionTemplate) {
          return _.includes(_.map(node.junctions, 'id'), junctionTemplate.junctionTemplate.id);
        }), 'junctionTemplate')
      });

      selectFeaturesToHighlight(nodeMarkerVector, nodes[0], nodes[1]);
      nodeMarkerLayer.setOpacity(0.2);
    };

    var clearHighlights = function () {
      var nodes = nodeMarkerLayer.getSource().getFeatures().concat(nodeMarkerSelectedLayer.getSource().getFeatures());
      var junctions = junctionMarkerLayer.getSource().getFeatures().concat(junctionMarkerSelectedLayer.getSource().getFeatures());
      var templates = {
        nodePoints: nodePointTemplateLayer.getSource().getFeatures().concat(nodePointTemplateSelectedLayer.getSource().getFeatures()),
        junctions: junctionTemplateLayer.getSource().getFeatures().concat(junctionTemplateSelectedLayer.getSource().getFeatures())
      };

      selectFeaturesToHighlight(nodeMarkerVector, [], nodes);
      selectFeaturesToHighlight(junctionMarkerVector, [], junctions);
      selectFeaturesToHighlight(nodePointTemplateVector, [], templates.nodePoints);
      selectFeaturesToHighlight(junctionTemplateVector, [], templates.junctions);

      setGeneralOpacity(1);
      nodeLayerSelectInteraction.getFeatures().clear();
    };

    me.eventListener.listenTo(eventbus, 'node:unselected', function (current, cancel) {
      if (cancel) {
        var original = nodeCollection.getNodeByNodeNumber(current.nodeNumber);
        if (original && original.nodeNumber) {
          updateCurrentNodeMarker(original);
        } else {
          removeCurrentNodeMarker(current);
        }
      }
    });

    me.eventListener.listenTo(eventbus, 'templates:selected', function (templates) {
      highlightTemplates(templates);
    });

    me.eventListener.listenTo(eventbus, 'node:unselected templates:unselected', function () {
      clearHighlights();
    });

    me.eventListener.listenTo(eventbus, 'tool:changed', function (tool) {
      toggleSelectInteractions(!applicationModel.isSelectedTool(ViiteEnumerations.Tool.Add.value));
      switch (tool) {
        case ViiteEnumerations.Tool.Unknown.value:
          me.eventListener.stopListening(eventbus, 'map:clicked', createNewNodeMarker);
          setProperty([nodeMarkerLayer], 'selectable', false);
          setProperty([nodePointTemplateLayer, junctionTemplateLayer], 'selectable', true);
          break;
        case ViiteEnumerations.Tool.Select.value:
        case ViiteEnumerations.Tool.Attach.value:
          me.eventListener.stopListening(eventbus, 'map:clicked', createNewNodeMarker);
          setProperty([nodeMarkerLayer], 'selectable', true);
          setProperty([nodePointTemplateLayer, junctionTemplateLayer], 'selectable', false);
          break;
        case ViiteEnumerations.Tool.Add.value:
          me.eventListener.listenToOnce(eventbus, 'map:clicked', createNewNodeMarker);
          setProperty([nodeMarkerLayer, nodePointTemplateLayer, junctionTemplateLayer], 'selectable', false);
          break;
        default:
          break;
      }
    });

    var createNewNodeMarker = function (coords) {
      var node = {
        coordinates: {x: parseInt(coords.x), y: parseInt(coords.y)},
        type: ViiteEnumerations.NodeType.UnknownNodeType.value,
        nodePoints: [],
        junctions: []
      };
      addFeature(nodeMarkerSelectedLayer, new NodeMarker().createNodeMarker(node),
        function (feature) {
          return feature.node.id === node.id;
        });
      attachNode(node, selectedNodesAndJunctions.getCurrentTemplates());
    };

    var removeCurrentNodeMarker = function (node) {
      _.each(nodeMarkerSelectedLayer.getSource().getFeatures(), function (nodeFeature) {
        if (_.isEqual(nodeFeature.node, node)) {
          nodeMarkerSelectedLayer.getSource().removeFeature(nodeFeature);
        }
      });
    };

    var updateCurrentNodeMarker = function (node) {
      _.each(nodeMarkerSelectedLayer.getSource().getFeatures(), function (nodeFeature) {
        if (nodeFeature.node.id === node.id) {
          nodeFeature.setProperties({type: node.type});
          nodeFeature.setProperties({name: node.name});
          nodeFeature.setGeometry(new ol.geom.Point([node.coordinates.x, node.coordinates.y]));
        }
      });

      _.each(node.nodePoints, function (nodePoint) {
        toggleNodePointToTemplate(nodePoint);
      });

      _.each(node.junctions, function (junction) {
        toggleJunctionToTemplate(junction);
      });

      _.each(junctionMarkerSelectedLayer.getSource().getFeatures(), function (junctionFeature) {
        var junction = _.find(node.junctions, function (junctionFound) {
          return junctionFound.id === junctionFeature.junction.id;
        });
        if (!_.isUndefined(junction)) {
          junctionFeature.setProperties({junctionNumber: junction.junctionNumber});
        }
      });
    };

    var addJunctionToMap = function (junction, layer) {
      if (_.has(junction, 'junctionPoints') && !_.isEmpty(junction.junctionPoints)) {
        addFeature(layer, new JunctionMarker().createJunctionMarker(junction),
          function (feature) {
            return feature.junction.id === junction.id;
          });
      }
    };

    var addJunctionTemplateToMap = function (junction, layer) {
      if (_.has(junction, 'junctionPoints') && !_.isEmpty(junction.junctionPoints)) {
        addFeature(layer, new JunctionTemplateMarker().createJunctionTemplateMarker(junction), function (feature) {
          if (feature.junctionTemplate) {
            return feature.junctionTemplate.id === junction.id;
          } else {
            return feature.junction.id === junction.id;
          }
        });
      }
    };


    var toggleJunctionToTemplate = function (junction, toTemplate) {
      if (toTemplate) {
        _.each(junctionMarkerSelectedLayer.getSource().getFeatures(), function (junctionFeature) {
          if (_.isEqual(junctionFeature.junction, junction)) {
            junctionMarkerSelectedLayer.getSource().removeFeature(junctionFeature);
          }
        });
        addJunctionTemplateToMap(junction, junctionTemplateSelectedLayer);
      } else {
        _.each(junctionTemplateSelectedLayer.getSource().getFeatures(), function (junctionFeature) {
          if (_.isEqual(junctionFeature.junctionTemplate, junction)) {
            junctionTemplateSelectedLayer.getSource().removeFeature(junctionFeature);
          }
        });
        addJunctionToMap(junction, junctionMarkerSelectedLayer);
      }
    };

    var toggleNodePointToTemplate = function (nodePoint, toTemplate) {
      if (toTemplate) {
        addFeature(nodePointTemplateSelectedLayer, new NodePointTemplateMarker().createNodePointTemplateMarker(nodePoint),
          function (feature) {
            return feature.nodePointTemplate.id === nodePoint.id;
          });
      } else {
        var nodePointTemplateFeature = _.find(nodePointTemplateSelectedLayer.getSource().getFeatures(), function (feature) {
          return feature.nodePointTemplate.id === nodePoint.id;
        });
        if (!_.isUndefined(nodePointTemplateFeature)) {
          nodePointTemplateSelectedLayer.getSource().removeFeature(nodePointTemplateFeature);
        }
      }
    };

    me.eventListener.listenTo(eventbus, 'junction:mapNumberUpdate', function (junction) {
      var updateJunctionTemplateNumberOnMap = function (junctionToUpdate) {
        _.each(junctionTemplateSelectedLayer.getSource().getFeatures(), function (junctionFeature) {
          if (_.isEqual(junctionFeature.junctionTemplate.id, junctionToUpdate.id)) {
            junctionFeature.setProperties({junctionNumber: junctionToUpdate.junctionNumber});
          }
        });
      };

      var updateJunctionNumberOnMap = function (junctionToMap) {
        _.each(junctionMarkerSelectedLayer.getSource().getFeatures(), function (junctionFeature) {
          if (_.isEqual(junctionFeature.junction.id, junctionToMap.id)) {
            junctionFeature.setProperties({junctionNumber: junctionToMap.junctionNumber});
          }
        });
      };

      if (!_.isUndefined(junction)) {
        if (_.isUndefined(junction.nodeNumber)) {
          updateJunctionTemplateNumberOnMap(junction);
        } else {
          updateJunctionNumberOnMap(junction);
        }
      }
    });

    me.eventListener.listenTo(eventbus, 'junction:detach', function (junction) {
      if (!_.isUndefined(junction)) {
        toggleJunctionToTemplate(junction, true);
      }
    });

    me.eventListener.listenTo(eventbus, 'junction:attach', function (junction) {
      if (!_.isUndefined(junction)) {
        toggleJunctionToTemplate(junction);
      }
    });

    me.eventListener.listenTo(eventbus, 'nodePoint:detach', function (nodePointToDetach) {
      if (!_.isUndefined(nodePointToDetach)) {
        toggleNodePointToTemplate(nodePointToDetach, true);
      }
    });

    me.eventListener.listenTo(eventbus, 'nodePoint:attach', function (nodePointToAttach) {
      if (!_.isUndefined(nodePointToAttach)) {
        toggleNodePointToTemplate(nodePointToAttach);
      }
    });

    me.eventListener.listenTo(eventbus, 'nodeLayer:fetch', function (callback) {
      map.getView().setZoom(Math.round(zoomlevels.getViewZoom(map)));
      roadCollection.fetchWithNodes(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1, callback);
    });

    me.eventListener.listenTo(eventbus, 'nodeLayer:refreshView', function () {
      applicationModel.refreshMap(zoomlevels.getViewZoom(map), map.getLayers().getArray()[0].getExtent(), map.getView().getCenter());
    });

    me.eventListener.listenTo(eventbus, 'node:repositionNode', function (node, coordinates) {
      _.each(nodeMarkerSelectedLayer.getSource().getFeatures(), function (nodeFeature) {
        if (nodeFeature.node.id === node.id) {
          nodeFeature.setGeometry(new ol.geom.Point([coordinates.x, coordinates.y]));
        }
      });
      return false;
    });

    me.eventListener.listenTo(eventbus, 'layer:selected', function (layer, previouslySelectedLayer) {
      toggleSelectInteractions(layer === 'node');
      if (previouslySelectedLayer === 'node') {
        hideLayer();
        removeInteractions();
      } else if (layer === 'node') {
        setGeneralOpacity(1);
        addInteractions();
        eventbus.trigger('nodeLayer:fetch');
      }
    });

    this.refreshView = function () {
      //Generalize the zoom levels as the resolutions and zoom levels differ between map tile sources
      roadCollection.reset();
      roadCollection.fetchWithNodes(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map));
      roadLayer.layer.changed();
    };

    this.layerStarted = function (eventListener) {
      eventListener.listenTo(eventbus, 'roadLinks:fetched', function () {
        if (applicationModel.getSelectedLayer() === 'node') {
          me.clearLayers(layers);
        }
      });

      eventListener.listenTo(eventbus, 'node:addNodesToMap', function (nodes, templates, zoom) {
        var filteredNodes = nodes;
        var currentNode = selectedNodesAndJunctions.getCurrentNode();
        var currentTemplates = selectedNodesAndJunctions.getCurrentTemplates();

        if (parseInt(zoom) >= zoomlevels.minZoomForNodes) {
          var filteredNodePointTemplates = templates.nodePoints;

          if (currentNode) {
            eventbus.trigger('node:fetchCoordinates', nodeCollection.getNodeByNodeNumber(currentNode.nodeNumber));
            filteredNodes = _.filter(nodes, function (node) {
              return node.id !== currentNode.id;
            });

            filteredNodePointTemplates = _.filter(templates.nodePoints, function (nodePoint) {
              return !_.includes(_.map(currentNode.nodePoints, 'id'), nodePoint.id);
            });

            addFeature(nodeMarkerSelectedLayer, new NodeMarker().createNodeMarker(currentNode),
              function (feature) {
                return feature.node.id === currentNode.id;
              });

            _.each(_.filter(currentNode.nodePoints, function (nodePoint) {
              return _.isUndefined(nodePoint.nodeNumber);
            }), function (nodePointTemplate) {
              addFeature(nodePointTemplateSelectedLayer, new NodePointTemplateMarker().createNodePointTemplateMarker(nodePointTemplate),
                function (feature) {
                  return feature.nodePointTemplate.id === nodePointTemplate.id;
                });
            });
          }

          if (_.has(currentTemplates, 'nodePoints')) {
            _.each(currentTemplates.nodePoints, function (nodePointTemplate) {
              addFeature(nodePointTemplateSelectedLayer, new NodePointTemplateMarker().createNodePointTemplateMarker(nodePointTemplate),
                function (feature) {
                  return feature.nodePointTemplate.id === nodePointTemplate.id;
                });
            });
          }

          _.each(filteredNodes, function (node) {
            addFeature(nodeMarkerLayer, new NodeMarker().createNodeMarker(node),
              function (feature) {
                return feature.node.id === node.id;
              });
          });

          _.each(filteredNodePointTemplates, function (nodePointTemplate) {
            addFeature(nodePointTemplateLayer, new NodePointTemplateMarker().createNodePointTemplateMarker(nodePointTemplate),
              function (feature) {
                return feature.nodePointTemplate.id === nodePointTemplate.id;
              });
          });
        }

        if (parseInt(zoom) >= zoomlevels.minZoomForJunctions) {

          var filteredJunctions = _.flatten(_.map(filteredNodes, "junctions"));
          var filteredJunctionTemplates = templates.junctions;

          if (currentNode) {
            var currentJunctions = _.partition(currentNode.junctions, function (junction) {
              return _.isUndefined(junction.nodeNumber);
            });

            _.each(currentJunctions[0], function (junction) {
              addJunctionTemplateToMap(junction, junctionTemplateSelectedLayer);
            });

            _.each(currentJunctions[1], function (junction) {
              addJunctionToMap(junction, junctionMarkerSelectedLayer);
            });
          }

          if (_.has(currentTemplates, 'junctions')) {
            filteredJunctionTemplates = _.filter(templates.junctions, function (junctionTemplate) {
              return !_.includes(_.map(currentTemplates.junctions, 'id'), junctionTemplate.id);
            });

            _.each(currentTemplates.junctions, function (junctionTemplate) {
              addJunctionTemplateToMap(junctionTemplate, junctionTemplateSelectedLayer);
            });
          }

          _.each(filteredJunctions, function (junction) {
            addJunctionToMap(junction, junctionMarkerLayer);
          });

          _.each(filteredJunctionTemplates, function (junctionTemplate) {
            addJunctionTemplateToMap(junctionTemplate, junctionTemplateLayer);
          });
        }
      });

      eventListener.listenTo(eventbus, 'roadLinks:refreshView', function () {
        me.refreshView();
      });

      eventListener.listenTo(eventbus, 'map:clearLayers', me.clearLayers);

      eventListener.listenTo(eventbus, 'change:node', function (node, _junction) {
        updateCurrentNodeMarker(node);
      });
    };

    var showLayer = function () {
      me.start();
      me.layerStarted(me.eventListener);
      me.toggleLayersVisibility(layers, true);
    };

    var hideLayer = function () {
      me.clearLayers(layers);
      me.toggleLayersVisibility(layers, false);
    };

    me.addLayers(layers);

    return {
      show: showLayer,
      hide: hideLayer,
      minZoomForContent: me.minZoomForContent
    };
  };

}(this));
