package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.DigiroadEventBus
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.model.RoadAddressLink
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, GeometryUtils, Point, Vector3d}
import fi.vaylavirasto.viite.model.CalibrationPointType.NoCP
import fi.vaylavirasto.viite.model.{AdministrativeClass, Discontinuity, LinkGeomSource, RoadAddressChangeType, SideCode, Track}
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mockito.stubbing.OngoingStubbing
import org.scalatest.{FunSuite, Matchers}
import org.scalatest.mockito.MockitoSugar
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

class ProjectValidatorSpec extends FunSuite with Matchers {
  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadwayAddressMapper: RoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  private val mockRoadAddressService = MockitoSugar.mock[RoadAddressService]
  private val mockNodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  private val mockProjectReservedPartDAO = MockitoSugar.mock[ProjectReservedPartDAO]
  private val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodeDAO = new NodeDAO
  val nodePointDAO = new NodePointDAO
  val junctionPointDAO = new JunctionPointDAO
  val roadwayChangesDAO = new RoadwayChangesDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)

  val projectValidator = new ProjectValidator {
    override val roadAddressService = mockRoadAddressService
  }

  val projectService: ProjectService =
    new ProjectService(
                        mockRoadAddressService,
                        mockRoadLinkService,
                        mockNodesAndJunctionsService,
                        roadwayDAO,
                        roadwayPointDAO,
                        linearLocationDAO,
                        projectDAO,
                        projectLinkDAO,
                        nodeDAO,
                        nodePointDAO,
                        junctionPointDAO,
                        projectReservedPartDAO,
                        roadwayChangesDAO,
                        roadwayAddressMapper,
                        mockEventBus
                        ) {
                            override def withDynSession[T](f: => T): T = f
                            override def withDynTransaction[T](f: => T): T = f
                          }

  private val roadwayNumber1 = 1000000000L
  private val roadwayNumber2 = 2000000000L
  private val roadwayNumber3 = 3000000000L
  private val roadwayNumber4 = 4000000000L
  private val linearLocationId = 1

  private def projectLink(startAddrM: Long, endAddrM: Long, track: Track, projectId: Long, status: RoadAddressChangeType = RoadAddressChangeType.NotHandled,
                          roadNumber: Long = 19999L, roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L, linearLocationId: Long = 0L, plRoadwayNumber: Long = NewIdValue): ProjectLink = {
    val startDate = if (status !== RoadAddressChangeType.New) Some(DateTime.now()) else None
    ProjectLink(NewIdValue, roadNumber, roadPartNumber, track, discontinuity, startAddrM, endAddrM, startAddrM, endAddrM, startDate, None, Some("User"), startAddrM.toString, 0.0, (endAddrM - startAddrM).toDouble, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP), Seq(Point(0.0, startAddrM), Point(0.0, endAddrM)), projectId, status, AdministrativeClass.State, LinkGeomSource.NormalLinkInterface, (endAddrM - startAddrM).toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L, roadwayNumber = plRoadwayNumber)
  }

  def toProjectLink(project: Project)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(roadAddress.id, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track, roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate, roadAddress.endDate, createdBy = Option(project.createdBy), roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue, roadAddress.sideCode, roadAddress.calibrationPointTypes, (NoCP, NoCP), roadAddress.geometry, project.id, RoadAddressChangeType.NotHandled, AdministrativeClass.State, roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.id, roadAddress.linearLocationId, roadAddress.ely, reversed = false, None, roadAddress.adjustedTimestamp)
  }

  def toNewUnCalculated(pl: ProjectLink): ProjectLink = {
    pl.copy(status = RoadAddressChangeType.New, startAddrMValue = 0, endAddrMValue = 0, originalStartAddrMValue = 0, originalEndAddrMValue = 0)
  }

  def toRoadwayAndLinearLocation(p: ProjectLink): (LinearLocation, Roadway) = {
    val startDate = p.startDate.getOrElse(DateTime.now()).minusDays(1)

    (LinearLocation(-1000, 1, p.linkId, p.startMValue, p.endMValue, p.sideCode, p.linkGeometryTimeStamp,
      (CalibrationPointsUtils.toCalibrationPointReference(p.startCalibrationPoint),
        CalibrationPointsUtils.toCalibrationPointReference(p.endCalibrationPoint)),
      p.geometry, p.linkGeomSource,
      p.roadwayNumber, Some(startDate), p.endDate),
      Roadway(-1000, p.roadwayNumber, p.roadNumber, p.roadPartNumber, p.administrativeClass, p.track, p.discontinuity, p.startAddrMValue, p.endAddrMValue, p.reversed, startDate, p.endDate,
        p.createdBy.getOrElse("-"), p.roadName, p.ely, TerminationCode.NoTermination, DateTime.now(), None))
  }


  private def setUpProjectWithLinks(roadAddressChangeType: RoadAddressChangeType, addrM: Seq[Long], changeTrack: Boolean = false, roadNumber: Long = 19999L,
                                    roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L,
                                    lastLinkDiscontinuity: Discontinuity = Discontinuity.Continuous, withRoadInfo: Boolean = false) = {

    val id = Sequences.nextViiteProjectId

    val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
      "", Seq(), Seq(), None, None)
    projectDAO.create(project)
    addProjectLinksToProject(roadAddressChangeType, addrM, changeTrack, roadNumber, roadPartNumber, discontinuity, ely, roadwayId, lastLinkDiscontinuity, project, withRoadInfo)
    project
  }


  private def addProjectLinksToProject(roadAddressChangeType: RoadAddressChangeType, addrM: Seq[Long], changeTrack: Boolean = false, roadNumber: Long = 19999L,
                                       roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L,
                                       lastLinkDiscontinuity: Discontinuity = Discontinuity.Continuous, project: Project, withRoadInfo: Boolean = false, roadwayNumberValue: Long = NewIdValue): Project = {

    def withTrack(t: Track): Seq[ProjectLink] = {
      addrM.init.zip(addrM.tail).map { case (st, en) =>
        projectLink(st, en, t, project.id, roadAddressChangeType, roadNumber, roadPartNumber, discontinuity, ely, roadwayId, plRoadwayNumber = roadwayNumberValue)
      }
    }

    val links =
      if (changeTrack) {
        withTrack(Track.RightSide) ++ withTrack(Track.LeftSide)
      } else {
        withTrack(Track.Combined)
      }
    if (projectReservedPartDAO.fetchReservedRoadPart(roadNumber, roadPartNumber).isEmpty)
      projectReservedPartDAO.reserveRoadPart(project.id, roadNumber, roadPartNumber, "u")
    val newLinks = links.dropRight(1) ++ Seq(links.last.copy(discontinuity = lastLinkDiscontinuity))
    val newLinksWithRoadwayInfo = if (withRoadInfo) {
      val (ll, rw) = newLinks.map(toRoadwayAndLinearLocation).unzip
      linearLocationDAO.create(ll)
      roadwayDAO.create(rw)
      val roadways = newLinks.map(p => (p.roadNumber, p.roadPartNumber)).distinct.flatMap(p => roadwayDAO.fetchAllByRoadAndPart(p._1, p._2))
      newLinks.map(nl => {
        val roadway = roadways.find(r => r.roadNumber == nl.roadNumber && r.roadPartNumber == nl.roadPartNumber && r.startAddrMValue == nl.startAddrMValue && r.endAddrMValue == nl.endAddrMValue)
        if (roadway.nonEmpty) {
          nl.copy(roadwayId = roadway.get.id, roadwayNumber = roadway.get.roadwayNumber)
        }
        else nl
      })
    } else {
      newLinks
    }
    projectLinkDAO.create(newLinksWithRoadwayInfo)
    project
  }

  def mockEmptyRoadAddressServiceCalls(): OngoingStubbing[Option[Long]] = {
    when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
    when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
    when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
    when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
    when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
    when(mockRoadAddressService.getRoadAddressLinksByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[RoadAddressLink])
    when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(None)
  }

  test("Test checkRoadContinuityCodes When project links geometry are continuous Then Project Links should be continuous") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L))
      val endOfRoadSet = projectLinks.init :+ projectLinks.last.copy(discontinuity = Discontinuity.EndOfRoad)

      mockEmptyRoadAddressServiceCalls()

      projectValidator.checkRoadContinuityCodes(project, endOfRoadSet).distinct should have size 0
      val brokenContinuity = endOfRoadSet.tail :+ endOfRoadSet.head.copy(endMValue = 11L, geometry = projectLinks.head.geometry.map(_ + Vector3d(1.0, 1.0, 0.0)))
      val errors = projectValidator.checkRoadContinuityCodes(project, brokenContinuity).distinct
      errors should have size 1
      errors.head.validationError should be(projectValidator.ValidationErrorList.MinorDiscontinuityFound)
    }
  }

  test("Test checkRoadContinuityCodes When geometry is discontinuous Then Project Links should be discontinuous") {
    runWithRollback {
      val project = setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L))
      val projectLinks = projectLinkDAO.fetchProjectLinks(project.id)

      val raId = Sequences.nextRoadwayId

      val roadway = Roadway(raId, roadwayNumber1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.parse("1901-01-01"), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)
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
        case ls :+ last => ls :+ last.copy(sideCode = SideCode.AgainstDigitizing)
      }
      val ra = Seq(
        RoadAddress(12345, 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 10.0), Point(10.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(19999L, 2L)).thenReturn(ra)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(19999L, 1L)).thenReturn(None)

      val errors = projectValidator.checkRoadContinuityCodes(project, links).distinct
      errors should have size 1
      errors.head.validationError should be(projectValidator.ValidationErrorList.DiscontinuousFound)

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

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(19999L, 2L)).thenReturn(ra)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(19999L, 1L)).thenReturn(None)
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

  test("Test checkRoadContinuityCodes When project links geometry are continuous for Left and Right Tracks Then project links should continuous ") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val (left, right) = projectLinks.partition(_.track == Track.LeftSide)
      val endOfRoadLeft = left.init :+ left.last.copy(discontinuity = Discontinuity.EndOfRoad)
      val endOfRoadRight = right.init :+ right.last.copy(discontinuity = Discontinuity.EndOfRoad)
      val endOfRoadSet = endOfRoadLeft ++ endOfRoadRight

      mockEmptyRoadAddressServiceCalls()

      projectValidator.checkRoadContinuityCodes(project, endOfRoadSet).distinct should have size 0
      val brokenContinuity = endOfRoadSet.tail :+ endOfRoadSet.head.copy(endMValue = 11L, geometry = projectLinks.head.geometry.map(_ + Vector3d(1.0, 1.0, 0.0)))
      val errors = projectValidator.checkRoadContinuityCodes(project, brokenContinuity).distinct
      errors should have size 1
      errors.head.validationError should be(projectValidator.ValidationErrorList.MinorDiscontinuityFound)
    }
  }

  test("Test checkRoadContinuityCodes When Tracks Combined only connecting (to at least one of other Tracks) to LeftSide situation Then validator should not return MinorDiscontinuity") {
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
                       |
                       |
                       |
                       |
       */

    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 1999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, 1999L, 1L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 10L, 20L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //LeftSide
        Roadway(raId + 2, roadwayNumber3, 1999L, 1L, AdministrativeClass.State, Track.LeftSide, Discontinuity.EndOfRoad, 10L, 20L, reversed = false, DateTime.now(), None, "test_user ", None, 8, NoTermination, startDate, None))

      val combinedTrack = roadwayDAO.create(Set(ra.head))
      val rightleftTracks = roadwayDAO.create(ra.tail)
      val linearLocationId = Sequences.nextLinearLocationId
      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference.None),  Seq(Point(0.0,  0.0), Point( 0.0, 10.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference(Some(20L))), Seq(Point(0.0,  5.0), Point(10.0,  5.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None),
        LinearLocation(linearLocationId + 2, 1, 3000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference(Some(20L))), Seq(Point(0.0, 10.0), Point(10.0, 10.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber3, Some(startDate), None)
      )
      linearLocationDAO.create(linearLocations)

      val combinedAddresses = roadwayDAO.fetchAllByRoadwayId(combinedTrack).sortBy(_.roadPartNumber)
      val rightleftAddresses = roadwayDAO.fetchAllByRoadwayId(rightleftTracks).sortBy(_.roadPartNumber)

      val roadwayLocation: Seq[(Roadway, Seq[LinearLocation])] = Seq(
        combinedAddresses.head -> Seq(linearLocations.head),
        rightleftAddresses.head -> Seq(linearLocations.tail.head),
        rightleftAddresses.last -> Seq(linearLocations.last))

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 1999L, 1L, "u")

      val roadAddress = roadwayLocation.flatMap(rl => roadwayAddressMapper.mapRoadAddresses(rl._1, rl._2))
      val projectLinksToCreate: Seq[ProjectLink] = roadAddress.map(toProjectLink(project))
      projectLinkDAO.create(projectLinksToCreate)

      mockEmptyRoadAddressServiceCalls()

      val validationErrors = projectValidator.checkRoadContinuityCodes(project, projectLinkDAO.fetchProjectLinks(id)).distinct
      validationErrors.size should be(0)
    }
  }

  test("Test checkRoadContinuityCodes When Tracks Combined only connecting (to at least one of other Tracks) to RightSide situation Then validator should not return MinorDiscontinuity") {
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
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),

        //RightSide
        Roadway(raId + 1, roadwayNumber2, 19999L, 1L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 10L, 20L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //LeftSide
        Roadway(raId + 2, roadwayNumber3, 19999L, 1L, AdministrativeClass.State, Track.LeftSide, Discontinuity.EndOfRoad, 10L, 20L, reversed = false, DateTime.now(), None, "test_user ", None, 8, NoTermination, startDate, None))

      val combinedTrack = roadwayDAO.create(Set(ra.head))
      val rightleftTracks = roadwayDAO.create(ra.tail)
      val linearLocationId = Sequences.nextLinearLocationId
      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference.None),  Seq(Point(10.0,  0.0), Point(10.0, 10.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference(Some(20L))), Seq(Point(10.0, 10.0), Point( 0.0, 10.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None),
        LinearLocation(linearLocationId + 2, 1, 3000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference(Some(20L))), Seq(Point(10.0,  0.0), Point( 0.0,  0.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber3, Some(startDate), None)
      )
      linearLocationDAO.create(linearLocations)

      val combinedAddresses = roadwayDAO.fetchAllByRoadwayId(combinedTrack).sortBy(_.roadPartNumber)
      val rightleftAddresses = roadwayDAO.fetchAllByRoadwayId(rightleftTracks).sortBy(_.roadPartNumber)

      val roadwayLocation: Seq[(Roadway, Seq[LinearLocation])] = Seq(
        combinedAddresses.head -> Seq(linearLocations.head),
        rightleftAddresses.head -> Seq(linearLocations.tail.head),
        rightleftAddresses.last -> Seq(linearLocations.last))

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")

      val roadAddress = roadwayLocation.flatMap(rl => roadwayAddressMapper.mapRoadAddresses(rl._1, rl._2))
      val projectLinksToCreate: Seq[ProjectLink] = roadAddress.map(toProjectLink(project))
      projectLinkDAO.create(projectLinksToCreate)

      mockEmptyRoadAddressServiceCalls()

      val validationErrors = projectValidator.checkRoadContinuityCodes(project, projectLinkDAO.fetchProjectLinks(id)).distinct
      validationErrors.size should be(0)
    }
  }

  test("Test checkRoadContinuityCodes " +
       "When a new road and road part with MinorDiscontinuity is not calculated and has no MinorDiscontinuity set" +
       "Then validator should return MinorDiscontinuity") {
    runWithRollback {
      val user           = "TestUser"
      val roadNumber     = 10000
      val roadPartNumber = 1
      val project_id     = -1000
      val project        = Project(project_id, ProjectState.Incomplete, "f", user, DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)
      val projectLinks   = Seq(
        ProjectLink(1001,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,0,0,0,None,None,Some(user),5175001.toString,0.0, 72.789,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536938.0,6984394.0,0.0), Point(536865.0,6984398.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality,LinkGeomSource.FrozenLinkInterface, 72.789,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1002,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,0,0,0,None,None,Some(user),5174998.toString,0.0, 84.091,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536781.0,6984396.0,0.0), Point(536865.0,6984398.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality,LinkGeomSource.FrozenLinkInterface, 84.091,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1004,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,0,0,0,None,None,Some(user),5174545.toString,0.0, 89.803,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536684.0,6984513.0,0.0), Point(536773.0,6984525.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality,LinkGeomSource.FrozenLinkInterface, 89.803,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1005,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,0,0,0,None,None,Some(user),5174996.toString,0.0,138.959,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536694.0,6984375.0,0.0), Point(536684.0,6984513.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality,LinkGeomSource.FrozenLinkInterface,138.959,0,0,8,false,None,1551999616000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1006,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,0,0,0,None,None,Some(user),5175004.toString,0.0, 41.233,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536653.0,6984373.0,0.0), Point(536694.0,6984375.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality,LinkGeomSource.FrozenLinkInterface, 41.233,0,0,8,false,None,1551999616000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1007,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,0,0,0,None,None,Some(user),5174936.toString,0.0,117.582,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536535.0,6984364.0,0.0), Point(536653.0,6984373.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality,LinkGeomSource.FrozenLinkInterface,117.582,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1008,roadNumber,roadPartNumber,Track.Combined,Discontinuity.EndOfRoad, 0,0,0,0,None,None,Some(user),5174956.toString,0.0, 75.055,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536535.0,6984364.0,0.0), Point(536528.0,6984439.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality,LinkGeomSource.FrozenLinkInterface, 75.055,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None)
      )
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(project_id, roadNumber, roadPartNumber, user)
      projectLinkDAO.create(projectLinks)
      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getPreviousRoadPartNumber(roadNumber, roadPartNumber)).thenReturn(None)
      val validationErrors = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      validationErrors should have size 1
      validationErrors.head.validationError should be(projectValidator.ValidationErrorList.MinorDiscontinuityFound)
    }
  }

  test("Test checkRoadContinuityCodes " +
       "When a new road and road part with MinorDiscontinuity is not calculated and has MinorDiscontinuity set" +
       "Then validator should return no errors.") {
    runWithRollback {
      val user           = "TestUser"
      val roadNumber     = 10000
      val roadPartNumber = 1
      val project_id     = -1000
      val project        = Project(project_id, ProjectState.Incomplete, "f", user, DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)
      val projectLinks   = Seq(
        ProjectLink(1001,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,        0,0,0,0,None,None,Some(user),5175001.toString,0.0,72.789,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536938.0,6984394.0,0.0), Point(536865.0,6984398.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,72.789,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1002,roadNumber,roadPartNumber,Track.Combined,Discontinuity.MinorDiscontinuity,0,0,0,0,None,None,Some(user),5174998.toString,0.0,84.091,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536781.0,6984396.0,0.0), Point(536865.0,6984398.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,84.091,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1004,roadNumber,roadPartNumber,Track.Combined,Discontinuity.EndOfRoad,         0,0,0,0,None,None,Some(user),5174545.toString,0.0,89.803,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536684.0,6984513.0,0.0), Point(536773.0,6984525.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,89.803,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None)
      )
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(project_id, roadNumber, roadPartNumber, user)
      projectLinkDAO.create(projectLinks)
      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getPreviousRoadPartNumber(roadNumber, roadPartNumber)).thenReturn(None)
      val validationErrors = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      validationErrors should have size 0
    }
  }

  test("Test checkRoadContinuityCodes " +
       "When a new road and road part with MinorDiscontinuity is not calculated and has MinorDiscontinuity set to a false link" +
       "Then validator should return an error.") {
    runWithRollback {
      val user           = "TestUser"
      val roadNumber     = 10000
      val roadPartNumber = 1
      val project_id     = -1000
      val project        = Project(project_id, ProjectState.Incomplete, "f", user, DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)
      val projectLinks   = Seq(
        ProjectLink(1001,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,        0,0,0,0,None,None,Some(user),5175001.toString,0.0, 72.789,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536938.0,6984394.0,0.0), Point(536865.0,6984398.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 72.789,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1002,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,        0,0,0,0,None,None,Some(user),5174998.toString,0.0, 84.091,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536781.0,6984396.0,0.0), Point(536865.0,6984398.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 84.091,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1004,roadNumber,roadPartNumber,Track.Combined,Discontinuity.MinorDiscontinuity,0,0,0,0,None,None,Some(user),5174545.toString,0.0, 89.803,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536684.0,6984513.0,0.0), Point(536773.0,6984525.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 89.803,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1005,roadNumber,roadPartNumber,Track.Combined,Discontinuity.EndOfRoad,         0,0,0,0,None,None,Some(user),5174996.toString,0.0,138.959,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536694.0,6984375.0,0.0), Point(536684.0,6984513.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,138.959,0,0,8,false,None,1551999616000L,0,Some("testroad"),None,None,None,None,None,None)
      )
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(project_id, roadNumber, roadPartNumber, user)
      projectLinkDAO.create(projectLinks)
      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getPreviousRoadPartNumber(roadNumber, roadPartNumber)).thenReturn(None)
      val validationErrors = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      validationErrors should have size 1
    }
  }

  test("Test checkRoadContinuityCodes " +
       "When a new road and road part is not calculated and has no Discontinuity set on the last link" +
       "Then validator should return MissingEndOfRoad") {
    runWithRollback {
    val user           = "TestUser"
    val roadNumber     = 10000
    val roadPartNumber = 1
    val project_id     = -1000
    val project        = Project(project_id, ProjectState.Incomplete, "f", user, DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)
    val projectLinks   = Seq(
      ProjectLink(1000,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,0,0,0,None,None,Some(user),5174997.toString,0.0,152.337,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536938.0,6984394.0,0.0), Point(536926.0,6984546.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,152.337,0,0,8,false,None,1548802841000L,0,Some("testroad"),None,None,None,None,None,None),
      ProjectLink(1001,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,0,0,0,None,None,Some(user),5175001.toString,0.0, 72.789,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536938.0,6984394.0,0.0), Point(536865.0,6984398.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 72.789,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
      ProjectLink(1002,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,0,0,0,None,None,Some(user),5174998.toString,0.0, 84.091,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536781.0,6984396.0,0.0), Point(536865.0,6984398.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 84.091,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None)
    )
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(project_id, roadNumber, roadPartNumber, user)
      projectLinkDAO.create(projectLinks)
      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getPreviousRoadPartNumber(roadNumber, roadPartNumber)).thenReturn(None)
      val validationErrors = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      validationErrors should have size 1
      validationErrors.head.validationError should be(projectValidator.ValidationErrorList.MissingEndOfRoad)
    }
  }

  test("Test checkRoadContinuityCodes " +
       "When a new road and road part is not calculated and has Discontinuity set on the last link" +
       "Then validator should no errors.") {
    runWithRollback {
      val user           = "TestUser"
      val roadNumber     = 10000
      val roadPartNumber = 1
      val project_id     = -1000
      val project        = Project(project_id, ProjectState.Incomplete, "f", user, DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)
      val projectLinks   = Seq(
        ProjectLink(1007,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,0,0,0,None,None,Some(user),5174936.toString,0.0,117.582,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536535.0,6984364.0,0.0), Point(536653.0,6984373.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface,117.582,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1008,roadNumber,roadPartNumber,Track.Combined,Discontinuity.Continuous,0,0,0,0,None,None,Some(user),5174956.toString,0.0, 75.055,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536535.0,6984364.0,0.0), Point(536528.0,6984439.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 75.055,0,0,8,false,None,1500418814000L,0,Some("testroad"),None,None,None,None,None,None),
        ProjectLink(1009,roadNumber,roadPartNumber,Track.Combined,Discontinuity.EndOfRoad, 0,0,0,0,None,None,Some(user),5174584.toString,0.0, 45.762,SideCode.Unknown,(NoCP,NoCP),(NoCP,NoCP),List(Point(536528.0,6984439.0,0.0), Point(536522.0,6984484.0,0.0)),project_id,RoadAddressChangeType.New,AdministrativeClass.Municipality, LinkGeomSource.FrozenLinkInterface, 45.762,0,0,8,false,None,1551999616000L,0,Some("testroad"),None,None,None,None,None,None)
      )
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(project_id, roadNumber, roadPartNumber, user)
      projectLinkDAO.create(projectLinks)
      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getPreviousRoadPartNumber(roadNumber, roadPartNumber)).thenReturn(None)
      val validationErrors = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      validationErrors.size should be(0)
    }
  }

  test("Test checkRoadContinuityCodes When there is Project Links without End of Road Then MissingEndOfRoad should be caught") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L))
      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getRoadAddressesFiltered(1999L, 1L)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(1999L, 1L)).thenReturn(None)
      val errors = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      errors should have size 1
      errors.head.validationError should be(projectValidator.ValidationErrorList.MissingEndOfRoad)
    }
  }

  test("Test checkRoadContinuityCodes When next part exists in project Then Project Links must not have an end of road code in previous part") {
    runWithRollback {
      val nextViiteId = Sequences.nextViitePrimaryKeySeqValue
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L))
      projectReservedPartDAO.reserveRoadPart(project.id, 1999L, 2L, "u")
      projectLinkDAO.create(projectLinks.map(l => l.copy(id = NewIdValue, roadPartNumber = 2L, createdBy = Some("User"), geometry = l.geometry.map(_ + Vector3d(0.0, 40.0, 0.0)))))
      val projectReservedPart = Seq(ProjectReservedPart(nextViiteId, 1999L, 2L, None, None, None, None, None, None, None))
      when(mockProjectReservedPartDAO.fetchProjectReservedRoadPartsByProjectId(project.id)).thenReturn(projectReservedPart)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(1999L, 1L, false, false, false)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(1999L, 2L, false, false, false)).thenReturn(Seq.empty[RoadAddress])
      val updProject = projectService.fetchProjectById(project.id).get
      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getRoadAddressesFiltered(1999L, 1L)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(1999L, 1L)).thenReturn(None)

      val errors = projectValidator.checkRoadContinuityCodes(updProject, projectLinks).distinct
      projectLinkDAO.fetchProjectLinks(project.id) should have size 8
      errors should have size 0
      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = projectValidator.checkRoadContinuityCodes(updProject,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.EndOfRoad))).distinct
      errorsUpd should have size 1
      errorsUpd.head.validationError should be(projectValidator.ValidationErrorList.EndOfRoadNotOnLastPart)
    }
  }

  test("Test checkRoadContinuityCodes When next part exists in road address table Then Project Links must not have an end of road code") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L))
      when(mockRoadAddressService.getRoadAddressesFiltered(1999L, 1L)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(1999L, 1L)).thenReturn(None)

      val error = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      error should have size 1
      error.head.validationError should be(projectValidator.ValidationErrorList.MissingEndOfRoad)

      val ra = Seq(
        RoadAddress(12345, 1, 1999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()

      val roadway = Roadway(raId, roadwayNumber1, 1999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(1999L, 2L)).thenReturn(ra)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(1999L, 2L)).thenReturn(None)

      val errors = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      errors should have size 0

      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = projectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.EndOfRoad))).distinct
      errorsUpd should have size 1
      errorsUpd.head.validationError should be(projectValidator.ValidationErrorList.EndOfRoadNotOnLastPart)
    }
  }

  test("Test checkRoadContinuityCodes When next part exists in road address / project link table and is not connected Then Project Links must have a Discontinuity.Discontinuous code") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L))
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val ra = Seq(
        RoadAddress(12345, 1, 1999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val roadway = Roadway(raId, roadwayNumber1, 1999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(1999L, 2L)).thenReturn(ra)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(1999L, 1L)).thenReturn(None)

      val errors = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      errors should have size 1
      errors.head.validationError should be(projectValidator.ValidationErrorList.DiscontinuousFound)

      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = projectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Discontinuous))).distinct
      errorsUpd should have size 0

      //update geometry in order to make links be connected by geometry
      val ra2 = Seq(
        RoadAddress(12345, 1, 1999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(1999L, 2L)).thenReturn(ra2)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(1999L, 1L)).thenReturn(None)

      val connectedError = projectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Discontinuous))).distinct
      connectedError should have size 1
      connectedError.head.validationError should be(projectValidator.ValidationErrorList.ConnectedDiscontinuousLink)
    }
  }

  test("Test checkProjectContinuity When there is minor discontinuity or discontinuity on a continuous road Then should be validation error ConnectedDiscontinuousLink") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.MinorDiscontinuity, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,          0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.MinorDiscontinuity, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Unchanged, 19999L, 1L, discontinuity = Discontinuity.MinorDiscontinuity, 8, 12345.toString, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Unchanged, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346.toString, raId + 1, linearLocationId + 1).copy(geometry = roadAddresses.last.geometry)))


      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L,false, false, false)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 2L,false, false, false)).thenReturn(Seq.empty[RoadAddress])
      val updProject = projectService.fetchProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.fetchProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.checkProjectContinuity(updProject, currentProjectLinks).distinct
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.ConnectedDiscontinuousLink.value)
    }
  }

  test("Test validateProject When Checking end of road with first part being continuous and not terminated in project with multiple parts Then should not return TerminationContinuity error (value 18)") {
    //Returns 1 error, because the previous road part is also reserved on the same project, and the error should not be TerminationContinuity, but MissingEndOfRoad
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,  0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)
      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Unchanged, 19999L, 1L, discontinuity = Discontinuity.Continuous, 8, 12345.toString, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Termination, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346.toString, raId + 1, linearLocationId + 1).copy(geometry = roadAddresses.last.geometry)))

      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L, false, false, false)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 2L, false, false, false)).thenReturn(Seq.empty[RoadAddress])
      val updProject = projectService.fetchProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.fetchProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.validateProject(updProject, currentProjectLinks).distinct
      errors.filter(_.validationError.value == projectValidator.ValidationErrorList.TerminationContinuity.value) should have size 0
    }
  }

  test("Test checkRemovedEndOfRoadParts When Checking end of road after first part being EndOfRoad and not terminated, and second being EndOfRoad but terminated in project with multiple parts Then should not return any error") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Unchanged, 19999L, 1L, discontinuity = Discontinuity.Continuous, 8, 12345.toString, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Termination, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346.toString, raId + 1, linearLocationId + 1).copy(geometry = roadAddresses.last.geometry)))

      when(mockRoadAddressService.getValidRoadAddressParts(19999L, project.startDate)).thenReturn(Seq(1L))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L,false, false, false)).thenReturn(roadAddresses.init)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L,false, true,  false)).thenReturn(roadAddresses.init)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 2L,false, false, false)).thenReturn(roadAddresses.tail)

      val updProject = projectService.fetchProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.fetchProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.validateProject(updProject, currentProjectLinks).distinct
      errors.filter(_.validationError.value == projectValidator.ValidationErrorList.TerminationContinuity.value) should have size 0
    }
  }

  test("Test validateProject When Checking end of road after first reserved part being Continuous and not terminated, and second reserved part being EndOfRoad but terminated in project with multiple parts Then should return MissingEndOfRoad error") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,  0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Unchanged, 19999L, 1L, discontinuity = Discontinuity.Continuous, 8, 12345.toString, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Termination, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346.toString, raId + 1, linearLocationId + 1).copy(geometry = roadAddresses.last.geometry)))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L, false, false, false)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 2L, false, false, false)).thenReturn(Seq.empty[RoadAddress])

      val updProject = projectService.fetchProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.fetchProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.validateProject(updProject, currentProjectLinks).distinct
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.MissingEndOfRoad.value)
    }
  }

  test("Test validateProject When Checking end of road after first not reserved part being Continuous and not terminated, and second reserved part being EndOfRoad but terminated in project with multiple parts Then should return TerminationContinuity error") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,  0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")
      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Termination, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346.toString, raId + 1, linearLocationId + 1).copy(geometry = roadAddresses.last.geometry)))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L, false, false, false)).thenReturn(Seq(roadAddresses.head))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L, false, true,  false)).thenReturn(Seq(roadAddresses.head))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 2L, false, false, false)).thenReturn(Seq(roadAddresses.last))

      val updProject = projectService.fetchProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.fetchProjectLinks(updProject.id)

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L, withHistory = false, fetchOnlyEnd = false)).thenReturn(Seq(roadAddresses.head))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 2L, withHistory = false, fetchOnlyEnd = false)).thenReturn(Seq(roadAddresses.last))

      val errors = projectValidator.validateProject(updProject, currentProjectLinks).distinct
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.TerminationContinuity.value)
    }
  }

  test("Test validateProject When reserving part 2 (which has EndOfRoad) and Terminate it, Reserve and Transfer part 1 to part 2 (with and without EndOfRoad) Then different results should be returned") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,  0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, project.id, RoadAddressChangeType.Termination, roadAddresses.last.roadNumber, roadAddresses.last.roadPartNumber, discontinuity = Discontinuity.EndOfRoad).copy(roadwayId = ra.last.id)))
      val currentProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L, false, false, false)).thenReturn(Seq(roadAddresses.head))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L, false, true,  false)).thenReturn(Seq(roadAddresses.head))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 2L, false, false, false)).thenReturn(Seq(roadAddresses.last))

      val updProject = projectService.fetchProjectById(project.id).get

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(19999L, 1L)).thenReturn(None)
      when(mockRoadAddressService.getPreviousRoadPartNumber(19999L, 2L)).thenReturn(Some(1L))

      val error1 = projectValidator.validateProject(updProject, currentProjectLinks).distinct
      error1 should have size 1
      error1.head.validationError.value should be(projectValidator.ValidationErrorList.TerminationContinuity.value)

      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, project.id, RoadAddressChangeType.Unchanged).copy(roadNumber = roadAddresses.head.roadNumber, roadPartNumber = roadAddresses.head.roadPartNumber, roadwayId = ra.head.id)))
      val currentProjectLinks2 = projectLinkDAO.fetchProjectLinks(project.id)

      val updProject2 = projectService.fetchProjectById(project.id).get

      val error2 = projectValidator.validateProject(updProject2, currentProjectLinks2).distinct
      error2 should have size 1
      error2.head.validationError.value should be(projectValidator.ValidationErrorList.MissingEndOfRoad.value)

      val updatedProjectLinks = Seq(currentProjectLinks2.filter(_.status == RoadAddressChangeType.Unchanged).head.copy(roadPartNumber = 2L, discontinuity = Discontinuity.EndOfRoad, status = RoadAddressChangeType.Transfer))
      projectLinkDAO.updateProjectLinks(updatedProjectLinks, "U", roadAddresses)
      val afterProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)

      val errors3 = projectValidator.validateProject(updProject2, afterProjectLinks).distinct
      errors3 should have size 0
    }
  }

  test("Test validateProject When reserve part 2 (which has EndOfRoad) and Terminate it. Create new part 2 (with and without EndOfRoad) Then different results should be expected") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,  0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val newRa = RoadAddress(12347, linearLocationId + 2, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 3000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(20.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, project.id, RoadAddressChangeType.Termination).copy(roadNumber = roadAddresses.last.roadNumber, roadPartNumber = roadAddresses.last.roadPartNumber, roadwayId = ra.last.id)))
      //add new link with same terminated road part (which had EndOfRoad)
      projectLinkDAO.create(Seq(util.toProjectLink(project, RoadAddressChangeType.New)(newRa).copy(roadNumber = newRa.roadNumber, roadPartNumber = newRa.roadPartNumber)))

      val currentProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 2L,false, false, false)).thenReturn(Seq.empty[RoadAddress])

      val updProject = projectService.fetchProjectById(project.id).get

      mockEmptyRoadAddressServiceCalls()

      val error1 = projectValidator.validateProject(updProject, currentProjectLinks).distinct
      error1 should have size 1
      error1.head.validationError.value should be(projectValidator.ValidationErrorList.MissingEndOfRoad.value)

      val updatedProjectLinks = Seq(currentProjectLinks.filter(_.status == RoadAddressChangeType.New).head.copy(roadPartNumber = 2L, discontinuity = Discontinuity.EndOfRoad))

      projectLinkDAO.updateProjectLinks(updatedProjectLinks, "U", roadAddresses)
      val currentProjectLinks2 = projectLinkDAO.fetchProjectLinks(project.id)

      mockEmptyRoadAddressServiceCalls()

      val error2 = projectValidator.validateProject(updProject, currentProjectLinks2).distinct
      error2 should have size 0
    }
  }

  test("Test validateProject When reserve part 2 (which has EndOfRoad) and Terminate it. Create new part 3 (with and without EndOfRoad) Then different results should be expected") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 20000L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,  0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val newRa = RoadAddress(12347, linearLocationId + 2, 20000L, 3L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 3000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(20.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)

      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, 20000L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 20000L, 2L, "u")
      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, project.id, RoadAddressChangeType.Termination).copy(roadNumber = ra.last.roadNumber, roadPartNumber = ra.last.roadPartNumber, roadwayId = ra.last.id)))

      //add new link with same terminated road part (which had EndOfRoad)
      projectReservedPartDAO.reserveRoadPart(id, 20000L, 3L, "u")
      val newpl = Seq(util.toProjectLink(project, RoadAddressChangeType.New)(newRa).copy(roadNumber = newRa.roadNumber, roadPartNumber = newRa.roadPartNumber, projectId = project.id))
      projectLinkDAO.create(newpl)

      val currentProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(20000L, 2L, false, false, false)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(20000L, 3L, false, false, false)).thenReturn(Seq.empty[RoadAddress])

      val updProject = projectService.fetchProjectById(project.id).get

      mockEmptyRoadAddressServiceCalls()

      val error1 = projectValidator.validateProject(updProject, currentProjectLinks).distinct
      error1 should have size 1
      error1.head.validationError.value should be(projectValidator.ValidationErrorList.MissingEndOfRoad.value)

      val updatedProjectLinks = Seq(currentProjectLinks.filter(_.status == RoadAddressChangeType.New).head.copy(roadPartNumber = 2L, discontinuity = Discontinuity.EndOfRoad))

      projectLinkDAO.updateProjectLinks(updatedProjectLinks, "U", roadAddresses)
      val currentProjectLinks2 = projectLinkDAO.fetchProjectLinks(project.id)

      mockEmptyRoadAddressServiceCalls()

      val error2 = projectValidator.validateProject(updProject, currentProjectLinks2).distinct
      error2 should have size 0
    }
  }

  test("Test validateProject When Terminate all links for all parts in a roadNumber Then should not exist any error") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,  0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )
      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")
      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Termination, 19999L, 1L, discontinuity = Discontinuity.Continuous, 8, 12345.toString, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Termination, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346.toString, raId + 1, linearLocationId + 1).copy(geometry = roadAddresses.last.geometry)))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L, false, false, false)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 2L, false, false, false)).thenReturn(Seq.empty[RoadAddress])

      val updProject = projectService.fetchProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.fetchProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.validateProject(updProject, currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Test checkRemovedEndOfRoadParts When Checking end of road with all parts being EndOfRoad and all terminated on project with multiple parts Then should not exist any error") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )
      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Termination, 19999L, 1L, discontinuity = Discontinuity.Continuous, 8, 12345.toString, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Unchanged, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346.toString, raId + 1, linearLocationId + 1).copy(geometry = roadAddresses.last.geometry)))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L, false, false, false)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 2L, false, false, false)).thenReturn(Seq.empty[RoadAddress])

      val updProject = projectService.fetchProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.fetchProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(updProject, currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Test checkRemovedEndOfRoadParts When Checking end of road after first part being EndOfRoad and terminated, and second being EndOfRoad and not terminated in project with multiple parts Then should not exist any error") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )
      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        //RightSide
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Termination, 19999L, 1L, discontinuity = Discontinuity.Continuous, 8, 12345.toString, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Termination, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346.toString, raId + 1, linearLocationId + 1).copy(geometry = roadAddresses.last.geometry)))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L, false, false, false)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 2L, false, false, false)).thenReturn(Seq.empty[RoadAddress])

      val updProject = projectService.fetchProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.fetchProjectLinks(updProject.id)

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(updProject, currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Test validateProject Checking end of road with both parts EndOfRoad and both not terminated in project with multiple parts Then should return EndOfRoadNotOnLastPart error") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(startDate), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(startDate), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(startDate), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(startDate), None, None)
      )
      val ra = Seq(
        Roadway(raId,     roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined,  Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Unchanged, 19999L, 1L, discontinuity = Discontinuity.EndOfRoad, 8, 12345.toString, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Unchanged, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346.toString, raId + 1, linearLocationId + 1).copy(geometry = roadAddresses.last.geometry)))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L, false, false, false)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 2L, false, false, false)).thenReturn(Seq.empty[RoadAddress])

      val updProject = projectService.fetchProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.fetchProjectLinks(updProject.id)

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(roadAddresses)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(19999L, 1L)).thenReturn(None)
      when(mockRoadAddressService.getPreviousRoadPartNumber(19999L, 2L)).thenReturn(Some(1L))

      val errors = projectValidator.validateProject(updProject, currentProjectLinks).distinct
      errors should have size 1
      errors.head.affectedPlIds.head should be(currentProjectLinks.head.id)
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.EndOfRoadNotOnLastPart.value)
    }
  }

  test("Test checkRemovedEndOfRoadParts Checking end of road in different road numbers with both parts EndOfRod and both not terminated in project with multiple parts Then should not exist any error") {

    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19998L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(startDate), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(startDate), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(startDate), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(startDate), None, None)
      )
      val ra = Seq(
        Roadway(raId, roadwayNumber1, 19998L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)
      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19998L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Unchanged, 19998L, 1L, discontinuity = Discontinuity.EndOfRoad, 8, 12345.toString, raId, linearLocationId).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Unchanged, 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346.toString, raId + 1, linearLocationId + 1).copy(geometry = roadAddresses.last.geometry)))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 1L, false, false, false)).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(19999L, 2L, false, false, false)).thenReturn(Seq.empty[RoadAddress])

      val updProject = projectService.fetchProjectById(project.id).get
      val currentProjectLinks = projectLinkDAO.fetchProjectLinks(updProject.id)

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(roadAddresses)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(19999L, 1L)).thenReturn(None)
      when(mockRoadAddressService.getPreviousRoadPartNumber(19999L, 2L)).thenReturn(Some(1L))

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(updProject, currentProjectLinks).distinct
      errors should have size 0
    }
  }

  test("Test checkRoadContinuityCodes When Ramp has a MinorDiscontinuity code but no minor discontinuity Then should exist DiscontinuityOnRamp error") {
    runWithRollback {
      val project = util.setUpProjectWithRampLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L))
      val projectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val modifiedProjectLinks = projectLinks.head.copy(discontinuity = Discontinuity.MinorDiscontinuity) +: projectLinks.tail
      mockEmptyRoadAddressServiceCalls()
      val errors = projectValidator.checkRoadContinuityCodes(project, modifiedProjectLinks, isRampValidation = true)
      errors.size should be > 0
      errors.map(_.validationError.value) should contain(projectValidator.ValidationErrorList.DiscontinuityOnRamp.value)
    }
  }

  test("Test checkRoadContinuityCodes When Ramp has disconnected geometry Then should exist DiscontinuityOnRamp error") {
    runWithRollback {
      val project = util.setUpProjectWithRampLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L))
      val projectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val modifiedProjectLinks = projectLinks.head.copy(geometry = List(Point(0.0,0.0),Point(5.0,5.0))) +: projectLinks.tail
      mockEmptyRoadAddressServiceCalls()
      val errors = projectValidator.checkRoadContinuityCodes(project, modifiedProjectLinks, isRampValidation = true)
      errors.size should be > 0
      errors.map(_.validationError.value) should contain(projectValidator.ValidationErrorList.DiscontinuityOnRamp.value)
    }
  }

  //VIITE-2816
  test("Test checkRoadContinuityCodes When Ramp has two tracks with identical start and end address values then should be no errors") {
    runWithRollback {
      val project = util.setUpProjectWithRampLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L))
      val projectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val modifiedProjectLinks = projectLinks.map(pl => pl.copy(track = Track.RightSide, geometry = List(Point(0.0,0.0+pl.startAddrMValue),Point(0.0,0.0+pl.endAddrMValue)))) ++
        projectLinks.map(pl => pl.copy(track = Track.LeftSide, geometry = List(Point(5.0,0.0+pl.startAddrMValue),Point(5.0,0.0+pl.endAddrMValue))))

      mockEmptyRoadAddressServiceCalls()
      val errors = projectValidator.checkRoadContinuityCodes(project, modifiedProjectLinks, isRampValidation = true)
      errors should have size 0
    }
  }

  test("Test checkRoadContinuityCodes When Ramp has both Combined and Two-track sections that are connected then should not exist any error") {
    runWithRollback {
      val project = util.setUpProjectWithRampLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L))
      val projectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val modifiedProjectLinks = Seq(projectLinks.head,
        projectLinks.last.copy(track=Track.RightSide, geometry=List(Point(0.0,10.0), Point(5.0,15.0))),
        projectLinks.last.copy(track=Track.LeftSide, geometry=List(Point(0.0,10.0), Point(7.5, 12.5))))

      mockEmptyRoadAddressServiceCalls()
      val errors = projectValidator.checkRoadContinuityCodes(project, modifiedProjectLinks, isRampValidation = true).distinct
      errors should have size 0
    }
  }

  test("Test checkRoadContinuityCodes When Ramp has both Two-Track and Combined sections that are connected then should not exist any error") {
    runWithRollback {
      val project = util.setUpProjectWithRampLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L))
      val projectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val modifiedProjectLinks = Seq(
        projectLinks.head.copy(track=Track.RightSide, geometry=List(Point(7.5,0.0), Point(0.0, 10.0))),
        projectLinks.head.copy(track=Track.LeftSide, geometry=List(Point(2.5,0.0), Point(0.0, 10.0))),
        projectLinks.last
      )

      mockEmptyRoadAddressServiceCalls()
      val errors = projectValidator.checkRoadContinuityCodes(project, modifiedProjectLinks, isRampValidation = true).distinct
      errors should have size 0
    }
  }

  test("Test checkRoadContinuityCodes When Ramp has both Two-track and Combined sections that are NOT connected then should exist DiscontinuityOnRamp error") {
    runWithRollback {
      val project = util.setUpProjectWithRampLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L))
      val projectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val modifiedProjectLinks = Seq(
        projectLinks.head.copy(track=Track.RightSide, geometry=List(Point(7.5,0.0), Point(0.0, 8.5))),
        projectLinks.head.copy(track=Track.LeftSide, geometry=List(Point(2.5,0.0), Point(0.0, 10.0))),
        projectLinks.last
      )

      mockEmptyRoadAddressServiceCalls()
      val errors = projectValidator.checkRoadContinuityCodes(project, modifiedProjectLinks, isRampValidation = true).distinct
      errors.size should be > 0
      errors.map(_.validationError.value) should contain(projectValidator.ValidationErrorList.DiscontinuityOnRamp.value)
    }
  }

  test("Test checkRoadContinuityCodes When Ramp last lisk is discontinuous and there is disconnected road part after then should not exist any error") {
    runWithRollback {
      val project = util.setUpProjectWithRampLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L))
      val projectLinks = projectLinkDAO.fetchProjectLinks(project.id)

      mockEmptyRoadAddressServiceCalls()
      val errors = projectValidator.checkRoadContinuityCodes(project, projectLinks, isRampValidation = true).distinct
      errors should have size 0

      val (starting, last) = projectLinks.splitAt(3)

      mockEmptyRoadAddressServiceCalls()
      val errorsUpd = projectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Continuous)), isRampValidation = true).distinct
      errorsUpd should have size 1
      errorsUpd.head.validationError.value should be(projectValidator.ValidationErrorList.MissingEndOfRoad.value)

      mockEmptyRoadAddressServiceCalls()
      val errorsUpd2 = projectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.MinorDiscontinuity)), isRampValidation = true).distinct
      errorsUpd2 should have size 1
      errorsUpd2.head.validationError.value should be(projectValidator.ValidationErrorList.MissingEndOfRoad.value)

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId

      val ra = Seq(
        Roadway(raId, roadwayNumber1, 39998L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 30L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 2, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(2.0, 30.0), Point(7.0, 35.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 2, 3, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(7.0, 35.0), Point(0.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      projectReservedPartDAO.reserveRoadPart(project.id, 39999L, 20L, "u")

      projectLinkDAO.create((starting ++ last.map(_.copy(discontinuity = Discontinuity.EndOfRoad)))
        .map(_.copy(id = NewIdValue, roadPartNumber = 20L, createdBy = Some("I"))))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(39999L, 20L, false, false, false)).thenReturn(Seq.empty[RoadAddress])

      val updProject = projectService.fetchProjectById(project.id).get

      mockEmptyRoadAddressServiceCalls()

      projectValidator.checkRoadContinuityCodes(updProject,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Discontinuous)), isRampValidation = true).distinct should have size 0
    }
  }

  test("Test validateProject When there are some Not handled links Then should return HasNotHandledLinks error") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 10, Some(startDate), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(startDate), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,  0, 10, Some(startDate), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(startDate), None, None)
      )
      val ra = Seq(
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 2L, "u")

      projectLinkDAO.create(Seq(
        util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.NotHandled,  roadNumber = 19999L, 1L, discontinuity = Discontinuity.EndOfRoad, 8, 12345.toString, raId,     linearLocationId    ).copy(geometry = roadAddresses.head.geometry),
        util.projectLink(0L, 10L, Track.Combined, id, RoadAddressChangeType.Termination, roadNumber = 19999L, 2L, discontinuity = Discontinuity.EndOfRoad, 8, 12346.toString, raId + 1, linearLocationId + 1).copy(geometry = roadAddresses.last.geometry)))

      mockEmptyRoadAddressServiceCalls()

      val validationErrors = projectValidator.validateProject(project, projectLinkDAO.fetchProjectLinks(project.id)).filter(_.validationError.value == projectValidator.ValidationErrorList.HasNotHandledLinks.value)
      validationErrors.size should be(1)
      validationErrors.head.validationError.message should be("")
      validationErrors.head.optionalInformation should not be ""
    }
  }

  test("Test checkForInvalidUnchangedLinks When it is connected after any other action Then should return invalid unchanged links error") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,  0, 10, Some(startDate), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(startDate), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 10, 20, Some(startDate), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(startDate), None, None)
      )
      val ra = Seq(
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId + 1, roadwayNumber2, 19999L, 1L, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)


      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")

      projectLinkDAO.create(
        Seq(
          util.projectLink( 0L, 10L, Track.Combined, id, RoadAddressChangeType.NotHandled, discontinuity = Discontinuity.Continuous, linkId = 1000.toString, roadwayId = ra.head.id).copy(geometry = roadAddresses.head.geometry),
          util.projectLink( 0L, 10L, Track.Combined, id, RoadAddressChangeType.Transfer,   discontinuity = Discontinuity.Continuous, linkId = 1000.toString, roadwayId = ra.head.id).copy(geometry = roadAddresses.head.geometry),
          util.projectLink(10L, 20L, Track.Combined, id, RoadAddressChangeType.NotHandled, discontinuity = Discontinuity.Continuous, linkId = 2000.toString, roadwayId = ra.last.id).copy(geometry = roadAddresses.last.geometry),
          util.projectLink(10L, 20L, Track.Combined, id, RoadAddressChangeType.Unchanged,  discontinuity = Discontinuity.Continuous, linkId = 2000.toString, roadwayId = ra.last.id).copy(geometry = roadAddresses.last.geometry)
        ))

      val projectLinks = projectLinkDAO.fetchProjectLinks(id, Some(RoadAddressChangeType.NotHandled))

      /*
      |---Transfer--->|---Unchanged--->|
       */
      val updatedProjectLinkToTransfer = Seq(projectLinks.head.copy(startAddrMValue = 10, endAddrMValue = 20, status = RoadAddressChangeType.Transfer)) ++ projectLinks.tail.map(pl => pl.copy(startAddrMValue = 0, endAddrMValue = 10, status = RoadAddressChangeType.Unchanged))
      projectLinkDAO.updateProjectLinks(updatedProjectLinkToTransfer, "U", roadAddresses)
      mockEmptyRoadAddressServiceCalls()
      val validationErrors1 = projectValidator.checkForInvalidUnchangedLinks(project, projectLinkDAO.fetchProjectLinks(project.id))
      validationErrors1.size shouldNot be(0)
      validationErrors1.foreach(e => e.validationError.value should be(projectValidator.ValidationErrorList.ErrorInValidationOfUnchangedLinks.value))
      /*
       |---Numbering--->|---Unchanged--->|
        */
      val updatedProjectLinkToNumbering = Seq(projectLinks.head.copy(startAddrMValue = 10, endAddrMValue = 20, status = RoadAddressChangeType.Renumeration))
      projectLinkDAO.updateProjectLinks(updatedProjectLinkToNumbering, "U", roadAddresses)
      mockEmptyRoadAddressServiceCalls()
      val validationErrors2 = projectValidator.checkForInvalidUnchangedLinks(project, projectLinkDAO.fetchProjectLinks(project.id))
      validationErrors2.size shouldNot be(0)
      validationErrors2.foreach(e => e.validationError.value should be(projectValidator.ValidationErrorList.ErrorInValidationOfUnchangedLinks.value))
      /*
       |---Terminated--->|---Unchanged--->|
        */
      val updatedProjectLinkToTerminated = Seq(projectLinks.head.copy(startAddrMValue = 10, endAddrMValue = 20, status = RoadAddressChangeType.Termination))
      projectLinkDAO.updateProjectLinks(updatedProjectLinkToTerminated, "U", roadAddresses)
      mockEmptyRoadAddressServiceCalls()
      val validationErrors3 = projectValidator.checkForInvalidUnchangedLinks(project, projectLinkDAO.fetchProjectLinks(project.id))
      validationErrors3.size shouldNot be(0)
      validationErrors3.foreach(e => e.validationError.value should be(projectValidator.ValidationErrorList.ErrorInValidationOfUnchangedLinks.value))
    }
  }

  test("Test checkForInvalidUnchangedLinks When project link with status New is added to the start of the road part then links with status Unchanged are forbidden after the New link") {
    /*
    BEFORE PROJECT
                                19999
                 |-------------------------------------------->

    AFTER OPERATIONS
      19999               19999                  19999
        New             Unchanged              Unchanged
    |------------>--------------------->---------------------->
                        ^Must produce validation error^
    */
    runWithRollback {
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val projectId = Sequences.nextViiteProjectId
      val geometryNew = Seq(Point(10.0, 30.0), Point(10.0, 40.0))
      val geometryUnchanged1 = Seq(Point(10.0, 40.0), Point(10.0, 50.0))
      val geometryUnchanged2 = Seq(Point(10.0, 50.0), Point(10.0, 60.0))
      val roadwayId = Sequences.nextRoadwayId

      val roadway = Seq(
        Roadway(roadwayId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 20L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None)
      )
      roadwayDAO.create(roadway)

      //create linear locations
      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), geometryNew, LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 2, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), geometryUnchanged1, LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 2, 3, 3000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), geometryUnchanged2, LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None)
      )

      linearLocationDAO.create(linearLocations)

      //create project and reserve road parts
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(projectId, 19999L, 1L, "u")

      // create project links
      projectLinkDAO.create(
        Seq(
          util.projectLink( 0L, 10L, Track.Combined, projectId, RoadAddressChangeType.New,       discontinuity = Discontinuity.Continuous, linkId = 1000.toString, roadwayId = roadwayId, linearLocationId = linearLocationId    ).copy(originalStartAddrMValue =  0, originalEndAddrMValue =  0, geometry = geometryNew),
          util.projectLink(10L, 20L, Track.Combined, projectId, RoadAddressChangeType.Unchanged, discontinuity = Discontinuity.Continuous, linkId = 2000.toString, roadwayId = roadwayId, linearLocationId = linearLocationId + 1).copy(originalStartAddrMValue =  0, originalEndAddrMValue = 10, geometry = geometryUnchanged1),
          util.projectLink(20L, 30L, Track.Combined, projectId, RoadAddressChangeType.Unchanged, discontinuity = Discontinuity.EndOfRoad,  linkId = 3000.toString, roadwayId = roadwayId, linearLocationId = linearLocationId + 2).copy(originalStartAddrMValue = 10, originalEndAddrMValue = 20, geometry = geometryUnchanged2)
        )
      )

      // fetch project links
      val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)

      mockEmptyRoadAddressServiceCalls()

      // call the function that will be tested
      val validationErrors = projectValidator.checkForInvalidUnchangedLinks(project, projectLinks)
      validationErrors.size should be(1)
      validationErrors.head.validationError.value should be(projectValidator.ValidationErrorList.ErrorInValidationOfUnchangedLinks.value)
      val lastTwoProjectLinkIds = projectLinks.tail.map(pl => pl.id).toList
      val affectedIds = validationErrors.head.affectedPlIds
      affectedIds should contain theSameElementsInOrderAs lastTwoProjectLinkIds
    }
  }

   test("Test checkForInvalidUnchangedLinks When middle of road part is changed to another road number then there cant be Unchanged link after the Transferred link") {
    /*
    BEFORE PROJECT
                    19999
    -------------------------------------------->

    AFTER OPERATIONS
    19999             20000           19999
    Unchanged         Transfer        Unchanged
    ------------>---------------->-------------->
                                          ^Must produce validation error
    */
    runWithRollback {
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val projectId = Sequences.nextViiteProjectId
      val geometry1 = Seq(Point(10.0, 30.0), Point(10.0, 40.0))
      val geometry2 = Seq(Point(10.0, 40.0), Point(10.0, 50.0))
      val geometry3 = Seq(Point(10.0, 50.0), Point(10.0, 60.0))
      val roadwayId = Sequences.nextRoadwayId

      val roadway = Seq(
        Roadway(roadwayId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 30L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None)
      )
      roadwayDAO.create(roadway)

      //create linear locations
      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), geometry1, LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 2, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), geometry2, LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 2, 3, 3000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), geometry3, LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None)
      )

      linearLocationDAO.create(linearLocations)

      //create project and reserve road parts
      val project = Project(projectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(), "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(projectId, 19999L, 1L, "u")
      projectReservedPartDAO.reserveRoadPart(projectId, 20000L, 1L, "u")

      // create project links
      projectLinkDAO.create(
        Seq(
          util.projectLink( 0L, 10L, Track.Combined, projectId, RoadAddressChangeType.Unchanged,            discontinuity = Discontinuity.MinorDiscontinuity, linkId = 1000.toString, roadwayId = roadwayId, linearLocationId = linearLocationId    ).copy(originalStartAddrMValue =  0, originalEndAddrMValue = 10, geometry = geometry1),
          util.projectLink( 0L, 10L, Track.Combined, projectId, RoadAddressChangeType.Transfer, roadNumber = 20000L, discontinuity = Discontinuity.EndOfRoad, linkId = 2000.toString, roadwayId = roadwayId, linearLocationId = linearLocationId + 1).copy(originalStartAddrMValue = 10, originalEndAddrMValue = 20, geometry = geometry2),
          util.projectLink(20L, 30L, Track.Combined, projectId, RoadAddressChangeType.Unchanged,                     discontinuity = Discontinuity.EndOfRoad, linkId = 3000.toString, roadwayId = roadwayId, linearLocationId = linearLocationId + 2).copy(originalStartAddrMValue = 20, originalEndAddrMValue = 30, geometry = geometry3)
        )
      )

      // fetch project links
      val projectLinks = projectLinkDAO.fetchProjectLinks(projectId)

      mockEmptyRoadAddressServiceCalls()

      // call the function that will be tested
      val validationErrors = projectValidator.checkForInvalidUnchangedLinks(project, projectLinks)

      validationErrors.size should be(1)
      validationErrors.head.validationError.value should be(projectValidator.ValidationErrorList.ErrorInValidationOfUnchangedLinks.value)
      val affectedIds = validationErrors.head.affectedPlIds
      val invalidUnchangedPlId = projectLinks.tail.head.id
      affectedIds.size should be (1)
      affectedIds.head shouldEqual invalidUnchangedPlId
    }
  }

  test("Test validateProject When it is connected after any other action Then it should return has not handled links error") {
    import org.scalatest.enablers.Definition.definitionOfOption
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 10, Some(startDate), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(startDate), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 10, 20, Some(startDate), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(startDate), None, None)
      )
      val ra = Seq(
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId + 1, roadwayNumber2, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)


      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectLinkDAO.create(
        Seq(
          util.projectLink( 0L, 10L, Track.Combined, id, RoadAddressChangeType.NotHandled, discontinuity = Discontinuity.Continuous, linkId = 1000.toString, roadwayId = ra.head.id).copy(geometry = roadAddresses.head.geometry),
          util.projectLink( 0L, 10L, Track.Combined, id, RoadAddressChangeType.Transfer,   discontinuity = Discontinuity.Continuous, linkId = 1000.toString, roadwayId = ra.head.id).copy(geometry = roadAddresses.head.geometry),
          util.projectLink(10L, 20L, Track.Combined, id, RoadAddressChangeType.NotHandled, discontinuity = Discontinuity.EndOfRoad,  linkId = 2000.toString, roadwayId = ra.last.id).copy(geometry = roadAddresses.last.geometry),
          util.projectLink(10L, 20L, Track.Combined, id, RoadAddressChangeType.Unchanged,  discontinuity = Discontinuity.EndOfRoad,  linkId = 2000.toString, roadwayId = ra.last.id).copy(geometry = roadAddresses.last.geometry)
        ))

      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.validateProject(project, projectLinkDAO.fetchProjectLinks(project.id))

      validationErrors.size should not be 0
      validationErrors.find(e => e.validationError.value == projectValidator.ValidationErrorList.HasNotHandledLinks.value) shouldBe defined
    }
  }

  test("Test validateProject When there is one Unchanged link after one NotHandled Then the error should not be ErrorInValidationOfUnchangedLinks but instead about untreated NotHandled links") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0, 10, Some(startDate), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(startDate), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 10, 20, Some(startDate), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(startDate), None, None)
      )
      val ra = Seq(
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId + 1, roadwayNumber2, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 30.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)


      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")
      projectLinkDAO.create(
        Seq(
          util.projectLink( 0L, 10L, Track.Combined, id, RoadAddressChangeType.NotHandled, discontinuity = Discontinuity.Continuous, linkId = 1000.toString, roadwayId = ra.head.id).copy(geometry = roadAddresses.head.geometry),
          util.projectLink(10L, 20L, Track.Combined, id, RoadAddressChangeType.Unchanged,  discontinuity = Discontinuity.EndOfRoad,  linkId = 2000.toString, roadwayId = ra.last.id).copy(geometry = roadAddresses.last.geometry)
        ))

      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.validateProject(project, projectLinkDAO.fetchProjectLinks(project.id))

      validationErrors.size should not be 0
      validationErrors.foreach(e => e.validationError.value should be(projectValidator.ValidationErrorList.HasNotHandledLinks.value))
    }
  }

  test("Test checkForInvalidUnchangedLinks When endPoint of current (even if it is any action than Unchanged) is not connected to startPoint of next one (Unchanged) Then validator should not return invalid unchanged links error") {
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
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,  0, 10, Some(startDate), None, Some("User"), 1000.toString,  0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point( 0.0, 10.0), Point(10.0, 10.0)),                                      LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(startDate), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 10, 30, Some(startDate), None, Some("User"), 1000.toString, 10, 35, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 10.0), Point(10.0, 15.0), Point(20.0, 15.0), Point(20.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(startDate), None, None),
        RoadAddress(12347, linearLocationId + 1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 30, 50, Some(startDate), None, Some("User"), 1000.toString, 35, 50, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(20.0,  0.0), Point(10.0,  0.0), Point(10.0, 10.0)),                   LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber3, Some(startDate), None, None)
      )
      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 50L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 10.0), Point(10.0, 15.0), Point(20.0, 15.0), Point(20.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 2, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 10.0), Point(10.0, 15.0), Point(20.0, 15.0), Point(20.0, 0.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None),
        LinearLocation(linearLocationId + 3, 3, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(20.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber3, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")

      val projectLinksToCreate: Seq[ProjectLink] = roadAddresses.map(toProjectLink(project)).map(_.copy(roadwayId = ra.head.id))
      projectLinkDAO.create(projectLinksToCreate)

      val projectLinks = projectLinkDAO.fetchProjectLinks(id).sortBy(_.startAddrMValue)
      val updatedProjectLinks = Seq(projectLinks.head.copy(status = RoadAddressChangeType.Unchanged)) ++ projectLinks.tail.map(pl => pl.copy(status = RoadAddressChangeType.Transfer))
      projectLinkDAO.updateProjectLinks(updatedProjectLinks, "U", roadAddresses)
      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkForInvalidUnchangedLinks(project, projectLinkDAO.fetchProjectLinks(id))
      validationErrors.size should be(0)
    }
  }

  test("Test checkForInvalidUnchangedLinks When it is connected after any other action but having lower address Then should NOT return invalid unchanged links error") {
    /*
      Left
    | - - >|- - - - - - - ->
    ^      ^
Left|      |Right
    |      |
    | - - >|- - - - - - - - >
    ^ Right^
    |      |
   */

    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val roadAddresses = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,  0, 10, Some(startDate), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber1, Some(startDate), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 10, 20, Some(startDate), None, Some("User"), 2000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point( 0.0, 40.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, 8, NoTermination, roadwayNumber2, Some(startDate), None, None)
      )
      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId + 1, roadwayNumber2, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 10L, 20L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 2, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point( 0.0, 40.0), Point(10.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      val id = Sequences.nextViiteProjectId
      val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
        "", Seq(), Seq(), None, None)
      projectDAO.create(project)
      projectReservedPartDAO.reserveRoadPart(id, 19999L, 1L, "u")

      val projectLinksToCreate: Seq[ProjectLink] = roadAddresses.map(toProjectLink(project)).map(_.copy(roadwayId = ra.head.id))
      projectLinkDAO.create(projectLinksToCreate)

      val projectLinks = projectLinkDAO.fetchProjectLinks(id).sortBy(_.startAddrMValue)
      val updatedProjectLinks = Seq(projectLinks.head.copy(status = RoadAddressChangeType.Unchanged)) ++ projectLinks.tail.map(pl => pl.copy(status = RoadAddressChangeType.Transfer))
      projectLinkDAO.updateProjectLinks(updatedProjectLinks, "U", roadAddresses)
      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkForInvalidUnchangedLinks(project, projectLinkDAO.fetchProjectLinks(id))
      validationErrors.size should be(0)
    }
  }


  test("Test checkForInvalidUnchangedLinks When there is a new link is before unchanged Then ProjectValidator should show ErrorInValidationOfUnchangedLinks") {
      /* |-> New-UnChanged-UnChanged-UnChanged-UnChanged-UnChanged */
    runWithRollback {
      mockEmptyRoadAddressServiceCalls()
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Unchanged, Seq(0L, 10L, 20L, 30L, 40L), withRoadAddress = false)
      val newPl = projectLinks.head.copy(status = RoadAddressChangeType.New, startAddrMValue = 0, endAddrMValue = 0, originalStartAddrMValue = 0, originalEndAddrMValue = 0, geometry = Seq(Point(0,-10),Point(0,0)))
      val validationErrors = projectValidator.checkForInvalidUnchangedLinks(project, newPl +: projectLinks).distinct
      validationErrors.size shouldNot be(0)
      validationErrors.foreach(e => e.validationError.value should be(projectValidator.ValidationErrorList.ErrorInValidationOfUnchangedLinks.value))
    }
  }

  test("Test checkForInvalidUnchangedLinks When there is a new link in middle of unchanged links Then ProjectValidator should show ErrorInValidationOfUnchangedLinks") {
     /* |-> UnChanged-UnChanged-UnChanged-UnChanged-UnChanged-New-UnChanged-UnChanged-UnChanged-UnChanged-UnChanged */
   runWithRollback {
     mockEmptyRoadAddressServiceCalls()
     val newLinkStartAddress = 50
     val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Unchanged, Seq(0L, 10L, 20L, 30L, 40L), withRoadAddress = false)
     val projectLinksAfterNewLink = projectLinks.map(pl => pl.copy(
       startAddrMValue = pl.startAddrMValue + newLinkStartAddress,
       endAddrMValue = pl.endAddrMValue + newLinkStartAddress,
       originalStartAddrMValue = pl.startAddrMValue + newLinkStartAddress,
       originalEndAddrMValue = pl.endAddrMValue + newLinkStartAddress,
       geometry = Seq(Point(pl.startingPoint.x, pl.startingPoint.y + newLinkStartAddress), Point(pl.endPoint.x, pl.endPoint.y + newLinkStartAddress))
       )
                                                     )
     val newPl = projectLinks.head.copy(id = projectLinks.head.id-1, status = RoadAddressChangeType.New, startAddrMValue = 0, endAddrMValue = 0, originalStartAddrMValue = 0, originalEndAddrMValue = 0, geometry = Seq(Point(0,40),Point(0,50)))
     val validationErrors = projectValidator.checkForInvalidUnchangedLinks(project, projectLinks ++ Seq(newPl) ++ projectLinksAfterNewLink).distinct
     validationErrors.size shouldNot be(0)
     validationErrors.foreach(e => e.validationError.value should be(projectValidator.ValidationErrorList.ErrorInValidationOfUnchangedLinks.value))
   }
  }

  test("Test checkForInvalidUnchangedLinks When there is a new link is after unchanged links Then ProjectValidator shouldn't show ErrorInValidationOfUnchangedLinks") {
      /* |-> UnChanged-UnChanged-UnChanged-UnChanged-UnChanged-New */
    runWithRollback {
      mockEmptyRoadAddressServiceCalls()
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Unchanged, Seq(0L, 10L, 20L, 30L, 40L), withRoadAddress = false)
      val newPl = projectLinks.head.copy(id = projectLinks.last.id+1, status = RoadAddressChangeType.New, startAddrMValue = 0, endAddrMValue = 0, originalStartAddrMValue = 0, originalEndAddrMValue = 0, geometry = Seq(Point(0,40),Point(0,50)))
      val validationErrors = projectValidator.checkForInvalidUnchangedLinks(project, projectLinks ++ Seq(newPl)).distinct
      validationErrors should have size 0
    }
  }

  test("Test checkProjectElyCodes When un-calculated new links have ely change but no road part change " +
                "Then validator should return ElyCodeChangeButNotOnEnd error.") {
    runWithRollback {
      mockEmptyRoadAddressServiceCalls()
      val testRoad = (16320L, 1L, "name")
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true, Seq(testRoad), Discontinuity.Continuous)
      // New projectLinks with 0 address and ely-change at middle link
      val newLinks = (projectLinks.filter(_.endAddrMValue < 30) ++
      projectLinks.filter(_.endAddrMValue == 30).map(_.copy(discontinuity = Discontinuity.ChangingELYCode)) ++
      projectLinks.filter(_.endAddrMValue > 30).map(_.copy(discontinuity = Discontinuity.EndOfRoad))).map(toNewUnCalculated)

      val validationErrors = projectValidator.checkProjectElyCodes(project, newLinks).distinct
      validationErrors.size should be(1)
      validationErrors.map(_.validationError).contains(projectValidator.ValidationErrorList.ElyCodeChangeButNotOnEnd)
    }
  }

  test("Test checkProjectElyCodes When un-calculated new links have ely change but no ChangingELYCode discontinuity is set " +
                "Then validator should return ElyCodeChangeDetected error.") {
    runWithRollback {
      mockEmptyRoadAddressServiceCalls()
      val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
      val projectValidator = new ProjectValidator { override val roadwayDAO = mockRoadwayDAO }
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq.empty[Roadway])

      val testRoad = (16320L, 1L, "name")
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true, Seq(testRoad), Discontinuity.Continuous, ely = 8L)
      // New projectLinks with 0 address and ely-change at middle link
      val newLinks = (projectLinks.filter(_.endAddrMValue <= 30) ++
      projectLinks.filter(_.endAddrMValue > 30).map(_.copy(roadPartNumber = 2L, ely = 9L, discontinuity = Discontinuity.EndOfRoad))).map(toNewUnCalculated)

      val validationErrors = projectValidator.checkProjectElyCodes(project, newLinks).distinct
      validationErrors.size should be(1)
      validationErrors.map(_.validationError.value) should contain(projectValidator.ValidationErrorList.ElyCodeChangeDetected.value)
    }
  }

  test("Test checkProjectElyCodes When un-calculated new links and existing roadway with next road and part numbering with different ely-code but no ChangingELYCode discontinuity is set " +
                "Then validator should return ElyCodeChangeDetected error.") {
    runWithRollback {
      mockEmptyRoadAddressServiceCalls()
      val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
      val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
      val projectValidator = new ProjectValidator {
        override val roadwayDAO = mockRoadwayDAO
        override val linearLocationDAO = mockLinearLocationDAO
      }
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq.empty[Roadway])

      val testRoad = (16320L, 1L, "name")
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true, Seq(testRoad), Discontinuity.Continuous, ely = 8L)
      // New projectLinks with 0 address and ely-change at middle link
      val newLinks = projectLinks.filter(_.endAddrMValue <= 30).map(toNewUnCalculated)

      val (linearLocations, roadways) = projectLinks.filter(_.endAddrMValue > 30).map(_.copy(roadPartNumber = 2L, ely = 9L, discontinuity = Discontinuity.EndOfRoad)).map(pl => toRoadwayAndLinearLocation(pl)).unzip
      when(mockRoadwayDAO.fetchAllByRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean])).thenReturn(roadways)
      when(mockLinearLocationDAO.fetchByRoadways(any[Set[Long]])).thenReturn(linearLocations)

      val validationErrors = projectValidator.checkProjectElyCodes(project, newLinks).distinct
      validationErrors.size should be(1)
      validationErrors.map(_.validationError.value) should contain(projectValidator.ValidationErrorList.ElyCodeChangeDetected.value)
    }
  }

  test("Test checkProjectElyCodes When un-calculated new links have ely change at end of road part and road part change with ely change for rest " +
                "Then validator should not return errors ") {
    /*
         New part 1,ely 8 |New part 2,ely 9
         |---->|--->|---->|---> Track 1
         |---->|--->|---->|---> Track 2
                      ElyChange
                            EndOfRoad
    */
    runWithRollback {
      mockEmptyRoadAddressServiceCalls()
      val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
      val projectValidator = new ProjectValidator { override val roadwayDAO = mockRoadwayDAO }
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq.empty[Roadway])
      when(mockRoadwayDAO.fetchAllByRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean])).thenReturn(Seq.empty[Roadway])

      val testRoad = (16320L, 1L, "name")
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true, Seq(testRoad), Discontinuity.Continuous,ely = 8L)
      // New projectLinks with 0 address and ely-change at middle link
      val newLinks = (projectLinks.filter(_.endAddrMValue < 30) ++
      projectLinks.filter(_.endAddrMValue == 30).map(_.copy(discontinuity = Discontinuity.ChangingELYCode)) ++
      projectLinks.filter(_.endAddrMValue > 30).map(_.copy(roadPartNumber = 2L, ely = 9L, discontinuity = Discontinuity.EndOfRoad))).map(toNewUnCalculated)

      val validationErrors = projectValidator.checkProjectElyCodes(project, newLinks).distinct
      validationErrors.size should be(0)
    }
  }

  test("Test checkProjectElyCodes When discontinuity is 3 and next road part ely is equal Then validator should return errors") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val ra = Seq(
        RoadAddress(12345, 1, 16320L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.AgainstDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val roadway = Roadway(raId, roadwayNumber1, 16320L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val testRoad = {
        (16320L, 1L, "name")
      }
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Unchanged, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = false, Seq(testRoad), Discontinuity.ChangingELYCode)

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(ra)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(None)

      val validationErrors = projectValidator.checkProjectElyCodes(project, projectLinks).distinct
      validationErrors.size should be(1)
      validationErrors.map(_.validationError).contains(projectValidator.ValidationErrorList.ElyCodeChangeButNotOnEnd)
    }
  }

  test("Test checkProjectElyCodes When discontinuity is 3 (ELY change) on one track but not on the opposite track Then validator should return errors") {
    runWithRollback {
      val raId1 = Sequences.nextRoadwayId
      val raId2 = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocationId2 = Sequences.nextLinearLocationId

      val roadway1 = Roadway(raId1, roadwayNumber1, 16320L, 2L, AdministrativeClass.State, Track.LeftSide, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None)
      val roadway2 = Roadway(raId2, roadwayNumber1, 16320L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None)

      val linearLocation1 = LinearLocation(linearLocationId1, 1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None)
      val linearLocation2 = LinearLocation(linearLocationId2, 1, 1001L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None)

      roadwayDAO.create(Seq(roadway1, roadway2))
      linearLocationDAO.create(Seq(linearLocation1, linearLocation2))

      val ra = Seq(
        RoadAddress(12345, 1, 16320L, 2L, AdministrativeClass.State, Track.LeftSide,  Discontinuity.Continuous, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, 1, 16320L, 2L, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous, 0, 10, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val testRoad = {
        (16320L, 1L, "name")
      }
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Unchanged, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true, Seq(testRoad), Discontinuity.Continuous)

      val projectLinkToChange = projectLinks.maxBy(_.endAddrMValue)
      val projectLinksRemoved = projectLinks.dropWhile(_.linkId == projectLinkToChange.linkId)
      val changedProjectLink = projectLinkToChange.copy(discontinuity = Discontinuity.ChangingELYCode)
      val combinedProjectLinks = projectLinksRemoved ++ Seq(changedProjectLink)


      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(ra)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(None)

      val validationErrors = projectValidator.checkProjectElyCodes(project, combinedProjectLinks).distinct
      validationErrors.size should be(1)
      validationErrors.map(_.validationError).contains(projectValidator.ValidationErrorList.UnpairedElyCodeChange) should be(true)
    }
  }

  test("Test checkProjectElyCodes When discontinuity is anything BUT 3 and next road part ely is different Then validator should return errors") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val linkId = 1817196L.toString

      val ra = Seq(
        RoadAddress(12345, 1, 27L, 20L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 6109L, 6559L, Some(DateTime.parse("1901-01-01")), None, Some("User"), linkId, 0, 10, SideCode.AgainstDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val roadway = Roadway(raId, roadwayNumber1, 27L, 20L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 6109L, 6559L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None)

      val linearLocation = LinearLocation(linearLocationId, 1, linkId, 0.0, 450.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val testRoad = {
        (27L, 19L, "name")
      }
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Unchanged, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = false, Seq(testRoad), Discontinuity.Continuous, 12L)
      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(ra)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(None)


      val validationErrors = projectValidator.checkProjectElyCodes(project, projectLinks).distinct
      validationErrors.size should be(0)
    }
  }

  test("Test checkTrackCodePairing When project links are created with similar addr m values then project track codes should be consistent") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkTrackCodePairing(project, projectLinks)
      validationErrors.size should be(0)
    }
  }

  test("Test checkTrackCodePairing When project links are created with inconsistent addr m values Then project track codes should inconsistent in middle of track") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val inconsistentLinks = projectLinks.map { l =>
        if (l.startAddrMValue == 20 && l.track == Track.RightSide)
          l.copy(track = Track.LeftSide)
        else l
      }
      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkTrackCodePairing(project, inconsistentLinks).distinct
      validationErrors.size should be(2)
    }
  }

  test("Test checkTrackCodePairing When project links are created with inconsistent addr m values in middle Then project track codes should inconsistent in extremities of track") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val inconsistentLinks = projectLinks.map { l =>
        if (l.startAddrMValue == 0 && l.track == Track.RightSide)
          l.copy(startAddrMValue = 5)
        else l
      }

      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkTrackCodePairing(project, inconsistentLinks).distinct
      validationErrors.size should be(1)
      validationErrors.head.validationError should be(projectValidator.ValidationErrorList.InsufficientTrackCoverage)
    }
  }

  test("Test checkTrackCodePairing When project links are consistent due to the addding of one simple link with track combined Then should not be any error") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L))
      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkTrackCodePairing(project, projectLinks).distinct
      validationErrors.size should be(0)
    }
  }

  test("Test checkTrackCodePairing When two track projects links have length difference > 20% Then there should be validation error.") {
    // > 20% case
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val extraLength             = 10L
      val inconsistentLinks       = projectLinks.map {l =>
        if (l.endAddrMValue == 40L && l.track == Track.RightSide) l.copy(geometryLength = l.geometryLength + extraLength, geometry = Seq(l.getFirstPoint, l.getLastPoint.copy(y = l.getLastPoint.y + extraLength))) else l
      }

      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkTrackCodePairing(project, inconsistentLinks).distinct
      validationErrors.size should be(1)
    }

    // > 50m case
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 100L, 200L, 300L, 400L), changeTrack = true)
      val extraLength             = 60L
      val inconsistentLinks       = projectLinks.map {l =>
        if (l.endAddrMValue == 400L && l.track == Track.RightSide) l.copy(geometryLength = l.geometryLength + extraLength, geometry = Seq(l.getFirstPoint, l.getLastPoint.copy(y = l.getLastPoint.y + extraLength))) else l
      }

      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkTrackCodePairing(project, inconsistentLinks).distinct
      validationErrors.size should be(1)
    }
  }

  test("Test checkDiscontinuityInsideRoadParts When there are no Discontinuity.Discontinuous codes inside a road part Then should not be any error") {
    runWithRollback {
      val project = setUpProjectWithLinks(RoadAddressChangeType.Transfer, Seq(0L, 10L, 20L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad)
      val allLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val reservedParts = projectReservedPartDAO.fetchReservedRoadParts(project.id)
      val errors = allLinks.groupBy(l => (l.roadNumber, l.roadPartNumber)).flatMap(g => projectValidator.checkRoadContinuityCodes(project.copy(reservedParts = reservedParts), g._2).distinct)
      errors.size should be(0)
      sqlu"""UPDATE PROJECT_LINK SET DISCONTINUITY_TYPE = 2  WHERE ROAD_NUMBER = 19999 AND ROAD_PART_NUMBER = 1 AND DISCONTINUITY_TYPE <> 1""".execute
      val linksAfterTransfer = projectLinkDAO.fetchProjectLinks(project.id)
      val errorsAfterTransfer = linksAfterTransfer.groupBy(l => (l.roadNumber, l.roadPartNumber)).flatMap(g => projectValidator.checkRoadContinuityCodes(project.copy(reservedParts = reservedParts), g._2).distinct)
      linksAfterTransfer.head.connected(linksAfterTransfer.last) should be(true)
      //Should return DiscontinuityInsideRoadPart, ConnectedDiscontinuousLink
      errorsAfterTransfer.size should be(2)
    }
  }

  test("Test checkRoadContinuityCodes When there is Discontinuity.Discontinuous ending in ramp road between parts (of any kind) Then should not give any error") {
    runWithRollback {
      val project = util.setUpProjectWithRampLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L))
      val projectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      mockEmptyRoadAddressServiceCalls()
      val errors = projectValidator.checkRoadContinuityCodes(project, projectLinks, isRampValidation = true).distinct
      errors should have size 0
      val (starting, last) = projectLinks.splitAt(3)

      val raId = Sequences.nextRoadwayId
      val startDate = DateTime.now()
      val linearLocationId = Sequences.nextLinearLocationId
      val ra = Seq(
        //Combined
        Roadway(raId, roadwayNumber1, 39998L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 20L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None),
        Roadway(raId + 1, roadwayNumber2, 39998L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 10L, 30L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, startDate, None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(2.0, 30.0), Point(0.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 1, 2, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(2.0, 30.0), Point(7.0, 35.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(startDate), None),
        LinearLocation(linearLocationId + 3, 1, 2000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(7.0, 35.0), Point(0.0, 40.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(startDate), None)
      )
      roadwayDAO.create(ra)
      linearLocationDAO.create(linearLocations)

      projectReservedPartDAO.reserveRoadPart(project.id, 39999L, 20L, "u")

      projectLinkDAO.create((starting ++ last.map(_.copy(discontinuity = Discontinuity.EndOfRoad)))
        .map(_.copy(id = NewIdValue, roadPartNumber = 20L, createdBy = Some("I"))))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(39999L, 20L, false, false, false)).thenReturn(Seq.empty[RoadAddress])

      val updProject = projectService.fetchProjectById(project.id).get
      mockEmptyRoadAddressServiceCalls()
      projectValidator.checkRoadContinuityCodes(updProject,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Discontinuous)), isRampValidation = true).distinct should have size 0
    }
  }

  test("Test checkRoadContinuityCodes When next part exists in road address / project link table and is not connected Then Project Links could be only Discontinuity.Discontinuous") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L))
      val raId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId

      val ra = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.AgainstDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val roadway = Roadway(raId, roadwayNumber1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(10.0, 40.0), Point(10.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(ra)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(None)

      val errors = projectValidator.checkRoadContinuityCodes(project, projectLinks).distinct
      errors should have size 1
      errors.head.validationError should be(projectValidator.ValidationErrorList.DiscontinuousFound)

      val (starting, last) = projectLinks.splitAt(3)
      val errorsUpd = projectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.Discontinuous))).distinct
      errorsUpd should have size 0

      val errorsUpd2 = projectValidator.checkRoadContinuityCodes(project,
        starting ++ last.map(_.copy(discontinuity = Discontinuity.MinorDiscontinuity))).distinct
      errorsUpd2 should have size 1
    }
  }

  test("Test checkRoadContinuityCodes When there is transfer on last part to another previous part Then should not exist any error if the last link of the previous part is the only one that have 1 - Tien Loppu continuity") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val ra = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, Some(DateTime.now()), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0,  0.0), Point(0.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, Some(DateTime.now()), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 10.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val roadways = Seq(Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None),
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None))

      val linearLocations = Seq(
        LinearLocation(linearLocationId,     1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.now()), None),
        LinearLocation(linearLocationId + 1, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 10.0), Point(0.0, 20.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(DateTime.now()), None))

      roadwayDAO.create(roadways)
      linearLocationDAO.create(linearLocations)

      val project = setUpProjectWithLinks(RoadAddressChangeType.Transfer, Seq(0L, 10L), discontinuity = Discontinuity.Continuous, roadwayId = roadways.head.id)
      projectReservedPartDAO.reserveRoadPart(project.id, 19999L, 2L, "u")
      val addrMNew = Seq(0L, 10L)
      val links = addrMNew.init.zip(addrMNew.tail).map { case (st, en) =>
        projectLink(st, en, Track.Combined, project.id, RoadAddressChangeType.Transfer, 19999L, 2L, Discontinuity.EndOfRoad, roadwayId = roadways.last.id).copy(geometry = Seq(Point(0.0, 10.0), Point(0.0, 20.0)))
      }
      projectLinkDAO.create(links)
      val allLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val reservedParts = projectReservedPartDAO.fetchReservedRoadParts(project.id)
      val formedParts = projectReservedPartDAO.fetchFormedRoadParts(project.id)

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(19999L, 2L)).thenReturn(Seq(ra.last))
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(None)

      val errors = allLinks.groupBy(l => (l.roadNumber, l.roadPartNumber)).flatMap(g => projectValidator.checkRoadContinuityCodes(project.copy(reservedParts = reservedParts, formedParts = formedParts), g._2).distinct)
      errors.size should be(0)
      sqlu"""UPDATE PROJECT_LINK SET ROAD_PART_NUMBER = 1, STATUS = 3, START_ADDR_M = 10, END_ADDR_M = 20 WHERE ROAD_NUMBER = 19999 AND ROAD_PART_NUMBER = 2""".execute
      val linksAfterTransfer = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(19999L, 2L)).thenReturn(Seq(ra.last))
      when(mockRoadAddressService.getRoadAddressesFiltered(19999L, 1L)).thenReturn(Seq(ra.head))
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(None)

      val errorsAfterTransfer = linksAfterTransfer.groupBy(l => (l.roadNumber, l.roadPartNumber)).flatMap(g => projectValidator.checkRoadContinuityCodes(project.copy(reservedParts = reservedParts), g._2).distinct)

      linksAfterTransfer.head.connected(linksAfterTransfer.last) should be(true)
      errorsAfterTransfer.size should be(0)
    }
  }

  test("Test checkRoadContinuityCodes When there is a road end on a previous connected road part outside of project Then should be a validation error") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId

      val ra = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val roadway = Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(10L, 20L), roads = Seq((19999L, 2L, "Test road")), discontinuity = Discontinuity.EndOfRoad)
      val editedProjectLinks = projectLinks.map(pl => pl.copy(geometry=Seq(Point(0.0, 50.0+pl.startMValue), Point(0.0, 50.0+pl.endMValue))))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L,2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra.head))
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(Some(1L))
      val errors = projectValidator.validateProject(project, editedProjectLinks)
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.DoubleEndOfRoad.value)
    }
  }

  test("Test checkRoadContinuityCodes When there is a discontinuous code on a connected previous road part outside of project Then should be a validation error") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId

      val ra = Seq(
        RoadAddress(12345, linearLocationId, 20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous, 0L, 10L,
          Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis,
          (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val roadway = Roadway(raId, roadwayNumber1, 20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous,
        0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(10L, 20L), roads = Seq((20000L, 2L, "Test road")), discontinuity = Discontinuity.EndOfRoad)
      val editedProjectLinks = projectLinks.map(pl => pl.copy(geometry=Seq(Point(0.0, 50.0+pl.startMValue), Point(0.0, 50.0+pl.endMValue))))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L,2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra.head))
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(Some(1L))

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(project, editedProjectLinks)
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.DiscontinuousCodeOnConnectedRoadPartOutside.value)
    }
  }

  test("Test checkDiscontinuityOnPreviousRoadPart When a road part is terminated from between two road parts causing a discontinuity on that road number Then should be a validation error") {
    runWithRollback {
      val ra1Id = Sequences.nextRoadwayId
      val ra3Id = Sequences.nextRoadwayId
      val linearLocation1Id = Sequences.nextLinearLocationId
      val linearLocation3Id = Sequences.nextLinearLocationId

      val ra = Seq(
        RoadAddress(ra1Id, linearLocation1Id, 20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis,
          (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(ra3Id, linearLocation3Id, 20000L, 3L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis,
          (None, None), Seq(Point(0.0, 60.0), Point(0.0, 70.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber3, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Termination, Seq(0L, 10L), roads= Seq((20000L, 2L, "Test road")), discontinuity = Discontinuity.Continuous)
      val geometryProjectLinks = projectLinks.map(pl => pl.copy(geometry=Seq(Point(0.0, 50.0+pl.startMValue), Point(0.0, 50.0+pl.endMValue))))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L, 3L))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(20000L, 1L, fetchOnlyEnd = true)).thenReturn(ra.init)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(20000L, 3L)).thenReturn(ra.tail)

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(project, geometryProjectLinks)
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.NotDiscontinuousCodeOnDisconnectedRoadPartOutside.value)
    }
  }

  test("Test checkDiscontinuityOnPreviousRoadPart When two road parts are terminated from between two road parts causing a discontinuity on that road number Then should be only one validation error") {
    runWithRollback {
      val ra1Id = Sequences.nextRoadwayId
      val ra3Id = Sequences.nextRoadwayId
      val linearLocation1Id = Sequences.nextLinearLocationId
      val linearLocation3Id = Sequences.nextLinearLocationId

      val ra = Seq(
        RoadAddress(ra1Id, linearLocation1Id, 20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis,
          (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(ra3Id, linearLocation3Id, 20000L, 4L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis,
          (None, None), Seq(Point(0.0, 70.0), Point(0.0, 80.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber3, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Termination, Seq(0L, 10L), roads= Seq((20000L, 2L, "Test road"), (20000L, 3L, "Test road")), discontinuity = Discontinuity.Continuous)
      val geometryProjectLinks = projectLinks.map(pl => pl.copy(geometry=Seq(Point(0.0, 50.0+pl.startMValue + (10.0*(pl.roadPartNumber-2L))), Point(0.0, 50.0+pl.endMValue+ (10.0*(pl.roadPartNumber-2L))))))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 4L))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(20000L, 1L, fetchOnlyEnd = true)).thenReturn(ra.init)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(20000L, 4L)).thenReturn(ra.tail)

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(project, geometryProjectLinks)
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.NotDiscontinuousCodeOnDisconnectedRoadPartOutside.value)
    }
  }

  test("Test checkDiscontinuityOnPreviousRoadPart When the last two road parts of a road number are terminated causing the end of road to shift on that road number Then should be only one validation error") {
    runWithRollback {
      val ra1Id = Sequences.nextRoadwayId
      val linearLocation1Id = Sequences.nextLinearLocationId

      val ra = Seq(
        RoadAddress(ra1Id, linearLocation1Id, 20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
          0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis,
          (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Termination, Seq(0L, 10L), roads= Seq((20000L, 2L, "Test road"), (20000L, 3L, "Test road")), discontinuity = Discontinuity.Continuous)
      val geometryProjectLinks = projectLinks.map(pl => pl.copy(geometry=Seq(Point(0.0, 50.0+pl.startMValue + (10.0*(pl.roadPartNumber-2L))), Point(0.0, 50.0+pl.endMValue+ (10.0*(pl.roadPartNumber-2L))))))
      val withDiscontinuity = geometryProjectLinks.init :+ geometryProjectLinks.last.copy(discontinuity = Discontinuity.EndOfRoad)

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(20000L, 1L, fetchOnlyEnd = true)).thenReturn(ra)

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(project, withDiscontinuity)
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.TerminationContinuity.value)
    }
  }

  /**
   * Test case:
   *                        /
   *                       / RP3
   *                      /
   *            RP1      /      RP2 (terminated)
   *          ---------->      -------->
   *         Discontinuous
   */
  test("Test checkDiscontinuityOnPreviousRoadPart When a road part that is disconnected from the previous road part is terminated and the next road part is connected to the previous road part Then should be a validation error") {
    runWithRollback {
      val ra1Id = Sequences.nextRoadwayId
      val ra3Id = Sequences.nextRoadwayId
      val linearLocation1Id = Sequences.nextLinearLocationId
      val linearLocation3Id = Sequences.nextLinearLocationId

      val ra = Seq(
        RoadAddress(ra1Id, linearLocation1Id, 20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous,
          0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis,
          (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(ra3Id, linearLocation3Id, 20000L, 3L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,
          0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis,
          (None, None), Seq(Point(0.0, 50.0), Point(10.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber3, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Termination, Seq(0L, 10L), roads= Seq((20000L, 2L, "Test road")), discontinuity = Discontinuity.Discontinuous)
      val geometryProjectLinks = projectLinks.map(pl => pl.copy(geometry=Seq(Point(0.0, 60.0+pl.startMValue), Point(0.0, 60.0+pl.endMValue))))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L, 3L))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(20000L, 1L, fetchOnlyEnd = true)).thenReturn(ra.init)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(20000L, 3L)).thenReturn(ra.tail)

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(project, geometryProjectLinks)
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.DiscontinuousCodeOnConnectedRoadPartOutside.value)
    }
  }


  test("Test checkRoadContinuityCodes When there is a continuous code on a disconnected previous road part outside of project Then should be a validation error") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId

      val roadway = Roadway(raId, roadwayNumber1, 20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous,
        0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      val ra = Seq(
        RoadAddress(12345, linearLocationId, 20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(10L, 20L), roads = Seq((20000L, 2L, "Test road")), discontinuity = Discontinuity.EndOfRoad)
      val editedProjectLinks = projectLinks.map(pl => pl.copy(geometry=Seq(Point(0.0, 60.0+pl.startMValue), Point(0.0, 60.0+pl.endMValue))))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra.head))
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(Some(1L))

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(project, editedProjectLinks)
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.NotDiscontinuousCodeOnDisconnectedRoadPartOutside.value)
    }
  }

  test("Test checkRoadContinuityCodes When there is a continuous code on a previous road part outside of project" +
    " and the roadpart in project has two tracks where only one track is connected Then should be no validation error") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId

      val roadway = Roadway(raId, roadwayNumber1, 20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous,
        0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      val ra = Seq(
        RoadAddress(12345, linearLocationId, 20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(10L, 20L), changeTrack = true, roads = Seq((20000L, 2L, "Test road")), discontinuity = Discontinuity.EndOfRoad)
      val editedProjectLinks = projectLinks.map(pl => pl.copy(geometry= if (pl.track != Track.RightSide) Seq(Point(pl.getFirstPoint.x, 50.0+pl.startMValue), Point(pl.getFirstPoint.x, 50.0+pl.endMValue)) else
          Seq(Point(5.0, 50.0+pl.startMValue), Point(5.0, 50.0+pl.endMValue))))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra.head))
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(Some(1L))

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(project, editedProjectLinks)
      errors should have size 0
    }
  }

  test("Test checkRoadContinuityCodes When there is a continuous code on a disconnected previous two-track road part outside of project Then should be a validation error") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId

      val roadway = Roadway(raId, roadwayNumber1, 20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous,
        0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      val ra = Seq(
        RoadAddress(12345, linearLocationId, 20000L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(10L, 20L), roads = Seq((20000L, 2L, "Test road")), discontinuity = Discontinuity.EndOfRoad)
      val editedProjectLinks = projectLinks.map(pl => pl.copy(geometry=Seq(Point(0.0, 60.0+pl.startMValue), Point(0.0, 60.0+pl.endMValue))))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra.head))
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(Some(1L))

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(project, editedProjectLinks)
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.NotDiscontinuousCodeOnDisconnectedRoadPartOutside.value)
    }
  }

  test("Test checkRoadContinuityCodes where there is an ElyChange code on a previous road part outside of a project but Ely number doesn't change Then should be a validation error") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId

      val roadway = Roadway(raId, roadwayNumber1, 20001L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.ChangingELYCode,
        0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, DateTime.now().getMillis, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 10.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      val ra = Seq(
        RoadAddress(12345, linearLocationId, 20001L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.ChangingELYCode, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 10.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Unchanged, Seq(0, 10), roads=Seq((20001L, 2L, "test road")), discontinuity = Discontinuity.EndOfRoad, ely=8)
      val projectLinksWithGeometry = projectLinks.map(pl => pl.copy(geometry=Seq(Point(0.0, 20.0+pl.startMValue), Point(0.0, 20.0+pl.endMValue))))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra.head))
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(Some(1L))

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(project, projectLinksWithGeometry)
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.ElyDiscontinuityCodeBeforeProjectButNoElyChange.value)
    }
  }


  test("Test checkRoadContinuityCodes where there is no ElyChange code or Discontinuous code on a previous road part outside of a project but Ely number changes Then should be a validation error") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId

      val roadway = Roadway(raId, roadwayNumber1, 20001L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
        0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, DateTime.now().getMillis, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 10.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      val ra = Seq(
        RoadAddress(12345, linearLocationId, 20001L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 10.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Unchanged, Seq(0, 10), roads=Seq((20001L, 2L, "test road")), discontinuity = Discontinuity.EndOfRoad, ely=9)
      val projectLinksWithGeometry = projectLinks.map(pl => pl.copy(geometry=Seq(Point(0.0, 20.0+pl.startMValue), Point(0.0, 20.0+pl.endMValue))))

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra.head))
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(Some(1L))

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(project, projectLinksWithGeometry)
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.WrongDiscontinuityBeforeProjectWithElyChangeInProject.value)
    }
  }

  test("Test checkRoadContinuityCodes When there is a road end on previous road part outside of project for 1 & 2 track codes Then should be a validation error") {
    runWithRollback {

      val raId1 = Sequences.nextRoadwayId
      val raId2 = Sequences.nextRoadwayId
      val linearLocationId1 = Sequences.nextLinearLocationId
      val linearLocationId2 = Sequences.nextLinearLocationId

      val ra = Seq(
        RoadAddress(12345, linearLocationId1, 19999L, 1L, AdministrativeClass.State, Track.LeftSide,  Discontinuity.EndOfRoad, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId1, 19999L, 1L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(5.0, 40.0), Point(5.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val roadways = Seq(
        Roadway(raId1, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.LeftSide, Discontinuity.EndOfRoad,
          0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None),
        Roadway(raId2, roadwayNumber2, 19999L, 1L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad,
          0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)
      )

      val linearLocations = Seq(
        LinearLocation(linearLocationId1, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 40.0), Point(0.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None),
        LinearLocation(linearLocationId2, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(5.0, 40.0), Point(5.0, 50.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None)
      )

      roadwayDAO.create(roadways)
      linearLocationDAO.create(linearLocations)

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(ra)
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(Some(1L))

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(10L, 20L), changeTrack = true, roads = Seq((19999L, 2L, "Test road")), discontinuity = Discontinuity.EndOfRoad)
      val editedProjectLinks = projectLinks.map(pl => pl.copy(geometry= if (pl.track != Track.RightSide) Seq(Point(pl.getFirstPoint.x, 50.0+pl.startMValue), Point(pl.getFirstPoint.x, 50.0+pl.endMValue)) else
        Seq(Point(5.0, 50.0+pl.startMValue), Point(5.0, 50.0+pl.endMValue))))

      val errors = projectValidator.checkDiscontinuityOnPreviousRoadPart(project, editedProjectLinks)
      errors should have size 1
      errors.head.validationError.value should be(projectValidator.ValidationErrorList.DoubleEndOfRoad.value)
    }
  }

  test("Test checkRoadContinuityCodes When there is any of the track codes on the end of a part are not End Of Road Then validator should return MissingEndOfRoad validation error") {
    runWithRollback {

      val raId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId

      val ra = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val roadway = Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val (project, _) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(10L, 20L), roads = Seq((19999L, 1L, "Test road")), discontinuity = Discontinuity.Continuous, changeTrack = true)
      projectLinkDAO.create(Seq(util.toProjectLink(project, RoadAddressChangeType.New)(ra.head)))
      val projectLinks = projectLinkDAO.fetchProjectLinks(project.id)

      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkRoadContinuityCodes(project, projectLinks)
      //Should have error in both tracks
      validationErrors.size should be(1)
      validationErrors.head.projectId should be(project.id)
      validationErrors.head.validationError.value should be(projectValidator.ValidationErrorList.MissingEndOfRoad.value)
      validationErrors.head.affectedPlIds.sorted should be(projectLinks.filterNot(_.track == Track.Combined).map(_.id).sorted)
      //Should only have error in LEFT TRACK

      val leftErrors = projectValidator.checkRoadContinuityCodes(project, projectLinks.map(pl => {
        if (pl.track == Track.RightSide)
          pl.copy(discontinuity = Discontinuity.EndOfRoad)
        else pl
      }))
      leftErrors.size should be(1)
      leftErrors.head.projectId should be(project.id)
      leftErrors.head.validationError.value should be(projectValidator.ValidationErrorList.MissingEndOfRoad.value)
      leftErrors.head.affectedPlIds.sorted should be(projectLinks.filter(_.track == Track.LeftSide).map(_.id).sorted)
      //Should only have error in RIGHT TRACK
      val rightErrors = projectValidator.checkRoadContinuityCodes(project, projectLinks.map(pl => {
        if (pl.track == Track.LeftSide)
          pl.copy(discontinuity = Discontinuity.EndOfRoad)
        else pl
      }))
      rightErrors.size should be(1)
      rightErrors.head.projectId should be(project.id)
      rightErrors.head.validationError.value should be(projectValidator.ValidationErrorList.MissingEndOfRoad.value)
      rightErrors.head.affectedPlIds.sorted should be(projectLinks.filter(_.track == Track.RightSide).map(_.id).sorted)
      //Should have no error
      val noErrors = projectValidator.checkRoadContinuityCodes(project, projectLinks.map(pl => {
        if (pl.track != Track.Combined)
          pl.copy(discontinuity = Discontinuity.EndOfRoad)
        else pl
      }))
      noErrors.size should be(0)
    }
  }

  test("Test checkRoadContinuityCodes When there is any of the track codes on the end of a part are not End Of Road Then Validator should return Discontinuity.Discontinuous validation error") {

    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId

      val ra = Seq(
        RoadAddress(12345, linearLocationId, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val roadway = Roadway(raId, roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val linearLocation = LinearLocation(linearLocationId, 1, 1000L.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None)

      roadwayDAO.create(Seq(roadway))
      linearLocationDAO.create(Seq(linearLocation))

      val (project, _) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(10L, 20L, 30L), roads = Seq((19999L, 1L, "Test road")), discontinuity = Discontinuity.Continuous, changeTrack = true)
      projectLinkDAO.create(Seq(util.toProjectLink(project, RoadAddressChangeType.New)(ra.head)))
      projectReservedPartDAO.reserveRoadPart(project.id, 19999L, 2L, "u")
      sqlu"""UPDATE Project_Link Set Road_part_Number = 2, Discontinuity_type = 1, start_addr_m = 0 , end_addr_m = 10 Where project_id = ${project.id} and end_addr_m = 30""".execute
      val projectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val reservedParts = projectReservedPartDAO.fetchReservedRoadParts(project.id)
      val formedParts = projectReservedPartDAO.fetchFormedRoadParts(project.id)
      val projectWithReservations = project.copy(reservedParts = reservedParts, formedParts = formedParts)
      //Should NOT have error in both tracks
      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(ra)
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra.head))
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(Some(1L))

      val noErrors = projectValidator.checkRoadContinuityCodes(projectWithReservations, projectLinks)
      noErrors.size should be(0)

      //Should return Discontinuity.Discontinuous to both Project Links, part number = 1
      val discontinuousGeom = Seq(Point(40.0, 50.0), Point(60.0, 70.0))
      val geometry = Seq(Point(40.0, 50.0), Point(60.0, 70.0))
      val lineString: String = PostGISDatabase.createJGeometry(geometry)
      val geometryQuery = s"ST_GeomFromText('$lineString', 3067)"
      sqlu"""UPDATE PROJECT_LINK Set GEOMETRY = #$geometryQuery Where PROJECT_ID = ${project.id} AND ROAD_PART_NUMBER = 2""".execute
      val errorsAtEnd = projectValidator.checkRoadContinuityCodes(projectWithReservations, projectLinks.map(pl => {
        if (pl.roadPartNumber == 2L)
          pl.copyWithGeometry(discontinuousGeom)
        else pl
      }))
      errorsAtEnd.size should be(1)
      errorsAtEnd.head.validationError.value should be(projectValidator.ValidationErrorList.DiscontinuousFound.value)
      errorsAtEnd.head.affectedPlIds.sorted should be(projectLinks.filter(pl => pl.roadPartNumber == 1L && pl.track != Track.Combined).map(_.id).sorted)
    }
  }

  test("Test checkRoadContinuityCodes When there is a minor discontinuity on only one track of a two track section" +
    "Then should not return validation error") {
    runWithRollback {

      val project = Project(Sequences.nextViiteProjectId, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),"", Seq(), Seq(), None, None)
      projectDAO.create(project)


      val linkId  = 1000.toString
      val ra = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.LeftSide,  Discontinuity.MinorDiscontinuity, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), linkId, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(5.0, 0.0), Point(5.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 1L, AdministrativeClass.State, Track.LeftSide,  Discontinuity.EndOfRoad,          10L, 20L, Some(DateTime.parse("1901-01-01")), None, Some("User"), linkId, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 0.0), Point(5.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12347, linearLocationId + 2, 19999L, 1L, AdministrativeClass.State, Track.RightSide, Discontinuity.Continuous,          0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), linkId, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 0.0), Point(10.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber3, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12348, linearLocationId + 3, 19999L, 1L, AdministrativeClass.State, Track.RightSide, Discontinuity.EndOfRoad,          10L, 20L, Some(DateTime.parse("1901-01-01")), None, Some("User"), linkId, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 10.0), Point(5.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber4, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val projectLinks = ra.map { toProjectLink(project)(_) }

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq.empty[Long])
      when(mockRoadAddressService.getRoadAddressesFiltered(any[Long], any[Long])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq(ra.head))
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(None)

      val errors = projectValidator.checkRoadContinuityCodes(project, projectLinks)
      errors should have size 0
    }
  }

  test("Test checkRoadContinuityCodes for Discontinuity.MinorDiscontinuity and Discontinuity.Discontinuous") {
    runWithRollback {

      val linearLocationId = Sequences.nextLinearLocationId

      // Create roadAddresses that have gap in geometry between second and third
      val ra = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,         0L,  5L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 5, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0,  0.0), Point(0.0,  5.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.MinorDiscontinuity, 5L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 5, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0,  5.0), Point(0.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12347, linearLocationId + 2, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,        10L, 15L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 5, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 15.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber3, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12348, linearLocationId + 3, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,         15L, 20L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 5, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 20.0), Point(0.0, 25.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber4, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val (project, _) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(10L, 20L), roads = Seq((19999L, 1L, "Test road"), (19999L, 2L, "Test road")), discontinuity = Discontinuity.Continuous, changeTrack = true)
      val projectLinks = ra.map {
        toProjectLink(project)(_)
      }

      mockEmptyRoadAddressServiceCalls()

      // Discontinuity.MinorDiscontinuity is ok because we are in same roadpart
      val errors = projectValidator.checkRoadContinuityCodes(project, projectLinks)
      errors should have size 0

      // Discontinuity.MinorDiscontinuity is not anymore ok because we have now two roadparts
      val (first2Links, restOfLinks) = projectLinks.partition(_.endAddrMValue <= 10)
      val changedFirstRestOfLinksToPart2 = restOfLinks.head.copy(roadPartNumber = 2, startAddrMValue = 0, endAddrMValue = 5)
      val changedLastRestOfLinksToPart2 = restOfLinks.last.copy(roadPartNumber = 2, startAddrMValue = 5, endAddrMValue = 10)
      val formedParts = List(
        ProjectReservedPart(project.id, 19999L, 1L, Some(0L), Some(Discontinuity.Continuous), None, Option(1L), None, None, None),
        ProjectReservedPart(project.id, 19999L, 2L, Some(0L), Some(Discontinuity.Continuous), None, Option(1L), None, None, None)
      )
      val errors2 = projectValidator.checkRoadContinuityCodes(project.copy(formedParts = formedParts), first2Links ++ Seq(changedFirstRestOfLinksToPart2, changedLastRestOfLinksToPart2))
      errors2 should have size 1
      errors2.head.validationError.value should be(projectValidator.ValidationErrorList.DiscontinuousFound.value)

      // Discontinuity.Discontinuous is ok because we have two roadparts
      val changedFirstFirst2Links = first2Links.head.copy()
      val changedLastFirst2Links = first2Links.last.copy(discontinuity = Discontinuity.Discontinuous)
      val errors3 = projectValidator.checkRoadContinuityCodes(project.copy(formedParts = formedParts), Seq(changedFirstFirst2Links, changedLastFirst2Links) ++ Seq(changedFirstRestOfLinksToPart2, changedLastRestOfLinksToPart2))
      errors3 should have size 0

    }
  }

  test("Test checkProjectContinuity When there is End Of Road in the middle of road part Then Validator should return validation error") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L), discontinuity = Discontinuity.EndOfRoad)
      val errorLinks = projectLinks.map { l =>
        if (l.startAddrMValue == 10)
          l.copy(discontinuity = Discontinuity.EndOfRoad)
        else l
      }
      mockEmptyRoadAddressServiceCalls()
      val validationErrors = projectValidator.checkProjectContinuity(project, errorLinks.distinct)
      validationErrors.size should be(1)
      validationErrors.head.validationError.value should be(projectValidator.ValidationErrorList.EndOfRoadMiddleOfPart.value)
    }
  }

  test("Test projectValidator.checkProjectElyCodes When converting all of ely codes to a new one and putting the correct link status then validator should not return an error") {
    runWithRollback {
      val project = setUpProjectWithLinks(RoadAddressChangeType.Renumeration, Seq(0L, 10L, 20L, 30L, 40L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.ChangingELYCode)
      val originalProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      addProjectLinksToProject(RoadAddressChangeType.Renumeration, Seq(40L, 50L, 60L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad, project = project, roadPartNumber = 2L, ely = 50L)
      val additionalProjectLinks2 = projectLinkDAO.fetchProjectLinks(project.id)
      val newLinksOnly = additionalProjectLinks2.diff(originalProjectLinks)
      val min = newLinksOnly.minBy(_.startAddrMValue).startAddrMValue
      newLinksOnly.foreach(p => {
        projectLinkDAO.updateAddrMValues(p.copy(startAddrMValue = p.startAddrMValue - min, endAddrMValue = p.endAddrMValue - min, originalStartAddrMValue = p.originalStartAddrMValue - min, originalEndAddrMValue = p.originalEndAddrMValue - min))
      })
      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      updatedProjectLinks.groupBy(_.ely).size should be(2)
      mockEmptyRoadAddressServiceCalls()
      val rw = updatedProjectLinks.map(toRoadwayAndLinearLocation).map(_._2).map(p => p.copy(ely = 8L))
      roadwayDAO.create(rw)
      val elyCodeCheck = projectValidator.checkProjectElyCodes(project, updatedProjectLinks)
      elyCodeCheck.size should be(0)
    }
  }


  test("Test projectValidator.checkProjectElyCodes When converting all of ely codes to a new one and putting the correct link status but using many different elys in the change then validator should return an error") {
    runWithRollback {
      val project = setUpProjectWithLinks(RoadAddressChangeType.Unchanged, Seq(0L, 10L, 20L, 30L, 40L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.ChangingELYCode, ely = 10, withRoadInfo = true)
      val originalProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      addProjectLinksToProject(RoadAddressChangeType.Unchanged, Seq(40L, 50L, 60L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad, project = project, ely = 10L, withRoadInfo = true)
      val additionalProjectLinks2 = projectLinkDAO.fetchProjectLinks(project.id)
      val newLinksOnly = additionalProjectLinks2.diff(originalProjectLinks)
      val min = newLinksOnly.minBy(_.startAddrMValue).startAddrMValue
      newLinksOnly.foreach(p => {
        projectLinkDAO.updateAddrMValues(p.copy(startAddrMValue = p.startAddrMValue - min, endAddrMValue = p.endAddrMValue - min, originalStartAddrMValue = p.originalStartAddrMValue - min, originalEndAddrMValue = p.originalEndAddrMValue - min))
        sqlu"""UPDATE project_link SET ely = ${scala.util.Random.nextInt(5)} WHERE id = ${p.id}""".execute
      })
      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      updatedProjectLinks.map(_.roadwayId)
      mockEmptyRoadAddressServiceCalls()
      updatedProjectLinks.foreach(p =>
        sqlu"""UPDATE roadway Set ely = 8 Where road_number = ${p.roadNumber} and road_part_number = ${p.roadPartNumber} """.execute
      )
      val elyCodeCheck = projectValidator.checkProjectElyCodes(project, updatedProjectLinks)
      elyCodeCheck.size should be(1)

      elyCodeCheck.head.projectId should be(project.id)
      elyCodeCheck.head.validationError should be(projectValidator.ValidationErrorList.MultipleElyInPart)
    }
  }

  test("Test projectValidator.checkProjectElyCodes When converting all of ely codes to a new one and putting the correct link status but not putting the correct discontinuity value in the change then validator should return an error") {
    runWithRollback {
      val project = setUpProjectWithLinks(RoadAddressChangeType.Unchanged, Seq(0L, 10L, 20L, 30L, 40L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.Continuous, ely = 1L)
      val originalProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      addProjectLinksToProject(RoadAddressChangeType.Transfer, Seq(40L, 50L, 60L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad, project = project, roadPartNumber = 2L, ely = 2L)
      val additionalProjectLinks2 = projectLinkDAO.fetchProjectLinks(project.id)
      val newLinksOnly = additionalProjectLinks2.diff(originalProjectLinks)
      val min = newLinksOnly.minBy(_.startAddrMValue).startAddrMValue
      newLinksOnly.foreach(p => {
        projectLinkDAO.updateAddrMValues(p.copy(startAddrMValue = p.startAddrMValue - min, endAddrMValue = p.endAddrMValue - min, originalStartAddrMValue = p.originalStartAddrMValue - min, originalEndAddrMValue = p.originalEndAddrMValue - min))
      })
      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      mockEmptyRoadAddressServiceCalls()
      val rw = updatedProjectLinks.map(toRoadwayAndLinearLocation).map(_._2).map(p => p.copy(ely = 8))
      roadwayDAO.create(rw)
      val elyCodeCheck = projectValidator.checkProjectElyCodes(project, updatedProjectLinks)
      elyCodeCheck.size should be(1)
      elyCodeCheck.head.projectId should be(project.id)
      elyCodeCheck.head.validationError should be(projectValidator.ValidationErrorList.ElyCodeChangeDetected)
    }
  }

  test("Test projectValidator.checkProjectElyCodes When converting all of ely codes to a new one and putting the correct link status and discontinuity value in the change but not changing the next road part then validator should return an error") {
    runWithRollback {
      val project = setUpProjectWithLinks(RoadAddressChangeType.Unchanged, Seq(0L, 10L, 20L, 30L, 40L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.ChangingELYCode, ely = 1L)
      val originalProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      addProjectLinksToProject(RoadAddressChangeType.Transfer, Seq(40L, 50L, 60L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad, project = project, ely = 1L, roadwayNumberValue = 0L)
      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      mockEmptyRoadAddressServiceCalls()
      val rw = updatedProjectLinks.map(toRoadwayAndLinearLocation).map(_._2)
      roadwayDAO.create(rw)
      val elyCodeCheck = projectValidator.checkProjectElyCodes(project, updatedProjectLinks)
      elyCodeCheck.size should be(1)
      elyCodeCheck.head.projectId should be(project.id)
      elyCodeCheck.head.validationError should be(projectValidator.ValidationErrorList.ElyCodeChangeButNotOnEnd)
    }
  }

  test("Test projectValidator.checkProjectElyCodes When converting all of ely codes to a new one and putting the correct link status and discontinuity value in the change but not changing the Ely Code on the next road part then validator should return an error") {
    runWithRollback {
      val project = setUpProjectWithLinks(RoadAddressChangeType.Unchanged, Seq(0L, 10L, 20L, 30L, 40L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.ChangingELYCode, ely = 1L)
      addProjectLinksToProject(RoadAddressChangeType.Transfer, Seq(40L, 50L, 60L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad, project = project, roadPartNumber = 2L, ely = 1L)
      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      mockEmptyRoadAddressServiceCalls()
      val rw = updatedProjectLinks.map(toRoadwayAndLinearLocation).map(_._2)
      roadwayDAO.create(rw)
      val elyCodeCheck = projectValidator.checkProjectElyCodes(project, updatedProjectLinks)
      elyCodeCheck.size should be(1)
      elyCodeCheck.head.projectId should be(project.id)
      elyCodeCheck.head.validationError.value should be(projectValidator.ValidationErrorList.ElyCodeDiscontinuityChangeButNoElyChange.value)
    }
  }

  //TODO - needs to be tested after VIITE-1788
  test("Test projectValidator.checkRoadContinuityCodes When issuing a Ely change and one of the roads becomes the end of it Then the discontinuity codes validations should return a MissingEndOfRoad error.") {
    runWithRollback {
      val project = setUpProjectWithLinks(RoadAddressChangeType.Unchanged, Seq(0L, 10L, 20L, 30L, 40L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.Continuous, ely = 1L)
      addProjectLinksToProject           (RoadAddressChangeType.Transfer,  Seq(60L, 70L, 80L),          discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad, project = project, roadNumber = 20000L, ely = 2L)
      val updatedProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
      mockEmptyRoadAddressServiceCalls()
      val rw = updatedProjectLinks.map(toRoadwayAndLinearLocation).map(_._2)
      roadwayDAO.create(rw)
      val checkRoadContinuityChecks = projectValidator.checkRoadContinuityCodes(project, updatedProjectLinks)
      checkRoadContinuityChecks.size should be(1)
      checkRoadContinuityChecks.head.validationError should be(projectValidator.ValidationErrorList.MissingEndOfRoad)
      checkRoadContinuityChecks.head.affectedPlIds.size should be(1)
      checkRoadContinuityChecks.head.affectedPlIds.head should be(updatedProjectLinks.find(p => p.roadNumber != 20000L && p.endAddrMValue == updatedProjectLinks.filter(_.roadNumber != 20000L).maxBy(_.endAddrMValue).endAddrMValue).get.id)
    }
  }

  test("Test checkRoadContinuityCodes When there is transfer on last part to another road Then should give error MissingEndOfRoad if the last link of the previous and new last part does not have continuity 1 - Tien Loppu") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val ra = Seq(
        RoadAddress(12345, linearLocationId,     19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, Some(DateTime.now()), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point( 0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12346, linearLocationId + 1, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,  0L, 10L, Some(DateTime.now()), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 10.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val roadways = Seq(
        Roadway(raId,     roadwayNumber1, 19999L, 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None),
        Roadway(raId + 1, roadwayNumber2, 19999L, 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,  0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None))

      val linearLocations = Seq(LinearLocation(linearLocationId, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 0.0), Point(0.0, 10.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber1, Some(DateTime.now()), None), LinearLocation(linearLocationId + 1, 1, 1000.toString, 0.0, 10.0, SideCode.TowardsDigitizing, 10000000000L, (CalibrationPointReference(Some(0L)), CalibrationPointReference(Some(10L))), Seq(Point(0.0, 10.0), Point(0.0, 20.0)), LinkGeomSource.ComplementaryLinkInterface, roadwayNumber2, Some(DateTime.now()), None))

      roadwayDAO.create(roadways)
      linearLocationDAO.create(linearLocations)

      val project = setUpProjectWithLinks(RoadAddressChangeType.Transfer, Seq(0L, 10L), discontinuity = Discontinuity.Continuous, roadwayId = roadways.head.id)
      projectReservedPartDAO.reserveRoadPart(project.id, 19999L, 2L, "u")
      val addrMNew = Seq(0L, 10L)
      val links = addrMNew.init.zip(addrMNew.tail).map { case (st, en) =>
        projectLink(st, en, Track.Combined, project.id, RoadAddressChangeType.Transfer, 19999L, 2L, Discontinuity.EndOfRoad, roadwayId = roadways.last.id).copy(geometry = Seq(Point(0.0, 10.0), Point(0.0, 20.0)))
      }
      projectLinkDAO.create(links)
      val allLinks = projectLinkDAO.fetchProjectLinks(project.id)
      val reservedParts = projectReservedPartDAO.fetchReservedRoadParts(project.id)
      val formedParts = projectReservedPartDAO.fetchFormedRoadParts(project.id)

      when(mockRoadAddressService.getValidRoadAddressParts(any[Long], any[DateTime])).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(19999L, 2L)).thenReturn(Seq(ra.last))
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(None)

      val errors = allLinks.groupBy(l => (l.roadNumber, l.roadPartNumber)).flatMap(g => projectValidator.checkRoadContinuityCodes(project.copy(reservedParts = reservedParts, formedParts = formedParts), g._2).distinct)
      errors.size should be(0)
      when(mockLinearLocationDAO.fetchByRoadways(any[Set[Long]])).thenReturn(Seq(linearLocations.last))
      when(mockRoadwayAddressMapper.getRoadAddressesByRoadway(any[Seq[Roadway]])).thenReturn(Seq(ra.last))
      when(mockRoadAddressService.getRoadAddressesByRoadwayIds(any[Seq[Long]])).thenReturn(Seq(ra.last))
      when(mockRoadAddressService.getRoadAddressesFiltered(19999L, 2L)).thenReturn(Seq(ra.last))
      projectService.updateProjectLinks(project.id, allLinks.filter(_.roadPartNumber == 2).map(_.id).toSet, Seq(), RoadAddressChangeType.Transfer, "silari", 20000, 1, 0, None, 1, 1, Some(1), reversed = false, Some("asd"), None)
      val linksAfterTransfer = projectLinkDAO.fetchProjectLinks(project.id).sortBy(_.startAddrMValue)

      when(mockRoadAddressService.getRoadAddressesFiltered(20000L, 2L)).thenReturn(Seq())
      when(mockRoadAddressService.getValidRoadAddressParts(20000L, project.startDate)).thenReturn(Seq())
      when(mockRoadAddressService.getValidRoadAddressParts(19999L, project.startDate)).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getRoadAddressesFiltered(19999L, 1L)).thenReturn(Seq(ra.head))
      when(mockRoadAddressService.fetchLinearLocationByBoundingBox(any[BoundingRectangle], any[Seq[(Int, Int)]])).thenReturn(Seq.empty[LinearLocation])
      when(mockRoadAddressService.getCurrentRoadAddresses(any[Seq[LinearLocation]])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(any[Long], any[Long], any[Boolean], any[Boolean], any[Boolean])).thenReturn(Seq.empty[RoadAddress])
      when(mockRoadAddressService.getPreviousRoadPartNumber(any[Long], any[Long])).thenReturn(None)

      val errorsAfterTransfer = linksAfterTransfer.groupBy(l => (l.roadNumber, l.roadPartNumber)).flatMap(g => projectValidator.checkRoadContinuityCodes(project.copy(reservedParts = reservedParts), g._2).distinct)

      linksAfterTransfer.head.connected(linksAfterTransfer.last) should be(true)
      errorsAfterTransfer.size should be(1)
      errorsAfterTransfer.head.validationError should be(projectValidator.ValidationErrorList.MissingEndOfRoad)
    }
  }

  test("Test checkTrackAdministrativeClass When there are different Road Types in each Track Then ProjectValidator should show DistinctAdministrativeClasssBetweenTracks") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val (left, right) = projectLinks.partition(_.track == Track.LeftSide)
      val endOfRoadLeft = left.init :+ left.last.copy(discontinuity = Discontinuity.EndOfRoad)
      val endOfRoadRight = right.init :+ right.last.copy(discontinuity = Discontinuity.EndOfRoad)
      val endOfRoadSet = endOfRoadLeft ++ endOfRoadRight

      mockEmptyRoadAddressServiceCalls()

      projectValidator.checkRoadContinuityCodes(project, endOfRoadSet).distinct should have size 0
      val brokenContinuity = endOfRoadSet.tail :+ endOfRoadSet.head.copy(administrativeClass = AdministrativeClass.Municipality)
      val errors = projectValidator.checkTrackCodePairing(project, brokenContinuity).distinct
      errors should have size 1
      errors.head.validationError should be(projectValidator.ValidationErrorList.DistinctAdministrativeClassesBetweenTracks)
    }
  }

  test("Test checkTrackAdministrativeClass When there are same Road Types in each Track but in different order Then ProjectValidator should show DistinctAdministrativeClasssBetweenTracks") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val (left, right) = projectLinks.partition(_.track == Track.LeftSide)
      val endOfRoadLeft = left.init :+ left.last.copy(discontinuity = Discontinuity.EndOfRoad)
      val endOfRoadRight = right.init :+ right.last.copy(discontinuity = Discontinuity.EndOfRoad)
      val leftAdministrativeClass = endOfRoadLeft.init :+ endOfRoadLeft.last.copy(administrativeClass = AdministrativeClass.Municipality)
      val rightAdministrativeClass = endOfRoadRight.head.copy(administrativeClass = AdministrativeClass.Municipality) +: endOfRoadRight.tail
      val endOfRoadSet = leftAdministrativeClass ++ rightAdministrativeClass

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.checkTrackCodePairing(project, endOfRoadSet).distinct
      errors should have size 1
      errors.head.validationError should be(projectValidator.ValidationErrorList.DistinctAdministrativeClassesBetweenTracks)
    }
  }

  test("Test checkTrackAdministrativeClass When there are same Road Types in each Track and in same order Then ProjectValidator shouldn't show DistinctAdministrativeClassesBetweenTracks") {
    runWithRollback {
      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.New, Seq(0L, 10L, 20L, 30L, 40L), changeTrack = true)
      val (left, right) = projectLinks.partition(_.track == Track.LeftSide)
      val endOfRoadLeft = left.init :+ left.last.copy(discontinuity = Discontinuity.EndOfRoad)
      val endOfRoadRight = right.init :+ right.last.copy(discontinuity = Discontinuity.EndOfRoad)
      val leftAdministrativeClass  = endOfRoadLeft.init  :+ endOfRoadLeft.last.copy(administrativeClass = AdministrativeClass.State)
      val rightAdministrativeClass = endOfRoadRight.init :+ endOfRoadRight.last.copy(administrativeClass = AdministrativeClass.State)
      val endOfRoadSet = leftAdministrativeClass ++ rightAdministrativeClass

      mockEmptyRoadAddressServiceCalls()

      val errors = projectValidator.checkTrackCodePairing(project, endOfRoadSet).distinct
      errors should have size 0
    }
  }

  test("Test checkOriginalRoadPartAfterTransfer When a road part with EndOfRoad discontinuity is numbered to another road number where the original road number has another road part" +
       "Then should return WrongDiscontinuityOutsideOfProject error") {
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val ra2Id = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val roadNumber = 20001L
      val newRoadNumber = 20002L

      val roadway = Roadway(raId, roadwayNumber1, roadNumber, roadPartNumber = 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous,
        0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      val roadwayToBeTransferred = Roadway(ra2Id, roadwayNumber2, roadNumber, roadPartNumber = 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,
        0L, 30L, reversed = false, DateTime.now().minusDays(5), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      roadwayDAO.create(Seq(roadway, roadwayToBeTransferred))

      val ra = Seq(
        RoadAddress(12345, linearLocationId, roadNumber, roadPartNumber = 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Continuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 10.0), Point(0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12345, linearLocationId, roadNumber, roadPartNumber = 2L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,  0L, 30L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(0.0, 20.0), Point(0.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Renumeration, Seq(10L, 20L, 30L), roads = Seq((newRoadNumber, 1L, "Test road")), discontinuity = Discontinuity.Continuous)
      val changedProjectLinks = projectLinks.map(pl =>
        pl.copy(roadwayId=ra2Id)
      )
      val addedEndOfRoad = changedProjectLinks.init :+ changedProjectLinks.last.copy(discontinuity=Discontinuity.EndOfRoad)

      when(mockRoadAddressService.getValidRoadAddressParts(roadNumber, project.startDate)).thenReturn(Seq(1L, 2L))
      when(mockRoadAddressService.getValidRoadAddressParts(newRoadNumber, project.startDate)).thenReturn(Seq())
      when(mockRoadAddressService.getPreviousRoadPartNumber(roadNumber, roadPart = 2L)).thenReturn(Some(1L))
      when(mockRoadAddressService.getPreviousRoadPartNumber(newRoadNumber, roadPart = 1L)).thenReturn(None)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(roadNumber, part = 2L)).thenReturn(ra.tail)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(roadNumber, part = 1L, fetchOnlyEnd = true)).thenReturn(ra.init)

      val errors = projectValidator.validateProject(project, addedEndOfRoad)
      errors should have size 1
      errors.head.validationError.value should equal (18)
    }
  }


  test("Test checkOriginalRoadPartAfterTransfer When a road part with Discontinuous discontinuity is numbered to another road number where the original road number has a discontinuous previous road part" +
    "Then should not return WrongDiscontinuityOutsideOfProject error") {
    /**
     * Before project:
     *                                            Reserved for project
     *  Roadnumber 20001L
     *  RoadPart 1 Discontinuous                  RoadPart 2 Discontinuous           RoadPart 3 EndOfRoad
     * |---------------------->                   |------------------>              |---------------------->
     *
     * After Project:
     *  Roadnumber 20001L                         Roadnumber 20002L                  Roadnumber 20001L
     *  RoadPart 1 Discontinuous                  RoadPart 2 EndOfRoad               RoadPart 3 EndOfRoad
     * |---------------------->                   |------------------>              |---------------------->
     *
     */
    runWithRollback {
      val raId = Sequences.nextRoadwayId
      val ra2Id = Sequences.nextRoadwayId
      val ra3Id = Sequences.nextRoadwayId
      val linearLocationId = Sequences.nextLinearLocationId
      val roadNumber = 20001L
      val newRoadNumber = 20002L

      val roadways = Seq(Roadway(raId, roadwayNumber1, roadNumber, roadPartNumber = 1L, AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous,
        0L, 10L, reversed = false, DateTime.now(), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None),
        Roadway(ra3Id, roadwayNumber3, roadNumber,  roadPartNumber =  3L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,
          0L, 20L, reversed = false, DateTime.now().minusDays(5), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None))

      val roadwayToBeTransferred = Roadway(ra2Id, roadwayNumber2, roadNumber,  roadPartNumber =  2L, AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous,
        0L, 30L, reversed = false, DateTime.now().minusDays(5), None, "test_user", None, 8, NoTermination, DateTime.parse("1901-01-01"), None)

      roadwayDAO.create(roadways :+ roadwayToBeTransferred)

      val ra = Seq(
        RoadAddress(12345, linearLocationId, roadNumber,  roadPartNumber =  1L, AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point( 0.0, 10.0), Point( 0.0, 20.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber1, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12345, linearLocationId, roadNumber,  roadPartNumber =  2L, AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous, 0L, 30L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(10.0, 20.0), Point(10.0, 50.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber2, Some(DateTime.parse("1901-01-01")), None, None),
        RoadAddress(12345, linearLocationId, roadNumber,  roadPartNumber =  3L, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad,     0L, 20L, Some(DateTime.parse("1901-01-01")), None, Some("User"), 1000.toString, 0, 10, SideCode.TowardsDigitizing, DateTime.now().getMillis, (None, None), Seq(Point(20.0, 20.0), Point(20.0, 40.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, roadwayNumber3, Some(DateTime.parse("1901-01-01")), None, None)
      )

      val (project, projectLinks) = util.setUpProjectWithLinks(RoadAddressChangeType.Renumeration, Seq(10L, 20L, 30L), roads = Seq((newRoadNumber, 1L, "Test road")), discontinuity = Discontinuity.Continuous)
      val changedProjectLinks = projectLinks.map(pl =>
        pl.copy(roadwayId=ra2Id)
      )
      val addedEndOfRoad = changedProjectLinks.init :+ changedProjectLinks.last.copy(discontinuity=Discontinuity.EndOfRoad)

      when(mockRoadAddressService.getValidRoadAddressParts(roadNumber, project.startDate)).thenReturn(Seq(1L, 2L, 3L))
      when(mockRoadAddressService.getValidRoadAddressParts(newRoadNumber, project.startDate)).thenReturn(Seq())
      when(mockRoadAddressService.getPreviousRoadPartNumber(roadNumber, roadPart = 2L)).thenReturn(Some(1L))
      when(mockRoadAddressService.getPreviousRoadPartNumber(newRoadNumber, roadPart = 1L)).thenReturn(None)
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(roadNumber, part = 1L, fetchOnlyEnd = true)).thenReturn(Seq(ra.init.head))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(roadNumber, part = 2L)).thenReturn(Seq(ra.tail.head))
      when(mockRoadAddressService.getRoadAddressWithRoadAndPart(roadNumber, part = 3L)).thenReturn(Seq(ra.init.last))

      val errors = projectValidator.validateProject(project, addedEndOfRoad)
      errors should have size 0
    }
  }

  test("Test checkSingleAdminClassOnLink When one linkId has two different AdminClasses Then return SingleAdminClassOnLink error.") {
    runWithRollback {
      val project                            = setUpProjectWithLinks(RoadAddressChangeType.Transfer, Seq(0L, 10L, 20L, 30L, 40L), discontinuity = Discontinuity.Continuous, lastLinkDiscontinuity = Discontinuity.EndOfRoad)
      val projectLinks                       = projectLinkDAO.fetchProjectLinks(project.id)
      val sameLinkIds                        = projectLinks.take(2).map(_.copy(linkId = projectLinks.head.linkId))
      val withSameLinkAndDifferentAdminClass = Seq(sameLinkIds.head.copy(administrativeClass = AdministrativeClass.Municipality), sameLinkIds.last) ++ projectLinks.drop(2)

      mockEmptyRoadAddressServiceCalls()
      val errors = projectValidator.checkUniformAdminClassOnLink(project, withSameLinkAndDifferentAdminClass)
      errors should have size 1
      errors.head.validationError.value should equal (projectValidator.ValidationErrorList.UniformAdminClassOnLink.value)
    }
  }
}
