package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import org.joda.time.DateTime
import fi.liikennevirasto.viite.NewIdValue
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}



class CalculatedNodePointDAO extends BaseDAO {

  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

  def create(nodePoints: Iterable[NodePoint]): Seq[Long] = {

    val ps = dynamicSession.prepareStatement(
      """insert into NODE_POINT (ID, BEFORE_AFTER, ROADWAY_POINT_ID, NODE_NUMBER, "TYPE", CREATED_BY)
      values (?, ?, ?, ?, ?, ?)""".stripMargin)

    // Set ids for the node points without one
    val (ready, idLess) = nodePoints.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchNodePointIds(idLess.size)
    val createNodePoints = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    createNodePoints.foreach {
      nodePoint =>
        ps.setLong(1, nodePoint.id)
        ps.setLong(2, nodePoint.beforeAfter.value)
        ps.setLong(3, nodePoint.roadwayPointId)
        if (nodePoint.nodeNumber.isDefined) {
          ps.setLong(4, nodePoint.nodeNumber.get)
        } else {
          ps.setNull(4, java.sql.Types.INTEGER)
        }
        ps.setInt(5, nodePoint.nodePointType.value)
        ps.setString(6, nodePoint.createdBy)
        ps.addBatch()
    }
    ps.executeBatch()
    ps.close()
    createNodePoints.map(_.id).toSeq
  }
  def fetchCalculatedNodePoints(ids: Iterable[Long]) : Seq[NodePoint] = {
    val query =
      s"""
         SELECT DISTINCT NP.ID, NP.BEFORE_AFTER, NP.ROADWAY_POINT_ID, NULL AS NODE_NUMBER, NP."TYPE", NULL, NULL,
         NP.VALID_FROM, NP.VALID_TO, NP.CREATED_BY, NP.CREATED_TIME, RP.ROADWAY_NUMBER, RP.ADDR_M, RW.ROAD_NUMBER, RW.ROAD_PART_NUMBER, RW.TRACK, rw.ELY
         FROM NODE_POINT NP
         JOIN ROADWAY_POINT RP ON (RP.ID = ROADWAY_POINT_ID)
         JOIN LINEAR_LOCATION LL ON (LL.ROADWAY_NUMBER = RP.ROADWAY_NUMBER AND LL.VALID_TO IS NULL)
         LEFT JOIN ROADWAY RW ON (RP.ROADWAY_NUMBER = RW.ROADWAY_NUMBER)
         where NP.valid_to is null and NP.node_number is null and RW.end_date is NULL and RW.valid_to is null
         AND NP.type = 2
       """
    queryList(query)
  }
  implicit val getNodePoint: GetResult[NodePoint] = new GetResult[NodePoint] {
    def apply(r: PositionedResult): NodePoint = {
      val id = r.nextLong()
      val beforeAfter = r.nextLong()
      val roadwayPointId = r.nextLong()
      val nodeNumber = r.nextLongOption()
      val nodePointType = NodePointType.apply(r.nextInt())
      val startDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val endDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val validFrom = formatter.parseDateTime(r.nextDate.toString)
      val validTo = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val createdBy = r.nextString()
      val createdTime = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val roadwayNumber = r.nextLong()
      val addrM = r.nextLong()
      val roadNumber = r.nextLongOption().map(l => l).getOrElse(0L)
      val roadPartNumber = r.nextLongOption().map(l => l).getOrElse(0L)
      val track = r.nextLongOption().map(l => Track.apply(l.toInt)).getOrElse(Track.Unknown)
      val ely = r.nextLongOption().map(l => l).getOrElse(0L)

      NodePoint(id, BeforeAfter.apply(beforeAfter), roadwayPointId, nodeNumber, nodePointType, startDate, endDate, validFrom, validTo, createdBy, createdTime, roadwayNumber, addrM, roadNumber, roadPartNumber, track, ely)
    }
  }
  private def queryList(query: String): List[NodePoint] = {
    Q.queryNA[NodePoint](query).list.groupBy(_.id).map {
      case (_, list) =>
        list.head
    }.toList
  }
}
