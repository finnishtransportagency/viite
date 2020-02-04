(function (root) {
  root.SelectedNodesAndJunctions = function (nodeCollection) {
    var current = {};

    var openNode = function (node, templates) {
      clean();
      setCurrentNode(node);
      eventbus.trigger('node:selected', node, templates);
    };

    var getCurrentNode = function () {
      return current.node;
    };

    var setCurrentNode = function (node) {
      current.node = _.cloneDeep(node);
    };

    var templates = function (coordinates) {
      return {
        nodePoints: nodeCollection.getNodePointTemplatesByCoordinates(coordinates),
        junction: nodeCollection.getJunctionTemplateByCoordinates(coordinates)
      };
    };

    var openNodePointTemplates = function (nodePointTemplates) {
      openTemplates(templates(_.first(nodePointTemplates).coordinates));
    };

    var openJunctionTemplate = function (junctionTemplate) {
      openTemplates(templates(_.first(junctionTemplate.junctionPoints).coordinates));
    };

    var openTemplates = function (templates) {
      clean();
      setCurrentTemplates(templates.nodePoints, _.first(templates.junction));
      eventbus.trigger('templates:selected', templates);
    };

    var getCurrentTemplates = function () {
      return current.templates;
    };

    var setCurrentTemplates = function (nodePoints, junction) {
      current.templates = {
        nodePoints: _.cloneDeep(nodePoints),
        junction: _.cloneDeep(junction)
      };
    };

    var getJunctions = function () {
      return nodeCollection.getNodeByNodeNumber(current.node.nodeNumber).junctions;
    };

    var getNodePoints = function () {
      return nodeCollection.getNodeByNodeNumber(current.node.nodeNumber).nodePoints;
    };

    var addNodePoints = function (nodePoints) {
      _.each(nodePoints, function (nodePoint) { current.node.nodePoints.push(nodePoint); });
    };

    var addJunctions = function (junctions) {
      _.each(junctions, function (junction) { current.node.junctions.push(junction); });
    };

    var getStartingCoordinates = function () {
      return current.node.startingCoordinates;
    };

    var setStartingCoordinates = function (coordinates) {
      current.node.startingCoordinates = coordinates;
    };

    var setCoordinates = function (coordinates) {
      current.node.coordinates = coordinates;
      eventbus.trigger('change:node-coordinates');
    };

    var setNodeName = function (name) {
      current.node.name = name;
      eventbus.trigger('change:node', current.node);
    };

    var setNodeType = function (type) {
      current.node.type = type;
      eventbus.trigger('change:node', current.node);
    };

    var setStartDate = function (startDate) {
      current.node.startDate = startDate;
    };

    var typeHasChanged = function (nodeType) {
      if (current.node.nodeNumber) {
        return nodeCollection.getNodeByNodeNumber(current.node.nodeNumber).type !== nodeType;
      } else return LinkValues.NodeType.UnknownNodeType.value !== nodeType;
    };

    var getInitialStartDate = function () {
      return nodeCollection.getNodeByNodeNumber(current.node.nodeNumber).startDate;
    };

    var detachJunctionAndNodePoints = function (junction, nodePoints) {
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

    var attachJunctionAndNodePoints = function (junction, nodePoints) {
      if (!_.isUndefined(junction)) {
        current.node.junctions.push(junction);
        eventbus.trigger('junction:attach', junction);
      }
      _.each(nodePoints, function (nodePoint) {
        current.node.nodePoints.push(nodePoint);
        eventbus.trigger('nodePoint:attach', nodePoint);
      });
    };

    var isDirty = function () {
      var original = false;
      if (current.node.nodeNumber) { original = nodeCollection.getNodeByNodeNumber(current.node.nodeNumber); }
      var nodePointsEquality = false;
      var junctionsEquality = false;
      var junctionPointsEquality = false;
      //  comparing nodes without junctions or nodePoints
      var nodesEquality = isEqualWithout(original, current.node, ['junctions', 'nodePoints']);
      //  comparing the nodePoints of both nodes
      if (original && original.nodePoints && original.nodePoints.length !== 0 && original.nodePoints.length === current.node.nodePoints.length) {
        nodePointsEquality = !_.some(_.flatMap(_.zip(original.nodePoints, current.node.nodePoints), _.spread(function(originalNodePoint, currentNodePoint) {
          return {equality: isEqualWithout(originalNodePoint, currentNodePoint, 'coordinates')};
        })), ['equality', false]);
      }
      //  comparing the junctions of both nodes
      if (original && original.junctions && original.junctions.length !== 0 && original.junctions.length === current.node.junctions.length) {
        junctionsEquality = !_.some(_.flatMap(_.zip(original.junctions, current.node.junctions), _.spread(function (originalJunction, currentJunction) {
          // return isEqualWithout(originalJunction, currentJunction, 'junctionPoints');
          return {equality: isEqualWithout(originalJunction, currentJunction, 'junctionPoints')};
        })), ['equality', false]);

        //  comparing the junctionPoints of all junctions in both nodes
        junctionPointsEquality = !_.some(_.flatMap(_.zip(original.junctions, current.node.junctions), _.spread(function(originalJunction, currentJunction) {
          if (originalJunction.junctionPoints.length === currentJunction.junctionPoints.length && originalJunction.junctionPoints.length !== 0) {
            return _.flatMap(_.zip(originalJunction.junctionPoints, currentJunction.junctionPoints), _.spread(function (originalJunctionPoint, currentJunctionPoint) {
              // return isEqualWithout(originalJunctionPoint, currentJunctionPoint, 'coordinates');
              return {equality: isEqualWithout(originalJunctionPoint, currentJunctionPoint, 'coordinates')};
            }));
          } else return false;
        })), ['equality', false]);
      }
      //  true equality implemented
      return !(nodesEquality && nodePointsEquality && junctionsEquality && junctionPointsEquality);
    };

    var isEqualWithout = function (original, current, toIgnore) {
      return _.isEqual(
          _.omit(original, toIgnore),
          _.omit(current, toIgnore)
      );
    };

    var clean = function () {
      current = {};
    };

    var close = function (options, params) {
      eventbus.trigger(options, params);
      eventbus.trigger('nodesAndJunctions:open');
    };

    var closeForm = function () {
      eventbus.trigger('nodeLayer:closeForm', current); // all nodes and junctions forms should listen to this trigger
    };

    var closeNode = function () {
      close('node:unselected', current.node);
      clean();
      eventbus.trigger('nodeLayer:refreshView');
    };

    var closeTemplates = function () {
      clean();
      close('templates:unselected');
    };

    var saveNode = function () {
      eventbus.trigger('node:save', current.node);
    };

    eventbus.on('selectedNodesAndJunctions:openTemplates', function (templates) {
      openTemplates(templates);
    });

    return {
      openNode: openNode,
      openNodePointTemplates: openNodePointTemplates,
      openJunctionTemplate: openJunctionTemplate,
      openTemplates: openTemplates,
      getCurrentNode: getCurrentNode,
      getCurrentTemplates: getCurrentTemplates,
      getJunctions: getJunctions,
      getNodePoints: getNodePoints,
      addNodePoints: addNodePoints,
      addJunctions: addJunctions,
      getStartingCoordinates: getStartingCoordinates,
      setStartingCoordinates: setStartingCoordinates,
      setCoordinates: setCoordinates,
      setNodeName: setNodeName,
      setNodeType: setNodeType,
      typeHasChanged: typeHasChanged,
      getInitialStartDate: getInitialStartDate,
      setStartDate: setStartDate,
      detachJunctionAndNodePoints: detachJunctionAndNodePoints,
      attachJunctionAndNodePoints: attachJunctionAndNodePoints,
      isDirty: isDirty,
      closeNode: closeNode,
      closeTemplates: closeTemplates,
      closeForm: closeForm,
      saveNode: saveNode
    };
  };
})(this);