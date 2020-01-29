(function (root) {
  root.JunctionMarker = function () {
    var createJunctionMarker = function (junction, referencePoint, roadLink) {
      var point = [];

      if ((roadLink.sideCode === LinkValues.SideCode.TowardsDigitizing.value && referencePoint.addrM === roadLink.endAddressM) || (roadLink.sideCode === LinkValues.SideCode.AgainstDigitizing.value && referencePoint.addrM === roadLink.startAddressM)) {
        point = roadLink.points[roadLink.points.length - 1];
      } else {
        point = roadLink.points[0];
      }

      var marker = new ol.Feature({
        geometry: new ol.geom.Point([point.x, point.y]),
        junctionNumber: junction.junctionNumber
      });

      var junctionMarkerStyleProvider = function(junctionNumber) {
        return new ol.style.Style({
          image: new ol.style.Icon({
            src: 'images/junction.svg',
            scale: 0.75
          }),
          text: new ol.style.Text({
            text: junctionNumber ? junctionNumber.toString() : '',
            font: '13px arial',
            fill: new ol.style.Fill({
              color: 'white'
            })
          })
        });
      };

      marker.on('change:junctionNumber', function () {
        this.setStyle(junctionMarkerStyleProvider(this.get('junctionNumber')));
      });

      marker.junction = junction;
      marker.roadLink = roadLink;
      marker.setStyle(junctionMarkerStyleProvider(junction.junctionNumber));
      return marker;
    };

    return {
      createJunctionMarker: createJunctionMarker
    };
  };
}(this));