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

    var setGeneralOpacityForTemplates = function (opacity) {
      nodePointTemplateLayer.setOpacity(opacity);
      junctionTemplateLayer.setOpacity(opacity);
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
      // Multi is the one en charge of defining if we select just the feature we clicked or all the overlapping
      multi: true,
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
      var selectedNodePointTemplates = _.filter(event.selected, function (selectionTarget) {
        return !_.isUndefined(selectionTarget.nodePointTemplate);
      });

      var selectedJunctionTemplate = _.find(event.selected, function (selectionTarget) {
        return !_.isUndefined(selectionTarget.junctionTemplate);
      });

      switch (applicationModel.getSelectedTool()) {
        case LinkValues.Tool.Unknown.value:
          if (!_.isUndefined(selectedJunctionTemplate) && !_.isUndefined(selectedJunctionTemplate.junctionTemplate)) {
            selectJunctionTemplate(selectedJunctionTemplate.junctionTemplate);
          } else if (!_.isUndefined(selectedNodePointTemplates) && selectedNodePointTemplates.length > 0) {
            selectNodePointTemplates(_.map(selectedNodePointTemplates, "nodePointTemplate"));
          }
          break;
        case LinkValues.Tool.Select.value:
          if (!_.isUndefined(selectedNode) && !_.isUndefined(selectedNode.node)) {
            selectNode(selectedNode.node);
          }
          break;
      }
    });

    /**
     * This will add all the following interactions from the map:
     * - nodeLayerSelectInteraction
     */
    var toggleSelectInteractions = function (activate) {
      nodeLayerSelectInteraction.setActive(activate);
    };

    var addSelectInteractions = function () {
      map.addInteraction(nodeLayerSelectInteraction);
    };

    var removeSelectInteractions = function () {
      map.removeInteraction(nodeLayerSelectInteraction);
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
      selectedNodesAndJunctions.closeForm();
      clearHighlights();
      selectedNodesAndJunctions.openNode(node);
      highlightNode(node);
    };

    var selectNodePointTemplates = function(nodePointTemplates) {
      selectedNodesAndJunctions.closeForm();
      clearHighlights();
      selectedNodesAndJunctions.openNodePointTemplates(nodePointTemplates);
    };

    var selectJunctionTemplate = function (junctionTemplate) {
      selectedNodesAndJunctions.closeForm();
      clearHighlights();
      selectedNodesAndJunctions.openJunctionTemplate(junctionTemplate);
    };

    var addFeature = function(layer, feature, predicate) {
      if (_.isUndefined(_.find(layer.getSource().getFeatures(), predicate))) {
        layer.getSource().addFeature(feature);
      }
    };

    var highlightNode = function (node) {
      var highlightJunctions = function(nodeNumber) {
        var junctions = _.partition(junctionMarkerLayer.getSource().getFeatures(), function (junctionFeature) {
          return junctionFeature.junction.nodeNumber === nodeNumber;
        });

        selectFeaturesToHighlight(junctionMarkerVector, junctions[0], junctions[1]);

        junctionMarkerLayer.setOpacity(0.2);
      };

      var nodes = _.partition(nodeMarkerLayer.getSource().getFeatures(), function (nodeFeature) {
        return nodeFeature.node.id === node.id;
      });

      selectFeaturesToHighlight(nodeMarkerVector, nodes[0], nodes[1]);
      var nodeNumber = _.uniq(_.map(nodes[0], "node.nodeNumber"));
      if (nodeNumber.length === 1) {
        highlightJunctions(nodeNumber[0]);
      }

      nodeMarkerLayer.setOpacity(0.2);
      setGeneralOpacityForTemplates(0.2);
    };

    var clearHighlights = function () {
      var nodes = nodeMarkerLayer.getSource().getFeatures().concat(nodeMarkerSelectedLayer.getSource().getFeatures());
      var junctions = junctionMarkerLayer.getSource().getFeatures().concat(junctionMarkerSelectedLayer.getSource().getFeatures());

      selectFeaturesToHighlight(nodeMarkerVector, [], nodes);
      selectFeaturesToHighlight(junctionMarkerVector, [], junctions);

      setGeneralOpacity(1);
      nodeLayerSelectInteraction.getFeatures().clear();
    };

    me.eventListener.listenTo(eventbus, 'node:unselected', function (node) {
      if (!_.isUndefined(node)) {
        removeCurrentNodeMarker(node);
      }
    });

    me.eventListener.listenTo(eventbus, 'node:unselected nodePointTemplate:unselected junctionTemplate:unselected', function () {
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
          toggleSelectInteractions(false);
          me.eventListener.listenToOnce(eventbus, 'map:clicked', createNewNodeMarker);
          if (!_.isUndefined(selectedNodesAndJunctions.getCurrentNode()) || !_.isUndefined(selectedNodesAndJunctions.getCurrentNodePointTemplates()) || !_.isUndefined(selectedNodesAndJunctions.getCurrentJunctionTemplate())) {
            selectedNodesAndJunctions.closeForm();
          }
          setProperty([nodeMarkerLayer, nodePointTemplateLayer, junctionTemplateLayer], 'selectable', false);
          break;
      }
    });

    var createNewNodeMarker = function (coords) {
      var node = {
        coordinates:  { x: parseInt(coords.x), y: parseInt(coords.y) },
        type:         LinkValues.NodeType.UnknownNodeType.value
      };
      addFeature(nodeMarkerSelectedLayer, new NodeMarker().createNodeMarker(node),
        function (feature) { return feature.node.id === node.id; });
      selectNode(node);
      applicationModel.setSelectedTool(LinkValues.Tool.Unknown.value);
    };

    var removeCurrentNodeMarker = function (node) {
      _.each(nodeMarkerSelectedLayer.getSource().getFeatures(), function (nodeFeature) {
        if (_.isEqual(nodeFeature.node, node)) {
          nodeMarkerSelectedLayer.getSource().removeFeature(nodeFeature);
        }
      });
    };

    var updateCurrentNodeMarker = function (node) {
      if (!_.isUndefined(node)) {
        _.each(nodeMarkerSelectedLayer.getSource().getFeatures(), function (nodeFeature) {
          if (_.isEqual(nodeFeature.node, node)) {
            nodeFeature.setProperties({type: node.type});
          }
        });
      }
    };

    var roadLinkForPoint = function (predicate) {
      var roadLinksWithValues = _.reject(roadCollection.getAll(), function (rl) {
        return rl.roadNumber === 0;
      });

      return _.find(roadLinksWithValues, predicate);
    };

    var addJunctionToMap = function (junction, isTemplate, selectedLayer) {
      var refJunctionPoint = {};
      var roadLink = roadLinkForPoint(function (roadLink) {
        refJunctionPoint = _.find(junction.junctionPoints, function (junctionPoint) {
          return (roadLink.startAddressM === junctionPoint.addrM || roadLink.endAddressM === junctionPoint.addrM) && roadLink.roadwayNumber === junctionPoint.roadwayNumber;
        });
        return !_.isUndefined(refJunctionPoint);
      });
      if (!_.isUndefined(roadLink) && !_.isUndefined(refJunctionPoint)) {
        if (_.isUndefined(junction.nodeNumber) || isTemplate) {
          addFeature(selectedLayer || junctionTemplateLayer, new JunctionTemplateMarker().createJunctionTemplateMarker(junction, refJunctionPoint, roadLink, roadLinkForPoint, nodeCollection.getCoordinates),
            function (feature) { return feature.junctionTemplate.id === junction.id; });
        } else {
          addFeature(selectedLayer || junctionMarkerLayer, new JunctionMarker().createJunctionMarker(junction, refJunctionPoint, roadLink),
            function (feature) { return feature.junction.id === junction.id; });
        }
      }
    };

    var toggleJunctionToTemplate = function (junction, toTemplate) {
      var roadLink = {};
      if (toTemplate) {
        _.each(junctionMarkerSelectedLayer.getSource().getFeatures(), function (junctionFeature) {
          if (_.isEqual(junctionFeature.junction, junction)) {
            roadLink = junctionFeature.roadLink;
            junctionMarkerSelectedLayer.getSource().removeFeature(junctionFeature);
          }
        });
        if (!_.isUndefined(roadLink)) {
          addJunctionToMap(junction, toTemplate, junctionTemplateSelectedLayer);
        }
      } else {
        _.each(junctionTemplateSelectedLayer.getSource().getFeatures(), function (junctionFeature) {
          if (_.isEqual(junctionFeature.junctionTemplate, junction)) {
            roadLink = junctionFeature.roadLink;
            junctionTemplateSelectedLayer.getSource().removeFeature(junctionFeature);
          }
        });
        if (!_.isUndefined(roadLink)) {
          addJunctionToMap(junction, toTemplate, junctionMarkerSelectedLayer);
        }
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

    me.eventListener.listenTo(eventbus, 'junction:detach', function (junctionToDetach) {
      if (!_.isUndefined(junctionToDetach)) {
        toggleJunctionToTemplate(junctionToDetach, true);
      }
    });
    
    me.eventListener.listenTo(eventbus, 'junction:attach', function (junctionToAttach) {
      if (!_.isUndefined(junctionToAttach)) {
        toggleJunctionToTemplate(junctionToAttach);
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

    me.eventListener.listenTo(eventbus, 'layer:selected', function (layer, previouslySelectedLayer) {
      toggleSelectInteractions(layer === 'node');
      if (previouslySelectedLayer === 'node') {
        hideLayer();
        removeSelectInteractions();
      } else if (layer === 'node') {
        setGeneralOpacity(1);
        addSelectInteractions();
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

      eventListener.listenTo(eventbus, 'node:addNodesToMap', function(nodes, nodePointTemplates, junctionTemplates, zoom) {
        var selectedNode = selectedNodesAndJunctions.getCurrentNode();

        if (parseInt(zoom, 10) >= zoomlevels.minZoomForNodes) {
          if (!_.isUndefined(selectedNode) && _.isUndefined(selectedNode.id)) {
            // adds node created by user which isn't saved yet.
            addFeature(nodeMarkerSelectedLayer, new NodeMarker().createNodeMarker(selectedNode, roadLinkForPoint, nodeCollection.getCoordinates),
              function (feature) { return feature.node.id === selectedNode.id; });
          }

          _.each(nodes, function (node) {
            addFeature(nodeMarkerLayer, new NodeMarker().createNodeMarker(node, roadLinkForPoint, nodeCollection.getCoordinates),
              function (feature) { return feature.node.id === node.id; });
          });

          if (!_.isUndefined(selectedNode)) {
            _.remove(nodes, function (node) {
              return node.nodeNumber === selectedNode.nodeNumber;
            });
          }

          _.each(nodePointTemplates, function (nodePointTemplate) {
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
          if (!_.isUndefined(selectedNode)) {
            _.each(selectedNode.junctions, function (junction) {
              addJunctionToMap(junction, false, junctionMarkerSelectedLayer);
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
          if (!_.isUndefined(selectedNode.junctionsToDetach) && selectedNode.junctionsToDetach.length > 0) {
            _.each(selectedNode.junctionsToDetach, function (junctionToDetach) {
              toggleJunctionToTemplate(junctionToDetach, true);
            });
          }

          if (!_.isUndefined(selectedNode.nodePointsToDetach) && selectedNode.nodePointsToDetach.length > 0) {
            _.each(selectedNode.nodePointsToDetach, function (nodePointToDetach) {
              toggleNodePointToTemplate(nodePointToDetach, true);
            });
          }
        }
      });

      eventListener.listenTo(eventbus, 'roadLinks:refreshView', function () {
        me.refreshView();
      });

      eventListener.listenTo(eventbus, 'map:clearLayers', me.clearLayers);

      eventListener.listenTo(eventbus, 'changed:NodeType', function (node) {
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
