/**
 * CalibrationPointMarker - Creates styled OpenLayers markers for road calibration points.
 */
export function CalibrationPoint(data) {
  let cachedMarker = null;
  let cachedDirectionArrow = null;

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

  const getCalibrationPointMarker = function () {
    return cachedMarker;
  };

  const getDirectionArrow = function (shouldCreate) {
    if (shouldCreate || !cachedDirectionArrow) {
      cachedDirectionArrow = createCalibrationPointMarker();
    }
    return cachedDirectionArrow;
  };

  const moveTo = function (lonlat) {
    getDirectionArrow().move(lonlat);
    getCalibrationPointMarker().moveTo(lonlat);
  };

  const select = function () {
    getCalibrationPointMarker().select();
  };

  const deselect = function () {
    getCalibrationPointMarker().deselect();
  };

  const finalizeMove = function () {
    getCalibrationPointMarker().finalizeMove();
  };

  const rePlaceInGroup = function () {
    getCalibrationPointMarker().rePlaceInGroup();
  };

  return {
    getMarker: getMarker,
    getDirectionArrow: getDirectionArrow,
    moveTo: moveTo,
    select: select,
    deselect: deselect,
    finalizeMove: finalizeMove,
    rePlaceInGroup: rePlaceInGroup
  };
}
