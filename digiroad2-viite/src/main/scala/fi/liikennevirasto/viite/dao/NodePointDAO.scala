package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.dao.Sequences
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult}
import fi.liikennevirasto.viite._

sealed trait BeforeAfter {
  def value: Long
}

object BeforeAfter {
  val values: Set[BeforeAfter] = Set(Before, After, UnknownBeforeAfter)

  def apply(intValue: Long): BeforeAfter = {
    values.find(_.value == intValue).getOrElse(UnknownBeforeAfter)
  }

  case object Before extends BeforeAfter {
    def value = 1
  }

  case object After extends BeforeAfter {
    def value = 2
  }

  case object UnknownBeforeAfter extends BeforeAfter {
    def value = 9
  }
}

case class NodePoint(id: Long, beforeAfter: BeforeAfter, roadwayPointId: Long, nodeId: Option[Long], startDate: DateTime, endDate: Option[DateTime], validFrom: DateTime, validTo: Option[DateTime],
                     createdBy: Option[String], createdTime: Option[DateTime])

class NodePointDAO extends BaseDAO {

  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

  implicit val getNodePoint: GetResult[NodePoint] = new GetResult[NodePoint] {
    def apply(r: PositionedResult): NodePoint = {
      val id = r.nextLong()
      val beforeAfter = r.nextLong()
      val roadwayPointId = r.nextLong()
      val nodeId = r.nextLongOption()
      val startDate = formatter.parseDateTime(r.nextDate.toString)
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = formatter.parseDateTime(r.nextDate.toString)
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextStringOption()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))

      NodePoint(id, BeforeAfter.apply(beforeAfter), roadwayPointId, nodeId, startDate, endDate, validFrom, validTo, createdBy, createdTime)
    }
  }

  def fetchById(id: Long): Option[NodePoint] = {
    sql"""
      SELECT ID, BEFORE_AFTER, ROADWAY_POINT_ID, NODE_ID, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
      from NODE_POINT
      where NODE_ID = $id
      """.as[NodePoint].firstOption
  }

  def create(nodePoints: Iterable[NodePoint], createdBy: String = "-"): Seq[Long] = {

    val ps = dynamicSession.prepareStatement(
      """insert into NODE_POINT (ID, BEFORE_AFTER, ROADWAY_POINT_ID, NODE_ID, START_DATE, END_DATE, CREATED_BY)
      values (?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?)""".stripMargin)

    // Set ids for the nodes points without one
    val (ready, idLess) = nodePoints.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchNodePointIds(idLess.size)
    val createNodePoints = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    createNodePoints.foreach {
      nodePoint =>
        ps.setLong(1, nodePoint.id)
        ps.setLong(2, nodePoint.beforeAfter.value)
        ps.setObject(3, nodePoint.roadwayPointId)
        if (nodePoint.nodeId.isDefined) {
          ps.setLong(4, nodePoint.nodeId.get)
        } else {
          ps.setNull(4, java.sql.Types.INTEGER)
        }
        ps.setString(5, dateFormatter.print(nodePoint.startDate))
        ps.setString(6, nodePoint.endDate match {
          case Some(date) => dateFormatter.print(date)
          case None => ""
        })
        ps.setString(7, if (createdBy == null) "-" else createdBy)
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    createNodePoints.map(_.id).toSeq
  }

}
