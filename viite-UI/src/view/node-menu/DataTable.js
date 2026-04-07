import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';

/**
 * DataTable - Dumb, props-driven table renderer.
 * Parent prepares headers/rows/grouping and DataTable only renders HTML.
 *
 * Props contract:
 * - title: string
 * - headers: array of primitive values, DOM/jQuery nodes, or { label/content, className }
 * - rows: array of row values/arrays or { id, className, cells }
 * - evkGroups: optional object where each key maps to either row-array or { label, rows }
 * - tableId: optional table id attribute
 *
 * Usage examples:
 * const table = new root.DataTable();
 *
 * const plainHtml = table.setProps({
 *   title: 'Example',
 *   headers: ['A', 'B', $('<span>Supports jQuery</span>')],
 *   rows: [
 *     { id: 1, className: 'clickable-row', cells: ['1', '111', '<b>321</b>'] },
 *     ['2', '222', '333']
 *   ]
 * }).render();
 *
 * const groupedHtml = table.setProps({
 *   title: 'Grouped Example',
 *   headers: ['TIE', 'OSA', 'ET', 'EJ'],
 *   evkGroups: {
 *     'Uusimaa (1)': {
 *       rows: [{ id: 10, className: 'node-point-template-link', cells: [1, 2, 300, 'E'] }]
 *     },
 *     Muut: {
 *       label: 'Muut',
 *       rows: [{ id: 11, className: 'node-point-template-link', cells: [4, 5, 600, 'J'] }]
 *     }
 *   }
 * }).render();
 */
function DataTable(options) {
  const defaults = {
    title: '',
    headers: [],
    rows: [],
    evkGroups: null,
    tableId: ''
  };

  let props = _.assign({}, defaults, options || {});

  const cellContentToHtml = function (content) {
    if (_.isUndefined(content) || _.isNull(content)) {
      return '';
    }

    if (_.isArray(content)) {
      return _.map(content, function (item) {
        return cellContentToHtml(item);
      }).join('');
    }

    if (_.isString(content) || _.isNumber(content) || _.isBoolean(content)) {
      return String(content);
    }

    if (content && content.jquery) {
      const wrapper = $('<div></div>');
      _.each(content, function (element) {
        wrapper.append($(element).clone());
      });
      return wrapper.html();
    }

    if (content && content.nodeType) {
      if (content.nodeType === 1) {
        return content.outerHTML || $('<div></div>').append($(content).clone()).html();
      }
      if (content.nodeType === 3) {
        return content.nodeValue || '';
      }
    }

    return String(content);
  };

  const normalizeHeader = function (header) {
    if (_.isObject(header) && !_.isArray(header) && !header.jquery && !header.nodeType) {
      return {
        className: header.className || '',
        content: _.has(header, 'content') ? header.content : header.label
      };
    }

    return {
      className: '',
      content: header
    };
  };

  const normalizeCell = function (cell) {
    if (_.isObject(cell) && !_.isArray(cell) && !cell.jquery && !cell.nodeType) {
      return {
        className: cell.className || '',
        colspan: cell.colspan || null,
        content: _.has(cell, 'content') ? cell.content : cell.value
      };
    }

    return {
      className: '',
      colspan: null,
      content: cell
    };
  };

  const normalizeRow = function (row) {
    if (_.isArray(row)) {
      return {
        id: undefined,
        className: '',
        cells: _.map(row, normalizeCell)
      };
    }

    if (_.isObject(row) && !row.jquery && !row.nodeType) {
      const rawCells = _.isArray(row.cells) ? row.cells : [];
      return {
        id: row.id,
        className: row.className || '',
        cells: _.map(rawCells, normalizeCell)
      };
    }

    return {
      id: undefined,
      className: '',
      cells: [normalizeCell(row)]
    };
  };

  const renderHeaderRow = function () {
    const headers = _.map(props.headers || [], function (header) {
      const normalizedHeader = normalizeHeader(header);
      const className = normalizedHeader.className ? ' class="' + normalizedHeader.className + '"' : '';
      return '<th' + className + '>' + cellContentToHtml(normalizedHeader.content) + '</th>';
    }).join('');

    return '<tr>' + headers + '</tr>';
  };

  const renderRow = function (row) {
    const normalizedRow = normalizeRow(row);
    const rowClass = normalizedRow.className ? ' class="' + normalizedRow.className + '"' : '';
    const idAttribute = _.isUndefined(normalizedRow.id) ? '' : ' id="' + normalizedRow.id + '"';
    const cells = _.map(normalizedRow.cells, function (cell) {
      const cellClass = cell.className ? ' class="' + cell.className + '"' : '';
      const colspan = cell.colspan ? ' colspan="' + cell.colspan + '"' : '';
      return '<td' + cellClass + colspan + '>' + cellContentToHtml(cell.content) + '</td>';
    }).join('');

    return '<tr' + idAttribute + rowClass + '>' + cells + '</tr>';
  };

  const renderGroupedRows = function () {
    if (_.isEmpty(props.evkGroups)) {
      return '';
    }

    const columnCount = (props.headers || []).length || 1;

    return _.map(_.keys(props.evkGroups), function (groupName) {
      const groupConfig = props.evkGroups[groupName];
      const rows = _.isArray(groupConfig)
        ? groupConfig
        : (_.get(groupConfig, 'rows', []));
      const label = _.isArray(groupConfig)
        ? groupName
        : (_.get(groupConfig, 'label', groupName));

      const headerRow = '<tr><td colspan="' + columnCount + '" class="node-search-group-row">' +
        cellContentToHtml(label || 'Muut') + '</td></tr>';

      const renderedRows = _.map(rows, renderRow).join('');
      return headerRow + renderedRows;
    }).join('');
  };

  const renderRows = function () {
    if (!_.isEmpty(props.evkGroups)) {
      return renderGroupedRows();
    }

    return _.map(props.rows || [], renderRow).join('');
  };

  const render = function () {
    const title = props.title ? '<p>' + props.title + '</p>' : '';
    const tableId = props.tableId ? ' id="' + props.tableId + '"' : '';

    return `
      <div class="data-table-container">
        ${title}
        <table${tableId} class="node-search-table node-template-table">
          <thead>
            ${renderHeaderRow()}
          </thead>
          <tbody>${renderRows()}</tbody>
        </table>
      </div>
    `;
  };

  const setProps = function (nextProps) {
    props = _.assign({}, defaults, nextProps || {});
    return api;
  };

  const getProps = function () {
    return _.cloneDeep(props);
  };

  const api = {
    render: render,
    setProps: setProps,
    getProps: getProps
  };

  return api;
}

const NodeTableUtils = {
  getNodePointsRowsInfo: function (nodePoints) {
    if (_.isUndefined(nodePoints) || nodePoints.length === 0) {
      return [];
    }

    const nodePointsRows = _.map(nodePoints, function (point) {
      return {
        id: point.id,
        nodeNumber: point.nodeNumber,
        roadNumber: point.roadNumber,
        roadPartNumber: point.roadPartNumber,
        addr: point.addrM,
        beforeAfter: point.beforeAfter,
        type: point.type
      };
    });

    const groupedHomogeneousRows = _.groupBy(nodePointsRows, function (row) {
      return [row.roadNumber, row.roadPartNumber, row.addr];
    });

    const joinedHomogeneousRows = _.partition(groupedHomogeneousRows, function (group) {
      return group.length > 1;
    });

    const doubleRows = _.map(joinedHomogeneousRows[0], function (rows) {
      const first = _.head(rows);
      return {
        id: first.id,
        nodeNumber: first.nodeNumber,
        roadNumber: first.roadNumber,
        roadPartNumber: first.roadPartNumber,
        addr: first.addr,
        beforeAfter: 'EJ',
        type: first.type
      };
    });

    const singleRows = _.map(joinedHomogeneousRows[1], function (rows) {
      const first = _.head(rows);
      return {
        id: first.id,
        nodeNumber: first.nodeNumber,
        roadNumber: first.roadNumber,
        roadPartNumber: first.roadPartNumber,
        addr: first.addr,
        beforeAfter: (first.beforeAfter === 1 ? 'E' : 'J'),
        type: first.type
      };
    });

    return _.sortBy(doubleRows.concat(singleRows), ['roadNumber', 'roadPartNumber', 'track', 'addr', 'beforeAfter']);
  },

  getJunctionPointsInfo: function (junction) {
    const info = _.map((junction && junction.junctionPoints) || [], function (point) {
      return {
        id: point.id,
        roadNumber: point.roadNumber,
        roadPartNumber: point.roadPartNumber,
        track: point.track,
        addr: point.addrM,
        beforeAfter: point.beforeAfter
      };
    });

    const groupedHomogeneousRows = _.groupBy(info, function (row) {
      return [row.roadNumber, row.track, row.roadPartNumber, row.addr];
    });

    const joinedHomogeneousRows = _.partition(groupedHomogeneousRows, function (group) {
      return group.length > 1;
    });

    const doubleRows = _.map(joinedHomogeneousRows[0], function (rows) {
      const first = _.head(rows);
      const last = _.last(rows);
      return {
        id: Math.min(first.id, last.id) + '-' + Math.max(first.id, last.id),
        roadNumber: first.roadNumber,
        track: first.track,
        roadPartNumber: first.roadPartNumber,
        addr: first.addr,
        beforeAfter: 'EJ'
      };
    });

    const singleRows = _.map(joinedHomogeneousRows[1], function (rows) {
      const first = _.head(rows);
      return {
        id: first.id,
        roadNumber: first.roadNumber,
        track: first.track,
        roadPartNumber: first.roadPartNumber,
        addr: first.addr,
        beforeAfter: (first.beforeAfter === 1 ? 'E' : 'J')
      };
    });

    return _.sortBy(doubleRows.concat(singleRows), ['roadNumber', 'roadPartNumber', 'track', 'addr', 'beforeAfter']);
  },

  asFlexColumn: function (values, itemClassName) {
    const items = _.map(values || [], function (value) {
      return '<div class="' + itemClassName + '">' + value + '</div>';
    }).join('');
    return '<div class="node-flex-column">' + items + '</div>';
  },

  sortedTemplateRows: function (rows) {
    return _.chain(rows || [])
      .sortBy('addrM')
      .sortBy('track')
      .sortBy('roadPartNumber')
      .sortBy('roadNumber')
      .value();
  },

  nodePointTemplateRows: function (nodePointTemplates) {
    return _.uniqWith(nodePointTemplates || [], function (first, second) {
      return first.roadNumber === second.roadNumber &&
        first.roadPartNumber === second.roadPartNumber &&
        first.addrM === second.addrM;
    });
  },

  junctionTemplateRows: function (junctionTemplates) {
    return _.flatten(_.map(junctionTemplates || [], function (junction) {
      if (_.isEmpty(junction.junctionPoints)) {
        return [{
          id: junction.id,
          roadMaintainer: junction.roadMaintainer,
          evkCode: junction.evkCode || junction.elinvoimakeskusCode,
          elyCode: junction.elyCode,
          roadNumber: junction.roadNumber,
          track: junction.track,
          roadPartNumber: junction.roadPartNumber,
          addrM: junction.addrM,
          ej: junction.ej
        }];
      }

      return _.map(junction.junctionPoints || [], function (junctionPoint) {
        return {
          id: junction.id,
          roadMaintainer: junction.roadMaintainer || junctionPoint.roadMaintainer,
          evkCode: junction.evkCode || junction.elinvoimakeskusCode || junctionPoint.evkCode || junctionPoint.elinvoimakeskusCode,
          elyCode: junction.elyCode || junctionPoint.elyCode,
          roadNumber: junctionPoint.roadNumber,
          track: junctionPoint.track,
          roadPartNumber: junctionPoint.roadPartNumber,
          addrM: junctionPoint.addrM,
          ej: junction.ej
        };
      });
    }));
  },

  evkGroupLabel: function (evkCode) {
    if (_.isUndefined(evkCode) || _.isNull(evkCode) || evkCode === '' || evkCode === 'Muut') {
      return 'Muut';
    }

    const normalizedCodeAsString = String(evkCode).trim();
    const normalizedCodeAsNumber = Number(normalizedCodeAsString);

    const evkInfo = _.find(_.values(ViiteEnumerations.EVKCodes), function (entry) {
      if (_.isNaN(normalizedCodeAsNumber)) {
        return String(entry.value) === normalizedCodeAsString;
      }
      return Number(entry.value) === normalizedCodeAsNumber;
    });

    if (!evkInfo) {
      return 'Muut';
    }

    return '<label class="control-label evk-group-label">' + evkInfo.name + ' (' + evkInfo.value + ')</label>';
  },

  templateGroupKey: function (row) {
    const candidateFields = [row.roadMaintainer, row.evkCode, row.elyCode];
    const candidate = _.find(candidateFields, function (fieldValue) {
      return !_.isUndefined(fieldValue) && !_.isNull(fieldValue) && fieldValue !== '';
    });

    if (_.isUndefined(candidate)) {
      return 'Muut';
    }

    return String(candidate).trim();
  },

  toEvkGroups: function (items, rowMapper) {
    const grouped = _.groupBy(items || [], NodeTableUtils.templateGroupKey);

    return _.reduce(_.sortBy(Object.keys(grouped)), function (result, groupKey) {
      result[groupKey] = {
        label: NodeTableUtils.evkGroupLabel(groupKey),
        rows: _.map(NodeTableUtils.sortedTemplateRows(grouped[groupKey]), rowMapper)
      };
      return result;
    }, {});
  }
};

export { DataTable, NodeTableUtils };

