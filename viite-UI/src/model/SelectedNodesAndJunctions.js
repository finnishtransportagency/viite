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

    var openNodePointTemplate = function (nodePointTemplates) {
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

    var setName = function (name) {
      current.node.name = name;
      setDirty(true);
    };

    var setType = function (type) {
      current.node.type = type;
      eventbus.trigger('changed:type', current.node);
      setDirty(true);
    };

    var setStartDate = function (startDate) {
      current.node.startDate = startDate;
      setDirty(true);
    };

    var detachNodePoint = function (id) {
      var nodePoints = _.partition(current.node.nodePoints, function (nodePoint) {
        return nodePoint.id === id;
      });
      current.node.nodePointsToDetach = current.node.nodePointsToDetach.concat(nodePoints[0]);
      current.node.nodePoints = nodePoints[1];
      setDirty(true);
    };

    var attachNodePoint = function () {
      var nodePoints = _.partition(current.node.nodePointsToDetach, function (nodePoint) {
        return nodePoint.id === id;
      });
      current.node.nodePoints = current.node.nodePoints.concat(nodePoints[0]);
      current.node.nodePointsToDetach = nodePoints[1];
    };

    var detachJunction = function (id) {
      var junctions = _.partition(current.node.junctions, function (junction) {
        return junction.id === id;
      });
      current.node.junctionsToDetach = current.node.junctionsToDetach.concat(junctions[0]);
      current.node.junctions = junctions[1];
      setDirty(true);
    };

    var attachJunction = function (id) {
      var junctions = _.partition(current.node.junctionsToDetach, function (junction) {
        return junction.id === id;
      });
      current.node.junctions = current.node.junctions.concat(junctions[0]);
      current.node.junctionsToDetach = junctions[1];
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
      clean();
      eventbus.trigger(options, params);
      eventbus.trigger('nodesAndJunctions:open');
    };

    var closeNode = function () {
      var node = {};
      if (!_.isUndefined(current.node) && _.isUndefined(current.node.id)) {
        node = current.node;
      }
      close('node:unselected', node);
    };

    var closeForm = function () {
      close('node:unselected');
      closeNodePoint();
      closeJunction();
    };

    var closeNodePoint = function () {
      close('nodePointTemplate:unselected');
    };

    var closeJunction = function () {
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
      openNodePointTemplate: openNodePointTemplate,
      openJunctionTemplate: openJunctionTemplate,
      getCurrentNode: getCurrentNode,
      getCurrentNodePointTemplates: getCurrentNodePointTemplates,
      getCurrentJunctionTemplate: getCurrentJunctionTemplate,
      setName: setName,
      setType: setType,
      setStartDate: setStartDate,
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