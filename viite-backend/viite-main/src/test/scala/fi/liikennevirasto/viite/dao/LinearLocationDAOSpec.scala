package fi.liikennevirasto.viite.dao

import java.sql.BatchUpdateException
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.process.RoadAddressFiller.LinearLocationAdjustment
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.model.{AdministrativeClass, CalibrationPointLocation, CalibrationPointType, Discontinuity, LinkGeomSource, RoadPart, SideCode, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}

class LinearLocationDAOSpec extends FunSuite with Matchers {

  val linearLocationDAO = new LinearLocationDAO
  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO

  private val testLinearLocation = LinearLocation(NewIdValue, 1, 1000L.toString, 0.0, 100.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference.None), Seq(Point(0.0, 0.0), Point(0.0, 100.0)), LinkGeomSource.NormalLinkInterface, 200L)

  test("Test create When creating linear location with new roadway id and no calibration points Then return new linear location") {
    runWithRollback {
      linearLocationDAO.create(Seq(testLinearLocation.copy(calibrationPoints = (CalibrationPointReference.None, CalibrationPointReference.None), roadwayNumber = NewIdValue)))
    }
  }

  /**
   * This test is for a case where loop-road is formed by 2 linear locations and share the geometry shares the same start and endpoints.
   * The case has to have only 2 crossing roads at either end, otherwise it will be handled in a different function.
   * In a case which has only 2 roads crossing in a junction, the function that handles all others junctions to find a coordinate of a junction, lacks sufficient information and needs to be calculated with side code and before-after values.
   * This is a rare edge-case.
   *
   * Example:
   * X = junction
   *
   *     -------X   <-- Junction that needs to be handles with side code and before-after values
   *    |       |
   *    X-------
   *    |
   *    |
   *
   */
  test("Test FetchCoordinatesForJunction when there are two linear locations with the same start and endpoints forming a loop-road, but are against digitizing direction Then return coordinate point of junction") {
    runWithRollback {
      val crossingRoads: Seq[RoadwaysForJunction] = Seq(
        RoadwaysForJunction(
          jId = 1,
          roadwayNumber = 100,
          roadPart = RoadPart(1, 1),
          track = 0,
          addrM = 100,
          beforeAfter = 1
        ),
        RoadwaysForJunction(
          jId = 1,
          roadwayNumber = 200,
          roadPart = RoadPart(2, 1),
          track = 0,
          addrM = 200,
          beforeAfter = 1
        )
      )
      val currentLinearLocations: Seq[LinearLocation] = Seq(
        LinearLocation(NewIdValue, 2, 1001L.toString, 0.0, 100.0, SideCode.AgainstDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference.None), Seq(Point(0.0, 100.0), Point(0.0, 200.0)), LinkGeomSource.NormalLinkInterface, 100L),
        LinearLocation(NewIdValue, 2, 1001L.toString, 0.0, 100.0, SideCode.AgainstDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference.None), Seq(Point(0.0, 100.0), Point(0.0, 200.0)), LinkGeomSource.NormalLinkInterface, 100L))
      val llIds: Seq[Long] = Seq(currentLinearLocations.head.id, currentLinearLocations.last.id)
      val result: Option[Point] = linearLocationDAO.fetchCoordinatesForJunction(llIds, crossingRoads, currentLinearLocations)

      // Expected point is either at the start or end of the geometry depending on the side code and before-after values, using the logic in the fetchCoordinatesForJunctions.
      val expectedPoint: Option[Point] = Some(Point(0.0, 100.0))

      result should be(expectedPoint)
    }

  }

    test("Test create When creating new linear location Then read it successfully from the database") {
    runWithRollback {
      val id = linearLocationDAO.getNextLinearLocationId
      val orderNumber = 1
      val linkId = 10L.toString
      val startMValue = 0.0
      val endMValue = 100.0
      val sideCode = SideCode.TowardsDigitizing
      val adjustedTimestamp = 10000000000L
      val calibrationPoints = (CalibrationPointReference(Some(0L), Some(CalibrationPointType.RoadAddressCP)), CalibrationPointReference.None)
      val geometry = Seq(Point(0.0, 0.0), Point(0.0, 100.0))
      val linkSource = LinkGeomSource.NormalLinkInterface
      val roadwayNumber = 200L
      val linearLocation = LinearLocation(id, orderNumber, linkId, startMValue, endMValue, sideCode, adjustedTimestamp, calibrationPoints, geometry, linkSource, roadwayNumber)
      linearLocationDAO.create(Seq(linearLocation))
      val roadwayPointId = roadwayPointDAO.create(linearLocation.roadwayNumber, linearLocation.startCalibrationPoint.addrM.get, "test")
      CalibrationPointDAO.create(roadwayPointId, linearLocation.linkId, startOrEnd = CalibrationPointLocation.StartOfLink, calType = CalibrationPointType.RoadAddressCP, createdBy = "test")
      val loc = linearLocationDAO.fetchById(id).getOrElse(fail())

      // Check that the values were saved correctly in database
      loc.id should be(id)
      loc.orderNumber should be(orderNumber)
      loc.linkId should be(linkId)
      loc.startMValue should be(startMValue +- 0.001)
      loc.endMValue should be(endMValue +- 0.001)
      loc.sideCode should be(sideCode)
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
      linearLocationDAO.create(Seq(testLinearLocation))
      intercept[BatchUpdateException] {
        linearLocationDAO.create(Seq(testLinearLocation))
      }
    }
  }

  test("Test expire When expiring linear location Then linear location validTo should not be empty") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 9999L.toString)))

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
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 9999L.toString)))

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
      val linkId = 1000L.toString
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 9999L.toString)))

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
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = 111111111L.toString)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = 222222222L.toString)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 333333333L.toString)))

      val locations = linearLocationDAO.fetchById(id1)
      locations.size should be(1)
      locations.count(l => l.id == id1) should be(1)

      val massQueryLocations = linearLocationDAO.fetchByIdMassQuery(Set(id1, -101L, -102L, -103L, id2))
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
      val (linkId1, linkId2) = (111111111L.toString, 222222222L.toString)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId1, startMValue = 200.0, endMValue = 300.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = linkId2)))

      val locations = linearLocationDAO.fetchByLinkId(Set(linkId1))
      locations.size should be(2)
      locations.count(l => l.id == id1) should be(1)
      locations.count(l => l.id == id2) should be(1)

      val massQueryLocations = linearLocationDAO.fetchByLinkIdMassQuery(Set(linkId1, "-101", "-102", "-103", linkId2))
      massQueryLocations.size should be(3)
      massQueryLocations.count(l => l.id == id1) should be(1)
      massQueryLocations.count(l => l.id == id2) should be(1)
      massQueryLocations.count(l => l.id == id3) should be(1)
    }
  }

  test("Test fetchById When fetching linear location by id including floating Then should return linear locations with corresponding id") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111L.toString, 222222222L.toString)
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

      val massQueryLocations = linearLocationDAO.fetchByLinkIdMassQuery(Set(linkId1, "-101", "-102", "-103", linkId2))
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
      val (linkId1, linkId2) = (111111111L.toString, 222222222L.toString)
      val roadwayNumber = 11111111L
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1, roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId1, startMValue = 200.0, endMValue = 300.0, roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = linkId2, startMValue = 300.0, endMValue = 400.0, roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id4, linkId = linkId2, roadwayNumber = 222222L)))

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
      val (linkId1, linkId2) = (111111111L.toString, 222222222L.toString)
      val roadwayNumber = 111111L
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1, roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId1, startMValue = 200.0, endMValue = 300.0, roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = linkId2, roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id4, linkId = linkId2, roadwayNumber = 222222L)))

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

      val massQueryLocations = linearLocationDAO.fetchRoadwayByLinkIdMassQuery(Set(linkId1, "-101", "-102", "-103", linkId2))
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
      val (linkId1, linkId2) = (111111111L.toString, 222222222L.toString)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      val startM = 1.1
      val endM = 2.2
      linearLocationDAO.update(LinearLocationAdjustment(id2, linkId2, Some(startM), Some(endM), Seq(Point(0.0, 0.0), Point(0.0, 1.1))), createdBy = "test")

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
      linearLocationDAO.update(LinearLocationAdjustment(updated.id, linkId2, Some(startM - 1), None, Seq(Point(0.0, 0.0), Point(0.0, 2.1))), createdBy = "test")
      val updated2 = linearLocationDAO.fetchByLinkId(Set(updated.linkId)).head
      updated2.startMValue should be(startM - 1 +- 0.001)
      updated2.endMValue should be(endM +- 0.001)
      updated2.geometry.head.x should be(0.0)
      updated2.geometry.head.y should be(0.0)
      updated2.geometry.last.x should be(0.0)
      updated2.geometry.last.y should be(2.1)

      // Update linkId and endM
      val linkId3 = 999999999L.toString
      val endM3 = 9999.9
      linearLocationDAO.update(LinearLocationAdjustment(updated2.id, linkId3, None, Some(endM3), Seq(Point(0.0, 0.0), Point(0.0, 9999.9))), createdBy = "test")
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
      val (linkId1, linkId2) = (111111111L.toString, 222222222L.toString)
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
      val linkId3 = 999999999L.toString
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
      val (linkId1, linkId2) = (111111111L.toString, 222222222L.toString)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1, geometry = Seq(Point(0.0, 0.0), Point(0.0, 100.0)))))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      val before = linearLocationDAO.fetchById(id1).getOrElse(fail())
      before.sideCode should be(SideCode.TowardsDigitizing)

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
      updated.sideCode should be(SideCode.TowardsDigitizing)

    }
  }

  test("Test updateGeometry When updating linear location geometry with flipping of the side code Then geometry should be updated") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111L.toString, 222222222L.toString)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1, geometry = Seq(Point(0.0, 0.0), Point(0.0, 100.0)))))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      val before = linearLocationDAO.fetchById(id1).getOrElse(fail())
      before.sideCode should be(SideCode.TowardsDigitizing)

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
      updated.sideCode should be(SideCode.AgainstDigitizing)

    }
  }

  test("Test updateGeometry When updating linear location geometry with flipping of the side code in horizontal case Then geometry should be updated") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val (linkId1, linkId2) = (111111111L.toString, 222222222L.toString)
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1, geometry = Seq(Point(0.0, 0.0), Point(100.0, 0.0)))))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2)))

      val before = linearLocationDAO.fetchById(id1).getOrElse(fail())
      before.sideCode should be(SideCode.TowardsDigitizing)

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
      updated.sideCode should be(SideCode.AgainstDigitizing)

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
      val (roadwayNumber1, roadwayNumber2, roadwayNumber3) = (100000001L, 100000002L, 100000003L)
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
      val linkId = 111111111L.toString
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId, startMValue = 0.0, endMValue = 100.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId, startMValue = 100.0, endMValue = 200.0)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 333333333L.toString)))

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
      val (roadwayNumber1, roadwayNumber2, roadwayNumber3) = (100000001L, 100000002L, 100000003L)
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
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = 111111111L.toString)))
      val linkId = 222222222L.toString
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId, geometry = Seq(Point(1000.0, 1000.0), Point(1100.0, 1000.0)))))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 333333333L.toString)))
      val locations = linearLocationDAO.fetchByBoundingBox(BoundingRectangle(Point(900.0, 900.0), Point(1200.0, 1200.0)))
      locations.size should be(1)
      locations.count(l => l.id == id2) should be(1)
    }
  }

  test("Test fetchByRoadAddress When fetching linear locations Then should return locations in that road address") {
    runWithRollback {
      val (id1, id2) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val roadwayNumber1 = 11111L
      val roadPart = RoadPart(99999 ,1)
      val linkId1 = 111111111L.toString
      val linkId2 = 222222222L.toString
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1, roadwayNumber = roadwayNumber1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2, geometry = Seq(Point(1000.0, 1000.0), Point(1100.0, 1000.0)), roadwayNumber = roadwayNumber1)))

      roadwayDAO.create(Seq(Roadway(NewIdValue, roadwayNumber1, roadPart, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 200, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("ROAD 1"), 1, TerminationCode.NoTermination)))

      val locations = linearLocationDAO.fetchByRoadAddress(roadPart, 1)
      locations.size should be(2)
    }
  }

  test("Test fetchRoadwayByBoundingBox When fetching roadways with bounding box query Then should return roadways in bounding box") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val roadwayNumber = 11111L
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = 111111111L.toString, roadwayNumber = roadwayNumber)))
      val linkId = 222222222L.toString
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId, geometry = Seq(Point(1000.0, 1000.0), Point(1100.0, 1000.0)), roadwayNumber = roadwayNumber)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = 333333333L.toString, roadwayNumber = 2222L)))
      val locations = linearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(900.0, 900.0), Point(1200.0, 1200.0)), Seq())
      locations.size should be(2)
      locations.count(l => l.id == id1) should be(1)
      locations.count(l => l.id == id2) should be(1)
    }
  }

  test("Test fetchRoadwayByBoundingBox When fetching roadways by bounding box with roadNumberFilter Then should return correct roadways in bounding box") {
    runWithRollback {
      val (id1, id2, id3) = (linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId, linearLocationDAO.getNextLinearLocationId)
      val roadwayNumber1 = 11111L
      val roadwayNumber2 = 22222L
      val linkId1 = 111111111L.toString
      val linkId2 = 222222222L.toString
      val linkId3 = 333333333L.toString
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id1, linkId = linkId1, roadwayNumber = roadwayNumber1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id2, linkId = linkId2, geometry = Seq(Point(1000.0, 1000.0), Point(1100.0, 1000.0)), roadwayNumber = roadwayNumber1)))
      linearLocationDAO.create(Seq(testLinearLocation.copy(id = id3, linkId = linkId3, roadwayNumber = roadwayNumber2)))

      roadwayDAO.create(Seq(Roadway(NewIdValue, roadwayNumber1, RoadPart(100, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 200, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("ROAD 1"), 1, TerminationCode.NoTermination)))
      roadwayDAO.create(Seq(Roadway(NewIdValue, roadwayNumber2, RoadPart(101, 1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 100, reversed = false, DateTime.parse("2000-01-01"), None, "test", Some("ROAD 2"), 1, TerminationCode.NoTermination)))

      val roadNumberFilter = Seq((100, 100))
      val locations = linearLocationDAO.fetchLinearLocationByBoundingBox(BoundingRectangle(Point(900.0, 900.0), Point(1200.0, 1200.0)), roadNumberFilter)
      locations.size should be(2)
      locations.count(l => l.id == id1) should be(1)
      locations.count(l => l.id == id2) should be(1)
    }
  }

  test("Test fetchByRoadways When fetching linear locations by roadways Then should return correct linear locations") {
    runWithRollback {
      val roadwayNumber = 11111111111L
      val (linkId1, linkId2, linkId3) = (11111111111L.toString, 22222222222L.toString, 33333333333L.toString)
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
