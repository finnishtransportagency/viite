// Displays data about clicked link
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';

export function LinkInfo(selectedLinkProperty) {

	// --- Main render function ---

	this.render = function (props) {
		const links = selectedLinkProperty.get();
		const count = selectedLinkProperty.count();
		const firstLink = _.head(links) || props;
		const isSingle = count === 1;

		return `
        <div class="wrapper read-only link-info-wrapper">
          <div class="form form-horizontal form-dark link-info-content">
            <div class="metadata-container">
              ${renderMetadata(props, links, count, firstLink, isSingle)}
            </div>

            <div class="attribute-section">
                ${renderAttributeSection(props, links, firstLink, isSingle)}
            </div>
          </div>
        </div>`;
	};

	// --- Metadata section (top of the panel) ---

	function renderMetadata(props, links, count, firstLink, isSingle) {
		return `
              <div class="form-group-metadata">
                 Muokattu viimeksi: ${withFallback(firstLink.modifiedBy, '-')} ${withFallback(firstLink.modifiedAt)}
              </div>
              <div class="form-group-metadata">Linkkien lukumäärä: ${withFallback(count, 0)}</div>
              <div class="form-group-metadata">
                 Geometrian lähde: ${withFallback(props.roadLinkSource)}${isSingle && props.mmlId ? '; MTKID: ' + props.mmlId : ''}
              </div>
              ${renderMunicipality(links)}
              ${renderLinkId(props, isSingle)}
              ${renderGeometryLength(props, links, isSingle)}`;
	}

	function renderMunicipality(links) {
		const firstMuni = _.get(links, '[0].municipalityName');
		const allSame = _.every(links, l => l.municipalityName === firstMuni);
		return (allSame && firstMuni) ? `<div class="form-group-metadata">Kunta: ${withFallback(firstMuni)}</div>` : '';
	}

	function renderLinkId(props, isSingle) {
		return isSingle ? `<div class="form-group-metadata">Linkin ID: ${withFallback(props.linkId)}</div>` : '';
	}

	// Total geometry length (endMValue - startMValue), distinct from the
	// address-based length shown in lengthField().
	function renderGeometryLength(props, links, isSingle) {
		const totalLength = isSingle
			? Math.round(props.endMValue - props.startMValue)
			: _.reduce(links, (sum, l) => sum + Math.round(l.endMValue - l.startMValue), 0);

		return `<div class="form-group-metadata">Geometrioiden yhteenlaskettu pituus: ${withFallback(totalLength)}</div>`;
	}

	// --- Attribute section (road number, part, track, distances, ...) ---

	function renderAttributeSection(props, links, firstLink, isSingle) {
		const roadNumbers = _.uniq(_.map(links, 'roadNumber'));
		const roadPartNumbers = _.uniq(_.map(links, 'roadPartNumber'));
		const roadNames = _.uniq(_.map(links, 'roadName').filter(name => name && name.trim() !== ''));
		const administrativeClasses = _.uniq(_.map(links, 'administrativeClassId'));
		const evkCodes = _.uniq(_.map(links, 'evkCode'));

		const isSameRoad = roadNumbers.length === 1;
		const isSamePart = isSameRoad && roadPartNumbers.length === 1;

		return `
                ${singleOrJoinedField('TIEN NIMI', isSingle, firstLink.roadName, roadNames)}
                ${singleOrJoinedField('TIENUMERO', isSingle, firstLink.roadNumber, roadNumbers)}

                ${conditionalField(isSameRoad, 'TIEOSANUMERO', () => dynamicField('TIEOSANUMERO', 'roadPartNumber', links))}
                ${conditionalField(isSamePart, 'AJORATA', () => dynamicField('AJORATA', 'trackCode', links))}
                ${conditionalField(isSamePart, 'ALKUETÄISYYS', () => staticField('ALKUETÄISYYS', _.get(props, 'addrMRange.start')))}
                ${conditionalField(isSamePart, 'LOPPUETÄISYYS', () => staticField('LOPPUETÄISYYS', _.get(props, 'addrMRange.end')))}

                ${lengthField(links)}
                ${singleOrJoinedDecodedField('ELINVOIMAKESKUS', isSingle, firstLink.evkCode, evkCodes)}
                ${singleOrJoinedDecodedField('HALLINNOLLINEN LUOKKA', isSingle, firstLink.administrativeClassId, administrativeClasses)}
                ${conditionalField(isSamePart, 'JATKUVUUS', () => dynamicField('JATKUVUUS', 'discontinuity', links))}
                ${conditionalField(isSamePart, 'ALKUPÄIVÄMÄÄRÄ', () => startDateField(links))}`;
	}

	// --- Field renderers / builders ---

	// Renders `renderFn()` only when `condition` holds, otherwise an empty field
	// with the same label. Used for fields that only make sense when every
	// selected link shares the same road / road part.
	function conditionalField(condition, label, renderFn) {
		return condition ? renderFn() : constructField(label, '');
	}

	// Renders a single value when exactly one link is selected, otherwise the
	// distinct values across all selected links, joined by comma.
	function singleOrJoinedField(label, isSingle, singleValue, values) {
		return isSingle
			? staticField(label, withFallback(singleValue))
			: constructField(label, values.map(v => withFallback(v)).join(', '));
	}

	// Same as singleOrJoinedField, but each multi-value entry is annotated with
	// its decoded description and rows are kept from wrapping.
	function singleOrJoinedDecodedField(label, isSingle, singleValue, values) {
		return isSingle
			? staticField(label, singleValue)
			: constructField(label, formatRowsNoWrap(values.map(v => `${withFallback(v)} ${decodeAttribute(label, v)}`.trim())));
	}

	// Renders every distinct value of `propertyName` across the selected links.
	function dynamicField(id, propertyName, links) {
		const uniqueValues = _.uniq(_.map(links, propertyName));
		const htmlContent = uniqueValues
			.map(v => `${withFallback(v)} ${decodeAttribute(id, v)}`)
			.join(', <br> ');
		return constructField(id, htmlContent);
	}

	function lengthField(links) {
		const hasAllDistances = _.every(links, l => {
			const start = _.get(l, 'addrMRange.start');
			const end = _.get(l, 'addrMRange.end');
			return Number.isFinite(start) && Number.isFinite(end);
		});

		const totalLength = hasAllDistances
			? _.reduce(links, (acc, l) => acc + (l.addrMRange.end - l.addrMRange.start), 0)
			: '';

		const label = (links.length === 1) ? 'PITUUS' : 'YHTEENLASKETTU PITUUS';
		return constructField(label, totalLength);
	}

	function startDateField(links) {
		const dates = _.compact(_.map(links, l => {
			if (!l.startDate) return null;
			const [d, m, y] = l.startDate.split('.');
			return new Date(y, m - 1, d);
		}));

		if (!dates.length) return constructField('ALKUPÄIVÄMÄÄRÄ', '');

		const latest = new Date(Math.max(...dates));
		const formatted = `${String(latest.getDate()).padStart(2, '0')}.${String(latest.getMonth() + 1).padStart(2, '0')}.${latest.getFullYear()}`;
		return constructField('ALKUPÄIVÄMÄÄRÄ', formatted);
	}

	// --- Low-level field construction ---

	function staticField(label, val) {
		const decoded = decodeAttribute(label, val);
		return `
        <div class="attribute-row attribute-row-static">
          <label class="attribute-label">${label}</label>
          <div class="attribute-value">${withFallback(val)} ${withFallback(decoded)}</div>
        </div>`;
	}

	function constructField(label, data) {
		return `
        <div class="attribute-row">
          <label class="attribute-label">${label}</label>
          <div class="attribute-value">${withFallback(data)}</div>
        </div>`;
	}

	// --- Attribute code -> description lookup ---

	function decodeAttribute(attrId, value) {
		if (value === null) return '';
		const options = ATTRIBUTE_OPTIONS[attrId];
		if (!options) return '';
		return Object.prototype.hasOwnProperty.call(options, value) ? options[value] : 'Ei määritelty';
	}

	function optionsFromEnum(enumObj, useName) {
		return _.reduce(enumObj, (options, i) => {
			options[i.value] = useName ? i.name : i.description;
			return options;
		}, {});
	}

	// Maps an attribute id (e.g. 'AJORATA') to a { value: description } lookup.
	const ATTRIBUTE_OPTIONS = {
		AJORATA: {
			0: 'Yksiajoratainen osuus',
			1: 'Oikeanpuoleinen ajorata',
			2: 'Vasemmanpuoleinen ajorata'
		},
		ELINVOIMAKESKUS: optionsFromEnum(ViiteEnumerations.EVKCodes, true),
		'HALLINNOLLINEN LUOKKA': {
			[ViiteEnumerations.AdministrativeClass.PublicRoad.value]: ViiteEnumerations.AdministrativeClass.PublicRoad.textValue,
			[ViiteEnumerations.AdministrativeClass.MunicipalityStreetRoad.value]: ViiteEnumerations.AdministrativeClass.MunicipalityStreetRoad.textValue,
			[ViiteEnumerations.AdministrativeClass.PrivateRoad.value]: ViiteEnumerations.AdministrativeClass.PrivateRoad.textValue,
			[ViiteEnumerations.AdministrativeClass.Unknown.value]: ViiteEnumerations.AdministrativeClass.Unknown.description
		},
		// Enum values win over the fallback default if 6 is ever defined there too
		// (mirrors the previous "first match wins" lookup order).
		JATKUVUUS: Object.assign({ 6: 'Rinnakkainen linkki' }, optionsFromEnum(ViiteEnumerations.Discontinuity, false))
	};

	// --- Formatting helpers ---

	// Helper to handle null/undefined/NaN values by returning a fallback string.
	function withFallback(val, fallback = '') {
		if (val === null || val === undefined || Number.isNaN(val)) return fallback;
		return val;
	}

	function formatRowsNoWrap(values) {
		return values
			.map(v => `<span style="white-space: nowrap; display: inline-block;">${withFallback(v)}</span>`)
			.join('<br>');
	}
}