/* eslint-disable new-cap */
(function (root) {
  root.LocationSearch = function (backend, applicationModel) {
    /**
     * Search by street address
     *
     * @param street
     * @returns {*}
     */
    const geocode = function (street) {
      return backend.getSearchResults(street.search).then(function (coordinateData) {
        const result = coordinateData[0].street[0].features;
        const withErrors = _.some(result, function(r) {return !_.isUndefined(r.properties.virheet);});
        const vkmResultToCoordinates = function(r) {
          return { title: r.properties.katunimi + " " + r.properties.katunumero + ", " + r.properties.kuntanimi, lon: r.properties.x, lat: r.properties.y};
        };
        if (withErrors) {
          return $.Deferred().reject('Tuntematon katuosoite');
        } else {
          return _.map(result, vkmResultToCoordinates);
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
      const sideCodes = LinkValues.SideCode;
      const constructTitle = function (address) {
        const titleParts = [_.get(address, 'roadNumber'), _.get(address, 'roadPartNumber')];
        return _.some(titleParts, _.isUndefined) ? '' : 'Tieosa, ' + titleParts.join(' ');
      };
      let lon, lat;
      const addressMValueFixed = _.isUndefined(addressMValue) ? 0 : addressMValue;
      if ((roadData.startAddrMValue === addressMValueFixed && roadData.sideCode === sideCodes.TowardsDigitizing.value) || (roadData.endAddrMValue === addressMValueFixed && roadData.sideCode === sideCodes.AgainstDigitizing.value)) {
        lon = roadData.geometry[0].x;
        lat = roadData.geometry[0].y;
      } else {
        lon = roadData.geometry[roadData.geometry.length - 1].x;
        lat = roadData.geometry[roadData.geometry.length - 1].y;
      }
      const title = constructTitle(roadData);
      if (lon && lat) {
        return [{title: title, x: lon, y: lat, resultType: "road"}];
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
    const getCoordinatesFromSearchInput = function (input) {
      return backend.getSearchResults(input.search).then(function (coordinateData) {
        const searchResult = [];
        coordinateData.forEach(function (item) {
          let partialResult;
          if (item && item.linkId && item.linkId[0]) {
            partialResult = _.first(item.linkId);
            partialResult.title = `linkId, ${input.search}`;
          } else if (item && item.mtkId && item.mtkId[0]) {
            partialResult = _.first(item.mtkId);
            partialResult.title = `mtkId, ${input.search}`;
          } else if (item && item.road && item.road[0]) {
            const sortedRoad = _.sortBy(_.sortBy(item.road, function (addr) {
              return addr.startAddrMValue;
            }), function (road) {
              return road.roadPartNumber;
            });
            const parsed = _.map(_.words(input.search), _.parseInt);
            partialResult = _.first(roadLocationAPIResultParser(sortedRoad[0], parsed[2]));
          } else if (item && item.roadM && item.roadM[0]) {
            partialResult = _.first(item.roadM);
            partialResult.title = 'Tieosoite, ' + input.search;
          }

          if (partialResult) {
            if (partialResult.x) {
              partialResult.lon = partialResult.x;
              partialResult.lat = partialResult.y;
            }
            searchResult.push(partialResult);
          }
        });
        if (searchResult.length === 0) {
          return $.Deferred().reject('Tuntematon tieosoite');
        } else {
          return searchResult;
        }
      });
    };

    const resultFromCoordinates = function (coordinates) {
      const result = _.assign({}, coordinates, {title: coordinates.lat + ',' + coordinates.lon});
      return $.Deferred().resolve([result]);
    };

    this.search = function (searchString) {
      function addDistance(item) {
        const currentLocation = applicationModel.getCurrentLocation();
        const distance = GeometryUtils.distanceOfPoints(
          [currentLocation[0], currentLocation[1]],
          [item.lon, item.lat]);
        return _.assign(item, {
          distance: distance
        });
      }

      const input = LocationInputParser.parse(searchString);
      const resultByInputType = {
        coordinate: resultFromCoordinates,
        street: geocode,
        road: getCoordinatesFromSearchInput,
        invalid: function () {
          return $.Deferred().reject('Syötteestä ei voitu päätellä koordinaatteja, katuosoitetta tai tieosoitetta');
        }
      };

      const results = resultByInputType[input.type](input);
      return results.then(function (result) {
        return _.map(result, addDistance);
      });
    };
  };
}(this));
