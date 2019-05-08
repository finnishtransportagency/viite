package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class Node(id: Long, nodeNumber: Long, coordinates: Point, name: Option[String], nodeType: Long, startDate: Option[DateTime], endDate: Option[DateTime],
                validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime])

case class NodePoint(id: Long, beforeOrAfter: Long, roadwayPointId: Long, nodeId: Option[Long], startDate: Option[DateTime], endDate: Option[DateTime],
                     validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime], roadwayNumber: Long, addrM: Long)

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

  implicit val getNodePoint: GetResult[NodePoint] = new GetResult[NodePoint] {
    def apply(r: PositionedResult) : NodePoint = {
      val id = r.nextLong()
      val beforeOrAfter = r.nextLong()
      val roadwayPointId = r.nextLong()
      val nodeId = r.nextLongOption()
      val startDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextStringOption()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val roadwayNumber = r.nextLong()
      val addrM = r.nextLong()
      NodePoint(id, beforeOrAfter, roadwayPointId, nodeId, startDate, endDate, validFrom, validTo, createdBy, createdTime, roadwayNumber, addrM)
    }
  }

  private def queryNodeList(query: String): List[Node] = {
    Q.queryNA[Node](query).list.groupBy(_.id).map {
      case (_, list) =>
        list.head
    }.toList
  }

  private def queryNodePointList(query: String): List[NodePoint] = {
    Q.queryNA[NodePoint](query).list.groupBy(_.id).map {
      case (_, list) =>
        list.head
    }.toList
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

  def fetchByRoadAttributes(roadNumber: Long, roadPartNumber: Option[Long], minAddrM: Option[Long], maxAddrM: Option[Long]): List[Node] = {
    val roadPartCondition = if (roadPartNumber.isDefined) {
      s"AND ROAD_PART_NUMBER = ${roadPartNumber.get}"
    } else {
      ""
    }
    val minAddrMCondition = if (minAddrM.isDefined) {
      s"AND ADDR_M >= ${minAddrM.get}"
    } else {
      ""
    }
    val maxAddrMCondition = if (maxAddrM.isDefined) {
      s"AND ADDR_M <= ${maxAddrM.get}"
    } else {
      ""
    }
    val query =
      s"""
        SELECT ID, NODE_NUMBER, COORDINATES, "NAME", "TYPE", START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
          from NODE
          where ID IN (
            SELECT node_id FROM node_point WHERE ROADWAY_POINT_ID IN (
              SELECT id FROM roadway_point WHERE ROADWAY_NUMBER IN (
                SELECT roadway_number FROM roadway WHERE ROAD_NUMBER = $roadNumber $roadPartCondition)
                  $minAddrMCondition $maxAddrMCondition)
        )
      """
    queryNodeList(query)
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

  def fetchNodePointsByNodeId(nodeIds: Seq[Long]): Seq[NodePoint] = {
    val query =
      s"""
         SELECT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NP.NODE_ID, NP.START_DATE, NP.END_DATE, NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY,
         NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M FROM NODE_POINT NP
         JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
         where NP.ID in (${nodeIds.mkString(",")})
       """
    queryNodePointList(query)
  }


}
