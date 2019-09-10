package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import org.joda.time.DateTime
import fi.liikennevirasto.viite.NewIdValue
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

sealed trait BeforeAfter {
  def value: Long
  def acronym: String
}

object BeforeAfter {
  val values: Set[BeforeAfter] = Set(Before, After, UnknownBeforeAfter)

  def apply(intValue: Long): BeforeAfter = {
    values.find(_.value == intValue).getOrElse(UnknownBeforeAfter)
  }

  def switch(beforeAfter: BeforeAfter): BeforeAfter = {
    beforeAfter match {
      case After => Before
      case Before => After
      case _ => beforeAfter
    }
  }

  case object Before extends BeforeAfter {
    def value = 1
    def acronym = "E"
  }

  case object After extends BeforeAfter {
    def value = 2
    def acronym = "J"
  }

  case object UnknownBeforeAfter extends BeforeAfter {
    def value = 9
    def acronym = ""
  }

}

case class NodePoint(id: Long, beforeAfter: BeforeAfter, roadwayPointId: Long, nodeId: Option[Long], startDate: DateTime, endDate: Option[DateTime], validFrom: DateTime, validTo: Option[DateTime],
                     createdBy: Option[String], createdTime: Option[DateTime], roadwayNumber: Long, addrM : Long, roadNumber: Long, roadPartNumber: Long, track: Track, elyCode: Long)

class NodePointDAO extends BaseDAO {

  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

  val selectFromNodePoint = """SELECT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NP.NODE_ID, NP.START_DATE, NP.END_DATE,
                             NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M, RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, RW.ELY
                             FROM NODE_POINT NP
                             JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
                             LEFT OUTER JOIN NODE N ON (N.id = np.NODE_ID)
                             JOIN ROADWAY RW on (RW.ROADWAY_NUMBER = RP.ROADWAY_NUMBER) """

  implicit val getNodePoint: GetResult[NodePoint] = new GetResult[NodePoint] {
    def apply(r: PositionedResult): NodePoint = {
      val id = r.nextLong()
      val beforeAfter = r.nextLong()
      val roadwayPointId = r.nextLong()
      val nodeId = r.nextLongOption()
      val startDate = formatter.parseDateTime(r.nextDate.toString)
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = formatter.parseDateTime(r.nextDate.toString)
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextStringOption()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val roadwayNumber = r.nextLong()
      val addrM = r.nextLong()
      val roadNumber = r.nextLongOption().map(l => l).getOrElse(0L)
      val roadPartNumber = r.nextLongOption().map(l => l).getOrElse(0L)
      val track = r.nextLongOption().map(l => Track.apply(l.toInt)).getOrElse(Track.Unknown)
      val ely = r.nextLongOption().map(l => l).getOrElse(0L)

      NodePoint(id, BeforeAfter.apply(beforeAfter), roadwayPointId, nodeId, startDate, endDate, validFrom, validTo, createdBy, createdTime, roadwayNumber, addrM, roadNumber, roadPartNumber, track, ely)
    }
  }

  private def queryList(query: String): List[NodePoint] = {
    Q.queryNA[NodePoint](query).list.groupBy(_.id).map {
      case (_, list) =>
        list.head
    }.toList
  }

  def fetchByIds(ids: Seq[Long]): Seq[NodePoint] = {
    if (ids.isEmpty) {
      Seq()
    } else {
      val query =
        s"""
            $selectFromNodePoint
            where NP.id in (${ids.mkString(", ")}) and NP.valid_to is null and NP.end_date is null
         """
      queryList(query)
    }
  }

  def fetchNodePointsByNodeId(nodeIds: Seq[Long]): Seq[NodePoint] = {
    if (nodeIds.isEmpty) Seq()
    else {
      val query =
        s"""
         $selectFromNodePoint
         where N.id in (${nodeIds.mkString(",")}) and NP.valid_to is null and NP.end_date is null
         and rw.end_date is null
       """
      queryList(query)
    }
  }

  def fetchByRoadwayPointIds(roadwayPointIds: Seq[Long]): Seq[NodePoint] = {
    if (roadwayPointIds.isEmpty) {
      Seq()
    } else {
      val query =
        s"""
       $selectFromNodePoint
       where NP.ROADWAY_POINT_ID in (${roadwayPointIds.mkString(", ")}) and NP.valid_to is null and NP.end_date is null
     """
      queryList(query)
    }
  }

  def fetchByRoadwayPointId(roadwayPointId: Long): Seq[NodePoint] = {
    val query =
      s"""
     $selectFromNodePoint
     where NP.ROADWAY_POINT_ID = $roadwayPointId and NP.valid_to is null and NP.end_date is null
   """
    queryList(query)
  }

  def fetchNodePoint(roadwayNumber: Long): Option[NodePoint] = {
    val query =
      s"""
         $selectFromNodePoint
         where RP.roadway_number = $roadwayNumber and NP.valid_to is null and NP.end_date is null
       """
    queryList(query).headOption
  }

  def fetchTemplatesByRoadwayNumber(roadwayNumber: Long): List[NodePoint] = {
    val query =
      s"""
        SELECT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NP.NODE_ID, NP.START_DATE, NP.END_DATE,
        NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M, NULL, NULL, NULL, NULL
        FROM NODE_POINT NP
        JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
        JOIN ROADWAY RW ON (RP.ROADWAY_NUMBER = RW.ROADWAY_NUMBER)
        where RP.roadway_number = $roadwayNumber and NP.valid_to is null and NP.end_date is null
      """
    queryList(query)
  }

  def fetchNodePointsTemplates(roadwayNumbers: Set[Long]): List[NodePoint] = {
    val query =
      if (roadwayNumbers.isEmpty) {
        ""
      } else {
        s"""SELECT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NP.NODE_ID, NP.START_DATE, NP.END_DATE,
          NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M, RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, rw.ELY
          FROM NODE_POINT NP
          JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
          JOIN ROADWAY RW ON (RP.ROADWAY_NUMBER = RW.ROADWAY_NUMBER)
          where RP.roadway_number in (${roadwayNumbers.mkString(", ")}) and NP.valid_to is null and NP.end_date is null
       """
      }
    queryList(query)
  }

  def fetchTemplates() : Seq[NodePoint] = {
    val query =
      s"""
         SELECT DISTINCT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NULL AS NODE_ID, NP.START_DATE, NP.END_DATE,
         NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M, RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, rw.ELY
         FROM NODE_POINT NP
         JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
         JOIN LINEAR_LOCATION LL ON (LL.ROADWAY_NUMBER = RP.ROADWAY_NUMBER AND LL.VALID_TO IS NULL)
         LEFT JOIN ROADWAY RW ON (RP.ROADWAY_NUMBER = RW.ROADWAY_NUMBER)
         where NP.valid_to is null and NP.end_date is null and NP.node_id is null and RW.end_date is NULL and RW.valid_to is null
       """
    queryList(query)
  }

  def fetchTemplatesByBoundingBox(boundingRectangle: BoundingRectangle): Seq[NodePoint] = {
    time(logger, "Fetch NodePoint templates by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))

      val boundingBoxFilter = OracleDatabase.boundingBoxFilter(extendedBoundingRectangle, "LL.geometry")

      val query =
        s"""
          SELECT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NULL AS NODE_ID, NP.START_DATE, NP.END_DATE,
            NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M, RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, RW.ELY
          FROM NODE_POINT NP
          JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
          JOIN LINEAR_LOCATION LL ON (LL.ROADWAY_NUMBER = RP.ROADWAY_NUMBER AND LL.VALID_TO IS NULL)
          LEFT JOIN ROADWAY RW ON (RP.ROADWAY_NUMBER = RW.ROADWAY_NUMBER)
          where $boundingBoxFilter and NP.valid_to is null and NP.end_date is null and NP.node_id is null and RW.end_date is null
        """
      queryList(query)
    }
  }

  def create(nodePoints: Iterable[NodePoint], createdBy: String = "-"): Seq[Long] = {

    val ps = dynamicSession.prepareStatement(
      """insert into NODE_POINT (ID, BEFORE_AFTER, ROADWAY_POINT_ID, NODE_ID, START_DATE, END_DATE, CREATED_BY)
      values (?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?)""".stripMargin)

    // Set ids for the node points without one
    val (ready, idLess) = nodePoints.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchNodePointIds(idLess.size)
    val createNodePoints = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    createNodePoints.foreach {
      nodePoint =>
        ps.setLong(1, nodePoint.id)
        ps.setLong(2, nodePoint.beforeAfter.value)
        ps.setLong(3, nodePoint.roadwayPointId)
        if (nodePoint.nodeId.isDefined) {
          ps.setLong(4, nodePoint.nodeId.get)
        } else {
          ps.setNull(4, java.sql.Types.INTEGER)
        }
        ps.setString(5, dateFormatter.print(nodePoint.startDate))
        ps.setString(6, nodePoint.endDate match {
          case Some(date) => dateFormatter.print(date)
          case None => ""
        })
        ps.setString(7, if (createdBy == null) "-" else createdBy)
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    createNodePoints.map(_.id).toSeq
  }

  def update(nodePoints: Iterable[NodePoint], updatedBy: String = "-"): Seq[Long] = {

    val ps = dynamicSession.prepareStatement(
      "update NODE_POINT SET BEFORE_AFTER = ?, ROADWAY_POINT_ID = ?, NODE_ID = ?, END_DATE = TO_DATE(?, 'YYYY-MM-DD'), VALID_TO = TO_DATE(?, 'YYYY-MM-DD') WHERE ID = ?")

    nodePoints.foreach {
      nodePoint =>
        ps.setLong(1, nodePoint.beforeAfter.value)
        ps.setLong(2, nodePoint.roadwayPointId)
        if (nodePoint.nodeId.isDefined) {
          ps.setLong(3, nodePoint.nodeId.get)
        } else {
          ps.setNull(3, java.sql.Types.INTEGER)
        }
        ps.setString(4, nodePoint.endDate match {
          case Some(date) => dateFormatter.print(date)
          case None => ""
        })
        ps.setString(5, nodePoint.validTo match {
          case Some(date) => dateFormatter.print(date)
          case None => ""
        })
        ps.setLong(6, nodePoint.id)
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    nodePoints.map(_.id).toSeq
  }

  /**
    * Expires node points (set their valid_to to the current system date).
    *
    * @param ids : Iterable[Long] - The ids of the node points to expire.
    * @return
    */
  def expireById(ids: Iterable[Long]): Int = {
    val query =
      s"""
        Update NODE_POINT Set valid_to = sysdate where valid_to IS NULL and id in (${ids.mkString(", ")})
      """
    if (ids.isEmpty)
      0
    else
      Q.updateNA(query).first
  }

  def updateRoadwayPointId(nodePointId: Any, roadwayPointId: Long) = {
    Q.updateNA(s"UPDATE NODE_POINT SET ROADWAY_POINT_ID = $roadwayPointId WHERE ID = $nodePointId").execute
  }

}
