/**
 * NumberInput - Reusable component for numeric input fields
 * 
 * Provides standardized numeric input fields with number-only validation.
 * 
 * Usage Example:
 * const input = new NumberInput({
 *   id: 'roadNumber',
 *   value: '123',
 *   maxCharacters: null,
 *   customStyle: 'width: 100px',
 *   isDisabled: false
 * });
 * const html = input.render();
 * 
 * Configuration Options:
 * - id: String - HTML element id (required)
 * - value: String/Number - Initial input value
 * - maxCharacters: Number - Maximum character length
 * - customStyle: String - Custom CSS style (optional)
 * - isDisabled: Boolean - Whether input is readonly
 * - className: String - CSS class names (default: 'form-control small-input roadAddressProject')
 */
(function (root) {
  root.NumberInput = function (options) {
    const defaults = {
      id: '',
      value: '',
      customStyle: '',
      isDisabled: false,
      className: 'form-control small-input roadAddressProject'
    };

    const config = Object.assign({}, defaults, options);

    const render = function () {
      const disabled = config.isDisabled ? ' readonly="readonly" ' : '';
      const style = config.customStyle ? `style="${config.customStyle}"` : '';
      const maxCharactersAttr = config.maxCharacters ? `maxlength="${config.maxCharacters}"` : '';
      const value = _.isUndefined(config.value) ? '' : config.value;

      return [
        `<input type="text"`,
        ` ${style}`,
        ` onkeypress="return (event.charCode >= 48 && event.charCode <= 57) || (event.keyCode == 8 || event.keyCode == 9)"`,
        ` class="${config.className}"`,
        ` id="${config.id}"`,
        ` value="${value}"`,
        ` ${disabled}`,
        ` ${maxCharactersAttr}`,
        ` onclick=""/>`
      ].join('');
    };

    // Public API
    return {
      render: render
    };
  };
}(window));
