(function (root) {
  root.Backend = function () {
    var self = this;
    var loadingProject;

    this.getRoadLinks = createCallbackRequestor(function (params) {
      var zoom = params.zoom;
      var boundingBox = params.boundingBox;
      var withHistory = params.withHistory;
      var day = params.day;
      var month = params.month;
      var year = params.year;
      if (!withHistory)
        return {
          url: 'api/viite/roadlinks?zoom=' + zoom + '&bbox=' + boundingBox
        };
      else
        return {
          url: 'api/viite/roadlinks?zoom=' + zoom + '&bbox=' + boundingBox + '&dd=' + day + '&mm=' + month + '&yyyy=' + year
        };
    });

    this.abortLoadingProject = (function () {
      if (loadingProject) {
        loadingProject.abort();
      }
    });

    this.getProjectLinks = createCallbackRequestor(function (params) {
      var zoom = params.zoom;
      var boundingBox = params.boundingBox;
      var projectId = params.projectId;
      return {
        url: 'api/viite/project/roadlinks?zoom=' + zoom + '&bbox=' + boundingBox + '&id=' + projectId
      };
    });

    this.revertChangesRoadlink = _.throttle(function (data, success, errorCallback) {
        $.ajax({
            contentType: "application/json",
            type: "PUT",
            url: "api/viite/roadlinks/roadaddress/project/revertchangesroadlink",
            data: JSON.stringify(data),
            dataType: "json",
            success: success,
            error: errorCallback
        });
    }, 1000);

    this.getRoadLinkByLinkId = _.throttle(function (linkId, callback) {
      return $.getJSON('api/viite/roadlinks/' + linkId, function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getNonOverridenVVHValuesForLink = _.throttle(function (linkId, callback) {
      return $.getJSON('api/viite/roadlinks/project/prefillfromvvh/' + linkId, function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getRoadLinkByMmlId = _.throttle(function (mmlId, callback) {
      return $.getJSON('api/viite/roadlinks/mml/' + mmlId, function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);


      this.getRoadName =
          _.debounce(function (roadNumber, projectID, callback) {
              if (projectID !== 0) {
                  return $.getJSON('api/viite/roadlinks/roadname/' + roadNumber + '/' + projectID, function (data) {
                      return _.isFunction(callback) && callback(data);
                  });
              }
          }, 500);


      this.getFloatingAdjacent = _.throttle(function (roadData, callback) {
      return $.getJSON('api/viite/roadlinks/adjacent?roadData=' + JSON.stringify(roadData), function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getTargetAdjacent = _.throttle(function (roadData, callback) {
      return $.getJSON('api/viite/roadlinks/adjacent/target?roadData=' + JSON.stringify(roadData), function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getAdjacentsFromMultipleSources = _.throttle(function (roadData, callback) {
      return $.getJSON('api/viite/roadlinks/multiSourceAdjacents?roadData=' + JSON.stringify(roadData), function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getTransferResult = _.throttle(function (dataTransfer, callback) {
      return $.getJSON('api/viite/roadlinks/transferRoadLink?data=' + JSON.stringify(dataTransfer), function (data) {
        return _.isFunction(callback) && callback(data);
      }).fail(function (obj) {
        eventbus.trigger('linkProperties:transferFailed', obj.status);
      });
    }, 1000);

    this.createRoadAddress = _.throttle(function (data, errorCallback) {
      $.ajax({
        contentType: "application/json",
        type: "PUT",
        url: "api/viite/roadlinks/roadaddress",
        data: JSON.stringify(data),
        dataType: "json",
        success: function (link) {
          eventbus.trigger('linkProperties:saved');
        },
        error: errorCallback
      });
    }, 1000);

    this.saveRoadAddressProject = _.throttle(function (data, success, failure) {
      $.ajax({
        contentType: "application/json",
        type: "PUT",
        url: "api/viite/roadlinks/roadaddress/project",
        data: JSON.stringify(data),
        dataType: "json",
        success: success,
        error: failure
      });
    }, 1000);

    this.createRoadAddressProject = _.throttle(function (data, success, failure) {
      $.ajax({
        contentType: "application/json",
        type: "POST",
        url: "api/viite/roadlinks/roadaddress/project",
        data: JSON.stringify(data),
        dataType: "json",
        success: success,
        error: failure
      });
    }, 1000);

    this.deleteRoadAddressProject = _.throttle(function (projectId, success, failure){
      $.ajax({
        contentType: "application/json",
        type: "DELETE",
        url: "api/viite/roadlinks/roadaddress/project",
        data: JSON.stringify(projectId),
        dataType: "json",
        success: success,
        error: failure
      });
    });

    this.sendProjectToTR = _.throttle(function (projectID, success, failure) {
      var Json = {
        projectID: projectID
      };
      $.ajax({
        contentType: "application/json",
        type: "POST",
        url: "api/viite/roadlinks/roadaddress/project/sendToTR",
        data: JSON.stringify(Json),
        dataType: "json",
        success: success,
        error: failure
      });
    }, 1000);

    this.checkIfRoadpartReserved = (function (roadNumber,startPart,endPart,projDate) {
      return $.get('api/viite/roadlinks/roadaddress/project/validatereservedlink/', {
        roadNumber: roadNumber,
        startPart: startPart,
        endPart: endPart,
        projDate: projDate
      })
        .then(function (x) {
          eventbus.trigger('roadPartsValidation:checkRoadParts', x);
        });
    });

    this.createProjectLinks = _.throttle(function (data, success, failure) {
      $.ajax({
        contentType: "application/json",
        type: "POST",
        url: "api/viite/roadlinks/roadaddress/project/links",
        data: JSON.stringify(data),
        dataType: "json",
        success: success,
        error: failure
      });
    }, 1000);

    this.updateProjectLinks = _.throttle(function (data, success, error) {
      $.ajax({
        contentType: "application/json",
        type: "PUT",
        url: "api/viite/roadlinks/roadaddress/project/links",
        data: JSON.stringify(data),
        dataType: "json",
        success: success,
        error: error
      });
    }, 1000);

    this.getCutLine = _.throttle(function (data, success, error) {
      $.ajax({
        contentType: "application/json",
        type: "POST",
        url: "api/viite/project/getCutLine",
        data: JSON.stringify(data),
        dataType: "json",
        success: success,
        error: error
      });
    }, 1000);

      this.directionChangeNewRoadlink = _.throttle(function (data, success, failure) {
          $.ajax({
              contentType: "application/json",
              type: "PUT",
              url: "api/viite/project/reverse",
              data: JSON.stringify(data),
              dataType: "json",
              success: success,
              error: failure
          });
      }, 1000);

    this.getRoadAddressProjects = _.throttle(function (callback) {
      return $.getJSON('api/viite/roadlinks/roadaddress/project/all', function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getProjectsWithLinksById = _.throttle(function (id, callback) {
      if (loadingProject) {
        loadingProject.abort();
      }
      loadingProject= $.getJSON('api/viite/roadlinks/roadaddress/project/all/projectId/' + id, function (data) {
        return _.isFunction(callback) && callback(data);
      });
      return loadingProject;
    }, 1000);

    this.getChangeTable = _.throttle(function (id,callback) {
      $.getJSON('api/viite/project/getchangetable/' + id, callback);
    }, 500);


    this.getUserRoles = function () {
      $.get('api/viite/user', function (response) {
        eventbus.trigger('userData:fetched', response);
      });
    };

    this.getStartupParametersWithCallback = function (callback) {
      var url = 'api/viite/startupParameters';
      $.getJSON(url, callback);
    };

    this.getRoadAddressProjectList = function () {
      $.get('api/viite/roadlinks/roadaddress/project/all', function (list) {
        eventbus.trigger('projects:fetched', list);
      });
    };

    this.getGeocode = function (address) {
      return $.post("vkm/geocode", { address: address }).then(function (x) { return JSON.parse(x); });
    };

    this.getCoordinatesFromRoadAddress = function (roadNumber, section, distance, lane) {
      return $.get("vkm/tieosoite", {tie: roadNumber, osa: section, etaisyys: distance, ajorata: lane})
        .then(function (x) { return JSON.parse(x); });
    };

    this.removeProjectLinkSplit = function (data, success, errorCallback) {
      $.ajax({
        contentType: "application/json",
        type: "DELETE",
        url: "api/viite/project/split",
        data: JSON.stringify(data),
        dataType: "json",
        success: success,
        error: errorCallback
      });
    };

    this.reOpenProject = function (projectId, success, errorCallback) {
      $.ajax({
        type: "DELETE",
        url: "api/viite/project/trid/" + projectId,
        success: success,
        error: errorCallback
      });
    };

    this.getPreSplitedData = _.throttle(function (data, linkId, success, errorCallback) {
        $.ajax({
          contentType: "application/json",
          type: "PUT",
          url: "api/viite/project/presplit/" + linkId,
          data: JSON.stringify(data),
          dataType: "json",
          success: success,
          error: errorCallback
        });
      }, 1000);

    this.saveProjectLinkSplit = _.throttle(function (data, linkId, success, errorCallback) {
     $.ajax({
       contentType: "application/json",
        type: "PUT",
        url: "api/viite/project/split/" + linkId,
        data: JSON.stringify(data),
        dataType: "json",
       success: success,
       error: errorCallback
     });
    }, 1000);

    this.getFloatingRoadAddresses = function () {
      return $.getJSON('api/viite/floatingRoadAddresses');
    };

    this.getRoadAddressErrors = function () {
      return $.getJSON('api/viite/roadAddressErrors');
    };

    function createCallbackRequestor(getParameters) {
      var requestor = latestResponseRequestor(getParameters);
      return function (parameter, callback) {
        requestor(parameter).then(callback);
      };
    }

    function latestResponseRequestor(getParameters) {
      var deferred;
      var requests = new Bacon.Bus();
      var responses = requests.debounceImmediate(500).flatMapLatest(function (params) {
        return Bacon.$.ajax(params, true);
      });

      return function () {
        if (deferred) { deferred.reject(); }
        deferred = responses.toDeferred();
        requests.push(getParameters.apply(undefined, arguments));
        return deferred.promise();
      };
    }

    //Methods for the UI Integrated Tests
    var mockedRoadLinkModel = function (data) {
      var selected = false;
      var original = _.clone(data);

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
        if (value != data[name]) {
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
        return !_.isUndefined(data.linkType) && !_.contains([8, 9, 21, 99], data.linkType);
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

    var afterSave = false;

    var resetAfterSave = function () {
      afterSave = false;
    };

    this.withRoadAddressProjects = function (returnData) {
      self.getRoadAddressProjects = function () {
        return returnData;
      };
      return self;
    };

    this.withLinkData = function (linkData, afterSaveLinkData) {

      var fetchedRoadLinkModels = function (fetchedRoadLinks) {
       return _.map(fetchedRoadLinks, function (roadLinkGroup) {
          return _.map(roadLinkGroup, function (roadLink) {
            return new mockedRoadLinkModel(roadLink);
          });
        });
      };
      self.getRoadLinks = function (boundingBox, callback) {
        if (afterSave) {
          callback(afterSaveLinkData);
        } else {
          callback(linkData);
        }
        eventbus.trigger('roadLinks:fetched', afterSave ? fetchedRoadLinkModels(afterSaveLinkData) : fetchedRoadLinkModels(linkData));
      };
      return self;
    };

    this.withUserRolesData = function (userRolesData) {
      self.getUserRoles = function () {
        eventbus.trigger('userData:fetched', userRolesData);
      };
      afterSave = false;
      return self;
    };

    this.withStartupParameters = function (startupParameters) {
      self.getStartupParametersWithCallback = function (callback) { callback(startupParameters); };
      return self;
    };

    this.withFloatingAdjacents = function (selectedFloatingData, selectedUnknownData) {
      self.getFloatingAdjacent= function (linkData, callback) {
        if (linkData.linkId === 1718151 || linkData.linkId === 1718152) {
          callback(selectedFloatingData);
        } else if (linkData.linkId === 500130202) {
          callback(selectedUnknownData);
        } else {
          callback([]);
        }
      };
      return self;
    };

    this.withGetTransferResult = function (simulationData) {
      self.getTransferResult = function (selectedRoadAddressData, callback) {
        callback(simulationData);
      };
      return self;
    };

    this.withRoadAddressCreation = function () {
      self.createRoadAddress = function (data) {
        afterSave = true;
        eventbus.trigger('linkProperties:saved');
      };
      return self;
    };

    this.withRoadAddressProjectData = function (roadAddressProjectData) {
      self.getRoadAddressProjectList = function () {
        eventbus.trigger('projects:fetched', roadAddressProjectData);
      };
      return self;
    };

    this.withRoadPartReserved = function (returnData) {
      self.checkIfRoadpartReserved = function () {
        eventbus.trigger('roadPartsValidation:checkRoadParts', returnData);
        return returnData;
      };
      return self;
    };
    this.withProjectLinks = function (returnData) {
      self.getProjectLinks = function (params, callback) {
        callback(returnData);
        return returnData;
      };
      return self;
    };

    this.withGetProjectsWithLinksById = function (returnData) {
      self.getProjectsWithLinksById = function (params, callback) {
        callback(returnData);
        return returnData;
      };
      return self;
    };

    this.withSaveRoadAddressProject = function (returnData){
      self.saveRoadAddressProject = function (){
        return returnData;
      };
      return self;
    };

    this.withCreateRoadAddressProject = function (returnData) {
      self.createRoadAddressProject = function (data, successCallback) {
        successCallback(returnData);
        return returnData;
      };
      return self;
    };

    this.withGetRoadLinkByLinkId = function (returnData) {
      self.getRoadLinkByLinkId = function (linkId, callback) {
        callback(returnData);
        return returnData;
      };
      return self;
    };

    this.withGetTargetAdjacent = function (returnData) {
      self.getTargetAdjacent = function (linkId, callback) {
        callback(returnData);
        return returnData;
      };
      return self;
    };

    this.withPreSplitData = function (returnData) {
      self.getPreSplitedData = function (data, linkId, callback) {
        callback(returnData);
        return returnData;
      };
      return self;
    };

      this.getDummyRoadAddressesByRoadNumber = function (roadNumber, callback) {
          //add API call here
          var dummyReturnObject = [{
              id: 0,
              roadNumber: 12345,
              roadNameFi: "AAAAAAA",
              startDate: "22-06-2018",
              endDate: "11-02-2000"
          }, {
              id: 1,
              roadNumber: 12345,
              roadNameFi: "AAAAAAA",
              startDate: "22-06-2018",
              endDate: ""
          }, {
              id: 2,
              roadNumber: 123456,
              roadNameFi: "BBBBBBBB",
              startDate: "22-06-2018",
              endDate: ""
          }];
          return callback(dummyReturnObject);
      };

      this.getRoadAddressesByRoadNumber = createCallbackRequestor(function (roadNumber) {
          return {
              url: 'api/viite/roadnames?roadNumber=' + roadNumber
          };
      });

      this.saveRoadNamesChanges = _.throttle(function (roadNumber, data, success, failure) {
          $.ajax({
              contentType: "application/json",
              type: "PUT",
              url: "api/viite/roadnames/" + roadNumber,
              data: JSON.stringify(data),
              dataType: "json",
              success: success,
              error: failure
          });
      }, 1000);

  };
}(this));
