(function (root) {
  root.NodePointTemplateMarker = function () {
    var createNodePointTemplateMarker = function (nodePoint) {

      var marker = new ol.Feature({
        geometry: new ol.geom.Point([nodePoint.coordinates.x, nodePoint.coordinates.y])
      });

      var nodePointMarkerStyle = new ol.style.Style({
        image: new ol.style.Icon({
          src: 'images/node-point-template.svg',
          scale: 2
        })
      });

      marker.nodePointTemplate = nodePoint;
      marker.setStyle(nodePointMarkerStyle);
      return marker;
    };

    return {
      createNodePointTemplateMarker: createNodePointTemplateMarker
    };
  };
}(this));
