(function (root) {
    root.RoadAddressChangesBrowserWindow = function (backend, roadAddressBrowserForm) {
        let searchParams = {};
        let datePickers = [];
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
            arr[++arrPointer] = `<table id="roadAddressChangesBrowserTable" class="road-address-browser-window-results-table">
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
                $('#exportAsExcelFile').prop("disabled", false); // enable Excel download button
            }
            else {
                // hide the results and notify user to download result table as Excel file
                roadAddressChangesBrowserWindow.append($('<p id="tableNotification"><b>Tulostaulu liian suuri, lataa tulokset Excel -taulukkona</b></p>'));
                roadAddressChangesBrowserWindow.append(table.hide());
                $('#exportAsExcelFile').prop("disabled", false); // enable Excel download button
            }
        }

        function toggle() {
            $('.container').append('<div class="road-address-browser-modal-overlay confirm-modal"><div class="road-address-browser-modal-window"></div></div>');
            $('.road-address-browser-modal-window').append(roadAddressChangesBrowserWindow.toggle());
            addDatePicker("roadAddrChangesStartDate", "calendarIconStartDate");
            addDatePicker("roadAddrChangesEndDate", "calendarIconEndDate");
            bindEvents();
        }

        function hide() {
            $('.road-address-browser-modal-window').append(roadAddressChangesBrowserWindow.toggle());
            $('.road-address-browser-modal-overlay').remove();
            destroyDatePickers();
        }

        function addDatePicker(inputFieldId, triggerElementId) {
            const inputField = $('#' + inputFieldId);
            const triggerElement = document.getElementById(triggerElementId);
            const options = {
                trigger: triggerElement,
            }
            const datePicker = dateutil.addSingleDatePicker(inputField, options);
            datePickers.push(datePicker);
        }

        function destroyDatePickers() {
            datePickers.forEach((datePicker) => {
                datePicker.destroy();
            });
            datePickers = [];
        }

        function hidePickers() {
            datePickers.forEach((picker) => {
                picker.hide();
            });
        }

        function exportDataAsExcelFile() {
            const params = me.getSearchParams();
            const fileNameString = "Viite_" + params.dateTarget + "_" + params.startDate + "_" + params.endDate + "_" + params.ely + "_" + params.roadNumber + "_" + params.minRoadPartNumber + "_" + params.maxRoadPartNumber + ".xlsx";
            const fileName = fileNameString.replaceAll("undefined", "-");
            const options = {
                cellDates: true,
                dateNF: 'mm"."dd"."yyyy' // sheetJS reads the tables' date cells in M/D/YYYY format even though they are in DD.MM.YYYY (Finnish) format
                // To get the right format to the Excel file the DD and MM fields need to be in reversed order
                // example:
                // table cell value 01.06.2022 is read by sheetJS as 1/6/2022 i.e. M = 1, D = 6
                // so when we want to construct the finnish date format DD.MM.YYYY we need to put them in reversed order MM.DD.YYYY
            };
            const wb = XLSX.utils.table_to_book(document.getElementById("roadAddressChangesBrowserTable"), options);
            /* Export to file (start a download) */
            XLSX.writeFile(wb, fileName);
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

            function reportValidations() {
                return roadAddrChangesStartDate.reportValidity() &&
                    ely.reportValidity() &&
                    roadNumber.reportValidity() &&
                    minRoadPartNumber.reportValidity() &&
                    maxRoadPartNumber.reportValidity();
            }

            function validateDate(date) {
                if (dateutil.isValidDate(date)) {
                    if(!dateutil.isDateInYearRange(date, ViiteConstants.MIN_YEAR_INPUT, ViiteConstants.MAX_YEAR_INPUT))
                        roadAddrChangesStartDate.setCustomValidity("Vuosiluvun tulee olla väliltä " + ViiteConstants.MIN_YEAR_INPUT + " - " + ViiteConstants.MAX_YEAR_INPUT);
                }
                else
                    roadAddrChangesStartDate.setCustomValidity("Päivämäärän tulee olla muodossa pp.kk.yyyy");
            }

            function willPassValidations() {
                validateDate(roadAddrStartDateObject);
                if (roadAddrChangesEndDate.value)
                    validateDate(moment(roadAddrChangesEndDate.value, "DD-MM-YYYY").toDate());
                return reportValidations();
            }

            function createParams() {
                const parsedDateString = dateutil.parseDateToString(roadAddrStartDateObject);
                const params = {
                    startDate: parsedDateString,
                    dateTarget: dateTarget.value
                };
                if (roadAddrChangesEndDate.value)
                    params.endDate = dateutil.parseDateToString(moment(roadAddrChangesEndDate.value, "DD-MM-YYYY").toDate());
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

            if (willPassValidations())
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

        function clearResultsAndDisableExcelButton() {
            $('.road-address-browser-window-results-table').remove(); // empty the result table
            $('#exportAsExcelFile').prop("disabled", true); //disable Excel download button
            $('#tableNotification').remove(); // remove notification if present
        }

        function bindEvents() {

            document.getElementById('roadAddrChangesStartDate').onchange = function () {
                hidePickers();
            };

            document.getElementById('roadAddrChangesEndDate').onchange = function () {
                hidePickers();
            };

            // if any of the input fields change (the input fields are child elements of this wrapper/parent element)
            document.getElementById('roadAddressChangesBrowser').onchange = function () {
                clearResultsAndDisableExcelButton();
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

            roadAddressChangesBrowserWindow.on('click', '#exportAsExcelFile', function () {
                exportDataAsExcelFile();
                return false; // cancel form submission
            });

            roadAddressChangesBrowserWindow.on('click', 'button.close', function () {
                hide();
            });

            roadAddressChangesBrowserWindow.on('click', '#fetchRoadAddressChanges', function () {
                clearResultsAndDisableExcelButton();
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
