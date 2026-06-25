import { showToast } from '@components/toast/Toast.js';
import { searchLocation } from '@model/LocationSearch.js';
import { moveMapToCoordinates } from '@view/map/MapView.js';
import { button } from '@components/button/Button.js';

const TOOLTIP = `Hae katuosoitteella (esim. 'Aputie 10', 'Aputie, Vihti', tai 'Aputie 10, Vihti'),
tieosoitteella (esim. '2 1 1000 2', '2/1/1000/2', '2', '2/1' tai '2 1 1000'),
linkki-id:llä (esim. '06ad934c-5241-4055-9ae6-71d63190f6d7:1')
tai koordinaateilla ('P, I', esim. '6673830, 388774')`;

// Handles road address/location search input, result rendering, and coordinate selection events
export function SearchBox(map) {
	const groupDiv = $('<div id="searchBox" class="panel-group search-box"></div>');
	const coordinatesDiv = $('<div class="panel"></div>');

	const inputWrapper = $('<div class="input-wrapper"></div>');

	const coordinatesInput = $(`
    <input
      type="text"
      class="location-search-input"
      placeholder="Osoite / koordinaatit"
      title="${TOOLTIP}"
    />
  `);

	const clearButton = $(button({ id: 'clearSearch', label: '<i class="fas fa-times"></i>', className: 'close wbtn-close clear-btn', title: 'Tyhjennä haku', onClick: clearSearch })).attr('aria-label', 'Tyhjennä haku');

	const searchButton = $(button({ id: 'executeSearch', label: 'Hae', className: 'btn-primary', onClick: executeSearch }));

	const panelHeader = $('<div class="panel-header"></div>');
	const searchResults = $('<ul id="search-results"></ul>');
	const resultsSection = $('<div class="panel-section"></div>')
		.append(searchResults)
		.hide();

	inputWrapper.append(coordinatesInput, clearButton);
	panelHeader.append(inputWrapper, searchButton);
	coordinatesDiv.append(panelHeader, resultsSection);
	groupDiv.append(coordinatesDiv);

	function updateClearButtonVisibility() {
		clearButton.toggle(coordinatesInput.val().trim().length > 0);
	}

	function showError(message) {
		resultsSection.hide();
		clearButton.hide();

		showToast(
			_.isString(message)
				? message
				: 'Yhteys Viitekehysmuuntimeen epäonnistui',
			{ type: 'error' }
		);
	}

	function selectResult(result) {
		if (map) {
			moveMapToCoordinates({
				lon: result.lon,
				lat: result.lat
			});
		}
	}

	function populateSearchResults(results) {
		const sortedResults = [...results].sort((a, b) => {
			const municipalityA = a.title.split(', ')[1] || '';
			const municipalityB = b.title.split(', ')[1] || '';

			return (
				municipalityA.localeCompare(municipalityB) ||
        a.title.localeCompare(b.title) ||
        a.distance - b.distance
			);
		});

		const items = sortedResults.map(result =>
			$('<li></li>')
				.text(result.title)
				.on('click', () => selectResult(result))
		);

		searchResults.empty().append(items);

		resultsSection.show();
		updateClearButtonVisibility();
	}

	function showLoading() {
		searchResults.text('Haku käynnissä…');
		resultsSection.show();
	}

	function executeSearch() {
		const query = coordinatesInput.val().trim();

		if (!query) {
			resultsSection.hide();
			return;
		}

		showLoading();

		searchLocation(query)
			.then(results => {
				populateSearchResults(results);

				if (results.length === 1) {
					selectResult(results[0]);
				}
			})
			.fail(showError);
	}

	function clearSearch() {
		coordinatesInput.val('');
		coordinatesInput.focus();

		resultsSection.hide();
		clearButton.hide();
	}

	function bindEvents() {
		coordinatesInput.on('keydown', event => {
			if (event.key === 'Enter') {
				executeSearch();
			}
		});

		coordinatesInput.on('input', () => {
			updateClearButtonVisibility();

			if (!coordinatesInput.val()) {
				resultsSection.hide();
			}
		});

		clearButton.hide();
	}

	bindEvents();

	return {
		element: groupDiv
	};
}