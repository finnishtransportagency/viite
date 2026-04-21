// Generic confirm popup component for confirmation and alert dialogs
/* Usage:
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';

$('#remove-button').on('click', function () {
  ConfirmPopup('Are you sure?', {
    type: 'confirm',
    successCallback: onConfirm,
    closeCallback: onCancel
  });
});
*/
export function ConfirmPopup(message, options = {}) {
    const defaultOptions = {
      type: "confirm",
      okButtonLbl: 'Sulje',
      yesButtonLbl: 'Kyllä',
      noButtonLbl: 'Ei',
      okCallback: function () {},
      successCallback: function () {},
      closeCallback: function () {}
    };

    const optionsMerged = _.merge(defaultOptions, options);

    const confirmDiv = `
      <div class="modal-overlay confirm-modal" id="ConfirmationDialog">
        <div class="modal-dialog">
          <div class="content">
            ${message}
          </div>
          <div class="actions">
            <button class="btn-primary yes">${optionsMerged.yesButtonLbl}</button>
            <button class="btn-secondary no">${optionsMerged.noButtonLbl}</button>
          </div>
        </div>
      </div>`;

    const alertDiv = `
      <div class="modal-overlay confirm-modal" id="ConfirmationDialog">
        <div class="modal-dialog">
          <div class="content" style="max-height: 500px; overflow-y: scroll">
            ${message}
          </div>
          <div class="actions">
            <button class="btn-secondary ok">${optionsMerged.okButtonLbl}</button>
          </div>
        </div>
      </div>`;

    const renderConfirmDialog = function () {
      const template = optionsMerged.type === 'alert' ? alertDiv : confirmDiv;
      jQuery('.container').append(template);
    };

    const bindEvents = function () {
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

    const show = function () {
      purge();
      renderConfirmDialog();
      bindEvents();
    };

    const purge = function () {
      jQuery('#ConfirmationDialog').remove();
    };

    show();
  }
