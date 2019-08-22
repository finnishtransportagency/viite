(function(root) {
  root.SelectedNodePoint = function(nodeCollection) {
    var current = [];
    var dirty = false;

    var open = function (nodePoint) {
      setCurrent(nodePoint);
      eventbus.trigger('nodePoint:selected', current.nodePointTemplate);
    };

    var setCurrent = function(nodePointTemplate) {
      current.nodePointTemplate = nodePointTemplate;
    };

    var getCurrent = function () {
      return current;
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
      open: open,
      setCurrent: setCurrent,
      getCurrent: getCurrent,
      isDirty: isDirty,
      setDirty: setDirty,
      clean: clean,
      close: close
    };

  };
})(this);