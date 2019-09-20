(function (root) {
    root.JunctionPointForm = function (backend) {


        var addSaveEvent = function () {
            var saveButton = '<button id="saveEtaisyys" class="btn btn-primary save btn-save-road-data" disabled>Tallenna</button>';
            $('#buttons-div').append(saveButton);
            $('#aosa').on('click', function (clickEvent) {
                var saveMessage = ($('#aosa').length > 0 ? "Tiellä on jo nimi. Haluatko varmasti antaa sille uuden nimen?" : "Tiellä on jo nimi. Haluatko varmasti muokata sitä?");

                new GenericConfirmPopup(saveMessage, {
                    successCallback: function () {
                        roadNameCollection.saveChanges();
                    },
                    closeCallback: function () {
                    }
                });
            });
        };

        var addReturn = function (junctionId) {
            var returnButton = '<button id="return" class="btn btn-primary save btn-save-road-data">Palaa</button>';
            $('#buttons-div').append(returnButton);
            $('button#return').on('click', function (e) {
                e.preventDefault();
                eventbus.trigger('junctionEdit:selected', junctionId);
                return false;
            });
        };

        var title = function () {
            return '<span class="header-orange">Liittymäkohtien tiedot:</span>';
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
        var getDataTemplateInfo = function (junctionId) {
            return backend.getJunctionInfoByJunctionId(junctionId, function (junctionInfo) {
                //template(junctionInfo);
                var rootElement = $('#feature-attributes');
                rootElement.empty();
                $('#feature-attributes').append('' +
                    '<p class="center">' + title() + ' </p>' +
                    '<div class="form form-horizontal form-dark">' +
                    '<div class="edit-control-group project-choice-group">' +
                    staticField('Solmunro:', junctionInfo.nodeNumber) +
                    staticField('Liittymänumero:', junctionInfo.junctionNumber) +
                    '</div>' +
                    '<div id="junctions-content">' +
                    '</div>' +
                    '<div id="buttons-div">' +
                    '</div>' +
                    '</div>' +
                    '<footer>' + '</footer>');
                addSaveEvent();
                addReturn(junctionId);

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