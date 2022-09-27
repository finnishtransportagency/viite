(function (root) {
    root.RoadAddressBrowserWindow = function (roadAddressCollection) {

        const MAX_ROWS_TO_DISPLAY = 100;

        const roadAddrBrowserWindow = $('<div id="road-address-browser-window" class="form-horizontal road-address-browser-window"></div>').hide();
        roadAddrBrowserWindow.append('<button class="close btn-close" id="closeRoadAddrBrowserWindow">x</button>');
        roadAddrBrowserWindow.append('<div class="content road-address-browser-header">Tieosoitteiden katselu</div>');
        roadAddrBrowserWindow.append('' +
            '<form id="roadAddressBrowser" class="road-address-browser-form">' +
                '<div class="input-container"><label class="control-label-small">Tilanne Pvm</label> <input type="date" id="roadAddrStartDate" value="' + getCurrentDate() + '" style="width: 100px" required/></div>' +
                '<div class="input-container"><label class="control-label-small">Ely</label><input type="number" min="1" max="14" id="roadAddrInputEly" /></div>' +
                '<div class="input-container"><label class="control-label-small">Tie</label><input type="number" min="1" max="99999" id="roadAddrInputRoad" /></div>' +
                '<div class="input-container"><label class="control-label-small">Aosa</label><input type="number" min="1" max="999" id="roadAddrInputStartPart"/></div>' +
                '<div class="input-container"><label class="control-label-small">Losa</label><input type="number" min="1" max="999" id="roadAddrInputEndPart"/></div>' +
                '<div class="input-container"><label>Tieosat</label><input type="radio" name="roadAddrBrowserForm" value="Roads" checked="checked"></div>' +
                '<div class="input-container"><label>Solmut</label><input type="radio" name="roadAddrBrowserForm" value="Nodes"></div>' +
                '<div class="input-container"><label>Liittymät</label><input type="radio" name="roadAddrBrowserForm" value="Junctions"></div>' +
                '<div class="input-container"><label>Tiennimet</label><input type="radio" name="roadAddrBrowserForm" value="RoadNames"></div>' +
                '<button class="btn btn-primary btn-fetch-road-addresses"> Hae </button>' +
                '<button id="exportAsExcelFile" class="download-excel btn" disabled>Lataa Excelinä <i class="fas fa-file-excel"></i></button>' +
            '</form>'
        );

        function showResultsForRoads() {
            const results = roadAddressCollection.getRoads();
            const table = $('<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table"></table>');
            table.append(
                '<thead>' +
                    '<tr>' +
                        '<th>Ely</th>' +
                        '<th>Tie</th>' +
                        '<th>Ajr</th>' +
                        '<th>Osa</th>' +
                        '<th>Aet</th>' +
                        '<th>Let</th>' +
                        '<th>Pituus</th>' +
                        '<th>Alkupäivämäärä</th>' +
                    '</tr>' +
                '</thead>'
            );
            results.forEach((resRow) => table.append(
                '<tr>' +
                    '<td>' + resRow.ely + '</td>' +
                    '<td>' + resRow.roadNumber + '</td>' +
                    '<td>' + resRow.track + '</td>' +
                    '<td>' + resRow.roadPartNumber + '</td>' +
                    '<td>' + resRow.startAddrM + '</td>' +
                    '<td>' + resRow.endAddrM + '</td>' +
                    '<td>' + resRow.lengthAddrM + '</td>' +
                    '<td>' + resRow.startDate + '</td>' +
                '</tr>'
            ));
            showData(results, table);
        }

        function showResultsForNodes() {
            const results = roadAddressCollection.getNodes();
            const table = $('<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table"></table>');
            table.append(
                '<thead>' +
                    '<tr>' +
                        '<th>Ely</th>' +
                        '<th>Tie</th>' +
                        '<th>Osa</th>' +
                        '<th>Et</th>' +
                        '<th>Alkupäivämäärä</th>' +
                        '<th>Tyyppi</th>' +
                        '<th>Nimi</th>' +
                        '<th>Solmunumero</th>' +
                    '</tr>' +
                '</thead>'
            );
            results.forEach((resRow) => table.append(
                '<tr>' +
                    '<td>' + resRow.ely + '</td>' +
                    '<td>' + resRow.roadNumber + '</td>' +
                    '<td>' + resRow.roadPartNumber + '</td>' +
                    '<td>' + resRow.addrM + '</td>' +
                    '<td>' + resRow.startDate + '</td>' +
                    '<td>' + resRow.nodeType + '</td>' +
                    '<td>' + resRow.nodeName + '</td>' +
                    '<td>' + resRow.nodeNumber + '</td>' +
                '</tr>'
            ));
            showData(results, table);
        }

        function showResultsForJunctions() {
            const results = roadAddressCollection.getJunctions();
            const table = $('<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table"></table>');
            table.append(
                '<thead>' +
                    '<tr>' +
                        '<th>Solmu-numero</th>' +
                        '<th>P-Koord</th>' +
                        '<th>I-Koord</th>' +
                        '<th>Nimi</th>' +
                        '<th>Solmu-tyyppi</th>' +
                        '<th>Alkupvm</th>' +
                        '<th>Liittymä-nro</th>' +
                        '<th>Tie</th>' +
                        '<th>Ajr</th>' +
                        '<th>Osa</th>' +
                        '<th>Et</th>' +
                        '<th>EJ</th>' +
                    '</tr>' +
                '</thead>'
            );
            results.forEach((resRow) => table.append(
                '<tr>' +
                    '<td>' + resRow.nodeNumber + '</td>' +
                    '<td>' + resRow.nodeCoordinates.y + '</td>' +
                    '<td>' + resRow.nodeCoordinates.x + '</td>' +
                    '<td>' + resRow.nodeName + '</td>' +
                    '<td>' + resRow.nodeType + '</td>' +
                    '<td>' + resRow.startDate + '</td>' +
                    '<td>' + resRow.junctionNumber + '</td>' +
                    '<td>' + resRow.roadNumber + '</td>' +
                    '<td>' + resRow.track + '</td>' +
                    '<td>' + resRow.roadPartNumber + '</td>' +
                    '<td>' + resRow.addrM + '</td>' +
                    '<td>' + resRow.beforeAfter + '</td>' +
                '</tr>'
            ));
            showData(results, table);
        }

        function showResultsForRoadNames() {
            const results = roadAddressCollection.getRoadNames();
            const table = $('<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table"></table>');
            table.append(
                '<thead>' +
                    '<tr>' +
                        '<th>Ely</th>' +
                        '<th>Tie</th>' +
                        '<th>Nimi</th>' +
                    '</tr>' +
                '</thead>'
            );
            results.forEach((resRow) => table.append(
                '<tr>' +
                    '<td>' + resRow.ely + '</td>' +
                    '<td>' + resRow.roadNumber + '</td>' +
                    '<td>' + resRow.roadName + '</td>' +
                '</tr>'
            ));
            showData(results, table);
        }

        function showData(results, table) {
            $('#exportAsExcelFile').prop("disabled", false);
            if (results.length <= MAX_ROWS_TO_DISPLAY) {
                roadAddrBrowserWindow.append(table);

            } else {
                // hide the results and notify user to download result table as excel file
                roadAddrBrowserWindow.append($('<p id="tableTooBigNotification"><b>Tulostaulu liian suuri, lataa tulokset excel taulukkona</b></p>'));
                roadAddrBrowserWindow.append(table.hide());
            }
        }


        function toggle() {
            $('.container').append('<div class="modal-overlay confirm-modal"><div class="modal-dialog"></div></div>');
            $('.modal-dialog').append(roadAddrBrowserWindow.toggle());
            bindEvents();
        }

        function getCurrentDate() {
            const today = new Date();
            const dayInNumber = today.getDate();
            const day = dayInNumber < 10 ? '0' + dayInNumber.toString() : dayInNumber.toString();
            const monthInNumber = today.getMonth() + 1;
            const month = monthInNumber < 10 ? '0' + monthInNumber.toString() : monthInNumber.toString();
            const year = today.getFullYear().toString();
            return year + '-' + month + '-' + day;
        }

        function hide() {
            $('.modal-dialog').append(roadAddrBrowserWindow.toggle());
            $('.modal-overlay').remove();
        }

        function exportDataAsExcelFile() {
            const timeInSeconds = new Date().getTime();
            const fileName = "Tieosoitteet_" + timeInSeconds.toString() + ".xlsx";
            const options = {raw: true};
            const wb = XLSX.utils.table_to_book(document.getElementById("roadAddressBrowserTable"), options);
            /* Export to file (start a download) */
            XLSX.writeFile(wb, fileName);
        }

        function getData(event) {
            const roadAddrStartDate   = document.getElementById('roadAddrStartDate');
            const ely                 = document.getElementById('roadAddrInputEly');
            const roadNumber          = document.getElementById('roadAddrInputRoad');
            const minRoadPartNumber   = document.getElementById('roadAddrInputStartPart');
            const maxRoadPartNumber   = document.getElementById('roadAddrInputEndPart');
            const targetValue        = $("input:radio[name ='roadAddrBrowserForm']:checked").val();

            //reset ely input field's custom validity
            ely.setCustomValidity("");


            function validateUserInput() {
                return roadAddrStartDate.reportValidity() &&
                    ely.reportValidity() &&
                    roadNumber.reportValidity() &&
                    minRoadPartNumber.reportValidity() &&
                    maxRoadPartNumber.reportValidity();
            }

            if (ely.value === "" && roadNumber.value === "") {
                event.preventDefault();
                ely.setCustomValidity("Ely tai Tie on pakollinen tieto");
                ely.reportValidity();
            } else if (validateUserInput()){
                const params = {
                    startDate: roadAddrStartDate.value,
                    target: targetValue
                };
                if (ely.value)
                    params.ely = ely.value;
                if (roadNumber.value)
                    params.roadNumber = roadNumber.value;
                if (minRoadPartNumber.value)
                    params.minRoadPartNumber = minRoadPartNumber.value;
                if (maxRoadPartNumber.value)
                    params.maxRoadPartNumber = maxRoadPartNumber.value;

                roadAddressCollection.fetchByTargetValue(params);
            }
        }

        eventbus.on('roadAddressBrowser:roadsFetched', function () {
            applicationModel.removeSpinner();
            showResultsForRoads();
        });

        eventbus.on('roadAddressBrowser:nodesFetched', function () {
           applicationModel.removeSpinner();
           showResultsForNodes();
        });

        eventbus.on('roadAddressBrowser:junctionsFetched', function () {
            applicationModel.removeSpinner();
            showResultsForJunctions();
        });

        eventbus.on('roadAddressBrowser:roadNamesFetched', function () {
            applicationModel.removeSpinner();
            showResultsForRoadNames();
        });

        function bindEvents() {
            roadAddrBrowserWindow.on('click', '#exportAsExcelFile', function () {
                exportDataAsExcelFile();
                return false; // cancel form submission
            });
            roadAddrBrowserWindow.on('click', 'button.close', function () {
                hide();
            });

            roadAddrBrowserWindow.on('click', '.btn-fetch-road-addresses', function (e) {
                $('.road-address-browser-window-results-table').remove(); // empty the result table
                $('#exportAsExcelFile').prop("disabled", true); //disable excel download button
                $('#tableTooBigNotification').remove(); // remove notification if present
                getData(e);
                return false; // cancel form submission
            });
        }

        return {
            toggle: toggle
        };
    };
}(this));
