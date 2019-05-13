(function (root) {
  root.NodeSearchForm = function (nodeCollection) {
    var formCommon = new FormCommon('');
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
        '<div id= "road-attributes" class="form-group">' +
        inputField('tie', '', 5) + inputField('aosa', '', 3) + inputField('losa', '', 3) +
        searchButton() +
        '</div>' +
        '</form>' +
        '</div>' +
        '</div>' +
        '</div>' +
        '<div id="nodes-and-junctions-content">' +
        '</div>' +
        '</div>'
      );
    };

    var nodesAndRoadAttributesHtmlList = function () {
      var text = '';
      var index = 0;
      var nodes = nodeCollection.getNodesWithAttributes();
      _.each(nodes, function (nodeWithAttributes) {
        text += ' ' + '</br>' +
          formCommon.addSmallLabelLowercase('Solmutyyppi: ') + ' ' + formCommon.addSmallLabelLowercase(nodeWithAttributes.type) + '</br>' +
          formCommon.addSmallLabelLowercase('Solmun nimi: ') + ' ' + formCommon.addSmallLabelLowercase(nodeWithAttributes.name) + '</br>';
      });
      return text;
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      eventbus.on('nodesAndRoadAttributes:fetched', function() {
        $('#nodes-and-junctions-content').html(nodesAndRoadAttributesHtmlList());
        applicationModel.removeSpinner();
      });

      eventbus.on('nodesAndJunctions:open', function () {
        rootElement.html(searchNodesTemplate());
        applicationModel.selectLayer('node', true);
        $('#close-node-search').click(function () {
          applicationModel.selectLayer('linkProperty', true);
          eventbus.trigger('nodesAndJunctions:close');
          return false;
        });

        var searchButton = $('#node-search-btn');
        rootElement.on('change', '.small-input', searchButton, function () {
          var startRoadPart = $("#aosa").val();
          var endRoadPart = $("#losa").val();
          searchButton[0].disabled = !($("#tie").val() &&
            (!startRoadPart && !endRoadPart) || (startRoadPart && endRoadPart));
        });

        rootElement.on('click', '.node-search-btn', function () {
          applicationModel.addSpinner();
          nodeCollection.getNodesByRoadAttributes({
            roadNumber: $("#tie").val(),
            minRoadPartNumber: $("#aosa").val(),
            maxRoadPartNumber: $("#losa").val()
          });
        });

      });
    };
    bindEvents();
  };
})(this);
