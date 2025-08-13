(function (root) {
    let sourcePicker = '';
    let targetPicker = '';

    root.AdminPanel = function (backend) {
        const adminPanelWindow =
            $(
                '<div class="generic-window" id="adminPanelWindow">' +
                    '<div class="generic-window-header"> ' +
                        '<p>Admin Paneeli</p>' +
                        '<button class="close btn-close" id="closeAdminPanel">x</button>' +
                    '</div>' +
                    '<div id="adminPanelWindowContent"></div>' +
                '</div>');

        const navBar =
            $('<nav class="navbar">' +
                '<button class="tab-button active" data-tab="tab1">Dynaaminen tielinkkiverkko</button>' +
                '<button class="tab-button" data-tab="tab2">Käyttäjien hallinta</button>' +
                '<button class="tab-button" data-tab="tab3">Alkulataus</button>' +
                '<button class="tab-button" data-tab="tab4">Tieosoiteverkon virheet</button>' +
                '</nav>');

        const dynamicLinkNetworkContent =
            '<div class="dynamic-link-network-content-wrapper">' +
                '<div>' +
                    '<p style="padding: 10px; font-size: 14px">Valitse päivämäärät päivittääksesi tielinkkiverkkoa</p>' +
                    '<p id="dynamicLinkNetworkInfo" style="padding: 10px; font-size: 14px"><span style="visibility: hidden;">Placeholder Text</span></p>' +
                '</div>' +
                '<div class="dynamic-link-network-input-wrapper">' +
                    '<div class="dynamic-link-network-input">' +
                        '<label>Nykytilanne</label>' +
                        '<input type="text" id="sourceDate" readonly>' +
                    '</div>' +
                    '<p style="font-size: 20px">&#8594</p>' +
                    '<div class="dynamic-link-network-input">' +
                        '<label>Tavoitepäivämäärä</label>' +
                        '<input type="text" id="targetDate" readonly>' +
                    '</div>' +
                '</div>' +
                '<div class="dynamic-link-network-input-wrapper">' +
                    '<input type="checkbox" id="processPerDay">' +
                    '<label for="processPerDay">Päivä kerrallaan</label>' +
                '</div>' +
                '<button id="updateLinkNetwork" class="btn btn-primary" style="max-height: 30px; margin: 10px">Päivitä tielinkkiverkko</button>' +
            '</div>';

        const contentForTabs = $(
                '<div class="content-area">' +
                    '<div id="tab1" class="tab-content active">' +
                        dynamicLinkNetworkContent +
                    '</div>' +
                    '<div id="tab2" class="tab-content">' +
                        '<p>TODO Käyttäjien hallinta tapahtuu täällä</p>' +
                    '</div>' +
                    '<div id="tab3" class="tab-content">' +
                        '<p>TODO Alkulatauksen käynnistys tapahtuu täältä</p>' +
                    '</div>' +
                    '<div id="tab4" class="tab-content">' +
                        '<p>TODO Tieosoiteverkon virheet listaus siirtyy tänne (ehkä?)</p>' +
                    '</div>' +
                '</div>'
            );

        const showAdminPanelWindow = function () {
            $('.container').append(
                '<div class="admin-panel-modal-overlay viite-modal-overlay confirm-modal" id="adminPanel">' +
                '<div class="admin-panel-modal-window"></div>' +
                '</div>');

            $('.admin-panel-modal-window').append(adminPanelWindow);

            const contentWrapper = $('#adminPanelWindowContent');
            contentWrapper.append(navBar);
            contentWrapper.append(contentForTabs);

            addDatePickersToInputFields();

            bindEvents();
        };

        const addDatePickersToInputFields = function () {
            // First get the current road link situation date from the backend
            backend.getRoadLinkDate(function (roadLinkDate) {
                // Then add the date pickers to the input fields
                addDatePickers(roadLinkDate);
            });
        };

        const addDatePickers = function(roadLinkDate) {

            const destroyPicker = function (picker) {
                // Destroy the date picker to prevent infinite recursion
                // If Pikaday is initialized twice for a single DOM Element, the two instances will create a loop, firing change events back and forth
                picker.destroy();
            };

            // Add date pickers to input fields
            const targetElem = $('#targetDate');
            const sourceElem = $('#sourceDate');

            const minimumDateObject = dateutil.parseCustomDateString(roadLinkDate.result);

            if (sourcePicker) {
                destroyPicker(sourcePicker);
                sourcePicker = '';
            }
            if (targetPicker) {
                destroyPicker(targetPicker);
                targetPicker = '';
            }

            sourcePicker = dateutil.addSingleDatePicker(sourceElem, {defaultDate: minimumDateObject, setDefaultDate: true});
            targetPicker = dateutil.addSingleDatePicker(targetElem);
            targetPicker.gotoDate(minimumDateObject);
        };

        const hideAdminPanelWindow = function () {
            $('.admin-panel-modal-overlay').remove();
        };

        function willPassValidations(dateString, _dateElement) {
            // Check format ignoring whitespace
            if (dateutil.isFinnishDateString(dateString.trim())) {
                const dateObject = moment(dateString, "DD-MM-YYYY").toDate();
                if (dateutil.isValidDate(dateObject)){
                    if (dateutil.isDateInYearRange(dateObject, ViiteConstants.MIN_YEAR_INPUT, ViiteConstants.MAX_YEAR_INPUT)) {
                        setInfoText("");
                        return true;
                    } else {
                        setInfoText("Vuosiluvun tulee olla väliltä " + ViiteConstants.MIN_YEAR_INPUT + " - " + ViiteConstants.MAX_YEAR_INPUT);
                        return false;
                    }
                } else {
                    setInfoText("Tarkista päivämäärä!");
                    return false;
                }
            } else {
                setInfoText("Päivämäärän tulee olla muodossa pp.kk.vvvv");
                return false;
            }
        }

        /**
         *  Check that the source date is before the target date.
         */
        function reasonableDates(sourceDateObject, _sourceDateElem, targetDateObject, _targetDateElem) {
            if (sourceDateObject >= targetDateObject) {
                setInfoText("Nykytilanteen tulee olla ennen tavoitepäivämäärää!");
                return false;
            } else {
                setInfoText("");
                return true;
            }
        }

        const startRoadLinkNetworkUpdate = function () {
            // Get the date input elements
            const sourceDateElem = document.getElementById('sourceDate');
            const targetDateElem = document.getElementById('targetDate');

            // Get the date input values
            const sourceDateString = sourceDateElem.value;
            const targetDateString = targetDateElem.value;

            // Convert date input text to date object
            const sourceDateObject = moment(sourceDateString, "DD-MM-YYYY").toDate();
            const targetDateObject = moment(targetDateString, "DD-MM-YYYY").toDate();

            // Date validations
            if (!willPassValidations(sourceDateString, sourceDateElem) ||
                !willPassValidations(targetDateString, targetDateElem) ||
                !reasonableDates(sourceDateObject, sourceDateElem, targetDateObject, targetDateElem)) {
                return; // Stop execution if validation fails
            }

            const sourceDateStringISO8601 = dateutil.parseDateToString(sourceDateObject);
            const targetDateStringISO8601 = dateutil.parseDateToString(targetDateObject);

            const checkBoxElement = document.getElementById('processPerDay');
            const checkboxBoolean = checkBoxElement.checked;

            const jsonDateData = {
                sourceDate: sourceDateStringISO8601,
                targetDate: targetDateStringISO8601,
                processPerDay: checkboxBoolean
            };

            backend.startLinkNetworkUpdate(jsonDateData, function(result) {
                const infoElem = document.getElementById('dynamicLinkNetworkInfo');
                infoElem.innerText = result.message; // Display the result message to the user
            });
        };

        const controlTabs = function (clickedButton, contentWrapper) {
            // If the clicked button is already active, do nothing
            if (clickedButton.hasClass('active')) {
                return;
            }

            const tabButtons = contentWrapper.find('.navbar .tab-button');
            const tabContents = contentWrapper.find('.content-area .tab-content');

            // Deactivate all buttons and hide all content panes within this window
            tabButtons.removeClass('active');
            tabContents.removeClass('active'); // Hides content with CSS rule

            // Activate the clicked button
            clickedButton.addClass('active');

            // Activate the corresponding content pane
            // Construct the ID selector (e.g., #tab1) and find it within the contentWrapper
            const targetTabId = clickedButton.data('tab'); // Get the value of data-tab="xxx"
            const targetTabContent = contentWrapper.find('#' + targetTabId);
            targetTabContent.addClass('active'); // Makes content visible via CSS rule
        };

        function dateFieldsFilled() {
            const sourceDate = document.getElementById('sourceDate');
            const targetDate = document.getElementById('targetDate');
            return sourceDate.value.length > 0 && targetDate.value.length > 0;
        }

        function countDaysBetweenTwoDates(date1Str, date2Str) {
            const date1 = dateutil.parseDate(date1Str);
            const date2 = dateutil.parseDate(date2Str);
            const msPerDay = 1000 * 60 * 60 * 24;
            const diffTime = date2 - date1;
            return Math.round(diffTime / msPerDay);
        }

        function setInfoText(text) {
            const infoElem = document.getElementById('dynamicLinkNetworkInfo');
            infoElem.innerText = text;
        }

        function buildInfoText() {
            const sourceDate = document.getElementById('sourceDate');
            const targetDate = document.getElementById('targetDate');
            const daysBetween = countDaysBetweenTwoDates(sourceDate.value, targetDate.value);
            return "Olet päivittämässä linkkiverkkoa " + daysBetween + " päivää " + (Math.sign(daysBetween) > 0 ? "eteenpäin." : "taaksepäin. Korjaa päivämäärät!");
        }

        function notifyUserWithDateChangeInfo() {
            const text = buildInfoText();
            setInfoText(text);
        }

        const bindEvents = function () {

            $('.generic-window-header').on('click', '#closeAdminPanel', function() {
                hideAdminPanelWindow();
            });

            // Navbar Tab Button Event Binding
            const contentWrapper = $('#adminPanelWindowContent'); // Get the main content container

            // Use event delegation: listen for clicks on the contentWrapper,
            // but only trigger the function if the click happened on an element
            // matching '.navbar .tab-button' inside the wrapper.
            contentWrapper.on('click', '.navbar .tab-button', function() {
                // 'this' refers to the specific .tab-button that was clicked
                const clickedButton = $(this);
                controlTabs(clickedButton, contentWrapper);
            });

            $('.generic-window').on('click', '#updateLinkNetwork', function() {
                startRoadLinkNetworkUpdate();
            });

            $('.generic-window').on('change', '#targetDate', function() {
                if (dateFieldsFilled() && reasonableDates())
                    notifyUserWithDateChangeInfo();
            });

            $('.generic-window').on('change', '#sourceDate', function() {
                if (dateFieldsFilled() && reasonableDates())
                    notifyUserWithDateChangeInfo();
            });

        };

        return {
            showAdminPanelWindow: showAdminPanelWindow
        };
    };
}(this));
