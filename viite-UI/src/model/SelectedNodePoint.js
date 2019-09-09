(function(root) {
  root.SelectedNodePoint = function() {
    var current = {};
    var dirty = false;

    var openNodePointTemplates = function (nodePointTemplates) {
      setCurrentNodePointTemplates(nodePointTemplates);
      eventbus.trigger('nodePoint:selected');
    };

    var setCurrentNodePointTemplates = function(nodePointTemplates) {
      current.nodePointTemplates = nodePointTemplates;
    };

    var getCurrentNodePointTemplates = function () {
      return current.nodePointTemplates;
    };

    var isDirty = function() {
      return dirty;
    };

    var setDirty = function(value) {
      dirty = value;
    };

    var clean = function() {
      current = [];
      dirty = false;
    };

    var close = function() {
      clean();
      eventbus.trigger('node:unselected');
      eventbus.trigger('nodesAndJunctions:open');
      eventbus.trigger('nodeLayer:fetch');
    };

    return {
      openNodePointTemplates: openNodePointTemplates,
      getCurrentNodePointTemplates: getCurrentNodePointTemplates,
      isDirty: isDirty,
      setDirty: setDirty,
      clean: clean,
      close: close
    };

  };
})(this);