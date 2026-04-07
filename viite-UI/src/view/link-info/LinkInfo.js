// Displays data about clicked link
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';

export function LinkInfo(selectedLinkProperty) {

    // Helper to handle null/undefined values by returning a fallback string.
    function withFallback(val) {
      if (val === null || val === undefined) return '';
      return val;
    }

    function createAttributesFromEnum(enumObj, useName) {
      return _.map(enumObj, i => ({
        value: i.value,
        description: useName ? i.name : i.description
      }));
    }

    const decodedAttributes = [
      {
        id: 'AJORATA',
        attributes: [
          { value: 0, description: "Yksiajoratainen osuus" },
          { value: 1, description: "Oikeanpuoleinen ajorata" },
          { value: 2, description: "Vasemmanpuoleinen ajorata" }
        ]
      },
      { id: 'Elinvoimakeskus', attributes: createAttributesFromEnum(ViiteEnumerations.EVKCodes, true) },
      {
        id: 'HALLINNOLLINEN LUOKKA',
        attributes: [
          { value: ViiteEnumerations.AdministrativeClass.PublicRoad.value, description: ViiteEnumerations.AdministrativeClass.PublicRoad.textValue },
          { value: ViiteEnumerations.AdministrativeClass.MunicipalityStreetRoad.value, description: ViiteEnumerations.AdministrativeClass.MunicipalityStreetRoad.textValue },
          { value: ViiteEnumerations.AdministrativeClass.PrivateRoad.value, description: ViiteEnumerations.AdministrativeClass.PrivateRoad.textValue },
          { value: ViiteEnumerations.AdministrativeClass.Unknown.value, description: ViiteEnumerations.AdministrativeClass.Unknown.description }
        ]
      },
      { id: 'JATKUVUUS', attributes: createAttributesFromEnum(ViiteEnumerations.Discontinuity, false).concat([{ value: 6, description: "Rinnakkainen linkki" }]) }
    ];

    function decodeAttributes(attrId, value) {
      if (value === null) return "";

      const category = _.find(decodedAttributes, o => o.id === attrId);
      if (category) {
        const attribute = _.find(category.attributes, a => a.value === value);
        return attribute ? attribute.description : "Ei määritelty";
      }
      return "";
    }

    function showMunicipality() {
      const links = selectedLinkProperty.get();
      const firstMuni = _.get(links, '[0].municipalityName');
      const allSame = _.every(links, l => l.municipalityName === firstMuni);
      return (allSame && firstMuni) ? `<div class="form-group-metadata">Kunta: ${withFallback(firstMuni)}</div>` : '';
    }

    function showLinkId(props) {
      return (selectedLinkProperty.count() === 1) ? `<div class="form-group-metadata">Linkin ID: ${withFallback(props.linkId)}</div>` : '';
    }

    function showLinkLength(props) {
      const links = selectedLinkProperty.get();
      const totalLength = (selectedLinkProperty.count() === 1)
        ? Math.round(props.endMValue - props.startMValue)
        : _.reduce(links, (sum, l) => sum + Math.round(l.endMValue - l.startMValue), 0);

      return `<div class="form-group-metadata">Geometrian pituus: ${withFallback(totalLength)}</div>`;
    }

    function constructField(label, data) {
      return `
        <div class="attribute-row">
          <label class="attribute-label">${label}</label>
          <div class="attribute-value">${withFallback(data)}</div>
        </div>`;
    }

    function staticField(label, val) {
      const decoded = decodeAttributes(label, val);
      return `
        <div class="attribute-row attribute-row-static">
          <label class="attribute-label">${label}</label>
          <div class="attribute-value">${withFallback(val)} ${withFallback(decoded)}</div>
        </div>`;
    }

    function dynamicField(id, propertyName) {
      const uniqueValues = _.uniq(_.map(selectedLinkProperty.get(), propertyName));
      const htmlContent = _.map(uniqueValues, v => {
          const val = withFallback(v);
          const desc = decodeAttributes(id, v);
          return `${val} ${desc}`;
      }).join(', <br> ');
      return constructField(id, htmlContent);
    }

    function lengthDynamicField() {
      const links = selectedLinkProperty.get();
      const totalLen = _.reduce(links, (acc, l) => {
          const start = _.get(l, 'addrMRange.start', 0);
          const end = _.get(l, 'addrMRange.end', 0);
          return acc + (end - start);
      }, 0);
      const label = (links.length === 1) ? 'PITUUS' : 'YHTEENLASKETTU PITUUS';
      return constructField(label, totalLen);
    }

    function dateDynamicField() {
      const dates = _.compact(_.map(selectedLinkProperty.get(), l => {
        if (!l.startDate) return null;
        const [d, m, y] = l.startDate.split(".");
        return new Date(y, m - 1, d);
      }));

      if (!dates.length) return constructField('ALKUPÄIVÄMÄÄRÄ', '');

      const maxDate = new Date(Math.max(...dates));
      const formattedDate = `${String(maxDate.getDate()).padStart(2, '0')}.${String(maxDate.getMonth() + 1).padStart(2, '0')}.${maxDate.getFullYear()}`;
      return constructField('ALKUPÄIVÄMÄÄRÄ', formattedDate);
    }

    // --- Main Render Function ---
    this.render = function (props) {
      const links = selectedLinkProperty.get();
      const count = selectedLinkProperty.count();
      const firstLink = _.head(links) || props;
      const isSingle = count === 1;

      const roadNumbers = _.uniq(_.map(links, 'roadNumber'));
      const roadPartNumbers = _.uniq(_.map(links, 'roadPartNumber'));
      const roadNames = _.uniq(_.map(links, 'roadName').filter(name => name && name.trim() !== ''));
      const administrativeClasses = _.uniq(_.map(links, 'administrativeClassId'));
      const evkCodes = _.uniq(_.map(links, 'evkCode'));
      
      const isSameRoad = roadNumbers.length === 1;
      const isSamePart = isSameRoad && roadPartNumbers.length === 1;

      return `
        <header class="link-info-header"><span>Tieosoitteen ominaisuustiedot</span></header>
        <div class="wrapper read-only link-info-wrapper">
          <div class="form form-horizontal form-dark link-info-content">
            <div class="metadata-container">
              <div class="form-group-metadata">
                 Muokattu viimeksi: ${withFallback(firstLink.modifiedBy, '-')} ${withFallback(firstLink.modifiedAt)}
              </div>
              <div class="form-group-metadata">Linkkien lukumäärä: ${withFallback(count, 0)}</div>
              <div class="form-group-metadata">
                 Geometrian lähde: ${withFallback(props.roadLinkSource)}${isSingle && props.mmlId ? '; MTKID: ' + props.mmlId : ''}
              </div>
              ${showMunicipality()}
              ${showLinkId(props)}
              ${showLinkLength(props)}
            </div>

            <div class="attribute-section">
                ${isSingle ? staticField('TIEN NIMI', withFallback(firstLink.roadName)) : constructField('TIEN NIMI', roadNames.length > 0 ? roadNames.map(v => withFallback(v)).join(', ') : roadNumbers.map(v => withFallback(v)).join(', '))}
                ${isSingle ? staticField('TIENUMERO', withFallback(firstLink.roadNumber)) : constructField('TIENUMERO', roadNumbers.map(v => withFallback(v)).join(', '))}
                
                ${isSameRoad ? dynamicField('TIEOSANUMERO', 'roadPartNumber') : constructField('TIEOSANUMERO', '')}
                ${isSamePart ? dynamicField('AJORATA', 'trackCode') : constructField('AJORATA', '')}
                ${isSamePart ? staticField('ALKUETÄISYYS', _.get(props, 'addrMRange.start')) : constructField('ALKUETÄISYYS', '')}
                ${isSamePart ? staticField('LOPPUETÄISYYS', _.get(props, 'addrMRange.end')) : constructField('LOPPUETÄISYYS', '')}
                
                ${lengthDynamicField()}
                ${isSingle ? staticField('Elinvoimakeskus', firstLink.evkCode) : constructField('Elinvoimakeskus', evkCodes.map(v => withFallback(v) + ' ' + decodeAttributes('Elinvoimakeskus', v)).join(', '))}
                ${isSingle ? staticField('HALLINNOLLINEN LUOKKA', firstLink.administrativeClassId) : constructField('HALLINNOLLINEN LUOKKA', administrativeClasses.map(v => withFallback(v) + ' ' + decodeAttributes('HALLINNOLLINEN LUOKKA', v)).join(', '))}
                ${isSamePart ? dynamicField('JATKUVUUS', 'discontinuity') : constructField('JATKUVUUS', '')}
                ${isSamePart ? dateDynamicField() : constructField('ALKUPÄIVÄMÄÄRÄ', '')}
            </div>
          </div>
        </div><footer></footer>`;
    };
}
