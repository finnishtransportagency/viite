(function(root) {
  var parse = function(input) {
    var coordinateRegex = /^\s*(\d+)\s*,\s*(\d+)\s*$/;
    var streetRegex = /^\s*[^0-9,]+\s*\d*(,\s*[^0-9,]+\s*$)?/;
    var roadRegex = /^\s*\d*\s*\d*\s*\d*\s*\d+$/;

    var matchedCoordinates = input.match(coordinateRegex);
    if (matchedCoordinates) {
      return parseCoordinates(matchedCoordinates);
    } else if (input.match(streetRegex)) {
      return { type: 'street', search: input };
    } else if (input.match(roadRegex)) {
      return { type: 'road', search: input };
    } else {
      return { type: 'invalid' };
    }
  };

  var parseCoordinates = function(coordinates) {
    return { type: 'coordinate', lat: _.parseInt(coordinates[1]), lon: _.parseInt(coordinates[2]) };
  };

  root.LocationInputParser = {
    parse: parse
  };
})(window);
