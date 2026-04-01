(function (root) {
  /**
   * NodeEditor - Editable node form with detach, validation and save flows.
   * Supports editing node metadata, junction numbers and junction ET values.
   */
  root.NodeEditor = function (selectedNodesAndJunctions, dataTable, startupParameters, backend, roadCollection, containerElement) {
    const nodeEditingDisabledAttribute = _.includes(startupParameters.roles, 'viite') ? '' : 'disabled';
    const userHasPermissionToEdit = _.includes(startupParameters.roles, 'viite');
    const tableUtils = root.NodeMenuTableUtils;

    let picker;
    let editorExitHandler = _.noop;
    let activeEventbusHandlers = [];
    let addressEditMode = false;

    const renderDataTable = function (props) {
      return dataTable.setProps(props).render();
    };

    const getContainer = function () {
      if (_.isFunction(containerElement)) {
        const resolved = containerElement();
        return resolved ? $(resolved) : $('#menu-container');
      }
      return containerElement ? $(containerElement) : $('#menu-container');
    };

    const getNodeType = function (nodeValue) {
      const nodeType = _.find(ViiteEnumerations.NodeType, function (type) {
        return type.value === nodeValue;
      });
      return _.isUndefined(nodeType) ? ViiteEnumerations.NodeType.UnknownNodeType : nodeType;
    };

    const staticField = function (labelText, dataField) {
      return `<div class="node-editor-field-row"><label>${labelText}</label>${dataField}</div>`;
    };

    const inputFieldRequired = function (labelText, id, placeholder, value, propName, propValue) {
      let property = '';
      if (!_.isUndefined(propName) && !_.isUndefined(propValue)) {
        property = `${propName}="${propValue}"`;
      }
      return `
        <div class="node-editor-field-row">
          <label class="required node-editor-field-label">${labelText}</label>
          <input type="text" class="form-control asset-input-node-data node-editor-field-control" id="${id}" ${property} placeholder="${placeholder}" value="${value}" ${nodeEditingDisabledAttribute}/>
        </div>
      `;
    };

    const addNodeTypeDropdown = function (labelText, id, nodeType) {
      const options = _.map(_.filter(ViiteEnumerations.NodeType, function (nodeTypeFiltered) {
        return nodeTypeFiltered !== ViiteEnumerations.NodeType.UnknownNodeType;
      }), function (nodeTypeMapped) {
        const selected = _.isEqual(nodeTypeMapped, nodeType) ? 'selected' : '';
        return `<option value="${nodeTypeMapped.value}" ${selected}>${nodeTypeMapped.value} ${nodeTypeMapped.description}</option>`;
      }).join('');

      const unknownNodeType = nodeType === ViiteEnumerations.NodeType.UnknownNodeType ?
        `<option value="${nodeType.value}" selected disabled hidden>${nodeType.value} ${nodeType.description}</option>` : '';

      return `
          <div class="node-editor-field-row">
            <label class="required node-editor-field-label">${labelText}</label>
            <select type="text" class="form-control asset-input-node-data node-editor-field-control" id="${id}" ${nodeEditingDisabledAttribute}>
              ${unknownNodeType}
              ${options}
            </select>
          </div>
      `;
    };

    const renderNodeForm = function (node) {
      const nodeNumber = node.nodeNumber ? node.nodeNumber : '-';
      const nodeName = node.name ? node.name : '';
      const startDate = node.startDate ? node.startDate : '';

      return `
        <div class="wrapper form-dark">
          <div class="node-metadata-container">
            ${staticField('Solmunumero:', nodeNumber)}
            ${staticField('Koordinaatit (P, I):',`<span id="node-coordinates">${Math.round(node.coordinates.y)}, ${Math.round(node.coordinates.x)}</span>`)}
            ${inputFieldRequired('Solmun nimi', 'nodeName', '', nodeName, 'maxlength', 30)}
            ${addNodeTypeDropdown('Solmutyyppi', 'nodeTypeDropdown', getNodeType(node.type))}
            ${inputFieldRequired('Alkupvm', 'nodeStartDate', 'pp.kk.vvvv', startDate, 'disabled', true)}
            <div class="form-check-date-notifications">
              <p id="nodeStartDate-validation-notification"></p>
            </div>
          </div>
          <div>
            <div id="junctions-info-content"></div>
            <div id="node-points-info-content"></div>
          </div>
        </div>
      `;
    };

    const renderFooter = function () {
      return `
        <div class="node-editor-footer">
          <button class="save btn-primary btn-block btn-edit-node-save" disabled>Tallenna</button>
          <button class="cancel btn-secondary btn-block btn-edit-node-cancel">Peruuta</button>
        </div>
      `;
    };

    const setAddressEditMode = function (enabled) {
      addressEditMode = enabled;
      const $container = getContainer();
      $container.find('.junction-point-address-label-editable').toggle(!enabled);
      $container.find('.junction-point-address-input').toggle(enabled);
      $container.find('#edit-junction-point-addresses').toggleClass('active', enabled);
    };

    const getAddressLimitsFromNeighbouringLinks = function (addr, junctionPoint) {
      const neighbouringRoadLinks = _.map(roadCollection.getByRoadPartAndAddr(junctionPoint.roadNumber, junctionPoint.roadPartNumber, junctionPoint.addr), function (roadLink) {
        return roadLink.getData();
      });

      let minAddrByRoadLink = '';
      let maxAddrByRoadLink = '';

      _.each(neighbouringRoadLinks, function (roadLink) {
        if (roadLink.addrMRange.end === addr) {
          minAddrByRoadLink = roadLink.addrMRange.start + 1;
        } else if (roadLink.addrMRange.start === addr) {
          maxAddrByRoadLink = roadLink.addrMRange.end - 1;
        }
      });

      return { minAddrByRoadLink: minAddrByRoadLink, maxAddrByRoadLink: maxAddrByRoadLink };
    };

    const getAllowedAddrEditRange = function (junctionPoint) {
      const addr = junctionPoint.addr;
      let maxAddr = junctionPoint.addr + 9;
      let minAddr = junctionPoint.addr - 9;

      const range = getAddressLimitsFromNeighbouringLinks(addr, junctionPoint);
      if (range.minAddrByRoadLink > minAddr) {
        minAddr = range.minAddrByRoadLink;
      }
      if (range.maxAddrByRoadLink < maxAddr) {
        maxAddr = range.maxAddrByRoadLink;
      }

      return { minAddr: minAddr, maxAddr: maxAddr };
    };

    const junctionPointAddressInput = function (junctionPoint) {
      const range = getAllowedAddrEditRange(junctionPoint);
      return `
        <input
          disabled="true"
          type="number"
          class="form-control junction-point-address-input"
          id="junction-point-address-input-${junctionPoint.id}"
          junctionPointId="${junctionPoint.id}"
          maxlength="5"
          value="${junctionPoint.addr}"
          min="${range.minAddr}"
          max="${range.maxAddr}"/>
      `;
    };

    const requestJunctionPointEditableStatus = function ($container) {
      $container.find('[id^=junction-point-address-input-]').each(function () {
        const junctionPointId = $(this).attr('junctionPointId');
        backend.getJunctionPointEditableStatus(junctionPointId, { id: junctionPointId });
      });
    };

    const addDatePicker = function (fromElement, minDate) {
      picker = new root.DatePicker({
        id: fromElement.attr('id'),
        minDate: minDate,
        onChange: function (value) {
          selectedNodesAndJunctions.setStartDate(value);
        }
      });
      picker.addToElement(fromElement);
      fromElement.on('input.nodeEditor', function () {
        $(this).change();
      });
    };

    const resetDatePicker = function (originalStartDate) {
      if (!picker) {
        return;
      }
      picker.setDate(originalStartDate);
      picker.gotoToday();
    };

    const disabledDatePicker = function (isDisabled) {
      getContainer().find('#nodeStartDate').prop('disabled', isDisabled);
    };

    const disableAutoComplete = function () {
      const $container = getContainer();
      $container.find('[id=nodeName]').attr('autocomplete', 'false');
      $container.find('[id=nodeStartDate]').attr('autocomplete', 'false');
    };

    const formIsInvalid = function () {
      const $container = getContainer();
      let junctionInputs = [];
      $container.find('#junctions-table-info').each(function () {
        junctionInputs = $(this).find(':input').get();
      });

      return $container.find('#nodeName').val() === '' ||
        $container.find('#nodeTypeDropdown').val() === ViiteEnumerations.NodeType.UnknownNodeType.value.toString() ||
        $container.find('#nodeStartDate').val() === '' ||
        !selectedNodesAndJunctions.validateJunctionNumbers() ||
        !selectedNodesAndJunctions.isDirty() ||
        !junctionInputs.every(function (input) { return input.validity.valid; });
    };

    const clearEventbusHandlers = function () {
      _.each(activeEventbusHandlers, function (handlerInfo) {
        eventbus.off(handlerInfo.eventName, handlerInfo.callback);
      });
      activeEventbusHandlers = [];
    };

    const subscribeEventbus = function (eventName, callback) {
      eventbus.on(eventName, callback);
      activeEventbusHandlers.push({ eventName: eventName, callback: callback });
    };

    const cleanup = function () {
      const rootElement = getContainer();
      rootElement.off('.nodeEditor');
      $('#menu-container').off('.nodeEditorFooter');
      clearEventbusHandlers();
    };

    const setSaveButtonDisabled = function (disabled) {
      const rootElement = getContainer();
      rootElement.find('.btn-edit-node-save').prop('disabled', disabled);
      $('#menu-container').find('.menu-footer .btn-edit-node-save').prop('disabled', disabled);
    };

    const detachNodePointBox = function (nodePoint, options) {
      const nodePointType = _.find(ViiteEnumerations.NodePointType, function (nodePointTypeFound) {
        return nodePointTypeFound.value === nodePoint.type;
      });

      let isDetachable = 'title="' + nodePointType.description + '"';
      if (_.isEqual(nodePointType, ViiteEnumerations.NodePointType.CalculatedNodePoint)) {
        isDetachable += ' disabled hidden';
      }

      const disabledAttribute = _.get(options, 'disabledAttribute', '');

      return '<input ' + isDetachable + ' type="checkbox" name="detach-node-point-' + nodePoint.id + '" value="' + nodePoint.id +
        '" id="detach-node-point-' + nodePoint.id + '"' + disabledAttribute + '>';
    };

    const detachJunctionBox = function (junction, options) {
      const disabledAttribute = _.get(options, 'disabledAttribute', '');
      return '<input type="checkbox" name="detach-junction-' + junction.id + '" value="' + junction.id + '" id="detach-junction-' +
        junction.id + '" data-junction-number=" ' + junction.junctionNumber + ' " ' + disabledAttribute + '>';
    };

    const junctionInputNumber = function (junction, options) {
      const disabledAttribute = _.get(options, 'disabledAttribute', '');
      return '<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode === 8 || event.keyCode === 9)" class="form-control junction-number-input" id="junction-number-textbox-' +
        junction.id + '" junctionId="' + junction.id + '" maxlength="2" value="' + (junction.junctionNumber || '') + '" ' + disabledAttribute + '/>';
    };

    const junctionAddressCells = function (junctionPointsInfo, options) {
      return _.map(junctionPointsInfo, function (junctionPoint) {
        const addressInputRenderer = _.get(options, 'junctionPointAddressInputRenderer');
        const isEditableAddress = junctionPoint.beforeAfter === 'EJ' && _.isFunction(addressInputRenderer);
        const addressInput = isEditableAddress
          ? addressInputRenderer(junctionPoint)
          : '';
        const labelClassName = isEditableAddress
          ? 'junction-point-address-label junction-point-address-label-editable'
          : 'junction-point-address-label';

        return `
          <span class="${labelClassName}">
            ${junctionPoint.addr}
          </span>${addressInput}`;
      }).join('');
    };

    const toNodePointsRows = function (nodePointsInfo, options, _isTemplate) {
      return _.map(_.sortBy(tableUtils.getNodePointsRowsInfo(nodePointsInfo), ['roadNumber', 'roadPartNumber', 'addr']), function (row) {
        const cells = [];

        if (options && options.checkbox) {
          cells.push({ className: 'detach-column-left', content: detachNodePointBox(row, options) });
        }

        cells.push({content: row.roadNumber });
        cells.push({ content: row.roadPartNumber });
        cells.push({ content: row.addr });
        cells.push({ content: row.beforeAfter });

        return {
          className: 'node-point-template-static-row',
          cells: cells
        };
      });
    };

    const toJunctionRows = function (junctionsInfo, options, _isTemplate) {
      return _.map(junctionsInfo || [], function (junction) {
        const junctionPointsInfo = tableUtils.getJunctionPointsInfo(junction);
        const cells = [];

        if (options && options.checkbox) {
          cells.push({ className: 'detach-column-left', content: detachJunctionBox(junction, options) });
        }
        if (options && options.junctionInputNumber) {
          cells.push({ content: junctionInputNumber(junction, options) });
        }

        cells.push({ content: tableUtils.asFlexColumn(_.map(junctionPointsInfo, 'roadNumber')) });
        cells.push({ content: tableUtils.asFlexColumn(_.map(junctionPointsInfo, 'track')) });
        cells.push({ content: tableUtils.asFlexColumn(_.map(junctionPointsInfo, 'roadPartNumber')) });
        cells.push({ content: '<div class="node-flex-column">' + junctionAddressCells(junctionPointsInfo, options) + '</div>' });
        cells.push({ content: tableUtils.asFlexColumn(_.map(junctionPointsInfo, 'beforeAfter')) });

        return {
          className: 'junction-template-static-row node-junctions-table-border-bottom',
          cells: cells
        };
      });
    };

    const buildNodePointsTable = function (data) {
      const options = data.options || {};
      const columns = [];
      if (options.checkbox) {
        columns.push({
          label: 'Irrota<br>solmukohta',
          className: 'detach-column-left'
        });
      }

      columns.push({ label: 'TIE'});
      columns.push({ label: 'OSA'});
      columns.push({ label: 'ET'});
      columns.push({ label: 'EJ'});

      return {
        title: data.title || '',
        tableId: 'nodePoints-table-info',
        headers: columns,
        rows: toNodePointsRows(data.nodePointTemplates, options, true)
          .concat(toNodePointsRows(data.currentNodePoints, options, false))
      };
    };

    const buildJunctionsTable = function (data) {
      const options = data.options || {};
      const columns = [];
      if (options.checkbox) {
        columns.push({
          label: 'Irrota<br>liittymä<br>solmusta',
          className: 'detach-column-left'
        });
      }
      if (options.junctionInputNumber) {
        columns.push({ label: 'NRO'});
      }

      columns.push({ label: 'TIE'});
      columns.push({ label: 'AJR'});
      columns.push({ label: 'OSA'});
      columns.push({ label: 'ET', className: ' junction-address-header' });
      columns.push({ label: 'EJ'});

      return {
        title: data.title || '',
        tableId: 'junctions-table-info',
        headers: columns,
        rows: toJunctionRows(data.junctionTemplates, options, true)
          .concat(toJunctionRows(data.currentJunctions, options, false))
      };
    };

    const buildMessage = function (junction, nodePoints) {
      let nodePointsHtmlTable = '';
      if (!_.isUndefined(nodePoints)) {
        nodePointsHtmlTable = renderDataTable(buildNodePointsTable({
          title: 'Solmukohdat',
          currentNodePoints: nodePoints
        }));
      }

      let junctionHtmlTable = '';
      if (!_.isUndefined(junction)) {
        junctionHtmlTable = renderDataTable(buildJunctionsTable({
          title: 'Liittymät',
          currentJunctions: [junction]
        }));
      }

      return `Haluatko varmasti irrottaa solmukohdat ja liittymän solmusta?${nodePointsHtmlTable}${junctionHtmlTable}`;
    };

    const junctionAndNodePointsByJunctionPointsCoordinates = function (junctionId) {
      const junction = _.find(selectedNodesAndJunctions.getJunctions(), function (junctionFound) {
        return junctionFound.id === junctionId;
      });

      const nodePoints = _.filter(selectedNodesAndJunctions.getNodePoints(), function (nodePoint) {
        const junctionPointsCoordinates = _.map(junction.junctionPoints, function (junctionPoint) {
          return junctionPoint.coordinates;
        });

        return !_.isEmpty(_.intersectionWith(junctionPointsCoordinates, [nodePoint.coordinates], _.isEqual));
      });

      return {
        junction: junction,
        nodePoints: _.filter(nodePoints, function (nodePoint) {
          return nodePoint.type === ViiteEnumerations.NodePointType.RoadNodePoint.value ||
            nodePoint.type === ViiteEnumerations.NodePointType.UnknownNodePointType.value;
        })
      };
    };

    const junctionAndNodePointsByNodePointCoordinates = function (nodePointId) {
      const nodePoints = selectedNodesAndJunctions.getNodePoints();
      const targetNodePoint = _.find(nodePoints, function (nodePoint) {
        return nodePoint.id === nodePointId;
      });

      const junction = _.find(selectedNodesAndJunctions.getJunctions(), function (junctionFound) {
        const junctionPointsCoordinates = _.map(junctionFound.junctionPoints, 'coordinates');
        return !_.isEmpty(_.intersectionWith(junctionPointsCoordinates, [targetNodePoint.coordinates], _.isEqual));
      });

      if (junction) {
        return junctionAndNodePointsByJunctionPointsCoordinates(junction.id);
      }

      return {
        nodePoints: _.filter(nodePoints, function (nodePoint) {
          return _.isEqual(nodePoint.coordinates, targetNodePoint.coordinates) &&
            (nodePoint.type === ViiteEnumerations.NodePointType.RoadNodePoint.value ||
              nodePoint.type === ViiteEnumerations.NodePointType.UnknownNodePointType.value);
        })
      };
    };

    const toggleJunctionInputNumber = function (junction, disabled) {
      const junctionInputElement = $('[id="junction-number-textbox-' + junction.id + '"]');
      junction.junctionNumber = disabled ? '' : junction.junctionNumber;
      junctionInputElement.prop('disabled', disabled);
      junctionInputElement.val(junction.junctionNumber);
      updateJunctionNumberEmptyState(junctionInputElement);
      selectedNodesAndJunctions.validateJunctionNumbers();
      selectedNodesAndJunctions.updateNodesAndJunctionsMarker([junction]);
    };

    const updateJunctionNumberEmptyState = function (inputElement) {
      const $input = $(inputElement);
      const isEmpty = !$input.prop('disabled') && _.trim($input.val() || '') === '';
      $input.toggleClass('junction-number-input-empty', isEmpty);
    };

    const updateAllJunctionNumberEmptyStates = function (targetContainer) {
      targetContainer.find('[id^=junction-number-textbox-]').each(function () {
        updateJunctionNumberEmptyState(this);
      });
    };

    const markJunctionAndNodePoints = function (junction, nodePoints, checked) {
      if (!_.isUndefined(junction)) {
        $('[id^="detach-junction-' + junction.id + '"]').prop('checked', checked);
        toggleJunctionInputNumber(junction, checked);
      }
      _.each(nodePoints, function (nodePoint) {
        $('[id^="detach-node-point-' + nodePoint.id + '"]').prop('checked', checked);
      });
    };

    const bindEditorEvents = function () {
      const rootElement = getContainer();
      rootElement.off('.nodeEditor');

      rootElement.on('change.nodeEditor', '#nodeName, #nodeTypeDropdown, #nodeStartDate', function (event) {
        eventbus.trigger(event.type + ':' + event.target.id, $(this).val());
      });

      rootElement.on('change.nodeEditor', '[id^=junction-number-textbox-]', function () {
        updateJunctionNumberEmptyState(this);
        selectedNodesAndJunctions.setJunctionNumber(parseInt($(this).attr('junctionId')), parseInt(this.value));
      });

      rootElement.on('change.nodeEditor', '[id^=junction-point-address-input-]', function () {
        const idString = $(this).attr('junctionPointId');
        const addr = parseInt(this.value);
        selectedNodesAndJunctions.setJunctionPointAddress(idString, addr);
      });

      rootElement.on('change.nodeEditor', '[id^="detach-node-point-"]', function () {
        const me = this;
        const match = junctionAndNodePointsByNodePointCoordinates(parseInt(me.value));
        if (me.checked) {
          if (!_.isEmpty(match.junction) || match.nodePoints.length > 1) {
            new ConfirmPopup(buildMessage(match.junction, match.nodePoints), {
              successCallback: function () {
                selectedNodesAndJunctions.detachJunctionAndNodePoints(match.junction, match.nodePoints);
                markJunctionAndNodePoints(match.junction, match.nodePoints, true);
              },
              closeCallback: function () {
                $(me).prop('checked', false);
              }
            });
          } else {
            selectedNodesAndJunctions.detachJunctionAndNodePoints(undefined, match.nodePoints);
            markJunctionAndNodePoints(undefined, match.nodePoints, true);
          }
        } else {
          new ConfirmPopup('Haluatko peruuttaa solmukohtien ja liittymän irrotuksen solmusta ?', {
            successCallback: function () {
              selectedNodesAndJunctions.attachJunctionAndNodePoints(match.junction, match.nodePoints);
              markJunctionAndNodePoints(match.junction, match.nodePoints, false);
            },
            closeCallback: function () {
              $(me).prop('checked', true);
            }
          });
        }
      });

      rootElement.on('change.nodeEditor', '[id^="detach-junction-"]', function () {
        const me = this;
        const match = junctionAndNodePointsByJunctionPointsCoordinates(parseInt(me.value));
        if (me.checked) {
          if (!_.isEmpty(match.nodePoints)) {
            new ConfirmPopup(buildMessage(match.junction, match.nodePoints), {
              successCallback: function () {
                selectedNodesAndJunctions.detachJunctionAndNodePoints(match.junction, match.nodePoints);
                markJunctionAndNodePoints(match.junction, match.nodePoints, true);
              },
              closeCallback: function () {
                $(me).prop('checked', false);
              }
            });
          } else {
            selectedNodesAndJunctions.detachJunctionAndNodePoints(match.junction, undefined);
            markJunctionAndNodePoints(match.junction, undefined, true);
          }
        } else {
          new ConfirmPopup('Haluatko peruuttaa solmukohtien ja liittymän irrotuksen solmusta ?', {
            successCallback: function () {
              selectedNodesAndJunctions.attachJunctionAndNodePoints(match.junction, match.nodePoints);
              markJunctionAndNodePoints(match.junction, match.nodePoints, false);
            },
            closeCallback: function () {
              $(me).prop('checked', true);
            }
          });
        }
      });

      rootElement.on('click.nodeEditor', '.btn-edit-node-save', function () {
        if (selectedNodesAndJunctions.isObsoleteNode()) {
          new ConfirmPopup('Tämä toiminto päättää solmun, tallennetaanko muutokset?', {
            successCallback: function () {
              selectedNodesAndJunctions.saveNode();
            }
          });
        } else {
          selectedNodesAndJunctions.saveNode();
        }
      });

      rootElement.on('click.nodeEditor', '.btn-edit-node-cancel', function () {
        selectedNodesAndJunctions.closeNode(true);
        cleanup();
        editorExitHandler('search');
      });

      const panelElement = $('#menu-container');
      panelElement.off('.nodeEditorFooter');

      panelElement.on('click.nodeEditorFooter', '.btn-edit-node-save', function () {
        if (selectedNodesAndJunctions.isObsoleteNode()) {
          new ConfirmPopup('Tämä toiminto päättää solmun, tallennetaanko muutokset?', {
            successCallback: function () {
              selectedNodesAndJunctions.saveNode();
            }
          });
        } else {
          selectedNodesAndJunctions.saveNode();
        }
      });

      panelElement.on('click.nodeEditorFooter', '.btn-edit-node-cancel', function () {
        selectedNodesAndJunctions.closeNode(true);
        cleanup();
        editorExitHandler('search');
      });

      rootElement.on('click.nodeEditor', '#edit-junction-point-addresses', function () {
        setAddressEditMode(!addressEditMode);
      });

      rootElement.on('input.nodeEditor', '[id^=junction-number-textbox-]', function () {
        updateJunctionNumberEmptyState(this);
        $(this).trigger('change');
      });

      rootElement.on('input.nodeEditor', '[id=nodeName]', function () {
        $(this).trigger('change');
      });
    };

    const bindValidationEvents = function () {
      const rootElement = getContainer();

      subscribeEventbus('node:displayCoordinates', function (coordinates) {
        $('#node-coordinates').text(Math.round(coordinates.y) + ', ' + Math.round(coordinates.x));
      });

      subscribeEventbus('change:nodeName', function (nodeName) {
        selectedNodesAndJunctions.setNodeName(nodeName);
      });

      subscribeEventbus('change:nodeStartDate', function (nodeStartDate) {
        let text = '';
        const partsDMY = nodeStartDate.split('.');
        const nodeSD = new Date(partsDMY[2], partsDMY[1] - 1, partsDMY[0]);
        const nowDate = new Date();

        if (nodeSD.getFullYear() < nowDate.getFullYear() - 20) {
          text = 'Vanha päiväys. Solmun alkupäivämäärä yli 20 vuotta historiassa. Varmista päivämäärän oikeellisuus ennen tallennusta.';
        } else if (nodeSD.getFullYear() > nowDate.getFullYear() + 1) {
          text = 'Tulevaisuuden päiväys. Solmun alkupäivä yli vuoden verran tulevaisuudessa. Varmista päivämäärän oikeellisuus ennen tallennusta.';
        }

        rootElement.find('#nodeStartDate-validation-notification').html(text);
      });

      subscribeEventbus('change:nodeTypeDropdown', function (nodeType) {
        const typeHasChanged = selectedNodesAndJunctions.typeHasChanged(parseInt(nodeType));
        selectedNodesAndJunctions.setNodeType(parseInt(nodeType));

        if (!typeHasChanged) {
          selectedNodesAndJunctions.setStartDate(selectedNodesAndJunctions.getInitialStartDate());
          rootElement.find('#nodeStartDate').val(selectedNodesAndJunctions.getInitialStartDate());
        }

        disabledDatePicker(!typeHasChanged);
      });

      subscribeEventbus('junction:validate', function () {
        selectedNodesAndJunctions.validateJunctionNumbers();
      });

      subscribeEventbus('junctionPoint:validate', function (idString, addr) {
        selectedNodesAndJunctions.validateJunctionPointAddress(idString, addr);
      });

      subscribeEventbus('change:node-coordinates change:nodeName change:nodeTypeDropdown change:nodeStartDate junction:validate junctionPoint:validate junction:detach nodePoint:detach junction:attach nodePoint:attach', function () {
        setSaveButtonDisabled(formIsInvalid());
      });

      subscribeEventbus('nodeStartDate:setCustomValidity', function (_startDate, errorMessage) {
        rootElement.find('#nodeStartDate')[0].setCustomValidity(errorMessage);
        rootElement.find('#nodeStartDate-validation-notification').html(errorMessage);
      });

      subscribeEventbus('junction:setCustomValidity', function (junctions, errorMessage) {
        _.each(junctions, function (junction) {
          rootElement.find('#junction-number-textbox-' + junction.id)[0].setCustomValidity(errorMessage);
        });
      });

      subscribeEventbus('junctionPoint:setCustomValidity', function (idString, errorMessage) {
        const input = rootElement.find('#junction-point-address-input-' + idString)[0];
        if (input) {
          input.setCustomValidity(errorMessage);
          input.reportValidity();
        }
        setSaveButtonDisabled(formIsInvalid());
      });

      subscribeEventbus('junctionPoint:editableStatusFetched', function (response, junctionPoint) {
        const inputField = rootElement.find('#junction-point-address-input-' + junctionPoint.id);
        if (!inputField.length) {
          return;
        }

        if (response.isEditable) {
          inputField.attr('disabled', false);
          inputField.attr('title', '');
          return;
        }

        inputField.attr('title', response.validationMessage || '');
      });

      subscribeEventbus('reset:startDate', function (originalStartDate) {
        resetDatePicker(originalStartDate);
      });

      subscribeEventbus('node:saveSuccess', function () {
        selectedNodesAndJunctions.closeNode(false);
        cleanup();
        editorExitHandler('search');
      });

      subscribeEventbus('node:saveFailed', function (errorMessage, spinnerEvent) {
        applicationModel.removeSpinner(spinnerEvent);
        new ConfirmPopup(errorMessage, { type: 'alert' });
      });
    };

    const showNode = function (currentNode, templates, handlers) {
      cleanup();
      editorExitHandler = handlers && handlers.onExit ? handlers.onExit : _.noop;

      const $container = getContainer();
      $container.html(renderNodeForm(currentNode));

      const nodePointTemplates = !_.isUndefined(templates) && _.has(templates, 'nodePoints') && templates.nodePoints;
      const junctionTemplates = !_.isUndefined(templates) && _.has(templates, 'junctions') && templates.junctions;

      $container.find('#junctions-info-content').html(renderDataTable(buildJunctionsTable({
        title: 'Liittymät',
        junctionTemplates: junctionTemplates,
        currentJunctions: _.sortBy(currentNode.junctions, 'junctionNumber'),
        options: {
          checkbox: _.isUndefined(templates),
          junctionInputNumber: true,
          disabledAttribute: nodeEditingDisabledAttribute,
          junctionPointAddressInputRenderer: junctionPointAddressInput
        }
      })));

      $container.find('#node-points-info-content').html(renderDataTable(buildNodePointsTable({
        title: 'Solmukohdat',
        nodePointTemplates: nodePointTemplates,
        currentNodePoints: currentNode.nodePoints,
        options: {
          checkbox: _.isUndefined(templates),
          junctionInputNumber: true,
          disabledAttribute: nodeEditingDisabledAttribute
        }
      })));

      requestJunctionPointEditableStatus($container);

      selectedNodesAndJunctions.addNodePointTemplates(nodePointTemplates);
      selectedNodesAndJunctions.addJunctionTemplates(junctionTemplates);

      addDatePicker($container.find('#nodeStartDate'), currentNode.startDate || moment('1.1.1900', dateutil.FINNISH_DATE_FORMAT).toDate());
      disableAutoComplete();

      if (userHasPermissionToEdit) {
        $container.find('.junction-address-header').append('<i id="edit-junction-point-addresses" class="btn-pencil-edit fas fa-pencil-alt"></i>');
      }

      setAddressEditMode(false);

      bindEditorEvents();
      bindValidationEvents();
      updateAllJunctionNumberEmptyStates($container);
      setSaveButtonDisabled(formIsInvalid());
    };

    return {
      showNode: showNode,
      cleanup: cleanup,
      getHeader: function () { return 'Solmun tiedot:'; },
      renderFooter: renderFooter
    };
  };
}(this));