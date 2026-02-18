(function (root) {
  root.NodeMarker = function () {
    var createNodeMarker = function (node) {

      var marker = new ol.Feature({
        geometry: new ol.geom.Point([node.coordinates.x, node.coordinates.y]),
        type: node.type,
        name: node.name
      });

      var nodeMarkerStyleProvider = function (type) {
        return new ol.style.Style({
          image: new ol.style.Icon({
            src: 'images/node-sprite.svg#' + type,
            scale: 1.6
          })
        });
      };

      marker.on('change:type', function () {
        this.setStyle(nodeMarkerStyleProvider(this.get('type')));
      });

      marker.node = node;
      marker.setStyle(nodeMarkerStyleProvider(node.type));
      return marker;
    };

    return {
      createNodeMarker: createNodeMarker
    };
  };
}(this));
