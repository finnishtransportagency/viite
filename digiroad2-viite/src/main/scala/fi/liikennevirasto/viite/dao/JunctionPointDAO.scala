package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class JunctionPoint(id: Long, beforeOrAfter: BeforeAfter, roadwayPointId: Long, junctionId: Long, startDate: Option[DateTime], endDate: Option[DateTime],
                         validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime], roadwayNumber: Long, addrM: Long)

class JunctionPointDAO extends BaseDAO {

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
      JunctionPoint(id, BeforeAfter.apply(beforeOrAfter), roadwayPointId, junctionId, startDate, endDate, validFrom, validTo, createdBy, createdTime, roadwayNumber, addrM)
    }
  }

  private def queryList(query: String): List[JunctionPoint] = {
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
    queryList(query)
  }

  def fetchTemplatesByBoundingBox(boundingRectangle: BoundingRectangle): Seq[JunctionPoint] = {
    time(logger, "Fetch JunctionPoint templates by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))

      val boundingBoxFilter = OracleDatabase.boundingBoxFilter(extendedBoundingRectangle, "LL.geometry")

      val query =
        s"""
          SELECT JP.ID, JP.BEFORE_AFTER, JP.ROADWAY_POINT_ID, JP.JUNCTION_ID, JP.START_DATE, JP.END_DATE, JP.VALID_FROM,
          JP.VALID_TO, JP.CREATED_BY, JP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M
          FROM JUNCTION_POINT JP
          JOIN ROADWAY_POINT RP ON (RP.ID = JP.ROADWAY_POINT_ID)
          JOIN JUNCTION J ON (J.ID = JP.JUNCTION_ID AND J.NODE_ID IS NULL)
          JOIN LINEAR_LOCATION LL ON (LL.ROADWAY_NUMBER = RP.ROADWAY_NUMBER AND LL.VALID_TO IS NULL)
          where $boundingBoxFilter and NP.valid_to is null and NP.end_date is null
        """
      queryList(query)
    }
  }

}
