/**
 * MapView component
 * Coordinates map interactions, visible layers, cursor state, and crosshair tooling.
 * @param {Object} map - OpenLayers map instance
 * @param {Object} layers - Active map layers keyed by layer name
 */
import { eventbus } from '@utils/eventbus.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { setZoomLevel, getRoadVisibility, refreshMap, getSelectedTool } from '@model/ApplicationModel.js';

export function MapView(map, layers) {
    const centerMarkerLayer = new ol.source.Vector({});
    let enableCtrlModifier = false;
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

    eventbus.on('application:initialized layer:fetched', function () {
      const zoom = zoomlevels.getViewZoom(map);
      setZoomLevel(zoom);
      eventbus.trigger('map:initialized', map);
    }, this);

    const setCursor = function (tool) {
      const cursor = {
        'Attach': 'default',
        'Add': 'crosshair',
        'Cut': 'crosshair',
        'Copy': 'copy',
        'Default': 'default',
        'Unknown': 'default'
      };
      map.getViewport().style.cursor = tool ? cursor[tool] || 'default' : 'default';
    };

    eventbus.on('tool:changed', function (tool) {
      setCursor(tool);
    });

    eventbus.on('tool:clear', function () {
      map.getViewport().style.cursor = 'default';
    });

    eventbus.on('coordinates:selected', function (position) {
      let zoomLevel = zoomlevels.getAssetZoomLevelIfNotCloser(zoomlevels.getViewZoom(map));
      if (!_.isUndefined(position.zoom))
        zoomLevel = position.zoom;
      map.getView().setCenter([position.lon, position.lat]);
      map.getView().setZoom(zoomLevel);
    }, this);

    map.on('coordinates:marked', function (event) {
      if (event && event.position) {
        drawCenterMarker(event.position);
      }
    });

    eventbus.on('layer:selected', function selectLayer(layer, previouslySelectedLayer) {
      const layerToBeHidden = layers[previouslySelectedLayer];
      const layerToBeShown = layers[layer];

      if (layerToBeHidden) {
        layerToBeHidden.hide(map);
      }
      if (getRoadVisibility()) layerToBeShown.show(map);
      enableCtrlModifier = (layer === 'roadAddressProject' || layer === 'linkProperty');
    }, this);

    map.on('moveend', function () {
      refreshMap(zoomlevels.getViewZoom(map), map.getLayers().getArray()[0].getExtent(), map.getView().getCenter());
      setCursor(getSelectedTool());
    });

    map.on('pointermove', function (event) {
      const pixel = map.getEventPixel(event.originalEvent);
      eventbus.trigger('map:mouseMoved', event, pixel);
    }, true);

    map.on('singleclick', function (event) {
      eventbus.trigger('map:clicked', {x: event.coordinate[0], y: event.coordinate[1]});
    });
    map.on('dblclick', function (event) {
      eventbus.trigger('map:dblclicked', {x: event.coordinate[0], y: event.coordinate[1]});
    });

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
      setCursor(getSelectedTool());
    });

    $('body').on('keydown', function (evt) {
      if ((evt.ctrlKey || evt.metaKey) && enableCtrlModifier)
        map.getViewport().style.cursor = "copy";
    });

    $('body').on('keyup', function (evt) {
      if (_.includes(metaKeyCodes, evt.which) && evt.originalEvent.key !== ViiteEnumerations.SelectKeyName) // ctrl key up
        setCursor(getSelectedTool());
    });

  setCursor(getSelectedTool());
}
