/**
 * MapView component
 * Coordinates map interactions, visible layers, cursor state, and crosshair tooling.
 * @param {Object} map - OpenLayers map instance
 * @param {Object} layers - Active map layers keyed by layer name
 * @param {Object} applicationModel - Application state manager
 */
import { createCrosshairToggle } from '@view/footer/CrosshairToggle.js';
import { eventbus } from '@utils/eventbus.js';
import { showToast } from '@components/Toast.js';
import { geometrycalculator } from '@utils/GeometryCalculations.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { zoomlevels } from '@utils/ZoomLevels.js';

export function MapView(map, layers, applicationModel) {
    let isInitialized = false;
    const centerMarkerLayer = new ol.source.Vector({});
    let enableCtrlModifier = false;
    const metaKeyCodes = ViiteEnumerations.MetaKeyCodes;

    const showAssetZoomDialog = function () {
      //showToast('Zoomaa lähemmäksi, jos haluat nähdä kohteita', { type: 'info' });
    };

    const minZoomForContent = function () {
      if (applicationModel.getSelectedLayer()) {
        return layers[applicationModel.getSelectedLayer()].minZoomForContent || zoomlevels.minZoomForRoadNetwork;
      }
      return zoomlevels.minZoomForRoadNetwork;
    };

    const refreshMap = function (mapState) {
      if (mapState.zoom < minZoomForContent() && (isInitialized && mapState.hasZoomLevelChanged)) {
        showAssetZoomDialog();
      }
    };

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
      applicationModel.setZoomLevel(zoom);

      createCrosshairToggle($('.mapplugin.coordinates'), map);
      isInitialized = true;
      eventbus.trigger('map:initialized', map);
    }, this);

    const setCursor = function (tool) {
      const cursor = {
        'Select': 'default',
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
      if (geometrycalculator.isInBounds(map.getView().calculateExtent(map.getSize()), position.lon, position.lat)) {
        let zoomLevel = zoomlevels.getAssetZoomLevelIfNotCloser(zoomlevels.getViewZoom(map));
        if (!_.isUndefined(position.zoom))
          zoomLevel = position.zoom;
        map.getView().setCenter([position.lon, position.lat]);
        map.getView().setZoom(zoomLevel);
      } else {
        showToast('Koordinaatit eivät osu kartalle.', { type: 'warning' });
      }
    }, this);

    eventbus.on('map:refresh', refreshMap, this);

    eventbus.on('coordinates:marked', drawCenterMarker, this);

    eventbus.on('layer:selected', function selectLayer(layer, previouslySelectedLayer) {
      const layerToBeHidden = layers[previouslySelectedLayer];
      const layerToBeShown = layers[layer];

      if (layerToBeHidden) {
        layerToBeHidden.hide(map);
      }
      if (applicationModel.getRoadVisibility()) layerToBeShown.show(map);
      applicationModel.setMinDirtyZoomLevel(minZoomForContent());
      enableCtrlModifier = (layer === 'roadAddressProject' || layer === 'linkProperty');
    }, this);

    map.on('moveend', function () {
      applicationModel.refreshMap(zoomlevels.getViewZoom(map), map.getLayers().getArray()[0].getExtent(), map.getView().getCenter());
      setCursor(applicationModel.getSelectedTool());
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
      setCursor(applicationModel.getSelectedTool());
    });

    $('body').on('keydown', function (evt) {
      if ((evt.ctrlKey || evt.metaKey) && enableCtrlModifier)
        map.getViewport().style.cursor = "copy";
    });

    $('body').on('keyup', function (evt) {
      if (_.includes(metaKeyCodes, evt.which) && evt.originalEvent.key !== ViiteEnumerations.SelectKeyName) // ctrl key up
        setCursor(applicationModel.getSelectedTool());
    });

  setCursor(applicationModel.getSelectedTool());
}
