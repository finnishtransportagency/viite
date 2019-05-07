package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class Junction(id: Long, junctionNumber: Long, rampNumber: Option[Long], trafficLights: Long, nodeId: Option[Long], startDate: Option[DateTime], endDate: Option[DateTime],
                    lightsStartDate: Option[DateTime], validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime])

case class JunctionPoint(id: Long, beforeOrAfter: Long, roadwayPointId: Long, junctionId: Long, startDate: Option[DateTime], endDate: Option[DateTime],
                         validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime])

class JunctionDAO {
  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()
  implicit val getJunction: GetResult[Junction] = new GetResult[Junction] {
    def apply(r: PositionedResult): Junction = {
      val id = r.nextLong()
      val junctionNumber = r.nextLong()
      val rampNumber = r.nextLongOption()
      val trafficLights = r.nextLong()
      val nodeId = r.nextLongOption()
      val startDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val lightsStartDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextStringOption()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      Junction(id, junctionNumber, rampNumber, trafficLights, nodeId, startDate, endDate, lightsStartDate, validFrom, validTo, createdBy, createdTime)
    }
  }

  implicit val getJunctionPoint: GetResult[JunctionPoint] = new GetResult[JunctionPoint] {
    def apply(r: PositionedResult): JunctionPoint = {
      val id = r.nextLong()
      val beforeOrAfter = r.nextLong()
      val roadwayPointId = r.nextLong()
      val junctionId = r.nextLong()
      val startDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextStringOption()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      JunctionPoint(id, beforeOrAfter, roadwayPointId, junctionId, startDate, endDate, validFrom, validTo, createdBy, createdTime)
    }
  }

  def fetchByNodeId(nodeId: Long): Seq[Junction] = {
    sql"""
      SELECT ID, JUNCTION_NUMBER, RAMP_NUMBER, TRAFFIC_LIGHTS, NODE_ID, START_DATE, END_DATE, LIGHTS_START_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
      FROM JUNCTION
      where NODE_ID = $nodeId
      """.as[Junction].list
  }

  def fetchId(nodeNumber: Long): Option[Long] = {
    sql"""
      SELECT ID
      from NODE
      where NODE_NUMBER = $nodeNumber
      """.as[Long].firstOption
  }

}
