(function (root) {
  root.SearchBox = function (instructionsPopup, locationSearch) {
    const tooltip = "Hae katuosoitteella (esim. 'Aputie 10', 'Aputie, Vihti', tai 'Aputie 10, Vihti'), \ntieosoitteella (esim. '2 1 1000 2', '2/1/1000/2', '2', '2/1' tai '2 1 1000'),\nlinkki-id:llä (esim. '06ad934c-5241-4055-9ae6-71d63190f6d7:1')\ntai koordinaateilla ('P, I', esim. '6673830, 388774')";
    const groupDiv = $('<div id="searchBox" class="panel-group search-box"></div>');
    const coordinatesDiv = $('<div class="panel"></div>');
    const inputWrapper = $('<div class="input-wrapper"></div>');
    const coordinatesText = $('<input type="text" class="location input-sm" placeholder="Osoite / koordinaatit" title="' + tooltip + '"/>');
    const clearButton = $('<button id="clearSearch" class="close wbtn-close clear-btn" aria-label="Tyhjennä haku" title="Tyhjennä haku"><i class="fas fa-times"></i></button>');
    const moveButton = $('<button id="executeSearch" class="btn btn-sm btn-primary">Hae</button>');
    const panelHeader = $('<div class="panel-header"></div>');
    const searchResults = $('<ul id="search-results"></ul>');
    const resultsSection = $('<div class="panel-section"></div>').append(searchResults).hide();

    inputWrapper.append(coordinatesText).append(clearButton);
    panelHeader.append(inputWrapper).append(moveButton);
    groupDiv.append(coordinatesDiv.append(panelHeader).append(resultsSection));

    const bindEvents = function () {
      const populateSearchResults = function (results) {
        const resultItems = _.chain(results).sortBy('distance').sortBy('title').sortBy(function (item) {
          return item.title.split(', ')[1];
        }).map(function (result) {
          return $('<li></li>').text(result.title).on('click', function () {
            eventbus.trigger('coordinates:selected', { lon: result.lon, lat: result.lat });
          });
        }).value();

        searchResults.html(resultItems);
        resultsSection.show();
        clearButton.show();
      };

      const moveToLocation = function () {
        const showDialog = function (message) {
          resultsSection.hide();
          clearButton.hide();
          instructionsPopup.show(_.isString(message) ? message : 'Yhteys Viitekehysmuuntimeen epäonnistui', 3000);
        };

        searchResults.html('Haku käynnissä…');
        resultsSection.show();

        locationSearch.search(coordinatesText.val()).then(function (results) {
          populateSearchResults(results);
          if (results.length === 1) {
            const result = results[0];
            eventbus.trigger('coordinates:selected', { lon: result.lon, lat: result.lat });
          }
        }).fail(showDialog);
      };

      coordinatesText.on('keypress', function (event) {
        if (event.keyCode === 13) {
          moveToLocation();
        }
      });

      moveButton.on('click', function () {
        moveToLocation();
      });

      clearButton.on('click', function () {
        coordinatesText.val('');
        coordinatesText.focus();
        resultsSection.hide();
        clearButton.hide();
      });

      clearButton.hide(); // Hide initially

      coordinatesText.on('input', function () {
        if (coordinatesText.val().length > 0) {
          clearButton.show();
        } else {
          clearButton.hide();
          resultsSection.hide();
        }
      });
    };

    bindEvents();

    this.element = groupDiv;
  };
}(this));
