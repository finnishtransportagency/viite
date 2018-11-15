(function(root) {
  root.TileMapSelector = function(container, applicationModel) {
    var element =
    '<div class="tile-map-selector">' +
      '<ul>' +
        '<li data-layerid="terrain" title="Maastokartta">Maastokartta</li>' +
        '<li data-layerid="aerial" title="Ortokuvat">Ortokuvat</li>' +
        '<li data-layerid="background" title="Taustakarttasarja" class="selected">Taustakarttasarja</li>' +
        '<li data-layerid="greyscale" title="Harmaasävy">Harmaasävykartta</li>' +
      '</ul>' +
      '<div class="property-boundaries-visible-wrapper">' +
        '<div class="checkbox">' +
          '<label><input type="checkbox" name="propertyBoundariesVisible" value="propertyBoundariesVisible"  id="propertyBoundariesVisibleCheckbox">Näytä kiinteistörajat</label>' +
        '</div>' +
      '</div>' +
      '<div class="suravage-visible-wrapper">' +
        '<div class="checkbox">' +
          '<label><input type="checkbox" name="suravageVisible" value="suravageVisible" checked="true" id="suravageVisibleCheckbox">Näytä Suravage-Linkit</label>' +
        '</div>' +
      '</div>' +
        '<div class="roads-visible-wrapper">' +
        '<div class="checkbox">' +
        '<label><input type="checkbox" name="roadsVisible" value="roadsVisible" checked="true" id="roadsVisibleCheckbox">Näytä tieosoiteverkko</label>' +
        '</div>' +
        '</div>' +
    '</div>';

    container.append(element);
    container.find('li').click(function(event) {
      container.find('li.selected').removeClass('selected');
      var selectedTileMap = $(event.target);
      selectedTileMap.addClass('selected');
      eventbus.trigger('tileMap:selected', selectedTileMap.attr('data-layerid'));
    });
    $('#propertyBoundariesVisibleCheckbox').change(function () {
      eventbus.trigger('tileMap:togglepropertyBorder', this.checked);
    });

    $('#suravageVisibleCheckbox').change(function() {
      eventbus.trigger('suravageRoads:toggleVisibility', this.checked);
      eventbus.trigger("suravageProjectRoads:toggleVisibility", this.checked);
    });
    $('#roadsVisibleCheckbox').change(function () {
      applicationModel.toggleRoadVisibility();
      eventbus.trigger('linkProperty:visibilityChanged');
      eventbus.trigger('roadAddressProject:visibilityChanged');

    });

  };
})(this);