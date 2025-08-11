(function (root) {
  root.RoadNamingToolWindow = function (roadNameCollection) {

    const newId = -1000;
    const FINNISH_HINT_TEXT = 'pp.kk.vvvv';
    const defaultDateFormat = 'DD.MM.YYYY';
    const nameToolSearchWindow = $('<div id="name-search-window" class="form-horizontal naming-list"></div>').hide();

    nameToolSearchWindow.append('<button class="close btn-close" id="closeRoadNameTool">x</button>');
    nameToolSearchWindow.append('<div class="content">Tienimi</div>');
    nameToolSearchWindow.append(`
      <div class="name-tool-content-new">
        <label></label>
        <div class="panel-header">
          <input type="text" class="road-input" style="height: 22px" id="roadSearchParameter" placeholder="Tienumero">
          <div id="buttons-div">
            <button id="executeRoadSearch" class="btn btn-sm btn-primary button-spacing">Hae</button>
            <button id="createRoad" class="btn btn-sm btn-primary" style="display: none">Luo Tie</button>
          </div>
        </div>
        <div id="table-labels" style="padding-bottom: 4px">
          <label class="label" style="width:138px">Tie</label>
          <label class="label" style="width:242px">Tien nimi</label>
          <label class="label" style="width:100px">Alkupvm</label>
          <label class="label" style="width:100px">Loppupvm</label>
        </div>
      </div>
    `);

    nameToolSearchWindow.append('<div id="road-list" style="width:810px; height:365px; overflow:auto;"></div>');

    const staticFieldRoadNumber = (dataField, roadId, fieldName) => `
      <div>
        <input class="input-road-details-readonly" style="width: 110px" value="${dataField}" data-FieldName="${fieldName}" name="roadNumber-${roadId}" readonly>
      </div>
    `;

    const staticFieldRoadList = (dataField, writable, roadId, fieldName, maxLength) => {
      const inputClass = writable ? "form-control" : "input-road-details-readonly";
      const readOnly = writable ? "" : "readonly";
      const leftMargin = writable ? "margin-left: 8px;" : "";
      const maxLengthAttr = (maxLength !== undefined) ? `maxlength="${maxLength}"` : "";

      if ((fieldName === "startDate" || fieldName === "endDate") && writable) {
        return `
          <div id="datePicker" data-roadId="${roadId}" data-FieldName="${fieldName}">
            <input id="datePickerInput" class="${inputClass} date-picker-input" value="${dataField}" ${readOnly} data-roadId="${roadId}" data-FieldName="${fieldName}" name="${fieldName}-${roadId}" style="margin-top: 0; ${leftMargin} width: 85%">
          </div>
        `;
      } else if (fieldName === "roadName") {
        return `
          <div>
            <input class="${inputClass}" value="${dataField}" ${readOnly} data-roadId="${roadId}" data-FieldName="${fieldName}" data-originalvalue="${dataField}" name="${fieldName}-${roadId}" style="margin-top: 0; ${leftMargin} width: 85%" ${maxLengthAttr}>
          </div>
        `;
      } else {
        return `
          <div>
            <input class="${inputClass}" value="${dataField}" ${readOnly} data-roadId="${roadId}" data-FieldName="${fieldName}" name="${fieldName}-${roadId}" style="margin-top: 0; ${leftMargin} width: 85%">
          </div>
        `;
      }
    };

    const searchForRoadNames = () => {
      const roadParam = $('#roadSearchParameter').val();
      $('.roadList-item').remove();
      $('#saveChangedRoads').remove();
      roadNameCollection.fetchRoads(roadParam);
    };

    const addSaveEvent = () => {
      const saveButton = '<button id="saveChangedRoads" class="btn btn-primary save btn-save-road-data" disabled>Tallenna</button>';
      $('#road-list').append(saveButton);
      $('#saveChangedRoads').on('click', () => {
        const saveMessage = ($('#newRoadName').length > 0)
            ? "Tiellä on jo nimi. Haluatko varmasti antaa sille uuden nimen?"
            : "Tiellä on jo nimi. Haluatko varmasti muokata sitä?";

        new GenericConfirmPopup(saveMessage, {
          successCallback: () => roadNameCollection.saveChanges(),
          closeCallback: () => {}
        });
      });
    };

    const retroactivelyAddDatePickers = (originalStartDate) => {
      const inputs = $('.form-control[data-fieldName=startDate]:not([placeholder])');
      inputs.each((_, input) => {
        if (parseInt(input.dataset.roadid) === newId) {
          dateutil.addSingleDatePickerWithMinDate($(input), originalStartDate);
        }
      });
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

    const getDateObjects = (fieldValue, originalStartDate) => {
      const fieldDateString = fieldValue.trim();
      const fieldDate = moment(fieldDateString, defaultDateFormat);
      const lowerStart = moment(originalStartDate.trim(), defaultDateFormat).add(1, 'days');
      const upperLimitStart = moment(originalStartDate.trim(), defaultDateFormat).add(5, 'years');
      const currentUpperLimit = moment().add(5, 'years');

      return {
        fieldDate,
        futureDateSinceCurrent: currentUpperLimit,
        pastDate: lowerStart,
        futureDateSinceOriginal: upperLimitStart
      };
    };

    const isValidDate = (dateString, originalStartDate) => {
      const dates = getDateObjects(dateString, originalStartDate);
      const splitDateString = dateString.split(".");
      const day = parseInt(splitDateString[0]);
      const month = parseInt(splitDateString[1]);
      const year = parseInt(splitDateString[2]);

      const monthLength = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
      if (year % 400 === 0 || (year % 100 !== 0 && year % 4 === 0)) {
        monthLength[1] = 29;
      }

      const dateValidation = dates.futureDateSinceCurrent.isAfter(dates.fieldDate) && dates.pastDate.isSameOrBefore(dates.fieldDate);
      const sizeValidation = splitDateString.length === 3 && splitDateString[splitDateString.length - 1].length === 4;
      const dayValidation = day > 0 && day <= monthLength[month - 1];
      const monthValidation = month > 0 && month <= 12;

      return dateString !== '' && dateValidation && sizeValidation && dayValidation && monthValidation;
    };

    function toggleSaveButton() {
      // Check if a new row is being created and validate its fields
      const newRow = $('#newRoadName');
      const hasNewRow = newRow.length > 0;

      let newRowValid = false;
      if (hasNewRow) {
        const newNameEl = newRow.find('input.form-control[data-fieldname="roadName"]');
        const newStartEl = newRow.find('input.date-picker-input[data-fieldname="startDate"]');
        const nameOk = newNameEl.length > 0 && newNameEl.val().trim() !== '';
        const startOk = newStartEl.length > 0 && isValidDate(newStartEl.val(), newRow.attr('data-originalStartDate'));
        newRowValid = nameOk && startOk;
      }

      // Check if any existing editable road name has been modified from its original value
      const existingChanged = $('tr.roadList-item:not(#newRoadName) input.form-control[data-fieldname="roadName"]').toArray()
          .some(el => {
            const $el = $(el);
            const original = $el.attr('data-originalvalue');
            return typeof original !== 'undefined' && $el.val() !== original;
          });

      const enable = newRowValid || existingChanged;
      $('#saveChangedRoads').prop('disabled', !enable);
    }

    function editEvent(eventObject) {
      const target = $(eventObject.target);
      const roadId = target.attr("data-roadId");
      const fieldName = target.attr("data-FieldName");
      const fieldValue = target.val();
      const originalRoadId = target.closest("#newRoadName").attr("data-originalRoadId");
      const originalStartDate = target.closest("#newRoadName").attr("data-originalStartDate");

      switch (fieldName) {
        case "roadName":
          roadNameCollection.setRoadName(roadId, fieldValue);
          break;
        case "startDate":
          if (parseInt(roadId) === newId) {
            const endDateForPreviousRoadName = moment(fieldValue, defaultDateFormat).subtract(1, 'days').format(defaultDateFormat);
            $(`.form-control[data-roadId=${originalRoadId}][data-fieldName=endDate]`).val(endDateForPreviousRoadName);
            roadNameCollection.setEndDate(originalRoadId, endDateForPreviousRoadName);
          }
          target.css('color', isValidDate(fieldValue, originalStartDate) ? 'black' : 'red');
          roadNameCollection.setStartDate(roadId, fieldValue);
          break;
        default:
          break;
      }
      toggleSaveButton();
    }

    function bindEvents() {
      eventbus.on("namingTool:toggleCreate", () => {
        const $createRoad = $("#createRoad");
        $createRoad.css({
          display: $createRoad.is(":visible") ? "inline-block" : "none"
        });
      });


      nameToolSearchWindow.on('click', 'button.close', () => {
        $('.roadList-item').remove();
        roadNameCollection.clear();
        hide();
      });

      nameToolSearchWindow.on('click', '#executeRoadSearch', () => {
        searchForRoadNames();
      });

      eventbus.on("roadNameTool:roadsFetched", (roadData) => {
        applicationModel.removeSpinner();
        let html = '<table id="roadList-table" style="table-layout: fixed; width: 100%;">';

        if (roadData && roadData.length > 0) {
          roadData.forEach(road => {
            const writable = !road.endDate;
            const startDate = road.startDate ? road.startDate.format('DD.MM.YYYY') : '';
            html += `
              <tr class="roadList-item">
                <td style="width: 150px;">${staticFieldRoadNumber(road.roadNumber, road.id)}</td>
                <td style="width: 250px;">${staticFieldRoadList(road.name, writable, road.id, "roadName", 50)}</td>
                <td style="width: 110px;">${staticFieldRoadList(startDate, false, road.id, "startDate")}</td>
                <td style="width: 110px;">${staticFieldRoadList(road.endDate ? road.endDate.format('DD.MM.YYYY') : '', writable, road.id, "endDate")}</td>
            `;
            if (road.endDate) {
              html += `<td><button class="project-open btn btn-new" style="visibility:hidden; margin-bottom:6px; margin-left: 70px" id="spaceFillerButton">+</button></td>
                </tr><tr style="border-bottom:1px solid darkgray;"><td colspan="100%"></td></tr>`;
            } else {
              html += `<td><div id="plus_minus_buttons" data-roadId="${road.id}" data-roadNumber="${road.roadNumber}"><button class="project-open btn btn-new" style="margin-bottom:6px; margin-left: 10px" id="new-road-name" data-roadId="${road.id}" data-roadNumber="${road.roadNumber}" data-originalStartDate="${startDate}">+</button></div></td>
                </tr><tr style="border-bottom:1px solid darkgray;"><td colspan="100%"></td></tr>`;
            }
          });

          html += '</table>';
          $('#road-list').html($(html));
          const prevEndDateInput = $('#road-list tr.roadList-item input[data-fieldname="endDate"]').last();
          if (prevEndDateInput[0].value === "") prevEndDateInput.val(FINNISH_HINT_TEXT);
          prevEndDateInput.prop("readonly", true);
          retroactivelyAddDatePickers();

          addSaveEvent();
          $('.form-control').on("input", editEvent);
          $('.date-picker-input').on("change", editEvent);
          toggleSaveButton();

          $('#new-road-name').on("click", (eventObject) => {
            const target = $(eventObject.target);
            target.css("visibility", "hidden");

            const prevRoadNameInput = $('#road-list tr.roadList-item input[data-fieldname="roadName"]').last();
            prevRoadNameInput.addClass("input-road-details-readonly").removeClass("form-control").prop("readonly", true);

            const originalRoadId = target.attr("data-roadId");
            const prevEndDateInputControl = $(`.form-control[data-roadId=${originalRoadId}][data-fieldName=endDate]`);
            if (prevEndDateInputControl[0].value === "") prevEndDateInputControl.val(FINNISH_HINT_TEXT);
            prevEndDateInputControl.prop("readonly", true);

            const roadNumber = target.attr("data-roadNumber");
            const originalStartDate = target.attr("data-originalStartDate");

            $('#roadList-table').append(`
              <tr class="roadList-item" id="newRoadName" data-originalRoadId="${originalRoadId}" data-roadNumber="${roadNumber}" data-originalStartDate="${originalStartDate}">
                <td style="width: 150px;">${staticFieldRoadNumber(roadNumber, newId)}</td>
                <td style="width: 250px;">${staticFieldRoadList("", true, newId, "roadName", 50)}</td>
                <td style="width: 110px;">${staticFieldRoadList("", true, newId, "startDate")}</td>
                <td style="width: 110px;">${staticFieldRoadList("", true, newId, "endDate")}</td>
                <td><div id="plus_minus_buttons" data-roadId="${newId}" data-roadNumber="${roadNumber}"><button class="project-open btn btn-new" style="margin-bottom:6px; margin-left: 10px" id="undo-new-road-name" data-roadId="${originalRoadId}" data-roadNumber="${roadNumber}">-</button></div></td>
              </tr><tr style="border-bottom:1px solid darkgray;"><td colspan="100%"></td></tr>
            `);

            const newEndDateInput = $(`.form-control[data-roadId=${newId}][data-fieldName=endDate]`);
            newEndDateInput.val(FINNISH_HINT_TEXT).prop("readonly", true);
            retroactivelyAddDatePickers(originalStartDate);
            toggleSaveButton();
            $('.form-control').on("input", editEvent);
            $('.date-picker-input').on("change", editEvent);

            $('#undo-new-road-name').on("click", (eventObjectRoadName) => {
              const roadNameTarget = $(eventObjectRoadName.target);
              const roadId = roadNameTarget.attr("data-roadId");
              roadNameCollection.undoNewRoadName();
              const roadnameRoadNumber = roadNameTarget.attr("data-roadNumber");
              $(`#new-road-name[data-roadid|=${roadId}][data-roadnumber|=${roadnameRoadNumber}]`).css("visibility", "visible");
              $(`#newRoadName[data-originalRoadId|=${roadId}][data-roadnumber|=${roadnameRoadNumber}]`).remove();

              const prevRoadNameInputControl = $('#road-list tr.roadList-item input[data-fieldname="roadName"]').last();
              prevRoadNameInputControl.addClass("form-control").removeClass("input-road-details-readonly").prop("readonly", false);

              const prevEndDateInputField = $(`.form-control[data-roadId=${originalRoadId}][data-fieldName=endDate]`);
              prevEndDateInputField.val(FINNISH_HINT_TEXT);
              toggleSaveButton();
            });
          });

        } else {
          html += '</table>';
          $('#road-list').html($(html));
          retroactivelyAddDatePickers();
        }
      });

      eventbus.on("roadNameTool:saveSuccess", () => {
        applicationModel.removeSpinner();
        $('#saveChangedRoads').prop("disabled", true);
        searchForRoadNames();
      });

      eventbus.on('roadNameTool:saveUnsuccessful', (error) => {
        new ModalConfirm(error ? error.toString() : 'Tallennus epäonnistui.');
        applicationModel.removeSpinner();
      });
    }

    return {
      toggle,
      hide,
      element: nameToolSearchWindow,
      bindEvents
    };

  };
}(this));
