(function (root) {
  root.JunctionMarker = function () {
    var createJunctionMarker = function (junctionPoint, junction, roadLink) {
      var beforeOrAfter = junctionPoint.beforeOrAfter;
      var point = [];
        if(beforeOrAfter === 1)
          point = roadLink.points[roadLink.points.length -1];
        else
          point = roadLink.points[0];

      var marker = new ol.Feature({
        geometry: new ol.geom.Point([point.x, point.y])
      });

      var junctionMarkerStyle = new ol.style.Style({
        image: new ol.style.Icon({
          src: 'images/junction.svg' /*+ junction.junctionNumber*/
        })
      });

      marker.setStyle(junctionMarkerStyle);
      marker.junctionPoint = junctionPoint;
      marker.junction = junction;
      marker.roadLink = roadLink;
      return marker;
    };

    return {
      createJunctionMarker: createJunctionMarker
    };
  };
}(this));

//
// '<object type="image/svg+xml" data="images/junction.svg" style="margin-right: 5px">\n' +
// '    <param name="number" value="99"/>\n' +
// '</object>' +