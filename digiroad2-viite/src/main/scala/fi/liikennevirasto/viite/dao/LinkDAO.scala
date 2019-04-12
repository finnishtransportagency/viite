package fi.liikennevirasto.viite.dao
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class Link (id: Long, source: Long, adjustedTimestamp: Long, createdTime: Option[DateTime])

object LinkDAO {
  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()
  implicit val getLink: GetResult[Link] = new GetResult[Link] {
    def apply(r: PositionedResult): Link = {
      val id = r.nextLong()
      val source = r.nextLong()
      val adjustedTimestamp = r.nextLong()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))

      Link(id, source, adjustedTimestamp, createdTime)
    }
  }

  def fetch(id: Long): Option[Link] = {
    val sql= s"""SELECT * FROM LINK where id = $id"""
    Q.queryNA[Link](sql).firstOption
  }

  def create(id: Long, adjustedTimestamp: Long, source: Long) = {
    sqlu"""
      insert into LINK (id, source, adjusted_timestamp) values (${id}, ${source}, ${adjustedTimestamp})
      """.execute

  }

  def createIfEmptyFetch(id: Long): Unit = {
    if(fetch(id).isEmpty){
      sqlu"""
        INSERT INTO LINK (ID) values (${id})
      """.execute
    }
  }



}