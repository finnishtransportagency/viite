/**
 * NodeLayer component
 * Manages the vector layer for displaying nodes and junctions with selection and translation interactions.
 * Handles node/junction highlighting, templates, and coordinate display.
 * @param {Object} map - OpenLayers map instance
 * @param {Object} roadLayer - Road layer reference
 * @param {Object} selectedNodesAndJunctions - Selected nodes and junctions manager
 * @param {Object} nodeCollection - Node collection manager
 * @param {Object} roadCollection - Road collection manager
 * @returns {Object} Layer with show/hide methods and minimum zoom level
 */
import { eventbus } from '@utils/Eventbus.js';
import { GeometryUtils } from '@utils/GeometryUtils.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { Layer } from './Layer.js';
import { JunctionMarker } from '../markers/JunctionMarker.js';
import { JunctionTemplateMarker } from '../markers/JunctionTemplateMarker.js';
import { NodeMarker } from '../markers/NodeMarker.js';
import { NodePointTemplateMarker } from '../markers/NodePointTemplateMarker.js';
import { getSessionUserRoles, getSelectedTool, setSelectedTool, refreshMap, isSelectedTool, getSelectedLayer } from '@model/ApplicationModel.js';

let addNodesToMapBridge = null;

// This  wrapper function is needed to expose the renderNodesToMap functon
export function addNodesToMap(nodes, templates, zoom) {
  if (_.isFunction(addNodesToMapBridge)) {
    addNodesToMapBridge(nodes, templates, zoom);
  }
}

export function NodeLayer(map, roadLayer, selectedNodesAndJunctions, nodeCollection, roadCollection) {
  Layer.call(this, map);

    const me = this;
    let isDraggingNode = false;
    let userHasPermissionToEdit = _.includes(getSessionUserRoles(), 'viite');
    const directionMarkerVector = new ol.source.Vector({});
    const dblVector = function () {
      return { selected: new ol.source.Vector({}), unselected: new ol.source.Vector({}) };
    };

    // This is used to fix a bug where sometimes popup doesn't disappear when moved
    let suppressOverlayUntilTs = 0;
    const suppressOverlayForMs = (ms) => {
      suppressOverlayUntilTs = Date.now() + ms;
    };
    const isOverlaySuppressed = () => Date.now() < suppressOverlayUntilTs;
    const isNodeDragged = () => isDraggingNode;

    const nodeMarkerVector = dblVector();
    const junctionMarkerVector = dblVector();
    const nodePointTemplateVector = dblVector();
    const junctionTemplateVector = dblVector();

    let selectedNodeStartingCoordinates = null;
    let lastSelectedTemplates = null;

    const directionMarkerLayer = new ol.layer.Vector({
      source: directionMarkerVector,
      name: 'directionMarkerLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.DirectionMarker.value
    });

    const nodeMarkerLayer = new ol.layer.Vector({
      source: nodeMarkerVector.unselected,
      name: 'nodeMarkerLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.NodeMarker.value,
      selectable: true
    });

    const nodeMarkerSelectedLayer = new ol.layer.Vector({
      source: nodeMarkerVector.selected,
      name: 'nodeMarkerSelectedLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.NodeMarker.selected
    });

    const junctionMarkerLayer = new ol.layer.Vector({
      source: junctionMarkerVector.unselected,
      name: 'junctionMarkerLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.JunctionMarker.value
    });

    const junctionMarkerSelectedLayer = new ol.layer.Vector({
      source: junctionMarkerVector.selected,
      name: 'junctionMarkerSelectedLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.JunctionMarker.selected
    });

    const nodePointTemplateLayer = new ol.layer.Vector({
      source: nodePointTemplateVector.unselected,
      name: 'nodePointTemplateLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.NodePointTemplate.value,
      selectable: true
    });

    const nodePointTemplateSelectedLayer = new ol.layer.Vector({
      source: nodePointTemplateVector.selected,
      name: 'nodePointTemplateSelectedLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.NodePointTemplate.selected
    });

    const junctionTemplateLayer = new ol.layer.Vector({
      source: junctionTemplateVector.unselected,
      name: 'junctionTemplateLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.JunctionTemplate.value,
      selectable: true
    });

    const junctionTemplateSelectedLayer = new ol.layer.Vector({
      source: junctionTemplateVector.selected,
      name: 'junctionTemplateSelectedLayer',
      zIndex: ViiteEnumerations.NodesAndJunctionsZIndex.JunctionTemplate.selected
    });

    const layers = [directionMarkerLayer, nodeMarkerLayer, nodeMarkerSelectedLayer, junctionMarkerLayer, junctionMarkerSelectedLayer, nodePointTemplateLayer, nodePointTemplateSelectedLayer, junctionTemplateLayer, junctionTemplateSelectedLayer];

    // Popup content shown when hovering mouse over a node which is not moved
    const infoContent = document.getElementById('popup-content');

    const getPopupOverlay = () => {
      const overlays = map.getOverlays().getArray();
      return _.find(overlays, (o) => {
        const el = o.getElement && o.getElement();
        return el && el.id === 'popup';
      });
    };

    const clearOverlay = () => {
      const overlay = getPopupOverlay();
      if (overlay) overlay.setPosition(undefined);
      if (infoContent) infoContent.innerHTML = '';
    };

    const displayNodeType = (nodeTypeCode) => {
      const nodeType = _.find(ViiteEnumerations.NodeType, (type) => type.value === nodeTypeCode);
      return _.isUndefined(nodeType) ? ViiteEnumerations.NodeType.UnknownNodeType.description : nodeType.description;
    };

    const displayNodeInfo = (event, pixel) => {
      // Do not show node info while moving a node
      if (isNodeDragged()) return;

      const featureAtPixel = map.forEachFeatureAtPixel(pixel, (feature) => feature);
      if (!featureAtPixel || _.isUndefined(featureAtPixel.node)) return;

      const overlay = getPopupOverlay();
      if (!overlay) return;

      const coordinate = map.getEventCoordinate(event.originalEvent);
      if (infoContent !== null) {
        let nodeName = '';
        const name = featureAtPixel.getProperties().name;
        if (!_.isUndefined(name)) {
          nodeName = `Nimi: ${_.escape(name)}<br>`;
        }
        infoContent.innerHTML = `${nodeName}Solmutyyppi: ${displayNodeType(featureAtPixel.getProperties().type)}<br>`;
      }
      overlay.setPosition(coordinate);
    };

    const displayJunctionInfo = (event, pixel) => {
      // Do not show junction info while moving a node
      if (isNodeDragged()) return;

      const featureAtPixel = map.forEachFeatureAtPixel(pixel, (feature) => feature);
      if (_.isUndefined(featureAtPixel) || _.isUndefined(featureAtPixel.junction) || _.isUndefined(featureAtPixel.junction.junctionPoints)) return;

      const overlay = getPopupOverlay();
      if (!overlay) return;

      const junctionData = featureAtPixel.junction;
      const junctionPointData = featureAtPixel.junction.junctionPoints;
      const node = nodeCollection.getNodeByNodeNumber(junctionData.nodeNumber);
      const coordinate = map.getEventCoordinate(event.originalEvent);
      const roadAddressInfo = [];
      _.map(junctionPointData, (point) => {
        roadAddressInfo.push({
          road: point.roadNumber,
          part: point.roadPartNumber,
          track: point.track,
          addr: point.addrM,
          beforeAfter: point.beforeAfter
        });
      });

      const groupedRoadAddresses = _.groupBy(roadAddressInfo, (row) => [row.road, row.track, row.part, row.addr]);

      const roadAddresses = _.partition(groupedRoadAddresses, (group) => group.length > 1);

      const doubleRows = _.map(roadAddresses[0], (junctionPoints) => {
        const first = _.head(junctionPoints);
        return { road: first.road, track: first.track, part: first.part, addr: first.addr };
      });

      const singleRows = _.map(roadAddresses[1], (junctionPoint) => ({
        road: junctionPoint[0].road,
        track: junctionPoint[0].track,
        part: junctionPoint[0].part,
        addr: junctionPoint[0].addr
      }));

      const roadAddressContent = _.sortBy(doubleRows.concat(singleRows), ['road', 'part', 'track', 'addr']);

      if (infoContent !== null) {
        infoContent.innerHTML =
          `Solmun nimi: ${node ? node.name.replace(' ', ' ') : ''}<br>
          Tieosoite:<br>
          ${_.map(roadAddressContent, function (junctionPoint) {
            return `&thinsp;${junctionPoint.road}&nbsp;/&nbsp;${junctionPoint.track}&nbsp;/&nbsp;${junctionPoint.part}&nbsp;/&nbsp;${junctionPoint.addr}<br>`;
          }).join('')}`;
      }
      overlay.setPosition(coordinate);
    };

    const setGeneralOpacity = function (opacity) {
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

    const setProperty = function (propertyLayers, propertyName, propertyValue) {
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
    const nodeLayerSelectInteraction = new ol.interaction.Select({
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

      const selectedNode = _.find(event.selected, function (selectionTarget) {
        return !_.isUndefined(selectionTarget.node);
      });

      // select all node point templates in same place.
      const selectedNodePointTemplate = _.find(event.selected, function (selectionTarget) {
        return !_.isUndefined(selectionTarget.nodePointTemplate);
      });

      const selectedJunctionTemplate = _.find(event.selected, function (selectionTarget) {
        return !_.isUndefined(selectionTarget.junctionTemplate);
      });

      switch (getSelectedTool()) {
        case ViiteEnumerations.Tool.Unknown.value:
          if (!_.isUndefined(selectedNode) && !_.isUndefined(selectedNode.node)) {
            selectNode(selectedNode.node);
            selectedNodeStartingCoordinates = selectedNode.node.coordinates;
          } else if (!_.isUndefined(selectedJunctionTemplate) && _.has(selectedJunctionTemplate, 'junctionTemplate')) {
            selectJunctionTemplate(selectedJunctionTemplate.junctionTemplate);
          } else if (!_.isUndefined(selectedNodePointTemplate) && _.has(selectedNodePointTemplate, 'nodePointTemplate')) {
            selectNodePointTemplate(selectedNodePointTemplate.nodePointTemplate);
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
    const nodeTranslate = new ol.interaction.Translate({
      layers: [nodeMarkerSelectedLayer]
    });

    /**
     * Save initial node position for comparison purposes
     */
    nodeTranslate.on('translatestart', function (evt) {
      isDraggingNode = true;
      const feature = evt.features && evt.features.item(0);
      const geometry = feature && feature.getGeometry && feature.getGeometry();
      const geometryCoordinates = geometry && geometry.getCoordinates && geometry.getCoordinates();
      const geometryStart = geometryCoordinates ? { x: geometryCoordinates[0], y: geometryCoordinates[1] } : null;

      const startingCoordinates =
        selectedNodeStartingCoordinates ||
        selectedNodesAndJunctions.getStartingCoordinates() ||
        geometryStart;

      selectedNodesAndJunctions.setStartingCoordinates(startingCoordinates);
      if (!selectedNodeStartingCoordinates && startingCoordinates) {
        selectedNodeStartingCoordinates = startingCoordinates;
      }
      // Hide any visible overlay immediately and suppress updates briefly to avoid flicker
      suppressOverlayForMs(150);
      clearOverlay();
    });

    /** Max distance that nodes can be moved (meters) */
    const maxNodeMovementDistance = 200;

    /**
     * while translating the new position the 200m limitation need to be verified
     * and stop the node movement when that limitation is not obeyed
     */
    nodeTranslate.on('translating', function (evt) {
      const coordinates = {
        x: evt.coordinate[0],
        y: evt.coordinate[1]
      };
      const startingCoordinates = selectedNodesAndJunctions.getStartingCoordinates();
      if (!startingCoordinates) {
        return;
      }

      if (GeometryUtils.distanceBetweenPoints(startingCoordinates, coordinates) < maxNodeMovementDistance) {
        eventbus.trigger('node:displayCoordinates', {
          x: evt.coordinate[0],
          y: evt.coordinate[1]
        });
      }
    });

    nodeTranslate.on('translateend', function (evt) {
      isDraggingNode = false;
      const geometry = evt.features.item(0).getGeometry();
      let coordinates = geometry.getCoordinates();
      coordinates = { x: coordinates[0], y: coordinates[1] }; // Format coordinates correctly
      const startingCoordinates = selectedNodesAndJunctions.getStartingCoordinates();

      if (!startingCoordinates) {
        selectedNodesAndJunctions.setCoordinates(coordinates);
        selectedNodeStartingCoordinates = coordinates;
        return;
      }

      // Check if node was moved over 200m
      if (GeometryUtils.distanceBetweenPoints(startingCoordinates, coordinates) < maxNodeMovementDistance) {
        selectedNodesAndJunctions.setCoordinates(coordinates);
        selectedNodeStartingCoordinates = coordinates;
      } else {
        eventbus.trigger('node:displayCoordinates', startingCoordinates);
        eventbus.trigger('node:repositionNode', selectedNodesAndJunctions.getCurrentNode(), startingCoordinates);
      }
    });

    /**
     * This will add all the following interactions from the map:
     * - nodeLayerSelectInteraction
     * - nodeTranslate
     */
    const addInteractions = function () {
      addSelectInteractions();
      if (userHasPermissionToEdit) {
        // only let the user move nodes if the user has permission to edit nodes
        addTranslateInteractions();
      }
    };

    const removeInteractions = function () {
      removeSelectInteractions();
      removeTranslateInteractions();
    };

    const toggleSelectInteractions = function (activate) {
      nodeLayerSelectInteraction.setActive(activate);
    };

    function addSelectInteractions() {
      map.addInteraction(nodeLayerSelectInteraction);
    }

    function removeSelectInteractions() {
      map.removeInteraction(nodeLayerSelectInteraction);
    }

    function addTranslateInteractions() {
      map.addInteraction(nodeTranslate);
    }

    function removeTranslateInteractions() {
      map.removeInteraction(nodeTranslate);
    }


    // Add the defined interactions to the map after userData has been fetched
    eventbus.on("userData:fetched", function (userData) {
      userHasPermissionToEdit = _.includes(userData.roles, 'viite');
      addInteractions();
    });

    // Immediately hide overlay when user starts interacting over node/junction to avoid flicker
    map.on('pointerdown', function (evt) {
      // Only act if clicking over a node or junction marker
      const featureAtPixel = map.forEachFeatureAtPixel(evt.pixel, function (feature) { return feature; });
      if (featureAtPixel && (featureAtPixel.node || featureAtPixel.junction)) {
        suppressOverlayForMs(200);
        clearOverlay();
      }
    });


    const selectFeaturesToHighlight = function (vector, featuresToHighlight, otherFeatures) {
      vector.selected.clear();
      vector.selected.addFeatures(featuresToHighlight);
      vector.unselected.clear();
      vector.unselected.addFeatures(otherFeatures);
    };

    function selectNode(node) {
      clearHighlights();
      selectedNodesAndJunctions.closeForm();
      selectedNodesAndJunctions.openNode(node);
      highlightNode(node);
      selectedNodeStartingCoordinates = node.coordinates;
    }

    function attachNode(node, templates) {
      clearHighlights();
      selectedNodesAndJunctions.openNode(node, templates);
      highlightNode(selectedNodesAndJunctions.getCurrentNode());

      // Set small delay to prevent bug where wrong menu appears after attaching node
      setTimeout(() => {
        setSelectedTool(ViiteEnumerations.Tool.Unknown.value);
      }, 10);
    }

    function selectNodePointTemplate(nodePointTemplate) {
      clearHighlights();
      selectedNodesAndJunctions.closeForm();
      selectedNodesAndJunctions.openNodePointTemplate(nodePointTemplate);
    }

    function selectJunctionTemplate(junctionTemplate) {
      clearHighlights();
      selectedNodesAndJunctions.closeForm();
      selectedNodesAndJunctions.openJunctionTemplate(junctionTemplate);
    }

    const addFeature = function (layer, feature, predicate) {
      if (_.isUndefined(_.find(layer.getSource().getFeatures(), predicate))) {
        layer.getSource().addFeature(feature);
      }
    };

    const highlightTemplates = function (templates) {
      if (!_.isUndefined(templates.nodePoints) && !_.isEmpty(templates.nodePoints)) {
        const nodePointTemplates = _.partition(nodePointTemplateLayer.getSource().getFeatures(), function (nodePointTemplateFeature) {
          return _.includes(_.map(templates.nodePoints, 'id'), nodePointTemplateFeature.nodePointTemplate.id);
        });
        selectFeaturesToHighlight(nodePointTemplateVector, nodePointTemplates[0], nodePointTemplates[1]);
      }

      if (!_.isUndefined(templates.junctions) && !_.isEmpty(templates.junctions)) {
        const junctionTemplates = _.partition(junctionTemplateLayer.getSource().getFeatures(), function (junctionTemplateFeature) {
          return _.includes(_.map(templates.junctions, 'id'), junctionTemplateFeature.junctionTemplate.id);
        });
        selectFeaturesToHighlight(junctionTemplateVector, junctionTemplates[0], junctionTemplates[1]);
      }

      nodePointTemplateLayer.setOpacity(0.2);
      junctionTemplateLayer.setOpacity(0.2);
    };

    function highlightNode(node) {
      const highlightJunctions = function () {
        const junctions = _.partition(junctionMarkerLayer.getSource().getFeatures(), function (junctionFeature) {
          return node.nodeNumber && junctionFeature.junction.nodeNumber === node.nodeNumber;
        });
        selectFeaturesToHighlight(junctionMarkerVector, junctions[0], junctions[1]);
        junctionMarkerLayer.setOpacity(0.2);
      };

      const nodes = _.partition(nodeMarkerLayer.getSource().getFeatures(), function (nodeFeature) {
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
    }

    function clearHighlights() {
      const nodes = nodeMarkerLayer.getSource().getFeatures().concat(nodeMarkerSelectedLayer.getSource().getFeatures());
      const junctions = junctionMarkerLayer.getSource().getFeatures().concat(junctionMarkerSelectedLayer.getSource().getFeatures());
      const templates = {
        nodePoints: nodePointTemplateLayer.getSource().getFeatures().concat(nodePointTemplateSelectedLayer.getSource().getFeatures()),
        junctions: junctionTemplateLayer.getSource().getFeatures().concat(junctionTemplateSelectedLayer.getSource().getFeatures())
      };

      selectFeaturesToHighlight(nodeMarkerVector, [], nodes);
      selectFeaturesToHighlight(junctionMarkerVector, [], junctions);
      selectFeaturesToHighlight(nodePointTemplateVector, [], templates.nodePoints);
      selectFeaturesToHighlight(junctionTemplateVector, [], templates.junctions);

      setGeneralOpacity(1);
      nodeLayerSelectInteraction.getFeatures().clear();
    }

    me.eventListener.listenTo(eventbus, 'node:unselected', function (current, cancel) {
      if (!current) {
        return;
      }
      if (cancel) {
        const original = nodeCollection.getNodeByNodeNumber(current.nodeNumber);
        if (original && original.nodeNumber) {
          updateCurrentNodeMarker(original);
        } else {
          removeCurrentNodeMarker(current);
        }
      }
    });

    me.eventListener.listenTo(eventbus, 'templates:selected', function (templates) {
      lastSelectedTemplates = _.cloneDeep(templates);
      highlightTemplates(templates);
    });

    me.eventListener.listenTo(eventbus, 'node:unselected templates:unselected', function () {
      clearHighlights();
    });

    me.eventListener.listenTo(eventbus, 'templates:unselected', function () {
      lastSelectedTemplates = null;
    });

    me.eventListener.listenTo(eventbus, 'tool:changed', function (tool) {
      toggleSelectInteractions(!isSelectedTool(ViiteEnumerations.Tool.Add.value));
      switch (tool) {
        case ViiteEnumerations.Tool.Unknown.value:
          me.eventListener.stopListening(eventbus, 'map:clicked', createNewNodeMarker);
          setProperty([nodeMarkerLayer], 'selectable', true);
          setProperty([nodePointTemplateLayer, junctionTemplateLayer], 'selectable', true);
          break;
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

    function createNewNodeMarker(coords) {
      const templates = selectedNodesAndJunctions.getCurrentTemplates() || lastSelectedTemplates;
      const node = {
        coordinates: { x: coords.x, y: coords.y },
        type: ViiteEnumerations.NodeType.UnknownNodeType.value,
        nodePoints: [],
        junctions: []
      };
      addFeature(nodeMarkerSelectedLayer, new NodeMarker().createNodeMarker(node),
        function (feature) {
          return feature.node.id === node.id;
        });
      attachNode(node, templates);
      eventbus.trigger('node:newNodeCreated', node, templates);
    }

    function removeCurrentNodeMarker(node) {
      _.each(nodeMarkerSelectedLayer.getSource().getFeatures(), function (nodeFeature) {
        if (_.isEqual(nodeFeature.node, node)) {
          nodeMarkerSelectedLayer.getSource().removeFeature(nodeFeature);
        }
      });
    }

    function updateCurrentNodeMarker(node) {
      _.each(nodeMarkerSelectedLayer.getSource().getFeatures(), function (nodeFeature) {
        if (nodeFeature.node.id === node.id) {
          nodeFeature.setProperties({ type: node.type });
          nodeFeature.setProperties({ name: node.name });
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
        const junction = _.find(node.junctions, function (junctionFound) {
          return junctionFound.id === junctionFeature.junction.id;
        });
        if (!_.isUndefined(junction)) {
          junctionFeature.setProperties({ junctionNumber: junction.junctionNumber });
        }
      });
    }

    const addJunctionToMap = function (junction, layer) {
      if (_.has(junction, 'junctionPoints') && !_.isEmpty(junction.junctionPoints)) {
        addFeature(layer, new JunctionMarker().createJunctionMarker(junction),
          function (feature) {
            return feature.junction.id === junction.id;
          });
      }
    };

    const addJunctionTemplateToMap = function (junction, layer) {
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


    function toggleJunctionToTemplate(junction, toTemplate) {
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
    }

    function toggleNodePointToTemplate(nodePoint, toTemplate) {
      if (toTemplate) {
        addFeature(nodePointTemplateSelectedLayer, new NodePointTemplateMarker().createNodePointTemplateMarker(nodePoint),
          function (feature) {
            return feature.nodePointTemplate.id === nodePoint.id;
          });
      } else {
        const nodePointTemplateFeature = _.find(nodePointTemplateSelectedLayer.getSource().getFeatures(), function (feature) {
          return feature.nodePointTemplate.id === nodePoint.id;
        });
        if (!_.isUndefined(nodePointTemplateFeature)) {
          nodePointTemplateSelectedLayer.getSource().removeFeature(nodePointTemplateFeature);
        }
      }
    }

    me.eventListener.listenTo(eventbus, 'junction:mapNumberUpdate', function (junction) {
      const updateJunctionTemplateNumberOnMap = function (junctionToUpdate) {
        _.each(junctionTemplateSelectedLayer.getSource().getFeatures(), function (junctionFeature) {
          if (_.isEqual(junctionFeature.junctionTemplate.id, junctionToUpdate.id)) {
            junctionFeature.setProperties({ junctionNumber: junctionToUpdate.junctionNumber });
          }
        });
      };

      const updateJunctionNumberOnMap = function (junctionToMap) {
        _.each(junctionMarkerSelectedLayer.getSource().getFeatures(), function (junctionFeature) {
          if (_.isEqual(junctionFeature.junction.id, junctionToMap.id)) {
            junctionFeature.setProperties({ junctionNumber: junctionToMap.junctionNumber });
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

    me.eventListener.listenTo(eventbus, 'nodeEditor:opened', function () {
      setProperty([nodePointTemplateLayer, junctionTemplateLayer], 'selectable', false);
    });

    me.eventListener.listenTo(eventbus, 'nodeEditor:closed', function () {
      setProperty([nodePointTemplateLayer, junctionTemplateLayer], 'selectable', true);
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
      refreshMap(zoomlevels.getViewZoom(map), map.getLayers().getArray()[0].getExtent(), map.getView().getCenter());
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
      // Generalize the zoom levels as the resolutions and zoom levels differ between map tile sources
      roadCollection.reset();
      roadCollection.fetchWithNodes(map.getView().calculateExtent(map.getSize()), zoomlevels.getViewZoom(map));
      roadLayer.layer.changed();
    };

    this.layerStarted = function (eventListener) {
      eventListener.listenTo(eventbus, 'roadLinks:fetched', function () {
        if (isNodeDragged()) return; // Stop node resetting to original position right after/before zoom
        if (getSelectedLayer() === 'node') {
          me.clearLayers(layers);
        }
      });



      eventListener.listenTo(eventbus, 'map:clearLayers', me.clearLayers);

      eventListener.listenTo(eventbus, 'change:node', function (node, _junction) {
        updateCurrentNodeMarker(node);
      });

      // Handle node & junction info popup on overlay update
      eventListener.listenTo(eventbus, 'overlay:update', function (event, pixel) {
        if (isOverlaySuppressed() || isNodeDragged()) {
          clearOverlay();
          return;
        }
        displayNodeInfo(event, pixel);
        displayJunctionInfo(event, pixel);
      });
    };

    function renderNodesToMap(nodes, templates, zoom) {
      let filteredNodes = nodes;
      const currentNode = selectedNodesAndJunctions.getCurrentNode();
      const currentTemplates = selectedNodesAndJunctions.getCurrentTemplates();

      if (parseInt(zoom, 10) >= zoomlevels.minZoomForNodes) {
        let filteredNodePointTemplates = templates.nodePoints;

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

      if (parseInt(zoom, 10) >= zoomlevels.minZoomForJunctions) {

        const filteredJunctions = _.flatten(_.map(filteredNodes, "junctions"));
        let filteredJunctionTemplates = templates.junctions;

        if (currentNode) {
          const currentJunctions = _.partition(currentNode.junctions, function (junction) {
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
    }

    addNodesToMapBridge = renderNodesToMap;

    const showLayer = function () {
      me.start();
      me.layerStarted(me.eventListener);
      me.toggleLayersVisibility(layers, true);
    };

    function hideLayer() {
      me.clearLayers(layers);
      me.toggleLayersVisibility(layers, false);
    }

    me.addLayers(layers);

    return {
      show: showLayer,
      hide: hideLayer,
      addNodesToMap: renderNodesToMap,
      minZoomForContent: me.minZoomForContent
    };
  }
