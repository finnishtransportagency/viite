package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils, Point, Vector3d}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
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

  private val linkId1 = 1000l
  private val linkId2 = 2000l
  private val linkId3 = 3000l

  private def dummyRoadways: Seq[Roadway] = {
    Seq(Roadway(NewRoadway, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
      0, 100, false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination),
      Roadway(NewRoadway, roadwayNumber2, roadNumber1, roadPartNumber2, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        0, 100, false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination)
    )
  }

  private def splittedDummyRoadways: Seq[Roadway] = {
    Seq(Roadway(NewRoadway, roadwayNumber1, roadNumber1, roadPartNumber1, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
      0, 100, false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), 1, TerminationCode.NoTermination)
    )
  }

  def dummyProjectLink(id: Long, projectId: Long, linkId: Long, roadwayId: Long = 0, roadwayNumber: Long = roadwayNumber1, roadNumber: Long = roadNumber1, roadPartNumber: Long = roadPartNumber1, startAddrMValue: Long, endAddrMValue: Long,
                       startMValue: Double, endMValue: Double, endDate: Option[DateTime] = None, calibrationPoints: (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = (None, None),
                       floating: FloatingReason = NoFloating, geometry: Seq[Point] = Seq(), status: LinkStatus, roadType: RoadType, reversed: Boolean, linearLocationId: Long, connectedLinkId: Option[Long] = None, track: Track = Track.Combined): ProjectLink =
    ProjectLink(id, roadNumber, roadPartNumber, track,
      Discontinuity.Continuous, startAddrMValue, endAddrMValue, startAddrMValue, endAddrMValue, Some(DateTime.parse("1901-01-01")),
      endDate, Some("testUser"), linkId, startMValue, endMValue,
      TowardsDigitizing, calibrationPoints, floating, geometry, projectId, status, roadType,
      LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geometry), roadwayId, linearLocationId, 0, reversed,
      connectedLinkId = connectedLinkId, 631152000, roadwayNumber, roadAddressLength = Some(endAddrMValue - startAddrMValue))

  private def dummyRoadAddressProject(id: Long, status: ProjectState, reservedParts: Seq[ProjectReservedPart] = List.empty[ProjectReservedPart], ely: Option[Long] = None, coordinates: Option[ProjectCoordinates] = None): Project = {
    Project(id, status, "testProject", "testUser", DateTime.parse("1901-01-01"), "testUser", DateTime.parse("1901-01-01"), DateTime.now(), "additional info here", reservedParts, Some("current status info"), ely, coordinates)
  }

  //TODO test coverage missing for ProjectLinkDAO methods:
  /**
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
      val projectLink = dummyProjectLink(projectLinkId, projectId, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      projectLinkDAO.create(Seq(projectLink))
      val (originalStartAddrM, originalEndStartAddrM) = (projectLink.originalStartAddrMValue, projectLink.originalEndAddrMValue)
      projectLinkDAO.updateAddrMValues(projectLink.copy(startAddrMValue = 200, endAddrMValue = 300))
      val returnedProjectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      returnedProjectLinks.size should be(1)
      returnedProjectLinks.head.originalStartAddrMValue should be(originalStartAddrM)
      returnedProjectLinks.head.originalEndAddrMValue should be(originalEndStartAddrM)
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
      val projectLinks = Seq(dummyProjectLink(projectLinkId, projectId, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      val returnedProjectLinks = projectLinkDAO.fetchProjectLinks(projectId)
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
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, roadNumber1, roadPartNumber2, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      projectDAO.fetchById(id).nonEmpty should be(true)
      val returnedProjectLinks = projectLinkDAO.fetchProjectLinks(id)
      projectLinkDAO.removeProjectLinksById(returnedProjectLinks.map(_.id).toSet) should be(returnedProjectLinks.size)
      projectLinkDAO.fetchProjectLinks(id).nonEmpty should be(false)
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
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, roadNumber1, roadPartNumber1, 100, 200, 100.0, 200.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      val returnedProjectLinks = projectLinkDAO.fetchProjectLinks(id)
      val biggestProjectLink = returnedProjectLinks.maxBy(_.endAddrMValue)
      projectLinkDAO.updateProjectLinkRoadTypeDiscontinuity(returnedProjectLinks.map(x => x.id).filterNot(_ == biggestProjectLink.id).toSet, LinkStatus.UnChanged, "test", 2, None)
      projectLinkDAO.updateProjectLinkRoadTypeDiscontinuity(Set(biggestProjectLink.id), LinkStatus.UnChanged, "test", 2, Some(2))
      val savedProjectLinks = projectLinkDAO.fetchProjectLinks(id)
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
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, roadNumber1, roadPartNumber2, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks.map(x => x.copy(reversed = true)))
      val returnedProjectLinks = projectLinkDAO.fetchProjectLinks(id)
      returnedProjectLinks.count(x => x.reversed) should be(projectLinks.size)
    }
  }

  test("Test getProjectLinksByIds and removeProjectLinksById When supplying a empty list Then both methods should NOT throw an exception") {
    projectLinkDAO.fetchProjectLinksByIds(Seq())
    projectLinkDAO.removeProjectLinksById(Set())
  }

  test("Test updateProjectLinks When giving one new projectLink with new values Then it should update project link") {
    runWithRollback {
      val projectLinks = projectLinkDAO.fetchProjectLinks(7081807)
      val header = projectLinks.head
      val newRoadType = RoadType.FerryRoad
      val newDiscontinuty = Discontinuity.ChangingELYCode
      val newStartAddrMValue = header.startAddrMValue + 1
      val newEndAddrMValue = header.endAddrMValue + 1
      val newTrack = Track.Unknown
      val newStatus = LinkStatus.Unknown

      val modifiedProjectLinks = Seq(header.copy(roadType = newRoadType, discontinuity = newDiscontinuty, startAddrMValue = newStartAddrMValue, endAddrMValue = newEndAddrMValue, track = newTrack, status = newStatus))
      projectLinkDAO.updateProjectLinks(modifiedProjectLinks, "test", Seq())

      val updatedProjectLink = projectLinkDAO.fetchProjectLinks(7081807).filter(link => link.id == header.id).head

      updatedProjectLink.roadType should be(newRoadType)
      updatedProjectLink.discontinuity should be(newDiscontinuty)
      updatedProjectLink.startAddrMValue should be(newStartAddrMValue)
      updatedProjectLink.endAddrMValue should be(newEndAddrMValue)
      updatedProjectLink.track should be(newTrack)
      updatedProjectLink.status should be(newStatus)

    }
  }

  test("Test updateProjectLinksGeometry When giving one link with updated geometry Then it should be updated") {
    runWithRollback {
      val projectLinks = projectLinkDAO.fetchProjectLinks(7081807)
      val header = projectLinks.head
      val newGeometry = Seq(Point(0.0, 10.0), Point(0.0, 20.0))

      val modifiedProjectLinks = Seq(header.copy(geometry = newGeometry))
      projectLinkDAO.updateProjectLinksGeometry(modifiedProjectLinks, "test")

      val updatedProjectLink = projectLinkDAO.fetchProjectLinks(7081807).filter(link => link.id == header.id).head
      updatedProjectLink.geometry should be(newGeometry)
    }
  }

  test("Test updateProjectLinkNumbering When giving one link with updated road number and part Then it should be updated") {
    runWithRollback {
      val projectLinks = projectLinkDAO.fetchProjectLinks(7081807)
      val header = projectLinks.head
      val newRoadNumber = 1
      val newRoadPartNumber = 5
      projectReservedPartDAO.reserveRoadPart(7081807, newRoadNumber, newRoadPartNumber, "test")
      projectLinkDAO.updateProjectLinkNumbering(header.projectId, header.roadNumber, header.roadPartNumber, header.status, newRoadNumber, newRoadPartNumber, "test", header.discontinuity.value, header.track)

      val updatedProjectLink = projectLinkDAO.fetchProjectLinks(7081807).filter(link => link.id == header.id).head
      updatedProjectLink.roadNumber should be(newRoadNumber)
      updatedProjectLink.roadPartNumber should be(newRoadPartNumber)
    }
  }

  test("Test updateProjectLinkNumbering When changing the discontinuity value of one track only Then the discontinuity value for that track should be updated the other should remain") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId1 = projectId + 1
      val projectLinkId2 = projectId + 2
      val newRoadNumber = 99999L
      val newPartNumber = 1L
      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, Seq(), None, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadNumber1, roadPartNumber1, rap.createdBy)
      val projectLinkRightSide = dummyProjectLink(projectLinkId1, projectId, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0, track = Track.RightSide)
      val projectLinkLeftSide = dummyProjectLink(projectLinkId2, projectId, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0, track = Track.LeftSide)
      projectLinkDAO.create(Seq(projectLinkLeftSide, projectLinkRightSide))
      val links = projectLinkDAO.fetchProjectLinks(projectId)
      links.size should be (2)
      projectReservedPartDAO.reserveRoadPart(projectId, newRoadNumber, newPartNumber, "test")
      projectLinkDAO.updateProjectLinkNumbering(projectId, roadNumber1, roadPartNumber1, LinkStatus.Numbering, newRoadNumber, newPartNumber, "test", Discontinuity.MinorDiscontinuity.value, Track.LeftSide)
      val linksAfterUpdate = projectLinkDAO.fetchProjectLinks(projectId)
      linksAfterUpdate.size should be (2)
      linksAfterUpdate.groupBy(p => (p.roadNumber, p.roadPartNumber)).keys.head should be ((newRoadNumber, newPartNumber))
      linksAfterUpdate.find(_.track== Track.LeftSide).get.discontinuity should be (Discontinuity.MinorDiscontinuity)
      linksAfterUpdate.find(_.track== Track.RightSide).get.discontinuity should be (Discontinuity.Continuous)
    }
  }

  test("Test updateProjectLinksStatus When giving one link with updated road number and part Then it should be updated") {
    runWithRollback {
      val projectLinks = projectLinkDAO.fetchProjectLinks(7081807)
      val header = projectLinks.head
      val newRoadNumber = 1
      val newRoadPartNumber = 5
      projectLinkDAO.updateProjectLinksStatus(Set(header.id), LinkStatus.Terminated, "test")

      val updatedProjectLink = projectLinkDAO.fetchProjectLinks(7081807).filter(link => link.id == header.id).head
      updatedProjectLink.status should be(LinkStatus.Terminated)
    }
  }

  test("Test updateAddrMValues When giving one link with updated road number and part Then it should be updated") {
    runWithRollback {
      val projectLinks = projectLinkDAO.fetchProjectLinks(7081807)
      val header = projectLinks.head
      val newStartAddrMValues = 0
      val newEndAddrMValues = 100
      projectLinkDAO.updateAddrMValues(header.copy(startAddrMValue = newStartAddrMValues, endAddrMValue = newEndAddrMValues))

      val updatedProjectLink = projectLinkDAO.fetchProjectLinks(7081807).filter(link => link.id == header.id).head
      updatedProjectLink.startAddrMValue should be(newStartAddrMValues)
      updatedProjectLink.endAddrMValue should be(newEndAddrMValues)
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
        dummyProjectLink(projectId + 1, projectId, linkId1, roadway.id, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, linearLocationId = linearLocation.id)
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

      val roadAddress = RoadAddress(raId, linearLocationId, roadNumber, roadPartNumber, roadType, track,
        discontinuity, startAddrMValue, endAddrMValue, Some(DateTime.now), None, None, 12345, startMValue, endMValue, side, 1542205983000L, (None, None),
        NoFloating, geometry, LinkGeomSource.NormalLinkInterface, 8, NoTermination, 1, Some(DateTime.now), None, None)

      projectLinkDAO.updateProjectLinkValues(projectId, roadAddress, updateGeom = true)

      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      val updatedProjectLink = updatedProjectLinks.filter(link => link.id == projectLinks.head.id).head
      updatedProjectLink.roadNumber should be(roadNumber)
      updatedProjectLink.roadPartNumber should be(roadPartNumber)
      updatedProjectLink.track should be(track)
      updatedProjectLink.discontinuity should be(discontinuity)
      updatedProjectLink.roadType should be(roadType)
      updatedProjectLink.startAddrMValue should be(startAddrMValue)
      updatedProjectLink.endAddrMValue should be(endAddrMValue)
      updatedProjectLink.startMValue should be(startMValue)
      updatedProjectLink.endMValue should be(endMValue)
      updatedProjectLink.sideCode should be(side)
      updatedProjectLink.geometry should be(geometry)
    }
  }

  test("Test getElyFromProjectLinks When trying to get ely by project Then it should be returned with success") {
    runWithRollback {
      val projectId = 7081807
      val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      val ely = projectLinks.head.ely
      val elyByProject = projectLinkDAO.fetchElyFromProjectLinks(projectId)
      ely should be(elyByProject.getOrElse(0))
    }
  }
  test("Test moveProjectLinksToHistory and getProjectLinksHistory When trying to get ely by project Then it should be returned with success") {
    runWithRollback {
      val projectId = 7081807
      projectLinkDAO.moveProjectLinksToHistory(projectId)
      val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      projectLinks.isEmpty should be(true)
      val projectLinksHistory = projectLinkDAO.fetchProjectLinksHistory(projectId)
      projectLinksHistory.isEmpty should be(false)
    }
  }

  test("Test getProjectLinksByConnectedLinkId When creating one entry with some connectedLinkId Then it should be returned ") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId
      val connectedId = 123456l

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
        dummyProjectLink(projectId + 1, projectId, linkId1, roadway.id, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, linearLocationId = linearLocation.id, connectedLinkId = Some(connectedId))
      )
      projectLinkDAO.create(projectLinks)

      val connectedLinks = projectLinkDAO.fetchProjectLinksByConnectedLinkId(Seq(connectedId))
      connectedLinks.size should be(projectLinks.size)
    }
  }

  test("Test getProjectLinksByLinkId When creating one projectLink Then it should be returned ") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId
      val connectedId = 123456l

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
        dummyProjectLink(projectId + 1, projectId, linkId1, roadway.id, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, linearLocationId = linearLocation.id, connectedLinkId = Some(connectedId))
      )
      projectLinkDAO.create(projectLinks)

      val result = projectLinkDAO.getProjectLinksByLinkId(linkId1)
      result.size should be(projectLinks.size)
      result.head.id should be(projectLinks.head.id)
    }
  }

  test("Test getProjectLinksByProjectAndLinkId When creating one projectLink Then it should be returned ") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId
      val connectedId = 123456l

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
        dummyProjectLink(projectId + 1, projectId, linkId1, roadway.id, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, linearLocationId = linearLocation.id, connectedLinkId = Some(connectedId))
      )
      projectLinkDAO.create(projectLinks)

      val result1 = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(projectLinks.head.id), Set(), projectId)
      val result2 = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(), Set(linkId1), projectId)
      result1.size should be(result2.size)
      result1.head.linkId should be(linkId1)
    }
  }

  test("Test fetchByProjectRoadPart When creating one projectLink Then it should be returned ") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId
      val connectedId = 123456l

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
        dummyProjectLink(projectId + 1, projectId, linkId1, roadway.id, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, linearLocationId = linearLocation.id, connectedLinkId = Some(connectedId))
      )
      projectLinkDAO.create(projectLinks)

      val result = projectLinkDAO.fetchByProjectRoadPart(roadNumber1, roadPartNumber1, projectId)
      result.head.linkId should be(linkId1)
    }
  }

  test("Test fetchByProjectRoadParts When creating two project links Then they should be returned") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId1 = id + 1
      val projectLinkId2 = id + 2
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId1, roadwayIds.last, roadwayNumber1, roadNumber1, roadPartNumber2, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      projectReservedPartDAO.roadPartReservedByProject(roadNumber1, roadPartNumber1) should be(Some("TestProject"))
      projectReservedPartDAO.roadPartReservedByProject(roadNumber1, roadPartNumber2) should be(Some("TestProject"))
      val reserved203 = projectLinkDAO.fetchByProjectRoadParts(Set((roadNumber1, roadPartNumber1)), id)
      reserved203.nonEmpty should be(true)
      val reserved205 = projectLinkDAO.fetchByProjectRoadParts(Set((roadNumber1, roadPartNumber2)), id)
      reserved205.nonEmpty should be(true)
      reserved203 shouldNot be(reserved205)
      reserved203.toSet.intersect(reserved205.toSet) should have size 0
      val reserved = projectLinkDAO.fetchByProjectRoadParts(Set((roadNumber1, roadPartNumber1), (roadNumber1, roadPartNumber2)), id)
      reserved.map(_.id).toSet should be(reserved203.map(_.id).toSet ++ reserved205.map(_.id).toSet)
      reserved should have size projectLinks.size
    }
  }

  test("Test reverseRoadPartDirection When creating one projectLink and reverse it Then it should be reversed ") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId
      val connectedId = 123456l

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
        dummyProjectLink(projectId + 1, projectId, linkId1, roadway.id, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, linearLocationId = linearLocation.id, connectedLinkId = Some(connectedId))
      )
      projectLinkDAO.create(projectLinks)

      val side = projectLinks.head.sideCode
      projectLinkDAO.reverseRoadPartDirection(projectId, roadNumber1, roadPartNumber1)
      val result1 = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(projectLinks.head.id), Set(), projectId)
      side should not be result1.head.sideCode

      result1.head.linkId should be(linkId1)
    }
  }
  //  projectLinkDAO.countLinksByStatus(projectId, roadNumber, roadPartNumber, Set(UnChanged.value, NotHandled.value))

  test("Test countLinksByStatus When creating one projectLink with some status Then when searching by that LinkStatus it should be returned ") {
    runWithRollback {
      val projectId = Sequences.nextViitePrimaryKeySeqValue
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId
      val connectedId = 123456l

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
        dummyProjectLink(projectId + 1, projectId, linkId1, roadway.id, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, linearLocationId = linearLocation.id, connectedLinkId = Some(connectedId))
      )
      projectLinkDAO.create(projectLinks)

      val side = projectLinks.head.sideCode
      val result1 = projectLinkDAO.countLinksByStatus(projectId, roadNumber1, roadPartNumber1, Set(LinkStatus.Transfer.value))
      val result2 = projectLinkDAO.countLinksByStatus(projectId, roadNumber1, roadPartNumber1, Set(LinkStatus.UnChanged.value, LinkStatus.Terminated.value, LinkStatus.Unknown.value, LinkStatus.NotHandled.value, LinkStatus.Numbering.value, LinkStatus.New.value))
      result1 should be(projectLinks.size)
      result2 should be(0)
    }
  }

  test("Test fetchFirstLink When creating two projectLinks Then the fetchFirstLink should return only the first one") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId1 = id + 1
      val projectLinkId2 = id + 2
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId1, roadwayIds.last, roadwayNumber1, roadNumber1, roadPartNumber2, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      val first = projectLinkDAO.fetchFirstLink(id, roadNumber1,roadPartNumber1)
      first.get.id should be (projectLinkId1)
    }
  }

  test("Test removeProjectLinksById When creating two projectLinks and delete one of them Then only one should be returned") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId1 = id + 1
      val projectLinkId2 = id + 2
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId1, roadwayIds.last, roadwayNumber1, roadNumber1, roadPartNumber2, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      projectLinkDAO.removeProjectLinksById(Set(projectLinkId1))
      val result = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(), Set(linkId1), id)
      result.size should be (projectLinks.size - 1)
      result.head.id should be (projectLinkId2)
    }
  }

  test("Test removeProjectLinksByProject When creating two projectLinks and deleting them by projectId Then none should be returned") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId1 = id + 1
      val projectLinkId2 = id + 2
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId1, roadwayIds.last, roadwayNumber1, roadNumber1, roadPartNumber2, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      projectLinkDAO.removeProjectLinksByProject(id)
      val result = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(), Set(linkId1), id)
      result.size should be (0)
    }
  }

  test("Test removeProjectLinksByLinkId When creating two projectLinks and deleting them by linkId Then none should be returned") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId1 = id + 1
      val projectLinkId2 = id + 2
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId1, roadwayIds.last, roadwayNumber1, roadNumber1, roadPartNumber2, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      projectLinkDAO.removeProjectLinksByLinkId(id, Set(linkId1))
      val result = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(), Set(linkId1), id)
      result.size should be (0)
    }
  }

  test("Test fetchSplitLinks When creating three projectLinks by splitted origin and fetching by linkId should get all of them") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(splittedDummyRoadways)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId1 = id + 1
      val projectLinkId2 = id + 2
      val projectLinkId3 = id + 3
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId3, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, false, 0),
        dummyProjectLink(projectLinkId1, id, linkId2, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 40, 0.0, 40.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, false, 0, Some(linkId1)),
        dummyProjectLink(projectLinkId2, id, linkId3, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 40, 100, 0.0, 60.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, false, 0, Some(linkId1))
      )
      projectLinkDAO.create(projectLinks)
      projectLinkDAO.fetchSplitLinks(id, linkId1)
      val result = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(), Set(linkId1, linkId2, linkId3), id)
      result.size should be (3)
    }
  }

  test("Test removeProjectLinksByProjectAndRoadNumber When creating two projectLinks and deleting roadNumber1 by project and road number Then link with roadNumber2 should be returned") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val projectLinkId1 = id + 1
      val projectLinkId2 = id + 2
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber1, roadPartNumber1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadNumber2, roadPartNumber2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadNumber1, roadPartNumber1, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, roadNumber2, roadPartNumber2, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      projectLinkDAO.removeProjectLinksByProjectAndRoadNumber(id, roadNumber1, roadPartNumber1)
      val result = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(projectLinkId1, projectLinkId2), Set(), id)
      result.size should be (1)
      result.head.linkId should be (linkId2)
    }
  }

  val dummyProjectLink2 = dummyProjectLink(0, 0, 0, 0, 0, 0, 0, 0, 100, 0.0, 100.0, None, (None, None), FloatingReason.NoFloating, Seq(), LinkStatus.Transfer, RoadType.PublicRoad, reversed = false, 0)

  test("Test ProjectLink.lastSegmentDirection When / towards digitizing and not reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(geometry = Seq(Point(50, 50), Point(150, 150), Point(200, 200)))
    projectLink.lastSegmentDirection should be(Vector3d(50, 50, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When / against digitizing and not reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(geometry = Seq(Point(50, 50), Point(150, 150), Point(200, 200)), sideCode = AgainstDigitizing)
    projectLink.lastSegmentDirection should be(Vector3d(-100, -100, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When \\ towards digitizing and not reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(geometry = Seq(Point(-50, 50), Point(-150, 150), Point(-200, 200)))
    projectLink.lastSegmentDirection should be(Vector3d(-50, 50, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When \\ against digitizing and not reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(geometry = Seq(Point(-50, 50), Point(-150, 150), Point(-200, 200)), sideCode = AgainstDigitizing)
    projectLink.lastSegmentDirection should be(Vector3d(100, -100, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When / towards digitizing and reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(geometry = Seq(Point(50, 50), Point(150, 150), Point(200, 200)), reversed = true)
    projectLink.lastSegmentDirection should be(Vector3d(-100, -100, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When / against digitizing and reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(geometry = Seq(Point(50, 50), Point(150, 150), Point(200, 200)), sideCode = AgainstDigitizing, reversed = true)
    projectLink.lastSegmentDirection should be(Vector3d(50, 50, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When \\ towards digitizing and reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(geometry = Seq(Point(-50, 50), Point(-150, 150), Point(-200, 200)), reversed = true)
    projectLink.lastSegmentDirection should be(Vector3d(100, -100, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When \\ against digitizing and reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(geometry = Seq(Point(-50, 50), Point(-150, 150), Point(-200, 200)), sideCode = AgainstDigitizing, reversed = true)
    projectLink.lastSegmentDirection should be(Vector3d(-50, 50, 0))
  }

}