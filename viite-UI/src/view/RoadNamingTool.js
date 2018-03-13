(function (root) {
    root.RoadNamingTool = function () {

        var nameToolSearchWindow = $('<div id="name-search-window" class="form-horizontal naming-list"></div>').hide();
        nameToolSearchWindow.append('<button class="close btn-close" id="closeRoadNameTool">x</button>');
        nameToolSearchWindow.append('<div class="content">Tienimi</div>');
        nameToolSearchWindow.append('<div class="name-tool-content-new">' +
            '<label class="name-tool-content-new label">Tie</label>' +
            '<div class = "panel-header">' +
            '<input type="text" class = "road-input" title="Tie Nimi">' +
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
        }

        return {
            toggle: toggle,
            hide: hide,
            element: nameToolSearchWindow,
            bindEvents: bindEvents
        };

    };
})(this);