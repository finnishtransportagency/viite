(function(root) {
  root.NodeLayer = function (map, roadLayer, selectedNodesAndJunctions, nodeCollection, roadCollection, linkPropertiesModel, applicationModel) {
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
      zIndex: RoadZIndex.CalibrationPointLayer.value + 1,
      selectable: false
    });

    var junctionTemplateSelectedLayer = new ol.layer.Vector({
      source: junctionTemplateVector.selected,
      name: 'junctionTemplateSelectedLayer',
      zIndex: RoadZIndex.CalibrationPointLayer.value + 1
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
      nodeLayerSelectInteraction.setMap(null); // stop openLayers from using internal unmanaged layer
      var selectedNode = _.find(event.selected, function (selectionTarget) {
        return !_.isUndefined(selectionTarget.node);
      });

      // select all node point templates in same place.
      var selectedNodePointTemplate = _.find(event.selected, function (selectionTarget) {
        return !_.isUndefined(selectionTarget.nodePointTemplate);
      });

      var selectedJunctionTemplate = _.find(event.selected, function (selectionTarget) {
        return !_.isUndefined(selectionTarget.junctionTemplate);
      });

      switch (applicationModel.getSelectedTool()) {
        case LinkValues.Tool.Unknown.value:
          if (!_.isUndefined(selectedJunctionTemplate) && _.has(selectedJunctionTemplate, 'junctionTemplate')) {
            selectJunctionTemplate(selectedJunctionTemplate.junctionTemplate);
          } else if (!_.isUndefined(selectedNodePointTemplate) && _.has(selectedNodePointTemplate, 'nodePointTemplate')) {
            selectNodePointTemplate(selectedNodePointTemplate.nodePointTemplate);
          }
          break;
        case LinkValues.Tool.Select.value:
          if (!_.isUndefined(selectedNode) && !_.isUndefined(selectedNode.node)) {
            selectNode(selectedNode.node);
          }
          break;
        case LinkValues.Tool.Attach.value:
          if (!_.isUndefined(selectedNode) && !_.isUndefined(selectedNode.node)) {
            attachNode(selectedNode.node, selectedNodesAndJunctions.getCurrentTemplates());
          }
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
      selectedNodesAndJunctions.setStartingCoordinates({
        x: parseInt(evt.coordinate[0]),
        y: parseInt(evt.coordinate[1])
      });
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
      if (GeometryUtils.distanceBetweenPoints(selectedNodesAndJunctions.getStartingCoordinates(), coordinates) < LinkValues.MaxAllowedDistanceForNodesToBeMoved) {
        eventbus.trigger('node:setCoordinates', {
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
      if (GeometryUtils.distanceBetweenPoints(selectedNodesAndJunctions.getStartingCoordinates(), coordinates) < LinkValues.MaxAllowedDistanceForNodesToBeMoved) {
        selectedNodesAndJunctions.setCoordinates(coordinates);
      } else {
        var startingCoordinates = selectedNodesAndJunctions.getStartingCoordinates();
        eventbus.trigger('node:setCoordinates', startingCoordinates);
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
      addTranslateInteractions();
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

    // We add the defined interactions to the map.
    addInteractions();

    var selectFeaturesToHighlight = function (vector, featuresToHighlight, otherFeatures) {
      vector.selected.clear();
      vector.selected.addFeatures(featuresToHighlight);
      vector.unselected.clear();
      vector.unselected.addFeatures(otherFeatures);
    };

    var selectNode = function(node) {
      clearHighlights();
      selectedNodesAndJunctions.closeForm();
      selectedNodesAndJunctions.openNode(node);
      highlightNode(node);
    };

    var attachNode = function (node, templates) {
      clearHighlights();
      selectedNodesAndJunctions.openNode(node, templates);
      highlightNode(selectedNodesAndJunctions.getCurrentNode());
      applicationModel.setSelectedTool(LinkValues.Tool.Select.value);
    };

    var selectNodePointTemplate = function(nodePointTemplate) {
      clearHighlights();
      selectedNodesAndJunctions.closeForm();
      selectedNodesAndJunctions.openNodePointTemplate(nodePointTemplate);
    };

    var selectJunctionTemplate = function (junctionTemplate) {
      clearHighlights();
      selectedNodesAndJunctions.closeForm();
      selectedNodesAndJunctions.openJunctionTemplate(junctionTemplate);
    };

    var addFeature = function(layer, feature, predicate) {
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
        nodePointTemplateLayer.setOpacity(0.2);
      }

      if (!_.isUndefined(templates.junctions) && !_.isEmpty(templates.junctions)) {
        var junctionTemplates = _.partition(junctionTemplateLayer.getSource().getFeatures(), function (junctionTemplateFeature) {
          return _.includes(_.map(templates.junctions, 'id'), junctionTemplateFeature.junctionTemplate.id);
        });
        selectFeaturesToHighlight(junctionTemplateVector, junctionTemplates[0], junctionTemplates[1]);
        junctionTemplateLayer.setOpacity(0.2);
      }
    };

    var highlightNode = function (node) {
      var highlightJunctions = function() {
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

    me.eventListener.listenTo(eventbus, 'node:unselected', function (current) {
      var original = nodeCollection.getNodeByNodeNumber(current.nodeNumber);
      if (original && original.nodeNumber) {
        updateCurrentNodeMarker(original, current.junctions);
      } else {
        removeCurrentNodeMarker(current);
      }
    });

    me.eventListener.listenTo(eventbus, 'nodePointTemplates:selected junctionTemplates:selected', function (templates) {
      highlightTemplates(templates);
    });

    me.eventListener.listenTo(eventbus, 'node:unselected templates:unselected', function () {
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
        case LinkValues.Tool.Attach.value:
          me.eventListener.stopListening(eventbus, 'map:clicked', createNewNodeMarker);
          setProperty([nodeMarkerLayer], 'selectable', true);
          setProperty([nodePointTemplateLayer, junctionTemplateLayer], 'selectable', false);
          break;
        case LinkValues.Tool.Add.value:
          toggleSelectInteractions(false);
          me.eventListener.listenToOnce(eventbus, 'map:clicked', createNewNodeMarker);
          setProperty([nodeMarkerLayer, nodePointTemplateLayer, junctionTemplateLayer], 'selectable', false);
          break;
      }
    });

    var createNewNodeMarker = function (coords) {
      var node = {
        coordinates:  { x: parseInt(coords.x), y: parseInt(coords.y) },
        type:         LinkValues.NodeType.UnknownNodeType.value,
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

    var updateCurrentNodeMarker = function (node, junctions) {
      if (!_.isUndefined(node)) {
        _.each(nodeMarkerSelectedLayer.getSource().getFeatures(), function (nodeFeature) {
          if (nodeFeature.node.id === node.id) {
            nodeFeature.setProperties({type: node.type});
            nodeFeature.setProperties({name: node.name});
            nodeFeature.setGeometry(new ol.geom.Point([node.coordinates.x, node.coordinates.y]));
          }
        });
        _.each(junctionMarkerSelectedLayer.getSource().getFeatures(), function (junctionFeature) {
          var junction = _.find(node.junctions, function (junction) {
            return junctionFeature.junction.id === junction.id;
          });
          if (!_.isUndefined(junction)) {
            junctionFeature.setProperties({junctionNumber: junction.junctionNumber});
          }
        });

        if (!_.isUndefined(junctions)) {
          _.each(junctionTemplateSelectedLayer.getSource().getFeatures(), function (junctionTemplateFeature) {
            var junctionTemplate = _.find(junctions, function (junctionTemplate) {
              return junctionTemplateFeature.junctionTemplate.id === junctionTemplate.id;
            });
            if (!_.isUndefined(junctionTemplate)) {
              junctionTemplateFeature.setProperties({junctionNumber: undefined});
            }
          });
        }
      }
    };

    var roadLinkForPoint = function (predicate) {
      var roadLinksWithValues = _.reject(roadCollection.getAll(), function (rl) {
        return rl.roadNumber === 0;
      });

      return _.find(roadLinksWithValues, predicate);
    };

    var addJunctionToMap = function (junction, layer) {
      var refJunctionPoint = {};
      var roadLink = roadLinkForPoint(function (roadLink) {
        refJunctionPoint = _.find(junction.junctionPoints, function (junctionPoint) {
          return (roadLink.startAddressM === junctionPoint.addrM || roadLink.endAddressM === junctionPoint.addrM) && roadLink.roadwayNumber === junctionPoint.roadwayNumber;
        });
        return !_.isUndefined(refJunctionPoint);
      });
      if (!_.isUndefined(roadLink) && !_.isUndefined(refJunctionPoint)) {
        addFeature(layer, new JunctionMarker().createJunctionMarker(junction, refJunctionPoint, roadLink),
            function (feature) { return feature.junction.id === junction.id; });
      }
    };

    var addJunctionTemplateToMap = function (junction, layer) {
      var refJunctionPoint = {};
      var roadLink = roadLinkForPoint(function (roadLink) {
        refJunctionPoint = _.find(junction.junctionPoints, function (junctionPoint) {
          return (roadLink.startAddressM === junctionPoint.addrM || roadLink.endAddressM === junctionPoint.addrM) && roadLink.roadwayNumber === junctionPoint.roadwayNumber;
        });
        return !_.isUndefined(refJunctionPoint);
      });
      if (!_.isUndefined(roadLink) && !_.isUndefined(refJunctionPoint)) {
        addFeature(layer, new JunctionTemplateMarker().createJunctionTemplateMarker(junction, refJunctionPoint, roadLink), function (feature) {
          if (!_.isUndefined(feature.junctionTemplate)) {
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
      var roadLink = roadLinkForPoint(function (roadLink) {
        return (roadLink.startAddressM === nodePoint.addrM || roadLink.endAddressM === nodePoint.addrM) && roadLink.roadwayNumber === nodePoint.roadwayNumber;
      });
      if (!_.isUndefined(roadLink)) {
        if (toTemplate) {
          addFeature(nodePointTemplateSelectedLayer, new NodePointTemplateMarker().createNodePointTemplateMarker(nodePoint, roadLink),
            function (feature) { return feature.nodePointTemplate.id === nodePoint.id; });
        } else {
          nodePointTemplateSelectedLayer.getSource().removeFeature(_.find(nodePointTemplateSelectedLayer.getSource().getFeatures(), function (feature) {
            return feature.nodePointTemplate.id === nodePoint.id;
          }));
        }
      }
    };

    me.eventListener.listenTo(eventbus, 'junction:mapNumberUpdate', function (junction) {
      var updateJunctionTemplateNumberOnMap = function (junction) {
        _.each(junctionTemplateSelectedLayer.getSource().getFeatures(), function (junctionFeature) {
          if (_.isEqual(junctionFeature.junctionTemplate.id, junction.id)) {
            junctionFeature.setProperties({junctionNumber: junction.junctionNumber});
          }
        });
      };

      var updateJunctionNumberOnMap = function (junction) {
        _.each(junctionMarkerSelectedLayer.getSource().getFeatures(), function (junctionFeature) {
          if (_.isEqual(junctionFeature.junction.id, junction.id)) {
            junctionFeature.setProperties({junctionNumber: junction.junctionNumber});
          }
        });
      };

      if(!_.isUndefined(junction)) {
        if (_.isUndefined(junction.nodeNumber)) { updateJunctionTemplateNumberOnMap(junction); }
        else { updateJunctionNumberOnMap(junction); }
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

    me.eventListener.listenTo(eventbus, 'nodeLayer:fetch', function () {
      map.getView().setZoom(Math.round(zoomlevels.getViewZoom(map)));
      roadCollection.fetchWithNodes(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1);
    });

    me.eventListener.listenTo(eventbus, 'nodeLayer:refreshView', function () {
      toggleSelectInteractions(!applicationModel.isSelectedTool(LinkValues.Tool.Add.value));
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
        showLayer();
        eventbus.trigger('nodeLayer:fetch');
      }
    });

    var redraw = function () {
      if (applicationModel.getSelectedLayer() === 'node') {

        cachedMarker = new LinkPropertyMarker();
        var underConstructionLinks = roadCollection.getUnderConstructionLinks();
        var roadLinks = _.reject(roadCollection.getAll(), function (rl) {
          return _.includes(_.map(underConstructionLinks, function (sl) {
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

      eventListener.listenTo(eventbus, 'node:addNodesToMap', function(nodes, templates, zoom) {
        var filteredNodes = nodes;
        var currentNode = selectedNodesAndJunctions.getCurrentNode();
        var currentTemplates = selectedNodesAndJunctions.getCurrentTemplates();

        if (parseInt(zoom, 10) >= zoomlevels.minZoomForNodes) {
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
                function (feature) { return feature.node.id === currentNode.id; });

            _.each(_.filter(currentNode.nodePoints, function (nodePoint) {
              return _.isUndefined(nodePoint.nodeNumber);
            }), function (nodePointTemplate) {
              var roadLink = roadLinkForPoint(function (roadLink) {
                return (roadLink.startAddressM === nodePointTemplate.addrM || roadLink.endAddressM === nodePointTemplate.addrM) && roadLink.roadwayNumber === nodePointTemplate.roadwayNumber;
              });

              if (!_.isUndefined(roadLink)) {
                addFeature(nodePointTemplateSelectedLayer, new NodePointTemplateMarker().createNodePointTemplateMarker(nodePointTemplate, roadLink),
                  function (feature) { return feature.nodePointTemplate.id === nodePointTemplate.id; });
              }
            });
          }

          if (_.has(currentTemplates, 'nodePoints')) {
            _.each(currentTemplates.nodePoints, function (nodePointTemplate) {
              var roadLink = roadLinkForPoint(function (roadLink) {
                return (roadLink.startAddressM === nodePointTemplate.addrM || roadLink.endAddressM === nodePointTemplate.addrM) && roadLink.roadwayNumber === nodePointTemplate.roadwayNumber;
              });

              if (!_.isUndefined(roadLink)) {
                addFeature(nodePointTemplateSelectedLayer, new NodePointTemplateMarker().createNodePointTemplateMarker(nodePointTemplate, roadLink),
                  function (feature) { return feature.nodePointTemplate.id === nodePointTemplate.id; });
              }
            });
          }

          _.each(filteredNodes, function (node) {
            addFeature(nodeMarkerLayer, new NodeMarker().createNodeMarker(node),
                function (feature) { return feature.node.id === node.id; });
          });

          _.each(filteredNodePointTemplates, function (nodePointTemplate) {
            var roadLink = roadLinkForPoint(function (roadLink) {
              return (roadLink.startAddressM === nodePointTemplate.addrM || roadLink.endAddressM === nodePointTemplate.addrM) && roadLink.roadwayNumber === nodePointTemplate.roadwayNumber;
            });

            if (!_.isUndefined(roadLink)) {
              addFeature(nodePointTemplateLayer, new NodePointTemplateMarker().createNodePointTemplateMarker(nodePointTemplate, roadLink),
                  function (feature) { return feature.nodePointTemplate.id === nodePointTemplate.id; });
            }
          });
        }

        if (parseInt(zoom, 10) >= zoomlevels.minZoomForJunctions) {

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

      eventListener.listenTo(eventbus, 'change:node', function (node) {
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

})(this);
