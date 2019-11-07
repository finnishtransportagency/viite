(function (dateUtils, undefined) {
  var FINNISH_DATE_FORMAT = 'D.M.YYYY';
  dateUtils.FINNISH_DATE_FORMAT = FINNISH_DATE_FORMAT;
  var ISO_8601_DATE_FORMAT = 'YYYY-MM-DD';
  var FINNISH_HINT_TEXT = 'pp.kk.vvvv';
  var FINNISH_PIKADAY_I18N = {
    previousMonth: 'edellinen kuukausi',
    nextMonth: 'seuraava kuukausi',
    months: ['Tammikuu', 'Helmikuu', 'Maaliskuu', 'Huhtikuu', 'Toukokuu', 'Kes&auml;kuu', 'Hein&auml;kuu', 'Elokuu', 'Syyskuu', 'Lokakuu', 'Marraskuu', 'Joulukuu'],
    weekdays: ['sunnuntai', 'maanantai', 'tiistai', 'keskiviikko', 'torstai', 'perjantai', 'lauantai'],
    weekdaysShort: ['Su', 'Ma', 'Ti', 'Ke', 'To', 'Pe', 'La']
  };

  dateUtils.iso8601toFinnish = function (iso8601DateString) {
    return _.isString(iso8601DateString) ? moment(iso8601DateString, ISO_8601_DATE_FORMAT).format(FINNISH_DATE_FORMAT) : "";
  };

  dateUtils.finnishToIso8601 = function (finnishDateString) {
    return moment(finnishDateString, FINNISH_DATE_FORMAT).format(ISO_8601_DATE_FORMAT);
  };

  var dateToFinnishString = function (s) {
    return s ? moment(s, dateUtils.FINNISH_DATE_FORMAT) : null;
  };

  dateUtils.addFinnishDatePicker = function (element) {
    return addPicker(jQuery(element));
  };

  dateUtils.addFinnishDatePickerWithStartDate = function (element, startDate) {
    return addPickerWithStartDate(jQuery(element), startDate);
  };

  dateUtils.addNullableFinnishDatePicker = function (element, onSelect) {
    var elem = jQuery(element);
    var resetButton = jQuery("<div class='pikaday-footer'><div class='deselect-button'>Ei tietoa</div></div>");
    var picker = addPicker(elem, function () {
      jQuery('.pika-single').append(resetButton);
      picker.adjustPosition();
      // FIXME: Dirty hack to prevent odd behavior when clicking year and month selector.
      // Remove once we have a sane feature attribute saving method.
      jQuery('.pika-select').remove();
    }, onSelect);
    resetButton.on('click', function () {
      elem.val(null);
      elem.trigger('datechange');
      picker.hide();
      elem.blur();
    });
    return picker;
  };

  dateUtils.addDependentDatePickers = function (fromElement, toElement) {
    var from = dateToFinnishString(fromElement.val());
    var to = dateToFinnishString(toElement.val());
    var datePickers;
    var fromCallback = function () {
      datePickers.to.setMinDate(datePickers.from.getDate());
      fromElement.trigger('datechange');
    };
    var toCallback = function () {
      datePickers.from.setMaxDate(datePickers.to.getDate());
      toElement.trigger('datechange');
    };
    datePickers = {
      from: dateUtils.addNullableFinnishDatePicker(fromElement, fromCallback),
      to: dateUtils.addNullableFinnishDatePicker(toElement, toCallback)
    };
    if (to) {
      datePickers.from.setMaxDate(to.toDate());
    }
    if (from) {
      datePickers.to.setMinDate(from.toDate());
    }
  };

  dateUtils.addSingleDatePicker = function (fromElement) {
    dateUtils.addFinnishDatePicker(fromElement);
  };

  dateUtils.addSingleDatePickerWithMinDate = function (fromElement, minDate) {
    var from = dateToFinnishString(minDate);
    var datePicker = dateUtils.addFinnishDatePicker(fromElement);
    datePicker.setMinDate(from.toDate());
    datePicker.gotoToday();
    return datePicker;
  };

  dateUtils.removeDatePickersFromDom = function () {
    jQuery('.pika-single.is-bound.is-hidden').remove();
  };

  function addPicker(jqueryElement, onDraw, onSelect) {
    var picker = new Pikaday({
      field: jqueryElement.get(0),
      format: FINNISH_DATE_FORMAT,
      firstDay: 1,
      yearRange: [1950, 2050],
      onDraw: onDraw,
      onSelect: onSelect,
      i18n: FINNISH_PIKADAY_I18N
    });
    jqueryElement.keypress(function (e) {
      if (e.which === 13) { // hide on enter key press
        picker.hide();
        jqueryElement.blur();
      }
    });
    jqueryElement.attr('placeholder', FINNISH_HINT_TEXT);
    return picker;
  }

  function addPickerWithStartDate(jqueryElement, startDate, onDraw, onSelect) {
    var picker = new Pikaday({
      field: jqueryElement.get(0),
      format: FINNISH_DATE_FORMAT,
      firstDay: 1,
      yearRange: [1950, 2050],
      onDraw: onDraw,
      onSelect: onSelect,
      i18n: FINNISH_PIKADAY_I18N,
      defaultDate: moment(startDate, FINNISH_DATE_FORMAT).toDate()
    });
    jqueryElement.keypress(function (e) {
      if (e.which === 13) { // hide on enter key press
        picker.hide();
        jqueryElement.blur();
      }
    });
    jqueryElement.attr('placeholder', FINNISH_HINT_TEXT);
    return picker;
  }

  dateUtils.extractLatestModifications = function (elementsWithModificationTimestamps) {
    var newest = _.maxBy(elementsWithModificationTimestamps, function (s) {
      return moment(s.modifiedAt, "DD.MM.YYYY HH:mm:ss").valueOf() || 0;
    });
    return _.pick(newest, ['modifiedAt', 'modifiedBy']);
  };
}(window.dateutil = window.dateutil || {}));
