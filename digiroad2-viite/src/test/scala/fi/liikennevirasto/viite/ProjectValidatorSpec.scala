package fi.liikennevirasto.viite

import java.sql.Timestamp

import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{Combined, LeftSide, RightSide}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils, Point, Vector3d}
import fi.liikennevirasto.viite.RoadType.PublicRoad
import fi.liikennevirasto.viite.dao.Discontinuity.EndOfRoad
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao.{LinearLocationDAO, TerminationCode, _}
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import org.mockito.Matchers.any
import org.mockito.Mockito.{reset, when}
import org.mockito.stubbing.Answer
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
  val mockRoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val projectRoadAddressService = new RoadAddressService(mockRoadLinkService, new RoadwayDAO, new LinearLocationDAO,  new RoadNetworkDAO, mockRoadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val mockRoadAddressService = MockitoSugar.mock[RoadAddressService]

  val projectValidator = new ProjectValidator {
    override val roadAddressService = mockRoadAddressService
  }

  val projectService = new ProjectService(projectRoadAddressService, mockRoadLinkService, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)

  private val roadwayNumber1 = 1000000000l
  private val roadwayNumber2 = 2000000000l
  private val roadwayNumber3 = 3000000000l
  private val linearLocationId = 1

  private def projectLink(startAddrM: Long, endAddrM: Long, track: Track, projectId: Long, status: LinkStatus = LinkStatus.NotHandled,
                          roadNumber: Long = 19999L, roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L, linearLocationId: Long = 0L) = {
    ProjectLink(NewRoadway, roadNumber, roadPartNumber, track, discontinuity, startAddrM, endAddrM, startAddrM, endAddrM, None, None,
      Some("User"), startAddrM, 0.0, (endAddrM - startAddrM).toDouble, SideCode.TowardsDigitizing, (None, None),
      floating = NoFloating, Seq(Point(0.0, startAddrM), Point(0.0, endAddrM)), projectId, status, RoadType.PublicRoad,
      LinkGeomSource.NormalLinkInterface, (endAddrM - startAddrM).toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
  }

  def toProjectLink(project: RoadAddressProject)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(roadAddress.id, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track,
      roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate,
      roadAddress.endDate, createdBy =Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue,
      roadAddress.sideCode, roadAddress.toProjectLinkCalibrationPoints(), floating=NoFloating, roadAddress.geometry, project.id, LinkStatus.NotHandled, RoadType.PublicRoad,
      roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.id, roadAddress.linearLocationId, roadAddress.ely,false,
      None, roadAddress.adjustedTimestamp)
  }

  private def setUpProjectWithLinks(linkStatus: LinkStatus, addrM: Seq[Long], changeTrack: Boolean = false, roadNumber: Long = 19999L,
                                    roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L) = {
    val id = Sequences.nextViitePrimaryKeySeqValue

    def withTrack(t: Track): Seq[ProjectLink] = {
      addrM.init.zip(addrM.tail).map { case (st, en) =>
        projectLink(st, en, t, id, linkStatus, roadNumber, roadPartNumber, discontinuity, ely, roadwayId)
      }
    }

    val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
      "", Seq(), None, Some(8), None)
    projectDAO.createRoadAddressProject(project)
    val links =
      if (changeTrack) {
        withTrack(RightSide) ++ withTrack(LeftSide)
      } else {
        withTrack(Combined)
      }
    projectReservedPartDAO.reserveRoadPart(id, roadNumber, roadPartNumber, "u")
    projectLinkDAO.create(links)
    project
  }

  private def setUpProjectWithRampLinks(linkStatus: LinkStatus, addrM: Seq[Long]) = {
    val id = Sequences.nextViitePrimaryKeySeqValue
    val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
      "", Seq(), None, Some(8), None)
    projectDAO.createRoadAddressProject(project)
    val links = addrM.init.zip(addrM.tail).map { case (st, en) =>
      projectLink(st, en, Combined, id, linkStatus).copy(roadNumber = 39999)
    }
    projectReservedPartDAO.reserveRoadPart(id, 39999L, 1L, "u")
    projectLinkDAO.create(links.init :+ links.last.copy(discontinuity = EndOfRoad))
    project
  }

  def mockEmptyRoadAddressServiceCalls() = {
    when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
    when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
    when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
    when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
    when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
    when(mockRoadAddressService.getPreviousRoadAddressPart(any[Long], any[Long])).thenReturn(None)
  }

  test("Project Links should be continuous if geometry is continuous") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val endOfRoadSet = projectLinks.init :+ projectLinks.last.copy(discontinuity = EndOfRoad)

      mockEmptyRoadAddressServiceCalls()

      projectValidator.checkRoadContinuityCodes(project, endOfRoadSet, false).distinct should have size 0
      val brokenContinuity = endOfRoadSet.tail :+ endOfRoadSet.head.copy(geometry = projectLinks.head.geometry.map(_ + Vector3d(1.0, 1.0, 0.0)), endMValue = 11L)
      val errors = projectValidator.checkRoadContinuityCodes(project, brokenContinuity).distinct
      errors should have size 1
      errors.head.validationError should be(projectValidator.ValidationErrorList.MinorDiscontinuityFound)
    }
  }

  test("Project Links should be continuous if geometry is continuous for Left and Right Tracks") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val (left, right) = projectLinks.partition(_.track == LeftSide)
      val endOfRoadLeft = left.init :+ left.last.copy(discontinuity = EndOfRoad)
      val endOfRoadRight = right.init :+ right.last.copy(discontinuity = EndOfRoad)
      val endOfRoadSet = endOfRoadLeft++endOfRoadRight

      mockEmptyRoadAddressServiceCalls()

      projectValidator.checkRoadContinuityCodes(project, endOfRoadSet).distinct should have size 0
      val brokenContinuity = endOfRoadSet.tail :+ endOfRoadSet.head.copy(geometry = projectLinks.head.geometry.map(_ + Vector3d(1.0, 1.0, 0.0)), endMValue = 11L)
      val errors = projectValidator.checkRoadContinuityCodes(project, brokenContinuity).distinct
      errors should have size 1
      errors.head.validationError should be(projectValidator.ValidationErrorList.MinorDiscontinuityFound)
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
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 1999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId+1, roadwayNumber2, 1999L, 1L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          10L, 20L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //LeftSide
        Roadway(raId+2, roadwayNumber3, 1999L, 1L, RoadType.PublicRoad, Track.LeftSide, Discontinuity.EndOfRoad,
          10L, 20L, false, DateTime.now(), None, "test_user ", None, 8, NoTermination, startDate, None))

      val combinedTrack = roadwayDAO.create(Set(ra.head))
      val rightleftTracks = roadwayDAO.create(ra.tail)
      val linearLocationId = Sequences.nextLinearLocationId
      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
        (Some(0l), None), FloatingReason.NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface,
        roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
        (None, Some(20l)), FloatingReason.NoFloating, Seq(Point(0.0, 0.0), Point(10.0, 0.0)), LinkGeomSource.ComplimentaryLinkInterface,
        roadwayNumber2, Some(startDate), None),

        LinearLocation(linearLocationId+2, 1, 3000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
        (None, Some(20l)), FloatingReason.NoFloating, Seq(Point(0.0, 10.0), Point(10.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface,
        roadwayNumber3, Some(startDate), None)
      )
      linearLocationDAO.create(linearLocations)

      val combinedAddresses = roadwayDAO.fetchAllByRoadwayId(combinedTrack).sortBy(_.roadPartNumber)
      val rightleftAddresses = roadwayDAO.fetchAllByRoadwayId(rightleftTracks).sortBy(_.roadPartNumber)

      val roadwayLocation: Seq[(Roadway, Seq[LinearLocation])]= Seq(
        combinedAddresses.head -> Seq(linearLocations.head),
        rightleftAddresses.head -> Seq(linearLocations.tail.head),
        rightleftAddresses.last -> Seq(linearLocations.last))

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 1999L, 1L, "u")

      val roadAddress = roadwayLocation.flatMap(rl => roadwayAddressMapper.mapRoadAddresses(rl._1, rl._2))
      val projectLinksToCreate: Seq[ProjectLink] = roadAddress.map(toProjectLink(project))
      projectLinkDAO.create(projectLinksToCreate)

      mockEmptyRoadAddressServiceCalls()

      val validationErrors = projectValidator.checkRoadContinuityCodes(project, projectLinkDAO.getProjectLinks(id)).distinct
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
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),

        //RightSide
        Roadway(raId+1, roadwayNumber2, 19999L, 1L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          10L, 20L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //LeftSide
        Roadway(raId+2, roadwayNumber3, 19999L, 1L, RoadType.PublicRoad, Track.LeftSide, Discontinuity.EndOfRoad,
          10L, 20L, false, DateTime.now(), None, "test_user ", None, 8, NoTermination, startDate, None))

      val combinedTrack = roadwayDAO.create(Set(ra.head))
      val rightleftTracks = roadwayDAO.create(ra.tail)
      val linearLocationId = Sequences.nextLinearLocationId
      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), None), FloatingReason.NoFloating, Seq(Point(10.0, 0.0), Point(10.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (None, Some(20l)), FloatingReason.NoFloating, Seq(Point(10.0, 10.0), Point(0.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None),

        LinearLocation(linearLocationId+2, 1, 3000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (None, Some(20l)), FloatingReason.NoFloating,  Seq(Point(10.0, 0.0), Point(0.0, 0.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber3, Some(startDate), None)
      )
      linearLocationDAO.create(linearLocations)

      val combinedAddresses = roadwayDAO.fetchAllByRoadwayId(combinedTrack).sortBy(_.roadPartNumber)
      val rightleftAddresses = roadwayDAO.fetchAllByRoadwayId(rightleftTracks).sortBy(_.roadPartNumber)

      val roadwayLocation: Seq[(Roadway, Seq[LinearLocation])]= Seq(
        combinedAddresses.head -> Seq(linearLocations.head),
        rightleftAddresses.head -> Seq(linearLocations.tail.head),
        rightleftAddresses.last -> Seq(linearLocations.last))

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")

      val roadAddress = roadwayLocation.flatMap(rl => roadwayAddressMapper.mapRoadAddresses(rl._1, rl._2))
      val projectLinksToCreate: Seq[ProjectLink] = roadAddress.map(toProjectLink(project))
      projectLinkDAO.create(projectLinksToCreate)

      mockEmptyRoadAddressServiceCalls()

      val validationErrors = projectValidator.checkRoadContinuityCodes(project, projectLinkDAO.getProjectLinks(id)).distinct
      validationErrors.size should be(0)
    }
  }

  test("Project Links should be discontinuous if geometry is discontinuous") {
    runWithRollback {
      val startDate = DateTime.now()
      val project = setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L))
      val projectLinks = projectLinkDAO.getProjectLinks(project.id)
      val lastLinkPart = projectLinks.init :+ projectLinks.last.copy(discontinuity = Discontinuity.Continuous)
      val (road, part) = (lastLinkPart.last.roadNumber, lastLinkPart.last.roadPartNumber)

      val raId = Sequences.nextRoadwayId

      val roadway = Roadway(raId, roadwayNumber1, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
        0L, 10L, false, DateTime.parse("1901-01-01"), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
        (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.ComplimentaryLinkInterface,
        roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)
      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      /*
      1st case: |(x1)|---link A---|(x2)|  |(x2)|---link B---|(x3)|
      x1 < x2 <3
      A.discontinuity = Continuous
      A.sideCode = AgainstDigitizing =>   last point A x1
                                                         x1 != x2 => Discontinuity != A.discontinuity => error
      B.sideCode = TowardsDigitizing =>  first point B x2

       */
      //defining new growing geometry digitizing for link B
      val links = projectLinks match {
        case Nil => Nil
        case ls :+ last => ls :+ last.copy(sideCode = AgainstDigitizing)
      }
      val ra = Seq(
        RoadAddress(12345,1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(0.0, 10.0), Point(10.0, 20.0)) ,LinkGeomSource.NormalLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None)
      )

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(2l))
      when(mockRoadAddressService.getRoadAddressesFiltered(19999l, 2L)).thenReturn(ra)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadAddressPart(19999l, 1l)).thenReturn(None)

      val errors = projectValidator.checkRoadContinuityCodes(project, links).distinct
      errors should have size 1
      errors.head.validationError should be(projectValidator.ValidationErrorList.MajorDiscontinuityFound)

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

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(2l))
      when(mockRoadAddressService.getRoadAddressesFiltered(19999l, 2L)).thenReturn(ra)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadAddressPart(19999l, 1l)).thenReturn(None)
      val errors2 = projectValidator.checkRoadContinuityCodes(project, linksLastLinkGeomReversed).distinct
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
      val errors3 = projectValidator.checkRoadContinuityCodes(project, linksDiscontinuousLastLink).distinct
      errors3 should have size 0
    }
  }

  test("Project Links missing end of road should be caught") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getRoadAddressesFiltered(1999l, 1L)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadAddressPart(1999l, 1l)).thenReturn(None)
      val errors = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      errors should have size 1
      errors.head.validationError should be(projectValidator.ValidationErrorList.MissingEndOfRoad)
    }
  }

  test("Project Links must not have an end of road code if next part exists in project") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      projectReservedPartDAO.reserveRoadPart(project.id, 1999L, 2L, "u")
      projectLinkDAO.create(projectLinks.map(l => l.copy(id = NewRoadway, roadPartNumber = 2L, createdBy = Some("User"),
        geometry = l.geometry.map(_ + Vector3d(0.0, 40.0, 0.0)))))
      val updProject = projectDAO.getRoadAddressProjectById(project.id).get

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getRoadAddressesFiltered(1999L, 1L)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadAddressPart(1999L, 1l)).thenReturn(None)

      val errors = projectValidator.checkRoadContinuityCodes(updProject, projectLinks).distinct
      projectLinkDAO.getProjectLinks(project.id) should have size 8
      errors should have size 0
      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = projectValidator.checkRoadContinuityCodes(updProject,
        starting ++ last.map(_.copy(discontinuity = EndOfRoad))).distinct
      errorsUpd should have size 1
      errorsUpd.head.validationError should be(projectValidator.ValidationErrorList.EndOfRoadNotOnLastPart)
    }
  }

  test("Project Links must not have an end of road code if next part exists in road address table") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1l))
      when(mockRoadAddressService.getRoadAddressesFiltered(1999L, 1L)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadAddressPart(1999L, 1l)).thenReturn(None)

      val error = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      error should have size 1
      error.head.validationError should be(projectValidator.ValidationErrorList.MissingEndOfRoad)

      val ra = Seq(
        RoadAddress(12345,1, 1999L, 2L, RoadType.PublicRoad,Track.Combined, EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(0.0, 40.0), Point(0.0, 50.0)) ,LinkGeomSource.NormalLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None)
      )
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()

      val roadway = Roadway(raId, roadwayNumber1, 1999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
        0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
        (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
        roadwayNumber1, Some(startDate), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(1999L, 2L)).thenReturn(ra)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadAddressPart(1999L, 2l)).thenReturn(None)

      val errors = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      errors should have size 0
      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = projectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = EndOfRoad))).distinct
      errorsUpd should have size 1
      errorsUpd.head.validationError should be(projectValidator.ValidationErrorList.EndOfRoadNotOnLastPart)
    }
  }

  test("Project Links must have a major discontinuity code if and only if next part exists in road address / project link table and is not connected") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val ra = Seq(
        RoadAddress(12345,1, 1999L, 2L, RoadType.PublicRoad,Track.Combined, EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.NormalLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None)
      )

      val roadway = Roadway(raId, roadwayNumber1, 1999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
      0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
        (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
        roadwayNumber1, Some(startDate), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(1999L, 2L)).thenReturn(ra)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadAddressPart(1999L, 1l)).thenReturn(None)

      val errors = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      errors should have size 1
      errors.head.validationError should be(projectValidator.ValidationErrorList.MajorDiscontinuityFound)

      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = projectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Discontinuous))).distinct
      errorsUpd should have size 0

      //update geometry in order to make links be connected by geometry
      val ra2 = Seq(
        RoadAddress(12345,1, 1999L, 2L, RoadType.PublicRoad,Track.Combined, EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(0.0, 40.0), Point(0.0, 50.0)) ,LinkGeomSource.NormalLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None)
      )

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(1999L, 2L)).thenReturn(ra2)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadAddressPart(1999L, 1l)).thenReturn(None)

      val connectedError = projectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Discontinuous))).distinct
      connectedError should have size 1
      connectedError.head.validationError should be(projectValidator.ValidationErrorList.ConnectedDiscontinuousLink)
    }
  }

  test("There should be validation error when there is minor discontinuity or discontinuity on a continuous road") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.MinorDiscontinuity,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(DateTime.parse("1901-01-01")), None,None)
      )

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.MinorDiscontinuity,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId+1, roadwayNumber2, 19999L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged, 19999L, 1L, discontinuity = Discontinuity.MinorDiscontinuity, 8, 12345, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346, raId+1, linearLocationId+1).copy(geometry = roadAddresses.last.geometry)))

      val updProject = projectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.getProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.checkProjectContinuity(updProject,currentProjectLinks).distinct
      errors should have size 1
      errors.head.validationError.value should be (projectValidator.ValidationErrorList.ConnectedDiscontinuousLink.value)
    }
  }

  test("Check end of road with first part being continuous and not terminated in project with multiple parts (checkRemovedEndOfRoadParts method)") {
    //Now this validation returns 0 errors, because the previous road part is also reserved on the same project, and the error should not be TerminationContinuity, but MissingEndOfRoad
    //and that is not checked on checkRemovedEndOfRoadParts method
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(DateTime.parse("1901-01-01")), None,None)
      )

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId+1, roadwayNumber2, 19999L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged, 19999L, 1L, discontinuity = Discontinuity.Continuous, 8, 12345, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346, raId+1, linearLocationId+1).copy(geometry = roadAddresses.last.geometry)))

      val updProject = projectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.getProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.checkRemovedEndOfRoadParts(updProject,currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Check end of road after first part being EndOfRoad and not terminated, and second being EndOfRoad but terminated in project with multiple parts (checkRemovedEndOfRoadParts method)") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(DateTime.parse("1901-01-01")), None,None)
      )

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId+1, roadwayNumber2, 19999L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged, 19999L, 1L, discontinuity = Discontinuity.Continuous, 8, 12345, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346, raId+1, linearLocationId+1).copy(geometry = roadAddresses.last.geometry)))

      val updProject = projectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.getProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.checkRemovedEndOfRoadParts(updProject,currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Check end of road after first reserved part being Continuous and not terminated, and second reserved part being EndOfRoad but terminated in project with multiple parts (checkRemovedEndOfRoadParts method)") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(DateTime.parse("1901-01-01")), None,None)
      )

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId+1, roadwayNumber2, 19999L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged, 19999L, 1L, discontinuity = Discontinuity.Continuous, 8, 12345, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346, raId+1, linearLocationId+1).copy(geometry = roadAddresses.last.geometry)))

      val updProject = projectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.getProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.validateProject(updProject,currentProjectLinks).distinct
      errors should have size 1
      errors.head.validationError.value should be (projectValidator.ValidationErrorList.MissingEndOfRoad.value)
    }
  }

  test("Check end of road after first not reserved part being Continuous and not terminated, and second reserved part being EndOfRoad but terminated in project with multiple parts (checkRemovedEndOfRoadParts method)") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(DateTime.parse("1901-01-01")), None,None)
      )

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId+1, roadwayNumber2, 19999L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")
      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346, raId+1, linearLocationId+1).copy(geometry = roadAddresses.last.geometry)))

      val updProject = projectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.getProjectLinks(updProject.id)

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(roadAddresses.last))
      when(mockRoadAddressService.getPreviousRoadAddressPart(any[Long], any[Long])).thenReturn(Some(1L))

      val errors = projectValidator.validateProject(updProject, currentProjectLinks).distinct
      errors should have size 1
      errors.head.validationError.value should be (projectValidator.ValidationErrorList.TerminationContinuity.value)
    }
  }

  test("reserve part 2 (which has EndOfRoad) and Terminate it. Reserve and Transfer part 1 to part 2 (with and without EndOfRoad)") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(DateTime.parse("1901-01-01")), None,None)
      )

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId+1, roadwayNumber2, 19999L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, project.id, LinkStatus.Terminated,roadAddresses.last.roadNumber, roadAddresses.last.roadPartNumber, discontinuity = EndOfRoad).copy(roadwayId = ra.last.id)))
      val currentProjectLinks = projectLinkDAO.getProjectLinks(project.id)
      val updProject = projectDAO.getRoadAddressProjectById(project.id).get

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(roadAddresses.last))
      when(mockRoadAddressService.getPreviousRoadAddressPart(any[Long], any[Long])).thenReturn(Some(1L))

      val error1 = projectValidator.validateProject(updProject,currentProjectLinks).distinct
      error1 should have size 1
      error1.head.validationError.value should be (projectValidator.ValidationErrorList.TerminationContinuity.value)

      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, project.id, LinkStatus.UnChanged).copy(roadwayId = ra.head.id, roadNumber = roadAddresses.head.roadNumber, roadPartNumber = roadAddresses.head.roadPartNumber)))
      val currentProjectLinks2 = projectLinkDAO.getProjectLinks(project.id)

      mockEmptyRoadAddressServiceCalls()

      val error2 = projectValidator.validateProject(updProject,currentProjectLinks2).distinct
      error2 should have size 1
      error2.head.validationError.value should be (projectValidator.ValidationErrorList.MissingEndOfRoad.value)

      val updatedProjectLinks = Seq(currentProjectLinks2.filter(_.status == LinkStatus.UnChanged).head.copy(status = LinkStatus.Transfer, roadPartNumber = 2L, discontinuity = EndOfRoad))

      projectLinkDAO.updateProjectLinksToDB(updatedProjectLinks, "U")
      val afterProjectLinks =projectLinkDAO.getProjectLinks(project.id)

      mockEmptyRoadAddressServiceCalls()

      val errors3 = projectValidator.validateProject(updProject,afterProjectLinks).distinct
      errors3 should have size 0
    }
  }

  test("reserve part 2 (which has EndOfRoad) and Terminate it. Create new part 2 (with and without EndOfRoad)"){
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(DateTime.parse("1901-01-01")), None,None)
      )

      val newRa = RoadAddress(12347, linearLocationId+2, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 3000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
        Seq(Point(10.0, 40.0), Point(20.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None)

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId+1, roadwayNumber2, 19999L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, project.id, LinkStatus.Terminated).copy(roadwayId = ra.last.id, roadNumber = roadAddresses.last.roadNumber, roadPartNumber = roadAddresses.last.roadPartNumber)))
      //add new link with same terminated road part (which had EndOfRoad)
      projectLinkDAO.create(Seq(util.toProjectLink(project, LinkStatus.New)(newRa).copy(roadNumber = newRa.roadNumber, roadPartNumber = newRa.roadPartNumber)))

      val currentProjectLinks = projectLinkDAO.getProjectLinks(project.id)
      val updProject = projectDAO.getRoadAddressProjectById(project.id).get

      mockEmptyRoadAddressServiceCalls()

      val error1 = projectValidator.validateProject(updProject,currentProjectLinks).distinct
      error1 should have size 1
      error1.head.validationError.value should be (projectValidator.ValidationErrorList.MissingEndOfRoad.value)

      val updatedProjectLinks = Seq(currentProjectLinks.filter(_.status == LinkStatus.New).head.copy(roadPartNumber = 2L, discontinuity = EndOfRoad))

      projectLinkDAO.updateProjectLinksToDB(updatedProjectLinks, "U")
      val currentProjectLinks2 = projectLinkDAO.getProjectLinks(project.id)

      mockEmptyRoadAddressServiceCalls()

      val error2 = projectValidator.validateProject(updProject,currentProjectLinks2).distinct
      error2 should have size 0
    }
  }

    test("reserve part 2 (which has EndOfRoad) and Terminate it. Create new part 3 (with and without EndOfRoad)"){
      runWithRollback {

        val raId = Sequences.nextRoadwayId
        val startDate = DateTime.now()
        val linearLocationId = Sequences.nextLinearLocationId
        val roadAddresses = Seq(
          RoadAddress(12345, linearLocationId, 20000L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
            Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None),
          RoadAddress(12346, linearLocationId+1, 20000L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
            Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(DateTime.parse("1901-01-01")), None,None)
        )

        val newRa = RoadAddress(12347, linearLocationId+2, 20000L, 3L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 3000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(20.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None)

        val ra = Seq(
          //Combined
          Roadway(raId, roadwayNumber1, 20000L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
            0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
          //RightSide
          Roadway(raId+1, roadwayNumber2, 20000L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
            0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

        val linearLocations = Seq(
          LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
            (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
            roadwayNumber1, Some(startDate), None),

          LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
            (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
            roadwayNumber2, Some(startDate), None)
        )
        roadwayDAO.create(ra)
        linearLocationDAO.create(linearLocations)

        val id = Sequences.nextViitePrimaryKeySeqValue
        val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
          "", Seq(), None, Some(8), None)
        projectDAO.createRoadAddressProject(project)
        projectReservedPartDAO.reserveRoadPart(id, 20000L, 2L, "u")
        projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, project.id, LinkStatus.Terminated).copy(roadwayId = ra.last.id, roadNumber = ra.last.roadNumber, roadPartNumber = ra.last.roadPartNumber)))

        //add new link with same terminated road part (which had EndOfRoad)
        projectReservedPartDAO.reserveRoadPart(id, 20000L, 3L, "u")
        val newpl = Seq(util.toProjectLink(project, LinkStatus.New)(newRa).copy(projectId = project.id, roadNumber = newRa.roadNumber, roadPartNumber = newRa.roadPartNumber))
        projectLinkDAO.create(newpl)

        val currentProjectLinks = projectLinkDAO.getProjectLinks(project.id)
        val updProject = projectDAO.getRoadAddressProjectById(project.id).get

        mockEmptyRoadAddressServiceCalls()

        val error1 = projectValidator.validateProject(updProject,currentProjectLinks).distinct
        error1 should have size 1
        error1.head.validationError.value should be (projectValidator.ValidationErrorList.MissingEndOfRoad.value)

        val updatedProjectLinks = Seq(currentProjectLinks.filter(_.status == LinkStatus.New).head.copy(roadPartNumber = 2L, discontinuity = EndOfRoad))

        projectLinkDAO.updateProjectLinksToDB(updatedProjectLinks, "U")
        val currentProjectLinks2 = projectLinkDAO.getProjectLinks(project.id)

        mockEmptyRoadAddressServiceCalls()

        val error2 = projectValidator.validateProject(updProject,currentProjectLinks2).distinct
        error2 should have size 0
      }
  }

  test("Terminate all links for all parts in a roadNumber") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(DateTime.parse("1901-01-01")), None,None)
      )
      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId+1, roadwayNumber2, 19999L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")
      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated, 19999L, 1L, discontinuity = Discontinuity.Continuous, 8, 12345, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346, raId+1, linearLocationId+1).copy(geometry = roadAddresses.last.geometry)))

      val updProject = projectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.getProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.validateProject(updProject,currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Check end of road with all parts being EndOfRoad and all terminated on project with multiple parts (checkRemovedEndOfRoadParts method)") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(DateTime.parse("1901-01-01")), None,None)
      )
      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId+1, roadwayNumber2, 19999L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated, 19999L, 1L, discontinuity = Discontinuity.Continuous, 8, 12345, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346, raId+1, linearLocationId+1).copy(geometry = roadAddresses.last.geometry)))
      val updProject = projectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.getProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.checkRemovedEndOfRoadParts(updProject,currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Check end of road after first part being EndOfRoad and terminated, and second being EndOfRoad and not terminated in project with multiple parts (checkRemovedEndOfRoadParts method)") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(DateTime.parse("1901-01-01")), None,None)
      )
      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId+1, roadwayNumber2, 19999L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated, 19999L, 1L, discontinuity = Discontinuity.Continuous, 8, 12345, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346, raId+1, linearLocationId+1).copy(geometry = roadAddresses.last.geometry)))


      val updProject = projectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.getProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.checkRemovedEndOfRoadParts(updProject,currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Check end of road with both parts EndOfRoad and both not terminated in project with multiple parts") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(startDate),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(startDate), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(startDate),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(startDate), None,None)
      )
      val ra = Seq(
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId+1, roadwayNumber2, 19999L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged, 19999L, 1L, discontinuity = Discontinuity.EndOfRoad, 8, 12345, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346, raId+1, linearLocationId+1).copy(geometry = roadAddresses.last.geometry)))


      val updProject = projectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.getProjectLinks(updProject.id)

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1l, 2l))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(roadAddresses)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadAddressPart(19999l, 1l)).thenReturn(None)
      when(mockRoadAddressService.getPreviousRoadAddressPart(19999l, 2l)).thenReturn(Some(1l))

      val errors = projectValidator.validateProject(updProject, currentProjectLinks).distinct
      errors should have size 1
      errors.head.affectedIds.head should be (currentProjectLinks.head.id)
      errors.head.validationError.value should be (projectValidator.ValidationErrorList.EndOfRoadNotOnLastPart.value)
    }
  }

  test("Check end of road in different road numbers with both parts EndOfRod and both not terminated in project with multiple parts (checkRemovedEndOfRoadParts method)") {

    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19998L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(startDate),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(startDate), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(startDate),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(startDate), None,None)
      )
      val ra = Seq(
        Roadway(raId, roadwayNumber1, 19998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId+1, roadwayNumber2, 19999L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)
      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19998L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged, 19998L, 1L, discontinuity = Discontinuity.EndOfRoad, 8, 12345, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.UnChanged, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346, raId+1, linearLocationId+1).copy(geometry = roadAddresses.last.geometry)))

      val updProject = projectDAO.getRoadAddressProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.getProjectLinks(updProject.id)

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1l, 2l))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(roadAddresses)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadAddressPart(19999l, 1l)).thenReturn(None)
      when(mockRoadAddressService.getPreviousRoadAddressPart(19999l, 2l)).thenReturn(Some(1l))

      val errors = projectValidator.checkRemovedEndOfRoadParts(updProject,currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Ramps must have continuity validation") {
    runWithRollback {
      val project = util.setUpProjectWithRampLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
      val projectLinks = projectLinkDAO.getProjectLinks(project.id)

      mockEmptyRoadAddressServiceCalls()
      val errors = projectValidator.checkRoadContinuityCodes(project, projectLinks, isRampValidation = true).distinct
      errors should have size 0

      val (starting, last) = projectLinks.splitAt(3)

      mockEmptyRoadAddressServiceCalls()
      val errorsUpd = projectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Continuous)), isRampValidation = true).distinct
      errorsUpd should have size 1
      errorsUpd.head.validationError.value should be (projectValidator.ValidationErrorList.MissingEndOfRoad.value)

      mockEmptyRoadAddressServiceCalls()
      val errorsUpd2 = projectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.MinorDiscontinuity)), isRampValidation = true).distinct
      errorsUpd2 should have size 1
      errorsUpd2.head.validationError.value should be (projectValidator.ValidationErrorList.MissingEndOfRoad.value)

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 39998L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(startDate),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(2.0, 30.0), Point(0.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(startDate), None,None),
        RoadAddress(12346, linearLocationId+1, 39998L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,10, 20, Some(startDate),None, Some("User"), 1000, 10, 20, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(2.0, 30.0), Point(7.0, 35.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(startDate), None,None),
        RoadAddress(12347, linearLocationId+1, 39998L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,20, 30, Some(startDate),None, Some("User"), 2000, 20, 30, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(7.0, 35.0), Point(0.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(startDate), None,None)
      )
      val ra = Seq(
        Roadway(raId, roadwayNumber1, 39998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 30L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId+1, 2, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(2.0, 30.0), Point(7.0, 35.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId+2, 3, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(7.0, 35.0), Point(0.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      projectReservedPartDAO.reserveRoadPart(project.id, 39999L, 20L, "u")

      projectLinkDAO.create((starting ++ last.map(_.copy(discontinuity = Discontinuity.EndOfRoad)))
        .map(_.copy(id = NewRoadway, roadPartNumber = 20L, createdBy = Some("I"))))

      val updProject = projectDAO.getRoadAddressProjectById(project.id).get

      mockEmptyRoadAddressServiceCalls()

      projectValidator.checkRoadContinuityCodes(updProject,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.MinorDiscontinuity)), isRampValidation = true).distinct should have size 0
    }
  }

  test("validator should produce an error on Not Handled links") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous, 0, 10, Some(startDate),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(startDate), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 2L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,0, 10, Some(startDate),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(startDate), None,None)
      )
      val ra = Seq(
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId+1, roadwayNumber2, 19999L, 2L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Combined, id, LinkStatus.NotHandled, roadNumber = 19999L, 1L, discontinuity = Discontinuity.EndOfRoad, 8, 12345, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Combined, id, LinkStatus.Terminated, roadNumber = 19999L,2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346, raId+1, linearLocationId+1).copy(geometry = roadAddresses.last.geometry)))

      mockEmptyRoadAddressServiceCalls()

      val validationErrors = projectValidator.validateProject(project, projectLinkDAO.getProjectLinks(project.id)).filter(_.validationError.value == projectValidator.ValidationErrorList.HasNotHandledLinks.value)
      validationErrors.size should be(1)
      validationErrors.head.validationError.message should be("")
      validationErrors.head.optionalInformation should not be ("")
    }
  }

  test("validator should return invalid unchanged links error") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous, 0, 10, Some(startDate),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(startDate), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,10, 20, Some(startDate),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(startDate), None,None)
      )
      val ra = Seq(
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId+1, roadwayNumber2, 19999L, 1L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)


      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")

      projectLinkDAO.create(
        Seq(
          util.projectLink(0L, 10L, Combined, id, LinkStatus.NotHandled, roadNumber = 19999L, 1L, discontinuity = Discontinuity.Continuous, ely = 8L, linkId = 1000, roadwayId = ra.head.id).copy(geometry = roadAddresses.head.geometry),
          util.projectLink(0L, 10L, Combined, id, LinkStatus.Transfer, roadNumber = 19999L, 1L, discontinuity = Discontinuity.Continuous, ely = 8L, linkId = 1000, roadwayId = ra.head.id).copy(geometry = roadAddresses.head.geometry),
          util.projectLink(10L, 20L, Combined, id, LinkStatus.NotHandled, roadNumber = 19999L, 1L, discontinuity = Discontinuity.EndOfRoad, ely = 8L, linkId = 2000, roadwayId = ra.last.id).copy(geometry = roadAddresses.last.geometry),
          util.projectLink(10L, 20L, Combined, id, LinkStatus.UnChanged, roadNumber = 19999L, 1L, discontinuity = Discontinuity.EndOfRoad, ely = 8L, linkId = 2000, roadwayId = ra.last.id).copy(geometry = roadAddresses.last.geometry)
      ))

      val projectLinks = projectLinkDAO.getProjectLinks(id, Some(LinkStatus.NotHandled))
      val updatedProjectLinks = Seq(projectLinks.head.copy(status = LinkStatus.Transfer)) ++ projectLinks.tail.map(pl => pl.copy(status = LinkStatus.UnChanged))
      projectLinkDAO.updateProjectLinksToDB(updatedProjectLinks, "U")

      mockEmptyRoadAddressServiceCalls()

      val validationErrors = projectValidator.validateProject(project, projectLinkDAO.getProjectLinks(project.id))

      validationErrors.size shouldNot be(0)
      validationErrors.foreach(e => e.validationError.value should be (projectValidator.ValidationErrorList.ErrorInValidationOfUnchangedLinks.value))
    }
  }

  test("validator should return invalid unchanged links error if is connected after any other action") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous, 0, 10, Some(startDate),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(startDate), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous,10, 20, Some(startDate),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(startDate), None,None)
      )
      val ra = Seq(
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId+1, roadwayNumber2, 19999L, 1L, RoadType.PublicRoad, Track.RightSide, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)


      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")

      projectLinkDAO.create(
        Seq(
          util.projectLink(0L, 10L, Combined, id, LinkStatus.NotHandled, roadNumber = 19999L, 1L, discontinuity = Discontinuity.Continuous, ely = 8L, linkId = 1000, roadwayId = ra.head.id).copy(geometry = roadAddresses.head.geometry),
          util.projectLink(0L, 10L, Combined, id, LinkStatus.Transfer, roadNumber = 19999L, 1L, discontinuity = Discontinuity.Continuous, ely = 8L, linkId = 1000, roadwayId = ra.head.id).copy(geometry = roadAddresses.head.geometry),
          util.projectLink(10L, 20L, Combined, id, LinkStatus.NotHandled, roadNumber = 19999L, 1L, discontinuity = Discontinuity.Continuous, ely = 8L, linkId = 2000, roadwayId = ra.last.id).copy(geometry = roadAddresses.last.geometry),
          util.projectLink(10L, 20L, Combined, id, LinkStatus.UnChanged, roadNumber = 19999L, 1L, discontinuity = Discontinuity.Continuous, ely = 8L, linkId = 2000, roadwayId = ra.last.id).copy(geometry = roadAddresses.last.geometry)
        ))

      val projectLinks = projectLinkDAO.getProjectLinks(id, Some(LinkStatus.NotHandled))

      /*
      |---Transfer--->|---Unchanged--->|
       */
      val updatedProjectLinkToTransfer = Seq(projectLinks.head.copy(status = LinkStatus.Transfer, startAddrMValue = 10, endAddrMValue = 20)) ++ projectLinks.tail.map(pl => pl.copy(status = LinkStatus.UnChanged, startAddrMValue = 0, endAddrMValue = 10))
      projectLinkDAO.updateProjectLinksToDB(updatedProjectLinkToTransfer, "U")
      mockEmptyRoadAddressServiceCalls()
      val validationErrors1 = projectValidator.checkForInvalidUnchangedLinks(project, projectLinkDAO.getProjectLinks(project.id))
      validationErrors1.size shouldNot be(0)
      validationErrors1.foreach(e => e.validationError.value should be (projectValidator.ValidationErrorList.ErrorInValidationOfUnchangedLinks.value))
      /*
       |---Numbering--->|---Unchanged--->|
        */
      val updatedProjectLinkToNumbering = Seq(projectLinks.head.copy(status = LinkStatus.Numbering, startAddrMValue = 10, endAddrMValue = 20))
      projectLinkDAO.updateProjectLinksToDB(updatedProjectLinkToNumbering, "U")
      mockEmptyRoadAddressServiceCalls()
      val validationErrors2 = projectValidator.checkForInvalidUnchangedLinks(project, projectLinkDAO.getProjectLinks(project.id))
      validationErrors2.size shouldNot be(0)
      validationErrors2.foreach(e => e.validationError.value should be (projectValidator.ValidationErrorList.ErrorInValidationOfUnchangedLinks.value))
      /*
       |---Terminated--->|---Unchanged--->|
        */
      val updatedProjectLinkToTerminated = Seq(projectLinks.head.copy(status = LinkStatus.Terminated, startAddrMValue = 10, endAddrMValue = 20))
      projectLinkDAO.updateProjectLinksToDB(updatedProjectLinkToTerminated, "U")
      mockEmptyRoadAddressServiceCalls()
      val validationErrors3 = projectValidator.checkForInvalidUnchangedLinks(project, projectLinkDAO.getProjectLinks(project.id))
      validationErrors3.size shouldNot be(0)
      validationErrors3.foreach(e => e.validationError.value should be (projectValidator.ValidationErrorList.ErrorInValidationOfUnchangedLinks.value))
    }
  }

  test("validator should return only one invalid unchanged link error even though there is some other error in links") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous, 0, 10, Some(startDate),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 30.0), Point(10.0, 40.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(startDate), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.EndOfRoad,10, 20, Some(startDate),None, Some("User"), 2000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 40.0), Point(10.0, 50.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(startDate), None,None)
      )
      val ra = Seq(
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId+1, roadwayNumber2, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 1, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)


      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectLinkDAO.create(
        Seq(
          util.projectLink(0L, 10L, Combined, id, LinkStatus.NotHandled, roadNumber = 19999L, 1L, discontinuity = Discontinuity.Continuous, ely = 8L, linkId = 1000, roadwayId = ra.head.id).copy(geometry = roadAddresses.head.geometry),
          util.projectLink(0L, 10L, Combined, id, LinkStatus.Transfer, roadNumber = 19999L, 1L, discontinuity = Discontinuity.Continuous, ely = 8L, linkId = 1000, roadwayId = ra.head.id).copy(geometry = roadAddresses.head.geometry),
          util.projectLink(10L, 20L, Combined, id, LinkStatus.NotHandled, roadNumber = 19999L, 1L, discontinuity = Discontinuity.EndOfRoad, ely = 8L, linkId = 2000, roadwayId = ra.last.id).copy(geometry = roadAddresses.last.geometry),
          util.projectLink(10L, 20L, Combined, id, LinkStatus.UnChanged, roadNumber = 19999L, 1L, discontinuity = Discontinuity.EndOfRoad, ely = 8L, linkId = 2000, roadwayId = ra.last.id).copy(geometry = roadAddresses.last.geometry)
        ))

      val projectLinks = projectLinkDAO.getProjectLinks(id, Some(LinkStatus.NotHandled))
      val updatedProjectLinks = Seq(projectLinks.head.copy(status = LinkStatus.Transfer)) ++ projectLinks.tail.map(pl => pl.copy(status = LinkStatus.UnChanged))
      projectLinkDAO.updateProjectLinksToDB(updatedProjectLinks, "U")
      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.validateProject(project, projectLinkDAO.getProjectLinks(project.id))

      validationErrors.size should not be(0)
      validationErrors.foreach(e => e.validationError.value should be (projectValidator.ValidationErrorList.ErrorInValidationOfUnchangedLinks.value))
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

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous, 0, 10, Some(startDate),None, Some("User"), 1000, 0, 10, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(0.0, 10.0), Point(10.0, 10.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber1,Some(startDate), None,None),
        RoadAddress(12346, linearLocationId+1, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous,10, 30, Some(startDate),None, Some("User"), 1000, 10, 35, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(10.0, 10.0), Point(10.0, 15.0),Point(20.0, 15.0), Point(20.0, 0.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber2,Some(startDate), None,None),
        RoadAddress(12347, linearLocationId+1, 19999L, 1L, RoadType.PublicRoad,Track.Combined, Discontinuity.Continuous,30, 50, Some(startDate),None, Some("User"), 1000, 35, 50, TowardsDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(20.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0)) ,LinkGeomSource.ComplimentaryLinkInterface,8,NoTermination,roadwayNumber3,Some(startDate), None,None)
      )
      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
          0L, 50L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 10.0), Point(10.0, 15.0),Point(20.0, 15.0), Point(20.0, 0.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber1, Some(startDate), None),

        LinearLocation(linearLocationId+1, 2, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(10.0, 10.0), Point(10.0, 15.0),Point(20.0, 15.0), Point(20.0, 0.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber2, Some(startDate), None),

        LinearLocation(linearLocationId+3, 3, 2000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
          (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(20.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface,
          roadwayNumber3, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViitePrimaryKeySeqValue
      val project = RoadAddressProject(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), None, Some(8), None)
      projectDAO.createRoadAddressProject(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")

      val projectLinksToCreate: Seq[ProjectLink] = roadAddresses.map(toProjectLink(project)).map(_.copy(roadwayId = ra.head.id))
      projectLinkDAO.create(projectLinksToCreate)

      val projectLinks = projectLinkDAO.getProjectLinks(id).sortBy(_.startAddrMValue)
      val updatedProjectLinks = Seq(projectLinks.head.copy(status = LinkStatus.UnChanged)) ++ projectLinks.tail.map(pl => pl.copy(status = LinkStatus.Transfer))
      projectLinkDAO.updateProjectLinksToDB(updatedProjectLinks, "U")
      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkForInvalidUnchangedLinks(project, projectLinkDAO.getProjectLinks(id))
      validationErrors.size should be(0)
    }
  }

  test("validator should return errors if discontinuity is 3 and next road part ely is equal") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val ra = Seq(
        RoadAddress(12345,1, 16320L, 2L, RoadType.PublicRoad,Track.Combined, EndOfRoad,0, 10, Some(DateTime.parse("1901-01-01")),None, Some("User"), 1000, 0, 10, AgainstDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(0.0, 40.0), Point(0.0, 50.0)) ,LinkGeomSource.NormalLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None)
      )

      val roadway = Roadway(raId, roadwayNumber1, 16320L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        0L, 10L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000l, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000l,
        (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
        roadwayNumber1, Some(startDate), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val testRoad = {(16320L, 1L, "name")}
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.UnChanged, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = false, Seq(testRoad), Discontinuity.ChangingELYCode)

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(ra)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadAddressPart(any[Long], any[Long])).thenReturn(None)

      val validationErrors = projectValidator.checkProjectElyCodes(project, projectLinks).distinct
      validationErrors.size should be(1)
      validationErrors.head.validationError.value should be(projectValidator.ValidationErrorList.RoadNotEndingInElyBorder.value)
    }
  }

  test("validator should return errors if discontinuity is anything BUT 3 and next road part ely is different") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val linkId = 1817196L

      val ra = Seq(
        RoadAddress(12345,1, 27L, 20L, RoadType.PublicRoad,Track.Combined, EndOfRoad,6109L, 6559L, Some(DateTime.parse("1901-01-01")),None, Some("User"), linkId, 0, 10, AgainstDigitizing, DateTime.now().getMillis, (None, None),FloatingReason.NoFloating,
          Seq(Point(0.0, 40.0), Point(0.0, 50.0)) ,LinkGeomSource.NormalLinkInterface,8,NoTermination,roadwayNumber1,Some(DateTime.parse("1901-01-01")), None,None)
      )

      val roadway = Roadway(raId, roadwayNumber1, 27L, 20L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
        6109L, 6559L, false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None)

      val linearLocation = LinearLocation(linearLocationId, 1, linkId, 0.0, 450.0, SideCode.TowardsDigitizing, 10000000000l,
        (Some(0l), Some(10l)), FloatingReason.NoFloating, Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface,
        roadwayNumber1, Some(startDate), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val testRoad = {(27L, 19L, "name")}
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.UnChanged, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = false, Seq(testRoad), Discontinuity.Continuous, 12L)
      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(ra)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadAddressPart(any[Long], any[Long])).thenReturn(None)


      val validationErrors = projectValidator.checkProjectElyCodes(project, projectLinks).distinct
      validationErrors.size should be(1)
      validationErrors.head.validationError.value should be(projectValidator.ValidationErrorList.RoadContinuesInAnotherEly.value)
    }
  }

  test("project track codes should be consistent") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkTrackCodePairing(project, projectLinks)
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
      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkTrackCodePairing(project, inconsistentLinks).distinct
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

      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkTrackCodePairing(project, inconsistentLinks).distinct
      validationErrors.size should be(1)
    }
  }

  test("project track codes should be consistent when adding one simple link with track Combined") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L))
      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkTrackCodePairing(project, projectLinks).distinct
      validationErrors.size should be(0)
    }
  }

  //TODO this will be implemented at VIITE-1540
//  test("Minor discontinuous end ramp road between parts (of any kind) should not give error") {
//    runWithRollback {
//      val project = util.setUpProjectWithRampLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
//      val projectLinks = ProjectDAO.getProjectLinks(project.id)
//      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks, isRampValidation = true).distinct
//      errors should have size 0
//      val (starting, last) = projectLinks.splitAt(3)
//      val ra = Seq(
//        RoadAddress(NewRoadAddress, 39998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
//          0L, 10L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, AgainstDigitizing, 0L,
//          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
//          floating = NoFloating, Seq(Point(2.0, 30.0), Point(0.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
//        RoadAddress(NewRoadAddress, 39998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
//          10L, 20L, Some(DateTime.now()), None, None, 39398L, 0.0, 10.0, TowardsDigitizing, 0L,
//          (Some(CalibrationPoint(39398L, 0.0, 0L)), Some(CalibrationPoint(39398L, 10.0, 10L))),
//          floating = NoFloating, Seq(Point(2.0, 30.0), Point(7.0, 35.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0),
//        RoadAddress(NewRoadAddress, 39998L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
//          20L, 30L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L,
//          (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
//          floating = NoFloating, Seq(Point(7.0, 35.0), Point(0.0, 40.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
//      RoadAddressDAO.create(ra)
//
//      ProjectDAO.reserveRoadPart(project.id, 39999L, 20L, "u")
//      ProjectDAO.create((starting ++ last.map(_.copy(discontinuity = Discontinuity.EndOfRoad)))
//        .map(_.copy(id = NewRoadAddress, roadPartNumber = 20L, createdBy = Some("I"))))
//      val updProject = ProjectDAO.getRoadAddressProjectById(project.id).get
//      ProjectValidator.checkRoadContinuityCodes(updProject,
//        starting ++ last.map(_.copy(discontinuity = Discontinuity.MinorDiscontinuity)), isRampValidation = true).distinct should have size 0
//    }
//  }

  //TODO this will be implemented at VIITE-1540
//  test("Project Links could be both Minor discontinuity or Discontinuous if next part exists in road address / project link table and is not connected") {
//    runWithRollback {
//      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
//      RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
//        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
//        floating = NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))).head
//      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
//      errors should have size 1
//      errors.head.validationError should be(MajorDiscontinuityFound)
//
//      val (starting, last) = projectLinks.splitAt(3)
//      val errorsUpd = ProjectValidator.checkRoadContinuityCodes(project,
//        starting ++ last.map(_.copy(discontinuity = Discontinuity.Discontinuous))).distinct
//      errorsUpd should have size 0
//
//      val errorsUpd2 = ProjectValidator.checkRoadContinuityCodes(project,
//        starting ++ last.map(_.copy(discontinuity = Discontinuity.MinorDiscontinuity))).distinct
//      errorsUpd2 should have size 0
//    }
//  }

  //TODO this will be implemented at VIITE-1540
//  test("On end of road part transfer validation should detect new road end") {
//    runWithRollback {
//      //Create road addresses
//      val ids = RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
//        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
//        floating = NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8L, NoTermination, 0),
//        RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
//          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
//          floating = NoFloating, Seq(Point(0.0, 10.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8L, NoTermination, 0)))
//
//      val project = setUpProjectWithLinks(LinkStatus.Transfer, Seq(0L,10L), discontinuity = Discontinuity.Continuous, roadwayId = ids.min)
//      ProjectDAO.reserveRoadPart(project.id, 19999L, 2L, "u")
//      val addrMNew = Seq(0L,10L)
//      val links = addrMNew.init.zip(addrMNew.tail).map { case (st, en) =>
//        projectLink(st, en, Track.Combined, project.id, LinkStatus.Transfer, 19999L, 2L, Discontinuity.EndOfRoad, roadwayId = ids.max)
//      }
//      ProjectDAO.create(links)
//      val allLinks = ProjectDAO.getProjectLinks(project.id)
//      val errors = allLinks.groupBy(l => (l.roadNumber, l.roadPartNumber)).flatMap(g => ProjectValidator.checkRoadContinuityCodes(project, g._2).distinct)
//      errors.size should be (0)
//      sqlu"""UPDATE PROJECT_LINK SET ROAD_PART_NUMBER = 1, STATUS = 3, START_ADDR_M = 10, END_ADDR_M = 20 WHERE ROAD_NUMBER = 19999 AND ROAD_PART_NUMBER = 2""".execute
//      val linksAfterTransfer = ProjectDAO.getProjectLinks(project.id).sortBy(_.startAddrMValue)
//      val errorsAfterTransfer = linksAfterTransfer.groupBy(l => (l.roadNumber, l.roadPartNumber)).flatMap(g => ProjectValidator.checkRoadContinuityCodes(project, g._2).distinct)
//      linksAfterTransfer.head.connected(linksAfterTransfer.last) should be (false)
//      errorsAfterTransfer.size should be (1)
//      errorsAfterTransfer.head.validationError.value should be(MinorDiscontinuityFound.value)
//    }
//  }

  //TODO this will be implemented at VIITE-1540
//  test("There should be a validation error when there is a road end on previous road part outside of project") {
//    runWithRollback {
//      RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
//        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
//        floating = NoFloating, Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0)))
//      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(10L, 20L), roads = Seq((19999L, 2L, "Test road")), discontinuity = Discontinuity.EndOfRoad)
//      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks)
//      errors should have size 1
//      errors.head.validationError.value should be(DoubleEndOfRoad.value)
//    }
//  }

  //TODO this will be implemented at VIITE-1540
//  test("There should be a validation error when there is a road end on previous road part outside of project for 1 & 2 track codes") {
//    runWithRollback {
//      RoadAddressDAO.create(Seq(
//        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.LeftSide, Discontinuity.EndOfRoad,
//        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
//        floating = NoFloating, Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0
//      ),
//        RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.RightSide, Discontinuity.EndOfRoad,
//          0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
//          floating = NoFloating, Seq(Point(5.0, 40.0), Point(5.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0
//        )
//      ))
//      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(10L, 20L), changeTrack = true, roads = Seq((19999L, 2L, "Test road")), discontinuity = Discontinuity.EndOfRoad)
//      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks)
//      errors should have size 1
//      errors.head.validationError.value should be(DoubleEndOfRoad.value)
//    }
//  }

  //TODO this will be implemented at VIITE-1540
//  test("Validator should return MissingEndOfRoad validation error if any of the track codes on the end of a part are not End Of Road") {
//    runWithRollback {
//      val roadAddresses = Seq(RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
//        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (None, None),
//        floating = NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
//      val (project, _) = util.setUpProjectWithLinks(LinkStatus.New, Seq(10L, 20L), roads = Seq((19999L, 1L, "Test road")), discontinuity = Discontinuity.Continuous, changeTrack = true)
//      ProjectDAO.create(Seq(util.toProjectLink(project, LinkStatus.New)(roadAddresses.head)))
//      val projectLinks = ProjectDAO.getProjectLinks(project.id)
//      val validationErrors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks)
//      //Should have error in both tracks
//      validationErrors.size should be(1)
//      validationErrors.head.projectId should be(project.id)
//      validationErrors.head.validationError.value should be(MissingEndOfRoad.value)
//      validationErrors.head.affectedIds.sorted should be(projectLinks.filterNot(_.track == Track.Combined).map(_.id).sorted)
//      //Should only have error in LEFT TRACK
//      val leftErrors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks.map(pl => {
//        if (pl.track == Track.RightSide)
//          pl.copy(discontinuity = Discontinuity.EndOfRoad)
//        else pl
//      }))
//      leftErrors.size should be(1)
//      leftErrors.head.projectId should be(project.id)
//      leftErrors.head.validationError.value should be(MissingEndOfRoad.value)
//      leftErrors.head.affectedIds.sorted should be(projectLinks.filter(_.track == Track.LeftSide).map(_.id).sorted)
//      //Should only have error in RIGHT TRACK
//      val rightErrors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks.map(pl => {
//        if (pl.track == Track.LeftSide)
//          pl.copy(discontinuity = Discontinuity.EndOfRoad)
//        else pl
//      }))
//      rightErrors.size should be(1)
//      rightErrors.head.projectId should be(project.id)
//      rightErrors.head.validationError.value should be(MissingEndOfRoad.value)
//      rightErrors.head.affectedIds.sorted should be(projectLinks.filter(_.track == Track.RightSide).map(_.id).sorted)
//      //Should have no error
//      val noErrors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks.map(pl => {
//        if (pl.track != Track.Combined)
//          pl.copy(discontinuity = Discontinuity.EndOfRoad)
//        else pl
//      }))
//      noErrors.size should be(0)
//    }
//  }

  //TODO this will be implemented at VIITE-1540
//    test("Validator should return MajorDiscontinuity validation error if any of the track codes on the end of a part are not End Of Road") {
//      runWithRollback {
//      val roadAddresses = Seq(RoadAddress(NewRoadAddress, 19999L, 1L, RoadType.PublicRoad, Track.Combined, Discontinuity.Continuous,
//        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (None, None),
//        floating = NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.ComplimentaryLinkInterface, 8L, NoTermination, 0))
//      val (project, _) = util.setUpProjectWithLinks(LinkStatus.New, Seq(10L, 20L, 30L), roads = Seq((19999L, 1L, "Test road")), discontinuity = Discontinuity.Continuous, changeTrack = true)
//      ProjectDAO.create(Seq(util.toProjectLink(project, LinkStatus.New)(roadAddresses.head)))
//      ProjectDAO.reserveRoadPart(project.id, 19999L, 2L, "u")
//      sqlu"""UPDATE Project_Link Set Road_part_Number = 2, Discontinuity_type = 1, start_addr_m = 0 , end_addr_m = 10 Where project_id = ${project.id} and end_addr_m = 30""".execute
//      val projectLinks = ProjectDAO.getProjectLinks(project.id)
//      val reservedParts = ProjectDAO.fetchReservedRoadParts(project.id)
//      val projectWithReservations = project.copy(reservedParts = reservedParts)
//      //Should NOT have error in both tracks
//      val noErrors = ProjectValidator.checkRoadContinuityCodes(projectWithReservations, projectLinks)
//      noErrors.size should be(0)
//
//      //Should return MAJOR DISCONTINUITY to both Project Links, part number = 1
//      val originalGeom = projectLinks.filter(_.roadPartNumber == 2L).head.geometry
//      val discontinuousGeom = Seq(Point(40.0, 50.0), Point(60.0, 70.0))
//      val discontinuousGeomString = "'" + toGeomString(Seq(Point(40.0, 50.0), Point(60.0, 70.0))) + "'"
//      sqlu"""UPDATE PROJECT_LINK Set GEOMETRY = ${discontinuousGeomString} Where PROJECT_ID = ${project.id} AND ROAD_PART_NUMBER = 2""".execute
//      val errorsAtEnd = ProjectValidator.checkRoadContinuityCodes(projectWithReservations, projectLinks.map(pl => {
//        if (pl.roadPartNumber == 2L)
//          pl.copyWithGeometry(discontinuousGeom)
//        else pl
//      }))
//      errorsAtEnd.size should be(1)
//      errorsAtEnd.head.validationError.value should be(MajorDiscontinuityFound.value)
//      errorsAtEnd.head.affectedIds.sorted should be(projectLinks.filter(pl => pl.roadPartNumber == 1L && pl.track != Track.Combined).map(_.id).sorted)
//    }
//  }

  //TODO Will be implemented at VIITE-1540
//  test("Validator should return validation error if there is End Of Road in the middle of road part") {
//    runWithRollback {
//      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L), discontinuity = Discontinuity.EndOfRoad)
//      val errorLinks = projectLinks.map { l =>
//        if (l.startAddrMValue == 10 )
//          l.copy(discontinuity = Discontinuity.EndOfRoad)
//        else l
//      }
//      val validationErrors = ProjectValidator.checkProjectContinuity(project, errorLinks.distinct)
//      validationErrors.size should be(1)
//      validationErrors.head.validationError.value should be (EndOfRoadMiddleOfPart.value)
//    }
//  }

  //  //TODO to be done/changed in a more detailed story
  //  ignore("Project Links must have a ely change discontinuity code if next part is on different ely") {
  //    runWithRollback {
  //      val (project, projectLinks) = util.setUpProjectWithLinks(LinkStatus.New, Seq(0L, 10L, 20L, 30L, 40L))
  //      val raId = RoadAddressDAO.create(Seq(RoadAddress(NewRoadAddress, 19999L, 2L, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad,
  //        0L, 10L, Some(DateTime.now()), None, None, 39399L, 0.0, 10.0, TowardsDigitizing, 0L, (Some(CalibrationPoint(39399L, 0.0, 0L)), Some(CalibrationPoint(39399L, 10.0, 10L))),
  //        floating = NoFloating, Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplimentaryLinkInterface, 9L, NoTermination, 0))).head
  //      val errors = ProjectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
  //      errors should have size 1
  //      errors.head.validationError should be(ElyCodeChangeDetected)
  //
  //      val (starting, last) = projectLinks.splitAt(3)
  //      val errorsUpd = ProjectValidator.checkRoadContinuityCodes(project,
  //        starting ++ last.map(_.copy(discontinuity = Discontinuity.ChangingELYCode))).distinct
  //      errorsUpd should have size 0
  //
  //      RoadAddressDAO.updateGeometry(raId, Seq(Point(0.0, 40.0), Point(0.0, 50.0)))
  //
  //      val connectedError = ProjectValidator.checkRoadContinuityCodes(project,
  //        starting ++ last.map(_.copy(discontinuity = Discontinuity.Continuous))).distinct
  //      connectedError should have size 1
  //      connectedError.head.validationError should be(ElyCodeChangeDetected)
  //    }
  //  }
}
