/* Usage:
import { Spinner } from '@/components/spinner/Spinner.js';

const html = '<button id="fetch-data">Hae data</button>';
$('#actions').html(html);

$('#fetch-data').on('click', function () {
  Spinner.show('fetch-data');
  runSearch().finally(function () {
    Spinner.hide('fetch-data');
  });
});
*/
const AUTO_HIDE_DELAY_MS = 8000;

let autoHideTimeout = null;

function removeOverlay() {
  $('.spinner-overlay').remove();
}

function ensureOverlay() {
  if ($('.spinner-overlay').length) return;
  $('<div></div>')
    .addClass('spinner-overlay modal-overlay')
    .append($('<div></div>').addClass('spinner'))
    .appendTo($('body'));
}

function clearAutoHide() {
  if (autoHideTimeout !== null) {
    window.clearTimeout(autoHideTimeout);
    autoHideTimeout = null;
  }
}

export const Spinner = {
  show: function () {
    ensureOverlay();
    clearAutoHide();
    autoHideTimeout = window.setTimeout(function () {
      Spinner.hide();
    }, AUTO_HIDE_DELAY_MS);
  },

  hide: function () {
    clearAutoHide();
    removeOverlay();
  },

  clear: function () {
    clearAutoHide();
    removeOverlay();
  },

  isVisible: function () {
    return $('.spinner-overlay').length > 0;
  }
};