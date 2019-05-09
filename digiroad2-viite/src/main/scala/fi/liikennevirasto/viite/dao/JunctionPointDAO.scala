package fi.liikennevirasto.viite.dao

import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class JunctionPoint(id: Long, beforeOrAfter: Long, roadwayPointId: Long, junctionId: Long, startDate: Option[DateTime], endDate: Option[DateTime],
                         validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime], roadwayNumber: Long, addrM: Long)

class JunctionPointDAO {
  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()
  implicit val getJunctionPoint: GetResult[JunctionPoint] = new GetResult[JunctionPoint] {
    def apply(r: PositionedResult): JunctionPoint = {
      val id = r.nextLong()
      val beforeOrAfter = r.nextLong()
      val roadwayPointId = r.nextLong()
      val junctionId = r.nextLong()
      val startDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextStringOption()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val roadwayNumber = r.nextLong()
      val addrM = r.nextLong()
      JunctionPoint(id, beforeOrAfter, roadwayPointId, junctionId, startDate, endDate, validFrom, validTo, createdBy, createdTime, roadwayNumber, addrM)
    }
  }

  private def queryJunctionPointList(query: String): List[JunctionPoint] = {
    Q.queryNA[JunctionPoint](query).list.groupBy(_.id).map {
      case (_, list) =>
        list.head
    }.toList
  }

  def fetchJunctionPointsByJunctionIds(junctionIds: Seq[Long]): Seq[JunctionPoint] = {
    val query =
      s"""
       SELECT JP.ID, JP.BEFORE_AFTER, JP.ROADWAY_POINT_ID, JP.JUNCTION_ID, JP.START_DATE, JP.END_DATE, JP.VALID_FROM, JP.VALID_TO, JP.CREATED_BY, JP.CREATED_TIME,
       RP.ROADWAY_NUMBER, RP.ADDR_M FROM JUNCTION_POINT JP
       JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
       JOIN JUNCTION J on (J.ID = JP.JUNCTION_ID)
       where J.ID in (${junctionIds.mkString(",")})
     """
    queryJunctionPointList(query)
  }

}
