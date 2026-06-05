/**
 * RoadCollection - Road link data model and management
 * 
 * Provides road link functionality including:
 * - Road link data model with selection state
 * - Link property management and validation
 * - Traffic direction and functional class handling
 * - Point geometry management
 */
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { eventbus } from '@utils/Eventbus.js';

const RoadLinkModel = function (data) {
  let selected = false;
  const original = _.cloneDeep(data);

  const getId = function () {
    return data.roadLinkId || data.linkId;
  };

  const getData = function () {
    return data;
  };

  const getPoints = function () {
    return _.cloneDeep(data.points);
  };

  const setLinkProperty = function (name, value) {
    if (value !== data[name]) {
      data[name] = value;
    }
  };

  const select = function () {
    selected = true;
  };

  const unselect = function () {
    selected = false;
  };

  const isSelected = function () {
    return selected;
  };

  const isCarTrafficRoad = function () {
    return !_.isUndefined(data.linkType) && !_.includes([8, 9, 21, 99], data.linkType);
  };

  const cancel = function () {
    data.trafficDirection = original.trafficDirection;
    data.functionalClass = original.functionalClass;
    data.linkType = original.linkType;
  };

  return {
    getId: getId,
    getData: getData,
    getPoints: getPoints,
    setLinkProperty: setLinkProperty,
    isSelected: isSelected,
    isCarTrafficRoad: isCarTrafficRoad,
    select: select,
    unselect: unselect,
    cancel: cancel
  };
};

export function RoadCollection(backend) {
    let currentAllRoadLinks = [];
    let roadLinkGroups = [];
    let unaddressedRoadLinkGroups = [];
    const RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;
    const LinkSource = ViiteEnumerations.LinkGeomSource;
    const lifecycleStatus = ViiteEnumerations.lifecycleStatus;
    let clickedLinearLocationId = 0;
    let selectedRoadLinkModels = [];
    let pendingProjectHighlightId;

    eventbus.on('roadCollection:pendingProjectHighlight', function (projectId) {
      pendingProjectHighlightId = projectId;
    });

    const roadLinks = function () {
      return _.flatten(roadLinkGroups);
    };

    const getSelectedRoadLinkModels = function () {
      return selectedRoadLinkModels;
    };

    const getGroupByLinearLocationId = function (linearLocationId) {
      return _.find(roadLinkGroups, function (roadLinkGroup) {
        return _.some(roadLinkGroup, function (roadLink) {
          return roadLink.getData().linearLocationId === linearLocationId;
        });
      });
    };

    const updateGroup = function (linearLocationId, fetchedGroups) {
      const indexOfGroupToBeUpdated = roadLinkGroups.indexOf(getGroupByLinearLocationId(linearLocationId));

      const fetchedGroupThatWasClicked = _.find(fetchedGroups, function (roadLinkGroup) {
        return _.some(roadLinkGroup, function (roadLink) {
          return roadLink.getData().linearLocationId === linearLocationId;
        });
      });

      fetchedGroupThatWasClicked.forEach((roadLink) => {
        roadLink.select();
      });
      roadLinkGroups[indexOfGroupToBeUpdated] = fetchedGroupThatWasClicked;
    };

    this.fetch = function (boundingBox, zoom) {
      backend.getRoadLinks({
        boundingBox: boundingBox, zoom: zoom
      }, function (fetchedRoadLinks) {
        currentAllRoadLinks = fetchedRoadLinks;
        fetchProcess(fetchedRoadLinks, zoom);
      });
    };

    this.fetchWholeRoadPart = function (roadNumber, roadPart, selection) {
      backend.getRoadLinksOfWholeRoadPart({
        roadNumber: roadNumber, roadPartNumber: roadPart
      }, function (fetchedRoadLinks) {
        updateGroupToContainWholeRoadPart(fetchedRoadLinks, selection);
      });
    };

    this.fetchWithNodes = function (boundingBox, zoom, callback) {
      backend.getNodesAndJunctions({boundingBox: boundingBox, zoom: zoom}, function (fetchedNodesAndJunctions) {
        currentAllRoadLinks = fetchedNodesAndJunctions.fetchedRoadLinks;
        fetchProcess(currentAllRoadLinks, zoom);
        return callback(fetchedNodesAndJunctions.fetchedNodes);
      });
    };

    const updateGroupToContainWholeRoadPart = function (fetchedRoadLinks, selection) {
      const fetchedRoadLinkModels = _.map(fetchedRoadLinks, function (roadLinkGroup) {
        return _.map(roadLinkGroup, function (roadLink) {
          return new RoadLinkModel(roadLink);
        });
      });

      // update the roadlink group (that was clicked) with the newly fetched road links (containing whole road part instead of just the visible part of the road part)
      updateGroup(clickedLinearLocationId, fetchedRoadLinkModels);
      eventbus.trigger('roadCollection:wholeRoadPartFetched', selection);
    };


    const fetchProcess = function (fetchedRoadLinks, zoom) {
      const fetchedRoadLinkModels = _.map(fetchedRoadLinks, function (roadLinkGroup) {
        return _.map(roadLinkGroup, function (roadLink) {
          return new RoadLinkModel(roadLink);
        });
      });
      const [fetchedUnaddressed, fetchedWithAddresses] = _.partition(fetchedRoadLinkModels, function (model) {
        return _.every(model, function (mod) {
          return mod.getData().roadNumber === 0;
        });
      });

      unaddressedRoadLinkGroups = _.partition(fetchedUnaddressed, function (group) {
        return groupDataConstructionTypeFilter(group, lifecycleStatus.UnderConstruction);
      });

      if (parseInt(zoom, 10) <= zoomlevels.minZoomForEditMode) {
        // only the fetched road links that have an address
        setRoadLinkGroups(fetchedWithAddresses);
      } else {
        // ALL fetched road links
        setRoadLinkGroups(fetchedRoadLinkModels);
      }

      // get the selected links that were not fetched (i.e. were not inside the bounding box) and add them to the roadLinkGroups
      if (!_.isEmpty(getSelectedRoadLinkModels())) {
        const nonFetchedLinksInSelection = _.reject(getSelectedRoadLinkModels(), function (selected) {
          const allGroups = _.map(_.flatten(fetchedRoadLinkModels), function (group) {
            return group.getData();
          });
          return _.includes(_.map(allGroups, 'linkId'), selected.getData().linkId);
        });
        setRoadLinkGroups(roadLinkGroups.concat(nonFetchedLinksInSelection));
      }

      const nonHistoryConstructionRoadLinkGroups = _.reject(roadLinkGroups, function (group) {
        return groupDataSourceFilter(group, LinkSource.HistoryLinkInterface);
      });

      setRoadLinkGroups(nonHistoryConstructionRoadLinkGroups);
      eventbus.trigger('roadLinks:fetched');

      if (!_.isUndefined(pendingProjectHighlightId)) {
        eventbus.trigger('linkProperties:highlightSelectedProject', pendingProjectHighlightId);
        pendingProjectHighlightId = undefined;
      }
    };

    const groupDataSourceFilter = function (group, dataSource) {
      if (_.isArray(group)) {
        return _.some(group, function (roadLink) {
          if (roadLink)
            return roadLink.getData().roadLinkSource === dataSource.value;
          else return false;
        });
      } else {
        return group.getData().roadLinkSource === dataSource.value;
      }
    };

    const groupDataConstructionTypeFilter = function (group, dataConstructionType) {
      if (_.isArray(group)) {
        return _.some(group, function (roadLink) {
          if (roadLink)
            return roadLink.getData().lifecycleStatus === dataConstructionType.value;
          else return false;
        });
      } else {
        return group.getData().lifecycleStatus === dataConstructionType.value;
      }
    };

    this.getAll = function () {
      return _.map(roadLinks(), function (roadLink) {
        return roadLink.getData();
      });
    };

    this.setClickedLinearLocationId = function (linearlocationId) {
      clickedLinearLocationId = linearlocationId;
    };

    this.getUnaddressedRoadLinkGroups = function () {
      return _.map(_.flatten(_.flatten(unaddressedRoadLinkGroups)), function (roadLink) {
        return roadLink.getData();
      });
    };

    this.get = function (ids) {
      return _.map(ids, function (id) {
        return _.find(roadLinks(), function (road) {
          return road.getId() === id;
        });
      });
    };

    this.getByLinkId = function (ids) {
      const segments = _.filter(roadLinks(), function (road) {
        return road.getData().linkId === ids;
      });
      return segments;
    };

    this.getByRoadPartAndAddr = function (roadNumber, roadPart, addr) {
      return _.filter(roadLinks(), function (road) {
        return road.getData().roadNumber === roadNumber &&
                road.getData().roadPartNumber === roadPart &&
                (road.getData().addrMRange.start === addr || road.getData().addrMRange.end === addr);
      });
    };

    this.getByLinkIds = function (ids) {
      return _.filter(roadLinks(), function (road) {
        return ids.includes(road.getData().linkId);
      });
    };

    this.getByLinearLocationId = function (id) {
      const segments = _.filter(roadLinks(), function (road) {
        return road.getData().linearLocationId === id;
      });
      return segments;
    };

    this.getRoadLinkModelsByLinearLocationIds = function (ids) {
      return _.filter(roadLinks(), function (roadLink) {
        return ids.includes(roadLink.getData().linearLocationId);
      });
    };

    this.getGroupByLinkId = function (linkId) {
      return _.find(roadLinkGroups, function (roadLinkGroup) {
        return _.some(roadLinkGroup, function (roadLink) {
          return roadLink.getData().linkId === linkId;
        });
      });
    };

    this.getGroupByLinearLocationId = function (linearLocationId) {
      return _.find(roadLinkGroups, function (roadLinkGroup) {
        return _.some(roadLinkGroup, function (roadLink) {
          return roadLink.getData().linearLocationId === linearLocationId;
        });
      });
    };

    const setRoadLinkGroups = function (groups) {
      roadLinkGroups = groups;
    };

    this.setSelectedRoadLinkModels = function (selectedRoadLinks) {
      selectedRoadLinkModels = selectedRoadLinks;
    };

    this.reset = function () {
      roadLinkGroups = [];
    };

    this.findReservedProjectLinks = function (boundingBox, zoomLevel, projectId) {
      backend.getProjectLinks({
        boundingBox: boundingBox,
        zoom: zoomLevel,
        projectId: projectId
      }, function (fetchedLinks) {
        const projectLinks = _.chain(fetchedLinks).flatten().filter(function (link) {
          return link.status === RoadAddressChangeType.NotHandled.value ||
              link.status === RoadAddressChangeType.New.value ||
              link.status === RoadAddressChangeType.Terminated.value ||
              link.status === RoadAddressChangeType.Unchanged.value ||
              link.status === RoadAddressChangeType.Numbering.value ||
              link.status === RoadAddressChangeType.Transfer.value;
        }).uniq().value();
        const projectLinkFeatures = _.map(projectLinks, function (road) {
          const points = _.map(road.points, function (point) {
            return [point.x, point.y];
          });
          const feature = new ol.Feature({
            geometry: new ol.geom.LineString(points)
          });
          feature.linkData = road;
          feature.projectId = projectId;
          return feature;
        });
        eventbus.trigger('linkProperties:highlightReservedRoads', projectLinkFeatures);
      });
    };
}
