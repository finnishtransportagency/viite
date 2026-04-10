/**
 * Builds the left-side navigation panel with the search box and layer controls.
 * Updates visible controls and button enabled state based on layer selection events.
 */
import { eventbus } from '@utils/eventbus.js';
import { LocationSearch } from '@model/LocationSearch.js';
import { MapLegendDisplay } from './MapLegendDisplay.js';
import { SearchBox } from './SearchBox.js';

export function NavigationPanel(options = {}) {
  const container = options.container;
  const backend = options.backend;
  const applicationModel = options.applicationModel;

  const navigationPanel = $('<div class="navigation-panel"></div>');
  const searchBox = options.searchBox || new SearchBox(
    new LocationSearch(backend, applicationModel)
  );
  const assetControls = options.assetControls || [new MapLegendDisplay(applicationModel)];

  navigationPanel.append(searchBox.element);

  const assetElementDiv = $('<div></div>');
  assetControls.forEach(function (asset) {
    assetElementDiv.append(asset.element);
  });
  navigationPanel.append(assetElementDiv);

  const assetControlMap = _.chain(assetControls).map(function (asset) {
    return [asset.layerName, asset];
  }).fromPairs().value();

  eventbus.on('layer:selected', function selectLayer(layer, previouslySelectedLayer) {
    const previousControl = assetControlMap[previouslySelectedLayer];
    if (previousControl) previousControl.hide();
    assetControlMap.linkProperty.show();
    assetElementDiv.show();
  });

  container.append(navigationPanel);

  eventbus.on('layer:enableButtons', function enableButtons(value) {
    navigationPanel.find(':button').not('#executeSearch, #clearSearch').prop('disabled', !value);
  });

  return {
    element: navigationPanel
  };
}
