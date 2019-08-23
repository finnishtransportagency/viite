(function (root) {

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
        eventbus.on('junctionEditForm-junctionPoint:select', function (layerName) {
            template();
        });
    };

    root.JunctionPointForm = {
        initialize: function () {
            bindEvents();
        }
    };

})(this);