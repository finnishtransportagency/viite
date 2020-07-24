window.GenericConfirmPopup = function (message, options) {

  var defaultOptions = {
    type: "confirm",
    okButtonLbl: 'Sulje',
    yesButtonLbl: 'Kyllä',
    noButtonLbl: 'Ei',
    okCallback: function () {
    },
    successCallback: function () {
    },
    closeCallback: function () {
    }
  };

  const optionsMerged = _.merge(defaultOptions, options);

  var confirmDiv =
    '<div class="modal-overlay confirm-modal" id="genericConfirmationDialog">' +
    '<div class="modal-dialog">' +
    '<div class="content">' +
    message +
    '</div>' +
    '<div class="actions">' +
    '<button class = "btn btn-primary yes">' + optionsMerged.yesButtonLbl + '</button>' +
    '<button class = "btn btn-secondary no">' + optionsMerged.noButtonLbl + '</button>' +
    '</div>' +
    '</div>' +
    '</div>';

  var alertDiv =
    '<div class="modal-overlay confirm-modal">' +
    '<div class="modal-dialog">' +
    '<div class="content">' +
    message +
    '</div>' +
    '<div class="actions">' +
    '<button class = "btn btn-secondary ok">' + optionsMerged.okButtonLbl + '</button>' +
    '</div>' +
    '</div>' +
    '</div>';

  var renderConfirmDialog = function () {
    var template = confirmDiv;
    if (optionsMerged.type === 'alert')
      template = alertDiv;

    jQuery('.container').append(template);
  };

  var bindEvents = function () {
    jQuery('.confirm-modal .no').on('click', function () {
      purge();
      optionsMerged.closeCallback();
    });
    jQuery('.confirm-modal .yes').on('click', function () {
      purge();
      optionsMerged.successCallback();
    });
    jQuery('.confirm-modal .ok').on('click', function () {
      purge();
      optionsMerged.okCallback();
    });
  };

  var show = function () {
    purge();
    renderConfirmDialog();
    bindEvents();
  };

  var purge = function () {
    jQuery('#genericConfirmationDialog').remove();
  };

  show();
};
