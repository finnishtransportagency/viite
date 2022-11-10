(function (root) {
    root.RoadAddressBrowserForm = function () {

        function getRoadAddressChangesBrowserForm() {
            return '<form class="road-address-browser-form" id="roadAddressChangesBrowser">' +
                '<div class="input-container">' +
                    '<label class="control-label-small">Rajausperuste</label>' +
                    '<select id="dateTarget">' +
                        '<option value="ProjectAcceptedDate">Projektin hyväksymispvm</option>' +
                        '<option value="RoadAddressStartDate">Muutoksen voimaantulopvm</option>' +
                    '</select>' +
                '</div>' +
                '<div class="input-container"><label class="control-label-small">Alkupvm</label> <input type="text" class="road-address-browser-date-input" id="roadAddrChangesStartDate"required/></div>' +
                '<div class="input-container"> <b style="margin-top: 20px"> - </b></div>' +
                '<div class="input-container"><label class="control-label-small">Loppupvm</label> <input type="text" class="road-address-browser-date-input" id="roadAddrChangesEndDate" /></div>' +
                '<div class="input-container">' +
                    '<label class="control-label-small">Ely</label>' +
                    '<select name id="roadAddrChangesInputEly" /> ' +
                        createElyDropDownOptions() +
                    '</select>' +
                '</div>' +
                createRoadNumberInputField("roadAddrChangesInputRoad") +
                createRoadPartNumberInputFields("roadAddrChangesInputStartPart", "roadAddrChangesInputEndPart") +
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
            '<div class="input-container">' +
                '<label class="control-label-small">Hakukohde</label>' +
                '<select id="targetValue">' +
                    '<option value="Tracks">Ajoradat</option>' +
                    '<option value="RoadParts">Tieosat</option>' +
                    '<option value="Nodes">Solmut</option>' +
                    '<option value="Junctions">Liittymät</option>' +
                    '<option value="RoadNames">Tiennimet</option>' +
                '</select>' +
            '</div>' +
            createSearchButton("fetchRoadAddresses") +
            createExcelDownloadButton() +
            '</form>';
        }

        function createElyDropDownOptions() {
            let html = '<option value="">--</option>';
            for (const ely in ViiteEnumerations.ElyCodes) {
                if (Object.prototype.hasOwnProperty.call(ViiteEnumerations.ElyCodes, ely))
                    html += '<option value="' + ViiteEnumerations.ElyCodes[ely].value + '">' + ViiteEnumerations.ElyCodes[ely].value + '(' + ViiteEnumerations.ElyCodes[ely].shortName + ')</option>';
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
