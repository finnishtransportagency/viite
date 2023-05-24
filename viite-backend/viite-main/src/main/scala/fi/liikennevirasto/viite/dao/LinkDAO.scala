package fi.liikennevirasto.viite.dao

import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class Link(id: String, source: Long, adjustedTimestamp: Long, createdTime: Option[DateTime]){
  def this (id: Long, source: Long, adjustedTimestamp: Long, createdTime: Option[DateTime]) =
   this(id.toString, source, adjustedTimestamp, createdTime)
}

object LinkDAO {
  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()
  implicit val getLink: GetResult[Link] = new GetResult[Link] {
    def apply(r: PositionedResult): Link = {
      val id = r.nextString()
      val source = r.nextLong()
      val adjustedTimestamp = r.nextLong()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))

      Link(id, source, adjustedTimestamp, createdTime)
    }
  }

  def fetch(id: String): Option[Link] = {
    val sql = s"""SELECT * FROM LINK where id = '$id'"""
    Q.queryNA[Link](sql).firstOption
  }

  def create(id: String, adjustedTimestamp: Long, source: Long): Unit = {
    sqlu"""
      insert into LINK (id, source, adjusted_timestamp) values ($id, $source, $adjustedTimestamp)
      """.execute

  }

  def createIfEmptyFetch(id: String, adjustedTimestamp: Long, source: Long): Unit = {
    if (fetch(id).isEmpty) {
      sqlu"""
        INSERT INTO LINK (id, source, adjusted_timestamp) values ($id, $source, $adjustedTimestamp)
      """.execute
    }
  }

  def fetchMaxAdjustedTimestamp(): Long = {
    sql"""
      SELECT max(adjusted_timestamp) FROM link WHERE SOURCE IN (1, 4)
    """.as[Long].first

  }


}
