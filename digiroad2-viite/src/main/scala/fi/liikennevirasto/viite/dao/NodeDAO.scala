package fi.liikennevirasto.viite.dao

import java.sql.Timestamp

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.NewIdValue
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

sealed trait NodeType {
  def value: Int

  def displayValue: String
}

object NodeType {
  val values: Set[NodeType] = Set(NormalIntersection, Roundabout1, Roundabout, YIntersection, Interchange, InterchangeJunction, RoadBoundary, ELYBorder, SupportingPoint, MultitrackIntersection,
    DropIntersection, AccessRoad, EndOfRoad, Bridge, MaintenanceOpening, PrivateRoad, StaggeredIntersection, UnknownNodeType)

  def apply(intValue: Int): NodeType = {
    values.find(_.value == intValue).getOrElse(UnknownNodeType)
  }

  case object NormalIntersection extends NodeType {
    def value = 1

    def displayValue = "Normaali tasoliittymä"
  }

  case object Roundabout1 extends NodeType {
    def value = 2

    def displayValue = "Kiertoliittymä1"
  }

  case object Roundabout extends NodeType {
    def value = 3

    def displayValue = "Kiertoliittymä"
  }

  case object YIntersection extends NodeType {
    def value = 4

    def displayValue = "Y-liittymä"
  }

  case object Interchange extends NodeType {
    def value = 5

    def displayValue = "Eritasoliittymä"
  }

  case object InterchangeJunction extends NodeType {
    def value = 6

    def displayValue = "Eritasoristeys"
  }

  case object RoadBoundary extends NodeType {
    def value = 7

    def displayValue = "Maantien/kadun raja"
  }

  case object ELYBorder extends NodeType {
    def value = 8

    def displayValue = "ELY-raja"
  }

  case object SupportingPoint extends NodeType {
    def value = 9

    def displayValue = "Apupiste"
  }

  case object MultitrackIntersection extends NodeType {
    def value = 10

    def displayValue = "Moniajoratainen liittymä"
  }

  case object DropIntersection extends NodeType {
    def value = 11

    def displayValue = "Pisaraliittymä"
  }

  case object AccessRoad extends NodeType {
    def value = 12

    def displayValue = "Liityntätie"
  }

  case object EndOfRoad extends NodeType {
    def value = 13

    def displayValue = "Tien loppu"
  }

  case object Bridge extends NodeType {
    def value = 14

    def displayValue = "Silta"
  }

  case object MaintenanceOpening extends NodeType {
    def value = 15

    def displayValue = "Huoltoaukko"
  }

  case object PrivateRoad extends NodeType {
    def value = 16

    def displayValue = "Yksityistie- tai katuliittymä"
  }

  case object StaggeredIntersection extends NodeType {
    def value = 17

    def displayValue = "Porrastettu liittymä"
  }

  case object UnknownNodeType extends NodeType {
    def value = 99

    def displayValue = "Ei määritelty"
  }

}

case class Node(id: Long, nodeNumber: Long, coordinates: Point, name: Option[String], nodeType: NodeType, startDate: DateTime, endDate: Option[DateTime], validFrom: DateTime, validTo: Option[DateTime],
                createdBy: String, createdTime: Option[DateTime], editor: Option[String] = None, publishedTime: Option[DateTime] = None, registrationDate: DateTime)

case class RoadAttributes(roadNumber: Long, roadPartNumber: Long, addrMValue: Long)

class NodeDAO extends BaseDAO {

  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

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
      val registrationDate = new DateTime(r.nextDate())

      Node(id, nodeNumber, Point(coordX, coordY), name, nodeType, startDate, endDate, validFrom, validTo, createdBy, createdTime, editor, publishedTime, registrationDate)
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
      SELECT ID, NODE_NUMBER, coords.X, coords.Y, "NAME", "TYPE", START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME, EDITOR, PUBLISHED_TIME, REGISTRATION_DATE
      from NODE N
      CROSS JOIN TABLE(SDO_UTIL.GETVERTICES(N.COORDINATES)) coords
      where NODE_NUMBER = $nodeNumber and valid_to is null and end_date is null
      """.as[Node].firstOption
  }

  def fetchById(nodeId: Long): Option[Node] = {
    sql"""
      SELECT ID, NODE_NUMBER, coords.X, coords.Y, "NAME", "TYPE", START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME, EDITOR, PUBLISHED_TIME, REGISTRATION_DATE
      from NODE N
      CROSS JOIN TABLE(SDO_UTIL.GETVERTICES(N.COORDINATES)) coords
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

  def fetchByRoadAttributes(road_number: Long, minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[(Node, RoadAttributes)] = {
    val road_condition = (minRoadPartNumber.isDefined, maxRoadPartNumber.isDefined) match {
      case (true, true) => s"AND rw.ROAD_PART_NUMBER >= ${minRoadPartNumber.get} AND rw.ROAD_PART_NUMBER <= ${maxRoadPartNumber.get}"
      case (true, _) => s"AND rw.ROAD_PART_NUMBER = ${minRoadPartNumber.get}"
      case (_, true) => s"AND rw.ROAD_PART_NUMBER = ${maxRoadPartNumber.get}"
      case _ => ""
    }

    val query =
      s"""
        SELECT DISTINCT node.ID, node.NODE_NUMBER, coords.X, coords.Y, node.NAME, node."TYPE", node.START_DATE, node.END_DATE, node.VALID_FROM, node.VALID_TO,
                        node.CREATED_BY, node.CREATED_TIME, node.REGISTRATION_DATE, rw.ROAD_NUMBER, rw.ROAD_PART_NUMBER, rp.ADDR_M
        FROM NODE node
        CROSS JOIN TABLE(SDO_UTIL.GETVERTICES(node.COORDINATES)) coords
        JOIN NODE_POINT np ON node.NODE_NUMBER = np.NODE_NUMBER AND np.VALID_TO IS NULL
        JOIN ROADWAY_POINT rp ON np.ROADWAY_POINT_ID = rp.ID
        JOIN ROADWAY rw ON rp.ROADWAY_NUMBER = rw.ROADWAY_NUMBER AND rw.VALID_TO IS NULL AND rw.END_DATE IS NULL
          WHERE node.VALID_TO IS NULL AND node.END_DATE IS NULL
          AND rw.ROAD_NUMBER = $road_number $road_condition
          AND (np.BEFORE_AFTER = ${BeforeAfter.After.value} OR CASE WHEN EXISTS (
            SELECT * FROM NODE_POINT np_c
              JOIN ROADWAY_POINT rp_c ON np_c.ROADWAY_POINT_ID = rp_c.ID
              JOIN ROADWAY rw_c ON rp_c.ROADWAY_NUMBER = rw_c.ROADWAY_NUMBER AND rw_c.VALID_TO IS NULL AND rw_c.END_DATE IS NULL
                WHERE np_c.VALID_TO IS NULL AND np_c.NODE_NUMBER = node.NODE_NUMBER
                AND rw_c.ROAD_NUMBER = rw.ROAD_NUMBER AND rw_c.ROAD_PART_NUMBER != rw.ROAD_PART_NUMBER) THEN 0
            ELSE 1
          END = 1)
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
    sqlu"""
        Update NODE Set PUBLISHED_TIME = SYSDATE, EDITOR = $editor Where ID = $id
      """.execute
  }

  def create(nodes: Iterable[Node], createdBy: String = "-"): Seq[Long] = {

    val ps = dynamicSession.prepareStatement(
      """insert into NODE (ID, NODE_NUMBER, COORDINATES, "NAME", "TYPE", START_DATE, END_DATE, CREATED_BY)
      values (?, ?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?, ?)""".stripMargin)

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
        ps.setObject(3, OracleDatabase.createRoadsJGeometry(Seq(node.coordinates), dynamicSession.conn, 0))
        if (node.name.isDefined) {
          ps.setString(4, node.name.get)
        } else {
          ps.setNull(4, java.sql.Types.VARCHAR)
        }
        ps.setLong(5, node.nodeType.value)
        ps.setString(6, dateFormatter.print(node.startDate))
        ps.setString(7, node.endDate match {
          case Some(date) => dateFormatter.print(date)
          case None => ""
        })
        ps.setString(8, if (createdBy == null) "-" else createdBy)
        ps.setString(9, dateFormatter.print(node.registrationDate))
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    nodeNumbers
  }

  def fetchByBoundingBox(boundingRectangle: BoundingRectangle): Seq[Node] = {
    val extendedBoundingBoxRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(scalar = .15),
      boundingRectangle.rightTop - boundingRectangle.diagonal.scale(scalar = .15))
    val boundingBoxFilter = OracleDatabase.boundingBoxFilter(extendedBoundingBoxRectangle, geometryColumn = "coordinates")
    val query = s"""
      SELECT ID, NODE_NUMBER, coords.X, coords.Y, "NAME", "TYPE", START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME, EDITOR, PUBLISHED_TIME, REGISTRATION_DATE
      FROM NODE N
      CROSS JOIN TABLE(SDO_UTIL.GETVERTICES(N.COORDINATES)) coords
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
  def expireById(ids: Iterable[Long]): Int = {
    if (ids.isEmpty)
      0
    else {
      val query = s"""
        UPDATE NODE SET valid_to = sysdate WHERE valid_to IS NULL AND id IN (${ids.mkString(", ")})
      """
      Q.updateNA(query).first
    }
  }

  def fetchEmptyNodes(nodeNumbers: Seq[Long]): Seq[Node] = {
    if (nodeNumbers.isEmpty) {
      Seq()
    } else {
      // TODO - Might be needed to check node point type here - since calculate node points should not be considered to identify empty nodes
      val query = s"""
        SELECT ID, NODE_NUMBER, coords.X, coords.Y, "NAME", "TYPE", START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME, EDITOR, PUBLISHED_TIME, REGISTRATION_DATE
        FROM NODE N
        CROSS JOIN TABLE(SDO_UTIL.GETVERTICES(N.COORDINATES)) coords
          WHERE END_DATE IS NULL AND VALID_TO IS NULL AND NODE_NUMBER IN (${nodeNumbers.mkString(", ")})
          AND NOT EXISTS (
            SELECT NULL FROM JUNCTION J WHERE N.NODE_NUMBER = J.NODE_NUMBER AND J.VALID_TO IS NULL AND J.END_DATE IS NULL
          ) AND NOT EXISTS (
            SELECT NULL FROM NODE_POINT NP WHERE N.NODE_NUMBER = NP.NODE_NUMBER AND NP.VALID_TO IS NULL AND NP."TYPE" IN (${NodePointType.UnknownNodePointType.value}, ${NodePointType.RoadNodePoint.value})
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
         SELECT ID, NODE_NUMBER, coords.X, coords.Y, "NAME", "TYPE", START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME, EDITOR, PUBLISHED_TIME, REGISTRATION_DATE
         FROM NODE N
         CROSS JOIN TABLE(SDO_UTIL.GETVERTICES(N.COORDINATES)) coords
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
    Q.queryNA[(Long)](query).iterator.toSeq
  }

}