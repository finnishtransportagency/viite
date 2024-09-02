/* eslint-disable prefer-named-capture-group */
(function (root, factory) {
  // UMD (Universal Module Definition) so the code is compatible with both Node.js and browser environments
  if (typeof module === 'object' && module.exports) {
    // Node.js environment
    module.exports = factory(require('lodash'));
  } else {
    // Browser environment
    root.LocationInputParser = factory(root._);
  }
}(this, function (_) {

  /* Categorizes input type as 'coordinate', 'road', or 'street', or to be 'invalid'.*/
  var parse = function (input) {
    var coordinateRegex = /^\s*(\d+)\s*,\s*(\d+)\s*$/;
    var wildLetterRegex = /^(\s*[*]*[A-Za-zÀ-ÿ].*)/;
    var roadNumberRegex = /^\s*(\d+(\s+\d+(\s+\d+(\s+\d)?)?)?)\s*$/; // road addr separated with whitespaces.
    const linkIdRegex   = /^\s*(\w+-\w+-\w+-\w+-\w+:\d+)\s*$/;

    var matchedCoordinates = input.match(coordinateRegex);
    if      (matchedCoordinates)            {    return parseCoordinates(matchedCoordinates);  }
    else if (input.match(roadNumberRegex) ) {    return {type: 'road',   search: input};  }
    else if (input.match(linkIdRegex)     ) {    return {type: 'road',   search: input};  }
    else if (input.match(wildLetterRegex) ) {    return {type: 'street', search: input};  }
    else                                    {    return {type: 'invalid'              };  }
  };

  var parseCoordinates = function (coordinates) {
    return {type: 'coordinate', lat: _.parseInt(coordinates[1]), lon: _.parseInt(coordinates[2])};
  };

  return {
    parse: parse
  };
}));
