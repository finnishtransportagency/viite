/**
 * ProjectLinkLayer component
 * Manages the vector layer for displaying project links with various visual states (terminated, not handled, etc.).
 * Handles link selection, highlighting, and interaction management.
 * @param {Object} map - OpenLayers map instance
 * @param {Object} projectCollection - Project collection manager
 * @param {Object} selectedProjectLinkProperty - Selected project link property manager
 * @returns {Object} Layer with redraw method
 */
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { eventbus } from '@utils/eventbus.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { ProjectLinkStyler } from '@view/map/ProjectLinkStyler.js';
import { navigateToRoadAddressProject } from '@src/router.js';
import { addLayers, clearLayers, drawProjectCalibrationMarkers, toggleLayersVisibility } from './LayerUtils.js';
import { ProjectLinkMarker } from '../markers/ProjectLinkMarker.js';
import { CalibrationPoint } from '../markers/CalibrationPointMarker.js';
import { getSelectedLayer, selectLayer, getRoadVisibility } from '@model/ApplicationModel.js';

let _instance = null;

export function fetchProjectLinksForCurrentMap() { return _instance.fetchProjectLinks(); }
export function openRoadAddressProject(projectSelected) { return _instance.openRoadAddressProject(projectSelected); }
export function clearOnProjectClose() { return _instance.clearOnProjectClose(); }
export function discardProjectLinkChanges() { return _instance.discardChanges(); }
export function highlightProjectLinkLayerFeatures() { return _instance.highlightProjectLinkLayerFeatures(); }
export function setProjectLinkDiscardChanges(handler) { return _instance.setDiscardChanges(handler); }

export function initProjectLinkLayer(map, projectCollection, selectedProjectLinkProperty) {
	const layerName = 'roadAddressProject';
	const eventListener = _.extend({}, Backbone.Events);

	const SideCode = ViiteEnumerations.SideCode;
	const RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;
	const RoadClass = ViiteEnumerations.RoadClass;
	const lifecycleStatus = ViiteEnumerations.lifecycleStatus;
	let isActiveLayer = false;
	let discardChangesHandler = function () {};

	let lockedLinkIds = [];
	const isLocked = function (linkData) {
		if (!linkData || lockedLinkIds.length === 0) return false;
		return _.includes(lockedLinkIds, linkData.id) || _.includes(lockedLinkIds, linkData.linkId);
	};
	const isLockedId = function (id) {
		return lockedLinkIds.length > 0 && _.includes(lockedLinkIds, id);
	};

	const lockProjectLinks = function (ids, linkIds) {
		lockedLinkIds = (ids || []).concat(linkIds || []);
	};

	const unlockProjectLinks = function () {
		lockedLinkIds = [];
		map.getViewport().style.cursor = '';
		redraw();
	};

	projectCollection.setProjectLinkLockHandlers({
		lockProjectLinks: lockProjectLinks,
		unlockProjectLinks: unlockProjectLinks
	});

	const projectLinkStyler = new ProjectLinkStyler();

	const calibrationPointVector = new ol.source.Vector({});
	const underConstructionRoadVector = new ol.source.Vector({});
	const directionMarkerVector = new ol.source.Vector({});
	const unAddressedRoadsRoadVector = new ol.source.Vector({});
	const projectLinkVector = new ol.source.Vector({});
	const notReservedInProjectVector = new ol.source.Vector({});
	const notHandledProjectLinkVector = new ol.source.Vector({});
	const terminatedProjectLinkVector = new ol.source.Vector({});

	const calibrationPointLayer = new ol.layer.Vector({
		source: calibrationPointVector,
		name: 'calibrationPointLayer',
		zIndex: ViiteEnumerations.ProjectModeZIndex.CalibrationPoint.value
	});

	const terminatedProjectLinkLayer = new ol.layer.Vector({
		source: terminatedProjectLinkVector,
		name: 'terminatedProjectLinkLayer',
		style: function (feature) {
			return projectLinkStyler.getTerminatedProjectLinksStyle(feature.linkData, map);
		}
	});

	const notHandledProjectLinksLayer = new ol.layer.Vector({
		source: notHandledProjectLinkVector,
		name: 'notHandledProjectLinksLayer',
		style: function (feature) {
			return projectLinkStyler.getNotHandledProjectLinksStyle(feature.linkData, map);
		}
	});

	const notReservedInProjectLayer = new ol.layer.Vector({
		source: notReservedInProjectVector,
		name: 'notReservedInProjectLayer',
		opacity: 0.4,
		style: function (feature) {
			return projectLinkStyler.getNotInProjectStyles(feature.linkData, map);
		}
	});

	const underConstructionRoadProjectLayer = new ol.layer.Vector({
		source: underConstructionRoadVector,
		name: 'underConstructionRoadProjectLayer',
		style: function (feature) {
			return projectLinkStyler.getUnderConstructionStyles(feature.linkData, map);
		}
	});

	const unAddressedRoadsProjectLayer = new ol.layer.Vector({
		source: unAddressedRoadsRoadVector,
		name: 'unAddressedRoadsProjectLayer',
		style: function (feature) {
			return projectLinkStyler.getUnAddressedStyles(feature.linkData, map);
		}
	});

	const projectLinkLayer = new ol.layer.Vector({
		source: projectLinkVector,
		name: layerName,
		style: function (feature) {
			return projectLinkStyler.getProjectLinkStyles(feature.linkData, map);
		}
	});

	const directionMarkerLayer = new ol.layer.Vector({
		source: directionMarkerVector,
		name: 'directionMarkerLayer',
		zIndex: ViiteEnumerations.ProjectModeZIndex.DirectionMarker.value
	});

	const layers = [notReservedInProjectLayer, terminatedProjectLinkLayer, unAddressedRoadsProjectLayer, underConstructionRoadProjectLayer, projectLinkLayer, notHandledProjectLinksLayer, calibrationPointLayer, directionMarkerLayer];

	const getSelectedId = function (selected) {
		if (!_.isUndefined(selected.id) && selected.id > 0) {
			return selected.id;
		} else {
			return selected.linkId;
		}
	};

	const fireDeselectionConfirmation = function (ctrlPressed, selection, clickType) {
		new ConfirmPopup('Haluatko poistaa tien valinnan ja hylätä muutokset?', {
			successCallback: function () {
				discardChangesHandler();
				if (!_.isUndefined(selection)) {
					if (clickType === 'single')
						showSingleClickChanges(ctrlPressed, selection);
					else
						showDoubleClickChanges(ctrlPressed, selection);
				}
			}
		});
	};

	const possibleStatusForSelection = [RoadAddressChangeType.NotHandled.value, RoadAddressChangeType.New.value, RoadAddressChangeType.Terminated.value, RoadAddressChangeType.Transfer.value, RoadAddressChangeType.Unchanged.value, RoadAddressChangeType.Numbering.value];

	const selectSingleClick = new ol.interaction.Select({
		layer: [projectLinkLayer, underConstructionRoadProjectLayer, unAddressedRoadsProjectLayer, notHandledProjectLinksLayer, terminatedProjectLinkLayer],
		condition: ol.events.condition.singleClick,
		style: function (feature) {
			if (feature.linkData) {
				if (projectRoadAddressChangeTypeIn(feature.linkData, possibleStatusForSelection) || feature.linkData.roadClass === RoadClass.NoClass.value ||
                    feature.linkData.lifecycleStatus === lifecycleStatus.UnderConstruction.value) {
					return projectLinkStyler.getSelectionLinkStyle(feature.linkData, map);
				}
			}
			return null;
		}
	});

	selectSingleClick.set('name', 'selectSingleClickInteractionPLL');

	selectSingleClick.on('select', function (event) {
		const modPressed = (event.mapBrowserEvent) ? (
			event.mapBrowserEvent.originalEvent.ctrlKey || event.mapBrowserEvent.originalEvent.metaKey
		) : false;
		const rawSelection = (event.mapBrowserEvent) ? map.forEachFeatureAtPixel(event.mapBrowserEvent.pixel, function (feature) {
			return feature;
		}) : event.selected;
		const selection = _.find(modPressed ? [rawSelection] : [rawSelection].concat(selectSingleClick.getFeatures().getArray()), function (selectionTarget) {
			if (selectionTarget)
				return !_.isUndefined(selectionTarget.linkData) && (
					projectRoadAddressChangeTypeIn(selectionTarget.linkData, possibleStatusForSelection) || selectionTarget.linkData.roadClass === RoadClass.NoClass.value);
			else return false;
		});
		if (selection && isLocked(selection.linkData)) {
			selectSingleClick.getFeatures().remove(selection);
			return;
		}
		const isDeselectClick = event.selected.length === 0;
		if (modPressed) {
			showDoubleClickChanges(modPressed, selection);
		} else if (!isDeselectClick) {
			showSingleClickChanges(modPressed, selection);
		} else {
			const selectedFeatures = event.deselected.concat(selectDoubleClick.getFeatures().getArray());
			clearHighlights();
			addFeaturesToSelection(selectedFeatures);

			if (projectCollection.isDirty()) {
				fireDeselectionConfirmation(modPressed, selection, 'single');
			} else {
				discardChangesHandler();
				if (!_.isUndefined(selection)) {
					showSingleClickChanges(modPressed, selection);
				}
			}
		}
		highlightProjectLinkLayerFeaturesInternal();
	});

	function showSingleClickChanges(ctrlPressed, selection) {
		if (ctrlPressed && !_.isUndefined(selection) && !_.isUndefined(selectedProjectLinkProperty.get())) {
			if (canBeAddedToSelection(selection.linkData)) {
				const clickedId = getSelectedId(selection.linkData);
				const clickedIds = projectCollection.getMultiProjectLinks(clickedId).filter(id => !isLockedId(id));
				let selectedLinkIds = _.map(selectedProjectLinkProperty.get(), function (selected) {
					return getSelectedId(selected);
				});
				if (_.includes(selectedLinkIds, clickedId)) {
					selectedLinkIds = _.without(selectedLinkIds, clickedIds);
				} else {
					selectedLinkIds = _.union(selectedLinkIds, clickedIds);
				}
				selectedProjectLinkProperty.openCtrl(selectedLinkIds);
			}
			highlightProjectLinkLayerFeaturesInternal();
			return;
		}

		// Single click without pending edits replaces selection and resets temporary dirty state.
		if (!_.isUndefined(selection) && !selectedProjectLinkProperty.isDirty()) {
			selectedProjectLinkProperty.clean();
			projectCollection.setTmpDirty([]);
			projectCollection.setDirty([]);
			const selectedId = getSelectedId(selection.linkData);
			const groupIds = projectCollection.getMultiProjectLinks(selectedId);
			const unlockedGroupIds = _.reject(groupIds, isLockedId);
			if (unlockedGroupIds.length > 0) {
				if (unlockedGroupIds.length === groupIds.length) {
					selectedProjectLinkProperty.open(selectedId, true);
				} else {
					selectedProjectLinkProperty.openCtrl(unlockedGroupIds);
				}
			}
			return;
		}

		discardChangesHandler();
	}

	const selectDoubleClick = new ol.interaction.Select({
		layer: [projectLinkLayer, underConstructionRoadProjectLayer, unAddressedRoadsProjectLayer, terminatedProjectLinkLayer, notHandledProjectLinksLayer],
		condition: ol.events.condition.doubleClick,
		style: function (feature) {
			if (projectRoadAddressChangeTypeIn(feature.linkData, possibleStatusForSelection) || feature.linkData.roadClass === RoadClass.NoClass.value ||
                feature.linkData.lifecycleStatus === lifecycleStatus.UnderConstruction.value) {
				return projectLinkStyler.getSelectionLinkStyle(feature.linkData, map);
			}
			return null;
		}
	});

	selectDoubleClick.set('name', 'selectDoubleClickInteractionPLL');

	selectDoubleClick.on('select', function (event) {
		const ctrlPressed = event.mapBrowserEvent.originalEvent.ctrlKey;
		const selection = _.find(event.selected, function (selectionTarget) {
			return (!_.isUndefined(selectionTarget.linkData) && (
				projectRoadAddressChangeTypeIn(selectionTarget.linkData, possibleStatusForSelection) ||
                selectionTarget.linkData.roadClass === RoadClass.NoClass.value ||
                selectionTarget.linkData.lifecycleStatus === lifecycleStatus.UnderConstruction.value)
			);
		});

		if (selection && isLocked(selection.linkData)) {
			selectDoubleClick.getFeatures().remove(selection);
			return;
		}
    console.log('selectDoubleClick.on select', { ctrlPressed, selection, isDirty: projectCollection.isDirty() });
		if (!projectCollection.isDirty()) {
			showDoubleClickChanges(ctrlPressed, selection);
		} else {
			const selectedFeatures = event.deselected.concat(selectSingleClick.getFeatures().getArray());
			clearHighlights();
			addFeaturesToSelection(selectedFeatures);
			fireDeselectionConfirmation(ctrlPressed, selection, 'double');
		}
		highlightProjectLinkLayerFeaturesInternal();
	});

	function showDoubleClickChanges(ctrlPressed, selection) {
		if (ctrlPressed && !_.isUndefined(selectedProjectLinkProperty.get())) {
			if (!_.isUndefined(selection) && canBeAddedToSelection(selection.linkData)) {
				let selectedLinkIds = _.map(selectedProjectLinkProperty.get(), function (selected) {
					return getSelectedId(selected);
				});
				if (_.includes(selectedLinkIds, getSelectedId(selection.linkData))) {
					selectedLinkIds = _.without(selectedLinkIds, getSelectedId(selection.linkData));
				} else {
					selectedLinkIds = selectedLinkIds.concat(getSelectedId(selection.linkData));
				}
				selectedProjectLinkProperty.openCtrl(selectedLinkIds);
			}
			highlightProjectLinkLayerFeaturesInternal();
		} else if (!_.isUndefined(selection) && !selectedProjectLinkProperty.isDirty()) {
			selectedProjectLinkProperty.clean();
			projectCollection.setTmpDirty([]);
			projectCollection.setDirty([]);
			selectedProjectLinkProperty.open(getSelectedId(selection.linkData));
		}
	}

	map.addInteraction(selectSingleClick);
	map.addInteraction(selectDoubleClick);

	function canBeAddedToSelection(selectionData) {
		if (selectedProjectLinkProperty.get().length === 0) {
			return true;
		}
		const currentlySelectedSample = _.head(selectedProjectLinkProperty.get());
		return selectionData.roadNumber === currentlySelectedSample.roadNumber &&
            selectionData.roadPartNumber === currentlySelectedSample.roadPartNumber &&
            selectionData.trackCode === currentlySelectedSample.trackCode &&
            (selectionData.administrativeClassId === currentlySelectedSample.administrativeClassId || selectionData.roadNumber === 0) && // unaddressed road can be added to selection even if their administrative class don't match
            selectionData.elyCode === currentlySelectedSample.elyCode;
	}

	function highlightProjectLinkLayerFeaturesInternal() {
		clearHighlights();
		const featuresToHighlight = [];
		_.each(projectLinkLayer.getSource().getFeatures()
			.concat(underConstructionRoadProjectLayer.getSource().getFeatures())
			.concat(unAddressedRoadsProjectLayer.getSource().getFeatures())
			.concat(notHandledProjectLinksLayer.getSource().getFeatures())
			.concat(terminatedProjectLinkLayer.getSource().getFeatures()), function (feature) {
			const canIHighlight = (!_.isUndefined(feature.linkData.linkId) || feature.linkData.status === RoadAddressChangeType.Terminated.value
				? selectedProjectLinkProperty.isSelected(getSelectedId(feature.linkData)) : false);
			if (canIHighlight) {
				featuresToHighlight.push(feature);
			}
		});
		addFeaturesToSelection(featuresToHighlight);
	}

	function addFeaturesToSelection(features) {
		const olUids = _.map(selectSingleClick.getFeatures().getArray(), function (feature) {
			return feature.ol_uid;
		});
		_.each(features, function (feature) {
			if (!_.includes(olUids, feature.ol_uid)) {
				selectSingleClick.getFeatures().push(feature);
				olUids.push(feature.ol_uid);
			}
		});
	}

	const zoomDoubleClickListener = function (event) {
		if (isActiveLayer) {
			_.defer(function () {
				if (!event.originalEvent.ctrlKey &&
                    selectedProjectLinkProperty.get().length === 0 && zoomlevels.getViewZoom(map) <= 13) {
					map.getView().setZoom(zoomlevels.getViewZoom(map) + 1);
				}
			});
		}
	};
	//This will control the double click zoom when there is no selection that activates
	map.on('dblclick', zoomDoubleClickListener);

	if (window.getSelection) {
		window.getSelection().removeAllRanges();
	} else if (document.selection) {
		document.selection.empty();
	}

	const addSelectInteractions = function () {
		removeSelectInteractions();
		map.addInteraction(selectDoubleClick);
		map.addInteraction(selectSingleClick);
	};

	function removeSelectInteractions() {
		map.removeInteraction(selectDoubleClick);
		map.removeInteraction(selectSingleClick);
	}

	eventListener.listenTo(eventbus, 'map:mouseMoved', function (event, pixel) {
		if (event.dragging) {
			return;
		}
		eventbus.trigger('overlay:update', event, pixel);
		if (lockedLinkIds.length > 0) {
			const hasLockedFeature = map.forEachFeatureAtPixel(pixel, function (feature) {
				return feature.linkData && isLocked(feature.linkData);
			});
			map.getViewport().style.cursor = hasLockedFeature ? 'wait' : '';
		} else {
			map.getViewport().style.cursor = '';
		}
	});

	const showLayer = function () {
	};

	const hideLayer = function () {
		clearLayers(layers);
	};

	function clearHighlights() {
		selectSingleClick.getFeatures().clear();
		selectDoubleClick.getFeatures().clear();
		map.updateSize();
	}

	const toggleSelectInteractions = function (activate, both) {
		selectDoubleClick.setActive(activate);
		if (both) {
			selectSingleClick.setActive(activate);
		}
	};

	function projectRoadAddressChangeTypeIn(projectLink, possibleStatus) {
		if (!_.isUndefined(possibleStatus) && !_.isUndefined(projectLink))
			return _.includes(possibleStatus, projectLink.status);
		else return false;
	}

	const onProjectLinksFetched = function () {
    redraw();
		_.defer(function () {
			highlightProjectLinkLayerFeaturesInternal();
			lockedLinkIds = [];
			map.getViewport().style.cursor = '';
			eventbus.trigger('roadAddressProject:fetched');
		});
	};

	const fetchProjectLinksWith = function (options = {}) {
		const boundingBox = _.isUndefined(options.boundingBox) ? map.getView().calculateExtent(map.getSize()).join(',') : options.boundingBox;
		const zoom = _.isUndefined(options.zoom) ? zoomlevels.getViewZoom(map) + 1 : options.zoom;
		let projectId = options.projectId;
		if (_.isUndefined(projectId)) {
			const currentProject = projectCollection.getCurrentProject();
			projectId = _.isUndefined(currentProject) ? undefined : currentProject.project.id;
		}
		const isPublishable = _.isUndefined(options.isPublishable) ? projectCollection.getPublishableStatus() : options.isPublishable;
		const onFetched = _.isUndefined(options.onFetched) ? onProjectLinksFetched : options.onFetched;

		projectCollection.fetch(boundingBox, zoom, projectId, isPublishable, onFetched);
	};

	/**
     * This function is responsible for adding features to the correct layers that they belong to.
     * */
	const redraw = function () {
		const addLinkFeaturesToLayer = function (links, destinationLayer) {
			_.map(links, function (link) {
				const points = _.map(link.points, function (point) {
					return [point.x, point.y];
				});
				const feature = new ol.Feature({
					geometry: new ol.geom.LineString(points)
				});
				feature.linkData = link;
				destinationLayer.getSource().addFeatures([feature]);
			});
		};

		clearLayers(layers);
		removeSelectInteractions();
		const cachedMarker = new ProjectLinkMarker(selectedProjectLinkProperty);

		if (getSelectedLayer() === 'roadAddressProject') {
			const [linksWithNoRoadNumber, linksWithRoadNumber] = _.partition(projectCollection.getAll(), function (projectRoad) {
				return projectRoad.roadNumber === 0;
			});

			const [underConstruction, unAddressed] = _.partition(linksWithNoRoadNumber, function (projectRoad) {
				return projectRoad.lifecycleStatus === lifecycleStatus.UnderConstruction.value;
			});

			const [outsideOfProjectLinks, inProjectWithRoadNumberLinks] = _.partition(linksWithRoadNumber, function (link) {
				return link.status === RoadAddressChangeType.Undefined.value;
			});

			const [notHandledLinks, othersInProject] = _.partition(inProjectWithRoadNumberLinks, function (link) {
				return link.status === RoadAddressChangeType.NotHandled.value;
			});

			const [terminatedLinks, restOfProjectLinks] = _.partition(othersInProject, function (link) {
				return link.status === RoadAddressChangeType.Terminated.value;
			});

			addLinkFeaturesToLayer(underConstruction, underConstructionRoadProjectLayer);
			addLinkFeaturesToLayer(unAddressed, unAddressedRoadsProjectLayer);
			addLinkFeaturesToLayer(outsideOfProjectLinks, notReservedInProjectLayer);
			addLinkFeaturesToLayer(notHandledLinks, notHandledProjectLinksLayer);
			addLinkFeaturesToLayer(terminatedLinks, terminatedProjectLinkLayer);
			addLinkFeaturesToLayer(restOfProjectLinks, projectLinkLayer);

			if (zoomlevels.getViewZoom(map) > zoomlevels.minZoomForDirectionalMarkers) {
				const addMarkersToLayer = function (links, layer) {
					const directionMarkers = _.filter(links, function (projectLink) {
						const acceptedLinks = projectLink.id !== 0;
						return acceptedLinks && projectLink.sideCode !== SideCode.Unknown.value && projectLink.addrMRange.end !== 0;
					});
					_.each(directionMarkers, function (directionLink) {
						cachedMarker.createProjectMarker(directionLink, function (marker) {
							layer.getSource().addFeature(marker);
						});
					});
				};
				addMarkersToLayer(linksWithRoadNumber, directionMarkerLayer);
			}

			if (zoomlevels.getViewZoom(map) >= zoomlevels.minZoomLevelForCalibrationPoints) {
				const actualCalibrationPoints = drawProjectCalibrationMarkers(linksWithRoadNumber.concat(underConstruction));
				_.each(actualCalibrationPoints, function (actualPoint) {
					const calMarker = new CalibrationPoint(actualPoint);
					calibrationPointLayer.getSource().addFeature(calMarker.getMarker(true));
				});
			}

			unAddressedRoadsProjectLayer.changed();
			terminatedProjectLinkLayer.changed();
			underConstructionRoadProjectLayer.changed();
			notReservedInProjectLayer.changed();
			notHandledProjectLinksLayer.changed();
			projectLinkLayer.changed();

			addSelectInteractions();
		}
	};

	const onRoadAddressProjectSelected = function (projId) {
		projectCollection.getProjectsWithLinksById(projId, function (projectInfo) {
			fetchProjectLinksWith({
				boundingBox: map.getView().calculateExtent(map.getSize()),
				zoom: zoomlevels.getViewZoom(map),
				projectId: projectInfo.id,
				isPublishable: projectInfo.publishable
			});
		});
	};

	const openRoadAddressProjectInternal = function (projectSelected) {
		this.project = projectSelected;
		navigateToRoadAddressProject(projectSelected.id);
		onRoadAddressProjectSelected(projectSelected.id);
		selectLayer(layerName);
	};

	eventListener.listenTo(eventbus, 'layer:selected', function (layer, previouslySelectedLayer) {
		isActiveLayer = layer === 'roadAddressProject';
		toggleSelectInteractions(isActiveLayer, true);
		if (isActiveLayer) {
			addSelectInteractions();
		} else {
			clearHighlights();
			removeSelectInteractions();
		}
		if (previouslySelectedLayer === 'roadAddressProject') {
			hideLayer();
			removeSelectInteractions();
		}
		projectLinkLayer.setVisible(isActiveLayer && getRoadVisibility());
		calibrationPointLayer.setVisible(isActiveLayer && getRoadVisibility());
	});

	eventListener.listenTo(eventbus, 'roadAddressProject:clearAndDisableInteractions', function () {
		clearHighlights();
		removeSelectInteractions();
	});

	function updateRoadVisibility() {
		toggleLayersVisibility([projectLinkLayer, calibrationPointLayer, directionMarkerLayer, notHandledProjectLinksLayer, terminatedProjectLinkLayer, notReservedInProjectLayer, underConstructionRoadProjectLayer, unAddressedRoadsProjectLayer], getRoadVisibility());
	}

	toggleLayersVisibility(layers, true);

	function setVisible(name, visible) {
		const layersByName = {
			underConstructionRoadLayer: underConstructionRoadProjectLayer,
			unAddressedRoadLayer: unAddressedRoadsProjectLayer
		};
		const targetLayer = layersByName[name];
		if (targetLayer) {
			targetLayer.setVisible(visible);
		}
	}

	addLayers(map, layers);

	_instance = {
		fetchProjectLinks: fetchProjectLinksWith,
		openRoadAddressProject: openRoadAddressProjectInternal,
		clearOnProjectClose: function () {
			clearHighlights();
			clearLayers(layers);
		},
		discardChanges: function () { discardChangesHandler(); },
		setDiscardChanges: function (handler) {
			discardChangesHandler = typeof handler === 'function' ? handler : function () {};
		},
		highlightProjectLinkLayerFeatures: highlightProjectLinkLayerFeaturesInternal,
		show: showLayer,
		hide: hideLayer,
		clearHighlights: clearHighlights,
		setVisible: setVisible,
		updateRoadVisibility: updateRoadVisibility
	};

	return _instance;
}