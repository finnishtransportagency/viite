(function() {
  window.zoomlevels = {
    isInRoadLinkZoomLevel: function(zoom) {
      return zoom >= this.minZoomForRoadLinks;
    },
    isInAssetZoomLevel: function(zoom) {
      return zoom >= this.minZoomForAssets;
    },
    getAssetZoomLevelIfNotCloser: function(zoom) {
      return zoom < 10 ? 10 : zoom;
    },
    minZoomForRoadLinks: 5,
    minZoomForAssets: 6,
    minZoomLevelForCalibrationPoints: 8,
    minZoomForEditMode: 10,
    minZoomForDirectionalMarkers: 11,
    minZoomForLinkSearch: 12,
    maxZoomLevel: 15
  };
})();