(function (root) {
    root.JunctionPointForm = function (map, nodeCollection, backend) {


        var addSaveEvent = function () {
            var saveButton = '<button id="saveEtaisyys" class="btn btn-primary save btn-save-road-data" disabled>Tallenna</button>';
            $('#feature-attributes').append(saveButton);
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

        var addReturn = function () {
            var returnButton = '<button id="return" class="btn btn-primary save btn-save-road-data" disabled>Palaa</button>';
            $('#feature-attributes').append(returnButton);


        };
        var template = function () {
            var rootElement = $('#feature-attributes');
            rootElement.empty();
            $('#feature-attributes').append('' +
                '<p class="center">' + title() + ' </p>' +
                '<div class="form form-horizontal form-dark">' +
                '<div class="edit-control-group project-choice-group">' +
                staticField('Solmunro:', '-') +
                staticField('Liittymänumero:', '-') +
                '<div class="form-group editable form-editable-roadAddressProject"> ' +
                '<form  id="junctionPoint"  class="input-unit-combination form-group form-horizontal roadAddressProject">' +
                '<div class="form-group">' +
                addSmallLabel('TIE') + addSmallLabel('OSA') + addSmallLabel('ETÄISYYS') +
                addSmallLabel('E/J') + addSmallLabel('LOPPUPVM') + addSmallLabel('ALKUPVM') +
                '</div>' +
                '<div class="form-group">' +
                addSmallInputNumber('tie', '', 5) + addSmallInputNumber('aosa', '', 3) + addSmallInputNumber('losa', '', 3) + //addReserveButton() +
                addSmallInputNumber('E/J', '', 2) + addSmallInputNumber('loppupvm', '', 12) + addSmallInputNumber('alkupvm', '', 12) +
                '</div>' +
                '</form>' +
                '</div>' +
                '</div>' +
                '<div id="junctions-content">' +
                '</div>' +
                '<footer>' + '</footer>');

        };
        var title = function () {
            return '<span class="header-orange">Liittymäkohtien tiedot:</span>';
        };
        var addSmallLabel = function (label) {
            return '<label class="junction-control-label-small">' + label + '</label>';
        };
        var addSmallInputNumber = function (id, value, maxLength) {
            //Validate only number characters on "onkeypress" including TAB and backspace
            var smallNumberImput = '<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode == 8 || event.keyCode == 9)' +
                '" class="form-junction small-input roadAddressProject" id="' + id + '" value="' + (_.isUndefined(value) ? '' : value) + '"' +
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
            eventbus.on('junctionPointForm-junctionPoint:select', function (layerName) {
                template();
                getDataTemplates();
                addSaveEvent();
                addReturn();
            });
        };
        var getDataTemplates = function () {
            backend.getTemplates(function (data) {
                eventbus.trigger('templates:fetched', data);
                var junctionTemplates = _.map(_.filter(data, function (junction) {
                    return !_.isUndefined(junction.junctionTemplate);
                }), function (template) {
                    return template.junctionTemplate;
                });
                $('#unctions-content').html(junctionTemplatesHtml(junctionTemplates));
                //applicationModel.removeSpinner();
            });
        };
        var junctionTemplateLink = function (junctionTemplate) {
            return addSmallInputNumber('tie', '', 5) + addSmallInputNumber('aosa', '', 3) + addSmallInputNumber('losa', '', 3) + //addReserveButton() +
                addSmallInputNumber('E/J', '', 2) + addSmallInputNumber('loppupvm', '', 12) + addSmallInputNumber('alkupvm', '', 12);

        };
        var junctionTemplatesHtml = function (junctionPoints) {
            var groups = _.groupBy(junctionPoints, function (template) {
                return template.elyCode;
            });
            var text = "";
            text +=
                addSmallLabel('TIE') + addSmallLabel('OSA') + addSmallLabel('ETÄISYYS') +
                addSmallLabel('E/J') + addSmallLabel('LOPPUPVM') + addSmallLabel('ALKUPVM');
            // text += '<label class="control-label-small" style="text-transform:none;color:white;">(TIE / OSA / AET)</label></br>';

            _.each(junctionPoints, function (junctionPoint) {
                text += junctionTemplateLink(junctionPoint) + '</br>';
            });

            return text;
        };
        bindEvents();


    };
})(this);