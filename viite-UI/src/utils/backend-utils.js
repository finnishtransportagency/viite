/* eslint-disable new-cap */
(function (root) {
  root.Backend = function () {
    var loadingProject;
    // var finnishDatePattern = /(\d{2})\.(\d{2})\.(\d{4})/;
    var gettingRoadLinks;
    moment.locale('fi');

    this.startLinkNetworkUpdate = _.throttle(function (data, success, failure) {
      $.ajax({
        contentType: "application/json",
        type: "POST",
        url: "api/viite/startLinkNetworkUpdate",
        data: JSON.stringify(data),
        dataType: "json",
        success: success,
        error: failure
      });
    }, 1000);

    this.getRoadNetworkErrors = _.throttle(function (callback) {
      return $.get('api/viite/roadnetworkerrors', function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    // TODO: Remove mock data
    this.getDataForRoadAddressBrowser = _.throttle(function (params, callback) {
      return $.get('api/viite/roadaddressbrowser', params, function (data) {
        // Add evk field to results array
        if (data && Array.isArray(data.results)) {
          data.results.forEach((item, index) => {
            item.evk = (index % 3) + 1; // 1,2,3,1,2,3...
          });
        }
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);
    
    this.getDataForRoadAddressChangesBrowser = _.throttle(function (params, callback) {
      return $.get('api/viite/roadaddresschangesbrowser', params, function (data) {
        if (data && Array.isArray(data.changeInfos)) {
          data.changeInfos.forEach((item, index) => {
            item.oldEvk = (index % 3) + 1;
            item.newEvk = ((index + 1) % 3) + 1;

          });
        }
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    // Old implementation without mock
    // this.getDataForRoadAddressBrowser = _.throttle(function (params, callback) {
    //   return $.get('api/viite/roadaddressbrowser', params, function (data) {
    //     return _.isFunction(callback) && callback(data);
    //   });
    // }, 1000);

    // this.getDataForRoadAddressChangesBrowser = _.throttle(function (params, callback) {
    //   return $.get('api/viite/roadaddresschangesbrowser', params, function (data) {
    //     return _.isFunction(callback) && callback(data);
    //   });
    // }, 1000);
    
    
    this.getRoadLinks = createCallbackRequestor(function (params) {
      var zoom = params.zoom;
      var boundingBox = params.boundingBox;
      return {
        url: 'api/viite/roadaddress?zoom=' + zoom + '&bbox=' + boundingBox,
        dataType: 'json',
        // TODO, Inject mock EVK values, remove later
        dataFilter: function (raw) {
          try {
            var parsed = JSON.parse(raw);
         //   var transformed = addMockEvkCodes(parsed);
            return JSON.stringify(parsed);
          } catch (e) {
            return raw;
          }
        }
      };
    });

    this.getRoadLinksOfWholeRoadPart = createCallbackRequestor(function (params) {
      var roadNumber = params.roadNumber;
      var roadPart = params.roadPartNumber;
      return {
        url: 'api/viite/roadlinks/wholeroadpart/?roadnumber=' + roadNumber + '&roadpart=' + roadPart,
        dataType: 'json',
        // TODO, Inject mock EVK values, remove later
        dataFilter: function (raw) {
          try {
            var parsed = JSON.parse(raw);
         //   var transformed = addMockEvkCodes(parsed);
            return JSON.stringify(parsed);
          } catch (e) {
            return raw;
          }
        }
      };
    });

    this.getNodesAndJunctions = _.throttle(function (params, callback) {
      var zoom = params.zoom;
      var boundingBox = params.boundingBox;

      return $.get('api/viite/nodesjunctions?zoom=' + zoom + '&bbox=' + boundingBox, function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 500);

    this.abortLoadingProject = (function () {
      if (loadingProject) {
        loadingProject.abort();
      }
    });

    this.abortGettingRoadLinks = (function () {
      if (gettingRoadLinks) {
        _.map(gettingRoadLinks.desc.args, function (r) {
          r.abort();
        });
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

    this.getProjectLinksById = _.throttle(function (projectId, callback) {
      return $.getJSON('api/viite/project/links/' + projectId, function (data) {
        // TODO EVK: Add mock EVK codes to the project links data, remove this later
    //    const dataWithEvk = addMockEvkCodes(data);
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

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

    this.getProjectLinkByLinkId = _.throttle(function (linkId, callback) {
      return $.getJSON('api/viite/project/roadaddress/linkid/' + linkId, function (data) {
        // TODO EVK: Add mock EVK codes, remove this later
      //  const dataWithEvk = addMockEvkCodes(data);
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getRoadAddressByLinkId = _.throttle(function (linkId, callback) {
      return $.getJSON('api/viite/roadaddress/linkid/' + linkId, function (data) {
        // TODO EVK: Add mock EVK codes, remove this later
      //  const dataWithEvk = addMockEvkCodes(data);
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getPrefillValuesForLink = _.throttle(function (linkId, currentProjectId, callback) {
      return $.getJSON('api/viite/roadlinks/project/prefill?linkId=' + linkId + '&currentProjectId=' + currentProjectId, function (data) {
        // TODO EVK: Add mock EVK codes, remove this later
      //  const dataWithEvk = addMockEvkCodes(data);
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getRoadLinkByMmlId = _.throttle(function (mmlId, callback) {
      return $.getJSON('api/viite/roadlinks/mml/' + mmlId, function (data) {
        // TODO EVK: Add mock EVK codes, remove this later
      //  const dataWithEvk = addMockEvkCodes(data);
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getRoadLinkByMtkId = _.throttle(function (mtkId, callback) {
      return $.getJSON('api/viite/roadlinks/mtkid/' + mtkId, function (data) {
        // TODO EVK: Add mock EVK codes, remove this later
      //  const dataWithEvk = addMockEvkCodes(data);
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);


    this.getRoadName =
      _.debounce(function (roadNumber, projectID, callback) {
        if (projectID !== 0 && roadNumber !== '') {
          return $.getJSON('api/viite/roadlinks/roadname/' + roadNumber + '/' + projectID, function (data) {
            // TODO EVK: Add mock EVK codes, remove this later
        //    const dataWithEvk = addMockEvkCodes(data);
            return _.isFunction(callback) && callback(data);
          });
        } else {
          $('#roadName').val('').change();
          $('#roadName').prop('disabled', false);
          return null;
        }
      }, 500);

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

    this.deleteRoadAddressProject = _.throttle(function (projectId, success, failure) {
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

    this.sendProjectChangesToViite = _.throttle(function (projectID, success, failure) {
      var Json = {
        projectID: projectID
      };
      $.ajax({
        contentType: "application/json",
        type: "POST",
        url: "api/viite/roadlinks/roadaddress/project/sendProjectChangesToViite",
        data: JSON.stringify(Json),
        dataType: "json",
        success: success,
        error: failure
      });
    }, 1000);

    this.checkIfRoadpartReserved = (function (roadNumber, startPart, endPart, projDate, projectId) {
      return $.get('api/viite/roadlinks/roadaddress/project/validatereservedlink/', {
        roadNumber: roadNumber,
        startPart: startPart,
        endPart: endPart,
        projDate: convertDatetoSimpleDate(projDate),
        projectId: projectId
      }).then(function (data) {
        eventbus.trigger('roadPartsValidation:checkRoadParts', data);
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

    this.getRoadAddressProjects = _.throttle(function (onlyActive, callback) {
      return $.getJSON('api/viite/roadlinks/roadaddress/project/all/' + onlyActive, function (data) {
        // TODO EVK: Add mock EVK codes, remove this later
       // const dataWithEvk = addMockEvkCodes(data);
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getRoadAddressProjectStates = _.throttle(function (projectIDs, callback) {
      return $.getJSON('api/viite/roadlinks/roadaddress/project/states/' + projectIDs, function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getProjectsWithLinksById = _.throttle(function (id, callback) {
      if (loadingProject) {
        loadingProject.abort();
      }
      loadingProject = $.getJSON('api/viite/roadlinks/roadaddress/project/all/projectId/' + id, function (data) {
        // TODO EVK: Add mock EVK codes, remove this later
      // const dataWithEvk = addMockEvkCodes(data);
        return _.isFunction(callback) && callback(data);
      });
      return loadingProject;
    }, 1000);

    this.getChangeTable = _.throttle(function (id, callback) {
      $.getJSON('api/viite/project/getchangetable/' + id, function(data) {
        // Add mock EVK data to source and target objects
        if (data && data.changeTable && data.changeTable.changeInfoSeq) {
          data.changeTable.changeInfoSeq.forEach(change => {
            if (change.source) {
              // Add mock EVK (1-3) based on road number to ensure consistency
              change.source.evk = (change.source.roadNumber % 3) + 1;
            }
            if (change.target) {
              // Add mock EVK (1-3) based on road number to ensure consistency
              change.target.evk = (change.target.roadNumber % 3) + 1;
            }
          });
        }
        callback(data);
      }).fail(function(error) {
        console.error('Error fetching change table:', error);
        callback({ error: 'Failed to load change table data' });
      });
    }, 500);

    this.recalculateAndValidateProject = function (id, callback) {
      $.getJSON('api/viite/project/recalculateProject/' + id, callback);
    };

    this.validateProject = function (id, callback) {
      $.getJSON('api/viite/project/validateProject/' + id, callback);
    };

    this.getJunctionPointEditableStatus = function (ids, jp) {
      $.get('api/viite/junctions/getEditableStatusOfJunctionPoints?ids=' + ids, function (response) {
        eventbus.trigger('junctionPoint:editableStatusFetched', response, jp);
      });
    };

    this.getUserRoles = function () {
      $.get('api/viite/user', function (response) {
        eventbus.trigger('userData:fetched', response);
      });
    };

    this.getRoadLinkDate = _.throttle(function (callback) {
      return $.get('api/viite/getRoadLinkDate', function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getStartupParametersWithCallback = function (callback) {
      var url = 'api/viite/startupParameters';
      $.getJSON(url, callback);
    };

    this.getSearchResults = function (searchString) {
      return $.get("api/viite/roadlinks/search", { search: searchString }).then(function (x) {
        return x;
      });
    };

    this.reOpenProject = function (projectId, success, errorCallback) {
      $.ajax({
        type: "POST",
        url: "api/viite/project/id/" + projectId,
        success: success,
        error: errorCallback
      });
    };

    function createCallbackRequestor(getParameters) {
      var requestor = latestResponseRequestor(getParameters);
      return function (parameter, callback) {
        requestor(parameter).then(callback);
      };
    }

    function latestResponseRequestor(getParameters) {
      var deferred;
      var request;

      function doRequest() {
        if (request)
          request.abort();

        request = $.ajax(getParameters.apply(undefined, arguments)).done(function (result) {
          deferred.resolve(result);
        });
        return deferred;
      }

      return function () {
        deferred = $.Deferred();
        _.debounce(doRequest, 200).apply(undefined, arguments);
        return deferred;
      };
    }

    function convertDatetoSimpleDate(date) {
      return moment(date, 'DD.MM.YYYY').format("YYYY-MM-DD");
    }

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

    this.getNodesByRoadAttributes = _.throttle(function (roadAttributes, callback) {
      return $.get('api/viite/nodes', roadAttributes, function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getTemplates = _.throttle(function (callback) {
      return $.get('api/viite/templates', function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getNodePointTemplateById = _.throttle(function (nodePointTemplateId, callback) {
      return $.getJSON('api/viite/node-point-templates/' + nodePointTemplateId, function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.getJunctionTemplateById = _.throttle(function (junctionTemplateId, callback) {
      return $.getJSON('api/viite/junction-templates/' + junctionTemplateId, function (data) {
        return _.isFunction(callback) && callback(data);
      });
    }, 1000);

    this.createNodeInfo = _.throttle(function (data, success, failure) {
      $.ajax({
        contentType: "application/json",
        type: "POST",
        url: "api/viite/nodes",
        data: JSON.stringify(data),
        dataType: "json",
        success: success,
        error: failure
      });
    }, 1000);

    this.updateNodeInfo = _.throttle(function (data, success, failure) {
      $.ajax({
        contentType: "application/json",
        type: "PUT",
        url: "api/viite/nodes/" + data.id,
        data: JSON.stringify(data),
        dataType: "json",
        success: success,
        error: failure
      });
    }, 1000);

  };
}(this));
