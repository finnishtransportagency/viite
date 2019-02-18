(function (root) {
  root.NodeMarker = function () {

    var createNodeMarker = function (node) {
      var box = new ol.Feature({
        geometry: new ol.geom.Point([node.x, node.y]),
        nodeId: node.id,
        type: "marker"
      });

      var colorMap =
        {           //comment includes legend name
          1: '#db0e0e',         // TODO
          2: '#ff6600',         // TODO
          3: '#ff9955',         // TODO
          4: '#1414db',         // TODO
          5: '#10bfc4',         // TODO
          6: '#800080',         // TODO
          7: '#10bfc4',         // TODO
          8: '#fc6da0',         // TODO
          9: '#fc6da0',         // TODO
          10: '#fc6da0',        // TODO
          11: '#888888'         // TODO
        };

      var nodeColor = function (roadLink) {
        if (roadLink.status === LinkValues.LinkStatus.New.value) {
          return '#ff55dd';
        } else if (roadLink.roadLinkSource === LinkValues.LinkGeomSource.SuravageLinkInterface.value && roadLink.id === 0) {
          return '#d3aff6';
        }
        else if (roadLink.roadClass in colorMap) {
          return colorMap[roadLink.roadClass];
        } else
          return '#888888';
      };

      var boxStyleNode = function (rl) {
        var fillColor = nodeColor(rl);
        var strokeColor = nodeColor(rl);
        var nodeMarker = '<?xml version="1.0" encoding="UTF-8" standalone="no"?><svg xmlns="http://www.w3.org/2000/svg" class="node" height="24" width="18" viewBox="0 0 100 141.07506"><path style="opacity:1;fill:' + strokeColor + ';fill-opacity:1;stroke:none;" d="M 50,4.4085955 102.90315,70.537531 50,136.66646 -2.9031496,70.537531 Z" class="stroke" /><path class="fill" d="M 50,29.390638 82.917516,70.537531 50,111.68443 17.082484,70.537531 Z" style="opacity:1;fill:' + fillColor + ';fill-opacity:1;stroke:none;" /></svg>';
        return new ol.style.Style({
          image: new ol.style.Icon({
            src: 'data:image/svg+xml;utf8,' + nodeMarker
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
      createNodeMarker: createNodeMarker
    };
  };
}(this));
