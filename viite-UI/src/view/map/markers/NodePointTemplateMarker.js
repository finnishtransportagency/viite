/**
 * NodePointTemplateMarker - Creates styled OpenLayers markers for node point templates.
 */
export function NodePointTemplateMarker() {
  const createNodePointTemplateMarker = function (nodePoint) {
    const marker = new ol.Feature({
      geometry: new ol.geom.Point([nodePoint.coordinates.x, nodePoint.coordinates.y])
    });

    const nodePointMarkerStyle = new ol.style.Style({
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
}
