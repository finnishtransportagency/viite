package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import fi.liikennevirasto.viite._

class LinearLocationDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  test("Create new linear location") {
    runWithRollback {
      val id = LinearLocationDAO.getNextLinearLocationId
      val linearLocation = LinearLocation(id, 1, 1000, 0, 100, SideCode.TowardsDigitizing, 10000000,
        (Some(0), None), FloatingReason.NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 100.0)), LinkGeomSource.NormalLinkInterface, 200)
      LinearLocationDAO.create(Seq(linearLocation))
      val loc = LinearLocationDAO.fetchById(id).getOrElse(fail())
      loc.validFrom.nonEmpty should be(true)

    }
  }

}
