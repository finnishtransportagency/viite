(function (root) {
    root.RoadNameCollection = function (backend) {

        var newId = -1000;
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

        this.addNewEntry = function (originalRoadId, originalRoadNumber) {
            var fieldData = [{editedField: "roadNumber", value: originalRoadNumber},
                {editedField: "orignalRoadId", value: originalRoadId}];
            var newRoadName = {originalRoadId: originalRoadId, editions: fieldData};
            newRoads = newRoads.concat([newRoadName]);
        };

        this.editNewEntry = function (originalRoadId, originalRoadNumber, fieldName, fieldValue) {
            var newEdition = {editedField: fieldName, value: fieldValue};
            var foundNew = _.find(newRoads, function (rd) {
                return rd.originalRoadId === originalRoadId;
            });
            var combinedNew = _.cloneDeep(foundNew);
            var existingEditions = _.find(foundNew.editions, function (edition) {
                return edition.editedField === fieldName;
            });
            if (_.isUndefined(existingEditions)) {
                combinedNew.editions = foundNew.editions.concat([newEdition]);
                newRoads[_.indexOf(newRoads, foundNew)] = combinedNew;
            } else {
                var mainEditionIndex = _.indexOf(newRoads, foundNew);
                var registeredEditionsIndex = _.indexOf(newRoads[mainEditionIndex].editions, existingEditions);
                newRoads[mainEditionIndex].editions[registeredEditionsIndex].value = fieldValue;
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
            var currentNew = _.map(newRoads, function (road) {
                var editions = road.editions;
                return {roadId: newId, editions: editions};
            });
            var objectToSave = currentNew.concat(editedRoadData);
            backend.saveRoadNamesChanges(objectToSave, function (successObject) {
                {
                    currentRoadData = [];
                    editedRoadData = [];
                    eventbus.trigger("roadNameTool: saveSuccess");
                }
            }, function (unsuccessObject) {
                eventbus.trigger("roadNameTool: saveUnsuccessful");
            });


        };
    };
})(this);