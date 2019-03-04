window.ModalConfirm = function(insertedText) {

    // In case an exception is not properly handled, a generic message will be added.
    var detailInformation = insertedText.match("^ORA-") ?
        '<div class="content"> Tekninen virhe, Ota yhteyttä pääkäyttäjään.' +
        '</div>' : "";

    var confirmDiv =
      '<div class="modal-overlay confirm-modal">' +
      '<div class="modal-dialog">' +
      '<div class="content">' + insertedText +
      '</div>' +
      detailInformation +
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