package fi.liikennevirasto.viite.dao


import fi.liikennevirasto.digiroad2.Point
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.jdbc.{GetResult, PositionedResult}

case class Node (id: Long, nodeNumber: Long, coordinates: Point, name: Option[String], nodeType: Long, startDate: Option[DateTime], endDate: Option[DateTime],
                 validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime])

class NodeDAO {
  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()
  implicit val getNode: GetResult[Node] = new GetResult[Node] {
    def apply(r: PositionedResult): Node = {
      val id = r.nextLong()
      val nodeNumber = r.nextLong()
      val xValue = r.nextLong()
      val yValue = r.nextLong()
      val name = r.nextStringOption()
      val nodeType = r.nextLong()
      val startDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextStringOption()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))

      Node(id, nodeNumber, Point(xValue, yValue), name, nodeType, startDate, endDate, validFrom, validTo, createdBy, createdTime)
    }
  }


}
