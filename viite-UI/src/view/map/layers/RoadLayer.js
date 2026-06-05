/**
 * RoadLayer component
 * Manages the vector layer for displaying road links and handling road address information overlays.
 * @param {Object} map - OpenLayers map instance
 * @returns {Object} Layer with refresh and clear methods
 */
import { eventbus } from '@utils/Eventbus.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { RoadLinkStyler } from '@view/map/RoadLinkStyler.js';
import { Layer } from './Layer.js';
import { fetchLinkPropertiesForCurrentMap } from './LinkPropertyLayer.js';
import { fetchAndApplyNodesAndJunctionsForCurrentMap } from './NodeLayer.js';
import { fetchProjectLinksForCurrentMap } from './ProjectLinkLayer.js';
import { getRoadVisibility, getSelectedLayer } from '@model/ApplicationModel.js';

export function RoadLayer(map) {
  Layer.call(this, map);
    const me = this;
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

    const overlay = new ol.Overlay(({
      element: infoContainer
    }));

    map.addOverlay(overlay);

    const displayRoadAddressInfo = (event, pixel) => {
      const featureAtPixel = map.forEachFeatureAtPixel(pixel, (feature) => feature);
      let coordinate;
      const popupBox = document.getElementById('popup-content').getBoundingClientRect();

      // Prevent update when cursor is in the box
      if (!(event.originalEvent.clientX < popupBox.right &&
        event.originalEvent.clientX > popupBox.left &&
        event.originalEvent.clientY > popupBox.top &&
        event.originalEvent.clientY < popupBox.bottom)) {

        // Ignore if target feature is marker
        if (!_.isNil(featureAtPixel) && featureAtPixel.linkData) {
          const roadData = featureAtPixel.linkData;

          if (infoContent !== null) {
            if (roadData.roadNumber !== 0 && roadData.roadPartNumber !== 0) {
              coordinate = map.getEventCoordinate(event.originalEvent);

              infoContent.innerHTML = `
                <div class="popup-line-div"><div>Tienumero:&nbsp;</div><div class="selectable">${roadData.roadNumber}</div></div>
                <div class="popup-line-div"><div>Tieosanumero:&nbsp;</div><div class="selectable">${roadData.roadPartNumber}</div></div>
                <div class="popup-line-div"><div>Ajorata:&nbsp;</div><div class="selectable">${roadData.trackCode}</div></div>
                <div class="popup-line-div"><div>AET:&nbsp;</div><div class="selectable">${roadData.addrMRange.start}</div></div>
                <div class="popup-line-div"><div>LET:&nbsp;</div><div class="selectable">${roadData.addrMRange.end}</div></div>
                <div class="popup-line-div"><div>Hall. luokka:&nbsp;</div><div class="selectable">${displayAdministrativeClass(roadData.administrativeClassId)}</div></div>
              `;

              const altShiftPressed = event.originalEvent.shiftKey && event.originalEvent.altKey;
              if (altShiftPressed) {
                infoContent.innerHTML += `<hr>`;

                if (!_.isUndefined(roadData.municipalityCode)) {
                  infoContent.innerHTML += `
                    <div class="popup-line-div"><div>MunicipalityCode:&nbsp;</div><div class="selectable">${roadData.municipalityCode}</div></div>
                  `;
                }
                infoContent.innerHTML += `
                  <div class="popup-line-div"><div>Elinvoimakeskus:&nbsp;</div><div class="selectable">${roadData.evkCode}</div></div>
                  <div class="popup-line-div"><div>Link&nbsp;id:&nbsp;</div><div class="selectable">${roadData.linkId}</div></div>
                  <div class="popup-line-div"><div>LinearLocation&nbsp;id:&nbsp;</div><div class="selectable">${roadData.linearLocationId}</div></div>
                  <div class="popup-line-div"><div>Roadway&nbsp;id:&nbsp;</div><div class="selectable">${roadData.roadwayId}</div></div>
                  <div class="popup-line-div"><div>RoadwayNumber:&nbsp;</div><div class="selectable">${roadData.roadwayNumber}</div></div>
                `;
              }
            }
          }
        }
      }

      // Keep info box open with altkey
      if (!event.originalEvent.altKey) {
        overlay.setPosition(coordinate);
      }
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

    // Listen pointerMove and get pixel for displaying roadAddress feature info
    me.eventListener.listenTo(eventbus, 'overlay:update', function (event, pixel) {
      displayRoadAddressInfo(event, pixel);
    });

    const handleRoadsVisibility = function () {
      roadLayer.setVisible(getRoadVisibility() && zoomlevels.getViewZoom(map) >= zoomlevels.minZoomForRoadLinks);
    };

    this.refreshMap = function (mapState) {
      if (mapState.zoom < zoomlevels.minZoomForRoadLinks) {
        roadLayer.getSource().clear();
        eventbus.trigger('map:clearLayers');
        Spinner.hide();
      } else {
        /*
         This could be implemented also with eventbus.trigger(getSelectedLayer() + ':fetch');
         but this implementation makes it easier to find the eventbus call when needed.
        */
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

    this.eventListener.listenTo(eventbus, 'map:refresh', me.refreshMap, this);

    const clear = function () {
      roadLayer.getSource().clear();
    };

    return {
      layer: roadLayer,
      clear: clear
    };
  }
