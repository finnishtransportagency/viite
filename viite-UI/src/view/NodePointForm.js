(function (root) {
  root.NodePointForm = function (selectedNodePoint) {
    var formCommon = new FormCommon('node-point-');

    var nodeTemplatePicture = function () {
      return '<object type="image/svg+xml" data="images/node-point-template.svg" style="margin: 5px 5px 5px 5px"></object>';
    };

    var formButtons = function () {
      return '<div class="form form-controls">' +
        '<button class="save btn btn-edit-node-save" disabled>Tallenna</button>' +
        '<button class="cancel btn btn-edit-node-cancel" disabled>Peruuta</button>' +
        '<button class="cancel btn btn-block btn-return-list">Palaa listanäkymään</button>' +
        '<button class="send btn btn-block btn-send" disabled>Vie Tierekisteriin</button>' +
        '</div>';
    };

    var inputNumber = function (id, value) {
      return '<input type="text" class="form-control node-point-input" disabled id="' + id + '" value="' + value + '"/>';
    };

    var nodePointsInSamePlaceInfo = function (ids) {
      return '<div id="node-point-ids" class="form-group-metadata">' +
        '<p class="form-control-static asset-log-info-metadata">Kaikki aihiot tässä paikassa: ' + ids.join(', ') + '</p></div>';
    };

    var nodePointInformation = function (nodePoint) {
      return '<form id="node-point-information" class="input-unit-combination form-group form-horizontal node-point">' +
        '<div class="form-group">' +
        formCommon.addSmallLabel('TIE') + formCommon.addSmallLabel('OSA') + formCommon.addSmallLabel('ETÄISYYS') +
        '</div>' +
        '<div class="form-group">' +
        inputNumber('tie', nodePoint.roadNumber) +
        inputNumber('osa', nodePoint.roadPartNumber) +
        inputNumber('etaisyys', nodePoint.addrM) + '</div></form>';
    };

    var nodePointTemplateForm = function (nodePointTemplates) {
      var nodePointTemplate = _.sortBy(nodePointTemplates, function (nodePoint) {
        return [nodePoint.roadNumber, nodePoint.roadPartNumber, nodePoint.addrM, nodePoint.beforeAfter];
      })[0];
      eventbus.on('nodePointTemplate:open', nodePointTemplate.id);
      return _.template('' +
        '<header>' +
        formCommon.captionTitle('Solmukohta-aihion tiedot:') +
        '</header>' +

        '<div class="wrapper read-only">' +
        ' <div class="form form-horizontal form-dark">' +
        nodeTemplatePicture() +
        '   <div>' +
        '     <div class="form-group-metadata">' +
        '       <p class="form-control-static asset-log-info-metadata">Alkupvm: ' + nodePointTemplate.startDate + '</p>' +
        '     </div>' +
        '     <div class="form-group-metadata">' +
        '       <p class="form-control-static asset-log-info-metadata">Solmunro:</p>' +
        '     </div>' +
        '     <div class="form-group-metadata">' +
        '       <p class="form-control-static asset-log-info-metadata">Solmunimi:</p>' +
        '     </div>' +
        nodePointsInSamePlaceInfo(_.map(nodePointTemplates, "id")) +
        '   </div>' +
        nodePointInformation(nodePointTemplate) +
        ' </div>' +
        '</div>' +

        '<footer>' +
        formButtons() +
        '</footer>'
      );
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      rootElement.on('click', '.btn-return-list', function () {
        selectedNodePoint.closeNodePoint();
      });

      eventbus.on('selectedNodeAndJunctionPoint:close', function () {
        selectedNodePoint.closeNodePoint();
      });

      eventbus.on('nodePointTemplate:selected', function () {
        rootElement.empty();
        var templatesList = selectedNodePoint.getCurrentNodePointTemplates();

        if (!_.isEmpty(templatesList)) {
          rootElement.html(nodePointTemplateForm(templatesList));
        }

      });
    };
    bindEvents();
  };
})(this);