(function (root) {
  root.JunctionTemplateMarker = function () {
    var createJunctionTemplateMarker = function (junctionTemplate, referencePoint, roadLink, roadLinkForPoint, getCoordinates) {
      var point = [];

      if ((roadLink.sideCode === LinkValues.SideCode.TowardsDigitizing.value && referencePoint.addrM === roadLink.endAddressM) || (roadLink.sideCode === LinkValues.SideCode.AgainstDigitizing.value && referencePoint.addrM === roadLink.startAddressM)) {
        point = roadLink.points[roadLink.points.length - 1];
      } else {
        point = roadLink.points[0];
      }

      var marker = new ol.Feature({
        geometry: new ol.geom.Point([point.x, point.y])
      });

      var junctionTemplateMarkerStyle = new ol.style.Style({
        image: new ol.style.Icon({
          src: 'images/junction-template.svg',
          scale: 1
        })
      });

      // add coordinates to junction points
      _.each(junctionTemplate.junctionPoints, function (junctionPoint) {
        var roadLink = roadLinkForPoint(function (roadLink) {
          return (roadLink.startAddressM === junctionPoint.addrM || roadLink.endAddressM === junctionPoint.addrM) && roadLink.roadwayNumber === junctionPoint.roadwayNumber;
        });

        if (!_.isUndefined(roadLink)) {
          var point = [];

          if ((roadLink.sideCode === LinkValues.SideCode.TowardsDigitizing.value && junctionPoint.addrM === roadLink.endAddressM) || (roadLink.sideCode === LinkValues.SideCode.AgainstDigitizing.value && junctionPoint.addrM === roadLink.startAddressM)) {
            point = roadLink.points[roadLink.points.length - 1];
          } else {
            point = roadLink.points[0];
          }
          junctionPoint.coordinates = { x: point.x, y: point.y };
        } else {
          getCoordinates(junctionPoint.road, junctionPoint.part, junctionPoint.addrM, function (results) {
            if (results.length >= 1) {
              var result = results[0];
              junctionPoint.coordinates = { x: result.lon, y: result.lat };
            }
          });
        }
      });

      marker.setStyle(junctionTemplateMarkerStyle);
      marker.junctionTemplate = junctionTemplate;
      marker.roadLink = roadLink;
      return marker;
    };

    return {
      createJunctionTemplateMarker: createJunctionTemplateMarker
    };
  };
}(this));
