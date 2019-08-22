(function (root) {
  root.NodePointForm = function (selectedNodePoint) {
    var me = this;
    var prefix = 'node-point-';
    var formCommon = new FormCommon(prefix);

    // var header = function() {
    //   return '<header>' +
    //     '<span id="close-node-point" class="rightSideSpan">Sulje <i class="fas fa-window-close"></i></span>' +
    //     '</header>';
    // };

    var caption = function(caption) {
      return '<label class="'+prefix+'caption-label">'+caption+'</label>';
    };

    var addNodeTemplatePicture = function () {
      return '<object type="image/svg+xml" data="images/node-point-template.svg"">\n' +
        '    <param name="number" value="99"/>\n' +
        '</object>';
    };

    var nodePointTemplateForm = function () {
      return _.template('' +
        caption('Solmukohta-aihion Tiedot:') +
        addNodeTemplatePicture()
      );
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      eventbus.on('nodePoint:selected', function(nodePoint) {
        rootElement.empty();

        if (!_.isEmpty(nodePoint)) {
          if (_.isEmpty(nodePoint.nodeId)) {
            rootElement.html(nodePointTemplateForm());
          }
          else {

          }

          $('#close-node-point').click(function () {
            selectedNodePoint.close();
          });
        }
      });
    };
    bindEvents();
  };
})(this);