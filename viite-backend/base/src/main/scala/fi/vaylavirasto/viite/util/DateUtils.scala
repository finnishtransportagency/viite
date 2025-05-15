package fi.vaylavirasto.viite.util

import fi.vaylavirasto.viite.util.DateTimeFormatters.ISOdateFormatter
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter, ISODateTimeFormat}

trait TimeStamps {
  val created: Modification
  val modified: Modification
}

case class Modification(modificationTime: Option[DateTime], modifier: Option[String])

object DateTimeFormatters {
  val finnishDateTimeFormatter:  DateTimeFormatter = DateTimeFormat.forPattern("dd.MM.yyyy HH:mm:ss")
  val finnishDateCommaTimeFormatter:  DateTimeFormatter = DateTimeFormat.forPattern("dd.MM.yyyy, HH:mm:ss")
  val finnishDateFormatter:      DateTimeFormatter = DateTimeFormat.forPattern("dd.MM.yyyy")
  val ISOdateFormatter:          DateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd")
  val basicDateFormatter:        DateTimeFormatter = ISODateTimeFormat.basicDate()
  val dateTimeNoMillisFormatter: DateTimeFormatter = ISODateTimeFormat.dateTimeNoMillis()
  val dateOptTimeFormatter:      DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()
  val dateSlashFormatter:        DateTimeFormatter = DateTimeFormat.forPattern("dd/MM/yyyy")
}

object DateUtils {

  /**
   * Tries to parse a string ("yyyy-MM-dd") in to a DateTime object
   * If the text to parse is invalid, throws IllegalArgumentException
   */
  def parseStringToDateTime(date: String): DateTime = {
    ISOdateFormatter.parseDateTime(date)
  }

}
