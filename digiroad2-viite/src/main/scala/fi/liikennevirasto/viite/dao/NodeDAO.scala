package fi.liikennevirasto.viite.dao

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import fi.liikennevirasto.viite._

sealed trait NodeType {
  def value: Long

  def displayValue: String
}

object NodeType {
  val values: Set[NodeType] = Set(NormalIntersection, Roundabout, YIntersection, Interchange, RoadBoundary, ELYBorder, MultitrackIntersection,
                                  DropIntersection, AccessRoad, EndOfRoad, Bridge, MaintenanceOpening, PrivateRoad, StaggeredIntersection, UnkownNodeType)

  def apply(intValue: Long): NodeType = {
    values.find(_.value == intValue).getOrElse(UnkownNodeType)
  }

  case object NormalIntersection extends NodeType {
    def value = 1

    def displayValue = "Normaali tasoliittymä"
  }

  case object Roundabout extends NodeType {
    def value = 3

    def displayValue = "Kiertoliittymä"
  }

  case object YIntersection	 extends NodeType {
    def value = 4

    def displayValue = "Y-liittymä"
  }

  case object Interchange extends NodeType {
    def value = 5

    def displayValue = "Eritasoliittymä"
  }

  case object RoadBoundary extends NodeType {
    def value = 7

    def displayValue = "Maantien/kadun raja"
  }

  case object ELYBorder extends NodeType {
    def value = 8

    def displayValue = "ELY-raja"
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

    def displayValue = "Yksityistie-tai katuliittymä"
  }

  case object StaggeredIntersection extends NodeType {
    def value = 17

    def displayValue = "Porrastettu liittymä"
  }

  case object UnkownNodeType extends NodeType {
    def value = 99

    def displayValue = "Ei määritelty"
  }
}

case class Node(id: Long, nodeNumber: Long, coordinates: Point, name: Option[String], nodeType: NodeType, startDate: DateTime, endDate: Option[DateTime], validFrom: DateTime, validTo: Option[DateTime],
                createdBy: Option[String], createdTime: Option[DateTime])

case class RoadAttributes(roadNumber: Long, track: Long, roadPartNumber: Long, addrMValue: Long)

class NodeDAO extends BaseDAO {

  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

  implicit val getNode: GetResult[Node] = new GetResult[Node] {
    def apply(r: PositionedResult): Node = {
      val id = r.nextLong()
      val nodeNumber = r.nextLong()
      val coordinates = OracleDatabase.loadRoadsJGeometryToGeometry(r.nextObjectOption())
      val name = r.nextStringOption()
      val nodeType = NodeType.apply(r.nextLong())
      val startDate = formatter.parseDateTime(r.nextDate.toString)
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = formatter.parseDateTime(r.nextDate.toString)
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextStringOption()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))

      Node(id, nodeNumber, coordinates.head, name, nodeType, startDate, endDate, validFrom, validTo, createdBy, createdTime)
    }
  }

  private def queryNodeList(query: String): List[Node] = {
    Q.queryNA[Node](query).list.groupBy(_.id).map {
      case (_, list) =>
        list.head
    }.toList
  }

  def fetchByNodeNumber(nodeNumber: Long): Option[Node] = {
    sql"""
      SELECT ID, NODE_NUMBER, COORDINATES, "NAME", "TYPE", START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
      from NODE
      where NODE_NUMBER = $nodeNumber and valid_to is null and end_date is null
      """.as[Node].firstOption
  }

  def fetchId(nodeNumber: Long): Option[Long] = {
    sql"""
      SELECT ID
      from NODE
      where NODE_NUMBER = $nodeNumber and valid_to is null and end_date is null
      """.as[Long].firstOption
  }

  def fetchIdWithHistory(nodeNumber: Long): Option[Long] = {
    sql"""
      SELECT ID
      from NODE
      where NODE_NUMBER = $nodeNumber
      """.as[Long].firstOption
  }

  def fetchByRoadAttributes(road_number: Long, minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[(Node, RoadAttributes)] = {
    val road_condition = (minRoadPartNumber.isDefined, maxRoadPartNumber.isDefined) match {
      case (true, true) => s"AND rw.ROAD_PART_NUMBER >= ${minRoadPartNumber.get} AND rw.ROAD_PART_NUMBER <= ${maxRoadPartNumber.get}"
      case (true, _) => s"AND rw.ROAD_PART_NUMBER = ${minRoadPartNumber.get}"
      case (_, true) => s"AND rw.ROAD_PART_NUMBER = ${maxRoadPartNumber.get}"
      case _ =>""
    }

    val query = s"""
      SELECT DISTINCT node.ID, node.NODE_NUMBER, coords.X, coords.Y, node.NAME, node."TYPE", node.START_DATE, node.END_DATE, node.VALID_FROM, node.VALID_TO,
                      node.CREATED_BY, node.CREATED_TIME, rw.ROAD_NUMBER, rw.TRACK, rw.ROAD_PART_NUMBER, rp.ADDR_M
        FROM NODE node
        CROSS JOIN TABLE(SDO_UTIL.GETVERTICES(node.COORDINATES)) coords
        LEFT JOIN NODE_POINT np ON node.ID = np.NODE_ID AND np.VALID_TO IS NULL AND np.END_DATE IS NULL
        LEFT JOIN ROADWAY_POINT rp ON np.ROADWAY_POINT_ID = rp.ID
        LEFT JOIN ROADWAY rw ON rp.ROADWAY_NUMBER = rw.ROADWAY_NUMBER AND rw.VALID_TO IS NULL AND rw.END_DATE IS NULL
          WHERE node.VALID_TO IS NULL AND node.END_DATE IS NULL
          AND rw.ROAD_NUMBER = $road_number $road_condition
          AND (np.BEFORE_AFTER = ${BeforeAfter.After.value} OR CASE WHEN EXISTS (
            SELECT * FROM NODE_POINT np_c
              LEFT JOIN ROADWAY_POINT rp_c ON np_c.ROADWAY_POINT_ID = rp_c.ID
              LEFT JOIN ROADWAY rw_c ON rp_c.ROADWAY_NUMBER = rw_c.ROADWAY_NUMBER AND rw_c.VALID_TO IS NULL AND rw_c.END_DATE IS NULL
                WHERE np_c.VALID_TO IS NULL AND np_c.END_DATE IS NULL AND np_c.NODE_ID = node.ID
                AND rw_c.ROAD_NUMBER = rw.ROAD_NUMBER AND rw_c.ROAD_PART_NUMBER != rw.ROAD_PART_NUMBER) THEN 0
            ELSE 1
          END = 1)
        ORDER BY rw.ROAD_NUMBER, rw.ROAD_PART_NUMBER, rp.ADDR_M, rw.TRACK
      """

    Q.queryNA[(Long, Long, Long, Long, Option[String], Option[Long], DateTime, Option[DateTime], DateTime, Option[DateTime],
      Option[String], Option[DateTime], Long, Long, Long, Long)](query).list.map {

      case (id, nodeNumber, x, y, name, nodeType, startDate, endDate, validFrom, validTo,
            createdBy, createdTime, roadNumber, track, roadPartNumber, addrMValue) =>

        (Node(id, nodeNumber, Point(x, y), name, NodeType.apply(nodeType.getOrElse(NodeType.UnkownNodeType.value)), startDate, endDate, validFrom, validTo, createdBy, createdTime),
          RoadAttributes(roadNumber, track, roadPartNumber, addrMValue))
    }
  }

  def create(nodes: Iterable[Node], createdBy: String = "-"): Seq[Long] = {

    val ps = dynamicSession.prepareStatement(
      """insert into NODE (ID, NODE_NUMBER, COORDINATES, "NAME", "TYPE", START_DATE, END_DATE, CREATED_BY)
      values (?, ?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?)""".stripMargin)

    // Set ids for the nodes without one
    val (ready, idLess) = nodes.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchNodeIds(idLess.size)
    val createNodes = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    createNodes.foreach {
      node =>
        val nodeNumber = if (node.nodeNumber == NewIdValue) {
          Sequences.nextNodeNumber
        } else {
          node.nodeNumber
        }
        ps.setLong(1, node.id)
        ps.setLong(2, nodeNumber)
        ps.setObject(3, OracleDatabase.createPointJGeometry(node.coordinates))
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
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    createNodes.map(_.id).toSeq
  }

  def fetchByBoundingBox(boundingRectangle: BoundingRectangle) : Seq[Node] = {
    val extendedBoundingBoxRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(scalar = .15),
      boundingRectangle.rightTop - boundingRectangle.diagonal.scale(scalar = .15))
    val boundingBoxFilter = OracleDatabase.boundingBoxFilter(extendedBoundingBoxRectangle, geometryColumn = "coordinates")
    val query =
      s"""
         SELECT ID, NODE_NUMBER, COORDINATES, "NAME", "TYPE", START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
         FROM NODE
         WHERE $boundingBoxFilter
         AND END_DATE IS NULL AND VALID_TO IS NULL
       """
    queryNodeList(query)
  }
}