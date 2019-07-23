package fi.liikennevirasto.digiroad2.dao

import fi.liikennevirasto.digiroad2.linearasset.KMTKID
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class Link(id: Long, uuid: String, version: Long, source: Long, adjustedTimestamp: Long, createdTime: Option[DateTime]) {
  def kmtkId: KMTKID = KMTKID(uuid, version)
}

class LinkDAO {

  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()

  implicit val getLink: GetResult[Link] = new GetResult[Link] {
    def apply(r: PositionedResult) = {
      val id = r.nextLong()
      val uuid = r.nextString()
      val version = r.nextLong()
      val source = r.nextLong()
      val adjustedTimestamp = r.nextLong()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))

      Link(id, uuid, version, source, adjustedTimestamp, createdTime)
    }
  }

  def fetch(id: Long): Option[Link] = {
    val sql = s"""SELECT * FROM LINK where id = $id"""
    Q.queryNA[Link](sql).firstOption
  }

  private def queryList(query: String): List[Link] = {
    Q.queryNA[Link](query).list
  }

  // TODO Mass query
  def fetch(ids: Iterable[Long]): Seq[Link] = {
    if (ids.isEmpty) {
      Seq.empty[Link]
    } else {
      val query =
        s"""
          SELECT * FROM LINK where id in (${ids.mkString(", ")})
         """
      queryList(query)
    }
  }

  def fetch(kmtkId: KMTKID): Option[Link] = {
    val sql = s"""SELECT * FROM LINK where uuid = '${kmtkId.uuid}' and version = ${kmtkId.version}"""
    Q.queryNA[Link](sql).firstOption
  }

  def create(id: Long, kmtkId: KMTKID, adjustedTimestamp: Long, source: Long) = {
    sqlu"""
      insert into LINK (id, uuid, version, source, adjusted_timestamp) values ($id, ${kmtkId.uuid}, ${kmtkId.version}, $source, $adjustedTimestamp)
      """.execute

  }

  // TODO KMTKID
  def createIfEmptyFetch(id: Long): Unit = {
    if (fetch(id).isEmpty) {
      sqlu"""
        INSERT INTO LINK (ID) values ($id)
      """.execute
    }
  }


}