package fi.liikennevirasto.viite.dao

import java.sql.BatchUpdateException

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.process.RoadAddressFiller.LinearLocationAdjustment

class LinearLocationDAOSpec extends FunSuite with Matchers {

  val testLinearLocation = LinearLocation(NewLinearLocation, 1, 1000l, 0.0, 100.0, SideCode.TowardsDigitizing, 10000000000l,
    (Some(0l), None), FloatingReason.NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 100.0)), LinkGeomSource.NormalLinkInterface, 200l)

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  test("Create new linear location and read it from the database") {
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

  test("No duplicate linear locations") {
    runWithRollback {
      val id1 = LinearLocationDAO.getNextLinearLocationId
      val id2 = LinearLocationDAO.getNextLinearLocationId
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id1)))
      intercept[BatchUpdateException] {
        LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id2)))
      }
    }
  }

  test("Remove (expire) linear location") {
    runWithRollback {
      val id1 = LinearLocationDAO.getNextLinearLocationId
      val id2 = LinearLocationDAO.getNextLinearLocationId
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id1)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 9999l)))

      // Before expiration valid_to date should be null for both
      val loc1 = LinearLocationDAO.fetchById(id1).getOrElse(fail())
      loc1.validTo.isEmpty should be(true)
      val loc2 = LinearLocationDAO.fetchById(id2).getOrElse(fail())
      loc2.validTo.isEmpty should be(true)

      // After expiration valid_to date should be set for the first
      LinearLocationDAO.remove(Seq(loc1))
      val expired = LinearLocationDAO.fetchById(id1).getOrElse(fail())
      expired.validTo.nonEmpty should be(true)
      val nonExpired = LinearLocationDAO.fetchById(id2).getOrElse(fail())
      nonExpired.validTo.isEmpty should be(true)

    }
  }

  test("Expire linear location by id") {
    runWithRollback {
      val id1 = LinearLocationDAO.getNextLinearLocationId
      val id2 = LinearLocationDAO.getNextLinearLocationId
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id1)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 9999l)))

      // Before expiration valid_to date should be null for both
      val loc1 = LinearLocationDAO.fetchById(id1).getOrElse(fail())
      loc1.validTo.isEmpty should be(true)
      val loc2 = LinearLocationDAO.fetchById(id2).getOrElse(fail())
      loc2.validTo.isEmpty should be(true)

      // After expiration valid_to date should be set for the first
      LinearLocationDAO.expireById(Set(id1))
      val expired = LinearLocationDAO.fetchById(id1).getOrElse(fail())
      expired.validTo.nonEmpty should be(true)
      val nonExpired = LinearLocationDAO.fetchById(id2).getOrElse(fail())
      nonExpired.validTo.isEmpty should be(true)

    }
  }

  test("Expire linear location by link id") {
    runWithRollback {
      val id1 = LinearLocationDAO.getNextLinearLocationId
      val id2 = LinearLocationDAO.getNextLinearLocationId
      val linkId = 1000l
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 9999l)))

      // Before expiration valid_to date should be null for both
      val loc1 = LinearLocationDAO.fetchById(id1).getOrElse(fail())
      loc1.validTo.isEmpty should be(true)
      val loc2 = LinearLocationDAO.fetchById(id2).getOrElse(fail())
      loc2.validTo.isEmpty should be(true)

      // After expiration valid_to date should be set for the first
      LinearLocationDAO.expireByLinkId(Set(linkId))
      val expired = LinearLocationDAO.fetchById(id1).getOrElse(fail())
      expired.validTo.nonEmpty should be(true)
      val nonExpired = LinearLocationDAO.fetchById(id2).getOrElse(fail())
      nonExpired.validTo.isEmpty should be(true)

    }
  }

  test("Fetch by id") {
    runWithRollback {
      val id1 = LinearLocationDAO.getNextLinearLocationId
      val id2 = LinearLocationDAO.getNextLinearLocationId
      val id3 = LinearLocationDAO.getNextLinearLocationId
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = 111111111l)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 222222222l)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 333333333l)))

      val locations = LinearLocationDAO.fetchById(id1)
      locations.size should be(1)
      locations.filter(l => l.id == id1).size should be(1)

      val massQueryLocations = LinearLocationDAO.fetchByIdMassQuery(Set(id1, -101l, -102l, -103l, id2))
      massQueryLocations.size should be(2)
      massQueryLocations.filter(l => l.id == id1).size should be(1)
      massQueryLocations.filter(l => l.id == id2).size should be(1)
    }
  }

  test("Fetch by link id") {
    runWithRollback {
      val id1 = LinearLocationDAO.getNextLinearLocationId
      val id2 = LinearLocationDAO.getNextLinearLocationId
      val id3 = LinearLocationDAO.getNextLinearLocationId
      val linkId1 = 111111111l
      val linkId2 = 222222222l
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId1, startMValue = 200.0, endMValue = 300.0)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = linkId2)))

      val locations = LinearLocationDAO.fetchByLinkId(Set(linkId1))
      locations.size should be(2)
      locations.filter(l => l.id == id1).size should be(1)
      locations.filter(l => l.id == id2).size should be(1)

      val massQueryLocations = LinearLocationDAO.fetchByLinkIdMassQuery(Set(linkId1, -101l, -102l, -103l, linkId2))
      massQueryLocations.size should be(3)
      massQueryLocations.filter(l => l.id == id1).size should be(1)
      massQueryLocations.filter(l => l.id == id2).size should be(1)
      massQueryLocations.filter(l => l.id == id3).size should be(1)
    }
  }

  test("Fetch all floating linear locations") {
    runWithRollback {
      val id1 = LinearLocationDAO.getNextLinearLocationId
      val id2 = LinearLocationDAO.getNextLinearLocationId
      val id3 = LinearLocationDAO.getNextLinearLocationId
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = 1l)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 2l, floating = FloatingReason.ManualFloating)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 3l, floating = FloatingReason.NewAddressGiven)))

      val locations = LinearLocationDAO.fetchAllFloatingLinearLocations
      locations.filter(l => l.id == id1).size should be(0)
      locations.filter(l => l.id == id2).size should be(1)
      locations.filter(l => l.id == id3).size should be(1)
    }
  }

  test("Set linear location floating reason") {
    runWithRollback {
      val id1 = LinearLocationDAO.getNextLinearLocationId
      val id2 = LinearLocationDAO.getNextLinearLocationId
      val id3 = LinearLocationDAO.getNextLinearLocationId
      val linkId = 222222222l
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = 111111111l)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 333333333l)))

      val locationsBefore = LinearLocationDAO.fetchByLinkId(Set(linkId))
      locationsBefore.head.floating should be(FloatingReason.NoFloating)

      val floating = FloatingReason.ManualFloating
      LinearLocationDAO.setLinearLocationFloatingReason(id = id2, geometry = None, floatingReason = floating, createdBy = "test")
      val expired = LinearLocationDAO.fetchById(id2).getOrElse(fail())
      expired.validTo.nonEmpty should be(true)
      expired.floating should be(FloatingReason.NoFloating)
      val linearLocations = LinearLocationDAO.fetchByLinkId(Set(linkId), includeFloating = true)
      linearLocations.size should be(1)
      val floatingLocation = linearLocations.head
      floatingLocation.floating should be(floating)
    }
  }

  test("Update linear location") {
    runWithRollback {
      val id1 = LinearLocationDAO.getNextLinearLocationId
      val id2 = LinearLocationDAO.getNextLinearLocationId
      val linkId1 = 111111111l
      val linkId2 = 222222222l
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      val startM = 1.1
      val endM = 2.2
      LinearLocationDAO.updateLinearLocation(LinearLocationAdjustment(id2, linkId2, Some(startM), Some(endM)), createdBy = "test")

      // Original linear location should be expired
      val expired = LinearLocationDAO.fetchById(id2).getOrElse(fail())
      expired.validTo.nonEmpty should be(true)
      val unChanged = LinearLocationDAO.fetchById(id1).getOrElse(fail())
      unChanged.validTo.isEmpty should be(true)

      // New linear location should have new startM and endM
      val locations = LinearLocationDAO.fetchByLinkId(Set(linkId2))
      val updated = locations.head
      updated.startMValue should be(startM +- 0.001)
      updated.endMValue should be(endM +- 0.001)

      // Update linkId and endM
      LinearLocationDAO.updateLinearLocation(LinearLocationAdjustment(updated.id, 999999999l, None, Some(9999.9)), createdBy = "test")
      val locations2 = LinearLocationDAO.fetchByLinkId(Set(updated.linkId))
      locations2.size should be(0)

    }
  }

  test("Update geometry") {
    runWithRollback {
      val id1 = LinearLocationDAO.getNextLinearLocationId
      val id2 = LinearLocationDAO.getNextLinearLocationId
      val linkId1 = 111111111l
      val linkId2 = 222222222l
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1, geometry = Seq(Point(0.0, 0.0), Point(0.0, 100.0)))))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      LinearLocationDAO.fetchById(id1)

      // Update geometry so that the direction of digitization changes
      val newStart = Point(0.0, 100.0)
      val newEnd = Point(0.0, 0.0)
      LinearLocationDAO.updateGeometry(id1, Seq(newStart, newEnd), "test")

      // Original linear location should be expired
      val expired = LinearLocationDAO.fetchById(id1).getOrElse(fail())
      expired.validTo.nonEmpty should be(true)
      val unChanged = LinearLocationDAO.fetchById(id2).getOrElse(fail())
      unChanged.validTo.isEmpty should be(true)

      // New linear location should have new geometry and side code
      val locations = LinearLocationDAO.fetchByLinkId(Set(linkId1))
      val updated = locations.head
      updated.id should not be (id1)
      updated.geometry.head.x should be(newStart.x +- 0.001)
      updated.geometry.head.y should be(newStart.y +- 0.001)
      updated.geometry.last.x should be(newEnd.x +- 0.001)
      updated.geometry.last.y should be(newEnd.y +- 0.001)

    }
  }

  test("Query floating by link id") {
    runWithRollback {
      val id1 = LinearLocationDAO.getNextLinearLocationId
      val id2 = LinearLocationDAO.getNextLinearLocationId
      val id3 = LinearLocationDAO.getNextLinearLocationId
      val linkId1 = 1111111111l
      val linkId2 = 2222222222l
      val linkId3 = 3333333333l
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2, floating = FloatingReason.ManualFloating)))
      LinearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = linkId3, floating = FloatingReason.NewAddressGiven)))

      val locations = LinearLocationDAO.queryFloatingByLinkId(Set(linkId1, linkId2, linkId3))
      locations.filter(l => l.id == id1).size should be(0)
      locations.filter(l => l.id == id2).size should be(1)
      locations.filter(l => l.id == id3).size should be(1)

      val massQueryLocations = LinearLocationDAO.queryFloatingByLinkIdMassQuery(Set(linkId1, linkId2, linkId3))
      massQueryLocations.filter(l => l.id == id1).size should be(0)
      massQueryLocations.filter(l => l.id == id2).size should be(1)
      massQueryLocations.filter(l => l.id == id3).size should be(1)
    }
  }

}
