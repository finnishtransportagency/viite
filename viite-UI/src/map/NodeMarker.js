(function (root) {
  root.NodeMarker = function () {
    var createNodeMarker = function (node, roadLinkForPoint, getCoordinates) {
      // add coordinates to node points
      _.each(node.nodePoints, function (nodePoint) {
        var roadLink = roadLinkForPoint(function (roadLink) {
          return (roadLink.startAddressM === nodePoint.addrM || roadLink.endAddressM === nodePoint.addrM) && roadLink.roadwayNumber === nodePoint.roadwayNumber;
        });

        if (!_.isUndefined(roadLink)) {
          var point = [];

          if ((roadLink.sideCode === LinkValues.SideCode.TowardsDigitizing.value && nodePoint.addrM === roadLink.endAddressM) || (roadLink.sideCode === LinkValues.SideCode.AgainstDigitizing.value && nodePoint.addrM === roadLink.startAddressM)) {
            point = roadLink.points[roadLink.points.length - 1];
          } else {
            point = roadLink.points[0];
          }
          nodePoint.coordinates = { x: point.x, y: point.y };
        } else {
          getCoordinates(nodePoint.road, nodePoint.part, nodePoint.addrM, function (results) {
            if (results.length >= 1) {
              var result = results[0];
              nodePoint.coordinates = { x: parseFloat(result.lon).toFixed(3), y: parseFloat(result.lat).toFixed(3) };
            }
          });
        }
      });

      // add coordinates to junction points
      _.each(_.flatMap(node.junctions, "junctionPoints"), function (junctionPoint) {
        var roadLink = roadLinkForPoint(function (roadLink) {
          return (roadLink.startAddressM === junctionPoint.addrM || roadLink.endAddressM === junctionPoint.addrM) && roadLink.roadwayNumber === junctionPoint.roadwayNumber;
        });

        if (!_.isUndefined(roadLink)) {
          var point = [];

          if ((roadLink.sideCode === LinkValues.SideCode.TowardsDigitizing.value && junctionPoint.addrM === roadLink.endAddressM) || (roadLink.sideCode === LinkValues.SideCode.AgainstDigitizing.value && junctionPoint.addrM === roadLink.startAddressM)) {
            point = roadLink.points[roadLink.points.length - 1];
          } else {
            point = roadLink.points[0];
          }
          junctionPoint.coordinates = { x: point.x, y: point.y };
        } else {
          getCoordinates(junctionPoint.road, junctionPoint.part, junctionPoint.addrM, function (results) {
            if (results.length >= 1) {
              var result = results[0];
              junctionPoint.coordinates = { x: parseFloat(result.lon).toFixed(3), y: parseFloat(result.lat).toFixed(3) };
            }
          });
        }
      });

      var marker = new ol.Feature({
        geometry: new ol.geom.Point([node.coordinates.x, node.coordinates.y]),
        type: node.type
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
