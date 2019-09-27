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
        '<div id="nodes-content">' +
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

    var changeTable =
      $('<div class="change-table-frame"></div>');
    // Text about validation success hard-coded now
    var changeTableHeader = $('<div class="change-table-fixed-header"></div>');
    changeTableHeader.append('<div class="change-table-borders">' +
      '<div id ="change-table-borders-changetype"></div>' +
      '<div id ="change-table-borders-source"></div>' +
      '<div id ="change-table-borders-reversed"></div>' +
      '<div id ="change-table-borders-target"></div></div>');
    changeTableHeader.append('<div class="change-header">' +
      '<label class="project-change-table-dimension-header target">    </label>' +
      '<label class="project-change-table-dimension-header">NRO</label>' +
      '<label class="project-change-table-dimension-header">TIE</label>' +
      '<label class="project-change-table-dimension-header">AJR</label>' +
      '<label class="project-change-table-dimension-header">AOSA</label>' +
      '<label class="project-change-table-dimension-header">AET</label>' +
      '<label class="project-change-table-dimension-header">EJ</label>');

    changeTableHeader.append('<div class="change-table-dimension-headers" style="overflow-y: auto;">' +
      '<table class="change-table-dimensions">' +
      '</table>' +
      '</div>');
    changeTable.append(changeTableHeader);

    var detachJunctionBox = function(rowInfo){
      return '<td class="project-change-table-dimension" id="deleteProjectSpan">&#9744 <i id="deleteJunction_' + rowInfo.junctionId + '" ' +
        'class="fas fa-trash-alt" value="' + rowInfo.junctionId + '"></i></td>';

      // html += '<span id="deleteProjectSpan" class="deleteSpan">POISTA PROJEKTI <i id="deleteProject_' + currentProject.id + '" ' +
      //   'class="fas fa-trash-alt" value="' + currentProject.id + '"></i></span>';
        // return ((changeInfoSeq.reversed) ? '<td class="project-change-table-dimension">&#9745</td>': '<td class="project-change-table-dimension">&#9744</td>');
    };

    function setTableHeight() {
      var changeTableHeight = parseInt(changeTable.height());
      var headerHeight = parseInt($('.change-header').height());
      $('.change-table-dimension-headers').height(changeTableHeight - headerHeight - 30);// scroll size = total - header - border
    }


    // junctionId: first.junctionId, junctionNumber: first.junctionNumber, road: first.road, track: first.track, part: first.part, addr: first.addr, EJ: "EJ"
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

    var junctionsHtml = function (junctionsInfo) {
      var htmlTable = "";
      _.each(junctionsInfo, function (junctionRow) {
        var rowsInfo = getJunctionRowsInfo(junctionRow);
        _.each(rowsInfo, function(row){
          htmlTable += '<tr class="row-changes">';
          htmlTable += detachJunctionBox(row);
          htmlTable += junctionInfoHtml(row);
          htmlTable += '</tr>';
        });
      });
      setTableHeight();
      $('.row-changes').remove();
      $('.change-table-dimensions').append($(htmlTable));
    };

    var getNodeJunctions = function (junctions) {
      applicationModel.addSpinner();
        $('#junctions-content').html(junctionsHtml(junctions));
      applicationModel.removeSpinner();
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      // rootElement.on('click', '#deleteProjectSpan', function(){
      //   displayDetachConfirmMessage("Haluatko varmasti poistaa tämän projektin?", true);
      // });
      //
      // var displayDetachConfirmMessage = function (popupMessage) {
      //   new GenericConfirmPopup(popupMessage, {
      //     successCallback: function () {
      //       detachJunction();
      //     },
      //     closeCallback: function () {
      //       return false;
      //     }
      //   });
      // };

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

      // $('[id=junction-point-link]').click(function () {
      //
      //   eventbus.trigger('junctionPointForm-junctionPoint:select', junctionId);
      //   return false;
      // });


    };

    bindEvents();
  };
})(this);