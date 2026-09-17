
/*
 * MultiColumnDropdown
 *
 * Creates a multi-column dropdown with multiple selectable items.
 *
 * Usage:
 *
 * const dateTargetSelector = MultiColumnDropdown({
 *   placeholder: 'Valitse Elinvoimakeskus / ELY',
 *   width: 240,
 *   data: {
 *     0: {
 *       columnTitle: 'ELY',
 *       items: [
 *         { value: 1, label: 'ELY 1' },
 *         { value: 2, label: 'ELY 2', selected: true }
 *       ]
 *     },
 *     1: {
 *       columnTitle: 'Target',
 *       items: [
 *         { value: 'A', label: 'Roads' },
 *         { value: 'B', label: 'Nodes' }
 *       ]
 *     }
 *   }
 * });
 *
 * Use in a template:
 *
 * const html = `
 *   <div class="input-container">
 *     <label>Rajausperuste</label>
 *     ${dateTargetSelector.render()}
 *   </div>
 * `;
 *
 * Get selected values:
 *
 * dateTargetSelector.getValue();
 *
 * Set selected values:
 *
 * dateTargetSelector.setValue([1, 'A']);
 *
 * Clear selection:
 *
 * dateTargetSelector.clear();
 *
 * DOM events (open/close, item selection) are bound automatically - no setup call needed,
 * even if the markup is inserted into the document afterwards. To react to selection
 * changes, register a callback:
 *
 * dateTargetSelector.setOnChange(function (values) { ... }); 
 * */

export function MultiColumnDropdown(options) {
  const id = options.id || 'multiColumnDropdown-' + Date.now();
  const placeholder = options.placeholder || 'Valitse';
  const width = options.width || '100%';
  const data = options.data || {};
  const multiple = Boolean(options.multiple);
  let onChange = options.onChange;

  let selectedValues = new Set();
  Object.keys(data).forEach(function (key) {
    data[key].items.forEach(function (item) {
      if (item.selected) selectedValues.add(String(item.value));
    });
  });

  function render() {
    const columns = Object.keys(data).map(function(key) {
      const column = data[key];
      const title = column.columnTitle
        ? `<div class="modern-column-title">${column.columnTitle}</div>`
        : '';

      const items = column.items.map(function(item) {
        const selected = selectedValues.has(String(item.value));

        return `
          <div
            class="modern-item${selected ? ' selected' : ''}"
            data-value="${item.value}"
          >
            <input
              type="checkbox"
              class="modern-checkbox"
              ${selected ? 'checked' : ''}
            >
            <span class="modern-item-label">${item.label}</span>
          </div>
        `;
      }).join('');

      return `
        <div class="modern-column">
          ${title}
          ${items}
        </div>
      `;
    }).join('');

    return `
      <div
        id="${id}"
        class="modern-container"
        style="width: ${typeof width === 'number' ? width + 'px' : width}"
      >
        <button type="button" class="modern-button">
          <span class="modern-label">${getLabel()}</span>
          <img src="images/chevron-down.svg" class="chevron" alt="">
        </button>

        <div class="modern-dropdown hidden">
          <div class="modern-columns">
            ${columns}
          </div>
        </div>
      </div>
    `;
  }

  function getLabel() {
    const selectedLabels = [];

    Object.keys(data).forEach(function(key) {
      data[key].items.forEach(function(item) {
        if (selectedValues.has(String(item.value))) {
          selectedLabels.push(item.label);
        }
      });
    });

    return selectedLabels.length
      ? selectedLabels.join(', ')
      : placeholder;
  }

  function setSelected(value, selected) {
    if (selected) {
      selectedValues.add(String(value));
    } else {
      selectedValues.delete(String(value));
    }
  }

  function getValue() {
    return Array.from(selectedValues);
  }

  function setValue(values) {
    selectedValues = new Set(
      (values || []).map(function(value) {
        return String(value);
      })
    );
  }

  function clear() {
    selectedValues.clear();
  }

  function setOnChange(fn) {
    onChange = fn;
  }

  // Bound once via document-level delegation, so it keeps working even though the markup
  // returned by render() is (re)inserted into the DOM after this constructor runs.
  function bindEvents() {
    const ns = `.multiColumnDropdown-${id}`;

    $(document).off(`click${ns}-button`).on(`click${ns}-button`, `#${id} .modern-button`, function (e) {
      e.stopPropagation();
      $(`#${id} .modern-dropdown`).toggleClass('hidden');
      $(this).toggleClass('open');
    });

    $(document).off(`click${ns}-item`).on(`click${ns}-item`, `#${id} .modern-item`, function (e) {
      e.stopPropagation();
      const value = $(this).attr('data-value');
      const isSelected = selectedValues.has(value);

      if (multiple) {
        setSelected(value, !isSelected);
      } else {
        setValue([value]);
      }

      const wasOpen = !$(`#${id} .modern-dropdown`).hasClass('hidden');
      const $new = $(render());
      if (wasOpen) {
        $new.find('.modern-dropdown').removeClass('hidden');
        $new.find('.modern-button').addClass('open');
      }
      $(`#${id}`).replaceWith($new);

      if (!multiple) {
        $(`#${id} .modern-dropdown`).addClass('hidden');
        $(`#${id} .modern-button`).removeClass('open');
      }
      if (onChange) onChange(getValue());
    });

    $(document).off(`click${ns}-outside`).on(`click${ns}-outside`, function () {
      $(`#${id} .modern-dropdown`).addClass('hidden');
      $(`#${id} .modern-button`).removeClass('open');
    });
  }

  bindEvents();

  return {
    render: render,
    getValue: getValue,
    setValue: setValue,
    clear: clear,
    setSelected: setSelected,
    setOnChange: setOnChange
  };
}

