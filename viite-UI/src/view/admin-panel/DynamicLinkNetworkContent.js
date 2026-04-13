import * as ViiteConstants from '@utils/ViiteConstants.js';
import { dateutil } from '@utils/DateUtils.js';

// UI elements responsible for triggering and configuring dynamic link network update process
export function DynamicLinkNetworkContent(backend) {

        // Helper function to convert DD.MM.YYYY to YYYY-MM-DD for HTML5 date input
        const finnishDateToInputDate = function(finnishDate) {
            if (!finnishDate || typeof finnishDate !== 'string') return '';
            const parts = finnishDate.split('.');
            if (parts.length !== 3) return '';
            return `${parts[2]}-${parts[1]}-${parts[0]}`;
        };

        function getContent() {
            return `
                <div class="dynamic-link-network-content-wrapper">
                    <div>
                        <p class="dynamic-link-network-description">
                            Valitse päivämäärät päivittääksesi tielinkkiverkkoa
                        </p>
                        <p id="dynamicLinkNetworkInfo" class="dynamic-link-network-info">
                            <span class="dynamic-link-network-info-placeholder">Placeholder Text</span>
                        </p>
                    </div>
                    <div class="dynamic-link-network-input-wrapper">
                        <div class="dynamic-link-network-input">
                            <label for="sourceDate">Nykytilanne</label>
                            <input type="date" id="sourceDate" class="form-control dynamic-link-network-date-input">
                        </div>
                        <p class="dynamic-link-network-arrow">&#8594</p>
                        <div class="dynamic-link-network-input">
                            <label for="targetDate">Tavoitepäivämäärä</label>
                            <input type="date" id="targetDate" class="form-control dynamic-link-network-date-input">
                        </div>
                    </div>
                    <div class="dynamic-link-network-input-wrapper">
                        <input type="checkbox" id="processPerDay">
                        <label for="processPerDay">Päivä kerrallaan</label>
                    </div>
                    <button id="updateLinkNetwork" class="btn-primary update-link-network-button">
                        Päivitä tielinkkiverkko
                    </button>
                </div>
            `;
        }

        function addDatePickersToInputFields() {
            backend.getRoadLinkDate(function (roadLinkDate) {
                const minimumDateObject = dateutil.parseCustomDateString(roadLinkDate.result);
                const minimumDateFinnish = dateutil.parseDateToString(minimumDateObject);
                const minimumDateInput = finnishDateToInputDate(minimumDateFinnish);

                $('#sourceDate').val(minimumDateInput);
            });
        }

        function willPassValidations(dateString) {
            if (!dateString) return false;
            // dateString is in YYYY-MM-DD format from HTML5 date input
            const parts = dateString.split('-');
            if (parts.length !== 3) return false;
            
            const year = parseInt(parts[0], 10);
            const month = parseInt(parts[1], 10);
            const day = parseInt(parts[2], 10);
            
            if (month < 1 || month > 12 || day < 1 || day > 31) {
                setInfoText("Tarkista päivämäärä!");
                return false;
            }
            
            if (year < ViiteConstants.MIN_YEAR_INPUT || year > ViiteConstants.MAX_YEAR_INPUT) {
                setInfoText("Vuosiluvun tulee olla väliltä " + ViiteConstants.MIN_YEAR_INPUT + " - " + ViiteConstants.MAX_YEAR_INPUT);
                return false;
            }
            
            setInfoText("");
            return true;
        }

        function reasonableDates(sourceDateObject, targetDateObject) {
            // Both dates are already Date objects
            if (sourceDateObject >= targetDateObject) {
                setInfoText("Nykytilanteen tulee olla ennen tavoitepäivämäärää!");
                return false;
            } else {
                setInfoText("");
                return true;
            }
        }

        function dateFieldsFilled() {
            return $('#sourceDate').val().length > 0 && $('#targetDate').val().length > 0;
        }

        function countDaysBetweenTwoDates(date1Str, date2Str) {
            // Input strings are in YYYY-MM-DD format from date inputs
            const date1 = new Date(date1Str);
            const date2 = new Date(date2Str);
            const msPerDay = 1000 * 60 * 60 * 24;
            const diffTime = date2 - date1;
            return Math.round(diffTime / msPerDay);
        }

        function buildInfoText() {
            const sourceDate = document.getElementById('sourceDate').value;
            const targetDate = document.getElementById('targetDate').value;
            if (!sourceDate || !targetDate) return '';
            const daysBetween = countDaysBetweenTwoDates(sourceDate, targetDate);
            return "Olet päivittämässä linkkiverkkoa " + daysBetween + " päivää " + (daysBetween > 0 ? "eteenpäin." : "taaksepäin. Korjaa päivämäärät!");
        }

        function setInfoText(text) {
            const infoElem = document.getElementById('dynamicLinkNetworkInfo');
            infoElem.innerText = text;
        }

        function notifyUserWithDateChangeInfo() {
            const text = buildInfoText();
            setInfoText(text);
        }

        function startRoadLinkNetworkUpdate() {
            const sourceDateString = $('#sourceDate').val() || '';
            const targetDateString = $('#targetDate').val() || '';

            if (!willPassValidations(sourceDateString) || !willPassValidations(targetDateString)) {
                return;
            }

            const sourceDateObject = new Date(sourceDateString);
            const targetDateObject = new Date(targetDateString);

            if (!reasonableDates(sourceDateObject, targetDateObject)) {
                return;
            }

            const jsonDateData = {
                sourceDate: dateutil.parseDateToString(sourceDateObject),
                targetDate: dateutil.parseDateToString(targetDateObject),
                processPerDay: document.getElementById('processPerDay').checked
            };

            backend.startLinkNetworkUpdate(jsonDateData, function (result) {
                setInfoText(result.message);
            });
        }

        function bindEvents(containerSelector) {
            const $container = $(containerSelector);

            $container.on('click', '#updateLinkNetwork', function () {
                startRoadLinkNetworkUpdate();
            });

            $container.on('change', '#targetDate, #sourceDate', function () {
                if (dateFieldsFilled()) notifyUserWithDateChangeInfo();
            });
        }

        return {
            getContent: getContent,
            addDatePickersToInputFields: addDatePickersToInputFields,
            bindEvents: bindEvents
        };
}
