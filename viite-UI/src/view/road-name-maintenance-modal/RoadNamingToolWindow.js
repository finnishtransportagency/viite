import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { ModalContainer } from '@components/modals/ModalContainer.js';
import { DatePicker } from '@components/date-picker/DatePicker.js';
import { eventbus } from '@utils/eventbus.js';

export function RoadNamingToolWindow(roadNameCollection) {
    const newId = -1000;
    const defaultDateFormat = 'DD.MM.YYYY';
    let modal = null;

    // Generate the base HTML content for the naming tool
    const createNamingToolContent = () => {
      return $(`
        <div id="name-search-window" class="form-horizontal naming-list">
          <div class="name-tool-content-new">
            <div class="panel-header">
              <input type="text" class="road-input" style="height: 22px" id="roadSearchParameter" placeholder="Tienumero">
              <div id="buttons-div">
                <button id="executeRoadSearch" class="btn-primary" style="height: 22px; padding: 2px 8px">Hae</button>
                <button id="createRoad" class="btn-primary" style="display: none">Luo Tie</button>
              </div>
            </div>
            <div id="table-labels" style="padding-bottom: 4px">
              <label class="label" style="width:138px">Tie</label>
              <label class="label" style="width:242px">Tien nimi</label>
              <label class="label" style="width:100px">Alkupvm</label>
              <label class="label" style="width:100px">Loppupvm</label>
            </div>
          </div>
          <div id="road-list" style="width:810px; height:365px; overflow:auto;"></div>
        </div>
      `);
    };

    const staticFieldRoadNumber = (dataField, roadId) => `
      <div>
        <input class="input-road-details-readonly" value="${dataField}" data-FieldName="roadNumber" name="roadNumber-${roadId}" readonly>
      </div>
    `;

    const staticFieldRoadList = (dataField, writable, roadId, fieldName, maxLength) => {
      const inputClass = writable ? "form-control" : "input-road-details-readonly";
      const readOnly = writable ? "" : "readonly";
      const leftMargin = writable ? "margin-left: 8px;" : "";
      const maxLengthAttr = (maxLength !== undefined) ? `maxlength="${maxLength}"` : "";

      if ((fieldName === "startDate" || fieldName === "endDate") && writable) {
        return `
          <div class="date-picker-container" data-roadId="${roadId}" data-FieldName="${fieldName}">
            <input id="datePickerInput-${roadId}-${fieldName}" class="${inputClass} date-picker-input" value="${dataField}" ${readOnly} data-roadId="${roadId}" data-FieldName="${fieldName}" name="${fieldName}-${roadId}" style="margin-top: 0; ${leftMargin} width: 85%">
          </div>
        `;
      } else {
        return `
          <div>
            <input class="${inputClass}" value="${dataField}" ${readOnly} data-roadId="${roadId}" data-FieldName="${fieldName}" data-originalvalue="${dataField}" name="${fieldName}-${roadId}" style="margin-top: 0; ${leftMargin} width: 85%" ${maxLengthAttr}>
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
      if ($('#saveChangedRoads').length === 0) {
        const saveButton = '<button id="saveChangedRoads" class="btn-primary save btn-save-road-data" disabled>Tallenna</button>';
        $('#road-list').append(saveButton);
        $('#saveChangedRoads').on('click', () => {
          const saveMessage = ($('#newRoadName').length > 0)
            ? "Tiellä on jo nimi. Haluatko varmasti antaa sille uuden nimen?"
            : "Tiellä on jo nimi. Haluatko varmasti muokata sitä?";

          new ConfirmPopup(saveMessage, {
            successCallback: () => roadNameCollection.saveChanges(),
            closeCallback: () => {}
          });
        });
      }
    };

    const retroactivelyAddDatePickers = (originalStartDate) => {
      const inputs = $('.date-picker-input:not([placeholder])');
      inputs.each((_, input) => {
        if (parseInt(input.dataset.roadid) === newId && !$(input).hasClass('hasDatepicker')) {
          const datePicker = new DatePicker({
            id: input.id,
            minDate: originalStartDate,
            onChange: () => {
              toggleSaveButton();
            }
          });
          datePicker.addToElement($(input));
        }
      });
    };

    function toggleSaveButton() {
      const newRow = $('#newRoadName');
      const hasNewRow = newRow.length > 0;

      let newRowValid = false;
      if (hasNewRow) {
        const newNameEl = newRow.find('input[data-FieldName="roadName"]');
        const newStartEl = newRow.find('input[data-FieldName="startDate"]');
        const nameOk = newNameEl.val() && newNameEl.val().trim() !== '';
        const startOk = newStartEl.val() && isValidDate(newStartEl.val(), newRow.attr('data-originalStartDate'));
        newRowValid = nameOk && startOk;
      }

      const existingChanged = $('tr.roadList-item:not(#newRoadName) input.form-control[data-FieldName="roadName"]').toArray()
        .some(el => {
          const $el = $(el);
          const original = $el.attr('data-originalvalue');
          return typeof original !== 'undefined' && $el.val() !== original;
        });

      $('#saveChangedRoads').prop('disabled', !(newRowValid || existingChanged));
    }

    function editEvent(eventObject) {
      const target = $(eventObject.target);
      const roadId = target.attr("data-roadId");
      const fieldName = target.attr("data-FieldName");
      const fieldValue = target.val();
      const parentRow = target.closest(".roadList-item");
      const originalRoadId = parentRow.attr("data-originalRoadId");
      const originalStartDate = parentRow.attr("data-originalStartDate");

      switch (fieldName) {
        case "roadName":
          roadNameCollection.setRoadName(roadId, fieldValue);
          break;
        case "startDate":
          if (parseInt(roadId) === newId) {
            const endDateForPreviousRoadName = moment(fieldValue, defaultDateFormat).subtract(1, 'days').format(defaultDateFormat);
            $(`.form-control[data-roadId=${originalRoadId}][data-FieldName=endDate]`).val(endDateForPreviousRoadName);
            roadNameCollection.setEndDate(originalRoadId, endDateForPreviousRoadName);
          }
          target.css('color', isValidDate(fieldValue, originalStartDate) ? 'black' : 'red');
          roadNameCollection.setStartDate(roadId, fieldValue);
          break;
        default:
          console.warn(`Unexpected field name: ${fieldName}`);
          break;
      }
      toggleSaveButton();
    }

    const isValidDate = (dateString, originalStartDate) => {
      if (!dateString || !originalStartDate) return false;
      const dates = getDateObjects(dateString, originalStartDate);
      const splitDateString = dateString.split(".");
      if (splitDateString.length !== 3) return false;

      const day = parseInt(splitDateString[0]);
      const month = parseInt(splitDateString[1]);
      const year = parseInt(splitDateString[2]);

      const monthLength = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
      if (year % 400 === 0 || (year % 100 !== 0 && year % 4 === 0)) monthLength[1] = 29;

      const dateValidation = dates.futureDateSinceCurrent.isAfter(dates.fieldDate) && dates.pastDate.isSameOrBefore(dates.fieldDate);
      const sizeValidation = splitDateString[2].length === 4;
      const dayValidation = day > 0 && day <= monthLength[month - 1];
      const monthValidation = month > 0 && month <= 12;

      return dateValidation && sizeValidation && dayValidation && monthValidation;
    };

    const getDateObjects = (fieldValue, originalStartDate) => {
      const fieldDate = moment(fieldValue.trim(), defaultDateFormat);
      const lowerStart = moment(originalStartDate.trim(), defaultDateFormat).add(1, 'days');
      const currentUpperLimit = moment().add(5, 'years');
      return { fieldDate, futureDateSinceCurrent: currentUpperLimit, pastDate: lowerStart };
    };

    function bindEvents() {
      const $content = modal.getContent();

      // Use the modal's content container for event delegation
      $content.on('input change', '.form-control, .date-picker-input', (e) => editEvent(e));
      $content.on('click', '#executeRoadSearch', () => searchForRoadNames());

      eventbus.on("roadNameTool:roadsFetched", (roadData) => {
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
                <td>
                  ${road.endDate ?
                    `<button class="btn-primary" style="visibility:hidden;">+</button>` : 
                    `<div id="plus_minus_buttons"><button class="btn-primary" id="new-road-name" data-roadId="${road.id}" data-roadNumber="${road.roadNumber}" data-originalStartDate="${startDate}">+</button></div>`
                  }
                </td>
              </tr><tr style="border-bottom:1px solid darkgray;"><td colspan="100%"></td></tr>`;
          });

          html += '</table>';
          $content.find('#road-list').html(html);
          
          const lastEndDate = $content.find('input[data-FieldName="endDate"]').last();
          if (lastEndDate.val() === "") lastEndDate.val("pp.kk.vvvv");
          lastEndDate.prop("readonly", true);

          addSaveEvent();
          toggleSaveButton();
        }
      });

      // Handle the "+" button click (delegated via document or $content)
      $(document).off('click', '#new-road-name').on('click', '#new-road-name', (e) => {
        const target = $(e.target);
        target.css("visibility", "hidden");
        const prevRoadNameInput = $('#road-list tr.roadList-item input[data-FieldName="roadName"]').last();
        prevRoadNameInput.addClass("input-road-details-readonly").removeClass("form-control").prop("readonly", true);

        const originalRoadId = target.attr("data-roadId");
        const originalStartDate = target.attr("data-originalStartDate");
        const roadNumber = target.attr("data-roadNumber");

        $('#roadList-table').append(`
          <tr class="roadList-item" id="newRoadName" data-originalRoadId="${originalRoadId}" data-roadNumber="${roadNumber}" data-originalStartDate="${originalStartDate}">
            <td style="width: 150px;">${staticFieldRoadNumber(roadNumber, newId)}</td>
            <td style="width: 250px;">${staticFieldRoadList("", true, newId, "roadName", 50)}</td>
            <td style="width: 110px;">${staticFieldRoadList("", true, newId, "startDate")}</td>
            <td style="width: 110px;">${staticFieldRoadList("", true, newId, "endDate")}</td>
            <td><button class="btn-primary" id="undo-new-road-name" data-roadId="${originalRoadId}"> — </button></td>
          </tr><tr style="border-bottom:1px solid darkgray;"><td colspan="100%"></td></tr>
        `);

        $(`.form-control[data-roadId=${newId}][data-FieldName=endDate]`).val("pp.kk.vvvv").prop("readonly", true);
        retroactivelyAddDatePickers(originalStartDate);
        toggleSaveButton();
      });

      $(document).off('click', '#undo-new-road-name').on('click', '#undo-new-road-name', (e) => {
        const roadId = $(e.target).attr("data-roadId");
        roadNameCollection.undoNewRoadName();
        $(`#new-road-name[data-roadId=${roadId}]`).css("visibility", "visible");
        $('#newRoadName').next('tr').remove();
        $('#newRoadName').remove();
        const prevName = $('#road-list tr.roadList-item input[data-FieldName="roadName"]').last();
        prevName.addClass("form-control").removeClass("input-road-details-readonly").prop("readonly", false);
        toggleSaveButton();
      });
    }

    function showRoadNamingToolWindow() {
      modal = new ModalContainer({
        onClose: () => {
          $('.roadList-item').remove();
          roadNameCollection.clear();
          modal = null;
        }
      });

      modal.open({
        title: 'Tienimi',
        content: createNamingToolContent()
      });
      bindEvents();
    }

    return { show: showRoadNamingToolWindow };
}