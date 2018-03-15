(function (root) {
    root.RoadNameCollection = function (backend) {
        var currentRoadData = [];
        var editedRoadData = [];

        this.fetchRoads = function (roadNumber) {
            editedRoadData = [];
            backend.getRoadAddressesByRoadNumber(roadNumber, function (roadData) {
                var sortedRoadData = _.chain(roadData).filter(function (rd) {
                    return rd.roadNumber == roadNumber;
                }).sortBy('endDate').reverse().sortBy('roadNumber').value();
                currentRoadData = sortedRoadData;
                eventbus.trigger("roadNameTool: roadsFetched", sortedRoadData);
            });
        };

        this.registerEdition = function (roadId, editedField, newValue) {
            var originalRecord = _.find(currentRoadData, function (road) {
                return road.id == roadId;
            });
            editedRoadData = editedRoadData + [{
                originalRoad: originalRecord,
                changedField: editedField,
                newValue: newValue
            }]
        };

        this.clearCurrent = function () {
            currentRoadData = [];
        };

        this.clearEditions = function () {
            editedRoadData = [];
        };

        this.clearBoth = function () {
            currentRoadData = [];
            editedRoadData = [];
        };
    };
})(this);