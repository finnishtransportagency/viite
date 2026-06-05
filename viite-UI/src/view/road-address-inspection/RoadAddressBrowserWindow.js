import { dateutil } from '@utils/DateUtils.js';
import * as ViiteConstants from '@utils/ViiteConstants.js';
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { ModalContainer } from '@components/modals/ModalContainer.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { RoadAddressBrowserForm } from './RoadAddressBrowserForm.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { EnumerationUtils } from '@utils/EnumerationUtils.js';

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

      function createArrayOfArraysForTracks(results) {
          const array = [];
          let arrayPointer = -1;
          array[++arrayPointer] = ['Elinvoimakeskus', 'Ely','Tie', 'Ajr', 'Osa', 'Aet', 'Let', 'Pituus', 'Hall. luokka', 'Alkupvm'];
          for (let i = 0, len = results.length; i < len; i++) {
              array[++arrayPointer] = [
                  results[i].evk,
                  typeof results[i].ely === 'undefined' || results[i].ely === null ? '-' : results[i].ely,
                  results[i].roadNumber,
                  results[i].track,
                  results[i].roadPartNumber,
                  results[i].addrMRange.start,
                  results[i].addrMRange.end,
                  results[i].lengthAddrM,
                  EnumerationUtils.getAdministrativeClassTextValue(results[i].administrativeClass),
                  results[i].startDate
              ];
          }
          return array; // join the array to one large string and create jquery element from said string
      }

      /**
       *      This function is performance critical. Pointers in use for reasonable processing time.
       *      If edited be sure to measure table creation time with the largest possible dataset!
       */
      function createResultTableForTracks(results) {
          const arr = [];
          let arrPointer = -1;
          arr[++arrPointer] = `<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table viite-table">
                                  <thead>
                                      <tr>
                                          <th>Elinvoimakeskus</th>
                                          <th>Ely</th>
                                          <th>Tie</th>
                                          <th>Ajr</th>
                                          <th>Osa</th>
                                          <th>Aet</th>
                                          <th>Let</th>
                                          <th>Pituus</th>
                                          <th>Hall. luokka</th>
                                          <th>Alkupvm</th>
                                      </tr>
                                  </thead>
                                  <tbody>`;
          for (let i = 0, len = results.length; i < len; i++) {
              arr[++arrPointer] =`    <tr>
              <td>${results[i].evk}</td>
                                          <td>${typeof results[i].ely === 'undefined' || results[i].ely === null ? '-' : results[i].ely}</td>
                                          <td>${results[i].roadNumber}</td>
                                          <td>${results[i].track}</td>
                                          <td>${results[i].roadPartNumber}</td>
                                          <td>${results[i].addrMRange.start}</td>
                                          <td>${results[i].addrMRange.end}</td>
                                          <td>${results[i].lengthAddrM}</td>
                                          <td>${EnumerationUtils.getAdministrativeClassTextValue(results[i].administrativeClass)}</td>
                                          <td>${results[i].startDate}</td>
                                      </tr>`;
          }
          arr.push(`    </tbody>
                          </table>`);
          return $(arr.join('')); // join the array to one large string and create jquery element from said string
      }

      function createArrayOfArraysForRoadParts(results) {
          const array = [];
          let arrayPointer = -1;
          array[++arrayPointer] = ['Elinvoimakeskus', 'Ely','Tie', 'Osa', 'Aet', 'Let', 'Pituus', 'Alkupvm'];
          for (let i = 0, len = results.length; i < len; i++) {
              array[++arrayPointer] = [
                  results[i].evk,
                  typeof results[i].ely === 'undefined' || results[i].ely === null ? '-' : results[i].ely,
                  results[i].roadNumber,
                  results[i].roadPartNumber,
                  results[i].addrMRange.start,
                  results[i].addrMRange.end,
                  results[i].lengthAddrM,
                  results[i].startDate
              ];
          }
          return array; // join the array to one large string and create jquery element from said string
      }

      /**
       *      This function is performance critical. Pointers in use for reasonable processing time.
       *      If edited be sure to measure table creation time with the largest possible dataset!
       */
      function createResultTableForRoadParts(results) {
          const arr = [];
          let arrPointer = -1;
          arr[++arrPointer] = `<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table viite-table">
                                  <thead>
                                      <tr>
                                          <th>Elinvoimakeskus</th>
                                          <th>Ely</th>
                                          <th>Tie</th>
                                          <th>Osa</th>
                                          <th>Aet</th>
                                          <th>Let</th>
                                          <th>Pituus</th>
                                          <th>Alkupvm</th>
                                      </tr>
                                  </thead>
                                  <tbody>`;
          for (let i = 0, len = results.length; i < len; i++) {
              arr[++arrPointer] =`    <tr>
                                          <td>${results[i].evk}</td>
                                          <td>${typeof results[i].ely === 'undefined' || results[i].ely === null ? '-' : results[i].ely}</td>
                                          <td>${results[i].roadNumber}</td>
                                          <td>${results[i].roadPartNumber}</td>
                                          <td>${results[i].addrMRange.start}</td>
                                          <td>${results[i].addrMRange.end}</td>
                                          <td>${results[i].lengthAddrM}</td>
                                          <td>${results[i].startDate}</td>
                                      </tr>`;
          }
          arr.push(`    </tbody>
                          </table>`);
          return $(arr.join('')); // join the array to one large string and create jquery element from said string
      }

      function createArrayOfArraysForNodes(results) {
          const array = [];
          let arrayPointer = -1;
          array[++arrayPointer] = ['Elinvoimakeskus', 'Ely','Tie', 'Osa', 'Et', 'Alkupvm', 'Tyyppi', 'Nimi', 'P-Koord', 'I-Koord', 'Solmunumero'];
          for (let i = 0, len = results.length; i < len; i++) {
              array[++arrayPointer] = [
                  results[i].evk,
                  typeof results[i].ely === 'undefined' || results[i].ely === null ? '-' : results[i].ely,
                  results[i].roadNumber,
                  results[i].roadPartNumber,
                  results[i].addrM,
                  results[i].startDate,
                  results[i].nodeType,
                  results[i].nodeName,
                  results[i].nodeCoordinates.y,
                  results[i].nodeCoordinates.x,
                  results[i].nodeNumber
              ];
          }
          return array; // join the array to one large string and create jquery element from said string
      }

      /**
       *      This function is performance critical. Pointers in use for reasonable processing time.
       *      If edited be sure to measure table creation time with the largest possible dataset!
       */
      function createResultTableForNodes(results) {
          const arr = [];
          let arrPointer = -1;
          arr[++arrPointer] =`<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table viite-table">
                                  <thead>
                                      <tr>
                                          <th>Elinvoimakeskus</th>
                                          <th>Ely</th>
                                          <th>Tie</th>
                                          <th>Osa</th>
                                          <th>Et</th>
                                          <th>Alkupvm</th>
                                          <th>Tyyppi</th>
                                          <th>Nimi</th>
                                          <th>P-Koord</th>
                                          <th>I-Koord</th>
                                          <th>Solmunumero</th>
                                      </tr>
                                  </thead>
                                  <tbody>`;

          for (let i = 0, len = results.length; i < len; i++) {
              arr[++arrPointer] =`    <tr>
                                          <td>${results[i].evk}</td>
                                          <td>${typeof results[i].ely === 'undefined' || results[i].ely === null ? '-' : results[i].ely}</td>
                                          <td>${results[i].roadNumber}</td>
                                          <td>${results[i].roadPartNumber}</td>
                                          <td>${results[i].addrM}</td>
                                          <td>${results[i].startDate}</td>
                                          <td>${results[i].nodeType}</td>
                                          <td>${results[i].nodeName}</td>
                                          <td>${results[i].nodeCoordinates.y}</td>
                                          <td>${results[i].nodeCoordinates.x}</td>
                                          <td>${results[i].nodeNumber}</td>
                                      </tr>`;
          }
          arr.push(`</tbody>
                              </table>`);
          return $(arr.join('')); // join the array to one large string and create jquery element from said string
      }

      function createArrayOfArraysForJunctions(results) {
          const array = [];
          let arrayPointer = -1;
          array[++arrayPointer] = ['Solmu-numero','P-Koord', 'I-Koord', 'Nimi', 'Solmu-tyyppi', 'Alkupvm', 'Liittymä-nro', 'Tie', 'Ajr', 'Osa', 'Et', 'EJ'];
          for (let i = 0, len = results.length; i < len; i++) {
              array[++arrayPointer] = [
                  results[i].nodeNumber,
                  results[i].nodeCoordinates.y,
                  results[i].nodeCoordinates.x,
                  results[i].nodeName,
                  results[i].nodeType,
                  results[i].startDate,
                  results[i].junctionNumber,
                  results[i].roadNumber,
                  results[i].track,
                  results[i].roadPartNumber,
                  results[i].addrM,
                  getBeforeAfterDisplayText(results[i].beforeAfter)
              ];
          }
          return array; // join the array to one large string and create jquery element from said string
      }

      /**
       *      This function is performance critical. Pointers in use for reasonable processing time.
       *      If edited be sure to measure table creation time with the largest possible dataset!
       */
      function createResultTableForJunctions(results) {
          const arr = [];
          let arrPointer = -1;
          arr[++arrPointer] =`<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table viite-table">
                                  <thead>
                                      <tr>
                                          <th>Solmu-numero</th>
                                          <th>P-Koord</th>
                                          <th>I-Koord</th>
                                          <th>Nimi</th>
                                          <th>Solmu-tyyppi</th>
                                          <th>Alkupvm</th>
                                          <th>Liittymä-nro</th>
                                          <th>Tie</th>
                                          <th>Ajr</th>
                                          <th>Osa</th>
                                          <th>Et</th>
                                          <th>EJ</th>
                                      </tr>
                                  </thead>
                                  <tbody>`;

          for (let i = 0, len = results.length; i < len; i++) {
              arr[++arrPointer] =`    <tr>
                                          <td>${results[i].nodeNumber}</td>
                                          <td>${results[i].nodeCoordinates.y}</td>
                                          <td>${results[i].nodeCoordinates.x}</td>
                                          <td>${results[i].nodeName}</td>
                                          <td>${results[i].nodeType}</td>
                                          <td>${results[i].startDate}</td>
                                          <td>${results[i].junctionNumber}</td>
                                          <td>${results[i].roadNumber}</td>
                                          <td>${results[i].track}</td>
                                          <td>${results[i].roadPartNumber}</td>
                                          <td>${results[i].addrM}</td>
                                          <td>${getBeforeAfterDisplayText(results[i].beforeAfter)}</td>
                                      </tr>`;
          }
          arr.push(`    </tbody>
                              </table>`);
          return $(arr.join('')); // join the array to one large string and create jquery element from said string
      }

      function createArrayOfArraysForRoadNames(results) {
          const array = [];
          let arrayPointer = -1;
          array[++arrayPointer] = ['Elinvoimakeskus', 'Ely', 'Tie', 'Nimi'];
          for (let i = 0, len = results.length; i < len; i++) {
              array[++arrayPointer] = [
                  results[i].evk,
                  typeof results[i].ely === 'undefined' || results[i].ely === null ? '-' : results[i].ely,
                  results[i].roadNumber,
                  results[i].roadName
              ];
          }
          return array; // join the array to one large string and create jquery element from said string
      }

      /**
       *      This function is performance critical. Pointers in use for reasonable processing time.
       *      If edited be sure to measure table creation time with the largest possible dataset!
       */
      function createResultTableForRoadNames(results) {
          const arr = [];
          let arrPointer = -1;
          arr[++arrPointer] = `<table id="roadAddressBrowserTable" class="road-address-browser-window-results-table viite-table">
                                  <thead>
                                      <tr>
                                          <th>Elinvoimakeskus</th>
                                          <th>Ely</th>
                                          <th>Tie</th>
                                          <th>Nimi</th>
                                      </tr>
                                  </thead>
                                  <tbody>`;

          for (let i = 0, len = results.length; i < len; i++) {
              arr[++arrPointer] = `   <tr>
                                          <td>${results[i].evk}</td>
                                          <td>${typeof results[i].ely === 'undefined' || results[i].ely === null ? '-' : results[i].ely}</td>
                                          <td>${results[i].roadNumber}</td>
                                          <td>${results[i].roadName}</td>
                                      </tr>`;
          }
          arr.push(`    </tbody>
                              </table>`);
          return $(arr.join('')); // join the array to one large string and create jquery element from said string
      }

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

          let data = [];
          switch (params.target) {
              case "Tracks":
                  data = createArrayOfArraysForTracks(searchResults);
                  break;
              case "RoadParts":
                  data = createArrayOfArraysForRoadParts(searchResults);
                  break;
              case "Nodes":
                  data = createArrayOfArraysForNodes(searchResults);
                  break;
              case "Junctions":
                  data = createArrayOfArraysForJunctions(searchResults);
                  break;
              case "RoadNames":
                  data = createArrayOfArraysForRoadNames(searchResults);
                  break;
              default:
          }
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

      function getData() {
          const roadAddrSituationDate   = modal.getContent().find('#roadAddrSituationDate')[0];
          const elyEvkSelector      = getElyEvkSelectorValue();
          const roadNumber          = modal.getContent().find('#roadAddrInputRoad')[0];
          const minRoadPartNumber   = modal.getContent().find('#roadAddrInputStartPart')[0];
          const maxRoadPartNumber   = modal.getContent().find('#roadAddrInputEndPart')[0];
          const targetValue         = getTargetSelectorValue();

          // Validate elements exist
          if (!roadAddrSituationDate || !roadNumber || !minRoadPartNumber || !maxRoadPartNumber) {
              console.error('Required form elements not found');
              return;
          }

          // convert date input text to date object
          const roadAddrSituationDateObject  = moment(roadAddrSituationDate.value, "DD-MM-YYYY").toDate();

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

          function validateElyEvkAndRoadNumber (elyValue, roadNumberElement) {
              
              // If neither ELY/EVK or road number is provided, show error
              if (!elyValue && (!roadNumberElement || !roadNumberElement.value)) {
                  if (roadNumberElement) {
                      roadNumberElement.setCustomValidity("Elinvoimakeskus, Ely tai Tie on pakollinen tieto");
                  }
                  return false;
              }
              
              return true;
          }

          // Validate A-osa and L-osa
          function validateBeginningAndEndParts () {
              const aOsa = document.getElementById('roadAddrInputStartPart');
              const lOsa = document.getElementById('roadAddrInputEndPart');

              const aOsaValue = Number(aOsa.value);
              const lOsaValue = Number(lOsa.value);

              const aOsaIsNumber = !isNaN(aOsaValue);
              const lOsaIsNumber = !isNaN(lOsaValue);

              // If both values are valid numbers, validate the range
              if (aOsaIsNumber && lOsaIsNumber && aOsaValue > lOsaValue) {
                  lOsa.setCustomValidity("L-osa ei voi olla pienempi kuin A-osa");
                  return false;
              }

              // Clear error if input is valid or values are not both numbers
              lOsa.setCustomValidity("");
              return true;
          }

          function willPassValidations() {
              validateDate(roadAddrSituationDate.value);
              const elyEvkValid = validateElyEvkAndRoadNumber(elyEvkSelector, roadNumber);
              const partsValid = validateBeginningAndEndParts();
              const formValid = reportValidations();
              
              // Only proceed with search if all validations pass
              return elyEvkValid && partsValid && formValid;
          }

          function createParams() {
              const parsedDateString = dateutil.parseDateToString(roadAddrSituationDateObject);
              const params = {
                  situationDate: parsedDateString,
                  target: targetValue
              };

              // Handle ELY/EVK selection
              if (elyEvkSelector) {
                if (elyEvkSelector.startsWith('EVK_')) {
                    params.roadMaintainer = elyEvkSelector.substring(4); // Backend expects EVK as roadMaintainer
                } else if (elyEvkSelector.startsWith('ELY_')) {
                    params.ely = elyEvkSelector.substring(4); // Remove 'ELY_' prefix
                } else {
                    // Fallback in case the value doesn't have a prefix
                    params.ely = elyEvkSelector;
                }
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

          switch (targetValue) {
              case "Tracks":
              case "RoadParts":
                  validateElyEvkAndRoadNumber(elyEvkSelector, roadNumber);
                  if (willPassValidations())
                      fetchByTargetValue(createParams());
                  break;
              case "Nodes":
              case "Junctions":
              case "RoadNames":
                  validateDate(roadAddrSituationDate.value);
                  if (reportValidations())
                      fetchByTargetValue(createParams());
                  break;
              default:
          }
      }

      function createResultTable(params, results) {
          let resultTable;
          switch (params.target) {
              case "Tracks":
                  resultTable = createResultTableForTracks(results);
                  break;
              case "RoadParts":
                  resultTable = createResultTableForRoadParts(results);
                  break;
              case "Nodes":
                  resultTable = createResultTableForNodes(results);
                  break;
              case "Junctions":
                  resultTable = createResultTableForJunctions(results);
                  break;
              case "RoadNames":
                  resultTable = createResultTableForRoadNames(results);
                  break;
              default:
          }
          return resultTable;
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
                          showData(createResultTable(params, result.results));
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
              return selectorComponents.elyEvk.getSelectedValue();
          }
          return null;
      }

      function getTargetSelectorValue() {
          const selectorComponents = roadAddressBrowserForm.getSelectorComponents();
          if (selectorComponents && selectorComponents.target) {
              const value = selectorComponents.target.getSelectedValue();
              if (value) return value;
          }
          return 'Tracks'; // Default value
      }

      function getTargetSelector() {
          const selectorComponents = roadAddressBrowserForm.getSelectorComponents();
          return selectorComponents ? selectorComponents.target : null;
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

          const startPartInput = modal.getContent().find('#roadAddrInputStartPart')[0];
          if (startPartInput) {
              startPartInput.oninput = function (event) {
                  const input = event.currentTarget;
                  if (input.value.length > ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER) {
                      input.value = input.value.slice(0, ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER);
                  }
              };
          }

          const endPartInput = modal.getContent().find('#roadAddrInputEndPart')[0];
          if (endPartInput) {
              endPartInput.oninput = function (event) {
                  const input = event.currentTarget;
                  if (input.value.length > ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER) {
                      input.value = input.value.slice(0, ViiteConstants.MAX_LENGTH_FOR_ROAD_PART_NUMBER);
                  }
                  const startPart = modal.getContent().find('#roadAddrInputStartPart')[0];
                  const startValue = startPart ? Number(startPart.value) : NaN;
                  const endValue = Number(input.value);
                  if (!isNaN(startValue) && !isNaN(endValue) && startValue > endValue) {
                      input.setCustomValidity("L-osa ei voi olla pienempi kuin A-osa");
                  } else {
                      input.setCustomValidity("");
                  }
              };
          }

          const situationDateInput = modal.getContent().find('#roadAddrSituationDate')[0];
          if (situationDateInput) {
              situationDateInput.oninput = function (event) {
                  event.currentTarget.setCustomValidity("");
              };
          }

          if (startPartInput) {
              const originalStartPartInput = startPartInput.oninput;
              startPartInput.oninput = function(event) {
                  originalStartPartInput(event);
                  const startValue = Number(event.currentTarget.value);
                  const endValue = endPartInput ? Number(endPartInput.value) : NaN;
                  if (endPartInput && !isNaN(startValue) && !isNaN(endValue) && startValue > endValue) {
                      endPartInput.setCustomValidity("L-osa ei voi olla pienempi kuin A-osa");
                  } else if (endPartInput) {
                      endPartInput.setCustomValidity("");
                  }
              };
          }

          /**
           * Situation date input field is disabled when Nodes or Junctions are selected as the target value
           * Nodes and Junctions can only be browsed on the current road network (complete history info not available)
           */
          const targetSelector = getTargetSelector();
          if (targetSelector && targetSelector.config) {
              const originalOnChange = targetSelector.config.onSelectionChange;
              targetSelector.config.onSelectionChange = function(value, event) {
                  const situationDate = modal.getContent().find('#roadAddrSituationDate')[0];
                  if (situationDate) {
                      switch (value) {
                          case "Tracks":
                          case "RoadParts":
                          case "RoadNames":
                              situationDate.disabled = false;
                              situationDate.title = "";
                              break;
                          case "Nodes":
                          case "Junctions":
                              situationDate.value = dateutil.getCurrentDateString();
                              situationDate.disabled = true;
                              situationDate.title = "Solmuja ja liittymiä voi tarkastella vain nykyisellä tieverkolla";
                              break;
                          default:
                      }
                  }
                  if (originalOnChange) {
                      originalOnChange(value, event);
                  }
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

          const formEl = modal.getContent().find('#roadAddressBrowser')[0];
          if (formEl && roadAddressBrowserForm.bindSelectorEvents) {
              roadAddressBrowserForm.bindSelectorEvents(formEl);
          }
          bindEvents();
      }

      return { show };
}
