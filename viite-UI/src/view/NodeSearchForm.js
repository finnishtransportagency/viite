(function (root) {
  root.NodeSearchForm = function (instructionsPopup, map, nodeCollection, backend) {
    var formCommon = new FormCommon('node-search-');
    var header = function () {
      return '<header>' +
        '<span id="close-node-search" class="rightSideSpan">Sulje <i class="fas fa-window-close"></i></span>' +
        '</header>';
    };

    var label = function (labelFormatted) {
      return '<label class="control-label-small">' + labelFormatted + '</label>';
    };

    var searchButton = function () {
      return '<button id="node-search-btn" type="button" class="btn node-search-btn" disabled>Hae solmut</button>';
    };

    var searchNodesTemplate = function () {
      return _.template(String(header()) +
        '<div class="wrapper read-only">' +
        '<div class="form form-horizontal form-dark" style="margin-left: auto;">' +
        '<div class="edit-control-group">' +
        '<div class="form-group editable">' +
        '<form id="node-search" class="input-unit-combination form-group form-horizontal node-search">' +
        '<div class="form-group">' +
        label('Tie') + label('Aosa') + label('Losa') +
        '</div>' +
        '<div id= "road-attributes" class="form-group">' +
        formCommon.nodeInputNumber('tie', 5) + formCommon.nodeInputNumber('aosa', 3) + formCommon.nodeInputNumber('losa', 3) +
        searchButton() +
        '</div>' +
        '<button id="clear-node-search" type="button" class="btn btn-clean-node-search btn-block" disabled>Tyhjenn&auml; tulokset</button>' +
        '</form>' +
        '</div>' +
        '</div>' +
        '</div>' +
        '<div id="nodes-and-junctions-content">' +
        '</div>' +
        '</div>'
      );
    };

    var addNodeAttributeLabel = function (labelFormatted) {
      return '<label class="control-label-small" style="text-transform:none;color:#f4b183">' + labelFormatted + '</label>';
    };

    var roadAddressLink = function (index, nodeWithAttributes) {
      return '<a id="' + index + '" class="node-link" href="#node/' + nodeWithAttributes.id + '" style="font-weight:bold;cursor:pointer;">' +
        nodeWithAttributes.roadNumber + '/' +
        nodeWithAttributes.roadPartNumber + '/' +
        nodeWithAttributes.addrMValue + '</a>';
    };

    var nodePointTemplateLink = function (nodePointTemplate) {
      return '<a id=' + nodePointTemplate.id + ' class="node-point-template-link" href="#node/nodePointTemplate/' + nodePointTemplate.id + '" style="font-weight:bold;cursor:pointer;color: darkorange;">' +
        nodePointTemplate.roadNumber + ' / ' +
        nodePointTemplate.roadPartNumber + ' / ' +
        nodePointTemplate.addrM + '</a>';
    };

    var junctionTemplateLink = function (junctionTemplate) {
      return '<a id=' + junctionTemplate.id + ' class="junction-template-link" href="#node/junctionTemplate/' + junctionTemplate.id + '" style="font-weight:bold;cursor:pointer;">' +
        junctionTemplate.roadNumber + ' / ' +
        junctionTemplate.track + ' / ' +
        junctionTemplate.roadPartNumber + ' / ' +
        junctionTemplate.addrM + '</a>';

    };

    var nodesAndRoadAttributesHtmlList = function () {
      var text = '<label class="control-label-small" style="text-transform:none;color:white;">TIE / OSA / ET</label></br>';
      var index = 0;
      var nodes = nodeCollection.getNodesWithAttributes();
      _.each(nodes, function (nodeWithAttributes) {
        text += roadAddressLink(index++, nodeWithAttributes) + '&nbsp;&nbsp;' + addNodeAttributeLabel(nodeWithAttributes.name) + '</br>' +
          addNodeAttributeLabel('Solmutyyppi:&nbsp;') + addNodeAttributeLabel(nodeWithAttributes.type) + '</br>' +
          addNodeAttributeLabel('Solmunumero:&nbsp;') + addNodeAttributeLabel(nodeWithAttributes.nodeNumber) + '</br></br>';
      });
      return text;
    };

    var junctionTemplatesHtml = function (junctionTemplates) {
      var groups = _.groupBy(junctionTemplates, function (template) {
        return template.elyCode;
      });
      var text = "";
      if (!_.isEmpty(groups)) {
        text = '<label class="control-label-small" style="color:#c09853;">Käsittelemättömät liittymäaihiot</label>';
        _.each(groups, function (templatesByEly) {
          var sortedTemplates = _.chain(templatesByEly).sortBy('addrM').sortBy('track').sortBy('roadPartNumber').sortBy('roadNumber').value();
          text += elyNameLabel(sortedTemplates[0].elyCode);
          text += '<label class="control-label-small" style="text-transform:none;color:white;">(TIE / AJR / OSA / AET)</label></br>';
          _.each(sortedTemplates, function (junctionTemplate) {
            text += junctionTemplateLink(junctionTemplate) + '</br>';
          });
        });
      }
      return text;
    };

    var nodePointTemplatesHtml = function (nodePointTemplates) {
      var uniqueNPTemplates = _.uniqWith(nodePointTemplates, function (o1, o2) {
        return o1.roadNumber === o2.roadNumber && o1.roadPartNumber === o2.roadPartNumber && o1.addrM === o2.addrM;
      });
      var groups = _.groupBy(uniqueNPTemplates, function (template) {
        return template.elyCode;
      });
      var text = "";
      if (!_.isEmpty(groups)) {
        text = '</br></br><label class="control-label-small" style="color:#c09853;">Käsittelemättömät solmukohta-aihiot</label>';
        _.each(groups, function (templatesByEly) {
          var sortedTemplates = _.chain(templatesByEly).sortBy('addrM').sortBy('track').sortBy('roadPartNumber').sortBy('roadNumber').value();
          text += elyNameLabel(sortedTemplates[0].elyCode);
          text += '<label class="control-label-small" style="text-transform:none;color:white;">(TIE / OSA / AET)</label></br>';
          _.each(sortedTemplates, function (nodePointTemplate) {
            text += nodePointTemplateLink(nodePointTemplate) + '</br>';
          });
        });
      }
      return text;
    };

    var elyNameLabel = function (elyCode) {
      var elyInfo = _.find(ViiteEnumerations.ElyCodes, function (obj) {
        return obj.value === elyCode;
      });
      return '</br><label class="control-label" style="color:#c09853;">' + elyInfo.name + ' ELY (' + elyInfo.value + ')</label></br>';
    };

    var checkInputs = function (selector, disabled) {
      var rootElement = $('#feature-attributes');
      rootElement.find(selector).prop('disabled', disabled);
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      var getTemplates = function () {
        backend.getTemplates(function (data) {
          var nodePointTemplates = data.nodePointTemplates;
          var junctionTemplates = data.junctionTemplates;
          eventbus.trigger('templates:fetched', nodePointTemplates, junctionTemplates);
          $('#nodes-and-junctions-content').html(junctionTemplatesHtml(junctionTemplates) + nodePointTemplatesHtml(nodePointTemplates));
          applicationModel.removeSpinner();
        });
      };

      eventbus.on('nodeSearchTool:fetched', function (hasResults) {
        applicationModel.removeSpinner();
        $('#clear-node-search').prop('disabled', false);
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
        applicationModel.addSpinner();
        getTemplates();

        $('#close-node-search').click(function () {
          applicationModel.selectLayer('linkProperty', true);
          eventbus.trigger('nodesAndJunctions:close');
          return false;
        });

        $('#clear-node-search').click(function () {
          applicationModel.addSpinner();
          $('#nodes-and-junctions-content').html("");
          getTemplates();
          $('#clear-node-search').prop('disabled', true);
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
          if (minPart) {
            data.minRoadPartNumber = minPart;
          }
          if (maxPart) {
            data.maxRoadPartNumber = maxPart;
          }
          nodeCollection.getNodesByRoadAttributes(data);
        });

        rootElement.on('click', '.node-link', function (event) {
          eventbus.trigger('nodeSearchTool:clickNode', event.currentTarget.id, map);
        });

        rootElement.one('click', '.node-point-template-link', function (event) {
          eventbus.trigger('nodeSearchTool:clickNodePointTemplate', event.currentTarget.id);
        });

        rootElement.on('click', '.junction-template-link', function (event) {
          // Using window.location.hash with preventDefault instead of eventbus.trigger
          // to Prevent double event triggering from both click and URL change
          event.preventDefault();
          window.location.hash = `node/junctionTemplate/${event.currentTarget.id}`;
        });
      });
    };
    bindEvents();
  };
}(this));
