import { NodeTableUtils } from './DataTable.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';

/**
 * NodeDataMenu - Read-only detail panel for searched node and template data.
 * Shows node/junction tables and exposes buttons that continue into editing flows.
 */
export function NodeDataMenu(dataTable, containerElement, dependencies) {
  const tableUtils = NodeTableUtils;
  const applicationModel = dependencies.applicationModel;

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

    const staticField = function (labelText, dataField) {
      return `<p class="form-control-static asset-log-info-metadata"><label>` + labelText + `</label>` + dataField + `</p>`;
    };

    const toNodePointsRows = function (nodePointsInfo, isTemplate) {
      const cellClassName = isTemplate ? 'node-points-table template' : 'node-points-table';
      return _.map(_.sortBy(tableUtils.getNodePointsRowsInfo(nodePointsInfo), ['roadNumber', 'roadPartNumber', 'addr']), function (row) {
        return {
          className: isTemplate ? 'node-point-template-row' : '',
          cells: [
            { className: cellClassName, content: row.roadNumber },
            { className: cellClassName, content: row.roadPartNumber },
            { className: cellClassName, content: row.addr },
            { className: cellClassName, content: row.beforeAfter }
          ]
        };
      });
    };

    const toJunctionRows = function (junctionsInfo, isTemplate) {
      const cellClassName = isTemplate ? 'node-junctions-table template' : 'node-junctions-table';
      return _.map(junctionsInfo || [], function (junction) {
        const junctionPointsInfo = tableUtils.getJunctionPointsInfo(junction);
        return {
          cells: [
            { className: cellClassName, content: tableUtils.asFlexColumn(_.map(junctionPointsInfo, 'roadNumber'), cellClassName) },
            { className: cellClassName, content: tableUtils.asFlexColumn(_.map(junctionPointsInfo, 'track'), cellClassName) },
            { className: cellClassName, content: tableUtils.asFlexColumn(_.map(junctionPointsInfo, 'roadPartNumber'), cellClassName) },
            { className: cellClassName, content: tableUtils.asFlexColumn(_.map(junctionPointsInfo, 'addr'), cellClassName) },
            { className: cellClassName, content: tableUtils.asFlexColumn(_.map(junctionPointsInfo, 'beforeAfter'), cellClassName) }
          ]
        };
      });
    };

    const buildNodePointsTable = function (nodePointTemplates, currentNodePoints) {
      return {
        title: 'Solmukohdat',
        tableId: 'nodePoints-table-info',
        tableClassName: 'node-points-table-dimension node-template-table',
        headers: [
          { label: 'TIE', className: '' },
          { label: 'OSA', className: '' },
          { label: 'ET', className: '' },
          { label: 'EJ', className: '' }
        ],
        rows: toNodePointsRows(nodePointTemplates, true)
          .concat(toNodePointsRows(currentNodePoints, false))
      };
    };

    const buildJunctionsTable = function (junctionTemplates, currentJunctions) {
      return {
        title: 'Liittymät',
        tableId: 'junctions-table-info',
        headers: [
          { label: 'TIE', className: 'node-junctions-table-header' },
          { label: 'AJR', className: 'node-junctions-table-header' },
          { label: 'OSA', className: 'node-junctions-table-header' },
          { label: 'ET', className: 'node-junctions-table-header junction-address-header' },
          { label: 'EJ', className: 'node-junctions-table-header' }
        ],
        rows: toJunctionRows(junctionTemplates, true)
          .concat(toJunctionRows(currentJunctions, false))
      };
    };

    const renderNodeDetailsBody = function (node, templates) {
      const nodePointTemplates = !_.isUndefined(templates) && _.has(templates, 'nodePoints') ? templates.nodePoints : undefined;
      const junctionTemplates = !_.isUndefined(templates) && _.has(templates, 'junctions') ? templates.junctions : undefined;
      const nodePointsTable = renderDataTable(buildNodePointsTable(nodePointTemplates, node.nodePoints));
      const junctionsTable = renderDataTable(buildJunctionsTable(junctionTemplates, _.sortBy(node.junctions, 'junctionNumber')));

      return `
        <div class="wrapper read-only node-form-wrapper">
          <div class="form form-horizontal form-dark">
            <div>
              ${staticField('Solmunumero:', node.nodeNumber ? node.nodeNumber : '-')}
              ${staticField('Koordinaatit (<i>P</i>, <i>I</i>):', `<span id="node-coordinates">${Math.round(node.coordinates.y)}, ${Math.round(node.coordinates.x)}</span>`)}
              ${staticField('*Solmun nimi:', node.name || '-')}
              ${staticField('*Solmutyyppi:', node.type || '-')}
              ${staticField('*Alkupvm:', node.startDate || '-')}
            </div>
            <div>
              <div id="node-points-info-content">
                ${nodePointsTable}
              </div>
              <div id="junctions-info-content">
                ${junctionsTable}
              </div>
            </div>
          </div>
        </div>
      `;
    };

    const renderTemplateDetailsBody = function (templates) {
      const templateTables = [];

      const sortedJunctionRows = _.map(tableUtils.sortedTemplateRows(tableUtils.junctionTemplateRows(templates.junctions)), function (item) {
        return {
          id: item.id,
          className: 'junction-template-static-row',
          cells: [
            item.roadNumber,
            item.track,
            item.roadPartNumber,
            item.addrM,
            item.ej || 'E'
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

      const sortedNodePointRows = _.map(_.sortBy(tableUtils.getNodePointsRowsInfo(templates.nodePoints), ['roadNumber', 'roadPartNumber', 'addr']), function (item) {
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

    const renderTemplateFooter = function () {
      return `
        <div class="form form-controls node-template-actions">
          <button id="attachToMapNode" class="btn-primary btn-block">Valitse kartalta solmu, johon haluat liittää aihiot</button>
          <button id="attachToNewNode" class="btn-primary btn-block">Luo uusi solmu, johon haluat liittää aihiot</button>
          <div class="node-template-actions-split-row">
            <button class="btn-primary btn-edit-node-save btn-block" disabled>Tallenna</button>
            <button class="cancel btn-secondary btn-edit-templates-cancel btn-block">Peruuta</button>
          </div>
        </div>
      `;
    };

    const showNode = function (node, templates) {
      getContainer().html(renderNodeDetailsBody(node, templates));
    };

    const showTemplates = function (templates) {
      getContainer().html(renderTemplateDetailsBody(templates));
    };

    const bindEvents = function (handlers) {
      const panelElement = $('#menu-container');
      panelElement.off('.nodeDataMenu');

      panelElement.on('click.nodeDataMenu', '.btn-open-node-editor', function () {
        handlers.onEditNode();
      });

      panelElement.on('click.nodeDataMenu', '.btn-node-display-back', function () {
        handlers.onBackToSearch();
      });

      panelElement.on('click.nodeDataMenu', '.btn-edit-templates-cancel', function () {
        handlers.onBackToSearch();
      });

      panelElement.on('click.nodeDataMenu', '.btn-edit-node-save', function () {
        if (_.isFunction(handlers.onSaveTemplates)) {
          handlers.onSaveTemplates();
        }
      });

      panelElement.on('click.nodeDataMenu', '#attachToMapNode', function () {
        panelElement.find('#attachToMapNode, #attachToNewNode').removeClass('active');
        panelElement.find('#attachToMapNode').addClass('active');
        applicationModel.setSelectedTool(ViiteEnumerations.Tool.Attach.value);
      });

      panelElement.on('click.nodeDataMenu', '#attachToNewNode', function () {
        panelElement.find('#attachToMapNode, #attachToNewNode').removeClass('active');
        panelElement.find('#attachToNewNode').addClass('active');
        applicationModel.setSelectedTool(ViiteEnumerations.Tool.Add.value);
      });
    };

    return {
      showNode: showNode,
      showTemplates: showTemplates,
      bindEvents: bindEvents,
      getNodeHeader: function () { return 'Solmun tiedot'; },
      getTemplateHeader: function () { return 'Aihioiden tiedot:'; },
      renderTemplateFooter: renderTemplateFooter
    };
  }

