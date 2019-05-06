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

    this.getNodesByRoadAttributes = function(roadNumber, startRoadPartNumber, endRoadPartNumber) {
      applicationModel.addSpinner();
      var roadAttributes = {
        roadNumber: roadNumber,
        startRoadPartNumber: startRoadPartNumber,
        endRoadPartNumber: endRoadPartNumber
      };
      backend.getNodesByRoadAttributes(roadAttributes, function (result) {
        if (result.success) {
          eventbus.trigger('nodesAndJunctions:fetched', result);
        } else {
          eventbus.trigger('nodesAndJunctions:failed', result.errorMessage);
          applicationModel.removeSpinner();
        }
      });
    };

  };
})(this);