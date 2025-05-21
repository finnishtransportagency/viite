package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.viite.Dummies.dummyLinearLocation
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.NewIdValue
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point, Vector3d}
import fi.vaylavirasto.viite.model.ArealRoadMaintainer.{ARMInvalid, ELYKeskiSuomi, ELYPohjoisSavo}
import fi.vaylavirasto.viite.model.CalibrationPointType.{NoCP, RoadAddressCP}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, ArealRoadMaintainer, CalibrationPointType, Discontinuity, LinkGeomSource, RoadAddressChangeType, RoadPart, SideCode, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.contains
import org.mockito.Mockito.verify
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock
import org.slf4j.Logger

/**
  * Class to test DB trigger that does not allow reserving already reserved links to project
  */
class ProjectLinkDAOSpec extends AnyFunSuite with Matchers {

  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO

  private val roadNumber1 = 5
  private val roadNumber2 = 6

  private val roadPartNumber1 = 1
  private val roadPartNumber2 = 2

  private val roadwayNumber1 = 1000000000L
  private val roadwayNumber2 = 2000000000L

  private val linkId1 = 1000L.toString
  private val linkId2 = 2000L.toString
  private val linkId3 = 3000L.toString

  private def dummyRoadways: Seq[Roadway] = {
    Seq(
      Roadway(NewIdValue, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0, 100),reversed = false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), ArealRoadMaintainer("ELY1"), TerminationCode.NoTermination),
      Roadway(NewIdValue, roadwayNumber2, RoadPart(roadNumber1, roadPartNumber2), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0, 100),reversed = false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), ArealRoadMaintainer("ELY1"), TerminationCode.NoTermination)
    )
  }

  private def dummyLinearLocations = Seq(
    dummyLinearLocation(roadwayNumber = roadwayNumber1, orderNumber = 1L, linkId = linkId1, startMValue = 0.0, endMValue = 10.0),
    dummyLinearLocation(roadwayNumber = roadwayNumber2, orderNumber = 1L, linkId = linkId2, startMValue = 0.0, endMValue = 10.0))

  private def splittedDummyRoadways: Seq[Roadway] = {
    Seq(Roadway(NewIdValue, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, AddrMRange(0, 100),reversed = false, DateTime.parse("2000-01-01"), None, "testUser", Some("Test Rd. 1"), ArealRoadMaintainer("ELY1"), TerminationCode.NoTermination)
    )
  }

  def dummyProjectLink(id: Long, projectId: Long, linkId: String, roadwayId: Long = 0, roadwayNumber: Long = roadwayNumber1, roadPart: RoadPart = RoadPart(roadNumber1, roadPartNumber1), addrMRange: AddrMRange, startMValue: Double, endMValue: Double, endDate: Option[DateTime] = None, calibrationPoints: (Option[ProjectCalibrationPoint], Option[ProjectCalibrationPoint]) = (None, None), geometry: Seq[Point] = Seq(), status: RoadAddressChangeType, administrativeClass: AdministrativeClass, reversed: Boolean, linearLocationId: Long, connectedLinkId: Option[String] = None, track: Track = Track.Combined): ProjectLink =
    ProjectLink(id, roadPart, track, Discontinuity.Continuous, addrMRange, addrMRange, Some(DateTime.parse("1901-01-01")), endDate, Some("testUser"), linkId, startMValue, endMValue, SideCode.TowardsDigitizing, (if (calibrationPoints._1.isDefined) calibrationPoints._1.get.typeCode else NoCP, if (calibrationPoints._2.isDefined) calibrationPoints._2.get.typeCode else NoCP), (NoCP, NoCP), geometry, projectId, status, administrativeClass, LinkGeomSource.NormalLinkInterface, GeometryUtils.geometryLength(geometry), roadwayId, linearLocationId, ArealRoadMaintainer.ARMInvalid, reversed, connectedLinkId = connectedLinkId, 631152000, roadwayNumber, roadAddressLength = addrMRange.lengthOption)

  private def dummyRoadAddressProject(id: Long, status: ProjectState, reservedParts: Seq[ProjectReservedPart] = List.empty[ProjectReservedPart], coordinates: Option[ProjectCoordinates] = None): Project = {
    Project(id, status, "testProject", "testUser", DateTime.parse("1901-01-01"), "testUser", DateTime.parse("1901-01-01"), DateTime.now(), "additional info here", reservedParts, Seq(), Some("current status info"), coordinates)
  }

  //TODO test coverage missing for ProjectLinkDAO methods:
  /**
    * removeProjectLinksByProjectAndRoadNumber VIITE-1543
    */

  test("Test updateAddrMValues When having changed addr values for some reason (e.g. recalculate) Then should still return original addr values") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val projectId = Sequences.nextViiteProjectId
      val projectLinkId = Sequences.nextProjectLinkId
      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, RoadPart(roadNumber1, roadPartNumber1), rap.createdBy)
      val projectLink = dummyProjectLink(projectLinkId, projectId, linkId1, roadwayIds.head, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0)
      projectLinkDAO.create(Seq(projectLink))
      val (originalStartAddrM, originalEndStartAddrM) = (projectLink.originalAddrMRange.start, projectLink.originalAddrMRange.end)
      projectLinkDAO.updateAddrMValues(projectLink.copy(addrMRange = AddrMRange(200, 300)))
      val returnedProjectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      returnedProjectLinks.size should be(1)
      returnedProjectLinks.head.originalAddrMRange should be(AddrMRange(originalStartAddrM,originalEndStartAddrM))
    }
  }

  test("Test create When having no reversed links Then should return no reversed project links") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val projectId = Sequences.nextViiteProjectId
      val projectLinkId = Sequences.nextProjectLinkId
      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, RoadPart(roadNumber1, roadPartNumber1), rap.createdBy)
      val projectLinks = Seq(dummyProjectLink(projectLinkId, projectId, linkId1, roadwayIds.head, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      val returnedProjectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      returnedProjectLinks.count(x => x.reversed) should be(0)
    }
  }

  test("Test create When having calibration points Then should save and fetch the calibration point types correctly") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val projectId = Sequences.nextViiteProjectId
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val project = dummyRoadAddressProject(projectId, ProjectState.Incomplete, Seq(), None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(projectId, RoadPart(roadNumber1, roadPartNumber1), project.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, projectId, linkId1, roadwayIds.head, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AddrMRange(  0, 100), 0.0, 100.0, None, (Some(ProjectCalibrationPoint(linkId1, 0.0, 0, RoadAddressCP)),     None), Seq(), RoadAddressChangeType.New, AdministrativeClass.State, reversed = false, 0)
                                          .copy(originalCalibrationPointTypes = (NoCP, NoCP)),
        dummyProjectLink(projectLinkId2, projectId, linkId2, roadwayIds.head, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AddrMRange(100, 200), 0.0, 100.0, None, (None, Some(ProjectCalibrationPoint(linkId2, 100.0, 200, RoadAddressCP))), Seq(), RoadAddressChangeType.New, AdministrativeClass.State, reversed = false, 0)
                                          .copy(originalCalibrationPointTypes = (NoCP, NoCP))
      )
      projectLinkDAO.create(projectLinks)
      val returnedProjectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      val linksWithStartCP = returnedProjectLinks.filter(p => p.hasCalibrationPointAtStart)
      val linksWithEndCP = returnedProjectLinks.filter(p => p.hasCalibrationPointAtEnd)
      linksWithStartCP.size should be(1)
      linksWithEndCP.size should be(1)
      val firstLink = linksWithStartCP.head
      val lastLink = linksWithEndCP.head

      firstLink.hasCalibrationPointAtStart should be(true)
      lastLink.hasCalibrationPointAtEnd should be(true)

      firstLink.hasCalibrationPointCreatedInProject should be(true)
      lastLink.hasCalibrationPointCreatedInProject should be(true)

      firstLink.hasCalibrationPointAtEnd should be(false)
      lastLink.hasCalibrationPointAtStart should be(false)

      firstLink.startCalibrationPointType should be(RoadAddressCP)
      lastLink.endCalibrationPointType should be(RoadAddressCP)

      firstLink.endCalibrationPointType should be(NoCP)
      lastLink.startCalibrationPointType should be(NoCP)
    }
  }

  test("Test removeProjectLinksById When list of project links are removed Then they should not be found anymore") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViiteProjectId
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, RoadPart(roadNumber1, roadPartNumber1), rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, RoadPart(roadNumber1, roadPartNumber2), rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, RoadPart(roadNumber1, roadPartNumber2), AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      projectDAO.fetchById(id).nonEmpty should be(true)
      val returnedProjectLinks = projectLinkDAO.fetchProjectLinks(id)
      projectLinkDAO.removeProjectLinksById(returnedProjectLinks.map(_.id).toSet) should be(returnedProjectLinks.size)
      projectLinkDAO.fetchProjectLinks(id).nonEmpty should be(false)
    }
  }

  test("Test updateProjectLinkAdministrativeClassDiscontinuity When Administrative Class or discontinuity got updated Then update should be made with success") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViiteProjectId
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, RoadPart(roadNumber1, roadPartNumber1), rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, RoadPart(roadNumber1, roadPartNumber2), rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AddrMRange(  0, 100),   0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, RoadPart(roadNumber1, roadPartNumber1), AddrMRange(100, 200), 100.0, 200.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      val returnedProjectLinks = projectLinkDAO.fetchProjectLinks(id)
      val biggestProjectLink = returnedProjectLinks.maxBy(_.addrMRange.end)
      projectLinkDAO.updateProjectLinkAdministrativeClassDiscontinuity(returnedProjectLinks.map(x => x.id).filterNot(_ == biggestProjectLink.id).toSet, RoadAddressChangeType.Unchanged, "test", 2, None)
      projectLinkDAO.updateProjectLinkAdministrativeClassDiscontinuity(Set(biggestProjectLink.id), RoadAddressChangeType.Unchanged, "test", 2, Some(2))
      val savedProjectLinks = projectLinkDAO.fetchProjectLinks(id)
      savedProjectLinks.count(_.administrativeClass.value == 2) should be(savedProjectLinks.size)
      savedProjectLinks.count(_.discontinuity.value == 2) should be(1)
      savedProjectLinks.filter(_.discontinuity.value == 2).head.id should be(biggestProjectLink.id)
    }
  }

  test("Test create When project links is saved as reversed Then project links should also be reversed") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViiteProjectId
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val rap = dummyRoadAddressProject(id, ProjectState.Incomplete, List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, RoadPart(roadNumber1, roadPartNumber1), rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, RoadPart(roadNumber1, roadPartNumber2), rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, RoadPart(roadNumber1, roadPartNumber2), AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks.map(x => x.copy(reversed = true)))
      val returnedProjectLinks = projectLinkDAO.fetchProjectLinks(id)
      returnedProjectLinks.count(x => x.reversed) should be(projectLinks.size)
    }
  }

  test("Test updateProjectLinks When giving one new projectLink with new values Then it should update project link") {
    runWithRollback {
      val projectLinks = projectLinkDAO.fetchProjectLinks(7081807)
      val header = projectLinks.head
      val administrativeClass = AdministrativeClass.State
      val newDiscontinuty = Discontinuity.ChangingArealRoadMaintainer
      val newStartAddrMValue = header.addrMRange.start + 1
      val newEndAddrMValue   = header.addrMRange.end   + 1
      val newTrack = Track.Unknown
      val newStatus = RoadAddressChangeType.Unknown

      val modifiedProjectLinks = Seq(header.copy(track = newTrack, discontinuity = newDiscontinuty, addrMRange = AddrMRange(newStartAddrMValue, newEndAddrMValue), status = newStatus, administrativeClass = administrativeClass))
      projectLinkDAO.updateProjectLinks(modifiedProjectLinks, "test", Seq())

      val updatedProjectLink = projectLinkDAO.fetchProjectLinks(7081807).filter(link => link.id == header.id).head

      updatedProjectLink.administrativeClass should be(administrativeClass)
      updatedProjectLink.discontinuity should be(newDiscontinuty)
      updatedProjectLink.addrMRange should be(AddrMRange(newStartAddrMValue,newEndAddrMValue))
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
      val updatedARM = if(header.arealRoadMaintainer==ELYPohjoisSavo) {  ELYKeskiSuomi  } else {  ELYPohjoisSavo  }
      val newRoadNumber = 1
      val newRoadPartNumber = 5
      projectReservedPartDAO.reserveRoadPart(7081807, RoadPart(newRoadNumber, newRoadPartNumber), "test")
      projectLinkDAO.updateProjectLinkNumbering(header.projectId, header.roadPart, header.status, RoadPart(newRoadNumber, newRoadPartNumber), "test", updatedARM)

      val updatedProjectLink = projectLinkDAO.fetchProjectLinks(7081807).filter(link => link.id == header.id).head
      updatedProjectLink.roadPart should be(RoadPart(newRoadNumber,newRoadPartNumber))
      updatedProjectLink.arealRoadMaintainer should be(updatedARM)
    }
  }

  test("Test updateProjectLinkNumbering When changing the discontinuity value of Left track specific id only " +
    "Then the discontinuity value for that Left track ids should be updated the other should remain") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val projectId = Sequences.nextViiteProjectId
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val roadPart    = RoadPart(roadNumber1, roadPartNumber1)
      val newRoadPart = RoadPart(99999, 1)
      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, rap.createdBy)
      val projectLinkRightSide = dummyProjectLink(projectLinkId1, projectId, linkId1, roadwayIds.head, roadwayNumber1, roadPart, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0, track = Track.RightSide)
      val projectLinkLeftSide  = dummyProjectLink(projectLinkId2, projectId, linkId1, roadwayIds.head, roadwayNumber1, roadPart, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0, track = Track.LeftSide)
      projectLinkDAO.create(Seq(projectLinkLeftSide, projectLinkRightSide))
      val links = projectLinkDAO.fetchProjectLinks(projectId)
      links.size should be(2)
      projectReservedPartDAO.reserveRoadPart(projectId, newRoadPart, "test")
      projectLinkDAO.updateProjectLinkNumbering(projectId, roadPart, RoadAddressChangeType.Renumeration, newRoadPart, "test", ArealRoadMaintainer.ELYPohjoisSavo)
      projectLinkDAO.updateProjectLinkAdministrativeClassDiscontinuity(Set(links.filter(_.track == Track.LeftSide).maxBy(_.addrMRange.end).id), RoadAddressChangeType.Renumeration, "test", links.filter(_.track == Track.LeftSide).head.administrativeClass.value, Some(Discontinuity.MinorDiscontinuity.value))
      val linksAfterUpdate = projectLinkDAO.fetchProjectLinks(projectId)
      linksAfterUpdate.size should be(2)
      linksAfterUpdate.groupBy(p => (p.roadPart)).keys.head should be(newRoadPart)
      linksAfterUpdate.find(_.track == Track.LeftSide ).get.discontinuity should be(Discontinuity.MinorDiscontinuity)
      linksAfterUpdate.find(_.track == Track.RightSide).get.discontinuity should be(Discontinuity.Continuous)
    }
  }

  test("Test updateProjectLinksStatus When giving one link with updated road number and part Then it should be updated") {
    runWithRollback {
      val projectLinks = projectLinkDAO.fetchProjectLinks(7081807)
      val header = projectLinks.head
      projectLinkDAO.updateProjectLinksStatus(Set(header.id), RoadAddressChangeType.Termination, "test")

      val updatedProjectLink = projectLinkDAO.fetchProjectLinks(7081807).filter(link => link.id == header.id).head
      updatedProjectLink.status should be(RoadAddressChangeType.Termination)
    }
  }

  test("Test updateAddrMValues When giving one link with updated road number and part Then it should be updated") {
    runWithRollback {
      val projectLinks = projectLinkDAO.fetchProjectLinks(7081807)
      val header = projectLinks.head
      val newStartAddrMValues = 0
      val newEndAddrMValues = 100
      projectLinkDAO.updateAddrMValues(header.copy(addrMRange = AddrMRange(newStartAddrMValues, newEndAddrMValues)))

      val updatedProjectLink = projectLinkDAO.fetchProjectLinks(7081807).filter(link => link.id == header.id).head
      updatedProjectLink.addrMRange should be(AddrMRange(newStartAddrMValues,newEndAddrMValues))
    }
  }

  test("Test updateProjectLinkValues When giving one link with updated values and new geometry Then it should be updated") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId

      val roadway = Roadway(raId, roadwayNumber1, RoadPart(19999, 2), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0L, 10L), reversed = false, DateTime.parse("1901-01-01"), None, "test_user", None, ArealRoadMaintainer("ELY8"), NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, RoadPart(roadNumber1, roadPartNumber1), rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(Sequences.nextProjectLinkId, projectId, linkId1, roadway.id, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, linearLocationId = linearLocation.id)
      )
      projectLinkDAO.create(projectLinks)

      val roadNumber = 1
      val roadPartNumber = 1
      val administrativeClass = AdministrativeClass.State
      val track = Track.Combined
      val discontinuity = Discontinuity.Continuous
      val addrMRange = AddrMRange(0, 100)
      val startMValue = 0
      val endMValue = 100
      val side = SideCode.TowardsDigitizing
      val geometry = Seq(Point(0.0, 0.0), Point(0.0, 100.0))

      projectReservedPartDAO.reserveRoadPart(projectId, RoadPart(roadNumber, roadPartNumber), rap.createdBy)

      val roadAddress = RoadAddress(raId, linearLocationId, RoadPart(roadNumber, roadPartNumber), administrativeClass, track, discontinuity, addrMRange, Some(DateTime.now), None, None, 12345.toString, startMValue, endMValue, side, 1542205983000L, (None, None), geometry, LinkGeomSource.NormalLinkInterface, ArealRoadMaintainer.ELYPohjoisSavo, NoTermination, 1, Some(DateTime.now), None, None)

      projectLinkDAO.updateProjectLinkValues(projectId, roadAddress)

      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      val updatedProjectLink = updatedProjectLinks.filter(link => link.id == projectLinks.head.id).head
      updatedProjectLink.roadPart should be(RoadPart(roadNumber, roadPartNumber))
      updatedProjectLink.track should be(track)
      updatedProjectLink.discontinuity should be(discontinuity)
      updatedProjectLink.administrativeClass should be(administrativeClass)
      updatedProjectLink.addrMRange should be(addrMRange)
      updatedProjectLink.startMValue should be(startMValue)
      updatedProjectLink.endMValue should be(endMValue)
      updatedProjectLink.sideCode should be(side)
      updatedProjectLink.geometry should be(geometry)
    }
  }

  test("Test batchUpdateProjectLinksToTerminate When batch updating project links back to original values Then all the project link values should reset except for RoadAddressChangeType which should be updated to termination.") {
    runWithRollback {
      // Mock logger to verify that no rows were updated
      val mockLogger = mock[Logger]
      val projectLinkDAO = new ProjectLinkDAO {
        override protected def logger: Logger = mockLogger
      }
      // Create project
      val projectId = Sequences.nextViiteProjectId
      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, List.empty, None)
      projectDAO.create(rap)
      // "Original" values for which to reset into
      val originalRoadPart1 = RoadPart(roadNumber1,roadPartNumber1)
      val originalRoadPart2 = RoadPart(roadNumber2,roadPartNumber2)
      val originalTrack = Track.Combined
      val originalDiscontinuity = Discontinuity.Continuous
      val originalAdministrativeClass = AdministrativeClass.State
      val originalARM = ArealRoadMaintainer.ELYUusimaa
      val originalAddrMRange = AddrMRange(0L, 10L)
      val originalStartMValue = 0.0
      val originalEndMValue = 10.0
      val originalSide = SideCode.TowardsDigitizing
      val originalLinkID = 1000L.toString
      // Roadway
      val raId = Sequences.nextRoadwayId
      val roadway = Roadway(raId, roadwayNumber1, originalRoadPart1, originalAdministrativeClass, originalTrack, originalDiscontinuity,
        AddrMRange(0L, 10L), reversed = false, DateTime.now(), None, "testUser", None, ArealRoadMaintainer("ELY8"), NoTermination, DateTime.now(), None)
      roadwayDAO.create(Seq(roadway))
      // LinearLocation
      val linearLocationId = 1
      val linearLocation = LinearLocation(linearLocationId, 1, originalLinkID, originalStartMValue, originalEndMValue, originalSide,
        10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 10.0),
          Point(10.0, 20.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)
      linearLocationDAO.create(Seq(linearLocation))
      // Reserve roadParts
      projectReservedPartDAO.reserveRoadPart(projectId, originalRoadPart1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(projectId, originalRoadPart2, rap.createdBy)
      // Create project Links
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, projectId, originalLinkID, roadway.id, roadwayNumber2, originalRoadPart2,
          AddrMRange(0, 100), 0.0, 10.0, None, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 10.0)),
          RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, linearLocationId = linearLocationId)
      )
      projectLinkDAO.create(projectLinks)
      // Update values
      val resetProjectLinks = projectLinks.map(pl =>
        pl.copy(
          roadPart = originalRoadPart1,
          track = originalTrack,
          administrativeClass = originalAdministrativeClass,
          addrMRange = originalAddrMRange,
          startMValue = originalStartMValue,
          endMValue = originalEndMValue,
          arealRoadMaintainer = originalARM,
          linearLocationId = pl.linearLocationId,
          id = pl.id
        )
      )
      val resetProjectLinksFalsely = projectLinks.map(pl =>
        pl.copy(
          linearLocationId = -2, // wrong linearLocationId
          id = pl.id,
          roadPart = RoadPart(99999,999)
        )
      )
      // Perform the batch update
      projectLinkDAO.batchUpdateProjectLinksToTerminate(resetProjectLinks)
      projectLinkDAO.batchUpdateProjectLinksToTerminate(resetProjectLinksFalsely) // This should not update anything with incorrect linearLocationId
      verify(mockLogger).warn(contains("No rows were updated")) // Verify that the logger was called with the expected message
      // Fetch and verify the updates
      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      updatedProjectLinks.foreach { link =>
        link.roadPart shouldBe originalRoadPart1
        link.track shouldBe originalTrack
        link.discontinuity shouldBe originalDiscontinuity
        link.administrativeClass shouldBe originalAdministrativeClass
        link.addrMRange shouldBe originalAddrMRange
        link.startMValue shouldBe originalStartMValue
        link.endMValue shouldBe originalEndMValue
        link.status shouldBe RoadAddressChangeType.Termination
      }
    }
  }

  test("Test fetchElyFromProjectLinks When trying to get ely by project Then it should be returned with success") {
    runWithRollback {
      val projectId = 7081807
      val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)
      val ARM = projectLinks.head.arealRoadMaintainer
      val elyByProject = projectLinkDAO.fetchElyFromProjectLinks(projectId)
      ARM should be(ArealRoadMaintainer.getELYOrARMInvalid(elyByProject.getOrElse(0)))
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
      val projectId = Sequences.nextViiteProjectId
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId
      val connectedId = 123456L.toString

      val roadway = Roadway(raId, roadwayNumber1, RoadPart(19999, 2), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0L, 10L), reversed = false, DateTime.parse("1901-01-01"), None, "test_user", None, ArealRoadMaintainer("ELY8"), NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, RoadPart(roadNumber1, roadPartNumber1), rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(Sequences.nextProjectLinkId, projectId, linkId1, roadway.id, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, linearLocationId = linearLocation.id, connectedLinkId = Some(connectedId))
      )
      projectLinkDAO.create(projectLinks)

      val connectedLinks = projectLinkDAO.fetchProjectLinksByConnectedLinkId(Seq(connectedId))
      connectedLinks.size should be(projectLinks.size)
    }
  }

  test("Test getProjectLinksByLinkId When creating one projectLink Then it should be returned ") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId
      val connectedId = 123456L.toString

      val roadway = Roadway(raId, roadwayNumber1, RoadPart(19999, 2), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0L, 10L), reversed = false, DateTime.parse("1901-01-01"), None, "test_user", None, ArealRoadMaintainer("ELY8"), NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, RoadPart(roadNumber1, roadPartNumber1), rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(Sequences.nextProjectLinkId, projectId, linkId1, roadway.id, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, linearLocationId = linearLocation.id, connectedLinkId = Some(connectedId))
      )
      projectLinkDAO.create(projectLinks)

      val result = projectLinkDAO.getProjectLinksByLinkId(linkId1)
      result.size should be(projectLinks.size)
      result.head.id should be(projectLinks.head.id)
    }
  }

  test("Test getProjectLinksByProjectAndLinkId When creating one projectLink Then it should be returned ") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId
      val connectedId = 123456L.toString

      val roadway = Roadway(raId, roadwayNumber1, RoadPart(19999, 2), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0L, 10L), reversed = false, DateTime.parse("1901-01-01"), None, "test_user", None, ArealRoadMaintainer("ELY8"), NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, RoadPart(roadNumber1, roadPartNumber1), rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(Sequences.nextProjectLinkId, projectId, linkId1, roadway.id, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, linearLocationId = linearLocation.id, connectedLinkId = Some(connectedId))
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
      val projectId = Sequences.nextViiteProjectId
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId
      val connectedId = 123456L.toString

      val roadway = Roadway(raId, roadwayNumber1, RoadPart(19999, 2), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0L, 10L), reversed = false, DateTime.parse("1901-01-01"), None, "test_user", None, ArealRoadMaintainer("ELY8"), NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, RoadPart(roadNumber1, roadPartNumber1), rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(Sequences.nextProjectLinkId, projectId, linkId1, roadway.id, roadwayNumber1, RoadPart(roadNumber1, roadPartNumber1), AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, linearLocationId = linearLocation.id, connectedLinkId = Some(connectedId))
      )
      projectLinkDAO.create(projectLinks)

      val result = projectLinkDAO.fetchByProjectRoadPart(RoadPart(roadNumber1, roadPartNumber1), projectId)
      result.head.linkId should be(linkId1)
    }
  }

  test("Test fetchByProjectRoadParts When creating two project links Then they should be returned") {
    runWithRollback {

      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val linearLocationIds = linearLocationDAO.create(dummyLinearLocations)
      val id = Sequences.nextViiteProjectId
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val roadPart11 = RoadPart(roadNumber1, roadPartNumber1)
      val roadPart12 = RoadPart(roadNumber1, roadPartNumber2)
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadPart11, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadPart12, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadPart11, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, linearLocationIds.head),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber1, roadPart12, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, linearLocationIds.last)
      )
      projectLinkDAO.create(projectLinks)
      projectReservedPartDAO.fetchProjectReservedPart(roadPart11) should be(Some(rap.id, "TestProject"))
      projectReservedPartDAO.fetchProjectReservedPart(roadPart12) should be(Some(rap.id, "TestProject"))
      val reserved203 = projectLinkDAO.fetchByProjectRoadParts(Set(roadPart11), id)
      reserved203.nonEmpty should be(true)
      val reserved205 = projectLinkDAO.fetchByProjectRoadParts(Set(roadPart12), id)
      reserved205.nonEmpty should be(true)
      reserved203 shouldNot be(reserved205)
      reserved203.toSet.intersect(reserved205.toSet) should have size 0
      val reserved = projectLinkDAO.fetchByProjectRoadParts(Set(roadPart11, roadPart12), id)
      reserved.map(_.id).toSet should be(reserved203.map(_.id).toSet ++ reserved205.map(_.id).toSet)
      reserved should have size projectLinks.size
    }
  }

  test("Test reverseRoadPartDirection When creating one projectLink and reverse it Then it should be reversed ") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId
      val roadPart = RoadPart(roadNumber1, roadPartNumber1)
      val connectedId = 123456L.toString

      val roadway = Roadway(raId, roadwayNumber1, RoadPart(19999, 2), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0L, 10L), reversed = false, DateTime.parse("1901-01-01"), None, "test_user", None, ArealRoadMaintainer("ELY8"), NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(Sequences.nextProjectLinkId, projectId, linkId1, roadway.id, roadwayNumber1, roadPart, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, linearLocationId = linearLocation.id, connectedLinkId = Some(connectedId))
      )
      projectLinkDAO.create(projectLinks)

      val side = projectLinks.head.sideCode
      projectLinkDAO.reverseRoadPartDirection(projectId, roadPart)
      val result1 = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(projectLinks.head.id), Set(), projectId)
      side should not be result1.head.sideCode

      result1.head.linkId should be(linkId1)
    }
  }
  //  projectLinkDAO.countLinksByStatus(projectId, roadNumber, roadPartNumber, Set(UnChanged.value, NotHandled.value))

  test("Test countLinksByStatus When creating one projectLink with some status Then when searching by that RoadAddressChangeType it should be returned ") {
    runWithRollback {
      val projectId = Sequences.nextViiteProjectId
      val linearLocationId = 1
      val raId = Sequences.nextRoadwayId
      val roadPart = RoadPart(roadNumber1, roadPartNumber1)
      val connectedId = 123456L.toString

      val roadway = Roadway(raId, roadwayNumber1, RoadPart(19999, 2), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0L, 10L), reversed = false, DateTime.parse("1901-01-01"), None, "test_user", None, ArealRoadMaintainer("ELY8"), NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val rap = dummyRoadAddressProject(projectId, ProjectState.Incomplete, List.empty, None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(projectId, roadPart, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(Sequences.nextProjectLinkId, projectId, linkId1, roadway.id, roadwayNumber1, roadPart, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, linearLocationId = linearLocation.id, connectedLinkId = Some(connectedId))
      )
      projectLinkDAO.create(projectLinks)

      val result1 = projectLinkDAO.countLinksByStatus(projectId, roadPart, Set(RoadAddressChangeType.Transfer.value))
      val result2 = projectLinkDAO.countLinksByStatus(projectId, roadPart, Set(RoadAddressChangeType.Unchanged.value, RoadAddressChangeType.Termination.value, RoadAddressChangeType.Unknown.value, RoadAddressChangeType.NotHandled.value, RoadAddressChangeType.Renumeration.value, RoadAddressChangeType.New.value))
      result1 should be(projectLinks.size)
      result2 should be(0)
    }
  }

  test("Test fetchFirstLink When creating two projectLinks Then the fetchFirstLink should return only the first one") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViiteProjectId
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val roadPart1 = RoadPart(roadNumber1, roadPartNumber1)
      val roadPart2 = RoadPart(roadNumber1, roadPartNumber2)
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadPart1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadPart2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadPart1, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId1, roadwayIds.last, roadwayNumber1, roadPart2, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      val first = projectLinkDAO.fetchFirstLink(id, roadPart1)
      first.get.id should be(projectLinkId1)
    }
  }

  test("Test removeProjectLinksById When creating two projectLinks and delete one of them Then only one should be returned") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViiteProjectId
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val roadPart1 = RoadPart(roadNumber1, roadPartNumber1)
      val roadPart2 = RoadPart(roadNumber1, roadPartNumber2)
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadPart1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadPart2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadPart1, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId1, roadwayIds.last, roadwayNumber1, roadPart2, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      projectLinkDAO.removeProjectLinksById(Set(projectLinkId1))
      val result = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(), Set(linkId1), id)
      result.size should be(projectLinks.size - 1)
      result.head.id should be(projectLinkId2)
    }
  }

  test("Test removeProjectLinksByProject When creating two projectLinks and deleting them by projectId Then none should be returned") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViiteProjectId
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val roadPart1 = RoadPart(roadNumber1, roadPartNumber1)
      val roadPart2 = RoadPart(roadNumber1, roadPartNumber2)
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadPart1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadPart2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadPart1, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId1, roadwayIds.last, roadwayNumber1, roadPart2, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      projectLinkDAO.removeProjectLinksByProject(id)
      val result = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(), Set(linkId1), id)
      result.size should be(0)
    }
  }

  test("Test removeProjectLinksByLinkId When creating two projectLinks and deleting them by linkId Then none should be returned") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViiteProjectId
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val roadPart1 = RoadPart(roadNumber1, roadPartNumber1)
      val roadPart2 = RoadPart(roadNumber1, roadPartNumber2)
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadPart1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadPart2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadPart1, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId1, roadwayIds.last, roadwayNumber1, roadPart2, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      projectLinkDAO.removeProjectLinksByLinkId(id, Set(linkId1))
      val result = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(), Set(linkId1), id)
      result.size should be(0)
    }
  }

  test("Test fetchSplitLinks When creating three projectLinks by splitted origin and fetching by linkId should get all of them") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(splittedDummyRoadways)
      val id = Sequences.nextViiteProjectId
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val projectLinkId3 = Sequences.nextProjectLinkId
      val roadPart = RoadPart(roadNumber1, roadPartNumber1)
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadPart, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId3, id, linkId1, roadwayIds.head, roadwayNumber1, roadPart, AddrMRange( 0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0),
        dummyProjectLink(projectLinkId1, id, linkId2, roadwayIds.head, roadwayNumber1, roadPart, AddrMRange( 0,  40), 0.0,  40.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0, Some(linkId1)),
        dummyProjectLink(projectLinkId2, id, linkId3, roadwayIds.head, roadwayNumber1, roadPart, AddrMRange(40, 100), 0.0,  60.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0, Some(linkId1))
      )
      projectLinkDAO.create(projectLinks)
      projectLinkDAO.fetchSplitLinks(id, linkId1)
      val result = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(), Set(linkId1, linkId2, linkId3), id)
      result.size should be(3)
    }
  }

  test("Test removeProjectLinksByProjectAndRoadNumber When creating two projectLinks and deleting roadNumber1 by project and road number Then link with roadNumber2 should be returned") {
    runWithRollback {
      val roadwayIds = roadwayDAO.create(dummyRoadways)
      val id = Sequences.nextViiteProjectId
      val projectLinkId1 = Sequences.nextProjectLinkId
      val projectLinkId2 = Sequences.nextProjectLinkId
      val roadPart1 = RoadPart(roadNumber1, roadPartNumber1)
      val roadPart2 = RoadPart(roadNumber2, roadPartNumber2)
      val rap = Project(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty, Seq(), None)
      projectDAO.create(rap)
      projectReservedPartDAO.reserveRoadPart(id, roadPart1, rap.createdBy)
      projectReservedPartDAO.reserveRoadPart(id, roadPart2, rap.createdBy)
      val projectLinks = Seq(
        dummyProjectLink(projectLinkId1, id, linkId1, roadwayIds.head, roadwayNumber1, roadPart1, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0),
        dummyProjectLink(projectLinkId2, id, linkId2, roadwayIds.last, roadwayNumber2, roadPart2, AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0)
      )
      projectLinkDAO.create(projectLinks)
      projectLinkDAO.removeProjectLinksByProjectAndRoadNumber(id, roadPart1)
      val result = projectLinkDAO.fetchProjectLinksByProjectAndLinkId(Set(projectLinkId1, projectLinkId2), Set(), id)
      result.size should be(1)
      result.head.linkId should be(linkId2)
    }
  }

  private val dummyProjectLink2 = dummyProjectLink(0, 0, 0.toString, 0, 0, RoadPart(0, 0), AddrMRange(0, 100), 0.0, 100.0, None, (None, None), Seq(), RoadAddressChangeType.Transfer, AdministrativeClass.State, reversed = false, 0)

  test("Test ProjectLink.lastSegmentDirection When / towards digitizing and not reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(geometry = Seq(Point(50, 50), Point(150, 150), Point(200, 200)))
    projectLink.lastSegmentDirection should be(Vector3d(50, 50, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When / against digitizing and not reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(sideCode = SideCode.AgainstDigitizing, geometry = Seq(Point(50, 50), Point(150, 150), Point(200, 200)))
    projectLink.lastSegmentDirection should be(Vector3d(-100, -100, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When \\ towards digitizing and not reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(geometry = Seq(Point(-50, 50), Point(-150, 150), Point(-200, 200)))
    projectLink.lastSegmentDirection should be(Vector3d(-50, 50, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When \\ against digitizing and not reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(sideCode = SideCode.AgainstDigitizing, geometry = Seq(Point(-50, 50), Point(-150, 150), Point(-200, 200)))
    projectLink.lastSegmentDirection should be(Vector3d(100, -100, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When / towards digitizing and reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(geometry = Seq(Point(50, 50), Point(150, 150), Point(200, 200)), reversed = true)
    projectLink.lastSegmentDirection should be(Vector3d(-100, -100, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When / against digitizing and reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(sideCode = SideCode.AgainstDigitizing, geometry = Seq(Point(50, 50), Point(150, 150), Point(200, 200)), reversed = true)
    projectLink.lastSegmentDirection should be(Vector3d(50, 50, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When \\ towards digitizing and reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(geometry = Seq(Point(-50, 50), Point(-150, 150), Point(-200, 200)), reversed = true)
    projectLink.lastSegmentDirection should be(Vector3d(100, -100, 0))
  }

  test("Test ProjectLink.lastSegmentDirection When \\ against digitizing and reversed Then correct vector") {
    val projectLink = dummyProjectLink2.copy(sideCode = SideCode.AgainstDigitizing, geometry = Seq(Point(-50, 50), Point(-150, 150), Point(-200, 200)), reversed = true)
    projectLink.lastSegmentDirection should be(Vector3d(-50, 50, 0))
  }

}
