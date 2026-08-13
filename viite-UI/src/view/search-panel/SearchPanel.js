import { eventbus } from '@utils/eventbus.js';
import { getLegendDisplayHtml } from './LegendDisplay.js';
import { SearchBox } from './SearchBox.js';
import { getSelectedLayer } from '@model/ApplicationModel.js';

// Orchestrates search panel rendering and updates legend HTML based on selected layer
export function SearchPanel(map) {
	const container = jQuery('#map-tools');
	const searchBox = new SearchBox(map);

	const legendGroup = $(`
    <div class="panel-group road-links">
      <div class="panel road-link">
        <header class="panel-header expanded">Selite</header>
        <div class="legend-container">
          <div id="legendDiv" class="panel-section panel-legend linear-asset-legend road-class-legend"></div>
          </div>
      </div>
    </div>`);

	const legendContent = legendGroup.find('#legendDiv');

	container.append(searchBox.element);
	container.append(legendGroup);

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