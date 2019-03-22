package fi.liikennevirasto.viite.dao
import fi.liikennevirasto.digiroad2.dao.Sequences
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult}
import slick.jdbc.StaticQuery.interpolation

object RoadwayPointDAO {

  case class RoadwayPoint(id: Long, roadwayNumber: Long, addrMValue: Long, createdBy: String, createdTime: Option[DateTime] = None, modifiedBy: Option[String] = None, modifiedTime: Option[DateTime] = None)

  implicit val getRoadwayPointRow = new GetResult[RoadwayPoint] {
    def apply(r: PositionedResult) = {
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

  def create(roadwayPoint: RoadwayPoint): Unit = {
    sqlu"""
      Insert Into ROADWAY_POINT (ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, MODIFIED_BY) Values
      (${Sequences.nextRoadwayPointId}, ${roadwayPoint.roadwayNumber}, ${roadwayPoint.addrMValue}, ${roadwayPoint.createdBy}, ${roadwayPoint.createdBy})
      """.execute
  }

  def create(roadwayNumber: Long, addrMValue: Long, createdBy: String): Unit = {
        sqlu"""
      Insert Into ROADWAY_POINT (ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, MODIFIED_BY) Values
      (${Sequences.nextRoadwayPointId}, ${roadwayNumber}, ${addrMValue}, ${createdBy}, ${createdBy})
      """.execute
  }

  def update(id: Long, addressMValue: Long, modifiedBy: String) = {
    sqlu"""
        Update ROADWAY_POINT Set ADDR_M = ${addressMValue}, MODIFIED_BY = ${modifiedBy} Where ID = ${id}
      """.execute
  }

  def update(id: Long, roadwayNumber: Long, addressMValue: Long, modifiedBy: String) = {
    sqlu"""
        Update ROADWAY_POINT Set ROADWAY_NUMBER = ${roadwayNumber}, ADDR_M = ${addressMValue}, MODIFIED_BY = ${modifiedBy} Where ID = ${id}
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
}
