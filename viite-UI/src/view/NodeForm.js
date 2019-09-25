(function (root) {
  root.NodeForm = function (selectedNode) {
    var NodeType = LinkValues.NodeType;
    var formCommon = new FormCommon('node-');

    var formButtons = function () {
      return '<div class="form form-controls">' +
        '<button class="save btn btn-edit-node-save" disabled>Tallenna</button>' +
        '<button class="cancel btn btn-edit-node-cancel">Peruuta</button>' +
        '</div>';
    };

    var getNodeType = function (nodeValue) {
      var nodeType =  _.find(NodeType, function(type){
        return type.value == nodeValue;
      });
      return _.isUndefined(nodeType) ? NodeType.UnkownNodeType : nodeType;
    };

    var staticField = function (labelText, dataField) {
      var field;
      field = '<div class="form-group-node-metadata">' +
        '<p class="form-control-static asset-log-info-metadata">' + labelText + ' : ' + dataField + '</p>' +
        '</div>';
      return field;
    };

    var inputField = function (labelText, id, placeholder, value, maxLength) {
      var lengthLimit = '';
      if (maxLength)
        lengthLimit = 'maxlength="' + maxLength + '"';
      return '<div class="form-group-node-metadata">' +
        '<p class="form-control-static asset-node-data">' +
        '<label>' + labelText + '</label>' +
        '<input type="text" class="form-control-static asset-input-node-data" id = "' + id + '"' + lengthLimit + ' placeholder = "' + placeholder + '" value="' + value + '" disabled/>' +
        '</p>' +
        '</div>';
    };

    var nodeForm = function (nodes) {
      var node = _.sortBy(nodes, function (node) {
        return [node.nodeNumber, node.type];
      })[0];
      return _.template('' +
        '<header>' +
        formCommon.captionTitle('Solmun tiedot:') +
        '</header>' +

        '<div class="wrapper read-only">' +
        ' <div class="form form-horizontal form-dark">' +
        '   <div>' +
        staticField('Solmunumero', node.nodeNumber) +
        inputField('*Solumn nimi:', 'name', '', node.name, 32) +
        inputField('*Solmutyyppi:', 'type', '', getNodeType(node.type).description) +
        inputField('*Alkupvm    :', 'date', '', node.startDate) +
        staticField('Koordinaatit', node.coordY + ', ' + node.coordX) +
        '   </div>' +
        ' </div>' +
        '<div>' +
        '<p><a id="node-point-link" class="node-points">Näytä solmukohdat</a></p>' +
        '<p><a id="junction-link" class="node-points">Näytä liittymat</a></p>' +
        '</div>' +
        '</div>' +
        '<footer>' +
        formButtons() +
        '</footer>'
      );
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      rootElement.on('click', '.btn-edit-node-cancel', function () {
        selectedNode.close();
      });

      eventbus.on('node:selected', function () {
        rootElement.empty();
        var currentNodesList = selectedNode.getCurrentNodes();

        if (!_.isEmpty(currentNodesList)) {
          rootElement.html(nodeForm(currentNodesList));
        } else {
          selectedNode.close();
        }
      });

      $('[id=junction-link]').click(function () {
        return false;
      });
      $('[id=node-point-link]').click(function () {
        return false;
      });
    };

    bindEvents();
  };
})(this);