/**
 * Centralizes Finnish date parsing, formatting, and Pikaday setup for the UI.
 * Exposes helpers for converting between strings, Date objects, and modification timestamps.
 */
export const dateutil = {};
  const momentLib = window.moment;

  function getPikaday() {
    if (!window.Pikaday) {
      throw new Error('Pikaday is required for date picker functionality.');
    }
    return window.Pikaday;
  }

  if (!momentLib) {
    throw new Error('Moment is required for date utilities.');
  }

  const FINNISH_DATE_FORMAT = 'D.M.YYYY';
  dateutil.FINNISH_DATE_FORMAT = FINNISH_DATE_FORMAT;
  const FINNISH_HINT_TEXT = 'pp.kk.vvvv';
  const FINNISH_PIKADAY_I18N = {
    previousMonth: 'edellinen kuukausi',
    nextMonth: 'seuraava kuukausi',
    months: ['Tammikuu', 'Helmikuu', 'Maaliskuu', 'Huhtikuu', 'Toukokuu', 'Kes&auml;kuu', 'Hein&auml;kuu', 'Elokuu', 'Syyskuu', 'Lokakuu', 'Marraskuu', 'Joulukuu'],
    weekdays: ['sunnuntai', 'maanantai', 'tiistai', 'keskiviikko', 'torstai', 'perjantai', 'lauantai'],
    weekdaysShort: ['Su', 'Ma', 'Ti', 'Ke', 'To', 'Pe', 'La']
  };

  dateutil.dateObjectToFinnishString = function (dateObject) {
    return momentLib(dateObject).format(FINNISH_DATE_FORMAT);
  };


  dateutil.isFinnishDateString = function (dateString) {
    // Regular expression to match date format with day 1-31 and month 1-12
    const regex = /^(?<day>0?[1-9]|[12]\d|3[01])\.(?<month>0?[1-9]|1[0-2])\.(?<year>\d{4})$/;
    return regex.test(dateString);
  };

  dateutil.addFinnishDatePicker = function (element, additionalOptions) {
    return addPicker(jQuery(element),additionalOptions);
  };

  dateutil.addSingleDatePicker = function (element, additionalOptions) {
    return dateutil.addFinnishDatePicker(element, additionalOptions);
  };

  function addPicker(jqueryElement, additionalOptions) {
    const basicOptions = {
      field: jqueryElement.get(0),
      format: FINNISH_DATE_FORMAT,
      firstDay: 1,
      yearRange: [1900, 2050],
      i18n: FINNISH_PIKADAY_I18N
    };

    const options = {
      ...basicOptions,
      ...additionalOptions
    };

    const picker = new (getPikaday())(options);

    jqueryElement.keypress(function (e) {
      if (e.which === 13) { // hide on enter key press
        picker.hide();
        jqueryElement.blur();
      }
    });
    jqueryElement.attr('placeholder', FINNISH_HINT_TEXT);
    return picker;
  }

  dateutil.extractLatestModifications = function (elementsWithModificationTimestamps) {
    const newest = _.maxBy(elementsWithModificationTimestamps, function (s) {
      return momentLib(s.modifiedAt, "DD.MM.YYYY HH:mm:ss").valueOf() || 0;
    });
    return _.pick(newest, ['modifiedAt', 'modifiedBy']);
  };

  dateutil.isValidDate = function (date) {
    return date instanceof Date && !isNaN(date);
  };

  dateutil.isDateInYearRange = function (date, minYear, maxYear) {
    return date.getFullYear() >= minYear && date.getFullYear() <= maxYear;
  };

  /** Sets date to the next day */
  dateutil.addOneDayToDate = function (dateObject) {
    const nextDay = new Date(dateObject.getTime());
    nextDay.setDate(nextDay.getDate() + 1);
    return nextDay;
  };

  /** Converts date object to string "yyyy-mm-dd" (ISO 8601)*/
  dateutil.parseDateToString = function (date) {
    return momentLib(date).format('YYYY-MM-DD');
  };


  /**
   *  Convert string (dd.mm.yyyy) to Date
   */
  dateutil.parseDate = function (str) {
    const parsed = momentLib(str, FINNISH_DATE_FORMAT, true);
    return parsed.isValid() ? parsed.toDate() : null;
  };

  /** Creates a string ("dd.mm.yyyy") from current date */
  dateutil.getCurrentDateString = function () {
    return momentLib().format('DD.MM.YYYY');
  };


  /**
   * Converts a date-time string in the format "DD.MM.YYYY HH:mm:ss" into a JavaScript Date object
   * or an ISO 8601 string.
   *
   * @param {string} input - The date-time string to parse (e.g., "20.09.2024 00:00:00").
   * @param {boolean} [returnISO=false] - If true, returns an ISO 8601 string instead of a Date object.
   * @returns {Date|string} - A JavaScript Date object or ISO 8601 string representation.
   * @throws {Error} - Throws an error if the input is not a string or if the format is invalid.
   */
  dateutil.parseCustomDateString = function (input, returnISO = false) {
    if (typeof input !== 'string') throw new Error('Input must be a string');

    const normalizedInput = input.trim().replace(/\s+/, ' ');
    const parsed = momentLib(normalizedInput, 'DD.MM.YYYY HH:mm:ss', true);
    if (!parsed.isValid()) {
      throw new Error('Invalid date');
    }

    const dateObj = parsed.toDate();

    return returnISO ? dateObj.toISOString() : dateObj;
  };
