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

        var staticFieldRoadNumber = function (dataField, fieldName) {
            var field;
            field = '<div>' +
                '<input class="input-road-details-readonly" style="width: 300px" value="' + dataField + '" data-FieldName="' + fieldName + '" readonly >' +
                '</div>';
            return field;
        };

        var staticFieldRoadList = function (dataField, writable, roadId, fieldName) {
            var field;
            var inputClass = (writable ? "input-road-details-writable" : "input-road-details-readonly");
            var readOnly = (writable ? "" : "readonly");
            field = '<div>' +
                '<input class="' + inputClass + '" value="' + dataField + '" ' + readOnly + ' data-roadId="' + roadId + '" data-FieldName="' + fieldName + '">' +
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
                $('.roadList-item').remove();
                roadNameCollection.clearBoth();
                hide();
            });

            nameToolSearchWindow.on('click', '#executeRoadSearch', function () {
                var roadParam = $('#roadSearchParameter').val();
                $('.roadList-item').remove();
                roadNameCollection.fetchRoads(roadParam);
            });

            eventbus.on("roadNameTool: roadsFetched", function (roadData) {
                var saveButton = '<button id="saveChangedRoads" class="btn btn-primary save btn-save-road-data">Tallenna</button>';
                var html = '<table style="align-content: left;align-items: left;table-layout: fixed;width: 100%;">';
                if (!_.isEmpty(roadData)) {
                    _.each(roadData, function (road) {
                        var writable = road.endDate == "";
                        html += '<tr class="roadList-item">' +
                            '<td style="width: 310px;">' + staticFieldRoadNumber(road.roadNumber, road.id) + '</td>' +
                            '<td style="width: 110px;">' + staticFieldRoadList(road.roadNameFi, writable, road.id) + '</td>' +
                            '<td style="width: 110px;">' + staticFieldRoadList(road.startDate, false, road.id) + '</td>' +
                            '<td style="width: 110px;">' + staticFieldRoadList(road.endDate, writable, road.id) + '</td>';
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
                    $('#road-list').append(saveButton);

                    $('.input-road-details-writable').on("change", function (eventObject) {
                        var target = $(eventObject.target);
                        var roadId = target.attr("data-roadid");
                        var fieldName = target.attr("data-FieldName");
                        var fieldValue = target.val();
                        roadNameCollection.registerEdition(roadId, fieldName, fieldValue);
                    });

                } else {
                    html += '</table>';
                    $('#road-list').html($(html));
                    $('#road-list').append(saveButton);
                }
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