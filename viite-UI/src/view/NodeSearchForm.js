(function (root) {
  root.NodeSearchForm = function (map, nodeCollection) {
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
      return '<input type="text" class="form-control node-input" id = "' + id + '"' + lengthLimit + ' value="' + value + '"/>';
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

    var addNodeAttributeLabel = function (label) {
      return '<label class="control-label-small" style="text-transform:none;color:#f4b183">'+label+'</label>';
    };

    var roadAddressLink = function (index, nodeWithAttributes) {
      return '<a id="' + index + '" class="node-link" href="#node/' + nodeWithAttributes.id + '" style="font-weight:bold;cursor:pointer;">' +
        nodeWithAttributes.roadNumber + '/' +
        nodeWithAttributes.track + '/' +
        nodeWithAttributes.roadPartNumber + '/' +
        nodeWithAttributes.startAddrMValue + '</a>';
    };

    var nodesAndRoadAttributesHtmlList = function () {
      var text = '<label class="control-label-small" style="text-transform:none;color:white;font-weight:bold">TIE / AJR / OSA / ET</label></br>';
      var index = 0;
      var nodes = nodeCollection.getNodesWithAttributes();
      _.each(nodes, function (nodeWithAttributes) {
        text += roadAddressLink(index++, nodeWithAttributes) + '</br>' +
          addNodeAttributeLabel('Solmutyyppi: ') + addNodeAttributeLabel(nodeWithAttributes.type) + '</br>' +
          addNodeAttributeLabel('Solmun nimi: ') + addNodeAttributeLabel(nodeWithAttributes.name) + '</br></br>';
      });
      return text;
    };

    var checkInputs = function (selector, disabled) {
      var rootElement = $('#feature-attributes');
      var startRoadPart = $("#aosa").val();
      var endRoadPart = $("#losa").val();
      if (disabled || !(!startRoadPart && !endRoadPart || startRoadPart && endRoadPart)) {
        rootElement.find(selector).prop('disabled', true);
      } else {
        rootElement.find(selector).prop('disabled', false);
      }
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      eventbus.on('nodeSearchTool:fetched', function() {
        $('#nodes-and-junctions-content').html(nodesAndRoadAttributesHtmlList());
        applicationModel.removeSpinner();
        eventbus.trigger('nodeSearchTool:refreshView', map);
      });

      eventbus.on('nodeSearchTool:failed', function(errorMessage) {
        applicationModel.removeSpinner();
        new ModalConfirm(errorMessage);
      });

      eventbus.on('nodesAndJunctions:open', function () {
        rootElement.html(searchNodesTemplate());
        applicationModel.selectLayer('node');
        $('#close-node-search').click(function () {
          applicationModel.selectLayer('linkProperty', true);
          eventbus.trigger('nodesAndJunctions:close');
          return false;
        });

        rootElement.on('keyup, input', '.node-input', function () {
          checkInputs('#node-search-btn', !$("#tie").val());
        });

        // rootElement.on('click', '#node-search-btn', function () {
        //   applicationModel.addSpinner();
        //   var data = {
        //     roadNumber: $("#tie").val()
        //   };
        //   var minPart = $("#aosa").val();
        //   var maxPart = $("#losa").val();
        //   if (minPart) { data.minRoadPartNumber = minPart; }
        //   if (maxPart) { data.maxRoadPartNumber = maxPart; }
        //   nodeCollection.getNodesByRoadAttributes(data);
        // });

        rootElement.on('click', '.node-link', function (event) {
          eventbus.trigger('nodeSearchTool:clickNode', event.currentTarget.id, map);
        });

      });
    };
    bindEvents();
  };
})(this);
