/**
 * Builds the left-side navigation panel with the search box and layer controls.
 * Updates visible controls and button enabled state based on layer selection events.
 */
import { eventbus } from '@utils/eventbus.js';

let navigationPanel = $('<div class="navigation-panel"></div>');

function initialize(container, searchBox, assetControlGroups) {

    navigationPanel = $('<div class="navigation-panel"></div>');
    navigationPanel.append(searchBox.element);

    const assetControls = _.flatten(assetControlGroups);

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


    eventbus.on('layer:enableButtons', enableButtons);

  function enableButtons(value) {
    navigationPanel.find(':button').not('#executeSearch, #clearSearch').prop('disabled', !value);
  }
}

export const NavigationPanel = {
  initialize: initialize
};
