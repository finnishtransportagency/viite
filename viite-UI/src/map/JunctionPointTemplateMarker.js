(function (root) {
  root.JunctionPointTemplateMarker = function () {
    var createJunctionPointTemplateMarker = function (junctionPoint, roadLink) {
      var point = [];

      if((roadLink.sideCode === LinkValues.SideCode.TowardsDigitizing.value && junctionPoint.addrM === roadLink.endAddressM) ||(roadLink.sideCode === LinkValues.SideCode.AgainstDigitizing.value && junctionPoint.addrM === roadLink.startAddressM)){
        point = roadLink.points[roadLink.points.length -1];
      }
      else
        point = roadLink.points[0];


      var marker = new ol.Feature({
        geometry: new ol.geom.Point([point.x, point.y])
      });

      var junctionPointMarkerStyle = new ol.style.Style({
        image: new ol.style.Icon({
          src: 'images/junction-template.svg',
          scale: 1
        })
      });

      marker.setStyle(junctionPointMarkerStyle);
      marker.junctionPointTemplateInfo = junctionPoint;
      return marker;
    };

    return {
      createJunctionPointTemplateMarker: createJunctionPointTemplateMarker
    };
  };
}(this));
