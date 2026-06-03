import { DataTable, NodeTableUtils } from './DataTable.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { isSelectedTool, setSelectedTool } from '@model/ApplicationModel.js';

/**
 * NodeDataMenu - Read-only detail panel for searched node and template data.
 * Shows node/junction tables and exposes buttons that continue into editing flows.
 */
export function NodeDataMenu(selectedNodesAndJunctions, setNodeMenuState) {
    const handlers = {
        onEditNode: () => {
            const currentNode = selectedNodesAndJunctions.getCurrentNode();
            if (currentNode) {
                setNodeMenuState('editor', currentNode, selectedNodesAndJunctions.getCurrentTemplates());
            }
        },
        onBackToSearch: () => {
            selectedNodesAndJunctions.closeTemplates();
            setNodeMenuState('search');
        },
        onSaveTemplates: () => {
            const currentNode = selectedNodesAndJunctions.getCurrentNode();
            if (currentNode) {
                selectedNodesAndJunctions.saveNode();
            }
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
      const attachToNewNodeClass = isSelectedTool(ViiteEnumerations.Tool.Add.value) ? ' active' : '';
      return `
        <div class="form form-controls node-template-actions">
          <button id="attachToNewNode" class="btn-primary btn-block${attachToNewNodeClass}">Luo uusi solmu, johon haluat liittää aihiot</button>
          <div class="node-template-actions-split-row">
            <button class="btn-primary btn-edit-node-save btn-block" disabled>Tallenna</button>
            <button class="cancel btn-secondary btn-edit-templates-cancel btn-block">Peruuta</button>
          </div>
        </div>
      `;
    };

    const bindEvents = function () {
      const panelElement = $('#menu-container');
      panelElement.off('.nodeDataMenu');

      panelElement.on('click.nodeDataMenu', '.btn-open-node-editor', function () {
        if (_.isFunction(handlers.onEditNode)) {
          handlers.onEditNode();
        }
      });

      panelElement.on('click.nodeDataMenu', '.btn-node-display-back', function () {
        if (_.isFunction(handlers.onBackToSearch)) {
          handlers.onBackToSearch();
        }
      });

      panelElement.on('click.nodeDataMenu', '.btn-edit-templates-cancel', function () {
        if (_.isFunction(handlers.onBackToSearch)) {
          handlers.onBackToSearch();
        }
      });

      panelElement.on('click.nodeDataMenu', '.btn-edit-node-save', function () {
        if (_.isFunction(handlers.onSaveTemplates)) {
          handlers.onSaveTemplates();
        }
      });

      panelElement.on('click.nodeDataMenu', '#attachToNewNode', function () {
        setSelectedTool(ViiteEnumerations.Tool.Add.value);
        panelElement.find('#attachToNewNode').toggleClass('active', isSelectedTool(ViiteEnumerations.Tool.Add.value));
      });
    };

    return {
      renderBody,
      renderFooter
    };
  }

