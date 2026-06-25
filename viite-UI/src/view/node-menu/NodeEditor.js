import { button } from '@components/button/Button.js';
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { DatePicker } from '@components/date-picker/DatePicker.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { DataTable, NodeTableUtils } from '@node-menu/DataTable.js';
import { dateutil } from '@utils/DateUtils.js';
import { eventbus } from '@utils/eventbus.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { setNodeMenuState } from '@node-menu/NodeMenu.js';

/**
 * NodeEditor - Editable node form with detach, validation and save flows.
 * Supports editing node metadata, junction numbers and junction ET values.
 */
export function NodeEditor(
	selectedNodesAndJunctions,
	backend,
	roadCollection,
	containerElement,
	permissionToEditNodes
) {
	const dis = permissionToEditNodes ? '' : 'disabled';
	const dataTable = new DataTable();

	let picker;
	let activeEventbusHandlers = [];
	let addressEditMode = false;
	let saveInProgress = false;
	let sourceTemplates;
	let cancelExitTarget = 'templates';

	// ─── Helpers ────────────────────────────────────────────────────────────────

	const getContainer = () => {
		const resolved = _.result({ containerElement }, 'containerElement');
		return resolved ? $(resolved) : $('#menu-container');
	};

	const getNodeType = (value) =>
		_.find(ViiteEnumerations.NodeType, t => t.value === value) || ViiteEnumerations.NodeType.UnknownNodeType;

	const getAllowedAddrEditRange = (jp) => {
		let { minAddr, maxAddr } = { minAddr: jp.addr - 9, maxAddr: jp.addr + 9 };
		const links = _.map(roadCollection.getByRoadPartAndAddr(jp.roadNumber, jp.roadPartNumber, jp.addr), l => l.getData());
		_.each(links, l => {
			if (l.addrMRange.end   === jp.addr && l.addrMRange.start + 1 > minAddr) minAddr = l.addrMRange.start + 1;
			if (l.addrMRange.start === jp.addr && l.addrMRange.end   - 1 < maxAddr) maxAddr = l.addrMRange.end   - 1;
		});
		return { minAddr, maxAddr };
	};

	// ─── HTML ───────────────────────────────────────────────────────────────────

	const renderForm = (node, junctionsHtml, nodePointsHtml) => {
		const nodeType     = getNodeType(node.type);
		const nodeNumber   = node.nodeNumber || '-';
		const nodeName     = node.name       || '';
		const startDate    = node.startDate  || '';
		const unknownOpt   = nodeType === ViiteEnumerations.NodeType.UnknownNodeType
			? `<option value="${nodeType.value}" selected disabled hidden>${nodeType.value} ${nodeType.description}</option>` : '';
		const typeOptions  = _.map(
			_.filter(ViiteEnumerations.NodeType, t => t !== ViiteEnumerations.NodeType.UnknownNodeType),
			t => `<option value="${t.value}" ${_.isEqual(t, nodeType) ? 'selected' : ''}>${t.value} ${t.description}</option>`
		).join('');

		return `
      <div class="wrapper form-dark">
        <div class="node-metadata-container">
          <div class="node-editor-field-row"><label>Solmunumero:</label>${nodeNumber}</div>
          <div class="node-editor-field-row"><label>Koordinaatit (P, I):</label><span id="node-coordinates">${Math.round(node.coordinates.y)}, ${Math.round(node.coordinates.x)}</span></div>

          <div class="node-editor-field-row">
            <label class="required node-editor-field-label">Solmun nimi</label>
            <input type="text" class="form-control asset-input-node-data node-editor-field-control" id="nodeName" maxlength="30" value="${nodeName}" ${dis}/>
          </div>

          <div class="node-editor-field-row">
            <label class="required node-editor-field-label">Solmutyyppi</label>
            <select class="form-control asset-input-node-data node-editor-field-control" id="nodeTypeDropdown" ${dis}>
              ${unknownOpt}${typeOptions}
            </select>
          </div>

          <div class="node-editor-field-row">
            <label class="required node-editor-field-label">Alkupvm</label>
            <input type="text" class="form-control asset-input-node-data node-editor-field-control" id="nodeStartDate" placeholder="pp.kk.vvvv" value="${startDate}" disabled/>
          </div>
          <div class="form-check-date-notifications"><p id="nodeStartDate-validation-notification"></p></div>
        </div>

        <div>
          <div id="junctions-info-content">${junctionsHtml}</div>
          <div id="node-points-info-content">${nodePointsHtml}</div>
        </div>
      </div>`;
	};

	const onSave = () => {
		if (saveInProgress) {
			return;
		}

		saveInProgress = true;
		syncActionButtons();

		if (selectedNodesAndJunctions.isObsoleteNode())
			new ConfirmPopup('Tämä toiminto päättää solmun, tallennetaanko muutokset?', {
				successCallback: triggerNodeSave,
				closeCallback: () => {
					saveInProgress = false;
					syncActionButtons();
				}
			});
		else
			triggerNodeSave();
	};

	const exitEditor = (targetMenu) => {
		if (targetMenu === 'templates') {
			const templatesToShow = selectedNodesAndJunctions.getCurrentTemplates() || sourceTemplates;
			if (!templatesToShow) {
				setNodeMenuState('search');
				return;
			}

			selectedNodesAndJunctions.openTemplates(templatesToShow);
			setNodeMenuState('display-templates');
			return;
		}

		setNodeMenuState('search');
	};

	const onSaveSuccess = () => {
		cleanup();
		selectedNodesAndJunctions.closeNode(false);
		exitEditor('search');
	};

	const onSaveFail = (errorMessage, spinnerEvent) => {
		saveInProgress = false;
		syncActionButtons();
		Spinner.hide(spinnerEvent);
		new ConfirmPopup(errorMessage, { type: 'alert' });
	};

	const triggerNodeSave = () => selectedNodesAndJunctions.saveNode(onSaveSuccess, onSaveFail);

	const onCancel = () => {
		cleanup();
		selectedNodesAndJunctions.closeNode(true);
		exitEditor(cancelExitTarget);
	};

	const renderFooter = () => `
    <div class="node-editor-footer">
      ${button({ id: 'node-editor-save', label: 'Tallenna', className: 'save btn-primary btn-block node-editor-save', disabled: true, onClick: () => onSave() })}
      ${button({ id: 'node-editor-cancel', label: 'Peruuta', className: 'cancel btn-secondary btn-block node-editor-cancel', onClick: () => onCancel() })}
    </div>`;

	// ─── Table builders ─────────────────────────────────────────────────────────

	const detachNodePointBox = (np, opts) => {
		const t = _.find(ViiteEnumerations.NodePointType, type => type.value === np.type);
		const extra = _.isEqual(t, ViiteEnumerations.NodePointType.CalculatedNodePoint) ? ' disabled hidden' : '';
		return `<input type="checkbox" title="${t.description}" name="detach-node-point-${np.id}" value="${np.id}" id="detach-node-point-${np.id}" ${opts.disabledAttribute || ''}${extra}>`;
	};

	const detachJunctionBox = (j, opts) =>
		`<input type="checkbox" name="detach-junction-${j.id}" value="${j.id}" id="detach-junction-${j.id}" data-junction-number=" ${j.junctionNumber} " ${opts.disabledAttribute || ''}>`;

	const junctionNumberInput = (j, opts) =>
		`<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode === 8 || event.keyCode === 9)"
      class="form-control junction-number-input" id="junction-number-textbox-${j.id}" junctionId="${j.id}"
      maxlength="2" value="${j.junctionNumber || ''}" ${opts.disabledAttribute || ''}>`;

	const junctionAddressCells = (junctionPoints, opts) =>
		_.map(junctionPoints, jp => {
			const editable = jp.beforeAfter === 'EJ' && _.isFunction(opts.junctionPointAddressInputRenderer);
			const range    = editable ? getAllowedAddrEditRange(jp) : {};
			const input    = editable
				? `<input disabled type="number" class="form-control junction-point-address-input"
             id="junction-point-address-input-${jp.id}" junctionPointId="${jp.id}"
             maxlength="5" value="${jp.addr}" min="${range.minAddr}" max="${range.maxAddr}"/>`
				: '';
			const labelClass = editable
				? 'junction-point-address-label junction-point-address-label-editable'
				: 'junction-point-address-label';
			return `<span class="${labelClass}">${jp.addr}</span>${input}`;
		}).join('');

	const buildJunctionsTable = (data) => {
		const opts = data.options || {};
		const columns = [
			...(opts.checkbox          ? [{ label: 'Irrota<br>liittymä<br>solmusta', className: 'detach-column-left' }] : []),
			...(opts.junctionInputNumber ? [{ label: 'NRO' }] : []),
			{ label: 'TIE' }, { label: 'AJR' }, { label: 'OSA' },
			{ label: 'ET', className: ' junction-address-header' }, { label: 'EJ' }
		];

		const toRows = (junctions, _isTemplate) => _.map(junctions || [], j => {
			const jps   = NodeTableUtils.getJunctionPointsInfo(j);
			const cells = [
				...(opts.checkbox          ? [{ className: 'detach-column-left', content: detachJunctionBox(j, opts) }] : []),
				...(opts.junctionInputNumber ? [{ content: junctionNumberInput(j, opts) }] : []),
				{ content: NodeTableUtils.asFlexColumn(_.map(jps, 'roadNumber')) },
				{ content: NodeTableUtils.asFlexColumn(_.map(jps, 'track')) },
				{ content: NodeTableUtils.asFlexColumn(_.map(jps, 'roadPartNumber')) },
				{ content: `<div class="node-flex-column">${junctionAddressCells(jps, opts)}</div>` },
				{ content: NodeTableUtils.asFlexColumn(_.map(jps, 'beforeAfter')) }
			];
			return { className: 'junction-template-static-row node-junctions-table-border-bottom', cells };
		});

		return {
			title: data.title || '', tableId: 'junctions-table-info', headers: columns,
			rows: toRows(data.junctionTemplates, true).concat(toRows(data.currentJunctions, false))
		};
	};

	const buildNodePointsTable = (data) => {
		const opts = data.options || {};
		const columns = [
			...(opts.checkbox ? [{ label: 'Irrota<br>solmukohta', className: 'detach-column-left' }] : []),
			{ label: 'TIE' }, { label: 'OSA' }, { label: 'ET' }, { label: 'EJ' }
		];

		const toRows = (nodePoints, _isTemplate) => _.map(
			_.sortBy(NodeTableUtils.getNodePointsRowsInfo(nodePoints), ['roadNumber', 'roadPartNumber', 'addr']),
			row => ({
				className: 'node-point-template-static-row',
				cells: [
					...(opts.checkbox ? [{ className: 'detach-column-left', content: detachNodePointBox(row, opts) }] : []),
					{ content: row.roadNumber }, { content: row.roadPartNumber },
					{ content: row.addr },       { content: row.beforeAfter }
				]
			})
		);

		return {
			title: data.title || '', tableId: 'nodePoints-table-info', headers: columns,
			rows: toRows(data.nodePointTemplates, true).concat(toRows(data.currentNodePoints, false))
		};
	};

	// ─── Detach/attach helpers ──────────────────────────────────────────────────

	const junctionAndNodePointsByJunction = (junctionId) => {
		const junction   = _.find(selectedNodesAndJunctions.getJunctions(), j => j.id === junctionId);
		const jpCoords   = _.map(junction.junctionPoints, 'coordinates');
		const nodePoints = _.filter(selectedNodesAndJunctions.getNodePoints(), np =>
			!_.isEmpty(_.intersectionWith(jpCoords, [np.coordinates], _.isEqual)) &&
      [ViiteEnumerations.NodePointType.RoadNodePoint.value, ViiteEnumerations.NodePointType.UnknownNodePointType.value].includes(np.type)
		);
		return { junction, nodePoints };
	};

	const junctionAndNodePointsByNodePoint = (nodePointId) => {
		const target  = _.find(selectedNodesAndJunctions.getNodePoints(), np => np.id === nodePointId);
		const junction = _.find(selectedNodesAndJunctions.getJunctions(), j =>
			!_.isEmpty(_.intersectionWith(_.map(j.junctionPoints, 'coordinates'), [target.coordinates], _.isEqual))
		);
		if (junction) return junctionAndNodePointsByJunction(junction.id);
		return {
			nodePoints: _.filter(selectedNodesAndJunctions.getNodePoints(), np =>
				_.isEqual(np.coordinates, target.coordinates) &&
        [ViiteEnumerations.NodePointType.RoadNodePoint.value, ViiteEnumerations.NodePointType.UnknownNodePointType.value].includes(np.type)
			)
		};
	};

	const markJunctionAndNodePoints = (junction, nodePoints, checked) => {
		if (junction) {
			$(`[id^="detach-junction-${junction.id}"]`).prop('checked', checked);
			const el = $(`[id="junction-number-textbox-${junction.id}"]`);
			junction.junctionNumber = checked ? '' : junction.junctionNumber;
			el.prop('disabled', checked).val(junction.junctionNumber);
			updateJunctionNumberEmptyState(el);
			selectedNodesAndJunctions.validateJunctionNumbers(applyJunctionValidity);
			selectedNodesAndJunctions.updateNodesAndJunctionsMarker([junction]);
		}
		_.each(nodePoints, np => $(`[id^="detach-node-point-${np.id}"]`).prop('checked', checked));
	};

	const confirmDetach = (message, match, checkboxEl, checked, onSuccess = _.noop) => {
		new ConfirmPopup(message, {
			successCallback: () => {
				selectedNodesAndJunctions.detachJunctionAndNodePoints(match.junction, match.nodePoints);
				markJunctionAndNodePoints(match.junction, match.nodePoints, true);
				onSuccess();
			},
			closeCallback: () => $(checkboxEl).prop('checked', !checked)
		});
	};

	const confirmAttach = (match, checkboxEl, onSuccess = _.noop) => {
		new ConfirmPopup('Haluatko peruuttaa solmukohtien ja liittymän irrotuksen solmusta ?', {
			successCallback: () => {
				selectedNodesAndJunctions.attachJunctionAndNodePoints(match.junction, match.nodePoints);
				markJunctionAndNodePoints(match.junction, match.nodePoints, false);
				onSuccess();
			},
			closeCallback: () => $(checkboxEl).prop('checked', true)
		});
	};

	// ─── Validation ─────────────────────────────────────────────────────────────

	const applyJunctionValidity = (junctions, message) =>
		_.each(junctions, j => getContainer().find(`#junction-number-textbox-${j.id}`)[0].setCustomValidity(message));

	const updateJunctionNumberEmptyState = (inputEl) => {
		const $el = $(inputEl);
		$el.toggleClass('junction-number-input-empty', !$el.prop('disabled') && _.trim($el.val() || '') === '');
	};

	const formIsInvalid = () => {
		const $c = getContainer();
		const hasEmptyEnabledJunctionNumbers = $c
			.find('[id^=junction-number-textbox-]:enabled')
			.toArray()
			.some(input => _.trim($(input).val() || '') === '');

		const checks = {
			isNodeNameEmpty: $c.find('#nodeName').val() === '',
			isUnknownNodeType: $c.find('#nodeTypeDropdown').val() === String(ViiteEnumerations.NodeType.UnknownNodeType.value),
			isStartDateEmpty: $c.find('#nodeStartDate').val() === '',
			hasEmptyEnabledJunctionNumbers: hasEmptyEnabledJunctionNumbers,
			areJunctionNumbersValid: selectedNodesAndJunctions.validateJunctionNumbers(applyJunctionValidity)
		};

		const invalid = checks.isNodeNameEmpty
      || checks.isUnknownNodeType
      || checks.isStartDateEmpty
      || checks.hasEmptyEnabledJunctionNumbers
      || !checks.areJunctionNumbersValid;

		return invalid;
	};

	const setSaveButtonDisabled = (disabled) => { $('#node-editor-save').prop('disabled', disabled); };
	const setCancelButtonDisabled = (disabled) => { $('#node-editor-cancel').prop('disabled', disabled); };

	const syncActionButtons = () => {
		setSaveButtonDisabled(saveInProgress || formIsInvalid());
		setCancelButtonDisabled(saveInProgress);
	};

	// ─── Eventbus ───────────────────────────────────────────────────────────────

	const subscribeEventbus = (eventName, callback) => {
		eventbus.on(eventName, callback);
		activeEventbusHandlers.push({ eventName, callback });
	};

	const clearEventbusHandlers = () => {
		_.each(activeEventbusHandlers, h => eventbus.off(h.eventName, h.callback));
		activeEventbusHandlers = [];
	};

	const cleanup = () => {
		getContainer().off('.nodeEditor');
		clearEventbusHandlers();
	};

	// ─── Event binding ──────────────────────────────────────────────────────────

	const bindEvents = ($container) => {
		$container.off('.nodeEditor');
		const revalidate = () => syncActionButtons();

		// Field changes → update model + revalidate
		$container.on('input.nodeEditor change.nodeEditor', '#nodeName', function () {
			selectedNodesAndJunctions.setNodeName($(this).val());
			revalidate();
		});

		$container.on('change.nodeEditor', '#nodeTypeDropdown', function () {
			const val = parseInt($(this).val(), 10);
			const typeHasChanged = selectedNodesAndJunctions.typeHasChanged(val);
			selectedNodesAndJunctions.setNodeType(val);
			if (!typeHasChanged) {
				selectedNodesAndJunctions.setStartDate(selectedNodesAndJunctions.getInitialStartDate());
				$container.find('#nodeStartDate').val(selectedNodesAndJunctions.getInitialStartDate());
			}
			$container.find('#nodeStartDate').prop('disabled', !typeHasChanged);
			revalidate();
		});

		$container.on('change.nodeEditor', '#nodeStartDate', function () {
			const val = $(this).val();
			const parts = val.split('.');
			const d = new Date(parts[2], parts[1] - 1, parts[0]);
			const now = new Date();
			let msg = '';
			if (d.getFullYear() < now.getFullYear() - 20)
				msg = 'Vanha päiväys. Solmun alkupäivämäärä yli 20 vuotta historiassa. Varmista päivämäärän oikeellisuus ennen tallennusta.';
			else if (d.getFullYear() > now.getFullYear() + 1)
				msg = 'Tulevaisuuden päiväys. Solmun alkupäivä yli vuoden verran tulevaisuudessa. Varmista päivämäärän oikeellisuus ennen tallennusta.';
			$container.find('#nodeStartDate-validation-notification').html(msg);
			revalidate();
		});

		$container.on('input.nodeEditor change.nodeEditor', '[id^=junction-number-textbox-]', function () {
			updateJunctionNumberEmptyState(this);
			selectedNodesAndJunctions.setJunctionNumber(parseInt($(this).attr('junctionId'), 10), parseInt(this.value, 10));
			revalidate();
		});

		$container.on('change.nodeEditor', '[id^=junction-point-address-input-]', function () {
			selectedNodesAndJunctions.setJunctionPointAddress($(this).attr('junctionPointId'), parseInt(this.value, 10));
			revalidate();
		});

		$container.on('change.nodeEditor', '[id^="detach-node-point-"]', function () {
			const match = junctionAndNodePointsByNodePoint(parseInt(this.value, 10));
			if (this.checked) {
				if (!_.isEmpty(match.junction) || match.nodePoints.length > 1)
					confirmDetach(`Haluatko varmasti irrottaa solmukohdat ja liittymän solmusta?${buildDetachMessage(match.junction, match.nodePoints)}`, match, this, true, revalidate);
				else {
					selectedNodesAndJunctions.detachJunctionAndNodePoints(undefined, match.nodePoints);
					markJunctionAndNodePoints(undefined, match.nodePoints, true);
					revalidate();
				}
			} else {
				confirmAttach(match, this, revalidate);
			}
		});

		$container.on('change.nodeEditor', '[id^="detach-junction-"]', function () {
			const match = junctionAndNodePointsByJunction(parseInt(this.value, 10));
			if (this.checked) {
				if (!_.isEmpty(match.nodePoints))
					confirmDetach(`Haluatko varmasti irrottaa solmukohdat ja liittymän solmusta?${buildDetachMessage(match.junction, match.nodePoints)}`, match, this, true, revalidate);
				else {
					selectedNodesAndJunctions.detachJunctionAndNodePoints(match.junction, undefined);
					markJunctionAndNodePoints(match.junction, undefined, true);
					revalidate();
				}
			} else {
				confirmAttach(match, this, revalidate);
			}
		});

		$container.on('click.nodeEditor', '#edit-junction-point-addresses', () => {
			addressEditMode = !addressEditMode;
			$container.find('.junction-point-address-label-editable').toggle(!addressEditMode);
			$container.find('.junction-point-address-input').toggle(addressEditMode);
			$container.find('#edit-junction-point-addresses').toggleClass('active', addressEditMode);
		});

		// subscribeEventbus('nodeStartDate:setCustomValidity', (_date, errorMessage) => {
		// 	$container.find('#nodeStartDate')[0].setCustomValidity(errorMessage);
		// 	$container.find('#nodeStartDate-validation-notification').html(errorMessage);
		// });

		subscribeEventbus('junctionPoint:setCustomValidity', (idString, errorMessage) => {
			const input = $container.find(`#junction-point-address-input-${idString}`)[0];
			if (input) { input.setCustomValidity(errorMessage); input.reportValidity(); }
			revalidate();
		});

		subscribeEventbus('reset:startDate', (originalStartDate) => {
			if (picker) { picker.setDate(originalStartDate); picker.gotoToday(); }
		});

	};

	// ─── Detach confirm message ─────────────────────────────────────────────────

	const buildDetachMessage = (junction, nodePoints) => {
		const npHtml = nodePoints
			? dataTable.setProps(buildNodePointsTable({ title: 'Solmukohdat', currentNodePoints: nodePoints })).render()
			: '';
		const jHtml  = junction
			? dataTable.setProps(buildJunctionsTable({ title: 'Liittymät', currentJunctions: [junction] })).render()
			: '';
		return npHtml + jHtml;
	};

	// ─── Public API ─────────────────────────────────────────────────────────────

	const showNode = (currentNode, templates, options = {}) => {
		cleanup();
		sourceTemplates    = templates;
		cancelExitTarget   = options.cancelTarget || 'templates';
		addressEditMode    = false;
		saveInProgress     = false;

		const noTemplates  = _.isUndefined(templates);
		const npTemplates  = !noTemplates && _.has(templates, 'nodePoints') && templates.nodePoints;
		const jTemplates   = !noTemplates && _.has(templates, 'junctions')  && templates.junctions;
		const tableOpts    = { checkbox: noTemplates, junctionInputNumber: true, disabledAttribute: dis };

		const junctionsHtml   = dataTable.setProps(buildJunctionsTable({
			title: 'Liittymät', junctionTemplates: jTemplates,
			currentJunctions: _.sortBy(currentNode.junctions, 'junctionNumber'),
			options: { ...tableOpts, junctionPointAddressInputRenderer: (jp) => {
				const r = getAllowedAddrEditRange(jp);
				return `<input disabled type="number" class="form-control junction-point-address-input"
          id="junction-point-address-input-${jp.id}" junctionPointId="${jp.id}"
          maxlength="5" value="${jp.addr}" min="${r.minAddr}" max="${r.maxAddr}"/>`;
			}}
		})).render();

		const nodePointsHtml  = dataTable.setProps(buildNodePointsTable({
			title: 'Solmukohdat', nodePointTemplates: npTemplates,
			currentNodePoints: currentNode.nodePoints, options: tableOpts
		})).render();

		const $container = getContainer();
		$container.html(renderForm(currentNode, junctionsHtml, nodePointsHtml));

		// Request editable status for all junction point address inputs
		$container.find('[id^=junction-point-address-input-]').each(function () {
			const id = $(this).attr('junctionPointId');
			backend.getJunctionPointEditableStatus(id, function (response) {
				const $input = $container.find(`#junction-point-address-input-${id}`);
				if (!$input.length) return;
				$input.attr('disabled', !response.isEditable).attr('title', response.isEditable ? '' : (response.validationMessage || ''));
			});
		});

		selectedNodesAndJunctions.addNodePointTemplates(npTemplates);
		selectedNodesAndJunctions.addJunctionTemplates(jTemplates);

		picker = new DatePicker({
			id: 'nodeStartDate',
			minDate: currentNode.startDate || moment('1.1.1900', dateutil.FINNISH_DATE_FORMAT).toDate(),
			onChange: (value) => selectedNodesAndJunctions.setStartDate(value)
		});
		picker.addToElement($container.find('#nodeStartDate'));
		$container.find('#nodeStartDate').on('input.nodeEditor', function () { $(this).change(); });

		// Disable autocomplete
		$container.find('#nodeName, #nodeStartDate').attr('autocomplete', 'false');

		if (permissionToEditNodes) {
			$container.find('.junction-address-header').append('<i id="edit-junction-point-addresses" class="btn-pencil-edit fas fa-pencil-alt"></i>');
		}

		// Initialize address edit mode (hidden inputs, visible labels)
		$container.find('.junction-point-address-label-editable').show();
		$container.find('.junction-point-address-input').hide();

		// Initialize junction number empty states
		$container.find('[id^=junction-number-textbox-]').each(function () { updateJunctionNumberEmptyState(this); });

		bindEvents($container);
		syncActionButtons();
		// Set correct save button state
		_.defer(() => syncActionButtons());
	};

	return {
		showNode,
		cleanup,
		getHeader: () => 'Solmun tiedot:',
		renderFooter
	};
}