/**
 * Provides shared layer lifecycle helpers for map-related layer controllers.
 * Exposes reusable utilities for map-related layer controllers.
 */
export function addLayers(map, layers) {
	_.each(layers, function (layer) {
		map.addLayer(layer);
	});
}

export function toggleLayersVisibility(layers, visibleToggle) {
	_.each(layers, function (layer) {
		layer.setVisible(visibleToggle);
	});
}

export function clearLayers(layers) {
	_.each(layers, function (layer) {
		layer.getSource().clear();
	});
}

export function removeFeaturesFromLayers(layers) {
	_.each(layers, function (layer) {
		const features = layer.getSource().getFeatures();
		features.forEach((feature) => {
			layer.getSource().removeFeature(feature);
		});
	});
}

export function drawCalibrationMarkers(roadLinks) {
	const calibrationPointsWithValue = [];
	_.filter(roadLinks, function (roadLink) {
		return roadLink.calibrationPoints.length > 0 && roadLink.addrMRange.start === 0;
	}).forEach(function (roadLink) {
		roadLink.calibrationPoints.forEach(function (currentPoint) {
			const point = currentPoint.point;
			if (point && currentPoint.value === 0)
				calibrationPointsWithValue.push({points: point, calibrationCode: roadLink.calibrationCode});
		});
	});
	return calibrationPointsWithValue;
}

export function drawProjectCalibrationMarkers(roadLinks) {
	const calibrationPointsWithValue = [];
	_.filter(roadLinks, function (roadLink) {
		return roadLink.calibrationPoints.length > 0;
	}).forEach(function (roadLink) {
		roadLink.calibrationPoints.forEach(function (currentPoint) {
			const point = currentPoint.point;
			if (point)
				calibrationPointsWithValue.push({points: point, calibrationCode: roadLink.calibrationCode});
		});
	});
	return calibrationPointsWithValue;
}
