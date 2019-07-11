import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.dao.{RoadwayPoint, RoadwayPointDAO}
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation


class RoadwayPointDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  val dao = new RoadwayPointDAO

  test("Test RoadwayPoint unique constraint When reversing roadway Then update values") {
    withDynTransaction {
      val p1 = RoadwayPoint(Sequences.nextRoadwayPointId, -100, 0, "1")
      val p2 = RoadwayPoint(Sequences.nextRoadwayPointId, -100, 100, "2")
      dao.create(p1)
      dao.create(p2)
    }
    withDynTransaction {
      val points = dao.fetchByRoadwayNumber(-100)
      points.size should be(2)
      val p1 = points.filter(p => p.addrMValue == 0).head
      val p2 = points.filter(p => p.addrMValue == 100).head
      val roadwayNumber = 12345L
      p1.addrMValue should be(0)
      p2.addrMValue should be(100)
      dao.update(Seq((roadwayNumber, p2.addrMValue, "1b", p1.id), (roadwayNumber, p1.addrMValue, "2b", p2.id)))
    }
    withDynTransaction {
      val points = dao.fetchByRoadwayNumber(-100)
      val p1 = points.filter(p => p.modifiedBy == Some("1b")).head
      val p2 = points.filter(p => p.modifiedBy == Some("2b")).head
      p1.addrMValue should be(100)
      p2.addrMValue should be(0)

      // Clean up
      sqlu"""
          DELETE FROM ROADWAY_POINT WHERE ID IN (${p1.id}, ${p2.id})
      """.execute

    }
  }

}
