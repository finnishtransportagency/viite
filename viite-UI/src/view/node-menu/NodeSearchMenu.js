import { DataTable, NodeTableUtils } from './DataTable.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { selectLayer } from '@model/ApplicationModel.js';
import { moveMapToCoordinates } from '@view/map/MapView.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { button } from '@components/button/Button.js';
import { setNodeMenuState } from '@node-menu/NodeMenu.js';
import { navigateToNodePointTemplate, navigateToJunctionTemplate } from '@src/router.js';

export function NodeSearchMenu(map, nodeCollection, backend, selectedNodesAndJunctions) {
	const dataTable = new DataTable();
	const ROOT = '.node-search-root';
	let pendingSearchNodeNumber = null;
	let storedTemplates = { nodePoints: [], junctions: [] };

	// --- PRIVATE: DATA & LIFECYCLE ---

	function hasCompleteNodeData(node) {
		return Boolean(node) && _.isArray(node.nodePoints) && _.isArray(node.junctions);
	}

	function openSearchNodeWithMapData(searchNode) {
		const completeNode = nodeCollection.getNodeByNodeNumber(searchNode.nodeNumber);
		if (hasCompleteNodeData(completeNode)) {
			selectedNodesAndJunctions.openNode(completeNode);
			setNodeMenuState('editor', 'search');
			return;
		}
		pendingSearchNodeNumber = searchNode.nodeNumber;
		(async () => {
			await nodeCollection.fetchAndApplyNodesAndJunctions(zoomlevels.getViewZoom(map) + 1);
			if (pendingSearchNodeNumber !== searchNode.nodeNumber) return;

			const fetchedNode = nodeCollection.getNodeByNodeNumber(searchNode.nodeNumber);
			const nodeToOpen = hasCompleteNodeData(fetchedNode) ? fetchedNode : searchNode;
			selectedNodesAndJunctions.openNode(nodeToOpen);
			setNodeMenuState('editor', 'search');
		})();
	}

	function fetchAndRenderTemplates() {
		Spinner.show('node-menu-templates');
		backend.getTemplates((data) => {
			const nodePointTemplates = _.get(data, 'nodePointTemplates', []);
			const junctionTemplates = _.get(data, 'junctionTemplates', []);
			storedTemplates = { nodePoints: nodePointTemplates, junctions: junctionTemplates };
			nodeCollection.setUserTemplates(nodePointTemplates, junctionTemplates);
			setUntreatedTemplates(nodePointTemplates, junctionTemplates);
			Spinner.hide('node-menu-templates');
		});
	}

	// --- PRIVATE: DOM HELPERS ---

	function root() {
		return $(ROOT);
	}

	function setSearchResults(nodes) {
		root().find('#node-search-results-content').html(!_.isEmpty(nodes) ? renderSearchResults(nodes) : '');
	}

	function clearSearchResults() {
		root().find('#node-search-results-content').html('');
	}

	function setUntreatedTemplates(nodePointTemplates, junctionTemplates) {
		root().find('#untreated-nodes-junctions-content').html(renderUntreatedTemplates(nodePointTemplates, junctionTemplates));
	}

	function clearUntreatedTemplates() {
		root().find('#untreated-nodes-junctions-content').html('');
	}

	function getSearchData() {
		const r = root();
		return _.pickBy({
			roadNumber: r.find('#tie').val(),
			minRoadPartNumber: r.find('#aosa').val() || undefined,
			maxRoadPartNumber: r.find('#losa').val() || undefined
		}, _.identity);
	}

	function resolveJunctionPointCoordinatesByRow(templateId, rowData) {
		const clickedTemplate = _.find(storedTemplates.junctions, function (junction) {
			return junction.id === templateId;
		});

		if (!clickedTemplate) {
			return null;
		}

		const matchingPoint = _.find(clickedTemplate.junctionPoints || [], function (jp) {
			return Number(jp.roadNumber) === Number(rowData.roadNumber) &&
        Number(jp.track) === Number(rowData.track) &&
        Number(jp.roadPartNumber) === Number(rowData.roadPartNumber) &&
        Number(jp.addrM) === Number(rowData.addrM);
		});

		if (matchingPoint && matchingPoint.coordinates) {
			return matchingPoint.coordinates;
		}

		return _.get(_.first(clickedTemplate.junctionPoints), 'coordinates', null);
	}

	// --- BUTTON LOGIC ---

	function handleSearch() {
		Spinner.show('node-menu-search');
		clearSearchResults();
		clearUntreatedTemplates();
		(async () => {
			try {
				await nodeCollection.getNodesByRoadAttributes(getSearchData());
				if (!root().length) return;
				const nodes = nodeCollection.getNodesWithAttributes();
				setSearchResults(nodes);
				$('#clear-node-search').prop('disabled', false);
				nodeCollection.fitMapToSearchResults();
			} catch (error) {
				console.error('Search failed:', error);
			} finally {
				Spinner.hide('node-menu-search');
			}
		})();
	}

	function handleClear() {
		clearSearchResults();
		$('#clear-node-search').prop('disabled', true);
		fetchAndRenderTemplates();
	}

	function getIsSearchDisabled() {
		const r = root();
		const aosa = Number(r.find('#aosa').val()) || 0;
		const losa = Number(r.find('#losa').val()) || 999;
		return r.find('#tie').val() && aosa > losa;
	}

	// EVENT BINDING

	$(document).on('click', `${ROOT} [data-action="result-click"]`, function (event) {
		event.preventDefault();
		const id = $(event.currentTarget).attr('id');
		const node = nodeCollection.getNodesWithAttributes()[id];
		if (node) {
			moveMapToCoordinates({
				lon: node.coordinates.x,
				lat: node.coordinates.y,
				zoom: 12
			});
			openSearchNodeWithMapData(node);
		}
	});

	$(document).on('click', `${ROOT} .node-point-template-link`, function (event) {
		const templateId = Number(event.currentTarget.id);
		navigateToNodePointTemplate(templateId);
		nodeCollection.openNodePointTemplate({ id: templateId });
	});

	$(document).on('click', `${ROOT} .junction-template-link`, function (event) {
		const templateId = Number(event.currentTarget.id);
		const $cells = $(event.currentTarget).find('td');
		const rowData = {
			roadNumber: $cells.eq(0).text(),
			track: $cells.eq(1).text(),
			roadPartNumber: $cells.eq(2).text(),
			addrM: $cells.eq(3).text()
		};

		const coordinates = resolveJunctionPointCoordinatesByRow(templateId, rowData);
		navigateToJunctionTemplate(templateId);
		nodeCollection.openJunctionTemplate({
			id: templateId,
			coordinates: coordinates,
			rowData: rowData
		});
	});


	// --- PRIVATE: RENDERING ---

	function renderControls() {
		return `
      <form id="node-search" class="node-search-grid form-dark">
        <div class="grid-column-center"><label class="label-centered">TIE</label></div>
        <div class="grid-column-center-2"><label class="label-centered">AOSA</label></div>
        <div class="grid-column-center-3"><label class="label-centered">LOSA</label></div>
        <div class="grid-column-button"></div>

        <div class="grid-column-input-1"><input type="number" class="form-control node-input" id="tie" maxlength="5"></div>
        <div class="grid-column-input-2"><input type="number" class="form-control node-input" id="aosa" maxlength="3"></div>
        <div class="grid-column-input-3"><input type="number" class="form-control node-input" id="losa" maxlength="3"></div>
        <div class="grid-column-button">
          ${button({ id: 'node-search-btn', label: 'Hae solmut', onClick: handleSearch, disabled: true, disabledWhen: getIsSearchDisabled, watchSelector: `${ROOT} .node-input` })}
        </div>
        <div class="grid-column-clear-button">
          ${button({ id: 'clear-node-search', label: 'Tyhjennä tulokset', onClick: handleClear, className: 'btn-secondary btn-clean-node-search', disabled: true })}
        </div>
      </form>
    `;
	}

	function renderSearchResults(nodes) {
		const config = buildSearchResults(nodes);
		const itemsHtml = _.map(config.items || [], (item) => `
      <div class="node-search-results-item">
        <div class="node-search-results-primary-row">
          <a id="${item.id}" data-action="result-click" class="node-link node-search-result-link" href="#node">${item.tieOsaEt}</a>
          ${item.name ? `<label class="node-search-results-value node-search-results-name">${item.name}</label>` : ''}
        </div>
        <div class="node-search-results-meta-row">
          <label class="node-search-results-label">Solmutyyppi:&nbsp;</label>
          <label class="node-search-results-value">${item.type}</label>
        </div>
        <div class="node-search-results-meta-row">
          <label class="node-search-results-label">Solmunumero:&nbsp;</label>
          <label class="node-search-results-value">${item.nodeNumber}</label>
        </div>
      </div>
    `).join('');

		return `
      <div class="node-search-section-title-container"><label>${config.title}</label></div>
      <div id="nodes-and-junctions-content" class="node-search-results-list">
        <label class="node-search-results-address-header">${config.addressHeader}</label>
        ${itemsHtml}
      </div>
    `;
	}

	function renderUntreatedTemplates(nodePointTemplates, junctionTemplates) {
		const tables = [];

		const junctionGroups = NodeTableUtils.toEvkGroups(
			NodeTableUtils.junctionTemplateRows(junctionTemplates || []),
			(item) => ({
				id: item.id,
				className: 'junction-template-link node-template-clickable-row',
				cells: [item.roadNumber, item.track, item.roadPartNumber, item.addrM]
			})
		);
		if (hasRowsInGroups(junctionGroups)) {
			tables.push(renderDataTable({
				title: 'Käsittelemättömät liittymäaihiot',
				headers: ['TIE', 'AJR', 'OSA', 'AET'],
				evkGroups: junctionGroups
			}));
		}

		const nodePointGroups = NodeTableUtils.toEvkGroups(
			NodeTableUtils.nodePointTemplateRows(nodePointTemplates || []),
			(item) => ({
				id: item.id,
				className: 'node-point-template-link node-template-clickable-row',
				cells: [item.roadNumber, item.roadPartNumber, item.addrM]
			})
		);
		if (hasRowsInGroups(nodePointGroups)) {
			tables.push(renderDataTable({
				title: 'Käsittelemättömät solmukohta-aihiot',
				headers: ['TIE', 'OSA', 'AET'],
				evkGroups: nodePointGroups
			}));
		}

		return tables.join('');
	}

	function buildSearchResults(nodes) {
		const items = _.map(nodes || [], (node, index) => ({
			id: index,
			tieOsaEt: `${node.roadNumber || ''}/${node.roadPartNumber || ''}/${_.isNil(node.addrMValue) ? 0 : node.addrMValue}`,
			name: node.name || '',
			type: node.type || '-',
			nodeNumber: node.nodeNumber || '-'
		}));
		return { title: 'Hakutulokset', addressHeader: 'TIE / OSA / ET', items };
	}

	function renderDataTable(props) {
		return dataTable.setProps(props).render();
	}

	function hasRowsInGroups(groups) {
		return _.some(groups, (group) => (group.rows || []).length > 0);
	}

	// --- PUBLIC API ---

	function render() {
		selectLayer('node');
		fetchAndRenderTemplates();
		return `
      <div class="node-search-root wrapper read-only">
        ${renderControls()}
        <div class="node-search-scroll-content">
          <div id="node-search-results-content"></div>
          <div id="untreated-nodes-junctions-content"></div>
        </div>
      </div>
    `;
	}

	return { render };
}