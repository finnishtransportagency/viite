/**
 * Main application entry point and initialization module for Viite UI.
 * Handles application startup, map setup, layer management, and component initialization.
 */
import { setStartupParameters, refreshMap } from '@model/ApplicationModel.js';
import { Backend } from '@utils/BackendUtils.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { Footer } from '@view/footer/Footer.js';
import { Header } from '@view/header/Header.js';
import { LinkPropertyLayer } from '@view/map/layers/LinkPropertyLayer.js';
import { MainMenu } from '@view/MainMenu.js';
import { MapView } from '@view/map/MapView.js';
import { createSearchPanel } from '@view/search-panel/SearchPanel.js';
import { NodeCollection } from '@model/NodeCollection.js';
import { NodeLayer } from '@view/map/layers/NodeLayer.js';
import { ProjectChangeInfoModel } from '@model/ProjectChangeInfoModel.js';
import { ProjectCollection } from '@model/ProjectCollection.js';
import { ProjectLinkLayer } from '@view/map/layers/ProjectLinkLayer.js';
import { RoadCollection } from '@model/RoadCollection.js';
import { RoadLayer } from '@view/map/layers/RoadLayer.js';
import { RoadNameCollection } from '@model/RoadNameCollection.js';
import { ScaleBar } from '@view/map/markers/ScaleBar.js';
import { SelectedLinkProperty } from '@model/SelectedLinkProperty.js';
import { SelectedNodesAndJunctions } from '@model/SelectedNodesAndJunctions.js';
import { SelectedProjectLink } from '@model/SelectedProjectLink.js';
import { TileMapCollection } from '@model/TileMapCollection.js';
import { URLRouter } from './router.js';
import { ZoomBox } from '@view/map/markers/ZoomBox.js';
import { eventbus } from '@utils/eventbus.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { Environment } from '@utils/EnvironmentUtils.js';

// Starts application
export function start() {
  document.title = Environment.browserTitle();
  const backend = new Backend();

  backend.getStartupParametersWithCallback(function (startupParameters) {
    setStartupParameters(startupParameters);

    const roadCollection = new RoadCollection(backend);
    const projectCollection = new ProjectCollection(backend, startupParameters);
    const roadNameCollection = new RoadNameCollection(backend);
    const selectedLinkProperty = new SelectedLinkProperty(roadCollection);
    const selectedProjectLinkProperty = new SelectedProjectLink(projectCollection);
    const projectChangeInfoModel = new ProjectChangeInfoModel(backend);
    const nodeCollection = new NodeCollection(backend);
    const selectedNodesAndJunctions = new SelectedNodesAndJunctions(nodeCollection);

    const models = {
      roadCollection: roadCollection,
      projectCollection: projectCollection,
      projectChangeInfoModel: projectChangeInfoModel,
      selectedLinkProperty: selectedLinkProperty,
      selectedProjectLinkProperty: selectedProjectLinkProperty,
      nodeCollection: nodeCollection,
      selectedNodesAndJunctions: selectedNodesAndJunctions
    };

    const searchPanel = createSearchPanel();
    jQuery('#map-tools').append(searchPanel.element);

    backend.getUserRoles();
    setupProjections();

    const map = startApplication(backend, models, startupParameters, roadNameCollection);
    new URLRouter(map, backend, models);
    eventbus.trigger('application:initialized');
  });
}

// Global AJAX error handler to catch and log any AJAX errors across the application
$(document).ajaxError(function (event, jqxhr, settings, thrownError) {
  if (jqxhr.getAllResponseHeaders()) {
    Spinner.clear();
    console.error(`Request '${settings.url}' failed: ${thrownError}`);
  }
});

const createOpenLayersMap = function (startupParameters, layers) {
  const map = new ol.Map({
    interactions: ol.interaction.defaults.defaults({ doubleClickZoom: false }),
    keyboardEventTarget: document,
    target: 'mapdiv',
    layers: layers,
    view: new ol.View({
      center: [startupParameters.lon, startupParameters.lat],
      projection: 'EPSG:3067',
      zoom: startupParameters.zoom,
      constrainResolution: true,
      resolutions: [2048, 1024, 512, 256, 128, 64, 32, 16, 8, 4, 2, 1, 0.5, 0.25, 0.125, 0.0625]
    })
  });

  const shiftDragZoom = new ol.interaction.DragZoom({
    className: 'dragZoom',
    duration: 1500,
    condition: function (mapBrowserEvent) {
      const originalEvent = mapBrowserEvent.originalEvent;

      return (
        originalEvent.shiftKey &&
        !(originalEvent.metaKey || originalEvent.altKey) &&
        !originalEvent.ctrlKey
      );
    }
  });

  map.getInteractions().forEach(function (interaction) {
    if (interaction instanceof ol.interaction.DragZoom) {
      map.removeInteraction(interaction);
    }
  });

  shiftDragZoom.setActive(true);
  map.addInteraction(shiftDragZoom);
  map.setProperties({extent: [-548576, 6291456, 1548576, 8388608]});

  return map;
};

const createMapLayers = function (map, models) {
  const roadLayer = new RoadLayer(map);
  const projectLinkLayer = new ProjectLinkLayer(map, models.projectCollection, models.selectedProjectLinkProperty);
  const linkPropertyLayer = new LinkPropertyLayer(map, roadLayer, models.selectedLinkProperty, models.roadCollection);
  const nodeLayer = new NodeLayer(map, roadLayer, models.selectedNodesAndJunctions, models.nodeCollection, models.roadCollection);

  return {
    road: roadLayer,
    roadAddressProject: projectLinkLayer,
    linkProperty: linkPropertyLayer,
    node: nodeLayer
  };
};

const initializeMap = function (models, startupParameters) {

  const tileMaps = new TileMapCollection();
  const map = createOpenLayersMap(startupParameters, tileMaps.layers);
  const layers = createMapLayers(map, models);

  models.projectLinkLayer = layers.roadAddressProject;

  return {map, layers, tileMaps};
};

const initializeUI = function (map, backend, startupParameters, layers, tileMaps, models, roadNameCollection) {
  const mapPluginsContainer = jQuery('#map-plugins');

  new ScaleBar(map, mapPluginsContainer);
  new ZoomBox(map, mapPluginsContainer);
  new Footer(map, mapPluginsContainer, layers.linkProperty, layers.roadAddressProject, tileMaps);
  new Header(jQuery('#header'), backend, startupParameters);

  new MainMenu({
    selectedLinkProperty: models.selectedLinkProperty,
    eventbus: eventbus,
    projectCollection: models.projectCollection,
    map: map,
    backend: backend,
    selectedProjectLinkProperty: models.selectedProjectLinkProperty,
    projectLinkLayer: models.projectLinkLayer,
    projectChangeInfoModel: models.projectChangeInfoModel,
    roadNameCollection: roadNameCollection,
    models: models
  });
};

const startApplication = function (backend, models, startupParameters, roadNameCollection) {
  const mapContext = initializeMap(
    models,
    startupParameters
  );

  initializeUI(mapContext.map, backend, startupParameters, mapContext.layers, mapContext.tileMaps, models, roadNameCollection);

  new MapView(mapContext.map, mapContext.layers);

  refreshMap(zoomlevels.getViewZoom(mapContext.map), mapContext.map.getLayers().getArray()[0].getExtent());

  return mapContext.map;
};

const setupProjections = function () {
  proj4.defs('EPSG:3067', '+proj=utm +zone=35 +ellps=GRS80 +units=m +no_defs');
  ol.proj.proj4.register(proj4);
};

$(function () {
  start();
});