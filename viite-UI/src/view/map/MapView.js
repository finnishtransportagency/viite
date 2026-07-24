/**
 * MapView component
 * Coordinates map interactions, visible layers, cursor state, and crosshair tooling.
 * @param {Object} map - OpenLayers map instance
 * @param {Object} layers - Active map layers keyed by layer name
 */

import { eventbus } from '@utils/eventbus.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { getSelectedLayer } from '@model/ApplicationModel.js';
import { refreshRoadLayer } from '@view/map/layers/RoadLayer.js';

const mapState = {
	map: undefined,
	zoomLevel: undefined,
	centerLonLat: undefined
};

export function setZoomLevel(level) {
	mapState.zoomLevel = Math.round(level);
}

export function getZoomLevel() {
	return mapState.zoomLevel;
}

export function getCurrentLocation() {
	return mapState.centerLonLat;
}

export function getUserGeoLocation() {
	if (!mapState.centerLonLat) return undefined;

	return {
		x: mapState.centerLonLat[0],
		y: mapState.centerLonLat[1],
		zoom: mapState.zoomLevel
	};
}

export function refreshMap(zoomLevel, bbox, center) {
	setZoomLevel(zoomLevel);
	mapState.centerLonLat = center;

	refreshRoadLayer({
		selectedLayer: getSelectedLayer(),
		zoom: mapState.zoomLevel,
		bbox,
		center
	});
}

export function moveMapToCoordinates(position) {
	let zoomLevel = zoomlevels.getAssetZoomLevelIfNotCloser(zoomlevels.getViewZoom(mapState.map));
	if (!_.isUndefined(position.zoom))
		zoomLevel = position.zoom;
	mapState.map.getView().setCenter([position.lon, position.lat]);
	mapState.map.getView().setZoom(zoomLevel);
}

export function MapView(map) {
	mapState.map = map;
	const centerMarkerLayer = new ol.source.Vector({});
	const metaKeyCodes = ViiteEnumerations.MetaKeyCodes;


	const drawCenterMarker = function (position) {
		// Create a new Feature with the exact point in the center of the map
		const icon = new ol.Feature({
			geometry: new ol.geom.Point(position)
		});

		// Create the style of the icon of the 'Merkitse' Button
		const styleIcon = new ol.style.Style({
			image: new ol.style.Icon({
				src: 'images/center-marker.svg'
			})
		});

		//add Icon Style
		icon.setStyle(styleIcon);
		//clear the previous icon
		centerMarkerLayer.clear();
		//add icon to vector source
		centerMarkerLayer.addFeature(icon);
	};

	const vectorLayer = new ol.layer.Vector({
		source: centerMarkerLayer
	});
	vectorLayer.set('name', 'mapViewVectorLayer');

	const addCenterMarkerLayerToMap = function (mapMarker) {
		mapMarker.addLayer(vectorLayer);
	};

	const setDefaultCursor = function () {
		map.getViewport().style.cursor = 'default';
	};

	map.on('coordinates:marked', function (event) {
		if (event && event.position) {
			drawCenterMarker(event.position);
		}
	});

	map.on('moveend', function () {
		refreshMap(zoomlevels.getViewZoom(map), map.getLayers().getArray()[0].getExtent(), map.getView().getCenter());
		setDefaultCursor();
	});

	map.on('pointermove', function (event) {
		const pixel = map.getEventPixel(event.originalEvent);
		eventbus.trigger('map:mouseMoved', event, pixel);
	}, true);

	addCenterMarkerLayerToMap(map);

	// Initial cursor when the map user is not dragging the map
	map.getViewport().style.cursor = "initial";

	// When the map is moving (the user is dragging the map)
	// Only work's when the developer options in the browser aren't open
	map.on('pointerdrag', function (_evt) {
		map.getViewport().style.cursor = "move";
	});

	// When the map dragging stops the cursor value returns to the initial one
	map.on('pointerup', function (_evt) {
		setDefaultCursor();
	});

	$('body').on('keydown', function (evt) {
		if ((evt.ctrlKey || evt.metaKey))
			map.getViewport().style.cursor = "copy";
	});

	$('body').on('keyup', function (evt) {
		if (_.includes(metaKeyCodes, evt.which) && evt.originalEvent.key !== ViiteEnumerations.SelectKeyName) // ctrl key up
			setDefaultCursor();
	});

	setDefaultCursor();
}
