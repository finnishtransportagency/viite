/* eslint-disable prefer-named-capture-group */
(function (root) {
  var parse = function (input) {
    var coordinateRegex = /^\s*(\d+)\s*,\s*(\d+)\s*$/;
    var letters = /^(\s*[A-Za-zÀ-ÿ].*)/;
    var roadRegex = /^\s*\d*\s*\d*\s*\d*\s*\d+$/;

    var matchedCoordinates = input.match(coordinateRegex);
    if (matchedCoordinates) {
      return parseCoordinates(matchedCoordinates);
    } else if (input.match(letters)) {
      return {type: 'street', search: input};
    } else if (input.match(roadRegex)) {
      return {type: 'road', search: input};
    } else {
      return {type: 'invalid'};
    }
  };

  var parseCoordinates = function (coordinates) {
    return {type: 'coordinate', lat: _.parseInt(coordinates[1]), lon: _.parseInt(coordinates[2])};
  };

  root.LocationInputParser = {
    parse: parse
  };
}(window));
