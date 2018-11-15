package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.asset.SideCode.TowardsDigitizing
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{Combined, LeftSide, RightSide}
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
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
  val linearLocationDAO = new LinearLocationDAO

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

  def dummyProjectLink(id: Long, projectId: Long, linkId : Long, roadwayId: Long = 0, roadwayNumber: Long = roadwayNumber1, roadNumber: Long = roadNumber1, roadPartNumber: Long =roadPartNumber1,  startAddrMValue: Long, endAddrMValue: Long,
                       startMValue: Double, endMValue: Double, endDate: Option[DateTime] = None, calibrationPoints: (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = (None, None),
                       floating: FloatingReason = NoFloating, geometry: Seq[Point] = Seq(), status: LinkStatus, roadType: RoadType, reversed: Boolean, linearLocationId: Long): ProjectLink =
    ProjectLink(id, roadNumber, roadPartNumber, Track.Combined,
      Discontinuity.Continuous, startAddrMValue, endAddrMValue, startAddrMValue, endAddrMValue, Some(DateTime.parse("1901-01-01")),
      endDate, Some("testUser"), linkId, startMValue, endMValue,
      TowardsDigitizing, calibrationPoints, floating, geometry, projectId, status, roadType,
      LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geometry), roadwayId, linearLocationId, 0, reversed,
      connectedLinkId = None, 631152000, roadwayNumber, roadAddressLength = Some(endAddrMValue - startAddrMValue))

  private def dummyRoadAddressProject(id: Long, status: ProjectState, reservedParts: Seq[ProjectReservedPart] = List.empty[ProjectReservedPart], ely: Option[Long] = None, coordinates: Option[ProjectCoordinates] = None): Project ={
    Project(id, status, "testProject", "testUser", DateTime.parse("1901-01-01"), "testUser", DateTime.parse("1901-01-01"), DateTime.now(), "additional info here", reservedParts, Some("current status info"), ely, coordinates)
  }

  //TODO test coverage missing for ProjectLinkDAO methods:
  /**
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
    * countLinksByStatus VIITE-1543
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
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber1, roadPartNumber1, rap.createdBy)
      val projectLink = dummyProjectLink(projectLinkId, projectId, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      projectLinkDAO.create(Seq(projectLink))
      val (originalStartAddrM, originalEndStartAddrM) = (projectLink.originalStartAddrMValue, projectLink.originalEndAddrMValue)
      projectLinkDAO.updateAddrMValues(projectLink.copy(startAddrMValue = 200, endAddrMValue = 300))
      val returnedProjectLinks = projectLinkDAO.getProjectLinks(projectId)
      returnedProjectLinks.size should be (1)
      returnedProjectLinks.head.originalStartAddrMValue should be (originalStartAddrM)
      returnedProjectLinks.head.originalEndAddrMValue should be (originalEndStartAddrM)
    }
  }

  test("Test create When having no reversed links Then should return no reversed project links") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId = projectId + 1
      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, Seq(), None, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber1, roadPartNumber1, rap.createdBy)
      val projectLinks = Seq(dummyProjectLink(projectLinkId, projectId, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
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
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, roadNumber1, roadPartNumber2, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      projectDAO.fetchById(id).nonEmpty should be(true)
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
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, roadNumber1, roadPartNumber1, 100, 200, 100.0, 200.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
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
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, roadNumber1, roadPartNumber2, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks.map(x => x.copy(reversed = true)))
      val returnedProjectLinks = projectLinkDAO.getProjectLinks(id)
      returnedProjectLinks.count(x => x.reversed) should be(projectLinks.size)
    }
  }

  test("Test getProjectLinksByIds and removeProjectLinksById When supplying a empty list Then both methods should NOT throw an exception") {
    projectLinkDAO.getProjectLinksByIds(Seq())
    projectLinkDAO.removeProjectLinksById(Set())
  }

  test("Test updateProjectLinks When giving one new projectLink with new values Then it should update project link") {
    runWithRollback {
      val projectLinks = projectLinkDAO.getProjectLinks(7081807)
      val header = projectLinks.head
      val newRoadType = RoadType.FerryRoad
      val newDiscontinuty = Discontinuity.ChangingELYCode
      val newStartAddrMValue = header.startAddrMValue + 1
      val newEndAddrMValue = header.endAddrMValue + 1
      val newTrack = Track.Unknown
      val newStatus = LinkStatus.Unknown

      val modifiedProjectLinks = Seq(header.copy(roadType = newRoadType, discontinuity = newDiscontinuty, startAddrMValue = newStartAddrMValue, endAddrMValue = newEndAddrMValue, track = newTrack, status = newStatus))
      projectLinkDAO.updateProjectLinks(modifiedProjectLinks, "test", Seq())

      val updatedProjectLink = projectLinkDAO.getProjectLinks(7081807).filter(link => link.id == header.id).head

      updatedProjectLink.roadType should be (newRoadType)
      updatedProjectLink.discontinuity should be (newDiscontinuty)
      updatedProjectLink.startAddrMValue should be (newStartAddrMValue)
      updatedProjectLink.endAddrMValue should be (newEndAddrMValue)
      updatedProjectLink.track should be (newTrack)
      updatedProjectLink.status should be (newStatus)

    }
  }

    test("Test updateProjectLinksGeometry When giving one link with updated geometry Then it should be updated") {
      runWithRollback {
        val projectLinks = projectLinkDAO.getProjectLinks(7081807)
        val header = projectLinks.head
        val newGeometry = Seq(Point(0.0, 10.0), Point(0.0, 20.0))

        val modifiedProjectLinks = Seq(header.copy(geometry = newGeometry))
        projectLinkDAO.updateProjectLinksGeometry(modifiedProjectLinks, "test")

        val updatedProjectLink = projectLinkDAO.getProjectLinks(7081807).filter(link => link.id == header.id).head
        updatedProjectLink.geometry should be (newGeometry)
      }
    }

  test("Test updateProjectLinkNumbering When giving one link with updated road number and part Then it should be updated") {
    runWithRollback {
      val projectLinks = projectLinkDAO.getProjectLinks(7081807)
      val header = projectLinks.head
      val newRoadNumber = 1
      val newRoadPartNumber = 5
      projectReservedPartDAO.reserveRoadPart(7081807, newRoadNumber, newRoadPartNumber, "test")
      projectLinkDAO.updateProjectLinkNumbering(Seq(header.id), header.status, newRoadNumber, newRoadPartNumber, "test", header.discontinuity.value)

      val updatedProjectLink = projectLinkDAO.getProjectLinks(7081807).filter(link => link.id == header.id).head
      updatedProjectLink.roadNumber should be (newRoadNumber)
      updatedProjectLink.roadPartNumber should be (newRoadPartNumber)
    }
  }

  test("Test updateProjectLinksStatus When giving one link with updated road number and part Then it should be updated") {
    runWithRollback {
      val projectLinks = projectLinkDAO.getProjectLinks(7081807)
      val header = projectLinks.head
      val newRoadNumber = 1
      val newRoadPartNumber = 5
      projectLinkDAO.updateProjectLinksStatus(Set(header.id), LinkStatus.Terminated, "test")

      val updatedProjectLink = projectLinkDAO.getProjectLinks(7081807).filter(link => link.id == header.id).head
      updatedProjectLink.status should be (LinkStatus.Terminated)
    }
  }

  test("Test updateAddrMValues When giving one link with updated road number and part Then it should be updated") {
    runWithRollback {
      val projectLinks = projectLinkDAO.getProjectLinks(7081807)
      val header = projectLinks.head
      val newStartAddrMValues = 0
      val newEndAddrMValues = 100
      projectLinkDAO.updateAddrMValues(header.copy(startAddrMValue = newStartAddrMValues, endAddrMValue = newEndAddrMValues))

      val updatedProjectLink = projectLinkDAO.getProjectLinks(7081807).filter(link => link.id == header.id).head
      updatedProjectLink.startAddrMValue should be (newStartAddrMValues)
      updatedProjectLink.endAddrMValue should be (newEndAddrMValues)
    }
  }

  test("Test updateProjectLinkValues When giving one link with updated values and new geometry Then it should be updated") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId

      val roadway = Roadway(raId, roadwayNumber1, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
        0L, 10L, reversed = false, DateTime.parse("1901-01-01"), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
        (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.ComplimentaryLinkInterface,
        roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, List.empty, None, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber1, roadPartNumber1, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectId+1, projectId, linkId1, roadway.id, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(),LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, linearLocationId = linearLocation.id)
      )
      projectLinkDAO.create(projectLinks)

      val roadNumber = 1
      val roadPartNumber = 1
      val roadType = RoadType.PublicRoad
      val track = Track.Combined
      val discontinuity = Discontinuity.Continuous
      val startAddrMValue, startMValue = 0
      val endAddrMValue, endMValue = 100
      val side = SideCode.TowardsDigitizing
      val geometry = Seq(Point(0.0, 0.0), Point(0.0, 100.0))

      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber, roadPartNumber, rap.createdBy)

      val roadAddress = RoadAddress(raId, linearLocationId, roadNumber, roadPartNumber,  roadType, track,
        discontinuity, startAddrMValue, endAddrMValue, Some(DateTime.now), None, None, 12345, startMValue, endMValue, side, 1542205983000L, (None, None),
        NoFloating, geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 1, Some(DateTime.now), None, None)

      projectLinkDAO.updateProjectLinkValues(projectId, roadAddress, updateGeom = true)

      val updatedProjectLinks = projectLinkDAO.getProjectLinks(projectId)
      val updatedProjectLink = updatedProjectLinks.filter(link => link.id == projectLinks.head.id).head
      updatedProjectLink.roadNumber should be (roadNumber)
      updatedProjectLink.roadPartNumber should be (roadPartNumber)
      updatedProjectLink.track should be (track)
      updatedProjectLink.discontinuity should be (discontinuity)
      updatedProjectLink.roadType should be (roadType)
      updatedProjectLink.startAddrMValue should be (startAddrMValue)
      updatedProjectLink.endAddrMValue should be (endAddrMValue)
      updatedProjectLink.startMValue should be (startMValue)
      updatedProjectLink.endMValue should be (endMValue)
      updatedProjectLink.sideCode should be (side)
      updatedProjectLink.geometry should be (geometry)
    }
  }

  test("Test getElyFromProjectLinks When trying to get ely by project Then it should be returned with success") {
    runWithRollback {
      val projectId = 7081807
      val projectLinks = projectLinkDAO.getProjectLinks(projectId)
      val ely = projectLinks.head.ely
      val elyByProject = projectLinkDAO.getElyFromProjectLinks(projectId)
       ely should be (elyByProject.getOrElse(0))
    }
  }

}