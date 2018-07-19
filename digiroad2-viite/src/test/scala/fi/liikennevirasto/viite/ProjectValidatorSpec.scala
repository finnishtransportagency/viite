package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{Combined, LeftSide, RightSide}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils, Point, Vector3d}
import fi.liikennevirasto.viite.ProjectValidator.ValidationErrorList
import fi.liikennevirasto.viite.ProjectValidator.ValidationErrorList._
import fi.liikennevirasto.viite.RoadType.PublicRoad
import fi.liikennevirasto.viite.dao.Discontinuity.EndOfRoad
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao.{TerminationCode, _}
import fi.liikennevirasto.viite.util.toProjectLink
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

class ProjectValidatorSpec extends FunSuite with Matchers {
  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val roadAddressService = new RoadAddressService(mockRoadLinkService, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }


  val projectService = new ProjectService(roadAddressService, mockRoadLinkService, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  private def testDataForCheckTerminationContinuity(noErrorTest: Boolean = false) = {
    val roadAddressId = RoadAddressDAO.getNextRoadAddressId
    val ra = Seq(RoadAddress(roadAddressId, 27L, 20L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 6109L, 6559L,
      Some(DateTime.parse("1996-01-01")), None, Option("TR"), 1817196, 0.0, 108.261, SideCode.AgainstDigitizing, 1476392565000L, (None, None), floating = false,
      Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, TerminationCode.NoTermination, 0))
    if(noErrorTest) {
      val roadsToCreate = ra ++ Seq(RoadAddress(RoadAddressDAO.getNextRoadAddressId, 26L, 21L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 6559L, 5397L,
        Some(DateTime.parse("1996-01-01")), None, Option("TR"), 1817197, 0.0, 108.261, SideCode.AgainstDigitizing, 1476392565000L, (None, None), floating = false,
        Seq(Point(0.0, 40.0), Point(0.0, 55.0)), LinkGeomSource.NormalLinkInterface, 8, TerminationCode.NoTermination, 0),
        RoadAddress(RoadAddressDAO.getNextRoadAddressId, 27L, 22L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 6559L, 5397L,
          Some(DateTime.parse("1996-01-01")), None, Option("TR"), 1817198, 0.0, 108.261, SideCode.AgainstDigitizing, 1476392565000L, (None, None), floating = false,
          Seq(Point(0.0, 40.0), Point(0.0, 55.0)), LinkGeomSource.NormalLinkInterface, 8, TerminationCode.NoTermination, 0),
        RoadAddress(RoadAddressDAO.getNextRoadAddressId, 27L, 23L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 6559L, 5397L,
          Some(DateTime.parse("1996-01-01")), None, Option("TR"), 1817199, 0.0, 108.261, SideCode.AgainstDigitizing, 1476392565000L, (None, None), floating = false,
          Seq(Point(0.0, 120.0), Point(0.0, 130.0)), LinkGeomSource.NormalLinkInterface, 8, TerminationCode.NoTermination, 0))
      RoadAddressDAO.create(roadsToCreate)
    } else {
      RoadAddressDAO.create(ra)
    }
  }

  private def projectLink(startAddrM: Long, endAddrM: Long, track: Track, projectId: Long, status: LinkStatus = LinkStatus.NotHandled,
                          roadNumber: Long = 19999L, roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadAddressId: Long = 0L) = {
    ProjectLink(NewRoadAddress, roadNumber, roadPartNumber, track, discontinuity, startAddrM, endAddrM, None, None,
      Some("User"), startAddrM, 0.0, (endAddrM - startAddrM).toDouble, SideCode.TowardsDigitizing, (None, None),
      floating = false, Seq(Point(0.0, startAddrM), Point(0.0, endAddrM)), projectId, status, RoadType.PublicRoad,
      LinkGeomSource.NormalLinkInterface, (endAddrM - startAddrM).toDouble, roadAddressId, ely, reversed = false, None, 0L)
  }

  def toProjectLink(project: RoadAddressProject)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(roadAddress.id, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track,
      roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate,
      roadAddress.endDate, createdBy =Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue,
      roadAddress.sideCode, roadAddress.calibrationPoints, floating=false, roadAddress.geometry, project.id, LinkStatus.NotHandled, RoadType.PublicRoad,
      roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.id, roadAddress.ely,false,
      None, roadAddress.adjustedTimestamp)
  }

  private def setUpProjectWithLinks(linkStatus: LinkStatus, addrM: Seq[Long], changeTrack: Boolean = false, roadNumber: Long = 19999L,
                                    roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadAddressId: Long = 0L) = {
    val id = Sequences.nextViitePrimaryKeySeqValue

    def withTrack(t: Track): Seq[ProjectLink] = {
      addrM.init.zip(addrM.tail).map { case (st, en) =>
        projectLink(st, en, t, id, linkStatus, roadNumber, roadPartNumber, discontinuity, ely, roadAddressId)
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
      Some(DateTime.parse("1982-09-01")), None, Option("TR"), 2583382, 0.0, 38.517, SideCode.AgainstDigitizing, 1476392565000L, (None, None), floating = false,
      Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, TerminationCode.NoTermination, 0))
    RoadAddressDAO.create(ra)

  }

  private def testDataForElyTest02(): (Long, Long) = {
    val roadAddressId = RoadAddressDAO.getNextRoadAddressId
    val linkId = 1817196L
    val ra = Seq(RoadAddress(roadAddressId, 27L, 20L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous, 6109L, 6559L,
      Some(DateTime.parse("1996-01-01")), None, Option("TR"), linkId, 0.0, 108.261, SideCode.AgainstDigitizing, 1476392565000L, (None, None), floating = false,
      Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, TerminationCode.NoTermination, 0))
    RoadAddressDAO.create(ra)
    (roadAddressId, linkId)
  }

  test("Project Links should be continuous if geometry is continuous") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val endOfRoadSet = projectLinks.init :+ projectLinks.last.copy(discontinuity = EndOfRoad)
      ProjectValidator.checkRoadContinuityCodes(project, endOfRoadSet).distinct should have size 0
      val brokenContinuity = endOfRoadSet.tail :+ endOfRoadSet.head.copy(geometry = projectLinks.head.geometry.map(_ + Vector3d(1.0, 1.0, 0.0)), endMValue = 11L)
      val errors = ProjectValidator.checkRoadContinuityCodes(project, brokenContinuity).distinct
      errors should have size 1
      errors.head.validationError should be(MinorDiscontinuityFound)
    }
  }

  test("Project Links should be continuous if geometry is continuous for Left and Right Tracks") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val (left, right) = projectLinks.partition(_.track == LeftSide)
      val endOfRoadLeft = left.init :+ left.last.copy(discontinuity = EndOfRoad)
      val endOfRoadRight = right.init :+ right.last.copy(discontinuity = EndOfRoad)
      val endOfRoadSet = endOfRoadLeft++endOfRoadRight
      ProjectValidator.checkRoadContinuityCodes(project, endOfRoadSet).distinct should have size 0
      val brokenContinuity = endOfRoadSet.tail :+ endOfRoadSet.head.copy(geometry = projectLinks.head.geometry.map(_ + Vector3d(1.0, 1.0, 0.0)), endMValue = 11L)
      val errors = ProjectValidator.checkRoadContinuityCodes(project, brokenContinuity).distinct
      errors should have size 1
      errors.head.validationError should be(MinorDiscontinuityFound)
    }
  }

  test("Tracks Combined only connecting (to least one of other Tracks) to LeftSide situation where validator should not return MinorDiscontinuity") {
    /*

                  catches discontinuity between Combined -> RightSide ? true => checks discontinuity between Combined -> LeftSide ? false => No error
                  catches discontinuity between Combined -> RightSide ? true => checks discontinuity between Combined -> LeftSide ? true => Error

                            Track 2
                       --------------->
                       ^
                       |
             Track 0   |
                       |    Track 1
                       |-------------->
                       ^
                       |
                       |
                       |
       */

    runWithRollback {
      val ra = Seq(
        //Combined
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39391L, 0.0, 0L)), Some(CalibrationPoint(39392L, 10.0, 10L))),
          floating = false, Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),

        //RightSide
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          10L, 20L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39393L, 0.0, 0L)), Some(CalibrationPoint(39394L, 10.0, 10L))),
          floating = false, Seq(Point(0.0, 0.0), Point(10.0, 0.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        //LeftSide
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.LeftSide, Discontinuity.EndOfRoad,
          10L, 20L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39395L, 0.0, 0L)), Some(CalibrationPoint(39396L, 10.0, 10L))),
          floating = false, Seq(Point(0.0, 10.0), Point(10.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      val combinedTrack = RoadAddressDAO.create(Set(ra.head), Some("U"))
      val rightleftTracks = RoadAddressDAO.create(ra.tail, Some("U"))
      val combinedAddresses = RoadAddressDAO.fetchByIdMassQuery(combinedTrack.toSet).sortBy(_.roadPartNumber)
      val rightleftAddresses = RoadAddressDAO.fetchByIdMassQuery(rightleftTracks.toSet).sortBy(_.roadPartNumber)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      ProjectDAO.createRoadAddressProject(project)
      ProjectDAO.reserveRoadPart(id, 19999L, 1L, "u")

      val projectLinksToCreate: Seq[ProjectLink] = (combinedAddresses++rightleftAddresses).map(toProjectLink(project))
      ProjectDAO.create(projectLinksToCreate)

      val validationErrors = ProjectValidator.checkRoadContinuityCodes(project,ProjectDAO.getProjectLinks(id)).distinct
      validationErrors.size should be(0)
    }
  }

  test("Tracks Combined only connecting (to least one of other Tracks) to RightSide situation where validator should not return MinorDiscontinuity") {
    /*

                  catches discontinuity between Combined -> LeftSide ? true => checks discontinuity between Combined -> RightSide ? false => No error
                  catches discontinuity between Combined -> LeftSide ? true => checks discontinuity between Combined -> RightSide ? true => Error

                        Track 1
                     <----------^
                                |
                                | Track 0
                       Track 2  |
                     <----------|
                                ^
                                |
                                |
       */

    runWithRollback {
      val ra = Seq(
        //Combined
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39391L, 0.0, 0L)), Some(CalibrationPoint(39392L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 0.0), Point(10.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),

        //RightSide
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          10L, 20L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39393L, 0.0, 0L)), Some(CalibrationPoint(39394L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 10.0), Point(0.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        //LeftSide
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.LeftSide, Discontinuity.EndOfRoad,
          10L, 20L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39395L, 0.0, 0L)), Some(CalibrationPoint(39396L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 0.0), Point(0.0, 0.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      val combinedTrack = RoadAddressDAO.create(Set(ra.head), Some("U"))
      val rightleftTracks = RoadAddressDAO.create(ra.tail, Some("U"))
      val combinedAddresses = RoadAddressDAO.fetchByIdMassQuery(combinedTrack.toSet).sortBy(_.roadPartNumber)
      val rightleftAddresses = RoadAddressDAO.fetchByIdMassQuery(rightleftTracks.toSet).sortBy(_.roadPartNumber)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      ProjectDAO.createRoadAddressProject(project)
      ProjectDAO.reserveRoadPart(id, 19999L, 1L, "u")

      val projectLinksToCreate: Seq[ProjectLink] = (combinedAddresses++rightleftAddresses).map(toProjectLink(project))
      ProjectDAO.create(projectLinksToCreate)

      val validationErrors = ProjectValidator.checkRoadContinuityCodes(project,ProjectDAO.getProjectLinks(id)).distinct
      validationErrors.size should be(0)
    }
  }

  test("Project Links should be discontinuous if geometry is discontinuous") {
    runWithRollback {
      val project = setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L))
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      val lastLinkPart = projectLinks.init :+ projectLinks.last.copy(discontinuity = Discontinuity.Continuous)
      val (road, part) = (lastLinkPart.last.roadNumber, lastLinkPart.last.roadPartNumber)

      val raId = RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
        0L, 10L, Some(DateTime.now()), None, None, 19999L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(19999L, 0.0, 0L)), Some(CalibrationPoint(19999L, 10.0, 10L))),
        floating = false, Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))).head

      val nextAddressPart = RoadAddressDAO.getValidRoadParts(road.toInt, project.startDate).filter(_ > part)
      val nextLinks = RoadAddressDAO.fetchByRoadPart(road, nextAddressPart.head, includeFloating = true)
        .filterNot(rp => projectLinks.exists(link => rp.roadPartNumber != link.roadPartNumber && rp.id == link.roadAddressId)).filter(_.startAddrMValue == 0L)

      /*
      1st case: |(x1)|---link A---|(x2)|  |(x2)|---link B---|(x3)|
      x1 < x2 <3
      A.discontinuity = Continuous
      A.sideCode = AgainstDigitizing =>   last point A x1
                                                         x1 != x2 => Discontinuity != A.discontinuity => error
      B.sideCode = TowardsDigitizing =>  first point B x2

       */
      //defining new growing geometry digitizing
      val links = projectLinks match {
        case Nil => Nil
        case ls :+ last => ls :+ last.copy(sideCode = AgainstDigitizing)
      }
      val errors = ProjectValidator.checkRoadContinuityCodes(project, links).distinct
      errors should have size 1
      errors.head.validationError should be(MajorDiscontinuityFound)

      /*
      2nd case: |(x2)|---link A---|(x1)|  |(x2)|---link B---|(x3)|
      x1 < x2 <3
      A.discontinuity = Continuous
      A.sideCode = AgainstDigitizing =>   last point A x2
                                                         x2 == x2 => Continuous === A.discontinuity => no error
      B.sideCode = TowardsDigitizing =>  first point B x2
      */
      val linksLastLinkGeomReversed = links match {
        case Nil => Nil
        case ls :+ last => ls :+ last.copy(geometry = last.geometry.reverse)
      }
      val errors2 = ProjectValidator.checkRoadContinuityCodes(project, linksLastLinkGeomReversed).distinct
      errors2 should have size 0

      /*
      3rd case: |(x1)|---link A---|(x2)|  |(x2)|---link B---|(x3)|
      x1 < x2 <3
      A.discontinuity = Discontinuous (can also be MinorDiscontinuity)
      A.sideCode = AgainstDigitizing =>   last point A x1
                                                         x1 != x2 => Discontinuous === A.discontinuity => no error
      B.sideCode = TowardsDigitizing =>  first point B x2
     */
      val linksDiscontinuousLastLink = links match {
        case Nil => Nil
        case l :+ last => l :+ last.copy(discontinuity = Discontinuity.Discontinuous)
      }
      val errors3 = ProjectValidator.checkRoadContinuityCodes(project, linksDiscontinuousLastLink).distinct
      errors3 should have size 0
    }
  }

  test("Project Links missing end of road should be caught") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      errors should have size 1
      errors.head.validationError should be(MissingEndOfRoad)
    }
  }

  test("Project Links must not have an end of road code if next part exists in project") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      ProjectDAO.reserveRoadPart(project.id, 1999L, 2L, "u")
      ProjectDAO.create(projectLinks.map(l => l.copy(id = NewRoadAddress, roadPartNumber = 2L, createdBy = Some("User"),
        geometry = l.geometry.map(_ + Vector3d(0.0, 40.0, 0.0)))))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val errors = ProjectValidator.checkRoadContinuityCodes(updProject, projectLinks).distinct
      ProjectDAO.getProjectLinks(project.id) should have size 8
      errors should have size 0
      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = ProjectValidator.checkRoadContinuityCodes(updProject,
        starting ++ last.map(_.copy(discontinuity = EndOfRoad))).distinct
      errorsUpd should have size 1
      errorsUpd.head.validationError should be(EndOfRoadNotOnLastPart)
    }
  }

  test("Project Links must not have an end of road code if next part exists in road address table") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      ProjectValidator.checkRoadContinuityCodes(project, projectLinks).distinct should have size 1
      RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 1999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
        floating = false, Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0)))
      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      errors should have size 0
      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = ProjectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = EndOfRoad))).distinct
      errorsUpd should have size 1
      errorsUpd.head.validationError should be(EndOfRoadNotOnLastPart)
    }
  }

  test("Project Links must have a major discontinuity code if and only if next part exists in road address / project link table and is not connected") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val raId = RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 1999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
        floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))).head
      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      errors should have size 1
      errors.head.validationError should be(MajorDiscontinuityFound)

      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = ProjectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Discontinuous))).distinct
      errorsUpd should have size 0

      RoadAddressDAO.updateGeometry(raId, Seq(Point(0.0, 40.0), Point(0.0, 50.0)))

      val connectedError = ProjectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Discontinuous))).distinct
      connectedError should have size 1
      connectedError.head.validationError should be(ConnectedDiscontinuousLink)
    }
  }
  //TODO to be done/changed in a more detailed story
  ignore("Project Links must have a ely change discontinuity code if next part is on different ely") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val raId = RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
        floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 9L, NoTermination, 0))).head
      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      errors should have size 1
      errors.head.validationError should be(ElyCodeChangeDetected)

      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = ProjectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.ChangingELYCode))).distinct
      errorsUpd should have size 0

      RoadAddressDAO.updateGeometry(raId, Seq(Point(0.0, 40.0), Point(0.0, 50.0)))

      val connectedError = ProjectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Continuous))).distinct
      connectedError should have size 1
      connectedError.head.validationError should be(ElyCodeChangeDetected)
    }
  }

  test("There should be validation error when there is minor discontinuity or discontinuity on a continuous road") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.MinorDiscontinuity,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
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

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged)).zip(roadAddress).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = ProjectDAO.getProjectLinks(updProject.id)
      val errors = ProjectValidator.checkProjectContinuity(updProject,currentProjectLinks).distinct
      errors should have size 1
      errors.head.validationError.value should be (ConnectedDiscontinuousLink.value)
    }
  }

  test("Check end of road with first part being continuous and not terminated in project with multiple parts (checkRemovedEndOfRoadParts method)") {
    //Now this validation returns 0 errors, because the previous road part is also reserved on the same project, and the error should not be TerminationContinuity, but MissingEndOfRoad
    //and that is not checked on checkRemovedEndOfRoadParts method
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
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

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated)).zip(roadAddress).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = ProjectDAO.getProjectLinks(updProject.id)
      val errors = ProjectValidator.checkRemovedEndOfRoadParts(updProject,currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Check end of road after first part being EndOfRoad and not terminated, and second being EndOfRoad but terminated in project with multiple parts (checkRemovedEndOfRoadParts method)") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
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

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated)).zip(roadAddress).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = ProjectDAO.getProjectLinks(updProject.id)
      val errors = ProjectValidator.checkRemovedEndOfRoadParts(updProject,currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Check end of road after first reserved part being Continuous and not terminated, and second reserved part being EndOfRoad but terminated in project with multiple parts (checkRemovedEndOfRoadParts method)") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
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

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated)).zip(roadAddress).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = ProjectDAO.getProjectLinks(updProject.id)
      val errors = ProjectValidator.validateProject(updProject,currentProjectLinks).distinct
      errors should have size 1
      errors.head.validationError.value should be (MissingEndOfRoad.value)
    }
  }

  test("Check end of road after first not reserved part being Continuous and not terminated, and second reserved part being EndOfRoad but terminated in project with multiple parts (checkRemovedEndOfRoadParts method)") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      val raIds = RoadAddressDAO.create(ra, Some("U"))
      val roadAddress = RoadAddressDAO.fetchByIdMassQuery(raIds.toSet).sortBy(_.roadPartNumber)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      ProjectDAO.createRoadAddressProject(project)
      ProjectDAO.reserveRoadPart(id, 19999L, 2L, "u")

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, project.id, LinkStatus.Terminated).copy(roadAddressId = raIds.last, roadNumber = ra.last.roadNumber, roadPartNumber = ra.last.roadPartNumber, discontinuity = ra.last.discontinuity)))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = ProjectDAO.getProjectLinks(updProject.id)
      val errors = ProjectValidator.validateProject(updProject, currentProjectLinks).distinct
      errors should have size 1
      errors.head.validationError.value should be (TerminationContinuity.value)
    }
  }

  test("reserve part 2 (which has EndOfRoad) and Terminate it. Reserve and Transfer part 1 to part 2 (with and without EndOfRoad)") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      val raIds = RoadAddressDAO.create(ra, Some("U"))
      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      ProjectDAO.createRoadAddressProject(project)
      ProjectDAO.reserveRoadPart(id, 19999L, 2L, "u")

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, project.id, LinkStatus.Terminated,ra.last.roadNumber, ra.last.roadPartNumber, discontinuity = EndOfRoad).copy(roadAddressId = raIds.last)))
      val currentProjectLinks = ProjectDAO.getProjectLinks(project.id)
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val error1 = ProjectValidator.validateProject(updProject,currentProjectLinks).distinct
      error1 should have size 1
      error1.head.validationError.value should be (TerminationContinuity.value)

      ProjectDAO.reserveRoadPart(id, 19999L, 1L, "u")
      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, project.id, LinkStatus.UnChanged).copy(roadAddressId = raIds.head, roadNumber = ra.head.roadNumber, roadPartNumber = ra.head.roadPartNumber)))
      val currentProjectLinks2 = ProjectDAO.getProjectLinks(project.id)
      val error2 = ProjectValidator.validateProject(updProject,currentProjectLinks2).distinct
      error2 should have size 1
      error2.head.validationError.value should be (MissingEndOfRoad.value)

      val updatedProjectLinks = Seq(currentProjectLinks2.filter(_.status == LinkStatus.UnChanged).head.copy(status = LinkStatus.Transfer, roadPartNumber = 2L, discontinuity = EndOfRoad))

      ProjectDAO.updateProjectLinksToDB(updatedProjectLinks, "U")
      val afterProjectLinks = ProjectDAO.getProjectLinks(project.id)
      val errors3 = ProjectValidator.validateProject(updProject,afterProjectLinks).distinct
      errors3 should have size 0
    }
  }

  test("reserve part 2 (which has EndOfRoad) and Terminate it. Create new part 2 (with and without EndOfRoad)"){

    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))

      val newRa = RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        0L, 10L, Some(DateTime.now()), None, None, 39400L, 0.0, 10.0, SideCode.Unknown, 0L,
        (Some(CalibrationPoint(39400L, 0.0, 0L)), Some(CalibrationPoint(39400L, 10.0, 10L))),
        floating = false, Seq(Point(10.0, 40.0), Point(20.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0)

      val raIds = RoadAddressDAO.create(ra, Some("U"))

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      ProjectDAO.createRoadAddressProject(project)
      ProjectDAO.reserveRoadPart(id, 19999L, 2L, "u")

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, project.id, LinkStatus.Terminated).copy(roadAddressId = raIds.last, roadNumber = ra.last.roadNumber, roadPartNumber = ra.last.roadPartNumber)))
      //add new link with same terminated road part (which had EndOfRoad)
      ProjectDAO.create(Seq(util.toProjectLink(project, LinkStatus.New)(newRa).copy(roadNumber = newRa.roadNumber, roadPartNumber = newRa.roadPartNumber)))

      val currentProjectLinks = ProjectDAO.getProjectLinks(project.id)
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get

      val error1 = ProjectValidator.validateProject(updProject,currentProjectLinks).distinct
      error1 should have size 1
      error1.head.validationError.value should be (MissingEndOfRoad.value)

      val updatedProjectLinks = Seq(currentProjectLinks.filter(_.status == LinkStatus.New).head.copy(roadPartNumber = 2L, discontinuity = EndOfRoad))

      ProjectDAO.updateProjectLinksToDB(updatedProjectLinks, "U")
      val currentProjectLinks2 = ProjectDAO.getProjectLinks(project.id)
      val error2 = ProjectValidator.validateProject(updProject,currentProjectLinks2).distinct
      error2 should have size 0
    }
  }

    test("reserve part 2 (which has EndOfRoad) and Terminate it. Create new part 3 (with and without EndOfRoad)"){
      runWithRollback {
        val ra = Seq(
          RoadAddress(NewRoadAddress, 20000L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
            0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
            (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
            floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
          RoadAddress(NewRoadAddress, 20000L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
            0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
            (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
            floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))

        val newRa = RoadAddress(NewRoadAddress, 20000L, 3L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 0L, Some(DateTime.now()), None, None, 39400L, 0.0, 10.0, SideCode.Unknown, 0L,
          (None, None),
          floating = false, Seq(Point(10.0, 40.0), Point(20.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0)

        val raIds = RoadAddressDAO.create(ra, Some("U"))

        val id = Sequences.nextViitePrimaryKeySeqValue
        val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
          "", Seq(), None, Some(8), None)
        ProjectDAO.createRoadAddressProject(project)
        ProjectDAO.reserveRoadPart(id, 20000L, 2L, "u")
        ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, project.id, LinkStatus.Terminated).copy(roadAddressId = raIds.last, roadNumber = ra.last.roadNumber, roadPartNumber = ra.last.roadPartNumber)))

        //add new link with same terminated road part (which had EndOfRoad)
        ProjectDAO.reserveRoadPart(id, 20000L, 3L, "u")
        val newpl = Seq(util.toProjectLink(project, LinkStatus.New)(newRa).copy(projectId = project.id, roadNumber = newRa.roadNumber, roadPartNumber = newRa.roadPartNumber))
        ProjectDAO.create(newpl)

        val currentProjectLinks = ProjectDAO.getProjectLinks(project.id)
        val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get

        val error1 = ProjectValidator.validateProject(updProject,currentProjectLinks).distinct
        error1 should have size 1
        error1.head.validationError.value should be (MissingEndOfRoad.value)

        val updatedProjectLinks = Seq(currentProjectLinks.filter(_.status == LinkStatus.New).head.copy(roadPartNumber = 2L, discontinuity = EndOfRoad))

        ProjectDAO.updateProjectLinksToDB(updatedProjectLinks, "U")
        val currentProjectLinks2 = ProjectDAO.getProjectLinks(project.id)
        val error2 = ProjectValidator.validateProject(updProject,currentProjectLinks2).distinct
        error2 should have size 0
      }
  }

  test("Terminate all links for all parts in a roadNumber") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
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

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated)).zip(roadAddress).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = ProjectDAO.getProjectLinks(updProject.id)
      val errors = ProjectValidator.validateProject(updProject,currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Check end of road with all parts being EndOfRoad and all terminated on project with multiple parts (checkRemovedEndOfRoadParts method)") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
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

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged)).zip(roadAddress).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = ProjectDAO.getProjectLinks(updProject.id)
      val errors = ProjectValidator.checkRemovedEndOfRoadParts(updProject,currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Check end of road after first part being EndOfRoad and terminated, and second being EndOfRoad and not terminated in project with multiple parts (checkRemovedEndOfRoadParts method)") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
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

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated)).zip(roadAddress).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = ProjectDAO.getProjectLinks(updProject.id)
      val errors = ProjectValidator.checkRemovedEndOfRoadParts(updProject,currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Check end of road with both parts EndOfRoad and both not terminated in project with multiple parts") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
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

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged)).zip(roadAddress).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = ProjectDAO.getProjectLinks(updProject.id)
      val errors = ProjectValidator.validateProject(updProject, currentProjectLinks).distinct
      errors should have size 1
      errors.head.affectedIds.head should be (currentProjectLinks.head.id)
      errors.head.validationError.value should be (EndOfRoadNotOnLastPart.value)
    }
  }

  test("Check end of road in different road numbers with both parts EndOfRod and both not terminated in project with multiple parts (checkRemovedEndOfRoadParts method)") {

    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      val raIds = RoadAddressDAO.create(ra, Some("U"))
      val roadAddress = RoadAddressDAO.fetchByIdMassQuery(raIds.toSet).sortBy(_.roadPartNumber)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      ProjectDAO.createRoadAddressProject(project)
      ProjectDAO.reserveRoadPart(id, 19998L, 1L, "u")
      ProjectDAO.reserveRoadPart(id, 19999L, 2L, "u")

      val projectLinks = Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged, roadNumber = 19998L, roadPartNumber = 1L),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged, roadNumber = 19999L, roadPartNumber = 2L)).zip(roadAddress).map(x => x._1.copy(roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity))
      ProjectDAO.create(projectLinks)
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = ProjectDAO.getProjectLinks(updProject.id)
      val errors = ProjectValidator.checkRemovedEndOfRoadParts(updProject,currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Ramps must have continuity validation") {
    runWithRollback {
      val project = util.setUpProjectWithRampLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks, isRampValidation = true).distinct
      errors should have size 0

      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = ProjectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Continuous)), isRampValidation = true).distinct
      errorsUpd should have size 1

      val errorsUpd2 = ProjectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.MinorDiscontinuity)), isRampValidation = true).distinct
      errorsUpd2 should have size 1

      val ra = Seq(
        RoadAddress(NewRoadAddress, 39998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, AgainstDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(2.0, 30.0), Point(0.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 39998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          10L, 20L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(2.0, 30.0), Point(7.0, 35.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 39998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          20L, 30L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
          floating = false, Seq(Point(7.0, 35.0), Point(0.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      RoadAddressDAO.create(ra)

      ProjectDAO.reserveRoadPart(project.id, 39999L, 20L, "u")
      ProjectDAO.create((starting ++ last.map(_.copy(discontinuity = Discontinuity.EndOfRoad)))
        .map(_.copy(id = NewRoadAddress, roadPartNumber = 20L, createdBy = Some("I"))))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      ProjectValidator.checkRoadContinuityCodes(updProject,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.MinorDiscontinuity)), isRampValidation = true).distinct should have size 0
    }
  }

  test("validator should produce an error on Not Handled links") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
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

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.NotHandled),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated)).zip(roadAddress).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
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
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          10L, 20L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
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

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.NotHandled),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Transfer)).zip(roadAddress1).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))
      ProjectDAO.create(Seq(util.projectLink(10L, 20L, Combined, id, LinkStatus.NotHandled),
        util.projectLink(10L, 20L, Combined, id, LinkStatus.UnChanged)).zip(roadAddress2).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))

      val projectLinks = ProjectDAO.getProjectLinks(id, Some(LinkStatus.NotHandled))
      val updatedProjectLinks = Seq(projectLinks.head.copy(status = LinkStatus.Transfer)) ++ projectLinks.tail.map(pl => pl.copy(status = LinkStatus.UnChanged))
      ProjectDAO.updateProjectLinksToDB(updatedProjectLinks, "U")
      val validationErrors = ProjectValidator.validateProject(project, ProjectDAO.getProjectLinks(project.id))

      validationErrors.size shouldNot be(0)
      validationErrors.count(_.validationError.value == ErrorInValidationOfUnchangedLinks.value) should be(1)
    }
  }

  test("validator should return invalid unchanged links error if is connected after any other action") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          10L, 20L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      val siirto = RoadAddressDAO.create(Set(ra.head), Some("U"))
      val unchanged = RoadAddressDAO.create(ra.tail, Some("U"))
      val roadAddress1 = RoadAddressDAO.fetchByIdMassQuery(siirto.toSet).sortBy(_.roadPartNumber)
      val roadAddress2 = RoadAddressDAO.fetchByIdMassQuery(unchanged.toSet).sortBy(_.roadPartNumber)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      ProjectDAO.createRoadAddressProject(project)
      ProjectDAO.reserveRoadPart(id, 19999L, 1L, "u")

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.NotHandled),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Transfer)).zip(roadAddress1).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))
      ProjectDAO.create(Seq(util.projectLink(10L, 20L, Combined, id, LinkStatus.NotHandled),
        util.projectLink(10L, 20L, Combined, id, LinkStatus.UnChanged)).zip(roadAddress2).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))

      val projectLinks = ProjectDAO.getProjectLinks(id, Some(LinkStatus.NotHandled))

      /*
      |---Transfer--->|---Unchanged--->|
       */
      val updatedProjectLinkToTransfer = Seq(projectLinks.head.copy(status = LinkStatus.Transfer, startAddrMValue = 10, endAddrMValue = 20)) ++ projectLinks.tail.map(pl => pl.copy(status = LinkStatus.UnChanged, startAddrMValue = 0, endAddrMValue = 10))
      ProjectDAO.updateProjectLinksToDB(updatedProjectLinkToTransfer, "U")
      val validationErrors1 = ProjectValidator.checkForInvalidUnchangedLinks(project, ProjectDAO.getProjectLinks(project.id))
      validationErrors1.size shouldNot be(0)
      validationErrors1.count(_.validationError.value == ErrorInValidationOfUnchangedLinks.value) should be(1)
      /*
       |---Numbering--->|---Unchanged--->|
        */
      val updatedProjectLinkToNumbering = Seq(projectLinks.head.copy(status = LinkStatus.Numbering, startAddrMValue = 10, endAddrMValue = 20))
      ProjectDAO.updateProjectLinksToDB(updatedProjectLinkToNumbering, "U")
      val validationErrors2 = ProjectValidator.checkForInvalidUnchangedLinks(project, ProjectDAO.getProjectLinks(project.id))
      validationErrors2.size shouldNot be(0)
      validationErrors2.count(_.validationError.value == ErrorInValidationOfUnchangedLinks.value) should be(1)
      /*
       |---Terminated--->|---Unchanged--->|
        */
      val updatedProjectLinkToTerminated = Seq(projectLinks.head.copy(status = LinkStatus.Terminated, startAddrMValue = 10, endAddrMValue = 20))
      ProjectDAO.updateProjectLinksToDB(updatedProjectLinkToTerminated, "U")
      val validationErrors3 = ProjectValidator.checkForInvalidUnchangedLinks(project, ProjectDAO.getProjectLinks(project.id))
      validationErrors3.size shouldNot be(0)
      validationErrors3.count(_.validationError.value == ErrorInValidationOfUnchangedLinks.value) should be(1)
    }
  }

  test("validator should return only one invalid unchanged link error even though there is some other error in links") {
    runWithRollback {
      val ra = Seq(
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          10L, 20L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
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

      ProjectDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.NotHandled),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Transfer)).zip(roadAddress1).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))
      ProjectDAO.create(Seq(util.projectLink(10L, 20L, Combined, id, LinkStatus.NotHandled),
        util.projectLink(20L, 30L, Combined, id, LinkStatus.UnChanged)).zip(roadAddress2).map(x => x._1.copy(roadPartNumber = x._2.roadPartNumber,
        roadAddressId = x._2.id, geometry = x._2.geometry, discontinuity = x._2.discontinuity)))

      val projectLinks = ProjectDAO.getProjectLinks(id, Some(LinkStatus.NotHandled))
      val updatedProjectLinks = Seq(projectLinks.head.copy(status = LinkStatus.Transfer)) ++ projectLinks.tail.map(pl => pl.copy(status = LinkStatus.UnChanged))
      ProjectDAO.updateProjectLinksToDB(updatedProjectLinks, "U")
      val validationErrors = ProjectValidator.validateProject(project, ProjectDAO.getProjectLinks(project.id))

      validationErrors.size should be(1)
      validationErrors.count(_.validationError.value == ErrorInValidationOfUnchangedLinks.value) should be(1)
    }
  }

  test("validator should not return invalid unchanged links error if endPoint of current (even if it is any action than Unchanged) is not connected to startPoint of next one (Unchanged)") {
    /*
                                        Transfer
                                    ---------------
                                    |             |
                       Unchanged    |             |
                   |--------------->              |
                                    ^             |
                                    |             v
                                    |--------------
                                       Transfer (this one should not give any error even if the next one is Unchanged)


   */

    runWithRollback {
      val ra = Seq(
        //Unchanged
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39391L, 0.0, 0L)), Some(CalibrationPoint(39392L, 10.0, 10L))),
          floating = false, Seq(Point(0.0, 10.0), Point(10.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),

        //Transfer
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          10L, 35L, Some(DateTime.now()), None, None, 39398L, 0.0, 25.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39393L, 0.0, 0L)), Some(CalibrationPoint(39394L, 10.0, 10L))),
          floating = false, Seq(Point(10.0, 10.0), Point(10.0, 15.0),Point(20.0, 15.0), Point(20.0, 0.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        //Transfer
        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          35L, 50L, Some(DateTime.now()), None, None, 39398L, 0.0, 15.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39395L, 0.0, 0L)), Some(CalibrationPoint(39396L, 10.0, 10L))),
          floating = false, Seq(Point(20.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      val unchanged = RoadAddressDAO.create(Set(ra.head), Some("U"))
      val transfer = RoadAddressDAO.create(ra.tail, Some("U"))
      val combinedAddresses = RoadAddressDAO.fetchByIdMassQuery(unchanged.toSet).sortBy(_.roadPartNumber)
      val rightleftAddresses = RoadAddressDAO.fetchByIdMassQuery(transfer.toSet).sortBy(_.roadPartNumber)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      ProjectDAO.createRoadAddressProject(project)
      ProjectDAO.reserveRoadPart(id, 19999L, 1L, "u")

      val projectLinksToCreate: Seq[ProjectLink] = (combinedAddresses++rightleftAddresses).map(toProjectLink(project))
      ProjectDAO.create(projectLinksToCreate)
      val projectLinks = ProjectDAO.getProjectLinks(id).sortBy(_.startAddrMValue)
      val updatedProjectLinks = Seq(projectLinks.head.copy(status = LinkStatus.UnChanged)) ++ projectLinks.tail.map(pl => pl.copy(status = LinkStatus.Transfer))
      ProjectDAO.updateProjectLinksToDB(updatedProjectLinks, "U")
      val validationErrors = ProjectValidator.checkForInvalidUnchangedLinks(project,ProjectDAO.getProjectLinks(id))
      validationErrors.size should be(0)
    }
  }

  test("validator should return errors if discontinuity is 3 and next road part ely is equal") {
    runWithRollback {
      testDataForElyTest01()
      val testRoad = {(16320L, 1L, "name")}
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.UnChanged, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = false, Seq(testRoad), Discontinuity.ChangingELYCode)

      val validationErrors = ProjectValidator.checkProjectElyCodes(project, projectLinks).distinct
      validationErrors.size should be(1)
      validationErrors.head.validationError.value should be(RoadNotEndingInElyBorder.value)
    }
  }

  test("validator should return errors if discontinuity is anything BUT 3 and next road part ely is different") {
    runWithRollback {
      testDataForElyTest02()
      val testRoad = {(27L, 19L, "name")}
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.UnChanged, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = false, Seq(testRoad), Discontinuity.Continuous, 12L)

      val validationErrors = ProjectValidator.checkProjectElyCodes(project, projectLinks).distinct
      validationErrors.size should be(1)
      validationErrors.head.validationError.value should be(RoadContinuesInAnotherEly.value)
    }
  }

  test("project track codes should be consistent") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val validationErrors = ProjectValidator.checkTrackCodePairing(project, projectLinks)
      validationErrors.size should be(0)
    }
  }

  test("project track codes inconsistent in midle of track") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val inconsistentLinks = projectLinks.map { l =>
        if (l.startAddrMValue == 20 && l.track == Track.RightSide)
          l.copy(track = Track.LeftSide)
        else l
      }
      val validationErrors = ProjectValidator.checkTrackCodePairing(project, inconsistentLinks).distinct
      validationErrors.size should be(1)
    }
  }

  test("project track codes inconsistent in extermities") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val inconsistentLinks = projectLinks.map { l =>
        if (l.startAddrMValue == 0 && l.track == Track.RightSide)
          l.copy(startAddrMValue = 5)
        else l
      }
      val validationErrors = ProjectValidator.checkTrackCodePairing(project, inconsistentLinks).distinct
      validationErrors.size should be(1)
    }
  }

  test("project track codes should be consistent when adding one simple link with track Combined") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L))
      val validationErrors = ProjectValidator.checkTrackCodePairing(project, projectLinks).distinct
      validationErrors.size should be(0)
    }
  }

  test("Minor discontinuous end ramp road between parts (of any kind) should not give error") {
    runWithRollback {
      val project = util.setUpProjectWithRampLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks, isRampValidation = true).distinct
      errors should have size 0
      val (starting, last) = projectLinks.splitAt(3)
      val ra = Seq(
        RoadAddress(NewRoadAddress, 39998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, AgainstDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(2.0, 30.0), Point(0.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 39998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          10L, 20L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
          floating = false, Seq(Point(2.0, 30.0), Point(7.0, 35.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 39998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          20L, 30L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
          (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
          floating = false, Seq(Point(7.0, 35.0), Point(0.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      RoadAddressDAO.create(ra)

      ProjectDAO.reserveRoadPart(project.id, 39999L, 20L, "u")
      ProjectDAO.create((starting ++ last.map(_.copy(discontinuity = Discontinuity.EndOfRoad)))
        .map(_.copy(id = NewRoadAddress, roadPartNumber = 20L, createdBy = Some("I"))))
      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
      ProjectValidator.checkRoadContinuityCodes(updProject,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.MinorDiscontinuity)), isRampValidation = true).distinct should have size 0
    }
  }

  test("Project Links could be both Minor discontinuity or Discontinuous if next part exists in road address / project link table and is not connected") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 1999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
        floating = false, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))).head
      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      errors should have size 1
      errors.head.validationError should be(MajorDiscontinuityFound)

      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = ProjectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Discontinuous))).distinct
      errorsUpd should have size 0

      val errorsUpd2 = ProjectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.MinorDiscontinuity))).distinct
      errorsUpd2 should have size 0
    }
  }

  test("On end of road part transfer validation should detect new road end") {
    runWithRollback {
      //Create road addresses
      val ids = RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
        floating = false, Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8L, NoTermination, 0),
        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
          floating = false, Seq(Point(0.0, 10.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8L, NoTermination, 0)))

      val project = setUpProjectWithLinks(LinkStatus.Transfer, Seq(0L,10L), discontinuity = Discontinuity.Continuous, roadAddressId = ids.min)
      ProjectDAO.reserveRoadPart(project.id, 19999L, 2L, "u")
      val addrMNew = Seq(0L,10L)
      val links = addrMNew.init.zip(addrMNew.tail).map { case (st, en) =>
        projectLink(st, en, Track.Combined, project.id, LinkStatus.Transfer, 19999L, 2L, Discontinuity.EndOfRoad, roadAddressId = ids.max)
      }
      ProjectDAO.create(links)
      val allLinks = ProjectDAO.getProjectLinks(project.id)
      val errors = allLinks.groupBy(l => (l.roadNumber, l.roadPartNumber)).flatMap(g => ProjectValidator.checkRoadContinuityCodes(project, g._2).distinct)
      errors.size should be (0)
      sqlu"""UPDATE PROJECT_LINK SET ROAD_PART_NUMBER = 1, STATUS = 3, START_ADDR_M = 10, END_ADDR_M = 20 WHERE ROAD_NUMBER = 19999 AND ROAD_PART_NUMBER = 2""".execute
      val linksAfterTransfer = ProjectDAO.getProjectLinks(project.id).sortBy(_.startAddrMValue)
      val errorsAfterTransfer = linksAfterTransfer.groupBy(l => (l.roadNumber, l.roadPartNumber)).flatMap(g => ProjectValidator.checkRoadContinuityCodes(project, g._2).distinct)
      linksAfterTransfer.head.connected(linksAfterTransfer.last) should be (false)
      errorsAfterTransfer.size should be (1)
      errorsAfterTransfer.head.validationError.value should be(MinorDiscontinuityFound.value)
    }
  }

  test("There should be a validation error when there is a road end on previous road part outside of project") {
    runWithRollback {
      RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 1999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
        floating = false, Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0)))
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(10L, 20L), roads = Seq((1999L, 2L, "Test road")), discontinuity = Discontinuity.EndOfRoad)
      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks)
      errors should have size 1
      errors.head.validationError.value should be(DoubleEndOfRoad.value)
    }
  }

  test("There should be a validation error when there is a road end on previous road part outside of project for 1 & 2 track codes") {
    runWithRollback {
      RoadAddressDAO.create(Seq(
        RoadAddress(NewRoadAddress, 1999L, 1L, RoadType.PublicRoad, Track.LeftSide, Discontinuity.EndOfRoad,
        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
        floating = false, Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0
      ),
        RoadAddress(NewRoadAddress, 1999L, 1L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
          floating = false, Seq(Point(5.0, 40.0), Point(5.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0
        )
      ))
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(10L, 20L), changeTrack = true, roads = Seq((1999L, 2L, "Test road")), discontinuity = Discontinuity.EndOfRoad)
      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks)
      errors should have size 1
      errors.head.validationError.value should be(DoubleEndOfRoad.value)
    }
  }

  test("Validator should return MissingEndOfRoad validation error if any of the track codes on the end of a part are not End Of Road") {
    runWithRollback {
      val roadAddresses = Seq(RoadAddress(NewRoadAddress, 1999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (None, None),
        floating = false, Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      val (project, _) = util.setUpProjectWithLinks(LinkStatus.New, Seq(10L, 20L), roads = Seq((1999L, 1L, "Test road")), discontinuity = Discontinuity.Continuous, changeTrack = true)
      ProjectDAO.create(Seq(util.toProjectLink(project, LinkStatus.New)(roadAddresses.head)))
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      val validationErrors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks)
      //Should have error in both tracks
      validationErrors.size should be(1)
      validationErrors.head.projectId should be(project.id)
      validationErrors.head.validationError.value should be(MissingEndOfRoad.value)
      validationErrors.head.affectedIds.sorted should be(projectLinks.filterNot(_.track == Track.Combined).map(_.id).sorted)
      //Should only have error in LEFT TRACK
      val leftErrors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks.map(pl => {
        if (pl.track == Track.RightSide)
          pl.copy(discontinuity = Discontinuity.EndOfRoad)
        else pl
      }))
      leftErrors.size should be(1)
      leftErrors.head.projectId should be(project.id)
      leftErrors.head.validationError.value should be(MissingEndOfRoad.value)
      leftErrors.head.affectedIds.sorted should be(projectLinks.filter(_.track == Track.LeftSide).map(_.id).sorted)
      //Should only have error in RIGHT TRACK
      val rightErrors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks.map(pl => {
        if (pl.track == Track.LeftSide)
          pl.copy(discontinuity = Discontinuity.EndOfRoad)
        else pl
      }))
      rightErrors.size should be(1)
      rightErrors.head.projectId should be(project.id)
      rightErrors.head.validationError.value should be(MissingEndOfRoad.value)
      rightErrors.head.affectedIds.sorted should be(projectLinks.filter(_.track == Track.RightSide).map(_.id).sorted)
      //Should have no error
      val noErrors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks.map(pl => {
        if (pl.track != Track.Combined)
          pl.copy(discontinuity = Discontinuity.EndOfRoad)
        else pl
      }))
      noErrors.size should be(0)
    }
  }


    test("Validator should return MajorDiscontinuity validation error if any of the track codes on the end of a part are not End Of Road") {
      runWithRollback {
      val roadAddresses = Seq(RoadAddress(NewRoadAddress, 1999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (None, None),
        floating = false, Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
      val (project, _) = util.setUpProjectWithLinks(LinkStatus.New, Seq(10L, 20L, 30L), roads = Seq((1999L, 1L, "Test road")), discontinuity = Discontinuity.Continuous, changeTrack = true)
      ProjectDAO.create(Seq(util.toProjectLink(project, LinkStatus.New)(roadAddresses.head)))
      ProjectDAO.reserveRoadPart(project.id, 1999L, 2L, "u")
      sqlu"""UPDATE Project_Link Set Road_part_Number = 2, Discontinuity_type = 1, start_addr_m = 0 , end_addr_m = 10 Where project_id = ${project.id} and end_addr_m = 30""".execute
      val projectLinks = ProjectDAO.getProjectLinks(project.id)
      val reservedParts = ProjectDAO.fetchReservedRoadParts(project.id)
      val projectWithReservations = project.copy(reservedParts = reservedParts)
      //Should NOT have error in both tracks
      val noErrors = ProjectValidator.checkRoadContinuityCodes(projectWithReservations, projectLinks)
      noErrors.size should be(0)

      //Should return MAJOR DISCONTINUITY to both Project Links, part number = 1
      val originalGeom = projectLinks.filter(_.roadPartNumber == 2L).head.geometry
      val discontinuousGeom = Seq(Point(40.0, 50.0), Point(60.0, 70.0))
      val discontinuousGeomString = "'" + toGeomString(Seq(Point(40.0, 50.0), Point(60.0, 70.0))) + "'"
      sqlu"""UPDATE PROJECT_LINK Set GEOMETRY = ${discontinuousGeomString} Where PROJECT_ID = ${project.id} AND ROAD_PART_NUMBER = 2""".execute
      val errorsAtEnd = ProjectValidator.checkRoadContinuityCodes(projectWithReservations, projectLinks.map(pl => {
        if (pl.roadPartNumber == 2L)
          pl.copyWithGeometry(discontinuousGeom)
        else pl
      }))
      errorsAtEnd.size should be(1)
      errorsAtEnd.head.validationError.value should be(MajorDiscontinuityFound.value)
      errorsAtEnd.head.affectedIds.sorted should be(projectLinks.filter(pl => pl.roadPartNumber == 1L && pl.track != Track.Combined).map(_.id).sorted)
    }
  }

  test("Validator should return validation error if there is End Of Road in the middle of road part") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L), discontinuity = Discontinuity.EndOfRoad)
      val errorLinks = projectLinks.map { l =>
        if (l.startAddrMValue == 10 )
          l.copy(discontinuity = Discontinuity.EndOfRoad)
        else l
      }
      val validationErrors = ProjectValidator.checkProjectContinuity(project, errorLinks.distinct)
      validationErrors.size should be(1)
      validationErrors.head.validationError.value should be (EndOfRoadMiddleOfPart.value)
    }
  }
}
