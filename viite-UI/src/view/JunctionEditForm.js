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
        var template = function (showJunctionTemplateEditForm) {


            return _.template('' +
                '<header>' +
                title() +
                '</header>' +
                '<div class="wrapper read-only">' +
                addPicture(showJunctionTemplateEditForm) +
                '<a id="junction-list-link" class="floating-stops" href="#junction-list/junctionListInfos">Tarkastele liittymäkohtien tietoja</a>' +
                '<div class="form form-horizontal form-dark">' +
                '<div class="form-group-metadata">' +
                '<p class="form-control-static asset-log-info-metadata">Alkupvm: + "TODO"</p>' +
                '</div>' +
                '<div class="form-group-metadata">' +
                '<p class="form-control-static asset-log-info-metadata">Solmunro: ' + "TODO" + '</p>' +
                '</div>' +
                '<div class="form-group-metadata">' +
                '<p class="form-control-static asset-log-info-metadata">Solmunimi: ' + "TODO" + '</p>' +
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
            return '<span>Liittymän tiedot</span>';
        };
        var addPicture = function (showJunctionTemplateEditForm) {
            //var s = new XMLSerializer().serializeToString(svg);
            var encodedData = window.btoa(svgJunction);
            var junctionMarkerStyle = new ol.style.Style({
                image: new ol.style.Icon({
                    src: 'data:image/svg+xml;base64,' + encodedData,
                    scale: 0.75
                })
            });
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
                '" class="form-control small-input roadAddressProject" id="' + id + '" value="' + (_.isUndefined(value) ? '' : value) + '"' +
                (_.isUndefined(maxLength) ? '' : ' maxlength="' + maxLength + '"') + ' onclick=""/>';
            return smallNumberImput;
        };


        var bindEvents = function () {
            var rootElement = $('#feature-attributes');

            eventbus.on('junctionEdit:selected', function (linkProperties) {
                toggleMode(true, true);
            });
            var toggleMode = function (readOnly, showJunctionTemplateEditForm) {
                var firstSelectedLinkProperty = _.first(selectedLinkProperty.get());
                if (!_.isEmpty(selectedLinkProperty.get())) {
                    if (readOnly) {
                        if (firstSelectedLinkProperty.floating === LinkValues.SelectionType.Floating.value) {
                        } else {
                            rootElement.html(template(showJunctionTemplateEditForm)(firstSelectedLinkProperty));
                        }
                    } else {
                        if (_.last(selectedLinkProperty.get(showJunctionTemplateEditForm)).floating === LinkValues.SelectionType.Floating.value) {

                        } else {
                            rootElement.html(template(showJunctionTemplateEditForm)(firstSelectedLinkProperty));
                        }
                    }
                } else {
                    rootElement.html(template(showJunctionTemplateEditForm)(firstSelectedLinkProperty));
                }

            };

        };

        bindEvents();

    };
})(this);