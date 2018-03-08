package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{Combined, LeftSide, RightSide}
import fi.liikennevirasto.digiroad2.{Point, Vector3d}
import fi.liikennevirasto.viite.ProjectValidator.ValidationErrorList._
import fi.liikennevirasto.viite.dao.Discontinuity.EndOfRoad
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao.{TerminationCode, _}
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class ProjectValidatorSpec extends FunSuite with Matchers {
  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  private def testDataForCheckTerminationContinuity(noErrorTest: Boolean = false) = {
    val roadAddressId = RoadAddressDAO.getNextRoadAddressId
    val ra = Seq(RoadAddress(roadAddressId, 27L, 20L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 4278L, 4387L,
      Some(DateTime.parse("1996-01-01")), None, Option("TR"), 0, 1817196, 0.0, 108.261, SideCode.AgainstDigitizing, 1476392565000L, (None, None), floating = false,
      Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, TerminationCode.NoTermination, 0))
    if(noErrorTest) {
      val nextRoadAddressId = RoadAddressDAO.getNextRoadAddressId
      val roadsToCreate = ra ++ Seq(RoadAddress(nextRoadAddressId, 27L, 21L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 4387L, 4397L,
        Some(DateTime.parse("1996-01-01")), None, Option("TR"), 1, 1817197, 0.0, 108.261, SideCode.AgainstDigitizing, 1476392565000L, (None, None), floating = false,
        Seq(Point(0.0, 40.0), Point(0.0, 55.0)), LinkGeomSource.NormalLinkInterface, 8, TerminationCode.NoTermination, 0))
      RoadAddressDAO.create(roadsToCreate)
    } else {
      RoadAddressDAO.create(ra)
    }
  }

  private def projectLink(startAddrM: Long, endAddrM: Long, track: Track, projectId: Long, status: LinkStatus = LinkStatus.NotHandled,
                          roadNumber: Long = 19999L, roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L) = {
    ProjectLink(NewRoadAddress, roadNumber, roadPartNumber, track, discontinuity, startAddrM, endAddrM, None, None,
      Some("User"), 0L, startAddrM, 0.0, (endAddrM - startAddrM).toDouble, SideCode.TowardsDigitizing, (None, None),
      floating = false, Seq(Point(0.0, startAddrM), Point(0.0, endAddrM)), projectId, status, RoadType.PublicRoad,
      LinkGeomSource.NormalLinkInterface, (endAddrM - startAddrM).toDouble, 0L, ely, reversed = false, None, 0L)
  }

  private def setUpProjectWithLinks(linkStatus: LinkStatus, addrM: Seq[Long], changeTrack: Boolean = false, roadNumber: Long = 19999L,
                                    roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L) = {
    val id = Sequences.nextViitePrimaryKeySeqValue

    def withTrack(t: Track): Seq[ProjectLink] = {
      addrM.init.zip(addrM.tail).map { case (st, en) =>
        projectLink(st, en, t, id, linkStatus, roadNumber, roadPartNumber, discontinuity, ely)
      }
    }

    val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
      "", Seq(), None, Some(8), None)
    ProjectDAO.createRoadAddressProject(project)
    val links =
      if (changeTrack) {
        withTrack(RightSide) ++ withTrack(LeftSide)
      } else {
        withTrack(Combined)
      }
    ProjectDAO.reserveRoadPart(id, roadNumber, roadPartNumber, "u")
    ProjectDAO.create(links)
    project
  }

  private def setUpProjectWithRampLinks(linkStatus: LinkStatus, addrM: Seq[Long]) = {
    val id = Sequences.nextViitePrimaryKeySeqValue
    val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
      "", Seq(), None, Some(8), None)
    ProjectDAO.createRoadAddressProject(project)
    val links = addrM.init.zip(addrM.tail).map { case (st, en) =>
      projectLink(st, en, Combined, id, linkStatus).copy(roadNumber = 39999)
    }
    ProjectDAO.reserveRoadPart(id, 39999L, 1L, "u")
    ProjectDAO.create(links.init :+ links.last.copy(discontinuity = EndOfRoad))
    project
  }

  private def testDataForElyTest01() = {
    val roadAddressId = RoadAddressDAO.getNextRoadAddressId
    val ra = Seq(RoadAddress(roadAddressId, 16320L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 1270L, 1309L,
      Some(DateTime.parse("1982-09-01")), None, Option("TR"), 0, 2583382, 0.0, 38.517, SideCode.AgainstDigitizing, 1476392565000L, (None, None), floating = false,
      Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, TerminationCode.NoTermination, 0))
    RoadAddressDAO.create(ra)

  }

  private def testDataForElyTest02() = {
    val roadAddressId = RoadAddressDAO.getNextRoadAddressId
    val ra = Seq(RoadAddress(roadAddressId, 27L, 20L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 4278L, 4387L,
      Some(DateTime.parse("1996-01-01")), None, Option("TR"), 0, 1817196, 0.0, 108.261, SideCode.AgainstDigitizing, 1476392565000L, (None, None), floating = false,
      Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, TerminationCode.NoTermination, 0))
    RoadAddressDAO.create(ra)
  }

  test("Project Links should be continuous if geometry is continuous") {
    runWithRollback {
      val project = setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      val endOfRoadSet = projectLinks.init :+ projectLinks.last.copy(discontinuity = EndOfRoad)
      ProjectValidator.checkOrdinaryRoadContinuityCodes(project, endOfRoadSet) should have size 0
      val brokenContinuity = endOfRoadSet.tail :+ endOfRoadSet.head.copy(geometry = projectLinks.head.geometry.map(_ + Vector3d(1.0, 1.0, 0.0)))
      val errors = ProjectValidator.checkOrdinaryRoadContinuityCodes(project, brokenContinuity)
      errors should have size 1
      errors.head.validationError should be(MinorDiscontinuityFound)
    }
  }

  test("Project Links missing end of road should be caught") {
    runWithRollback {
      val project = setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      val errors = ProjectValidator.checkOrdinaryRoadContinuityCodes(project, projectLinks)
      errors should have size 1
      errors.head.validationError should be(MissingEndOfRoad)
    }
  }

  test("Project Links must not have an end of road code if next part exists in project") {
    runWithRollback {
      val project = setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      ProjectDAO.reserveRoadPart(project.id, 19999L, 2L, "u")
      ProjectDAO.create(projectLinks.map(l => l.copy(id = NewRoadAddress, roadPartNumber = 2L, modifiedBy = Some("User"),
        geometry = l.geometry.map(_ + Vector3d(0.0, 40.0, 0.0)))))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val errors = ProjectValidator.checkOrdinaryRoadContinuityCodes(updProject, projectLinks)
      ProjectDAO.getProjectLinks(project.id) should have size 8
      errors should have size 0
      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = ProjectValidator.checkOrdinaryRoadContinuityCodes(updProject,
        starting ++ last.map(_.copy(discontinuity = EndOfRoad)))
      errorsUpd should have size 1
      errorsUpd.head.validationError should be(EndOfRoadNotOnLastPart)
    }
  }

  test("Project Links must not have an end of road code if next part exists in road address table") {
    runWithRollback {
      val project = setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      ProjectValidator.checkOrdinaryRoadContinuityCodes(project, projectLinks) should have size 1
      RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
        0L, 10L, Some(DateTime.now()), None, None, 0L, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
        floating = false, Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0)))
      val errors = ProjectValidator.checkOrdinaryRoadContinuityCodes(project, projectLinks)
      errors should have size 0
      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = ProjectValidator.checkOrdinaryRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = EndOfRoad)))
      errorsUpd should have size 1
      errorsUpd.head.validationError should be(EndOfRoadNotOnLastPart)
    }
  }

  test("Project Links must have a major discontinuity code if and only if next part exists in road address / project link table and is not connected") {
    runWithRollback {
      val project = setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      val raId = RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
        0L, 10L, Some(DateTime.now()), None, None, 0L, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
        floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))).head
      val errors = ProjectValidator.checkOrdinaryRoadContinuityCodes(project, projectLinks)
      errors should have size 1
      errors.head.validationError should be(MajorDiscontinuityFound)

      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = ProjectValidator.checkOrdinaryRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Discontinuous)))
      errorsUpd should have size 0

      RoadAddressDAO.updateGeometry(raId, Seq(Point(0.0, 40.0), Point(0.0, 50.0)))

      val connectedError = ProjectValidator.checkOrdinaryRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Discontinuous)))
      connectedError should have size 1
      connectedError.head.validationError should be(ConnectedDiscontinuousLink)
    }
  }
  //TODO to be done/changed in a more detailed story
  ignore("Project Links must have a ely change discontinuity code if next part is on different ely") {
    runWithRollback {
      val project = setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      val raId = RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
        0L, 10L, Some(DateTime.now()), None, None, 0L, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
        floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 9L, NoTermination, 0))).head
      val errors = ProjectValidator.checkOrdinaryRoadContinuityCodes(project, projectLinks)
      errors should have size 1
      errors.head.validationError should be(ElyCodeChangeDetected)

      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = ProjectValidator.checkOrdinaryRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.ChangingELYCode)))
      errorsUpd should have size 0

      RoadAddressDAO.updateGeometry(raId, Seq(Point(0.0, 40.0), Point(0.0, 50.0)))

      val connectedError = ProjectValidator.checkOrdinaryRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Continuous)))
      connectedError should have size 1
      connectedError.head.validationError should be(ElyCodeChangeDetected)
    }
  }

  test("Termination of last road part requires a change of discontinuity on previous part") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 0L, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 0L, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      val raIds = RoadAddressDAO.create(ra, Some("U"))
      val roadAddress = RoadAddressDAO.fetchByIdMassQuery(raIds.toSet).sortBy(_.roadPartNumber)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      ProjectDAO.createRoadAddressProject(project)
      ProjectDAO.reserveRoadPart(id, 19999L, 1L, "u")
      ProjectDAO.reserveRoadPart(id, 19999L, 2L, "u")

      ProjectDAO.create(Seq(projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged),
        projectLink(0L, 10L, Combined, id, LinkStatus.Terminated)).zip(roadAddress).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val errors = ProjectValidator.checkRemovedEndOfRoadParts(updProject)
      errors should have size 1
      errors.head.validationError should be(MissingEndOfRoad)
      errors.head.optionalInformation should be(Some("TIE 19999 OSA 1"))
      val projectLinks = ProjectDAO.getProjectLinks(id, Some(LinkStatus.UnChanged)).map(_.copy(discontinuity = EndOfRoad))
      ProjectDAO.updateProjectLinksToDB(projectLinks, "U")
      val updProject2 = ProjectDAO.getRoadAddressProjectById(project.id).get
      ProjectValidator.checkRemovedEndOfRoadParts(updProject2) should have size 0
    }
  }

  test("Ramps must have continuity validation") {
    runWithRollback {
      val project = setUpProjectWithRampLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      val errors = ProjectValidator.checkRampContinuityCodes(project, projectLinks)
      errors should have size 0

      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = ProjectValidator.checkRampContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Continuous)))
      errorsUpd should have size 1

      val errorsUpd2 = ProjectValidator.checkRampContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.MinorDiscontinuity)))
      errorsUpd2 should have size 1

      val ra = Seq(
        RoadAddress(NewRoadAddress, 39998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 0L, 39398L, 0.0, 10.0, AgainstDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(2.0, 30.0), Point(0.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 39998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          10L, 20L, Some(DateTime.now()), None, None, 0L, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(2.0, 30.0), Point(7.0, 35.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 39998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          20L, 30L, Some(DateTime.now()), None, None, 0L, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
          floating = false, Seq(Point(7.0, 35.0), Point(0.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      RoadAddressDAO.create(ra)

      ProjectDAO.reserveRoadPart(project.id, 39999L, 20L, "u")
      ProjectDAO.create((starting ++ last.map(_.copy(discontinuity = Discontinuity.EndOfRoad)))
        .map(_.copy(id = NewRoadAddress, roadPartNumber = 20L, modifiedBy = Some("I"))))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      ProjectValidator.checkRampContinuityCodes(updProject,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.MinorDiscontinuity))) should have size 0
    }
  }

  test("validator should produce an error on Not Handled links") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 0L, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 0L, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      val raIds = RoadAddressDAO.create(ra, Some("U"))
      val roadAddress = RoadAddressDAO.fetchByIdMassQuery(raIds.toSet).sortBy(_.roadPartNumber)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      ProjectDAO.createRoadAddressProject(project)
      ProjectDAO.reserveRoadPart(id, 19999L, 1L, "u")
      ProjectDAO.reserveRoadPart(id, 19999L, 2L, "u")

      ProjectDAO.create(Seq(projectLink(0L, 10L, Combined, id, LinkStatus.NotHandled),
        projectLink(0L, 10L, Combined, id, LinkStatus.Terminated)).zip(roadAddress).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))

      val validationErrors = ProjectValidator.validateProject(project, ProjectDAO.getProjectLinks(project.id)).filter(_.validationError.value == HasNotHandledLinks.value)
      validationErrors.size should be(1)
      validationErrors.head.validationError.message should be("")
      validationErrors.head.optionalInformation should not be ("")
    }
  }

  test("validator should return invalid unchanged links error") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 0L, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          10L, 20L, Some(DateTime.now()), None, None, 0L, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      val raId1 = RoadAddressDAO.create(Set(ra.head), Some("U"))
      val raId2 = RoadAddressDAO.create(ra.tail, Some("U"))
      val roadAddress1 = RoadAddressDAO.fetchByIdMassQuery(raId1.toSet).sortBy(_.roadPartNumber)
      val roadAddress2 = RoadAddressDAO.fetchByIdMassQuery(raId2.toSet).sortBy(_.roadPartNumber)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      ProjectDAO.createRoadAddressProject(project)
      ProjectDAO.reserveRoadPart(id, 19999L, 1L, "u")

      ProjectDAO.create(Seq(projectLink(0L, 10L, Combined, id, LinkStatus.NotHandled),
        projectLink(0L, 10L, Combined, id, LinkStatus.Transfer)).zip(roadAddress1).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))
      ProjectDAO.create(Seq(projectLink(10L, 20L, Combined, id, LinkStatus.NotHandled),
        projectLink(10L, 20L, Combined, id, LinkStatus.UnChanged)).zip(roadAddress2).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))

      val projectLinks = ProjectDAO.getProjectLinks(id, Some(LinkStatus.NotHandled))
      val updatedProjectLinks = Seq(projectLinks.head.copy(status = LinkStatus.Transfer)) ++ projectLinks.tail.map(pl => pl.copy(status = LinkStatus.UnChanged))
      ProjectDAO.updateProjectLinksToDB(updatedProjectLinks, "U")
      val validationErrors = ProjectValidator.validateProject(project, ProjectDAO.getProjectLinks(project.id))

      validationErrors.size shouldNot be(0)
      validationErrors.count(_.validationError.value == ErrorInValidationOfUnchangedLinks.value) should be(1)
    }
  }

  test("validator should return errors if discontinuity is 3 and next road part ely is equal") {
    runWithRollback {
      testDataForElyTest01()
      val project = setUpProjectWithLinks(LinkStatus.UnChanged, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = false, 16320L, 1L, Discontinuity.ChangingELYCode)
      val projectLinks = ProjectDAO.getProjectLinks(project.id)

      val validationErrors = ProjectValidator.checkProjectElyCodes(project, projectLinks)
      validationErrors.size should be(1)
      validationErrors.head.validationError.value should be(RoadNotEndingInElyBorder.value)
    }
  }

  test("validator should return errors if discontinuity is anything BUT 3 and next road part ely is different") {
    runWithRollback {
      testDataForElyTest02()
      val project = setUpProjectWithLinks(LinkStatus.UnChanged, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = false, 27L, 19L, Discontinuity.Continuous, 12L)
      val projectLinks = ProjectDAO.getProjectLinks(project.id)

      val validationErrors = ProjectValidator.checkProjectElyCodes(project, projectLinks)
      validationErrors.size should be(1)
      validationErrors.head.validationError.value should be(RoadContinuesInAnotherEly.value)
    }
  }

  test("project track codes should be consistent") {
    runWithRollback {
      val project = setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      val validationErrors = ProjectValidator.checkTrackCode(project, projectLinks)
      validationErrors.size should be(0)
    }
  }

  test("project track codes inconsistent in midle of track") {
    runWithRollback {
      val project = setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      val inconsistentLinks = projectLinks.map { l =>
        if (l.startAddrMValue == 20 && l.track == Track.RightSide)
          l.copy(track = Track.LeftSide)
        else l
      }
      val validationErrors = ProjectValidator.checkTrackCode(project, inconsistentLinks)
      validationErrors.size should be(1)
    }
  }

  test("project track codes inconsistent in extermities") {
    runWithRollback {
      val project = setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      val inconsistentLinks = projectLinks.map { l =>
        if (l.startAddrMValue == 0 && l.track == Track.RightSide)
          l.copy(startAddrMValue = 5)
        else l
      }
      val validationErrors = ProjectValidator.checkTrackCode(project, inconsistentLinks)
      validationErrors.size should be(1)
    }
  }

  test("validator should return an issue whenever a road gets terminated and adjacent to that same road lies other roads with discontinuity value = 1") {
    runWithRollback {
      testDataForCheckTerminationContinuity()
      val project = setUpProjectWithLinks(LinkStatus.Terminated, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = false, 16320L, 1L, Discontinuity.ChangingELYCode)
      val projectLinks = ProjectDAO.getProjectLinks(project.id)

      val validationErrors = ProjectValidator.checkTerminationContinuity(project, projectLinks)
      validationErrors.size should be(1)
      validationErrors.head.validationError.value should be(TerminationContinuity.value)
    }
  }

  test("validator should NOT return an issue whenever a road gets terminated and adjacent lies other roads with discontinuity value = 1," +
    "that share the same road number, track code and are adjacent between them") {
    runWithRollback {
      testDataForCheckTerminationContinuity(true)
      val project = setUpProjectWithLinks(LinkStatus.Terminated, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = false, 16320L, 1L, Discontinuity.ChangingELYCode)
      val projectLinks = ProjectDAO.getProjectLinks(project.id)

      val validationErrors = ProjectValidator.checkTerminationContinuity(project, projectLinks)
      validationErrors.size should be(1)
      validationErrors.head.validationError.value should be(TerminationContinuity.value)
    }
  }
}
