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
          ' <button id="attachToMapNode" class="btn btn-block btn-attach-node">Valitse kartatta solmu, johon haluat liittää aihiot</button>' +
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

      var junctionIcon = function (junction, asTemplate) {
        if (asTemplate) {
          return '<object type="image/svg+xml" id="junction-number-icon-' + junction.id + '" data="images/junction-template.svg">';
        }
        if (_.isUndefined(junction.junctionNumber)) {
          return '<object type="image/svg+xml" id="junction-number-icon-' + junction.id + '" data="images/junction.svg">';
        } else {
          return '<object type="image/svg+xml" id="junction-number-icon-' + junction.id + '" data="images/junction.svg">' +
            ' <param name="number"  value="' + junction.junctionNumber + '"/></object>';
        }
      };

      var junctionInfoHtml = function(junctionPointsInfo) {
        var roads = _.map(_.map(junctionPointsInfo, 'roadNumber'), function (roadNumber) {
          return '<tr><td class="node-junctions-table">' + roadNumber + '</td></tr>';
        });

        var tracks = _.map(_.map(junctionPointsInfo, 'track'), function (track) {
          return '<tr><td class="node-junctions-table">' + track + '</td></tr>';
        });

        var parts = _.map(_.map(junctionPointsInfo, 'roadPartNumber'), function (roadPartNumber) {
          return '<tr><td class="node-junctions-table">' + roadPartNumber + '</td></tr>';
        });

        var addresses = _.map(_.map(junctionPointsInfo, 'addr'), function (addr) {
          return '<tr><td class="node-junctions-table">' + addr + '</td></tr>';
        });

        var beforeOrAfter = _.map(_.map(junctionPointsInfo, 'beforeAfter'), function (beforeAfter) {
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

      var toMessage = function (junctionsInfo) {
        return toHtmlTable(junctionsInfo, {message: true});
      };

      var toHtmlTable = function(junctionsInfo, asMessageOrTemplate) {
        var asMessage = _.has(asMessageOrTemplate, 'message') && asMessageOrTemplate.message;
        var asTemplate = _.has(asMessageOrTemplate, 'template') && asMessageOrTemplate.template;
        var asResume = asMessage || asTemplate;
        var htmlTable = "";
        htmlTable += '<table class="node-junctions-table-dimension">';
        htmlTable += headers(asResume);
        _.each(junctionsInfo, function (junction) {
          htmlTable += '<tr class="node-junctions-table-border-bottom">';
          if (asResume) {
            htmlTable += '<td>' + junctionIcon(junction, asTemplate) + '</td>';
          } else {
            htmlTable += detachJunctionBox(junction);
            htmlTable += '<td>' + junctionIcon(junction) + '</td>';
          }
          htmlTable += junctionInfoHtml(getJunctionPointsInfo(junction));
          htmlTable += '</tr>';
        });
        htmlTable += '</table>';
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
        toHtmlTable: toHtmlTable,
        junctionIcon: junctionIcon
      };
    };

    var NodePoints = function () {
      var toMessage = function (nodePointsInfo) {
        return toHtmlTable(nodePointsInfo, {message: true});
      };

      var toHtmlTable = function(nodePointsInfo, asMessageOrTemplate) {
        var asResume = _.has(asMessageOrTemplate, 'message') && asMessageOrTemplate.message || _.has(asMessageOrTemplate, 'template') && asMessageOrTemplate.template;
        var htmlTable = "";
        htmlTable += '<table class="node-points-table-dimension">';
        htmlTable += headers(asResume);
        var rowsInfo = getNodePointsRowsInfo(nodePointsInfo);
        _.each(_.sortBy(rowsInfo, ['roadNumber', 'roadPartNumber', 'addr']), function(row){
          htmlTable += '<tr class="node-junctions-table-border-bottom">';
          if (!asResume) htmlTable += detachNodePointBox(row);
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
        return '<td class="node-points-table">' + rowInfo.roadNumber + '</td>' +
          '<td class="node-points-table">' + rowInfo.roadPartNumber + '</td>' +
          '<td class="node-points-table">' + rowInfo.addr + '</td>' +
          '<td class="node-points-table">' + rowInfo.beforeAfter + '</td>';
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

          return doubleRows.concat(singleRows);
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

    var nodeChangeHandler = function () {
      var textIsEmpty = $('#nodeName').val() === "";
      var nodeTypeInvalid = $('#nodeTypeDropdown :selected').val() === LinkValues.NodeType.UnknownNodeType.value.toString();
      var startDateIsEmpty = $('#nodeStartDate').val() === "";

      if (textIsEmpty || nodeTypeInvalid || startDateIsEmpty) {
        $('.btn-edit-node-save').prop('disabled', true);
      } else {
        $('.btn-edit-node-save').prop('disabled', !selectedNodesAndJunctions.isDirty());
      }
    };

    var closeNode = function () {
      selectedNodesAndJunctions.closeNode();
      eventbus.off('change:nodeName, change:nodeTypeDropdown, change:nodeStartDate');
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      rootElement.on('change', '#nodeName, #nodeTypeDropdown, #nodeStartDate', function (event) {
        eventbus.trigger(event.type + ':' + event.target.id, $(this).val());
      });

      var buildMessage = function (junction, nodePoints) {
        return 'Haluatko varmasti irrottaa solmukohdat ja liittymän solmusta ?' +
          '<p class="node-info">Solmukohdat :</p>' +
          nodePointsTable.toMessage(nodePoints) +
          '<p class="node-info">Liittyma :</p>' +
          junctionsTable.toMessage([junction]);
      };

      var junctionAndNodePointsByJunctionPointsCoordinates = function (node, junctionId) {
        var junction = _.find(_.concat(node.junctions, node.junctionsToDetach), function (junction) {
          return junction.id === junctionId;
        });

        var nodePoints = _.filter(_.concat(node.nodePoints, node.nodePointsToDetach), function (nodePoint) {
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

      var junctionAndNodePointsByNodePointCoordinates = function (node, nodePointId) {
        var nodePoint = _.find(_.concat(node.nodePoints, node.nodePointsToDetach), function (nodePoint) {
          return nodePoint.id === nodePointId;
        });

        var junction = _.find(_.concat(node.junctions, node.junctionsToDetach), function (junction) {
          var junctionPointsCoordinates = _.map(junction.junctionPoints, function (junctionPoint) {
            return {x: junctionPoint.x, y: junctionPoint.y};
          });

          return !_.isEmpty(_.intersectionWith(junctionPointsCoordinates, [{x: nodePoint.x, y: nodePoint.y}], _.isEqual));
        });

        if (!_.isUndefined(junction)) { return junctionAndNodePointsByJunctionPointsCoordinates(node, junction.id); }
        else if (nodePoint.type === LinkValues.NodePointType.RoadNodePoint.value || nodePoint.type === LinkValues.NodePointType.UnknownNodePointType.value) {
          return {
            nodePoints: [nodePoint]
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
        var match = junctionAndNodePointsByNodePointCoordinates(selectedNodesAndJunctions.getCurrentNode(), nodePointId);
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
            selectedNodesAndJunctions.detachJunctionAndNodePoints(null, nodePoints);
            markJunctionAndNodePoints(null, nodePoints, true);
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
        var match = junctionAndNodePointsByJunctionPointsCoordinates(selectedNodesAndJunctions.getCurrentNode(), junctionId);
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
            selectedNodesAndJunctions.detachJunctionAndNodePoints(junction);
            markJunctionAndNodePoints(junction, null, true);
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
        _.forEach(selectedNodesAndJunctions.getCurrentNode().junctions, function(junction) {
          var element = $('#junction-number-textbox-' + junction.id);
          //verify editing mode (on/off) by the current element being presented. On(#junction-number-textbox)/Off(#junction-number-icon)
          if(_.isEmpty(element)){
            //repaint junction icons to junction text boxes.
            $('#junction-number-icon-' + junction.id).replaceWith(formCommon.nodeInputNumber('junction-number-textbox-' + junction.id, 2, junction.junctionNumber, 'width:20px;'));
          } else {
            //replace junction.junctionNumber
            junction.junctionNumber = _.isEmpty(element.val()) ? 0 : parseInt(element.val());
            //repaint junction text boxes to junction icons
            element.replaceWith(junctionsTable.junctionIcon(junction));
          }
        });
      });

      rootElement.on('click', '.btn-edit-node-save', function () {
        var node = selectedNodesAndJunctions.getCurrentNode();
        eventbus.trigger('node:repositionNode', node, [node.coordinates.x, node.coordinates.y]);
        selectedNodesAndJunctions.saveNode();
      });

      rootElement.on('click', '.btn-edit-node-cancel', function () {
        selectedNodesAndJunctions.revertFormChanges();
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
        if (!_.isEmpty(templates.nodePoints) || !_.isUndefined(templates.junction)) {
          rootElement.html(templatesForm('Aihioiden tiedot:'));
          var nodePointsElement = $('#node-points-info-content');
          nodePointsElement.html(nodePointsTable.toHtmlTable(templates.nodePoints, {template: true}));
          var junctionsElement = $('#junctions-info-content');
          junctionsElement.html(junctionsTable.toHtmlTable(templates.junction, {template: true}));
        }
      });

      eventbus.on('node:selected', function (currentNode) {
        rootElement.empty();
        if (!_.isEmpty(currentNode)) {
          rootElement.html(nodeForm('Solmun tiedot:', currentNode));
          addDatePicker($('#nodeStartDate'), currentNode.oldStartDate || currentNode.startDate || moment("1.1.2000", dateutil.FINNISH_DATE_FORMAT).toDate());
          var nodePointsElement = $('#node-points-info-content');
          nodePointsElement.html(nodePointsTable.toHtmlTable(currentNode.nodePoints));
          var junctionsElement = $('#junctions-info-content');
          junctionsElement.html(junctionsTable.toHtmlTable(_.sortBy(currentNode.junctions, 'junctionNumber')));

          eventbus.on('node:setCoordinates', function (coordinates) {
            $("#node-coordinates").text(coordinates[1] + ', ' + coordinates[0]);
          });

          eventbus.on('change:nodeName', function (nodeName) {
            selectedNodesAndJunctions.setNodeName(nodeName);
          });

          eventbus.on('change:nodeTypeDropdown', function (nodeType) {
            selectedNodesAndJunctions.setNodeType(parseInt(nodeType));
            disabledDatePicker(!selectedNodesAndJunctions.typeHasChanged());
          });

          eventbus.on('change:node-coordinates change:nodeName change:nodeTypeDropdown change:nodeStartDate junction:detach nodePoint:detach', function () {
            nodeChangeHandler();
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
