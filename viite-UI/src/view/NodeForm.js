(function (root) {
  root.NodeForm = function (selectedNode) {
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
        '<p><a id="node-point-link" class="node-points">Näytä solmukohdat</a></p>' +
        '<div id="node-points-content">' +
        '</div>' +
        '<p><a id="junction-link" class="node-points">Näytä liittymat</a></p>' +
        '<div id="junctions-content">' +
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
        return '<td class="project-change-table-dimension">' + rowInfo.junctionNumber + '</td>' +
          '<td class="project-change-table-dimension">' + rowInfo.road + '</td>' +
          '<td class="project-change-table-dimension">' + rowInfo.track + '</td>' +
          '<td class="project-change-table-dimension">' + rowInfo.part + '</td>' +
          '<td class="project-change-table-dimension">' + rowInfo.addr + '</td>' +
          '<td class="project-change-table-dimension">' + rowInfo.EJ + '</td>';
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
          '<label class="project-change-table-dimension-header target">    </label>' +
          '<label class="project-change-table-dimension-header">NRO</label>' +
          '<label class="project-change-table-dimension-header">TIE</label>' +
          '<label class="project-change-table-dimension-header">AJR</label>' +
          '<label class="project-change-table-dimension-header">AOSA</label>' +
          '<label class="project-change-table-dimension-header">AET</label>' +
          '<label class="project-change-table-dimension-header">EJ</label>' +
          '</tr>';
      };
      return {
        junctionsHtmlTable: junctionsHtmlTable
      };
    };

    var NodePoints = function () {
      var nodePointsHtmlTable = function(node){
        var htmlTable = "";
        htmlTable += '<table class="change-table-dimensions">';
        htmlTable += headRow();
          var rowsInfo = getNodePointsRowsInfo(node.nodePoints);
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
        return '<td class="project-change-table-dimension">' + rowInfo.road + '</td>' +
          '<td class="project-change-table-dimension">' + rowInfo.part + '</td>' +
          '<td class="project-change-table-dimension">' + rowInfo.addr + '</td>' +
          '<td class="project-change-table-dimension">' + rowInfo.EJ + '</td>';
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
          '<label class="project-change-table-dimension-header target">    </label>' +
          '<label class="project-change-table-dimension-header">TIE</label>' +
          '<label class="project-change-table-dimension-header">AOSA</label>' +
          '<label class="project-change-table-dimension-header">AET</label>' +
          '<label class="project-change-table-dimension-header">EJ</label>' +
          '</tr>';
      };
      return {
        nodePointsHtmlTable: nodePointsHtmlTable
      };
    };

    var nodeJunctions = new NodeJunctions();
    var nodePoints = new NodePoints();

    var getNodePoints = function (nPoints) {
      applicationModel.addSpinner();
      $('#node-points-content').html(nodePoints.nodePointsHtmlTable(nPoints));
      applicationModel.removeSpinner();
    };

    var getNodeJunctions = function (junctions) {
      applicationModel.addSpinner();
      $('#junctions-content').html(nodeJunctions.junctionsHtmlTable(junctions));
      applicationModel.removeSpinner();
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
            changeNodePointsLinkText();
            var node = currentNode;
            if(node.nodePoints.length > 0)
              getNodePoints(node);
            return false;
          });

          $('[id=junction-link]').click(function () {
            changeJunctionsLinkText();
            var node = currentNode;
            if(node.junctions.length > 0)
            getNodeJunctions(node.junctions);
            return false;
          });
        } else {
          selectedNode.close();
        }
      });

      function changeNodePointsLinkText() {
        var nodePointLink = $('[id=node-point-link]')[0];
        if(!_.isUndefined(nodePointLink)){
          if(nodePointLink.textContent == "Näytä solmukohdat"){
            $('[id=node-point-link]')[0].text = "Piilota solmukohdat";
          } else if(nodePointLink.textContent == "Piilota solmukohdat"){
            $('[id=node-point-link]')[0].text = "Näytä solmukohdat";
          }
        }
      }

      function changeJunctionsLinkText(){
        var junctionLink = $('[id=junction-link]')[0];
        if(!_.isUndefined(junctionLink)){
          if(junctionLink.textContent == "Näytä liittymat"){
            $('[id=junction-link]')[0].text = "Piilota liittymat";
          } else if(junctionLink.textContent == "Piilota liittymat"){
            $('[id=junction-link]')[0].text = "Näytä liittymat";
          }
        }
      }

    };

    bindEvents();
  };
})(this);