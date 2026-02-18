/* eslint-disable new-cap */
(function (root) {
  root.DevAddressTool = function (prefix, editableStatus) {
    const addSmallInputNumber = function (id, value, isDisabled, maxLength, customWidth) {
      //Validate only number characters on "onkeypress" including TAB and backspace
      const disabled = isDisabled ? ' readonly="readonly" ' : '';
      const customStyle = customWidth ? `style="${customWidth}"` : '';
      return `<input type="text" ${customStyle} onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode == 8 || event.keyCode == 9)" class="${prefix}form-control small-input roadAddressProject" id="${id}" value="${_.isUndefined(value) ? '' : value}" ${disabled} ${_.isUndefined(maxLength) ? '' : `maxlength="${maxLength}"`} onclick=""/>`;
    };

    const render = function (links, project) {
      const projectEditable = project.statusCode === editableStatus;
      const startOfSection = Math.min(...links.map((link) => Number(link.addrMRange.start)));
      const endOfSection   = Math.max(...links.map((link) => Number(link.addrMRange.end)));
      const originalStartOfSection = Math.min(...links.map((link) => Number(link.originalStartAddressM)));
      const originalEndOfSection   = Math.max(...links.map((link) => Number(link.originalEndAddressM)));
      let sideCodeDropDown = '';

      if (links.length === 1) {
        const sideCodeValue = links[0].sideCode;
        const label = `<label>Linkin SideCode</label>`;
        const dropDown = `<select class="${prefix}form-control administrativeClassAndRoadName" id="sideCodeDropdown" size="1">
          <option ${sideCodeValue === 9 ? 'selected' : ''} value="9">Unknown</option>
          <option ${sideCodeValue === 2 ? 'selected' : ''} value="2">Towards Digitizing</option>
          <option ${sideCodeValue === 3 ? 'selected' : ''} value="3">Against Digitizing</option>
        </select>`;
        sideCodeDropDown = `${label}${dropDown}`;
      }

      return `
        <div class="dev-address-tool" hidden="true">
          <div class="dev-address-tool-wrapper">
            <div>
              <label>Osoitteiden hallinta (dev työkalu)</label>
            </div>
            <div class="dev-wrapper-column">
              <label>CP linkin alussa</label>
              <select class="${prefix}form-control administrativeClassAndRoadName" id="startCPDropdown" size="1">
                <option value="0" selected>NoCp</option>
                <option value="1">UserDefinedCP</option>
                <option value="2">JunctionPointCP</option>
                <option value="3">RoadAddressCP</option>
              </select>
            </div>
            <div class="dev-wrapper-column">
              <label>CP linkin lopussa</label>
              <select class="${prefix}form-control administrativeClassAndRoadName" id="endCPDropdown" size="1">
                <option value="0" selected>NoCp</option>
                <option value="1">UserDefinedCP</option>
                <option value="2">JunctionPointCP</option>
                <option value="3">RoadAddressCP</option>
              </select>
            </div>
            <label>Uusi osoite:</label>
            <div class="dev-address-field-wrapper">
              <div class="dev-addressfield">
                <label>Alku</label> ${addSmallInputNumber('addrStart', startOfSection, !projectEditable, 5)}
              </div>
              <div class="dev-addressfield">
                <label>Loppu</label> ${addSmallInputNumber('addrEnd', endOfSection, !projectEditable, 5)}
              </div>
              <div class="dev-addressfield">
                <label class="pituus-label">Pituus</label>
                <p id="addrLength" style="color: white;">${endOfSection - startOfSection}</p>
              </div>
            </div>
            <label>Alkuperäinen osoite:</label>
            <div class="dev-address-field-wrapper">
              <div class="dev-addressfield">
                <label>Alku</label> ${addSmallInputNumber('origAddrStart', originalStartOfSection, !projectEditable, 5)}
              </div>
              <div class="dev-addressfield">
                <label>Loppu</label> ${addSmallInputNumber('origAddrEnd', originalEndOfSection, !projectEditable, 5)}
              </div>
              <div class="dev-addressfield">
                <label class="pituus-label">Alkup. Pituus</label>
                <p id="origAddrLength" style="color: white;">${originalEndOfSection - originalStartOfSection}</p>
              </div>
            </div>
            <div class="dev-wrapper-row">
              <input type="checkbox" id="newRoadwayNumber"/>
              <label> Uusi Roadway numero valituille linkeille</label>
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
  };
}(window));
