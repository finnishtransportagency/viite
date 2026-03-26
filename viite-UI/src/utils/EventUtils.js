(function () {
  window.events = {
    spinners: {
      saving: 'node-saving',
      fetched: 'node-fetched'
    }
  };

  window.eventutil = window.eventutil || {};

  window.eventutil.bindClick = function (container, selector, callback) {
    $(container).off('click', selector).on('click', selector, function (e) {
      callback($(this), e);
    });
  };
}());

