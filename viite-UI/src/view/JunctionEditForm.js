(function (root) {
    root.JunctionEditForm = function (selectedLinkProperty, roadNamingTool) {
        var svgJunction =
            '<svg\n' +
            '        xmlns="http://www.w3.org/2000/svg"\n' +
            '        xmlns:xlink="http://www.w3.org/1999/xlink"\n' +
            '        width="24"\n' +
            '        height="24"\n' +
            '        viewBox="0 0 24 24"\n' +
            '>\n' +
            '    <circle\n' +
            '            style="opacity:1;fill:#103bae;fill-opacity:1;stroke:none;"\n' +
            '            id="outline"\n' +
            '            cx="12"\n' +
            '            cy="12"\n' +
            '            r="12"/>\n' +
            '    <circle\n' +
            '            style="opacity:1;fill:#235aeb;fill-opacity:1;stroke:none;"\n' +
            '            id="fill"\n' +
            '            cx="12"\n' +
            '            cy="12"\n' +
            '            r="10"/>\n' +
            '    <text id="text"\n' +
            '          x="12"\n' +
            '          y="17"\n' +
            '          style="font-size:14px;font-family:sans-serif;text-align:center;text-anchor:middle;fill:#ffffff;fill-opacity:1;stroke:none;">\n' +
            '    </text>\n' +
            '    <script type="text/ecmascript" xlink:href="param.js" />\n' +
            '</svg>';

        var svgJunctionTemplate =
            '<svg\n' +
            '  xmlns="http://www.w3.org/2000/svg"\n' +
            '  width="24"\n' +
            '  height="24"\n' +
            '   viewBox="0 0 24 24" > \n' +
            '        <circle \n' +
            '   style="opacity:1;fill:#f2c74b;fill-opacity:1;stroke:none;"\n' +
            '   id="outline"\n' +
            '   cx="12"\n' +
            '   cy="12"\n' +
            '   r="12"/>\n' +
            '       <circle\n' +
            '   style="opacity:1;fill:#235aeb;fill-opacity:1;stroke:none;"\n' +
            '   id="fill"\n' +
            '   cx="12"\n' +
            '   cy="12"\n' +
            '   r="9"/>\n' +
            '       <text id="text"\n' +
            '   x="12"\n' +
            '   y="17"\n' +
            '   style="font-size:14px;font-family:sans-serif;text-align:center;text-anchor:middle;fill:#ffffff;fill-opacity:1;stroke:none;">\n' +
            '       </text>\n' +
            '       </svg>\n';
        var template = function (junctionId) {

            var showJunctionTemplateEditForm = false;
            var startDate = '';
            return _.template('' +
                '<header>' +
                title() +
                '</header>' +
                '<div class="wrapper read-only">' +
                addPicture(showJunctionTemplateEditForm) +
                '<a id="junction-point-link" class="floating-stops">Tarkastele liittymäkohtien tietoja</a>' +
                '<div class="form form-horizontal form-dark">' +
                '<div class="form-group-metadata">' +
                '<p class="form-control-static asset-log-info-metadata">Alkupvm: ' + startDate+ '</p>' +
                '</div>' +
                '<div class="form-group-metadata">' +
                '<p class="form-control-static asset-log-info-metadata">Solmunro: ' + junctionId + '</p>' +
                '</div>' +
                '<div class="form-group-metadata">' +
                '<p class="form-control-static asset-log-info-metadata">Solmunimi: ' + '</p>' +
                '</div>' +
                '<div>' +
                '<label class="control-label">Liittymänumero:</label>' + addSmallInputNumber('liittymanro', '', 5) +
                '</div>' +
                '</div>' +
                '</div>' +

                '</div>' +
                '<footer>' + '</footer>');

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

        var addSmallLabel = function (label) {
            return '<label class="control-label-small">' + label + '</label>';
        };
        var addSmallInputNumber = function (id, value, maxLength) {
            //Validate only number characters on "onkeypress" including TAB and backspace
            var smallNumberImput = '<input type="text" onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode == 8 || event.keyCode == 9)' +
                '" class="class="form-control junction-small-input" " id="' + id + '" value="' + (_.isUndefined(value) ? '' : value) + '"' +
                (_.isUndefined(maxLength) ? '' : ' maxlength="' + maxLength + '"') + ' onclick=""/>';
            return smallNumberImput;
        };


        var bindEvents = function () {
            var rootElement = $('#feature-attributes');

            eventbus.on('junctionEdit:selected', function (junctionId) {
                toggleMode(junctionId);
            });

            var toggleMode = function (junctionId) {
                rootElement.html(template(junctionId));
                $('[id=junction-point-link]').click(function () {
                    //eventbus.trigger('junctionEditForm-junctionPoints:select', selected);
                    var junctionNumber= $('[id=liittymanro]').val();
                    eventbus.trigger('junctionPointForm-junctionPoint:select',  junctionId, junctionNumber);
                    return false;
                });
            };

        };

        bindEvents();

    };
})(this);