(function(root) {
  root.SelectedNodeAndJunctionPoint = function() {
    var current = {};
    var dirty = false;

    var openNodePointTemplates = function (nodePointTemplates) {
      setCurrentNodePointTemplates(nodePointTemplates);
      eventbus.trigger('nodePoint:selected');
    };

    var openJunctionPointTemplates = function (junctionPointTemplates) {
      setCurrentJunctionPointTemplates(junctionPointTemplates);
      eventbus.trigger('junctionPoint:selected');
    };

    var setCurrentNodePointTemplates = function(nodePointTemplates) {
      current.nodePointTemplates = nodePointTemplates;
    };

    var setCurrentJunctionPointTemplates = function(junctionPointTemplates) {
      current.junctionPointTemplates = junctionPointTemplates;
    };

    var getCurrentNodePointTemplates = function () {
      return current.nodePointTemplates;
    };

      var getCurrentJunctionPointTemplates = function () {
          return current.junctionPointTemplates;
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
      eventbus.trigger('junction:unselected');
      eventbus.trigger('nodesAndJunctions:open');
      eventbus.trigger('nodeLayer:fetch');
    };

    return {
      openNodePointTemplates: openNodePointTemplates,
      openJunctionPointTemplates: openJunctionPointTemplates,
      getCurrentNodePointTemplates: getCurrentNodePointTemplates,
      getCurrentJunctionPointTemplates: getCurrentJunctionPointTemplates,
      isDirty: isDirty,
      setDirty: setDirty,
      clean: clean,
      close: close
    };

  };
})(this);