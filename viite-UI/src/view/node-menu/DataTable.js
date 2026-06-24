import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';

/**
 * DataTable - Dumb, props-driven table renderer.
 * Parent prepares headers/rows/grouping; DataTable only renders HTML.
 *
 * Props:
 * - title:     string
 * - headers:   string[] | { label: string, className?: string }[]
 * - rows:      (string[] | { id?, className?, cells: (string | { content: string, className?, colspan? })[] })[]
 * - evkGroups: { [groupName]: { label?: string, rows: row[] } }
 * - tableId:   string
 *
 * Usage:
 *   const table = new DataTable();
 *
 *   table.setProps({
 *     title: 'Example',
 *     headers: ['A', 'B', { label: 'C', className: 'highlight' }],
 *     rows: [
 *       ['1', '2', '3'],
 *       { id: 42, className: 'clickable-row', cells: ['4', '5', { content: '6', className: 'bold' }] }
 *     ]
 *   }).render();
 *
 *   table.setProps({
 *     title: 'Grouped',
 *     headers: ['TIE', 'OSA', 'ET', 'EJ'],
 *     evkGroups: {
 *       'Uusimaa': { rows: [{ id: 10, className: 'node-point-template-link', cells: ['1', '2', '300', 'E'] }] },
 *       'Muut':    { rows: [{ id: 11, className: 'node-point-template-link', cells: ['4', '5', '600', 'J'] }] }
 *     }
 *   }).render();
 */
function DataTable(options) {
	const defaults = { title: '', headers: [], rows: [], evkGroups: null, tableId: '' };
	let props = _.assign({}, defaults, options || {});

	// --- Rendering helpers ---

	const renderTableHeader = function (header) {
		const { label, className } = _.isObject(header)
			? { label: header.label, className: header.className || '' }
			: { label: header, className: '' };
		const cls = className ? ` class="${className}"` : '';
		return `<th${cls}>${(label !== undefined && label !== null) ? label : ''}</th>`;
	};

	const renderCell = function (cell) {
		const { content, className, colspan } = _.isObject(cell) && !_.isArray(cell)
			? { content: cell.content, className: cell.className || '', colspan: cell.colspan || null }
			: { content: cell, className: '', colspan: null };
		const cls     = className ? ` class="${className}"` : '';
		const colAttr = colspan   ? ` colspan="${colspan}"` : '';
		return `<td${cls}${colAttr}>${(content !== undefined && content !== null) ? content : ''}</td>`;
	};

	const renderRow = function (row) {
		const { id, className, cells } = _.isArray(row)
			? { id: undefined, className: '', cells: row }
			: { id: row.id, className: row.className || '', cells: row.cells || [] };
		const idAttr  = (id !== undefined && id !== null) ? ` id="${id}"` : '';
		const cls     = className          ? ` class="${className}"` : '';
		return `<tr${idAttr}${cls}>${_.map(cells, renderCell).join('')}</tr>`;
	};

	// --- Core render ---

	const renderRows = function () {
		if (!_.isEmpty(props.evkGroups)) {
			const columnCount = (props.headers || []).length || 1;
			return _.map(_.keys(props.evkGroups), function (groupName) {
				const group  = props.evkGroups[groupName];
				const label  = _.get(group, 'label', groupName);
				const rows   = _.get(group, 'rows',  []);
				const header = `<tr><td colspan="${columnCount}" class="node-search-group-row">${label || 'Muut'}</td></tr>`;
				return header + _.map(rows, renderRow).join('');
			}).join('');
		}

		return _.map(props.rows || [], renderRow).join('');
	};

	const render = function () {
		const title   = props.title   ? `<p>${props.title}</p>` : '';
		const tableId = props.tableId ? ` id="${props.tableId}"` : '';
		return `
      <div class="data-table-container">
        ${title}
        <table${tableId} class="node-search-table node-template-table">
          <thead><tr>${_.map(props.headers || [], renderTableHeader).join('')}</tr></thead>
          <tbody>${renderRows()}</tbody>
        </table>
      </div>
    `;
	};

	const setProps = function (nextProps) {
		props = _.assign({}, defaults, nextProps || {});
		return api;
	};

	const api = { render, setProps };
	return api;
}

// ---------------------------------------------------------------------------
// NodeTableUtils
// Data-transformation helpers that prepare domain objects for DataTable.
// ---------------------------------------------------------------------------

const NodeTableUtils = {
	/**
   * Collapses node points that share the same road/part/addr into single rows,
   * marking them 'E', 'J', or 'EJ' based on their beforeAfter values.
   */
	getNodePointsRowsInfo: function (nodePoints) {
		if (_.isEmpty(nodePoints)) return [];

		const rows = _.map(nodePoints, function (p) {
			return {
				id:             p.id,
				nodeNumber:     p.nodeNumber,
				roadNumber:     p.roadNumber,
				roadPartNumber: p.roadPartNumber,
				addr:           p.addrM,
				beforeAfter:    p.beforeAfter,
				type:           p.type
			};
		});

		const grouped    = _.groupBy(rows, function (r) { return [r.roadNumber, r.roadPartNumber, r.addr]; });
		const [doubles, singles] = _.partition(grouped, function (g) { return g.length > 1; });

		const doubleRows = _.map(doubles, function (g) {
			const first = _.head(g);
			return { ...first, beforeAfter: 'EJ' };
		});

		const singleRows = _.map(singles, function (g) {
			const first = _.head(g);
			return { ...first, beforeAfter: first.beforeAfter === 1 ? 'E' : 'J' };
		});

		return _.sortBy(doubleRows.concat(singleRows), ['roadNumber', 'roadPartNumber', 'track', 'addr', 'beforeAfter']);
	},

	/**
   * Collapses junction points that share the same road/track/part/addr into
   * single rows, marking them 'E', 'J', or 'EJ'.
   */
	getJunctionPointsInfo: function (junction) {
		const points = (junction && junction.junctionPoints) || [];

		const rows    = _.map(points, function (p) {
			return {
				id:             p.id,
				roadNumber:     p.roadNumber,
				roadPartNumber: p.roadPartNumber,
				track:          p.track,
				addr:           p.addrM,
				beforeAfter:    p.beforeAfter
			};
		});

		const grouped    = _.groupBy(rows, function (r) { return [r.roadNumber, r.track, r.roadPartNumber, r.addr]; });
		const [doubles, singles] = _.partition(grouped, function (g) { return g.length > 1; });

		const doubleRows = _.map(doubles, function (g) {
			const [first, last] = [_.head(g), _.last(g)];
			return {
				...first,
				id:          Math.min(first.id, last.id) + '-' + Math.max(first.id, last.id),
				beforeAfter: 'EJ'
			};
		});

		const singleRows = _.map(singles, function (g) {
			const first = _.head(g);
			return { ...first, beforeAfter: first.beforeAfter === 1 ? 'E' : 'J' };
		});

		return _.sortBy(doubleRows.concat(singleRows), ['roadNumber', 'roadPartNumber', 'track', 'addr', 'beforeAfter']);
	},

	/** Wraps a list of values in a vertical flex column of divs. */
	asFlexColumn: function (values, itemClassName) {
		const items = _.map(values || [], function (v) {
			return `<div class="${itemClassName}">${v}</div>`;
		}).join('');
		return `<div class="node-flex-column">${items}</div>`;
	},

	/** Sorts template rows by road number → part → track → address. */
	sortedTemplateRows: function (rows) {
		return _.chain(rows || [])
			.sortBy('addrM')
			.sortBy('track')
			.sortBy('roadPartNumber')
			.sortBy('roadNumber')
			.value();
	},

	/** Deduplicates node-point templates by road/part/address. */
	nodePointTemplateRows: function (nodePointTemplates) {
		return _.uniqWith(nodePointTemplates || [], function (a, b) {
			return a.roadNumber === b.roadNumber &&
             a.roadPartNumber === b.roadPartNumber &&
             a.addrM === b.addrM;
		});
	},

	/** Flattens junction templates so each junction point becomes its own row. */
	junctionTemplateRows: function (junctionTemplates) {
		return _.flatten(_.map(junctionTemplates || [], function (junction) {
			if (_.isEmpty(junction.junctionPoints)) {
				return [{
					id:             junction.id,
					roadMaintainer: junction.roadMaintainer,
					evkCode:        junction.evkCode || junction.elinvoimakeskusCode,
					elyCode:        junction.elyCode,
					roadNumber:     junction.roadNumber,
					track:          junction.track,
					roadPartNumber: junction.roadPartNumber,
					addrM:          junction.addrM,
					ej:             junction.ej
				}];
			}

			return _.map(junction.junctionPoints, function (jp) {
				return {
					id:             junction.id,
					roadMaintainer: junction.roadMaintainer || jp.roadMaintainer,
					evkCode:        junction.evkCode || junction.elinvoimakeskusCode || jp.evkCode || jp.elinvoimakeskusCode,
					elyCode:        junction.elyCode || jp.elyCode,
					roadNumber:     jp.roadNumber,
					track:          jp.track,
					roadPartNumber: jp.roadPartNumber,
					addrM:          jp.addrM,
					ej:             junction.ej
				};
			});
		}));
	},

	/**
   * Returns the HTML label for an EVK group header, or 'Muut' for unknown codes.
   * Accepts numeric or string EVK codes.
   */
	evkGroupLabel: function (evkCode) {
		if (evkCode === undefined || evkCode === null || evkCode === '' || evkCode === 'Muut') return 'Muut';

		const codeStr = String(evkCode).trim();
		const codeNum = Number(codeStr);

		const evkInfo = _.find(_.values(ViiteEnumerations.EVKCodes), function (entry) {
			return _.isNaN(codeNum)
				? String(entry.value) === codeStr
				: Number(entry.value) === codeNum;
		});

		return evkInfo
			? `<label class="control-label evk-group-label">${evkInfo.name} (${evkInfo.value})</label>`
			: 'Muut';
	},

	/**
   * Returns the grouping key for a template row.
   * Prefers roadMaintainer → evkCode → elyCode, falling back to 'Muut'.
   */
	templateGroupKey: function (row) {
		const key = _.find([row.roadMaintainer, row.evkCode, row.elyCode], function (v) {
			return v !== undefined && v !== null && v !== '';
		});
		return (key !== undefined && key !== null) ? String(key).trim() : 'Muut';
	},

	/**
   * Groups items by templateGroupKey, sorts groups alphabetically,
   * and maps each group's rows with the provided rowMapper.
   * Returns an evkGroups-shaped object ready for DataTable.
   */
	toEvkGroups: function (items, rowMapper) {
		const grouped = _.groupBy(items || [], NodeTableUtils.templateGroupKey);

		return _.reduce(_.sortBy(Object.keys(grouped)), function (result, key) {
			result[key] = {
				label: NodeTableUtils.evkGroupLabel(key),
				rows:  _.map(NodeTableUtils.sortedTemplateRows(grouped[key]), rowMapper)
			};
			return result;
		}, {});
	}
};

export { DataTable, NodeTableUtils };