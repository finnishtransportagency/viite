(function (root) {
  root.NodeMarker = function () {
    var createNodeMarker = function (node) {
      var marker = new ol.Feature({
        geometry: new ol.geom.Point([node.coordX, node.coordY])
      });

      var nodeMarkerStyle = new ol.style.Style({
        image: new ol.style.Icon({
          src: 'images/node-sprite.svg#' + node.type,
          anchor: [0.5, 1]
        })
      });

      marker.setStyle(nodeMarkerStyle);
      marker.nodeInfo = node;
      return marker;
    };

    return {
      createNodeMarker: createNodeMarker
    };
  };
}(this));
