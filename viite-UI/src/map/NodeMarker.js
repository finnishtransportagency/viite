(function (root) {
  root.NodeMarker = function () {

    var createNodeMarker = function (node) {
      var box = new ol.Feature({
        geometry: new ol.geom.Point([node.x, node.y]), // TODO assuming that node has x and y coordinates
        nodeId: node.id,
        type: "marker"
      });

      var colorMap =
        { //   Color                                    Node type
          1:  {fill: '#e9b28a', stroke: '#be7041'},  // Normaali tasoliittymä
          3:  {fill: '#b0ce94', stroke: '#5e803f'},  // Kiertoliittymä
          4:  {fill: '#f6c043', stroke: '#b89131'},  // Y-liittymä
          5:  {fill: '#fcf1cf', stroke: '#7a601d'},  // Eritasoliittymä
          7:  {fill: '#e17863', stroke: '#b02218'},  // Maantien / kadun raja
          8:  {fill: '#689ad0', stroke: '#3f74b0'},  // ELY-raja
          10: {fill: '#e4efdb', stroke: '#b1ce94'},  // Moniajoratainen liittymä
          11: {fill: '#bebfbe', stroke: '#b75f29'},  // Pisaraliittymä
          12: {fill: '#bebebf', stroke: '#515151'},  // Liityntätie
          13: {fill: '#bfbebe', stroke: '#b79030'},  // Tien loppu
          14: {fill: '#ebaa9e', stroke: '#eb3223'},  // Silta
          15: {fill: '#fae6a3', stroke: '#f5c042'},  // Huoltoaukko
          16: {fill: '#dcb5d1', stroke: '#68389a'}   // Yksityistie- tai katuliittymä
        };

      var color = function (node) {
        if (node.type in colorMap) { // TODO assuming that node has a type attribute
          return colorMap[node.type];
        } else
          return {fill: '#888888', stroke: '#555555'};
      };

      var boxStyleNode = function (node) {
        var nodeColor = color(node);
        var nodeMarker = '<?xml version="1.0" encoding="UTF-8" standalone="no"?><svg xmlns="http://www.w3.org/2000/svg" class="node" height="24" width="18" viewBox="0 0 100 141.07506"><path style="opacity:1;fill:' + nodeColor.stroke + ';fill-opacity:1;stroke:none;" d="M 50,4.4085955 102.90315,70.537531 50,136.66646 -2.9031496,70.537531 Z" class="stroke" /><path class="fill" d="M 50,29.390638 82.917516,70.537531 50,111.68443 17.082484,70.537531 Z" style="opacity:1;fill:' + nodeColor.fill + ';fill-opacity:1;stroke:none;" /></svg>';
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
      createNodeMarker: createNodeMarker // TODO Take in use in the new layer: NodeLayer.js
    };
  };
}(this));
