const DEFAULT_TOKEN = '__default__';
const AUTO_HIDE_DELAY_MS = 8000;

const activeTokens = new Set();
const tokenTimeouts = new Map();

function getToken(token) {
  return typeof token === 'undefined' ? DEFAULT_TOKEN : token;
}

function removeOverlay() {
  $('.spinner-overlay').remove();
}

function ensureOverlay() {
  if ($('.spinner-overlay').length) {
    return;
  }

  const $spinnerOverlay = $('<div></div>')
    .addClass('spinner-overlay modal-overlay')
    .append($('<div></div>').addClass('spinner'));

  $('body').append($spinnerOverlay);
}

function clearTokenTimeout(token) {
  const timeoutId = tokenTimeouts.get(token);
  if (typeof timeoutId === 'undefined') {
    return;
  }

  window.clearTimeout(timeoutId);
  tokenTimeouts.delete(token);
}

function scheduleAutoHide(token) {
  clearTokenTimeout(token);
  tokenTimeouts.set(token, window.setTimeout(function () {
    Spinner.hide(token);
  }, AUTO_HIDE_DELAY_MS));
}

export const Spinner = {
  show: function (token) {
    const resolvedToken = getToken(token);
    activeTokens.add(resolvedToken);
    ensureOverlay();
    scheduleAutoHide(resolvedToken);
  },

  hide: function (token) {
    const resolvedToken = getToken(token);
    clearTokenTimeout(resolvedToken);
    activeTokens.delete(resolvedToken);

    if (activeTokens.size === 0) {
      removeOverlay();
    }
  },

  clear: function () {
    tokenTimeouts.forEach(function (timeoutId) {
      window.clearTimeout(timeoutId);
    });
    tokenTimeouts.clear();
    activeTokens.clear();
    removeOverlay();
  },

  isVisible: function () {
    return activeTokens.size > 0 || $('.spinner-overlay').length > 0;
  }
};