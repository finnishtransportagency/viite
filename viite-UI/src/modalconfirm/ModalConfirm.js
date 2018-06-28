window.ModalConfirm = function(insertedText) {

    var confirmDiv =
      '<div class="modal-overlay confirm-modal">' +
      '<div class="modal-dialog">' +
      '<div class="content">' + insertedText +
      '</div>' +
      '<div class="actions">' +
      '<button class="btn btn-secondary close">Sulje</button>' +
      '</div>' +
      '</div>' +
      '</div>';

    var renderConfirmDialog = function() {
        jQuery('.container').append(confirmDiv);
        var modal = $('.modal-dialog');
    };

    var bindEvents = function() {
        jQuery('.confirm-modal .close').on('click', function() {
            purge();
        });
    };

    var show = function() {
        purge();
        renderConfirmDialog();
        bindEvents();
    };

    var purge = function() {
        jQuery('.confirm-modal').remove();
        applicationModel.removeSpinner();
    };
    if (insertedText !== "") {
        show();
    }
};