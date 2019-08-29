(function (root) {
  root.NodePointForm = function (selectedNodePoint) {
    var formCommon = new FormCommon('node-point-');

    var addNodeTemplatePicture = function () {
      return '<object type="image/svg+xml" data="images/node-point-template.svg" style="margin: 5px 5px 5px 5px">\n' +
        '    <param name="number" value="99"/>\n' +
        '</object>';
    };

    var addNodePointFormButtons = function () {
      return '<div class="form form-controls">' +
        '<button class="save btn btn-edit-node-save" disabled>Tallenna</button>' +
        '<button class="cancel btn btn-edit-node-cancel" disabled>Peruuta</button>' +
        '<button class="cancel btn btn-block btn-return-list">Palaa lista-näkymään</button>' +
        '<button class="send btn btn-block btn-send" disabled>Vie Tierekisteriin</button>' +
        '</div>';
    };

    var inputNumber = function (id, value) {
      return '<input type="text" class="form-control node-point-input" disabled id="' + id + '" value="' + value + '"/>';
    };

    var addNodePointInformation = function (nodePoint) {
      return '<div class="form-group">' +
          formCommon.addSmallLabel('TIE') + formCommon.addSmallLabel('OSA') + formCommon.addSmallLabel('ETÄISYYS') +
          '</div>' +
          '<div class="form-group">' +
          inputNumber('tie', nodePoint.roadNumber) +
          inputNumber('osa',  nodePoint.roadPartNumber) +
          inputNumber('etaisyys', nodePoint.addrM) +
          '</div>';
    };

    var nodePointTemplateForm = function (nodePointTemplate) {
      return _.template('' +
        '<header>' +
        formCommon.captionTitle('Solmukohta-aihion tiedot:') +
        '</header>' +

        '<div class="wrapper read-only">' +
        ' <div class="form form-horizontal form-dark">' +
        addNodeTemplatePicture() +
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
        '   </div>' +
        '   <form id="node-point-information" class="input-unit-combination form-group form-horizontal node-point"></form>' +
        ' </div>' +
        '</div>' +

        '<footer>' +
        addNodePointFormButtons() +
        '</footer>'
      );
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      rootElement.on('click', '.btn-return-list', function () {
        selectedNodePoint.close();
      });

      eventbus.on('nodePoint:selected', function(nodePoint) {
        rootElement.empty();

        if (!_.isEmpty(nodePoint)) {
          if (_.isEmpty(nodePoint.nodeId)) {
            rootElement.html(nodePointTemplateForm(selectedNodePoint.getCurrentNodePointTemplate()));
          }
          else {
            // build form for node point.
          }

          $('#close-node-point').click(function () {
            selectedNodePoint.close();
          });
        }
      });

      eventbus.on('nodePoint:fetch', function (nodePoints) {
        applicationModel.removeSpinner();
        selectedNodePoint.setGroupOfNodePoints(nodePoints);
        $('#node-point-information').html(addNodePointInformation(selectedNodePoint.getLowestRoadNumber()));
      });
    };
    bindEvents();
  };
})(this);