/**
 * Renders node search controls, search result rows, and untreated template lists.
 * Keeps the node search panel focused on locating nodes before opening details or editor views.
 */
import { NodeMenuTableUtils } from './DataTable.js';
import { createNodeSearchFormFields } from './NodeSearchFormFields.js';
export function NodeSearchMenu(dataTable, containerElement) {
  const formFields = createNodeSearchFormFields('node-search-');
    const tableUtils = NodeMenuTableUtils;

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

    const renderControls = function () {
      return `
          <form id="node-search" class="node-search-grid form-dark">
            <div class="grid-column-center"><label class="label-centered">TIE</label></div>
            <div class="grid-column-center-2"><label class="label-centered">AOSA</label></div>
            <div class="grid-column-center-3"><label class="label-centered">LOSA</label></div>
            <div class="grid-column-button"></div>

            <div class="grid-column-input-1">${formFields.nodeInputNumber('tie', 5)}</div>
            <div class="grid-column-input-2">${formFields.nodeInputNumber('aosa', 3)}</div>
            <div class="grid-column-input-3">${formFields.nodeInputNumber('losa', 3)}</div>
            <div class="grid-column-button">
              <button id="node-search-btn" type="button" class="btn-primary node-search-btn" disabled>Hae solmut</button>
            </div>

            <div class="grid-column-full">
              <button id="clear-node-search" type="button" class="btn-secondary btn-clean-node-search" disabled>Tyhjennä tulokset</button>
            </div>
          </form>
      `;
    };

    const renderLayout = function () {
      return `
        <div class="wrapper read-only">
          ${renderControls()}
          <div id="node-search-results-content"></div>
          <div id="untreated-nodes-junctions-content"></div>
        </div>
      `;
    };

    const isSearchDisabled = function () {
      const tieValue = $('#tie').val();
      const minPart = Number($('#aosa').val()) || 0;
      const maxPart = Number($('#losa').val()) || 999;
      return !tieValue || minPart > maxPart;
    };

    const setSearchEnabled = function (enabled) {
      getContainer().find('#node-search-btn').prop('disabled', !enabled);
    };

    const setClearEnabled = function (enabled) {
      getContainer().find('#clear-node-search').prop('disabled', !enabled);
    };

    const buildSearchResults = function (nodes) {
      const items = _.map(nodes || [], function (nodeWithAttributes, index) {
        const addrMValue = _.isUndefined(nodeWithAttributes.addrMValue) || _.isNull(nodeWithAttributes.addrMValue)
          ? 0
          : nodeWithAttributes.addrMValue;

        return {
          id: index,
          tieOsaEt: `${nodeWithAttributes.roadNumber || ''}/${nodeWithAttributes.roadPartNumber || ''}/${addrMValue}`,
          name: nodeWithAttributes.name || '',
          type: nodeWithAttributes.type || '-',
          nodeNumber: nodeWithAttributes.nodeNumber || '-'
        };
      });

      return {
        title: 'Hakutulokset',
        addressHeader: 'TIE / OSA / ET',
        items: items
      };
    };

    const renderSearchResults = function (config) {
      const title = _.get(config, 'title', 'Hakutulokset');
      const addressHeader = _.get(config, 'addressHeader', 'TIE / OSA / ET');

      const items = _.map(_.get(config, 'items', []), function (item) {
        const id = _.isUndefined(item.id) ? '' : item.id;
        const tieOsaEt = _.get(item, 'tieOsaEt', '');
        const name = _.get(item, 'name', '');
        const type = _.get(item, 'type', '-');
        const nodeNumber = _.get(item, 'nodeNumber', '-');
        const nameMarkup = name
          ? `<label class="node-search-results-value node-search-results-name">${name}</label>`
          : '';

        return `
          <div class="node-search-results-item">
            <div class="node-search-results-primary-row">
              <a id="${id}" class="node-link node-search-result-link" href="#node">${tieOsaEt}</a>
              ${nameMarkup}
            </div>
            <div class="node-search-results-meta-row">
              <label class="node-search-results-label">Solmutyyppi:&nbsp;</label>
              <label class="node-search-results-value">${type}</label>
            </div>
            <div class="node-search-results-meta-row">
              <label class="node-search-results-label">Solmunumero:&nbsp;</label>
              <label class="node-search-results-value">${nodeNumber}</label>
            </div>
          </div>
        `;
      }).join('');

      return `
          <div class="node-search-section-title-container"><label>${title}</label></div>
          <div id="nodes-and-junctions-content" class="node-search-results-list">
            <label class="node-search-results-address-header">${addressHeader}</label>
            ${items}
          </div>>
      `;
    };

    const show = function () {
      const $container = getContainer();
      $container.html(renderLayout());
    };

    const showSearchResults = function (nodes) {
      const searchResults = buildSearchResults(nodes || []);
      getContainer().find('#node-search-results-content').html(renderSearchResults(searchResults));
    };

    const clearSearchResults = function () {
      getContainer().find('#node-search-results-content').empty();
    };

    const hasRowsInGroups = function (groups) {
      return _.some(_.values(groups || {}), function (group) {
        return (group.rows || []).length > 0;
      });
    };

    const showUntreatedTemplates = function (nodePointTemplates, junctionTemplates) {
      const tables = [];

      const junctionGroups = tableUtils.toEvkGroups(tableUtils.junctionTemplateRows(junctionTemplates || []), function (item) {
        return {
          id: item.id,
          className: 'junction-template-link node-template-clickable-row',
          cells: [
            item.roadNumber,
            item.track,
            item.roadPartNumber,
            item.addrM
          ]
        };
      });

      if (hasRowsInGroups(junctionGroups)) {
        tables.push(renderDataTable({
          title: 'Käsittelemättömät liittymäaihiot',
          headers: ['TIE', 'AJR', 'OSA', 'AET'],
          evkGroups: junctionGroups
        }));
      }

      const nodePointGroups = tableUtils.toEvkGroups(tableUtils.nodePointTemplateRows(nodePointTemplates || []), function (item) {
        return {
          id: item.id,
          className: 'node-point-template-link node-template-clickable-row',
          cells: [
            item.roadNumber,
            item.roadPartNumber,
            item.addrM
          ]
        };
      });

      if (hasRowsInGroups(nodePointGroups)) {
        tables.push(renderDataTable({
          title: 'Käsittelemättömät solmukohta-aihiot',
          headers: ['TIE', 'OSA', 'AET'],
          evkGroups: nodePointGroups
        }));
      }

      const html = tables.join('');
      getContainer().find('#untreated-nodes-junctions-content').html(html);
    };

    const clearUntreatedTemplates = function () {
      getContainer().find('#untreated-nodes-junctions-content').empty();
    };

    const bindEvents = function (handlers) {
      const rootElement = getContainer();
      rootElement.off('.nodeSearchMenu');

      rootElement.on('keyup.nodeSearchMenu input.nodeSearchMenu', '.node-input', function () {
        setSearchEnabled(!isSearchDisabled());
      });

      rootElement.on('click.nodeSearchMenu', '#node-search-btn', function () {
        const data = { roadNumber: $('#tie').val() };
        const minPart = $('#aosa').val();
        const maxPart = $('#losa').val();

        if (minPart) {
          data.minRoadPartNumber = minPart;
        }
        if (maxPart) {
          data.maxRoadPartNumber = maxPart;
        }
        handlers.onSearch(data);
      });

      rootElement.on('click.nodeSearchMenu', '#clear-node-search', function () {
        handlers.onClear();
      });

      rootElement.on('click.nodeSearchMenu', '.node-search-result-link', function (event) {
        event.preventDefault();
        handlers.onResultClick(event.currentTarget.id);
      });

      rootElement.on('click.nodeSearchMenu', '.node-point-template-link', function (event) {
        handlers.onNodePointTemplateClick(event.currentTarget.id);
      });

      rootElement.on('click.nodeSearchMenu', '.junction-template-link', function (event) {
        handlers.onJunctionTemplateClick(event.currentTarget.id);
      });
    };

    return {
      show: show,
      bindEvents: bindEvents,
      showSearchResults: showSearchResults,
      clearSearchResults: clearSearchResults,
      showUntreatedTemplates: showUntreatedTemplates,
      clearUntreatedTemplates: clearUntreatedTemplates,
      setSearchEnabled: setSearchEnabled,
      setClearEnabled: setClearEnabled
    };
  }

