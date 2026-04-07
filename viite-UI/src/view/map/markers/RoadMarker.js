/**
 * RoadMarker - Creates and manages road markers on the map
 */
export function RoadMarker(data) {
    let cachedMarker = null;
    let cachedDirectionArrow = null;

    const defaultMarkerGraphics = {
        stroke: true,
        strokeColor: '#000000',
        fill: true
    };

    const createRoadMarker = function() {
        const markerGraphics = _.clone(defaultMarkerGraphics);
        return new OpenLayers.Feature.Vector(
            new OpenLayers.Geometry.Point(data.x, data.y),
            null,
            _.merge(markerGraphics, { label: data.roadNumber + ' / ' + data.roadPartNumber })
        );
    };

    const getMarker = function(shouldCreate) {
        if (shouldCreate || !cachedMarker) {
            cachedMarker = createRoadMarker();
        }
        return cachedMarker;
    };

    const getRoadMarker = function() {
        return cachedMarker;
    };

    const getDirectionArrow = function(shouldCreate) {
        if (shouldCreate || !cachedDirectionArrow) {
            cachedDirectionArrow = createRoadMarker();
        }
        return cachedDirectionArrow;
    };

    const moveTo = function(lonlat) {
        getDirectionArrow().move(lonlat);
        getRoadMarker().moveTo(lonlat);
    };

    const select = function() { getRoadMarker().select(); };
    const deselect = function() { getRoadMarker().deselect(); };
    const finalizeMove = function() { getRoadMarker().finalizeMove(); };
    const rePlaceInGroup = function() { getRoadMarker().rePlaceInGroup(); };

    return {
        getMarker,
        getDirectionArrow,
        moveTo,
        select,
        deselect,
        finalizeMove,
        rePlaceInGroup
    };
}
