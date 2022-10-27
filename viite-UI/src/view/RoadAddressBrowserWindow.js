(function (root) {
    root.RoadAddressBrowserWindow = function (backend, roadAddressBrowserForm) {
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
        roadAddrBrowserWindow.append('<div class="road-address-browser-modal-header">Tieosoitteiden katselu</div>');
        roadAddrBrowserWindow.append(roadAddressBrowserForm.getRoadAddressBrowserForm());


        /**
         *      This function is performance critical.
         *      If edited be sure to measure table creation time with largest possible dataset!
         */
        function createResultTableForTracks(results) {
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
                                            <th>Hall. luokka</th>
                                            <th>Alkupvm</th>
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
                                            <td>${EnumerationUtils.getAdministrativeClassTextValue(results[i].administrativeClass)}</td>
                                            <td>${results[i].startDate}</td>
                                        </tr>`;
            }
            arr[++arrPointer] =`    </tbody>
                            </table>`;
            return $(arr.join('')); // join the array to one large string and create jquery element from said string
        }

        /**
         *      This function is performance critical.
         *      If edited be sure to measure table creation time with largest possible dataset!
         */
        function createResultTableForRoadParts(results) {
            const arr = [];
            let arrPointer = -1;
            arr[++arrPointer] = `<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table">
                                    <thead>
                                        <tr>
                                            <th>Ely</th>
                                            <th>Tie</th>
                                            <th>Osa</th>
                                            <th>Aet</th>
                                            <th>Let</th>
                                            <th>Pituus</th>
                                            <th>Alkupvm</th>
                                        </tr>
                                    </thead>
                                    <tbody>`;
            for (let i = 0, len = results.length; i < len; i++) {
                arr[++arrPointer] =`    <tr>
                                            <td>${results[i].ely}</td>
                                            <td>${results[i].roadNumber}</td>
                                            <td>${results[i].roadPartNumber}</td>
                                            <td>${results[i].startAddrM}</td>
                                            <td>${results[i].endAddrM}</td>
                                            <td>${results[i].lengthAddrM}</td>
                                            <td>${results[i].startDate}</td>
                                        </tr>`;
            }
            arr[++arrPointer] =`    </tbody>
                            </table>`;
            return $(arr.join('')); // join the array to one large string and create jquery element from said string
        }

        /**
         *      This function is performance critical.
         *      If edited be sure to measure table creation time with largest possible dataset!
         */
        function createResultTableForNodes(results) {
            const arr = [];
            let arrPointer = -1;
            arr[++arrPointer] =`<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table">
                                    <thead>
                                        <tr>
                                            <th>Ely</th>
                                            <th>Tie</th>
                                            <th>Osa</th>
                                            <th>Et</th>
                                            <th>Alkupvm</th>
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
            return $(arr.join('')); // join the array to one large string and create jquery element from said string
        }

        /**
         *      This function is performance critical.
         *      If edited be sure to measure table creation time with largest possible dataset!
         */
        function createResultTableForJunctions(results) {
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
                                            <td>${EnumerationUtils.getBeforeAfterDisplayText(results[i].beforeAfter)}</td>
                                        </tr>`;
            }
            arr[++arrPointer] =`    </tbody>
                                </table>`;
            return $(arr.join('')); // join the array to one large string and create jquery element from said string
        }

        /**
         *      This function is performance critical.
         *      If edited be sure to measure table creation time with largest possible dataset!
         */
        function createResultTableForRoadNames(results) {
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
            return $(arr.join('')); // join the array to one large string and create jquery element from said string
        }

        function showData(results, table) {
            if (results.length === 0) {
                roadAddrBrowserWindow.append($('<p id="tableNotification"><b>Hakuehdoilla ei löytynyt yhtäkään osumaa</b></p>'));
                roadAddrBrowserWindow.append(table.hide());
            }
            else if (results.length <= ViiteConstants.MAX_ROWS_TO_DISPLAY) {
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
            $('.container').append('<div class="road-address-browser-modal-overlay confirm-modal"><div class="road-address-browser-modal-window"></div></div>');
            $('.road-address-browser-modal-window').append(roadAddrBrowserWindow.toggle());
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

        function hide() {
            $('.road-address-browser-modal-window').append(roadAddrBrowserWindow.toggle());
            $('.road-address-browser-modal-overlay').remove();
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
                if (dateutil.isValidDate(date)) {
                    if(!dateutil.isDateInYearRange(date, ViiteConstants.MIN_YEAR_INPUT, ViiteConstants.MAX_YEAR_INPUT))
                        roadAddrStartDate.setCustomValidity("Vuosiluvun tulee olla väliltä" + ViiteConstants.MIN_YEAR_INPUT + " - " + ViiteConstants.MAX_YEAR_INPUT);
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
                const parsedDateString = dateutil.parseDateToString(roadAddrStartDateObject);
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
                case "RoadParts":
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
                            showData(result.tracks, createResultTableForTracks(result.tracks));
                            break;
                        case "RoadParts":
                            showData(result.roadParts, createResultTableForRoadParts(result.roadParts));
                            break;
                        case "Nodes":
                            showData(result.nodes, createResultTableForNodes(result.nodes));
                            break;
                        case "Junctions":
                            showData(result.junctions, createResultTableForJunctions(result.junctions));
                            break;
                        case "RoadNames":
                            showData(result.roadNames, createResultTableForRoadNames(result.roadNames));
                            break;
                        default:
                    }
                } else
                    new ModalConfirm(result.error);
            });
        }

        function bindEvents() {

            document.getElementById('roadAddrInputRoad').oninput = function () {
                if (this.value.length > ViiteConstants.MAX_LENGTH_FOR_ROAD_NUMBER) {
                    this.value = this.value.slice(0, ViiteConstants.MAX_LENGTH_FOR_ROAD_NUMBER);
                }
            };

            document.getElementById('roadAddrInputStartPart').oninput = function () {
                if (this.value.length > ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER) {
                    this.value = this.value.slice(0, ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER);
                }
            };

            document.getElementById('roadAddrInputEndPart').oninput = function () {
                if (this.value.length > ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER) {
                    this.value = this.value.slice(0, ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER);
                }
            };

            roadAddrBrowserWindow.on('click', '#exportAsExcelFile', function () {
                exportDataAsExcelFile();
                return false; // cancel form submission
            });
            roadAddrBrowserWindow.on('click', 'button.close', function () {
                hide();
            });

            roadAddrBrowserWindow.on('click', '#fetchRoadAddresses', function () {
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
