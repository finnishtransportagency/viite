/**
 * RoadNameCollection - Road name management and history
 * 
 * Handles road name operations including:
 * - Road name data management by road number
 * - Historical road name tracking with dates
 * - Road name creation and modification
 * - Date range management for road names
 * - Backend integration for road name operations
 */
import { eventbus } from '@utils/Eventbus.js';

export function RoadNameCollection(backend) {

    const me = this;
    const newId = -1000;
    let currentRoadNumber = -1;
    let currentRoadNameData = [];
    let changedIds = [];
    let newRoadName = {id: newId};

    const findCurrentRoadName = function (id) {
      let roadName = _.find(currentRoadNameData, function (roadData) {
        return roadData.id === parseInt(id, 10);
      });
      roadName = roadName ? roadName : newRoadName;
      changedIds.push(roadName.id);
      return roadName;
    };

    this.fetchRoads = function (roadNumber, onFetched) {
      changedIds = [];
      return new Promise(function (resolve) {
        backend.getRoadAddressesByRoadNumber(roadNumber, function (roadData) {
          currentRoadNumber = roadNumber;
          const sortedRoadData = _.chain(roadData.roadNameInfo).filter(function (rd) {
            return rd.roadNumber === parseInt(roadNumber, 10);
          }).map(function (road) {
            const roadCopy = road;
            if (road.endDate)
              roadCopy.endDate = moment(road.endDate, 'DD.MM.YYYY, HH:mm:ss');
            if (road.startDate)
              roadCopy.startDate = moment(road.startDate, 'DD.MM.YYYY, HH:mm:ss');
            return roadCopy;
          }).sortBy('startDate').value();
          currentRoadNameData = sortedRoadData;
          if (typeof onFetched === 'function') {
            onFetched(sortedRoadData);
          }
          resolve(sortedRoadData);
        });
      });
    };

    this.setRoadName = function (id, name) {
      const roadName = findCurrentRoadName(id);
      roadName.name = name;
    };

    this.setStartDate = function (id, startDate) {
      const roadName = findCurrentRoadName(id);
      roadName.startDate = startDate;
    };

    this.setEndDate = function (id, endDate) {
      const roadName = findCurrentRoadName(id);
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
        // eslint-disable-next-line eqeqeq
        return id != newId;
      });
    };

    this.saveChanges = function () {
      const changedData = _.filter(currentRoadNameData.concat(newRoadName), function (roadName) {
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
}
