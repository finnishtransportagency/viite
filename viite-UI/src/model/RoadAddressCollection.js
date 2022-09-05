(function (root) {
    root.RoadAddressCollection = function (backend) {
        var roads = [];
        var me = this;

        this.fetchRoads = function(params) {
            backend.getRoads(params, function (result) {
                applicationModel.addSpinner();
                if (result.success) {
                    me.setRoads(result.roads);
                    eventbus.trigger('roadAddressBrowser:roadsFetched');
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
    };
}(this));
