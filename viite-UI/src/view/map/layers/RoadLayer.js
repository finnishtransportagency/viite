/**
 * Manages the vector layer for displaying road links and handling road address information overlays.
 * @param {Object} map - OpenLayers map instance
 * @returns {Object} Layer object with methods to manage road link display and interactions
 */
import { eventbus } from '@utils/eventbus.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { RoadLinkStyler } from '@view/map/RoadLinkStyler.js';
import { fetchLinkPropertiesForCurrentMap, clearLinkPropertyLayer } from './LinkPropertyLayer.js';
import { fetchAndApplyNodesAndJunctionsForCurrentMap, clearNodeLayer } from './NodeLayer.js';
import { fetchProjectLinksForCurrentMap, clearOnProjectClose } from './ProjectLinkLayer.js';
import { getRoadVisibility, getSelectedLayer } from '@model/ApplicationModel.js';

export function initRoadLayer(map) {
	const eventListener = _.extend({}, Backbone.Events);

	const roadLinkStyler = new RoadLinkStyler();
	const roadVector = new ol.source.Vector({});

	const roadLayer = new ol.layer.Vector({
		source: roadVector,
		style: function (feature) {
			return roadLinkStyler.getRoadLinkStyles(feature.linkData, map);
		}
	});

	roadLayer.setVisible(true);
	roadLayer.set('name', 'roadLayer');

	const infoContainer = document.getElementById('popup');
	const infoContent = document.getElementById('popup-content');

	const overlay = new ol.Overlay({
		element: infoContainer
	});

	map.addOverlay(overlay);


	const buildBaseHtml = (roadData) => `
		<div class="popup-line-div"><div>Tienumero:&nbsp;</div><div class="selectable">${roadData.roadNumber}</div></div>
		<div class="popup-line-div"><div>Tieosanumero:&nbsp;</div><div class="selectable">${roadData.roadPartNumber}</div></div>
		<div class="popup-line-div"><div>Ajorata:&nbsp;</div><div class="selectable">${roadData.trackCode}</div></div>
		<div class="popup-line-div"><div>AET:&nbsp;</div><div class="selectable">${roadData.addrMRange.start}</div></div>
		<div class="popup-line-div"><div>LET:&nbsp;</div><div class="selectable">${roadData.addrMRange.end}</div></div>
		<div class="popup-line-div">
			<div>Hall. luokka:&nbsp;</div>
			<div class="selectable">${displayAdministrativeClass(roadData.administrativeClassId)}</div>
		</div>
	`;

	const buildExtraHtml = (roadData) => `
		<hr>
		${roadData.municipalityCode
			? `<div class="popup-line-div"><div>MunicipalityCode:&nbsp;</div><div class="selectable">${roadData.municipalityCode}</div></div>`
			: ''
		}
		<div class="popup-line-div"><div>Elinvoimakeskus:&nbsp;</div><div class="selectable">${roadData.evkCode}</div></div>
		<div class="popup-line-div"><div>Link&nbsp;id:&nbsp;</div><div class="selectable">${roadData.linkId}</div></div>
		<div class="popup-line-div"><div>LinearLocation&nbsp;id:&nbsp;</div><div class="selectable">${roadData.linearLocationId}</div></div>
		<div class="popup-line-div"><div>Roadway&nbsp;id:&nbsp;</div><div class="selectable">${roadData.roadwayId}</div></div>
		<div class="popup-line-div"><div>RoadwayNumber:&nbsp;</div><div class="selectable">${roadData.roadwayNumber}</div></div>
	`;

	const updatePopup = (roadData, showExtra) => {
		infoContent.innerHTML =
			buildBaseHtml(roadData) +
			(showExtra ? buildExtraHtml(roadData) : '');
	};

	const clearPopup = () => {
		infoContent.innerHTML = '';
	};

	const displayRoadAddressInfo = (event, pixel) => {
		const { originalEvent } = event;

		const popupBox = document
			.getElementById('popup-content')
			.getBoundingClientRect();

		const insidePopup =
			originalEvent.clientX > popupBox.left &&
			originalEvent.clientX < popupBox.right &&
			originalEvent.clientY > popupBox.top &&
			originalEvent.clientY < popupBox.bottom;

		const feature = map.forEachFeatureAtPixel(pixel, f => f);
		const roadData = feature?.linkData;

		const validRoad =
			roadData &&
			roadData.roadNumber !== 0 &&
			roadData.roadPartNumber !== 0;

		const shouldRender = !insidePopup && infoContent && validRoad;

		const coordinate = shouldRender
			? map.getEventCoordinate(originalEvent)
			: undefined;

		if (!shouldRender) {
			clearPopup();
			overlay.setPosition(undefined);
			return;
		}

		const showExtra = originalEvent.shiftKey && originalEvent.altKey;

		updatePopup(roadData, showExtra);
		overlay.setPosition(coordinate);
	};

	function displayAdministrativeClass(administrativeClassCode) {
		let administrativeClass;

		switch (administrativeClassCode) {
			case ViiteEnumerations.AdministrativeClassShort.PublicRoad.value:
				administrativeClass = ViiteEnumerations.AdministrativeClassShort.PublicRoad.description;
				break;
			case ViiteEnumerations.AdministrativeClassShort.MunicipalityStreetRoad.value:
				administrativeClass = ViiteEnumerations.AdministrativeClassShort.MunicipalityStreetRoad.description;
				break;
			case ViiteEnumerations.AdministrativeClassShort.PrivateRoad.value:
				administrativeClass = ViiteEnumerations.AdministrativeClassShort.PrivateRoad.description;
				break;
			default:
				break;
		}

		return administrativeClass;
	}

	// Open info container when mouse is hovered over a road link
	eventListener.listenTo(eventbus, 'map:mouseMoved', function (event, pixel) {
		if (event.dragging) return;
		displayRoadAddressInfo(event, pixel);
	});

	const handleRoadsVisibility = function () {
		roadLayer.setVisible(
			getRoadVisibility() &&
			zoomlevels.getViewZoom(map) >= zoomlevels.minZoomForRoadLinks
		);
	};

	const refreshMap = function (mapState) {
		if (mapState.zoom < zoomlevels.minZoomForRoadLinks) {
			roadLayer.getSource().clear();
			clearLinkPropertyLayer();
			clearNodeLayer();
			clearOnProjectClose();
			Spinner.hide();
		} else {
			switch (getSelectedLayer()) {
				case 'linkProperty':
					fetchLinkPropertiesForCurrentMap();
					break;
				case 'roadAddressProject':
					fetchProjectLinksForCurrentMap();
					break;
				case 'node':
					fetchAndApplyNodesAndJunctionsForCurrentMap();
					break;
				default:
					break;
			}
			handleRoadsVisibility();
		}
	};

	eventListener.listenTo(eventbus, 'map:refresh', refreshMap);

	const clear = function () {
		roadLayer.getSource().clear();
	};

	return {
		layer: roadLayer,
		clear: clear
	};
}