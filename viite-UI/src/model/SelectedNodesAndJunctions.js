(function (root) {
  root.SelectedNodesAndJunctions = function (nodeCollection) {
    var current = {};

    var openNode = function (node) {
      clean();
      setCurrentNode(node);
      current.node.junctionsToDetach = [];
      current.node.junctionsToUpdate = [];
      current.node.nodePointsToDetach = [];
      eventbus.trigger('node:selected', node);
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

    var setInitialCoordinates = function (coordinates) {
      current.node.initialCoordinates = coordinates;
    };

    var setCoordinates = function (coordinates) {
      current.node.coordinates = coordinates;
      eventbus.trigger('change:node-coordinates');
    };

    var setNodeName = function (name) {
      current.node.name = name;
    };

    var setNodeType = function (type) {
      current.node.type = type;
      // if (!current.node.typeChanged) { current.node.oldType = current.node.type; }
      // current.node.type = type;
      // current.node.typeChanged = current.node.oldType !== type;
      // eventbus.trigger('change:type', current.node);
      // if (!current.node.typeChanged) { eventbus.trigger('reset:startDate', current.node.oldStartDate || current.node.startDate); }
      // setDirty(true);
    };

    var typeHasChanged = function () {
      return current.node.typeChanged;
    };

    var setStartDate = function (startDate) {
      if (!current.node.startDateChanged) { current.node.oldStartDate = current.node.startDate; }
      current.node.startDate = startDate;
      current.node.startDateChanged = current.node.oldStartDate !== startDate;
    };

    var detachJunctionAndNodePoints = function (junction, nodePoints) {
      if (!_.isUndefined(junction)) {
        current.node.junctionsToDetach.push(junction);
        _.remove(current.node.junctions, function (j) {
          return j.id === junction.id;
        });
        eventbus.trigger('junction:detach', junction);
      }
      _.each(nodePoints, function (nodePoint) {
        current.node.nodePointsToDetach.push(nodePoint);
        _.remove(current.node.nodePoints, function (np) {
          return np.id === nodePoint.id;
        });
        eventbus.trigger('nodePoint:detach', nodePoint);
      });
    };

    var attachJunctionAndNodePoints = function (junction, nodePoints) {
      if (!_.isUndefined(junction)) {
        current.node.junctions.push(junction);
        _.remove(current.node.junctionsToDetach, function (j) {
          return j.id === junction.id;
        });
        eventbus.trigger('junction:attach', junction);
      }
      _.each(nodePoints, function (nodePoint) {
        current.node.nodePoints.push(nodePoint);
        _.remove(current.node.nodePointsToDetach, function (np) {
          return np.id === nodePoint.id;
        });
        eventbus.trigger('nodePoint:attach', nodePoint);
      });
    };

    var isDirty = function () {
      var original = nodeCollection.getNodeByNodeNumber(current.node.nodeNumber);
      return !_.isEqual(current.node, original);
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
      var node = {};
      if (!_.isUndefined(current.node) && _.isUndefined(current.node.id)) {
        node = current.node;
      }
      clean();
      close('node:unselected', node);
      eventbus.trigger('nodeLayer:refreshView');
    };

    var closeTemplates = function () {
      clean();
      close('templates:unselected');
    };

    var saveNode = function () {
      eventbus.trigger('node:save', current.node);
    };

    /**
     * Checks for changes on form name, type, date and coordinates to revert them
     * and triggers node reposition to it's old coordinates
     */
    var revertFormChanges = function() {
        if(current.node.startNameChanged)
            current.node.name = current.node.oldName;
        if(current.node.typeChanged)
            current.node.type = current.node.oldType;
        if(current.node.startDateChanged)
            current.node.startDate = current.node.oldStartDate;

        if(current.node.oldCoordX && current.node.oldCoordY) {
            current.node.coordX = current.node.oldCoordX;
            current.node.coordY = current.node.oldCoordY;
            eventbus.trigger('node:repositionNode', current.node, [current.node.oldCoordX, current.node.oldCoordY]);
        }
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
      setInitialCoordinates: setInitialCoordinates,
      setCoordinates: setCoordinates,
      setNodeName: setNodeName,
      setNodeType: setNodeType,
      typeHasChanged: typeHasChanged,
      setStartDate: setStartDate,
      detachJunctionAndNodePoints: detachJunctionAndNodePoints,
      attachJunctionAndNodePoints: attachJunctionAndNodePoints,
      isDirty: isDirty,
      closeNode: closeNode,
      closeTemplates: closeTemplates,
      closeForm: closeForm,
      saveNode: saveNode,
      revertFormChanges: revertFormChanges
    };
  };
})(this);