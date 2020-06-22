(function(root) {
  root.LocationSearch = function(backend, applicationModel) {
    /**
     * Search by street address
     *
     * @param street
     * @returns {*}
     */
    var geocode = function(street) {
      return backend.getSearchResults(street.search).then(function (coordinateData) {
        var result = coordinateData[0].street[0];
        var resultLength = _.get(result, 'results.length');
        var vkmResultToCoordinates = function(r) {
          return { title: r.address, lon: r.x, lat: r.y};
        };
        if (resultLength > 0) {
          return _.map(result.results, vkmResultToCoordinates);
        } else {
          return $.Deferred().reject('Tuntematon katuosoite');
        }
      });
    };

    /**
     * Road address search
     *
     * @param roadData
     * @param addressMValue
     * @returns {*}
     */
    function roadLocationAPIResultParser(roadData, addressMValue) {
      var sideCodes = LinkValues.SideCode;
      var constructTitle = function(address) {
        var titleParts = [_.get(address, 'roadNumber'), _.get(address, 'roadPartNumber')];
        return _.some(titleParts, _.isUndefined) ? '' : titleParts.join(' ');
      };
      var lon, lat = 0;
      addressMValue = _.isUndefined(addressMValue) ? 0 : addressMValue;
      if ((roadData.startAddrMValue === addressMValue && roadData.sideCode === sideCodes.TowardsDigitizing.value) || (roadData.endAddrMValue === addressMValue && roadData.sideCode === sideCodes.AgainstDigitizing.value) ) {
        lon = roadData.geometry[0].x;
        lat = roadData.geometry[0].y;
      } else {
        lon = roadData.geometry[roadData.geometry.length - 1].x;
        lat =  roadData.geometry[roadData.geometry.length - 1].y;
      }
      var title = constructTitle(roadData);
      if (lon && lat) {
        return  [{title: title, lon: lon, lat: lat, resultType:"road"}];
      } else {
        return [];
      }
    }


    /**
     * Get coordinates for road address, linkId and mtkId
     *
     * @param input
     * @returns {*}
     */
    var getCoordinatesFromSearchInput = function (input) {
      return backend.getSearchResults(input.search).then(function (coordinateData) {
        var searchResult = [];
        if (!_.isUndefined(coordinateData)) {
          coordinateData.forEach(function (item) {
            if (item && item.linkId && item.linkId[0]) {
              item.linkId[0].lon = item.linkId[0].x;
              item.linkId[0].lat = item.linkId[0].y;
              item.linkId[0].title = 'linkId, ' + input.search;
              searchResult.push(item.linkId[0]);
            } else if (item && item.mtkId && item.mtkId[0]) {
              item.mtkId[0].lon = item.mtkId[0].x;
              item.mtkId[0].lat = item.mtkId[0].y;
              item.mtkId[0].title = 'mtkId, ' + input.search;
              searchResult.push(item.mtkId[0]);
            } else if (item && item.road && item.road[0]) {
              var sortedRoad = _.sortBy(_.sortBy(item.road, function (addr) {
                return addr.startAddrMValue;
              }), function (road) {
                return road.roadPartNumber;
              });
              var parsed = _.map(_.words(input.search), _.parseInt);
              searchResult = searchResult.concat(roadLocationAPIResultParser(sortedRoad[0], parsed[2]));
            }
          });
        } else return [];
        if (searchResult.length === 0) {
          return $.Deferred().reject('Tuntematon tieosoite');
        } else {
          return searchResult;
        }
      });
    };

    var resultFromCoordinates = function (coordinates) {
      var result = _.assign({}, coordinates, {title: coordinates.lat + ',' + coordinates.lon});
      return $.Deferred().resolve([result]);
    };

    this.search = function(searchString) {
      function addDistance(item) {
        var currentLocation = applicationModel.getCurrentLocation();

        var distance = GeometryUtils.distanceOfPoints(
          [currentLocation[0], currentLocation[1]],
          [item.lon, item.lat]);
        return _.assign(item, {
          distance: distance
        });
      }

      var input = LocationInputParser.parse(searchString);
      var resultByInputType = {
        coordinate: resultFromCoordinates,
        street: geocode,
        road: getCoordinatesFromSearchInput,
        invalid: function() { return $.Deferred().reject('Syötteestä ei voitu päätellä koordinaatteja, katuosoitetta tai tieosoitetta'); }
      };

      var results = resultByInputType[input.type](input);
      return results.then(function(result) {
        return _.map(result, addDistance);
      });
    };
  };
})(this);
