// Orchestrates search panel rendering and updates legend HTML by selected layer.
import { eventbus } from '@utils/eventbus.js';
import { LocationSearch } from '@model/LocationSearch.js';
import { getLegendDisplayHtml } from './LegendDisplay.js';
import { SearchBox } from './SearchBox.js';

export function SearchPanel(options = {}) {
  const { container, backend, applicationModel } = options;

  const searchPanel = $('<div class="search-panel"></div>');
  const searchBox = options.searchBox || new SearchBox(
    new LocationSearch(backend, applicationModel)
  );

  const legendGroup = $(`
    <div class="panel-group road-links">
      <div class="panel road-link">
        <header class="panel-header expanded">Selite</header>
        <div class="legend-container no-copy">
          <div id="legendDiv" class="panel-section panel-legend linear-asset-legend road-class-legend no-copy"></div>
        </div>
      </div>
    </div>`);

  const legendContent = legendGroup.find('#legendDiv');

  searchPanel.append(searchBox.element);
  searchPanel.append(legendGroup);

  function updateLegendContent(layerName) {
    const layer = layerName || applicationModel.getSelectedLayer();
    legendContent
      .empty()
      .append(getLegendDisplayHtml(layer));
  }

  eventbus.on('layer:selected', function onLayerSelected(layerName) {
    updateLegendContent(layerName);
  });

  // Initial render
  updateLegendContent(applicationModel.getSelectedLayer());
  container.append(searchPanel);

  return {
    element: searchPanel
  };
}