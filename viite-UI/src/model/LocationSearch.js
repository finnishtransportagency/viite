(function(root) {
  root.LocationSearch = function(backend, applicationModel) {
    /**
     * Search by street address
     *
     * @param street
     * @returns {*}
     */
    var geocode = function(street) {
      return backend.getGeocode(street.address).then(function(result) {
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
      if (addressMValue === 0 && (roadData.startAddrMValue === addressMValue && roadData.sideCode === sideCodes.TowardsDigitizing.value)) {
        lon = roadData.geometry[0].x;
        lat = roadData.geometry[0].y;
      } else if (addressMValue === 0 && (roadData.endAddrMValue === addressMValue && roadData.sideCode === sideCodes.AgainstDigitizing.value)) {
        lon = roadData.geometry[roadData.geometry.length - 1].x;
        lat = roadData.geometry[roadData.geometry.length - 1].y;
      } else {
        lon = roadData.geometry[roadData.geometry.length - 1].x;
        lat = roadData.geometry[roadData.geometry.length - 1].y;
      }
      var title = constructTitle(roadData);
      if (lon && lat) {
        return  [{title: title, lon: lon, lat: lat, resultType:"road"}];
      } else {
        return [];
      }
    }


    /**
     * Get road address coordinates
     *
     * @param road
     * @returns {*}
     */
    var getCoordinatesFromRoadAddress = function (road) {
      return backend.getCoordinatesFromRoadAddress(road.roadNumber, road.section, road.distance).then(function (roadData) {
        if (!_.isUndefined(roadData) && roadData.length > 0) {
          var sortedRoad = _.sortBy(_.sortBy(roadData, function (addr) {
            return addr.startAddrMValue;
          }), function (road) {
            return road.roadPartNumber;
          });
          var searchResult = roadLocationAPIResultParser(sortedRoad[0], road.distance);
          if (searchResult.length === 0) {
            return $.Deferred().reject('Tuntematon tieosoite');
          } else {
            return searchResult;
          }
        } else return [];
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
        road: getCoordinatesFromRoadAddress,
        invalid: function() { return $.Deferred().reject('Syötteestä ei voitu päätellä koordinaatteja, katuosoitetta tai tieosoitetta'); }
      };

      var results = resultByInputType[input.type](input);
      return results.then(function(result) {
        return _.map(result, addDistance);
      });
    };
  };
})(this);
