(function (root) {
    root.RoadNetworkErrorsList = function (backend) {

        const networkErrorListWindow =
            $('<div class="road-network-errors-list-window" id="roadNetworkErrorsListWindow">' +
                '<div class="road-network-errors-list-header"> ' +
                    '<p>Tieverkon virheet</p>' +
                    '<button class="close btn-close" id="closeRoadNetworkErrorsList">x</button>' +
                '</div>' +
                '<div id="roadNetworkErrorWindowContent" style="padding: 20px"></div>' +
            '</div>');

        const showRoadNetworkErrorsListWindow = function () {
            $('.container').append(
                '<div class="road-network-errors-modal-overlay viite-modal-overlay confirm-modal" id="roadNetworkErrorsList">' +
                    '<div class="road-network-error-list-modal-window"></div>' +
                '</div>');

            $('.road-network-error-list-modal-window').append(networkErrorListWindow);

            applicationModel.addSpinner();

            backend.getRoadNetworkErrors(function(result) {
                applicationModel.removeSpinner();
                if (result.success === true) {
                    if (result.missingCalibrationPointsFromStart.length > 0 || result.missingCalibrationPointsFromEnd.length > 0)
                        showMissingCalibrationPoints(result.missingCalibrationPointsFromStart.concat(result.missingCalibrationPointsFromEnd));
                    if (result.missingCalibrationPointsFromJunctions.length > 0)
                        showMissingCalibrationPointsForJunctions(result.missingCalibrationPointsFromJunctions);
                    if (result.linksWithExtraCalibrationPoints.length > 0)
                        showLinksWithExtraCalibrationPoints(result.linksWithExtraCalibrationPoints);
                    if (result.missingRoadwayPointsFromStart.length > 0 || result.missingRoadwayPointsFromEnd.length > 0)
                        showMissingRoadwayPoints(result.missingRoadwayPointsFromStart.concat(result.missingRoadwayPointsFromEnd));
                    if (result.invalidRoadwayLengths.length > 0)
                        showInvalidRoadwayLengths(result.invalidRoadwayLengths);
                    if (result.overlappingRoadways.length > 0)
                        showOverlappingRoadways(result.overlappingRoadways);
                    if (result.overlappingRoadwaysOnLinearLocations.length > 0)
                        showOverlappingRoadwaysOnLinearLocations(result.overlappingRoadwaysOnLinearLocations);
                } else {
                    showErrorMessage(result.error);
                }

            });

            bindEvents();
        };

        const hideRoadNetworkErrorsListWindow = function () {
            $('#roadNetworkErrorWindowContent').html(""); // empty the results
            $('.road-network-errors-modal-overlay').remove();
        };

        const showErrorMessage = function (errorMessage) {
            const contentWrapper = $('#roadNetworkErrorWindowContent');
            contentWrapper.append('<p>' + errorMessage + '</p>');
        };

        const showMissingCalibrationPoints = function (calibrationPoints) {
            const contentWrapper = $('#roadNetworkErrorWindowContent');
            contentWrapper.append('<h3>Puuttuvat kalibrointipisteet</h3>');
            const table = $('<table class="viite-table"> ' +
                '<thead> ' +
                    '<th>Tie</th>' +
                    '<th>Osa</th>' +
                    '<th>Ajr</th>' +
                    '<th>Et</th>' +
                '</thead>' +
            '<tbody></tbody></table>');
            calibrationPoints.forEach((cp) => {
                const string = $(`<tr>
                                    <td>${cp.roadNumber}</td>
                                    <td>${cp.roadPartNumber}</td>
                                    <td>${cp.track}</td>
                                    <td>${cp.addrM}</td>
                                </tr>`);
                table.append(string);
            });
            contentWrapper.append(table);
        };

        const showMissingCalibrationPointsForJunctions = function (calibrationPoints) {
            const contentWrapper = $('#roadNetworkErrorWindowContent');
            contentWrapper.append('<h3>Liittymiltä puuttuvat kalibrointipisteet</h3>');
            const table = $('<table class="viite-table"> ' +
                '<thead> ' +
                '<th>Tie</th>' +
                '<th>Osa</th>' +
                '<th>Ajr</th>' +
                '<th>Et</th>' +
                '<th>Solmu nro</th>' +
                '<th>Liittymä nro</th>' +
                '<th>Liittymäkohta Id</th>' +
                '</thead>' +
                '<tbody></tbody></table>');
            calibrationPoints.forEach((cp) => {
                const string = $(`<tr>
                                    <td>${cp.roadNumber}</td>
                                    <td>${cp.roadPartNumber}</td>
                                    <td>${cp.track}</td>
                                    <td>${cp.addrM}</td>
                                    <td>${cp.nodeNumber}</td>
                                    <td>${cp.junctionNumber}</td>
                                    <td>${cp.junctionPointId}</td>
                                </tr>`);
                table.append(string);
            });
            contentWrapper.append(table);
        };

        const showLinksWithExtraCalibrationPoints = function (linksWithExtraCalibrationPoints) {
            const contentWrapper = $('#roadNetworkErrorWindowContent');
            contentWrapper.append('<h3>Linkit joilla on ylimääräisiä kalibrointipisteitä</h3>');
            const table = $('<table class="viite-table"> ' +
                '<thead> ' +
                '<th>Linkin Id</th>' +
                '<th>Tie</th>' +
                '<th>Osa</th>' +
                '<th>Kpl/Alku</th>' +
                '<th>Kpl/Loppu</th>' +
                '<th>Kalibrointipisteiden Id:t</th>'+
                '</thead>' +
                '<tbody></tbody></table>');
            linksWithExtraCalibrationPoints.forEach((link) => {
                const tableRow = $(`<tr>
                            <td>${link.linkId}</td>
                            <td>${link.roadNumber}</td>
                            <td>${link.roadPartNumber}</td>
                            <td>${link.startCount}</td>
                            <td>${link.endCount}</td>
                            <td>${link.calibrationPoints}</td>
                        </tr>`);
                table.append(tableRow);
            });
            contentWrapper.append(table);
        };

        const showMissingRoadwayPoints = function (roadwayPoints) {
            const contentWrapper = $('#roadNetworkErrorWindowContent');
            contentWrapper.append('<h3>Tieosalta puuttuvat roadwaypointit</h3>');
            const table = $('<table class="viite-table"> ' +
                '<thead> ' +
                '<th>Tie</th>' +
                '<th>Osa</th>' +
                '<th>Ajr</th>' +
                '<th>Et</th>' +
                '</thead>' +
                '<tbody></tbody></table>');
            roadwayPoints.forEach((rwp) => {
                const tableRow = $(`<tr>
                                    <td>${rwp.roadNumber}</td>
                                    <td>${rwp.roadPartNumber}</td>
                                    <td>${rwp.track}</td>
                                    <td>${rwp.addrM}</td>
                                </tr>`);
                table.append(tableRow);
            });
            contentWrapper.append(table);
        };

        const showInvalidRoadwayLengths = function (invalidRoadways) {
            const contentWrapper = $('#roadNetworkErrorWindowContent');
            contentWrapper.append('<h3>Vääränpituiset roadwayt</h3>');
            const table = $('<table class="viite-table"> ' +
                '<thead> ' +
                '<th>Tie</th>' +
                '<th>Osa</th>' +
                '<th>Ajr</th>' +
                '<th>Aet</th>' +
                '<th>Let</th>' +
                '<th>Pituus</th>' +
                '<th>Roadway numero</th>' +
                '</thead>' +
                '<tbody></tbody></table>');
            invalidRoadways.forEach((rw) => {
                const tableRow = $(`<tr>
                                    <td>${rw.roadNumber}</td>
                                    <td>${rw.roadPartNumber}</td>
                                    <td>${rw.track}</td>
                                    <td>${rw.startAddrM}</td>
                                    <td>${rw.endAddrM}</td>
                                    <td>${rw.length}</td>
                                    <td>${rw.roadwayNumber}</td>
                                </tr>`);
                table.append(tableRow);
            });
            contentWrapper.append(table);
        };

        const showOverlappingRoadways = function (overlappingRoadways) {
            const contentWrapper = $('#roadNetworkErrorWindowContent');
            contentWrapper.append('<h3>Päällekkäiset roadwayt</h3>');
            const table = $('<table class="viite-table"> ' +
                '<thead> ' +
                '<th>Tie</th>' +
                '<th>Osa</th>' +
                '<th>Ajr</th>' +
                '<th>Aet</th>' +
                '<th>Let</th>' +
                '<th>Roadway numero</th>' +
                '</thead>' +
                '<tbody></tbody></table>');
            overlappingRoadways.forEach((rw) => {
                const tableRow = $(`<tr>
                                    <td>${rw.roadNumber}</td>
                                    <td>${rw.roadPartNumber}</td>
                                    <td>${rw.track}</td>
                                    <td>${rw.startAddrM}</td>
                                    <td>${rw.endAddrM}</td>
                                    <td>${rw.roadwayNumber}</td>
                                </tr>`);
                table.append(tableRow);
            });
            contentWrapper.append(table);
        };

        const showOverlappingRoadwaysOnLinearLocations = function (overlappingRoadways) {
            const contentWrapper = $('#roadNetworkErrorWindowContent');
            contentWrapper.append('<h3>Päällekkäiset roadwayt lineaarilokaatioilla</h3>');
            const table = $('<table class="viite-table"> ' +
                '<thead> ' +
                '<th>Tie</th>' +
                '<th>Osa</th>' +
                '<th>Ajr</th>' +
                '<th>Aet</th>' +
                '<th>Let</th>' +
                '<th>Roadway numero</th>' +
                '<th>Lineaarilokaatio Id</th>' +
                '<th>Link Id</th>' +
                '</thead>' +
                '<tbody></tbody></table>');
            overlappingRoadways.forEach((rw) => {
                const tableRow = $(`<tr>
                                    <td>${rw.roadNumber}</td>
                                    <td>${rw.roadPartNumber}</td>
                                    <td>${rw.track}</td>
                                    <td>${rw.startAddrM}</td>
                                    <td>${rw.endAddrM}</td>
                                    <td>${rw.roadwayNumber}</td>
                                    <td>${rw.linearLocationId}</td>
                                    <td>${rw.linkId}</td>
                                </tr>`);
                table.append(tableRow);
            });
            contentWrapper.append(table);
        };

        const bindEvents = function () {

            $('.road-network-errors-list-header').on('click', '#closeRoadNetworkErrorsList', function() {
                hideRoadNetworkErrorsListWindow();
            });
        };

        return {
            showRoadNetworkErrorsListWindow: showRoadNetworkErrorsListWindow
        };

    };
}(this));
