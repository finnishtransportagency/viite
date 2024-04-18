package fi.vaylavirasto.viite.util

import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter, ISODateTimeFormat}

trait TimeStamps {
  val created: Modification
  val modified: Modification
}

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

case class Modification(modificationTime: Option[DateTime], modifier: Option[String])

