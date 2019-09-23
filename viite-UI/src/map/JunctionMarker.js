(function (root) {
  root.JunctionMarker = function () {
    var createJunctionMarker = function (junctionPoint, junction, roadLink) {
      var beforeOrAfter = junctionPoint.beforeOrAfter;
      var point = [];
      var junctionNumber = 0;
      if (junction.junctionNumber !== 99)
        junctionNumber = junction.junctionNumber;

      if ((roadLink.sideCode === LinkValues.SideCode.TowardsDigitizing.value && junctionPoint.addrM === roadLink.endAddressM) || (roadLink.sideCode === LinkValues.SideCode.AgainstDigitizing.value && junctionPoint.addrM === roadLink.startAddressM)) {
        point = roadLink.points[roadLink.points.length - 1];
      } else {
        point = roadLink.points[0];
      }

      var marker = new ol.Feature({
        geometry: new ol.geom.Point([point.x, point.y])
      });

      var junctionMarkerStyle = new ol.style.Style({
        image: new ol.style.Icon({
          src: 'images/junction-template.svg',
          scale: 0.75
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