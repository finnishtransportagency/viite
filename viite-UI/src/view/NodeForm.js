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
            '       <div class="form-group-metadata">' +
            '         <p class="form-control-static asset-log-info-metadata">Solmunumero: ' + node.nodeNumber + '</p>' +
            '       </div>' +'' +
              '     <div class="form-group-metadata">' +
              '       <p class="form-control-static asset-log-info-metadata">Solumn nimi: ' + node.name + '</p>' +
              '     </div>' +
            '       <div class="form-group-metadata">' +
              '       <p class="form-control-static asset-log-info-metadata">Solmutyyppi: ' + getNodeType(node.type).description + '</p>' +
              '     </div>' +
              '     <div class="form-group-metadata">' +
              '       <p class="form-control-static asset-log-info-metadata">Alkupvm: ' + node.startDate + '</p>' +
              '     </div>' +
              '     <div class="form-group-metadata">' +
              '       <p class="form-control-static asset-log-info-metadata">Koordinaatit (P, I): ' + node.coordY + ', ' + node.coordX + '</p>' +
              '     </div>' +
              '   </div>' +
              ' </div>' +
              '<div>' +
                '<p><a id="node-point-link" class="node-points">Nayta solmukohdat</a></p>' +
                '<p><a id="junction-link" class="node-points">Nayta liittymat</a></p>' +
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