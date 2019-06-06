(function (root) {
  root.NodeSearchForm = function (instructionsPopup, map, nodeCollection) {
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

    var inputNumber = function (id, maxLength) {
      return '<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode === 8 || event.keyCode === 9)' +
        '" class="form-control node-input" id = "' + id + '"' +
        (_.isUndefined(maxLength) ? '' : ' maxlength="' + maxLength + '"') + '/>';
    };

    var searchButton = function () {
      return '<button id="node-search-btn" type="button" class="btn node-search-btn" disabled>Hae solmut</button>';
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
        inputNumber('tie', 5) + inputNumber('aosa', 3) + inputNumber('losa', 3) +
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
      var text = '<label class="control-label-small" style="text-transform:none;color:white;">TIE / AJR / OSA / ET</label></br>';
      var index = 0;
      var nodes = nodeCollection.getNodesWithAttributes();
      _.each(nodes, function (nodeWithAttributes) {
        text += roadAddressLink(index++, nodeWithAttributes) + '</br>' +
          addNodeAttributeLabel('Solmutyyppi:&nbsp;') + addNodeAttributeLabel(nodeWithAttributes.type) + '</br>' +
          addNodeAttributeLabel('Solmun nimi:&nbsp;') + addNodeAttributeLabel(nodeWithAttributes.name) + '</br></br>';
      });
      return text;
    };

    var checkInputs = function (selector, disabled) {
      var rootElement = $('#feature-attributes');
      rootElement.find(selector).prop('disabled', disabled);
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      eventbus.on('nodeSearchTool:fetched', function(hasResults) {
        applicationModel.removeSpinner();
        if (hasResults) {
          $('#nodes-and-junctions-content').html(nodesAndRoadAttributesHtmlList());
          eventbus.trigger('nodeSearchTool:refreshView', map);
        } else {
          instructionsPopup.show('Ei tuloksia', 3000);
        }
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
          checkInputs('#node-search-btn',
            !$("#tie").val() || ((parseInt($("#aosa").val()) || 0) > (parseInt($("#losa").val()) || 999)));
        });

        rootElement.on('click', '#node-search-btn', function () {
          applicationModel.addSpinner();
          $('#nodes-and-junctions-content').html("");
          var data = {
            roadNumber: $("#tie").val()
          };
          var minPart = $("#aosa").val();
          var maxPart = $("#losa").val();
          if (minPart) { data.minRoadPartNumber = minPart; }
          if (maxPart) { data.maxRoadPartNumber = maxPart; }
          nodeCollection.getNodesByRoadAttributes(data);
        });

        rootElement.on('click', '.node-link', function (event) {
          eventbus.trigger('nodeSearchTool:clickNode', event.currentTarget.id, map);
        });

      });
    };
    bindEvents();
  };
})(this);
