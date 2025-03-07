//noinspection ThisExpressionReferencesGlobalObjectJS
(function (root) {
  root.MapView = function (map, layers, instructionsPopup) {
    var isInitialized = false;
    var centerMarkerLayer = new ol.source.Vector({});
    var enableCtrlModifier = false;
    var metaKeyCodes = ViiteEnumerations.MetaKeyCodes;

    var showAssetZoomDialog = function () {
      instructionsPopup.show('Zoomaa lähemmäksi, jos haluat nähdä kohteita', 2000);
    };

    var minZoomForContent = function () {
      if (applicationModel.getSelectedLayer()) {
        return layers[applicationModel.getSelectedLayer()].minZoomForContent || zoomlevels.minZoomForRoadNetwork;
      }
      return zoomlevels.minZoomForRoadNetwork;
    };

    var refreshMap = function (mapState) {
      if (mapState.zoom < minZoomForContent() && (isInitialized && mapState.hasZoomLevelChanged)) {
        showAssetZoomDialog();
      }
    };

    var drawCenterMarker = function (position) {
      //Create a new Feature with the exact point in the center of the map
      var icon = new ol.Feature({
        geometry: new ol.geom.Point(position)
      });

      //create the style of the icon of the 'Merkitse' Button
      var styleIcon = new ol.style.Style({
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

    var vectorLayer = new ol.layer.Vector({
      source: centerMarkerLayer
    });
    vectorLayer.set('name', 'mapViewVectorLayer');

    var addCenterMarkerLayerToMap = function (mapMarker) {
      mapMarker.addLayer(vectorLayer);
    };

    eventbus.on('application:initialized layer:fetched', function () {
      var zoom = zoomlevels.getViewZoom(map);
      applicationModel.setZoomLevel(zoom);

      new CrosshairToggle($('.mapplugin.coordinates'));
      isInitialized = true;
      eventbus.trigger('map:initialized', map);
    }, this);

    var setCursor = function (tool) {
      var cursor = {
        'Select': 'default',
        'Attach': 'default',
        'Add': 'crosshair',
        'Cut': 'crosshair',
        'Copy': 'copy'
      };
      map.getViewport().style.cursor = tool ? cursor[tool] : 'default';
    };

    eventbus.on('tool:changed', function (tool) {
      setCursor(tool);
    });

    eventbus.on('tool:clear', function () {
      map.getViewport().style.cursor = 'default';
    });

    eventbus.on('coordinates:selected', function (position) {
      if (geometrycalculator.isInBounds(map.getView().calculateExtent(map.getSize()), position.lon, position.lat)) {
        var zoomLevel = zoomlevels.getAssetZoomLevelIfNotCloser(zoomlevels.getViewZoom(map));
        if (!_.isUndefined(position.zoom))
          zoomLevel = position.zoom;
        map.getView().setCenter([position.lon, position.lat]);
        map.getView().setZoom(zoomLevel);
      } else {
        instructionsPopup.show('Koordinaatit eivät osu kartalle.', 3000);
      }
    }, this);

    eventbus.on('map:refresh', refreshMap, this);

    eventbus.on('coordinates:marked', drawCenterMarker, this);

    eventbus.on('layer:selected', function selectLayer(layer, previouslySelectedLayer) {
      var layerToBeHidden = layers[previouslySelectedLayer];
      var layerToBeShown = layers[layer];

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
      var pixel = map.getEventPixel(event.originalEvent);
      eventbus.trigger('map:mouseMoved', event, pixel);
    }, true);

    map.on('singleclick', function (event) {
      eventbus.trigger('map:clicked', {x: event.coordinate[0], y: event.coordinate[1]});
    });
    map.on('dblclick', function (event) {
      eventbus.trigger('map:dblclicked', {x: event.coordinate[0], y: event.coordinate[1]});
    });

    addCenterMarkerLayerToMap(map);

    //initial cursor when the map user is not dragging the map
    map.getViewport().style.cursor = "initial";

    //when the map is moving (the user is dragging the map)
    //only work's when the developer options in the browser aren't open
    map.on('pointerdrag', function (_evt) {
      map.getViewport().style.cursor = "move";
    });

    //when the map dragging stops the cursor value returns to the initial one
    map.on('pointerup', function (_evt) {
      if (applicationModel.getSelectedTool() === 'Select')
        map.getViewport().style.cursor = "initial";
    });

    $('body').on('keydown', function (evt) {
      if ((evt.ctrlKey || evt.metaKey) && enableCtrlModifier)
        map.getViewport().style.cursor = "copy";
    });

    $('body').on('keyup', function (evt) {
      if (_.includes(metaKeyCodes, evt.which) && evt.originalEvent.key !== ViiteEnumerations.SelectKeyName) // ctrl key up
        map.getViewport().style.cursor = "initial";
    });

    setCursor(applicationModel.getSelectedTool());
  };
}(this));
