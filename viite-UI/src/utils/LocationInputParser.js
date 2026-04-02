/* eslint-disable prefer-named-capture-group */

/* Categorizes input type as 'coordinate', 'road', or 'street', or to be 'invalid'. */
const parse = function (input) {
  var coordinateRegex = /^\s*(\d+)\s*,\s*(\d+)\s*$/;
  var wildLetterRegex = /^(\s*[*]*[A-Za-zÀ-ÿ].*)/;
  var roadNumberRegex = /^\s*(\d+(\s+\d+(\s+\d+(\s+\d)?)?)?)\s*$/; // road addr separated with whitespaces.
  var roadNumberRegex2 = /^\s*(\d+(\/\d+(\/\d+(\/\d)?)?)?)\s*$/; // road addr separated with slashes.
  const linkIdRegex = /^\s*(\w+-\w+-\w+-\w+-\w+:\d+)\s*$/;

  var matchedCoordinates = input.match(coordinateRegex);
  if (matchedCoordinates) { return parseCoordinates(matchedCoordinates); }
  else if (input.match(roadNumberRegex)) { return {type: 'road', search: input}; }
  else if (input.match(roadNumberRegex2)) { return {type: 'road', search: input}; }
  else if (input.match(linkIdRegex)) { return {type: 'road', search: input}; }
  else if (input.match(wildLetterRegex)) { return {type: 'street', search: input}; }
  else { return {type: 'invalid'}; }
};

const parseCoordinates = function (coordinates) {
  return {type: 'coordinate', lat: _.parseInt(coordinates[1]), lon: _.parseInt(coordinates[2])};
};

export const LocationInputParser = {
  parse: parse
};

window.LocationInputParser = LocationInputParser;
