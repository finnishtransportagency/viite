(function (root) {
    root.RoadAddressBrowserWindow = function (backend) {

        const MAX_ROWS_TO_DISPLAY = 100;
        const MAX_YEAR_PARAM = 2100;
        const MIN_YEAR_PARAM = 1800;
        const MAX_LENGTH_FOR_ROAD_NUMBER = 5;
        const MAX_LENGTH_FOR_ROAD_PART_NUMBER = 3;
        let searchParams = {};
        let datePicker = '';
        const me = this;

        const roadAddrBrowserWindow = $('<div id="road-address-browser-window" class="form-horizontal road-address-browser-window"></div>').hide();
        roadAddrBrowserWindow.append('<a href="manual/index.html#!index.md#10_Tieosoitteiden_katselu_-ty%C3%B6kalu" target="_blank">' +
                                        '<button class="btn-manual" title="Avaa käyttöohje">' +
                                            '<i class="fas fa-question"></i>' +
                                        '</button>' +
                                    '</a>');
        roadAddrBrowserWindow.append('<button class="close btn-close" id="closeRoadAddrBrowserWindow">x</button>');
        roadAddrBrowserWindow.append('<div class="content road-address-browser-header">Tieosoitteiden katselu</div>');
        roadAddrBrowserWindow.append('' +
            '<form id="roadAddressBrowser" class="road-address-browser-form">' +
                '<div class="input-container"><label class="control-label-small">Tilannepvm</label> <input type="text" id="roadAddrStartDate" value="' + getCurrentDate() + '" style="width: 100px" required/></div>' +
                '<div class="input-container"><label class="control-label-small">Ely</label>' +
                    '<select name id="roadAddrInputEly" /> ' +
                        '<option value="">--</option>' +
                        '<option value="1">1 (UUD)</option>' +
                        '<option value="2">2 (VAR)</option>' +
                        '<option value="3">3 (KAS)</option>' +
                        '<option value="4">4 (PIR)</option>' +
                        '<option value="8">8 (POS)</option>' +
                        '<option value="9">9 (KES)</option>' +
                        '<option value="10">10 (EPO)</option>' +
                        '<option value="12">12 (POP)</option>' +
                        '<option value="14">14 (LAP)</option>' +
                    '</select>' +
                '</div>' +
                '<div class="input-container"><label class="control-label-small">Tie</label><input type="number" min="1" max="99999" id="roadAddrInputRoad" /></div>' +
                '<div class="input-container"><label class="control-label-small">Aosa</label><input type="number" min="1" max="999" id="roadAddrInputStartPart"/></div>' +
                '<div class="input-container"><label class="control-label-small">Losa</label><input type="number" min="1" max="999" id="roadAddrInputEndPart"/></div>' +
                '<div class="input-container"><label>Ajoradat</label><input type="radio" name="roadAddrBrowserForm" value="Tracks" checked="checked"></div>' +
                '<div class="input-container"><label>Solmut</label><input type="radio" name="roadAddrBrowserForm" value="Nodes"></div>' +
                '<div class="input-container"><label>Liittymät</label><input type="radio" name="roadAddrBrowserForm" value="Junctions"></div>' +
                '<div class="input-container"><label>Tiennimet</label><input type="radio" name="roadAddrBrowserForm" value="RoadNames"></div>' +
                '<button class="btn btn-primary btn-fetch-road-addresses"> Hae </button>' +
                '<button id="exportAsExcelFile" class="download-excel btn" disabled>Lataa Excelinä <i class="fas fa-file-excel"></i></button>' +
            '</form>'
        );

        function showResultsForTracks(results) {
            const arr = [];
            let arrPointer = -1;
            arr[++arrPointer] = `<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table">
                                    <thead>
                                        <tr>
                                            <th>Ely</th>
                                            <th>Tie</th>
                                            <th>Ajr</th>
                                            <th>Osa</th>
                                            <th>Aet</th>
                                            <th>Let</th>
                                            <th>Pituus</th>
                                            <th>Hallinnollinen luokka</th>
                                            <th>Alkupäivämäärä</th>
                                        </tr>
                                    </thead>
                                    <tbody>`;
            for (let i = 0, len = results.length; i < len; i++) {
                arr[++arrPointer] =`    <tr>
                                            <td>${results[i].ely}</td>
                                            <td>${results[i].roadNumber}</td>
                                            <td>${results[i].track}</td>
                                            <td>${results[i].roadPartNumber}</td>
                                            <td>${results[i].startAddrM}</td>
                                            <td>${results[i].endAddrM}</td>
                                            <td>${results[i].lengthAddrM}</td>
                                            <td>${results[i].administrativeClass}</td>
                                            <td>${results[i].startDate}</td>
                                        </tr>`;
            }
            arr[++arrPointer] =`    </tbody>
                            </table>`;
            const table = $(arr.join('')); // join the array to one large string and create jquery element from said string
            showData(results, table);
        }

        function showResultsForNodes(results) {
            const arr = [];
            let arrPointer = -1;
            arr[++arrPointer] =`<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table">
                                    <thead>
                                        <tr>
                                            <th>Ely</th>
                                            <th>Tie</th>
                                            <th>Osa</th>
                                            <th>Et</th>
                                            <th>Alkupäivämäärä</th>
                                            <th>Tyyppi</th>
                                            <th>Nimi</th>
                                            <th>Solmunumero</th>
                                        </tr>
                                    </thead>
                                    <tbody>`;

            for (let i = 0, len = results.length; i < len; i++) {
                arr[++arrPointer] =`    <tr>
                                            <td>${results[i].ely}</td>
                                            <td>${results[i].roadNumber}</td>
                                            <td>${results[i].roadPartNumber}</td>
                                            <td>${results[i].addrM}</td>
                                            <td>${results[i].startDate}</td>
                                            <td>${results[i].nodeType}</td>
                                            <td>${results[i].nodeName}</td>
                                            <td>${results[i].nodeNumber}</td>
                                        </tr>`;
            }
            arr[++arrPointer] =     `</tbody>
                                </table>`;
            const table = $(arr.join('')); // join the array to one large string and create jquery element from said string
            showData(results, table);
        }

        function showResultsForJunctions(results) {
            const arr = [];
            let arrPointer = -1;
            arr[++arrPointer] =`<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table">
                                    <thead>
                                        <tr>
                                            <th>Solmu-numero</th>
                                            <th>P-Koord</th>
                                            <th>I-Koord</th>
                                            <th>Nimi</th>
                                            <th>Solmu-tyyppi</th>
                                            <th>Alkupvm</th>
                                            <th>Liittymä-nro</th>
                                            <th>Tie</th>
                                            <th>Ajr</th>
                                            <th>Osa</th>
                                            <th>Et</th>
                                            <th>EJ</th>
                                        </tr>
                                    </thead>
                                    <tbody>`;

            for (let i = 0, len = results.length; i < len; i++) {
                arr[++arrPointer] =`    <tr>
                                            <td>${results[i].nodeNumber}</td>
                                            <td>${results[i].nodeCoordinates.y}</td>
                                            <td>${results[i].nodeCoordinates.x}</td>
                                            <td>${results[i].nodeName}</td>
                                            <td>${results[i].nodeType}</td>
                                            <td>${results[i].startDate}</td>
                                            <td>${results[i].junctionNumber}</td>
                                            <td>${results[i].roadNumber}</td>
                                            <td>${results[i].track}</td>
                                            <td>${results[i].roadPartNumber}</td>
                                            <td>${results[i].addrM}</td>
                                            <td>${results[i].beforeAfter}</td>
                                        </tr>`;
            }
            arr[++arrPointer] =`    </tbody>
                                </table>`;
            const table = $(arr.join('')); // join the array to one large string and create jquery element from said string
            showData(results, table);
        }


        function showResultsForRoadNames(results) {
            const arr = [];
            let arrPointer = -1;
            arr[++arrPointer] = `<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table">
                                    <thead>
                                        <tr>
                                            <th>Ely</th>
                                            <th>Tie</th>
                                            <th>Nimi</th>
                                        </tr>
                                    </thead>
                                    <tbody>`;

            for (let i = 0, len = results.length; i < len; i++) {
                arr[++arrPointer] = `   <tr>
                                            <td>${results[i].ely}</td>
                                            <td>${results[i].roadNumber}</td>
                                            <td>${results[i].roadName}</td>
                                        </tr>`;
            }
            arr[++arrPointer] =`    </tbody>
                                </table>`;
            const table = $(arr.join('')); // join the array to one large string and create jquery element from said string
            showData(results, table);
        }

        function showData(results, table) {
            if (results.length === 0) {
                roadAddrBrowserWindow.append($('<p id="tableNotification"><b>Hakuehdoilla ei löytynyt yhtäkään osumaa</b></p>'));
                roadAddrBrowserWindow.append(table.hide());
            }
            else if (results.length <= MAX_ROWS_TO_DISPLAY) {
                roadAddrBrowserWindow.append(table);
                $('#exportAsExcelFile').prop("disabled", false); // enable Excel download button
            }
            else {
                // hide the results and notify user to download result table as excel file
                roadAddrBrowserWindow.append($('<p id="tableNotification"><b>Tulostaulu liian suuri, lataa tulokset excel taulukkona</b></p>'));
                roadAddrBrowserWindow.append(table.hide());
                $('#exportAsExcelFile').prop("disabled", false); // enable Excel download button
            }
        }


        function toggle() {
            $('.container').append('<div class="modal-overlay confirm-modal"><div class="modal-dialog"></div></div>');
            $('.modal-dialog').append(roadAddrBrowserWindow.toggle());
            addDatePicker();
            bindEvents();
        }

        function addDatePicker() {
            const dateInput = $('#roadAddrStartDate');
            datePicker = dateutil.addSingleDatePicker(dateInput);
        }

        function destroyDatePicker() {
            datePicker.destroy();
        }

        function getCurrentDate() {
            const today = new Date();
            const dayInNumber = today.getDate();
            const day = dayInNumber < 10 ? '0' + dayInNumber.toString() : dayInNumber.toString();
            const monthInNumber = today.getMonth() + 1;
            const month = monthInNumber < 10 ? '0' + monthInNumber.toString() : monthInNumber.toString();
            const year = today.getFullYear().toString();
            return day + '.' + month + '.' + year;
        }

        // converts date object to string "yyyy-mm-dd"
        function parseDateToString(date) {
            const dayInNumber = date.getDate();
            const day = dayInNumber < 10 ? '0' + dayInNumber.toString() : dayInNumber.toString();
            const monthInNumber = date.getMonth() + 1;
            const month = monthInNumber < 10 ? '0' + monthInNumber.toString() : monthInNumber.toString();
            const year = date.getFullYear().toString();
            return year + '-' + month + '-' + day;
        }


        function hide() {
            $('.modal-dialog').append(roadAddrBrowserWindow.toggle());
            $('.modal-overlay').remove();
            destroyDatePicker();
        }

        function exportDataAsExcelFile() {
            const params = me.getSearchParams();
            const fileNameString = "Viite_" + params.target + "_" + params.startDate + "_" + params.ely + "_" + params.roadNumber + "_" + params.minRoadPartNumber + "_" + params.maxRoadPartNumber + ".xlsx";
            const fileName = fileNameString.replaceAll("undefined", "-");
            const options = {dateNF: 'dd"."mm"."yyyy'};
            const wb = XLSX.utils.table_to_book(document.getElementById("roadAddressBrowserTable"), options);
            /* Export to file (start a download) */
            XLSX.writeFile(wb, fileName);
        }

        function getData() {
            const roadAddrStartDate   = document.getElementById('roadAddrStartDate');
            const ely                 = document.getElementById('roadAddrInputEly');
            const roadNumber          = document.getElementById('roadAddrInputRoad');
            const minRoadPartNumber   = document.getElementById('roadAddrInputStartPart');
            const maxRoadPartNumber   = document.getElementById('roadAddrInputEndPart');
            const targetValue        = $("input:radio[name ='roadAddrBrowserForm']:checked").val();

            // convert date input text to date object
            const roadAddrStartDateObject  = moment(roadAddrStartDate.value, "DD-MM-YYYY").toDate();

            function reportValidations() {
                return roadAddrStartDate.reportValidity() &&
                    ely.reportValidity() &&
                    roadNumber.reportValidity() &&
                    minRoadPartNumber.reportValidity() &&
                    maxRoadPartNumber.reportValidity();
            }

            function validateDate(date) {
                if (date instanceof Date && !isNaN(date)) {
                    if(date.getFullYear() > MAX_YEAR_PARAM || date.getFullYear() < MIN_YEAR_PARAM)
                        roadAddrStartDate.setCustomValidity("Vuosiluvun tulee olla väliltä" + MIN_YEAR_PARAM + " - " + MAX_YEAR_PARAM);
                }
                else
                    roadAddrStartDate.setCustomValidity("Päivämäärän tulee olla muodossa pp.kk.yyyy");
            }

            function validateElyAndRoadNumber (elyElement, roadNumberElement) {
                if (elyElement.value === "" && roadNumberElement.value === "")
                    elyElement.setCustomValidity("Ely tai Tie on pakollinen tieto");
            }

            function willPassValidations() {
                validateDate(roadAddrStartDateObject);
                validateElyAndRoadNumber(ely, roadNumber);
                return reportValidations();
            }

            function createParams() {
                const parsedDateString = parseDateToString(roadAddrStartDateObject);
                const params = {
                    startDate: parsedDateString,
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
                return params;
            }

            //reset ely and roadAddrStartDate input fields' custom validity
            ely.setCustomValidity("");
            roadAddrStartDate.setCustomValidity("");

            switch (targetValue) {
                case "Tracks":
                case "Nodes":
                case "Junctions":
                    if (willPassValidations())
                        fetchByTargetValue(createParams());
                    break;
                case "RoadNames":
                    validateDate(roadAddrStartDateObject);
                    if (reportValidations())
                        fetchByTargetValue(createParams());
                    break;
                default:
            }
        }

        function fetchByTargetValue(params) {
            applicationModel.addSpinner();
            backend.getDataForRoadAddressBrowser(params, function(result) {
                if (result.success) {
                    applicationModel.removeSpinner();
                    me.setSearchParams(params);
                    switch (params.target) {
                        case "Tracks":
                            showResultsForTracks(result.tracks);
                            break;
                        case "Nodes":
                            showResultsForNodes(result.nodes);
                            break;
                        case "Junctions":
                            showResultsForJunctions(result.junctions);
                            break;
                        case "RoadNames":
                            showResultsForRoadNames(result.roadNames);
                            break;
                        default:
                    }
                } else
                    new ModalConfirm(result.error);
            });
        }

        function bindEvents() {

            document.getElementById('roadAddrInputRoad').oninput = function () {
                if (this.value.length > MAX_LENGTH_FOR_ROAD_NUMBER) {
                    this.value = this.value.slice(0,MAX_LENGTH_FOR_ROAD_NUMBER);
                }
            };

            document.getElementById('roadAddrInputStartPart').oninput = function () {
                if (this.value.length > MAX_LENGTH_FOR_ROAD_PART_NUMBER) {
                    this.value = this.value.slice(0,MAX_LENGTH_FOR_ROAD_PART_NUMBER);
                }
            };

            document.getElementById('roadAddrInputEndPart').oninput = function () {
                if (this.value.length > MAX_LENGTH_FOR_ROAD_PART_NUMBER) {
                    this.value = this.value.slice(0,MAX_LENGTH_FOR_ROAD_PART_NUMBER);
                }
            };

            roadAddrBrowserWindow.on('click', '#exportAsExcelFile', function () {
                exportDataAsExcelFile();
                return false; // cancel form submission
            });
            roadAddrBrowserWindow.on('click', 'button.close', function () {
                hide();
            });

            roadAddrBrowserWindow.on('click', '.btn-fetch-road-addresses', function () {
                $('.road-address-browser-window-results-table').remove(); // empty the result table
                $('#exportAsExcelFile').prop("disabled", true); //disable excel download button
                $('#tableNotification').remove(); // remove notification if present
                getData();
                return false; // cancel form submission
            });
        }

        this.setSearchParams = function(params)  {
            searchParams = params;
        };

        this.getSearchParams = function() {
            return searchParams;
        };

        return {
            toggle: toggle
        };
    };
}(this));
