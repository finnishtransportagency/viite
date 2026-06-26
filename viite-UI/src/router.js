/**
 * Synchronizes URL routes with the selected map layer, link selection, and project navigation state.
 * Keeps Backbone history aligned with map-driven UI actions.
 */
/* eslint-disable prefer-named-capture-group */
import { eventbus } from '@utils/eventbus.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { selectLayer } from '@model/ApplicationModel.js';
import { refreshMap } from '@view/map/MapView.js';

const LAYER_LINK_PROPERTY = 'linkProperty';
const LAYER_ROAD_ADDRESS_PROJECT = 'roadAddressProject';

let navigateToRoadAddressProjectBridge = function () {};
export function navigateToRoadAddressProject(projectId) {
	return navigateToRoadAddressProjectBridge(projectId);
}

let navigateToSelectedProjectBridge = function () {};
export function navigateToSelectedProject(linkId, project) {
	return navigateToSelectedProjectBridge(linkId, project);
}

let navigateToNodePointTemplateBridge = function () {};
export function navigateToNodePointTemplate(templateId) {
	return navigateToNodePointTemplateBridge(templateId);
}

let navigateToJunctionTemplateBridge = function () {};
export function navigateToJunctionTemplate(templateId) {
	return navigateToJunctionTemplateBridge(templateId);
}

export function URLRouter(map, backend, models) {
	const openNodePointTemplate = models.nodeCollection.openNodePointTemplate;
	const openJunctionTemplate = models.nodeCollection.openJunctionTemplate;

	const Router = Backbone.Router.extend({
		initialize: function () {

			this.route(/^(\d+)$/, function (layer) {
				selectLayer(layer);
			});

			this.route(/^([A-Za-z]+)\/?$/, function (layer) {
				selectLayer(layer);
			});

			this.route(/^$/, function () {
				selectLayer(LAYER_LINK_PROPERTY);
			});
		},

		routes: {
			'linkProperty/:linkId': 'linkProperty',
			'linkProperty/mml/:mmlId': 'linkPropertyByMml',
			'linkProperty/mtkid/:mtkid': 'linkPropertyByMtk',
			'roadAddressProject/:projectId': 'roadAddressProject',
			'node/nodePointTemplate/:id': 'nodePointTemplate',
			'node/junctionTemplate/:id': 'junctionTemplate'
		},

		linkProperty: function (linkId) {
			selectLayer(LAYER_LINK_PROPERTY);
			backend.getRoadAddressByLinkId(linkId, function (response) {
				if (response.success) {
					map.getView().setCenter([response.middlePoint.x, response.middlePoint.y]);
					map.getView().setZoom(zoomlevels.minZoomForLinkSearch);
				} else {
					console.error(response.reason);
				}
			});
		},

		linkPropertyByMml: function (mmlId) {
			selectLayer(LAYER_LINK_PROPERTY);
			backend.getRoadLinkByMmlId(mmlId, function (response) {
				if (!response || !response.middlePoint) {
					console.error('Failed to load road link by MML id:', mmlId);
					return;
				}
				map.getView().setCenter([response.middlePoint.x, response.middlePoint.y]);
				map.getView().setZoom(zoomlevels.minZoomForLinkSearch);
			});
		},

		linkPropertyByMtk: function (mtkid) {
			selectLayer(LAYER_LINK_PROPERTY);
			backend.getRoadLinkByMtkId(mtkid, function (response) {
				if (!response || response.x === undefined) {
					console.error('Failed to load road link by MTK id:', mtkid);
					return;
				}
				map.getView().setCenter([response.x, response.y]);
				map.getView().setZoom(zoomlevels.minZoomForLinkSearch);
			});
		},
		roadAddressProject: function (projectId) {
			selectLayer(LAYER_ROAD_ADDRESS_PROJECT);
			const parsedProjectId = parseInt(projectId, 10);
			models.projectCollection.startProject(parsedProjectId);
		},

		nodePointTemplate: function (nodePointTemplateId) {
			openNodePointTemplate(nodePointTemplateId);
		},

		junctionTemplate: function (junctionTemplateId) {
			openJunctionTemplate(junctionTemplateId);
		}
	});


	const router = new Router();

	// We need to restart the router history so that tests can reset
	// the application before each test.
	Backbone.history.stop();
	Backbone.history.start();

	const navigateToRoadAddressProjectInternal = function (projectId) {
		router.navigate(`${LAYER_ROAD_ADDRESS_PROJECT}/${projectId}`);
	};

	const navigateToSelectedProjectInternal = function (linkId, project) {
		const baseUrl = `${LAYER_ROAD_ADDRESS_PROJECT}/${project.id}`;
		const linkIdUrl = linkId ? `/${linkId}` : '';
		router.navigate(`${baseUrl}${linkIdUrl}`);
		const initialCenter = map.getView().getCenter();
		const hasProjectCoords = !_.isUndefined(project.coordX) && project.coordX !== 0 &&
        !_.isUndefined(project.coordY) && project.coordY !== 0 &&
        !_.isUndefined(project.zoomLevel) && project.zoomLevel !== 0;
		if (hasProjectCoords) {
			selectLayer(LAYER_LINK_PROPERTY, false);
			map.getView().setCenter([project.coordX, project.coordY]);
			map.getView().setZoom(project.zoomLevel);
		} else if (typeof linkId !== 'undefined') {
			selectLayer(LAYER_LINK_PROPERTY, false);
			backend.getProjectLinkByLinkId(linkId, function (response) {
				map.getView().setCenter([response.middlePoint.x, response.middlePoint.y]);
			});
		}
		const newCenter = map.getView().getCenter();
		if (initialCenter[0] === newCenter[0] && initialCenter[1] === newCenter[1]) {
			refreshMap(zoomlevels.getViewZoom(map), map.getLayers().getArray()[0].getExtent(), newCenter);
		}
	};

	navigateToSelectedProjectBridge = navigateToSelectedProjectInternal;
	navigateToRoadAddressProjectBridge = navigateToRoadAddressProjectInternal;

	const navigateToNodePointTemplateInternal = function (templateId) {
		router.navigate(`node/nodePointTemplate/${templateId}`);
	};

	const navigateToJunctionTemplateInternal = function (templateId) {
		router.navigate(`node/junctionTemplate/${templateId}`);
	};

	navigateToNodePointTemplateBridge = navigateToNodePointTemplateInternal;
	navigateToJunctionTemplateBridge = navigateToJunctionTemplateInternal;

	eventbus.on('layer:selected', function (layer) {
		const layerAdjusted = layer.includes('/') ? layer : layer.concat('/');
		router.navigate(layerAdjusted);
	});
}
