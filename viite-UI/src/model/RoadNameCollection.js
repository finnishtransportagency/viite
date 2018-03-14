(function (root) {
    root.RoadNameCollection = function (backend) {
        this.fetchRoads = function (roadNumber) {
            backend.getRoadAddressesByRoadNumber(roadNumber, function (roadData) {
                eventbus.trigger("roadNameTool: roadsFetched", roadData);
            });
        };
    };
})(this);