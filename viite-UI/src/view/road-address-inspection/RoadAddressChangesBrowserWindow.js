import { MultiColumnDropdown } from '@components/dropdowns/MultiColumnDropdown.js';
import { ModalContainer } from '@components/modals/ModalContainer.js';
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { Spinner } from '@components/spinner/Spinner.js';
import * as ViiteConstants from '@utils/ViiteConstants.js';
import { ViiteEnumerations, getAdministrativeClassTextValue } from '@utils/ViiteEnumerations.js';
import { dateutil } from '@utils/DateUtils.js';
import { RoadAddressBrowserForm, createElyEvkSelectorData } from './RoadAddressBrowserForm.js';

// Renders '—' for missing values instead of 'undefined'/'null' in the table and CSV export.
function dash(value) {
	return value === undefined || value === null ? '—' : value;
}

function getChangeTypeDisplayText(changeTypeValue) {
	const changeType = _.find(ViiteEnumerations.ChangeType, function (obj) {
		return obj.value === changeTypeValue;
	});
	return changeType.displayText;
}

/**
 * Column definitions for the change-history table/CSV, in display order. Each entry
 * knows its own header and how to read its value off a result row, so the header
 * row, the HTML table body, and the CSV export are all generated from this single
 * list instead of three separate hand-written templates.
 */
const COLUMNS = [
	{ header: 'Voimaantulopvm', get: r => r.startDate },
	{ header: 'Elinvoimakeskus', get: r => dash(r.oldEvk) },
	{ header: 'Ely', get: r => dash(r.oldEly) },
	{ header: 'Tie', get: r => r.oldRoadNumber },
	{ header: 'Ajr', get: r => r.oldTrack },
	{ header: 'Aosa', get: r => r.oldRoadPartNumber },
	{ header: 'Aet', get: r => r.oldStartAddrM },
	{ header: 'Losa', get: r => r.oldRoadPartNumber },
	{ header: 'Let', get: r => r.oldEndAddrM },
	{ header: 'Pituus', get: r => r.oldLength },
	{ header: 'Hall. luokka', get: r => getAdministrativeClassTextValue(r.oldAdministrativeClass) },
	{ header: 'Muutos', get: r => getChangeTypeDisplayText(r.changeType) },
	{ header: 'u_Elinvoimakeskus', get: r => dash(r.newEvk) },
	{ header: 'u_Tie', get: r => r.newRoadNumber },
	{ header: 'u_Ajr', get: r => r.newTrack },
	{ header: 'u_Aosa', get: r => r.newRoadPartNumber },
	{ header: 'u_Aet', get: r => r.newAddrMRange.start },
	{ header: 'u_Losa', get: r => r.newRoadPartNumber },
	{ header: 'u_Let', get: r => r.newAddrMRange.end },
	{ header: 'u_Pituus', get: r => r.newLength },
	{ header: 'u_Hall. luokka', get: r => getAdministrativeClassTextValue(r.newAdministrativeClass) },
	{ header: 'Käännetty', get: r => r.reversed },
	{ header: 'Tien nimi', get: r => r.roadName },
	{ header: 'Projektin Nimi', get: r => r.projectName },
	{ header: 'Projektin hyväksymispvm', get: r => r.projectAcceptedDate }
];

// HTML table for on-screen display.
function resultsToTable(results) {
	const headHtml = COLUMNS.map(c => `<th>${c.header}</th>`).join('');
	const bodyHtml = results.map((row) => {
		const cells = COLUMNS.map(c => `<td>${c.get(row)}</td>`).join('');
		return `<tr>${cells}</tr>`;
	}).join('');

	return $(`<table id="roadAddressChangesBrowserTable" class="road-address-browser-window-results-table viite-table">
                  <thead><tr>${headHtml}</tr></thead>
                  <tbody>${bodyHtml}</tbody>
              </table>`);
}

// Rows as plain arrays (header row + one row per result) for CSV export, built
// straight from the same data/columns as the table above rather than re-reading
// the rendered DOM table's cell text.
function resultsToArray(results) {
	return [COLUMNS.map(c => c.header), ...results.map(row => COLUMNS.map(c => c.get(row)))];
}

/**
 * RoadAddressChangesBrowserWindow component
 * Allows users to search road address change history data and export it as CSV.
 * @param {Object} backend - Backend API wrapper
 */
export function RoadAddressChangesBrowserWindow(backend) {
	let searchParams = {};
	let searchResults = [];
	let elyEvkSelector;
	let modal = null;

	const roadAddressBrowserForm = new RoadAddressBrowserForm();

	const createModal = () => new ModalContainer({
		helpUrl: 'manual/index.html#!index.md#11_Tieosoitemuutosten_katselu_-ty%C3%B6kalu',
		helpTitle: 'Avaa käyttöohje',
		onClose: () => {
			$(document).off('keydown.roadAddressChangesBrowser');
			modal = null;
			elyEvkSelector = null;
		}
	});

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
			dateElement.setCustomValidity("Päivämäärän tulee olla muodossa pp-kk-vvvv");
			return false;
		}
	}

	function validateBeginningAndEndParts () {
		const aOsa = modal.getContent().find('#roadAddrChangesInputStartPart')[0];
		const lOsa = modal.getContent().find('#roadAddrChangesInputEndPart')[0];

		if (!aOsa || !lOsa) {
			return false;
		}

		const aOsaValue = Number(aOsa.value);
		const lOsaValue = Number(lOsa.value);

		const aOsaIsNumber = !isNaN(aOsaValue);
		const lOsaIsNumber = !isNaN(lOsaValue);

		// If both are numbers and A is greater than L, show error
		if (aOsaIsNumber && lOsaIsNumber && aOsaValue > lOsaValue) {
			lOsa.setCustomValidity("L-osa ei voi olla pienempi kuin A-osa");
			return false;
		}

		// Clear error if valid
		lOsa.setCustomValidity("");
		return true;
	}

	// Instantiate selector and inject it into the Changes form
	function insertElyEvkSelector() {
		// Render selector with id expected by getData()
		elyEvkSelector = new MultiColumnDropdown({
			id: 'roadAddrChangesInputEly',
			placeholder: 'Valitse Elinvoimakeskus / ELY',
			width: 240,
			multiple: true,
			data: createElyEvkSelectorData()
		});

		// Find the changes form and the end date container to insert after (search within the window container)
		const $form = modal.getContent().find('#roadAddressChangesBrowser');
		const $endDateContainer = $form.find('#roadAddrChangesEndDate').closest('.input-container');

		const $elyContainer = $(`
          <div class="input-container">
              <label>Elinvoimakeskus / ELY</label>
              ${elyEvkSelector.render()}
          </div>`);

		if ($endDateContainer.length > 0) {
			$endDateContainer.after($elyContainer);
		} else {
			// Fallback: append to end of form
			$form.append($elyContainer);
		}
	}

	function showData(results, table) {
		if (results.length === 0) {
			modal.getContent().append($('<p id="tableNotification"><b>Hakuehdoilla ei löytynyt yhtäkään osumaa</b></p>'));
			modal.getContent().append(table.hide());
		}
		else if (results.length <= ViiteConstants.MAX_ROWS_TO_DISPLAY) {
			modal.getContent().append(table);
			$('#exportAsCsvFile').prop("disabled", false); // enable CSV download button
		}
		else {
			// hide the results and notify user to download result table as CSV file
			modal.getContent().append($('<p id="tableNotification"><b>Tulostaulu liian suuri, lataa tulokset CSV -taulukkona</b></p>'));
			modal.getContent().append(table.hide());
			$('#exportAsCsvFile').prop("disabled", false); // enable CSV download button
		}
	}

	function exportDataAsCsvFile() {
		const params = searchParams;

		// Create file name
		const parts = [
			"Viite",
			params.dateTarget,
			params.startDate,
			params.endDate,
			params.ely || params.roadMaintainer,
			params.roadNumber,
			params.minRoadPartNumber,
			params.maxRoadPartNumber
		];
		const fileNameString = parts.map(val => val || '-').join('_') + ".csv";
		const fileName = fileNameString.replaceAll("undefined", "-");

		const data = resultsToArray(searchResults);
		let csvContent = "\uFEFF"; // UTF-8 BOM
		csvContent += data.map((row) => row.join(";")).join("\n");

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
		const roadAddrChangesStartDate      = modal.getContent().find('#roadAddrChangesStartDate')[0];
		const roadAddrChangesEndDate        = modal.getContent().find('#roadAddrChangesEndDate')[0];
		// ELY is a Selector component, not an input element
		const roadNumber                    = modal.getContent().find('#roadAddrChangesInputRoad')[0];
		const minRoadPartNumber             = modal.getContent().find('#roadAddrChangesInputStartPart')[0];
		const maxRoadPartNumber             = modal.getContent().find('#roadAddrChangesInputEndPart')[0];
		// Get dateTarget from the form's selector component
		const dateTargetSelector = roadAddressBrowserForm.getSelectorComponents().dateTarget;

		// Validate elements exist
		if (!roadAddrChangesStartDate || !roadAddrChangesEndDate || !roadNumber || !minRoadPartNumber || !maxRoadPartNumber) {
			console.error('Required form elements not found');
			return;
		}

		// convert date input text to date object
		const roadAddrStartDateObject  = moment(roadAddrChangesStartDate.value, "DD-MM-YYYY").toDate();
		const roadAddrEndDateObject  = moment(roadAddrChangesEndDate.value, "DD-MM-YYYY").toDate();

		function reportValidations() {
			return roadAddrChangesStartDate.reportValidity() &&
              roadAddrChangesEndDate.reportValidity() &&
              roadNumber.reportValidity() &&
              minRoadPartNumber.reportValidity() &&
              maxRoadPartNumber.reportValidity() &&
              validateBeginningAndEndParts();
		}

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

		function createParams() {
			const parsedDateString = dateutil.parseDateToString(roadAddrStartDateObject);
			const params = {
				startDate: parsedDateString,
				dateTarget: dateTargetSelector && typeof dateTargetSelector.getValue === 'function' && dateTargetSelector.getValue().length ? dateTargetSelector.getValue()[0] : 'ProjectAcceptedDate'
			};

			// Add end date to params
			if (roadAddrChangesEndDate.value) params.endDate = dateutil.parseDateToString(roadAddrEndDateObject);
			const selected = elyEvkSelector && typeof elyEvkSelector.getValue === 'function'
				? elyEvkSelector.getValue()
				: [];
			const selectedValues = Array.isArray(selected) ? selected : [selected].filter(Boolean);

			// Add ELY/EVK to params (multiple selections are sent as comma-separated lists)
			const elyValues = selectedValues.filter(v => v.startsWith('ELY_')).map(v => v.split('_')[1]).filter(Boolean);
			const evkValues = selectedValues.filter(v => v.startsWith('EVK_')).map(v => v.split('_')[1]).filter(Boolean);
			if (elyValues.length) params.ely = elyValues.join(',');
			if (evkValues.length) params.roadMaintainer = evkValues.join(','); // Backend handles evk value as roadMaintainer, so convert evk to that

			if (roadNumber.value)
				params.roadNumber = roadNumber.value;
			if (minRoadPartNumber.value)
				params.minRoadPartNumber = minRoadPartNumber.value;
			if (maxRoadPartNumber.value)
				params.maxRoadPartNumber = maxRoadPartNumber.value;
			return params;
		}

		//reset roadAddrStartDate input fields' custom validity
		roadAddrChangesStartDate.setCustomValidity("");
		roadAddrChangesEndDate.setCustomValidity("");

		if (!willPassValidations()) {
			return;
		}

		// Sets the end date 1 day ahead, so that the inputted end date is included in project listing.
		dateutil.addOneDayToDate(roadAddrEndDateObject);
		fetchRoadAddressChanges(createParams());
	}

	function fetchRoadAddressChanges(params) {
		Spinner.show();
		backend.getDataForRoadAddressChangesBrowser(params, function(result) {
			if (result.success) {
				Spinner.hide();
				searchParams = params;
				searchResults = result.changeInfos;
				showData(result.changeInfos, resultsToTable(result.changeInfos));
			} else {
				Spinner.hide();
				new ConfirmPopup(result.error, { type: "alert" });
			}
		});
	}

	function clearResultsAndDisableCsvButton() {
		searchResults = [];
		$('.road-address-browser-window-results-table').remove(); // empty the result table
		$('#exportAsCsvFile').prop("disabled", true); //disable CSV download button
		$('#tableNotification').remove(); // remove notification if present
	}

	// Truncates to the max length and re-checks the A-osa/L-osa range on every
	// keystroke; shared by the start and end road-part inputs.
	function bindPartNumberInput(element) {
		if (!element) return;
		element.oninput = function (event) {
			const input = event.currentTarget;
			if (input.value.length > ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER) {
				input.value = input.value.slice(0, ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER);
			}
			validateBeginningAndEndParts();
			input.setCustomValidity("");
		};
	}

	function bindEvents() {
		const eventNs = '.roadAddressChangesBrowser';
		const $content = modal.getContent();

		// Bind the enter key to the search button
		$(document).off('keydown' + eventNs).on('keydown' + eventNs, function(e) {

			// ModalContainer does not expose isVisible(); skip when modal is detached from DOM.
			if (!modal || !modal.getContent().closest('body').length) {
				return;
			}

			if (e.key === 'Enter') {
				e.preventDefault();
				clearResultsAndDisableCsvButton();
				getData();
			}
		});

		// if any of the input fields change (the input fields are child elements of the form wrapper)
		const formEl = modal.getContent().find('#roadAddressChangesBrowser')[0];
		if (formEl) {
			formEl.onchange = function () {
				clearResultsAndDisableCsvButton();
			};
		}

		// Input field validation handlers
		const roadInput = modal.getContent().find('#roadAddrChangesInputRoad')[0];
		if (roadInput) {
			roadInput.oninput = function (event) {
				const input = event.currentTarget;
				if (input.value.length > ViiteConstants.MAX_LENGTH_FOR_ROAD_NUMBER) {
					input.value = input.value.slice(0, ViiteConstants.MAX_LENGTH_FOR_ROAD_NUMBER);
				}
			};
		}


		bindPartNumberInput(modal.getContent().find('#roadAddrChangesInputStartPart')[0]);
		bindPartNumberInput(modal.getContent().find('#roadAddrChangesInputEndPart')[0]);

		const startDateEl = modal.getContent().find('#roadAddrChangesStartDate')[0];
		const endDateEl = modal.getContent().find('#roadAddrChangesEndDate')[0];
		if (startDateEl) {
			startDateEl.oninput = function (event) {
				event.currentTarget.setCustomValidity("");
			};
		}
		if (endDateEl) {
			endDateEl.oninput = function (event) {
				event.currentTarget.setCustomValidity("");
			};
		}

		$content.off('click' + eventNs, 'button.close').on('click' + eventNs, 'button.close', function () {
			modal.close();
		});
	}

	function show() {
		modal = createModal();
		modal.open({
			title: 'Tieosoitemuutosten katselu',
			content: roadAddressBrowserForm.getRoadAddressChangesBrowserForm(
				() => { clearResultsAndDisableCsvButton(); getData(); },
				exportDataAsCsvFile
			)
		});

		if (modal.getContent().find('#roadAddrChangesInputEly').length === 0) {
			insertElyEvkSelector();
		}
		bindEvents();
	}

	return { show };
}