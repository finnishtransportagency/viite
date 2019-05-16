(function() {
  window.zoomlevels = {
    getAssetZoomLevelIfNotCloser: function(zoom) {
      return zoom < 10 ? 10 : zoom;
    },
    getViewZoom: function(map) {
      return Math.round(map.getView().getZoom());
    } ,

    minZoomForRoadLinks: 5,
    minZoomForRoadNetwork: 6,
    minZoomLevelForCalibrationPoints: 8,
    minZoomForNodes: 9,
    minZoomForEditMode: 10,
    minZoomForDirectionalMarkers: 11,
    minZoomForLinkSearch: 12,
    minZoomForJunctions: 13,
    maxZoomLevel: 15
  };
})();