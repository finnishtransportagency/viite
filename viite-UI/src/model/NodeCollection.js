(function (root) {
  root.NodeCollection = function (backend) {
    var me = this;
    var groupedNodes = [];
    var nodes = [];

    this.getNodes = function() {
      return nodes;
    };

    this.setNodes = function(list) {
      nodes = list;
    };

    this.getNodesByRoadAttributes = function(roadNumber, minRoadPartNumber, maxRoadPartNumber) {
      applicationModel.addSpinner();
      var roadAttributes = {
        roadNumber: roadNumber,
        minRoadPartNumber: minRoadPartNumber,
        maxRoadPartNumber: maxRoadPartNumber
      };
      backend.getNodesByRoadAttributes(roadAttributes);
    };

    eventbus.on('node:addNodesToMap', function(nodes){

    })

  };
})(this);