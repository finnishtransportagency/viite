package fi.liikennevirasto.viite.dao
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}


case class JunctionPoint(id: Long, beforeAfter: BeforeAfter, roadwayPointId: Long, junctionId: Long, startDate: Option[DateTime], endDate: Option[DateTime],
                         validFrom: DateTime, validTo: Option[DateTime], createdBy: String, createdTime: Option[DateTime], roadwayNumber: Long, addrM: Long,
                         roadNumber: Long, roadPartNumber: Long, track: Track, discontinuity: Discontinuity, coordinates: Point = Point(0.0, 0.0))

class JunctionPointDAO extends BaseDAO {

  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

  val junctionPointQuery =
    """
      SELECT JP.ID, JP.BEFORE_AFTER, JP.ROADWAY_POINT_ID, JP.JUNCTION_ID, J.START_DATE, J.END_DATE, JP.VALID_FROM, JP.VALID_TO, JP.CREATED_BY, JP.CREATED_TIME,
      RP.ROADWAY_NUMBER, RP.ADDR_M, RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, RW.DISCONTINUITY
      FROM JUNCTION_POINT JP
      JOIN JUNCTION J ON (J.ID = JP.JUNCTION_ID AND J.VALID_TO IS NULL AND J.END_DATE IS NULL)
      JOIN ROADWAY_POINT RP ON (RP.ID = JP.ROADWAY_POINT_ID)
      JOIN ROADWAY RW ON (RW.ROADWAY_NUMBER = RP.ROADWAY_NUMBER)
    """

  val junctionPointHistoryQuery =
    """
      SELECT JP.ID, JP.BEFORE_AFTER, JP.ROADWAY_POINT_ID, JP.JUNCTION_ID, J.START_DATE, J.END_DATE, JP.VALID_FROM, JP.VALID_TO, JP.CREATED_BY, JP.CREATED_TIME,
      RP.ROADWAY_NUMBER, RP.ADDR_M, RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, RW.DISCONTINUITY
      FROM JUNCTION_POINT JP
      JOIN JUNCTION J ON (J.ID = JP.JUNCTION_ID)
      JOIN ROADWAY_POINT RP ON (RP.ID = JP.ROADWAY_POINT_ID)
      JOIN ROADWAY RW ON (RW.ROADWAY_NUMBER = RP.ROADWAY_NUMBER)
    """

  implicit val getJunctionPoint: GetResult[JunctionPoint] = new GetResult[JunctionPoint] {
    def apply(r: PositionedResult): JunctionPoint = {
      val id = r.nextLong()
      val beforeOrAfter = r.nextLong()
      val roadwayPointId = r.nextLong()
      val junctionId = r.nextLong()
      val startDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = formatter.parseDateTime(r.nextDate.toString)
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextString()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val roadwayNumber = r.nextLong()
      val addrM = r.nextLong()
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val track = Track.apply(r.nextInt())
      val discontinuity = Discontinuity.apply(r.nextInt())

      JunctionPoint(id, BeforeAfter.apply(beforeOrAfter), roadwayPointId, junctionId, startDate, endDate, validFrom, validTo, createdBy, createdTime, roadwayNumber, addrM, roadNumber, roadPartNumber, track, discontinuity)
    }
  }

  private def queryList(query: String): List[JunctionPoint] = {
    Q.queryNA[JunctionPoint](query).list.groupBy(_.id).map {
      case (_, list) =>
        list.head
    }.toList
  }

  def fetchByIds(ids: Seq[Long]): Seq[JunctionPoint] = {
    if (ids.isEmpty) {
      Seq()
    } else {
      val query =
        s"""
          $junctionPointQuery
          WHERE JP.ID IN (${ids.mkString(",")}) AND JP.VALID_TO IS NULL
        """
      queryList(query)
    }
  }

  def fetchAllByJunctionIds(junctionIds: Seq[Long]): Seq[JunctionPoint] = {
    if (junctionIds.isEmpty) {
      Seq()
    } else {
      val query =
        s"""
          $junctionPointHistoryQuery
          WHERE J.ID IN (${junctionIds.mkString(",")})
        """
      queryList(query)
    }
  }

  def fetchByJunctionIds(junctionIds: Seq[Long]): Seq[JunctionPoint] = {
    if (junctionIds.isEmpty) {
      Seq()
    } else {
      val query =
        s"""
          $junctionPointQuery
          WHERE J.ID IN (${junctionIds.mkString(",")}) AND JP.VALID_TO IS NULL
          AND RW.VALID_TO IS NULL AND RW.END_DATE IS NULL
        """
      queryList(query)
    }
  }

  def fetchByRoadwayPoint(roadwayNumber: Long, addrM: Long, beforeAfter: BeforeAfter): Option[JunctionPoint] = {
    val query =
      s"""
        $junctionPointQuery
        WHERE JP.VALID_TO IS NULL
        AND RP.ROADWAY_NUMBER = $roadwayNumber AND RP.ADDR_M = $addrM and JP.BEFORE_AFTER = ${beforeAfter.value}
      """
    queryList(query).headOption
  }

  def fetchByRoadwayPointId(roadwayPointId: Long): Seq[JunctionPoint] = {
    fetchByRoadwayPointIds(Seq(roadwayPointId))
  }

  def fetchByRoadwayPointIds(roadwayPointIds: Seq[Long]): Seq[JunctionPoint] = {
    if (roadwayPointIds.isEmpty) {
      Seq()
    } else {
      val query =
        s"""
          $junctionPointQuery
          WHERE JP.VALID_TO IS NULL
          AND JP.ROADWAY_POINT_ID IN (${roadwayPointIds.mkString(", ")})
        """
      queryList(query)
    }
  }

  def fetchByBoundingBox(boundingRectangle: BoundingRectangle): Seq[JunctionPoint] = {
    time(logger, "Fetch JunctionPoints by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))

      val boundingBoxFilter = OracleDatabase.boundingBoxFilter(extendedBoundingRectangle, "LL.geometry")

      val query =
        s"""
          $junctionPointQuery
          INNER JOIN LINEAR_LOCATION LL ON (LL.ROADWAY_NUMBER = RP.ROADWAY_NUMBER AND LL.VALID_TO IS NULL)
          WHERE RW.VALID_TO IS NULL AND RW.END_DATE IS NULL
          AND JP.VALID_TO IS NULL AND $boundingBoxFilter
        """
      queryList(query)
    }
  }

  def create(junctionPoints: Iterable[JunctionPoint]): Seq[Long] = {

    val ps = dynamicSession.prepareStatement(
      """insert into JUNCTION_POINT (ID, BEFORE_AFTER, ROADWAY_POINT_ID, JUNCTION_ID, CREATED_BY)
      values (?, ?, ?, ?, ?)""".stripMargin)

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
        ps.setString(5, junctionPoint.createdBy)
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    createJunctionPoints.map(_.id).toSeq
  }

  def update(junctionPoints: Iterable[JunctionPoint], updatedBy: String = "-"): Seq[Long] = {

    val ps = dynamicSession.prepareStatement("update JUNCTION_POINT SET BEFORE_AFTER = ?, ROADWAY_POINT_ID = ?, JUNCTION_ID = ? WHERE ID = ?")

    junctionPoints.foreach {
      junctionPoint =>
        ps.setLong(1, junctionPoint.beforeAfter.value)
        ps.setLong(2, junctionPoint.roadwayPointId)
        ps.setLong(3, junctionPoint.junctionId)
        ps.setLong(4, junctionPoint.id)
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    junctionPoints.map(_.id).toSeq
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
        Update JUNCTION_POINT Set valid_to = current_timestamp where valid_to IS NULL and id in (${ids.mkString(", ")})
      """
    if (ids.isEmpty)
      0
    else
      Q.updateNA(query).first
  }

}
