export const events = {
  spinners: {
    saving: 'node-saving',
    fetched: 'node-fetched'
  }
};

export const eventutil = {
  bindClick: function (container, selector, callback) {
    $(container).off('click', selector).on('click', selector, function (e) {
      callback($(this), e);
    });
  }
};
