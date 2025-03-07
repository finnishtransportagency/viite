(function (root) {
  var RoadLinkModel = function (data) {
    var selected = false;
    var original = _.cloneDeep(data);

    var getId = function () {
      return data.roadLinkId || data.linkId;
    };

    var getData = function () {
      return data;
    };

    var getPoints = function () {
      return _.cloneDeep(data.points);
    };

    var setLinkProperty = function (name, value) {
      if (value !== data[name]) {
        data[name] = value;
      }
    };

    var select = function () {
      selected = true;
    };

    var unselect = function () {
      selected = false;
    };

    var isSelected = function () {
      return selected;
    };

    var isCarTrafficRoad = function () {
      return !_.isUndefined(data.linkType) && !_.includes([8, 9, 21, 99], data.linkType);
    };

    var cancel = function () {
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

  root.RoadCollection = function (backend) {
    var currentAllRoadLinks = [];
    var roadLinkGroups = [];
    var unaddressedRoadLinkGroups = [];
    var RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;
    var LinkSource = ViiteEnumerations.LinkGeomSource;
    var lifecycleStatus = ViiteEnumerations.lifecycleStatus;
    var clickedLinearLocationId = 0;
    var selectedRoadLinkModels = [];

    var roadLinks = function () {
      return _.flatten(roadLinkGroups);
    };


    var getSelectedRoadLinkModels = function () {
      return selectedRoadLinkModels;
    };

    var getGroupByLinearLocationId = function (linearLocationId) {
      return _.find(roadLinkGroups, function (roadLinkGroup) {
        return _.some(roadLinkGroup, function (roadLink) {
          return roadLink.getData().linearLocationId === linearLocationId;
        });
      });
    };

    var updateGroup = function (linearLocationId, fetchedGroups) {
      var indexOfGroupToBeUpdated = roadLinkGroups.indexOf(getGroupByLinearLocationId(linearLocationId));

      var fetchedGroupThatWasClicked = _.find(fetchedGroups, function (roadLinkGroup) {
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
        return (_.isFunction(callback) && callback(fetchedNodesAndJunctions.fetchedNodes)) || eventbus.trigger('node:fetched', fetchedNodesAndJunctions.fetchedNodes, zoom);
      });
    };

    var updateGroupToContainWholeRoadPart = function (fetchedRoadLinks, selection) {
      var fetchedRoadLinkModels = _.map(fetchedRoadLinks, function (roadLinkGroup) {
        return _.map(roadLinkGroup, function (roadLink) {
          return new RoadLinkModel(roadLink);
        });
      });

      // update the roadlink group (that was clicked) with the newly fetched road links (containing whole road part instead of just the visible part of the road part)
      updateGroup(clickedLinearLocationId, fetchedRoadLinkModels);
      eventbus.trigger('roadCollection:wholeRoadPartFetched', selection);
    };


    var fetchProcess = function (fetchedRoadLinks, zoom) {
      var fetchedRoadLinkModels = _.map(fetchedRoadLinks, function (roadLinkGroup) {
        return _.map(roadLinkGroup, function (roadLink) {
          return new RoadLinkModel(roadLink);
        });
      });
      var [fetchedUnaddressed, fetchedWithAddresses] = _.partition(fetchedRoadLinkModels, function (model) {
        return _.every(model, function (mod) {
          return mod.getData().roadNumber === 0;
        });
      });

      unaddressedRoadLinkGroups = _.partition(fetchedUnaddressed, function (group) {
        return groupDataConstructionTypeFilter(group, lifecycleStatus.UnderConstruction);
      });

      if (parseInt(zoom) <= zoomlevels.minZoomForEditMode) {
        // only the fetched road links that have an address
        setRoadLinkGroups(fetchedWithAddresses);
      } else {
        // ALL fetched road links
        setRoadLinkGroups(fetchedRoadLinkModels);
      }

      // get the selected links that were not fetched (i.e. were not inside the bounding box) and add them to the roadLinkGroups
      if (!_.isEmpty(getSelectedRoadLinkModels())) {
        var nonFetchedLinksInSelection = _.reject(getSelectedRoadLinkModels(), function (selected) {
          var allGroups = _.map(_.flatten(fetchedRoadLinkModels), function (group) {
            return group.getData();
          });
          return _.includes(_.map(allGroups, 'linkId'), selected.getData().linkId);
        });
        setRoadLinkGroups(roadLinkGroups.concat(nonFetchedLinksInSelection));
      }

      var nonHistoryConstructionRoadLinkGroups = _.reject(roadLinkGroups, function (group) {
        return groupDataSourceFilter(group, LinkSource.HistoryLinkInterface);
      });

      setRoadLinkGroups(nonHistoryConstructionRoadLinkGroups);
      eventbus.trigger('roadLinks:fetched');
      if (applicationModel.isProjectButton()) {
        eventbus.trigger('linkProperties:highlightSelectedProject', applicationModel.getProjectFeature());
        applicationModel.setProjectButton(false);
      }
    };

    var groupDataSourceFilter = function (group, dataSource) {
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

    var groupDataConstructionTypeFilter = function (group, dataConstructionType) {
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
      var segments = _.filter(roadLinks(), function (road) {
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
      var segments = _.filter(roadLinks(), function (road) {
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

    var setRoadLinkGroups = function (groups) {
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
        var projectLinks = _.chain(fetchedLinks).flatten().filter(function (link) {
          return link.status === RoadAddressChangeType.NotHandled.value ||
              link.status === RoadAddressChangeType.New.value ||
              link.status === RoadAddressChangeType.Terminated.value ||
              link.status === RoadAddressChangeType.Unchanged.value ||
              link.status === RoadAddressChangeType.Numbering.value ||
              link.status === RoadAddressChangeType.Transfer.value;
        }).uniq().value();
        var projectLinkFeatures = _.map(projectLinks, function (road) {
          var points = _.map(road.points, function (point) {
            return [point.x, point.y];
          });
          var feature = new ol.Feature({
            geometry: new ol.geom.LineString(points)
          });
          feature.linkData = road;
          feature.projectId = projectId;
          return feature;
        });
        eventbus.trigger('linkProperties:highlightReservedRoads', projectLinkFeatures);
      });
    };
  };
}(this));
