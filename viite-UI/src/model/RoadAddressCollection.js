(function (root) {
    root.RoadAddressCollection = function (backend) {
        var roads = [];
        var nodes = [];
        var junctions = [];
        var roadNames = [];
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

        this.fetchJunctions = function(params) {
            backend.getJunctions(params, function (result) {
                if (result.success) {
                    me.setJunctions(result.junctions);
                    eventbus.trigger('roadAddressBrowser:junctionsFetched');
                } else
                    new ModalConfirm(result.error);
            });
        };

        this.fetchRoadNames = function(params) {
            backend.getRoadNames(params, function (result) {
                if (result.success) {
                    me.setRoadNames(result.roadNames);
                    eventbus.trigger('roadAddressBrowser:roadNamesFetched');
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

        this.setJunctions = function(data) {
            junctions = data;
        };

        this.getJunctions = function() {
          return junctions;
        };

        this.getRoadNames = function() {
          return roadNames;
        };

        this.setRoadNames = function(data) {
          roadNames = data;
        };
    };
}(this));
