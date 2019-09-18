package fi.liikennevirasto.viite.dao

import java.sql.Date
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.Track
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}

case class Junction(id: Long, junctionNumber: Long, nodeId: Option[Long], startDate: DateTime, endDate: Option[DateTime],
                    validFrom: DateTime, validTo: Option[DateTime], createdBy: Option[String], createdTime: Option[DateTime])
case class JunctionInfo(id: Long, junctionNumber: Long, nodeId: Long, startDate: DateTime,
                     nodeNumber: Long, nodeName: String)

case class JunctionTemplate(junctionId: Long, junctionNumber: Long, startDate: DateTime, roadNumber: Long, roadPartNumber: Long, track: Track, addrM: Long, elyCode: Long)

class JunctionDAO extends BaseDAO {

  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

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

  implicit val getJunctionTemplate: GetResult[JunctionTemplate] = new GetResult[JunctionTemplate] {
    def apply(r: PositionedResult): JunctionTemplate = {
      val junctionId = r.nextLong()
      val junctionNumber = r.nextLong()
      val startDate = formatter.parseDateTime(r.nextDate.toString)
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val trackCode = r.nextInt()
      val addrM = r.nextLong()
      val ely = r.nextLong()

      JunctionTemplate(junctionId, junctionNumber, startDate, roadNumber, roadPartNumber, Track.apply(trackCode), addrM, ely)
    }
  }

    implicit val getJunctionInfo: GetResult[JunctionInfo] = new GetResult[JunctionInfo] {
      def apply(r: PositionedResult): JunctionInfo = {
        val id = r.nextLong()
        val junctionNumber = r.nextLong()
        val nodeId = r.nextLong()
        val startDate = formatter.parseDateTime(r.nextDate.toString)
        val nodeNumber =r.nextLong()
        val nodeName = r.nextString()

        JunctionInfo(id, junctionNumber, nodeId, startDate, nodeNumber, nodeName)
      }
  }

  private def queryList(query: String): List[Junction] = {
    Q.queryNA[Junction](query).list.groupBy(_.id).map {
      case (_, list) =>
        list.head
    }.toList
  }

  private def queryListTemplate(query: String): List[JunctionTemplate] = {
    Q.queryNA[JunctionTemplate](query).list.groupBy(_.junctionId).map {
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

  def fetchByIds(ids: Seq[Long]): Seq[Junction] = {
    if (ids.isEmpty)
      List()
    else {
      val query =
        s"""
      SELECT ID, JUNCTION_NUMBER, NODE_ID, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME
      FROM JUNCTION
      WHERE ID IN (${ids.mkString(", ")}) AND VALID_TO IS NULL
      """
      queryList(query)
    }
  }
  def fetchJunctionInfoByJunctionId(ids: Seq[Long]): Option[JunctionInfo] = {
    sql"""
      SELECT junction.ID, junction.JUNCTION_NUMBER, junction.NODE_ID, junction.START_DATE, node.NODE_NUMBER, node.NAME
      FROM JUNCTION junction
      LEFT JOIN NODE node ON junction.NODE_ID = node.ID
      WHERE junction.ID IN (${ids.mkString(", ")})
      """.as[JunctionInfo].firstOption

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

  def fetchTemplates() : Seq[JunctionTemplate] = {
    val query =
      s"""
         SELECT DISTINCT junction.ID, junction.JUNCTION_NUMBER, junction.START_DATE, rw.ROAD_NUMBER, rw.ROAD_PART_NUMBER, rw.TRACK, rp.ADDR_M, rw.ELY
         FROM JUNCTION junction
         LEFT JOIN JUNCTION_POINT jp ON junction.ID = jp.JUNCTION_ID AND jp.VALID_TO IS NULL AND jp.END_DATE IS NULL
         LEFT JOIN ROADWAY_POINT rp ON jp.ROADWAY_POINT_ID = rp.ID
         LEFT JOIN ROADWAY rw ON rp.ROADWAY_NUMBER = rw.ROADWAY_NUMBER AND rw.VALID_TO IS NULL AND rw.END_DATE IS NULL
            WHERE junction.VALID_TO IS NULL AND junction.END_DATE IS NULL AND junction.NODE_ID IS NULL
       """
        queryListTemplate(query)
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
