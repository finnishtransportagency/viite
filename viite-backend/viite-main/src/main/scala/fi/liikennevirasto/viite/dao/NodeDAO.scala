package fi.liikennevirasto.viite.dao

import java.sql.Timestamp
import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.NewIdValue
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.model.{NodePointType, NodeType}
import fi.vaylavirasto.viite.postgis.PostGISDatabase
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}


case class Node(id: Long, nodeNumber: Long, coordinates: Point, name: Option[String], nodeType: NodeType, startDate: DateTime, endDate: Option[DateTime], validFrom: DateTime, validTo: Option[DateTime],
                createdBy: String, createdTime: Option[DateTime], editor: Option[String] = None, publishedTime: Option[DateTime] = None, registrationDate: DateTime)

case class RoadAttributes(roadNumber: Long, roadPartNumber: Long, addrMValue: Long)

case class NodeForRoadAddressBrowser(ely: Long, roadNumber: Long, roadPartNumber: Long, addrM: Long, startDate: DateTime, nodeType: NodeType, name: Option[String], nodeCoordinates: Point, nodeNumber: Long)

case class SuperNode(nodeNumber: Long, startDate:  DateTime, nodeType: NodeType, name: String, nodeCoordinates: Point)
case class NodesWithJunctions(nodeNumber: Long, startDate:  DateTime, nodeType: NodeType, name: String, nodeCoordinates: Point, junctions: Seq[JunctionWithCoordinate])

class NodeDAO extends BaseDAO {

  implicit val getNode: GetResult[Node] = new GetResult[Node] {
    def apply(r: PositionedResult): Node = {
      val id = r.nextLong()
      val nodeNumber = r.nextLong()
      val coordX = r.nextLong()
      val coordY = r.nextLong()
      val name = r.nextStringOption()
      val nodeType = NodeType.apply(r.nextInt())
      val startDate = new DateTime(r.nextDate())
      val endDate = r.nextDateOption().map(d => new DateTime(d))
      val validFrom = new DateTime(r.nextDate()) // r.nextTimestampOption() do not work here
      val validTo = r.nextTimestampOption().map(d => new DateTime(d))
      val createdBy = r.nextString()
      val createdTime = r.nextTimestampOption().map(d => new DateTime(d))
      val editor = r.nextStringOption()
      val publishedTime = r.nextTimestampOption().map(d => new DateTime(d))
      val registrationDate = new DateTime(r.nextTimestamp())

      Node(id, nodeNumber, Point(coordX, coordY), name, nodeType, startDate, endDate, validFrom, validTo, createdBy, createdTime, editor, publishedTime, registrationDate)
    }
  }

  implicit val getNodeForRoadAddressBrowser: GetResult[NodeForRoadAddressBrowser] = new GetResult[NodeForRoadAddressBrowser] {
    def apply(r: PositionedResult): NodeForRoadAddressBrowser = {
      val ely = r.nextLong()
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val addrM = r.nextLong()
      val startDate = new DateTime(r.nextDate())
      val nodeType = NodeType.apply(r.nextInt())
      val name = r.nextStringOption()
      val coordX = r.nextLong()
      val coordY = r.nextLong()
      val nodeNumber = r.nextLong()

      NodeForRoadAddressBrowser(ely, roadNumber, roadPartNumber, addrM, startDate, nodeType, name, Point(coordX, coordY), nodeNumber)
    }
  }

  implicit val getValidNodeForAPI: GetResult[SuperNode] = new GetResult[SuperNode] {
    def apply(r: PositionedResult): SuperNode = {
      val nodeNumber = r.nextLong()
      val startDate = new DateTime(r.nextDate())
      val nodeType = NodeType.apply(r.nextInt())
      val name = r.nextString()
      val coordX = r.nextLong()
      val coordY = r.nextLong()

      SuperNode(nodeNumber, startDate, nodeType, name, Point(coordX, coordY))
    }
  }

  private def queryList(query: String): List[Node] = {
    Q.queryNA[Node](query).list.groupBy(_.id).map {
      case (_, list) =>
        list.head
    }.toList
  }

  def fetchByNodeNumber(nodeNumber: Long): Option[Node] = {
    sql"""
      SELECT ID, NODE_NUMBER, ST_X(COORDINATES), ST_Y(COORDINATES), NAME, TYPE, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME, EDITOR, PUBLISHED_TIME, REGISTRATION_DATE
      from NODE N
      where NODE_NUMBER = $nodeNumber and valid_to is null and end_date is null
      """.as[Node].firstOption
  }

  def fetchById(nodeId: Long): Option[Node] = {
    sql"""
      SELECT ID, NODE_NUMBER, ST_X(COORDINATES), ST_Y(COORDINATES), NAME, TYPE, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME, EDITOR, PUBLISHED_TIME, REGISTRATION_DATE
      from NODE N
      where ID = $nodeId and valid_to is null and end_date is null
      """.as[Node].firstOption
  }

  def fetchId(nodeNumber: Long): Option[Long] = {
    sql"""
      SELECT ID
      from NODE
      where NODE_NUMBER = $nodeNumber and valid_to is null and end_date is null
      """.as[Long].firstOption
  }

  def fetchLatestId(nodeNumber: Long): Option[Long] = {
    sql"""
      SELECT ID
      from NODE
      where NODE_NUMBER = $nodeNumber and valid_to is null
      order by created_time desc, end_date desc
      """.as[Long].firstOption
  }

  def fetchValidNodesForAPI(): Seq[SuperNode] = {
    val query =
      s"""
    SELECT DISTINCT node.NODE_NUMBER, node.START_DATE, node.type, node.NAME, ST_X(node.COORDINATES) AS xcoord, ST_Y(node.COORDINATES) AS ycoord
    FROM NODE node
    JOIN NODE_POINT np ON node.NODE_NUMBER = np.NODE_NUMBER AND np.VALID_TO IS NULL
    JOIN ROADWAY_POINT rp ON np.ROADWAY_POINT_ID = rp.ID
    JOIN ROADWAY rw ON rp.ROADWAY_NUMBER = rw.ROADWAY_NUMBER AND rw.VALID_TO IS NULL AND rw.END_DATE IS null
    """
    Q.queryNA[SuperNode](query).iterator.toSeq
  }

  def fetchNodesForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[NodeForRoadAddressBrowser] = {
    def withOptionalParameters(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long])(query: String): String = {
      val dateCondition = "AND rw.start_date <='" + situationDate.get + "'"

      val elyCondition = {
        if (ely.nonEmpty)
          s" AND rw.ely = ${ely.get}"
        else
          ""
      }

      val roadNumberCondition = {
        if (roadNumber.nonEmpty)
          s" AND rw.road_number = ${roadNumber.get}"
        else
          ""
      }

      val roadPartCondition = {
        val parts = (minRoadPartNumber, maxRoadPartNumber)
        parts match {
          case (Some(minPart), Some(maxPart)) => s"AND rw.road_part_number BETWEEN $minPart AND $maxPart"
          case (None, Some(maxPart)) => s"AND rw.road_part_number <= $maxPart"
          case (Some(minPart), None) => s"AND rw.road_part_number >= $minPart"
          case _ => ""
        }
      }

      s"""$query WHERE node.VALID_TO IS NULL AND node.END_DATE IS NULL
        $dateCondition $elyCondition $roadNumberCondition $roadPartCondition
        ORDER BY rw.ROAD_NUMBER, rw.ROAD_PART_NUMBER, rp.ADDR_M""".stripMargin
    }

    def fetchNodes(queryFilter: String => String): Seq[NodeForRoadAddressBrowser] = {
      val query =
        """
      SELECT DISTINCT rw.ely, rw.ROAD_NUMBER, rw.ROAD_PART_NUMBER, rp.ADDR_M, node.START_DATE, node.type, node.NAME, ST_X(node.COORDINATES) AS xcoord, ST_Y(node.COORDINATES) AS ycoord, node.NODE_NUMBER
		  FROM NODE node
      JOIN NODE_POINT np ON node.NODE_NUMBER = np.NODE_NUMBER AND np.VALID_TO IS NULL
      JOIN ROADWAY_POINT rp ON np.ROADWAY_POINT_ID = rp.ID
      JOIN ROADWAY rw ON rp.ROADWAY_NUMBER = rw.ROADWAY_NUMBER AND rw.VALID_TO IS NULL AND rw.END_DATE IS null
      """
      val filteredQuery = queryFilter(query)
      Q.queryNA[NodeForRoadAddressBrowser](filteredQuery).iterator.toSeq
    }
    fetchNodes(withOptionalParameters(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber))
  }


  def fetchByRoadAttributes(road_number: Long, minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[(Node, RoadAttributes)] = {
    val road_condition = (minRoadPartNumber.isDefined, maxRoadPartNumber.isDefined) match {
      case (true, true) => s"AND rw.ROAD_PART_NUMBER >= ${minRoadPartNumber.get} AND rw.ROAD_PART_NUMBER <= ${maxRoadPartNumber.get}"
      case (true, _) => s"AND rw.ROAD_PART_NUMBER = ${minRoadPartNumber.get}"
      case (_, true) => s"AND rw.ROAD_PART_NUMBER = ${maxRoadPartNumber.get}"
      case _ => ""
    }

    val query =
      s"""
        SELECT DISTINCT node.ID, node.NODE_NUMBER, ST_X(node.COORDINATES), ST_Y(node.COORDINATES), node.NAME, node.TYPE, node.START_DATE, node.END_DATE, node.VALID_FROM, node.VALID_TO,
                        node.CREATED_BY, node.CREATED_TIME, node.REGISTRATION_DATE, rw.ROAD_NUMBER, rw.ROAD_PART_NUMBER, rp.ADDR_M
        FROM NODE node
        JOIN NODE_POINT np ON node.NODE_NUMBER = np.NODE_NUMBER AND np.VALID_TO IS NULL
        JOIN ROADWAY_POINT rp ON np.ROADWAY_POINT_ID = rp.ID
        JOIN ROADWAY rw ON rp.ROADWAY_NUMBER = rw.ROADWAY_NUMBER AND rw.VALID_TO IS NULL AND rw.END_DATE IS NULL
          WHERE node.VALID_TO IS NULL AND node.END_DATE IS NULL
          AND rw.ROAD_NUMBER = $road_number $road_condition
        ORDER BY rw.ROAD_NUMBER, rw.ROAD_PART_NUMBER, rp.ADDR_M
      """

    Q.queryNA[(Long, Long, Long, Long, Option[String], Option[Int], DateTime, Option[DateTime], DateTime, Option[DateTime],
      String, Option[DateTime], DateTime, Long, Long, Long)](query).list.map {

      case (id, nodeNumber, x, y, name, nodeType, startDate, endDate, validFrom, validTo,
      createdBy, createdTime, registrationDate, roadNumber, roadPartNumber, addrMValue) =>

        (Node(id, nodeNumber, Point(x, y), name, NodeType.apply(nodeType.getOrElse(NodeType.UnknownNodeType.value)), startDate, endDate, validFrom, validTo, createdBy, createdTime, None, None, registrationDate),
          RoadAttributes(roadNumber, roadPartNumber, addrMValue))
    }
  }

  def publish(id: Long, editor: String = "-"): Unit = {
    runUpdateToDb(s"""
        Update NODE Set PUBLISHED_TIME = CURRENT_TIMESTAMP, EDITOR = '$editor' Where ID = $id
    """)
  }

  def create(nodes: Iterable[Node], createdBy: String = "-"): Seq[Long] = {

    val ps = dynamicSession.prepareStatement(
      """insert into NODE (ID, NODE_NUMBER, COORDINATES, NAME, TYPE, START_DATE, END_DATE, CREATED_BY, REGISTRATION_DATE)
      values (?, ?, ST_GeomFromText(?, 3067), ?, ?, ?, ?, ?, ?)""".stripMargin)

    // Set ids for the nodes without one
    val (ready, idLess) = nodes.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchNodeIds(idLess.size)
    val createNodes = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    var nodeNumbers = scala.collection.mutable.MutableList[Long]()
    createNodes.foreach {
      node =>
        val nodeNumber = if (node.nodeNumber == NewIdValue) {
          Sequences.nextNodeNumber
        } else {
          node.nodeNumber
        }
        nodeNumbers += nodeNumber
        ps.setLong(1, node.id)
        ps.setLong(2, nodeNumber)
        ps.setString(3, PostGISDatabase.createRoundedPointGeometry(node.coordinates))
        if (node.name.isDefined) {
          ps.setString(4, node.name.get)
        } else {
          ps.setNull(4, java.sql.Types.VARCHAR)
        }
        ps.setLong(5, node.nodeType.value)
        ps.setDate(6, new java.sql.Date(node.startDate.getMillis))
        if (node.endDate.isDefined) {
          ps.setDate(7, new java.sql.Date(node.endDate.get.getMillis))
        } else {
          ps.setNull(7, java.sql.Types.DATE)
        }
        ps.setString(8, if (createdBy == null) "-" else createdBy)
        ps.setTimestamp(9, new java.sql.Timestamp(node.registrationDate.getMillis))
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    nodeNumbers
  }

  def fetchByBoundingBox(boundingRectangle: BoundingRectangle): Seq[Node] = {
    val extendedBoundingBoxRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(scalar = .15),
      boundingRectangle.rightTop - boundingRectangle.diagonal.scale(scalar = .15))
    val boundingBoxFilter = PostGISDatabase.boundingBoxFilter(extendedBoundingBoxRectangle, geometryColumn = "coordinates")
    val query = s"""
      SELECT ID, NODE_NUMBER, ST_X(COORDINATES), ST_Y(COORDINATES), NAME, TYPE, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME, EDITOR, PUBLISHED_TIME, REGISTRATION_DATE
      FROM NODE N
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
      val query = s"""
        UPDATE NODE SET valid_to = CURRENT_TIMESTAMP WHERE valid_to IS NULL AND id IN (${ids.mkString(", ")})
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
      val query = s"""
        SELECT ID, NODE_NUMBER, ST_X(COORDINATES), ST_Y(COORDINATES), NAME, TYPE, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME, EDITOR, PUBLISHED_TIME, REGISTRATION_DATE
        FROM NODE N
          WHERE END_DATE IS NULL AND VALID_TO IS NULL AND NODE_NUMBER IN (${nodeNumbers.mkString(", ")})
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
      val untilString = if (untilDate.nonEmpty) s"AND PUBLISHED_TIME <= to_timestamp('${new Timestamp(untilDate.get.getMillis)}', 'YYYY-MM-DD HH24:MI:SS.FF')" else s""
      val query =
        s"""
         SELECT ID, NODE_NUMBER, ST_X(COORDINATES), ST_Y(COORDINATES), NAME, TYPE, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME, EDITOR, PUBLISHED_TIME, REGISTRATION_DATE
         FROM NODE N
         WHERE NODE_NUMBER IN (SELECT NODE_NUMBER FROM NODE NC WHERE
         PUBLISHED_TIME IS NOT NULL AND PUBLISHED_TIME >= to_timestamp('${new Timestamp(sinceDate.getMillis)}', 'YYYY-MM-DD HH24:MI:SS.FF')
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
      s"""
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
    Q.queryNA[Long](query).iterator.toSeq
  }

}
