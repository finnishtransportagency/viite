(function (root) {
    root.RoadAddressBrowserForm = function () {

        function getRoadAddressChangesBrowserForm() {
            return '<form class="road-address-browser-form" id="roadAddressChangesBrowser">' +
                '<div class="input-container"><label class="control-label-small">Alkupvm</label> <input type="text" class="road-address-browser-date-input" id="roadAddrChangesStartDate" value="' + dateutil.getCurrentDateString() + '" required/></div>' +
                '<div class="input-container"><label class="control-label-small">Loppupvm</label> <input type="text" class="road-address-browser-date-input" id="roadAddrChangesEndDate" value="" /></div>' +
                '<div class="input-container">' +
                '<label class="control-label-small">Ely</label>' +
                    '<select name id="roadAddrChangesInputEly" /> ' +
                        createElyDropDownOptions() +
                    '</select>' +
                '</div>' +
                createRoadNumberInputField("roadAddrChangesInputRoad") +
                createRoadPartNumberInputFields("roadAddrChangesInputStartPart", "roadAddrChangesInputEndPart") +
                '<div style="display: flex; flex-direction: column; padding: 5px; white-space: nowrap">' +
                    '<div style="display: flex; flex-direction: row"><input type="radio" name="roadAddrChangesBrowserForm" value="ProjectAcceptedDate" checked="checked"><label style="margin-left: 5px; margin-top: 2.5px">Projektin hyväksymispvm</label></div>' +
                    '<div style="display: flex; flex-direction: row"><input type="radio" name="roadAddrChangesBrowserForm" value="RoadAddressStartDate"><label style="margin-left: 5px; margin-top: 2.5px">Muutoksen voimaantulopvm</label></div>' +
                '</div>' +
                createSearchButton("fetchRoadAddressChanges") +
                createExcelDownloadButton() +
                '</form>';
        }

        function getRoadAddressBrowserForm() {
            return '<form id="roadAddressBrowser" class="road-address-browser-form">' +
            '<div class="input-container"><label class="control-label-small">Tilannepvm</label> <input type="text" id="roadAddrStartDate" value="' + dateutil.getCurrentDateString() + '" style="width: 100px" required/></div>' +
            '<div class="input-container"><label class="control-label-small">Ely</label>' +
            '<select name id="roadAddrInputEly" /> ' +
                createElyDropDownOptions() +
            '</select>' +
            '</div>' +
            createRoadNumberInputField("roadAddrInputRoad") +
            createRoadPartNumberInputFields("roadAddrInputStartPart", "roadAddrInputEndPart") +
            '<div class="input-container"><label>Ajoradat</label><input type="radio" name="roadAddrBrowserForm" value="Tracks" checked="checked"></div>' +
            '<div class="input-container"><label>Tieosat</label><input type="radio" name="roadAddrBrowserForm" value="RoadParts"></div>' +
            '<div class="input-container"><label>Solmut</label><input type="radio" name="roadAddrBrowserForm" value="Nodes"></div>' +
            '<div class="input-container"><label>Liittymät</label><input type="radio" name="roadAddrBrowserForm" value="Junctions"></div>' +
            '<div class="input-container"><label>Tiennimet</label><input type="radio" name="roadAddrBrowserForm" value="RoadNames"></div>' +
            createSearchButton("fetchRoadAddresses") +
            createExcelDownloadButton() +
            '</form>';
        }

        function createElyDropDownOptions() {
            let html = '<option value="">--</option>';
            for (const ely in LinkValues.ElyCodes) {
                if (Object.prototype.hasOwnProperty.call(LinkValues.ElyCodes, ely))
                    html += '<option value="' + LinkValues.ElyCodes[ely].value + '">' + LinkValues.ElyCodes[ely].value + '(' + LinkValues.ElyCodes[ely].shortName + ')</option>';
            }
            return html;
        }

        function createRoadNumberInputField(id) {
            return '<div class="input-container"><label class="control-label-small">Tie</label><input class="road-address-browser-road-input" type="number" min="1" max="99999" id="' + id + '" /></div>';
        }

        function createRoadPartNumberInputFields(idStart, idEnd) {
            return  '<div class="input-container"><label class="control-label-small">Aosa</label><input type="number" min="1" max="999" id="' + idStart + '"/></div>' +
                    '<div class="input-container"><label class="control-label-small">Losa</label><input type="number" min="1" max="999" id="' + idEnd + '"/></div>';
        }

        function createExcelDownloadButton() {
            return '<button id="exportAsExcelFile" class="download-excel btn" disabled>Lataa Excelinä <i class="fas fa-file-excel"></i></button>';
        }

        function createSearchButton(id) {
            return '<button class="btn btn-primary" id="' + id + '"> Hae </button>';
        }

        return {
            getRoadRoadAddressChangesBrowserForm: getRoadAddressChangesBrowserForm,
            getRoadAddressBrowserForm: getRoadAddressBrowserForm
        };
    };
}(this));
