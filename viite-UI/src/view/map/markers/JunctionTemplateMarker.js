/**
 * JunctionTemplateMarker - Creates styled OpenLayers markers for junction templates.
 */
export function JunctionTemplateMarker() {
  const createJunctionTemplateMarker = function (junctionTemplate) {
    const marker = new ol.Feature({
      geometry: new ol.geom.Point([_.first(junctionTemplate.junctionPoints).coordinates.x, _.first(junctionTemplate.junctionPoints).coordinates.y]),
      junctionNumber: junctionTemplate.junctionNumber
    });

    const junctionTemplateStyleProvider = function (junctionNumber) {
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
    marker.setStyle(junctionTemplateStyleProvider(junctionTemplate.junctionNumber));
    return marker;
  };

  return {
    createJunctionTemplateMarker: createJunctionTemplateMarker
  };
}
