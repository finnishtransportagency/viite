(function (root) {
    root.RoadNameCollection = function (backend) {
        var currentRoadData = [];
        var editedRoadData = {};
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
            var newEdition = {editedField: editedField, value: newValue};
            var roadEdition = {roadId: roadId, editions: [newEdition]};
            var foundEdit = _.find(editedRoadData, function (rd) {
                return rd.roadId === roadId;
            });
            if (_.isUndefined(foundEdit)) {
                editedRoadData = editedRoadData.concat(roadEdition);
            } else {
                var combinedEdit = _.cloneDeep(foundEdit);
                var existingEditions = _.find(foundEdit.editions, function (edition) {
                    return edition.editedField === editedField;
                });
                if (_.isUndefined(existingEditions)) {
                    combinedEdit.editions = foundEdit.editions.concat([newEdition]);
                    editedRoadData[_.indexOf(editedRoadData, foundEdit)] = combinedEdit;
                } else {
                    var mainEditionIndex = _.indexOf(editedRoadData, foundEdit);
                    var registeredEditionsIndex = _.indexOf(editedRoadData[mainEditionIndex].editions, existingEditions);
                    editedRoadData[mainEditionIndex].editions[registeredEditionsIndex].value = newValue;
                }
            }
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


        };
    };
})(this);