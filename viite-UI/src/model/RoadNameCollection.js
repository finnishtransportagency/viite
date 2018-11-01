(function (root) {
    root.RoadNameCollection = function (backend) {

        var me = this;
        var newId = -1000;
        var currentRoadNumber = -1;
        var currentRoadNameData = [];
        var changedIds = [];
        var newRoadName = {id: newId};
        var yearLimit = 5;

        var findCurrentRoadName = function (id) {
            var roadName = _.find(currentRoadNameData, function (roadData) {
                return roadData.id == id;
            });
            roadName = roadName ? roadName : newRoadName;
            changedIds.push(roadName.id);
            return roadName;
        };

        this.fetchRoads = function (roadNumber) {
            applicationModel.addSpinner();
            changedIds = [];
            backend.getRoadAddressesByRoadNumber(roadNumber, function (roadData) {
                currentRoadNumber = roadNumber;
                var sortedRoadData = _.chain(roadData.roadNameInfo).filter(function (rd) {
                    return rd.roadNumber == roadNumber;
                }).map(function (road) {
                    var roadCopy = road;
                    if (road.endDate)
                        roadCopy.endDate = moment(road.endDate, 'DD.MM.YYYY, HH:mm:ss');
                    if (road.startDate)
                        roadCopy.startDate = moment(road.startDate, 'DD.MM.YYYY, HH:mm:ss');
                    return roadCopy;
                }).sortBy('startDate').value();
                currentRoadNameData = sortedRoadData;
                var lastRoadName = _.last(sortedRoadData);
                eventbus.trigger("roadNameTool:roadsFetched", sortedRoadData);
            });
        };

        this.setRoadName = function (id, name) {
            var roadName = findCurrentRoadName(id);
            roadName.name = name;
        };

        this.setStartDate = function (id, startDate) {
            var roadName = findCurrentRoadName(id);
            roadName.startDate = startDate;
        };

        this.setEndDate = function (id, endDate) {
            var roadName = findCurrentRoadName(id);
            if (endDate === '')
                delete roadName.endDate;
            else
                roadName.endDate = endDate;
        };

        this.clear = function () {
            currentRoadNameData = [];
            changedIds = [];
            newRoadName = {id: newId};
        };

        this.undoNewRoadName = function () {
            newRoadName = {id: newId};
            changedIds = _.filter(changedIds, function (id) {
                return id != newId;
            });
        };

        this.saveChanges = function () {
            applicationModel.addSpinner();
            var changedData = _.filter(currentRoadNameData.concat(newRoadName), function (roadName) {
                return _.includes(changedIds, roadName.id);
            });
            backend.saveRoadNamesChanges(currentRoadNumber, changedData, function (result) {
                if (result.success) {
                    me.clear();
                    eventbus.trigger("roadNameTool:saveSuccess");
                } else {
                    eventbus.trigger("roadNameTool:saveUnsuccessful", result.errorMessage);
                }
            }, function (result) {
                eventbus.trigger("roadNameTool:saveUnsuccessful", result.errorMessage);
            });
        };

    };
})(this);