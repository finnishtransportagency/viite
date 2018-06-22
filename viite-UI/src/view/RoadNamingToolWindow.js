(function (root) {
    root.RoadNamingToolWindow = function (roadNameCollection) {

        var newId = -1000;
      var yearLimit = 5;
        var FINNISH_HINT_TEXT = 'pp.kk.vvvv';
        var nameToolSearchWindow = $('<div id="name-search-window" class="form-horizontal naming-list"></div>').hide();
        nameToolSearchWindow.append('<button class="close btn-close" id="closeRoadNameTool">x</button>');
        nameToolSearchWindow.append('<div class="content">Tienimi</div>');
        nameToolSearchWindow.append('<div class="name-tool-content-new">' +
            '<label class="name-tool-content-new label">Tie</label>' +
            '<div class = "panel-header">' +
            '<input type="text" class="road-input" title="Tie Nimi" id="roadSearchParameter">' +
            '<div id="buttons-div" style="display: inline-flex;">' +
            '<button id="executeRoadSearch" class="btn btn-sm btn-primary button-spacing">Hae</button>' +
            //Regular display: inline-block
            '<button id="createRoad" class="btn btn-sm btn-primary" style="display: none">Luo Tie</button>' +
            '</div>' +
            '<div id="table-labels">' +
            '<label class="content-new label" style="width:138px">Tie</label>' +
            '<label class="content-new label" style="width: 242px">Tien nimi</label>' +
            '<label class="content-new label" style="width: 100px">Alkupvm</label>' +
            '<label class="content-new label" style="width: 100px">Loppupvm</label>' +
            '</div>' +
            '</div>');

        nameToolSearchWindow.append('<div id="road-list" style="width:810px; height:365px; overflow:auto;"></div>');

        var staticFieldRoadNumber = function (dataField, roadId, fieldName) {
            var field;
            field = '<div sty>' +
                '<input class="input-road-details-readonly" style="width: 110px" value="' + dataField + '" data-FieldName="' + fieldName + '" readonly >' +
                '</div>';
            return field;
        };

        var staticFieldRoadList = function (dataField, writable, roadId, fieldName) {
            var field;
            var inputClass = (writable ? "form-control" : "input-road-details-readonly");
            var readOnly = (writable ? "" : "readonly");
            var leftMargin = (writable ? "margin-left: 8px;" : "");
            if ((fieldName === "startDate" || fieldName === "endDate") && writable) {
                field = '<div id="datePicker" value="' + dataField + '" data-roadId="' + roadId + '" data-FieldName="' + fieldName + '">' +
                    '<input id="datePickerInput" class="' + inputClass + ' date-picker-input" value="' + dataField + '" ' + readOnly + ' data-roadId="' + roadId + '" data-FieldName="' + fieldName + '" style="margin-top: 0px; ' + leftMargin + ' width: 85%">' +
                    '</div>';
            } else {
                field = '<div>' +
                    '<input class="' + inputClass + '" value="' + dataField + '" ' + readOnly + ' data-roadId="' + roadId + '" data-FieldName="' + fieldName + '" style="margin-top: 0px; ' + leftMargin + ' width: 85%">' +
                    '</div>';
            }
            return field;
        };

        var searchForRoadNames = function () {
            var roadParam = $('#roadSearchParameter').val();
            $('.roadList-item').remove();
            $('#saveChangedRoads').remove();
            roadNameCollection.fetchRoads(roadParam);
        };

        var addSaveEvent = function () {
            var saveButton = '<button id="saveChangedRoads" class="btn btn-primary save btn-save-road-data" disabled>Tallenna</button>';
            $('#road-list').append(saveButton);
            $('#saveChangedRoads').on('click', function (clickEvent) {
                new GenericConfirmPopup("Tiellä on jo nimi. Haluatko varmasti muuttaa sen nimeä?", {
                    successCallback: function () {
                        roadNameCollection.saveChanges();
                    },
                    closeCallback: function () {
                    }
                });
            });
        };

        var retroactivelyAddDatePickers = function () {
            var minDate = roadNameCollection.getMinDate();
            var inputs = $('.form-control[data-fieldName=startDate]:not([placeholder])');
            inputs.each(function (index, input) {
                if(input.dataset.roadid == newId) {
                    var datePicker = dateutil.addSingleDatePicker($(input));
                    if (minDate)
                        datePicker.setMinDate(minDate);
                }
            });
            $('.pika-single.is-bound').css("width", "auto");
        };

        function toggle() {
            $('.container').append('<div class="modal-overlay confirm-modal"><div class="modal-dialog"></div></div>');
            $('.modal-dialog').append(nameToolSearchWindow.toggle());
            $('#name-search-window .road-input').val('');
            bindEvents();
        }

        function hide() {
            nameToolSearchWindow.hide();
            $('#saveChangedRoads').remove();
            $('.modal-overlay').remove();
        }

      var getDateObjects = function (fieldValue) {
        var fieldDateString = fieldValue.split('.');
        var fieldDate = new Date(fieldDateString[2], fieldDateString[1], fieldDateString[0]);
        var futureDate = new Date();
        futureDate.setFullYear(futureDate.getFullYear() + yearLimit);
        return {'fieldDate': fieldDate, 'futureDate': futureDate};
      };

      var isValidDate = function(dateString) {
        var dates = getDateObjects(dateString);
        var splitDateString = dateString.split(".");

        var day = parseInt(splitDateString[0], 10);
        var month = parseInt(splitDateString[1], 10);
        var year = parseInt(splitDateString[2], 10);

        var monthLength = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 ];
        //Check for leap year
        if (year % 400 === 0 || (year % 100 !== 0 && year % 4 === 0))
          monthLength[1] = 29;

        var dateValidation = dates.futureDate > dates.fieldDate;
        var sizeValidation = splitDateString.length === 3 && _.last(splitDateString).length === 4;
        var dayValidation = day > 0 && day <= monthLength[month - 1];
        var monthValidation = month > 0 && month <= 12;

        return dateString !== '' && dateValidation && sizeValidation && dayValidation && monthValidation;
      };

        function toggleSaveButton() {
            $('#saveChangedRoads').prop("disabled",
                !_.every($('input.form-control[data-fieldname="roadName"],input.form-control[data-fieldname="startDate"]'), function (element) {
                  var dateString = $(element).val();
                  if ($(element).attr('data-FieldName') === "startDate")
                    return isValidDate(dateString);
                  else
                    return dateString !== '';
                })
            );
        }

        function editEvent(eventObject) {
            var target = $(eventObject.target);
            var roadId = target.attr("data-roadId");
            var fieldName = target.attr("data-FieldName");
            var fieldValue = target.val();
            var originalRoadId = target.closest("#newRoadName").attr("data-originalRoadId");
            switch (fieldName) {
                case "roadName":
                    roadNameCollection.setRoadName(roadId, fieldValue);
                    break;
                case "startDate":
                    if (roadId == newId) {
                        $('.form-control[data-roadId=' + originalRoadId + '][data-fieldName=endDate]').val(fieldValue);
                        roadNameCollection.setEndDate(originalRoadId, fieldValue);
                    }

                    if (isValidDate(fieldValue))
                      target.css('color', 'black');
                    else
                      target.css('color', 'red');
                    roadNameCollection.setStartDate(roadId, fieldValue);
                    break;
            }
            toggleSaveButton();
        }

        function bindEvents() {
            eventbus.on("namingTool:toggleCreate", function () {
                if ($("#createRoad").is(":visible")) {
                    $("#createRoad").css({display: "inline-block"});
                } else {
                    $("#createRoad").css({display: "none"});
                }
            });

            nameToolSearchWindow.on('click', 'button.close', function () {
                $('.roadList-item').remove();
                roadNameCollection.clear();
                hide();
            });

            nameToolSearchWindow.on('click', '#executeRoadSearch', function () {
                searchForRoadNames();
            });

            eventbus.on("roadNameTool:roadsFetched", function (roadData) {
                applicationModel.removeSpinner();
                var html = '<table id="roadList-table" style="align-content: left;align-items: left;table-layout: fixed;width: 100%;">';
                if (!_.isEmpty(roadData)) {
                    _.each(roadData, function (road) {
                        var writable = !road.endDate;
                        html += '<tr class="roadList-item">' +
                            '<td style="width: 150px;">' + staticFieldRoadNumber(road.roadNumber, road.id) + '</td>' +
                            '<td style="width: 250px;">' + staticFieldRoadList(road.name, writable, road.id, "roadName") + '</td>' +
                            '<td style="width: 110px;">' + staticFieldRoadList(road.startDate ? road.startDate.format('DD.MM.YYYY') : '', false, road.id, "startDate") + '</td>' +
                            '<td style="width: 110px;">' + staticFieldRoadList(road.endDate ? road.endDate.format('DD.MM.YYYY') : '', writable, road.id, "endDate") + '</td>';
                        if (!road.endDate) {
                            html += '<td>' + '<div id="plus_minus_buttons" data-roadId="' + road.id + '" data-roadNumber="' + road.roadNumber + '"><button class="project-open btn btn-new" style="alignment: middle; margin-bottom:6px; margin-left: 10px" id="new-road-name" data-roadId="' + road.id + '" data-roadNumber="' + road.roadNumber + '">+</button></div>' + '</td>' +
                                '</tr>' + '<tr style="border-bottom:1px solid darkgray; "><td colspan="100%"></td></tr>';
                        } else {
                            html += '<td>' + '<button class="project-open btn btn-new" style="visibility:hidden; alignment: right; margin-bottom:6px; margin-left: 70px" id="spaceFillerButton">+</button>' + '</td>' +
                                '</tr>' + '<tr style="border-bottom:1px solid darkgray; "><td colspan="100%"></td></tr>';
                        }
                    });
                    html += '</table>';
                    $('#road-list').html($(html));
                    var prevEndDateInput = $('#road-list tr.roadList-item input[data-fieldname="endDate"]').last();
                    if(prevEndDateInput[0].value === "")
                    prevEndDateInput.val(FINNISH_HINT_TEXT);
                    prevEndDateInput.prop("readonly", true);
                    retroactivelyAddDatePickers();

                    addSaveEvent();
                    $('.form-control').on("input", editEvent);
                    $('.date-picker-input').on("change", editEvent);

                    $('#new-road-name').on("click", function (eventObject) {
                        var target = $(eventObject.target);
                        target.css("visibility", "hidden");

                        var prevRoadNameInput = $('#road-list tr.roadList-item input[data-fieldname="roadName"]').last();
                        prevRoadNameInput.addClass("input-road-details-readonly");
                        prevRoadNameInput.removeClass("form-control");
                        prevRoadNameInput.prop("readonly", true);
                        var originalRoadId = target.attr("data-roadId");
                        var prevEndDateInput = $('.form-control[data-roadId=' + originalRoadId + '][data-fieldName=endDate]');
                        if(prevEndDateInput[0].value === "")
                        prevEndDateInput.val(FINNISH_HINT_TEXT);
                        prevEndDateInput.prop("readonly", true);

                        var roadNumber = target.attr("data-roadNumber");
                        $('#roadList-table').append('<tr class="roadList-item" id="newRoadName" data-originalRoadId ="' + originalRoadId + '" data-roadNumber="' + roadNumber + '">' +
                            '<td style="width: 150px;">' + staticFieldRoadNumber(roadNumber, newId) + '</td>' +
                            '<td style="width: 250px;">' + staticFieldRoadList("", true, newId, "roadName") + '</td>' +
                            '<td style="width: 110px;">' + staticFieldRoadList("", true, newId, "startDate") + '</td>' +
                            '<td style="width: 110px;">' + staticFieldRoadList("", true, newId, "endDate") + '</td>' +
                            '<td>' + '<div id="plus_minus_buttons" data-roadId="' + newId + '" data-roadNumber="' + roadNumber + '"><button class="project-open btn btn-new" style="alignment: middle; margin-bottom:6px; margin-left: 10px" id="undo-new-road-name" data-roadId="' + originalRoadId + '" data-roadNumber="' + roadNumber + '">-</button></div>' + '</td>' +
                            '</tr>' + '<tr style="border-bottom:1px solid darkgray; "><td colspan="100%"></td></tr>');
                        var newEndDateInput = $('.form-control[data-roadId=' + newId + '][data-fieldName=endDate]');
                        newEndDateInput.val(FINNISH_HINT_TEXT);
                        newEndDateInput.prop("readonly", true);
                        retroactivelyAddDatePickers();
                        toggleSaveButton();
                        $('.form-control').on("input", editEvent);
                        $('.date-picker-input').on("change", editEvent);

                        $('#undo-new-road-name').on("click", function (eventObject) {
                            var target = $(eventObject.target);
                            var roadId = target.attr("data-roadId");
                            roadNameCollection.undoNewRoadName();
                            var roadNumber = target.attr("data-roadNumber");
                            $('#new-road-name[data-roadid|=' + roadId + '][data-roadnumber|=' + roadNumber + ']').css("visibility", "visible");
                            $('#newRoadName[data-originalRoadId|=' + roadId + '][data-roadnumber|=' + roadNumber + ']').remove();

                            var prevRoadNameInput = $('#road-list tr.roadList-item input[data-fieldname="roadName"]').last();
                            prevRoadNameInput.addClass("form-control");
                            prevRoadNameInput.removeClass("input-road-details-readonly");
                            prevRoadNameInput.prop("readonly", false);
                            var prevEndDateInput = $('.form-control[data-roadId=' + originalRoadId + '][data-fieldName=endDate]');
                            prevEndDateInput.val(FINNISH_HINT_TEXT);
                        });
                    });

                } else {
                    html += '</table>';
                    $('#road-list').html($(html));
                    retroactivelyAddDatePickers();
                }
            });

            eventbus.on("roadNameTool:saveSuccess", function () {
                applicationModel.removeSpinner();
                $('#saveChangedRoads').prop("disabled", true);
                searchForRoadNames();
            });

            eventbus.on('roadNameTool:saveUnsuccessful', function (error) {
                new ModalConfirm(error ? error.toString() : 'Tallennus epäonnistui.');
                applicationModel.removeSpinner();
            });
        }

        return {
            toggle: toggle,
            hide: hide,
            element: nameToolSearchWindow,
            bindEvents: bindEvents
        };

    };
})(this);