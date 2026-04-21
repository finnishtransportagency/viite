// Orchestrates search panel rendering and updates legend HTML by selected layer.
import { eventbus } from '@utils/eventbus.js';
import { getLegendDisplayHtml } from './LegendDisplay.js';
import { SearchBox } from './SearchBox.js';
import { getSelectedLayer } from '@model/ApplicationModel.js';

export function SearchPanel() {
  const searchPanel = $('<div class="search-panel"></div>');
  const searchBox = new SearchBox();

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

  return {
    element: searchPanel
  };
}

export const createSearchPanel = SearchPanel;