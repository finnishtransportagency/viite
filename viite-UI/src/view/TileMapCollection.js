(function (root) {
  root.TileMapCollection = function () {
    const layerConfig = {
      visible: false,
      extent: [-548576, 6291456, 1548576, 8388608]
    };

    const propertyLayerConfig = {
      maxResolution: 5,
      visible: false,
      extent: [-548576, 6291456, 1548576, 8388608]
    };

    const sourceConfig = {
      cacheSize: 4096,
      projection: 'EPSG:3067',
      tileSize: [256, 256]
    };

    const tileGridConfig = {
      extent: [-548576, 6291456, 1548576, 8388608],
      origin: [-548576, 8388608],
      projection: 'EPSG:3067'
    };

    const resolutionConfig = {
      resolutions: [8192, 4096, 2048, 1024, 512, 256, 128, 64, 32, 16, 8, 4, 2, 1, 0.5]
    };

    const aerialMapConfig = _.merge({}, sourceConfig, {
      url: 'wmts/maasto/1.0.0/ortokuva/default/ETRS-TM35FIN/{z}/{y}/{x}.jpg'
    });

    const backgroundMapConfig = _.merge({}, sourceConfig, {
      url: 'wmts/maasto/1.0.0/taustakartta/default/ETRS-TM35FIN/{z}/{y}/{x}.png'
    });

    const propertyBorderMapConfig = _.merge({}, sourceConfig, {
      url: 'wmts/kiinteisto/1.0.0/kiinteistojaotus/default/ETRS-TM35FIN/{z}/{y}/{x}.png'
    });

    const terrainMapConfig = _.merge({}, sourceConfig, {
      url: 'wmts/maasto/1.0.0/maastokartta/default/ETRS-TM35FIN/{z}/{y}/{x}.png'
    });

    const regionBordersSource = new ol.source.TileWMS({
      url: '/paikkatiedot/wms',
      params: {
        'LAYERS': 'paikkatiedot:maakuntarajat_10k',
        'FORMAT': 'image/png'
      },
      projection: 'EPSG:3067',
      tileGrid: new ol.tilegrid.TileGrid(_.merge({}, tileGridConfig, resolutionConfig)) 
    });

    const aerialMapLayer = new ol.layer.Tile(_.merge({
      source: new ol.source.XYZ(_.merge({
        tileGrid: new ol.tilegrid.TileGrid(_.merge({}, tileGridConfig, resolutionConfig))
      }, aerialMapConfig))
    }, layerConfig));
    aerialMapLayer.set('name', 'aerialMapLayer');

    const regionBordersLayer = new ol.layer.Tile(_.merge({
      source: regionBordersSource
    }, layerConfig));
    regionBordersLayer.set('name', 'regionsBorderLayer');

    const backgroundMapLayer = new ol.layer.Tile(_.merge({
      source: new ol.source.XYZ(_.merge({
        tileGrid: new ol.tilegrid.TileGrid(_.merge({}, tileGridConfig, resolutionConfig))
      }, backgroundMapConfig))
    }, layerConfig));
    backgroundMapLayer.set('name', 'backgroundMapLayer');

    const propertyBorderLayer = new ol.layer.Tile(_.merge({
      source: new ol.source.XYZ(_.merge({
        tileGrid: new ol.tilegrid.TileGrid(_.merge({}, tileGridConfig, resolutionConfig))
      }, propertyBorderMapConfig))
    }, propertyLayerConfig));
    propertyBorderLayer.set('name', 'propertyBorderLayer');

    const terrainMapLayer = new ol.layer.Tile(_.merge({
      source: new ol.source.XYZ(_.merge({
        tileGrid: new ol.tilegrid.TileGrid(_.merge({}, tileGridConfig, resolutionConfig))
      }, terrainMapConfig))
    }, layerConfig));
    terrainMapLayer.set('name', 'terrainMapLayer');

    const specialTransportRoutesLayer = new ol.layer.Vector({
      source: new ol.source.Vector(),
      visible: false,
      minZoom: zoomlevels.minZoomForRoadLinks, // same zoom range as regular road links
      zIndex: 160,
      style: new ol.style.Style({
        stroke: new ol.style.Stroke({ color: '#00eeff', width: 3 })
      })
    });
    specialTransportRoutesLayer.set('name', 'specialTransportRoutesLayer');

    const detourRoutesLayer = new ol.layer.Vector({
      source: new ol.source.Vector(),
      visible: false,
      minZoom: zoomlevels.minZoomForRoadLinks, // same zoom range as regular road links
      zIndex: 160,
      style: new ol.style.Style({
        stroke: new ol.style.Stroke({ color: '#03fc5e', width: 3 })
      })
    });
    detourRoutesLayer.set('name', 'detourRoutesLayer');

    const tileMapLayers = {
      background: backgroundMapLayer,
      aerial: aerialMapLayer,
      terrain: terrainMapLayer,
      propertyBorder: propertyBorderLayer,
      regionsBorder: regionBordersLayer,
      specialTransportRoutes: specialTransportRoutesLayer,
      detourRoutes: detourRoutesLayer
    };

    var selectMap = function (tileMap) {
      _.forEach(tileMapLayers, function (layer, key) {
        // Don't hide the property/region borders, or the Velho overlay layers, when changing base maps
        if (key === 'propertyBorder' || key === 'regionsBorder' || key === 'specialTransportRoutes' || key === 'detourRoutes') {
          return;
        }
        layer.setVisible(key === tileMap);
      });
    };

    const togglePropertyBorderVisibility = function (showPropertyBorder) {
      propertyBorderLayer.setVisible(showPropertyBorder);
    };

    const toggleRegionalBordersVisibility = function (showRegionalBorders) {
      regionBordersLayer.setVisible(showRegionalBorders); 
    };

    // geoJson coordinates come from Velho in EPSG:4326; reproject to the map's EPSG:3067.
    const geoJsonFormat = new ol.format.GeoJSON();

    const setVelhoLayerFeatures = function (layer, geoJson) {
      const source = layer.getSource();
      source.clear();
      if (geoJson && geoJson.features && geoJson.features.length) {
        source.addFeatures(geoJsonFormat.readFeatures(geoJson, {
          dataProjection: 'EPSG:4326',
          featureProjection: 'EPSG:3067'
        }));
      }
    };

    const toggleSpecialTransportRoutesVisibility = function (visible, geoJson) {
      specialTransportRoutesLayer.setVisible(visible);
      if (visible) {
        setVelhoLayerFeatures(specialTransportRoutesLayer, geoJson);
      }
    };

    const toggleDetourRoutesVisibility = function (visible, geoJson) {
      detourRoutesLayer.setVisible(visible);
      if (visible) {
        setVelhoLayerFeatures(detourRoutesLayer, geoJson);
      }
    };

    selectMap('background');
    eventbus.on('tileMap:selected', selectMap);
    eventbus.on('tileMap:togglepropertyBorder', togglePropertyBorderVisibility);
    eventbus.on('tileMap:toggleRegionalBorders', toggleRegionalBordersVisibility);
    eventbus.on('velho:specialTransportRoutesToggled', toggleSpecialTransportRoutesVisibility);
    eventbus.on('velho:detourRoutesToggled', toggleDetourRoutesVisibility);

    return {
      layers: Object.values(tileMapLayers),
      getLayer: function(name) {
        return tileMapLayers[name];
      }
    };
  };
}(this));
