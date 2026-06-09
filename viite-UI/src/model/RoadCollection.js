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
import { redrawLinkPropertyLayer, highlightProject, highlightReservedRoads } from '@view/map/layers/LinkPropertyLayer.js';

const RoadLinkModel = function (data) {
  const getData = function () {
    return data;
  };

  return {
    getData: getData
  };
};

export function RoadCollection(backend) {
    let currentAllRoadLinks = [];
    let roadLinkGroups = [];
    const RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;
    const LinkSource = ViiteEnumerations.LinkGeomSource;
    let clickedLinearLocationId = 0;
    let selectedRoadLinkModels = [];
    let pendingProjectHighlightId;

    function setPendingHighlight(projectId) {
      pendingProjectHighlightId = projectId;
    }

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

      roadLinkGroups[indexOfGroupToBeUpdated] = fetchedGroupThatWasClicked;
    };

    function fetch(boundingBox, zoom) {
      backend.getRoadLinks({
        boundingBox: boundingBox, zoom: zoom
      }, function (fetchedRoadLinks) {
        currentAllRoadLinks = fetchedRoadLinks;
        fetchProcess(fetchedRoadLinks, zoom);
      });
    }

    function fetchWholeRoadPart(roadNumber, roadPart) {
      backend.getRoadLinksOfWholeRoadPart({
        roadNumber: roadNumber, roadPartNumber: roadPart
      }, function (fetchedRoadLinks) {
        updateGroupToContainWholeRoadPart(fetchedRoadLinks);
      });
    }

    function fetchWithNodes(boundingBox, zoom, callback) {
      backend.getNodesAndJunctions({boundingBox: boundingBox, zoom: zoom}, function (fetchedNodesAndJunctions) {
        currentAllRoadLinks = fetchedNodesAndJunctions.fetchedRoadLinks;
        fetchProcess(currentAllRoadLinks, zoom);
        return callback(fetchedNodesAndJunctions.fetchedNodes);
      });
    }

    const updateGroupToContainWholeRoadPart = function (fetchedRoadLinks) {
      const fetchedRoadLinkModels = _.map(fetchedRoadLinks, function (roadLinkGroup) {
        return _.map(roadLinkGroup, function (roadLink) {
          return new RoadLinkModel(roadLink);
        });
      });

      updateGroup(clickedLinearLocationId, fetchedRoadLinkModels);
    };


    const fetchProcess = function (fetchedRoadLinks, zoom) {
      const fetchedRoadLinkModels = _.map(fetchedRoadLinks, function (roadLinkGroup) {
        return _.map(roadLinkGroup, function (roadLink) {
          return new RoadLinkModel(roadLink);
        });
      });
      const fetchedWithAddresses = _.reject(fetchedRoadLinkModels, function (model) {
        return _.every(model, function (mod) {
          return mod.getData().roadNumber === 0;
        });
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
      redrawLinkPropertyLayer();

      if (!_.isUndefined(pendingProjectHighlightId)) {
        highlightProject(pendingProjectHighlightId);
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

    function getAll() {
      return _.map(roadLinks(), function (roadLink) {
        return roadLink.getData();
      });
    }

    function setClickedLinearLocationId(linearlocationId) {
      clickedLinearLocationId = linearlocationId;
    }

    function getByLinkId(ids) {
      const segments = _.filter(roadLinks(), function (road) {
        return road.getData().linkId === ids;
      });
      return segments;
    }

    function getByRoadPartAndAddr(roadNumber, roadPart, addr) {
      return _.filter(roadLinks(), function (road) {
        return road.getData().roadNumber === roadNumber &&
                road.getData().roadPartNumber === roadPart &&
                (road.getData().addrMRange.start === addr || road.getData().addrMRange.end === addr);
      });
    }

    function getByLinkIds(ids) {
      return _.filter(roadLinks(), function (road) {
        return ids.includes(road.getData().linkId);
      });
    }

    function getByLinearLocationId(id) {
      const segments = _.filter(roadLinks(), function (road) {
        return road.getData().linearLocationId === id;
      });
      return segments;
    }

    function getRoadLinkModelsByLinearLocationIds(ids) {
      return _.filter(roadLinks(), function (roadLink) {
        return ids.includes(roadLink.getData().linearLocationId);
      });
    }

    function getGroupByLinkId(linkId) {
      return _.find(roadLinkGroups, function (roadLinkGroup) {
        return _.some(roadLinkGroup, function (roadLink) {
          return roadLink.getData().linkId === linkId;
        });
      });
    }

    const setRoadLinkGroups = function (groups) {
      roadLinkGroups = groups;
    };

    function setSelectedRoadLinkModels(selectedRoadLinks) {
      selectedRoadLinkModels = selectedRoadLinks;
    }

    function reset() {
      roadLinkGroups = [];
    }

    function findReservedProjectLinks(boundingBox, zoomLevel, projectId) {
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
        highlightReservedRoads(projectLinkFeatures);
      });
    }

    return {
      fetch: fetch,
      fetchWholeRoadPart: fetchWholeRoadPart,
      fetchWithNodes: fetchWithNodes,
      getAll: getAll,
      setClickedLinearLocationId: setClickedLinearLocationId,
      getByLinkId: getByLinkId,
      getByRoadPartAndAddr: getByRoadPartAndAddr,
      getByLinkIds: getByLinkIds,
      getByLinearLocationId: getByLinearLocationId,
      getRoadLinkModelsByLinearLocationIds: getRoadLinkModelsByLinearLocationIds,
      getGroupByLinkId: getGroupByLinkId,
      getGroupByLinearLocationId: getGroupByLinearLocationId,
      setSelectedRoadLinkModels: setSelectedRoadLinkModels,
      reset: reset,
      findReservedProjectLinks: findReservedProjectLinks,
      setPendingHighlight: setPendingHighlight
    };
}
