import { DataTable, NodeTableUtils } from './DataTable.js';
import { button } from '@components/button/Button.js';
import { setNodeMenuState } from '@node-menu/NodeMenu.js';
import { setNodeLayerCreateMode } from '@view/map/layers/NodeLayer.js';

let nodeCreateMode = false;

export function isNodeCreateModeEnabled() {
	return nodeCreateMode;
}

// Allows user to create a new node when clicking on map
export function setNodeCreateModeEnabled(enabled) {
	const nextValue = Boolean(enabled);
	if (nodeCreateMode === nextValue) return nodeCreateMode;

	nodeCreateMode = nextValue;
	$('#attachToNewNode').toggleClass('active', nodeCreateMode);
	setNodeLayerCreateMode(nextValue);
	return nodeCreateMode;
}

export function toggleNodeCreateMode() {
	return setNodeCreateModeEnabled(!nodeCreateMode);
}

/**
 * NodeDataMenu - Read-only detail panel for searched node and template data.
 * Shows node/junction tables and exposes buttons that continue into editing flows.
 */
export function NodeDataMenu(selectedNodesAndJunctions) {

	const onEditNode = () => {
		const currentNode = selectedNodesAndJunctions.getCurrentNode();
		if (currentNode) {
			setNodeMenuState('editor', 'templates');
		}
	};

	const onBackToSearch = () => {
		selectedNodesAndJunctions.closeTemplates();
		setNodeMenuState('search');
	};

	const onSaveTemplates = () => {
		const currentNode = selectedNodesAndJunctions.getCurrentNode();
		if (currentNode) {
			selectedNodesAndJunctions.saveNode();
		}
	};
	const dataTable = new DataTable();
	const renderDataTable = function (props) {
		return dataTable.setProps(props).render();
	};

	const getTemplateJunctionRowsInfo = function (junctionTemplates) {
		const rows = _.flatMap(junctionTemplates || [], function (junction) {
			const junctionPointsInfo = NodeTableUtils.getJunctionPointsInfo(junction);

			if (!_.isEmpty(junctionPointsInfo)) {
				return junctionPointsInfo;
			}

			return [{
				id: junction.id,
				roadNumber: junction.roadNumber,
				track: junction.track,
				roadPartNumber: junction.roadPartNumber,
				addr: junction.addrM,
				beforeAfter: junction.ej || 'E'
			}];
		});

		return _.uniqWith(rows, function (left, right) {
			return left.roadNumber === right.roadNumber &&
          left.track === right.track &&
          left.roadPartNumber === right.roadPartNumber &&
          left.addr === right.addr &&
          left.beforeAfter === right.beforeAfter;
		});
	};

	const renderBody = function (templates) {
		const effectiveTemplates = templates || selectedNodesAndJunctions.getCurrentTemplates() || {};
		const safeTemplates = {
			junctions: _.get(effectiveTemplates, 'junctions', []),
			nodePoints: _.get(effectiveTemplates, 'nodePoints', [])
		};
		const templateTables = [];

		const sortedJunctionRows = _.map(_.sortBy(getTemplateJunctionRowsInfo(safeTemplates.junctions), ['roadNumber', 'roadPartNumber', 'track', 'addr', 'beforeAfter']), function (item) {
			return {
				id: item.id,
				className: 'junction-template-static-row',
				cells: [
					item.roadNumber,
					item.track,
					item.roadPartNumber,
					item.addr,
					item.beforeAfter
				]
			};
		});

		if (sortedJunctionRows.length > 0) {
			templateTables.push(renderDataTable({
				title: 'Liittymät',
				headers: ['TIE', 'AJR', 'OSA', 'ET', 'EJ'],
				rows: sortedJunctionRows
			}));
		}

		const sortedNodePointRows = _.map(_.sortBy(NodeTableUtils.getNodePointsRowsInfo(safeTemplates.nodePoints), ['roadNumber', 'roadPartNumber', 'addr']), function (item) {
			return {
				id: item.id,
				className: 'node-point-template-static-row',
				cells: [
					item.roadNumber,
					item.roadPartNumber,
					item.addr,
					item.beforeAfter
				]
			};
		});

		if (sortedNodePointRows.length > 0) {
			templateTables.push(renderDataTable({
				title: 'Solmukohdat',
				headers: ['TIE', 'OSA', 'ET', 'EJ'],
				rows: sortedNodePointRows
			}));
		}

		const templateDetailsTable = templateTables.join('');

		return `
        <div class="wrapper read-only node-form-wrapper">
          <div class="form form-horizontal form-dark">
            <div id="node-items-info-content">${templateDetailsTable}</div>
          </div>
        </div>
      `;
	};

	const renderFooter = function () {
		bindEvents();
		const attachToNewNodeClass = isNodeCreateModeEnabled() ? ' active' : '';
		return `
        <div class="form form-controls node-template-actions">
          ${button({ 
            id: 'attachToNewNode',
            label: 'Luo uusi solmu, johon haluat liittää aihiot',
            className: 'btn-primary btn-block' + attachToNewNodeClass,
            onClick: () => { toggleNodeCreateMode(); } 
          })}
          <div class="node-template-actions-split-row">
            ${button({ id: 'btn-edit-node-save', label: 'Tallenna', className: 'btn-primary btn-edit-node-save btn-block', disabled: true, onClick: onSaveTemplates })}
            ${button({ id: 'btn-edit-templates-cancel', label: 'Peruuta', className: 'cancel btn-secondary btn-edit-templates-cancel btn-block', onClick: onBackToSearch })}
          </div>
        </div>
      `;
	};

	const bindEvents = function () {
		const panelElement = $('#menu-container');
		panelElement.off('.nodeDataMenu');
		panelElement.on('click.nodeDataMenu', '.btn-open-node-editor', onEditNode);
		panelElement.on('click.nodeDataMenu', '.btn-node-display-back', onBackToSearch);
	};

	return {
		renderBody,
		renderFooter
	};
}

