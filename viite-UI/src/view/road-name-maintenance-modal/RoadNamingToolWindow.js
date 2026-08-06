import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { ModalContainer } from '@components/modals/ModalContainer.js';
import { DatePicker } from '@components/date-picker/DatePicker.js';
import { button } from '@components/button/Button.js';
import { showToast } from '@components/toast/Toast.js';

export function RoadNamingToolWindow(roadNameCollection) {
	const newId = -1000;
	const defaultDateFormat = 'DD.MM.YYYY';
	const acceptedDateFormats = ['D.M.YYYY', 'DD.MM.YYYY'];
	let modal = null;

	// Generate the base HTML content for the naming tool
	const createNamingToolContent = () => {
		const searchButton = button({ id: 'executeRoadSearch', label: 'Hae', className: 'btn-primary', onClick: searchForRoadNames });
		return $(`
        <div id="name-search-window" class="form-horizontal naming-list">
          <div class="name-tool-content-new">
            <div class="panel-header">
              <input type="text" class="form-control" id="roadSearchParameter" placeholder="Tienumero" autocomplete="off">
              ${searchButton}
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
            <input id="datePickerInput-${roadId}-${fieldName}" class="${inputClass} date-picker-input" value="${dataField}" ${readOnly} data-roadId="${roadId}" data-FieldName="${fieldName}" name="${fieldName}-${roadId}" style="margin-top: 0; ${leftMargin} width: 85%" autocomplete="off">
          </div>
        `;
		} else {
			return `
          <div>
            <input class="${inputClass}" value="${dataField}" ${readOnly} data-roadId="${roadId}" data-FieldName="${fieldName}" data-originalvalue="${dataField}" name="${fieldName}-${roadId}" style="margin-top: 0; ${leftMargin} width: 85%" ${maxLengthAttr} autocomplete="off">
          </div>
        `;
		}
	};

	const renderRoadData = (roadData, $content) => {
		let html = '<table id="roadList-table" style="table-layout: fixed; width: 100%;">';

		if (roadData && roadData.length > 0) {
			roadData.forEach(road => {
				const writable = !road.endDate;
				const startDate = road.startDate ? road.startDate.format('DD.MM.YYYY') : '';
				const plusCell = road.endDate
					? `<div></div>`
					: `<div id="plus_minus_buttons">${button({ id: `new-road-name-${road.id}`, label: '+', className: 'btn-primary', onClick: () => handleNewRoadName(road.id, road.roadNumber, startDate) })}</div>`;
				html += `
            <tr class="roadList-item">
              <td style="width: 150px;">${staticFieldRoadNumber(road.roadNumber, road.id)}</td>
              <td style="width: 250px;">${staticFieldRoadList(road.name, writable, road.id, "roadName", 50)}</td>
              <td style="width: 110px;">${staticFieldRoadList(startDate, false, road.id, "startDate")}</td>
              <td style="width: 110px;">${staticFieldRoadList(road.endDate ? road.endDate.format('DD.MM.YYYY') : '', writable, road.id, "endDate")}</td>
              <td>${plusCell}</td>
            </tr><tr style="border-bottom:1px solid darkgray;"><td colspan="100%"></td></tr>`;
			});

			html += '</table>';
			$content.find('#road-list').html(html);

			const lastEndDate = $content.find('input[data-FieldName="endDate"]').last();
			if (lastEndDate.val() === "") lastEndDate.val("pp.kk.vvvv");
			lastEndDate.prop("readonly", true);
			lastEndDate.prop("disabled", true);

			addSaveEvent();
			toggleSaveButton();
		} else {
			$content.find('#road-list').html('');
		}
	};

	const searchForRoadNames = async () => {
		const roadParam = $('#roadSearchParameter').val();
		$('.roadList-item').remove();
		$('#saveChangedRoads').remove();

		const roadData = await roadNameCollection.fetchRoads(roadParam);
		if (!modal) {
			return;
		}

		const $content = modal.getContent();
		renderRoadData(roadData, $content);
	};

	const addSaveEvent = () => {
		if ($('#saveChangedRoads').length === 0) {
			$('#road-list').append(button({
				id: 'saveChangedRoads',
				label: 'Tallenna',
				className: 'btn-primary save btn-save-road-data',
				disabled: true,
				onClick: () => {
					const saveMessage = ($('#newRoadName').length > 0)
						? "Tielle on jo nimi. Haluatko varmasti antaa sille uuden nimen?"
						: "Tielle on jo nimi. Haluatko varmasti muokata sitä?";
					new ConfirmPopup(saveMessage, {
						successCallback: () => roadNameCollection.saveChanges({
							onSaveSuccess: () => {
								searchForRoadNames();
							},
							onSaveUnsuccessful: (errorMessage) => {
								const message = errorMessage || 'Tallennus epäonnistui';
								showToast(message, { type: 'error' });
							}
						}),
						closeCallback: () => {}
					});
				}
			}));
		}
	};

	const retroactivelyAddDatePickers = (originalStartDate) => {
		const inputs = $('.date-picker-input[data-FieldName="startDate"]:not([placeholder])');
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
				const parsedFieldDate = moment(fieldValue, acceptedDateFormats, true);
				const endDateForPreviousRoadName = parsedFieldDate.isValid()
					? parsedFieldDate.subtract(1, 'days').format(defaultDateFormat)
					: '';
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

		const fieldDate = moment(dateString.trim(), acceptedDateFormats, true);
		if (!fieldDate.isValid()) return false;

		const dates = getDateObjects(fieldDate, originalStartDate);
		if (!dates) return false;

		return dates.futureDateSinceCurrent.isAfter(fieldDate) && dates.pastDate.isSameOrBefore(fieldDate);
	};

	const getDateObjects = (fieldDate, originalStartDate) => {
		const originalDate = moment(originalStartDate.trim(), acceptedDateFormats, true);
		if (!originalDate.isValid()) {
			return null;
		}

		const lowerStart = originalDate.add(1, 'days');
		const currentUpperLimit = moment().add(5, 'years');
		return { fieldDate, futureDateSinceCurrent: currentUpperLimit, pastDate: lowerStart };
	};

	function handleNewRoadName(originalRoadId, roadNumber, originalStartDate) {
		$(`#new-road-name-${originalRoadId}`).css("visibility", "hidden");
		const prevRoadNameInput = $('#road-list tr.roadList-item input[data-FieldName="roadName"]').last();
		prevRoadNameInput.addClass("input-road-details-readonly").removeClass("form-control").prop("readonly", true);

		$('#roadList-table').append(`
        <tr class="roadList-item" id="newRoadName" data-originalRoadId="${originalRoadId}" data-roadNumber="${roadNumber}" data-originalStartDate="${originalStartDate}">
          <td style="width: 150px;">${staticFieldRoadNumber(roadNumber, newId)}</td>
          <td style="width: 250px;">${staticFieldRoadList("", true, newId, "roadName", 50)}</td>
          <td style="width: 110px;">${staticFieldRoadList("", true, newId, "startDate")}</td>
          <td style="width: 110px;">${staticFieldRoadList("", true, newId, "endDate")}</td>
          <td>${button({ id: `undo-new-road-name-${originalRoadId}`, label: ' \u2014 ', className: 'btn-primary', onClick: () => handleUndoNewRoadName(originalRoadId) })}</td>
        </tr><tr style="border-bottom:1px solid darkgray;"><td colspan="100%"></td></tr>
      `);

		$(`.form-control[data-roadId=${newId}][data-FieldName=endDate]`).val("pp.kk.vvvv").prop("readonly", true).prop("disabled", true);
		retroactivelyAddDatePickers(originalStartDate);
		toggleSaveButton();
	}

	function handleUndoNewRoadName(originalRoadId) {
		roadNameCollection.undoNewRoadName();
		$(`#new-road-name-${originalRoadId}`).css("visibility", "visible");
		$('#newRoadName').next('tr').remove();
		$('#newRoadName').remove();
		const prevName = $('#road-list tr.roadList-item input[data-FieldName="roadName"]').last();
		prevName.addClass("form-control").removeClass("input-road-details-readonly").prop("readonly", false);
		toggleSaveButton();
	}

	function bindEvents() {
		const $content = modal.getContent();

		// Use the modal's content container for event delegation
		$content.on('input change', '.form-control, .date-picker-input', (e) => editEvent(e));
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
			title: 'Tiennimen ylläpito',
			content: createNamingToolContent()
		});
		bindEvents();
	}

	return { show: showRoadNamingToolWindow };
}