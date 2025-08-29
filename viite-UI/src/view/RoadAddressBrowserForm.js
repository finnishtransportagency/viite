(function (root) {
  root.RoadAddressBrowserForm = function () {

    // Initialize multi-column selectors
    let dateTargetSelector, elyChangesSelector, elyEvkSelector, targetSelector;

    function initializeSelectors() {
      // Date target selector for changes browser
      dateTargetSelector = new Selector({
        id: 'dateTarget',
        placeholder: 'Valitse rajausperuste',
        data: {
          0: {
            columnTitle: 'Rajausperuste',
            items: [
              { value: 'ProjectAcceptedDate', label: 'Projektin hyväksymispvm' },
              { value: 'RoadAddressStartDate', label: 'Muutoksen voimaantulopvm' }
            ]
          },
          1: {
            columnTitle: 'Kuvaus',
            items: [
              { value: 'ProjectAcceptedDate', label: 'Päivämäärä jolloin projekti hyväksyttiin' },
              { value: 'RoadAddressStartDate', label: 'Päivämäärä jolloin muutos tuli voimaan' }
            ]
          }
        }
      });

      // ELY selector for changes browser
      elyChangesSelector = new Selector({
        id: 'roadAddrChangesInputEly',
        placeholder: 'Valitse ELY',
        data: createElyData()
      });

      // ELY/EVK selector for address browser
      elyEvkSelector = new Selector({
        id: 'roadAddrInputElyEvk',
        placeholder: 'Valitse ELY/EVK',
        width: 200,
        data: createElyEvkData()
      });

      // Target selector for address browser
      targetSelector = new Selector({
        id: 'targetValue',
        placeholder: 'Valitse hakukohde',
        value: 'Tracks',
        width: 120,
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

    function createElyData() {
      const elyItems = [];
      const elyDescriptions = [];
      
      if (typeof ViiteEnumerations !== 'undefined' && ViiteEnumerations.ElyCodes) {
        for (const ely in ViiteEnumerations.ElyCodes) {
          if (Object.prototype.hasOwnProperty.call(ViiteEnumerations.ElyCodes, ely)) {
            const elyData = ViiteEnumerations.ElyCodes[ely];
            elyItems.push({
              value: elyData.value,
              label: `${elyData.value} (${elyData.shortName})`
            });
            elyDescriptions.push({
              value: elyData.value,
              label: elyData.name || elyData.shortName
            });
          }
        }
      }

      return {
        0: {
          columnTitle: 'ELY-keskus',
          items: elyItems
        },
        1: {
          columnTitle: 'Nimi',
          items: elyDescriptions
        }
      };
    }

    function createElyEvkData() {
      const evkItems = [];
      const elyItems = [];

      // Add EVK items to first column
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
          columnTitle: 'EVK',
          items: evkItems
        },
        1: {
          columnTitle: 'ELY',
          items: elyItems
        }
      };
    }

    function getRoadAddressChangesBrowserForm() {
      if (!dateTargetSelector || !elyChangesSelector) {
        initializeSelectors();
      }

      const html = `
        <form class="road-address-browser-form" id="roadAddressChangesBrowser">
          <div class="input-container">
            <label class="control-label-small">Rajausperuste</label>
            ${dateTargetSelector.render()}
          </div>
          <div class="input-container">
            <label class="control-label-small">Alkupvm</label>
            <div>
              <input type="text" class="road-address-browser-date-input" id="roadAddrChangesStartDate" style="width: 80px" required/>
            </div>
          </div>
          <div class="input-container"> <b style="margin-top: 25px"> - </b></div>
          <div class="input-container">
            <label class="control-label-small">Loppupvm</label>
            <div>
              <input type="text" class="road-address-browser-date-input" id="roadAddrChangesEndDate" style="width: 80px" />
            </div>
          </div>
          <div class="input-container">
            <label class="control-label-small">Ely</label>
            ${elyChangesSelector.render()}
          </div>
          ${createRoadNumberInputField('roadAddrChangesInputRoad')}
          ${createRoadPartNumberInputFields('roadAddrChangesInputStartPart', 'roadAddrChangesInputEndPart')}
          <div class="road-address-browser-form-button-wrapper">
            ${createSearchButton('fetchRoadAddressChanges')}
            ${createCsvDownloadButton()}
          </div>
        </form>`;

      // Setup event delegation immediately
      dateTargetSelector.bindEvents();
      elyChangesSelector.bindEvents();

      return html;
    }

    function getRoadAddressBrowserForm() {
      if (!elyEvkSelector || !targetSelector) {
        initializeSelectors();
      }

      const html = `
        <form id="roadAddressBrowser" class="road-address-browser-form">
          <div class="input-container">
            <label class="control-label-small">Tilannepvm</label>
            <div>
              <input type="text" id="roadAddrSituationDate" value="${dateutil.getCurrentDateString()}" style="width: 80px" required />
            </div>
          </div>
          <div class="input-container">
            <label class="control-label-small">ELY/EVK</label>
            ${elyEvkSelector.render()}
          </div>
          ${createRoadNumberInputField('roadAddrInputRoad')}
          ${createRoadPartNumberInputFields('roadAddrInputStartPart', 'roadAddrInputEndPart')}
          <div class="input-container">
            <label class="control-label-small">Hakukohde</label>
            ${targetSelector.render()}
          </div>
          <div class="road-address-browser-form-button-wrapper">
            ${createSearchButton('fetchRoadAddresses')}
            ${createCsvDownloadButton()}
          </div>
        </form>`;

      // Setup event delegation immediately
      elyEvkSelector.bindEvents();
      targetSelector.bindEvents();

      return html;
    }

    // Bind events for all selector components after form is rendered
    function bindSelectorEvents(container) {
      if (dateTargetSelector) dateTargetSelector.bindEvents(container);
      if (elyChangesSelector) elyChangesSelector.bindEvents(container);
      if (elyEvkSelector) elyEvkSelector.bindEvents(container);
      if (targetSelector) targetSelector.bindEvents(container);
    }

    function createRoadNumberInputField(id) {
      return `<div class="input-container"><label class="control-label-small">Tie</label><input class="road-address-browser-road-input" type="number" min="1" max="99999" id="${id}" /></div>`;
    }

    function createRoadPartNumberInputFields(idStart, idEnd) {
      return `<div class="input-container"><label class="control-label-small">Aosa</label><input type="number" min="1" max="999" id="${idStart}"/></div>` +
        `<div class="input-container"><label class="control-label-small">Losa</label><input type="number" min="1" max="999" id="${idEnd}"/></div>`;
    }

    function createCsvDownloadButton() {
      return '<button id="exportAsCsvFile" class="download-csv btn" disabled>Lataa CSV-tiedostona <i class="fas fa-file-excel"></i></button>';
    }

    function createSearchButton(id) {
      return `<button class="btn btn-primary" id="${id}"> Hae </button>`;
    }

    return {
      getRoadRoadAddressChangesBrowserForm: getRoadAddressChangesBrowserForm,
      getRoadAddressBrowserForm: getRoadAddressBrowserForm,
      bindSelectorEvents: bindSelectorEvents,
      getSelectorComponents: function () {
        return {
          dateTarget: dateTargetSelector,
          elyChanges: elyChangesSelector,
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
  };
}(this));
