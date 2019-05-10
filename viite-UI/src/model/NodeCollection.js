(function (root) {
  root.NodeCollection = function (backend) {
    var me = this;
    var nodes = [];

    this.getNodes = function() {
      return nodes;
    };

    this.setNodes = function(list) {
      nodes = list;
    };

    this.getNodesByRoadAttributes = function(roadNumber, minRoadPartNumber, maxRoadPartNumber) {
      // applicationModel.addSpinner();
      var roadAttributes = {
        roadNumber: roadNumber,
        minRoadPartNumber: minRoadPartNumber,
        maxRoadPartNumber: maxRoadPartNumber
      };
      backend.getNodesByRoadAttributes(roadAttributes, function (response) {
        if (response.success) {
          alert('server responded!');
          nodes = response.nodes;
          eventbus.trigger('nodesAndJunctions:fetched', nodes);
        }
      });
    };

    eventbus.on('node:fetched', function(nodes, zoom){
      me.setNodes(nodes);
      eventbus.trigger('node:addNodesToMap', nodes, zoom)
    });
  };
})(this);