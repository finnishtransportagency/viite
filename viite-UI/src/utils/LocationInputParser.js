/* eslint-disable prefer-named-capture-group */

/* Categorizes input type as 'coordinate', 'road', or 'street', or to be 'invalid'. */
const parse = function (input) {
	const coordinateRegex = /^\s*(\d+)\s*,\s*(\d+)\s*$/;
	const wildLetterRegex = /^(\s*[*]*[A-Za-zÀ-ÿ].*)/;
	const roadNumberRegex = /^\s*(\d+(\s+\d+(\s+\d+(\s+\d)?)?)?)\s*$/; // road addr separated with whitespaces.
	const roadNumberRegex2 = /^\s*(\d+(\/\d+(\/\d+(\/\d)?)?)?)\s*$/; // road addr separated with slashes.
	const linkIdRegex = /^\s*(\w+-\w+-\w+-\w+-\w+:\d+)\s*$/;

	const matchedCoordinates = input.match(coordinateRegex);
	if (matchedCoordinates) { return parseCoordinates(matchedCoordinates); }
	else if (input.match(roadNumberRegex)) { return {type: 'road', search: input}; }
	else if (input.match(roadNumberRegex2)) { return {type: 'road', search: input}; }
	else if (input.match(linkIdRegex)) { return {type: 'road', search: input}; }
	else if (input.match(wildLetterRegex)) { return {type: 'street', search: input}; }
	else { return {type: 'invalid'}; }
};

// Input order does not matter: the smaller value is the easting (lon), the larger is the northing (lat).
const parseCoordinates = function (coordinates) {
	const [lon, lat] = _.sortBy([_.parseInt(coordinates[1]), _.parseInt(coordinates[2])]);
	return {type: 'coordinate', lat: lat, lon: lon};
};

export const LocationInputParser = {
	parse: parse
};

// This is needed for Mocha tests to work
if (typeof module !== 'undefined' && module.exports) {
	module.exports = { LocationInputParser };
}
