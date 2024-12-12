package fi.liikennevirasto.viite.dao

import java.sql.Timestamp
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.NewIdValue
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.postgis.GeometryDbUtils
import fi.vaylavirasto.viite.model.{NodePointType, NodeType, RoadPart}
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet


case class Node(id: Long, nodeNumber: Long, coordinates: Point, name: Option[String], nodeType: NodeType, startDate: DateTime, endDate: Option[DateTime], validFrom: DateTime, validTo: Option[DateTime],
                createdBy: String, createdTime: Option[DateTime], editor: Option[String] = None, publishedTime: Option[DateTime] = None, registrationDate: DateTime)

object Node extends SQLSyntaxSupport[Node] {

  def apply(rs: WrappedResultSet): Node = Node(
    id = rs.long("id"),
    nodeNumber = rs.long("node_number"),
    Point(rs.double("ST_X"), rs.double("ST_Y")),
    name = rs.stringOpt("name"),
    nodeType = NodeType(rs.int("type")),
    startDate = rs.jodaDateTime("start_date"),
    endDate = rs.jodaDateTimeOpt("end_date"),
    validFrom = rs.jodaDateTime("valid_from"),
    validTo = rs.jodaDateTimeOpt("valid_to"),
    createdBy = rs.string("created_by"),
    createdTime = rs.jodaDateTimeOpt("created_time"),
    editor = rs.stringOpt("editor"),
    publishedTime = rs.jodaDateTimeOpt("published_time"),
    registrationDate = rs.jodaDateTime("registration_date")
  )
}

case class RoadAttributes(roadPart: RoadPart, addrMValue: Long)

case class NodeForRoadAddressBrowser(ely: Long, roadPart: RoadPart, addrM: Long, startDate: DateTime, nodeType: NodeType, name: Option[String], nodeCoordinates: Point, nodeNumber: Long)

object NodeForRoadAddressBrowserScalike extends SQLSyntaxSupport[NodeForRoadAddressBrowser] {
  def apply(rs: WrappedResultSet): NodeForRoadAddressBrowser = NodeForRoadAddressBrowser(
    ely = rs.long("ely"),
    roadPart = RoadPart(rs.long("road_number"), rs.long("road_part_number")),
    addrM = rs.long("addr_m"),
    startDate = rs.jodaDateTime("start_date"),
    nodeType = NodeType(rs.int("type")),
    name = rs.stringOpt("name"),
    Point(rs.double("ST_X"), rs.double("ST_Y")),
    nodeNumber = rs.int("node_number")
  )
}

case class NodeWithJunctions(node: Node, junctionsWithCoordinates: Seq[JunctionWithCoordinateAndCrossingRoads])

class NodeDAO extends BaseDAO {

  private val n = Node.syntax("n")

  // Helper method to run select queries for Node objects
  private def queryList(query: SQL[Nothing, NoExtractor]): List[Node] = {
    runSelectQuery(query.map(Node.apply)).groupBy(_.id)
      .map { case (_, list) => list.head }
      .toList
  }
  private def querySingle(query: SQL[Nothing, NoExtractor]): Option[Node] = {
    runSelectSingleOption(query.map(Node.apply))
  }

  private lazy val selectAllFromNodeQuery = sqls"""
  SELECT id, node_number, ST_X(coordinates), ST_Y(coordinates), name, type, start_date,
         end_date, valid_from, valid_to, created_by, created_time, editor, published_time, registration_date
  FROM   node n
"""

  def fetchByNodeNumber(nodeNumber: Long): Option[Node] = {
    val query = sql"""
      $selectAllFromNodeQuery
      where node_number = $nodeNumber and valid_to IS NULL and end_date IS NULL
      """
    querySingle(query)
  }

  def fetchById(nodeId: Long): Option[Node] = {
    val query = sql"""
      $selectAllFromNodeQuery
      WHERE id = $nodeId and valid_to IS NULL and end_date IS NULL
      """
    querySingle(query)
  }

  def fetchId(nodeNumber: Long): Option[Long] = {
    val query = sql"""
      SELECT id
      FROM node
      WHERE node_number = $nodeNumber AND valid_to IS NULL AND end_date IS NULL
      """
    runSelectSingleOption(query.map(_.long("id"))) // Return the id
  }

  def fetchLatestId(nodeNumber: Long): Option[Long] = {
    val query = sql"""
      SELECT id
      FROM node
      WHERE node_number = $nodeNumber and valid_to IS NULL
      ORDER BY created_time DESC, end_date DESC
      """
    runSelectSingleOption(query.map(_.long("id"))) // Return the id
  }

  def fetchAllValidNodes(): Seq[Node] = {
    val query =
      sql"""
      SELECT  n.id, n.node_number, ST_X(n.coordinates), ST_Y(n.coordinates), n."name", n."type", n.start_date, n.end_date,
              n.valid_from, n.valid_to, n.created_by, n.created_time, n.editor, n.published_time, n.registration_date
      FROM node n
      WHERE n.end_date IS NULL AND n.valid_to IS NULL
    """
    queryList(query)
  }

  def fetchNodesForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long],
                                      minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[NodeForRoadAddressBrowser] = {
    val baseQuery =
      sqls"""
      SELECT DISTINCT rw.ely, rw.road_number, rw.road_part_number, rp.addr_m, node.start_date, node.type, node.name,
                      ST_X(node.coordinates), ST_Y(node.coordinates), node.node_number
		  FROM node node
      JOIN node_POINT np ON node.node_number = np.node_number AND np.valid_to IS NULL
      JOIN roadway_point rp ON np.roadway_point_id = rp.id
      JOIN roadway rw ON rp.roadway_number = rw.roadway_number AND rw.valid_to IS NULL AND rw.end_date IS null
      """

    def withOptionalParameters(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long],
                               minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long])(query: SQLSyntax): SQL[Nothing, NoExtractor] = {
      val dateCondition = situationDate.map { date => sqls"AND rw.start_date <= ${date}::date"}.getOrElse(sqls"")
      val elyCondition = ely.map(ely => sqls" AND rw.ely = $ely").getOrElse(sqls"")
      val roadNumberCondition = roadNumber.map(roadNumber => sqls" AND rw.road_number = $roadNumber").getOrElse(sqls"")

      val roadPartCondition = {
        val parts = (minRoadPartNumber, maxRoadPartNumber)
        parts match {
          case (Some(minPart), Some(maxPart)) => sqls"AND rw.road_part_number BETWEEN $minPart AND $maxPart"
          case (None, Some(maxPart)) => sqls"AND rw.road_part_number <= $maxPart"
          case (Some(minPart), None) => sqls"AND rw.road_part_number >= $minPart"
          case _ => sqls""
        }
      }

      sql"""
        $query
        WHERE node.valid_to IS NULL AND node.end_date IS NULL
        $dateCondition
        $elyCondition
        $roadNumberCondition
        $roadPartCondition
        ORDER BY rw.road_number, rw.road_part_number, rp.addr_m
        """
    }

    val queryWithOptionalParameters = withOptionalParameters(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)(baseQuery)
    runSelectQuery(queryWithOptionalParameters.map(NodeForRoadAddressBrowserScalike.apply))
  }

  def fetchByRoadAttributes(roadNumber: Long, minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[(Node, RoadAttributes)] = {
    val roadCondition = (minRoadPartNumber.isDefined, maxRoadPartNumber.isDefined) match {
      case (true, true) => sqls"AND rw.road_part_number >= ${minRoadPartNumber.get} AND rw.road_part_number <= ${maxRoadPartNumber.get}"
      case (true, _) => sqls"AND rw.road_part_number = ${minRoadPartNumber.get}"
      case (_, true) => sqls"AND rw.road_part_number = ${maxRoadPartNumber.get}"
      case _ => sqls.empty
    }

    val query =
      sql"""
        SELECT DISTINCT node.id, node.node_number, ST_X(node.coordinates), ST_Y(node.coordinates), node.name, node.type, node.start_date, node.end_date, node.valid_from, node.valid_to,
                        node.created_by, node.created_time, node.registration_date, node.editor, node.published_time, rw.road_number, rw.road_part_number, rp.addr_m
        FROM node
        JOIN node_POINT np ON node.node_number = np.node_number AND np.valid_to IS NULL
        JOIN roadway_point rp ON np.roadway_point_id = rp.id
        JOIN roadway rw ON rp.roadway_number = rw.roadway_number AND rw.valid_to IS NULL AND rw.end_date IS NULL
          WHERE node.valid_to IS NULL AND node.end_date IS NULL
          AND rw.road_number = $roadNumber $roadCondition
        ORDER BY rw.road_number, rw.road_part_number, rp.addr_m
      """

    runSelectQuery(query.map(rs => {
      val node = Node.apply(rs)
      val roadAttributes = RoadAttributes(
        roadPart = RoadPart(
          rs.long("road_number"),
          rs.long("road_part_number")
        ),
        addrMValue = rs.long("addr_m")
      )
      (node, roadAttributes)
    }))
  }

  def publish(id: Long, editor: String = "-"): Unit = {
    runUpdateToDb(sql"""
        UPDATE node SET published_time = CURRENT_TIMESTAMP, editor = $editor WHERE id = $id
    """)
  }

  def create(nodes: Iterable[Node], createdBy: String = "-"): Seq[Long] = {

    val query =
      sql"""
        INSERT INTO node (id, node_number, coordinates, name, type, start_date, end_date, created_by, registration_date)
        VALUES (?, ?, ST_GeomFromText(?, 3067), ?, ?, ?, ?, ?, ?)
      """

    // Set ids for the nodes without one
    val (ready, idLess) = nodes.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchNodeIds(idLess.size)
    val createNodes = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    val batchParams = createNodes.map { node =>
      val nodeNumber = if (node.nodeNumber == NewIdValue) {
        Sequences.nextNodeNumber
      } else {
        node.nodeNumber
      }
      Seq(
        node.id,
        nodeNumber,
        GeometryDbUtils.createRoundedPointGeometry(node.coordinates),
        node.name.orNull,
        node.nodeType.value,
        new java.sql.Date(node.startDate.getMillis),
        node.endDate.map(d => new java.sql.Date(d.getMillis)).orNull,
        if (createdBy == null) "-" else createdBy,
        new java.sql.Timestamp(node.registrationDate.getMillis)
      )
    }

    runBatchUpdateToDb(query, batchParams.toSeq)

    batchParams.map(_(1).asInstanceOf[Long]).toSeq // Return the node numbers
  }

  def fetchByBoundingBox(boundingRectangle: BoundingRectangle): Seq[Node] = {
    val extendedBoundingBoxRectangle = BoundingRectangle(
      boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(scalar = .15),
      boundingRectangle.rightTop   - boundingRectangle.diagonal.scale(scalar = .15)
    )
    val boundingBoxFilter = GeometryDbUtils.boundingBoxFilter(
      extendedBoundingBoxRectangle,
      geometryColumn = sqls"coordinates"
    )
    val query = sql"""
        $selectAllFromNodeQuery
        WHERE $boundingBoxFilter
        AND end_date IS NULL AND valid_to IS NULL
    """
    queryList(query)
  }

  /**
   * Expires nodes (set their valid_to to the current system date).
   *
   * @param ids : Iterable[Long] - The ids of the nodes to expire.
   * @return
   */
  def expireById(ids: Iterable[Long]): Unit = {
    if (ids.isEmpty)
      0
    else {
      val query = sql"""
        UPDATE node SET valid_to = CURRENT_TIMESTAMP WHERE valid_to IS NULL AND id IN ($ids)
      """
      logger.debug(s"******* Expiring nodes by ids: ${ids.mkString(", ")} \n    query: : $query")
      runUpdateToDb(query)
    }
  }

  def fetchEmptyNodes(nodeNumbers: Seq[Long]): Seq[Node] = {
    if (nodeNumbers.isEmpty) {
      Seq()
    } else {
      // TODO - Might be needed to check node point type here - since calculate node points should not be considered to identify empty nodes
      val query = sql"""
          $selectAllFromNodeQuery
          WHERE end_date IS NULL AND valid_to IS NULL AND node_number IN ($nodeNumbers)
          AND NOT EXISTS (
            SELECT NULL FROM junction j WHERE n.node_number = J.node_number AND J.valid_to IS NULL AND J.end_date IS NULL
          ) AND NOT EXISTS (
            SELECT NULL FROM node_point np WHERE n.node_number = np.node_number AND np.valid_to IS NULL AND np.type IN (${NodePointType.UnknownNodePointType.value}, ${NodePointType.RoadNodePoint.value})
          )
      """
      queryList(query)
    }
  }

  def fetchAllByDateRange(sinceDate: DateTime, untilDate: Option[DateTime]): Seq[Node] = {
    time(logger, "Fetch nodes by date range") {
      val untilString = if (untilDate.nonEmpty) s"AND published_time <= to_timestamp(${new Timestamp(untilDate.get.getMillis)}, YYYY-MM-DD HH24:MI:SS.FF)" else s""
      val query =
        sql"""
         $selectAllFromNodeQuery
         WHERE node_number IN (
              SELECT node_number
              FROM node NC
              WHERE published_time IS NOT NULL
                AND published_time >= to_timestamp(${new Timestamp(sinceDate.getMillis)}, YYYY-MM-DD HH24:MI:SS.FF)
                $untilString
                )
         AND valid_to IS NULL
       """
      queryList(query)
    }
  }

  // This query is designed to work in processing roadway_changes in phase where roadway_changes contains changes but other tables do not contain any updates yet
  // First and third union part handles project changes and second and forth part handles terminations
  // First and second union part finds nodes via junctions and third and fourth union find nodes via node_points
  // We find also nodes that have end_date not null to support change detection for tierekisteri for terminated nodes
  def fetchNodeNumbersByProject(projectId: Long): Seq[Long] = {
    val query =
      sql"""
         SELECT DISTINCT n.node_number
         FROM node n
         INNER JOIN junction j
           ON n.node_number = J.node_number
         INNER JOIN junction_point jp
           ON j.id = jp.junction_id
         INNER JOIN roadway_point rp
           ON jp.roadway_point_id = rp.id
         INNER JOIN roadway r
           ON rp.roadway_number = r.roadway_number
         INNER JOIN roadway_changes rc
           ON (r.road_number = rc.new_road_number
             AND r.road_part_number = rc.NEW_road_part_number)
         WHERE rc.project_id = $projectId
         AND r.valid_to IS NULL
         AND r.end_date IS NULL
         AND jp.valid_to IS NULL
         AND n.valid_to IS NULL
       UNION
         SELECT DISTINCT n.node_number
         FROM node n
         INNER JOIN junction j
           ON n.node_number = J.node_number
         INNER JOIN junction_point jp
           ON j.id = jp.junction_id
         INNER JOIN roadway_point rp
           ON jp.roadway_point_id = rp.id
         INNER JOIN roadway r
           ON rp.roadway_number = r.roadway_number
         INNER JOIN roadway_changes rc
           ON (r.road_number = rc.old_road_number AND rc.new_road_number IS NULL
             AND r.road_part_number = rc.old_road_part_number)
         WHERE rc.project_id = $projectId
         AND r.valid_to IS NULL
         AND r.end_date IS NULL
         AND jp.valid_to IS NULL
         AND n.valid_to IS NULL
       UNION
         SELECT DISTINCT n.node_number
         FROM node n
         INNER JOIN node_point np
           ON n.node_number = np.node_number
         INNER JOIN roadway_point rp
           ON np.roadway_point_id = rp.id
         INNER JOIN roadway r
           ON rp.roadway_number = r.roadway_number
         INNER JOIN roadway_changes rc
           ON (r.road_number = rc.new_road_number
             AND r.road_part_number = rc.NEW_road_part_number)
         WHERE rc.project_id = $projectId
         AND r.valid_to IS NULL
         AND r.end_date IS NULL
         AND np.valid_to IS NULL
         AND n.valid_to IS NULL
       UNION
         SELECT DISTINCT n.node_number
         FROM node N
         INNER JOIN node_point np
           ON n.node_number = np.node_number
         INNER JOIN roadway_point rp
           ON np.roadway_point_id = rp.id
         INNER JOIN roadway r
           ON rp.roadway_number = r.roadway_number
         INNER JOIN roadway_changes rc
           ON (r.road_number = rc.old_road_number AND rc.new_road_number IS NULL
             AND r.road_part_number = rc.old_road_part_number)
         WHERE rc.project_id = $projectId
         AND r.valid_to IS NULL
         AND r.end_date IS NULL
         AND np.valid_to IS NULL
         AND n.valid_to IS NULL
               """
    runSelectQuery(query.map(rs => rs.long(1)))
  }

}
