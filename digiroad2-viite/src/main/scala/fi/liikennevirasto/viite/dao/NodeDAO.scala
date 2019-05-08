package fi.liikennevirasto.viite.dao

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

sealed trait NodeType {
  def value: Long

  def displayValue: String
}

object NodeType {
  val values: Set[NodeType] = Set(NormalIntersection, Roundabout, YIntersection, Interchange, RoadBoundary, ELYBoarder, MultitrackIntersection,
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

  case object ELYBoarder extends NodeType {
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

case class Node(id: Long, nodeNumber: Long, coordinates: Point, name: Option[String], nodeType: NodeType, startDate: Option[DateTime], endDate: Option[DateTime], validFrom: Option[DateTime], validTo: Option[DateTime],
                createdBy: Option[String], createdTime: Option[DateTime], roadNumber: Option[Long] = None, roadPartNumber: Option[Long] = None, track: Option[Long] = None, startAddrMValue: Option[Long] = None)

class NodeDAO {
  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()
  implicit val getNode: GetResult[Node] = new GetResult[Node] {
    def apply(r: PositionedResult): Node = {
      val id = r.nextLong()
      val nodeNumber = r.nextLong()
      val coordinates = OracleDatabase.loadRoadsJGeometryToGeometry(r.nextObjectOption())
      val name = r.nextStringOption()
      val nodeType = NodeType.apply(r.nextLong())
      val startDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextStringOption()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))

      Node(id, nodeNumber, coordinates.head, name, nodeType, startDate, endDate, validFrom, validTo, createdBy, createdTime)
    }
  }

  def fetchByNodeNumber(nodeNumber: Long): Option[Node] = {
    sql"""
      SELECT ID, NODE_NUMBER, COORDINATES, "NAME", "TYPE", START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
      from NODE
      where NODE_NUMBER = $nodeNumber
      """.as[Node].firstOption
  }

  def fetchId(nodeNumber: Long): Option[Long] = {
    sql"""
      SELECT ID
      from NODE
      where NODE_NUMBER = $nodeNumber
      """.as[Long].firstOption
  }

  def fetchByRoadAttributes(road_number: Long, minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[Node] = {
    val road_condition = if (minRoadPartNumber.isDefined) { // if startRoadNumber is defined then endRoadNumber is mandatory
      if (!maxRoadPartNumber.isDefined) {
        throw new IllegalArgumentException(s"""When the min road part number is specified, also the max road part number is required.""")
      }
      s"AND ROAD_PART_NUMBER >= ${minRoadPartNumber.get} AND ROAD_PART_NUMBER <= ${maxRoadPartNumber.get}"
    } else {
      ""
    }
    val query = s"""
      SELECT DISTINCT node.ID, node.NODE_NUMBER, t.X, t.Y, node.NAME, node."TYPE", node.START_DATE, node.END_DATE, node.VALID_FROM, node.VALID_TO,
                      node.CREATED_BY, node.CREATED_TIME, rw.ROAD_NUMBER, rw.ROAD_PART_NUMBER, rw.TRACK, rw.START_ADDR_M
        FROM NODE node
        CROSS JOIN TABLE(SDO_UTIL.GETVERTICES(node.COORDINATES)) t
        LEFT JOIN NODE_POINT np ON node.ID = np.NODE_ID
        LEFT JOIN ROADWAY_POINT rp ON np.ROADWAY_POINT_ID = rp.ID
        LEFT JOIN ROADWAY rw ON rp.ROADWAY_NUMBER = rw.ROADWAY_NUMBER
         		WHERE ROAD_NUMBER = $road_number $road_condition
        ORDER BY rw.ROAD_NUMBER, rw.ROAD_PART_NUMBER, rw.TRACK, rw.START_ADDR_M, node.ID
      """
    Q.queryNA[(Long, Long, Long, Long, Option[String], Option[Long], Option[DateTime], Option[DateTime], Option[DateTime], Option[DateTime],
      Option[String], Option[DateTime], Option[Long], Option[Long], Option[Long], Option[Long])](query).list.map {

      case (id, nodeNumber, coordX, coordY, name, nodeType, startDate, endDate, validFrom, validTo,
            createdBy, createdTime, roadNumber, roadPartNumber, track, startAddrMValue) =>

        val coordinates = Point(coordX, coordY)

        Node(id, nodeNumber, coordinates, name, NodeType.apply(nodeType.getOrElse(NodeType.UnkownNodeType.value)), startDate, endDate, validFrom, validTo,
             createdBy, createdTime, roadNumber, roadPartNumber, track, startAddrMValue)
    }
  }
}