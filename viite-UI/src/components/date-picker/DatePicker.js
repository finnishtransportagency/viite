import { dateutil } from '@utils/DateUtils.js';

/* Usage:
import { DatePicker } from '@/components/date-picker/DatePicker.js';

const startDatePicker = DatePicker({
  id: 'startDate',
  onChange: function (value) {
    console.log('Valittu paivamaara:', value);
  }
});

const html = `
  <div id="start-date-slot">
    ${startDatePicker.render()}
  </div>
`;

$('#form-container').html(html);
startDatePicker.initialize('#start-date-slot');
*/
export function DatePicker(options) {
    const defaults = {
      id: '',
      className: 'form-control date-picker-input',
      value: '',
      placeholder: 'pp.kk.vvvv',
      minDate: null,
      maxDate: null,
      defaultDate: null,
      setDefaultDate: false,
      disabled: false,
      required: false,
      onChange: null,
      containerClass: 'date-picker-container'
    };

    const config = { ...defaults, ...options };
    let picker = null;
    let $inputRef = null; // Reference to the input element for value manipulation to avoid targetting other date pickers if multiple instances are used on the same page

    // Build minDate/maxDate options from config using the provided moment instance
    const buildDateRange = function (momentLib) {
      const opts = {};
      if (config.minDate) {
        opts.minDate = momentLib(config.minDate, dateutil.FINNISH_DATE_FORMAT).toDate();
      }
      if (config.maxDate) {
        opts.maxDate = momentLib(config.maxDate, dateutil.FINNISH_DATE_FORMAT).toDate();
      }
      return opts;
    };

    // Attach input/change event listeners to an element
    const bindChangeEvents = function ($el) {
      $el.on('input', function () {
        $(this).change();
      });
      $el.on('change', function () {
        if (config.onChange) {
          config.onChange($(this).val());
        }
      });
    };

    const render = function () {
      const disabledAttr = config.disabled ? 'disabled' : '';
      const requiredAttr = config.required ? 'required' : '';
      const idAttr = config.id ? `id="${config.id}"` : '';

      return `
        <div class="${config.containerClass}">
          <input
            autocomplete="off"
            type="text" 
            class="${config.className}" 
            ${idAttr}
            placeholder="${config.placeholder}" 
            value="${config.value}"
            ${disabledAttr}
            ${requiredAttr}
          />
        </div>
      `;
    };

    const initialize = function (container) {
      const momentLib = window.moment;
      const PikadayCtor = window.Pikaday;
      const $container = $(container);
      $container.html(render());

      const $input = $container.find('input');
      $inputRef = $input;
      
      if (config.id) {
        $input.attr('id', config.id);
      }

      // Restrict input to digits and dots
      $input.on('keypress', function (e) {
        const char = String.fromCharCode(e.which);
        if (!((/[\d.]/).test(char)) && e.which !== 13) {
          e.preventDefault();
        }
        if (e.which === 13) {
          picker.hide();
          $input.blur();
        }
      });

      const pickerOptions = Object.assign({
        field: $input.get(0),
        format: dateutil.FINNISH_DATE_FORMAT,
        firstDay: 1,
        yearRange: [1900, 2050],
        i18n: {
          previousMonth: 'edellinen kuukausi',
          nextMonth: 'seuraava kuukausi',
          months: ['Tammikuu', 'Helmikuu', 'Maaliskuu', 'Huhtikuu', 'Toukokuu', 'Kesäkuu', 'Heinäkuu', 'Elokuu', 'Syyskuu', 'Lokakuu', 'Marraskuu', 'Joulukuu'],
          weekdays: ['sunnuntai', 'maanantai', 'tiistai', 'keskiviikko', 'torstai', 'perjantai', 'lauantai'],
          weekdaysShort: ['Su', 'Ma', 'Ti', 'Ke', 'To', 'Pe', 'La']
        }
      }, buildDateRange(momentLib));

      if (config.defaultDate && config.setDefaultDate) {
        pickerOptions.defaultDate = momentLib(config.defaultDate, dateutil.FINNISH_DATE_FORMAT).toDate();
      }

      picker = new PikadayCtor(pickerOptions);

      bindChangeEvents($input);

      return picker;
    };

    const addToElement = function (element) {
      const momentLib = window.moment;
      const $element = $(element);
      $inputRef = $element;
      
      const pickerOptions = Object.assign(
        config.defaultDate ? { defaultDate: config.defaultDate, setDefaultDate: config.setDefaultDate } : {},
        buildDateRange(momentLib)
      );
      
      picker = dateutil.addFinnishDatePicker($element, pickerOptions);

      bindChangeEvents($element);

      return picker;
    };

    const getDate = function () {
      return picker ? picker.getDate() : null;
    };

    const setDate = function (date) {
      if (picker) {
        picker.setDate(date);
      }
    };

    const getValue = function () {
      return $inputRef && $inputRef.length ? $inputRef.val() : '';
    };

    const setValue = function (value) {
      if ($inputRef && $inputRef.length) {
        const momentLib = window.moment;
        $inputRef.val(value);
        if (picker && value) {
          const date = momentLib(value, dateutil.FINNISH_DATE_FORMAT);
          if (date.isValid()) {
            picker.setDate(date.toDate());
          }
        }
      }
    };

    const setDisabled = function (disabled) {
      if ($inputRef && $inputRef.length) {
        $inputRef.prop('disabled', disabled);
      }
    };

    const destroy = function () {
      if (picker) {
        picker.destroy();
        picker = null;
      }
    };

    const getElement = function () {
      return $inputRef || $();
    };

    return {
      render,
      initialize,
      addToElement,
      getDate,
      setDate,
      getValue,
      setValue,
      setDisabled,
      destroy,
      getElement
    };
  }

