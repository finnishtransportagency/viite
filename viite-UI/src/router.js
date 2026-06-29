/**
 * Synchronizes URL routes with the selected map layer, link selection, and project navigation state.
 */
import { zoomlevels } from '@utils/ZoomLevels.js';
import { selectLayer } from '@model/ApplicationModel.js';
import { refreshMap } from '@view/map/MapView.js';
import { eventbus } from '@utils/eventbus.js';

const LAYER_LINK_PROPERTY = 'linkProperty';
const LAYER_ROAD_ADDRESS_PROJECT = 'roadAddressProject';

// Singleton navigation API instance.
let api = null;

export function initNavigation({ map, backend, models }) {
	if (api) return api; // prevent double init

	const openNodePointTemplate = models.nodeCollection.openNodePointTemplate;
	const openJunctionTemplate = models.nodeCollection.openJunctionTemplate;

	const Router = Backbone.Router.extend({
		initialize() {
			// Numeric hash route -> select layer by id.
			this.route(/^(?<layer>\d+)$/, layer => selectLayer(layer));

			// Single-word hash route (with optional slash) -> select layer.
			this.route(/^(?<layer>[A-Za-z]+)\/?$/, layer => selectLayer(layer));

			// Empty hash route -> default layer.
			this.route(/^$/, () => selectLayer(LAYER_LINK_PROPERTY));
		},

		// Explicit routes with parameters.
		routes: {
			'linkProperty/:linkId': 'linkProperty',
			'linkProperty/mml/:mmlId': 'linkPropertyByMml',
			'linkProperty/mtkid/:mtkid': 'linkPropertyByMtk',
			'roadAddressProject/:projectId': 'roadAddressProject',
			'node/nodePointTemplate/:id': 'nodePointTemplate',
			'node/junctionTemplate/:id': 'junctionTemplate'
		},

		linkProperty(linkId) {
			selectLayer(LAYER_LINK_PROPERTY);

			backend.getRoadAddressByLinkId(linkId, response => {
				if (!response?.success) {
					console.error(response?.reason);
					return;
				}

				map.getView().setCenter([
					response.middlePoint.x,
					response.middlePoint.y
				]);

				map.getView().setZoom(zoomlevels.minZoomForLinkSearch);
			});
		},

		linkPropertyByMml(mmlId) {
			selectLayer(LAYER_LINK_PROPERTY);

			backend.getRoadLinkByMmlId(mmlId, response => {
				if (!response?.middlePoint) {
					console.error('Failed to load MML link:', mmlId);
					return;
				}

				map.getView().setCenter([
					response.middlePoint.x,
					response.middlePoint.y
				]);

				map.getView().setZoom(zoomlevels.minZoomForLinkSearch);
			});
		},

		linkPropertyByMtk(mtkid) {
			selectLayer(LAYER_LINK_PROPERTY);

			backend.getRoadLinkByMtkId(mtkid, response => {
				if (!response || response.x === undefined) {
					console.error('Failed to load MTK link:', mtkid);
					return;
				}

				map.getView().setCenter([response.x, response.y]);
				map.getView().setZoom(zoomlevels.minZoomForLinkSearch);
			});
		},

		roadAddressProject(projectId) {
			selectLayer(LAYER_ROAD_ADDRESS_PROJECT);
			models.projectCollection.startProject(Number(projectId));
		},

		nodePointTemplate(id) {
			openNodePointTemplate(id);
		},

		junctionTemplate(id) {
			openJunctionTemplate(id);
		}
	});

	const router = new Router();

	// Restart history so tests can reset app state between runs.
	// the application before each test.
  Backbone.history.stop();
	Backbone.history.start();

	// Public navigation helpers used by UI modules.
	const navigation = {
		navigateToRoadAddressProject(projectId) {
			router.navigate(`${LAYER_ROAD_ADDRESS_PROJECT}/${projectId}`);
		},

		navigateToSelectedProject(linkId, project) {
			const baseUrl = `${LAYER_ROAD_ADDRESS_PROJECT}/${project.id}`;
			const linkUrl = linkId ? `/${linkId}` : '';

			router.navigate(`${baseUrl}${linkUrl}`);

			// If center stays unchanged, trigger a manual refresh.
			const initialCenter = map.getView().getCenter();

			const hasCoords =
				project.coordX &&
				project.coordY &&
				project.zoomLevel;

			if (hasCoords) {
				selectLayer(LAYER_LINK_PROPERTY, false);

				map.getView().setCenter([
					project.coordX,
					project.coordY
				]);

				map.getView().setZoom(project.zoomLevel);
			} else if (linkId !== undefined) {
				selectLayer(LAYER_LINK_PROPERTY, false);

				backend.getProjectLinkByLinkId(linkId, response => {
					map.getView().setCenter([
						response.middlePoint.x,
						response.middlePoint.y
					]);
				});
			}

			const newCenter = map.getView().getCenter();

			if (
				initialCenter[0] === newCenter[0] &&
				initialCenter[1] === newCenter[1]
			) {
				refreshMap(
					zoomlevels.getViewZoom(map),
					map.getLayers().getArray()[0].getExtent(),
					newCenter
				);
			}
		},

		navigateToNodePointTemplate(templateId) {
			router.navigate(`node/nodePointTemplate/${templateId}`);
		},

		navigateToJunctionTemplate(templateId) {
			router.navigate(`node/junctionTemplate/${templateId}`);
		}
	};

	// Keep URL in sync when layer changes from UI events.
  eventbus.on('layer:selected', layer => {
    const route = layer.includes('/') ? layer : `${layer}/`;
    router.navigate(route);
  });

	api = navigation;
	return api;
}

export function getNavigation() {
	return api;
}