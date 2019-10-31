(function (root) {
  root.JunctionTemplateMarker = function () {
    var createJunctionTemplateMarker = function (junctionTemplate, roadLink) {
      var point = [];

      if ((roadLink.sideCode === LinkValues.SideCode.TowardsDigitizing.value && junctionTemplate.addrM === roadLink.endAddressM) || (roadLink.sideCode === LinkValues.SideCode.AgainstDigitizing.value && junctionTemplate.addrM === roadLink.startAddressM)) {
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

      marker.setStyle(junctionTemplateMarkerStyle);
      marker.junctionTemplateInfo = junctionTemplate;
      return marker;
    };

    return {
      createJunctionTemplateMarker: createJunctionTemplateMarker
    };
  };
}(this));
