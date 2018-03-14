(function (root) {
    root.RoadNameCollection = function (backend) {
        this.fetchRoads = function (roadNumber) {
            backend.getRoadAddressesByRoadNumber(roadNumber, function (roadData) {
                var sortedRoadData = _.chain(roadData).filter(function (rd) {
                    return rd.roadNumber == roadNumber;
                }).sortBy('endDate').reverse().sortBy('roadNumber').value();
                eventbus.trigger("roadNameTool: roadsFetched", sortedRoadData);
            });
        };
    };
})(this);