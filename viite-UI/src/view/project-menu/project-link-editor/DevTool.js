import { dropdown } from '@components/dropdowns/Dropdown.js';
import { numberInput } from '@components/number-input/NumberInput.js';

/**
 * DevAddressTool component
 * Provides development tools for making direct edits to address data (calibration points, side codes, etc.)
 * Only available to users with development rights.
 * @param {string} prefix - CSS class prefix for form controls
 * @returns {Object} Component with render method
 */
export function DevAddressTool(prefix) {

    const render = function (links) {
      const startOfSection = Math.min(...links.map((link) => Number(link.addrMRange.start)));
      const endOfSection   = Math.max(...links.map((link) => Number(link.addrMRange.end)));
      const originalStartOfSection = Math.min(...links.map((link) => Number(link.originalStartAddressM)));
      const originalEndOfSection   = Math.max(...links.map((link) => Number(link.originalEndAddressM)));
      let sideCodeDropDown = '';

      if (links.length === 1) {
        const sideCodeValue = links[0].sideCode;
        const label = `<label class="dev-label">Linkin SideCode</label>`;
        
        const sideCodeConfig = {
          id: 'sideCodeDropdown',
          className: `${prefix}form-control administrativeClassAndRoadName`,
          defaultValue: sideCodeValue,
          options: [
            { value: '9', text: 'Unknown' },
            { value: '2', text: 'Towards Digitizing' },
            { value: '3', text: 'Against Digitizing' }
          ]
        };
        
        const dropDown = dropdown(sideCodeConfig);
        sideCodeDropDown = `${label}${dropDown}`;
      }

      return `
        <div class="dev-address-tool" hidden="true">
          <div class="dev-address-tool-wrapper">
            <div>
              <label class="dev-label">DEV-työkalu</label>
            </div>
            <div class="dev-wrapper-column">
              <label class="dev-label">CP linkin alussa</label>
              ${dropdown({
                id: 'startCPDropdown',
                className: `${prefix}form-control administrativeClassAndRoadName`,
                defaultValue: '0',
                options: [
                  { value: '0', text: 'NoCp' },
                  { value: '1', text: 'UserDefinedCP' },
                  { value: '2', text: 'JunctionPointCP' },
                  { value: '3', text: 'RoadAddressCP' }
                ]
              })}
            </div>
            <div class="dev-wrapper-column">
              <label class="dev-label">CP linkin lopussa</label>
              ${dropdown({
                id: 'endCPDropdown',
                className: `${prefix}form-control administrativeClassAndRoadName`,
                defaultValue: '0',
                options: [
                  { value: '0', text: 'NoCp' },
                  { value: '1', text: 'UserDefinedCP' },
                  { value: '2', text: 'JunctionPointCP' },
                  { value: '3', text: 'RoadAddressCP' }
                ]
              })}
            </div>
            <label class="dev-label">Uusi osoite:</label>
            <div class="dev-address-field-wrapper">
              <div class="dev-addressfield">
                <label class="dev-label">Alku</label> ${numberInput('addrStart', 5, false, startOfSection)}
              </div>
              <div class="dev-addressfield">
                <label class="dev-label">Loppu</label> ${numberInput('addrEnd', 5, false, endOfSection)}
              </div>
              <div class="dev-addressfield">
                <label class="pituus-label dev-label">Pituus</label>
                <p id="addrLength" class="dev-length">${endOfSection - startOfSection}</p>
              </div>
            </div>
            <label class="dev-label">Alkuperäinen osoite:</label>
            <div class="dev-address-field-wrapper">
              <div class="dev-addressfield">
                <label class="dev-label">Alku</label> ${numberInput('origAddrStart', 5, false, originalStartOfSection)}
              </div>
              <div class="dev-addressfield">
                <label class="dev-label">Loppu</label> ${numberInput('origAddrEnd', 5, false, originalEndOfSection)}
              </div>
              <div class="dev-addressfield">
                <label class="pituus-label dev-label">Alkup. Pituus</label>
                <p id="origAddrLength" class="dev-length">${originalEndOfSection - originalStartOfSection}</p>
              </div>
            </div>
            <div class="dev-wrapper-row">
              <input type="checkbox" id="newRoadwayNumber"/>
              <label class="dev-label"> Uusi Roadway numero valituille linkeille</label>
            </div>
            <div class="dev-wrapper-column">
                ${sideCodeDropDown}
            </div>
          </div>
        </div>`;
    };

    // Public API
    return {
      render: render
    };
  }
