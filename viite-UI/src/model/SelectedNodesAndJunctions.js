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

    var setName = function (name) {
      current.node.name = name;
      eventbus.trigger('changed:name', current.node);
      setDirty(true);
    };

    var setType = function (type) {
      current.node.type = type;
      eventbus.trigger('changed:type', current.node);
      setDirty(true);
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
      setDirty(false);
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
      isDirty: isDirty,
      setDirty: setDirty,
      closeNode: closeNode,
      closeNodePoint: closeNodePoint,
      closeJunction: closeJunction,
      save: save,
      saveNode: saveNode
    };

  };
})(this);