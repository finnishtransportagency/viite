(function (root) {
  root.JunctionTemplateMarker = function () {
    var createJunctionTemplateMarker = function (junctionTemplate, referencePoint, roadLink) {
      var point;

      if ((roadLink.sideCode === LinkValues.SideCode.TowardsDigitizing.value && referencePoint.addrM === roadLink.endAddressM) || (roadLink.sideCode === LinkValues.SideCode.AgainstDigitizing.value && referencePoint.addrM === roadLink.startAddressM)) {
        point = roadLink.points[roadLink.points.length - 1];
      } else {
        point = roadLink.points[0];
      }

      var marker = new ol.Feature({
        geometry: new ol.geom.Point([point.x, point.y]),
        junctionNumber: junctionTemplate.junctionNumber
      });

      var junctionTemplateStyleProvider = function(junctionNumber) {
        return new ol.style.Style({
          image: new ol.style.Icon({
            src: 'images/junction-template.svg',
            scale: 1
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
        this.setStyle(junctionTemplateStyleProvider(this.get('junctionNumber')));
      });

      marker.junctionTemplate = junctionTemplate;
      marker.roadLink = roadLink;
      marker.setStyle(junctionTemplateStyleProvider(junctionTemplate.junctionNumber));
      return marker;
    };

    return {
      createJunctionTemplateMarker: createJunctionTemplateMarker
    };
  };
}(this));
