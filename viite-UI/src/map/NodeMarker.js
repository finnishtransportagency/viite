(function (root) {
  root.NodeMarker = function () {

    var createNodeMarker = function (node) {
      var box = new ol.Feature({
        geometry: new ol.geom.Point([node.x, node.y]), // TODO assuming that node has x and y coordinates
        nodeId: node.id,
        type: "marker"
      });

      var boxStyleNode = function (node) {
        return new ol.style.Style({
          image: new ol.style.Icon({
            src: 'images/node-sprite.svg#' + node.type,
            anchor: [0.5, 1]
          }),
          zIndex: 10
        });
      };

      box.setStyle(boxStyleNode(node));
      box.id = node.id;
      box.linkData = node;
      return box;
    };

    return {
      createNodeMarker: createNodeMarker // TODO Take in use in the new layer: NodeLayer.js
    };
  };
}(this));
