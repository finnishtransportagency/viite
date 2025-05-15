(function (root) {
    root.RoadAddressBrowserWindow = function (backend, roadAddressBrowserForm) {
        const me = this;
        let searchParams = {};
        let searchResults = [];

        const roadAddrBrowserWindow = $('<div id="road-address-browser-window" class="form-horizontal road-address-browser-window"></div>').hide();
        const roadAddressChangesBrowserHeader = $(
            '<div class="road-address-browser-modal-header">' +
                '<p>Tieosoitteiden katselu</p>' +
                '<a href="manual/index.html#!index.md#10_Tieosoitteiden_katselu_-ty%C3%B6kalu" target="_blank">' +
                    '<button class="btn-manual" title="Avaa käyttöohje">' +
                        '<i class="fas fa-question"></i>' +
                    '</button>' +
                '</a>' +
                '<button class="close btn-close-road-address-browser">x</button>' +
            '</div>'
        );
        roadAddrBrowserWindow.append(roadAddressChangesBrowserHeader);
        roadAddrBrowserWindow.append(roadAddressBrowserForm.getRoadAddressBrowserForm());

        function createArrayOfArraysForTracks(results) {
            const array = [];
            let arrayPointer = -1;
            array[++arrayPointer] = ['Ely','Tie', 'Ajr', 'Osa', 'Aet', 'Let', 'Pituus', 'Hall. luokka', 'Alkupvm'];
            for (let i = 0, len = results.length; i < len; i++) {
                array[++arrayPointer] = [
                    results[i].ely,
                    results[i].roadNumber,
                    results[i].track,
                    results[i].roadPartNumber,
                    results[i].addrMRange.start,
                    results[i].addrMRange.end,
                    results[i].lengthAddrM,
                    EnumerationUtils.getAdministrativeClassTextValue(results[i].administrativeClass),
                    results[i].startDate
                ];
            }
            return array; // join the array to one large string and create jquery element from said string
        }

        /**
         *      This function is performance critical. Pointers in use for reasonable processing time.
         *      If edited be sure to measure table creation time with the largest possible dataset!
         */
        function createResultTableForTracks(results) {
            const arr = [];
            let arrPointer = -1;
            arr[++arrPointer] = `<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table viite-table">
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
                                            <td>${results[i].addrMRange.start}</td>
                                            <td>${results[i].addrMRange.end}</td>
                                            <td>${results[i].lengthAddrM}</td>
                                            <td>${EnumerationUtils.getAdministrativeClassTextValue(results[i].administrativeClass)}</td>
                                            <td>${results[i].startDate}</td>
                                        </tr>`;
            }
            arr[++arrPointer] =`    </tbody>
                            </table>`;
            return $(arr.join('')); // join the array to one large string and create jquery element from said string
        }

        function createArrayOfArraysForRoadParts(results) {
            const array = [];
            let arrayPointer = -1;
            array[++arrayPointer] = ['Ely','Tie', 'Osa', 'Aet', 'Let', 'Pituus', 'Alkupvm'];
            for (let i = 0, len = results.length; i < len; i++) {
                array[++arrayPointer] = [
                    results[i].ely,
                    results[i].roadNumber,
                    results[i].roadPartNumber,
                    results[i].addrMRange.start,
                    results[i].addrMRange.end,
                    results[i].lengthAddrM,
                    results[i].startDate
                ];
            }
            return array; // join the array to one large string and create jquery element from said string
        }

        /**
         *      This function is performance critical. Pointers in use for reasonable processing time.
         *      If edited be sure to measure table creation time with the largest possible dataset!
         */
        function createResultTableForRoadParts(results) {
            const arr = [];
            let arrPointer = -1;
            arr[++arrPointer] = `<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table viite-table">
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
                                            <td>${results[i].addrMRange.start}</td>
                                            <td>${results[i].addrMRange.end}</td>
                                            <td>${results[i].lengthAddrM}</td>
                                            <td>${results[i].startDate}</td>
                                        </tr>`;
            }
            arr[++arrPointer] =`    </tbody>
                            </table>`;
            return $(arr.join('')); // join the array to one large string and create jquery element from said string
        }

        function createArrayOfArraysForNodes(results) {
            const array = [];
            let arrayPointer = -1;
            array[++arrayPointer] = ['Ely','Tie', 'Osa', 'Et', 'Alkupvm', 'Tyyppi', 'Nimi', 'P-Koord', 'I-Koord', 'Solmunumero'];
            for (let i = 0, len = results.length; i < len; i++) {
                array[++arrayPointer] = [
                    results[i].ely,
                    results[i].roadNumber,
                    results[i].roadPartNumber,
                    results[i].addrM,
                    results[i].startDate,
                    results[i].nodeType,
                    results[i].nodeName,
                    results[i].nodeCoordinates.y,
                    results[i].nodeCoordinates.x,
                    results[i].nodeNumber
                ];
            }
            return array; // join the array to one large string and create jquery element from said string
        }

        /**
         *      This function is performance critical. Pointers in use for reasonable processing time.
         *      If edited be sure to measure table creation time with the largest possible dataset!
         */
        function createResultTableForNodes(results) {
            const arr = [];
            let arrPointer = -1;
            arr[++arrPointer] =`<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table viite-table">
                                    <thead>
                                        <tr>
                                            <th>Ely</th>
                                            <th>Tie</th>
                                            <th>Osa</th>
                                            <th>Et</th>
                                            <th>Alkupvm</th>
                                            <th>Tyyppi</th>
                                            <th>Nimi</th>
                                            <th>P-Koord</th>
                                            <th>I-Koord</th>
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
                                            <td>${results[i].nodeCoordinates.y}</td>
                                            <td>${results[i].nodeCoordinates.x}</td>
                                            <td>${results[i].nodeNumber}</td>
                                        </tr>`;
            }
            arr[++arrPointer] =     `</tbody>
                                </table>`;
            return $(arr.join('')); // join the array to one large string and create jquery element from said string
        }

        function createArrayOfArraysForJunctions(results) {
            const array = [];
            let arrayPointer = -1;
            array[++arrayPointer] = ['Solmu-numero','P-Koord', 'I-Koord', 'Nimi', 'Solmu-tyyppi', 'Alkupvm', 'Liittymä-nro', 'Tie', 'Ajr', 'Osa', 'Et', 'EJ'];
            for (let i = 0, len = results.length; i < len; i++) {
                array[++arrayPointer] = [
                    results[i].nodeNumber,
                    results[i].nodeCoordinates.y,
                    results[i].nodeCoordinates.x,
                    results[i].nodeName,
                    results[i].nodeType,
                    results[i].startDate,
                    results[i].junctionNumber,
                    results[i].roadNumber,
                    results[i].track,
                    results[i].roadPartNumber,
                    results[i].addrM,
                    EnumerationUtils.getBeforeAfterDisplayText(results[i].beforeAfter)
                ];
            }
            return array; // join the array to one large string and create jquery element from said string
        }

        /**
         *      This function is performance critical. Pointers in use for reasonable processing time.
         *      If edited be sure to measure table creation time with the largest possible dataset!
         */
        function createResultTableForJunctions(results) {
            const arr = [];
            let arrPointer = -1;
            arr[++arrPointer] =`<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table viite-table">
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

        function createArrayOfArraysForRoadNames(results) {
            const array = [];
            let arrayPointer = -1;
            array[++arrayPointer] = ['Ely', 'Tie', 'Nimi'];
            for (let i = 0, len = results.length; i < len; i++) {
                array[++arrayPointer] = [
                    results[i].ely,
                    results[i].roadNumber,
                    results[i].roadName
                ];
            }
            return array; // join the array to one large string and create jquery element from said string
        }

        /**
         *      This function is performance critical. Pointers in use for reasonable processing time.
         *      If edited be sure to measure table creation time with the largest possible dataset!
         */
        function createResultTableForRoadNames(results) {
            const arr = [];
            let arrPointer = -1;
            arr[++arrPointer] = `<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table viite-table">
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

        function toggle() {
            $('.road-address-browser-modal-overlay').length ? hide() : show();
        }

        function show() {
            $('.container').append('<div class="road-address-browser-modal-overlay viite-modal-overlay confirm-modal"><div class="road-address-browser-modal-window"></div></div>');
            $('.road-address-browser-modal-window').append(roadAddrBrowserWindow.show());
            bindEvents();
        }

        function hide() {
            roadAddrBrowserWindow.hide();
            $('.road-address-browser-modal-overlay').remove();
        }

        function exportDataAsCsvFile() {
            function arrayToCSV(data) {
                return data.map((row) => row.join(";")).join("\n");
            }

            const params = me.getSearchParams();
            const fileNameString = "Viite_" + params.target + "_" + params.situationDate + "_" + params.ely + "_" + params.roadNumber + "_" + params.minRoadPartNumber + "_" + params.maxRoadPartNumber + ".csv";
            const fileName = fileNameString.replaceAll("undefined", "-");

            let data = [];
            switch (params.target) {
                case "Tracks":
                    data = createArrayOfArraysForTracks(me.getSearchResults());
                    break;
                case "RoadParts":
                    data = createArrayOfArraysForRoadParts(me.getSearchResults());
                    break;
                case "Nodes":
                    data = createArrayOfArraysForNodes(me.getSearchResults());
                    break;
                case "Junctions":
                    data = createArrayOfArraysForJunctions(me.getSearchResults());
                    break;
                case "RoadNames":
                    data = createArrayOfArraysForRoadNames(me.getSearchResults());
                    break;
                default:
            }
            let csvContent = "\uFEFF"; // UTF-8 BOM
            csvContent += arrayToCSV(data);

            // Create a downloadable CSV file
            const blob = new Blob([csvContent], { type: "text/csv;charset=utf-8;"}); // Create a file like object containing the CSV data
            const url = URL.createObjectURL(blob); // Create a temporary URL for the file
            const link = document.createElement("a");
            link.setAttribute("href", url);
            link.setAttribute("download", fileName);

            // Append the link and trigger download
            document.body.appendChild(link);
            link.click();

            // Cleanup
            document.body.removeChild(link);
        }

        function getData() {
            const roadAddrSituationDate   = document.getElementById('roadAddrSituationDate');
            const ely                 = document.getElementById('roadAddrInputEly');
            const roadNumber          = document.getElementById('roadAddrInputRoad');
            const minRoadPartNumber   = document.getElementById('roadAddrInputStartPart');
            const maxRoadPartNumber   = document.getElementById('roadAddrInputEndPart');
            const targetValue         = document.getElementById('targetValue');

            // convert date input text to date object
            const roadAddrSituationDateObject  = moment(roadAddrSituationDate.value, "DD-MM-YYYY").toDate();

            function reportValidations() {
                return roadAddrSituationDate.reportValidity() &&
                    ely.reportValidity() &&
                    roadNumber.reportValidity() &&
                    minRoadPartNumber.reportValidity() &&
                    maxRoadPartNumber.reportValidity();
            }

            function validateDate(dateString) {
                if (dateutil.isFinnishDateString(dateString)) {
                    if (dateutil.isDateInYearRange(roadAddrSituationDateObject, ViiteConstants.MIN_YEAR_INPUT, ViiteConstants.MAX_YEAR_INPUT)) {
                        roadAddrSituationDate.setCustomValidity("");
                    } else {
                        roadAddrSituationDate.setCustomValidity("Vuosiluvun tulee olla väliltä " + ViiteConstants.MIN_YEAR_INPUT + " - " + ViiteConstants.MAX_YEAR_INPUT);
                    }
                } else {
                    roadAddrSituationDate.setCustomValidity("Päivämäärän tulee olla muodossa pp.kk.vvvv");
                }
            }

            // Clear date error message when typing is started again
            document.getElementById('roadAddrSituationDate').addEventListener('input', function() {
                validateDate(this.value);
                this.setCustomValidity("");
            });

            function validateElyAndRoadNumber (elyElement, roadNumberElement) {
                if (elyElement.value === "" && roadNumberElement.value === "")
                    elyElement.setCustomValidity("Ely tai Tie on pakollinen tieto");
            }

            function willPassValidations() {
                validateDate(roadAddrSituationDate.value);
                validateElyAndRoadNumber(ely, roadNumber);
                return reportValidations();
            }

            function createParams() {
                const parsedDateString = dateutil.parseDateToString(roadAddrSituationDateObject);
                const params = {
                    situationDate: parsedDateString,
                    target: targetValue.value
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

            //reset ely and roadAddrSituationDate input fields' custom validity
            ely.setCustomValidity("");
            roadAddrSituationDate.setCustomValidity("");

            switch (targetValue.value) {
                case "Tracks":
                case "RoadParts":
                    if (willPassValidations())
                        fetchByTargetValue(createParams());
                    break;
                case "Nodes":
                case "Junctions":
                case "RoadNames":
                    validateDate(roadAddrSituationDate.value);
                    if (reportValidations())
                        fetchByTargetValue(createParams());
                    break;
                default:
            }
        }

        function createResultTable(params, results) {
            let resultTable;
            switch (params.target) {
                case "Tracks":
                    resultTable = createResultTableForTracks(results);
                    break;
                case "RoadParts":
                    resultTable = createResultTableForRoadParts(results);
                    break;
                case "Nodes":
                    resultTable = createResultTableForNodes(results);
                    break;
                case "Junctions":
                    resultTable = createResultTableForJunctions(results);
                    break;
                case "RoadNames":
                    resultTable = createResultTableForRoadNames(results);
                    break;
                default:
            }
            return resultTable;
        }

        function showData(table) {
            roadAddrBrowserWindow.append(table);
            $('#exportAsCsvFile').prop("disabled", false); // enable CSV download button
        }

        function showTableTooBigNotification() {
            roadAddrBrowserWindow.append($('<p id="tableNotification"><b>Tulostaulu liian suuri, lataa tulokset CSV-tiedostona</b></p>'));
            $('#exportAsCsvFile').prop("disabled", false); // enable CSV download button
        }

        function showNoResultsFoundNotification() {
            roadAddrBrowserWindow.append($('<p id="tableNotification"><b>Hakuehdoilla ei löytynyt yhtäkään osumaa</b></p>'));
        }

        function fetchByTargetValue(params) {
            applicationModel.addSpinner();
            backend.getDataForRoadAddressBrowser(params, function(result) {
                if (result.success) {
                    applicationModel.removeSpinner();
                    me.setSearchParams(params);
                    me.setSearchResults(result.results);
                    if (result.results.length > 0) {
                        if (result.results.length <= ViiteConstants.MAX_ROWS_TO_DISPLAY) {
                            showData(createResultTable(params, result.results));
                        } else {
                            showTableTooBigNotification();
                        }
                    } else {
                        showNoResultsFoundNotification();
                    }

                } else
                    new ModalConfirm(result.error);
            });
        }

        function clearResultsAndDisableCsvButton() {
            me.setSearchResults([]); // empty the search results
            $('.road-address-browser-window-results-table').remove(); // empty the result table
            $('#exportAsCsvFile').prop("disabled", true); //disable CSV download button
            $('#tableNotification').remove(); // remove notification if present
        }

        function bindEvents() {

            // if any of the input fields change (the input fields are child elements of this wrapper/parent element)
            document.getElementById('roadAddressBrowser').onchange = function () {
                clearResultsAndDisableCsvButton();
            };

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

            /**
             * Situation date input field is disabled when Nodes or Junctions are selected as the target value
             * This is because Nodes and Junctions can only be browsed on the current road network (complete history info not available)
             */
            document.getElementById('targetValue').onchange = function () {
                const targetValue = document.getElementById('targetValue').value;
                const situationDate = document.getElementById('roadAddrSituationDate');
                switch (targetValue) {
                    case "Tracks":
                    case "RoadParts":
                    case "RoadNames":
                        situationDate.disabled = false;
                        situationDate.tite = "";
                        break;
                    case "Nodes":
                    case "Junctions":
                        situationDate.value = dateutil.getCurrentDateString();
                        situationDate.disabled = true;
                        situationDate.title = "Solmuja ja liittymiä voi tarkastella vain nykyisellä tieverkolla";
                        break;
                    default:
                }
            };

            roadAddrBrowserWindow.on('click', '#exportAsCsvFile', function () {
                exportDataAsCsvFile();
                return false; // cancel form submission
            });
            roadAddrBrowserWindow.on('click', 'button.close', function () {
                hide();
            });

            roadAddrBrowserWindow.on('click', '#fetchRoadAddresses', function () {
                clearResultsAndDisableCsvButton();
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

        this.setSearchResults = function(results) {
            searchResults = results;
        };

        this.getSearchResults = function() {
            return searchResults;
        };

        return {
            toggle: toggle
        };
    };
}(this));
