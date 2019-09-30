(function (root) {
  root.NodeForm = function (selectedNode) {
    var SHOW = 'Näytä';
    var HIDE = 'Piilota';

    var NodeType = LinkValues.NodeType;
    var formCommon = new FormCommon('node-');

    var formButtons = function () {
      return '<div class="form form-controls">' +
        '<button class="save btn btn-edit-node-save" disabled>Tallenna</button>' +
        '<button class="cancel btn btn-edit-node-cancel">Peruuta</button>' +
        '</div>';
    };

    var getNodeType = function (nodeValue) {
      var nodeType =  _.find(NodeType, function(type){
        return type.value == nodeValue;
      });
      return _.isUndefined(nodeType) ? NodeType.UnkownNodeType : nodeType;
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

    var inputField = function (labelText, id, placeholder, value, maxLength) {
      var lengthLimit = '';
      if (maxLength)
        lengthLimit = 'maxlength="' + maxLength + '"';
      return '<div class="form-group-node-input-metadata">' +
        '<p class="form-control-static asset-node-data">' +
        '<label>' + labelText + '</label>' +
        '<input type="text" class="form-control-static asset-input-node-data" id = "' + id + '"' + lengthLimit + ' placeholder = "' + placeholder + '" value="' + value + '" disabled/>' +
        '</p>' +
        '</div>';
    };

    var nodeForm = function (node) {
      return _.template('' +
        '<header>' +
        formCommon.captionTitle('Solmun tiedot:') +
        '</header>' +

        '<div class="wrapper read-only">' +
        ' <div class="form form-horizontal form-dark">' +
        '   <div>' +
        staticField('Solmunumero:', node.nodeNumber) +
        inputField('*Solumn nimi:', 'name', '', node.name, 32) +
        inputField('*Solmutyyppi:', 'type', '', getNodeType(node.type).description) +
        inputField('*Alkupvm    :', 'date', '', node.startDate) +
        staticField('Koordinaatit:', node.coordY + ', ' + node.coordX) +
        '   </div>' +
        ' </div>' +
        '<div>' +
        '<p><a id="node-point-link" class="node-info-link" href="/">Näytä solmukohdat</a></p>' +
        '<div id="node-points-info-content">' +
        '</div>' +
        '<p><a id="junction-link" class="node-info-link" href="/">Näytä liittymat</a></p>' +
        '<div id="junctions-info-content">' +
        '</div>' +
        '</div>' +
        '</div>' +
        '<footer>' +
        formButtons() +
        '</footer>'
      );
    };

    var NodeJunctions = function () {
      var junctionsHtmlTable = function(junctionsInfo){
        junctionsInfo = _.sortBy(junctionsInfo, 'junctionNumber');
        var htmlTable = "";
        htmlTable += '<table class="change-table-dimensions">';
        htmlTable += headRow();
        _.each(junctionsInfo, function (junctionRow) {
          var rowsInfo = getJunctionRowsInfo(junctionRow);
          _.each(rowsInfo, function(row){
            htmlTable += '<tr>';
            htmlTable += detachJunctionBox(row);
            htmlTable += junctionInfoHtml(row);
            htmlTable += '</tr>';
          });
        });
        htmlTable += '</table>';
        return htmlTable;
      };

      var detachJunctionBox = function(rowInfo){
        return '<td>&#9744 <i id="deleteJunction_' + rowInfo.junctionId + '" ' +
          rowInfo.junctionId + '"></i></td>';
      };

      var junctionInfoHtml = function(rowInfo){
        return '<td class="node-junctions-table">' + rowInfo.junctionNumber + '</td>' +
          '<td class="node-junctions-table">' + rowInfo.road + '</td>' +
          '<td class="node-junctions-table">' + rowInfo.track + '</td>' +
          '<td class="node-junctions-table">' + rowInfo.part + '</td>' +
          '<td class="node-junctions-table">' + rowInfo.addr + '</td>' +
          '<td class="node-junctions-table">' + rowInfo.EJ + '</td>';
      };

      var getJunctionRowsInfo = function(junction){
        var junctionId = junction.id;
        var junctionNumber = junction.junctionNumber;
        var info = [];
        _.map(junction.junctionPoints, function(point){
          var row = {junctionId: junctionId, junctionNumber: junctionNumber, road: point.road, part: point.part, track: point.track, addr: point.addrM, EJ: point.beforeOrAfter};
          info.push(row);
        });

        var groupedHomogeneousRows = _.groupBy(info, function (row) {
          return [row.junctionNumber, row.road, row.track, row.part, row.addr];
        });

        var joinedHomogeneousRows = _.partition(groupedHomogeneousRows, function(group) {
          return group.length > 1;
        });

        var doubleHomogeneousRows = joinedHomogeneousRows[0];
        var singleHomogeneousRows = joinedHomogeneousRows[1];

        var doubleRows = _.map(doubleHomogeneousRows, function(drows){
          var first = _.first(drows);
          return {junctionId: first.junctionId, junctionNumber: first.junctionNumber, road: first.road, track: first.track, part: first.part, addr: first.addr, EJ: "EJ"};
        });

        var singleRows = _.map(singleHomogeneousRows, function(drows){
          var first = _.first(drows);
          return {junctionId: first.junctionId, junctionNumber: first.junctionNumber, road: first.road, track: first.track, part: first.part, addr: first.addr, EJ: (first.beforeOrAfter == 1 ? "E" : "J")};
        });

        return doubleRows.concat(singleRows);
      };

      var headRow = function(){
        return '<tr class="row-changes">'+
          '<th class="node-junctions-table-header">IRROTA</th>' +
          '<th class="node-junctions-table-header">NRO</th>' +
          '<th class="node-junctions-table-header">TIE</th>' +
          '<th class="node-junctions-table-header">AJR</th>' +
          '<th class="node-junctions-table-header">AOSA</th>' +
          '<th class="node-junctions-table-header">AET</th>' +
          '<th class="node-junctions-table-header">EJ</th>' +
          '</tr>';
      };
      return {
        junctionsHtmlTable: junctionsHtmlTable
      };
    };

    var NodePoints = function () {
      var nodePointsHtmlTable = function(nodePointsInfo){
        var htmlTable = "";
        htmlTable += '<table class="change-table-dimensions">';
        htmlTable += headRow();
          var rowsInfo = getNodePointsRowsInfo(nodePointsInfo);
          _.each(rowsInfo, function(row){
            htmlTable += '<tr>';
            htmlTable += detachNodePointBox(row);
            htmlTable += nodePointInfoHtml(row);
            htmlTable += '</tr>';
          });
        htmlTable += '</table>';
        return htmlTable;
      };

      var detachNodePointBox = function(rowInfo){
        return '<td>&#9744 <i id="deleteNodePoint_' + rowInfo.nodePointId + '" ' +
          rowInfo.nodePointId + '"></i></td>';
      };

      var nodePointInfoHtml = function(rowInfo){
        return '<td class="node-points-table">' + rowInfo.road + '</td>' +
          '<td class="node-points-table">' + rowInfo.part + '</td>' +
          '<td class="node-points-table">' + rowInfo.addr + '</td>' +
          '<td class="node-points-table">' + rowInfo.EJ + '</td>';
      };

      var getNodePointsRowsInfo = function(nodePoints){
        var info = [];
        if(!_.isUndefined(nodePoints) && nodePoints.length > 0){
        _.map(nodePoints, function(point){
          var row = {nodeId: point.nodeId, nodePointId: point.id, road: point.road, part: point.part, addr: point.addrM, EJ: point.beforeOrAfter};
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
          return {nodePointId: first.nodePointId, road: first.road, part: first.part, addr: first.addr, EJ: "EJ"};
        });

        var singleRows = _.map(singleHomogeneousRows, function(drows){
          var first = _.first(drows);
          return {nodePointId: first.nodePointId, road: first.road, part: first.part, addr: first.addr, EJ: (first.beforeOrAfter == 1 ? "E" : "J")};
        });

        return doubleRows.concat(singleRows);
        } else return [];
      };

      var headRow = function(){
        return '<tr class="row-changes">'+
          '<th class="node-points-table-header">IRROTA</th>' +
          '<th class="node-points-table-header">TIE</th>' +
          '<th class="node-points-table-header">AOSA</th>' +
          '<th class="node-points-table-header">AET</th>' +
          '<th class="node-points-table-header">EJ</th>' +
          '</tr>';
      };
      return {
        nodePointsHtmlTable: nodePointsHtmlTable
      };
    };

    var nodeJunctionsTable = new NodeJunctions();
    var nodePointsTable = new NodePoints();

    var showNodePoints = function (nodePoints) {
      $('#node-points-info-content').html(nodePointsTable.nodePointsHtmlTable(nodePoints));
    };

    var hideNodePoints = function () {
      $('#node-points-info-content').html("");
    };

    var showJunctions = function (junctions) {
      $('#junctions-info-content').html(nodeJunctionsTable.junctionsHtmlTable(junctions));
    };

    var hideJunctions = function () {
      $('#junctions-info-content').html("");
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

    var toggleContentTable = function (item, showContent, hideContent, data) {
      if (toggleLinkText(item)) {
        showContent(data);
      } else {
        hideContent();
      }
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      rootElement.on('click', '.btn-edit-node-cancel', function () {
        selectedNode.close();
      });

      eventbus.on('node:selected', function () {
        rootElement.empty();
        var currentNode = selectedNode.getCurrentNode();

        if (!_.isEmpty(currentNode)) {
          rootElement.html(nodeForm(currentNode));

          $('[id=node-point-link]').click(function () {
            toggleContentTable($(this), showNodePoints, hideNodePoints, currentNode.nodePoints);
            return false;
          });

          $('[id=junction-link]').click(function () {
            toggleContentTable($(this), showJunctions, hideJunctions, currentNode.junctions);
            return false;
          });

        } else {
          selectedNode.close();
        }
      });
    };

    bindEvents();
  };
})(this);