(function (root) {
    root.JunctionPointForm = function (backend) {

        var title = function () {
            return '<span class="caption-title">Liittymäkohtien tiedot:</span>';
        };
        var addSmallLabel = function (label) {
            return '<label class="junction-points junction-control-label-small">' + label + '</label>';
        };
        var addDisabledSmallInputNumber = function (id, value, maxLength) {
            var smallNumberImput = '<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode == 8 || event.keyCode == 9)' +
                '" class="junction-points form-control junction-disabled-small-input" id="' + id + '" value="' + (_.isUndefined(value) ? '' : value) + '"' +
                (_.isUndefined(maxLength) ? '' : ' maxlength="' + maxLength + '"') + ' onclick="" disabled/>';
            return smallNumberImput;
        };
        var addSmallInputNumber = function (id, value, maxLength) {
            var smallNumberImput = '<input type="text"  onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode == 8 || event.keyCode == 9)' +
                '" class="junction-points form-control junction-small-input" id="' + id + '" value="' + (_.isUndefined(value) ? '' : value) + '"' +
                (_.isUndefined(maxLength) ? '' : ' maxlength="' + maxLength + '"') + ' onclick=""/>';
            return smallNumberImput;
        };
        var staticField = function (labelText, dataField) {
            var field;
            field = '<div class="form-group">' +
                '<p class="form-control-static asset-log-info">' + labelText + ' : ' + dataField + '</p>' +
                '</div>';
            return field;
        };
        var bindEvents = function () {
            eventbus.on('junctionPointForm-junctionPoint:select', function (junctionId) {
                getDataTemplateInfo(junctionId);
                getDataTemplateForJunctionPoints(junctionId);

            });
        };

        var formButtons = function () {
            return '<div class="form form-controls" id="buttons-div">' +
                '<button id="saveDistance" class="save btn btn-edit-junction-save" disabled>Tallenna</button>' +
                '<button id="return" class="cancel btn btn-return-list-junction">Palaa</button>' +
                '</div>';
        };

        var addSaveEvent = function () {
            $('#aosa').on('click', function (clickEvent) {
                var saveMessage = ($('#aosa').length > 0 ? "Tiellä on jo nimi. Haluatko varmasti antaa sille uuden nimen?" : "Tiellä on jo nimi. Haluatko varmasti muokata sitä?");
                new GenericConfirmPopup(saveMessage, {
                    successCallback: function () {
                        //TODO edit or save junction point info
                    },
                    closeCallback: function () {
                    }
                });
            });
        };

        var addReturnEvent = function (junctionId) {
            $('button#return').on('click', function (e) {
                e.preventDefault();
                eventbus.trigger('junctionEdit:selected', junctionId);
                return false;
            });
        };

        var getDataTemplateInfo = function (junctionId) {
            return backend.getJunctionInfoByJunctionId(junctionId, function (junctionInfo) {
                var rootElement = $('#feature-attributes');
                rootElement.empty();
                $('#feature-attributes').append('' +
                    '<header>' +
                    title() +
                    '</header>' +
                    '<div class="wrapper read-only">' +
                    '<div class="form form-horizontal form-dark">' +
                    '<div class="edit-control-group project-choice-group">' +
                    staticField('Solmunumero: ', junctionInfo.nodeNumber) +
                    staticField('Liittymänumero: ', junctionInfo.junctionNumber) +
                    '</div>' +
                    '<div id="junctions-content">' +
                    '</div>' +
                    '</div>' +
                    '</div>' +
                    '<footer>' + formButtons() +
                    '</footer>');
                addSaveEvent();
                addReturnEvent(junctionId);

            });
        };
        var getDataTemplateForJunctionPoints = function (junctionId) {
            backend.getJunctionPointsByJunctionId(junctionId, function (result) {
                $('#junctions-content').html(junctionTemplatesHtml(result));
            });
        };
        var junctionDataRow = function (junctionPoint) {
            text = '<div class="form-group">' +
                addSmallLabel('TIE') + addSmallLabel('AJR')+ addSmallLabel('OSA') + addSmallLabel('ETÄISYYS') +
                addSmallLabel('E/J') +
                '</div>' +
                '<div class="form-group">' +
                addDisabledSmallInputNumber('tie', junctionPoint.junctionPointTemplate.roadNumber, 5) +addDisabledSmallInputNumber('ajr', junctionPoint.junctionPointTemplate.track, 5)+ addDisabledSmallInputNumber('osa', junctionPoint.junctionPointTemplate.roadPartNumber, 3) + addSmallInputNumber('etaisyys', '', 5) + //addReserveButton() +
                addDisabledSmallInputNumber('E/J', junctionPoint.junctionPointTemplate.beforeAfter, 2) +
                '</div>';
            return text;
        };
        var junctionTemplatesHtml = function (junctionPoints) {
            var dataRows = "";
            _.each(junctionPoints, function (junctionPoint) {
                dataRows += junctionDataRow(junctionPoint);
            });
            var text = "";
            text +=
                '<div class="form-group editable form-editable-roadAddressProject"> ' +
                '<form  id="junctionPoint"  class="input-unit-combination form-group form-horizontal junction-points">' + dataRows +
                '</form>' +
                '</div>';
            return text;
        };
        bindEvents();
    };
})(this);