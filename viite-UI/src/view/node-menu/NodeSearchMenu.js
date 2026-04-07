// Return HTML for node search controls, search result rows, and untreated template lists.
import { NodeTableUtils } from './DataTable.js';

export function NodeSearchMenu(dataTable) {

  function setClearEnabled($el, isEnabled) {
    $el.find('#clear-node-search').prop('disabled', !isEnabled);
  }

  function render() {
    return `
      <div class="wrapper read-only">
        ${renderControls()}
        <div id="node-search-results-content"></div>
        <div id="untreated-nodes-junctions-content"></div>
      </div>
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

    const junctionGroups = NodeTableUtils.toEvkGroups(NodeTableUtils.junctionTemplateRows(junctionTemplates || []), (item) => ({
      id: item.id,
      className: 'junction-template-link node-template-clickable-row',
      cells: [item.roadNumber, item.track, item.roadPartNumber, item.addrM]
    }));

    if (hasRowsInGroups(junctionGroups)) {
      tables.push(renderDataTable({
        title: 'Käsittelemättömät liittymäaihiot',
        headers: ['TIE', 'AJR', 'OSA', 'AET'],
        evkGroups: junctionGroups
      }));
    }

    const nodePointGroups = NodeTableUtils.toEvkGroups(NodeTableUtils.nodePointTemplateRows(nodePointTemplates || []), (item) => ({
      id: item.id,
      className: 'node-point-template-link node-template-clickable-row',
      cells: [item.roadNumber, item.roadPartNumber, item.addrM]
    }));

    if (hasRowsInGroups(nodePointGroups)) {
      tables.push(renderDataTable({
        title: 'Käsittelemättömät solmukohta-aihiot',
        headers: ['TIE', 'OSA', 'AET'],
        evkGroups: nodePointGroups
      }));
    }

    return tables.join('');
  }

  // --- EVENT BINDING ---

  function bindEvents($el, handlers) {
    $el.off('.nodeSearchMenu');

    $el.on('keyup.nodeSearchMenu input.nodeSearchMenu', '.node-input', () => {
      const isSearchDisabled = getIsSearchDisabled($el);
      $el.find('#node-search-btn').prop('disabled', isSearchDisabled);
    });

    $el.on('click.nodeSearchMenu', '[data-action]', function (event) {
      const $btn = $(event.currentTarget);
      const action = $btn.data('action');
      const id = $btn.attr('id');

      switch (action) {
        case 'search':
          handlers.onSearch(getSearchData($el));
          break;
        case 'clear':
          handlers.onClear();
          break;
        case 'result-click':
          event.preventDefault();
          handlers.onResultClick(id);
          break;
        default:
          break;
      }
    });

    $el.on('click.nodeSearchMenu', '.node-point-template-link', function (event) {
      handlers.onNodePointTemplateClick(event.currentTarget.id);
    });

    $el.on('click.nodeSearchMenu', '.junction-template-link', function (event) {
      handlers.onJunctionTemplateClick(event.currentTarget.id);
    });
  }

  // --- PRIVATE UTILITIES ---

  function getSearchData($el) {
    const data = {
      roadNumber: $el.find('#tie').val(),
      minRoadPartNumber: $el.find('#aosa').val() || undefined,
      maxRoadPartNumber: $el.find('#losa').val() || undefined
    };
    return _.pickBy(data, _.identity);
  }

  function getIsSearchDisabled($el) {
    const tieValue = $el.find('#tie').val();
    const aosa = Number($el.find('#aosa').val()) || 0;
    const losa = Number($el.find('#losa').val()) || 999;
    return !tieValue || aosa > losa;
  }

  function hasRowsInGroups(groups) {
    return _.some(groups, (group) => (group.rows || []).length > 0);
  }

  function renderDataTable(props) {
    return dataTable.setProps(props).render();
  }

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
          <button data-action="search" id="node-search-btn" type="button" class="btn-primary" disabled>Hae solmut</button>
        </div>

        <div class="grid-column-full">
          <button data-action="clear" id="clear-node-search" type="button" class="btn-secondary btn-clean-node-search" disabled>Tyhjennä tulokset</button>
        </div>
      </form>
    `;
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

  return {
    render,
    bindEvents,
    setClearEnabled,
    renderSearchResults,
    renderUntreatedTemplates
  };
}