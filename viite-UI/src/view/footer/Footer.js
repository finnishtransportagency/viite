import { checkbox } from '@components/checkbox/Checkbox.js';
import { eventbus } from '@utils/eventbus.js';

/* Contains following elements:
- TileMapSelector: A set of buttons used for selecting which map background to show
- CoordinatesDisplay: Displays the coordinates of the center of the map and contains a button for marking those coordinates on the map
- CrosshairToggle: A checkbox for toggling a crosshair in the center of the map and clicking on the map through it (see MapView.js for details)
*/

export function Footer(map, container, applicationModel) {
  const element = '<div class="map-footer"></div>';
  container.append(element);

  const footerContainer = container.find('.map-footer');
  renderTileMapSelector(footerContainer, applicationModel);
  renderCoordinatesDisplay(footerContainer);
  createCrosshairToggle(footerContainer.find('.mapplugin.coordinates'), map);
}

function renderTileMapSelector(container, applicationModel) {
  const renderLayerOptionCheckbox = (id, label, checked = false) => `
      <div class="layer-option-visible-wrapper">
        ${checkbox({
          id,
          label,
          checked
        })}
      </div>
    `;

  const renderDropdownCheckbox = (value, label) => checkbox({
    id: `dropdown-${value}`,
    label,
    value
  });

  const element = `
      <div class="tile-map-selector">
        <ul>
          <li data-layerid="terrain" title="Maastokartta">Maastokartta</li>
          <li data-layerid="aerial" title="Ortokuvat">Ortokuvat</li>
          <li data-layerid="background" title="Taustakarttasarja" class="selected">Taustakarttasarja</li>
          <li data-layerid="none" title="Piilota kartta">Piilota kartta</li>
        </ul>

        ${renderLayerOptionCheckbox('propertyBoundariesVisibleCheckbox', 'Näytä kiinteistörajat')}
        ${renderLayerOptionCheckbox('unAddressedRoadsVisibleCheckbox', 'Näytä tieosoitteettomat-linkit', true)}
        ${renderLayerOptionCheckbox('underConstructionVisibleCheckbox', 'Näytä rakenteilla-linkit', true)}
        ${renderLayerOptionCheckbox('roadsVisibleCheckbox', 'Näytä tieosoiteverkko', true)}
        ${renderLayerOptionCheckbox('regionalBordersVisibleCheckbox', 'Näytä maakuntarajat')}

        <div class="checkbox-dropdown-wrapper">
          <button class="dropdown-toggle" aria-expanded="false">Valitse karttavaihtoehdot</button>
          <div class="checkbox-dropdown">
            ${renderDropdownCheckbox('propertyBoundariesVisible', 'Näytä kiinteistörajat')}
            ${renderDropdownCheckbox('unAddressedRoadsVisible', 'Näytä tieosoitteettomat-linkit')}
            ${renderDropdownCheckbox('underConstructionVisible', 'Näytä rakenteilla-linkit')}
            ${renderDropdownCheckbox('roadsVisible', 'Näytä tieosoiteverkko')}
            ${renderDropdownCheckbox('regionalBordersVisible', 'Näytä maakuntarajat')}
          </div>
        </div>
      </div>
    `;

  container.append(element);

  const BREAKPOINT_PX = 1470;

  const $checkboxDropdownWrapper = container.find('.checkbox-dropdown-wrapper');
  const $dropdownToggle = $checkboxDropdownWrapper.find('.dropdown-toggle');
  const $dropdownCheckboxes = $checkboxDropdownWrapper.find('input[type="checkbox"]');

  const dropdownValueToCheckboxId = {
    propertyBoundariesVisible: 'propertyBoundariesVisibleCheckbox',
    unAddressedRoadsVisible: 'unAddressedRoadsVisibleCheckbox',
    underConstructionVisible: 'underConstructionVisibleCheckbox',
    roadsVisible: 'roadsVisibleCheckbox',
    regionalBordersVisible: 'regionalBordersVisibleCheckbox'
  };

  function syncDropdownCheckboxesFromMain() {
    $dropdownCheckboxes.each(function () {
      const value = $(this).val();
      const mainCheckbox = container.find(`#${dropdownValueToCheckboxId[value]}`);
      $(this).prop('checked', Boolean(mainCheckbox.prop('checked')));
    });
  }

  function syncMainCheckboxesFromDropdownAndTrigger() {
    $dropdownCheckboxes.each(function () {
      const value = $(this).val();
      const mainCheckbox = container.find(`#${dropdownValueToCheckboxId[value]}`);
      const newChecked = Boolean($(this).prop('checked'));
      const prevChecked = Boolean(mainCheckbox.prop('checked'));
      if (prevChecked !== newChecked) {
        mainCheckbox.prop('checked', newChecked);
        mainCheckbox.trigger('change');
      }
    });
  }

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

  container.on('change', '#roadsVisibleCheckbox', function () {
    applicationModel.toggleRoadVisibility();
    eventbus.trigger('linkProperty:visibilityChanged');
    eventbus.trigger('roadAddressProject:visibilityChanged');
  });

  container.on('change', '#regionalBordersVisibleCheckbox', function () {
    eventbus.trigger('tileMap:toggleRegionalBorders', this.checked);
  });

  container.find('li[data-layerid]').on('click', event => {
    container.find('li.selected').removeClass('selected');
    const selectedTileMap = $(event.target);
    selectedTileMap.addClass('selected');
    eventbus.trigger('tileMap:selected', selectedTileMap.data('layerid'));
  });

  $dropdownToggle.on('click', function (e) {
    e.stopPropagation();
    const isOpen = $checkboxDropdownWrapper.hasClass('open');
    if (isOpen) {
      $checkboxDropdownWrapper.removeClass('open');
      $dropdownToggle.attr('aria-expanded', 'false');
    } else {
      syncDropdownCheckboxesFromMain();
      $checkboxDropdownWrapper.addClass('open');
      $dropdownToggle.attr('aria-expanded', 'true');
    }
  });

  $(document).on('click', function (e) {
    if (!$(e.target).closest('.checkbox-dropdown-wrapper').length) {
      $checkboxDropdownWrapper.removeClass('open');
      $dropdownToggle.attr('aria-expanded', 'false');
    }
  });

  $dropdownCheckboxes.on('change', function () {
    syncMainCheckboxesFromDropdownAndTrigger();
  });

  container.on('change', '#propertyBoundariesVisibleCheckbox, #unAddressedRoadsVisibleCheckbox, #underConstructionVisibleCheckbox, #roadsVisibleCheckbox, #regionalBordersVisibleCheckbox', function () {
    if (window.innerWidth <= BREAKPOINT_PX) {
      syncDropdownCheckboxesFromMain();
    }
  });

  function updateUIForScreenSize() {
    if (window.innerWidth <= BREAKPOINT_PX) {
      container.find('.layer-option-visible-wrapper').hide();
      $checkboxDropdownWrapper.show();
      syncDropdownCheckboxesFromMain();
      $checkboxDropdownWrapper.removeClass('open');
      $dropdownToggle.attr('aria-expanded', 'false');
    } else {
      container.find('.layer-option-visible-wrapper').show();
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
}

function renderCoordinatesDisplay(container) {
  const element = `
    <div class="mapplugin coordinates" data-position="4">
      <span class="cbCrsLabel hide-on-medium">ETRS89-TM35FIN</span>
      <span class="cbCoordinate" axis="lat" data-label="P:">P: lat</span>
      <span class="cbCoordinate" axis="lon" data-label="I:">I: lon</span>
      <button class="btn-coordinate-marker" id="mark-coordinates">Merkitse</button>
    </div>
  `;

  container.append(element);

  let centerLonLat = { lon: 0, lat: 0 };
  eventbus.on('map:refresh', (event) => {
    centerLonLat = event.center;
    if (centerLonLat) {
      const latElement = container.find('.cbCoordinate[axis="lat"]');
      const lonElement = container.find('.cbCoordinate[axis="lon"]');

      latElement.text(`${latElement.data('label')} ${Math.round(centerLonLat[1])}`);
      lonElement.text(`${lonElement.data('label')} ${Math.round(centerLonLat[0])}`);
    }
  });

  $('#mark-coordinates').on('click', () => {
    eventbus.trigger('coordinates:marked', centerLonLat);
  });
}

// Contains double click functionality to support test automation
function createCrosshairToggle(parentElement, map, onFeatureClick = null) {
  const crosshairSelector = '.crosshair';

  const performClick = (callback, isDoubleClick = false) => {
    const coords = getCrosshairCenter();
    if (!coords) return;

    const clickData = getFeatureDataAtAtPixel(coords.x, coords.y);
    dispatchMapEvents(coords.x, coords.y, isDoubleClick);

    console.log('Crosshair Click Data:', clickData);
    const finalCallback = callback || onFeatureClick;
    if (typeof finalCallback === 'function') finalCallback(clickData);

    window.dispatchEvent(new CustomEvent('crosshairFeatureClick', { detail: clickData }));
  };

  const $crosshairElement = createUI();

  $(document).on('keydown', onKeyDown);
  parentElement.append($crosshairElement);

  return {
    click: () => performClick(),
    doubleClick: () => performClick(null, true),
    getData: () => {
      const coords = getCrosshairCenter();
      return coords ? getFeatureDataAtAtPixel(coords.x, coords.y) : null;
    },
    destroy: () => {
      $(document).off('keydown', onKeyDown);
      $crosshairElement.remove();
    }
  };

  function getFeatureDataAtAtPixel(x, y) {
    const viewport = map.getViewport();
    const viewRect = viewport.getBoundingClientRect();
    const pixel = [x - viewRect.left, y - viewRect.top];
    const features = [];

    map.forEachFeatureAtPixel(pixel, (feature, layer) => {
      features.push({
        feature: feature,
        layer: layer,
        properties: feature.getProperties(),
        linkData: feature.linkData || null
      });
    });

    return { features, pixel, coordinate: map.getCoordinateFromPixel(pixel), hasFeatures: features.length > 0 };
  }

  function dispatchMapEvents(x, y, isDoubleClick = false) {
    const viewport = map.getViewport();
    const target = viewport.querySelector('canvas') || viewport;
    const eventInit = { clientX: x, clientY: y, bubbles: true, detail: 1 };

    if (isDoubleClick) {
      target.dispatchEvent(new PointerEvent('pointerdown', { ...eventInit, buttons: 1 }));
      target.dispatchEvent(new PointerEvent('pointerup', { ...eventInit, buttons: 0 }));
      setTimeout(() => {
        target.dispatchEvent(new PointerEvent('pointerdown', { ...eventInit, buttons: 1 }));
        target.dispatchEvent(new PointerEvent('pointerup', { ...eventInit, buttons: 0 }));
        target.dispatchEvent(new PointerEvent('dblclick', { ...eventInit, detail: 2 }));
      }, 50);
    } else {
      target.dispatchEvent(new PointerEvent('pointerdown', { ...eventInit, buttons: 1 }));
      target.dispatchEvent(new PointerEvent('pointerup', { ...eventInit, buttons: 0 }));
    }
  }

  function getCrosshairCenter() {
    const el = document.querySelector(crosshairSelector);
    const rect = el.getBoundingClientRect();
    return {
      x: rect.left + (rect.width / 2),
      y: rect.top + (rect.height / 2)
    };
  }

  function createUI() {
    const $element = $(`
      <div class="crosshair-wrapper">
        ${checkbox({
          name: 'crosshair',
          label: 'Kohdistin',
          checked: true
        })}
      </div>
    `);
    $element.find('input').on('change', (e) => $(crosshairSelector).toggle(e.target.checked));
    return $element;
  }

  function onKeyDown(e) {
    if (e.shiftKey && e.key === 'ArrowRight') {
      e.preventDefault();
      performClick();
    } else if (e.shiftKey && e.key === 'ArrowLeft') {
      e.preventDefault();
      performClick(null, true);
    }
  }
}
