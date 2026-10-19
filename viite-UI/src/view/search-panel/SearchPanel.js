import { eventbus } from '@utils/eventbus.js';
import { getLegendDisplayHtml } from './LegendDisplay.js';
import { SearchBox } from './SearchBox.js';
import { getSelectedLayer } from '@model/ApplicationModel.js';

const LEGEND_EXPANDED_STORAGE_KEY = 'viite.legend.expanded';

// Orchestrates search panel rendering and updates legend HTML based on selected layer
export function SearchPanel(map) {
	const container = jQuery('#map-tools');
	const searchBox = new SearchBox(map);

	const legendGroup = $(`
    <div class="panel-group road-links">
      <div class="panel road-link">
				<button type="button" class="panel-header legend-toggle expanded" aria-expanded="true" aria-controls="legendDiv">
					<span>Selite</span>
					<i class="fas fa-chevron-down legend-toggle-icon" aria-hidden="true"></i>
				</button>
        <div class="legend-container">
          <div id="legendDiv" class="panel-section panel-legend linear-asset-legend road-class-legend"></div>
				</div>
      </div>
    </div>`);

	const legendToggle = legendGroup.find('.legend-toggle');
	const legendContainer = legendGroup.find('.legend-container');
	const legendContent = legendGroup.find('#legendDiv');

	container.append(searchBox.element);
	container.append(legendGroup);

	function setLegendExpanded(isExpanded) {
		legendToggle.attr('aria-expanded', String(isExpanded));
		legendToggle.toggleClass('expanded', isExpanded);
		legendContainer.toggleClass('collapsed', !isExpanded);
		legendContent.attr('aria-hidden', String(!isExpanded));
	}

	function getLegendPreference() {
		try {
			return window.localStorage.getItem(LEGEND_EXPANDED_STORAGE_KEY) !== 'false';
		} catch {
			return true;
		}
	}

	function saveLegendPreference(isExpanded) {
			window.localStorage.setItem(LEGEND_EXPANDED_STORAGE_KEY, String(isExpanded));
	}

	let isExpanded = getLegendPreference();
	setLegendExpanded(isExpanded);

	legendToggle.on('click', function () {
		isExpanded = legendToggle.attr('aria-expanded') !== 'true';
		setLegendExpanded(isExpanded);
		saveLegendPreference(isExpanded);
	});

	function updateLegendContent(layerName) {
		const layer = layerName || getSelectedLayer();
		legendContent
			.empty()
			.append(getLegendDisplayHtml(layer));
	}

	eventbus.on('layer:selected', function onLayerSelected(layerName) {
		updateLegendContent(layerName);
	});

	// Initial render
	updateLegendContent(getSelectedLayer());
}