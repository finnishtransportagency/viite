// This file provides reusable utilities for map-related layer controllers.
 
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

export function toggleInteractionsActive(interactions, activate) {
	_.each(interactions, function (interaction) {
		interaction.setActive(activate);
	});
}

export function clearLayers(layers) {
	_.each(layers, function (layer) {
		layer.getSource().clear();
	});
}

function getCalibrationMarkers(roadLinks, roadLinkFilter, calibrationPointFilter) {
	const calibrationPointsWithValue = [];
	_.filter(roadLinks, roadLinkFilter).forEach(function (roadLink) {
		roadLink.calibrationPoints.forEach(function (currentPoint) {
			const point = currentPoint.point;
			if (point && calibrationPointFilter(currentPoint))
				calibrationPointsWithValue.push({points: point, calibrationCode: roadLink.calibrationCode});
		});
	});
	return calibrationPointsWithValue;
}

export function drawCalibrationMarkers(roadLinks) {
	return getCalibrationMarkers(
		roadLinks,
		(roadLink) => roadLink.calibrationPoints.length > 0 && roadLink.addrMRange.start === 0,
		(currentPoint) => currentPoint.value === 0
	);
}

export function drawProjectCalibrationMarkers(roadLinks) {
	return getCalibrationMarkers(
		roadLinks,
		(roadLink) => roadLink.calibrationPoints.length > 0,
		() => true
	);
}
