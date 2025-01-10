(function (root) {
    root.RoadAddressChangesBrowserWindow = function (backend, roadAddressBrowserForm) {
        let searchParams = {};
        const me = this;

        const roadAddressChangesBrowserWindow = $('<div class="form-horizontal road-address-changes-browser-window"></div>').hide();
        const roadAddressChangesBrowserHeader = $(
            '<div class="road-address-browser-modal-header">' +
                '<p>Tieosoitemuutosten katselu</p>' +
                '<a href="manual/index.html#!index.md#11_Tieosoitemuutosten_katselu_-ty%C3%B6kalu" target="_blank">' +
                    '<button class="btn-manual" title="Avaa käyttöohje">' +
                        '<i class="fas fa-question"></i>' +
                    '</button>' +
                '</a>' +
                '<button class="close btn-close-road-address-browser">x</button>' +
            '</div>'
        );
        roadAddressChangesBrowserWindow.append(roadAddressChangesBrowserHeader);
        roadAddressChangesBrowserWindow.append(roadAddressBrowserForm.getRoadRoadAddressChangesBrowserForm());

        /**
         *      This function is performance critical. Pointers in use for reasonable processing time.
         *      If edited be sure to measure table creation time with the largest possible dataset!
         */
        function createResultTable(results) {
            const arr = [];
            let arrPointer = -1;
            arr[++arrPointer] = `<table id="roadAddressChangesBrowserTable" class="road-address-browser-window-results-table viite-table">
                                    <thead>
                                        <tr>
                                            <th>Voimaantulopvm</th>
                                            <th>Ely</th>
                                            <th>Tie</th>
                                            <th>Ajr</th>
                                            <th>Aosa</th>
                                            <th>Aet</th>
                                            <th>Losa</th>
                                            <th>Let</th>
                                            <th>Pituus</th>
                                            <th>Hall. luokka</th>
                                            <th>Muutos</th>
                                            <th>u_Ely</th>
                                            <th>u_Tie</th>
                                            <th>u_Ajr</th>
                                            <th>u_Aosa</th>
                                            <th>u_Aet</th>
                                            <th>u_Losa</th>
                                            <th>u_Let</th>
                                            <th>u_Pituus</th>
                                            <th>u_Hall. luokka</th>
                                            <th>Käännetty</th>
                                            <th>Tien nimi</th>
                                            <th>Projektin Nimi</th>
                                            <th>Projektin hyväksymispvm</th>
                                        </tr>
                                    </thead>
                                    <tbody>`;

            for (let i = 0, len = results.length; i < len; i++) {
                arr[++arrPointer] = `   <tr>
                                            <td>${results[i].startDate}</td>
                                            <td>${results[i].oldEly}</td>
                                            <td>${results[i].oldRoadNumber}</td>
                                            <td>${results[i].oldTrack}</td>
                                            <td>${results[i].oldRoadPartNumber}</td>
                                            <td>${results[i].oldStartAddrM}</td>
                                            <td>${results[i].oldRoadPartNumber}</td>
                                            <td>${results[i].oldEndAddrM}</td>
                                            <td>${results[i].oldLength}</td>
                                            <td>${EnumerationUtils.getAdministrativeClassTextValue(results[i].oldAdministrativeClass)}</td>
                                            <td>${EnumerationUtils.getChangeTypeDisplayText(results[i].changeType)}</td>
                                            <td>${results[i].newEly}</td>
                                            <td>${results[i].newRoadNumber}</td>
                                            <td>${results[i].newTrack}</td>
                                            <td>${results[i].newRoadPartNumber}</td>
                                            <td>${results[i].newStartAddrM}</td>
                                            <td>${results[i].newRoadPartNumber}</td>
                                            <td>${results[i].newEndAddrM}</td>
                                            <td>${results[i].newLength}</td>
                                            <td>${EnumerationUtils.getAdministrativeClassTextValue(results[i].newAdministrativeClass)}</td>
                                            <td>${results[i].reversed}</td>
                                            <td>${results[i].roadName}</td>
                                            <td>${results[i].projectName}</td>
                                            <td>${results[i].projectAcceptedDate}</td>
                                        </tr>`;
            }
            arr[++arrPointer] =`    </tbody>
                                </table>`;
            return $(arr.join('')); // join the array to one large string and create jquery element from said string
        }

        function showData(results, table) {
            if (results.length === 0) {
                roadAddressChangesBrowserWindow.append($('<p id="tableNotification"><b>Hakuehdoilla ei löytynyt yhtäkään osumaa</b></p>'));
                roadAddressChangesBrowserWindow.append(table.hide());
            }
            else if (results.length <= ViiteConstants.MAX_ROWS_TO_DISPLAY) {
                roadAddressChangesBrowserWindow.append(table);
                $('#exportAsCsvFile').prop("disabled", false); // enable CSV download button
            }
            else {
                // hide the results and notify user to download result table as CSV file
                roadAddressChangesBrowserWindow.append($('<p id="tableNotification"><b>Tulostaulu liian suuri, lataa tulokset CSV -taulukkona</b></p>'));
                roadAddressChangesBrowserWindow.append(table.hide());
                $('#exportAsCsvFile').prop("disabled", false); // enable CSV download button
            }
        }

        function toggle() {
            $('.road-address-browser-modal-overlay').length === 0 ? show() : hide();
        }

        function show() {
            $('.container').append('<div class="road-address-browser-modal-overlay viite-modal-overlay confirm-modal"><div class="road-address-browser-modal-window"></div></div>');
            $('.road-address-browser-modal-window').append(roadAddressChangesBrowserWindow.show());
            bindEvents();
        }

        function hide() {
            roadAddressChangesBrowserWindow.hide();
            $('.road-address-browser-modal-overlay').remove();
        }

        function exportDataAsCsvFile() {
            const params = me.getSearchParams();
            const fileNameString = "Viite_" + params.dateTarget + "_" + params.startDate + "_" + params.endDate + "_" + params.ely + "_" + params.roadNumber + "_" + params.minRoadPartNumber + "_" + params.maxRoadPartNumber + ".csv";
            const fileName = fileNameString.replaceAll("undefined", "-");

            const table = document.getElementById("roadAddressChangesBrowserTable");
            let csvContent = "\uFEFF"; // UTF-8 BOM

            for (const row of table.rows) {
                const rowData = [];
                for (const cell of row.cells) {
                    rowData.push(cell.innerText);
                }
                csvContent += rowData.join(";") + "\n"; // Join cells with commas
            }

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
            const roadAddrChangesStartDate      = document.getElementById('roadAddrChangesStartDate');
            const roadAddrChangesEndDate        = document.getElementById('roadAddrChangesEndDate');
            const ely                           = document.getElementById('roadAddrChangesInputEly');
            const roadNumber                    = document.getElementById('roadAddrChangesInputRoad');
            const minRoadPartNumber             = document.getElementById('roadAddrChangesInputStartPart');
            const maxRoadPartNumber             = document.getElementById('roadAddrChangesInputEndPart');
            const dateTarget                    = document.getElementById('dateTarget');

            // convert date input text to date object
            const roadAddrStartDateObject  = moment(roadAddrChangesStartDate.value, "DD-MM-YYYY").toDate();
            const roadAddrEndDateObject  = moment(roadAddrChangesEndDate.value, "DD-MM-YYYY").toDate();

            function reportValidations() {
                return roadAddrChangesStartDate.reportValidity() &&
                    roadAddrChangesEndDate.reportValidity() &&
                    ely.reportValidity() &&
                    roadNumber.reportValidity() &&
                    minRoadPartNumber.reportValidity() &&
                    maxRoadPartNumber.reportValidity();
            }

            function validateDate(dateString, dateElement) {
                // Check format ignoring whitespace
                if (dateutil.isFinnishDateString(dateString.trim())) {
                    const dateObject = moment(dateString, "DD-MM-YYYY").toDate();
                    if (dateutil.isValidDate(dateObject)){
                        if (dateutil.isDateInYearRange(dateObject, ViiteConstants.MIN_YEAR_INPUT, ViiteConstants.MAX_YEAR_INPUT)) {
                            dateElement.setCustomValidity("");
                            return true;
                        } else {
                            dateElement.setCustomValidity("Vuosiluvun tulee olla väliltä " + ViiteConstants.MIN_YEAR_INPUT + " - " + ViiteConstants.MAX_YEAR_INPUT);
                            return false;
                        }
                    } else {
                        dateElement.setCustomValidity("Tarkista päivämäärä!");
                        return false;
                    }
                } else {
                    dateElement.setCustomValidity("Päivämäärän tulee olla muodossa pp.kk.vvvv");
                    return false;
                }
            }

            // Clear date error message when typing is started again
            roadAddrChangesStartDate.addEventListener('input', function() {
                validateDate(this.value, this);
                this.setCustomValidity("");
            });

            roadAddrChangesEndDate.addEventListener('input', function() {
                validateDate(this.value, this);
                this.setCustomValidity("");
            });

            function willPassValidations() {
                // If start date is provided, validate it
                if (roadAddrChangesStartDate.value.trim().length > 0) {
                    validateDate(roadAddrChangesStartDate.value, roadAddrChangesStartDate);
                } else {
                    // If start date is not provided, set custom validity
                    roadAddrChangesStartDate.setCustomValidity("Alkupäivämäärä on pakollinen tieto");
                }
                // Validate end date
                if (roadAddrChangesEndDate.value && validateDate(roadAddrChangesEndDate.value, roadAddrChangesEndDate)) {
                    if (roadAddrEndDateObject.getTime() < roadAddrStartDateObject.getTime()) {
                        roadAddrChangesEndDate.setCustomValidity("Loppupäivämäärä ei voi olla ennen alkupäivämäärää");
                    }
                }
                return reportValidations();
            }

            if (!willPassValidations()) {
                return; // Stop execution if validation fails
            }

            function createParams() {
                const parsedDateString = dateutil.parseDateToString(roadAddrStartDateObject);
                const params = {
                    startDate: parsedDateString,
                    dateTarget: dateTarget.value
                };
                if (roadAddrChangesEndDate.value)
                    params.endDate = dateutil.parseDateToString(roadAddrEndDateObject);
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
            roadAddrChangesStartDate.setCustomValidity("");
            roadAddrChangesEndDate.setCustomValidity("");

            if (willPassValidations())

                //Sets the end date 1 day ahead, so that the inputted end date will be included in the projectlisting.
                dateutil.addOneDayToDate(roadAddrEndDateObject);

                fetchRoadAddressChanges(createParams());
        }

        function fetchRoadAddressChanges(params) {
            applicationModel.addSpinner();
            backend.getDataForRoadAddressChangesBrowser(params, function(result) {
                if (result.success) {
                    applicationModel.removeSpinner();
                    me.setSearchParams(params);
                    showData(result.changeInfos, createResultTable(result.changeInfos));
                } else
                    new ModalConfirm(result.error);
            });
        }

        function clearResultsAndDisableCsvButton() {
            $('.road-address-browser-window-results-table').remove(); // empty the result table
            $('#exportAsCsvFile').prop("disabled", true); //disable CSV download button
            $('#tableNotification').remove(); // remove notification if present
        }

        function bindEvents() {

            // if any of the input fields change (the input fields are child elements of this wrapper/parent element)
            document.getElementById('roadAddressChangesBrowser').onchange = function () {
                clearResultsAndDisableCsvButton();
            };

            document.getElementById('roadAddrChangesInputRoad').oninput = function () {
                if (this.value.length > ViiteConstants.MAX_LENGTH_FOR_ROAD_NUMBER) {
                    this.value = this.value.slice(0, ViiteConstants.MAX_LENGTH_FOR_ROAD_NUMBER);
                }
            };

            document.getElementById('roadAddrChangesInputStartPart').oninput = function () {
                if (this.value.length > ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER) {
                    this.value = this.value.slice(0, ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER);
                }
            };

            document.getElementById('roadAddrChangesInputEndPart').oninput = function () {
                if (this.value.length > ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER) {
                    this.value = this.value.slice(0, ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER);
                }
            };
            
            roadAddressChangesBrowserWindow.on('click', '#exportAsCsvFile', function () {
                exportDataAsCsvFile();
                return false; // cancel form submission
            });

            roadAddressChangesBrowserWindow.on('click', 'button.close', function () {
                hide();
            });

            roadAddressChangesBrowserWindow.on('click', '#fetchRoadAddressChanges', function () {
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

        return {
            toggle: toggle
        };
    };
}(this));
