import { checkbox } from '@components/checkbox/Checkbox.js';
import { button } from '@components/button/Button.js';
import { toggleRoadVisibility } from '@model/ApplicationModel.js';

/* Contains following elements:
- TileMapSelector: A set of buttons used for selecting which map background to show
- CoordinatesDisplay: Displays the coordinates of current location and contains a button for marking those coordinates on the map
- CrosshairToggle: A checkbox for toggling a crosshair in the center of the map and clicking on the map through it to support test automation
*/

export function Footer(map, container, linkPropertyLayer, projectLinkLayer, tileMapCollection) {
	const element = '<div class="map-footer"></div>';
	container.append(element);

	const footerContainer = container.find('.map-footer');
	renderTileMapSelector(footerContainer, linkPropertyLayer, projectLinkLayer, tileMapCollection);
	renderCoordinatesDisplay(footerContainer, map);
	createCrosshairToggle(footerContainer.find('.mapplugin.coordinates'), map);
}

function renderTileMapSelector(container, linkPropertyLayer, projectLinkLayer, tileMapCollection) {
	const BREAKPOINT_PX = 1470;

	const layerOptions = [
		{
			id: 'propertyBoundariesVisible',
			label: 'Näytä kiinteistörajat',
			checked: false,
			onChange(checked) {
				tileMapCollection.setVisible('propertyBorder', checked);
			}
		},
		{
			id: 'unAddressedRoadsVisible',
			label: 'Näytä tieosoitteettomat-linkit',
			checked: true,
			onChange(checked) {
				linkPropertyLayer.setVisible('unAddressedRoadLayer', checked);
				projectLinkLayer.setVisible('unAddressedRoadLayer', checked);
			}
		},
		{
			id: 'underConstructionVisible',
			label: 'Näytä rakenteilla-linkit',
			checked: true,
			onChange(checked) {
				linkPropertyLayer.setVisible('underConstructionRoadLayer', checked);
				projectLinkLayer.setVisible('underConstructionRoadLayer', checked);
			}
		},
		{
			id: 'roadsVisible',
			label: 'Näytä tieosoiteverkko',
			checked: true,
			onChange() {
				toggleRoadVisibility();
				linkPropertyLayer.updateRoadVisibility();
				projectLinkLayer.updateRoadVisibility();
			}
		},
		{
			id: 'regionalBordersVisible',
			label: 'Näytä maakuntarajat',
			checked: false,
			onChange(checked) {
				tileMapCollection.setVisible('regionsBorder', checked);
			}
		}
	];

	const renderLayerOptionCheckbox = (option) => `
    <div class="layer-option-visible-wrapper">
      ${checkbox({
		id: `${option.id}Checkbox`,
		label: option.label,
		checked: option.checked
	})}
    </div>
  `;

	const renderDropdownCheckbox = (option) =>
		checkbox({
			id: `dropdown-${option.id}`,
			label: option.label,
			value: option.id
		});

	const element = `
    <div class="tile-map-selector">
      <ul>
        <li data-layerid="terrain" title="Maastokartta">Maastokartta</li>
        <li data-layerid="aerial" title="Ortokuvat">Ortokuvat</li>
        <li data-layerid="background" title="Taustakarttasarja" class="selected">Taustakarttasarja</li>
        <li data-layerid="none" title="Piilota kartta">Piilota kartta</li>
      </ul>

      ${layerOptions.map(renderLayerOptionCheckbox).join('')}

      <div class="checkbox-dropdown-wrapper">
        <button class="dropdown-toggle" aria-expanded="false">
          Karttavaihtoehdot
        </button>

        <div class="checkbox-dropdown">
          ${layerOptions.map(renderDropdownCheckbox).join('')}
        </div>
      </div>
    </div>
  `;

	container.append(element);

	const $checkboxDropdownWrapper = container.find('.checkbox-dropdown-wrapper');
	const $dropdownToggle = $checkboxDropdownWrapper.find('.dropdown-toggle');
	const $dropdownCheckboxes = $checkboxDropdownWrapper.find('input[type="checkbox"]');

	const getMainCheckboxId = (value) => `${value}Checkbox`;

	function syncDropdownCheckboxesFromMain() {
		$dropdownCheckboxes.each(function () {
			const value = $(this).val();

			const checked = Boolean(
				container.find(`#${getMainCheckboxId(value)}`).prop('checked')
			);

			$(this).prop('checked', checked);
		});
	}

	function syncMainCheckboxesFromDropdownAndTrigger() {
		$dropdownCheckboxes.each(function () {
			const value = $(this).val();

			const $mainCheckbox = container.find(
				`#${getMainCheckboxId(value)}`
			);

			const newChecked = Boolean($(this).prop('checked'));
			const oldChecked = Boolean($mainCheckbox.prop('checked'));

			if (newChecked !== oldChecked) {
				$mainCheckbox.prop('checked', newChecked);
				$mainCheckbox.trigger('change');
			}
		});
	}

	// Register all checkbox handlers from configuration
	layerOptions.forEach(option => {
		container.on(
			'change',
			`#${option.id}Checkbox`,
			function () {
				option.onChange(this.checked);
			}
		);
	});

	// Sync dropdown when a main checkbox changes
	container.on(
		'change',
		layerOptions
			.map(option => `#${option.id}Checkbox`)
			.join(', '),
		function () {
			if (window.innerWidth <= BREAKPOINT_PX) {
				syncDropdownCheckboxesFromMain();
			}
		}
	);

	container.find('li[data-layerid]').on('click', event => {
		container.find('li.selected').removeClass('selected');

		const selectedTileMap = $(event.target);

		selectedTileMap.addClass('selected');
		tileMapCollection.selectMap(selectedTileMap.data('layerid'));
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

function renderCoordinatesDisplay(container, map) {
	const element = `
    <div class="mapplugin coordinates" data-position="4">
      <span class="cbCrsLabel hide-on-medium">ETRS89-TM35FIN</span>
      <span class="cbCoordinate" axis="lat" data-label="P:">P: lat</span>
      <span class="cbCoordinate" axis="lon" data-label="I:">I: lon</span>
      ${button({ id: 'mark-coordinates', label: 'Merkitse', className: 'btn-coordinate-marker', onClick: () => map.dispatchEvent({ type: 'coordinates:marked', position: centerLonLat }) })}
    </div>
  `;

	container.append(element);

	let centerLonLat = map.getView().getCenter() || [0, 0];

	const updateCoordinates = function () {
		centerLonLat = map.getView().getCenter() || [0, 0];
		const latElement = container.find('.cbCoordinate[axis="lat"]');
		const lonElement = container.find('.cbCoordinate[axis="lon"]');

		latElement.text(`${latElement.data('label')} ${Math.round(centerLonLat[1])}`);
		lonElement.text(`${lonElement.data('label')} ${Math.round(centerLonLat[0])}`);
	};

	map.on('moveend', updateCoordinates);
	updateCoordinates();
}

// Contains double click functionality to support test automation
function createCrosshairToggle(parentElement, map, onFeatureClick = null) {
	const crosshairSelector = '.crosshair';

	const performClick = (callback, isDoubleClick = false) => {
		const coords = getCrosshairCenter();
		if (!coords) return;

		const clickData = getFeatureDataAtAtPixel(coords.x, coords.y);
		dispatchMapEvents(coords.x, coords.y, isDoubleClick);

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
