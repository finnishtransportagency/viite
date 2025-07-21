(function (root) {
    root.dynamicLinkNetworkContent = function (backend, dateutil, ViiteConstants) {
        let sourcePicker = '';
        let targetPicker = '';

        function getContent() {
            return '' +
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
        }

        function addDatePickersToInputFields() {
            backend.getRoadLinkDate(function (roadLinkDate) {
                const minimumDateObject = dateutil.parseCustomDateString(roadLinkDate.result);

                if (sourcePicker) {
                    sourcePicker.destroy();
                    sourcePicker = '';
                }
                if (targetPicker) {
                    targetPicker.destroy();
                    targetPicker = '';
                }

                sourcePicker = dateutil.addSingleDatePicker($('#sourceDate'), { defaultDate: minimumDateObject, setDefaultDate: true });
                targetPicker = dateutil.addSingleDatePicker($('#targetDate'));
                targetPicker.gotoDate(minimumDateObject);
            });
        }

        function willPassValidations(dateString) {
            if (dateutil.isFinnishDateString(dateString.trim())) {
                const dateObject = moment(dateString, "DD-MM-YYYY").toDate();
                if (dateutil.isValidDate(dateObject)) {
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

        function reasonableDates(sourceDateObject, targetDateObject) {
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
            const date1 = dateutil.parseDate(date1Str);
            const date2 = dateutil.parseDate(date2Str);
            const msPerDay = 1000 * 60 * 60 * 24;
            const diffTime = date2 - date1;
            return Math.round(diffTime / msPerDay);
        }

        function buildInfoText() {
            const sourceDate = document.getElementById('sourceDate').value;
            const targetDate = document.getElementById('targetDate').value;
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
            const sourceDateElem = document.getElementById('sourceDate');
            const targetDateElem = document.getElementById('targetDate');
            const sourceDateString = sourceDateElem.value;
            const targetDateString = targetDateElem.value;

            const sourceDateObject = moment(sourceDateString, "DD-MM-YYYY").toDate();
            const targetDateObject = moment(targetDateString, "DD-MM-YYYY").toDate();

            if (!willPassValidations(sourceDateString) ||
                !willPassValidations(targetDateString) ||
                !reasonableDates(sourceDateObject, targetDateObject)) {
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

            $container.on('change', '#targetDate', function () {
                if (dateFieldsFilled()) notifyUserWithDateChangeInfo();
            });

            $container.on('change', '#sourceDate', function () {
                if (dateFieldsFilled()) notifyUserWithDateChangeInfo();
            });
        }

        return {
            getContent: getContent,
            addDatePickersToInputFields: addDatePickersToInputFields,
            bindEvents: bindEvents
        };
    };
})(this);
