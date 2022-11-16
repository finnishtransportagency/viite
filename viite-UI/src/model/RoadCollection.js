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
    var unaddressedUnknownRoadLinkGroups = [];
    var currentZoom = -1;
    var roadLinkGroups = [];
    var unaddressedUnderConstructionRoadLinkGroups = [];
    var unaddressedRoadLinkGroups = [];
    var tmpRoadLinkGroups = [];
    var preMovedRoadAddresses = [];
    var date = [];
    var historicRoadLinks = [];
    var LinkStatus = ViiteEnumerations.LinkStatus;
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

    this.getDate = function () {
      return date;
    };

    this.setDate = function (newDate) {
      date = newDate;
    };

    this.fetch = function (boundingBox, zoom) {
      var withHistory = date.length !== 0;
      var day = withHistory ? date[0] : -1;
      var month = withHistory ? date[1] : -1;
      var year = withHistory ? date[2] : -1;
      currentZoom = zoom;
      backend.getRoadLinks({
        boundingBox: boundingBox, zoom: zoom,
        withHistory: withHistory, day: day, month: month, year: year
      }, function (fetchedRoadLinks) {
        currentAllRoadLinks = fetchedRoadLinks;
        fetchProcess(fetchedRoadLinks, zoom);
      });
    };

    this.fetchWholeRoadPart = function (roadNumber, roadPart) {
      backend.getRoadLinksOfWholeRoadPart({
        roadNumber: roadNumber, roadPartNumber: roadPart
      }, function (fetchedRoadLinks) {
        updateGroupToContainWholeRoadPart(fetchedRoadLinks);
      });
    };

    this.fetchWithNodes = function (boundingBox, zoom, callback) {
      currentZoom = zoom;
      backend.getNodesAndJunctions({boundingBox: boundingBox, zoom: zoom}, function (fetchedNodesAndJunctions) {
        currentAllRoadLinks = fetchedNodesAndJunctions.fetchedRoadLinks;
        fetchProcess(currentAllRoadLinks, zoom);
        return (_.isFunction(callback) && callback(fetchedNodesAndJunctions.fetchedNodes)) || eventbus.trigger('node:fetched', fetchedNodesAndJunctions.fetchedNodes, zoom);
      });
    };

    eventbus.on("linkProperties:drawUnknowns", function () {
      fetchProcess(currentAllRoadLinks, currentZoom, true);
    });

    var updateGroupToContainWholeRoadPart = function (fetchedRoadLinks) {

      var fetchedRoadLinkModels = _.map(fetchedRoadLinks, function (roadLinkGroup) {
        return _.map(roadLinkGroup, function (roadLink) {
          return new RoadLinkModel(roadLink);
        });
      });

      // update the roadlink group (that was clicked) with the newly fetched road links (containing whole road part instead of just the visible part of the road part)
      updateGroup(clickedLinearLocationId, fetchedRoadLinkModels);

      eventbus.trigger('roadLinks:fetched:wholeRoadPart');
    };


    var fetchProcess = function (fetchedRoadLinks, zoom, drawUnknowns) {
      var selectedLinkIds = _.map(getSelectedRoadLinkModels(), function (roadLink) {
        return roadLink.getId();
      });
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

      unaddressedUnderConstructionRoadLinkGroups = unaddressedRoadLinkGroups[0];
      unaddressedUnknownRoadLinkGroups = unaddressedRoadLinkGroups[1];

      var includeUnknowns = _.isUndefined(drawUnknowns) && !drawUnknowns;
      if (parseInt(zoom) <= zoomlevels.minZoomForEditMode && (includeUnknowns && !applicationModel.selectionTypeIs(ViiteEnumerations.SelectionType.Unknown))) {
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

      historicRoadLinks = _.filter(roadLinkGroups, function (group) {
        return groupDataSourceFilter(group, LinkSource.HistoryLinkInterface);
      });

      var nonHistoryConstructionRoadLinkGroups = _.reject(roadLinkGroups, function (group) {
        return groupDataSourceFilter(group, LinkSource.HistoryLinkInterface);
      });

      setRoadLinkGroups(nonHistoryConstructionRoadLinkGroups);
      eventbus.trigger('roadLinks:fetched', nonHistoryConstructionRoadLinkGroups, (!_.isUndefined(drawUnknowns) && drawUnknowns), selectedLinkIds);
      if (historicRoadLinks.length !== 0) {
        eventbus.trigger('linkProperty:fetchedHistoryLinks', historicRoadLinks);
      }
      if (unaddressedUnderConstructionRoadLinkGroups.length !== 0)
        eventbus.trigger('underConstructionRoadLinks:fetched', unaddressedUnderConstructionRoadLinkGroups);
      if (unaddressedUnknownRoadLinkGroups.length !== 0)
        eventbus.trigger('unAddressedRoadLinks:fetched', unaddressedUnknownRoadLinkGroups);
      if (applicationModel.isProjectButton()) {
        eventbus.trigger('linkProperties:highlightSelectedProject', applicationModel.getProjectFeature());
        applicationModel.setProjectButton(false);
      }
      if (!_.isUndefined(drawUnknowns) && drawUnknowns) {
        eventbus.trigger('linkProperties:unknownsTreated');
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

    this.getUnderConstructionLinks = function () {
      return _.map(_.flatten(unaddressedUnderConstructionRoadLinkGroups), function (roadLink) {
        return roadLink.getData();
      });
    };

    this.getTmpRoadLinkGroups = function () {
      return tmpRoadLinkGroups;
    };

    this.getTmpByLinkId = function (ids) {
      var segments = _.filter(tmpRoadLinkGroups, function (road) {
        return road.getData().linkId === ids;
      });
      return segments;
    };

    this.getTmpById = function (ids) {
      return _.map(ids, function (id) {
        return _.find(tmpRoadLinkGroups, function (road) {
          return road.getData().id === id;
        });
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
                (road.getData().startAddressM === addr || road.getData().endAddressM === addr);
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

    this.addTmpRoadLinkGroups = function (tmp) {
      if (tmpRoadLinkGroups.filter(function (roadTmp) {
        return roadTmp.getData().linkId === tmp.linkId;
      }).length === 0) {
        tmpRoadLinkGroups.push(new RoadLinkModel(tmp));
      }
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

    this.addPreMovedRoadAddresses = function (ra) {
      preMovedRoadAddresses.push(ra);
    };

    this.resetPreMovedRoadAddresses = function () {
      preMovedRoadAddresses = [];
    };

    this.findReservedProjectLinks = function (boundingBox, zoomLevel, projectId) {
      backend.getProjectLinks({
        boundingBox: boundingBox,
        zoom: zoomLevel,
        projectId: projectId
      }, function (fetchedLinks) {
        var notHandledLinks = _.chain(fetchedLinks).flatten().filter(function (link) {
          return link.status === LinkStatus.NotHandled.value;
        }).uniq().value();
        var notHandledFeatures = _.map(notHandledLinks, function (road) {
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
        eventbus.trigger('linkProperties:highlightReservedRoads', notHandledFeatures);
      });
    };

    this.toRoadLinkModel = function (roadDataArray) {
      return _.map(roadDataArray, function (rda) {
        return new RoadLinkModel(rda);
      });
    };
  };
}(this));
