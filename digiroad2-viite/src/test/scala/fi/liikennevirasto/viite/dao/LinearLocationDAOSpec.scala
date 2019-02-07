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
  val roadwayDAO = new RoadwayDAO

  val testLinearLocation = LinearLocation(NewLinearLocation, 1, 1000l, 0.0, 100.0, SideCode.TowardsDigitizing, 10000000000l,
    (Some(0l), None), Seq(Point(0.0, 0.0), Point(0.0, 100.0)), LinkGeomSource.NormalLinkInterface, 200l)

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  test("Test create When creating linear location with new roadway id and no calibration points Then return new linear location") {
    runWithRollback {
      linearLocationDAO.create(Seq(testLinearLocation.copy(roadwayNumber = NewRoadwayNumber, calibrationPoints = (None, None))))
    }
  }

  test("Test lockLinearLocationWriting When creating new linear locations in same transaction Then successfully create linear locations") {
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

  test("Test create When creating new linear location Then read it succesfully from the database") {
    runWithRollback {
      val id = linearLocationDAO.getNextLinearLocationId
      val orderNumber = 1
      val linkId = 1000l
      val startMValue = 0.0
      val endMValue = 100.0
      val sideCode = SideCode.TowardsDigitizing
      val adjustedTimestamp = 10000000000l
      val calibrationPoints = (Some(0l), None)
      val geometry = Seq(Point(0.0, 0.0), Point(0.0, 100.0))
      val linkSource = LinkGeomSource.NormalLinkInterface
      val roadwayNumber = 200l
      val linearLocation = LinearLocation(id, orderNumber, linkId, startMValue, endMValue, sideCode, adjustedTimestamp,
        calibrationPoints, geometry, linkSource, roadwayNumber)
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
      loc.geometry.head.x should be(geometry.head.x +- 0.001)
      loc.geometry.head.y should be(geometry.head.y +- 0.001)
      loc.geometry.head.z should be(geometry.head.z +- 0.001)
      loc.geometry(1).x should be(geometry(1).x +- 0.001)
      loc.geometry(1).y should be(geometry(1).y +- 0.001)
      loc.geometry(1).z should be(geometry(1).z +- 0.001)
      loc.linkGeomSource should be(linkSource)
      loc.roadwayNumber should be(roadwayNumber)
      loc.validFrom.nonEmpty should be(true)
      loc.validTo.isEmpty should be(true)

    }
  }

  test("Test create When creating linear locations Then duplicate linear locations should not be created") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1)))
      intercept[BatchUpdateException] {
        linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2)))
      }
    }
  }

  test("Test expire When expiring linear location Then linear location validTo should not be empty") {
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

  test("Test expire When expiring linear location by id Then linear location validTo should not be empty") {
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

  test("Test expireByLinkId When expiring linear location with no linkid given Then should return none") {
    runWithRollback {
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = linearLocationDAO.getNextLinearLocationId)))
      linearLocationDAO.expireByLinkId(Set()) should be(0)
    }
  }

  test("Test expireByLinkId When expiring linear location by linkid Then linear location validTo should not be empty") {
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

  test("Test fetchById When fetching linear location by id Then should return linear location with corresponding id") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = 111111111l)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 222222222l)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 333333333l)))

      val locations = linearLocationDAO.fetchById(id1)
      locations.size should be(1)
      locations.count(l => l.id == id1) should be(1)

      val massQueryLocations = linearLocationDAO.fetchByIdMassQuery(Set(id1, -101l, -102l, -103l, id2))
      massQueryLocations.size should be(2)
      massQueryLocations.count(l => l.id == id1) should be(1)
      massQueryLocations.count(l => l.id == id2) should be(1)
    }
  }

  test("Test fetch by link id When fetching linear location with no id given Then should return none") {
    runWithRollback {
      linearLocationDAO.create(Seq(testLinearLocation))
      val noLocations = linearLocationDAO.fetchByLinkId(Set())
      noLocations.size should be(0)
    }
  }

  test("Test fetchById When fetching linear location by id Then should return linear location with corresponding id v2") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId1, startMValue = 200.0, endMValue = 300.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = linkId2)))

      val locations = linearLocationDAO.fetchByLinkId(Set(linkId1))
      locations.size should be(2)
      locations.count(l => l.id == id1) should be(1)
      locations.count(l => l.id == id2) should be(1)

      val massQueryLocations = linearLocationDAO.fetchByLinkIdMassQuery(Set(linkId1, -101l, -102l, -103l, linkId2))
      massQueryLocations.size should be(3)
      massQueryLocations.count(l => l.id == id1) should be(1)
      massQueryLocations.count(l => l.id == id2) should be(1)
      massQueryLocations.count(l => l.id == id3) should be(1)
    }
  }

  test("Test fetchById When fetching linear location by id including floating Then should return linear locations with corresponding id") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId1, startMValue = 200.0, endMValue = 300.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = linkId2)))

      val locations0 = linearLocationDAO.fetchByLinkId(Set(linkId1, linkId2), filterIds = Set(id1, id2, id3))
      locations0.size should be(0)

      val locations1 = linearLocationDAO.fetchByLinkId(Set(linkId1, linkId2), filterIds = Set(id2, id3))
      locations1.size should be(1)
      locations1.count(l => l.id == id1) should be(1)
      locations1.count(l => l.id == id2) should be(0)
      locations1.count(l => l.id == id3) should be(0)

      val locations2 = linearLocationDAO.fetchByLinkId(Set(linkId1, linkId2), filterIds = Set(id2))
      locations2.size should be(2)
      locations2.count(l => l.id == id1) should be(1)
      locations2.count(l => l.id == id2) should be(0)
      locations2.count(l => l.id == id3) should be(1)

      val massQueryLocations = linearLocationDAO.fetchByLinkIdMassQuery(Set(linkId1, -101l, -102l, -103l, linkId2))
      massQueryLocations.size should be(3)
      massQueryLocations.count(l => l.id == id1) should be(1)
      massQueryLocations.count(l => l.id == id2) should be(1)
      massQueryLocations.count(l => l.id == id3) should be(1)
    }
  }

  test("Test fetchRoadwayByLinkId When fetching with empty Then should return none") {
    runWithRollback {
      linearLocationDAO.create(Seq(testLinearLocation))
      val noLocations = linearLocationDAO.fetchRoadwayByLinkId(Set())
      noLocations.size should be(0)
    }
  }

  test("Test fetchRoadwayByLinkId When fetching roadways by linkid Then should return roadways with corresponding linkids") {
    runWithRollback {
      val (id1, id2, id3, id4) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      val roadwayNumber = 11111111l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, roadwayNumber = roadwayNumber, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, roadwayNumber = roadwayNumber, linkId = linkId1, startMValue = 200.0, endMValue = 300.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, roadwayNumber = roadwayNumber, linkId = linkId2, startMValue = 300.0, endMValue = 400.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id4, roadwayNumber = 222222l, linkId = linkId2)))

      val locations = linearLocationDAO.fetchRoadwayByLinkId(Set(linkId1))
      locations.count(l => l.id == id1) should be(1)
      locations.count(l => l.id == id2) should be(1)
      locations.count(l => l.id == id3) should be(1)
      locations.size should be(3)

      val massQueryLocations = linearLocationDAO.fetchRoadwayByLinkIdMassQuery(Set(linkId1))
      massQueryLocations.count(l => l.id == id1) should be(1)
      massQueryLocations.count(l => l.id == id2) should be(1)
      massQueryLocations.count(l => l.id == id3) should be(1)
      massQueryLocations.size should be(3)
    }
  }

  test("Test fetchRoadwayByLinkId When fetching roadways by linkid including floatings Then should return roadways with corresponding linkids") {
    runWithRollback {
      val (id1, id2, id3, id4) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      val roadwayNumber = 111111l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, roadwayNumber = roadwayNumber, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, roadwayNumber = roadwayNumber, linkId = linkId1, startMValue = 200.0, endMValue = 300.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, roadwayNumber = roadwayNumber, linkId = linkId2)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id4, roadwayNumber = 222222l, linkId = linkId2)))

      val locations1 = linearLocationDAO.fetchRoadwayByLinkId(Set(linkId1))
      locations1.count(l => l.id == id1) should be(1)
      locations1.count(l => l.id == id2) should be(1)
      locations1.count(l => l.id == id3) should be(1)
      locations1.size should be(3)

      val locations2 = linearLocationDAO.fetchRoadwayByLinkId(Set(linkId2))
      locations2.count(l => l.id == id1) should be(1)
      locations2.count(l => l.id == id2) should be(1)
      locations2.count(l => l.id == id3) should be(1)
      locations2.count(l => l.id == id4) should be(1)
      locations2.size should be(4)

      val massQueryLocations = linearLocationDAO.fetchRoadwayByLinkIdMassQuery(Set(linkId1, -101l, -102l, -103l, linkId2))
      massQueryLocations.count(l => l.id == id1) should be(1)
      massQueryLocations.count(l => l.id == id2) should be(1)
      massQueryLocations.count(l => l.id == id3) should be(1)
      massQueryLocations.count(l => l.id == id4) should be(1)
      massQueryLocations.size should be(4)
    }
  }

  test("Test update When updating linear location values Then new values should be updated") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      val startM = 1.1
      val endM = 2.2
      linearLocationDAO.update(LinearLocationAdjustment(id2, linkId2, Some(startM), Some(endM),
        Seq(Point(0.0, 0.0), Point(0.0, 1.1))), createdBy = "test")

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
      updated.geometry.head.x should be(0.0)
      updated.geometry.head.y should be(0.0)
      updated.geometry.last.x should be(0.0)
      updated.geometry.last.y should be(1.1)

      // Update only startM
      linearLocationDAO.update(LinearLocationAdjustment(updated.id, linkId2, Some(startM - 1), None,
        Seq(Point(0.0, 0.0), Point(0.0, 2.1))), createdBy = "test")
      val updated2 = linearLocationDAO.fetchByLinkId(Set(updated.linkId)).head
      updated2.startMValue should be(startM - 1 +- 0.001)
      updated2.endMValue should be(endM +- 0.001)
      updated2.geometry.head.x should be(0.0)
      updated2.geometry.head.y should be(0.0)
      updated2.geometry.last.x should be(0.0)
      updated2.geometry.last.y should be(2.1)

      // Update linkId and endM
      val linkId3 = 999999999l
      val endM3 = 9999.9
      linearLocationDAO.update(LinearLocationAdjustment(updated2.id, linkId3, None, Some(endM3),
        Seq(Point(0.0, 0.0), Point(0.0, 9999.9))), createdBy = "test")
      val expired2 = linearLocationDAO.fetchByLinkId(Set(updated2.linkId))
      expired2.size should be(0)
      val updated3 = linearLocationDAO.fetchByLinkId(Set(linkId3)).head
      updated3.linkId should be(linkId3)
      updated3.endMValue should be(endM3)
      updated3.geometry.head.x should be(0.0)
      updated3.geometry.head.y should be(0.0)
      updated3.geometry.last.x should be(0.0)
      updated3.geometry.last.y should be(9999.9)

    }
  }

  test("Test update When updating linear location without geometry Then new values should be updated") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      val startM = 1.1
      val endM = 2.2
      linearLocationDAO.update(LinearLocationAdjustment(id2, linkId2, Some(startM), Some(endM), Seq()), createdBy = "test")

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
      linearLocationDAO.update(LinearLocationAdjustment(updated.id, linkId2, Some(startM - 1), None, Seq()), createdBy = "test")
      val updated2 = linearLocationDAO.fetchByLinkId(Set(updated.linkId)).head
      updated2.startMValue should be(startM - 1 +- 0.001)
      updated2.endMValue should be(endM +- 0.001)

      // Update linkId and endM
      val linkId3 = 999999999l
      val endM3 = 9999.9
      linearLocationDAO.update(LinearLocationAdjustment(updated2.id, linkId3, None, Some(endM3), Seq()), createdBy = "test")
      val expired2 = linearLocationDAO.fetchByLinkId(Set(updated2.linkId))
      expired2.size should be(0)
      val updated3 = linearLocationDAO.fetchByLinkId(Set(linkId3)).head
      updated3.linkId should be(linkId3)
      updated3.endMValue should be(endM3)

    }
  }

  test("Test updateGeometry When updating linear location geometry with no flipping of the side code Then geometry should be updated") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111l, 222222222l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1, geometry = Seq(Point(0.0, 0.0), Point(0.0, 100.0)))))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      val before = linearLocationDAO.fetchById(id1).getOrElse(fail())
      before.sideCode should be(asset.SideCode.TowardsDigitizing)

      // Update geometry so that the direction of digitization changes
      val newStart: Point = Point(0.0, 0.0)
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
      updated.id should not be id1
      updated.geometry.head.x should be(newStart.x +- 0.001)
      updated.geometry.head.y should be(newStart.y +- 0.001)
      updated.geometry.last.x should be(newEnd.x +- 0.001)
      updated.geometry.last.y should be(newEnd.y +- 0.001)
      updated.sideCode should be(asset.SideCode.TowardsDigitizing)

    }
  }

  test("Test updateGeometry When updating linear location geometry with flipping of the side code Then geometry should be updated") {
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
      updated.id should not be id1
      updated.geometry.head.x should be(newStart.x +- 0.001)
      updated.geometry.head.y should be(newStart.y +- 0.001)
      updated.geometry.last.x should be(newEnd.x +- 0.001)
      updated.geometry.last.y should be(newEnd.y +- 0.001)
      updated.sideCode should be(asset.SideCode.AgainstDigitizing)

    }
  }

  test("Test updateGeometry When updating linear location geometry with flipping of the side code in horizontal case Then geometry should be updated") {
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
      updated.id should not be id1
      updated.geometry.head.x should be(newStart.x +- 0.001)
      updated.geometry.head.y should be(newStart.y +- 0.001)
      updated.geometry.last.x should be(newEnd.x +- 0.001)
      updated.geometry.last.y should be(newEnd.y +- 0.001)
      updated.sideCode should be(asset.SideCode.AgainstDigitizing)

    }
  }

  test("Test queryById When querying floating with empty Then should return none") {
    runWithRollback {
      linearLocationDAO.create(Seq(testLinearLocation))
      val noLocations = linearLocationDAO.queryById(Set())
      noLocations.size should be(0)
    }
  }

  test("Test queryById When querying floating by id Then should return floating with corresponding id") {
    runWithRollback {
      val id1 = linearLocationDAO.getNextLinearLocationId
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1)))
      linearLocationDAO.expireByIds(Set(id1))

      val noLocations = linearLocationDAO.queryById(Set(id1))
      noLocations.size should be(0)
      val locations = linearLocationDAO.queryById(Set(id1), rejectInvalids = false)
      locations.size should be(1)
      locations.count(l => l.id == id1) should be(1)

      val noLocationsM = linearLocationDAO.queryByIdMassQuery(Set(id1))
      noLocationsM.size should be(0)
      val locationsM = linearLocationDAO.queryByIdMassQuery(Set(id1), rejectInvalids = false)
      locationsM.size should be(1)
      locationsM.count(l => l.id == id1) should be(1)
    }
  }

  test("Test getRoadwayNumbersFromLinearLocation When fetching roadwaynumbers from linear locations Then should return roadwaynumbers") {
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

  test("Test getLinearLocationsByFilter When fetching linear locations with filter withLinkIdAndMeasure Then should return correct linear locations") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val linkId = 111111111l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId, startMValue = 0.0, endMValue = 100.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId, startMValue = 100.0, endMValue = 200.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 333333333l)))

      val locations1 = linearLocationDAO.getLinearLocationsByFilter(linearLocationDAO.withLinkIdAndMeasure(linkId, Some(0.0), Some(100.0)))
      locations1.size should be(1)
      locations1.count(l => l.id == id1) should be(1)
      val locations2 = linearLocationDAO.getLinearLocationsByFilter(linearLocationDAO.withLinkIdAndMeasure(linkId, Some(100.0), Some(200.0)))
      locations2.size should be(1)
      locations2.count(l => l.id == id2) should be(1)
    }
  }

  test("Test getLinearLocationsByFilter When fetching linear locations by withRoadwayNumbers filter Then should return correct linear locations") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (roadwayNumber1, roadwayNumber2, roadwayNumber3) = (100000001l, 100000002l, 100000003l)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, roadwayNumber = roadwayNumber1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, roadwayNumber = roadwayNumber2)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, roadwayNumber = roadwayNumber3)))

      val locations1 = linearLocationDAO.getLinearLocationsByFilter(linearLocationDAO.withRoadwayNumbers(roadwayNumber1, roadwayNumber1))
      locations1.size should be(1)
      locations1.count(l => l.id == id1) should be(1)
      val locations2 = linearLocationDAO.getLinearLocationsByFilter(linearLocationDAO.withRoadwayNumbers(roadwayNumber1, roadwayNumber3))
      locations2.size should be(3)
      locations2.count(l => l.id == id1) should be(1)
      locations2.count(l => l.id == id2) should be(1)
      locations2.count(l => l.id == id3) should be(1)
    }
  }

  test("Test fetchByBoundingBox When fetching linear locations with bounding box query Then should return locations in bounding box") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = 111111111l)))
      val linkId = 222222222l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId, geometry = Seq(Point(1000.0, 1000.0), Point(1100.0, 1000.0)))))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 333333333l)))
      val locations = linearLocationDAO.fetchByBoundingBox(BoundingRectangle(Point(900.0, 900.0), Point(1200.0, 1200.0)))
      locations.size should be(1)
      locations.count(l => l.id == id2) should be(1)
    }
  }

  test("Test fetchRoadwayByBoundingBox When fetching roadways with bounding box query Then should return roadways in bounding box") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val roadwayNumber = 11111l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, roadwayNumber = roadwayNumber, linkId = 111111111l)))
      val linkId = 222222222l
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, roadwayNumber = roadwayNumber, linkId = linkId, geometry = Seq(Point(1000.0, 1000.0), Point(1100.0, 1000.0)))))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, roadwayNumber = 2222l, linkId = 333333333l)))
      val locations = linearLocationDAO.fetchRoadwayByBoundingBox(BoundingRectangle(Point(900.0, 900.0), Point(1200.0, 1200.0)), Seq())
      locations.size should be(2)
      locations.count(l => l.id == id1) should be(1)
      locations.count(l => l.id == id2) should be(1)
    }
  }

  test("Test fetchRoadwayByBoundingBox When fetching roadways by bounding box with roadNumberFilter Then should return correct roadways in bounding box") {
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

      roadwayDAO.create(Seq(Roadway(NewRoadway, roadwayNumber1, 100, 1, RoadType.PublicRoad, Combined,
        Discontinuity.Continuous, 0, 200, reversed = false, DateTime.parse("2000-01-01"), None, "test",
        Some("ROAD 1"), 1, TerminationCode.NoTermination)))

      roadwayDAO.create(Seq(Roadway(NewRoadway, roadwayNumber2, 101, 1, RoadType.PublicRoad, Combined,
        Discontinuity.Continuous, 0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "test",
        Some("ROAD 2"), 1, TerminationCode.NoTermination)))

      val roadNumberFilter = Seq((100, 100))
      val locations = linearLocationDAO.fetchRoadwayByBoundingBox(BoundingRectangle(Point(900.0, 900.0), Point(1200.0, 1200.0)), roadNumberFilter)
      locations.size should be(2)
      locations.count(l => l.id == id1) should be(1)
      locations.count(l => l.id == id2) should be(1)
    }
  }

  test("Test fetchByRoadways When fetching linear locations by roadways Then should return correct linear locations") {
    runWithRollback {
      val roadwayNumber = 11111111111l
      val (linkId1, linkId2, linkId3) = (11111111111l, 22222222222l, 33333333333l)
      linearLocationDAO.create(Seq(testLinearLocation))
      linearLocationDAO.create(Seq(testLinearLocation.copy(linkId = linkId1, roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(linkId = linkId2, roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(linkId = linkId3, roadwayNumber = roadwayNumber)))

      val locations = linearLocationDAO.fetchByRoadways(Set(roadwayNumber))
      locations.size should be(3)
      locations.count(l => l.linkId == linkId1) should be(1)
      locations.count(l => l.linkId == linkId2) should be(1)
      locations.count(l => l.linkId == linkId3) should be(1)
    }
  }

}
