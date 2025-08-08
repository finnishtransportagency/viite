(function (root) {
  root.TileMapCollection = function () {
    const layerConfig = {
      visible: false,
      extent: [-548576, 6291456, 1548576, 8388608]
    };

    const propertyLayerConfig = {
      maxResolution: 5,
      visible: true,
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

    const aerialMapLayer = new ol.layer.Tile(_.merge({
      source: new ol.source.XYZ(_.merge({
        tileGrid: new ol.tilegrid.TileGrid(_.merge({}, tileGridConfig, resolutionConfig))
      }, aerialMapConfig))
    }, layerConfig));
    aerialMapLayer.set('name', 'aerialMapLayer');

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

    const tileMapLayers = {
      background: backgroundMapLayer,
      aerial: aerialMapLayer,
      terrain: terrainMapLayer,
      propertyBorder: propertyBorderLayer
    };

    var selectMap = function (tileMap) {
      _.forEach(tileMapLayers, function (layer, key) {
        layer.setVisible(key === tileMap);
      });
    };


    const togglePropertyBorderVisibility = function (showPropertyBorder) {
      propertyBorderLayer.setVisible(showPropertyBorder);
    };

    selectMap('background');
    eventbus.on('tileMap:selected', selectMap);
    eventbus.on('tileMap:togglepropertyBorder', togglePropertyBorderVisibility);

    return {
      layers: _.map(tileMapLayers, function (layer) {
        return layer;
      })
    };
  };
}(this));
