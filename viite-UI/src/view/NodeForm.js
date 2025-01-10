(function (root) {
  root.NodeForm = function (selectedNodesAndJunctions, roadCollection, backend, startupParameters) {
    var formCommon = new FormCommon('node-');
    var NodeType = ViiteEnumerations.NodeType;

    var NODE_POINTS_TITLE = 'Solmukohdat';
    var JUNCTIONS_TITLE = 'Liittymät';
    var picker;
    var userHasPermissionToEdit = _.includes(startupParameters.roles, 'viite');
    var nodeEditingDisabledAttribute = userHasPermissionToEdit ? '' : 'disabled';

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

    var inputFieldRequired = function (labelText, id, placeholder, value, propName, propValue) {
      var property = '';
      if (!_.isUndefined(propName) && !_.isUndefined(propValue)) {
        property = propName + '="' + propValue + '"';
      }
      return '<div class="form-group-node-input-metadata">' +
        '<p class="form-control-static asset-node-data">' +
        '<label class="required">' + labelText + '</label>' +
        '<input type="text" class="form-control asset-input-node-data" id = "' + id + '"' + property + ' placeholder = "' + placeholder + '" value="' + value + '"' + nodeEditingDisabledAttribute + '/>' +
        '</p></div>';
    };

    var addNodeTypeDropdown = function (labelText, id, nodeType) {
      var addNodeTypeOptions = function (selected) {
        var nodeTypes = _.filter(ViiteEnumerations.NodeType, function (nodeTypeFiltered) {
          return nodeTypeFiltered !== ViiteEnumerations.NodeType.UnknownNodeType;
        });

        return _.map(nodeTypes, function (nodeTypeMapped) {
          var option = _.isEqual(nodeTypeMapped, selected) ? 'selected' : '';
          return '<option value="' + nodeTypeMapped.value + '"' + option + '>' +
            nodeTypeMapped.value + ' ' + nodeTypeMapped.description + '</option>';
        });
      };

      var unknownNodeType = "";
      if (nodeType === ViiteEnumerations.NodeType.UnknownNodeType) {
        unknownNodeType = '<option value="' + nodeType.value + '" selected disabled hidden>' +
          nodeType.value + ' ' + nodeType.description + '</option>';
      }

      return '<div class="form-group-node-input-metadata"><p class="form-control-static asset-node-data">' +
        ' <label class="dropdown required">' + labelText + '</label>' +
        ' <select type="text" class="form-control asset-input-node-data" id="' + id + '"' + nodeEditingDisabledAttribute + '>' +
        unknownNodeType +
        addNodeTypeOptions(nodeType) +
        ' </select></p></div>';
    };

    var templatesForm = function (title) {
      var formButtons = function () {
        return '<div class="form form-controls">' +
          ' <button id="attachToMapNode" class="btn btn-block btn-attach-node">Valitse kartalta solmu, johon haluat liittää aihiot</button>' +
          ' <button id="attachToNewNode" class="btn btn-block btn-attach-node">Luo uusi solmu, johon haluat liittää aihiot</button>' +
          ' <button class="save btn btn-edit-node-save" disabled>Tallenna</button>' +
          ' <button class="cancel btn btn-edit-templates-cancel">Peruuta</button>' +
          '</div>';
      };

      return _.template('' +
        '<header>' +
        formCommon.captionTitle(title) +
        '</header>' +

        '<div class="wrapper read-only">' +
        ' <div class="form form-horizontal form-dark">' +
        '   <div>' +
        '     <p class="node-info">' + NODE_POINTS_TITLE + '</p>' +
        '     <div id="node-points-info-content">' +
        '     </div>' +
        '     <p class="node-info">' + JUNCTIONS_TITLE + '</p>' +
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

    var nodeForm = function (title, node) {
      var formButtons = function () {
        return '<div class="form form-controls">' +
          ' <button class="save btn btn-edit-node-save" disabled>Tallenna</button>' +
          ' <button class="cancel btn btn-edit-node-cancel">Peruuta</button>' +
          '</div>';
      };
      var nodeNumber = node.nodeNumber ? node.nodeNumber : '-';
      var nodeName = node.name ? node.name : '';
      var startDate = node.startDate ? node.startDate : '';

      return _.template('' +
        '<header>' +
        formCommon.captionTitle(title) +
        '</header>' +

        '<div class="wrapper read-only">' +
        ' <div class="form form-horizontal form-dark">' +
        '   <div>' +
        staticField('Solmunumero:', nodeNumber) +
        staticField('Koordinaatit (<i>P</i>, <i>I</i>):', '<span id="node-coordinates">' + node.coordinates.y + ', ' + node.coordinates.x + '</span>') +
        inputFieldRequired('Solmun nimi', 'nodeName', '', nodeName, 'maxlength', 30) +
        addNodeTypeDropdown('Solmutyyppi', 'nodeTypeDropdown', getNodeType(node.type)) +
        inputFieldRequired('Alkupvm', 'nodeStartDate', 'pp.kk.vvvv', startDate, 'disabled', true) +
        '   <div class="form-check-date-notifications"> ' +
        '     <p id="nodeStartDate-validation-notification"> </p>' +
        '   </div>' +
        '   </div>' +
        '   <div>' +
        '     <p class="node-info">' + NODE_POINTS_TITLE + '</p>' +
        '     <div id="node-points-info-content">' +
        '     </div>' +
        '     <p class="node-info">' + JUNCTIONS_TITLE + '</p>' +
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
      var headers = function (options) {
        return '<tr class="node-junctions-table-header-border-bottom">' +
          ((options && options.checkbox) ? '<th class="node-junctions-table-header">' +
            '   <table class="node-junctions-table-dimension">' +
            '     <tr><th class="node-junctions-table-header">Irrota</th></tr>' +
            '     <tr><th class="node-junctions-table-header">liittymä</th></tr>' +
            '     <tr><th class="node-junctions-table-header">solmusta</th></tr>' +
            '   </table>' +
            ' </th>' : '') +
          ((options && options.junctionInputNumber) ? '<th class="node-junctions-table-header">NRO</th>' : '') +
          ' <th class="node-junctions-table-header">TIE</th>' +
          ' <th class="node-junctions-table-header">AJR</th>' +
          ' <th class="node-junctions-table-header">OSA</th>' +
          ' <th class="node-junctions-table-header junction-address-header">ET</th>' +
          ' <th class="node-junctions-table-header">EJ</th>' +
          '</tr>';
      };

      var detachJunctionBox = function (junction) {
        return '<td><input type="checkbox" name="detach-junction-' + junction.id + '" value="' + junction.id + '" id="detach-junction-' + junction.id + '"' +
          ' data-junction-number=" ' + junction.junctionNumber + ' " ' + nodeEditingDisabledAttribute + '></td>';
      };

      var junctionInputNumber = function (junction) {
        return '<td><input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode === 8 || event.keyCode === 9)"' +
          ' class="form-control junction-number-input" id="junction-number-textbox-' + junction.id + '" junctionId="' + junction.id + '" maxlength="2" value="' + (junction.junctionNumber || '') + '"' + nodeEditingDisabledAttribute + '/></td>';
      };


      /**
       * If the junction point has a validation error
       * set the title attribute to notify the user and keep the input field disabled.
       * Otherwise enable the input field.
       */
      eventbus.on('junctionPoint:editableStatusFetched', function(response, jp) {
        var validationMessage = '';
        if (!response.isEditable) {
          validationMessage = response.validationMessage;
        }
        var inputField = $('#junction-point-address-input-' + jp.id);
        if(validationMessage.length === 0){
          inputField.attr('disabled', false);
        } else {
          inputField.attr('title', validationMessage);
        }
      });

      var getAddressLimitsFromNeighbouringLinks = function (addr, jp) {
        var neighbouringRoadLinks = _.map(roadCollection.getByRoadPartAndAddr(jp.roadNumber, jp.roadPartNumber, jp.addr), function(roadLink) {
          return roadLink.getData();
        });
        var minAddrByRoadLink = '';
        var maxAddrByRoadLink = '';
        neighbouringRoadLinks.forEach((roadLink) => {
          if (roadLink.endAddressM === addr) { // road link is before the junction
            minAddrByRoadLink = roadLink.startAddressM + 1;
          } else if (roadLink.startAddressM === addr) { // road link is after the junction
            maxAddrByRoadLink = roadLink.endAddressM - 1;
          }
        });
        return { minAddrByRoadLink, maxAddrByRoadLink };
      };

      var getAllowedAddrEditRange = function (jp) {
        var addr = jp.addr;
        // the address change must be less than 10 meters
        var maxAddr = jp.addr + 9;
        var minAddr = jp.addr - 9;

        // the new address must be within neighbouring linear locations / road links
        var range = getAddressLimitsFromNeighbouringLinks(addr, jp);
        var minAddrByRoadLink = range.minAddrByRoadLink;
        var maxAddrByRoadLink = range.maxAddrByRoadLink;

        if (minAddrByRoadLink > minAddr) {
          minAddr = minAddrByRoadLink;
        }
        if (maxAddrByRoadLink < maxAddr) {
          maxAddr = maxAddrByRoadLink;
        }
        return { minAddr, maxAddr };
      };

      var junctionPointInputAddr = function (jp) {
        backend.getJunctionPointEditableStatus(jp.id, jp);

        var range = getAllowedAddrEditRange(jp);
        var minAddr = range.minAddr;
        var maxAddr = range.maxAddr;

        // at this point the input field is disabled because backend is checking if the junction points are on reserved road parts.
        return '<input disabled="true" type="number"' +
          ' class="form-control junction-point-address-input" id="junction-point-address-input-' +
          jp.id + '" junctionPointId="' + jp.id + '" maxlength="5" value="' + jp.addr + '" min="' + minAddr + '" max="' + maxAddr + '"/>';
      };

      var toMessage = function (junctionsInfo) {
        return toHtmlTable({currentJunctions: junctionsInfo});
      };

      var toHtmlTemplateTable = function (junctionsInfo) {
        return toHtmlTable({junctionTemplates: junctionsInfo});
      };

      var toHtmlTable = function (data) {
        return '<table id="junctions-table-info" class="node-junctions-table-dimension">' +
          headers(data.options) +
          toHtmlRows(data.junctionTemplates, data.options, 'node-junctions-table template') +
          toHtmlRows(data.currentJunctions, data.options, 'node-junctions-table') +
          '</table>';
      };

      var toHtmlRows = function (junctionsInfo, options, tableRowClass) {
        var junctionInfoHtml = function (junctionPointsInfo) {
          var roads = _.map(_.map(junctionPointsInfo, 'roadNumber'), function (roadNumber) {
            return '<tr><td class="' + tableRowClass + '">' + roadNumber + '</td></tr>';
          });

          var tracks = _.map(_.map(junctionPointsInfo, 'track'), function (track) {
            return '<tr><td class="' + tableRowClass + '">' + track + '</td></tr>';
          });

          var parts = _.map(_.map(junctionPointsInfo, 'roadPartNumber'), function (roadPartNumber) {
            return '<tr><td class="' + tableRowClass + '">' + roadPartNumber + '</td></tr>';
          });

          var addresses = _.map(junctionPointsInfo, function (jp) {
            var addr = jp.addr;
            var beforeAfter = jp.beforeAfter;
            return '<tr><td class="' + tableRowClass + '"><span class="junction-point-address-label">' + addr + '</span>' +
              (beforeAfter === "EJ" ? junctionPointInputAddr(jp) : "") + '</td></tr>';
          });

          var beforeOrAfter = _.map(_.map(junctionPointsInfo, 'beforeAfter'), function (beforeAfter) {
            return '<tr><td class="' + tableRowClass + '">' + beforeAfter + '</td></tr>';
          });

          return '<td class="' + tableRowClass + '">' +
            ' <table class="node-junctions-table-dimension">' +
            roads.join('') +
            ' </table></td>' +
            '<td class="' + tableRowClass + '">' +
            ' <table class="node-junctions-table-dimension">' +
            tracks.join('') +
            ' </table></td>' +
            '<td class="' + tableRowClass + '">' +
            ' <table class="node-junctions-table-dimension">' +
            parts.join('') +
            ' </table></td>' +
            '<td class="' + tableRowClass + '">' +
            ' <table class="node-junctions-table-dimension">' +
            addresses.join('') +
            ' </table></td>' +
            '<td class="' + tableRowClass + '">' +
            ' <table class="node-junctions-table-dimension">' +
            beforeOrAfter.join('') +
            ' </table></td>';
        };

        var htmlTable = '';
        _.each(junctionsInfo, function (junction) {
          htmlTable += '<tr class="node-junctions-table-border-bottom">' +
            ((options && options.checkbox) ? detachJunctionBox(junction) : '') +
            ((options && options.junctionInputNumber) ? junctionInputNumber(junction) : '') +
            junctionInfoHtml(getJunctionPointsInfo(junction)) +
            '</tr>';
        });
        return htmlTable;
      };

      var getJunctionPointsInfo = function (junction) {
        var info = [];
        _.map(junction.junctionPoints, function (point) {
          var row = {
            id: point.id,
            roadNumber: point.roadNumber,
            roadPartNumber: point.roadPartNumber,
            track: point.track,
            addr: point.addrM,
            beforeAfter: point.beforeAfter
          };
          info.push(row);
        });

        var groupedHomogeneousRows = _.groupBy(info, function (row) {
          return [row.roadNumber, row.track, row.roadPartNumber, row.addr];
        });

        var joinedHomogeneousRows = _.partition(groupedHomogeneousRows, function (group) {
          return group.length > 1;
        });

        var doubleHomogeneousRows = joinedHomogeneousRows[0];
        var singleHomogeneousRows = joinedHomogeneousRows[1];

        var doubleRows = _.map(doubleHomogeneousRows, function (point) {
          var first = _.head(point);
          var last = _.last(point);
          return {
            id: Math.min(first.id, last.id) + '-' + Math.max(first.id, last.id),
            roadNumber: first.roadNumber,
            track: first.track,
            roadPartNumber: first.roadPartNumber,
            addr: first.addr,
            beforeAfter: "EJ"
          };
        });

        var singleRows = _.map(singleHomogeneousRows, function (point) {
          var first = _.head(point);
          return {
            id: first.id,
            roadNumber: first.roadNumber,
            track: first.track,
            roadPartNumber: first.roadPartNumber,
            addr: first.addr,
            beforeAfter: (first.beforeAfter === 1 ? "E" : "J")
          };
        });

        return _.sortBy(doubleRows.concat(singleRows), ['roadNumber', 'roadPartNumber', 'track', 'addr', 'beforeAfter']);
      };

      return {
        toMessage: toMessage,
        toHtmlTemplateTable: toHtmlTemplateTable,
        toHtmlTable: toHtmlTable
      };
    };

    var NodePoints = function () {
      var toMessage = function (nodePointsInfo) {
        return toHtmlTable({currentNodePoints: nodePointsInfo});
      };

      var toHtmlTable = function (data) {
        return '<table id="nodePoints-table-info" class="node-points-table-dimension">' +
          headers(data.options) +
          toHtmlRows(data.nodePointTemplates, data.options, 'node-points-table template') +
          toHtmlRows(data.currentNodePoints, data.options, 'node-points-table') +
          '</table>';
      };

      var toHtmlRows = function (nodePointsInfo, options, tableRowClass) {
        var nodePointInfoHtml = function (rowInfo) {
          return '<td class="' + tableRowClass + '">' + rowInfo.roadNumber + '</td>' +
            '<td class="' + tableRowClass + '">' + rowInfo.roadPartNumber + '</td>' +
            '<td class="' + tableRowClass + '">' + rowInfo.addr + '</td>' +
            '<td class="' + tableRowClass + '">' + rowInfo.beforeAfter + '</td>';
        };

        var rowsInfo = getNodePointsRowsInfo(nodePointsInfo);
        var htmlTable = '';
        _.each(_.sortBy(rowsInfo, ['roadNumber', 'roadPartNumber', 'addr']), function (row) {
          htmlTable += '<tr class="node-junctions-table-border-bottom">' +
            ((options && options.checkbox) ? detachNodePointBox(row) : '') +
            nodePointInfoHtml(row) +
            '</tr>';
        });
        return htmlTable;
      };

      var detachNodePointBox = function (nodePoint) {
        var nodePointType = _.find(ViiteEnumerations.NodePointType, function (nodePointTypeFound) {
          return nodePointTypeFound.value === nodePoint.type;
        });
        var isDetachable = 'title="' + nodePointType.description + '"'; // added for testing purposes, needs to be confirm if this title is a good idea for production env.
        if (_.isEqual(nodePointType, ViiteEnumerations.NodePointType.CalculatedNodePoint)) {
          isDetachable += ' disabled hidden';
        }
        return '<td><input ' + isDetachable + ' type="checkbox" name="detach-node-point-' + nodePoint.id + '" value="' + nodePoint.id + '" id="detach-node-point-' + nodePoint.id + '"' + nodeEditingDisabledAttribute + '></td>';
      };

      var getNodePointsRowsInfo = function (nodePoints) {
        if (!_.isUndefined(nodePoints) && nodePoints.length > 0) {
          var nodePointsRows = [];
          _.map(nodePoints, function (point) {
            var row = {
              id: point.id,
              nodeNumber: point.nodeNumber,
              roadNumber: point.roadNumber,
              roadPartNumber: point.roadPartNumber,
              addr: point.addrM,
              beforeAfter: point.beforeAfter,
              type: point.type
            };
            nodePointsRows.push(row);
          });

          var groupedHomogeneousRows = _.groupBy(nodePointsRows, function (row) {
            return [row.roadNumber, row.roadPartNumber, row.addr];
          });

          var joinedHomogeneousRows = _.partition(groupedHomogeneousRows, function (group) {
            return group.length > 1;
          });

          var doubleHomogeneousRows = joinedHomogeneousRows[0];
          var singleHomogeneousRows = joinedHomogeneousRows[1];

          var doubleRows = _.map(doubleHomogeneousRows, function (drows) {
            var first = _.head(drows);
            return {
              id: first.id,
              nodeNumber: first.nodeNumber,
              roadNumber: first.roadNumber,
              roadPartNumber: first.roadPartNumber,
              addr: first.addr,
              beforeAfter: "EJ",
              type: first.type
            };
          });

          var singleRows = _.map(singleHomogeneousRows, function (drows) {
            var first = _.head(drows);
            return {
              id: first.id,
              nodeNumber: first.nodeNumber,
              roadNumber: first.roadNumber,
              roadPartNumber: first.roadPartNumber,
              addr: first.addr,
              beforeAfter: (first.beforeAfter === 1 ? "E" : "J"),
              type: first.type
            };
          });

          return _.sortBy(doubleRows.concat(singleRows), ['roadNumber', 'roadPartNumber', 'track', 'addr', 'beforeAfter']);
        } else return [];
      };

      var headers = function (options) {
        return '<tr class="node-junctions-table-header-border-bottom">' +
          ((options && options.checkbox) ? '<th class="node-points-table-header">' +
            '   <table class="node-points-table-dimension">' +
            '     <tr><th class="node-points-table-header">Irrota</th></tr>' +
            '     <tr><th class="node-points-table-header">solmukohta</th></tr>' +
            '   </table>' +
            ' </th>' : '') +
          ' <th class="node-points-table-header">TIE</th>' +
          ' <th class="node-points-table-header">OSA</th>' +
          ' <th class="node-points-table-header">ET</th>' +
          ' <th class="node-points-table-header">EJ</th>' +
          '</tr>';
      };

      return {
        toMessage: toMessage,
        toHtmlTable: toHtmlTable
      };
    };

    var junctionsTable = new Junctions();
    var nodePointsTable = new NodePoints();

    var showAddressInputs = function () {
      $('#edit-junction-point-addresses').hide();
      $('.junction-point-address-label').hide();
      $('.junction-point-address-input').show();
    };

    var addDatePicker = function (fromElement, minDate) {
      picker = dateutil.addSingleDatePickerWithMinDate(fromElement, minDate);
      fromElement.on('input', function () {
        $(this).change();
      });
      fromElement.change(function () {
        selectedNodesAndJunctions.setStartDate(this.value);
      });
    };

    var resetDatePicker = function (originalStartDate) {
      picker.setDate(originalStartDate);
      picker.gotoToday();
    };

    var disabledDatePicker = function (isDisabled) {
      $('#nodeStartDate').prop('disabled', isDisabled);
    };

    var disableAutoComplete = function () {
      $('[id=nodeName]').attr('autocomplete', 'false');
      $('[id=nodeStartDate]').attr('autocomplete', 'false');
    };

    var formIsInvalid = function () {
      var junctionInputs = [];
      $("#junctions-table-info").each(function(){
        junctionInputs = $(this).find(':input').get(); //<-- Should return all junction input elements as an Array
      });

      return $('#nodeName').val() === "" ||
        $('#nodeTypeDropdown').val() === ViiteEnumerations.NodeType.UnknownNodeType.value.toString() ||
        $('#nodeStartDate').val() === "" ||
        !selectedNodesAndJunctions.validateJunctionNumbers() ||
        !selectedNodesAndJunctions.isDirty() ||
        !junctionInputs.every((inp) => inp.validity.valid);
    };

    var closeNode = function (cancel) {
      eventbus.off('change:nodeName change:nodeTypeDropdown change:nodeStartDate junction:validate junctionPoint:validate junction:setCustomValidity junction:detach nodePoint:detach junction:attach nodePoint:attach');
      selectedNodesAndJunctions.closeNode(cancel);
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      rootElement.on('change', '#nodeName, #nodeTypeDropdown, #nodeStartDate', function (event) {
        eventbus.trigger(event.type + ':' + event.target.id, $(this).val());
      });

      rootElement.on('change', '[id^=junction-number-textbox-]', function () {
        selectedNodesAndJunctions.setJunctionNumber(parseInt($(this).attr('junctionId')), parseInt(this.value));
      });

      rootElement.on('change', '[id^=junction-point-address-input-]', function () {
        var idString = $(this).attr('junctionPointId');
        var addr = parseInt(this.value);
        selectedNodesAndJunctions.setJunctionPointAddress(idString, addr);
      });

      var buildMessage = function (junction, nodePoints) {
        var nodePointsHtmlTable = '';
        if (!_.isUndefined(nodePoints)) {
          nodePointsHtmlTable = '<p class="node-info">Solmukohdat :</p>' +
            nodePointsTable.toMessage(nodePoints);
        }

        var junctionHtmlTable = '';
        if (!_.isUndefined(junction)) {
          junctionHtmlTable = '<p class="node-info">Liittyma :</p>' +
            junctionsTable.toMessage([junction]);
        }

        return 'Haluatko varmasti irrottaa solmukohdat ja liittymän solmusta ?' +
          nodePointsHtmlTable +
          junctionHtmlTable;
      };

      var junctionAndNodePointsByJunctionPointsCoordinates = function (junctionId) {
        var junction = _.find(selectedNodesAndJunctions.getJunctions(), function (junctionFound) {
          return junctionFound.id === junctionId;
        });

        var nodePoints = _.filter(selectedNodesAndJunctions.getNodePoints(), function (nodePoint) {
          var junctionPointsCoordinates = _.map(junction.junctionPoints, function (junctionPoint) {
            return junctionPoint.coordinates;
          });

          return !_.isEmpty(_.intersectionWith(junctionPointsCoordinates, [nodePoint.coordinates], _.isEqual));
        });

        return {
          junction: junction,
          nodePoints: _.filter(nodePoints, function (nodePoint) {
            return nodePoint.type === ViiteEnumerations.NodePointType.RoadNodePoint.value || nodePoint.type === ViiteEnumerations.NodePointType.UnknownNodePointType.value;
          })
        };
      };

      var junctionAndNodePointsByNodePointCoordinates = function (nodePointId) {
        var nodePoints = selectedNodesAndJunctions.getNodePoints();
        var targetNodePoint = _.find(nodePoints, function (nodePoint) {
          return nodePoint.id === nodePointId;
        });

        var junction = _.find(selectedNodesAndJunctions.getJunctions(), function (junctionFound) {
          var junctionPointsCoordinates = _.map(junctionFound.junctionPoints, 'coordinates');

          return !_.isEmpty(_.intersectionWith(junctionPointsCoordinates, [targetNodePoint.coordinates], _.isEqual));
        });

        if (junction) {
          return junctionAndNodePointsByJunctionPointsCoordinates(junction.id);
        } else {
          return {
            nodePoints: _.filter(nodePoints, function (nodePoint) {
              return _.isEqual(nodePoint.coordinates, targetNodePoint.coordinates) &&
                (nodePoint.type === ViiteEnumerations.NodePointType.RoadNodePoint.value || nodePoint.type === ViiteEnumerations.NodePointType.UnknownNodePointType.value);
            })
          };
        }
      };

      var toggleJunctionInputNumber = function (junction, disabled) {
        var junctionInputNumber = $('[id="junction-number-textbox-' + junction.id + '"]');
        junction.junctionNumber = disabled ? '' : junction.junctionNumber; // clears junction number upon detach
        junctionInputNumber.prop('disabled', disabled);
        junctionInputNumber.val(junction.junctionNumber);
        selectedNodesAndJunctions.validateJunctionNumbers();
        selectedNodesAndJunctions.updateNodesAndJunctionsMarker([junction]);
      };

      var markJunctionAndNodePoints = function (junction, nodePoints, checked) {
        if (!_.isUndefined(junction)) {
          $('[id^="detach-junction-' + junction.id + '"]').prop('checked', checked);
          toggleJunctionInputNumber(junction, checked);
        }
        _.each(nodePoints, function (nodePoint) {
          $('[id^="detach-node-point-' + nodePoint.id + '"]').prop('checked', checked);
        });
      };

      rootElement.on('change', '[id^="detach-node-point-"]', function () {
        var me = this;
        var nodePointId = parseInt(me.value);
        var match = junctionAndNodePointsByNodePointCoordinates(nodePointId);
        var junction = match.junction;
        var nodePoints = match.nodePoints;
        if (me.checked) {
          if (!_.isEmpty(junction) || nodePoints.length > 1) {
            new GenericConfirmPopup(buildMessage(junction, match.nodePoints), {
              successCallback: function () {
                selectedNodesAndJunctions.detachJunctionAndNodePoints(junction, nodePoints);
                markJunctionAndNodePoints(junction, nodePoints, true);
              },
              closeCallback: function () {
                $(me).prop('checked', false);
              }
            });
          } else {
            selectedNodesAndJunctions.detachJunctionAndNodePoints(undefined, nodePoints);
            markJunctionAndNodePoints(undefined, nodePoints, true);
          }
        } else {
          new GenericConfirmPopup('Haluatko peruuttaa solmukohtien ja liittymän irrotuksen solmusta ?', {
            successCallback: function () {
              selectedNodesAndJunctions.attachJunctionAndNodePoints(junction, nodePoints);
              markJunctionAndNodePoints(junction, nodePoints, false);
            },
            closeCallback: function () {
              $(me).prop('checked', true);
            }
          });
        }
      });

      rootElement.on('change', '[id^="detach-junction-"]', function () {
        var me = this;
        var junctionId = parseInt(me.value);
        var match = junctionAndNodePointsByJunctionPointsCoordinates(junctionId);
        var junction = match.junction;
        var nodePoints = match.nodePoints;
        if (me.checked) {
          // eslint-disable-next-line no-negated-condition
          if (!_.isEmpty(nodePoints)) {
            new GenericConfirmPopup(buildMessage(junction, match.nodePoints), {
              successCallback: function () {
                selectedNodesAndJunctions.detachJunctionAndNodePoints(junction, nodePoints);
                markJunctionAndNodePoints(junction, nodePoints, true);
              },
              closeCallback: function () {
                $(me).prop('checked', false);
              }
            });
          } else {
            selectedNodesAndJunctions.detachJunctionAndNodePoints(junction, undefined);
            markJunctionAndNodePoints(junction, undefined, true);
          }
        } else {
          new GenericConfirmPopup('Haluatko peruuttaa solmukohtien ja liittymän irrotuksen solmusta ?', {
            successCallback: function () {
              selectedNodesAndJunctions.attachJunctionAndNodePoints(junction, nodePoints);
              markJunctionAndNodePoints(junction, nodePoints, false);
            },
            closeCallback: function () {
              $(me).prop('checked', true);
            }
          });
        }
      });

      rootElement.on('click', '.btn-edit-node-save', function () {
        if (selectedNodesAndJunctions.isObsoleteNode()) {
          new GenericConfirmPopup('Tämä toiminto päättää solmun, tallennetaanko muutokset?', {
            successCallback: function () {
              selectedNodesAndJunctions.saveNode();
            }
          });
        } else {
          selectedNodesAndJunctions.saveNode();
        }
      });
      rootElement.on('click', '.btn-edit-node-cancel', function () {
        closeNode(true);
        window.location.hash = '#node';
      });

      rootElement.on('click', '#attachToMapNode', function () {
        applicationModel.setSelectedTool(ViiteEnumerations.Tool.Attach.value);
      });

      rootElement.on('click', '#attachToNewNode', function () {
        applicationModel.setSelectedTool(ViiteEnumerations.Tool.Add.value);
      });

      rootElement.on('click', '.btn-edit-templates-cancel', function () {
        selectedNodesAndJunctions.closeTemplates();
        window.location.hash = '#node';
      });

      rootElement.on('click', '#edit-junction-point-addresses', function () {
        showAddressInputs();
      });

      rootElement.on('input', '[id^=junction-number-textbox-]', function () {
        $(this).change();
      });

      rootElement.on('input', '[id=nodeName]', function () {
        $(this).change();
      });

      eventbus.on('templates:selected', function (templates) {
        rootElement.empty();
        if (!_.isEmpty(templates.nodePoints) || !_.isEmpty(templates.junctions)) {
          rootElement.html(templatesForm('Aihioiden tiedot:'));
          var nodePointsElement = $('#node-points-info-content');
          nodePointsElement.html(nodePointsTable.toHtmlTable({nodePointTemplates: templates.nodePoints}));
          var junctionsElement = $('#junctions-info-content');
          junctionsElement.html(junctionsTable.toHtmlTemplateTable(templates.junctions));
        }
      });

      eventbus.on('node:selected', function (currentNode, templates) {
        rootElement.empty();
        if (!_.isEmpty(currentNode)) {
          var nodePointTemplates = !_.isUndefined(templates) && _.has(templates, 'nodePoints') && templates.nodePoints;
          var junctionTemplates = !_.isUndefined(templates) && _.has(templates, 'junctions') && templates.junctions;
          rootElement.html(nodeForm('Solmun tiedot:', currentNode));
          addDatePicker($('#nodeStartDate'), currentNode.startDate || moment("1.1.1900", dateutil.FINNISH_DATE_FORMAT).toDate());
          disableAutoComplete(); // this code should broke on different browsers

          //  setting nodePoints on the form
          var nodePointsElement = $('#node-points-info-content');
          nodePointsElement.html(nodePointsTable.toHtmlTable({
            nodePointTemplates: nodePointTemplates,
            currentNodePoints: currentNode.nodePoints,
            options: {checkbox: _.isUndefined(templates), junctionInputNumber: true}
          }));
          selectedNodesAndJunctions.addNodePointTemplates(nodePointTemplates);

          //  setting junctions on the form
          var junctionsElement = $('#junctions-info-content');
          junctionsElement.html(junctionsTable.toHtmlTable({
            junctionTemplates: junctionTemplates,
            currentJunctions: _.sortBy(currentNode.junctions, 'junctionNumber'),
            options: {checkbox: _.isUndefined(templates), junctionInputNumber: true}
          }));

          // add the pen icon next to the address header so user can edit junction addresses
          if (userHasPermissionToEdit) {
            $('.junction-address-header').append('<i id="edit-junction-point-addresses" class="btn-pencil-edit fas fa-pencil-alt"></i>');
          }

          $('.btn-edit-node-save').prop('disabled', formIsInvalid());

          eventbus.on('node:displayCoordinates', function (coordinates) {
            $("#node-coordinates").text(coordinates.y + ', ' + coordinates.x);
          });

          eventbus.on('change:nodeName', function (nodeName) {
            selectedNodesAndJunctions.setNodeName(nodeName);
          });

          var checkDateNotification = function (nodeStartDate) {
            var nodeNotificationText = "";
            var parts_DMY=nodeStartDate.split('.');

            var nodeSD = new Date(parts_DMY[2], parts_DMY[1] - 1, parts_DMY[0]);
            var nowDate = new Date();
            if(nodeSD.getFullYear() < nowDate.getFullYear()-20) {
              nodeNotificationText = 'Vanha päiväys. Solmun alkupäivämäärä yli 20 vuotta historiassa. Varmista päivämäärän oikeellisuus ennen tallennusta.';
            }
            else if(nodeSD.getFullYear() > nowDate.getFullYear()+1){
              nodeNotificationText = 'Tulevaisuuden päiväys. Solmun alkupäivä yli vuoden verran tulevaisuudessa. Varmista päivämäärän oikeellisuus ennen tallennusta.';
            }
            return  '<div class="form-check-date-notifications"> ' +
                '  <p id="nodeStartDate-validation-notification">' + nodeNotificationText + '</p>' +
                '</div>';
          };

          eventbus.on('change:nodeStartDate', function (nodeStartDate) {
            $('#nodeStartDate-validation-notification').html(checkDateNotification(nodeStartDate));
          });

          eventbus.on('change:nodeTypeDropdown', function (nodeType) {
            var typeHasChanged = selectedNodesAndJunctions.typeHasChanged(parseInt(nodeType));
            selectedNodesAndJunctions.setNodeType(parseInt(nodeType));
            //  revert date picker to it's original value when node type is changed back
            if (!typeHasChanged) {
              selectedNodesAndJunctions.setStartDate(selectedNodesAndJunctions.getInitialStartDate());
              $("#nodeStartDate").val(selectedNodesAndJunctions.getInitialStartDate());
            }
            disabledDatePicker(!typeHasChanged);
          });

          eventbus.on('junction:validate', function () {
            selectedNodesAndJunctions.validateJunctionNumbers();
          });

          eventbus.on('junctionPoint:validate', function (idString, addr) {
            selectedNodesAndJunctions.validateJunctionPointAddress(idString, addr);
          });

          eventbus.on('change:node-coordinates change:nodeName change:nodeTypeDropdown change:nodeStartDate ' +
            'junction:validate junctionPoint:validate junction:detach nodePoint:detach junction:attach nodePoint:attach', function () {
            $('.btn-edit-node-save').prop('disabled', formIsInvalid());
          });

          eventbus.on('nodeStartDate:setCustomValidity', function (startDate, errorMessage) {
            document.getElementById('nodeStartDate').setCustomValidity(errorMessage);
            $('#nodeStartDate-validation-notification').html(errorMessage);
          });

          eventbus.on('junction:setCustomValidity', function (junctions, errorMessage) {
            _.each(junctions, function (junction) {
              document.getElementById('junction-number-textbox-' + junction.id).setCustomValidity(errorMessage);
            });
          });

          eventbus.on('junctionPoint:setCustomValidity', function (idString, errorMessage) {
            var input = document.getElementById('junction-point-address-input-' + idString);
            if (input) {
              input.setCustomValidity(errorMessage);
            }
            // trigger the validation message to user
            input.reportValidity();
            // check if the save button can be enabled (all the input fields of the form need to be valid)
            $('.btn-edit-node-save').prop('disabled', formIsInvalid());
          });

          selectedNodesAndJunctions.addJunctionTemplates(junctionTemplates);
        }
      });

      eventbus.on('reset:startDate', function (originalStartDate) {
        resetDatePicker(originalStartDate);
      });

      eventbus.on('nodeLayer:closeForm', function (current) {
        // templates should be handled here, by the time it's possible to change anything in templates form.
        if (!_.isUndefined(current) && !_.isUndefined(current.node)) {
          closeNode(true);
        }
      });

      eventbus.on('node:saveSuccess', function () {
        closeNode(false);
      });

      eventbus.on('node:saveFailed', function (errorMessage, spinnerEvent) {
        applicationModel.removeSpinner(spinnerEvent);
        new ModalConfirm(errorMessage);
      });
    };

    bindEvents();
  };
}(this));
