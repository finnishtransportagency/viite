/* eslint-disable new-cap */
import { eventbus } from '@utils/eventbus.js';

export function Backend() {
    let loadingProject;
    // var finnishDatePattern = /(\d{2})\.(\d{2})\.(\d{4})/;
    const gettingRoadLinks = null;
    moment.locale('fi');

    // Backend returns an array of EVK shortnames, but frontend expects numbers. So [EVK0] -> [0]
    const convertRoadMaintainersToNumbers = function(projects) {
      return projects.map(project => {
        if (project.roadMaintainers && Array.isArray(project.roadMaintainers)) {
          // Extract numbers from each string in roadMaintainers
          project.evks = project.roadMaintainers.map(rm => {
            const match = rm.match(/\d+/); // find digits in the string
            return match ? parseInt(match[0], 10) : null;
          }).filter(num => num !== null); // remove nulls if no number found
        } else {
          project.evks = [];
        }    
        return project;
      });
    };

    function createCallbackRequestor(getParameters) {
      const requestor = latestResponseRequestor(getParameters);
      return function (parameter, callback) {
        requestor(parameter).then(callback);
      };
    }

    function latestResponseRequestor(getParameters) {
      let deferred;
      let request;

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

    return {
        startLinkNetworkUpdate: _.throttle(function (data, success, failure) {
          $.ajax({
            contentType: "application/json",
            type: "POST",
            url: "api/viite/startLinkNetworkUpdate",
            data: JSON.stringify(data),
            dataType: "json",
            success: success,
            error: failure
          });
        }, 1000),

        getRoadNetworkErrors: _.throttle(function (callback) {
          return $.get('api/viite/roadnetworkerrors', function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),

        getDataForRoadAddressBrowser: _.throttle(function (params, callback) {
          return $.get('api/viite/roadaddressbrowser', params, function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),
        
        getDataForRoadAddressChangesBrowser: _.throttle(function (params, callback) {
          return $.get('api/viite/roadaddresschangesbrowser', params, function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),
        
        getRoadLinks: createCallbackRequestor(function (params) {
          const zoom = params.zoom;
          const boundingBox = params.boundingBox;
          return {
            url: 'api/viite/roadaddress?zoom=' + zoom + '&bbox=' + boundingBox,
            dataType: 'json'
          };
        }),

        getRoadLinksOfWholeRoadPart: createCallbackRequestor(function (params) {
          const roadNumber = params.roadNumber;
          const roadPart = params.roadPartNumber;
          return {
            url: 'api/viite/roadlinks/wholeroadpart/?roadnumber=' + roadNumber + '&roadpart=' + roadPart,
            dataType: 'json'
          };
        }),

        getNodesAndJunctions: _.throttle(function (params, callback) {
          const zoom = params.zoom;
          const boundingBox = params.boundingBox;

          return $.get('api/viite/nodesjunctions?zoom=' + zoom + '&bbox=' + boundingBox, function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 500),

        abortLoadingProject: function () {
          if (loadingProject) {
            loadingProject.abort();
          }
        },

        abortGettingRoadLinks: function () {
          if (gettingRoadLinks) {
            _.map(gettingRoadLinks.desc.args, function (r) {
              r.abort();
            });
          }
        },

        getProjectLinks: createCallbackRequestor(function (params) {
          const zoom = params.zoom;
          const boundingBox = params.boundingBox;
          const projectId = params.projectId;
          return {
            url: 'api/viite/project/roadlinks?zoom=' + zoom + '&bbox=' + boundingBox + '&id=' + projectId
          };
        }),

        getProjectLinksById: _.throttle(function (projectId, callback) {
          return $.getJSON('api/viite/project/links/' + projectId, function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),

        revertChangesRoadlink: _.throttle(function (data, success, errorCallback) {
          $.ajax({
            contentType: "application/json",
            type: "PUT",
            url: "api/viite/roadlinks/roadaddress/project/revertchangesroadlink",
            data: JSON.stringify(data),
            dataType: "json",
            success: success,
            error: errorCallback
          });
        }, 1000),

        getProjectLinkByLinkId: _.throttle(function (linkId, callback) {
          return $.getJSON('api/viite/project/roadaddress/linkid/' + linkId, function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),

        getRoadAddressByLinkId: _.throttle(function (linkId, callback) {
          return $.getJSON('api/viite/roadaddress/linkid/' + linkId, function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),

        getPrefillValuesForLink: _.throttle(function (linkId, currentProjectId, callback) {
          return $.getJSON('api/viite/roadlinks/project/prefill?linkId=' + linkId + '&currentProjectId=' + currentProjectId, function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),

        getRoadLinkByMmlId: _.throttle(function (mmlId, callback) {
          return $.getJSON('api/viite/roadlinks/mml/' + mmlId, function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),

        getRoadLinkByMtkId: _.throttle(function (mtkId, callback) {
          return $.getJSON('api/viite/roadlinks/mtkid/' + mtkId, function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),

        getRoadName: _.debounce(function (roadNumber, projectID, callback) {
          if (projectID !== 0 && roadNumber !== '') {
            return $.getJSON('api/viite/roadlinks/roadname/' + roadNumber + '/' + projectID, function (data) {
              return _.isFunction(callback) && callback(data);
            });
          } else {
            $('#roadName').val('').change();
            $('#roadName').prop('disabled', false);
            return null;
          }
        }, 500),

        saveRoadAddressProject: _.throttle(function (data, success, failure) {
          $.ajax({
            contentType: "application/json",
            type: "PUT",
            url: "api/viite/roadlinks/roadaddress/project",
            data: JSON.stringify(data),
            dataType: "json",
            success: success,
            error: failure
          });
        }, 1000),

        createRoadAddressProject: _.throttle(function (data, success, failure) {
          $.ajax({
            contentType: "application/json",
            type: "POST",
            url: "api/viite/roadlinks/roadaddress/project",
            data: JSON.stringify(data),
            dataType: "json",
            success: success,
            error: failure
          });
        }, 1000),

        deleteRoadAddressProject: _.throttle(function (projectId, success, failure) {
          $.ajax({
            contentType: "application/json",
            type: "DELETE",
            url: "api/viite/roadlinks/roadaddress/project",
            data: JSON.stringify(projectId),
            dataType: "json",
            success: success,
            error: failure
          });
        }),

        sendProjectChangesToViite: _.throttle(function (projectID, success, failure) {
          const Json = {
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
        }, 1000),

        checkIfRoadpartReserved: function (roadNumber, startPart, endPart, projDate, projectId, callback) {
          return $.get('api/viite/roadlinks/roadaddress/project/validatereservedlink/', {
            roadNumber: roadNumber,
            startPart: startPart,
            endPart: endPart,
            projDate: convertDatetoSimpleDate(projDate),
            projectId: projectId
          }).then(function (data) {
            if (_.isFunction(callback)) callback(data);
          });
        },

        createProjectLinks: _.throttle(function (data, success, failure) {
          $.ajax({
            contentType: "application/json",
            type: "POST",
            url: "api/viite/roadlinks/roadaddress/project/links",
            data: JSON.stringify(data),
            dataType: "json",
            success: success,
            error: failure
          });
        }, 1000),

        updateProjectLinks: _.throttle(function (data, success, error) {
          $.ajax({
            contentType: "application/json",
            type: "PUT",
            url: "api/viite/roadlinks/roadaddress/project/links",
            data: JSON.stringify(data),
            dataType: "json",
            success: success,
            error: error
          });
        }, 1000),

        directionChangeNewRoadlink: _.throttle(function (data, success, failure) {
          $.ajax({
            contentType: "application/json",
            type: "PUT",
            url: "api/viite/project/reverse",
            data: JSON.stringify(data),
            dataType: "json",
            success: success,
            error: failure
          });
        }, 1000),

        getRoadAddressProjects: _.throttle(function (onlyActive, callback) {
          return $.getJSON('api/viite/roadlinks/roadaddress/project/all/' + onlyActive, function (data) {
            const processedData = Array.isArray(data) ? convertRoadMaintainersToNumbers(data) : data;
            return _.isFunction(callback) && callback(processedData);
          });
        }, 1000),

        getRoadAddressProjectStates: _.throttle(function (projectIDs, callback) {
          // TODO: Fix 414 Request-URI Too Large.
          // This currently sends all project IDs in the URL path, which can exceed
          // proxy/server URI limits when the project list is large. Change to POST
          // with IDs in request body
          return $.getJSON('api/viite/roadlinks/roadaddress/project/states/' + projectIDs, function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),

        getProjectsWithLinksById: function (id, callback) {
          // Abort any previous request
          if (loadingProject) {
            loadingProject.abort();
          }

          // Start new request
          loadingProject = $.getJSON('api/viite/roadlinks/roadaddress/project/all/projectId/' + id)
            .done(function (data) {
              // eslint-disable-next-line callback-return
              if (_.isFunction(callback)) callback(data);
            })
            .always(function () {
              // Clear reference when request completes
              loadingProject = null;
            });

          return loadingProject;
        },

        getChangeTable: _.throttle(function (id, callback) {
          $.getJSON('api/viite/project/getchangetable/' + id, callback);
        }, 500),

        recalculateAndValidateProject: function (id, callback) {
          $.getJSON('api/viite/project/recalculateProject/' + id, callback);
        },

        validateProject: function (id, callback) {
          $.getJSON('api/viite/project/validateProject/' + id, callback);
        },

        getJunctionPointEditableStatus: function (ids, jp) {
          $.get('api/viite/junctions/getEditableStatusOfJunctionPoints?ids=' + ids, function (response) {
            eventbus.trigger('junctionPoint:editableStatusFetched', response, jp);
          });
        },

        getUserRoles: function () {
          $.get('api/viite/user', function (response) {
            eventbus.trigger('userData:fetched', response);
          });
        },

        getRoadLinkDate: _.throttle(function (callback) {
          return $.get('api/viite/getRoadLinkDate', function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),

        getStartupParametersWithCallback: function (callback) {
          const url = 'api/viite/startupParameters';
          $.getJSON(url, callback);
        },

        getSearchResults: function (searchString) {
          return $.get("api/viite/roadlinks/search", { search: searchString }).then(function (x) {
            return x;
          });
        },

        reOpenProject: function (projectId, success, errorCallback) {
          $.ajax({
            type: "POST",
            url: "api/viite/project/id/" + projectId,
            success: success,
            error: errorCallback
          });
        },

        getRoadAddressesByRoadNumber: createCallbackRequestor(function (roadNumber) {
          return {
            url: 'api/viite/roadnames?roadNumber=' + roadNumber
          };
        }),

        saveRoadNamesChanges: _.throttle(function (roadNumber, data, success, failure) {
          $.ajax({
            contentType: "application/json",
            type: "PUT",
            url: "api/viite/roadnames/" + roadNumber,
            data: JSON.stringify(data),
            dataType: "json",
            success: success,
            error: failure
          });
        }, 1000),

        getNodesByRoadAttributes: _.throttle(function (roadAttributes, callback) {
          return $.get('api/viite/nodes', roadAttributes, function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),

        getTemplates: _.throttle(function (callback) {
          return $.get('api/viite/templates', function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),

        getNodePointTemplateById: _.throttle(function (nodePointTemplateId, callback) {
          return $.getJSON('api/viite/node-point-templates/' + nodePointTemplateId, function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),

        getJunctionTemplateById: _.throttle(function (junctionTemplateId, callback) {
          return $.getJSON('api/viite/junction-templates/' + junctionTemplateId, function (data) {
            return _.isFunction(callback) && callback(data);
          });
        }, 1000),

        createNodeInfo: _.throttle(function (data, success, failure) {
          $.ajax({
            contentType: "application/json",
            type: "POST",
            url: "api/viite/nodes",
            data: JSON.stringify(data),
            dataType: "json",
            success: success,
            error: failure
          });
        }, 1000),

        updateNodeInfo: _.throttle(function (data, success, failure) {
          $.ajax({
            contentType: "application/json",
            type: "PUT",
            url: "api/viite/nodes/" + data.id,
            data: JSON.stringify(data),
            dataType: "json",
            success: success,
            error: failure
          });
        }, 1000)
    };
}
