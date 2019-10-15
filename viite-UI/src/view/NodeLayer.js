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
              selectJunctionTemplate(selectedJunctions);
            } else if (!_.isUndefined(selectedNodePointTemplates) && selectedNodePointTemplates.length > 0) {
              selectNodePointTemplate(selectedNodePointTemplates);
            }
            break;
          case LinkValues.Tool.Select.value:
            if (!_.isUndefined(selectedNodes) && selectedNodes.length > 0) { selectNode(selectedNodes); }
            break;
        }
      });

      var selectNode = function(selectedNodes) {
        // eventbus.trigger('selectedNodeAndJunctionPoint:close');
        var node = _.first(_.unique(_.map(selectedNodes, "nodeInfo"), "id"));
        selectedNodeAndJunctionPoint.openNode(node);
        clearHighlights();
        highlightNode(node.id);

        setOpacityFor([
          nodePointTemplateLayer,
          junctionTemplateLayer],
          0.2);
      };

      var selectNodePointTemplate = function(selectedNodePointTemplates) {
        // eventbus.trigger('selectedNodeAndJunctionPoint:close');
        selectedNodeAndJunctionPoint.openNodePointTemplate(_.unique(_.map(selectedNodePointTemplates, "nodePointTemplateInfo"), "id"));
      };

      var selectJunctionTemplate = function (selectedJunctionTemplates) {
        // eventbus.trigger('selectedNodeAndJunctionPoint:close');
        selectedNodeAndJunctionPoint.openJunctionTemplate(_.first(_.unique(_.map(selectedJunctionTemplates, "junctionTemplateInfo"), "junctionId")));
      };

      var selectFeaturesToHighlight = function (vector, featuresToHighlight, otherFeatures) {
        vector.selected.clear();
        vector.selected.addFeatures(featuresToHighlight);
        vector.unselected.clear();
        vector.unselected.addFeatures(otherFeatures);
      };

      var highlightNode = function (nodeId) {
        var highlightJunctions = function(nodeId) {
          var junctions = _.partition(junctionMarkerLayer.getSource().getFeatures(), function (junctionFeature) {
            return junctionFeature.junction.nodeId === nodeId;
          });

          selectFeaturesToHighlight(junctionMarkerVector, junctions[0], junctions[1]);

          setOpacityFor([junctionMarkerLayer], 0.2);
      };

        var nodes = _.partition(nodeMarkerLayer.getSource().getFeatures(), function (nodeFeature) {
          return nodeFeature.nodeInfo.id === nodeId;
        });

        selectFeaturesToHighlight(nodeMarkerVector, nodes[0], nodes[1]);
        highlightJunctions(nodeId);

        nodeMarkerLayer.setOpacity(0.2);
      };

      var clearHighlights = function () {
        var nodes = nodeMarkerLayer.getSource().getFeatures().concat(nodeMarkerSelectedLayer.getSource().getFeatures());
        var junctions = junctionMarkerLayer.getSource().getFeatures().concat(junctionMarkerSelectedLayer.getSource().getFeatures());

        selectFeaturesToHighlight(nodeMarkerVector, [], nodes);
        selectFeaturesToHighlight(junctionMarkerVector, [], junctions);

        setGeneralOpacity(1);
      };

      var toggleSelectInteractions = function (activate) {
        nodeAndJunctionPointTemplateClick.setActive(activate);
      };

      /**
       * This will add all the following interactions from the map:
       * - nodeAndJunctionPointTemplateClick
       */
      var addSelectInteractions = function () {
        map.addInteraction(nodeAndJunctionPointTemplateClick);
      };

      var removeSelectInteractions = function () {
        map.removeInteraction(nodeAndJunctionPointTemplateClick);
      };

      // We add the defined interactions to the map.
      addSelectInteractions();

      me.eventListener.listenTo(eventbus, 'node:unselected junctions:unselected nodePointTemplate:unselected junctionTemplate:unselected', function () {
        clearHighlights();
      });

      me.eventListener.listenTo(eventbus, 'node:unselected', function () {
        if (nodeMarkerSelectedLayer.getSource().getFeatures().length !== 0) {
          nodeMarkerSelectedLayer.getSource().clear();
          eventbus.trigger('junctions:unselected');
        }
      });

      me.eventListener.listenTo(eventbus, 'junctions:unselected', function () {
        if (junctionMarkerSelectedLayer.getSource().getFeatures().length !== 0) {
          junctionMarkerSelectedLayer.getSource().clear();
        }
      });

      me.eventListener.listenTo(eventbus, 'nodePointTemplate:unselected', function () {
        if (nodePointTemplateSelectedLayer.getSource().getFeatures().length !== 0) {
          nodePointTemplateSelectedLayer.getSource().clear();
        }
      });

      me.eventListener.listenTo(eventbus, 'junctionTemplate:unselected', function () {
        if (junctionTemplateSelectedLayer.getSource().getFeatures().length !== 0) {
          junctionTemplateSelectedLayer.getSource().clear();
        }
      });

      var addNodeToMap = function (node) {
        var nodeMarker = new NodeMarker().createNodeMarker(node);
        nodeMarkerSelectedLayer.getSource().addFeature(nodeMarker);
        return nodeMarker;
      };

      var createUserNodeMarker = function (coords) {
        var node = {
          coordX: parseInt(coords.x, 10),
          coordY: parseInt(coords.y, 10),
          type:   LinkValues.NodeType.UnkownNodeType.value
        };
        node.nodeMarker = addNodeToMap(node);
        selectedNodeAndJunctionPoint.openNode(node);
        eventbus.trigger('tool:changed', LinkValues.Tool.Unknown.value);

        setOpacityFor([
            nodePointTemplateLayer,
            junctionTemplateLayer],
          0.2);
      };

      var setProperty = function (layers, propertyName, propertyValue) {
        _.each(layers, function (layer) {
          layer.set(propertyName, propertyValue);
        });
      };

      me.eventListener.listenTo(eventbus, 'tool:changed', function (tool) {
        switch (tool) {
          case LinkValues.Tool.Unknown.value:
            me.eventListener.stopListening(eventbus, 'map:clicked', createUserNodeMarker);
            setProperty([nodeMarkerLayer], 'selectable', false);
            setProperty([nodePointTemplateLayer, junctionTemplateLayer], 'selectable', true);
            break;
          case LinkValues.Tool.Select.value:
            me.eventListener.stopListening(eventbus, 'map:clicked', createUserNodeMarker);
            setProperty([nodeMarkerLayer], 'selectable', true);
            setProperty([nodePointTemplateLayer, junctionTemplateLayer], 'selectable', false);
            break;
          case LinkValues.Tool.Add.value:
            setProperty([nodeMarkerLayer, nodePointTemplateLayer, junctionTemplateLayer], 'selectable', false);
            me.eventListener.listenTo(eventbus, 'map:clicked', createUserNodeMarker);
            break;
        }
      });

      me.eventListener.listenTo(eventbus, 'nodeLayer:fetch', function () {
        map.getView().setZoom(Math.round(zoomlevels.getViewZoom(map)));
        roadCollection.fetchWithNodes(map.getView().calculateExtent(map.getSize()).join(','), zoomlevels.getViewZoom(map) + 1);
      });

      me.eventListener.listenTo(eventbus, 'layer:selected', function (layer, previouslySelectedLayer) {
        isActiveLayer = layer === 'node';
        toggleSelectInteractions(isActiveLayer);
        if (isActiveLayer) {
          addSelectInteractions();
        } else {
          clearHighlights();
          removeSelectInteractions();
        }
        if (previouslySelectedLayer === 'node') {
          hideLayer();
          removeSelectInteractions();
        } else {
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

        eventListener.listenTo(eventbus, 'node:addNodesToMap', function(nodes, nodePointTemplates, junctionTemplates, zoom){
          var roadLinksWithValues = _.reject(roadCollection.getAll(), function (rl) {
            return rl.roadNumber === 0;
          });

          var selectedNode = selectedNodeAndJunctionPoint.getCurrentNode();

          if (parseInt(zoom, 10) >= zoomlevels.minZoomForNodes) {
            if (!_.isUndefined(selectedNode) && !_.isUndefined(selectedNode.nodeMarker)) {
                nodeMarkerLayer.getSource().addFeature(selectedNode.nodeMarker); // adds node created by user which isn't saved yet.
            }

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

          if (parseInt(zoom, 10) >= zoomlevels.minZoomForJunctions) {
            if (!_.isUndefined(selectedNode) && _.isUndefined(selectedNode.id)) {
              var nodeMarker = new NodeMarker();
              nodeMarkerLayer.getSource().addFeature(nodeMarker.createNodeMarker(selectedNode));
            }

            _.each(_.flatten(_.map(nodes, "junctions")), function (junction) {
              var junctionMarker = new JunctionMarker();
              var junctionPoint = {};
              var roadLink = _.find(roadLinksWithValues, function (roadLink) {
                junctionPoint = _.find(junction.junctionPoints, function (junctionPoint) {
                  return (roadLink.startAddressM === junctionPoint.addrM || roadLink.endAddressM === junctionPoint.addrM) && roadLink.roadwayNumber === junctionPoint.roadwayNumber;
                });
                return !_.isUndefined(junctionPoint);
              });
              if (!_.isUndefined(roadLink) && !_.isUndefined(junctionPoint)) {
                junctionMarkerLayer.getSource().addFeature(junctionMarker.createJunctionMarker(junction, junctionPoint, roadLink));
              }
            });

            _.each(junctionTemplates, function (junctionTemplate) {
              var junctionTemplateMarker = new JunctionTemplateMarker();
              var junctionPoint = {};
              var roadLink = _.find(roadLinksWithValues, function (roadLink) {
                junctionPoint = _.find(junctionTemplate.junctionPoints, function (junctionPoint) {
                  return (roadLink.startAddressM === junctionPoint.addrM || roadLink.endAddressM === junctionPoint.addrM) && roadLink.roadwayNumber === junctionPoint.roadwayNumber;
                });
                return !_.isUndefined(junctionPoint);
              });
              if (!_.isUndefined(roadLink) && !_.isUndefined(junctionPoint)) {
                junctionTemplateLayer.getSource().addFeature(junctionTemplateMarker.createJunctionTemplateMarker(junctionTemplate, roadLink));
              }
            });
          }

          if (!_.isUndefined(selectedNode.id)) {
            clearHighlights();
            highlightNode(selectedNode.id);
          }
        });

        eventListener.listenTo(eventbus, 'roadLinks:refreshView', function () {
          me.refreshView();
        });

        eventListener.listenTo(eventbus, 'map:clearLayers', me.clearLayers);

        eventListener.listenTo(eventbus, 'nodeType:changed', function (node) {
          if (!_.isUndefined(node)) {
            var nodeMarker = node.nodeMarker;
          }
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