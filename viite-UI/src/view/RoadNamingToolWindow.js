(function (root) {
    root.RoadNamingToolWindow = function (roadNameCollection) {

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
            '<label class="content-new label">Tie</label>' +
            '<label class="content-new label" style="width: 100px">Tien nimi</label>' +
            '<label class="content-new label" style="width: 100px">Alkupvm</label>' +
            '<label class="content-new label" style="width: 100px">Loppupvm</label>' +
            '</div>' +
            '</div>');

        nameToolSearchWindow.append('<div id="road-list" style="width:810px; height:400px; overflow:auto;"></div>');

        var staticFieldRoadNumber = function (dataField) {
            var field;
            field = '<div>' +
                '<label class="control-label-projects-list" style="width: 300px">' + dataField + '</label>' +
                '</div>';
            return field;
        };

        var staticFieldRoadList = function (dataField) {
            var field;
            field = '<div>' +
                '<label class="control-label-projects-list">' + dataField + '</label>' +
                '</div>';
            return field;
        };

        function toggle() {
            $('.container').append('<div class="modal-overlay confirm-modal"><div class="modal-dialog"></div></div>');
            $('.modal-dialog').append(nameToolSearchWindow.toggle());
            bindEvents();
        }

        function hide() {
            nameToolSearchWindow.hide();
            $('.modal-overlay').remove();
        }

        function bindEvents() {
            eventbus.on("namingTool: toggleCreate", function () {
                if ($("#createRoad").is(":visible")) {
                    $("#createRoad").css({display: "inline-block"});
                } else {
                    $("#createRoad").css({display: "none"});
                }
            });

            nameToolSearchWindow.on('click', 'button.close', function () {
                hide();
            });

            nameToolSearchWindow.on('click', '#executeRoadSearch', function () {
                var roadParam = $('#roadSearchParameter').val();
                roadNameCollection.fetchRoads(roadParam);
            });

            eventbus.on("roadNameTool: roadsFetched", function (roadData) {
                var html = '<table style="align-content: left;align-items: left;table-layout: fixed;width: 100%;">';
                if (!_.isEmpty(roadData)) {
                    _.each(roadData, function (road) {
                        html += '<tr class="project-item">' +
                            '<td style="width: 310px;">' + staticFieldRoadNumber(road.roadNumber) + '</td>' +
                            '<td style="width: 110px;">' + staticFieldRoadList(road.roadNameFi) + '</td>' +
                            '<td style="width: 110px;">' + staticFieldRoadList(road.startDate) + '</td>' +
                            '<td style="width: 110px;">' + staticFieldRoadList(road.endDate) + '</td>';
                        if (road.endDate === "") {
                            html += '<td>' + '<button class="project-open btn btn-new" style="alignment: right; margin-bottom:6px; margin-left: 70px" id="new-road-name-' + road.roadNumber + '" value="' + road.roadNumber + '"">+</button>' + '</td>' +
                                '</tr>' + '<tr style="border-bottom:1px solid darkgray; "><td colspan="100%"></td></tr>';
                        } else {
                            html += '<td>' + '<button class="project-open btn btn-new" style="visibility:hidden; alignment: right; margin-bottom:6px; margin-left: 70px" id="spaceFillerButton">+</button>' + '</td>' +
                                '</tr>' + '<tr style="border-bottom:1px solid darkgray; "><td colspan="100%"></td></tr>';
                        }
                    });
                    html += '</table>';
                    $('#road-list').html($(html));
                } else {
                    html += '</table>';
                    $('#road-list').html($(html));
                }
                alert("RoadData returned to UI");
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