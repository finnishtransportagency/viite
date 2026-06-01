import { eventbus } from '@utils/eventbus.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { setNodeMenuState } from '@node-menu/NodeMenu.js';

/**
 * SelectedNodesAndJunctions - Manages selected nodes and junctions state
 * 
 * Handles node and junction selection including:
 * - Node selection and template management
 * - Junction point handling
 * - Coordinate-based template retrieval
 * - Current state management for nodes/templates
 * - Event triggering for selection changes
 */
export function SelectedNodesAndJunctions(nodeCollection) {
    let current = {};

    const openNode = function (node, openNodetemplates) {
      current = {};
      setCurrentNode(node);
      eventbus.trigger('node:selected', current.node, openNodetemplates);
      setNodeMenuState('editor', current.node, openNodetemplates);
    };

    const getCurrentNode = function () {
      return current.node;
    };

    const initializeCurrentNode = function (node) {
      current = {};
      setCurrentNode(node);
    };

    function setCurrentNode(node) {
      current.node = _.cloneDeep(node);
    }

    const templates = function (coordinates) {
      return {
        nodePoints: nodeCollection.getNodePointTemplatesByCoordinates(coordinates),
        junctions: nodeCollection.getJunctionTemplateByCoordinates(coordinates)
      };
    };

    const openNodePointTemplate = function (nodePointTemplate) {
      openTemplates(templates(nodePointTemplate.coordinates));
    };

    const openJunctionTemplate = function (junctionTemplate) {
      openTemplates(templates(_.first(junctionTemplate.junctionPoints).coordinates));
    };

    function openTemplates(templatesToOpen) {
      current = {};
      setCurrentTemplates(templatesToOpen.nodePoints, templatesToOpen.junctions);
      eventbus.trigger('templates:selected', current.templates);
      if (!current.templates || (_.isEmpty(current.templates.nodePoints) && _.isEmpty(current.templates.junctions))) {
        return;
      }
      setNodeMenuState('display-templates', current.templates);
    }

    const getCurrentTemplates = function () {
      return current.templates;
    };

    function setCurrentTemplates(nodePoints, junctions) {
      current.templates = {
        nodePoints: _.cloneDeep(nodePoints),
        junctions: _.cloneDeep(junctions)
      };
    }

    const getJunctions = function () {
      return nodeCollection.getNodeByNodeNumber(current.node.nodeNumber).junctions;
    };

    const getNodePoints = function () {
      return nodeCollection.getNodeByNodeNumber(current.node.nodeNumber).nodePoints;
    };

    const addNodePointTemplates = function (nodePoints) {
      _.each(nodePoints, function (nodePoint) {
        current.node.nodePoints.push(nodePoint);
      });
      eventbus.trigger('nodePointTemplates:selected', {nodePoints: nodePoints});
    };

    const addJunctionTemplates = function (junctions) {
      _.each(junctions, function (junction) {
        current.node.junctions.push(junction);
      });
      eventbus.trigger('junction:validate');
      eventbus.trigger('junctionTemplates:selected', {junctions: junctions});
    };

    const getStartingCoordinates = function () {
      return current.node.startingCoordinates;
    };

    const setStartingCoordinates = function (coordinates) {
      current.node.startingCoordinates = coordinates;
    };

    const setCoordinates = function (coordinates) {
      current.node.coordinates = coordinates;
      eventbus.trigger('change:node-coordinates');
    };

    const setNodeName = function (name) {
      if (current.node) {
        current.node.name = name;
        updateNodesAndJunctionsMarker();
      }
    };

    const setNodeType = function (type) {
      if (current.node) {
        current.node.type = type;
        updateNodesAndJunctionsMarker();
      }
    };

    const setStartDate = function (startDate) {
      if (current.node) {
        current.node.startDate = startDate;
      }
    };

    const typeHasChanged = function (nodeType) {
      if (!current.node) {
        return ViiteEnumerations.NodeType.UnknownNodeType.value !== nodeType;
      }
      if (current.node.nodeNumber) {
        return nodeCollection.getNodeByNodeNumber(current.node.nodeNumber).type !== nodeType;
      } else return ViiteEnumerations.NodeType.UnknownNodeType.value !== nodeType;
    };

    const getInitialStartDate = function () {
      if (!current.node) {
        return '';
      }
      return nodeCollection.getNodeByNodeNumber(current.node.nodeNumber).startDate;
    };

    const setJunctionNumber = function (id, junctionNumber) {
      if (!current.node || !current.node.junctions) {
        return;
      }

      const normalizedJunctionNumber = _.trim((junctionNumber || '').toString()) === ''
        ? NaN
        : parseInt(junctionNumber, 10);

      const junction = _.find(current.node.junctions, function (junctionToSet) {
        return junctionToSet.id === id ||
          junctionToSet.id.toString() === (id || '').toString();
      });

      if (!_.isUndefined(junction)) {
        junction.junctionNumber = normalizedJunctionNumber;
        eventbus.trigger('junction:validate');
        updateNodesAndJunctionsMarker();
      }
    };

    const getJunctionPoint = function (id) {
      if (!current.node || !current.node.junctions) {
        return undefined;
      }
      const junctionPoints = _.flatMap(current.node.junctions, function (junction) {
        return junction.junctionPoints;
      });
      return _.find(junctionPoints, function (jp) {
        return jp.id === id;
      });
    };

    const setJunctionPointAddress = function (idString, addr) {
      const ids = idString.split("-");
      if (ids.length === 2) {
        _.each(ids, function (id) {
          const jp = getJunctionPoint(parseInt(id, 10));
          if (_.isUndefined(jp)) {
            console.log("Failed to find junction point " + id + " and set it's address to " + addr + ".");
          } else {
            jp.addrM = addr;
          }
        });
        eventbus.trigger('junctionPoint:validate', idString, addr);
      } else {
        console.log("Failed to update junction point address. (ids: " + idString + ", address: " + addr + ")");
      }
    };

    const detachJunctionAndNodePoints = function (junction, nodePoints) {
      if (!current.node) {
        return;
      }
      if (!_.isUndefined(junction)) {
        _.remove(current.node.junctions, function (j) {
          return j.id === junction.id;
        });
        eventbus.trigger('junction:detach', junction);
      }
      _.each(nodePoints, function (nodePoint) {
        _.remove(current.node.nodePoints, function (np) {
          return np.id === nodePoint.id;
        });
        eventbus.trigger('nodePoint:detach', nodePoint);
      });
    };

    const attachJunctionAndNodePoints = function (junction, nodePoints) {
      if (!current.node) {
        return;
      }
      if (!_.isUndefined(junction)) {
        if (_.filter(current.node.junctions, function (j) {
          return j.id === junction.id;
        }).length === 0) {
          current.node.junctions.push(junction);
          eventbus.trigger('junction:attach', junction);
        }
      }
      _.each(nodePoints, function (nodePoint) {
        if (_.filter(current.node.nodePoints, function (np) {
          return np.id === nodePoint.id;
        }).length === 0) {
          current.node.nodePoints.push(nodePoint);
          eventbus.trigger('nodePoint:attach', nodePoint);
        }
      });
    };

    const validateJunctionNumbers = function () {
      if (!current.node || !current.node.junctions) {
        return true;
      }

      const errorMessage = function (junctions) {
        let message = '';

        if (junctions.length !== 1) {
          message = 'Liittymänumero on jo käytössä'; // junction number is already in use
        } else if (_.isNaN(_.first(junctions).junctionNumber) || !_.isEmpty(_.find(junctions, function (j) {
          return j.junctionNumber <= 0;
        }))) {
          message = 'Liittymänumero on pakollinen tieto'; // junction number is compulsory information
        }

        verified = verified && _.isEmpty(message);
        return message;
      };

      let verified = true;

      _.each(_.groupBy(current.node.junctions, 'junctionNumber'), function (junctions) {
        eventbus.trigger('junction:setCustomValidity', junctions, errorMessage(junctions));
      });

      return verified;
    };

    const validateJunctionPointAddress = function (idString, addr) {
      let message = '';

      if (addr < 1) {
        message = 'Tieosan keskellä olevan liittymäkohdan osoitteen on oltava vähintään yksi'; // User is not allowed to set the address smaller than one
      } else if (_.isNaN(addr)) {
        message = 'Liittymäkohdan osoite on pakollinen tieto'; // junction point address is a compulsory information
      }

      eventbus.trigger('junctionPoint:setCustomValidity', idString, message);
      return _.isEmpty(message);
    };

    const isDirty = function () {
      if (!current.node) {
        return false;
      }
      let original = false;
      if (current.node && current.node.nodeNumber) {
        original = nodeCollection.getNodeByNodeNumber(current.node.nodeNumber);
      }
      let nodePointsEquality = false;
      let junctionsEquality = false;
      let junctionPointsEquality = false;
      //  comparing nodes without junctions or nodePoints
      const nodesEquality = isEqualWithout(original, current.node, ['junctions', 'nodePoints']);
      //  comparing the nodePoints of both nodes
      if (original && original.nodePoints && original.nodePoints.length !== 0 && original.nodePoints.length === current.node.nodePoints.length) {
        nodePointsEquality = !_.some(_.flatMap(_.zip(_.sortBy(original.nodePoints, 'id'), _.sortBy(current.node.nodePoints, 'id')), _.spread(function (originalNodePoint, currentNodePoint) {
          return {equality: isEqualWithout(originalNodePoint, currentNodePoint, 'coordinates')};
        })), ['equality', false]);
      }
      //  comparing the junctions of both nodes
      if (original && original.junctions && original.junctions.length !== 0 && original.junctions.length === current.node.junctions.length) {
        junctionsEquality = !_.some(_.flatMap(_.zip(_.sortBy(original.junctions, 'id'), _.sortBy(current.node.junctions, 'id')), _.spread(function (originalJunction, currentJunction) {
          return {equality: isEqualWithout(originalJunction, currentJunction, 'junctionPoints')};
        })), ['equality', false]);

        //  comparing the junctionPoints of all junctions in both nodes
        junctionPointsEquality = !_.some(_.flatMap(_.zip(_.sortBy(original.junctions, 'id'), _.sortBy(current.node.junctions, 'id')), _.spread(function (originalJunction, currentJunction) {
          if (originalJunction.junctionPoints.length === currentJunction.junctionPoints.length && originalJunction.junctionPoints.length !== 0) {
            return _.flatMap(_.zip(originalJunction.junctionPoints, currentJunction.junctionPoints), _.spread(function (originalJunctionPoint, currentJunctionPoint) {
              return {equality: isEqualWithout(originalJunctionPoint, currentJunctionPoint, 'coordinates')};
            }));
          } else return false;
        })), ['equality', false]);
      }
      //  true equality implemented
      return !(nodesEquality && nodePointsEquality && junctionsEquality && junctionPointsEquality);
    };

    const isObsoleteNode = function () {
      if (!current.node) {
        return true;
      }
      return _.isEmpty(current.node.junctions) && _.isEmpty(_.filter(current.node.nodePoints, function (np) {
        return np.type !== ViiteEnumerations.NodePointType.CalculatedNodePoint.value;
      }));
    };

    function isEqualWithout(original, currentObject, toIgnore) {
      return _.isEqual(
        _.omit(original, toIgnore),
        _.omit(currentObject, toIgnore)
      );
    }

    const close = function (options, params, cancel) {
      eventbus.trigger(options, params, cancel);
    };

    const closeForm = function () {
      eventbus.trigger('nodeLayer:closeForm', current); // all nodes and junctions forms should listen to this trigger
    };

    const closeNode = function (cancel) {
      const currentNode = current && current.node ? current.node : undefined;
      close('node:unselected', currentNode, cancel);
      current = {};
      eventbus.trigger('nodeLayer:refreshView');
    };

    const closeTemplates = function () {
      current = {};
      close('templates:unselected');
    };

    const saveNode = function () {
      if (_.isUndefined(current.node)) {
        return;
      }
      eventbus.trigger('node:save', current.node);
    };

    function updateNodesAndJunctionsMarker(junction) {
      eventbus.trigger('change:node', current.node, junction);
    }

    eventbus.on('selectedNodesAndJunctions:openTemplates', function (templatesToOpen) {
      openTemplates(templatesToOpen);
    });

    return {
      openNode: openNode,
      initializeCurrentNode: initializeCurrentNode,
      openNodePointTemplate: openNodePointTemplate,
      openJunctionTemplate: openJunctionTemplate,
      getCurrentNode: getCurrentNode,
      getCurrentTemplates: getCurrentTemplates,
      getJunctions: getJunctions,
      getNodePoints: getNodePoints,
      addNodePointTemplates: addNodePointTemplates,
      addJunctionTemplates: addJunctionTemplates,
      getStartingCoordinates: getStartingCoordinates,
      setStartingCoordinates: setStartingCoordinates,
      setCoordinates: setCoordinates,
      setNodeName: setNodeName,
      setNodeType: setNodeType,
      typeHasChanged: typeHasChanged,
      getInitialStartDate: getInitialStartDate,
      setStartDate: setStartDate,
      setJunctionNumber: setJunctionNumber,
      setJunctionPointAddress: setJunctionPointAddress,
      detachJunctionAndNodePoints: detachJunctionAndNodePoints,
      attachJunctionAndNodePoints: attachJunctionAndNodePoints,
      validateJunctionNumbers: validateJunctionNumbers,
      validateJunctionPointAddress: validateJunctionPointAddress,
      isDirty: isDirty,
      isObsoleteNode: isObsoleteNode,
      closeNode: closeNode,
      closeTemplates: closeTemplates,
      closeForm: closeForm,
      saveNode: saveNode,
      updateNodesAndJunctionsMarker: updateNodesAndJunctionsMarker
    };
}
