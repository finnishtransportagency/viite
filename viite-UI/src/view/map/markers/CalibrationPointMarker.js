/**
 * CalibrationPointMarker - Creates styled OpenLayers markers for road calibration points.
 */
export function CalibrationPoint(data) {
	let cachedMarker = null;

	const createCalibrationPointMarker = function () {
		const marker = new ol.Feature({
			geometry: new ol.geom.Point([data.points.x, data.points.y])
		});
		if (!_.isUndefined(data.points)) {
			const calibrationPointMarkerStyle = new ol.style.Style({
				image: new ol.style.Icon({
					src: "images/calibration-point.svg",
					anchor: [0.5, 1]
				})
			});
			marker.setStyle(calibrationPointMarkerStyle);
		}
		return marker;
	};

	const getMarker = function (shouldCreate) {
		if (shouldCreate || !cachedMarker) {
			cachedMarker = createCalibrationPointMarker();
		}
		return cachedMarker;
	};

	return {
		getMarker: getMarker
	};
}
