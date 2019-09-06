(function (root) {
    root.JunctionEditForm = function (backend) {

        var svgJunction =
            '<object type="image/svg+xml" data="images/junction.svg" style="margin-right: 10px; margin-top: 5px"></object>';

        var svgJunctionTemplate =
            '<object type="image/svg+xml" data="images/junction-template.svg" style="margin-right: 10px; margin-top: 5px"></object>';

        var getDataTemplates = function (junctionId) {
            return backend.getJunctionInfoByJunctionId(junctionId, function (junctionInfo) {
                var showJunctionTemplateEditForm = isJunctionTemplate(junctionInfo);
                $('#feature-attributes').html(template(junctionInfo, showJunctionTemplateEditForm));
                $('[id=junction-point-link]').click(function () {
                    eventbus.trigger('junctionPointForm-junctionPoint:select', junctionId);
                    return false;
                });
            });
        };

        var template = function (junctionInfo, showJunctionTemplateEditForm) {
            return _.template('' +
                '<header>' +
                title() +
                '</header>' +
                '<div class="wrapper read-only">' +
                '<div>' +
                addPicture(showJunctionTemplateEditForm) +
                '<a id="junction-point-link" class="floating-stops">Tarkastele liittymäkohtien tietoja</a>' +
                '</div>' +
                '<div class="form form-horizontal form-dark">' +
                '<div class="form-group-metadata">' +
                '<p class="form-control-static asset-log-info-metadata">Alkupvm: ' + junctionInfo.startDate + '</p>' +
                '</div>' +
                '<div class="form-group-metadata">' +
                '<p class="form-control-static asset-log-info-metadata">Solmunro: ' + checkEmptyAndNullAndZero(junctionInfo.nodeNumber) + '</p>' +
                '</div>' +
                '<div class="form-group-metadata">' +
                '<p class="form-control-static asset-log-info-metadata">Solmunimi: ' + checkEmptyAndNullAndZero(junctionInfo.nodeName) + '</p>' +
                '</div>' +
                '<div class="form-group-metadata">' +
                '<p class="form-control-static asset-log-info-metadata">Liittymä id: ' + checkEmptyAndNullAndZero(junctionInfo.id) + '</p>' +
                '</div>' +
                '<div>' +
                addSmallLabelYellow('Liittymänumero:') + addSmallInputNumber('liittymanro', checkEmptyAndNullAndZero(junctionInfo.junctionNumber), 5) +
                '</div>' +
                '</div>' +
                '</div>' +
                '</div>' +
                '<footer>' +
                '</footer>');

        };
        var checkEmptyAndNullAndZero = function (value) {
            if (null === value || '' === value) {
                return '';
            } else if (_.isUndefined(value)) {
                return '';
            } else if (0 === value) {
                return '';
            } else {
                return value;
            }

        };
        var isJunctionTemplate = function (junctionInfo) {
            if (null === junctionInfo.junctionNumber || '' === junctionInfo.junctionNumber || junctionInfo.junctionNumber === 0)
                return true;
        };
        var title = function () {
            return '<span class="header-orange">Liittymän tiedot</span>';
        };
        var addPicture = function (showJunctionTemplateEditForm) {
            if (showJunctionTemplateEditForm) {
                return svgJunctionTemplate;
            } else {
                return svgJunction;
            }
        };

        var addSmallLabelYellow = function (label) {
            return '<label class="form-control-static-short-yellow">' + label + '</label>';
        };
        var addSmallInputNumber = function (id, value, maxLength) {
            //Validate only number characters on "onkeypress" including TAB and backspace
            var smallNumberImput = '<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode == 8 || event.keyCode == 9)' +
                '" class="class="form-control junction-small-input" " id="' + id + '" value="' + (_.isUndefined(value) ? '' : value) + '"' +
                (_.isUndefined(maxLength) ? '' : ' maxlength="' + maxLength + '"') + ' onclick=""/>';
            return smallNumberImput;
        };


        var bindEvents = function () {

            eventbus.on('junctionEdit:selected', function (junctionId) {
                var rootElement = $('#feature-attributes');
                rootElement.empty();
                getDataTemplates(junctionId);
                return false;
            });


        };

        bindEvents();

    };
})(this);