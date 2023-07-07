(function (dateUtils) {
  const FINNISH_DATE_FORMAT = 'D.M.YYYY';
  dateUtils.FINNISH_DATE_FORMAT = FINNISH_DATE_FORMAT;
  const FINNISH_HINT_TEXT = 'pp.kk.vvvv';
  const FINNISH_PIKADAY_I18N = {
    previousMonth: 'edellinen kuukausi',
    nextMonth: 'seuraava kuukausi',
    months: ['Tammikuu', 'Helmikuu', 'Maaliskuu', 'Huhtikuu', 'Toukokuu', 'Kes&auml;kuu', 'Hein&auml;kuu', 'Elokuu', 'Syyskuu', 'Lokakuu', 'Marraskuu', 'Joulukuu'],
    weekdays: ['sunnuntai', 'maanantai', 'tiistai', 'keskiviikko', 'torstai', 'perjantai', 'lauantai'],
    weekdaysShort: ['Su', 'Ma', 'Ti', 'Ke', 'To', 'Pe', 'La']
  };

  dateUtils.dateObjectToFinnishString = function (dateObject) {
    return moment(dateObject).format(FINNISH_DATE_FORMAT);
  };

  var dateToFinnishString = function (s) {
    return s ? moment(s, dateUtils.FINNISH_DATE_FORMAT) : null;
  };

  dateUtils.addFinnishDatePicker = function (element, additionalOptions) {
    return addPicker(jQuery(element),additionalOptions);
  };

  dateUtils.addSingleDatePicker = function (element, additionalOptions) {
    return dateUtils.addFinnishDatePicker(element, additionalOptions);
  };

  dateUtils.addSingleDatePickerWithMinDate = function (fromElement, minDate) {
    var from = dateToFinnishString(minDate);
    var datePicker = dateUtils.addFinnishDatePicker(fromElement);
    datePicker.setMinDate(from.toDate());
    datePicker.gotoToday();
    return datePicker;
  };

  function addPicker(jqueryElement, additionalOptions) {
    var basicOptions = {
      field: jqueryElement.get(0),
      format: FINNISH_DATE_FORMAT,
      firstDay: 1,
      yearRange: [1900, 2050],
      i18n: FINNISH_PIKADAY_I18N
    };

    var options = {
      ...basicOptions,
      ...additionalOptions
    };

    var picker = new Pikaday(options);

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

  dateUtils.isValidDate = function (date) {
    return date instanceof Date && !isNaN(date);
  };

  dateUtils.isDateInYearRange = function (date, minYear, maxYear) {
    return date.getFullYear() >= minYear && date.getFullYear() <= maxYear;
  };

  /** Converts date object to string "yyyy-mm-dd" */
  dateUtils.parseDateToString = function (date) {
    const dayInNumber = date.getDate();
    const day = dayInNumber < 10 ? '0' + dayInNumber.toString() : dayInNumber.toString();
    const monthInNumber = date.getMonth() + 1;
    const month = monthInNumber < 10 ? '0' + monthInNumber.toString() : monthInNumber.toString();
    const year = date.getFullYear().toString();
    return year + '-' + month + '-' + day;
  };

  /** Creates a string ("dd.mm.yyyy") from current date */
  dateUtils.getCurrentDateString = function () {
    const today = new Date();
    const dayInNumber = today.getDate();
    const day = dayInNumber < 10 ? '0' + dayInNumber.toString() : dayInNumber.toString();
    const monthInNumber = today.getMonth() + 1;
    const month = monthInNumber < 10 ? '0' + monthInNumber.toString() : monthInNumber.toString();
    const year = today.getFullYear().toString();
    return day + '.' + month + '.' + year;
  };

}(window.dateutil = window.dateutil || {}));
