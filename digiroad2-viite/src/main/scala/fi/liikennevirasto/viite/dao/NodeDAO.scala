package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class Node (id: Long, nodeNumber: Long, coordinates: Point, name: Option[String], nodeType: Long, startDate: Option[DateTime], endDate: Option[DateTime],
                 validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime])

object NodeDAO {
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
      SELECT ID, NODE_NUMBER, COORDINATES, "NAME", "TYPE", START_DATE, END_DATE, VALID_TO, CREATED_BY, CREATED_TIME, VALID_FROM
      from NODE
      where id = $nodeNumber
      """.as[Node].firstOption
  }
}
