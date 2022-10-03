(function (root) {
    root.RoadAddressCollection = function (backend) {
        var roads = [];
        var nodes = [];
        var junctions = [];
        var roadNames = [];
        var me = this;

        this.fetchByTargetValue = function(params) {
            applicationModel.addSpinner();
            backend.getDataForRoadAddressBrowser(params, function(result) {
                if (result.success) {
                    switch (params.target) {
                        case "Roads":
                            me.setRoads(result.roads);
                            eventbus.trigger('roadAddressBrowser:roadsFetched', params);
                            break;
                        case "Nodes":
                            me.setNodes(result.nodes);
                            eventbus.trigger('roadAddressBrowser:nodesFetched', params);
                            break;
                        case "Junctions":
                            me.setJunctions(result.junctions);
                            eventbus.trigger('roadAddressBrowser:junctionsFetched', params);
                            break;
                        case "RoadNames":
                            me.setRoadNames(result.roadNames);
                            eventbus.trigger('roadAddressBrowser:roadNamesFetched', params);
                            break;
                        default:
                    }
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
