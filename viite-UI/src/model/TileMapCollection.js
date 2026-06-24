/**
 * Creates the background tile layers used by the map view and wires layer visibility events.
 * Manages base maps plus optional property and regional border overlays.
 */
export function TileMapCollection() {
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

	const tileMapLayers = {
		background: backgroundMapLayer,
		aerial: aerialMapLayer,
		terrain: terrainMapLayer,
		propertyBorder: propertyBorderLayer,
		regionsBorder: regionBordersLayer
	};

	const selectMap = function (tileMap) {
		_.forEach(tileMapLayers, function (layer, key) {
			// Don't hide the property and region borders when changing base maps
			if (key === 'propertyBorder' || key === 'regionsBorder') {
				return;
			}
			layer.setVisible(key === tileMap);
		});
	};

	const setVisible = function (layerName, visible) {
		const layer = tileMapLayers[layerName];
		if (layer) {
			layer.setVisible(visible);
		}
	};

	selectMap('background');

	return {
		layers: Object.values(tileMapLayers),
		selectMap: selectMap,
		setVisible: setVisible,
		getLayer: function(name) {
			return tileMapLayers[name];
		}
	};
}
