package fi.vaylavirasto.viite.asset

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

trait TimeStamps {
  val created: Modification
  val modified: Modification
}

//TODO REMOVE
object Asset {
  val DateTimePropertyFormat = DateTimeFormat.forPattern("dd.MM.yyyy HH:mm:ss")
}

case class Modification(modificationTime: Option[DateTime], modifier: Option[String])

