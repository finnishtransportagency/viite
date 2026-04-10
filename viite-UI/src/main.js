/**
 * Main application entry point and initialization module for Viite UI.
 * Handles application startup, map setup, layer management, and component initialization.
 */
import { AdminPanel } from '@view/admin-panel/AdminPanel.js';
import { ApplicationModel } from '@model/ApplicationModel.js';
import { Backend } from '@utils/BackendUtils.js';
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { Footer } from '@view/footer/Footer.js';
import { LinkPropertyLayer } from '@view/map/layers/LinkPropertyLayer.js';
import { LocationSearch } from '@model/LocationSearch.js';
import { MainMenu } from '@view/MainMenu.js';
import { MapView } from '@view/map/MapView.js';
import { NavigationPanel } from '@view/navigation-panel/NavigationPanel.js';
import { NodeCollection } from '@model/NodeCollection.js';
import { NodeLayer } from '@view/map/layers/NodeLayer.js';
import { NodeMenu } from '@node-menu/NodeMenu.js';
import { ProjectChangeInfoModel } from '@model/ProjectChangeInfoModel.js';
import { ProjectCollection } from '@model/ProjectCollection.js';
import { ProjectLinkLayer } from '@view/map/layers/ProjectLinkLayer.js';
import { RoadAddressBrowserForm } from '@view/road-address-inspection/RoadAddressBrowserForm.js';
import { RoadAddressBrowserWindow } from '@view/road-address-inspection/RoadAddressBrowserWindow.js';
import { RoadAddressChangesBrowserWindow } from '@view/road-address-inspection/RoadAddressChangesBrowserWindow.js';
import { RoadCollection } from '@model/RoadCollection.js';
import { RoadLayer } from '@view/map/layers/RoadLayer.js';
import { RoadLinkBox } from '@view/navigation-panel/RoadLinkBox.js';
import { RoadNameCollection } from '@model/RoadNameCollection.js';
import { RoadNamingToolWindow } from '@view/road-name-maintenance-modal/RoadNamingToolWindow.js';
import { RoadNetworkErrorsList } from '@view/road-network-errors-list/RoadNetworkErrorsList.js';
import { ScaleBar } from '@view/map/markers/ScaleBar.js';
import { SearchBox } from '@view/navigation-panel/SearchBox.js';
import { SelectedLinkProperty } from '@model/SelectedLinkProperty.js';
import { SelectedNodesAndJunctions } from '@model/SelectedNodesAndJunctions.js';
import { SelectedProjectLink } from '@model/SelectedProjectLink.js';
import { TileMapCollection } from '@model/TileMapCollection.js';
import { URLRouter } from './router.js';
import { ZoomBox } from '@view/map/markers/ZoomBox.js';
import { dateutil } from '@utils/DateUtils.js';
import { eventbus } from '@utils/eventbus.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { Environment } from '@utils/EnvironmentUtils.js';

let applicationModel;

// Starts application
export function start() {
  const backend = new Backend();
  backend.getStartupParametersWithCallback(function (startupParameters) {
    applicationModel = new ApplicationModel();
    applicationModel.setStartupParameters(startupParameters);
    const roadCollection = new RoadCollection(backend, applicationModel);
    const projectCollection = new ProjectCollection(backend, startupParameters, applicationModel);
    applicationModel.setProjectCollection(projectCollection);
    const roadNameCollection = new RoadNameCollection(backend);
    const selectedLinkProperty = new SelectedLinkProperty(roadCollection, applicationModel);
    const selectedProjectLinkProperty = new SelectedProjectLink(projectCollection);
    applicationModel.setSelectedProjectLinkProperty(selectedProjectLinkProperty);
    const projectChangeInfoModel = new ProjectChangeInfoModel(backend);
    const nodeCollection = new NodeCollection(backend, new LocationSearch(backend, applicationModel));
    const selectedNodesAndJunctions = new SelectedNodesAndJunctions(nodeCollection);
    proj4.defs('EPSG:3067', '+proj=utm +zone=35 +ellps=GRS80 +units=m +no_defs');
    ol.proj.proj4.register(proj4);

    const models = {
      roadCollection: roadCollection,
      projectCollection: projectCollection,
      projectChangeInfoModel: projectChangeInfoModel,
      selectedLinkProperty: selectedLinkProperty,
      selectedProjectLinkProperty: selectedProjectLinkProperty,
      nodeCollection: nodeCollection,
      selectedNodesAndJunctions: selectedNodesAndJunctions
    };
    bindEvents();
    const linkGroups = groupLinks(selectedProjectLinkProperty, applicationModel);

    NavigationPanel.initialize(
      jQuery('#map-tools'),
      new SearchBox(
        new LocationSearch(backend, applicationModel)
      ),
      linkGroups
    );

    backend.getUserRoles();
    startApplication(backend, models, startupParameters, roadNameCollection);
  });
}

const startApplication = function (backend, models, startupParameters, roadNameCollection) {
  setupProjections();
  const map = setupMap(backend, models, startupParameters, roadNameCollection);
  new URLRouter(map, backend, models, applicationModel);
  eventbus.trigger('application:initialized');
};

$(document).ajaxError(function (event, jqxhr, settings, thrownError) {
  if (jqxhr.getAllResponseHeaders()) {
    Spinner.clear();
    console.log(`Request '${settings.url}' failed: ${thrownError}`);
  }
});

const createOpenLayersMap = function (startupParameters, layers) {
  const map = new ol.Map({
    interactions : ol.interaction.defaults.defaults({doubleClickZoom :false}),
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
        !originalEvent.ctrlKey);
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

const setupMapLayers = function (map, models) {
  const roadLayer = new RoadLayer(map, applicationModel);
  const projectLinkLayer = new ProjectLinkLayer(map, models.projectCollection, models.selectedProjectLinkProperty, applicationModel);
  applicationModel.setProjectLinkLayer(projectLinkLayer);
  const linkPropertyLayer = new LinkPropertyLayer(map, roadLayer, models.selectedLinkProperty, models.roadCollection, applicationModel);
  const nodeLayer = new NodeLayer(map, roadLayer, models.selectedNodesAndJunctions, models.nodeCollection, models.roadCollection, applicationModel);

  return {
    road: roadLayer,
    roadAddressProject: projectLinkLayer,
    linkProperty: linkPropertyLayer,
    node: nodeLayer
  };
};

const initializeUIComponents = function (backend, models, map, startupParameters, roadNameCollection) {
  const roadNamingTool = new RoadNamingToolWindow(roadNameCollection);
  const roadAddressBrowserForm = new RoadAddressBrowserForm();
  const roadAddressBrowser = new RoadAddressBrowserWindow(backend, roadAddressBrowserForm, { applicationModel });
  const roadAddressChangesBrowser = new RoadAddressChangesBrowserWindow(backend, roadAddressBrowserForm, { applicationModel });
  const roadNetworkErrorsList = new RoadNetworkErrorsList(backend, { applicationModel });
  const adminPanel = new AdminPanel(backend, {
    applicationModel: applicationModel
  });

  const nodesAndJunctionsModule = new NodeMenu(
    map,
    models.nodeCollection,
    backend,
    models.selectedNodesAndJunctions,
    models.roadCollection,
    startupParameters,
    {
      applicationModel: applicationModel,
      dateutil: dateutil,
      moment: moment,
      navigateToHash: function (hashValue) {
        location.hash = hashValue;
      }
    }
  );
  nodesAndJunctionsModule.initialize();

  const mainMenu = new MainMenu(models.selectedLinkProperty, roadNamingTool, roadAddressBrowser, roadAddressChangesBrowser, startupParameters, roadNetworkErrorsList, adminPanel, nodesAndJunctionsModule, {
    applicationModel: applicationModel,
    eventbus: eventbus,
    projectCollection: models.projectCollection,
    map: map,
    backend: backend,
    selectedProjectLinkProperty: models.selectedProjectLinkProperty,
    projectLinkLayer: models.projectLinkLayer,
    projectChangeInfoModel: models.projectChangeInfoModel,
    startupParameters: startupParameters
  });
  applicationModel.setMainMenu(mainMenu);

  return { mainMenu };
};

const initializeMapPlugins = function (map, startupParameters) {
  const mapPluginsContainer = jQuery('#map-plugins');
  new ScaleBar(map, mapPluginsContainer);
  new ZoomBox(map, mapPluginsContainer, applicationModel);
  new Footer(map, mapPluginsContainer, applicationModel);

  const toolTip = `<i class="fas fa-info-circle" title="Versio: ${startupParameters.deploy_date}"></i>\n`;

  const pictureTooltip = jQuery('#pictureTooltip');
  pictureTooltip.empty();
  pictureTooltip.append(toolTip);
};

const setupVersionInfo = function (backend) {
  backend.getRoadLinkDate(function (versionData) {
    getRoadLinkDateInfo(versionData);
  });

  const getRoadLinkDateInfo = function (versionData) {
    const notification = jQuery('#notification');
    notification.append(Environment.localizedName());
    notification.append(' Tielinkkiaineisto: ' + versionData.result);
  };

  if (Environment.name() === 'integration') {
    new ConfirmPopup('Huom!<br>Olet integraatiotestiympäristössä.', {
      type: 'alert',
      okButtonLbl: 'Sulje'
    });
  }
};

const setupMap = function (backend, models, startupParameters, roadNameCollection) {
  const tileMaps = new TileMapCollection();
  const map = createOpenLayersMap(startupParameters, tileMaps.layers);

  const layers = setupMapLayers(map, models);
  models.projectLinkLayer = layers.roadAddressProject;
  initializeUIComponents(backend, models, map, startupParameters, roadNameCollection);
  initializeMapPlugins(map, startupParameters);
  setupVersionInfo(backend);

  new MapView(map, layers, applicationModel);

  applicationModel.refreshMap(zoomlevels.getViewZoom(map), map.getLayers().getArray()[0].getExtent());

  return map;
};

const setupProjections = function () {
  proj4.defs('EPSG:3067', '+proj=utm +zone=35 +ellps=GRS80 +units=m +no_defs');
};

function groupLinks(selectedProjectLinkProperty, appModel) {
  const roadLinkBox = new RoadLinkBox(selectedProjectLinkProperty, appModel);
  return [
    [roadLinkBox]
  ];
}

const bindEvents = function () {
  eventbus.on('linkProperties:available', function () {
    Spinner.clear();
  });
};

$(function () {
  start();
});
