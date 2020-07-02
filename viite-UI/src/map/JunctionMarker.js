(function (root) {
  root.JunctionMarker = function () {
    var createJunctionMarker = function (junction) {

      var marker = new ol.Feature({
        geometry: new ol.geom.Point([_.first(junction.junctionPoints).coordinates.x, _.first(junction.junctionPoints).coordinates.y]),
        junctionNumber: junction.junctionNumber
      });

      var junctionMarkerStyleProvider = function(junctionNumber) {
        return new ol.style.Style({
          image: new ol.style.Icon({
            src: 'images/junction.svg',
            scale: 0.75
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
        this.setStyle(junctionMarkerStyleProvider(this.get('junctionNumber')));
      });

      marker.junction = junction;
      marker.setStyle(junctionMarkerStyleProvider(junction.junctionNumber));
      return marker;
    };

    return {
      createJunctionMarker: createJunctionMarker
    };
  };
}(this));