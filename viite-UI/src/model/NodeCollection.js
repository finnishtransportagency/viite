(function (root) {
  root.NodeCollection = function (backend) {
    var me = this;
    var nodes = [];
    var nodesWithAttributes = [];

    this.getNodes = function() {
      return nodes;
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
          me.setNodesWithAttributes(result.nodes);
          eventbus.trigger('nodesAndRoadAttributes:fetched');
        }
      });
    };
  };
})(this);