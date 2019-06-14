package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import fi.liikennevirasto.viite._
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}


case class JunctionPoint(id: Long, beforeAfter: BeforeAfter, roadwayPointId: Long, junctionId: Long, startDate: DateTime, endDate: Option[DateTime],
                         validFrom: DateTime, validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime], roadwayNumber: Long, addrM: Long)

class JunctionPointDAO extends BaseDAO {

  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

  implicit val getJunctionPoint: GetResult[JunctionPoint] = new GetResult[JunctionPoint] {
    def apply(r: PositionedResult): JunctionPoint = {
      val id = r.nextLong()
      val beforeOrAfter = r.nextLong()
      val roadwayPointId = r.nextLong()
      val junctionId = r.nextLong()
      val startDate = formatter.parseDateTime(r.nextDate.toString)
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = formatter.parseDateTime(r.nextDate.toString)
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
    if (junctionIds.isEmpty) {
      Seq()
    }
    else {
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
  }

  def fetchJunctionPointsByRoadwayPoints(roadwayNumber: Long, addrM: Long, beforeAfter: BeforeAfter): Option[JunctionPoint] = {
      val query =
        s"""
       SELECT JP.ID, JP.BEFORE_AFTER, JP.ROADWAY_POINT_ID, JP.JUNCTION_ID, JP.START_DATE, JP.END_DATE, JP.VALID_FROM, JP.VALID_TO, JP.CREATED_BY, JP.CREATED_TIME,
       RP.ROADWAY_NUMBER, RP.ADDR_M FROM JUNCTION_POINT JP
       JOIN ROADWAY_POINT RP ON (RP.ID = JP.ROADWAY_POINT_ID)
       where JP.valid_to is null and (JP.end_date is null or JP.end_date >= sysdate) and
       RP.ROADWAY_NUMBER = $roadwayNumber and RP.ADDR_M = $addrM and JP.before_after = ${beforeAfter.value}
     """
      queryList(query).headOption
  }

  def fetchByRoadwayPointIds(roadwayPointIds: Seq[Long]): Seq[JunctionPoint] = {
    if (roadwayPointIds.isEmpty) {
      Seq()
    } else {
      val query =
        s"""
       SELECT JP.ID, JP.BEFORE_AFTER, JP.ROADWAY_POINT_ID, JP.JUNCTION_ID, JP.START_DATE, JP.END_DATE, JP.VALID_FROM, JP.VALID_TO, JP.CREATED_BY, JP.CREATED_TIME,
       RP.ROADWAY_NUMBER, RP.ADDR_M FROM JUNCTION_POINT JP
       JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
       where JP.ROADWAY_POINT_ID in (${roadwayPointIds.mkString(", ")})
     """
      queryList(query)
    }
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
          where $boundingBoxFilter and JP.valid_to is null and (JP.end_date is null or JP.end_date >= sysdate)
        """
      queryList(query)
    }
  }

  def create(junctionPoints: Iterable[JunctionPoint], createdBy: String = "-"): Seq[Long] = {

    val ps = dynamicSession.prepareStatement(
      """insert into JUNCTION_POINT (ID, BEFORE_AFTER, ROADWAY_POINT_ID, JUNCTION_ID, START_DATE, END_DATE, CREATED_BY)
      values (?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?)""".stripMargin)

    // Set ids for the junction points without one
    val (ready, idLess) = junctionPoints.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchJunctionPointIds(idLess.size)
    val createJunctionPoints = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    createJunctionPoints.foreach {
      junctionPoint =>
        ps.setLong(1, junctionPoint.id)
        ps.setLong(2, junctionPoint.beforeAfter.value)
        ps.setLong(3, junctionPoint.roadwayPointId)
        ps.setLong(4, junctionPoint.junctionId)
        ps.setString(5, dateFormatter.print(junctionPoint.startDate))
        ps.setString(6, junctionPoint.endDate match {
          case Some(date) => dateFormatter.print(date)
          case None => ""
        })
        ps.setString(7, if (createdBy == null) "-" else createdBy)
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    createJunctionPoints.map(_.id).toSeq
  }

  /**
    * Expires junction points (set their valid_to to the current system date).
    *
    * @param ids : Iterable[Long] - The ids of the junction points to expire.
    * @return
    */
  def expireById(ids: Iterable[Long]): Int = {
    val query =
      s"""
        Update JUNCTION_POINT Set valid_to = sysdate where valid_to IS NULL and id in (${ids.mkString(", ")})
      """
    if (ids.isEmpty)
      0
    else
      Q.updateNA(query).first
  }

}
