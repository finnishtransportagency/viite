(function(root) {
  root.LinkPropertyMarker = function(data) {
    var createMarker = function(roadlink) {
      var middlePoint = calculateMiddlePoint(roadlink);
      var box = new ol.Feature({
        geometry: new ol.geom.Point([middlePoint.x, middlePoint.y]),
        linkId : roadlink.linkId,
        type : "marker"
      });

      var boxStyleFloat = new ol.style.Style({
        image: new ol.style.Icon({
          src: 'images/link-properties/flag-floating-plus-stick.svg',
          anchor: [0, 1]
        }),
        zIndex: 10
      });

      var boxStyleUnknown = new ol.style.Style({
        image: new ol.style.Icon({
          src: "images/speed-limits/unknown.svg"
        }),
        zIndex: 10
      });

      if(roadlink.roadLinkType===-1){
        box.setStyle(boxStyleFloat);
      } else if(roadlink.id===0 && roadlink.roadLinkType === LinkValues.RoadLinkType.UnknownRoadLinkType.value){
        box.setStyle(boxStyleUnknown);
      }

      box.id = roadlink.linkId;
      box.linkData = roadlink;
      return box;
    };

    var calculateMiddlePoint = function(link){
      var points = _.map(link.points, function(point) {
        return [point.x, point.y];
      });
      var lineString = new ol.geom.LineString(points);
      return GeometryUtils.calculateMidpointOfLineString(lineString);
    };

    return {
      createMarker: createMarker
    };
  };
}(this));
