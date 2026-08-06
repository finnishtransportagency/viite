export function Backend() {
	let loadingProject;
	const gettingRoadLinks = null;
	moment.locale('fi');

	const REQUEST_THROTTLE_MS = 1000; // Prevents spamming backend with requests when user is e.g. dragging the map around

	// Backend returns an array of EVK shortnames, but frontend expects numbers. So [EVK0] -> [0]
	const convertRoadMaintainersToNumbers = function(projects) {
		return projects.map(project => {
			if (project.roadMaintainers && Array.isArray(project.roadMaintainers)) {
				// Extract numbers from each string in roadMaintainers
				project.evks = project.roadMaintainers.map(rm => {
					const match = rm.match(/\d+/); // find digits in the string
					return match ? parseInt(match[0]) : null;
				}).filter(num => num !== null); // remove nulls if no number found
			} else {
				project.evks = [];
			}    
			return project;
		});
	};

	// ------------------------------------------------------
	// Request helpers (debounce / request coordination)
	// ------------------------------------------------------

	function createCallbackRequestor(getParameters) {
		const requestor = latestResponseRequestor(getParameters);
		return function (parameter, callback) {
			requestor(parameter).then(callback);
		};
	}

	function latestResponseRequestor(getParameters) {
		// Every call queued while debounce is pending must be settled once the request
		// actually fires; otherwise callers whose deferred got overwritten by a later
		// call would wait indefinitely (seen as requests hanging on slow connections).
		let pendingDeferreds = [];
		let request;
		let debounced;

		function doRequest() {
			if (request) request.abort();

			const deferredsToSettle = pendingDeferreds;
			pendingDeferreds = [];

			request = $.ajax(getParameters.apply(undefined, arguments))
				.done(function (result) {
					deferredsToSettle.forEach(function (deferred) { deferred.resolve(result); });
				})
				.fail(function (error) {
					deferredsToSettle.forEach(function (deferred) { deferred.reject(error); });
				});
		}

		return function () {
			const deferred = new $.Deferred();
			pendingDeferreds.push(deferred);

			if (!debounced) {
				debounced = _.debounce(doRequest, 200);
			}

			debounced.apply(undefined, arguments);

			return deferred;
		};
	}

	function convertDatetoSimpleDate(date) {
		return moment(date, 'DD.MM.YYYY').format("YYYY-MM-DD");
	}

	return {
		// ------------------------------------------------------
		// Road network endpoints
		// ------------------------------------------------------
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
		}, REQUEST_THROTTLE_MS),

		getRoadNetworkErrors: _.throttle(function (callback) {
			return $.get('api/viite/roadnetworkerrors', function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),

		// ------------------------------------------------------
		// Road address browser endpoints
		// ------------------------------------------------------
		getDataForRoadAddressBrowser: _.throttle(function (params, callback) {
			return $.get('api/viite/roadaddressbrowser', params, function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),
        
		getDataForRoadAddressChangesBrowser: _.throttle(function (params, callback) {
			return $.get('api/viite/roadaddresschangesbrowser', params, function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),
        
		// ------------------------------------------------------
		// Road link and map fetch endpoints
		// ------------------------------------------------------
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
		}, REQUEST_THROTTLE_MS),

		// ------------------------------------------------------
		// Abort / cancellation helpers
		// ------------------------------------------------------
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

		// ------------------------------------------------------
		// Project link and road address lookup endpoints
		// ------------------------------------------------------
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
		}, REQUEST_THROTTLE_MS),

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
		}, REQUEST_THROTTLE_MS),

		getProjectLinkByLinkId: _.throttle(function (linkId, callback) {
			return $.getJSON('api/viite/project/roadaddress/linkid/' + linkId, function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),

		getRoadAddressByLinkId: _.throttle(function (linkId, callback) {
			return $.getJSON('api/viite/roadaddress/linkid/' + linkId, function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),

		getPrefillValuesForLink: _.throttle(function (linkId, currentProjectId, callback) {
			return $.getJSON('api/viite/roadlinks/project/prefill?linkId=' + linkId + '&currentProjectId=' + currentProjectId, function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),

		getRoadLinkByMmlId: _.throttle(function (mmlId, callback) {
			return $.getJSON('api/viite/roadlinks/mml/' + mmlId, function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),

		getRoadLinkByMtkId: _.throttle(function (mtkId, callback) {
			return $.getJSON('api/viite/roadlinks/mtkid/' + mtkId, function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),

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

		// ------------------------------------------------------
		// Road address project lifecycle endpoints
		// ------------------------------------------------------
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
		}, REQUEST_THROTTLE_MS),

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
		}, REQUEST_THROTTLE_MS),

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
		}, REQUEST_THROTTLE_MS),

		checkIfRoadpartReserved: function (roadNumber, startPart, endPart, projDate, projectId, callback = function () {}) {
			return $.get('api/viite/roadlinks/roadaddress/project/validatereservedlink/', {
				roadNumber: roadNumber,
				startPart: startPart,
				endPart: endPart,
				projDate: convertDatetoSimpleDate(projDate),
				projectId: projectId
			}).then(function (data) {
				callback(data);
			});
		},

		// ------------------------------------------------------
		// Project link mutation endpoints
		// ------------------------------------------------------
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
		}, REQUEST_THROTTLE_MS),

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
		}, REQUEST_THROTTLE_MS),

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
		}, REQUEST_THROTTLE_MS),

		// ------------------------------------------------------
		// Project lists and validation endpoints
		// ------------------------------------------------------
		getRoadAddressProjects: _.throttle(function (onlyActive, callback) {
			return $.getJSON('api/viite/roadlinks/roadaddress/project/all/' + onlyActive, function (data) {
				const processedData = Array.isArray(data) ? convertRoadMaintainersToNumbers(data) : data;
				return _.isFunction(callback) && callback(processedData);
			});
		}, REQUEST_THROTTLE_MS),

		getRoadAddressProjectStates: _.throttle(function (projectIDs, callback) {
			// TODO: Fix 414 Request-URI Too Large.
			// This currently sends all project IDs in the URL path, which can exceed
			// proxy/server URI limits when the project list is large. Change to POST
			// with IDs in request body
			return $.getJSON('api/viite/roadlinks/roadaddress/project/states/' + projectIDs, function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),

		getProjectsWithLinksById: function (id, callback = function () {}) {
			// Abort any previous request
			if (loadingProject) {
				loadingProject.abort();
			}

			// Start new request
			loadingProject = $.getJSON('api/viite/roadlinks/roadaddress/project/all/projectId/' + id)
				.done(function (data) {
					callback(data);
				})
				.always(function () {
					// Clear reference when request completes
					loadingProject = null;
				});

			return loadingProject;
		},

		getChangeTable: _.throttle(function (id, callback) {
			$.getJSON('api/viite/project/getchangetable/' + id, callback);
		}, REQUEST_THROTTLE_MS),

		recalculateAndValidateProject: function (id, callback) {
			$.getJSON('api/viite/project/recalculateProject/' + id, callback);
		},

		validateProject: function (id, callback) {
			$.getJSON('api/viite/project/validateProject/' + id, callback);
		},

		// ------------------------------------------------------
		// Direct fetch endpoints
		// ------------------------------------------------------
		getJunctionPointEditableStatus: function (ids, callback) {
			return $.get('api/viite/junctions/getEditableStatusOfJunctionPoints?ids=' + ids, function (response) {
				return _.isFunction(callback) && callback(response);
			});
		},

		getUserRoles: function (callback) {
			return $.get('api/viite/user', function (response) {
				return _.isFunction(callback) && callback(response);
			}).fail(function (jqxhr, textStatus, error) {
				console.error('[getUserRoles] Request failed:', textStatus, error, 'status:', jqxhr.status);
			});
		},

		// ------------------------------------------------------
		// Startup and search endpoints
		// ------------------------------------------------------
		getRoadLinkDate: _.throttle(function (callback) {
			return $.get('api/viite/getRoadLinkDate', function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),

		getStartupParametersWithCallback: function (callback) {
			const url = 'api/viite/startupParameters';
			$.getJSON(url, callback);
		},

		getNotificationBanner: function (callback) {
			$.get('api/viite/notificationbanner', callback);
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

		// ------------------------------------------------------
		// Road name endpoints
		// ------------------------------------------------------
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
		}, REQUEST_THROTTLE_MS),

		// ------------------------------------------------------
		// Node and template endpoints
		// ------------------------------------------------------
		getNodesByRoadAttributes: _.throttle(function (roadAttributes, callback) {
			return $.get('api/viite/nodes', roadAttributes, function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),

		getTemplates: _.throttle(function (callback) {
			return $.get('api/viite/templates', function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),

		getNodePointTemplateById: _.throttle(function (nodePointTemplateId, callback) {
			return $.getJSON('api/viite/node-point-templates/' + nodePointTemplateId, function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),

		getJunctionTemplateById: _.throttle(function (junctionTemplateId, callback) {
			return $.getJSON('api/viite/junction-templates/' + junctionTemplateId, function (data) {
				return _.isFunction(callback) && callback(data);
			});
		}, REQUEST_THROTTLE_MS),

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
		}, REQUEST_THROTTLE_MS),

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
		}, REQUEST_THROTTLE_MS)
	};
}

