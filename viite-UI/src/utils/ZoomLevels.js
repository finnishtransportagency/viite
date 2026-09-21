export const zoomlevels = {
	getViewZoom: function (map) {
		return Math.round(map.getView().getZoom());
	},

	minZoomForRoadLinks: 5,
	minZoomForRoadNetwork: 6,
	minZoomLevelForCalibrationPoints: 8,
	minZoomForNodes: 9,
	minZoomForEditMode: 10,
	minZoomForDirectionalMarkers: 11,
	minZoomForLinkSearch: 12,
	minZoomForJunctions: 12,
	maxZoomLevel: 15
};
