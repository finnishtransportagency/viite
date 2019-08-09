(function (root) {
  root.NodePointTemplateMarker = function () {
    var createNodePointTemplateMarker = function (nodePoint, roadLink) {
      var point = [];

      if ((roadLink.sideCode === LinkValues.SideCode.TowardsDigitizing.value && nodePoint.addrM === roadLink.endAddressM) || (roadLink.sideCode === LinkValues.SideCode.AgainstDigitizing.value && nodePoint.addrM === roadLink.startAddressM)) {
        point = roadLink.points[roadLink.points.length - 1];
      } else {
        point = roadLink.points[0];
      }

      var marker = new ol.Feature({
        geometry: new ol.geom.Point([point.x, point.y])
      });

      var nodePointMarkerStyle = new ol.style.Style({
        image: new ol.style.Icon({
          src: 'images/node-point-template.svg',
          scale: 2
        })
      });

      marker.setStyle(nodePointMarkerStyle);
      marker.nodePointTemplateInfo = nodePoint;
      marker.type = LinkValues.SelectedMarkerType.NodePointTemplateMarker;
      return marker;
    };

    return {
      createNodePointTemplateMarker: createNodePointTemplateMarker
    };
  };
}(this));
