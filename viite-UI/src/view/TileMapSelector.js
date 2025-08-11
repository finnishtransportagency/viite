(function (root) {
  root.TileMapSelector = function (container, applicationModel) {
    const element = `
      <div class="tile-map-selector">
        <ul>
          <li data-layerid="terrain" title="Maastokartta">Maastokartta</li>
          <li data-layerid="aerial" title="Ortokuvat">Ortokuvat</li>
          <li data-layerid="background" title="Taustakarttasarja" class="selected">Taustakarttasarja</li>
          <li data-layerid="none" title="Piilota kartta">Piilota kartta</li>
        </ul>

        <div class="property-boundaries-visible-wrapper">
          <div class="checkbox">
            <label>
              <input type="checkbox" id="propertyBoundariesVisibleCheckbox">
              Näytä kiinteistörajat
            </label>
          </div>
        </div>

        <div class="noroadaddress-visible-wrapper">
          <div class="checkbox">
            <label>
              <input type="checkbox" id="unAddressedRoadsVisibleCheckbox">
              Näytä tieosoitteettomat-linkit
            </label>
          </div>
        </div>

        <div class="underconstruction-visible-wrapper">
          <div class="checkbox">
            <label>
              <input type="checkbox" id="underConstructionVisibleCheckbox" checked>
              Näytä rakenteilla-linkit
            </label>
          </div>
        </div>

        <div class="roads-visible-wrapper">
          <div class="checkbox">
            <label>
              <input type="checkbox" id="roadsVisibleCheckbox" checked>
              Näytä tieosoiteverkko
            </label>
          </div>
        </div>
      </div>
    `;

    container.append(element);

    // Handle tile map selection
    container.find('li[data-layerid]').on('click', event => {
      container.find('li.selected').removeClass('selected');
      const selectedTileMap = $(event.target);
      selectedTileMap.addClass('selected');
      eventbus.trigger('tileMap:selected', selectedTileMap.data('layerid'));
    });


    // Toggle map visibility
    $('#toggleMapVisibility').on('click', function () {
      const map = $('#map');
      map.toggle();
      const isHidden = map.is(':hidden');
      $(this).text(isHidden ? 'Näytä kartta' : 'Piilota kartta');
    });

    $('#propertyBoundariesVisibleCheckbox').on('change', function () {
      eventbus.trigger('tileMap:togglepropertyBorder', this.checked);
    });
    $('#unAddressedRoadsVisibleCheckbox').on('change', function () {
      eventbus.trigger('unAddressedRoads:toggleVisibility', this.checked);
      eventbus.trigger('unAddressedProjectRoads:toggleVisibility', this.checked);
    });
    $('#underConstructionVisibleCheckbox').on('change', function () {
      eventbus.trigger('underConstructionRoads:toggleVisibility', this.checked);
      eventbus.trigger('underConstructionProjectRoads:toggleVisibility', this.checked);
    });
    $('#roadsVisibleCheckbox').on('change', function () {
      applicationModel.toggleRoadVisibility();
      eventbus.trigger('linkProperty:visibilityChanged');
      eventbus.trigger('roadAddressProject:visibilityChanged');
    });
  };
}(this));
