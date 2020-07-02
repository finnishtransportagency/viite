(function(root) {
  root.NavigationPanel = {
    initialize: initialize
  };
  var navigationPanel = $('<div class="navigation-panel"></div>');

  function initialize(container, searchBox, assetControlGroups) {

    navigationPanel = $('<div class="navigation-panel"></div>');
    navigationPanel.append(searchBox.element);

    var assetControls = _.flatten(assetControlGroups);

    var assetElementDiv = $('<div></div>');
    assetControls.forEach(function(asset) {
      assetElementDiv.append(asset.element);
    });
    navigationPanel.append(assetElementDiv);

    var assetControlMap = _.chain(assetControls)
      .map(function(asset) {
        return [asset.layerName, asset];
      })
      .fromPairs()
      .value();


    eventbus.on('layer:selected', function selectLayer(layer, previouslySelectedLayer) {
        var previousControl = assetControlMap[previouslySelectedLayer];
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
})(this);
