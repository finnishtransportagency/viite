(function (root) {
  root.DatePicker = function (options) {
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
            onkeypress="return event.charCode >= 48 && event.charCode <= 57 || event.charCode === 46"
          />
        </div>
      `;
    };

    const initialize = function (container) {
      const $container = $(container);
      $container.html(render());

      const $input = $container.find('input');
      
      if (config.id) {
        $input.attr('id', config.id);
      }

      const pickerOptions = {
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
      };

      if (config.minDate) {
        const minDate = moment(config.minDate, dateutil.FINNISH_DATE_FORMAT);
        pickerOptions.minDate = minDate.toDate();
      }

      if (config.maxDate) {
        const maxDate = moment(config.maxDate, dateutil.FINNISH_DATE_FORMAT);
        pickerOptions.maxDate = maxDate.toDate();
      }

      if (config.defaultDate) {
        if (config.setDefaultDate) {
          pickerOptions.defaultDate = moment(config.defaultDate, dateutil.FINNISH_DATE_FORMAT).toDate();
        }
      }

      picker = new Pikaday(pickerOptions);

      // Handle enter key press
      $input.keypress(function (e) {
        if (e.which === 13) {
          picker.hide();
          $input.blur();
        }
      });

      // Handle change events
      $input.on('input', function () {
        $(this).change();
      });

      $input.on('change', function () {
        if (config.onChange) {
          config.onChange($(this).val());
        }
      });

      return picker;
    };

    const addToElement = function (element) {
      const $element = $(element);
      
      const pickerOptions = config.defaultDate ? { defaultDate: config.defaultDate, setDefaultDate: config.setDefaultDate } : {};
      
      if (config.minDate) {
        const minDate = moment(config.minDate, dateutil.FINNISH_DATE_FORMAT);
        pickerOptions.minDate = minDate.toDate();
      }
      
      if (config.maxDate) {
        const maxDate = moment(config.maxDate, dateutil.FINNISH_DATE_FORMAT);
        pickerOptions.maxDate = maxDate.toDate();
      }
      
      picker = dateutil.addFinnishDatePicker($element, pickerOptions);

      $element.on('input', function () {
        $(this).change();
      });

      $element.on('change', function () {
        if (config.onChange) {
          config.onChange($(this).val());
        }
      });

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
      const $input = $(`#${config.id}`);
      return $input.length ? $input.val() : '';
    };

    const setValue = function (value) {
      const $input = $(`#${config.id}`);
      if ($input.length) {
        $input.val(value);
        if (picker && value) {
          const date = moment(value, dateutil.FINNISH_DATE_FORMAT);
          if (date.isValid()) {
            picker.setDate(date.toDate());
          }
        }
      }
    };

    const setDisabled = function (disabled) {
      const $input = $(`#${config.id}`);
      if ($input.length) {
        $input.prop('disabled', disabled);
      }
    };

    const destroy = function () {
      if (picker) {
        picker.destroy();
        picker = null;
      }
    };

    const getElement = function () {
      return $(`#${config.id}`);
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
  };

}(this));
