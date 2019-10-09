(function (root) {
  root.JunctionMarker = function () {
    var createJunctionMarker = function (junction, junctionPoint, roadLink) {
      var point = [];

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
          src: 'images/junction.svg',
          scale: 0.75
        }),
        text: new ol.style.Text({
          text: junction.junctionNumber === LinkValues.Generic.Undefined.value ? '' : junction.junctionNumber.toString(),
          font: '13px arial',
          fill: new ol.style.Fill({
            color: 'white'
          })
        })
      });

      marker.setStyle(junctionMarkerStyle);
      marker.junction = junction;
      marker.roadLink = roadLink;
      return marker;
    };

    return {
      createJunctionMarker: createJunctionMarker
    };
  };
}(this));