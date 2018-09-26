package fi.liikennevirasto.viite.dao

import java.sql.BatchUpdateException

import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.Track.Combined
import fi.liikennevirasto.digiroad2.{Point, asset}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.process.RoadAddressFiller.LinearLocationAdjustment
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class LinearLocationDAOSpec extends FunSuite with Matchers {

  val linearLocationDAO = new LinearLocationDAO
  val roadAddressDAO = new RoadwayDAO

  val testLinearLocation = LinearLocation(NewLinearLocation, 1, 1000l, 0.0, 100.0, SideCode.TowardsDigitizing, 10000000000l,
    (Some(0l), None), FloatingReason.NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 100.0)), LinkGeomSource.NormalLinkInterface, 200l)

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  test("Create new linear location with new roadway id with no calibration points") {
    runWithRollback {
      linearLocationDAO.create(Seq(testLinearLocation.copy(roadwayNumber = NewRoadwayNumber, calibrationPoints = (None, None))))
    }
  }

  test("Lock linear location writing - allow create in same transaction") {
    runWithRollback {
      linearLocationDAO.lockLinearLocationWriting
      linearLocationDAO.create(Seq(testLinearLocation))
      /* This would result in wait
      runWithRollback {
        intercept[BatchUpdateException] {
          linearLocationDAO.create(Seq(testLinearLocation))
        }
      }
      */
    }
  }

  test("Create new linear location and read it from the database") {
    runWithRollback {
      val id = linearLocationDAO.getNextLinearLocationId
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
      val roadwayNumber = 200l
      val linearLocation = LinearLocation(id, orderNumber, linkId, startMValue, endMValue, sideCode, adjustedTimestamp,
        calibrationPoints, floating, geometry, linkSource, roadwayNumber)
      linearLocationDAO.create(Seq(linearLocation))
      val loc = linearLocationDAO.fetchById(id).getOrElse(fail())

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
      loc.roadwayNumber should be(roadwayNumber)
      loc.validFrom.nonEmpty should be(true)
      loc.validTo.isEmpty should be(true)

    }
  }

  test("No duplicate linear locations") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1)))
      intercept[BatchUpdateException] {
        linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2)))
      }
    }
  }

  test("Remove (expire) linear location") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 9999l)))

      // Before expiration valid_to date should be null for both
      val loc1 = linearLocationDAO.fetchById(id1).getOrElse(fail())
      loc1.validTo.isEmpty should be(true)
      val loc2 = linearLocationDAO.fetchById(id2).getOrElse(fail())
      loc2.validTo.isEmpty should be(true)

      // After expiration valid_to date should be set for the first
      linearLocationDAO.expire(Seq(loc1))
      val expired = linearLocationDAO.fetchById(id1).getOrElse(fail())
      expired.validTo.nonEmpty should be(true)
      val nonExpired = linearLocationDAO.fetchById(id2).getOrElse(fail())
      nonExpired.validTo.isEmpty should be(true)

    }
  }

  test("Expire linear location by id") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 9999l)))

      // Before expiration valid_to date should be null for both
      val loc1 = linearLocationDAO.fetchById(id1).getOrElse(fail())
      loc1.validTo.isEmpty should be(true)
      val loc2 = linearLocationDAO.fetchById(id2).getOrElse(fail())
      loc2.validTo.isEmpty should be(true)

      linearLocationDAO.expireByIds(Set()) should be(0)

      // After expiration valid_to date should be set for the first
      linearLocationDAO.expireByIds(Set(id1))
      val expired = linearLocationDAO.fetchById(id1).getOrElse(fail())
      expired.validTo.nonEmpty should be(true)
      val nonExpired = linearLocationDAO.fetchById(id2).getOrElse(fail())
      nonExpired.validTo.isEmpty should be(true)

    }
  }

  test("Expire linear location by link id - none") {
    runWithRollback {
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = linearLocationDAO.getNextLinearLocationId)))
      linearLocationDAO.expireByLinkId(Set()) should be(0)
    }
  }

  test("Expire linear location by link id") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val linkId = 1000l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 9999l)))

      // Before expiration valid_to date should be null for both
      val loc1 = linearLocationDAO.fetchById(id1).getOrElse(fail())
      loc1.validTo.isEmpty should be(true)
      val loc2 = linearLocationDAO.fetchById(id2).getOrElse(fail())
      loc2.validTo.isEmpty should be(true)

      // After expiration valid_to date should be set for the first
      val updated = linearLocationDAO.expireByLinkId(Set(linkId))
      updated should be(1)
      val expired = linearLocationDAO.fetchById(id1).getOrElse(fail())
      expired.validTo.nonEmpty should be(true)
      val nonExpired = linearLocationDAO.fetchById(id2).getOrElse(fail())
      nonExpired.validTo.isEmpty should be(true)

    }
  }

  test("Fetch by id") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = 111111111l)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 222222222l)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 333333333l)))

      val locations = linearLocationDAO.fetchById(id1)
      locations.size should be(1)
      locations.filter(l => l.id == id1).size should be(1)

      val massQueryLocations = linearLocationDAO.fetchByIdMassQuery(Set(id1, -101l, -102l, -103l, id2))
      massQueryLocations.size should be(2)
      massQueryLocations.filter(l => l.id == id1).size should be(1)
      massQueryLocations.filter(l => l.id == id2).size should be(1)
    }
  }

  test("Fetch by link id - none") {
    runWithRollback {
      linearLocationDAO.create(Seq(testLinearLocation))
      val noLocations = linearLocationDAO.fetchByLinkId(Set())
      noLocations.size should be(0)
    }
  }

  test("Fetch by link id") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId1, startMValue = 200.0, endMValue = 300.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = linkId2)))

      val locations = linearLocationDAO.fetchByLinkId(Set(linkId1))
      locations.size should be(2)
      locations.filter(l => l.id == id1).size should be(1)
      locations.filter(l => l.id == id2).size should be(1)

      val massQueryLocations = linearLocationDAO.fetchByLinkIdMassQuery(Set(linkId1, -101l, -102l, -103l, linkId2))
      massQueryLocations.size should be(3)
      massQueryLocations.filter(l => l.id == id1).size should be(1)
      massQueryLocations.filter(l => l.id == id2).size should be(1)
      massQueryLocations.filter(l => l.id == id3).size should be(1)
    }
  }

  test("Fetch by link id - floatings and filter ids") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1, floating = FloatingReason.ManualFloating)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId1, startMValue = 200.0, endMValue = 300.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = linkId2)))

      val locations0 = linearLocationDAO.fetchByLinkId(Set(linkId1, linkId2), includeFloating = true, filterIds = Set(id1, id2, id3))
      locations0.size should be(0)

      val locations1 = linearLocationDAO.fetchByLinkId(Set(linkId1, linkId2), includeFloating = true, filterIds = Set(id2, id3))
      locations1.size should be(1)
      locations1.filter(l => l.id == id1).size should be(1)
      locations1.filter(l => l.id == id2).size should be(0)
      locations1.filter(l => l.id == id3).size should be(0)

      val locations2 = linearLocationDAO.fetchByLinkId(Set(linkId1, linkId2), includeFloating = true, filterIds = Set(id2))
      locations2.size should be(2)
      locations2.filter(l => l.id == id1).size should be(1)
      locations2.filter(l => l.id == id2).size should be(0)
      locations2.filter(l => l.id == id3).size should be(1)

      val massQueryLocations = linearLocationDAO.fetchByLinkIdMassQuery(Set(linkId1, -101l, -102l, -103l, linkId2), includeFloating = true)
      massQueryLocations.size should be(3)
      massQueryLocations.filter(l => l.id == id1).size should be(1)
      massQueryLocations.filter(l => l.id == id2).size should be(1)
      massQueryLocations.filter(l => l.id == id3).size should be(1)
    }
  }

  test("Fetch roadways by link id - none") {
    runWithRollback {
      linearLocationDAO.create(Seq(testLinearLocation))
      val noLocations = linearLocationDAO.fetchRoadwayByLinkId(Set())
      noLocations.size should be(0)
    }
  }

  test("Fetch roadways by link id") {
    runWithRollback {
      val (id1, id2, id3, id4) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      val roadwayNumber = 11111111l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, roadwayNumber = roadwayNumber, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, roadwayNumber = roadwayNumber, linkId = linkId1, startMValue = 200.0, endMValue = 300.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, roadwayNumber = roadwayNumber, linkId = linkId2, startMValue = 300.0, endMValue = 400.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id4, roadwayNumber = 222222l, linkId = linkId2)))

      val locations = linearLocationDAO.fetchRoadwayByLinkId(Set(linkId1))
      locations.filter(l => l.id == id1).size should be(1)
      locations.filter(l => l.id == id2).size should be(1)
      locations.filter(l => l.id == id3).size should be(1)
      locations.size should be(3)

      val massQueryLocations = linearLocationDAO.fetchRoadwayByLinkIdMassQuery(Set(linkId1))
      massQueryLocations.filter(l => l.id == id1).size should be(1)
      massQueryLocations.filter(l => l.id == id2).size should be(1)
      massQueryLocations.filter(l => l.id == id3).size should be(1)
      massQueryLocations.size should be(3)
    }
  }

  test("Fetch roadways by link id - floatings should be included") {
    runWithRollback {
      val (id1, id2, id3, id4) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      val roadwayNumber = 111111l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, roadwayNumber = roadwayNumber, linkId = linkId1, floating = FloatingReason.ManualFloating)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, roadwayNumber = roadwayNumber, linkId = linkId1, startMValue = 200.0, endMValue = 300.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, roadwayNumber = roadwayNumber, linkId = linkId2)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id4, roadwayNumber = 222222l, linkId = linkId2)))

      val locations1 = linearLocationDAO.fetchRoadwayByLinkId(Set(linkId1))
      locations1.filter(l => l.id == id1).size should be(1)
      locations1.filter(l => l.id == id2).size should be(1)
      locations1.filter(l => l.id == id3).size should be(1)
      locations1.size should be(3)

      val locations2 = linearLocationDAO.fetchRoadwayByLinkId(Set(linkId2))
      locations2.filter(l => l.id == id1).size should be(1)
      locations2.filter(l => l.id == id2).size should be(1)
      locations2.filter(l => l.id == id3).size should be(1)
      locations2.filter(l => l.id == id4).size should be(1)
      locations2.size should be(4)

      val massQueryLocations = linearLocationDAO.fetchRoadwayByLinkIdMassQuery(Set(linkId1, -101l, -102l, -103l, linkId2))
      massQueryLocations.filter(l => l.id == id1).size should be(1)
      massQueryLocations.filter(l => l.id == id2).size should be(1)
      massQueryLocations.filter(l => l.id == id3).size should be(1)
      massQueryLocations.filter(l => l.id == id4).size should be(1)
      massQueryLocations.size should be(4)
    }
  }

  test("Fetch all floating linear locations") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = 1l)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 2l, floating = FloatingReason.ManualFloating)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 3l, floating = FloatingReason.NewAddressGiven)))

      val locations = linearLocationDAO.fetchAllFloatingLinearLocations
      locations.filter(l => l.id == id1).size should be(0)
      locations.filter(l => l.id == id2).size should be(1)
      locations.filter(l => l.id == id3).size should be(1)
    }
  }

  test("Set linear location floating reason") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val linkId1 = 111111111l
      val linkId2 = 222222222l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      val locationsBefore = linearLocationDAO.fetchByLinkId(Set(linkId1))
      locationsBefore.head.floating should be(FloatingReason.NoFloating)

      // Without geometry
      val floating1 = FloatingReason.ManualFloating
      linearLocationDAO.updateToFloating(id = id1, geometry = None, floatingReason = floating1, createdBy = "test")
      val expired1 = linearLocationDAO.fetchById(id1).getOrElse(fail())
      expired1.validTo.nonEmpty should be(true)
      expired1.floating should be(FloatingReason.NoFloating)
      val linearLocations1 = linearLocationDAO.fetchByLinkId(Set(linkId1), includeFloating = true)
      linearLocations1.size should be(1)
      val floatingLocation1 = linearLocations1.head
      floatingLocation1.floating should be(floating1)

      // With geometry
      val floating2 = FloatingReason.GeometryChanged
      val newGeometry = Seq(Point(1.1, 10.1), Point(2.2, 20.2))
      linearLocationDAO.updateToFloating(id = id2, geometry = Some(newGeometry), floatingReason = floating2)
      val expired2 = linearLocationDAO.fetchById(id2).getOrElse(fail())
      expired2.validTo.nonEmpty should be(true)
      expired2.floating should be(FloatingReason.NoFloating)
      val linearLocations2 = linearLocationDAO.fetchByLinkId(Set(linkId2), includeFloating = true)
      linearLocations2.size should be(1)
      val floatingLocation2 = linearLocations2.head
      floatingLocation2.floating should be(floating2)
      floatingLocation2.geometry.head.x should be(newGeometry.head.x)
      floatingLocation2.geometry.head.y should be(newGeometry.head.y)
      floatingLocation2.geometry.last.x should be(newGeometry.last.x)
      floatingLocation2.geometry.last.y should be(newGeometry.last.y)

    }
  }

  test("Update linear location") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      val startM = 1.1
      val endM = 2.2
      linearLocationDAO.update(LinearLocationAdjustment(id2, linkId2, Some(startM), Some(endM)), createdBy = "test")

      // Original linear location should be expired
      val expired = linearLocationDAO.fetchById(id2).getOrElse(fail())
      expired.validTo.nonEmpty should be(true)
      val unChanged = linearLocationDAO.fetchById(id1).getOrElse(fail())
      unChanged.validTo.isEmpty should be(true)

      // New linear location should have new startM and endM
      val locations = linearLocationDAO.fetchByLinkId(Set(linkId2))
      val updated = locations.head
      updated.startMValue should be(startM +- 0.001)
      updated.endMValue should be(endM +- 0.001)

      // Update only startM
      linearLocationDAO.update(LinearLocationAdjustment(updated.id, linkId2, Some(startM - 1), None), createdBy = "test")
      val updated2 = linearLocationDAO.fetchByLinkId(Set(updated.linkId)).head
      updated2.startMValue should be(startM - 1 +- 0.001)
      updated2.endMValue should be(endM +- 0.001)

      // Update linkId and endM
      linearLocationDAO.update(LinearLocationAdjustment(updated2.id, 999999999l, None, Some(9999.9)), createdBy = "test")
      val locations3 = linearLocationDAO.fetchByLinkId(Set(updated2.linkId))
      locations3.size should be(0)

    }
  }

  test("Update geometry - no flipping of the side code") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1, geometry = Seq(Point(0.0, 0.0), Point(0.0, 100.0)))))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      val before = linearLocationDAO.fetchById(id1).getOrElse(fail())
      before.sideCode should be(asset.SideCode.TowardsDigitizing)

      // Update geometry so that the direction of digitization changes
      val newStart = Point(0.0, 0.0)
      val newEnd = Point(10.0, 90.0)
      linearLocationDAO.updateGeometry(id1, Seq(newStart, newEnd), "test")

      // Original linear location should be expired
      val expired = linearLocationDAO.fetchById(id1).getOrElse(fail())
      expired.validTo.nonEmpty should be(true)
      val unChanged = linearLocationDAO.fetchById(id2).getOrElse(fail())
      unChanged.validTo.isEmpty should be(true)

      // New linear location should have new geometry
      val locations = linearLocationDAO.fetchByLinkId(Set(linkId1))
      val updated = locations.head
      updated.id should not be (id1)
      updated.geometry.head.x should be(newStart.x +- 0.001)
      updated.geometry.head.y should be(newStart.y +- 0.001)
      updated.geometry.last.x should be(newEnd.x +- 0.001)
      updated.geometry.last.y should be(newEnd.y +- 0.001)
      updated.sideCode should be(asset.SideCode.TowardsDigitizing)

    }
  }

  test("Update geometry - flip the side code") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1, geometry = Seq(Point(0.0, 0.0), Point(0.0, 100.0)))))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      val before = linearLocationDAO.fetchById(id1).getOrElse(fail())
      before.sideCode should be(asset.SideCode.TowardsDigitizing)

      // Update geometry so that the direction of digitization changes
      val newStart = Point(0.0, 100.0)
      val newEnd = Point(0.0, 0.0)
      linearLocationDAO.updateGeometry(id1, Seq(newStart, newEnd), "test")

      // Original linear location should be expired
      val expired = linearLocationDAO.fetchById(id1).getOrElse(fail())
      expired.validTo.nonEmpty should be(true)
      val unChanged = linearLocationDAO.fetchById(id2).getOrElse(fail())
      unChanged.validTo.isEmpty should be(true)

      // New linear location should have new geometry and side code
      val locations = linearLocationDAO.fetchByLinkId(Set(linkId1))
      val updated = locations.head
      updated.id should not be (id1)
      updated.geometry.head.x should be(newStart.x +- 0.001)
      updated.geometry.head.y should be(newStart.y +- 0.001)
      updated.geometry.last.x should be(newEnd.x +- 0.001)
      updated.geometry.last.y should be(newEnd.y +- 0.001)
      updated.sideCode should be(asset.SideCode.AgainstDigitizing)

    }
  }

  test("Update geometry - flip the side code, horizontal case") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1, geometry = Seq(Point(0.0, 0.0), Point(100.0, 0.0)))))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      val before = linearLocationDAO.fetchById(id1).getOrElse(fail())
      before.sideCode should be(asset.SideCode.TowardsDigitizing)

      // Update geometry so that the direction of digitization changes
      val newStart = Point(100.0, 0.0)
      val newEnd = Point(0.0, 0.0)
      linearLocationDAO.updateGeometry(id1, Seq(newStart, newEnd), "test")

      // Original linear location should be expired
      val expired = linearLocationDAO.fetchById(id1).getOrElse(fail())
      expired.validTo.nonEmpty should be(true)
      val unChanged = linearLocationDAO.fetchById(id2).getOrElse(fail())
      unChanged.validTo.isEmpty should be(true)

      // New linear location should have new geometry and side code
      val locations = linearLocationDAO.fetchByLinkId(Set(linkId1))
      val updated = locations.head
      updated.id should not be (id1)
      updated.geometry.head.x should be(newStart.x +- 0.001)
      updated.geometry.head.y should be(newStart.y +- 0.001)
      updated.geometry.last.x should be(newEnd.x +- 0.001)
      updated.geometry.last.y should be(newEnd.y +- 0.001)
      updated.sideCode should be(asset.SideCode.AgainstDigitizing)

    }
  }

  test("Query floating by link id - none") {
    runWithRollback {
      linearLocationDAO.create(Seq(testLinearLocation.copy(floating = FloatingReason.ManualFloating)))
      val noLocations = linearLocationDAO.queryFloatingByLinkId(Set())
      noLocations.size should be(0)
    }
  }

  test("Query floating by link id") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2, linkId3) = (111111111l, 222222222l, 3333333333l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2, floating = FloatingReason.ManualFloating)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = linkId3, floating = FloatingReason.NewAddressGiven)))

      val locations = linearLocationDAO.queryFloatingByLinkId(Set(linkId1, linkId2, linkId3))
      locations.filter(l => l.id == id1).size should be(0)
      locations.filter(l => l.id == id2).size should be(1)
      locations.filter(l => l.id == id3).size should be(1)

      val massQueryLocations = linearLocationDAO.queryFloatingByLinkIdMassQuery(Set(linkId1, linkId2, linkId3))
      massQueryLocations.filter(l => l.id == id1).size should be(0)
      massQueryLocations.filter(l => l.id == id2).size should be(1)
      massQueryLocations.filter(l => l.id == id3).size should be(1)
    }
  }

  test("Query by id - none") {
    runWithRollback {
      linearLocationDAO.create(Seq(testLinearLocation))
      val noLocations = linearLocationDAO.queryById(Set())
      noLocations.size should be(0)
    }
  }

  test("Query by id") {
    runWithRollback {
      val id1 = linearLocationDAO.getNextLinearLocationId
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1)))
      linearLocationDAO.expireByIds(Set(id1))

      val noLocations = linearLocationDAO.queryById(Set(id1))
      noLocations.size should be(0)
      val locations = linearLocationDAO.queryById(Set(id1), rejectInvalids = false)
      locations.size should be(1)
      locations.filter(l => l.id == id1).size should be(1)

      val noLocationsM = linearLocationDAO.queryByIdMassQuery(Set(id1))
      noLocationsM.size should be(0)
      val locationsM = linearLocationDAO.queryByIdMassQuery(Set(id1), rejectInvalids = false)
      locationsM.size should be(1)
      locationsM.filter(l => l.id == id1).size should be(1)
    }
  }

  test("Get roadway ids from linear location") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (roadwayNumber1, roadwayNumber2, roadwayNumber3) = (100000001l, 100000002l, 100000003l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, roadwayNumber = roadwayNumber1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, roadwayNumber = roadwayNumber2)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, roadwayNumber = roadwayNumber3)))

      val roadwayNumbers = linearLocationDAO.getRoadwayNumbersFromLinearLocation
      roadwayNumbers.size should be >= 3
    }
  }

  test("Get linear locations by filter: withLinkIdAndMeasure") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val linkId = 111111111l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId, startMValue = 0.0, endMValue = 100.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId, startMValue = 100.0, endMValue = 200.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 333333333l)))

      val locations1 = linearLocationDAO.getLinearLocationsByFilter(linearLocationDAO.withLinkIdAndMeasure(linkId, Some(0.0), Some(100.0)))
      locations1.size should be(1)
      locations1.filter(l => l.id == id1).size should be(1)
      val locations2 = linearLocationDAO.getLinearLocationsByFilter(linearLocationDAO.withLinkIdAndMeasure(linkId, Some(100.0), Some(200.0)))
      locations2.size should be(1)
      locations2.filter(l => l.id == id2).size should be(1)
    }
  }

  test("Get linear locations by filter: withRoadwayNumbers") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (roadwayNumber1, roadwayNumber2, roadwayNumber3) = (100000001l, 100000002l, 100000003l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, roadwayNumber = roadwayNumber1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, roadwayNumber = roadwayNumber2)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, roadwayNumber = roadwayNumber3)))

      val locations1 = linearLocationDAO.getLinearLocationsByFilter(linearLocationDAO.withRoadwayNumbers(roadwayNumber1, roadwayNumber1))
      locations1.size should be(1)
      locations1.filter(l => l.id == id1).size should be(1)
      val locations2 = linearLocationDAO.getLinearLocationsByFilter(linearLocationDAO.withRoadwayNumbers(roadwayNumber1, roadwayNumber3))
      locations2.size should be(3)
      locations2.filter(l => l.id == id1).size should be(1)
      locations2.filter(l => l.id == id2).size should be(1)
      locations2.filter(l => l.id == id3).size should be(1)
    }
  }

  test("Fetch by bounding box") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = 111111111l)))
      val linkId = 222222222l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId, geometry = Seq(Point(1000.0, 1000.0), Point(1100.0, 1000.0)))))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 333333333l)))
      val locations = linearLocationDAO.fetchByBoundingBox(BoundingRectangle(Point(900.0, 900.0), Point(1200.0, 1200.0)))
      locations.size should be(1)
      locations.filter(l => l.id == id2).size should be(1)
    }
  }

  test("Fetch roadway by bounding box") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val roadwayNumber = 11111l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, roadwayNumber = roadwayNumber, linkId = 111111111l)))
      val linkId = 222222222l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, roadwayNumber = roadwayNumber, linkId = linkId, geometry = Seq(Point(1000.0, 1000.0), Point(1100.0, 1000.0)))))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, roadwayNumber = 2222l, linkId = 333333333l)))
      val locations = linearLocationDAO.fetchRoadwayByBoundingBox(BoundingRectangle(Point(900.0, 900.0), Point(1200.0, 1200.0)), Seq())
      locations.size should be(2)
      locations.filter(l => l.id == id1).size should be(1)
      locations.filter(l => l.id == id2).size should be(1)
    }
  }

  test("Fetch roadway by bounding box - with road number filter") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val roadwayNumber1 = 11111l
      val roadwayNumber2 = 22222l
      val linkId1 = 111111111l
      val linkId2 = 222222222l
      val linkId3 = 333333333l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, roadwayNumber = roadwayNumber1, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, roadwayNumber = roadwayNumber1, linkId = linkId2, geometry = Seq(Point(1000.0, 1000.0), Point(1100.0, 1000.0)))))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, roadwayNumber = roadwayNumber2, linkId = linkId3)))

      // TODO RoadAddressDAO should be renamed to RoadwayDAO and it should take in Roadway object
      roadAddressDAO.create(Seq(RoadAddress(NewRoadway, id1, 100, 1, RoadType.PublicRoad, Combined, Discontinuity.Continuous, 0, 200,
        Some(DateTime.parse("2000-01-01")), None, Some("test"), linkId1, testLinearLocation.startMValue, testLinearLocation.endMValue,
        testLinearLocation.sideCode, testLinearLocation.adjustedTimestamp, (None, None), testLinearLocation.floating,
        testLinearLocation.geometry, testLinearLocation.linkGeomSource, 1, TerminationCode.NoTermination, roadwayNumber1)))

      // TODO RoadAddressDAO should be renamed to RoadwayDAO and it should take in Roadway object
      roadAddressDAO.create(Seq(RoadAddress(NewRoadway, id2, 101, 1, RoadType.PublicRoad, Combined, Discontinuity.Continuous, 0, 100,
        Some(DateTime.parse("2000-01-01")), None, Some("test"), linkId3, testLinearLocation.startMValue, testLinearLocation.endMValue,
        testLinearLocation.sideCode, testLinearLocation.adjustedTimestamp, (None, None), testLinearLocation.floating,
        testLinearLocation.geometry, testLinearLocation.linkGeomSource, 1, TerminationCode.NoTermination, roadwayNumber2)))

      val roadNumberFilter = Seq((100, 100))
      val locations = linearLocationDAO.fetchRoadwayByBoundingBox(BoundingRectangle(Point(900.0, 900.0), Point(1200.0, 1200.0)), roadNumberFilter)
      locations.size should be(2)
      locations.filter(l => l.id == id1).size should be(1)
      locations.filter(l => l.id == id2).size should be(1)
    }
  }

  test("Fetch by roadways") {
    runWithRollback {
      val roadwayNumber = 11111111111l
      val (linkId1, linkId2, linkId3) = (11111111111l, 22222222222l, 33333333333l)
      linearLocationDAO.create(Seq(testLinearLocation))
      linearLocationDAO.create(Seq(testLinearLocation.copy(linkId = linkId1, roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(linkId = linkId2, roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(linkId = linkId3, roadwayNumber = roadwayNumber)))

      val locations = linearLocationDAO.fetchByRoadways(Set(roadwayNumber))
      locations.size should be(3)
      locations.filter(l => l.linkId == linkId1).size should be(1)
      locations.filter(l => l.linkId == linkId2).size should be(1)
      locations.filter(l => l.linkId == linkId3).size should be(1)
    }
  }

}
