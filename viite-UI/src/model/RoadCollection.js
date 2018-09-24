(function(root) {
  var RoadLinkModel = function(data) {
    var selected = false;
    var original = _.cloneDeep(data);

    var getId = function() {
      return data.roadLinkId || data.linkId;
    };

    var getData = function() {
      return data;
    };

    var getPoints = function() {
      return _.cloneDeep(data.points);
    };

    var setLinkProperty = function(name, value) {
      if (value != data[name]) {
        data[name] = value;
      }
    };

    var select = function() {
      selected = true;
    };

    var unselect = function() {
      selected = false;
    };

    var isSelected = function() {
      return selected;
    };

    var isCarTrafficRoad = function() {
      return !_.isUndefined(data.linkType) && !_.contains([8, 9, 21, 99], data.linkType);
    };

    var cancel = function() {
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

  root.RoadCollection = function(backend) {
      var currentAllRoadLinks = [];
      var unknownRoadLinkGroups = [];
      var currentZoom = -1;
    var roadLinkGroups = [];
    var roadLinkGroupsSuravage = [];
    var tmpRoadLinkGroups = [];
    var tmpRoadAddresses = [];
    var tmpNewRoadAddresses = [];
    var preMovedRoadAddresses = [];
    var date = [];
    var historicRoadLinks = [];
    var floatingRoadLinks = [];
    var changedIds = [];
    var LinkStatus = LinkValues.LinkStatus;
    var LinkSource = LinkValues.LinkGeomSource;
    var LinkType = LinkValues.RoadLinkType;

    var roadLinks = function() {
      return _.flatten(roadLinkGroups);
    };

    var getSelectedRoadLinks = function() {
      return _.filter(roadLinks().concat(suravageRoadLinks()), function(roadLink) {
        return roadLink.isSelected() && roadLink.getData().anomaly === 0;
      });
    };

    this.getDate = function() {
      return date;
    };

    this.setDate = function(newDate){
      date = newDate;
    };

    this.fetch = function(boundingBox, zoom) {
      var withHistory = date.length !== 0;
      var day = withHistory ? date[0] : -1;
      var month = withHistory ? date[1] : -1;
      var year = withHistory ? date[2] : -1;
        currentZoom = zoom;
      backend.getRoadLinks({boundingBox: boundingBox, zoom: zoom,
        withHistory: withHistory, day: day, month: month, year: year}, function(fetchedRoadLinks) {
          currentAllRoadLinks = fetchedRoadLinks;
          fetchProcess(fetchedRoadLinks, zoom);
      });
    };

      eventbus.on("linkProperties:drawUnknowns", function () {
        fetchProcess(currentAllRoadLinks, currentZoom, true);
      });

      var fetchProcess = function (fetchedRoadLinks, zoom, drawUnknowns) {
          var selectedLinkIds = _.map(getSelectedRoadLinks(), function(roadLink) {
              return roadLink.getId();
          });
          var fetchedRoadLinkModels = _.map(fetchedRoadLinks, function(roadLinkGroup) {
              return _.map(roadLinkGroup, function (roadLink) {
                  return new RoadLinkModel(roadLink);
              });
          });
          var fetched = _.partition(fetchedRoadLinkModels, function (model) {
              return _.every(model, function (mod) {
                  var modData = mod.getData();
                  return modData.anomaly === LinkValues.Anomaly.NoAddressGiven.value && modData.id === 0 || modData.anomaly === LinkValues.Anomaly.GeometryChanged.value;
              });
          });
          unknownRoadLinkGroups = fetched[0];
          var includeUnknowns = _.isUndefined(drawUnknowns) && !drawUnknowns;
          if (parseInt(zoom, 10) <= zoomlevels.minZoomForEditMode && (includeUnknowns && !applicationModel.selectionTypeIs(LinkValues.SelectionType.Unknown))) {
            setRoadLinkGroups(fetched[1]);
          } else {
            setRoadLinkGroups(fetchedRoadLinkModels);
          }

          if (!_.isEmpty(getSelectedRoadLinks())) {
              var nonFetchedLinksInSelection = _.reject(getSelectedRoadLinks(), function (selected) {
                  var allGroups = _.map(_.flatten(fetchedRoadLinkModels), function (group) {
                      return group.getData();
                  });
                  return _.contains(_.pluck(allGroups, 'linkId'), selected.getData().linkId);
              });
              roadLinkGroups.concat(nonFetchedLinksInSelection);
          }

          historicRoadLinks = _.filter(roadLinkGroups, function(group) {
              return groupDataSourceFilter(group, LinkSource.HistoryLinkInterface) && !groupLinkTypeFilter(group, LinkType.FloatingRoadLinkType);
          });

          floatingRoadLinks = _.filter(roadLinkGroups, function(group) {
              return groupDataSourceFilter(group, LinkSource.HistoryLinkInterface) && groupLinkTypeFilter(group, LinkType.FloatingRoadLinkType);
          });

          roadLinkGroupsSuravage = _.filter(roadLinkGroups, function(group) {
              return groupDataSourceFilter(group, LinkSource.SuravageLinkInterface);
          });
          var suravageRoadAddresses = _.partition(roadLinkGroupsSuravage, function(sur) {
              return findSuravageRoadAddressInGroup(sur);
          });
          var nonSuravageRoadLinkGroups = _.reject(roadLinkGroups, function(group) {
              return groupDataSourceFilter(group, LinkSource.HistoryLinkInterface) || groupDataSourceFilter(group, LinkSource.SuravageLinkInterface);
          });
        setRoadLinkGroups(nonSuravageRoadLinkGroups.concat(suravageRoadAddresses[0]).concat(floatingRoadLinks));
          applicationModel.removeSpinner();
          eventbus.trigger('roadLinks:fetched', nonSuravageRoadLinkGroups, (!_.isUndefined(drawUnknowns) && drawUnknowns), selectedLinkIds);
          if (historicRoadLinks.length !== 0) {
              eventbus.trigger('linkProperty:fetchedHistoryLinks', historicRoadLinks);
          }
          if (suravageRoadAddresses[1].length !== 0)
              eventbus.trigger('suravageRoadLinks:fetched', suravageRoadAddresses[1]);
          if (applicationModel.isProjectButton()) {
              eventbus.trigger('linkProperties:highlightSelectedProject', applicationModel.getProjectFeature());
              applicationModel.setProjectButton(false);
          }
          if (!_.isUndefined(drawUnknowns) && drawUnknowns) {
              eventbus.trigger('linkProperties:unknownsTreated');
          }
      };

    var findSuravageRoadAddressInGroup = function(group) {
      var groupData = _.map(group, function (data) {
        return data.getData();
      });
      var found = _.filter(groupData, function(grp) {
        var id = grp.id;
        var roadLinkSource = grp.roadLinkSource;
        return id !== 0 && roadLinkSource === LinkSource.SuravageLinkInterface.value;
      });
      return found.length !== 0;
    };

    var groupDataSourceFilter = function(group, dataSource){
      if(_.isArray(group)) {
        return _.some(group, function(roadLink) {
          if(roadLink !== null)
            return roadLink.getData().roadLinkSource === dataSource.value;
          else return false;
        });
      } else {
        return group.getData().roadLinkSource === dataSource.value;
      }
    };

    var groupLinkTypeFilter = function(group, dataSource) {
      if (_.isArray(group)) {
        return _.some(group, function(roadLink) {
          if (roadLink !== null)
            return roadLink.getData().roadLinkType === dataSource.value;
          else return false;
        });
      } else {
        return group.getData().roadLinkType === dataSource.value;
      }
    };

    var suravageRoadLinks = function() {
      return _.flatten(roadLinkGroupsSuravage);
    };

    this.getAll = function() {
      return _.map(roadLinks(), function(roadLink) {
        return roadLink.getData();
      });
    };

    this.getSuravageLinks = function() {
      return _.map(_.flatten(roadLinkGroupsSuravage), function(roadLink) {
        return roadLink.getData();
      });
    };

    this.getTmpRoadLinkGroups = function () {
      return tmpRoadLinkGroups;
    };

    this.getTmpByLinkId = function(ids) {
      var segments = _.filter(tmpRoadLinkGroups, function (road){
        return road.getData().linkId == ids;
      });
      return segments;
    };

    this.getTmpById = function(ids) {
      return _.map(ids, function(id) {
        return _.find(tmpRoadLinkGroups, function(road) { return road.getData().id === id; });
      });
    };

    this.get = function(ids) {
      return _.map(ids, function(id) {
        return _.find(roadLinks(), function(road) { return road.getId() === id; });
      });
    };

    this.getByLinkId = function(ids) {
      var segments = _.filter(roadLinks(), function (road){
        return road.getData().linkId == ids;
      });
      return segments;
    };

    this.getByLinearLocationId = function(id) {
      var segments = _.filter(roadLinks(), function (road){
        return road.getData().linearLocationId == id;
      });
      return segments;
    };

    this.getSuravageByLinkId = function(ids) {
      var segments = _.filter(suravageRoadLinks(), function (road){
        return road.getData().linkId == ids;
      });
      return segments;
    };

    this.getSuravageByLinearLocationId = function(ids) {
      return _.map(ids, function(id) {
        return _.find(suravageRoadLinks(), function(road) { return road.getData().linearLocationId === id; });
      });
    };

    this.getGroupByLinkId = function (linkId) {
      return _.find(roadLinkGroups, function(roadLinkGroup) {
        return _.some(roadLinkGroup, function(roadLink) {
          return roadLink.getData().linkId === linkId;
        });
      });
    };

    this.getSuravageGroupByLinearLocationId = function (id) {
      return _.find(roadLinkGroupsSuravage, function(roadLinkGroup) {
        return _.some(roadLinkGroup, function(roadLink) {
          return roadLink.getData().linearLocationId === id;
        });
      });
    };

      this.getSuravageGroupByLinkId = function (id) {
          return _.find(roadLinkGroupsSuravage, function(roadLinkGroup) {
              return _.some(roadLinkGroup, function(roadLink) {
                  return roadLink.getData().linkId === id;
              });
          });
      };


    this.getGroupByLinearLocationId = function (linearLocationId) {
      return _.find(roadLinkGroups, function(roadLinkGroup) {
        return _.some(roadLinkGroup, function(roadLink) {
          return roadLink.getData().linearLocationId === linearLocationId;
        });
      });
    };

    this.getSuravageGroupById = function (id) {
      return _.find(roadLinkGroupsSuravage, function(roadLinkGroup) {
        return _.some(roadLinkGroup, function(roadLink) {
          return roadLink.getData().id === id;
        });
      });
    };

    this.setTmpRoadAddresses = function (tmp){
      tmpRoadAddresses = tmp;
    };

    this.addTmpRoadLinkGroups = function (tmp) {
      if(tmpRoadLinkGroups.filter(function (roadTmp) { return roadTmp.getData().linkId === tmp.linkId;}).length === 0) {
        tmpRoadLinkGroups.push(new RoadLinkModel(tmp));
      }
    };

    this.setChangedIds = function (ids){
      changedIds = ids;
    };

    var setRoadLinkGroups = function(groups) {
      roadLinkGroups = groups;
    };

    this.reset = function(){
      roadLinkGroups = [];
    };
    this.resetTmp = function(){
      tmpRoadAddresses = [];
    };
    this.resetChangedIds = function(){
      changedIds = [];
    };

    this.setNewTmpRoadAddresses = function (tmp){
      tmpNewRoadAddresses = tmp;
    };

    this.resetNewTmpRoadAddresses = function(){
      tmpNewRoadAddresses = [];
    };

    this.addPreMovedRoadAddresses = function(ra){
      preMovedRoadAddresses.push(ra);
    };

    this.resetPreMovedRoadAddresses = function(){
      preMovedRoadAddresses = [];
    };
    
    this.findReservedProjectLinks = function(boundingBox, zoomLevel, projectId) {
      backend.getProjectLinks({boundingBox: boundingBox, zoom: zoomLevel, projectId: projectId}, function(fetchedLinks) {
        var notHandledLinks = _.chain(fetchedLinks).flatten().filter(function (link) {
          return link.status ===  LinkStatus.NotHandled.value;
        }).uniq().value();
        var notHandledOL3Features = _.map(notHandledLinks, function(road) {
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
        eventbus.trigger('linkProperties:highlightReservedRoads', notHandledOL3Features);
      });
    };

    this.toRoadLinkModel = function (roadDataArray) {
      return _.map(roadDataArray, function (rda) {
        return new RoadLinkModel(rda);
      });
    };

    eventbus.on('linkProperty:fetchedHistoryLinks',function (date){

    });
  };
})(this);
