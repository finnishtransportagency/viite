package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class Node(id: Long, nodeNumber: Long, coordinates: Point, name: Option[String], nodeType: Long, startDate: Option[DateTime], endDate: Option[DateTime],
                validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime])

class NodeDAO {
  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()
  implicit val getNode: GetResult[Node] = new GetResult[Node] {
    def apply(r: PositionedResult): Node = {
      val id = r.nextLong()
      val nodeNumber = r.nextLong()
      val coordinates = OracleDatabase.loadRoadsJGeometryToGeometry(r.nextObjectOption())
      val name = r.nextStringOption()
      val nodeType = r.nextLong()
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

  def fetchByRoadAttributes(roadNumber: Long, startRoadPartNumber: Option[Long], endRoadPartNumber: Option[Long]): List[Node] = {
    val roadPartCondition = if (startRoadPartNumber.isDefined) {
      s"AND ROAD_PART_NUMBER >= ${startRoadPartNumber.get} AND ROAD_PART_NUMBER <= ${endRoadPartNumber.get}"
    } else {
      ""
    }

    sql"""
      SELECT ID, NODE_NUMBER, COORDINATES, "NAME", "TYPE", START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
        from NODE
        where ID IN (
          SELECT node_id FROM node_point WHERE ROADWAY_POINT_ID IN (
            SELECT id FROM roadway_point WHERE ROADWAY_NUMBER IN (
              SELECT roadway_number FROM roadway WHERE ROAD_NUMBER = $roadNumber $roadPartCondition))
        )
      """.as[Node].list
  }

}
