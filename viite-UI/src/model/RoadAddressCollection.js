(function (root) {
    root.RoadAddressCollection = function (backend) {
        var roads = [];
        var nodes = [];
        var me = this;

        this.fetchRoads = function(params) {
            backend.getRoads(params, function (result) {
                if (result.success) {
                    me.setRoads(result.roads);
                    eventbus.trigger('roadAddressBrowser:roadsFetched');
                } else
                    new ModalConfirm(result.error);
            });
        };

        this.fetchNodes = function(params) {
            backend.getNodes(params, function (result) {
                if (result.success) {
                    me.setNodes(result.nodes);
                    eventbus.trigger('roadAddressBrowser:nodesFetched');
                } else
                    new ModalConfirm(result.error);
            });
        };

        this.getRoads = function() {
            return roads;
        };

        this.setRoads = function(data) {
            roads = data;
        };

        this.getNodes = function() {
          return nodes;
        };

        this.setNodes = function(data) {
          nodes = data;
        };
    };
}(this));
