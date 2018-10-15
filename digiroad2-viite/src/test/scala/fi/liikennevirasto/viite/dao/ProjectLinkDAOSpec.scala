package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.LinkGeomSource
import fi.liikennevirasto.digiroad2.asset.SideCode.TowardsDigitizing
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.{NewRoadway, RoadType}
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

/**
  * Class to test DB trigger that does not allow reserving already reserved links to project
  */
class ProjectLinkDAOSpec extends FunSuite with Matchers {
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockRoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockRoadNetworkDAO = MockitoSugar.mock[RoadNetworkDAO]

  def runWithRollback(f: => Unit): Unit = {
    // Prevent deadlocks in DB because we create and delete links in tests and don't handle the project ids properly
    // TODO: create projects with unique ids so we don't get into transaction deadlocks in tests
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val roadwayDAO = new RoadwayDAO

  private val roadNumber1 = 5
  private val roadNumber2 = 6

  private val roadPartNumber1 = 1
  private val roadPartNumber2 = 2

  private val roadwayNumber1 = 1000000000l
  private val roadwayNumber2 = 2000000000l
  private val roadwayNumber3 = 3000000000l

  private val linkId1 = 1000l
  private val linkId2 = 2000l
  private val linkId3 = 3000l

  private val linearLocationId = 1

  private def dummyRoadways: Seq[Roadway] = {
    Seq(Roadway(NewRoadway, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
      0, 100, false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination),
      Roadway(NewRoadway, roadwayNumber2, roadNumber1, roadPartNumber2, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        0, 100, false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination)
    )
  }

  def dummyProjectLink(id: Long, projectId: Long, linkId : Long, roadwayId: Long = 0, roadwayNumber: Long = roadwayNumber1, roadNumber: Long = roadNumber1, roadPartNumber: Long =roadPartNumber1, startAddrMValue: Long, endAddrMValue: Long,
                       startMValue: Double, endMValue: Double, endDate: Option[DateTime] = None, calibrationPoints: (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = (None, None),
                       floating: FloatingReason = NoFloating, geometry: Seq[Point] = Seq(), status: LinkStatus, roadType: RoadType, reversed: Boolean): ProjectLink =
    ProjectLink(id, roadNumber, roadPartNumber, Track.Combined,
      Discontinuity.Continuous, startAddrMValue, endAddrMValue, startAddrMValue, endAddrMValue, Some(DateTime.parse("1901-01-01")),
      endDate, Some("testUser"), linkId, startMValue, endMValue,
      TowardsDigitizing, calibrationPoints, floating, geometry, projectId, status, roadType,
      LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geometry), roadwayId, linearLocationId, 0, reversed,
      connectedLinkId = None, 631152000, roadwayNumber, roadAddressLength = Some(endAddrMValue - startAddrMValue))

  private def dummyRoadAddressProject(id: Long, status: ProjectState, reservedParts: Seq[ProjectReservedPart] = List.empty[ProjectReservedPart], ely: Option[Long] = None, coordinates: Option[ProjectCoordinates] = None): RoadAddressProject ={
    RoadAddressProject(id, status, "testProject", "testUser", DateTime.parse("1901-01-01"), "testUser", DateTime.parse("1901-01-01"), DateTime.now(), "additional info here", reservedParts, Some("current status info"), ely, coordinates)
  }

  //TODO test coverage missing for ProjectLinkDAO methods:
  /**
    * updateProjectLinksToDB VIITE-1540
    * updateProjectLinksGeometry VIITE-1540
    * updateProjectLinkNumbering VIITE-1540
    * updateProjectLinksToTerminated VIITE1540
    * updateAddrMValues VIITE-1540
    * updateProjectLinks VIITE-1540
    * updateProjectLinkValues VIITE-1540
    * getElyFromProjectLinks VIITE-1543
    * getProjectLinksHistory VIITE-1543
    * getProjectLinksByConnectedLinkId VIITE-1543
    * getProjectLinksByLinkIdAndProjectId VIITE-1543
    * getProjectLinksByProjectAndLinkId VIITE-1543
    * getProjectLinksByProjectRoadPart VIITE-1543
    * fetchByProjectRoadPart VIITE-1543
    * fetchByProjectRoadParts VIITE-1543
    * isRoadPartNotHandled VIITE-1543
    * reverseRoadPartDirection VIITE-1543
    * fetchProjectLinkIds VIITE-1543
    * countLinksUnchangedUnhandled VIITE-1543
    * getContinuityCodes VIITE-1543
    * fetchFirstLink VIITE-1543
    * deleteProjectLinks VIITE-1543
    * removeProjectLinksByLinkIds VIITE-1543
    * moveProjectLinksToHistory VIITE-1543
    * removeProjectLinksByProject VIITE-1543
    * removeProjectLinksByLinkId VIITE-1543
    * fetchSplitLinks VIITE-1543
    * removeProjectLinksByProjectAndRoadNumber VIITE-1543
    */

  test("Test updateAddrMValues When having changed addr values for some reason (e.g. recalculate) Then should still return original addr values") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId = projectId + 1
      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, Seq(), None, None)
      projectDAO.createRoadAddressProject(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber1, roadPartNumber1, rap.createdBy)
      val projectLink = dummyProjectLink(projectLinkId, projectId, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false)
     val (originalStartAddrM, originalEndStartAddrM) = (projectLink.originalStartAddrMValue, projectLink.originalEndAddrMValue)
      projectLinkDAO.updateAddrMValues(projectLink.copy(startAddrMValue = 200, endAddrMValue = 300))
      val returnedProjectLinks = projectLinkDAO.getProjectLinks(projectId)
      returnedProjectLinks.size should be (1)
      returnedProjectLinks.head.originalStartAddrMValue should be (projectLink.originalStartAddrMValue)
      returnedProjectLinks.head.originalEndAddrMValue should be (projectLink.originalEndAddrMValue)
    }
  }

  test("Test create When having no reversed links Then should return no reversed project links") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId = projectId + 1
      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, Seq(), None, None)
      projectDAO.createRoadAddressProject(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber1, roadPartNumber1, rap.createdBy)
      val projectLinks = Seq(dummyProjectLink(projectLinkId, projectId, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false)
      )
      projectLinkDAO.create(projectLinks)
      val returnedProjectLinks = projectLinkDAO.getProjectLinks(projectId)
      returnedProjectLinks.count(x => x.reversed) should be(0)
    }
  }

  test("Test removeProjectLinksById When list of project links are removed Then they should not be found anymore") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId1 = id + 1
      val projectLinkId2 = id + 2
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, Seq(), None, None)
      projectDAO.createRoadAddressProject(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, roadNumber1, roadPartNumber2, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false)
      )
      projectLinkDAO.create(projectLinks)
      projectDAO.getRoadAddressProjectById(id).nonEmpty should be(true)
      val returnedProjectLinks = projectLinkDAO.getProjectLinks(id)
      projectLinkDAO.removeProjectLinksById(returnedProjectLinks.map(_.id).toSet) should be(returnedProjectLinks.size)
      projectLinkDAO.getProjectLinks(id).nonEmpty should be(false)
    }
  }

  test("Test updateProjectLinkRoadTypeDiscontinuity When road type or discontinuity got updated Then update should be made with success") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId1 = id + 1
      val projectLinkId2 = id + 2
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, Seq(), None, None)
      projectDAO.createRoadAddressProject(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, roadNumber1, roadPartNumber1, 100, 200, 100.0, 200.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false)
      )
      projectLinkDAO.create(projectLinks)
      val returnedProjectLinks = projectLinkDAO.getProjectLinks(id)
      val biggestProjectLink = returnedProjectLinks.maxBy(_.endAddrMValue)
      projectLinkDAO.updateProjectLinkRoadTypeDiscontinuity(returnedProjectLinks.map(x => x.id).filterNot(_ == biggestProjectLink.id).toSet, LinkStatus.UnChanged, "test", 2, None)
      projectLinkDAO.updateProjectLinkRoadTypeDiscontinuity(Set(biggestProjectLink.id), LinkStatus.UnChanged, "test", 2, Some(2))
      val savedProjectLinks = projectLinkDAO.getProjectLinks(id)
      savedProjectLinks.count(_.roadType.value == 2) should be(savedProjectLinks.size)
      savedProjectLinks.count(_.discontinuity.value == 2) should be(1)
      savedProjectLinks.filter(_.discontinuity.value == 2).head.id should be(biggestProjectLink.id)
    }
  }

  test("Test create When project links is saved as reversed Then project links should also be reversed") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId1 = id + 1
      val projectLinkId2 = id + 2
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, List.empty, None, None)
      projectDAO.createRoadAddressProject(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, roadNumber1, roadPartNumber2, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false)
      )
      projectLinkDAO.create(projectLinks.map(x => x.copy(reversed = true)))
      val returnedProjectLinks = projectLinkDAO.getProjectLinks(id)
      returnedProjectLinks.count(x => x.reversed) should be(projectLinks.size)
    }
  }

  test("Empty list will not throw an exception") {
    projectLinkDAO.getProjectLinksByIds(Seq())
    projectLinkDAO.removeProjectLinksById(Set())
  }

  //  test("Create reversed project link") {
  //    runWithRollback {
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //      ProjectDAO.reserveRoadPart(id, 5, 203, rap.createdBy)
  //      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
  //      ProjectDAO.create(addresses.map(x => x.copy(reversed = true)))
  //      val projectLinks = ProjectDAO.getProjectLinks(id)
  //      projectLinks.count(x => x.reversed) should be(projectLinks.size)
  //    }
  //  }

//TODO Will be implemented at VIITE-1540
//  test("update project link") {
//    runWithRollback {
//      val projectLinks = ProjectDAO.getProjectLinks(7081807)
//      ProjectDAO.updateProjectLinks(projectLinks.map(x => x.id).toSet, LinkStatus.UnChanged, "test")
//      val savedProjectLinks = ProjectDAO.getProjectLinks(7081807)
//      ProjectDAO.updateProjectLinksToDB(Seq(savedProjectLinks.sortBy(_.startAddrMValue).last.copy(status = LinkStatus.Terminated)), "tester")
//      val terminatedLink = projectLinks.sortBy(_.startAddrMValue).last
//      val updatedProjectLinks = ProjectDAO.getProjectLinks(7081807).filter(link => link.id == terminatedLink.id)
//      val updatedLink = updatedProjectLinks.head
//      updatedLink.status should be(LinkStatus.Terminated)
//      updatedLink.discontinuity should be(Discontinuity.Continuous)
//      updatedLink.startAddrMValue should be(savedProjectLinks.sortBy(_.startAddrMValue).last.startAddrMValue)
//      updatedLink.endAddrMValue should be(savedProjectLinks.sortBy(_.startAddrMValue).last.endAddrMValue)
//      updatedLink.track should be(savedProjectLinks.sortBy(_.startAddrMValue).last.track)
//      updatedLink.roadType should be(savedProjectLinks.sortBy(_.startAddrMValue).last.roadType)
//    }
//  }

//  test("Empty list will not throw an exception") {
//    projectLinkDAO.getProjectLinksByIds(Seq())
//    projectLinkDAO.removeProjectLinksById(Set())
//  }

  //TODO will be implement at VIITE-1540
//  test("Change road address direction") {
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
//      ProjectDAO.createRoadAddressProject(rap)
//      ProjectDAO.reserveRoadPart(id, 5, 203, rap.createdBy)
//      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
//      ProjectDAO.create(addresses)
//      val (psidecode, linkid) = sql"select SIDE, ID from PROJECT_LINK where PROJECT_LINK.PROJECT_ID = $id".as[(Long, Long)].first
//      psidecode should be(2)
//      ProjectDAO.reverseRoadPartDirection(id, 5, 203)
//      val nsidecode = sql"select SIDE from PROJECT_LINK WHERE id=$linkid".as[Int].first
//      nsidecode should be(3)
//      ProjectDAO.reverseRoadPartDirection(id, 5, 203)
//      val bsidecode = sql"select SIDE from PROJECT_LINK WHERE id=$linkid".as[Int].first
//      bsidecode should be(2)
//    }
//  }

  //TODO will be implement at VIITE-1540
//  test("update ProjectLinkgeom") {
//    //Creation of Test road
//    val address = ReservedRoadPart(5: Long, 203: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List(address), None)
//      ProjectDAO.createRoadAddressProject(rap)
//      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
//      ProjectDAO.reserveRoadPart(id, 5, 203, "TestUser")
//      ProjectDAO.create(addresses)
//      ProjectDAO.getRoadAddressProjectById(id).nonEmpty should be(true)
//      val projectlinksWithDummyGeom = ProjectDAO.getProjectLinks(id).map(x=>x.copy(geometry = Seq(Point(1,1,1),Point(2,2,2),Point(1,2))))
//      ProjectDAO.updateProjectLinksGeometry(projectlinksWithDummyGeom,"test")
//      val updatedProjectlinks=ProjectDAO.getProjectLinks(id)
//      updatedProjectlinks.head.geometry.size should be (3)
//      updatedProjectlinks.head.geometry should be (Stream(Point(1.0,1.0,1.0), Point(2.0,2.0,2.0), Point(1.0,2.0,0.0)))
//    }
//  }

  //  test("check the removal of project links") {
  //    val address = ReservedRoadPart(5: Long, 5: Long, 203: Long, Some(6L), Some(Discontinuity.apply("jatkuva")), Some(8L), newLength = None, newDiscontinuity = None, newEly = None)
  //    runWithRollback {
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List(address), None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //      ProjectDAO.reserveRoadPart(id, address.roadNumber, address.roadPartNumber, rap.createdBy)
  //      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
  //      ProjectDAO.create(addresses)
  //      ProjectDAO.getRoadAddressProjectById(id).nonEmpty should be(true)
  //      val projectLinks = ProjectDAO.getProjectLinks(id)
  //      ProjectDAO.removeProjectLinksById(projectLinks.map(_.id).toSet) should be(projectLinks.size)
  //      ProjectDAO.getProjectLinks(id).nonEmpty should be(false)
  //    }
  //  }

  //TODO will be implement at VIITE-1540
  //  // Tests now also for VIITE-855 - accidentally removing all links if set is empty
  //  test("Update project link status and remove zero links") {
  //    runWithRollback {
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //      ProjectDAO.reserveRoadPart(id, 5, 203, rap.createdBy)
  //      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
  //      ProjectDAO.create(addresses)
  //      val projectLinks = ProjectDAO.getProjectLinks(id)
  //      ProjectDAO.updateProjectLinks(projectLinks.map(x => x.id).toSet, LinkStatus.Terminated, "test")
  //      val updatedProjectLinks = ProjectDAO.getProjectLinks(id)
  //      updatedProjectLinks.head.status should be(LinkStatus.Terminated)
  //      ProjectDAO.removeProjectLinksByLinkId(id, Set())
  //      ProjectDAO.getProjectLinks(id).size should be(updatedProjectLinks.size)
  //    }
  //  }

  //TODO will be implement at VIITE-1540
  //  test("Update project link to reversed") {
  //    runWithRollback {
  //      val id = Sequences.nextViitePrimaryKeySeqValue
  //      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
  //      ProjectDAO.createRoadAddressProject(rap)
  //      ProjectDAO.reserveRoadPart(id, 5, 203, rap.createdBy)
  //      val addresses = RoadAddressDAO.fetchByRoadPart(5, 203).map(toProjectLink(rap))
  //      ProjectDAO.create(addresses)
  //      val projectLinks = ProjectDAO.getProjectLinks(id)
  //      projectLinks.count(x => !x.reversed) should be(projectLinks.size)
  //      val maxM = projectLinks.map(_.endAddrMValue).max
  //      val reversedprojectLinks = projectLinks.map(x => x.copy(reversed = true, status=Transfer,
  //        startAddrMValue = maxM-x.endAddrMValue, endAddrMValue = maxM-x.startAddrMValue))
  //      ProjectDAO.updateProjectLinksToDB(reversedprojectLinks, "testuset")
  //      val updatedProjectLinks = ProjectDAO.getProjectLinks(id)
  //      updatedProjectLinks.count(x => x.reversed) should be(updatedProjectLinks.size)
  //    }
  //  }

}