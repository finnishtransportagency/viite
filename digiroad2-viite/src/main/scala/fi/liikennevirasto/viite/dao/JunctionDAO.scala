package fi.liikennevirasto.viite.dao

import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class Junction(id: Long, junctionNumber: Long, nodeId: Option[Long], startDate: Option[DateTime], endDate: Option[DateTime],
                    validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime])

class JunctionDAO {
  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()
  implicit val getJunction: GetResult[Junction] = new GetResult[Junction] {
    def apply(r: PositionedResult): Junction = {
      val id = r.nextLong()
      val junctionNumber = r.nextLong()
      val nodeId = r.nextLongOption()
      val startDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextStringOption()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      Junction(id, junctionNumber, nodeId, startDate, endDate, validFrom, validTo, createdBy, createdTime)
    }
  }

  private def queryJunctionList(query: String): List[Junction] = {
    Q.queryNA[Junction](query).list.groupBy(_.id).map {
      case (_, list) =>
        list.head
    }.toList
  }

  def fetchJunctionByNodeId(nodeId: Long): Seq[Junction] = {
    val query =
      s"""
        SELECT ID, JUNCTION_NUMBER, NODE_ID, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
        FROM JUNCTION
        where NODE_ID = $nodeId
      """
    queryJunctionList(query)
  }

  def fetchJunctionByNodeIds(nodeIds: Seq[Long]): Seq[Junction] = {
    val query =
      s"""
      SELECT ID, JUNCTION_NUMBER, NODE_ID, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
      FROM JUNCTION
      where NODE_ID in (${nodeIds.mkString(",")})
      """
    queryJunctionList(query)
  }

  def fetchJunctionId(nodeNumber: Long): Option[Long] = {
    sql"""
      SELECT ID
      from NODE
      where NODE_NUMBER = $nodeNumber
      """.as[Long].firstOption
  }
}
