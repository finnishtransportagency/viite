// This form is used to enter search criteria for road addresses, and supports CSV export
import { Selector } from '@components/dropdowns/MultiColumnDropdown.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { dateutil } from '@utils/DateUtils.js';
import { button } from '@components/button/Button.js';

export function createElyEvkSelectorData() {
  const evkItems = [];
  const elyItems = [];

  // Add Elinvoimakeskus items to first column
  if (typeof ViiteEnumerations !== 'undefined' && ViiteEnumerations.EVKCodes) {
    for (const evk in ViiteEnumerations.EVKCodes) {
      if (Object.prototype.hasOwnProperty.call(ViiteEnumerations.EVKCodes, evk)) {
        const evkData = ViiteEnumerations.EVKCodes[evk];
        evkItems.push({
          value: `EVK_${evkData.value}`,
          label: `${evkData.value} (${evkData.shortName})`
        });
      }
    }
  }

  // Add ELY items to second column
  if (typeof ViiteEnumerations !== 'undefined' && ViiteEnumerations.ElyCodes) {
    for (const ely in ViiteEnumerations.ElyCodes) {
      if (Object.prototype.hasOwnProperty.call(ViiteEnumerations.ElyCodes, ely)) {
        const elyData = ViiteEnumerations.ElyCodes[ely];
        elyItems.push({
          value: `ELY_${elyData.value}`,
          label: `${elyData.value} (${elyData.shortName})`
        });
      }
    }
  }

  return {
    0: {
      columnTitle: 'Elinvoimakeskus',
      items: evkItems
    },
    1: {
      columnTitle: 'ELY',
      items: elyItems
    }
  };
}

export function RoadAddressBrowserForm() {

    // Initialize multi-column selectors
    let dateTargetSelector, elyEvkSelector, targetSelector;

    function initializeSelectors() {
      // Date target selector for changes browser
      dateTargetSelector = new Selector({
        id: 'dateTarget',
        placeholder: 'Valitse rajausperuste',
        value: 'ProjectAcceptedDate',
        data: {
          0: {
            items: [
              { value: 'ProjectAcceptedDate', label: 'Projektin hyväksymispvm' },
              { value: 'RoadAddressStartDate', label: 'Muutoksen voimaantulopvm' }
            ]
          }
        }
      });

      // ELY/EVK selector for address browser
      elyEvkSelector = new Selector({
        id: 'roadAddrInputElyEvk',
        placeholder: 'Valitse Elinvoimakeskus / ELY',
        width: 240,
        data: createElyEvkSelectorData()
      });

      // Target selector for address browser
      targetSelector = new Selector({
        id: 'targetValue',
        placeholder: 'Valitse hakukohde',
        value: 'Tracks',
        width: 100,
        data: {
          0: {
            items: [
              { value: 'Tracks', label: 'Ajoradat' },
              { value: 'RoadParts', label: 'Tieosat' },
              { value: 'Nodes', label: 'Solmut' },
              { value: 'Junctions', label: 'Liittymät' },
              { value: 'RoadNames', label: 'Tiennimet' }
            ]
          }
        }
      });
    }

    function getRoadAddressChangesBrowserForm(onSearch, onCsvExport) {
      if (!dateTargetSelector) initializeSelectors();

      const html = `
        <form class="road-address-browser-form" id="roadAddressChangesBrowser">
          <div class="input-container">
            <label >Rajausperuste</label>
            ${dateTargetSelector.render()}
          </div>
          <div class="input-container">
            <label >Alkupvm</label>
            <div>
              <input type="text" class="modern-input road-address-browser-date-input" id="roadAddrChangesStartDate" style="width: 80px" required/>
            </div>
          </div>
          <div class="input-container"> <b style="margin-top: 25px"> - </b></div>
          <div class="input-container">
            <label >Loppupvm</label>
            <div>
              <input type="text" class="modern-input road-address-browser-date-input" id="roadAddrChangesEndDate" style="width: 80px" />
            </div>
          </div>
          ${createRoadNumberInputField('roadAddrChangesInputRoad')}
          ${createRoadPartNumberInputFields('roadAddrChangesInputStartPart', 'roadAddrChangesInputEndPart')}
          <div class="button-container">
            ${createSearchButton('fetchRoadAddressChanges', onSearch)}
            ${createCsvDownloadButton(onCsvExport)}
          </div>
        </form>`;

      return html;
    }

    function getRoadAddressBrowserForm(onSearch, onCsvExport) {
      if (!elyEvkSelector || !targetSelector) initializeSelectors();

      const html = `
        <form id="roadAddressBrowser" class="road-address-browser-form">
          <div class="input-container">
            <label>Tilannepvm</label>
            <div>
              <input type="text" class="modern-input" id="roadAddrSituationDate" value="${dateutil.getCurrentDateString()}" style="width: 90px !important" required />
            </div>
          </div>
          <div class="input-container">
            <label >Elinvoimakeskus / ELY</label>
            ${elyEvkSelector.render()}
          </div>
          ${createRoadNumberInputField('roadAddrInputRoad')}
          ${createRoadPartNumberInputFields('roadAddrInputStartPart', 'roadAddrInputEndPart')}
          <div class="input-container">
            <label >Hakukohde</label>
            ${targetSelector.render()}
          </div>
          <div class="button-container">
            ${createSearchButton('fetchRoadAddresses', onSearch)}
            ${createCsvDownloadButton(onCsvExport)}
          </div>
        </form>`;

      return html;
    }

    // Bind events for all selector components after form is rendered
    function bindSelectorEvents(container) {
      if (dateTargetSelector) dateTargetSelector.bindEvents(container);
      if (elyEvkSelector) elyEvkSelector.bindEvents(container);
      if (targetSelector) targetSelector.bindEvents(container);
    }

    function createRoadNumberInputField(id) {
      return `<div class="input-container"><label >Tie</label><input class="modern-input road-address-browser-road-input" type="number" min="1" max="99999" id="${id}" /></div>`;
    }

    function createRoadPartNumberInputFields(idStart, idEnd) {
      return `<div class="input-container"><label >Aosa</label><input class="modern-input" type="number" min="1" max="999" id="${idStart}"/></div>` +
        `<div class="input-container"><label >Losa</label><input class="modern-input" type="number" min="1" max="999" id="${idEnd}"/></div>`;
    }

    function createCsvDownloadButton(onClick) {
      return button({ id: 'exportAsCsvFile', label: 'Lataa CSV-tiedostona <i class="fas fa-file-excel"></i>', className: 'download-csv', disabled: true, onClick });
    }

    function createSearchButton(id, onClick) {
      return button({ id, label: 'Hae', className: 'btn-primary', onClick });
    }

    return {
      getRoadAddressChangesBrowserForm: getRoadAddressChangesBrowserForm,
      getRoadAddressBrowserForm: getRoadAddressBrowserForm,
      bindSelectorEvents: bindSelectorEvents,
      getSelectorComponents: function () {
        return {
          dateTarget: dateTargetSelector,
          elyEvk: elyEvkSelector,
          target: targetSelector
        };
      },
      setSelectorComponent: function (key, component) {
        switch (key) {
          case 'elyEvk':
            elyEvkSelector = component;
            break;
          default:
            break;
        }
      },
      initializeSelectors: initializeSelectors
    };
}
