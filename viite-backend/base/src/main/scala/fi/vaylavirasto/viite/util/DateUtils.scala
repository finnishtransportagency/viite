package fi.vaylavirasto.viite.util

import org.joda.time.DateTime
import fi.vaylavirasto.viite.util.DateTimeFormatters.ISOdateFormatter

object DateUtils {

  /**
   * Tries to parse a string ("yyyy-MM-dd") in to a DateTime object
   * If the text to parse is invalid, throws IllegalArgumentException
   */
  def parseStringToDateTime(date: String): DateTime = {
    ISOdateFormatter.parseDateTime(date)
  }

}
