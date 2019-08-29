(function(root) {
  root.SelectedNodePoint = function(nodeCollection) {
    var current = {};
    var dirty = false;

    var openNodePointTemplate = function (nodePointTemplate, boundingBox) {
      setCurrentNodePointTemplate(nodePointTemplate);
      getNodePointsTemplateInSamePlace(boundingBox);
      eventbus.trigger('nodePoint:selected', current.nodePointTemplate);
    };

    var getNodePointsTemplateInSamePlace = function (boundingBox) {
      return nodeCollection.getNodeTemplatesByBoundingBox(boundingBox);
    };

    var getLowestRoadNumber = function () {
      return _.head(_.sortBy(getGroupOfNodePoints(), function (nodePoint) {
        return [nodePoint.roadNumber, nodePoint.roadPartNumber, nodePoint.addrM, nodePoint.beforeAfter];
      }));
    };

    var setCurrentNodePointTemplate = function(nodePointTemplate) {
      current.nodePointTemplate = nodePointTemplate;
    };

    var getCurrentNodePointTemplate = function () {
      return current.nodePointTemplate;
    };

    var setGroupOfNodePoints = function (nodePoints) {
      current.groupOfNodePoints = nodePoints;
    };

    var getGroupOfNodePoints = function () {
      return current.groupOfNodePoints;
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
      openNodePointTemplate: openNodePointTemplate,
      getCurrentNodePointTemplate: getCurrentNodePointTemplate,
      setGroupOfNodePoints: setGroupOfNodePoints,
      getGroupOfNodePoints: getGroupOfNodePoints,
      getLowestRoadNumber: getLowestRoadNumber,
      isDirty: isDirty,
      setDirty: setDirty,
      clean: clean,
      close: close
    };

  };
})(this);