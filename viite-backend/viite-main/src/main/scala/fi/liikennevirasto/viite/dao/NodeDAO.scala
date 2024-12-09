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
  override val tableName = "NODE"

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
      where NODE_NUMBER = $nodeNumber and valid_to is null and end_date is null
      """
    querySingle(query)
  }

  def fetchById(nodeId: Long): Option[Node] = {
    val query = sql"""
      $selectAllFromNodeQuery
      where ID = $nodeId and valid_to is null and end_date is null
      """
    querySingle(query)
  }

  def fetchId(nodeNumber: Long): Option[Long] = {
    val query = sql"""
      SELECT ID
      from NODE
      where NODE_NUMBER = $nodeNumber and valid_to is null and end_date is null
      """
    runSelectSingleOption(query.map(_.long("id"))) // Return the id
  }

  def fetchLatestId(nodeNumber: Long): Option[Long] = {
    val query = sql"""
      SELECT ID
      from NODE
      where NODE_NUMBER = $nodeNumber and valid_to is null
      order by created_time desc, end_date desc
      """
    runSelectSingleOption(query.map(_.long("id"))) // Return the id
  }

  def fetchAllValidNodes(): Seq[Node] = {
    val query =
      sql"""
      SELECT n.id, n.node_number, ST_X(n.COORDINATES), ST_Y(n.COORDINATES), n."name", n."type", n.start_date, n.end_date, n.valid_from, n.valid_to, n.created_by, n.created_time, n.editor, n.published_time, n.registration_date
      FROM node n
      WHERE n.end_date IS NULL AND n.valid_to IS NULL
    """
    queryList(query)
  }

  def fetchNodesForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long],
                                      minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[NodeForRoadAddressBrowser] = {
    val baseQuery =
      sqls"""
      SELECT DISTINCT rw.ely, rw.ROAD_NUMBER, rw.ROAD_PART_NUMBER, rp.ADDR_M, node.START_DATE, node.type, node.NAME, ST_X(node.COORDINATES), ST_Y(node.COORDINATES), node.NODE_NUMBER
		  FROM NODE node
      JOIN NODE_POINT np ON node.NODE_NUMBER = np.NODE_NUMBER AND np.VALID_TO IS NULL
      JOIN ROADWAY_POINT rp ON np.ROADWAY_POINT_ID = rp.ID
      JOIN ROADWAY rw ON rp.ROADWAY_NUMBER = rw.ROADWAY_NUMBER AND rw.VALID_TO IS NULL AND rw.END_DATE IS null
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

      sql"""$query WHERE node.VALID_TO IS NULL AND node.END_DATE IS NULL
        $dateCondition $elyCondition $roadNumberCondition $roadPartCondition
        ORDER BY rw.ROAD_NUMBER, rw.ROAD_PART_NUMBER, rp.ADDR_M"""
    }

    val queryWithOptionalParameters = withOptionalParameters(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)(baseQuery)
    runSelectQuery(queryWithOptionalParameters.map(NodeForRoadAddressBrowserScalike.apply))
  }

  def fetchByRoadAttributes(roadNumber: Long, minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[(Node, RoadAttributes)] = {
    val roadCondition = (minRoadPartNumber.isDefined, maxRoadPartNumber.isDefined) match {
      case (true, true) => sqls"AND rw.ROAD_PART_NUMBER >= ${minRoadPartNumber.get} AND rw.ROAD_PART_NUMBER <= ${maxRoadPartNumber.get}"
      case (true, _) => sqls"AND rw.ROAD_PART_NUMBER = ${minRoadPartNumber.get}"
      case (_, true) => sqls"AND rw.ROAD_PART_NUMBER = ${maxRoadPartNumber.get}"
      case _ => sqls.empty
    }

    val query =
      sql"""
        SELECT DISTINCT node.ID, node.NODE_NUMBER, ST_X(node.COORDINATES), ST_Y(node.COORDINATES), node.NAME, node.TYPE, node.START_DATE, node.END_DATE, node.VALID_FROM, node.VALID_TO,
                        node.CREATED_BY, node.CREATED_TIME, node.REGISTRATION_DATE, node.EDITOR, node.PUBLISHED_TIME, rw.ROAD_NUMBER, rw.ROAD_PART_NUMBER, rp.ADDR_M
        FROM NODE node
        JOIN NODE_POINT np ON node.NODE_NUMBER = np.NODE_NUMBER AND np.VALID_TO IS NULL
        JOIN ROADWAY_POINT rp ON np.ROADWAY_POINT_ID = rp.ID
        JOIN ROADWAY rw ON rp.ROADWAY_NUMBER = rw.ROADWAY_NUMBER AND rw.VALID_TO IS NULL AND rw.END_DATE IS NULL
          WHERE node.VALID_TO IS NULL AND node.END_DATE IS NULL
          AND rw.ROAD_NUMBER = $roadNumber $roadCondition
        ORDER BY rw.ROAD_NUMBER, rw.ROAD_PART_NUMBER, rp.ADDR_M
      """

    runSelectQuery(query.map(rs => {
      val node = Node.apply(rs)
      val roadAttributes = RoadAttributes(
        roadPart = RoadPart(rs.long("ROAD_NUMBER"), rs.long("ROAD_PART_NUMBER")),
        addrMValue = rs.long("ADDR_M")
      )
      (node, roadAttributes)
    }))
  }

  def publish(id: Long, editor: String = "-"): Unit = {
    runUpdateToDb(sql"""
        Update NODE Set PUBLISHED_TIME = CURRENT_TIMESTAMP, EDITOR = $editor Where ID = $id
    """)
  }

  def create(nodes: Iterable[Node], createdBy: String = "-"): Seq[Long] = {

    val query =
      sql"""insert into NODE (ID, NODE_NUMBER, COORDINATES, NAME, TYPE, START_DATE, END_DATE, CREATED_BY, REGISTRATION_DATE)
      values (?, ?, ST_GeomFromText(?, 3067), ?, ?, ?, ?, ?, ?)"""

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
        AND END_DATE IS NULL AND VALID_TO IS NULL
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
        UPDATE NODE SET valid_to = CURRENT_TIMESTAMP WHERE valid_to IS NULL AND id IN ($ids)
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
          WHERE END_DATE IS NULL AND VALID_TO IS NULL AND NODE_NUMBER IN ($nodeNumbers)
          AND NOT EXISTS (
            SELECT NULL FROM JUNCTION J WHERE N.NODE_NUMBER = J.NODE_NUMBER AND J.VALID_TO IS NULL AND J.END_DATE IS NULL
          ) AND NOT EXISTS (
            SELECT NULL FROM NODE_POINT NP WHERE N.NODE_NUMBER = NP.NODE_NUMBER AND NP.VALID_TO IS NULL AND NP.TYPE IN (${NodePointType.UnknownNodePointType.value}, ${NodePointType.RoadNodePoint.value})
          )
      """
      queryList(query)
    }
  }

  def fetchAllByDateRange(sinceDate: DateTime, untilDate: Option[DateTime]): Seq[Node] = {
    time(logger, "Fetch nodes by date range") {
      val untilString = if (untilDate.nonEmpty) s"AND PUBLISHED_TIME <= to_timestamp(${new Timestamp(untilDate.get.getMillis)}, YYYY-MM-DD HH24:MI:SS.FF)" else s""
      val query =
        sql"""
         $selectAllFromNodeQuery
         WHERE NODE_NUMBER IN (SELECT NODE_NUMBER FROM NODE NC WHERE
         PUBLISHED_TIME IS NOT NULL AND PUBLISHED_TIME >= to_timestamp(${new Timestamp(sinceDate.getMillis)}, YYYY-MM-DD HH24:MI:SS.FF)
         $untilString)
         AND VALID_TO IS NULL
       """
      queryList(query)
    }
  }

  // This query is designed to work in processing ROADWAY_CHANGES in phase where ROADWAY_CHANGES contains changes but other tables do not contain any updates yet
  // First and third union part handles project changes and second and forth part handles terminations
  // First and second union part finds nodes via junctions and third and fourth union find nodes via node_points
  // We find also nodes that have end_date not null to support change detection for tierekisteri for terminated nodes
  def fetchNodeNumbersByProject(projectId: Long): Seq[Long] = {
    val query =
      sql"""
         SELECT DISTINCT N.NODE_NUMBER
         FROM NODE N
         INNER JOIN JUNCTION J
           ON N.NODE_NUMBER = J.NODE_NUMBER
         INNER JOIN JUNCTION_POINT JP
           ON J.ID = JP.JUNCTION_ID
         INNER JOIN ROADWAY_POINT RP
           ON JP.ROADWAY_POINT_ID = RP.ID
         INNER JOIN ROADWAY R
           ON RP.ROADWAY_NUMBER = R.ROADWAY_NUMBER
         INNER JOIN ROADWAY_CHANGES RC
           ON (R.ROAD_NUMBER = RC.NEW_ROAD_NUMBER
             AND R.ROAD_PART_NUMBER = RC.NEW_ROAD_PART_NUMBER)
         WHERE RC.PROJECT_ID = $projectId
         AND R.VALID_TO IS NULL
         AND R.END_DATE IS NULL
         AND JP.VALID_TO IS NULL
         AND N.VALID_TO IS NULL
       UNION
         SELECT DISTINCT N.NODE_NUMBER
         FROM NODE N
         INNER JOIN JUNCTION J
           ON N.NODE_NUMBER = J.NODE_NUMBER
         INNER JOIN JUNCTION_POINT JP
           ON J.ID = JP.JUNCTION_ID
         INNER JOIN ROADWAY_POINT RP
           ON JP.ROADWAY_POINT_ID = RP.ID
         INNER JOIN ROADWAY R
           ON RP.ROADWAY_NUMBER = R.ROADWAY_NUMBER
         INNER JOIN ROADWAY_CHANGES RC
           ON (R.ROAD_NUMBER = RC.OLD_ROAD_NUMBER AND RC.NEW_ROAD_NUMBER IS NULL
             AND R.ROAD_PART_NUMBER = RC.OLD_ROAD_PART_NUMBER)
         WHERE RC.PROJECT_ID = $projectId
         AND R.VALID_TO IS NULL
         AND R.END_DATE IS NULL
         AND JP.VALID_TO IS NULL
         AND N.VALID_TO IS NULL
       UNION
         SELECT DISTINCT N.NODE_NUMBER
         FROM NODE N
         INNER JOIN NODE_POINT NP
           ON N.NODE_NUMBER = NP.NODE_NUMBER
         INNER JOIN ROADWAY_POINT RP
           ON NP.ROADWAY_POINT_ID = RP.ID
         INNER JOIN ROADWAY R
           ON RP.ROADWAY_NUMBER = R.ROADWAY_NUMBER
         INNER JOIN ROADWAY_CHANGES RC
           ON (R.ROAD_NUMBER = RC.NEW_ROAD_NUMBER
             AND R.ROAD_PART_NUMBER = RC.NEW_ROAD_PART_NUMBER)
         WHERE RC.PROJECT_ID = $projectId
         AND R.VALID_TO IS NULL
         AND R.END_DATE IS NULL
         AND NP.VALID_TO IS NULL
         AND N.VALID_TO IS NULL
       UNION
         SELECT DISTINCT N.NODE_NUMBER
         FROM NODE N
         INNER JOIN NODE_POINT NP
           ON N.NODE_NUMBER = NP.NODE_NUMBER
         INNER JOIN ROADWAY_POINT RP
           ON NP.ROADWAY_POINT_ID = RP.ID
         INNER JOIN ROADWAY R
           ON RP.ROADWAY_NUMBER = R.ROADWAY_NUMBER
         INNER JOIN ROADWAY_CHANGES RC
           ON (R.ROAD_NUMBER = RC.OLD_ROAD_NUMBER AND RC.NEW_ROAD_NUMBER IS NULL
             AND R.ROAD_PART_NUMBER = RC.OLD_ROAD_PART_NUMBER)
         WHERE RC.PROJECT_ID = $projectId
         AND R.VALID_TO IS NULL
         AND R.END_DATE IS NULL
         AND NP.VALID_TO IS NULL
         AND N.VALID_TO IS NULL
               """
    runSelectQuery(query.map(rs => rs.long(1)))
  }

}
