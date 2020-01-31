(function (root) {
  root.JunctionEditForm = function (selectedJunction, backend) {
    var formButtons = function () {
      return '<div class="form form-controls">' +
        '<button class="save btn btn-edit-junction-save" disabled>Tallenna</button>' +
        '<button class="cancel btn btn-edit-junction-cancel" disabled>Peruuta</button>' +
        '<button class="send btn btn-detach-junction" disabled>Irrota liittymä solmusta</button>' +
        '<button class="cancel btn btn-return-list-junction">Palaa listanäkymään</button>' +
        '<button class="send btn btn-send-junction" disabled>Vie Tierekisteriin</button>' +
        '</div>';
    };

    var svgJunction =
      '<object type="image/svg+xml" data="images/junction.svg" style="margin-right: 10px;"></object>';

    var svgJunctionTemplate =
      '<object type="image/svg+xml" data="images/junction-template.svg" style="margin-right: 10px; margin-top: 5px"></object>';

    var inputFieldRequired = function (labelText, id, placeholder, value, maxLength) {
      var lengthLimit = '';
      if (maxLength)
        lengthLimit = 'maxlength="' + maxLength + '"';
      return '<div class="form-group-junction-input-metadata">' +
        '<p class="form-control-static asset-junction-data">' +
        '<label class="required">' + labelText + '</label>' +
        '<input type="text" class="form-control asset-input-junction-data" id = "' + id + '"' + lengthLimit + ' placeholder = "' + placeholder + '" value="' + value + '" disabled/>' +
        '</p>' +
        '</div>';
    };

    var template = function (junctionInfo, showJunctionTemplateEditForm) {
      return _.template('' +
        '<header>' +
        title() +
        '</header>' +
        '<div class="wrapper read-only">' +
        '<table><tr>' +
        '<td>' + addPicture(showJunctionTemplateEditForm) + '</td>' +
        '<td><a id="junction-point-link" class="junction-point">Tarkastele liittymäkohtien tietoja</a></td>' +
        '</tr></table>' +
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
        inputFieldRequired('Liittymänumero', 'liittymanro', '', checkEmptyAndNullAndZero(junctionInfo.junctionNumber), 2) +
        '</div>' +
        '</div>' +
        '</div>' +
        '<footer>' +
        formButtons() +
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
      return '<span class="caption-title">Liittymän tiedot:</span>';
    };

    var addPicture = function (showJunctionTemplateEditForm) {
      if (showJunctionTemplateEditForm) {
        return svgJunctionTemplate;
      } else {
        return svgJunction;
      }
    };

    var getJunctionData = function (junctionId) {
      return backend.getJunctionInfoByJunctionId(junctionId, function (junctionInfo) {
        var showJunctionTemplateEditForm = isJunctionTemplate(junctionInfo);
        $('#feature-attributes').html(template(junctionInfo, showJunctionTemplateEditForm));
        $('[id=junction-point-link]').click(function () {
          eventbus.trigger('junctionTemplate-points:select', junctionId);
          return false;
        });
      });
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');
      rootElement.on('click', '.btn-return-list-junction', function () {
        selectedJunction.closeJunction();
      });

      eventbus.on('selectedNodeAndJunctionPoint:close', function () {
        selectedJunction.closeJunction();
      });

      eventbus.on('junctionEdit:selected', function (junctionId) {
        rootElement.empty();
        getJunctionData(junctionId);
        return false;
      });

      eventbus.on('junctionTemplate:selected', function () {
        rootElement.empty();
        var junctionTemplate = selectedJunction.getCurrentJunctionTemplate();
        var showJunctionTemplateEditForm = isJunctionTemplate(junctionTemplate);
        $('#feature-attributes').html(template(junctionTemplate, showJunctionTemplateEditForm));
        $('[id=junction-point-link]').click(function () {
          eventbus.trigger('junctionTemplate-points:select', junctionTemplate.id);
          return false;
        });
        return false;
      });
      var saveBtnDisabled = (Environment.name() === 'integration' || Environment.name() === 'production');
      if (! saveBtnDisabled ) {
        $('.btn-edit-junction-save').prop('disabled', false);
      } else {
        $('.btn-edit-junction-save').prop('disabled', true);
      }
    };

    bindEvents();

  };
})(this);