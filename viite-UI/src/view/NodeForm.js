(function (root) {
  root.NodeForm = function (selectedNodesAndJunctions) {
    var formCommon = new FormCommon('node-');
    var NodeType = LinkValues.NodeType;

    var NODE_POINTS_TITLE = 'Solmukohdat';
    var JUNCTIONS_TITLE = 'Liittymät';
    var picker;

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
        '<input type="text" class="form-control asset-input-node-data" id = "' + id + '"' + property + ' placeholder = "' + placeholder + '" value="' + value + '"/>' +
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
        staticField('Koordinaatit (<i>P</i>, <i>I</i>):', '<label id="node-coordinates">' + node.coordinates.y + ', ' + node.coordinates.x + '</label>') +
        inputFieldRequired('Solmun nimi', 'nodeName', '', nodeName, 'maxlength', 32) +
        addNodeTypeDropdown('Solmutyyppi', 'nodeTypeDropdown', getNodeType(node.type)) +
        inputFieldRequired('Alkupvm', 'nodeStartDate', 'pp.kk.vvvv', startDate, 'disabled', true) +
        '   </div>' +
        '   <div>' +
        '     <p class="node-info">' + NODE_POINTS_TITLE + '</p>' +
        '     <div id="node-points-info-content">' +
        '     </div>' +
        '     <p class="node-info">' + JUNCTIONS_TITLE + '<i id="editNodeJunctions" class="btn-pencil-edit fas fa-pencil-alt"> </i> </p>' +
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
      var headers = function(asResume) {
        var checkbox = '';
        if (!asResume) {
          checkbox += '<th class="node-junctions-table-header">' +
            ' <table class="node-junctions-table-dimension">' +
            '   <tr><th class="node-junctions-table-header">Irrota</th></tr>' +
            '   <tr><th class="node-junctions-table-header">liittymä</th></tr>' +
            '   <tr><th class="node-junctions-table-header">solmusta</th></tr>' +
            ' </table>' +
            '</th>';
        }
        return '<tr class="node-junctions-table-header-border-bottom">' +
          checkbox +
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

      var junctionIcon = function (junction) {
        var text = '';
        if (junction.junctionNumber) { text = ' <param name="number"  value="' + junction.junctionNumber + '"/></object>'; }
        if (!_.has(junction, 'nodeNumber') || !junction.nodeNumber) {
          return '<object type="image/svg+xml" id="junction-number-icon-' + junction.id + '" data="images/junction-template.svg">' + text;
        } else {
          return '<object type="image/svg+xml" id="junction-number-icon-' + junction.id + '" data="images/junction.svg">' + text;
        }
      };

      var toMessage = function (junctionsInfo) {
        return toHtmlTable({
          currentJunctions: junctionsInfo,
          options: {asResume: true}
        });
      };

      var toHtmlTable = function(data) {
        var asResume = _.has(data.options, 'asResume') && data.options.asResume;
        var htmlTable = "";
        htmlTable += '<table id="junctions-table-info" class="node-junctions-table-dimension">';
        htmlTable += headers(asResume);
        htmlTable += toHtmlRows(data.junctionTemplates, asResume, 'node-junctions-table template');
        htmlTable += toHtmlRows(data.currentJunctions, asResume, 'node-junctions-table');
        htmlTable += '</table>';
        return htmlTable;
      };

      var toHtmlRows = function (junctionsInfo, asResume, tableRowClass) {
        var junctionInfoHtml = function(junctionPointsInfo) {
          var roads = _.map(_.map(junctionPointsInfo, 'roadNumber'), function (roadNumber) {
            return '<tr><td class="' + tableRowClass + '">' + roadNumber + '</td></tr>';
          });

          var tracks = _.map(_.map(junctionPointsInfo, 'track'), function (track) {
            return '<tr><td class="' + tableRowClass + '">' + track + '</td></tr>';
          });

          var parts = _.map(_.map(junctionPointsInfo, 'roadPartNumber'), function (roadPartNumber) {
            return '<tr><td class="' + tableRowClass + '">' + roadPartNumber + '</td></tr>';
          });

          var addresses = _.map(_.map(junctionPointsInfo, 'addr'), function (addr) {
            return '<tr><td class="' + tableRowClass + '">' + addr + '</td></tr>';
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
          htmlTable += '<tr class="node-junctions-table-border-bottom">';
          if (!asResume) htmlTable += detachJunctionBox(junction);
          htmlTable += '<td>' + junctionIcon(junction) + '</td>';
          htmlTable += junctionInfoHtml(getJunctionPointsInfo(junction));
          htmlTable += '</tr>';
        });
        return htmlTable;
      };

      var getJunctionPointsInfo = function(junction) {
        var info = [];
        _.map(junction.junctionPoints, function(point){
          var row = {roadNumber: point.roadNumber, roadPartNumber: point.roadPartNumber, track: point.track, addr: point.addrM, beforeAfter: point.beforeAfter};
          info.push(row);
        });

        var groupedHomogeneousRows = _.groupBy(info, function (row) {
          return [row.roadNumber, row.track, row.roadPartNumber, row.addr];
        });

        var joinedHomogeneousRows = _.partition(groupedHomogeneousRows, function(group) {
          return group.length > 1;
        });

        var doubleHomogeneousRows = joinedHomogeneousRows[0];
        var singleHomogeneousRows = joinedHomogeneousRows[1];

        var doubleRows = _.map(doubleHomogeneousRows, function(point) {
          var first = _.head(point);
          return {roadNumber: first.roadNumber, track: first.track, roadPartNumber: first.roadPartNumber, addr: first.addr, beforeAfter: "EJ"};
        });

        var singleRows = _.map(singleHomogeneousRows, function(point) {
          var first = _.head(point);
          return {roadNumber: first.roadNumber, track: first.track, roadPartNumber: first.roadPartNumber, addr: first.addr, beforeAfter: (first.beforeAfter === 1 ? "E" : "J")};
        });

        return _.sortBy(doubleRows.concat(singleRows), ['roadNumber', 'roadPartNumber', 'track', 'addr', 'beforeAfter']);
      };

      return {
        toMessage: toMessage,
        junctionIcon: junctionIcon,
        toHtmlTable: toHtmlTable
      };
    };

    var NodePoints = function () {
      var toMessage = function (nodePointsInfo) {
        return toHtmlTable({
          currentNodePoints: nodePointsInfo,
          options: { asResume: true }
        });
      };

      var toHtmlTable = function(data) {
        var asResume = _.has(data.options, 'asResume') && data.options.asResume;
        var htmlTable = "";
        htmlTable += '<table id="nodePoints-table-info" class="node-points-table-dimension">';
        htmlTable += headers(asResume);
        htmlTable += toHtmlRows(data.nodePointTemplates, asResume, 'node-points-table template');
        htmlTable += toHtmlRows(data.currentNodePoints, asResume, 'node-points-table');
        htmlTable += '</table>';
        return htmlTable;
      };

      var toHtmlRows = function (nodePointsInfo, asResume, tableRowClass) {
        var nodePointInfoHtml = function(rowInfo) {
          return '<td class="' + tableRowClass + '">' + rowInfo.roadNumber + '</td>' +
            '<td class="' + tableRowClass + '">' + rowInfo.roadPartNumber + '</td>' +
            '<td class="' + tableRowClass + '">' + rowInfo.addr + '</td>' +
            '<td class="' + tableRowClass + '">' + rowInfo.beforeAfter + '</td>';
        };

        var rowsInfo = getNodePointsRowsInfo(nodePointsInfo);
        var htmlTable = '';
        _.each(_.sortBy(rowsInfo, ['roadNumber', 'roadPartNumber', 'addr']), function(row){
          htmlTable += '<tr class="node-junctions-table-border-bottom">';
          if (!asResume) htmlTable += detachNodePointBox(row);
          htmlTable += nodePointInfoHtml(row);
          htmlTable += '</tr>';
        });
        return htmlTable;
      };

      var detachNodePointBox = function(nodePoint) {
        var nodePointType = _.find(LinkValues.NodePointType, function (nodePointType) {
          return nodePointType.value === nodePoint.type;
        });
        var isDetachable = 'title="' + nodePointType.description + '"'; // added for testing purposes, needs to be confirm if this title is a good idea for production env.
        if (_.isEqual(nodePointType, LinkValues.NodePointType.CalculatedNodePoint)) {
          isDetachable += ' disabled hidden';
        }
        return '<td><input ' + isDetachable + ' type="checkbox" name="detach-node-point-' + nodePoint.id + '" value="' + nodePoint.id + '" id="detach-node-point-' + nodePoint.id + '"></td>';
      };

      var getNodePointsRowsInfo = function(nodePoints) {
        if (!_.isUndefined(nodePoints) && nodePoints.length > 0) {
          var nodePointsRows = [];
          _.map(nodePoints, function(point) {
            var row = {id: point.id, nodeNumber: point.nodeNumber, roadNumber: point.roadNumber, roadPartNumber: point.roadPartNumber, addr: point.addrM, beforeAfter: point.beforeAfter, type: point.type};
            nodePointsRows.push(row);
          });

          var groupedHomogeneousRows = _.groupBy(nodePointsRows, function (row) {
            return [row.roadNumber, row.roadPartNumber, row.addr];
          });

          var joinedHomogeneousRows = _.partition(groupedHomogeneousRows, function(group) {
            return group.length > 1;
          });

          var doubleHomogeneousRows = joinedHomogeneousRows[0];
          var singleHomogeneousRows = joinedHomogeneousRows[1];

          var doubleRows = _.map(doubleHomogeneousRows, function(drows) {
            var first = _.head(drows);
            return {id: first.id, nodeNumber: first.nodeNumber, roadNumber: first.roadNumber, roadPartNumber: first.roadPartNumber, addr: first.addr, beforeAfter: "EJ", type: first.type};
          });

          var singleRows = _.map(singleHomogeneousRows, function(drows) {
            var first = _.head(drows);
            return {id: first.id, nodeNumber: first.nodeNumber, roadNumber: first.roadNumber, roadPartNumber: first.roadPartNumber, addr: first.addr, beforeAfter: (first.beforeAfter === 1 ? "E" : "J"), type: first.type};
          });

          return _.sortBy(doubleRows.concat(singleRows), ['roadNumber', 'roadPartNumber', 'track', 'addr', 'beforeAfter']);
        } else return [];
      };

      var headers = function(asResume) {
        var checkbox = '';
        if (!asResume) {
          checkbox += '<th class="node-points-table-header">' +
            ' <table class="node-points-table-dimension">' +
            '   <tr><th class="node-points-table-header">Irrota</th></tr>' +
            '   <tr><th class="node-points-table-header">solmukohta</th></tr>' +
            ' </table>' +
            '</th>';
        }
        return '<tr class="node-junctions-table-header-border-bottom">' +
          checkbox +
          '<th class="node-points-table-header">TIE</th>' +
          '<th class="node-points-table-header">OSA</th>' +
          '<th class="node-points-table-header">ET</th>' +
          '<th class="node-points-table-header">EJ</th>' +
          '</tr>';
      };

      return {
        toMessage: toMessage,
        toHtmlTable: toHtmlTable
      };
    };

    var junctionsTable = new Junctions();
    var nodePointsTable = new NodePoints();

    var addDatePicker = function (fromElement, minDate) {
      picker = dateutil.addSingleDatePickerWithMinDate(fromElement, minDate);
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
      $('#nodeName').attr('autocomplete', 'false');
      $('#nodeStartDate').attr('autocomplete', 'false');
    };

    var formIsInvalid = function () {
      //  TODO remove this variable after Nodes and Junctions are imported.
      var saveBtnDisabled = Environment.name() === 'production';

      return $('#nodeName').val() === "" ||
        $('#nodeTypeDropdown').val() === LinkValues.NodeType.UnknownNodeType.value.toString() ||
        $('#nodeStartDate').val() === "" ||
        !selectedNodesAndJunctions.isDirty() || saveBtnDisabled;
    };

    var closeNode = function () {
      selectedNodesAndJunctions.closeNode();
      eventbus.off('change:nodeName, change:nodeTypeDropdown, change:nodeStartDate');
    };

    var toggleEditNodeJunctions = function (junctions, replace) {
      _.forEach(junctions, function (junction) {
        replace(junction);
      });
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      rootElement.on('change', '#nodeName, #nodeTypeDropdown, #nodeStartDate', function (event) {
        eventbus.trigger(event.type + ':' + event.target.id, $(this).val());
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
        var junction = _.find(selectedNodesAndJunctions.getJunctions(), function (junction) {
          return junction.id === junctionId;
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
            return nodePoint.type === LinkValues.NodePointType.RoadNodePoint.value || nodePoint.type === LinkValues.NodePointType.UnknownNodePointType.value;
          })
        };
      };

      var junctionAndNodePointsByNodePointCoordinates = function (nodePointId) {
        var nodePoints = selectedNodesAndJunctions.getNodePoints();
        var targetNodePoint = _.find(nodePoints, function (nodePoint) {
          return nodePoint.id === nodePointId;
        });

        var junction = _.find(selectedNodesAndJunctions.getJunctions(), function (junction) {
          var junctionPointsCoordinates = _.map(junction.junctionPoints, 'coordinates');

          return !_.isEmpty(_.intersectionWith(junctionPointsCoordinates, [targetNodePoint.coordinates], _.isEqual));
        });

        if (!_.isUndefined(junction)) {
          return junctionAndNodePointsByJunctionPointsCoordinates(junction.id);
        } else {
          return {
            nodePoints: _.filter(nodePoints, function (nodePoint) {
              return _.isEqual(nodePoint.coordinates, targetNodePoint.coordinates) &&
                (nodePoint.type === LinkValues.NodePointType.RoadNodePoint.value || nodePoint.type === LinkValues.NodePointType.UnknownNodePointType.value);
            })
          };
        }
      };

      var markJunctionAndNodePoints = function (junction, nodePoints, checked) {
        if (!_.isUndefined(junction)) {
          $('[id^="detach-junction-' + junction.id + '"]').prop('checked', checked);
        }
        _.each(nodePoints, function (nodePoint) {
          $('[id^="detach-node-point-' + nodePoint.id + '"]').prop('checked', checked);
        });
      };

      rootElement.on('change', '[id^="detach-node-point-"]', function () {
        var checkbox = this;
        var nodePointId = parseInt(checkbox.value);
        var match = junctionAndNodePointsByNodePointCoordinates(nodePointId);
        var junction = match.junction;
        var nodePoints = match.nodePoints;
        if (checkbox.checked) {
          if (!_.isEmpty(junction) || nodePoints.length > 1) {
            new GenericConfirmPopup(buildMessage(junction, match.nodePoints), {
              successCallback: function () {
                selectedNodesAndJunctions.detachJunctionAndNodePoints(junction, nodePoints);
                markJunctionAndNodePoints(junction, nodePoints, true);
              },
              closeCallback: function () {
                $(checkbox).prop('checked', false);
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
              $(checkbox).prop('checked', true);
            }
          });
        }
      });

      rootElement.on('change', '[id^="detach-junction-"]', function () {
        var checkbox = this;
        var junctionId = parseInt(checkbox.value);
        var match = junctionAndNodePointsByJunctionPointsCoordinates(junctionId);
        var junction = match.junction;
        var nodePoints = match.nodePoints;
        if (checkbox.checked) {
          if (!_.isEmpty(nodePoints)) {
            new GenericConfirmPopup(buildMessage(junction, match.nodePoints), {
              successCallback: function () {
                selectedNodesAndJunctions.detachJunctionAndNodePoints(junction, nodePoints);
                markJunctionAndNodePoints(junction, nodePoints, true);
              },
              closeCallback: function () {
                $(checkbox).prop('checked', false);
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
              $(checkbox).prop('checked', true);
            }
          });
        }
      });

      rootElement.on('click', '#editNodeJunctions', function () {
        var junctions = selectedNodesAndJunctions.getCurrentNode().junctions;
        var editMode = _.isEmpty($('#junction-number-textbox-' + _.first(junctions).id));
        if (editMode) {
          toggleEditNodeJunctions(junctions, function (junction) {
            $('#junction-number-icon-' + junction.id).replaceWith(formCommon.nodeInputNumber('junction-number-textbox-' + junction.id, 2, junction.junctionNumber || '', 'text-align: center; width: 20px;'));
          });
        } else {
          toggleEditNodeJunctions(junctions, function (junction) {
            var element = $('#junction-number-textbox-' + junction.id);
            junction.junctionNumber = element.val() && parseInt(element.val());
            element.replaceWith(junctionsTable.junctionIcon(junction));
          });
        }
        $('.btn-edit-node-save').prop('disabled', !editMode || formIsInvalid());
      });

      rootElement.on('click', '.btn-edit-node-save', function () {
        var node = selectedNodesAndJunctions.getCurrentNode();
        eventbus.trigger('node:repositionNode', node, node.coordinates);
        if(selectedNodesAndJunctions.isObsoleteNode(node)){
        new GenericConfirmPopup('Tämä toiminto päättää solmun, tallennetaanko muutokset?', {
          successCallback: function () {
            selectedNodesAndJunctions.saveNode();
          },
        });
        } else {
          selectedNodesAndJunctions.saveNode();
        }
      });

      rootElement.on('click', '.btn-edit-node-cancel', function () {
        closeNode();
      });

      rootElement.on('click', '#attachToMapNode', function () {
        applicationModel.setSelectedTool(LinkValues.Tool.Attach.value);
      });

      rootElement.on('click', '#attachToNewNode', function () {
        applicationModel.setSelectedTool(LinkValues.Tool.Add.value);
      });

      rootElement.on('click', '.btn-edit-templates-cancel', function () {
        selectedNodesAndJunctions.closeTemplates();
      });

      eventbus.on('templates:selected', function (templates) {
        rootElement.empty();
        if (!_.isEmpty(templates.nodePoints) || !_.isEmpty(templates.junctions)) {
          var options = { asResume: true };
          rootElement.html(templatesForm('Aihioiden tiedot:'));
          var nodePointsElement = $('#node-points-info-content');
          nodePointsElement.html(nodePointsTable.toHtmlTable({
            nodePointTemplates: templates.nodePoints,
            options: options
          }));
          var junctionsElement = $('#junctions-info-content');
          junctionsElement.html(junctionsTable.toHtmlTable({
            junctionTemplates: templates.junctions,
            options: options
          }));
        }
        applicationModel.removeSpinner();
      });

      eventbus.on('node:selected', function (currentNode, templates) {
        rootElement.empty();
        if (!_.isEmpty(currentNode)) {
          var options = { asResume: !_.isUndefined(templates) && (templates.nodePoints.length > 0 || templates.junctions.length > 0) };
          var nodePointTemplates = !_.isUndefined(templates) && _.has(templates, 'nodePoints') && templates.nodePoints;
          var junctionTemplates = !_.isUndefined(templates) && _.has(templates, 'junctions') && templates.junctions;
          rootElement.html(nodeForm('Solmun tiedot:', currentNode));
          addDatePicker($('#nodeStartDate'), currentNode.startDate || moment("1.1.2000", dateutil.FINNISH_DATE_FORMAT).toDate());
          disableAutoComplete(); // this code should broke on different browsers

          //  setting nodePoints on the form
          var nodePointsElement = $('#node-points-info-content');
          nodePointsElement.html(nodePointsTable.toHtmlTable({
            currentNodePoints: currentNode.nodePoints,
            nodePointTemplates: nodePointTemplates,
            options: options
          }));
          selectedNodesAndJunctions.addNodePointTemplates(nodePointTemplates);

          //  setting junctions on the form
          var junctionsElement = $('#junctions-info-content');
          junctionsElement.html(junctionsTable.toHtmlTable({
              currentJunctions: _.sortBy(currentNode.junctions, 'junctionNumber'),
              junctionTemplates: junctionTemplates,
              options: options
            }));
          selectedNodesAndJunctions.addJunctionTemplates(junctionTemplates);

          $('.btn-edit-node-save').prop('disabled', formIsInvalid());

          eventbus.on('node:setCoordinates', function (coordinates) {
            $("#node-coordinates").text(coordinates.x + ', ' + coordinates.y);
          });

          eventbus.on('change:nodeName', function (nodeName) {
            selectedNodesAndJunctions.setNodeName(nodeName);
          });

          eventbus.on('change:nodeTypeDropdown', function (nodeType) {
            var typeHasChanged = selectedNodesAndJunctions.typeHasChanged(parseInt(nodeType));
            selectedNodesAndJunctions.setNodeType(parseInt(nodeType));
            //  revert date picker to it's original value when node type is changed back
            if(!typeHasChanged) {
              selectedNodesAndJunctions.setStartDate(selectedNodesAndJunctions.getInitialStartDate());
              $("#nodeStartDate").val(selectedNodesAndJunctions.getInitialStartDate());
            }
            disabledDatePicker(!typeHasChanged);
          });

          eventbus.on('change:node-coordinates change:nodeName change:nodeTypeDropdown change:nodeStartDate ' +
                      'junction:detach nodePoint:detach junction:attach nodePoint:attach', function () {
            $('.btn-edit-node-save').prop('disabled', formIsInvalid());
          });
        }
      });

      eventbus.on('reset:startDate', function (originalStartDate) {
        resetDatePicker(originalStartDate);
      });

      eventbus.on('nodeLayer:closeForm', function (current) {
        if (!_.isUndefined(current) && !_.isUndefined(current.node)) {
          closeNode();
        }
      });

      eventbus.on('node:saveSuccess', function () {
        applicationModel.removeSpinner();
        closeNode();
      });

      eventbus.on('node:saveFailed', function (errorMessage) {
        applicationModel.removeSpinner();
        new ModalConfirm(errorMessage);
      });
    };

    bindEvents();
  };
})(this);
