/**
 * ProjectButtons - Reusable component for project action buttons
 * 
 * Usage Example:
 * const buttons = new ProjectButtons({
 *   showValidate: true,
 *   validateVisible: true,
 *   disabled: false,
 *   disabledTitles: {
 *     recalculate: 'Custom title',
 *     changes: 'Custom title',
 *     send: 'Custom title'
 *   }
 * });
 * const html = buttons.render();
 * 
 * Configuration Options:
 * - showValidate: Boolean - Whether to show validate button (default: false)
 * - validateVisible: Boolean - Whether validate button is visible (default: false)
 * - disabled: Boolean - Whether all buttons are disabled (default: false)
 * - cssClasses: Object - Custom CSS classes for buttons
 * - buttonStates: Object - Individual button states and titles
 * - disabledTitles: Object - Custom titles for disabled buttons
 */
(function (root) {
  root.ProjectButtons = function (options) {
    // Default config ensures all keys exist even if options is empty
    const config = Object.assign({
      showValidate: false,
      validateVisible: false,
      disabled: false,
      cssClasses: {
        validate: 'validate btn btn-block btn-recalculate',
        recalculate: 'recalculate btn btn-block btn-recalculate',
        changes: 'show-changes btn btn-block btn-show-changes',
        send: 'send btn btn-block btn-send'
      },
      buttonStates: {},
      disabledTitles: {
        recalculate: 'Kaikki linkit tulee olla käsiteltyjä',
        changes: 'Projektin tulee läpäistä validoinnit',
        send: 'Hyväksy yhteenvedon jälkeen'
      }
    }, options);

    const getAttrs = (type) => {
      const state = config.buttonStates[type] || {};
      const isDisabled = config.disabled || state.disabled;
      
      // If disabled, use disabledTitle, else use state title or nothing
      const title = isDisabled ? (config.disabledTitles[type] || '') : (state.title || '');
      
      return {
        disabled: isDisabled ? ' disabled' : '',
        title: title ? ` title="${title}"` : ''
      };
    };

    return {
      render: function () {
        const rec = getAttrs('recalculate');
        const cha = getAttrs('changes');
        const snd = getAttrs('send');
        const val = getAttrs('validate');

        let validateBtn = '';
        if (config.showValidate) {
          const hidden = config.validateVisible ? '' : ' hidden="true"';
          validateBtn = `<button id="validate-button" class="${config.cssClasses.validate}"${val.disabled}${val.title}${hidden}>Validoi projekti</button>`;
        }

        return `
          ${validateBtn}
          <button id="recalculate-button" class="${config.cssClasses.recalculate}"${rec.disabled}${rec.title}>Päivitä etäisyyslukemat</button>
          <button id="changes-button" class="${config.cssClasses.changes}"${cha.disabled}${cha.title}>Avaa projektin yhteenvetotaulukko</button>
          <button id="send-button" class="${config.cssClasses.send}"${snd.disabled}${snd.title}>Hyväksy tieosoitemuutokset</button>`;
      }
    };
  };
}(window));
