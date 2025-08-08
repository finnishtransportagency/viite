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

        <!-- Dropdown version for small screens -->
        <div class="checkbox-dropdown-wrapper">
          <button class="dropdown-toggle" aria-expanded="false">Valitse karttavaihtoehdot</button>
          <div class="checkbox-dropdown">
            <label><input type="checkbox" value="propertyBoundariesVisible"  id="dropdown-propertyBoundariesVisible"> Näytä kiinteistörajat</label><br>
            <label><input type="checkbox" value="unAddressedRoadsVisible" id="dropdown-unAddressedRoadsVisible"> Näytä tieosoitteettomat-linkit</label><br>
            <label><input type="checkbox" value="underConstructionVisible" id="dropdown-underConstructionVisible"> Näytä rakenteilla-linkit</label><br>
            <label><input type="checkbox" value="roadsVisible" id="dropdown-roadsVisible"> Näytä tieosoiteverkko</label>
          </div>
        </div>
      </div>
    `;

    container.append(element);

    const BREAKPOINT_PX = 1220;

    const $checkboxDropdownWrapper = container.find('.checkbox-dropdown-wrapper');
    const $dropdownToggle = $checkboxDropdownWrapper.find('.dropdown-toggle');
    const $dropdownCheckboxes = $checkboxDropdownWrapper.find('input[type="checkbox"]');

    const dropdownValueToCheckboxId = {
      propertyBoundariesVisible: 'propertyBoundariesVisibleCheckbox',
      unAddressedRoadsVisible: 'unAddressedRoadsVisibleCheckbox',
      underConstructionVisible: 'underConstructionVisibleCheckbox',
      roadsVisible: 'roadsVisibleCheckbox'
    };

    // Sync dropdown checkboxes to match main checkboxes (no events triggered)
    function syncDropdownCheckboxesFromMain() {
      $dropdownCheckboxes.each(function () {
        const value = $(this).val();
        const mainCheckbox = container.find(`#${dropdownValueToCheckboxId[value]}`);
        $(this).prop('checked', Boolean(mainCheckbox.prop('checked')));
      });
    }

    // When a dropdown checkbox changes, update the corresponding main checkbox
    // and trigger its change handler only if its state actually changed.
    function syncMainCheckboxesFromDropdownAndTrigger() {
      $dropdownCheckboxes.each(function () {
        const value = $(this).val();
        const mainCheckbox = container.find(`#${dropdownValueToCheckboxId[value]}`);
        const newChecked = Boolean($(this).prop('checked'));
        const prevChecked = Boolean(mainCheckbox.prop('checked'));
        if (prevChecked !== newChecked) {
          mainCheckbox.prop('checked', newChecked);
          mainCheckbox.trigger('change'); // will run the main checkbox handler once
        }
      });
    }

    // Per-checkbox handlers (match original behavior)
    container.on('change', '#propertyBoundariesVisibleCheckbox', function () {
      eventbus.trigger('tileMap:togglepropertyBorder', this.checked);
    });

    container.on('change', '#unAddressedRoadsVisibleCheckbox', function () {
      eventbus.trigger('unAddressedRoads:toggleVisibility', this.checked);
      eventbus.trigger('unAddressedProjectRoads:toggleVisibility', this.checked);
    });

    container.on('change', '#underConstructionVisibleCheckbox', function () {
      eventbus.trigger('underConstructionRoads:toggleVisibility', this.checked);
      eventbus.trigger('underConstructionProjectRoads:toggleVisibility', this.checked);
    });

    // keep same signature as original: call toggleRoadVisibility() (no arg) so existing applicationModel logic is preserved
    container.on('change', '#roadsVisibleCheckbox', function () {
      applicationModel.toggleRoadVisibility();
      eventbus.trigger('linkProperty:visibilityChanged');
      eventbus.trigger('roadAddressProject:visibilityChanged');
    });

    // Tile map selection (keeps single-selection behavior for base maps)
    container.find('li[data-layerid]').on('click', event => {
      container.find('li.selected').removeClass('selected');
      const selectedTileMap = $(event.target);
      selectedTileMap.addClass('selected');
      eventbus.trigger('tileMap:selected', selectedTileMap.data('layerid'));
    });

    // Toggle map visibility button (unchanged)
    $('#toggleMapVisibility').on('click', function () {
      const map = $('#map');
      map.toggle();
      const isHidden = map.is(':hidden');
      $(this).text(isHidden ? 'Näytä kartta' : 'Piilota kartta');
    });

    // Dropdown toggle
    $dropdownToggle.on('click', function (e) {
      e.stopPropagation();
      const isOpen = $checkboxDropdownWrapper.hasClass('open');
      if (isOpen) {
        $checkboxDropdownWrapper.removeClass('open');
        $dropdownToggle.attr('aria-expanded', 'false');
      } else {
        // before opening, sync states so checkbox states reflect current mains
        syncDropdownCheckboxesFromMain();
        $checkboxDropdownWrapper.addClass('open');
        $dropdownToggle.attr('aria-expanded', 'true');
      }
    });

    // Close dropdown when clicking outside
    $(document).on('click', function (e) {
      if (!$(e.target).closest('.checkbox-dropdown-wrapper').length) {
        $checkboxDropdownWrapper.removeClass('open');
        $dropdownToggle.attr('aria-expanded', 'false');
      }
    });

    // When dropdown checkbox changes -> sync main checkboxes and run main handlers as necessary
    $dropdownCheckboxes.on('change', function () {
      syncMainCheckboxesFromDropdownAndTrigger();
    });

    // When any main checkbox changes, sync dropdown checkboxes when on small screens
    container.on('change', '#propertyBoundariesVisibleCheckbox, #unAddressedRoadsVisibleCheckbox, #underConstructionVisibleCheckbox, #roadsVisibleCheckbox', function () {
      if (window.innerWidth <= BREAKPOINT_PX) {
        syncDropdownCheckboxesFromMain();
      }
    });

    function updateUIForScreenSize() {
      if (window.innerWidth <= BREAKPOINT_PX) {
        container.find('.property-boundaries-visible-wrapper, .noroadaddress-visible-wrapper, .underconstruction-visible-wrapper, .roads-visible-wrapper').hide();
        $checkboxDropdownWrapper.show();
        syncDropdownCheckboxesFromMain();
        $checkboxDropdownWrapper.removeClass('open');
        $dropdownToggle.attr('aria-expanded', 'false');
      } else {
        container.find('.property-boundaries-visible-wrapper, .noroadaddress-visible-wrapper, .underconstruction-visible-wrapper, .roads-visible-wrapper').show();
        $checkboxDropdownWrapper.hide();
        $checkboxDropdownWrapper.removeClass('open');
        $dropdownToggle.attr('aria-expanded', 'false');
      }
    }

    updateUIForScreenSize();

    window.addEventListener('resize', () => {
      updateUIForScreenSize();
      if (window.innerWidth > BREAKPOINT_PX) {
        $checkboxDropdownWrapper.removeClass('open');
        $dropdownToggle.attr('aria-expanded', 'false');
      }
    });
  };
}(this));
