package fi.liikennevirasto.viite.dao
import fi.liikennevirasto.digiroad2.dao.Sequences
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

object RoadwayPointDAO {

  case class RoadwayPoint(id: Long, roadwayNumber: Long, addrMValue: Long, createdBy: String, createdTime: Option[DateTime] = None, modifiedBy: Option[String] = None, modifiedTime: Option[DateTime] = None)

  implicit val getRoadwayPointRow = new GetResult[RoadwayPoint] {
    def apply(r: PositionedResult): RoadwayPoint = {
      val roadwayPointId = r.nextLong()
      val roadwayNumber = r.nextLong()
      val addrMValue = r.nextLong()
      val createdBy = r.nextString()
      val createdTime = r.nextDateOption().map(d => new DateTime(d.getTime))
      val modifiedBy = r.nextStringOption()
      val modifiedTime = r.nextDateOption().map(d => new DateTime(d.getTime))

      RoadwayPoint(roadwayPointId, roadwayNumber, addrMValue, createdBy, createdTime, modifiedBy, modifiedTime)
    }
  }

  def create(roadwayPoint: RoadwayPoint): Long = {
    val id = Sequences.nextRoadwayPointId
    sqlu"""
      Insert Into ROADWAY_POINT (ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, MODIFIED_BY) Values
      ($id, ${roadwayPoint.roadwayNumber}, ${roadwayPoint.addrMValue}, ${roadwayPoint.createdBy}, ${roadwayPoint.createdBy})
      """.execute
    id
  }

  def create(roadwayNumber: Long, addrMValue: Long, createdBy: String): Long = {
    val id = Sequences.nextRoadwayPointId
        sqlu"""
      Insert Into ROADWAY_POINT (ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, MODIFIED_BY) Values
      ($id, $roadwayNumber, $addrMValue, $createdBy, $createdBy)
      """.execute
    id
  }

  def update(id: Long, addressMValue: Long, modifiedBy: String) = {
    sqlu"""
        Update ROADWAY_POINT Set ADDR_M = $addressMValue, MODIFIED_BY = $modifiedBy, MODIFIED_TIME = SYSDATE Where ID = $id
      """.execute
  }

  def update(id: Long, roadwayNumber: Long, addressMValue: Long, modifiedBy: String) = {
    sqlu"""
        Update ROADWAY_POINT Set ROADWAY_NUMBER = $roadwayNumber, ADDR_M = $addressMValue, MODIFIED_BY = $modifiedBy, MODIFIED_TIME = SYSDATE Where ID = $id
      """.execute
  }

  def fetch(id:Long) : RoadwayPoint = {
    sql"""
      SELECT ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, CREATED_TIME, MODIFIED_BY, MODIFIED_TIME
      from ROADWAY_POINT
      where id = $id
     """.as[RoadwayPoint].first
  }

  def fetch(roadwayNumber: Long, addrM: Long) : Option[RoadwayPoint] = {
    sql"""
      SELECT ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, CREATED_TIME, MODIFIED_BY, MODIFIED_TIME
      from ROADWAY_POINT
      where ROADWAY_NUMBER= $roadwayNumber and ADDR_M = $addrM
     """.as[RoadwayPoint].firstOption
  }

  def fetch(points: Seq[(Long, Long)]): Seq[RoadwayPoint] = {
    val whereClause = points.map(p => s" (roadway_number = ${p._1} and addr_m = ${p._2})").mkString(" where ", " or ", "")
    val query = s"""
      SELECT ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, CREATED_TIME, MODIFIED_BY, MODIFIED_TIME
      from ROADWAY_POINT $whereClause
       """
    queryList(query)
  }

  private def queryList(query: String): Seq[RoadwayPoint] = {
    Q.queryNA[RoadwayPoint](query).list
  }
}
