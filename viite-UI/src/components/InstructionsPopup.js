/**
 * Shows short-lived instruction messages inside the given container.
 * Renders a lightweight popup once and fades messages in and out on demand.
 */
export function InstructionsPopup(container) {
    const element = `
      <div class="instructions-popup">
        <header></header>
      </div>
    `;
    container.append(element);

    const show = function (message, timeout) {
      container.find('.instructions-popup').find('header').text(message);
      container.find('.instructions-popup').fadeIn(200);
      setTimeout(function () {
        container.find('.instructions-popup').fadeOut(200);
      }, timeout);
    };

    container.find('.instructions-popup').fadeOut(200);

    return {
      show: show
    };
  }
