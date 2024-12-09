package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.NewIdValue
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.postgis.GeometryDbUtils
import fi.vaylavirasto.viite.model.{AddrMRange, BeforeAfter, NodePointType, RoadPart, Track}
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet


case class NodePoint(id: Long, beforeAfter: BeforeAfter, roadwayPointId: Long, nodeNumber: Option[Long], nodePointType: NodePointType = NodePointType.UnknownNodePointType,
                     startDate: Option[DateTime], endDate: Option[DateTime], validFrom: DateTime, validTo: Option[DateTime],
                     createdBy: String, createdTime: Option[DateTime], roadwayNumber: Long, addrM : Long,
                     roadPart: RoadPart, track: Track, elyCode: Long, coordinates: Point = Point(0.0, 0.0))

object NodePoint extends SQLSyntaxSupport[NodePoint] {
  override val tableName = "NODE_POINT"

  def apply(rs: WrappedResultSet): NodePoint = NodePoint(
    id = rs.long("id"),
    beforeAfter = BeforeAfter(rs.int("before_after")),
    roadwayPointId = rs.long("roadway_point_id"),
    nodeNumber = rs.longOpt("node_number"),
    nodePointType = NodePointType(rs.int("type")),
    startDate = rs.jodaDateTimeOpt("start_date"),
    endDate = rs.jodaDateTimeOpt("end_date"),
    validFrom = rs.jodaDateTime("valid_from"),
    validTo = rs.jodaDateTimeOpt("valid_to"),
    createdBy = rs.string("created_by"),
    createdTime = rs.jodaDateTimeOpt("created_time"),
    roadwayNumber = rs.long("roadway_number"),
    addrM = rs.long("addr_m"),
    roadPart = RoadPart(
      rs.longOpt("road_number").map(l => l).getOrElse(0L),
      rs.longOpt("road_part_number").map(l => l).getOrElse(0L)
    ),
    track = rs.longOpt("track").map(l => Track.apply(l.toInt)).getOrElse(Track.Unknown),
    elyCode = rs.longOpt("ely").map(l => l).getOrElse(0L)
  )
}

class NodePointDAO extends BaseDAO {
  private val np = NodePoint.syntax("np")
  private def queryList(query: SQL[Nothing, NoExtractor]): List[NodePoint] = {
    runSelectQuery(query.map(NodePoint.apply))
      .groupBy(_.id)
      .map {case  (_, nodePoints) => nodePoints.head} // Remove duplicates
      .toList
  }

  /** Select/join clause for retrieving joined nodepoint~roadway_point~roadway, node~nodepoint data, where:
   * <li>nodepoint must match preserved roadway_point by roadway_point_id, as well as </li>
   * <li>roadway_point must match preserved roadway by roadway_number, and roadway must be eligible (end_date, and valid_to must be nulls) for it to match. </li>
   * <li>The existence of the corresponding node is not required, but it is serached by node_number matching
   *     that of node_point, and it must be still eligible for it to match.</li> */
  lazy val selectFromNodePoint = sqls"""
                             SELECT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NP.NODE_NUMBER, NP.TYPE, N.START_DATE, N.END_DATE,
                                NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M,
                                RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, RW.ELY
                             FROM NODE_POINT NP
                             JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
                             LEFT OUTER JOIN NODE N ON (N.NODE_NUMBER = np.NODE_NUMBER AND N.VALID_TO IS NULL AND N.END_DATE IS NULL)
                             JOIN ROADWAY RW on (RW.ROADWAY_NUMBER = RP.ROADWAY_NUMBER AND RW.END_DATE IS NULL AND RW.VALID_TO IS NULL)
                             """

  def fetchByIds(ids: Seq[Long]): Seq[NodePoint] = {
    if (ids.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
            $selectFromNodePoint
            where NP.id in ($ids) and NP.valid_to is null
         """
      queryList(query)
    }
  }

  def fetchByNodeNumber(nodeNumber: Long): Seq[NodePoint] = {
    fetchByNodeNumbers(Seq(nodeNumber))
  }

  /** Retrieves those eligible (valid_to is null) NodePoints, whose node_number matches any of  those in <i>nodeNumbers</i>.
   * Uses {@link selectFromNodePoint} as the select/join clause. */
  def fetchByNodeNumbers(nodeNumbers: Seq[Long]): Seq[NodePoint] = {
    if (nodeNumbers.isEmpty) Seq()
    else {
      val query =
        sql"""
         $selectFromNodePoint
         where N.node_number in ($nodeNumbers) and NP.valid_to is null
       """
      queryList(query)
    }
  }

  /** Retrieves those NodePoints, whose roadway_point_id matches any of  those in <i>roadwayPointIds</i>.
   * Uses altered version of {@link selectFromNodePoint}, where roadway needs NOT to be eligible.
   * Gets joined NodePoint, Node, RoadwayPoint, and Roadway info where
   * nodePoint~roadPoint~roadway, and nodePoint~node,
   * and <b>nodePoint is still eligible</b> (end_date, and valid_to are nulls) */
  def fetchByRoadwayPointIds(roadwayPointIds: Seq[Long]): Seq[NodePoint] = {
    if (roadwayPointIds.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
          SELECT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NP.NODE_NUMBER, NP.TYPE, N.START_DATE, N.END_DATE,
            NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M,
            RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, RW.ELY
          FROM NODE_POINT NP
          JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
          LEFT OUTER JOIN NODE N ON (N.NODE_NUMBER = np.NODE_NUMBER AND N.VALID_TO IS NULL AND N.END_DATE IS NULL)
          JOIN ROADWAY RW on (RW.ROADWAY_NUMBER = RP.ROADWAY_NUMBER)
          where NP.ROADWAY_POINT_ID in ($roadwayPointIds) and NP.valid_to is null
        """
      logger.debug(s"******* Querying by roadwaypointId.s: ${roadwayPointIds.mkString(", ")} \n    query: : $query")
      queryList(query)
    }
  }

  /** Gets joined NodePoint, Node, RoadwayPoint, and Roadway info where
   * nodePoint~roadPoint~roadway, and nodePoint~node,
   * and nodePoint is still eligible (end_date, and valid_to are nulls)
   * <b>but there is no more corresponding roadway available.</b>*/
  def fetchRoadwiseOrphansByRoadwayPointIds(roadwayPointIds: Seq[Long]): Seq[NodePoint] = {
    if (roadwayPointIds.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
           SELECT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NP.NODE_NUMBER, NP.TYPE,
                  N.START_DATE, N.END_DATE,
                  NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME,
                  RP.ROADWAY_NUMBER, RP.ADDR_M,
                  RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, RW.ELY
           FROM            NODE_POINT NP
           INNER JOIN      ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
           LEFT OUTER JOIN NODE N     ON (N.NODE_NUMBER = np.NODE_NUMBER AND N.VALID_TO IS NULL AND N.END_DATE IS NULL)
           LEFT OUTER JOIN ROADWAY RW ON (RW.ROADWAY_NUMBER = RP.ROADWAY_NUMBER)
           WHERE           NP.ROADWAY_POINT_ID IN ($roadwayPointIds) AND NP.valid_to is null
                           AND RW.ROADWAY_NUMBER is null
        """
      logger.debug(s"******* Querying Roadwaywise orphan Nodes by roadwaypointId.s: ${roadwayPointIds.mkString(", ")} \n    query: : $query")
      queryList(query)
    }
  }

  /** Get the eligible (valid_to is null) NodePoints that have the given roadwayPointId. */
  def fetchByRoadwayPointId(roadwayPointId: Long): Seq[NodePoint] = {
    val query =
      sql"""
     $selectFromNodePoint
     where NP.ROADWAY_POINT_ID = $roadwayPointId and NP.valid_to is null
   """
    queryList(query)
  }

  /** Get the eligible (valid_to is null) NodePoints and NodePoint templates that have the given roadwayNumber. */
  def fetchByRoadwayNumber(roadwayNumber: Long): Seq[NodePoint] = {
    val query =
      sql"""
         $selectFromNodePoint
         where RP.roadway_number = $roadwayNumber and NP.valid_to is null
       """
    queryList(query)
  }

  /** Get the eligible (valid_to is null) NodePoints that have a roadway number belonging to
   * the given roadwayNumbers sequence . */
  def fetchRoadAddressNodePoints(roadwayNumbers: Seq[Long]): Seq[NodePoint] = {
    if (roadwayNumbers.isEmpty) Seq()
    else {
      val query =
        sql"""
         $selectFromNodePoint
         where RP.roadway_number in ($roadwayNumbers) and NP.valid_to is null
         AND NP.TYPE = ${NodePointType.RoadNodePoint.value}
       """
      queryList(query)
    }
  }

  def fetchTemplatesByRoadwayNumber(roadwayNumber: Long): List[NodePoint] = {
    val query =
      sql"""
        SELECT  NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NP.NODE_NUMBER, NP.TYPE,
                NULL AS START_DATE, NULL AS END_DATE,
                NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M,
                NULL AS ROAD_NUMBER, NULL AS ROAD_PART_NUMBER, NULL AS ElY, NULL AS TRACK
        FROM NODE_POINT NP
        JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
        JOIN ROADWAY RW ON (RP.ROADWAY_NUMBER = RW.ROADWAY_NUMBER AND RW.end_date is NULL AND RW.VALID_TO IS NULL)
        where RP.roadway_number = $roadwayNumber and NP.valid_to is null and NP.node_number is null
      """
    queryList(query)
  }

  def fetchTemplatesByRoadwayNumbers(roadwayNumbers: Set[Long]): List[NodePoint] = {
    val query =
      if (roadwayNumbers.isEmpty) {
        sql""
      } else {
        sql"""SELECT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NP.NODE_NUMBER, NP.TYPE, NULL, NULL,
          NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M, RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, rw.ELY
          FROM NODE_POINT NP
          JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
          JOIN ROADWAY RW ON (RP.ROADWAY_NUMBER = RW.ROADWAY_NUMBER AND RW.end_date is NULL AND RW.VALID_TO IS NULL)
          where RP.roadway_number in ($roadwayNumbers) and NP.valid_to is null and NP.node_number is null
       """
      }
    queryList(query)
  }

  def fetchTemplates() : Seq[NodePoint] = {
    val query =
      sql"""
         SELECT DISTINCT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NULL AS NODE_NUMBER, NP.TYPE, NULL AS START_DATE, NULL AS END_DATE,
         NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M, RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, rw.ELY
         FROM NODE_POINT NP
         JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
         JOIN LINEAR_LOCATION LL ON (LL.ROADWAY_NUMBER = RP.ROADWAY_NUMBER AND LL.VALID_TO IS NULL)
         JOIN ROADWAY RW ON (RP.ROADWAY_NUMBER = RW.ROADWAY_NUMBER AND RW.end_date is NULL AND RW.VALID_TO IS NULL)
         where NP.valid_to is null and NP.node_number is null
       """
    queryList(query)
  }

  def fetchNodePointTemplateById(id: Long): Option[NodePoint] = {
    val query =
      sql"""
         SELECT DISTINCT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NP.NODE_NUMBER, NP.TYPE, NULL, NULL,
         NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M, RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, rw.ELY
         FROM NODE_POINT NP
         JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
         JOIN LINEAR_LOCATION LL ON (LL.ROADWAY_NUMBER = RP.ROADWAY_NUMBER AND LL.VALID_TO IS NULL)
         JOIN ROADWAY RW ON (RP.ROADWAY_NUMBER = RW.ROADWAY_NUMBER AND RW.end_date is NULL AND RW.VALID_TO IS NULL)
         where NP.id = $id AND NP.node_number is null and NP.valid_to is null
       """
    runSelectSingleOption(query.map(NodePoint.apply))
  }

  def fetchTemplatesByBoundingBox(boundingRectangle: BoundingRectangle): Seq[NodePoint] = {
    time(logger, "Fetch NodePoint templates by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))

      val boundingBoxFilter = GeometryDbUtils.boundingBoxFilter(extendedBoundingRectangle, sqls"LL.geometry")

      val query =
        sql"""
          SELECT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NP.NODE_NUMBER, NP.TYPE, NULL AS start_date, NULL AS end_date,
            NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M, RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, RW.ELY
          FROM NODE_POINT NP
          JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
          JOIN LINEAR_LOCATION LL ON (LL.ROADWAY_NUMBER = RP.ROADWAY_NUMBER AND LL.VALID_TO IS NULL)
          JOIN ROADWAY RW ON (RP.ROADWAY_NUMBER = RW.ROADWAY_NUMBER AND RW.VALID_TO IS NULL AND RW.END_DATE IS NULL)
          where $boundingBoxFilter AND NP.node_number is null and NP.valid_to is null
        """
      queryList(query)
    }
  }

  def create(nodePoints: Iterable[NodePoint]): Seq[Long] = {

    // Set ids for the node points without one
    val (ready, idLess) = nodePoints.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchNodePointIds(idLess.size)
    val createNodePoints = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    val batchParams: Iterable[Seq[Any]] = createNodePoints.map {
      nodePoint =>
        Seq(
          nodePoint.id,
          nodePoint.beforeAfter.value,
          nodePoint.roadwayPointId,
          nodePoint.nodeNumber,
          nodePoint.nodePointType.value,
          nodePoint.createdBy
        )
    }

    val query =
      sql"""
            INSERT INTO NODE_POINT (ID, BEFORE_AFTER, ROADWAY_POINT_ID, NODE_NUMBER, TYPE, CREATED_BY)
            VALUES (?, ?, ?, ?, ?, ?)
      """


    runBatchUpdateToDb(query, batchParams.toSeq)

    // Return the ids of the created node points
    createNodePoints.map(_.id).toSeq
  }

  /**
   * Expires node points (set their valid_to to the current system date).
   *
   * @param ids : Iterable[Long] - The ids of the node points to expire.
   * @return
   */
  def expireById(ids: Iterable[Long]): Int = {
    val query =
      sql"""
        Update NODE_POINT Set valid_to = CURRENT_TIMESTAMP where valid_to IS NULL and id in ($ids)
      """
    if (ids.isEmpty)
      0
    else {
      logger.debug(s"Expiring by id: ${ids.mkString(", ")} \n    query: : $query")
      runUpdateToDb(query)
    }
  }

  def expireByNodeNumberAndType(nodeNumber: Long, nodePointType: NodePointType): Unit = {
    val query =
      sql"""
        Update NODE_POINT Set valid_to = CURRENT_TIMESTAMP where valid_to IS NULL AND node_number = $nodeNumber
        AND type = ${nodePointType.value}
      """
    logger.debug(s"Expiring by number and type: $nodeNumber, ${nodePointType.value} \n    query: : $query")
    runUpdateToDb(query)
  }

  def fetchCalculatedNodePointsForNodeNumber(nodeNumber: Long): Seq[NodePoint] = {
    val query =
      sql"""
       SELECT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NP.NODE_NUMBER, NP.TYPE,
            NULL AS START_DATE, NULL AS END_DATE,
            NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME,
            RP.ROADWAY_NUMBER, RP.ADDR_M,
            NULL as ROAD_NUMBER, NULL as ROAD_PART_NUMBER, NULL as TRACK, NULL as ELY
       FROM NODE_POINT NP
       JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
       where NP.valid_to is null and NP.node_number = $nodeNumber
       AND NP.type = 2
       ORDER BY NP.ID
     """.map(NodePoint.apply)

    runSelectQuery(query)
  }

  case class RoadPartInfo(roadPart: RoadPart, roadwayNumber: Long, beforeAfter: Long, roadwayPointId: Long, addrM: Long, track: Long, addrMRange: AddrMRange, roadwayId: Long)

  def fetchRoadPartsInfoForNode(nodeNumber: Long): Seq[RoadPartInfo] = {
    val query =
      sql"""
         SELECT R.ROAD_NUMBER, R.ROADWAY_NUMBER, R.ROAD_PART_NUMBER, JP.BEFORE_AFTER, JP.ROADWAY_POINT_ID, RP.ADDR_M,
                R.TRACK, R.START_ADDR_M, R.END_ADDR_M, R.ID
         FROM ROADWAY R, ROADWAY_POINT RP, JUNCTION_POINT JP, JUNCTION J
         WHERE J.NODE_NUMBER = $nodeNumber
         AND J.ID = JP.JUNCTION_ID
         AND JP.ROADWAY_POINT_ID = RP.ID
         AND R.ROADWAY_NUMBER = RP.ROADWAY_NUMBER
         AND JP.VALID_TO IS NULL
         AND J.VALID_TO IS NULL
         AND J.END_DATE IS NULL
         AND R.END_DATE IS NULL AND R.VALID_TO IS NULL
         AND (R.ROAD_NUMBER BETWEEN 1 AND 19999 OR R.ROAD_NUMBER BETWEEN 40000 AND 69999)
         ORDER BY R.ROAD_NUMBER, R.ROAD_PART_NUMBER, R.TRACK, R.ROADWAY_NUMBER
       """
    runSelectQuery(query.map(rs => RoadPartInfo(
      roadPart = RoadPart(
        rs.longOpt("road_number").getOrElse(0L),
        rs.longOpt("road_part_number").getOrElse(0L)
      ),
      roadwayNumber = rs.long("roadway_number"),
      beforeAfter = rs.long("before_after"),
      roadwayPointId = rs.long("roadway_point_id"),
      addrM = rs.long("addr_m"),
      track = rs.long("track"),
      addrMRange = AddrMRange(rs.long("start_addr_m"), rs.long("end_addr_m")),
      roadwayId = rs.long("id")
    )))
  }

  def fetchNodePointTemplateByRoadwayPointId(roadwayPointId: Long): Seq[NodePoint] = {
    val query =
      sql"""
     $selectFromNodePoint
     where NP.ROADWAY_POINT_ID = $roadwayPointId and NP.valid_to is null and NP.node_number is null
   """
    queryList(query)
  }

  def fetchNodePointsCountForRoadAndRoadPart(roadPart: RoadPart, beforeAfter: Long, nodeNumber: Long): Option[Long] = {
    val query =
      sql"""
          SELECT count(NP.ID)
          FROM ROADWAY R, ROADWAY_POINT RP, NODE_POINT NP, NODE N
          WHERE R.ROAD_NUMBER = ${roadPart.roadNumber}
          AND R.ROAD_PART_NUMBER = ${roadPart.partNumber}
          AND R.END_DATE IS NULL AND R.VALID_TO IS NULL
          AND RP.ROADWAY_NUMBER = R.ROADWAY_NUMBER
          AND NP.ROADWAY_POINT_ID = RP.ID
          AND NP.NODE_NUMBER IS NOT NULL
          AND NP.VALID_TO IS NULL
          AND NP.TYPE = 1
          AND NP.NODE_NUMBER = N.NODE_NUMBER
          AND N.END_DATE IS NULL
          AND N.VALID_TO IS NULL
          AND N.NODE_NUMBER = $nodeNumber
          GROUP BY R.ROAD_PART_NUMBER
          ORDER BY R.ROAD_PART_NUMBER
       """
    runSelectSingleFirstOptionWithType[Long](query)
  }

  def fetchAverageAddrM(roadPart: RoadPart, nodeNumber: Long): Long = {
    val query =
      sql"""
        SELECT AVG(RP.ADDR_M)
        FROM ROADWAY_POINT RP
        INNER JOIN ROADWAY R
          ON (R.ROAD_NUMBER = ${roadPart.roadNumber}
            AND R.ROAD_PART_NUMBER = ${roadPart.partNumber}
            AND R.END_DATE IS NULL AND R.VALID_TO IS NULL
            AND RP.ROADWAY_NUMBER = R.ROADWAY_NUMBER)
        INNER JOIN JUNCTION_POINT JP
          ON (JP.ROADWAY_POINT_ID = RP.ID
            AND JP.VALID_TO IS NULL)
        INNER JOIN JUNCTION J
          ON (J.ID = JP.JUNCTION_ID
            AND J.VALID_TO IS NULL
            AND J.END_DATE IS NULL)
        INNER JOIN NODE N
          ON (J.NODE_NUMBER = N.NODE_NUMBER
            AND N.END_DATE IS NULL
            AND N.VALID_TO IS NULL
            AND N.NODE_NUMBER = $nodeNumber)
     """
    runSelectSingle(query.map(rs => rs.bigDecimal(1).longValue)) // The result for AVG is a BigDecimal
  }

  // Only for debugging
  def fetchAddrMForAverage(roadPart: RoadPart): Seq[NodePoint] = {
    val query =
      sql"""
          SELECT NULL AS ID, NULL, NULL, NULL, NULL, NULL, NULL,
          RP.CREATED_TIME, JP.VALID_TO, RP.CREATED_TIME, RP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M, R.ROAD_NUMBER, R.ROAD_PART_NUMBER, R.TRACK, R.ELY
          FROM ROADWAY_POINT RP
          INNER JOIN ROADWAY R
            ON (R.ROAD_NUMBER = ${roadPart.roadNumber}
              AND R.ROAD_PART_NUMBER = ${roadPart.partNumber}
              AND R.END_DATE IS NULL AND R.VALID_TO IS NULL
              AND RP.ROADWAY_NUMBER = R.ROADWAY_NUMBER)
          INNER JOIN JUNCTION_POINT JP
            ON (JP.ROADWAY_POINT_ID = RP.ID AND JP.VALID_TO IS NULL)
          INNER JOIN JUNCTION J
             ON (J.ID = JP.JUNCTION_ID
             AND JP.VALID_TO IS NULL
             AND J.VALID_TO IS NULL
             AND J.END_DATE IS NULL
             AND J.NODE_NUMBER IS NOT NULL)
          ORDER BY R.ROAD_NUMBER, R.ROAD_PART_NUMBER
       """
    queryList(query)
  }

  def insertRoadNodePoint(roadwayPointId: Long, beforeAfter: BeforeAfter, nodeNumber: Long, username: String): Unit = {
    create(Seq(NodePoint(NewIdValue, beforeAfter, roadwayPointId, Some(nodeNumber), NodePointType.RoadNodePoint,
      None, None, DateTime.now(), None,
      username, Some(DateTime.now()), 0L, 11,
      RoadPart(0, 0), null, 8)))
  }

  def insertCalculatedNodePoint(roadwayPointId: Long, beforeAfter: BeforeAfter, nodeNumber: Long, username: String): Unit = {
    create(Seq(NodePoint(NewIdValue, beforeAfter, roadwayPointId, Some(nodeNumber), NodePointType.CalculatedNodePoint,
      None, None, DateTime.now(), None,
      username, Some(DateTime.now()), 0L, 11,
      RoadPart(0, 0), null, 8)))
  }
}
