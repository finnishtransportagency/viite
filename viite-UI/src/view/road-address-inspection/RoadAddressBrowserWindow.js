import { dateutil } from '@utils/DateUtils.js';
import * as ViiteConstants from '@utils/ViiteConstants.js';
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { ModalContainer } from '@components/modals/ModalContainer.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { RoadAddressBrowserForm } from './RoadAddressBrowserForm.js';
import { ViiteEnumerations, getAdministrativeClassTextValue } from '@utils/ViiteEnumerations.js';

// Renders '—' for missing values instead of 'undefined'/'null' in tables and CSV exports.
function dash(value) {
	return value === undefined || value === null ? '—' : value;
}

function getBeforeAfterDisplayText(beforeAfterValues) {
	let letterString = "";
	beforeAfterValues.forEach((value) => {
		const beforeAfter = _.find(ViiteEnumerations.BeforeAfter, function (obj) {
			return obj.value === value;
		});
		letterString += beforeAfter.displayLetter;
	});
	return letterString.split('').sort().join(''); // sort letter string so that 'JE' becomes 'EJ'
}

const TARGET_CONFIG = {
	Tracks: {
		requiresElyValidation: true,
		columns: [
			{ header: 'Elinvoimakeskus', get: r => dash(r.evk) },
			{ header: 'Ely', get: r => dash(r.ely) },
			{ header: 'Tie', get: r => r.roadNumber },
			{ header: 'Ajr', get: r => r.track },
			{ header: 'Osa', get: r => r.roadPartNumber },
			{ header: 'Aet', get: r => r.addrMRange.start },
			{ header: 'Let', get: r => r.addrMRange.end },
			{ header: 'Pituus', get: r => r.lengthAddrM },
			{ header: 'Hall. luokka', get: r => getAdministrativeClassTextValue(r.administrativeClass) },
			{ header: 'Alkupvm', get: r => r.startDate }
		]
	},
	RoadParts: {
		requiresElyValidation: true,
		columns: [
			{ header: 'Elinvoimakeskus', get: r => dash(r.evk) },
			{ header: 'Ely', get: r => dash(r.ely) },
			{ header: 'Tie', get: r => r.roadNumber },
			{ header: 'Osa', get: r => r.roadPartNumber },
			{ header: 'Aet', get: r => r.addrMRange.start },
			{ header: 'Let', get: r => r.addrMRange.end },
			{ header: 'Pituus', get: r => r.lengthAddrM },
			{ header: 'Alkupvm', get: r => r.startDate }
		]
	},
	Nodes: {
		lockToCurrentNetwork: true,
		columns: [
			{ header: 'Elinvoimakeskus', get: r => dash(r.evk) },
			{ header: 'Ely', get: r => dash(r.ely) },
			{ header: 'Tie', get: r => r.roadNumber },
			{ header: 'Osa', get: r => r.roadPartNumber },
			{ header: 'Et', get: r => r.addrM },
			{ header: 'Alkupvm', get: r => r.startDate },
			{ header: 'Tyyppi', get: r => r.nodeType },
			{ header: 'Nimi', get: r => r.nodeName },
			{ header: 'P-Koord', get: r => r.nodeCoordinates.y },
			{ header: 'I-Koord', get: r => r.nodeCoordinates.x },
			{ header: 'Solmunumero', get: r => r.nodeNumber }
		]
	},
	Junctions: {
		lockToCurrentNetwork: true,
		columns: [
			{ header: 'Solmu-numero', get: r => r.nodeNumber },
			{ header: 'P-Koord', get: r => r.nodeCoordinates.y },
			{ header: 'I-Koord', get: r => r.nodeCoordinates.x },
			{ header: 'Nimi', get: r => r.nodeName },
			{ header: 'Solmu-tyyppi', get: r => r.nodeType },
			{ header: 'Alkupvm', get: r => r.startDate },
			{ header: 'Liittymä-nro', get: r => r.junctionNumber },
			{ header: 'Tie', get: r => r.roadNumber },
			{ header: 'Ajr', get: r => r.track },
			{ header: 'Osa', get: r => r.roadPartNumber },
			{ header: 'Et', get: r => r.addrM },
			{ header: 'EJ', get: r => getBeforeAfterDisplayText(r.beforeAfter) }
		]
	},
	RoadNames: {
		columns: [
			{ header: 'Elinvoimakeskus', get: r => dash(r.evk) },
			{ header: 'Ely', get: r => dash(r.ely) },
			{ header: 'Tie', get: r => r.roadNumber },
			{ header: 'Nimi', get: r => r.roadName }
		]
	}
};

// Rows as plain arrays (header row + one row per result) for CSV export.
function resultsToArray(target, results) {
	const { columns } = TARGET_CONFIG[target];
	return [columns.map(c => c.header), ...results.map(row => columns.map(c => c.get(row)))];
}

// Same data as an HTML table, using the same column definitions as resultsToArray
// so the export and the on-screen table can never drift apart.
function resultsToTable(target, results) {
	const { columns } = TARGET_CONFIG[target];
	const headHtml = columns.map(c => `<th>${c.header}</th>`).join('');
	const bodyHtml = results.map((row) => {
		const cells = columns.map(c => `<td>${c.get(row)}</td>`).join('');
		return `<tr>${cells}</tr>`;
	}).join('');

	return $(`<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table viite-table">
                  <thead><tr>${headHtml}</tr></thead>
                  <tbody>${bodyHtml}</tbody>
              </table>`);
}

/**
 * RoadAddressBrowserWindow component
 * Displays a modal for searching, viewing, and exporting road address data.
 * @param {Object} backend - Backend API wrapper
 */
export function RoadAddressBrowserWindow(backend) {
	let searchParams = {};
	let searchResults = [];
	let modal = null;
	const roadAddressBrowserForm = new RoadAddressBrowserForm();

	const createModal = () => new ModalContainer({
		helpUrl: 'manual/index.html#!index.md#10_Tieosoitteiden_katselu_-ty%C3%B6kalu',
		helpTitle: 'Avaa käyttöohje',
		onClose: () => {
			$(document).off('keydown.roadAddressBrowser');
			modal = null;
		}
	});

	function exportDataAsCsvFile() {
		function arrayToCSV(data) {
			return data.map((row) => row.join(";")).join("\n");
		}

		const params = searchParams;

		// Create file name
		const parts = [
			"Viite",
			params.target,
			params.situationDate,
			params.ely || params.roadMaintainer,
			params.roadNumber,
			params.minRoadPartNumber,
			params.maxRoadPartNumber
		];
		const fileNameString = parts.map(val => val || '-').join('_') + ".csv";
		const fileName = fileNameString.replaceAll("undefined", "-");

		const data = resultsToArray(params.target, searchResults);
		let csvContent = "\uFEFF"; // UTF-8 BOM
		csvContent += arrayToCSV(data);

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

	// Keeps L-osa (end part) from being set below A-osa (start part); shared by both
	// the live oninput handlers and the pre-submit validation in getData().
	function syncPartRangeValidation() {
		const startPart = modal.getContent().find('#roadAddrInputStartPart')[0];
		const endPart = modal.getContent().find('#roadAddrInputEndPart')[0];
		if (!startPart || !endPart) return true;

		const startValue = Number(startPart.value);
		const endValue = Number(endPart.value);
		const isInvalidRange = !isNaN(startValue) && !isNaN(endValue) && startValue > endValue;

		endPart.setCustomValidity(isInvalidRange ? "L-osa ei voi olla pienempi kuin A-osa" : "");
		return !isInvalidRange;
	}

	function getData() {
		const roadAddrSituationDate = modal.getContent().find('#roadAddrSituationDate')[0];
		const elyEvkSelector = getElyEvkSelectorValue();
		const roadNumber = modal.getContent().find('#roadAddrInputRoad')[0];
		const minRoadPartNumber = modal.getContent().find('#roadAddrInputStartPart')[0];
		const maxRoadPartNumber = modal.getContent().find('#roadAddrInputEndPart')[0];
		const targetValue = getTargetSelectorValue();

		// Validate elements exist
		if (!roadAddrSituationDate || !roadNumber || !minRoadPartNumber || !maxRoadPartNumber) {
			console.error('Required form elements not found');
			return;
		}

		// convert date input text to date object
		const roadAddrSituationDateObject = moment(roadAddrSituationDate.value, "DD-MM-YYYY").toDate();

		function reportValidations() {
			return roadAddrSituationDate.reportValidity() &&
                  roadNumber.reportValidity() &&
                  minRoadPartNumber.reportValidity() &&
                  maxRoadPartNumber.reportValidity();
		}

		function validateDate(dateString) {
			if (dateutil.isFinnishDateString(dateString)) {
				if (dateutil.isDateInYearRange(roadAddrSituationDateObject, ViiteConstants.MIN_YEAR_INPUT, ViiteConstants.MAX_YEAR_INPUT)) {
					roadAddrSituationDate.setCustomValidity("");
				} else {
					roadAddrSituationDate.setCustomValidity(`Vuosiluvun tulee olla väliltä ${ViiteConstants.MIN_YEAR_INPUT} - ${ViiteConstants.MAX_YEAR_INPUT}`);
				}
			} else {
				roadAddrSituationDate.setCustomValidity("Päivämäärän tulee olla muodossa pp-kk-vvvv");
			}
		}

		function validateElyEvkAndRoadNumber(elyValue, roadNumberElement) {
			// If neither ELY/EVK or road number is provided, show error
			if ((!elyValue || elyValue.length === 0) && (!roadNumberElement || !roadNumberElement.value)) {
				if (roadNumberElement) {
					roadNumberElement.setCustomValidity("Elinvoimakeskus, Ely tai Tie on pakollinen tieto");
				}
				return false;
			}
			return true;
		}

		function willPassValidations() {
			validateDate(roadAddrSituationDate.value);
			const elyEvkValid = validateElyEvkAndRoadNumber(elyEvkSelector, roadNumber);
			const partsValid = syncPartRangeValidation();
			const formValid = reportValidations();
			return elyEvkValid && partsValid && formValid;
		}

		function createParams() {
			const parsedDateString = dateutil.parseDateToString(roadAddrSituationDateObject);
			const params = {
				situationDate: parsedDateString,
				target: targetValue
			};

			// Handle ELY/EVK selection (multiple selections are sent as comma-separated lists)
			if (elyEvkSelector && elyEvkSelector.length) {
				const elyValues = elyEvkSelector.filter(v => v.startsWith('ELY_')).map(v => v.substring(4));
				const evkValues = elyEvkSelector.filter(v => v.startsWith('EVK_')).map(v => v.substring(4));
				const otherValues = elyEvkSelector.filter(v => !v.startsWith('ELY_') && !v.startsWith('EVK_'));
				if (evkValues.length) params.roadMaintainer = evkValues.join(','); // Backend expects EVK as roadMaintainer
				if (elyValues.length || otherValues.length) params.ely = elyValues.concat(otherValues).join(',');
			}

			if (roadNumber.value)
				params.roadNumber = roadNumber.value;
			if (minRoadPartNumber.value)
				params.minRoadPartNumber = minRoadPartNumber.value;
			if (maxRoadPartNumber.value)
				params.maxRoadPartNumber = maxRoadPartNumber.value;
			return params;
		}

		// Reset custom validities (form error notifications)
		roadNumber.setCustomValidity("");
		roadAddrSituationDate.setCustomValidity("");

		// Tracks/RoadParts require an ELY/EVK-or-road-number check; the other targets
		// only need the situation date validated (see TARGET_CONFIG).
		if (TARGET_CONFIG[targetValue].requiresElyValidation) {
			validateElyEvkAndRoadNumber(elyEvkSelector, roadNumber);
			if (willPassValidations())
				fetchByTargetValue(createParams());
		} else {
			validateDate(roadAddrSituationDate.value);
			if (reportValidations())
				fetchByTargetValue(createParams());
		}
	}

	function showData(table) {
		modal.getContent().append(table);
		$('#exportAsCsvFile').prop("disabled", false); // enable CSV download button
	}

	function showTableTooBigNotification() {
		modal.getContent().append($('<p id="tableNotification"><b>Tulostaulu liian suuri, lataa tulokset CSV-tiedostona</b></p>'));
		$('#exportAsCsvFile').prop("disabled", false); // enable CSV download button
	}

	function showNoResultsFoundNotification() {
		modal.getContent().append($('<p id="tableNotification"><b>Hakuehdoilla ei löytynyt yhtäkään osumaa</b></p>'));
	}

	function fetchByTargetValue(params) {
		Spinner.show();
		backend.getDataForRoadAddressBrowser(params, function(result) {
			if (result.success) {
				Spinner.hide();
				searchParams = params;
				searchResults = result.results;
				if (result.results.length > 0) {
					if (result.results.length <= ViiteConstants.MAX_ROWS_TO_DISPLAY) {
						showData(resultsToTable(params.target, result.results));
					} else {
						showTableTooBigNotification();
					}
				} else {
					showNoResultsFoundNotification();
				}
			} else {
				Spinner.hide();
				new ConfirmPopup(result.error, { type: "alert" });
			}
		});
	}

	function clearResultsAndDisableCsvButton() {
		searchResults = []; // empty the search results
		$('.road-address-browser-window-results-table').remove(); // empty the result table
		$('#exportAsCsvFile').prop("disabled", true); //disable CSV download button
		$('#tableNotification').remove(); // remove notification if present
	}

	function getElyEvkSelectorValue() {
		const selectorComponents = roadAddressBrowserForm.getSelectorComponents();
		if (selectorComponents && selectorComponents.elyEvk) {
			return selectorComponents.elyEvk.getValue();
		}
		return null;
	}

	function getTargetSelectorValue() {
		const selectorComponents = roadAddressBrowserForm.getSelectorComponents();
		if (selectorComponents && selectorComponents.target) {
			const values = selectorComponents.target.getValue();
			if (values && values.length) return values[0];
		}
		return 'Tracks'; // Default value
	}

	/**
	 * Situation date input field is disabled when Nodes or Junctions are selected as the target value.
	 * Nodes and Junctions can only be browsed on the current road network (complete history info not available).
	 */
	function onTargetSelectionChange(values) {
		const value = values && values.length ? values[0] : 'Tracks';
		const situationDate = modal.getContent().find('#roadAddrSituationDate')[0];
		if (!situationDate) return;

		if (TARGET_CONFIG[value] && TARGET_CONFIG[value].lockToCurrentNetwork) {
			situationDate.value = dateutil.getCurrentDateString();
			situationDate.disabled = true;
			situationDate.title = "Solmuja ja liittymiä voi tarkastella vain nykyisellä tieverkolla";
		} else {
			situationDate.disabled = false;
			situationDate.title = "";
		}
	}

	function bindEvents() {
		const eventNs = '.roadAddressBrowser';
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
		const formEl = modal.getContent().find('#roadAddressBrowser')[0];
		if (formEl) {
			formEl.onchange = function () {
				clearResultsAndDisableCsvButton();
			};
		}

		// Input field validation handlers
		const roadInput = modal.getContent().find('#roadAddrInputRoad')[0];
		if (roadInput) {
			roadInput.oninput = function (event) {
				const input = event.currentTarget;
				if (input.value.length > ViiteConstants.MAX_LENGTH_FOR_ROAD_NUMBER) {
					input.value = input.value.slice(0, ViiteConstants.MAX_LENGTH_FOR_ROAD_NUMBER);
				}
			};
		}

		// Start/end part inputs share the same length cap and both need to re-check
		// the A-osa/L-osa range whenever either one changes.
		const startPartInput = modal.getContent().find('#roadAddrInputStartPart')[0];
		if (startPartInput) {
			startPartInput.oninput = function (event) {
				const input = event.currentTarget;
				if (input.value.length > ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER) {
					input.value = input.value.slice(0, ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER);
				}
				syncPartRangeValidation();
			};
		}

		const endPartInput = modal.getContent().find('#roadAddrInputEndPart')[0];
		if (endPartInput) {
			endPartInput.oninput = function (event) {
				const input = event.currentTarget;
				if (input.value.length > ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER) {
					input.value = input.value.slice(0, ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER);
				}
				syncPartRangeValidation();
			};
		}

		const situationDateInput = modal.getContent().find('#roadAddrSituationDate')[0];
		if (situationDateInput) {
			situationDateInput.oninput = function (event) {
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
			title: 'Tieosoitteiden katselu',
			content: roadAddressBrowserForm.getRoadAddressBrowserForm(
				() => { clearResultsAndDisableCsvButton(); getData(); },
				exportDataAsCsvFile
			)
		});

		const targetSelector = roadAddressBrowserForm.getSelectorComponents().target;
		if (targetSelector) targetSelector.setOnChange(onTargetSelectionChange);
		bindEvents();
	}

	return { show };
}