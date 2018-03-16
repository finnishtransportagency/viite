(function (root) {
    root.RoadNameCollection = function (backend) {
        var currentRoadData = [];
        var editedRoadData = [];
        var newRoads = [];

        this.fetchRoads = function (roadNumber) {
            editedRoadData = [];
            backend.getRoadAddressesByRoadNumber(roadNumber, function (roadData) {
                var sortedRoadData = _.chain(roadData.roadNameInfo).filter(function (rd) {
                    return rd.roadNumber == roadNumber;
                }).map(function (road) {
                    var roadCopy = road;
                    roadCopy.endDate = _.first(road.endDate.split(","));
                    roadCopy.startDate = _.first(road.startDate.split(","));
                    return roadCopy;
                }).sortBy('endDate').reverse().sortBy('roadNumber').value();
                currentRoadData = sortedRoadData;
                eventbus.trigger("roadNameTool: roadsFetched", sortedRoadData);
            });
        };

        this.registerEdition = function (roadId, editedField, newValue) {
            editedRoadData = editedRoadData.concat([{
                originalRoadId: roadId,
                changedField: editedField,
                newValue: newValue
            }]);
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

        this.saveChanges = function () {
            //TODO
            var groupedChanges = _.groupBy(function (data) {
                return data.originalRoadId;
            });

        };
    };
})(this);