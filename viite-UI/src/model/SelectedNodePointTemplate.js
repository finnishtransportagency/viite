(function(root) {
  root.SelectedNodePointTemplate = function(nodeCollection) {
    var current = [];
    var me = this;

    var open = function (node) {
      setCurrent(node);
      alert('a node has been clicked! ' + node.node_number);
    };

    var setCurrent = function(newSelection) {
      current = newSelection;
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
    };

    var close = function() {
      clean();
      eventbus.trigger('layer:enableButtons', true);
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