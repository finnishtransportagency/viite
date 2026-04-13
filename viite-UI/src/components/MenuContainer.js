/* This is used as a shared container for the right menu panel for both node and project menus and it provides header, body and footer sections.

Usage:
import { MenuContainer } from '@/components/MenuContainer.js';

const menu = MenuContainer('#menu-container', onClose);
menu.setHeader('<h3>Valikko</h3>');
menu.setBody(`<div>${contentHtml}</div>`);
menu.setFooter('<button class="btn-primary">Tallenna</button>');
*/

export function MenuContainer(container, onClose) {
    const $container = $(container);
    let $root = null;
    let $headerEl = null;
    let $headerContentEl = null;
    let $closeBtn = null;
    let $bodyEl = null;
    let $footerEl = null;

    const buildDOM = () => {
      $root = $container;
      $root.empty().addClass('menu-container-layout');

      $headerEl = $('<header class="menu-header"></header>').appendTo($root).hide();
      $headerContentEl = $('<div class="menu-header-content"></div>').appendTo($headerEl);
      $closeBtn = $('<button class="menu-close-btn" title="Sulje" type="button"><i class="fas fa-window-close"></i></button>')
        .appendTo($headerEl);

      if (onClose) {
        $closeBtn.on('click', onClose);
      }

      $bodyEl = $('<main class="menu-body"></main>').appendTo($root);
      $footerEl = $('<footer class="menu-footer"></footer>').appendTo($root).hide();
    };

    const setHeader = (html) => {
      if (!$headerContentEl) return;
      if (html) {
        $headerEl.show();
        $headerContentEl.html(html);
      } else {
        $headerContentEl.empty();
        $headerEl.hide();
      }
    };

    const setBody = (html) => {
      if (!$bodyEl) return;
      $bodyEl.html(html || '');
    };

    const setFooter = (html) => {
      if (!$footerEl) return;
      if (html) {
        $footerEl.html(html).show();
      } else {
        $footerEl.empty().hide();
      }
    };

    const setOnClose = (callback) => {
      if (!$closeBtn) return;
      $closeBtn.off('click');
      if (callback) {
        $closeBtn.on('click', callback);
      }
    };

    const getBody = () => $bodyEl;

    const clear = () => {
      if ($closeBtn) {
        $closeBtn.off('click');
      }

      if ($root) {
        $root.removeClass('menu-container-layout').empty();
        $root = null;
        $headerEl = null;
        $headerContentEl = null;
        $closeBtn = null;
        $bodyEl = null;
        $footerEl = null;
      }
    };

    buildDOM();

    return { setHeader, setBody, setFooter, setOnClose, getBody, clear };
  }
