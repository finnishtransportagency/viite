(function (root) {
  root.NodeSearchForm = function (nodeCollection) {
    var container = $('#legendDiv');
    var roadClassLegend = $('<div id="legendDiv" class="panel-section panel-legend linear-asset-legend road-class-legend no-copy"></div>');
    var header = function() {
      return '<header>' +
        '<span id="close-node-search" class="rightSideSpan">Sulje <i class="fas fa-window-close"></i></span>' +
        '</header>';
    };

    var label = function(label) {
      return '<label class="control-label-small">' + label + '</label>';
    };

    var inputField = function (id, value, maxLength) {
      var lengthLimit = '';
      if (maxLength) lengthLimit = ' maxlength="' + maxLength + '"';
      return '<input type="text" class="form-control small-input" id = "' + id + '"' + lengthLimit + ' value="' + value + '"/>';
    };

    var searchButton = function () {
      return '<button id="node-search-btn" class="btn node-search-btn" disabled>Hae solmut</button>';
    };

    var searchNodesTemplate = function () {
      return _.template('' +
        header() +
        '<div class="wrapper read-only">' +
        '<div class="form form-horizontal form-dark" style="margin-left: auto;">' +
        '<div class="edit-control-group">' +
        '<div class="form-group editable">' +
        '<form id="node-search" class="input-unit-combination form-group form-horizontal node-search">' +
        '<div class="form-group">' +
        label('Tie') + label('Aosa') + label('Losa') +
        '</div>' +
        '<div id= "roadAttributes" class="form-group">' +
        inputField('tie', '', 5) + inputField('aosa', '', 3) + inputField('losa', '', 3) +
        searchButton() +
        '</div>' +
        '</form>' +
        '</div>' +
        '</div>' +
        '</div>' +
        '<div id="form-result">' +
        '</div>' +
        '</div>'
      );
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      eventbus.on('nodesAndJunctions:fetched', function(result) {
        alert('fetched is beeing called!');
      });

      eventbus.on('nodesAndJunctions:open', function () {
        rootElement.html(searchNodesTemplate());

        $('#close-node-search').click(function () {
          applicationModel.selectLayer('linkProperty', true);
          eventbus.trigger('nodesAndJunctions:close');
          return false;
        });

        var searchButton = $('#node-search-btn');
        rootElement.on('change', '.small-input', searchButton, function () {
          var startRoadPart = $("#aosa").val();
          var endRoadPart = $("#losa").val();
          var roadPartChecker = (!startRoadPart && !endRoadPart) || (startRoadPart && endRoadPart);
          searchButton[0].disabled = !($("#tie").val() && roadPartChecker);
        });

        searchButton.click(function() {
          nodeCollection.getNodesByRoadAttributes($("#tie").val(), $("#aosa").val(), $("#losa").val());
        });
      });
    };
    bindEvents();
  };
})(this);
