import { button } from '@components/button/Button.js';

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

    const renderConfirmDialog = function () {
      let template;
      if (optionsMerged.type === 'alert') {
        template = `
          <div class="modal-overlay confirm-modal" id="ConfirmationDialog">
            <div class="modal-dialog">
              <div class="content confirm-alert-scrollable">
                ${message}
              </div>
              <div class="actions">
                ${button({ id: 'confirm-popup-ok', label: optionsMerged.okButtonLbl, className: 'btn-secondary ok', onClick: () => { purge(); optionsMerged.okCallback(); } })}
              </div>
            </div>
          </div>`;
      } else {
        template = `
          <div class="modal-overlay confirm-modal" id="ConfirmationDialog">
            <div class="modal-dialog">
              <div class="content">
                ${message}
              </div>
              <div class="actions">
                ${button({ id: 'confirm-popup-yes', label: optionsMerged.yesButtonLbl, className: 'btn-primary yes', onClick: () => { purge(); optionsMerged.successCallback(); } })}
                ${button({ id: 'confirm-popup-no', label: optionsMerged.noButtonLbl, className: 'btn-secondary no', onClick: () => { purge(); optionsMerged.closeCallback(); } })}
              </div>
            </div>
          </div>`;
      }
      jQuery('.container').append(template);
    };

    const show = function () {
      purge();
      renderConfirmDialog();
    };

    const purge = function () {
      jQuery('#ConfirmationDialog').remove();
    };

    show();
  }
