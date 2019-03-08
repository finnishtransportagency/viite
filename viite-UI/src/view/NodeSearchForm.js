(function (root) {
  root.NodeSearchForm = function () {
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
        '<div class="form form-horizontal form-dark">' +
        '<div class="edit-control-group">' +
        '<div class="form-group editable">' +
        '<form id="node-search" class="input-unit-combination form-group form-horizontal node-search">' +
        '<div class="form-group">' +
        label('Solmu') + label('Tie') + label('Aosa') + label('Losa') +
        '</div>' +
        '<div class="form-group">' +
        inputField('solmu', '') + inputField('tie', '') + inputField('aosa', '') + inputField('losa', '') +
        searchButton() +
        '</div>' +
        '</form>' +
        '</div>' +
        '</div>' +
        '</div>' +
        '</div>'
      );
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      eventbus.on('nodesAndJunctions:open', function () {
        rootElement.html(searchNodesTemplate());

        $('#close-node-search').click(function () {
          applicationModel.selectLayer('linkProperty', true);
          eventbus.trigger('nodesAndJunctions:close');
          return false;
        });

        var searchButton = $('#node-search-btn');
        searchButton[0].disabled = false; // TODO when at least one search field is filled, then enable
        searchButton.click(function() {
          alert("Hakua ei ole vielä toteutettu."); // TODO
          return false;
        });

      });

    };

    bindEvents();
  };
})(this);
