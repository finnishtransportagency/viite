package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import fi.liikennevirasto.viite._

case class Junction(id: Long, junctionNumber: Long, nodeId: Option[Long], startDate: DateTime, endDate: Option[DateTime],
                    validFrom: DateTime, validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime])

class JunctionDAO extends BaseDAO {

  implicit val getJunction: GetResult[Junction] = new GetResult[Junction] {
    def apply(r: PositionedResult): Junction = {
      val id = r.nextLong()
      val junctionNumber = r.nextLong()
      val nodeId = r.nextLongOption()
      val startDate = formatter.parseDateTime(r.nextDate.toString)
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = formatter.parseDateTime(r.nextDate.toString)
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextStringOption()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      Junction(id, junctionNumber, nodeId, startDate, endDate, validFrom, validTo, createdBy, createdTime)
    }
  }

  private def queryList(query: String): List[Junction] = {
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
    queryList(query)
  }

  def fetchJunctionByNodeIds(nodeIds: Seq[Long]): Seq[Junction] = {
    val query =
      s"""
      SELECT ID, JUNCTION_NUMBER, NODE_ID, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
      FROM JUNCTION
      where NODE_ID in (${nodeIds.mkString(", ")})
      """
    queryList(query)
  }

  def fetchJunctionId(nodeNumber: Long): Option[Long] = {
    sql"""
      SELECT ID
      from NODE
      where NODE_NUMBER = $nodeNumber
      """.as[Long].firstOption
  }

  def fetchByIds(ids: Seq[Long]): Seq[Junction] = {
    val query =
      s"""
      SELECT ID, JUNCTION_NUMBER, NODE_ID, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
      FROM JUNCTION
      WHERE ID IN (${ids.mkString(", ")})
      """
    queryList(query)
  }

  // TODO
  /*
  def create(junctions: Iterable[Junction], createdBy: String = "-"): Seq[Long] = {

    val ps = dynamicSession.prepareStatement(
      """insert into JUNCTION (ID, JUNCTION_NUMBER, COORDINATES, "NAME", "TYPE", START_DATE, END_DATE, CREATED_BY)
      values (?, ?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?)""".stripMargin)

    // Set ids for the junctions without one
    val (ready, idLess) = junctions.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchJunctionIds(idLess.size)
    val createJunctions = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    createJunctions.foreach {
      junction =>
        val junctionNumber = if (junction.junctionNumber == NewIdValue) {
          Sequences.nextJunctionNumber
        } else {
          junction.junctionNumber
        }
        ps.setLong(1, junction.id)
        ps.setLong(2, junctionNumber)
        ps.setObject(3, OracleDatabase.createPointJGeometry(junction.coordinates))
        if (junction.name.isDefined) {
          ps.setString(4, junction.name.get)
        } else {
          ps.setNull(4, java.sql.Types.VARCHAR)
        }
        ps.setLong(5, junction.nodeType.value)
        ps.setString(6, dateFormatter.print(junction.startDate))
        ps.setString(7, junction.endDate match {
          case Some(date) => dateFormatter.print(date)
          case None => ""
        })
        ps.setString(8, if (createdBy == null) "-" else createdBy)
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    createJunctions.map(_.id).toSeq
  }*/

}
