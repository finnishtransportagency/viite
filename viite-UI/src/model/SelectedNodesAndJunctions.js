(function (root) {
  root.SelectedNodesAndJunctions = function () {
    var current = {};
    var dirty = false;

    var openNode = function (node) {
      eventbus.trigger('node:selected', node);
      setCurrentNode(node);
    };

    var openNodePointTemplate = function (nodePointTemplates) {
      setCurrentNodePointTemplates(nodePointTemplates);
      eventbus.trigger('nodePointTemplate:selected');
    };

    var openJunctionTemplate = function (junctionTemplate) {
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

    var setNodeType = function (nodeType) {
      current.node.nodeType = nodeType;
      eventbus.trigger('nodeType:changed', current.node);
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

    var close = function (options) {
      clean();
      eventbus.trigger(options);
      eventbus.trigger('nodesAndJunctions:open');
    };

    var closeNode = function () {
      close('node:unselected');
    };

    var closeNodePoint = function () {
      close('nodePointTemplate:unselected');
    };

    var closeJunction = function () {
      close('junctionTemplate:unselected');
    };

    return {
      openNode: openNode,
      openNodePointTemplate: openNodePointTemplate,
      openJunctionTemplate: openJunctionTemplate,
      getCurrentNode: getCurrentNode,
      getCurrentNodePointTemplates: getCurrentNodePointTemplates,
      getCurrentJunctionTemplate: getCurrentJunctionTemplate,
      setNodeType: setNodeType,
      isDirty: isDirty,
      setDirty: setDirty,
      closeNode: closeNode,
      closeNodePoint: closeNodePoint,
      closeJunction: closeJunction
    };

  };
})(this);