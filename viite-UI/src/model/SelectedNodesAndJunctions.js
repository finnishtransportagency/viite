(function (root) {
  root.SelectedNodesAndJunctions = function () {
    var current = {};
    var dirty = false;

    var openNode = function (node) {
      clean();
      setCurrentNode(node);
      current.node.junctionsToDetach = [];
      current.node.junctionsToUpdate = [];
      current.node.nodePointsToDetach = [];
      eventbus.trigger('node:selected', node);
    };

    var openNodePointTemplates = function (nodePointTemplates) {
      clean();
      setCurrentNodePointTemplates(nodePointTemplates);
      eventbus.trigger('nodePointTemplate:selected');
    };

    var openJunctionTemplate = function (junctionTemplate) {
      clean();
      setCurrentJunctionTemplate(junctionTemplate);
      eventbus.trigger('junctionTemplate:selected');
    };

    var setCurrentNode = function (node) {
      current.node = node;
    };

    var setCurrentNodePointTemplates = function (nodePointTemplates) {
      current.nodePointTemplates = nodePointTemplates;
    };

    var setCurrentJunctionTemplate = function (junctionTemplate) {
      current.junctionTemplate = junctionTemplate;
    };

    var getCurrentNode = function () {
      return current.node;
    };

    var getCurrentNodePointTemplates = function () {
      return current.nodePointTemplates;
    };

    var getCurrentJunctionTemplate = function () {
      return current.junctionTemplate;
    };

    var setNodeName = function (name) {
      current.node.name = name;
      setDirty(true);
    };

    var setNodeType = function (type) {
      if (!current.node.typeChanged) { current.node.oldType = current.node.type; }
      current.node.type = type;
      current.node.typeChanged = current.node.oldType !== type;
      eventbus.trigger('changed:type', current.node);
      if (!current.node.typeChanged) { eventbus.trigger('reset:startDate', current.node.oldStartDate || current.node.startDate); }
      setDirty(true);
    };

    var typeHasChanged = function () {
      return current.node.typeChanged;
    };

    var setStartDate = function (startDate) {
      if (!current.node.startDateChanged) { current.node.oldStartDate = current.node.startDate; }
      current.node.startDate = startDate;
      current.node.startDateChanged = current.node.oldStartDate !== startDate;
      setDirty(true);
    };

    var startDateHasChanged = function () {
      return current.node.startDateChanged;
    };

    var detachNodePoint = function (id) {
      var nodePoints = _.partition(current.node.nodePoints, function (nodePoint) {
        return nodePoint.id === id;
      });

      var nodePointToDetach = _.first(nodePoints[0]);

      current.node.nodePointsToDetach.push(nodePointToDetach);
      current.node.nodePoints = nodePoints[1];
      setDirty(true);
      eventbus.trigger('nodePoint:detach', nodePointToDetach);
    };

    var attachNodePoint = function (id) {
      var nodePoints = _.partition(current.node.nodePointsToDetach, function (nodePoint) {
        return nodePoint.id === id;
      });

      var nodePointToAttach = _.first(nodePoints[0]);

      current.node.nodePoints.push(nodePointToAttach);
      current.node.nodePointsToDetach = nodePoints[1];
      eventbus.trigger('nodePoint:attach', nodePointToAttach);
    };

    var detachJunction = function (id) {
      var junctions = _.partition(current.node.junctions, function (junction) {
        return junction.id === id;
      });

      var junctionToDetach = _.first(junctions[0]);

      current.node.junctionsToDetach.push(junctionToDetach);
      current.node.junctions = junctions[1];
      setDirty(true);
      eventbus.trigger('junction:detach', junctionToDetach);
    };

    var attachJunction = function (id) {
      var junctions = _.partition(current.node.junctionsToDetach, function (junction) {
        return junction.id === id;
      });

      var junctionToAttach = _.first(junctions[0]);

      current.node.junctions.push(junctionToAttach);
      current.node.junctionsToDetach = junctions[1];
      eventbus.trigger('junction:attach', junctionToAttach);
    };

    var isDirty = function () {
      return dirty;
    };

    var setDirty = function (value) {
      dirty = value;
    };

    var clean = function () {
      current = [];
      dirty = false;
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

    var closeNodePoint = function () {
      clean();
      close('nodePointTemplate:unselected');
    };

    var closeJunction = function () {
      clean();
      close('junctionTemplate:unselected');
    };

    var save = function (options, params) {
      eventbus.trigger(options, params);
    };

    var saveNode = function () {
      save('node:save', current.node);
    };

    return {
      openNode: openNode,
      openNodePointTemplates: openNodePointTemplates,
      openJunctionTemplate: openJunctionTemplate,
      getCurrentNode: getCurrentNode,
      getCurrentNodePointTemplates: getCurrentNodePointTemplates,
      getCurrentJunctionTemplate: getCurrentJunctionTemplate,
      setNodeName: setNodeName,
      setNodeType: setNodeType,
      typeHasChanged: typeHasChanged,
      setStartDate: setStartDate,
      startDateHasChanged: startDateHasChanged,
      detachNodePoint: detachNodePoint,
      attachNodePoint: attachNodePoint,
      detachJunction: detachJunction,
      attachJunction: attachJunction,
      isDirty: isDirty,
      setDirty: setDirty,
      closeNode: closeNode,
      closeForm: closeForm,
      closeNodePoint: closeNodePoint,
      closeJunction: closeJunction,
      saveNode: saveNode
    };

  };
})(this);