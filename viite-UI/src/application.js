/**
 * Main application entry point and initialization module for Viite UI.
 * Handles application startup, map setup, layer management, and component initialization.
 */
(function (application) {
  application.start = function (customBackend, withTileMaps) {
    const backend = customBackend || new Backend();
    backend.getStartupParametersWithCallback(function (startupParameters) {
      window.startupParameters = startupParameters; // Make globally accessible
      const tileMaps = _.isUndefined(withTileMaps) ? true : withTileMaps;
      const roadCollection = new RoadCollection(backend);
      const projectCollection = new ProjectCollection(backend, startupParameters);
      window.projectCollection = projectCollection;
      const roadNameCollection = new RoadNameCollection(backend);
      const selectedLinkProperty = new SelectedLinkProperty(backend, roadCollection);
      const selectedProjectLinkProperty = new SelectedProjectLink(projectCollection);
      window.selectedProjectLinkProperty = selectedProjectLinkProperty;
      const instructionsPopup = new InstructionsPopup(jQuery('.digiroad2'));
      const projectChangeInfoModel = new ProjectChangeInfoModel(backend);
      window.applicationModel = new ApplicationModel([selectedLinkProperty]);
      const nodeCollection = new NodeCollection(backend, new LocationSearch(backend, window.applicationModel));
      const selectedNodesAndJunctions = new SelectedNodesAndJunctions(nodeCollection);
      proj4.defs('EPSG:3067', '+proj=utm +zone=35 +ellps=GRS80 +units=m +no_defs');
      ol.proj.proj4.register(proj4);

      const models = {
        roadCollection: roadCollection,
        projectCollection: projectCollection,
        selectedLinkProperty: selectedLinkProperty,
        selectedProjectLinkProperty: selectedProjectLinkProperty,
        nodeCollection: nodeCollection,
        selectedNodesAndJunctions: selectedNodesAndJunctions
      };

      bindEvents();
      const linkGroups = groupLinks(selectedProjectLinkProperty);

      const projectList = new ProjectList(projectCollection);
      const projectChangeTable = new ProjectChangeTable(projectChangeInfoModel, models.projectCollection);

      NavigationPanel.initialize(
          jQuery('#map-tools'),
          new SearchBox(
              instructionsPopup,
              new LocationSearch(backend, window.applicationModel)
          ),
          linkGroups
      );

      backend.getUserRoles();
      startApplication(backend, models, tileMaps, startupParameters, projectChangeTable, roadNameCollection, projectList);
    });
  };

  // Application startup and initialization
  const startApplication = function (backend, models, withTileMaps, startupParameters, projectChangeTable, roadNameCollection, projectList) {
    setupProjections();
    const map = setupMap(backend, models, withTileMaps, startupParameters, projectChangeTable, roadNameCollection, projectList);
    new URLRouter(map, backend, models);
    eventbus.trigger('application:initialized');
  };

  // Global error handling
  $(document).ajaxError(function (event, jqxhr, settings, thrownError) {
    if (jqxhr.getAllResponseHeaders()) {
      applicationModel.removeSpinner();
      console.log(`Request '${settings.url}' failed: ${thrownError}`);
    }
  });

  // Map creation and configuration
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
        constrainResolution: true, // The view will always animate to the closest zoom level after an interaction
        resolutions: [2048, 1024, 512, 256, 128, 64, 32, 16, 8, 4, 2, 1, 0.5, 0.25, 0.125, 0.0625]
      })
    });

    const shiftDragZoom = new ol.interaction.DragZoom({
      className: "dragZoom",
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
    const roadLayer = new RoadLayer(map, models.roadCollection, models.selectedLinkProperty, models.nodeCollection);
    const projectLinkLayer = new ProjectLinkLayer(map, models.projectCollection, models.selectedProjectLinkProperty);
    window.projectLinkLayer = projectLinkLayer;
    const linkPropertyLayer = new LinkPropertyLayer(map, roadLayer, models.selectedLinkProperty, models.roadCollection, applicationModel);
    const nodeLayer = new NodeLayer(map, roadLayer, models.selectedNodesAndJunctions, models.nodeCollection, models.roadCollection, applicationModel);

    return {
      road: roadLayer,
      roadAddressProject: projectLinkLayer,
      linkProperty: linkPropertyLayer,
      node: nodeLayer
    };
  };

  // Initialize UI components
  const initializeUIComponents = function (backend, models, map, startupParameters, projectChangeTable, roadNameCollection, projectList) {
    // Create UI components
    const roadNamingTool = new RoadNamingToolWindow(roadNameCollection);
    const roadAddressBrowserForm = new RoadAddressBrowserForm();
    const roadAddressBrowser = new RoadAddressBrowserWindow(backend, roadAddressBrowserForm);
    const roadAddressChangesBrowser = new RoadAddressChangesBrowserWindow(backend, roadAddressBrowserForm);
    const roadNetworkErrorsList = new RoadNetworkErrorsList(backend);
    const adminPanel = new AdminPanel(backend);

    const mainMenu = new MainMenu(models.selectedLinkProperty, roadNamingTool, projectList, roadAddressBrowser, roadAddressChangesBrowser, startupParameters, roadNetworkErrorsList, adminPanel);
    window.mainMenu = mainMenu;

    // Initialize forms and tools
    new NodeSearchForm(new InstructionsPopup(jQuery('.digiroad2')), map, models.nodeCollection, backend);
    new NodeForm(models.selectedNodesAndJunctions, models.roadCollection, backend, startupParameters);
    new LinkEditForm(startupParameters);
    
    return { mainMenu };
  };

  // Setup project menus and event handlers
  const setupProjectMenus = function (models, map, backend, projectChangeTable, startupParameters) {
    const projectActionMenu = new ProjectActionMenu({
      projectCollection: models.projectCollection,
      map: map,
      eventbus: eventbus,
      applicationModel: applicationModel,
      backend: backend,
      projectChangeTable: projectChangeTable,
      startupParameters: startupParameters
    });

    const projectMenu = new ProjectMenu('#feature-attributes', eventbus, {
      projectMenu: projectActionMenu,
      projectCollection: models.projectCollection,
      applicationModel,
      map: map,
      backend: backend,
      projectChangeTable: projectChangeTable,
      startupParameters: startupParameters
    });

    window.projectMenu = projectMenu;

    return { projectActionMenu, projectMenu };
  };

  // Initialize map plugins and UI elements
  const initializeMapPlugins = function (map, startupParameters) {
    // Map plugins initialization
    const mapPluginsContainer = jQuery('#map-plugins');
    new ScaleBar(map, mapPluginsContainer);
    new TileMapSelector(mapPluginsContainer, applicationModel);
    new ZoomBox(map, mapPluginsContainer);
    new CoordinatesDisplay(map, mapPluginsContainer);

    const toolTip = `<i class="fas fa-info-circle" title="Versio: ${startupParameters.deploy_date}"></i>\n`;

    const pictureTooltip = jQuery('#pictureTooltip');
    pictureTooltip.empty();
    pictureTooltip.append(toolTip);
  };

  // Setup version and environment information
  const setupVersionInfo = function (backend) {
    backend.getRoadLinkDate(function (versionData) {
      getRoadLinkDateInfo(versionData);
    });

    const getRoadLinkDateInfo = function (versionData) {
      // Show environment name next to Viite logo
      const notification = jQuery('#notification');
      notification.append(Environment.localizedName());
      notification.append(' Tielinkkiaineisto: ' + versionData.result);
    };

    // Integration environment warning
    if (Environment.name() === 'integration') {
      new ConfirmPopup('Huom!<br>Olet integraatiotestiympäristössä.', {
        type: 'alert',
        okButtonLbl: 'Sulje'
      });
    }
  };

  // Main map setup with all layers and components
  const setupMap = function (backend, models, withTileMaps, startupParameters, projectChangeTable, roadNameCollection, projectList) {
    const tileMaps = new TileMapCollection();
    const map = createOpenLayersMap(startupParameters, tileMaps.layers);

    const layers = setupMapLayers(map, models);

    initializeUIComponents(backend, models, map, startupParameters, projectChangeTable, roadNameCollection, projectList);
    setupProjectMenus(models, map, backend, projectChangeTable, startupParameters);
    initializeMapPlugins(map, startupParameters);
    setupVersionInfo(backend);

    new MapView(map, layers, new InstructionsPopup(jQuery('.digiroad2')));

    applicationModel.refreshMap(zoomlevels.getViewZoom(map), map.getLayers().getArray()[0].getExtent());

    return map;
  };

  // Utility functions
  const setupProjections = function () {
    proj4.defs('EPSG:3067', '+proj=utm +zone=35 +ellps=GRS80 +units=m +no_defs');
  };

  function groupLinks(selectedProjectLinkProperty) {
    const roadLinkBox = new RoadLinkBox(selectedProjectLinkProperty);
    return [
      [roadLinkBox]
    ];
  }

  application.restart = function (backend, withTileMaps) {
    this.start(backend, withTileMaps);
  };

  const bindEvents = function () {
    eventbus.on('linkProperties:available', function () {
      jQuery('.spinner-overlay').remove();
    });

    eventbus.on('confirm:show', function () {
      new Confirm();
    });
  };

  // Modal container singleton pattern
  let modalContainerSingleton = null;
  
  application.getModalContainer = function(config) {
    if (!modalContainerSingleton) {
      modalContainerSingleton = new ModalContainer(config);
    }
    return modalContainerSingleton;
  };

}(window.Application = window.Application || {}));
