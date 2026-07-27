/**
 * ProjectCollection - Manages road address projects and project links
 *
 * Handles project-related operations including:
 * - Project link management and retrieval
 * - Road part reservation and formation
 * - Project validation and publishing
 * - Backend integration for project operations
 * - Dirty state (unsaved) tracking and change management
 */
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { GeometryUtils } from '@utils/GeometryUtils.js';
import { getUserGeoLocation } from '@view/map/MapView.js';
import { lockProjectLinks, unlockProjectLinks } from '@view/map/layers/ProjectLinkLayer.js';
import { refreshRoadLayer } from '@view/map/layers/RoadLayer.js';

export function ProjectCollection(backend, startupParameters) {
	const noop = function () {};

	let projectErrors = [];
	let reservedParts = [];
	let formedParts = [];
	let coordinateButtons = [];
	let projectInfo;
	let currentProject;
	let fetchedProjectLinks = [];
	let dirtyProjectLinkIds = [];
	let dirtyProjectLinks = [];
	let publishableProject = false;
	const RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;
	const ProjectStatus = ViiteEnumerations.ProjectStatus;
	const Track = ViiteEnumerations.Track;
	const ALLOWED_ADDR_M_VALUE_PERCENTAGE = 0.2;
	let editedEndDistance = false;
	let editedBeginDistance = false;

	const resetEditedDistance = function () {
		editedEndDistance = false;
		editedBeginDistance = false;
	};

	const projectLinkOperationSuccess = function (operation, response) {
		return {
			ok: true,
			operation: operation,
			response: response
		};
	};

	const projectLinkOperationFailure = function (message, details = null) {
		return {
			ok: false,
			message: message,
			details: details
		};
	};

	const normalizeProjectErrors = function (payload) {
		if (_.isArray(payload)) {
			return payload;
		}

		if (_.isObject(payload)) {
			const extractedErrors = _.find([
				payload.projectErrors,
				payload.validationErrors,
				_.get(payload, 'project.projectErrors'),
				_.get(payload, 'project.validationErrors')
			], _.isArray);

			return _.isArray(extractedErrors) ? extractedErrors : [];
		}

		return [];
	};

	const projectLinks = function () {
		return _.flatten(fetchedProjectLinks);
	};

	function getProjectLinks() {
		return backend.getProjectLinksById(currentProject.project.id);
	}

	function getAll() {
		return _.map(projectLinks(), function (projectLink) {
			return projectLink.getData();
		});
	}

	function reset() {
		fetchedProjectLinks = [];
	}

	function getMultiProjectLinks(id) {
		const chain = _.find(fetchedProjectLinks, function (linkChain) {
			const pureChain = _.map(linkChain, function (l) {
				return l.getData();
			});
			return _.some(pureChain, { id: id }) || _.some(pureChain, { linkId: id });
		});

		return _.map(chain, function (link) {
			if (link.getData().id > 0) {
				return link.getData().id;
			}
			return link.getData().linkId;
		});
	}

	function getProjectLink(ids) {
		return _.filter(projectLinks(), function (projectLink) {
			if (projectLink.getData().id > 0) {
				return _.includes(ids, projectLink.getData().id);
			}
			return _.includes(ids, projectLink.getData().linkId);
		});
	}

	function fetch(boundingBox, zoom, projectId, isPublishable) {
		let id = projectId;
		if (typeof id === 'undefined' && typeof projectInfo !== 'undefined') {
			id = projectInfo.id;
		}
		if (id) {
			backend.abortGettingRoadLinks();
		}

		return new Promise(function (resolve) {
			backend.getProjectLinks({ boundingBox: boundingBox, zoom: zoom, projectId: id }, function (fetchedLinks) {
				fetchedProjectLinks = _.map(fetchedLinks, function (projectLinkGroup) {
					return _.map(projectLinkGroup, function (projectLink) {
						return new ProjectLinkModel(projectLink);
					});
				});
				publishableProject = isPublishable;
				Spinner.hide();
				resolve();
			});
		});
	}

	function getProjects(onlyActive, onProjectsFetched = noop) {
		return backend.getRoadAddressProjects(onlyActive, function (projects) {
			onProjectsFetched(projects);
		});
	}

	function getProjectStates(projectIDs, onProjectStatesFetched = noop) {
		if (projectIDs.length > 0) {
			return backend.getRoadAddressProjectStates(projectIDs, function (projects) {
				onProjectStatesFetched(projects);
			});
		}
		return null;
	}

	function getProjectsWithLinksById(projectId, onProjectFetched = noop, onSetRecalculatedAfterChangesFlag = noop) {
		return backend.getProjectsWithLinksById(projectId, function (result) {
			currentProject = result;
			projectInfo = {
				id: result.project.id,
				publishable: result.publishable
			};
			setAndWriteProjectErrorsToUser(result);
			setReservedParts(result.reservedInfo);
			setFormedParts(result.formedInfo);
			publishableProject = result.publishable;
			onProjectFetched(projectInfo);
			onSetRecalculatedAfterChangesFlag(false);
		});
	}

	function revertRoadAddressChangeType() {
		resetEditedDistance();
		const fetchedLinks = getAll();
		dirtyProjectLinkIds.forEach(function (dirtyLink) {
			_.filter(fetchedLinks, { linkId: dirtyLink.id }).forEach(function (fetchedLink) {
				fetchedLink.status = dirtyLink.status;
			});
		});
	}

	function clearRoadAddressProjects() {
		fetchedProjectLinks = [];
		reservedParts = [];
		formedParts = [];
		dirtyProjectLinkIds = [];
		dirtyProjectLinks = [];
		currentProject = undefined;
		projectInfo = undefined;
		backend.abortLoadingProject();
	}

	function saveProject(data, resolution, callbacks = {}) {
		const onProjectSaved = callbacks.onProjectSaved || noop;
		const onProjectValidationFailed = callbacks.onProjectValidationFailed || noop;
		const onProjectFailed = callbacks.onProjectFailed || noop;
		let projectId = 0;
		if (projectInfo !== undefined) {
			projectId = projectInfo.id;
		} else if (currentProject !== undefined && currentProject.project.id !== undefined) {
			projectId = currentProject.project.id;
		}

		const dataJson = {
			id: projectId,
			projectEly: currentProject.project.ely,
			status: currentProject.project.statusCode,
			name: data[0].value,
			startDate: data[1].value,
			additionalInfo: data[2].value,
			reservedPartList: _.map(_.filter(getReservedParts(), function (part) {
				return !_.isUndefined(part.currentLength, part.currentEly);
			}), function (part) {
				return {
					discontinuity: (part.currentDiscontinuity),
					evk: (part.currentEvk),
					roadLength: (part.currentLength),
					roadNumber: part.roadNumber,
					roadPartId: 0,
					roadPartNumber: part.roadPartNumber,
					startingLinkId: part.startingLinkId
				};
			}),
			formedPartList: _.map(_.filter(getFormedParts(), function (part) {
				return !_.isUndefined(part.newLength, part.newEly);
			}), function (part) {
				return {
					discontinuity: (part.newDiscontinuity),
					evk: (part.newEvk),
					roadLength: (part.newLength),
					roadNumber: part.roadNumber,
					roadPartId: 0,
					roadPartNumber: part.roadPartNumber,
					startingLinkId: part.startingLinkId
				};
			}),
			resolution: resolution
		};

		backend.saveRoadAddressProject(dataJson, function (result) {
			if (result.success) {
				projectInfo = {
					id: result.project.id,
					additionalInfo: result.project.additionalInfo,
					status: result.project.status,
					startDate: result.project.startDate,
					publishable: false
				};
				currentProject = result;
				setAndWriteProjectErrorsToUser(result);
				setReservedParts(result.reservedInfo);
				setFormedParts(result.formedInfo);
				onProjectSaved(result);
			} else {
				onProjectValidationFailed(result.errorMessage);
			}
		}, function () {
			onProjectFailed();
		});
	}

	function revertChangesRoadlink(links) {
		if (!_.isEmpty(links)) {
			const coordinates = getUserGeoLocation();
			const revertIds = _.uniq(links.filter(l => l.id > 0).map(l => l.id));
			const revertLinkIds = _.uniq(links.map(l => l.linkId).filter(Boolean));
			const data = {
				projectId: currentProject.project.id,
				roadNumber: links[0].roadNumber,
				roadPartNumber: links[0].roadPartNumber,
				links: _.map(links, function (link) {
					return { id: link.id, linkId: link.linkId, status: link.status };
				}),
				coordinates: coordinates
			};
			lockProjectLinks(revertIds, revertLinkIds);
			return new Promise(function (resolve) {
				backend.revertChangesRoadlink(data, function (response) {
					if (response.success) {
						dirtyProjectLinkIds = [];
						publishableProject = response.publishable;
						setAndWriteProjectErrorsToUser(response);
						setFormedParts(response.formedInfo);
						resolve(projectLinkOperationSuccess('reverted', response));
					} else {
						unlockProjectLinks();
						resolve(projectLinkOperationFailure(response.errorMessage, response));
					}
				});
			});
		}

		return null;
	}

	const createOrUpdate = function (dataJson) {
		const hasLinks = !_.isEmpty(dataJson.linkIds) || !_.isEmpty(dataJson.ids);
		const hasProject = typeof dataJson.projectId !== 'undefined' && dataJson.projectId !== 0;
		const hasValidRoadPart = dataJson.roadNumber !== 0 && dataJson.roadPartNumber !== 0;

		if (!hasLinks || !hasProject) {
			return Promise.resolve(
				projectLinkOperationFailure('Virhe linkin tallentamisessa')
			);
		}

		if (!hasValidRoadPart) {
			return Promise.resolve(projectLinkOperationFailure('Virheellinen tieosanumero'));
		}

		resetEditedDistance();

		const ids = dataJson.ids;
		const isCreate =
			dataJson.roadAddressChangeType === RoadAddressChangeType.New.value &&
			ids.length === 0;
		const operation = isCreate ? 'created' : 'updated';
		const backendOperation = isCreate
			? backend.createProjectLinks
			: backend.updateProjectLinks;

		lockProjectLinks(ids, dataJson.linkIds);

		return new Promise(function (resolve) {
			backendOperation(dataJson, function (successObject) {
				if (!successObject.success) {
					unlockProjectLinks();
					resolve(projectLinkOperationFailure(successObject.errorMessage, successObject));
					return;
				}

				publishableProject = successObject.publishable;
				setAndWriteProjectErrorsToUser(successObject);
				setFormedParts(successObject.formedInfo);
				resolve(projectLinkOperationSuccess(operation, successObject));
			});
		});
	};

	function saveProjectLinks(changedLinks, statusCode, touchedEndDistance, formData = null) {
		const validUserGivenAddrMValues = function (linkId, userEndAddr) {
			if (!_.isUndefined(userEndAddr) && userEndAddr !== null) {
				const roadPartIds = getMultiProjectLinks(linkId);
				const roadPartLinks = getProjectLink(_.map(roadPartIds, function (road) {
					return road;
				}));
				const startAddrFromChangedLinks = _.minBy(_.map(roadPartLinks, function (link) {
					return link.getData().addrMRange.start;
				}));
				const userDiffFromChangedLinks = userEndAddr - startAddrFromChangedLinks;
				const roadPartGeometries = _.map(roadPartLinks, function (roadPart) {
					return roadPart.getData().points;
				});
				const roadPartLength = _.reduce((roadPartGeometries), function (length, geom) {
					return GeometryUtils.geometryLength(geom) + length;
				}, 0.0);
				return (userDiffFromChangedLinks >= (roadPartLength * (1 - ALLOWED_ADDR_M_VALUE_PERCENTAGE))) && (userDiffFromChangedLinks <= (roadPartLength * (1 + ALLOWED_ADDR_M_VALUE_PERCENTAGE)));
			}
			return true;
		};

		const newAndOtherLinks = _.partition(changedLinks, function (l) {
			return l.id === 0;
		});
		const newLinks = newAndOtherLinks[0];
		const otherLinks = newAndOtherLinks[1];

		const linkIds = _.uniq(_.map(newLinks, function (t) {
			if (t.linkId) {
				return t.linkId;
			}
			return 0;
		}));

		const ids = _.uniq(_.map(otherLinks, function (t) {
			if (t.id) {
				return t.id;
			}
			return 0;
		}));

		const projectId = projectInfo.id;
		const coordinates = getUserGeoLocation();
		const roadAddressProjectForm = $('#roadAddressProjectForm');
		const endDistance = formData ? { value: formData.endDistance } : ($('#endDistance')[0] || null);
		const hasDevRights = _.includes(startupParameters.roles, 'dev');

		const getFormValue = (selector) => {
			if (formData && formData[selector] !== undefined) return formData[selector];
			const el = roadAddressProjectForm.find(selector)[0];
			return el ? el.value : null;
		};

		const getValueWithId = function (id) {
			const val = getFormValue(id);
			return val !== null && val !== '' ? Number(val) : null;
		};

		const startAddrMValue = getValueWithId('#addrStart');
		const endAddrMValue = getValueWithId('#addrEnd');
		const origStartAddrMValue = getValueWithId('#origAddrStart');
		const origEndAddrMValue = getValueWithId('#origAddrEnd');
		const startCp = getValueWithId('#startCPDropdown');
		const endCp = getValueWithId('#endCPDropdown');
		const sideCode = getValueWithId('#sideCodeDropdown');
		let generateNewRoadwayNumber;
		if (formData) {
			generateNewRoadwayNumber = formData.newRoadwayNumber !== undefined ? formData.newRoadwayNumber : null;
		} else {
			const el = roadAddressProjectForm.find('#newRoadwayNumber')[0];
			generateNewRoadwayNumber = el ? el.checked : null;
		}

		let devToolData = null;
		if (hasDevRights) {
			devToolData = {
				startAddrMValue: startAddrMValue,
				endAddrMValue: endAddrMValue,
				originalStartAddrMValue: origStartAddrMValue,
				originalEndAddrMValue: origEndAddrMValue,
				startCp: startCp,
				endCp: endCp,
				generateNewRoadwayNumber: generateNewRoadwayNumber,
				editedSideCode: sideCode
			};
		}

		const reversed = _.chain(changedLinks).map(function (c) {
			return c.reversed;
		}).reduceRight(function (a, b) {
			return a || b;
		}).value();
		let userDefinedEndAddressM = null;
		if (endDistance && touchedEndDistance) {
			userDefinedEndAddressM = (isNaN(Number(endDistance.value)) ? null : Number(endDistance.value));
		}

		const dataJson = {
			ids: ids,
			linkIds: linkIds,
			roadAddressChangeType: statusCode,
			projectId: projectId,
			roadNumber: Number(getFormValue('#tie')),
			roadPartNumber: Number(getFormValue('#osa')),
			trackCode: Number(getFormValue('#trackCodeDropdown')),
			discontinuity: Number(getFormValue('#discontinuityDropdown')),
			roadEly: Number(0),
			roadEvk: Number(getFormValue('#elinvoimakeskus')),
			roadLinkSource: Number(_.head(changedLinks).roadLinkSource),
			administrativeClass: Number(getFormValue('#administrativeClassDropdown')),
			userDefinedEndAddressM: userDefinedEndAddressM,
			coordinates: coordinates,
			roadName: getFormValue('#roadName'),
			reversed: reversed,
			devToolData: devToolData
		};
		if (dataJson.trackCode === Track.Unknown.value) {
			new ConfirmPopup('Tarkista ajoratakoodi', { type: 'alert' });
			return null;
		}

		const changedLink = _.chain(changedLinks).uniq().sortBy(function (cl) {
			return cl.endAddressM;
		}).last().value();
		const isNewRoad = changedLink.status === RoadAddressChangeType.New.value;

		const validUserEndAddress = !validUserGivenAddrMValues(_.head(dataJson.ids || dataJson.linkIds), dataJson.userDefinedEndAddressM);
		if (isNewRoad && (editedEndDistance || editedBeginDistance) && validUserEndAddress) {
			return new Promise(function (resolve) {
				new ConfirmPopup('Antamasi pituus eroaa yli 20% prosenttia geometrian pituudesta, haluatko varmasti tallentaa tämän pituuden?', {
					successCallback: function () {
						createOrUpdate(dataJson).then(resolve);
					},
					closeCallback: function () {
						Spinner.hide();
						resolve(null);
					}
				});
			});
		} else {
			return createOrUpdate(dataJson);
		}
	}

	function createProject(data, resolution, callbacks = {}) {
		const onProjectSaved = callbacks.onProjectSaved || noop;
		const onProjectValidationFailed = callbacks.onProjectValidationFailed || noop;
		const onProjectFailed = callbacks.onProjectFailed || noop;
		const roadPartList = _.map(reservedParts, function (part) {
			return {
				roadNumber: part.roadNumber,
				roadPartNumber: part.roadPartNumber,
				evk: (part.newEvk ? part.newEvk : part.currentEvk)
			};
		});

		const dataJson = {
			id: 0,
			status: 1,
			name: data[0].value,
			startDate: data[1].value,
			additionalInfo: data[2].value,
			reservedPartList: roadPartList,
			resolution: resolution
		};

		backend.createRoadAddressProject(dataJson, function (result) {
			if (result.success) {
				projectInfo = {
					id: result.project.id,
					additionalInfo: result.project.additionalInfo,
					status: result.project.status,
					startDate: result.project.startDate,
					publishable: false
				};
				currentProject = result;
				setAndWriteProjectErrorsToUser(result);
				setReservedParts(result.reservedInfo);
				setFormedParts(result.formedInfo);
				onProjectSaved(result);
			} else {
				onProjectValidationFailed(result.errorMessage);
			}
		}, function () {
			onProjectFailed();
		});
	}

	function deleteProject(projectId, callbacks = {}) {
		const onProjectDeleteFailed = callbacks.onProjectDeleteFailed || noop;
		const onProjectFailed = callbacks.onProjectFailed || noop;
		backend.deleteRoadAddressProject(projectId, function (result) {
			if (result.success) {
				currentProject = undefined;
			} else {
				onProjectDeleteFailed(result.errorMessage);
			}
		}, function () {
			onProjectFailed();
		});
	}

	function changeNewProjectLinkDirection(projectId, selectedLinks, callbacks = {}) {
		const onChangeProjectDirectionClicked = callbacks.onChangeProjectDirectionClicked || noop;
		const onChangeDirectionFailed = callbacks.onChangeDirectionFailed || noop;
		Spinner.show();
		const links = _.filter(selectedLinks, function (link) {
			return link.status !== RoadAddressChangeType.Terminated.value;
		});
		const coordinates = getUserGeoLocation();
		const dataJson = {
			projectId: projectId,
			roadNumber: selectedLinks[0].roadNumber,
			roadPartNumber: selectedLinks[0].roadPartNumber,
			links: links,
			coordinates: coordinates
		};
		resetEditedDistance();
		backend.directionChangeNewRoadlink(dataJson, function (successObject) {
			if (successObject.success) {
				setAndWriteProjectErrorsToUser(successObject);
				onChangeProjectDirectionClicked(successObject);
			} else {
				onChangeDirectionFailed(successObject.errorMessage);
				Spinner.hide();
			}
		});
	}

	function publishProject(callbacks = {}) {
		const onProjectSentSuccess = callbacks.onProjectSentSuccess || noop;
		const onProjectSentFailed = callbacks.onProjectSentFailed || noop;
		backend.sendProjectChangesToViite(
			projectInfo.id,
			function (result) {
				if (result.sendSuccess) {
					onProjectSentSuccess(result);
				} else {
					onProjectSentFailed(result.errorMessage);
				}
			},
			function (result) {
				onProjectSentFailed(result.status);
			}
		);
	}

	function getDeleteButton(index, roadNumber, roadPartNumber, selector) {
		return deleteButton(index, roadNumber, roadPartNumber, selector);
	}

	const deleteButton = function (index, roadNumber, roadPartNumber, selector) {
		const disabledInput = !_.isUndefined(currentProject) &&
      (currentProject.project.statusCode === ProjectStatus.InUpdateQueue.value ||
        currentProject.project.statusCode === ProjectStatus.UpdatingToRoadNetwork.value);
		return '<i roadNumber="' + roadNumber + '" roadPartNumber="' + roadPartNumber + '" id="' + index + '" class="delete mt-1 btn-delete ' + selector + ' fas fa-trash-alt fa-lg" style="position: absolute; left: 365px;" ' + (disabledInput ? 'disabled' : '') + '></i>';
	};

	const addToReservedPartList = function (queryResult) {
		const qRoadParts = [];
		_.each(queryResult.reservedInfo, function (row) {
			qRoadParts.push(row);
		});

		const sameElements = arrayIntersection(qRoadParts, reservedParts, function (arrayarow, arraybrow) {
			return arrayarow.roadNumber === arraybrow.roadNumber && arrayarow.roadPartNumber === arraybrow.roadPartNumber;
		});
		_.each(sameElements, function (row) {
			_.remove(qRoadParts, row);
		});
		_.each(qRoadParts, function (row) {
			reservedParts.push(row);
		});
	};

	function setDirty(editedRoadLinks) {
		dirtyProjectLinkIds = editedRoadLinks;
	}

	function getDirty() {
		return dirtyProjectLinkIds;
	}

	function getReservedParts() {
		return reservedParts;
	}

	function getFormedParts() {
		return formedParts;
	}

	function getRoadAddressesFromFormedRoadPart(roadNumber, roadPartNumber) {
		return _.map(_.filter(formedParts, function (part) {
			return part.roadNumber.toString() === roadNumber && part.roadPartNumber.toString() === roadPartNumber;
		}), 'roadAddresses');
	}

	function setReservedParts(list) {
		reservedParts = list;
	}

	function setFormedParts(list) {
		formedParts = list;
	}

	function setAndWriteProjectErrorsToUser(errors) {
		setProjectErrors(errors);
	}

	function setProjectErrors(errors) {
		projectErrors = normalizeProjectErrors(errors);
	}

	function clearProjectErrors() {
		projectErrors = [];
	}

	function getProjectErrors() {
		return projectErrors;
	}

	function pushCoordinates(button) {
		coordinateButtons.push(button);
	}

	function clearCoordinates(_button) {
		coordinateButtons = [];
	}

	function setTmpDirty(editRoadLinks) {
		dirtyProjectLinks = editRoadLinks;
	}


	function getTmpDirty() {
		return dirtyProjectLinks;
	}

	function isDirty() {
		return dirtyProjectLinks.length > 0;
	}

	function arrayIntersection(a, b, areEqualFunction) {
		return _.filter(a, function (aElem) {
			return _.some(b, function (bElem) {
				return areEqualFunction(aElem, bElem);
			});
		});
	}

	function startProject(projectId, onProjectFetched = function () {}) {
		return getProjectsWithLinksById(projectId, onProjectFetched);
	}

	function handleValidationResponse(validationResult) {
		const reservationValidationSucceeded = validationResult.success === true || validationResult.success === 'ok';
		if (reservationValidationSucceeded) {
			addToReservedPartList(validationResult);
			return { success: true, validationResult: validationResult };
		}
		return { success: false, error: validationResult.error || validationResult.success, validationResult: validationResult };
	}

	function clearProject() {
		clearRoadAddressProjects();
	}

	function clickCoordinates(event, map) {
		const currentCoordinates = map.getView().getCenter();
		const errorIndex = event.currentTarget.id;
		const errorCoordinates = _.find(coordinateButtons, function (b) {
			return b.index === parseInt(errorIndex, 10);
		}).coordinates;
		const index = _.findIndex(errorCoordinates, function (coordinates) {
			return coordinates.x === currentCoordinates[0] && coordinates.y === currentCoordinates[1];
		});
		if (index >= 0 && index + 1 < errorCoordinates.length) {
			map.getView().setCenter([errorCoordinates[index + 1].x, errorCoordinates[index + 1].y]);
			map.getView().setZoom(errorCoordinates[index + 1].zoom);
		} else {
			map.getView().setCenter([errorCoordinates[0].x, errorCoordinates[0].y]);
			map.getView().setZoom(errorCoordinates[0].zoom);
		}
	}

	function markEditedBeginDistance() {
		editedBeginDistance = true;
	}

	function markEditedEndDistance() {
		editedEndDistance = true;
	}

	function getCurrentProject() {
		return currentProject;
	}

	function setCurrentProject(project) {
		currentProject = project;
	}

	function getPublishableStatus() {
		return publishableProject;
	}

	function checkIfReserved(data, callbacks = {}) {
		const onProjectValidationSucceed = callbacks.onProjectValidationSucceed || noop;
		const onProjectValidationFailed = callbacks.onProjectValidationFailed || noop;
		return backend.checkIfRoadpartReserved(
			data.roadNumber === '' ? 0 : parseInt(data.roadNumber, 10),
			data.startPart === '' ? 0 : parseInt(data.startPart, 10),
			data.endPart === '' ? 0 : parseInt(data.endPart, 10),
			data.projectDate,
			data.projectId,
			function (validationResult) {
				const response = handleValidationResponse(validationResult);
				if (response.success) {
					onProjectValidationSucceed(validationResult);
				} else {
					onProjectValidationFailed(response.error);
				}
			}
		);
	}

	const ProjectLinkModel = function (data) {
		const getData = function () {
			return data;
		};

		return {
			getData: getData
		};
	};

	function reOpenProjectById(projectId, callbacks = {}) {
		const onReOpenedProject = callbacks.onReOpenedProject || noop;
		const onFailed = callbacks.onFailed || noop;
		backend.reOpenProject(projectId, function (successObject) {
			onReOpenedProject(successObject);
		}, function (errorObject) {
			onFailed(errorObject);
			if (errorObject.message) {
				new ConfirmPopup(errorObject.message.toString(), { type: 'alert' });
			} else {
				new ConfirmPopup(errorObject.statusText.toString(), { type: 'alert' });
			}
			Spinner.hide();
			console.error('Error at deleting rotatingId: ' + errorObject);
		});
	}

	function removeReservedPart(roadNumber, roadPartNumber) {
		if (currentProject) {
			currentProject.isDirty = true;
		}
		setReservedParts(_.filter(getReservedParts(), function (part) {
			return part.roadNumber.toString() !== roadNumber.toString() || part.roadPartNumber.toString() !== roadPartNumber.toString();
		}));
		removeRenumberedPart(roadNumber, roadPartNumber);
	}

	function removeFormedPart(roadNumber, roadPartNumber) {
		if (currentProject) {
			currentProject.isDirty = true;
		}
		_.each(getRoadAddressesFromFormedRoadPart(roadNumber, roadPartNumber), function (roadAddresses) {
			_.each(roadAddresses, function (ra) {
				removeFormedPart(ra.roadAddressNumber, ra.roadAddressPartNumber);
			});
		});
		setFormedParts(_.filter(getFormedParts(), function (part) {
			return part.roadNumber.toString() !== roadNumber.toString() || part.roadPartNumber.toString() !== roadPartNumber.toString();
		}));
	}

	const removeRenumberedPart = function (roadNumber, roadPartNumber) {
		setFormedParts(_.filter(getFormedParts(), function (part) {
			let reNumberedPart = false;
			if (part.roadAddresses && part.roadAddresses.length > 0) {
				for (let i = 0; i < part.roadAddresses.length; ++i) {
					const ra = part.roadAddresses[i];
					reNumberedPart = (ra.roadAddressNumber.toString() === roadNumber.toString() &&
            ra.roadAddressPartNumber.toString() === roadPartNumber.toString()) && ra.isNumbering;
					if (reNumberedPart) {
						break;
					}
				}
			}
			return !reNumberedPart;
		}));
	};

	return {
		getProjectLinks: getProjectLinks,
		getAll: getAll,
		reset: reset,
		getMultiProjectLinks: getMultiProjectLinks,
		getProjectLink: getProjectLink,
		fetch: fetch,
		getProjects: getProjects,
		getProjectStates: getProjectStates,
		getProjectsWithLinksById: getProjectsWithLinksById,
		revertRoadAddressChangeType: revertRoadAddressChangeType,
		clearRoadAddressProjects: clearRoadAddressProjects,
		saveProject: saveProject,
		revertChangesRoadlink: revertChangesRoadlink,
		saveProjectLinks: saveProjectLinks,
		createProject: createProject,
		deleteProject: deleteProject,
		changeNewProjectLinkDirection: changeNewProjectLinkDirection,
		publishProject: publishProject,
		getDeleteButton: getDeleteButton,
		setDirty: setDirty,
		getDirty: getDirty,
		getReservedParts: getReservedParts,
		getFormedParts: getFormedParts,
		getRoadAddressesFromFormedRoadPart: getRoadAddressesFromFormedRoadPart,
		setReservedParts: setReservedParts,
		setFormedParts: setFormedParts,
		setAndWriteProjectErrorsToUser: setAndWriteProjectErrorsToUser,
		setProjectErrors: setProjectErrors,
		clearProjectErrors: clearProjectErrors,
		getProjectErrors: getProjectErrors,
		pushCoordinates: pushCoordinates,
		clearCoordinates: clearCoordinates,
		setTmpDirty: setTmpDirty,
		getTmpDirty: getTmpDirty,
		isDirty: isDirty,
		startProject: startProject,
		handleValidationResponse: handleValidationResponse,
		clearProject: clearProject,
		clickCoordinates: clickCoordinates,
		markEditedBeginDistance: markEditedBeginDistance,
		markEditedEndDistance: markEditedEndDistance,
		getCurrentProject: getCurrentProject,
		setCurrentProject: setCurrentProject,
		getPublishableStatus: getPublishableStatus,
		checkIfReserved: checkIfReserved,
		reOpenProjectById: reOpenProjectById,
		removeReservedPart: removeReservedPart,
		removeFormedPart: removeFormedPart
	};
}
