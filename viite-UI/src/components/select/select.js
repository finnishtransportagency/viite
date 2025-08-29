/*
This is a general selector component used for selecting one option from a list. It consists of a button and a dropdown menu, which can be single-column or multi-column.

Props (constructor argument):
- id (string, required): DOM id for the root container element
- data: {
    [columnIndex: number]: {
      columnTitle?: string, // optional
      items: Array<{ value: string|number, label: string }>
    }
  }
  Columns mapped by index (0, 1, 2, ...), each with a title and an items array
- selectedItem (string|null): Initially selected item id in the form `value-label`, or null
- value (string|number|undefined): Alternatively, provide just the item `value`; label is resolved from data
- placeholder (string): Text shown on the button when nothing is selected
- disabled (boolean)
- onSelectionChange (function): Callback as onSelectionChange(value|null, event) when selection changes
- className (string): Extra CSS classes for the container

Callable methods:
- render(): string              -> Returns component HTML (button + hidden dropdown)
- bindEvents(): void            -> Attaches event listeners; call after inserting the HTML
- setValue(value): void         -> Programmatically select by item value; updates button label and dropdown state
- getSelectedValue(): string|null       -> Returns the internal selected id `value-label` or null. Use `.split('-')[0]` for just value
- setDisabled(bool): void       -> Enables/disables the button
- updateData(newData): void     -> Replaces column data and re-renders the dropdown section.
- getElement(): HTMLElement?    -> Returns the root element by `id`.
- config: object                -> Exposes current configuration for advanced use.

Example: Single column

```
const targetSelector = new Selector({
  id: 'targetValue',
  placeholder: 'Valitse hakukohde...',
  value: 'Tracks',
  data: {
    0: {
      columnTitle: 'Hakukohde',
      items: [
        { value: 'Tracks', label: 'Ajoradat' },
        { value: 'RoadParts', label: 'Tieosat' },
        { value: 'Nodes', label: 'Solmut' },
        { value: 'Junctions', label: 'Liittymät' },
        { value: 'RoadNames', label: 'Tiennimet' }
      ]
    }
  }
});

return `<div>${targetSelector.render()}</div>`;
```

Example: Multi-column

```
const selector = new Selector({
  id: 'roadAddress',
  data: {
    0: {
      columnTitle: 'ELY',
      items: [ { value: 1, label: 'ELY 1' }, { value: 2, label: 'ELY 2' } ]
    },
    1: {
      columnTitle: 'Target',
      items: [ { value: 'A', label: 'Roads' }, { value: 'B', label: 'Nodes' } ]
    }
  },
  placeholder: 'Valitse...',
  onSelectionChange: (value) => console.log('Changed to', value)
});

return `<div>${selector.render()}</div>`;
```
*/

(function (root) {
  root.Selector = function (props) {
    const defaults = {
      id: '',
      data: {},
      selectedItem: null,
      placeholder: 'Valitse...',
      disabled: false,
      onSelectionChange: null,
      className: '',
      // Optional width for the control. Accepts number (px) or string (any CSS unit)
      width: null
    };

    const config = Object.assign({}, defaults, props);

    // Trigger error if passed data or id are invalid
    function validateConfig() {
      if (!config.id) {
        throw new Error('Selector: id is required');
      }
      if (typeof config.data !== 'object') {
        throw new Error('Selector: data must be an object');
      }
    }

    function createButton(selectedLabel) {
      const disabledAttr = config.disabled ? ' disabled' : '';
      const widthStyle = (config.width !== null && config.width !== undefined)
        ? ` style="width: ${typeof config.width === 'number' ? config.width + 'px' : config.width};"`
        : '';
      return `
        <button id="${config.id}-button" class="mcs-button"${widthStyle} ${disabledAttr}>
          <span class="mcs-label">${selectedLabel || config.placeholder}</span>
          <span class="mcs-arrow">&#9662;</span>
        </button>
      `;
    }

    function createDropdown() {
      const columns = Object.keys(config.data).map(Number).sort();
      let html = `<div id="${config.id}-dropdown" class="mcs-dropdown hidden"><div class="mcs-columns">`;

      columns.forEach((colIndex, idx) => {
        const colData = config.data[colIndex];
        if (!colData) return;

        html += `<div class="mcs-column">`;
        if (colData.columnTitle) {
          html += `<div class="mcs-column-title">${colData.columnTitle}</div>`;
        }

        colData.items.forEach(item => {
          const itemId = `${item.value}-${item.label}`;
          const selected = itemId === config.selectedItem ? ' selected' : '';
          html += `
            <div class="mcs-item${selected}" data-id="${itemId}">
              <span class="mcs-circle${selected ? ' filled' : ''}"></span>
              <span class="mcs-item-label">${item.label}</span>
            </div>
          `;
        });

        html += `</div>`;
      });

      html += `</div></div>`;
      return html;
    }

    function createComponent() {
      validateConfig();

      let selectedLabel = null;
      const allItems = Object.values(config.data).flatMap(c => c.items || []);

      if (config.value) {
        const found = allItems.find(it => it.value === config.value);
        if (found) {
          selectedLabel = found.label;
          config.selectedItem = `${found.value}-${found.label}`;
        }
      } else if (config.selectedItem) {
        const found = allItems.find(it => `${it.value}-${it.label}` === config.selectedItem);
        if (found) {
          selectedLabel = found.label;
          config.value = found.value;
        }
      }

      const containerWidthStyle = (config.width !== null && config.width !== undefined)
        ? ` style="width: ${typeof config.width === 'number' ? config.width + 'px' : config.width};"`
        : '';

      return `
        <div id="${config.id}" class="mcs-container ${config.className}"${containerWidthStyle}>
          ${createButton(selectedLabel)}
          ${createDropdown()}
        </div>
      `;
    }

    function bindEvents() {
      // Use event delegation to handle clicks on the button and menu items
      setupEventDelegation();
    }

    function setupEventDelegation() {
      // Remove existing listener if any
      if (config._globalClickHandler) {
        document.removeEventListener('click', config._globalClickHandler);
      }

      // Create global click handler
      config._globalClickHandler = function(e) {
        const rootEl = document.getElementById(config.id);
        if (!rootEl) return;

        const button = rootEl.querySelector('.mcs-button');
        const dropdown = rootEl.querySelector('.mcs-dropdown');
        const label = rootEl.querySelector('.mcs-label');

        if (!button || !dropdown || !label) return;

        // Handle button clicks
        if (button.contains(e.target)) {
          e.preventDefault();
          e.stopPropagation();
          dropdown.classList.toggle('hidden');
          button.classList.toggle('open', !dropdown.classList.contains('hidden'));
          return;
        }

        // Handle item clicks
        const itemEl = e.target.closest('.mcs-item');
        if (itemEl && dropdown.contains(itemEl)) {
          e.preventDefault();
          e.stopPropagation();
          
          const itemId = itemEl.getAttribute('data-id');
          const itemValue = itemId.split('-')[0];

          // Update selection state
          dropdown.querySelectorAll('.mcs-item').forEach(item => {
            item.classList.remove('selected');
            const circle = item.querySelector('.mcs-circle');
            if (circle) circle.classList.remove('filled');
          });

          const isSame = config.selectedItem === itemId;
          config.selectedItem = isSame ? null : itemId;
          config.value = isSame ? null : itemValue;
          
          if (config.selectedItem) {
            itemEl.classList.add('selected');
            const circle = itemEl.querySelector('.mcs-circle');
            if (circle) circle.classList.add('filled');
            label.textContent = itemEl.querySelector('.mcs-item-label').textContent;
          } else {
            label.textContent = config.placeholder;
          }

          if (config.onSelectionChange) {
            config.onSelectionChange(config.value, e);
          }

          dropdown.classList.add('hidden');
          button.classList.remove('open');
          return;
        }

        // Close menu when clicking outside
        if (!rootEl.contains(e.target)) {
          dropdown.classList.add('hidden');
          button.classList.remove('open');
        }
      };

      // Add global event listener
      document.addEventListener('click', config._globalClickHandler);
    }

    function setValue(value) {
      const allItems = Object.values(config.data).flatMap(c => c.items || []);
      const found = allItems.find(it => it.value == value);

      if (found) {
        config.selectedItem = `${found.value}-${found.label}`;
        config.value = found.value;
      } else {
        config.selectedItem = null;
        config.value = null;
      }

      const el = document.getElementById(config.id);
      if (el) {
        const label = el.querySelector('.mcs-label');
        const button = el.querySelector('.mcs-button');
        label.textContent = found ? found.label : config.placeholder;

        // Update dropdown items state
        const dropdown = el.querySelector('.mcs-dropdown');
        dropdown.querySelectorAll('.mcs-item').forEach(item => {
          const isSelected = item.getAttribute('data-id') === config.selectedItem;
          item.classList.toggle('selected', isSelected);
          const circle = item.querySelector('.mcs-circle');
          if (circle) circle.classList.toggle('filled', isSelected);
        });
      }
    }

    function getSelectedValue() {
      return config.value;
    }

    // This can be used to disable the selector (not used currently, so not tested)
    function setDisabled(disabled) {
      config.disabled = disabled;
      const el = document.getElementById(config.id);
      if (el) {
        const button = el.querySelector('.mcs-button');
        button.disabled = disabled;
      }
    }

    // This can be used to change the listed items and re-render the dropdown (not used currently, so not tested)
    function updateData(newData) {
      config.data = newData;
      const el = document.getElementById(config.id);
      if (el) {
        const dropdown = el.querySelector('.mcs-dropdown');
        dropdown.outerHTML = createDropdown();
      }
    }

    function getElement() {
      return document.getElementById(config.id);
    }

    return {
      render: createComponent,
      bindEvents: bindEvents,
      setValue: setValue,
      getSelectedValue: getSelectedValue,
      setDisabled: setDisabled,
      updateData: updateData,
      getElement: getElement,
      config: config
    };
  };
  // Backward compatibility: keep old name available
  if (!root.MultiColumnSelector) {
    root.MultiColumnSelector = root.Selector;
  }
})(this);
