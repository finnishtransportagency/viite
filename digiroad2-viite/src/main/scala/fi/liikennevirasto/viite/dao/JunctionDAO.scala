package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.viite._
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class Junction(id: Long, junctionNumber: Long, nodeId: Option[Long], startDate: DateTime, endDate: Option[DateTime],
                    validFrom: DateTime, validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime])

class JunctionDAO extends BaseDAO {

  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

  implicit val getJunction: GetResult[Junction] = (r: PositionedResult) => {
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

  private def queryList(query: String): List[Junction] = {
    Q.queryNA[Junction](query).list.groupBy(_.id).map {
      case (_, list) =>
        list.head
    }.toList
  }

  def fetchJunctionByNodeId(nodeId: Long): Seq[Junction] = {
    fetchJunctionByNodeIds(Seq(nodeId))
  }

  def fetchJunctionByNodeIds(nodeIds: Seq[Long]): Seq[Junction] = {
    if (nodeIds.isEmpty) {
      Seq()
    } else {
      val query = s"""
        SELECT ID, JUNCTION_NUMBER, NODE_ID, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
          FROM JUNCTION
          where NODE_ID in (${nodeIds.mkString(", ")}) AND VALID_TO IS NULL AND END_DATE IS NULL
        """
      queryList(query)
    }
  }

  def fetchJunctionId(nodeNumber: Long): Option[Long] = {
    sql"""
      SELECT ID
        FROM NODE
        WHERE NODE_NUMBER = $nodeNumber AND VALID_TO IS NULL AND END_DATE IS NULL
    """.as[Long].firstOption
  }

  def fetchByIds(ids: Iterable[Long]): Seq[Junction] = {
    if (ids.isEmpty) {
      Seq()
    } else {
      val query = s"""
        SELECT ID, JUNCTION_NUMBER, NODE_ID, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
          FROM JUNCTION
          WHERE ID IN (${ids.mkString(", ")}) AND VALID_TO IS NULL
        """
      queryList(query)
    }
  }

  /**
    * Search for Junctions that no longer have justification for the current network.
    *
    * @param ids : Iterable[Long] - The ids of the junctions to verify.
    * @return
    */
  def fetchObsoleteById(ids: Iterable[Long]): Seq[Junction] = {
    // An Obsolete junction are those that no longer have justification for the current network, and must be expired.
    if (ids.isEmpty) {
      Seq()
    } else {
      val query = s"""
        SELECT ID, JUNCTION_NUMBER, NODE_ID, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
          FROM JUNCTION J
          WHERE ID IN (${ids.mkString(", ")})
          AND (SELECT COUNT(DISTINCT RW.ROAD_NUMBER) FROM JUNCTION_POINT JP
            LEFT JOIN ROADWAY_POINT RP ON JP.ROADWAY_POINT_ID = RP.ID
            LEFT JOIN ROADWAY RW ON RW.ROADWAY_NUMBER = RP.ROADWAY_NUMBER AND RW.VALID_TO IS NULL AND RW.END_DATE IS NULL
            WHERE JP.JUNCTION_ID = J.ID AND JP.VALID_TO IS NULL AND JP.END_DATE IS NULL) < 2
          AND VALID_TO IS NULL AND END_DATE IS NULL
        """
      queryList(query)
    }
  }

  def create(junctions: Iterable[Junction], createdBy: String = "-"): Seq[Long] = {

    val ps = dynamicSession.prepareStatement(
      """insert into JUNCTION (ID, JUNCTION_NUMBER, NODE_ID, START_DATE, END_DATE, CREATED_BY)
      values (?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?)""".stripMargin)

    // Set ids for the junctions without one
    val (ready, idLess) = junctions.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchJunctionIds(idLess.size)
    val createJunctions = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    createJunctions.foreach {
      junction =>
        ps.setLong(1, junction.id)
        ps.setLong(2, junction.junctionNumber)
        if (junction.nodeId.isDefined) {
          ps.setLong(3, junction.nodeId.get)
        } else {
          ps.setNull(3, java.sql.Types.INTEGER)
        }
        ps.setString(4, dateFormatter.print(junction.startDate))
        ps.setString(5, junction.endDate match {
          case Some(date) => dateFormatter.print(date)
          case None => ""
        })
        ps.setString(6, if (createdBy == null) "-" else createdBy)
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    createJunctions.map(_.id).toSeq
  }

  /**
    * Expires junctions (set their valid_to to the current system date).
    *
    * @param ids : Iterable[Long] - The ids of the junctions to expire.
    * @return
    */
  def expireById(ids: Iterable[Long]): Int = {
    if (ids.isEmpty) 0
    else {
      val query = s"""UPDATE JUNCTION SET VALID_TO = SYSDATE WHERE VALID_TO IS NULL AND ID IN (${ids.mkString(", ")})"""
      Q.updateNA(query).first
    }
  }

}
