(function(root) {
  root.SelectedNode = function(nodeCollection) {
    var current = [];
    var me = this;

    var open = function (node) {
      alert('a node has been clicked!');
    };

    return {
      open: open
    };

  };
})(this);