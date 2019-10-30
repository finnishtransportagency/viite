(function (root) {
  root.NodeForm = function (selectedNode) {
    var SHOW = 'Näytä';
    var HIDE = 'Piilota';

    var NodeType = LinkValues.NodeType;
    var formCommon = new FormCommon('node-');

    var formButtons = function () {
      return '<div class="form form-controls">' +
        ' <button class="save btn btn-edit-node-save" disabled>Tallenna</button>' +
        ' <button class="cancel btn btn-edit-node-cancel">Peruuta</button>' +
        '</div>';
    };

    var getNodeType = function (nodeValue) {
      var nodeType = _.find(NodeType, function (type) {
        return type.value === nodeValue;
      });
      return _.isUndefined(nodeType) ? NodeType.UnknownNodeType : nodeType;
    };

    var staticField = function (labelText, dataField) {
      var field;
      field = '<div class="form-group-node-static-metadata">' +
        '<p class="form-control-static asset-log-info-metadata">' +
        '<label>' + labelText + '</label>' +
          dataField +
        '</p>' +
        '</div>';
      return field;
    };

    var inputFieldRequired = function (labelText, id, placeholder, value, maxLength) {
      var lengthLimit = '';
      if (maxLength)
        lengthLimit = 'maxlength="' + maxLength + '"';
      return '<div class="form-group-node-input-metadata">' +
        '<p class="form-control-static asset-node-data">' +
        '<label class="required">' + labelText + '</label>' +
        '<input type="text" class="form-control asset-input-node-data" id = "' + id + '"' + lengthLimit + ' placeholder = "' + placeholder + '" value="' + value + '"/>' +
        '</p></div>';
    };

    var addNodeTypeDropdown = function (labelText, id, nodeType) {
      var addNodeTypeOptions = function (selected) {
        var nodeTypes = _.filter(LinkValues.NodeType, function (nodeType) {
          return nodeType !== LinkValues.NodeType.UnknownNodeType;
        });

        return _.map(nodeTypes, function (nodeType) {
          var option = _.isEqual(nodeType, selected) ? 'selected' : '';
          return '<option value="' + nodeType.value + '"' + option + '>' +
            nodeType.value + ' ' + nodeType.description + '</option>';
        });
      };

      var unknownNodeType = "";
      if (nodeType === LinkValues.NodeType.UnknownNodeType) {
        unknownNodeType = '<option value="' + nodeType.value + '" selected disabled hidden>' +
          nodeType.value + ' ' + nodeType.description + '</option>';
      }

      return '<div class="form-group-node-input-metadata"><p class="form-control-static asset-node-data">' +
        ' <label class="dropdown required">' + labelText + '</label>' +
        ' <select type="text" class="form-control asset-input-node-data" id="' + id + '">' +
        unknownNodeType +
        addNodeTypeOptions(nodeType) +
        ' </select></p></div>';
    };

    var nodeForm = function (node) {
      var nodeNumber = node.nodeNumber ? node.nodeNumber : '-';
      var nodeName = node.name ? node.name : '';
      var startDate = node.startDate ? node.startDate : '';
      return _.template('' +
        '<header>' +
        formCommon.captionTitle('Solmun tiedot:') +
        '</header>' +

        '<div class="wrapper read-only">' +
        ' <div class="form form-horizontal form-dark">' +
        '   <div>' +
        staticField('Solmunumero:', nodeNumber) +
        staticField('Koordinaatit (<i>P</i>, <i>I</i>):', node.coordY + ', ' + node.coordX) +
        inputFieldRequired('Solmun nimi', 'nodeName', '', nodeName, 32) +
        addNodeTypeDropdown('Solmutyyppi', 'nodeTypeDropdown', getNodeType(node.type)) +
        inputFieldRequired('Alkupvm', 'nodeStartDate', 'pp.kk.vvvv', startDate) +
        '   </div>' +
        '   <div>' +
        '     <p><a id="node-point-link" class="node-info-link" href="/">Näytä solmukohdat</a></p>' +
        '     <div id="node-points-info-content">' +
        '     </div>' +
        '     <p><a id="junction-link" class="node-info-link" href="/">Näytä liittymat</a></p>' +
        '     <div id="junctions-info-content">' +
        '     </div>' +
        '   </div>' +
        ' </div>' +
        '</div>' +
        '<footer>' +
        formButtons() +
        '</footer>'
      );
    };

    var Junctions = function () {
      var headers = function(){
        return '<tr class="node-junctions-table-header-border-bottom">' +
          '<th class="node-junctions-table-header">' +
          ' <table class="node-junctions-table-dimension">' +
          '   <tr><th class="node-junctions-table-header">Irrota</th></tr>' +
          '   <tr><th class="node-junctions-table-header">liittymä</th></tr>' +
          '   <tr><th class="node-junctions-table-header">solmusta</th></tr>' +
          ' </table>' +
          '</th>' +
          '<th class="node-junctions-table-header">NRO</th>' +
          '<th class="node-junctions-table-header">TIE</th>' +
          '<th class="node-junctions-table-header">AJR</th>' +
          '<th class="node-junctions-table-header">OSA</th>' +
          '<th class="node-junctions-table-header">ET</th>' +
          '<th class="node-junctions-table-header">EJ</th>' +
          '</tr>';
      };

      var detachJunctionBox = function(junction) {
        return '<td><input type="checkbox" name="detach-junction-' + junction.id + '" value="' + junction.id + '" id="detach-junction-' + junction.id + '"' +
          'data-junction-number=" ' + junction.junctionNumber + ' "' +
          '></td>';
      };

      var junctionIcon = function (number) {
        if (_.isUndefined(number) || number === 99) {
          return '<object type="image/svg+xml" data="images/junction.svg">';
        } else {
          return '<object type="image/svg+xml" data="images/junction.svg">' +
            ' <param name="number" value="' + number + '"/></object>';
        }
      };

      var junctionInfoHtml = function(junctionPointsInfo) {
        var roads = _.map(_.pluck(junctionPointsInfo, 'road'), function (road) {
          return '<tr><td class="node-junctions-table">' + road + '</td></tr>';
        });

        var tracks = _.map(_.pluck(junctionPointsInfo, 'track'), function (track) {
          return '<tr><td class="node-junctions-table">' + track + '</td></tr>';
        });

        var parts = _.map(_.pluck(junctionPointsInfo, 'part'), function (part) {
          return '<tr><td class="node-junctions-table">' + part + '</td></tr>';
        });

        var addresses = _.map(_.pluck(junctionPointsInfo, 'addr'), function (addr) {
          return '<tr><td class="node-junctions-table">' + addr + '</td></tr>';
        });

        var beforeOrAfter = _.map(_.pluck(junctionPointsInfo, 'beforeAfter'), function (beforeAfter) {
          return '<tr><td class="node-junctions-table">' + beforeAfter + '</td></tr>';
        });

        return '<td class="node-junctions-table">' +
          ' <table class="node-junctions-table-dimension">' +
          roads.join('') +
          ' </table></td>' +
          '<td class="node-junctions-table">' +
          ' <table class="node-junctions-table-dimension">' +
          tracks.join('') +
          ' </table></td>' +
          '<td class="node-junctions-table">' +
          ' <table class="node-junctions-table-dimension">' +
          parts.join('') +
          ' </table></td>' +
          '<td class="node-junctions-table">' +
          ' <table class="node-junctions-table-dimension">' +
          addresses.join('') +
          ' </table></td>' +
          '<td class="node-junctions-table">' +
          ' <table class="node-junctions-table-dimension">' +
          beforeOrAfter.join('') +
          ' </table></td>';
      };

      var toHtmlTable = function(junctionsInfo){
        junctionsInfo = _.sortBy(junctionsInfo, 'junctionNumber');
        var htmlTable = "";
        htmlTable += '<table class="node-junctions-table-dimension">';
        htmlTable += headers();
        _.each(junctionsInfo, function (junction) {
          htmlTable += '<tr class="node-junctions-table-border-bottom">';
          htmlTable += detachJunctionBox(junction);
          htmlTable += '<td>' + junctionIcon(junction.junctionNumber) + '</td>';
          htmlTable += junctionInfoHtml(getJunctionPointsInfo(junction));
          htmlTable += '</tr>';
        });
        htmlTable += '</table>';
        return htmlTable;
      };

      var getJunctionPointsInfo = function(junction) {
        var info = [];
        _.map(junction.junctionPoints, function(point){
          var row = {road: point.road, part: point.part, track: point.track, addr: point.addrM, beforeAfter: point.beforeOrAfter};
          info.push(row);
        });

        var groupedHomogeneousRows = _.groupBy(info, function (row) {
          return [row.road, row.track, row.part, row.addr];
        });

        var joinedHomogeneousRows = _.partition(groupedHomogeneousRows, function(group) {
          return group.length > 1;
        });

        var doubleHomogeneousRows = joinedHomogeneousRows[0];
        var singleHomogeneousRows = joinedHomogeneousRows[1];

        var doubleRows = _.map(doubleHomogeneousRows, function(point) {
          var first = _.first(point);
          return {road: first.road, track: first.track, part: first.part, addr: first.addr, beforeAfter: "EJ"};
        });

        var singleRows = _.map(singleHomogeneousRows, function(point) {
          var first = _.first(point);
          return {road: first.road, track: first.track, part: first.part, addr: first.addr, beforeAfter: (first.beforeAfter === 1 ? "E" : "J")};
        });

        return _.sortBy(doubleRows.concat(singleRows), ['road', 'part', 'track', 'addr', 'beforeAfter']);
      };

      return {
        toHtmlTable: toHtmlTable
      };
    };

    var NodePoints = function () {
      var toHtmlTable = function(nodePointsInfo){
        var htmlTable = "";
        htmlTable += '<table class="node-points-table-dimension">';
        htmlTable += headers();
          var rowsInfo = getNodePointsRowsInfo(nodePointsInfo);
          _.each(_.sortBy(rowsInfo, ['road', 'part', 'addr']), function(row){
            htmlTable += '<tr class="node-junctions-table-border-bottom">';
            htmlTable += detachNodePointBox(row);
            htmlTable += nodePointInfoHtml(row);
            htmlTable += '</tr>';
          });
        htmlTable += '</table>';
        return htmlTable;
      };

      var detachNodePointBox = function(nodePoint) {
        var nodePointType = _.find(LinkValues.NodePointType, function (nodePointType) {
          return nodePointType.value === nodePoint.type;
        });
        var isDetachable = 'title="' + nodePointType.description + '"'; // added for testing purposes, needs to be confirm if this title is a good idea for production env.
        if (_.isEqual(nodePointType, LinkValues.NodePointType.CalculatedNodePoint)) {
          isDetachable += ' disabled';
        }
        return '<td><input ' + isDetachable + ' type="checkbox" name="detach-node-point-' + nodePoint.id + '" value="' + nodePoint.id + '" id="detach-node-point-' + nodePoint.id + '"></td>';
      };

      var nodePointInfoHtml = function(rowInfo){
        return '<td class="node-points-table">' + rowInfo.road + '</td>' +
          '<td class="node-points-table">' + rowInfo.part + '</td>' +
          '<td class="node-points-table">' + rowInfo.addr + '</td>' +
          '<td class="node-points-table">' + rowInfo.beforeAfter + '</td>';
      };

      var getNodePointsRowsInfo = function(nodePoints){
        var info = [];
        if(!_.isUndefined(nodePoints) && nodePoints.length > 0){
        _.map(nodePoints, function(point){
          var row = {id: point.id, nodeNumber: point.nodeNumber, road: point.road, part: point.part, addr: point.addrM, beforeAfter: point.beforeOrAfter, type: point.type};
          info.push(row);
        });

        var groupedHomogeneousRows = _.groupBy(info, function (row) {
          return [row.road, row.part, row.addr];
        });

        var joinedHomogeneousRows = _.partition(groupedHomogeneousRows, function(group) {
          return group.length > 1;
        });

        var doubleHomogeneousRows = joinedHomogeneousRows[0];
        var singleHomogeneousRows = joinedHomogeneousRows[1];

        var doubleRows = _.map(doubleHomogeneousRows, function(drows){
          var first = _.first(drows);
          return {id: first.id, nodeNumber: first.nodeNumber, road: first.road, part: first.part, addr: first.addr, beforeAfter: "EJ", type: first.type};
        });

        var singleRows = _.map(singleHomogeneousRows, function(drows){
          var first = _.first(drows);
          return {id: first.id, nodeNumber: first.nodeNumber, road: first.road, part: first.part, addr: first.addr, beforeAfter: (first.beforeAfter === 1 ? "E" : "J"), type: first.type};
        });

        return doubleRows.concat(singleRows);
        } else return [];
      };

      var headers = function() {
        return '<tr class="node-junctions-table-header-border-bottom">' +
          '<th class="node-points-table-header">' +
          ' <table class="node-points-table-dimension">' +
          '   <tr><th class="node-points-table-header">Irrota</th></tr>' +
          '   <tr><th class="node-points-table-header">solmukohta</th></tr>' +
          ' </table>' +
          '</th>' +
          '<th class="node-points-table-header">TIE</th>' +
          '<th class="node-points-table-header">OSA</th>' +
          '<th class="node-points-table-header">ET</th>' +
          '<th class="node-points-table-header">EJ</th>' +
          '</tr>';
      };
      return {
        toHtmlTable: toHtmlTable
      };
    };

    var junctionsTable = new Junctions();
    var nodePointsTable = new NodePoints();

    var showNodePoints = function () {
      $('#node-points-info-content').show();
    };

    var hideNodePoints = function () {
      $('#node-points-info-content').hide();
    };

    var showJunctions = function () {
      $('#junctions-info-content').show();
    };

    var hideJunctions = function () {
      $('#junctions-info-content').hide();
    };

    var toggleLinkText = function (item) {
      if (!_.isUndefined(item)) {
        var text = item.text();
        var isVisible = _.includes(text, SHOW);
        text = isVisible ? text.replace(SHOW, HIDE) : text.replace(HIDE, SHOW);
        item.text(text);
        return isVisible;
      }
    };

    var toggleContentTable = function (item, showContent, hideContent) {
      if (toggleLinkText(item)) {
        showContent();
      } else {
        hideContent();
      }
    };

    var addDatePicker = function () {
      var $date = $('#nodeStartDate');
      dateutil.addSingleDependentDatePicker($date);
      $date.change(function () {
        selectedNode.setStartDate(($(this).val()));
      });
    };

    var textFieldChangeHandler = function () {
      if (!_.isUndefined(selectedNode.getCurrentNode())) {
        selectedNode.setDirty(true);
      }

      var textIsNonEmpty = $('#nodeName').val() !== "";
      var nodeTypeIsValid = $('#nodeTypeDropdown :selected').val() !== LinkValues.NodeType.UnknownNodeType.value.toString();
      var dateIsNonEmpty = $('#nodeStartDate').val() !== "";

      if (textIsNonEmpty && nodeTypeIsValid && dateIsNonEmpty) {
        $('.btn-edit-node-save').removeProp('disabled');
      } else {
        $('.btn-edit-node-save').prop('disabled', true);
      }
    };

    var showCloseConfirmPopupMessage = function () {
      new GenericConfirmPopup('Haluatko tallentaa tekemäsi muutokset?', {
        successCallback: function () {
          selectedNode.saveNode();
        },
        closeCallback: function () {
          selectedNode.closeNode();
          eventbus.trigger('nodeLayer:refreshView');
        }
      });
    };

    var closeForm = function () {
      if (selectedNode.isDirty()) {
        showCloseConfirmPopupMessage();
      } else {
        selectedNode.closeNode();
      }
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      rootElement.on('change', '#nodeName, #nodeTypeDropdown, #nodeStartDate', function () {
        textFieldChangeHandler();
      });

      rootElement.on('change', '#nodeName', function () {
        selectedNode.setName(($(this).val()));
      });

      rootElement.on('change', '#nodeTypeDropdown', function () {
        selectedNode.setType(parseInt($(this).val()));
      });

      rootElement.on('change', '[id^="detach-node-point-"]', function () {
        new GenericConfirmPopup('Haluatko varmasti irrottaa solmukohdan solmusta?', {
          successCallback: function () {
            selectedNode.detachNodePoint(parseInt(this.value));
          },
          closeCallback: function () {
            // TODO checkbox off again :D
          }
        });
      });

      rootElement.on('change', '[id^="detach-junction-"]', function () {
        var id = parseInt(this.value);
        var junctionNumber = this.getAttribute('data-junction-number');
        if (this.checked) {
          new GenericConfirmPopup('Haluatko varmasti irrottaa liittymän ' + junctionNumber + ' solmusta?', {
            successCallback: function () {
              selectedNode.detachJunction(id);
            },
            closeCallback: function () {
              // TODO checkbox off again :D
            }
          });
        } else {
          selectedNode.attachJunction(id);
        }
      });

      rootElement.on('click', '.btn-edit-node-save', function () {
        selectedNode.saveNode();
      });

      rootElement.on('click', '.btn-edit-node-cancel', function () {
        closeForm();
      });

      eventbus.on('node:selected', function (currentNode) {
        rootElement.empty();
        if (!_.isEmpty(currentNode)) {
          rootElement.html(nodeForm(currentNode));
          addDatePicker();
          var nodePointsElement = $('#node-points-info-content');
          nodePointsElement.html(nodePointsTable.toHtmlTable(currentNode.nodePoints));
          nodePointsElement.hide();
          var junctionsElement = $('#junctions-info-content');
          junctionsElement.html(junctionsTable.toHtmlTable(currentNode.junctions));
          junctionsElement.hide();

          $('[id=node-point-link]').click(function () {
            toggleContentTable($(this), showNodePoints, hideNodePoints);
            return false;
          });

          $('[id=junction-link]').click(function () {
            toggleContentTable($(this), showJunctions, hideJunctions);
            return false;
          });
        }
      });

      eventbus.on('node:saveSuccess', function () {
        applicationModel.removeSpinner();
        selectedNode.closeNode(); // we'll have to change this later probably
      });

      eventbus.on("node:saveUnsuccessful", function (error) {
        applicationModel.removeSpinner();
        new ModalConfirm(error);
      });
    };

    bindEvents();
  };
})(this);
