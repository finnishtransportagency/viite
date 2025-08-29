/**
 * ViiteSelect - Reusable dropdown select component
 *
 * Styling and structure:
 * - Matches existing UI: wraps with <div class="input-container"> and <label class="control-label-small"> when label is provided.
 * - Renders only <select> when no label is provided.
 *
 * Props (constructor argument):
 * - id (string, required): DOM id for the <select> element.
 * - label (string): Optional field label text.
 * - props (Array<string|{value: string|number, text: string}>>): Option items. Strings are used as both value and text.
 * - value (string|number): Initially selected value.
 * - placeholder (string): First empty option label.
 * - required (boolean): Used in form validation.
 * - disabled (boolean): Disables the component.
 * - onChange (function): Called as onChange(value, event) when selection changes.
 * - className (string): Extra CSS classes for the <select>.
 *
 * Public API:
 * - render(): string            -> Returns HTML for this component.
 * - bindEvents(container): void -> Binds events; pass a DOM node that contains the rendered HTML.
 * - updateprops(newProps): void -> Replaces option items and updates DOM if mounted.
 * - setValue(value): void       -> Sets selected value (and updates DOM if mounted).
 * - getValue(): string          -> Returns current value (from DOM if mounted, else from config).
 * - setDisabled(bool): void     -> Enables/disables the control (DOM if mounted).
 * - getElement(): HTMLElement?  -> Returns the <select> DOM element by id.
 *
 * Helper creators:
 * - ViiteSelect.createElyprops(): builds ELY items from ViiteEnumerations.ElyCodes.
 * - ViiteSelect.createTargetprops(): builds target choices (Tracks, RoadParts, ...).
 *
 * Usage example:
 *   const elySelect = new ViiteSelect({
 *     id: 'roadAddrInputEly',
 *     label: 'Ely',
 *     props: ViiteSelect.createElyprops(),
 *     value: '',
 *     onChange: (val) => console.log('ELY changed to', val)
 *   });
 *   
 *   const container = document.getElementById('roadAddressBrowser');
 *   const html = elySelect.render();
 *   // e.g., someContainer.innerHTML = html; or $(someContainer).replaceWith(html);
 *   // Then bind events within the container that holds the rendered markup
 *   elySelect.bindEvents(container);
 */

// TODO: Apply select component to all select elements in the codebase to improve maintainability and avoid repetition
(function (root) {
  // Constructor: creates a dropdown component instance with given props
  root.ViiteSelect = function (props) {
    const defaults = {
      id: '',
      label: '',
      props: [],
      value: '',
      placeholder: '--',
      required: false,
      disabled: false,
      onChange: null,
      className: ''
    };

    const config = Object.assign({}, defaults, props);

    // Ensures required props are present and correctly typed before rendering
    function validateConfig() {
      if (!config.id) {
        throw new Error('ViiteSelect: id is required');
      }
      if (!Array.isArray(config.props)) {
        throw new Error('ViiteSelect: props must be an array');
      }
    }

    // Builds the <option> elements HTML from provided items (and placeholder)
    function createOptionElements() {
      let propsHtml = '';

      // Add placeholder option if specified
      if (config.placeholder) {
        propsHtml += `<option value="">${config.placeholder}</option>`;
      }

      // Add all props
      config.props.forEach(option => {
        const value = typeof option === 'object' ? option.value : option;
        const text = typeof option === 'object' ? option.text : option;
        const selected = value === config.value ? ' selected' : '';
        propsHtml += `<option value="${value}"${selected}>${text}</option>`;
      });

      return propsHtml;
    }

    // Creates the <select> element HTML with attributes (required, disabled, classes)
    function createSelectElement() {
      const requiredAttr = config.required ? ' required' : '';
      const disabledAttr = config.disabled ? ' disabled' : '';
      const classAttr = config.className ? ` class="${config.className}"` : '';

      return `<select id="${config.id}"${classAttr}${requiredAttr}${disabledAttr}>${createOptionElements()}</select>`;
    }

    // Returns component HTML
    function createComponent() {
      validateConfig();

      if (config.label) {
        return `<div class="select-container">
                    <label class="control-label-small">${config.label}</label>
                    ${createSelectElement()}
                </div>`;
      } else {
        return createSelectElement();
      }
    }

    // Make the onChange event work by listening user input
    function bindEvents(container) {
      const selectElement = container.querySelector(`#${config.id}`);
      if (selectElement && config.onChange) {
        selectElement.addEventListener('change', function (event) {
          config.onChange(event.target.value, event);
        });
      }
    }

    // Replaces current props
    function updateprops(newprops) {
      config.props = newprops;
      const selectElement = document.getElementById(config.id);
      if (selectElement) {
        selectElement.innerHTML = createOptionElements();
      }
    }

    // Sets selected value both in config and live DOM
    function setValue(value) {
      config.value = value;
      const selectElement = document.getElementById(config.id);
      if (selectElement) {
        selectElement.value = value;
      }
    }

    // Returns selected value
    function getValue() {
      const selectElement = document.getElementById(config.id);
      return selectElement ? selectElement.value : config.value;
    }

    function setDisabled(disabled) {
      config.disabled = disabled;
      const selectElement = document.getElementById(config.id);
      if (selectElement) {
        selectElement.disabled = disabled;
      }
    }

    // Returns element id so it can be used to access the element
    function getElement() {
      return document.getElementById(config.id);
    }

    return {
      render: createComponent,
      bindEvents: bindEvents,
      updateprops: updateprops,
      setValue: setValue,
      getValue: getValue,
      setDisabled: setDisabled,
      getElement: getElement,
      config: config
    };
  };

  // Helper: produce ELY dropdown items from ViiteEnumerations.ElyCodes
  root.ViiteSelect.createElyprops = function () {
    const props = [{ value: '', text: '--' }];

    if (typeof ViiteEnumerations !== 'undefined' && ViiteEnumerations.ElyCodes) {
      for (const ely in ViiteEnumerations.ElyCodes) {
        if (Object.prototype.hasOwnProperty.call(ViiteEnumerations.ElyCodes, ely)) {
          const elyData = ViiteEnumerations.ElyCodes[ely];
          props.push({
            value: elyData.value,
            text: `${elyData.value}(${elyData.shortName})`
          });
        }
      }
    }

    return props;
  };

  // Helper: produce fixed target choices used in the road address browser
  root.ViiteSelect.createTargetprops = function () {
    return [
      { value: 'Tracks', text: 'Ajoradat' },
      { value: 'RoadParts', text: 'Tieosat' },
      { value: 'Nodes', text: 'Solmut' },
      { value: 'Junctions', text: 'Liittymät' },
      { value: 'RoadNames', text: 'Tiennimet' }
    ];
  };

}(this));
