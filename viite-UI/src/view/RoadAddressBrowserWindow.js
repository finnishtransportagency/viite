(function (root) {
    root.RoadAddressBrowserWindow = function (roadAddressCollection) {

        var roadAddrBrowserWindow = $('<div id="road-address-browser-window" class="form-horizontal road-address-browser-window"></div>').hide();
        roadAddrBrowserWindow.append('<button class="close btn-close" id="closeRoadAddrBrowserWindow">x</button>');
        roadAddrBrowserWindow.append('<div class="content">Tieosoitteiden katselu</div>');
        roadAddrBrowserWindow.append('' +
            '<form id="roadAddressBrowser" class="road-address-browser-form">' +
                '<div class="input-container"><label class="control-label-small">Tilanne Pvm</label> <input type="date" id="roadAddrStartDate" value="' + getCurrentDate() + '" style="width: 100px"/></div>' +
                '<div class="input-container"><label class="control-label-small" >Ely</label><input type="number" min="0" id="roadAddrInputEly" /></div>' +
                '<div class="input-container"><label class="control-label-small" >Tie</label><input type="number" min="0" id="roadAddrInputRoad" /></div>' +
                '<div class="input-container"><label class="control-label-small">Aosa</label><input type="number" min="0" id="roadAddrInputStartPart"/></div>' +
                '<div class="input-container"><label class="control-label-small">Losa</label><input type="number" min="0" id="roadAddrInputEndPart"/></div>' +
                '<div class="input-container"><input type="radio" name="roadAddrBrowserForm" value="Roads" checked="checked"><label>Tieosat</label></div>' +
                '<div class="input-container"><input type="radio" name="roadAddrBrowserForm" value="Nodes"><label>Solmut</label></div>' +
                '<div class="input-container"><input type="radio" name="roadAddrBrowserForm" value="Junctions"><label>Liittymät</label></div>' +
                '<div class="input-container"><input type="radio" name="roadAddrBrowserForm" value="RoadNames"><label>Tiennimet</label></div>' +
                '<button class="btn btn-primary btn-fetch-road-addresses"> Hae </button>' +
            '</form>'
        );

        function showResults() {
            var results = roadAddressCollection.getRoads();
            var table =$('<table class="road-address-browser-window-results-table"></table>');
            table.append(
                '<tr>' +
                    '<th>Ely</th>' +
                    '<th>Tie</th>' +
                    '<th>Ajr</th>' +
                    '<th>Osa</th>' +
                    '<th>Aet</th>' +
                    '<th>Let</th>' +
                    '<th>Pituus</th>' +
                    '<th>Alkupäivämäärä</th>' +
                '</tr>'
            );
            results.forEach((resRow) => table.append(
                '<tr>' +
                    '<td>' + resRow.ely + '</td>' +
                    '<td>' + resRow.roadNumber + '</td>' +
                    '<td>' + resRow.track + '</td>' +
                    '<td>' + resRow.roadPartNumber + '</td>' +
                    '<td>' + resRow.startAddrM + '</td>' +
                    '<td>' + resRow.endAddrM + '</td>' +
                    '<td>' + resRow.lengthAddrM + '</td>' +
                    '<td>' + resRow.startDate + '</td>' +
                '</tr>'
            ));
            roadAddrBrowserWindow.append(table);
        }

        function showResultsForNodes() {
            var results = roadAddressCollection.getNodes();
            var table =$('<table class="road-address-browser-window-results-table"></table>');
            table.append(
                '<tr>' +
                    '<th>Ely</th>' +
                    '<th>Tie</th>' +
                    '<th>Osa</th>' +
                    '<th>Et</th>' +
                    '<th>Alkupäivämäärä</th>' +
                    '<th>Tyyppi</th>' +
                    '<th>Nimi</th>' +
                    '<th>Solmunumero</th>' +
                '</tr>'
            );
            results.forEach((resRow) => table.append(
                '<tr>' +
                    '<td>' + resRow.ely + '</td>' +
                    '<td>' + resRow.roadNumber + '</td>' +
                    '<td>' + resRow.roadPartNumber + '</td>' +
                    '<td>' + resRow.addrM + '</td>' +
                    '<td>' + resRow.startDate + '</td>' +
                    '<td>' + resRow.nodeType + '</td>' +
                    '<td>' + resRow.nodeName + '</td>' +
                    '<td>' + resRow.nodeNumber + '</td>' +
                '</tr>'
            ));
            roadAddrBrowserWindow.append(table);
        }


        function toggle() {
            $('.container').append('<div class="modal-overlay confirm-modal"><div class="modal-dialog"></div></div>');
            $('.modal-dialog').append(roadAddrBrowserWindow.toggle());
            bindEvents();
        }

        function getCurrentDate() {
            var today = new Date();
            var dayInNumber = today.getDate();
            var day = dayInNumber < 10 ? '0' + dayInNumber.toString() : dayInNumber.toString();
            var monthInNumber = today.getMonth() + 1;
            var month = monthInNumber < 10 ? '0' + monthInNumber.toString() : monthInNumber.toString();
            var year = today.getFullYear().toString();
            return year + '-' + month + '-' + day;
        }

        function hide() {
            $('.modal-dialog').append(roadAddrBrowserWindow.toggle());
            $('.modal-overlay').remove();
        }

        eventbus.on('roadAddressBrowser:roadsFetched', function () {
            applicationModel.removeSpinner();
            showResults();
        });

        eventbus.on('roadAddressBrowser:nodesFetched', function () {
           applicationModel.removeSpinner();
           showResultsForNodes();
        });


        function bindEvents() {

            roadAddrBrowserWindow.on('click', 'button.close', function () {
                hide();
            });

            roadAddrBrowserWindow.on('click', '.btn-fetch-road-addresses', function (e) {
                $('.road-address-browser-window-results-table').remove(); // empty the result table

                var roadAddrStartDate   = document.getElementById('roadAddrStartDate');
                var ely                 = document.getElementById('roadAddrInputEly');
                var roadNumber          = document.getElementById('roadAddrInputRoad');
                var minRoadPartNumber   = document.getElementById('roadAddrInputStartPart');
                var maxRoadPartNumber   = document.getElementById('roadAddrInputEndPart');
                var checkedValue        = $("input:radio[name ='roadAddrBrowserForm']:checked").val();

                if (ely.value === "" && roadNumber.value === "") {
                    e.preventDefault();
                    ely.setCustomValidity("Ely tai Tie on pakollinen tieto");
                    ely.reportValidity();
                } else {
                    var params = {
                        startDate: roadAddrStartDate.value
                    };
                    if (ely.value)
                        params.ely = ely.value;
                    if (roadNumber.value)
                        params.roadNumber = roadNumber.value;
                    if (minRoadPartNumber.value)
                        params.minRoadPartNumber = minRoadPartNumber.value;
                    if (maxRoadPartNumber.value)
                        params.maxRoadPartNumber = maxRoadPartNumber.value;

                    switch (checkedValue) {
                        case "Roads":
                            roadAddressCollection.fetchRoads(params);
                            applicationModel.addSpinner();
                            break;
                        case "Nodes":
                            roadAddressCollection.fetchNodes(params);
                            applicationModel.addSpinner();
                            break;
                        case "Junctions":
                            new ModalConfirm("Tämä toiminnallisuus ei ole vielä valmis");
                            //TODO roadAddressCollection.fetchJunctions()
                            break;
                        case "RoadNames":
                            new ModalConfirm("Tämä toiminnallisuus ei ole vielä valmis");
                            //TODO roadAddressCollection.fetchRoadNames()
                            break;
                        default:
                    }
                }
                return false; // cancel form submission
            });
        }

        return {
            toggle: toggle
        };
    };
}(this));
