(function (root) {
  root.NodeCollection = function (backend) {
    var me = this;
    var nodes = [];
    var nodesWithAttributes = [];
    var nodePointTemplates = [];
    var junctionTemplates = [];

    this.getNodes = function() {
      return nodes;
    };

    this.setNodePointTemplates = function(list) {
      nodePointTemplates = list;
    };

    this.setJunctionTemplates = function(list) {
      junctionTemplates = list;
    };

    this.setNodes = function(list) {
      nodes = list;
    };

    this.getNodesWithAttributes = function() {
      return nodesWithAttributes;
    };

    this.setNodesWithAttributes = function(list) {
      nodesWithAttributes = list;
    };


    this.getNodesByRoadAttributes = function(roadAttributes) {
      return backend.getNodesByRoadAttributes(roadAttributes, function (result) {
        if (result.success) {
          var searchResult = result.nodes;
          me.setNodesWithAttributes(searchResult);
          eventbus.trigger('nodeSearchTool:fetched', searchResult.length);
        } else {
          applicationModel.removeSpinner();
          new ModalConfirm(result.errorMessage);
        }
      });
    };

    eventbus.on('node:fetched', function(fetchResult, zoom) {
      var nodes = _.filter(fetchResult, function(node){
        return !_.isUndefined(node.node) ;
      });
      var nodePointTemplates = _.map(_.filter(fetchResult, function(node){
        return !_.isUndefined(node.nodePointTemplate) ;
      }), function (nodePointTemp) {
        return nodePointTemp.nodePointTemplate;
      });
      var junctionPointTemplates = _.map(_.filter(fetchResult, function(node){
          return !_.isUndefined(node.junctionPointTemplate) ;
      }), function (junctionPointTemp) {
          return junctionPointTemp.junctionPointTemplate;
      });

      me.setNodes(nodes);
      me.setNodePointTemplates(nodePointTemplates);
      me.setJunctionTemplates(junctionTemplates);
      me.setNodesWithAttributes(nodes);
      eventbus.trigger('node:addNodesToMap', nodes, nodePointTemplates, junctionPointTemplates, zoom);
    });

    eventbus.on('nodeSearchTool:clickNode', function (index, map) {
      var node = nodesWithAttributes[index];
      map.getView().animate({
        center: [node.coordX, node.coordY],
        zoom: 12,
        duration: 1500
      });
    });

    eventbus.on('nodeSearchTool:refreshView', function (map) {
      var coords = [];
      _.each(nodesWithAttributes, function(node) {
        coords.push([node.coordX, node.coordY]);
      });
      map.getView().fit(new ol.geom.Polygon([coords]), map.getSize());
    });

  };
})(this);