(function (application) {
  application.start = function (customBackend, withTileMaps) {
    var backend = customBackend || new Backend();
    backend.getStartupParametersWithCallback(function (startupParameters) {
      var tileMaps = _.isUndefined(withTileMaps) ? true : withTileMaps;
      var roadCollection = new RoadCollection(backend);
      var projectCollection = new ProjectCollection(backend, startupParameters);
      var roadNameCollection = new RoadNameCollection(backend);
      var selectedLinkProperty = new SelectedLinkProperty(backend, roadCollection);
      var selectedProjectLinkProperty = new SelectedProjectLink(projectCollection);
      var instructionsPopup = new InstructionsPopup(jQuery('.digiroad2'));
      var projectChangeInfoModel = new ProjectChangeInfoModel(backend);
      window.applicationModel = new ApplicationModel([selectedLinkProperty]);
      var nodeCollection = new NodeCollection(backend, new LocationSearch(backend, window.applicationModel));
      var selectedNodesAndJunctions = new SelectedNodesAndJunctions(nodeCollection);
      proj4.defs('EPSG:3067', '+proj=utm +zone=35 +ellps=GRS80 +units=m +no_defs');
      ol.proj.proj4.register(proj4);

      var models = {
        roadCollection: roadCollection,
        projectCollection: projectCollection,
        selectedLinkProperty: selectedLinkProperty,
        selectedProjectLinkProperty: selectedProjectLinkProperty,
        nodeCollection: nodeCollection,
        selectedNodesAndJunctions: selectedNodesAndJunctions
      };

      bindEvents();

      var linkGroups = groupLinks(selectedProjectLinkProperty);

      var projectListModel = new ProjectListModel(projectCollection);
      var projectChangeTable = new ProjectChangeTable(projectChangeInfoModel, models.projectCollection);

      NavigationPanel.initialize(
          jQuery('#map-tools'),
          new SearchBox(
              instructionsPopup,
              new LocationSearch(backend, window.applicationModel)
          ),
          linkGroups
      );

      backend.getUserRoles();
      startApplication(backend, models, tileMaps, startupParameters, projectChangeTable, roadNameCollection, projectListModel);
    });
  };

  var startApplication = function (backend, models, withTileMaps, startupParameters, projectChangeTable, roadNameCollection, projectListModel) {
    setupProjections();
    var map = setupMap(backend, models, withTileMaps, startupParameters, projectChangeTable, roadNameCollection, projectListModel);
    new URLRouter(map, backend, models);
    eventbus.trigger('application:initialized');
  };

  $(document).ajaxError(function (event, jqxhr, settings, thrownError) {
    if (jqxhr.getAllResponseHeaders()) {
      applicationModel.removeSpinner();
      console.log("Request '" + settings.url + "' failed: " + thrownError);
    }
  });

  var createOpenLayersMap = function (startupParameters, layers) {
    var map = new ol.Map({
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

    var shiftDragZoom = new ol.interaction.DragZoom({
      className: "dragZoom",
      duration: 1500,
      condition: function (mapBrowserEvent) {
        var originalEvent = mapBrowserEvent.originalEvent;
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
    }, this);

    shiftDragZoom.setActive(true);
    map.addInteraction(shiftDragZoom);
    map.setProperties({extent: [-548576, 6291456, 1548576, 8388608]});
    return map;
  };

  var setupMap = function (backend, models, withTileMaps, startupParameters, projectChangeTable, roadNameCollection, projectListModel) {
    var tileMaps = new TileMapCollection();

    var map = createOpenLayersMap(startupParameters, tileMaps.layers);

    var roadLayer = new RoadLayer(map, models.roadCollection, models.selectedLinkProperty, models.nodeCollection);
    var projectLinkLayer = new ProjectLinkLayer(map, models.projectCollection, models.selectedProjectLinkProperty);
    var linkPropertyLayer = new LinkPropertyLayer(map, roadLayer, models.selectedLinkProperty, models.roadCollection, applicationModel);
    var nodeLayer = new NodeLayer(map, roadLayer, models.selectedNodesAndJunctions, models.nodeCollection, models.roadCollection, applicationModel);
    var roadNamingTool = new RoadNamingToolWindow(roadNameCollection);
    var roadAddressBrowserForm = new RoadAddressBrowserForm();
    var roadAddressBrowser = new RoadAddressBrowserWindow(backend, roadAddressBrowserForm);
    var roadAddressChangesBrowser = new RoadAddressChangesBrowserWindow(backend, roadAddressBrowserForm);
    var roadNetworkErrorsList = new RoadNetworkErrorsList(backend);
    var adminPanel = new AdminPanel(backend);

    new LinkPropertyForm(models.selectedLinkProperty, roadNamingTool, projectListModel, roadAddressBrowser, roadAddressChangesBrowser, startupParameters, roadNetworkErrorsList, adminPanel);

    new NodeSearchForm(new InstructionsPopup(jQuery('.digiroad2')), map, models.nodeCollection, backend);
    new NodeForm(models.selectedNodesAndJunctions, models.roadCollection, backend, startupParameters);

    new ProjectForm(map, models.projectCollection, models.selectedProjectLinkProperty, projectLinkLayer, startupParameters);
    new ProjectEditForm(map, models.projectCollection, models.selectedProjectLinkProperty, projectLinkLayer, projectChangeTable, backend, startupParameters);

    var layers = _.merge({
      road: roadLayer,
      roadAddressProject: projectLinkLayer,
      linkProperty: linkPropertyLayer,
      node: nodeLayer
    });

    var mapPluginsContainer = jQuery('#map-plugins');
    new ScaleBar(map, mapPluginsContainer);
    new TileMapSelector(mapPluginsContainer, applicationModel);
    new ZoomBox(map, mapPluginsContainer);
    new CoordinatesDisplay(map, mapPluginsContainer);

    var toolTip = '<i class="fas fa-info-circle" title="Versio: ' + startupParameters.deploy_date + '"></i>\n';

    var pictureTooltip = jQuery('#pictureTooltip');
    pictureTooltip.empty();
    pictureTooltip.append(toolTip);

    backend.getRoadLinkDate(function (versionData) {
      getRoadLinkDateInfo(versionData);
    });

    var getRoadLinkDateInfo = function (versionData) {

      // Show environment name next to Viite logo
      var notification = jQuery('#notification');
      notification.append(Environment.localizedName());
      notification.append(' Tielinkkiaineisto: ' + versionData.result);
    };

    // Show information modal in integration environment (remove when not needed any more)
    if (Environment.name() === 'integration') {
      showInformationModal('Huom!<br>Olet integraatiotestiympäristössä.');
    }

    new MapView(map, layers, new InstructionsPopup(jQuery('.digiroad2')));

    applicationModel.refreshMap(zoomlevels.getViewZoom(map), map.getLayers().getArray()[0].getExtent());

    return map;
  };

  var setupProjections = function () {
    proj4.defs('EPSG:3067', '+proj=utm +zone=35 +ellps=GRS80 +units=m +no_defs');
  };

  function groupLinks(selectedProjectLinkProperty) {

    var roadLinkBox = new RoadLinkBox(selectedProjectLinkProperty);

    return [
      [roadLinkBox]
    ];
  }

  // Shows modal with message and close button
  function showInformationModal(message) {
    jQuery('.container').append('<div class="modal-overlay confirm-modal" style="z-index: 2000"><div class="modal-dialog"><div class="content">' + message + '</div><div class="actions"><button class="btn btn-secondary close">Sulje</button></div></div></div></div>');
    jQuery('.confirm-modal .close').on('click', function () {
      jQuery('.confirm-modal').remove();
    });
  }

  application.restart = function (backend, withTileMaps) {
    this.start(backend, withTileMaps);
  };

  var bindEvents = function () {

    eventbus.on('linkProperties:available', function () {
      jQuery('.spinner-overlay').remove();
    });

    eventbus.on('confirm:show', function () {
      new Confirm();
    });
  };

}(window.Application = window.Application || {}));
