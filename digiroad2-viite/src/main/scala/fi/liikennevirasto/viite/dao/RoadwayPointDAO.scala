package fi.liikennevirasto.viite.dao
import fi.liikennevirasto.digiroad2.dao.Sequences
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

object RoadwayPointDAO {

  case class RoadwayPoint(id: Long, roadwayNumber: Long, addrMValue: Long, createdBy: String, createdTime: Option[Long] = None, modifiedBy: Option[String] = None, modifiedTime: Option[Long] = None)

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

/*  def fetch(id:Long) : RoadwayPoint = {
    s"""

     """.stripMargin
  }*/
}
