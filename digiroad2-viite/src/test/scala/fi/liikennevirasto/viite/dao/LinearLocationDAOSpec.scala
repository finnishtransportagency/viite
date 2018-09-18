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
      val orderNumber = 1
      val linkId = 1000l
      val startMValue = 0.0
      val endMValue = 100.0
      val sideCode = SideCode.TowardsDigitizing
      val adjustedTimestamp = 10000000000l
      val calibrationPoints = (Some(0l), None)
      val floating = FloatingReason.NoFloating
      val geometry = Seq(Point(0.0, 0.0), Point(0.0, 100.0))
      val linkSource = LinkGeomSource.NormalLinkInterface
      val roadwayId = 200l
      val linearLocation = LinearLocation(id, orderNumber, linkId, startMValue, endMValue, sideCode, adjustedTimestamp,
        calibrationPoints, floating, geometry, linkSource, roadwayId)
      LinearLocationDAO.create(Seq(linearLocation))
      val loc = LinearLocationDAO.fetchById(id).getOrElse(fail())

      // Check that the values were saved correctly in database
      loc.id should be(id)
      loc.orderNumber should be(orderNumber)
      loc.linkId should be(linkId)
      loc.startMValue should be(startMValue +- 0.001)
      loc.endMValue should be(endMValue +- 0.001)
      loc.sideCode should be(sideCode)
      loc.adjustedTimestamp should be(adjustedTimestamp)
      loc.calibrationPoints should be(calibrationPoints)
      loc.floating should be(floating)
      loc.geometry(0).x should be(geometry(0).x +- 0.001)
      loc.geometry(0).y should be(geometry(0).y +- 0.001)
      loc.geometry(0).z should be(geometry(0).z +- 0.001)
      loc.geometry(1).x should be(geometry(1).x +- 0.001)
      loc.geometry(1).y should be(geometry(1).y +- 0.001)
      loc.geometry(1).z should be(geometry(1).z +- 0.001)
      loc.linkGeomSource should be(linkSource)
      loc.roadwayId should be(roadwayId)
      loc.validFrom.nonEmpty should be(true)
      loc.validTo.isEmpty should be(true)

    }
  }

}
