package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.NewIdValue
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.postgis.{GeometryDbUtils, PostGISDatabaseScalikeJDBC}
import fi.vaylavirasto.viite.model.{AddrMRange, BeforeAfter, NodePointType, RoadPart, Track}
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet


case class NodePoint(id: Long, beforeAfter: BeforeAfter, roadwayPointId: Long, nodeNumber: Option[Long], nodePointType: NodePointType = NodePointType.UnknownNodePointType,
                     startDate: Option[DateTime], endDate: Option[DateTime], validFrom: DateTime, validTo: Option[DateTime],
                     createdBy: String, createdTime: Option[DateTime], roadwayNumber: Long, addrM : Long,
                     roadPart: RoadPart, track: Track, elyCode: Long, coordinates: Point = Point(0.0, 0.0))

object NodePoint extends SQLSyntaxSupport[NodePoint] {
  def apply(rs: WrappedResultSet): NodePoint = NodePoint(
    id                = rs.long("id"),
    beforeAfter       = BeforeAfter(rs.int("before_after")),
    roadwayPointId    = rs.long("roadway_point_id"),
    nodeNumber        = rs.longOpt("node_number"),
    nodePointType     = NodePointType(rs.int("type")),
    startDate         = rs.jodaDateTimeOpt("start_date"),
    endDate           = rs.jodaDateTimeOpt("end_date"),
    validFrom         = rs.jodaDateTime("valid_from"),
    validTo           = rs.jodaDateTimeOpt("valid_to"),
    createdBy         = rs.string("created_by"),
    createdTime       = rs.jodaDateTimeOpt("created_time"),
    roadwayNumber     = rs.long("roadway_number"),
    addrM             = rs.long("addr_m"),
    roadPart = RoadPart(
      roadNumber      = rs.longOpt("road_number").map(l       => l).getOrElse(0L),
      partNumber      = rs.longOpt("road_part_number").map(l  => l).getOrElse(0L)
    ),
    track             = rs.longOpt("track").map(l  => Track.apply(l.toInt)).getOrElse(Track.Unknown),
    elyCode           = rs.longOpt("ely").map(l    => l).getOrElse(0L)
  )
}

class NodePointDAO extends BaseDAO {

  private def queryList(query: SQL[Nothing, NoExtractor]): List[NodePoint] = {
    runSelectQuery(query.map(NodePoint.apply))
      .groupBy(_.id)
      .map {case  (_, nodePoints) => nodePoints.head} // Remove duplicates
      .toList
  }

  /** Select/join clause for retrieving joined nodepoint~roadway_point~roadway, node~nodepoint data, where:
   * <li>nodepoint must match preserved roadway_point by roadway_point_id, AS well AS </li>
   * <li>roadway_point must match preserved roadway by roadway_number, AND roadway must be eligible (end_date, AND valid_to must be nulls) for it to match. </li>
   * <li>The existence of the corresponding node is not required, but it is serached by node_number matching
   *     that of node_point, AND it must be still eligible for it to match.</li> */
  lazy val selectFromNodePoint = sqls"""
                             SELECT np.id, np.before_after, np.roadway_point_id, np.node_number, np.type, n.start_date, n.end_date,
                                np.valid_from, np.valid_to, np.created_by, np.created_time, rp.roadway_number, rp.addr_m,
                                rw.road_number, rw.road_part_number, rw.track, rw.ely
                             FROM node_point np
                             JOIN roadway_point rp ON (rp.id = roadway_point_id)
                             LEFT OUTER JOIN node n ON (n.node_number = np.node_number AND n.valid_to IS NULL AND n.end_date IS NULL)
                             JOIN roadway rW on (rw.roadway_number = rp.roadway_number AND rw.end_date IS NULL AND rw.valid_to IS NULL)
                             """

  def fetchByIds(ids: Seq[Long]): Seq[NodePoint] = {
    if (ids.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
            $selectFromNodePoint
            WHERE np.id IN ($ids) AND np.valid_to IS NULL
         """
      queryList(query)
    }
  }

  def fetchByNodeNumber(nodeNumber: Long): Seq[NodePoint] = {
    fetchByNodeNumbers(Seq(nodeNumber))
  }

  /** Retrieves those eligible (valid_to IS NULL) NodePoints, whose node_number matches any of  those IN <i>nodeNumbers</i>.
   * Uses {@link selectFromNodePoint} AS the select/join clause. */
  def fetchByNodeNumbers(nodeNumbers: Seq[Long]): Seq[NodePoint] = {
    if (nodeNumbers.isEmpty) Seq()
    else {
      val query =
        sql"""
         $selectFromNodePoint
         WHERE n.node_number IN ($nodeNumbers) AND np.valid_to IS NULL
       """
      queryList(query)
    }
  }

  /** Retrieves those NodePoints, whose roadway_point_id matches any of  those IN <i>roadwayPointIds</i>.
   * Uses altered version of {@link selectFromNodePoint}, WHERE roadway needs NOT to be eligible.
   * Gets joined NodePoint, Node, RoadwayPoint, AND Roadway info where
   * nodePoint~roadPoint~roadway, AND nodePoint~node,
   * AND <b>nodePoint is still eligible</b> (end_date, AND valid_to are nulls) */
  def fetchByRoadwayPointIds(roadwayPointIds: Seq[Long]): Seq[NodePoint] = {
    if (roadwayPointIds.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
          SELECT np.id, np.before_after, np.roadway_point_id, np.node_number, np.type, n.start_date, n.end_date,
            np.valid_from, np.valid_to, np.created_by, np.created_time, rp.roadway_number, rp.addr_m,
            rw.road_number, rw.road_part_number, rw.track, rw.ely
          FROM node_point np
          JOIN roadway_point rp ON (rp.id = roadway_point_id)
          LEFT OUTER JOIN node n ON (n.node_number = np.node_number AND n.valid_to IS NULL AND n.end_date IS NULL)
          JOIN roadway rw ON (rw.roadway_number = rp.roadway_number)
          WHERE np.roadway_point_id IN ($roadwayPointIds) AND np.valid_to IS NULL
        """
      logger.debug(s"******* Querying by roadwaypointId.s: ${roadwayPointIds.mkString(", ")} \n    query: : $query")
      queryList(query)
    }
  }

  /** Gets joined NodePoint, Node, RoadwayPoint, AND Roadway info where
   * nodePoint~roadPoint~roadway, AND nodePoint~node,
   * AND nodePoint is still eligible (end_date, AND valid_to are nulls)
   * <b>but there is no more corresponding roadway available.</b>*/
  def fetchRoadwiseOrphansByRoadwayPointIds(roadwayPointIds: Seq[Long]): Seq[NodePoint] = {
    if (roadwayPointIds.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
           SELECT np.id, np.before_after, np.roadway_point_id, np.node_number, np.type,
                  n.start_date, n.end_date,
                  np.valid_from, np.valid_to, np.created_by, np.created_time,
                  rp.roadway_number, rp.addr_m,
                  rw.road_number, rw.road_part_number, rw.track, rw.ely
           FROM            node_point np
           INNER JOIN      roadway_point rp ON (rp.id = roadway_point_id)
           LEFT OUTER JOIN node n           ON (n.node_number = np.node_number AND n.valid_to IS NULL AND n.end_date IS NULL)
           LEFT OUTER JOIN roadway rw       ON (rw.roadway_number = rp.roadway_number)
           WHERE           np.roadway_point_id IN ($roadwayPointIds) AND np.valid_to IS NULL
                           AND rw.roadway_number IS NULL
        """
      logger.debug(s"******* Querying Roadwaywise orphan Nodes by roadwaypointId.s: ${roadwayPointIds.mkString(", ")} \n    query: : $query")
      queryList(query)
    }
  }

  /** Get the eligible (valid_to IS NULL) NodePoints that have the given roadwayPointId. */
  def fetchByRoadwayPointId(roadwayPointId: Long): Seq[NodePoint] = {
    val query =
      sql"""
     $selectFromNodePoint
     WHERE np.roadway_point_id = $roadwayPointId AND np.valid_to IS NULL
   """
    queryList(query)
  }

  /** Get the eligible (valid_to IS NULL) NodePoints AND NodePoint templates that have the given roadwayNumber. */
  def fetchByRoadwayNumber(roadwayNumber: Long): Seq[NodePoint] = {
    val query =
      sql"""
         $selectFromNodePoint
         WHERE rp.roadway_number = $roadwayNumber AND np.valid_to IS NULL
       """
    queryList(query)
  }

  /** Get the eligible (valid_to IS NULL) NodePoints that have a roadway number belonging to
   * the given roadwayNumbers sequence . */
  def fetchRoadAddressNodePoints(roadwayNumbers: Seq[Long]): Seq[NodePoint] = {
    if (roadwayNumbers.isEmpty) Seq()
    else {
      val query =
        sql"""
         $selectFromNodePoint
         WHERE rp.roadway_number IN ($roadwayNumbers) AND np.valid_to IS NULL
         AND np.type = ${NodePointType.RoadNodePoint.value}
       """
      queryList(query)
    }
  }

  def fetchTemplatesByRoadwayNumber(roadwayNumber: Long): List[NodePoint] = {
    val query =
      sql"""
        SELECT  np.id, np.before_after, np.roadway_point_id, np.node_number, np.type,
                NULL AS start_date, NULL AS end_date,
                np.valid_from, np.valid_to, np.created_by, np.created_time, rp.roadway_number, rp.addr_m,
                NULL AS road_number, NULL AS road_part_number, NULL AS ElY, NULL AS track
        FROM node_point np
        JOIN roadway_point rp ON (rp.id = roadway_point_id)
        JOIN roadway rW ON (rp.roadway_number = rw.roadway_number AND rw.end_date is NULL AND rw.valid_to IS NULL)
        WHERE rp.roadway_number = $roadwayNumber AND np.valid_to IS NULL AND np.node_number IS NULL
      """
    queryList(query)
  }

  def fetchTemplatesByRoadwayNumbers(roadwayNumbers: Set[Long]): List[NodePoint] = {
    val query =
      if (roadwayNumbers.isEmpty) {
        sql""
      } else {
        sql"""
          SELECT  np.id, np.before_after, np.roadway_point_id, np.node_number, np.type, NULL, NULL,
                  np.valid_from, np.valid_to, np.created_by, np.created_time, rp.roadway_number, rp.addr_m, rw.road_number, rw.road_part_number, rw.track, rw.ely
          FROM node_point np
          JOIN roadway_point rp ON (rp.id = roadway_point_id)
          JOIN roadway rW ON (rp.roadway_number = rw.roadway_number AND rw.end_date is NULL AND rw.valid_to IS NULL)
          WHERE rp.roadway_number IN ($roadwayNumbers) AND np.valid_to IS NULL AND np.node_number IS NULL
       """
      }
    queryList(query)
  }

  def fetchTemplates() : Seq[NodePoint] = {
    val query =
      sql"""
         SELECT DISTINCT  np.id, np.before_after, np.roadway_point_id, NULL AS node_number, np.type, NULL AS start_date,
                          NULL AS end_date, np.valid_from, np.valid_to, np.created_by, np.created_time, rp.roadway_number,
                          rp.addr_m, rw.road_number, rw.road_part_number, rw.track, rw.ely
         FROM node_point np
         JOIN roadway_point rp ON (rp.id = roadway_point_id)
         JOIN linear_location ll ON (ll.roadway_number = rp.roadway_number AND ll.valid_to IS NULL)
         JOIN roadway rW ON (rp.roadway_number = rw.roadway_number AND rw.end_date is NULL AND rw.valid_to IS NULL)
         WHERE np.valid_to IS NULL AND np.node_number IS NULL
       """
    queryList(query)
  }

  def fetchNodePointTemplateById(id: Long): Option[NodePoint] = {
    val query =
      sql"""
         SELECT DISTINCT np.id, np.before_after, np.roadway_point_id, np.node_number, np.type,
                NULL AS start_date, NULL AS end_date, np.valid_from, np.valid_to, np.created_by, np.created_time,
                rp.roadway_number, rp.addr_m, rw.road_number, rw.road_part_number, rw.track, rw.ely
         FROM node_point np
         JOIN roadway_point rp ON (rp.id = roadway_point_id)
         JOIN linear_location ll ON (ll.roadway_number = rp.roadway_number AND ll.valid_to IS NULL)
         JOIN roadway rw ON (rp.roadway_number = rw.roadway_number AND rw.end_date is NULL AND rw.valid_to IS NULL)
         WHERE np.id = $id AND np.node_number IS NULL AND np.valid_to IS NULL
       """
    runSelectSingleOption(query.map(NodePoint.apply))
  }

  def fetchTemplatesByBoundingBox(boundingRectangle: BoundingRectangle): Seq[NodePoint] = {
    time(logger, "Fetch NodePoint templates by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))

      val boundingBoxFilter = GeometryDbUtils.boundingBoxFilter(extendedBoundingRectangle, sqls"ll.geometry")

      val query =
        sql"""
          SELECT  np.id, np.before_after, np.roadway_point_id, np.node_number, np.type, NULL AS start_date, NULL AS end_date,
                  np.valid_from, np.valid_to, np.created_by, np.created_time, rp.roadway_number, rp.addr_m, rw.road_number,
                  rw.road_part_number, rw.track, rw.ely
          FROM node_point np
          JOIN roadway_point rp ON (rp.id = roadway_point_id)
          JOIN linear_location ll ON (ll.roadway_number = rp.roadway_number AND ll.valid_to IS NULL)
          JOIN roadway rW ON (rp.roadway_number = rw.roadway_number AND rw.valid_to IS NULL AND rw.end_date IS NULL)
          WHERE $boundingBoxFilter AND np.node_number IS NULL AND np.valid_to IS NULL
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
            INSERT INTO NODE_POINT (ID, before_after, roadway_point_id, node_number, type, created_by)
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
        UPDATE NODE_POINT SET valid_to = CURRENT_TIMESTAMP WHERE valid_to IS NULL AND id IN ($ids)
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
        UPDATE node_point SET valid_to = CURRENT_TIMESTAMP WHERE valid_to IS NULL AND node_number = $nodeNumber
        AND type = ${nodePointType.value}
      """
    logger.debug(s"Expiring by number AND type: $nodeNumber, ${nodePointType.value} \n    query: : $query")
    runUpdateToDb(query)
  }

  def fetchCalculatedNodePointsForNodeNumber(nodeNumber: Long): Seq[NodePoint] = {
    val query =
      sql"""
       SELECT np.id, np.before_after, np.roadway_point_id, np.node_number, np.type,
            NULL AS start_date, NULL AS end_date,
            np.valid_from, np.valid_to, np.created_by, np.created_time,
            rp.roadway_number, rp.addr_m,
            NULL AS road_number, NULL AS road_part_number, NULL AS track, NULL AS ely
       FROM node_point np
       JOIN roadway_point rp ON (rp.id = roadway_point_id)
       WHERE np.valid_to IS NULL AND np.node_number = $nodeNumber
       AND np.type = 2
       ORDER BY np.id
     """.map(NodePoint.apply)

    runSelectQuery(query)
  }

  case class RoadPartInfo(roadPart: RoadPart, roadwayNumber: Long, beforeAfter: Long, roadwayPointId: Long, addrM: Long, track: Long, addrMRange: AddrMRange, roadwayId: Long)

  def fetchRoadPartsInfoForNode(nodeNumber: Long): Seq[RoadPartInfo] = {
    val query =
      sql"""
         SELECT r.road_number, r.roadway_number, r.road_part_number, jp.before_after, jp.roadway_point_id, rp.addr_m,
                r.track, r.start_addr_m, r.end_addr_m, r.id
         FROM roadway r, roadway_point rp, junction_point jp, junction j
         WHERE j.node_number = $nodeNumber
         AND j.id = jp.junction_id
         AND jp.roadway_point_id = rp.id
         AND r.roadway_number = rp.roadway_number
         AND jp.valid_to IS NULL
         AND j.valid_to IS NULL
         AND j.end_date IS NULL
         AND r.end_date IS NULL AND r.valid_to IS NULL
         AND (r.road_number BETWEEN 1 AND 19999 OR r.road_number BETWEEN 40000 AND 69999)
         ORDER BY r.road_number, r.road_part_number, r.track, r.roadway_number
       """
    runSelectQuery(query.map(rs => RoadPartInfo(
      roadPart        = RoadPart(
        roadNumber    = rs.longOpt("road_number").getOrElse(0L),
        partNumber    = rs.longOpt("road_part_number").getOrElse(0L)
      ),
      roadwayNumber   = rs.long("roadway_number"),
      beforeAfter     = rs.long("before_after"),
      roadwayPointId  = rs.long("roadway_point_id"),
      addrM           = rs.long("addr_m"),
      track           = rs.long("track"),
      addrMRange      = AddrMRange(
        start         = rs.long("start_addr_m"),
        end           = rs.long("end_addr_m")
      ),
      roadwayId       = rs.long("id")
    )))
  }

  def fetchNodePointTemplateByRoadwayPointId(roadwayPointId: Long): Seq[NodePoint] = {
    val query =
      sql"""
     $selectFromNodePoint
     WHERE np.roadway_point_id = $roadwayPointId AND np.valid_to IS NULL AND np.node_number IS NULL
   """
    queryList(query)
  }

  def fetchNodePointsCountForRoadAndRoadPart(roadPart: RoadPart, beforeAfter: Long, nodeNumber: Long): Option[Long] = {
    val query =
      sql"""
          SELECT COUNT(np.id)
          FROM roadway r, roadway_point rp, node_point np, node n
          WHERE r.road_number = ${roadPart.roadNumber}
            AND r.road_part_number = ${roadPart.partNumber}
            AND r.end_date IS NULL AND r.valid_to IS NULL
            AND rp.roadway_number = r.roadway_number
            AND np.roadway_point_id = rp.id
            AND np.node_number IS NOT NULL
            AND np.valid_to IS NULL
            AND np.type = 1
            AND np.node_number = n.node_number
            AND n.end_date IS NULL
            AND n.valid_to IS NULL
            AND n.node_number = $nodeNumber
          GROUP BY r.road_part_number
          ORDER BY r.road_part_number
       """
    runSelectSingleFirstOptionWithType[Long](query)
  }

  def fetchAverageAddrM(roadPart: RoadPart, nodeNumber: Long): Long = {
    val query =
      sql"""
        SELECT AVG(rp.addr_m)
        FROM roadway_point rp
        INNER JOIN roadway r
          ON (r.road_number = ${roadPart.roadNumber}
            AND r.road_part_number = ${roadPart.partNumber}
            AND r.end_date IS NULL AND r.valid_to IS NULL
            AND rp.roadway_number = r.roadway_number)
        INNER JOIN junction_point jp
          ON (jp.roadway_point_id = rp.id
            AND jp.valid_to IS NULL)
        INNER JOIN junction j
          ON (j.id = jp.junction_id
            AND j.valid_to IS NULL
            AND j.end_date IS NULL)
        INNER JOIN node n
          ON (j.node_number = n.node_number
            AND n.end_date IS NULL
            AND n.valid_to IS NULL
            AND n.node_number = $nodeNumber)
     """
    runSelectSingle(query.map(rs => rs.bigDecimal(1).longValue)) // The result for AVG is a BigDecimal
  }

  // Only for debugging
  def fetchAddrMForAverage(roadPart: RoadPart): Seq[NodePoint] = {
    val query =
      sql"""
          SELECT  NULL AS id, NULL AS before_after, NULL AS roadway_point_id, NULL AS node_number, NULL AS type, NULL AS start_date, NULL AS end_date,
                  rp.created_time, jp.valid_to, rp.created_time, rp.created_time, rp.roadway_number, rp.addr_m, r.road_number, r.road_part_number, r.track, r.ely
          FROM roadway_point rp
          INNER JOIN roadway r
            ON (r.road_number = ${roadPart.roadNumber}
              AND r.road_part_number = ${roadPart.partNumber}
              AND r.end_date IS NULL AND r.valid_to IS NULL
              AND rp.roadway_number = r.roadway_number)
          INNER JOIN junction_point jp
            ON (jp.roadway_point_id = rp.id AND jp.valid_to IS NULL)
          INNER JOIN junction j
             ON (j.id = jp.junction_id
             AND jp.valid_to IS NULL
             AND j.valid_to IS NULL
             AND j.end_date IS NULL
             AND j.node_number IS NOT NULL)
          ORDER BY r.road_number, r.road_part_number
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
