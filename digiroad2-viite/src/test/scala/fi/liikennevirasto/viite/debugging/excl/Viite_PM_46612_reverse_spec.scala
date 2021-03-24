package fi.liikennevirasto.viite

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.{PolyLine, RoadLink}
//import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{Track, ViiteProperties}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.NoCP
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{ProjectAddressLink, RoadAddressLinkLike}
//import org.mockito.Mockito.spy
//import fi.liikennevirasto.viite.process.strategy.RoadAddressSectionCalculatorContext.defaultSectionCalculatorStrategy.assignMValues
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import fi.liikennevirasto.viite.process.strategy.DefaultSectionCalculatorStrategy
import fi.liikennevirasto.viite.util.{StaticTestData, _}
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, spy, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
//import org.mockito.stubbing.Answer
//import org.powermock.api.mockito.PowerMockito

class Viite_PM_46612_reverse_spec extends FunSuite with Matchers with BeforeAndAfter {
  val mockProjectService: ProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]

  val mockDefaultSectionCalculatorStrategy: DefaultSectionCalculatorStrategy = MockitoSugar.mock[DefaultSectionCalculatorStrategy]
  val mockVVHClient: VVHClient = MockitoSugar.mock[VVHClient]
  val mockRoadAddressService: RoadAddressService = MockitoSugar.mock[RoadAddressService]
  val mockRoadwayAddressMapper: RoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockNodesAndJunctionsService: NodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayDAO = new RoadwayDAO
  val roadNetworkDAO = new RoadNetworkDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodeDAO = new NodeDAO
  val nodePointDAO = new NodePointDAO
  val junctionPointDAO = new JunctionPointDAO
  val roadwayChangesDAO = new RoadwayChangesDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  val projectValidator = new ProjectValidator {
    override val roadAddressService = mockRoadAddressService
  }

  /* db digiroad2Context.scala*/
  val junctionDAO_db: JunctionDAO = {
    new JunctionDAO
  }
  val nodesAndJunctionsService_db : NodesAndJunctionsService = {
    new NodesAndJunctionsService(roadwayDAO, roadwayPointDAO, linearLocationDAO, nodeDAO, nodePointDAO, junctionDAO_db, junctionPointDAO, roadwayChangesDAO)
  }
  val vvhClient_db: VVHClient = {
    new VVHClient(ViiteProperties.vvhRestApiEndPoint)
  }

  val eventbus_db: DigiroadEventBus = {
    Class.forName(ViiteProperties.eventBus).newInstance().asInstanceOf[DigiroadEventBus]
  }
  val roadLinkService_db: RoadLinkService = {
    new RoadLinkService(vvhClient_db, eventbus_db, new JsonSerializer, true)
  }
  val roadAddressService_db: RoadAddressService = new RoadAddressService(roadLinkService_db, roadwayDAO, linearLocationDAO,
    roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, roadwayAddressMapper, eventbus_db, true)

  val projectService_db = spy(new ProjectService(roadAddressService_db, roadLinkService_db, nodesAndJunctionsService_db, roadwayDAO,
    roadwayPointDAO, linearLocationDAO, projectDAO, projectLinkDAO,
    nodeDAO, nodePointDAO, junctionPointDAO, projectReservedPartDAO, roadwayChangesDAO,
    roadwayAddressMapper, eventbus_db, frozenTimeVVHAPIServiceEnabled = true)
  )


  /* ---db */

  /*val roadAddressService: RoadAddressService = new RoadAddressService(mockRoadLinkService, roadwayDAO, linearLocationDAO,
    roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, roadwayAddressMapper, mockEventBus, frozenVVH = false) {

    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val projectService = new ProjectService(roadAddressService, mockRoadLinkService, mockNodesAndJunctionsService, roadwayDAO,
    roadwayPointDAO, linearLocationDAO, projectDAO, projectLinkDAO,
    nodeDAO, nodePointDAO, junctionPointDAO, projectReservedPartDAO, roadwayChangesDAO,
    roadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val projectServiceWithRoadAddressMock = new ProjectService(mockRoadAddressService, mockRoadLinkService, mockNodesAndJunctionsService, roadwayDAO,
    roadwayPointDAO, linearLocationDAO, projectDAO, projectLinkDAO,
    nodeDAO, nodePointDAO, junctionPointDAO, projectReservedPartDAO, roadwayChangesDAO,
    roadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }
*/
  after {
    reset(mockRoadLinkService)
  }

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
  t
}
  }
  /* def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

   def runWithRollback[T](f: => T): T = {
	 Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
	   val t = f
	   dynamicSession.rollback()
	   t
	 }
   }*/
  /*private def setUpProjectWithLinks(linkStatus: LinkStatus, addrM: Seq[Long], changeTrack: Boolean = false, roadNumber: Long = 19999L,
                                    roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L) = {
    val id = Sequences.nextViiteProjectId

    def withTrack(t: Track): Seq[ProjectLink] = {
      addrM.init.zip(addrM.tail).map { case (st, en) =>
        projectLink(st, en, t, id, linkStatus, roadNumber, roadPartNumber, discontinuity, ely, roadwayId)
      }
    }

    val project = Project(id, ProjectState.Incomplete, "f", "s", DateTime.now(), "", DateTime.now(), DateTime.now(),
      "", Seq(), Seq(), None, None)
    projectDAO.create(project)
    val links =
      if (changeTrack) {
        withTrack(RightSide) ++ withTrack(LeftSide)
      } else {
        withTrack(Combined)
      }
    projectReservedPartDAO.reserveRoadPart(id, roadNumber, roadPartNumber, "u")
    projectLinkDAO.create(links)
    project
  }*/

  private def projectLink(startAddrM: Long, endAddrM: Long, track: Track, projectId: Long, status: LinkStatus = LinkStatus.NotHandled,
                          roadNumber: Long = 19999L, roadPartNumber: Long = 1L, discontinuity: Discontinuity = Discontinuity.Continuous, ely: Long = 8L, roadwayId: Long = 0L, linearLocationId: Long = 0L) = {
    ProjectLink(NewIdValue, roadNumber, roadPartNumber, track, discontinuity, startAddrM, endAddrM, startAddrM, endAddrM, None, None,
      Some("User"), startAddrM, 0.0, (endAddrM - startAddrM).toDouble, SideCode.TowardsDigitizing, (NoCP, NoCP), (NoCP, NoCP),
      Seq(Point(0.0, startAddrM), Point(0.0, endAddrM)), projectId, status, RoadType.PublicRoad,
      LinkGeomSource.NormalLinkInterface, (endAddrM - startAddrM).toDouble, roadwayId, linearLocationId, ely, reversed = false, None, 0L)
  }

  /*private def createProjectLinks(linkIds: Seq[Long], projectId: Long, roadNumber: Long, roadPartNumber: Long, track: Int,
                                 discontinuity: Int, roadType: Int, roadLinkSource: Int,
                                 roadEly: Long, user: String, roadName: String): Map[String, Any] = {
    projectService.createProjectLinks(linkIds, projectId, roadNumber, roadPartNumber, Track.apply(track), Discontinuity.apply(discontinuity),
      RoadType.apply(roadType), LinkGeomSource.apply(roadLinkSource), roadEly, user, roadName)
  }*/

  /*private def toRoadLink(ral: ProjectLink): RoadLink = {
    RoadLink(ral.linkId, ral.geometry, ral.geometryLength, State, 1,
      extractTrafficDirection(ral.sideCode, ral.track), Motorway, None, None, Map(
        "MUNICIPALITYCODE" -> BigInt(749), "VERTICALLEVEL" -> BigInt(1), "SURFACETYPE" -> BigInt(1),
        "ROADNUMBER" -> BigInt(ral.roadNumber), "ROADPARTNUMBER" -> BigInt(ral.roadPartNumber)),
      ConstructionType.InUse, LinkGeomSource.NormalLinkInterface)
  }*/

  private def addressToRoadLink(ral: RoadAddress): RoadLink = {
    val geomLength = GeometryUtils.geometryLength(ral.geometry)
    val adminClass = ral.roadType match {
      case RoadType.FerryRoad => AdministrativeClass.apply(1)
      case RoadType.PublicRoad => AdministrativeClass.apply(1)
      case RoadType.MunicipalityStreetRoad => AdministrativeClass.apply(2)
      case RoadType.PrivateRoadType => AdministrativeClass.apply(3)
      case _ => AdministrativeClass.apply(99)
    }
    RoadLink(ral.linkId, ral.geometry, geomLength, adminClass, 1,
      extractTrafficDirection(ral.sideCode, ral.track), LinkType.apply(99), Option(ral.startDate.toString), ral.createdBy, Map(
        "MUNICIPALITYCODE" -> BigInt(749), "VERTICALLEVEL" -> BigInt(1), "SURFACETYPE" -> BigInt(1),
        "ROADNUMBER" -> BigInt(ral.roadNumber), "ROADPARTNUMBER" -> BigInt(ral.roadPartNumber)),
      ConstructionType.InUse, ral.linkGeomSource)
  }

  private def extractTrafficDirection(sideCode: SideCode, track: Track): TrafficDirection = {
    (sideCode, track) match {
      case (_, Track.Combined) => TrafficDirection.BothDirections
      case (TowardsDigitizing, Track.RightSide) => TrafficDirection.TowardsDigitizing
      case (TowardsDigitizing, Track.LeftSide) => TrafficDirection.AgainstDigitizing
      case (AgainstDigitizing, Track.RightSide) => TrafficDirection.AgainstDigitizing
      case (AgainstDigitizing, Track.LeftSide) => TrafficDirection.TowardsDigitizing
      case (_, _) => TrafficDirection.UnknownDirection
    }
  }


  private def mockForProject[T <: PolyLine](id: Long, l: Seq[T] = Seq()) = {
    val roadLink = RoadLink(Sequences.nextViitePrimaryKeySeqValue, Seq(Point(535602.222, 6982200.25, 89.9999), Point(535605.272, 6982204.22, 85.90899999999965))
      , 540.3960283713503, State, 99, TrafficDirection.AgainstDigitizing, UnknownLinkType, Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse, NormalLinkInterface)

    //val roadLink = RoadLink( Some("25.06.2015 03:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(684)), InUse, FrozenLinkInterface)

    val (projectLinks, palinks) = l.partition(_.isInstanceOf[ProjectLink])
    val dbLinks = projectLinkDAO.fetchProjectLinks(id)
    when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenAnswer(
      toMockAnswer(dbLinks ++ projectLinks.asInstanceOf[Seq[ProjectLink]].filterNot(l => dbLinks.map(_.linkId).contains(l.linkId)),
        roadLink, palinks.asInstanceOf[Seq[ProjectAddressLink]].map(toRoadLink)
      ))
  }

  private def toRoadLink(ral: RoadAddressLinkLike): RoadLink = {
    RoadLink(ral.linkId, ral.geometry, ral.length, ral.administrativeClass, 1,
      extractTrafficDirection(ral.sideCode, Track.apply(ral.trackCode.toInt)), ral.linkType, ral.modifiedAt, ral.modifiedBy, Map(
        "MUNICIPALITYCODE" -> BigInt(749), "VERTICALLEVEL" -> BigInt(1), "SURFACETYPE" -> BigInt(1),
        "ROADNUMBER" -> BigInt(ral.roadNumber), "ROADPARTNUMBER" -> BigInt(ral.roadPartNumber)),
      ral.constructionType, ral.roadLinkSource)
  }

  private def toMockAnswer(projectLinks: Seq[ProjectLink], roadLink: RoadLink, seq: Seq[RoadLink] = Seq()) = {
    new Answer[Seq[RoadLink]]() {
      override def answer(invocation: InvocationOnMock): Seq[RoadLink] = {
        val ids = if (invocation.getArguments.apply(0) == null)
          Set[Long]()
        else invocation.getArguments.apply(0).asInstanceOf[Set[Long]]
        projectLinks.groupBy(_.linkId).filterKeys(l => ids.contains(l)).mapValues { pl =>
          val startP = Point(pl.map(_.startAddrMValue).min, 0.0)
          val endP = Point(pl.map(_.endAddrMValue).max, 0.0)
          val maxLen = pl.map(_.endMValue).max
          val midP = Point((startP.x + endP.x) * .5,
            if (endP.x - startP.x < maxLen) {
              Math.sqrt(maxLen * maxLen - (startP.x - endP.x) * (startP.x - endP.x)) / 2
            }
            else 0.0)
          val forcedGeom = pl.filter(l => l.id == -1000L && l.geometry.nonEmpty).sortBy(_.startAddrMValue)
          val (startFG, endFG) = (forcedGeom.headOption.map(_.startingPoint), forcedGeom.lastOption.map(_.endPoint))
          if (pl.head.id == -1000L) {
            roadLink.copy(linkId = pl.head.linkId, geometry = Seq(startFG.get, endFG.get))
          } else
            roadLink.copy(linkId = pl.head.linkId, geometry = Seq(startP, midP, endP))
        }.values.toSeq ++ seq
      }
    }
  }

  private def mockRoadLinksWithStaticTestData(linkIds: Set[Long], linkSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface): Seq[RoadLink] = {
    val roadLinkTemplate =
      RoadLink(-1, Seq(), 540.3960283713503, State, 99, TrafficDirection.BothDirections, UnknownLinkType, Some("25.06.2015 03:00:00"),
        Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt.apply(749)), InUse, NormalLinkInterface)
    StaticTestData.roadLinkMocker(roadLinkTemplate)(linkIds)
  }

  /* update errors*/
  def errorPartsToApi(errorParts: projectService_db.projectValidator.ValidationErrorDetails): Map[String, Any] = {
    Map("ids" -> errorParts.affectedIds,
      "errorCode" -> errorParts.validationError.value,
      "errorMessage" -> errorParts.validationError.message,
      "info" -> errorParts.optionalInformation,
      "coordinates" -> errorParts.coordinates,
      "priority" -> errorParts.validationError.priority
    )
  }
  def projectFormedPartToApi(projectId: Option[Long] = None)(formedRoadPart: ProjectReservedPart): Map[String, Any] = {
    Map("roadNumber" -> formedRoadPart.roadNumber,
      "roadPartNumber" -> formedRoadPart.roadPartNumber,
      "id" -> formedRoadPart.id,
      "currentEly" -> formedRoadPart.ely,
      "currentLength" -> formedRoadPart.addressLength,
      "currentDiscontinuity" -> formedRoadPart.discontinuity.map(_.description),
      "newEly" -> formedRoadPart.newEly,
      "newLength" -> formedRoadPart.newLength,
      "newDiscontinuity" -> formedRoadPart.newDiscontinuity.map(_.description),
      "startingLinkId" -> formedRoadPart.startingLinkId,
      "roadAddresses" -> {
        projectId match {
          case None => Seq.empty
          case _ => projectService_db.getRoadAddressesFromFormedRoadPart(formedRoadPart.roadNumber, formedRoadPart.roadPartNumber, projectId.get)
        }
      }
    )
  }

  private def mapValidationIssues(issue: projectService_db.projectValidator.ValidationErrorDetails): Map[String, Any] = {
    Map(
      "id" -> issue.projectId,
      "validationError" -> issue.validationError.value,
      "affectedIds" -> issue.affectedIds.toArray,
      "coordinates" -> issue.coordinates,
      "optionalInformation" -> issue.optionalInformation.getOrElse("")
    )
  }
  def projectReservedPartToApi(reservedRoadPart: ProjectReservedPart): Map[String, Any] = {
    Map("roadNumber" -> reservedRoadPart.roadNumber,
      "roadPartNumber" -> reservedRoadPart.roadPartNumber,
      "id" -> reservedRoadPart.id,
      "currentEly" -> reservedRoadPart.ely,
      "currentLength" -> reservedRoadPart.addressLength,
      "currentDiscontinuity" -> reservedRoadPart.discontinuity.map(_.description),
      "newEly" -> reservedRoadPart.newEly,
      "newLength" -> reservedRoadPart.newLength,
      "newDiscontinuity" -> reservedRoadPart.newDiscontinuity.map(_.description),
      "startingLinkId" -> reservedRoadPart.startingLinkId
    )
  }
  def mockAssignMValues(newProjectLinks: Seq[ProjectLink], oldProjectLinks: Seq[ProjectLink], userCalibrationPoints: Seq[UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
    println(newProjectLinks)
    println(oldProjectLinks)

   /* val groupedProjectLinks = newProjectLinks.groupBy(record => (record.roadNumber, record.roadPartNumber))
    val groupedOldLinks = oldProjectLinks.groupBy(record => (record.roadNumber, record.roadPartNumber))
    val group = (groupedProjectLinks.keySet ++ groupedOldLinks.keySet).map(k =>
      k -> (groupedProjectLinks.getOrElse(k, Seq()), groupedOldLinks.getOrElse(k, Seq())))
   */ oldProjectLinks
    Seq()
  }


  test("Test PM reverse + transfer + terminate on projectService.updateProjectLinks() "
  //  "When reserving a road part, try to renumber it to the same number Then it should produce an error."
  ) {
    runWithRollback {

      val road_46612 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(46612, 1).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue).toList
      val road_46503 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(46503, 1).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue).toList

      val reservedRoadPart_1 = ProjectReservedPart(
                                                  road_46612.head.id,
                                                  road_46612.head.roadNumber,
                                                  road_46612.head.roadPartNumber,
                                                  Some(road_46612.head.endAddrMValue),
                                                  Some(road_46612.head.discontinuity),
                                                  Some(road_46612.head.ely),
                                                  newLength = None,
                                                  newDiscontinuity = None,
                                                  newEly = None,
                                                  startingLinkId = Some(road_46612.head.linkId))

      val reservedRoadPart_2 = ProjectReservedPart(
        road_46503.head.id,
        road_46503.head.roadNumber,
        road_46503.head.roadPartNumber,
        Some(road_46503.head.endAddrMValue),
        Some(road_46503.head.discontinuity),
        Some(road_46503.head.ely),
        newLength = None,
        newDiscontinuity = None,
        newEly = None,
        startingLinkId = Some(road_46503.head.linkId))

      val links = road_46612.map(addressToRoadLink) ++ road_46503.map(addressToRoadLink)

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(links)



      val rap = Project(0,
                        ProjectState.apply(1),
                        "PM-46612-test",
                        "test_code",
                        DateTime.now(),
                        "test_code",
                        DateTime.now(),
                        DateTime.now(),
                        null,
                        List(reservedRoadPart_1, reservedRoadPart_2),
                        Seq(),
                        None, Some(ProjectCoordinates(435824,6902528,12))
      )

      val project = projectService_db.createRoadLinkProject(rap)
      val projectSaved = projectService_db.saveProject(project)

      val road_46503_first_links_to_terminate = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.roadNumber == road_46503.head.roadNumber && x.startAddrMValue == 0).toList
      assert(road_46503_first_links_to_terminate.size == 2)

      // Terminate 46503 first link
      projectService_db.updateProjectLinks(
        projectSaved.id,
        Set(road_46503_first_links_to_terminate.head.id),
        List(),
        linkStatus = LinkStatus.Terminated,
        userName = projectSaved.name,
        newRoadNumber = road_46503_first_links_to_terminate.head.roadNumber,
        newRoadPartNumber = road_46503_first_links_to_terminate.head.roadPartNumber,
        newTrackCode = road_46503_first_links_to_terminate.head.track.value,
        userDefinedEndAddressM = None,
        roadType = road_46503_first_links_to_terminate.head.roadType.value,
        discontinuity = road_46503_first_links_to_terminate.head.discontinuity.value,
        ely = Some(road_46503_first_links_to_terminate.head.ely),
        reversed = false,
        roadName = road_46503_first_links_to_terminate.head.roadName,
        coordinates = projectSaved.coordinates
      )

      val road_46503_rest_of_the_track_links_after_the_terminated_link = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == road_46503_first_links_to_terminate.head.track && x.roadNumber == road_46503.head.roadNumber && x.startAddrMValue != 0).toList

      // Set status transfer to road_46503_rest_of_the_track_links_after_the_terminated_link
      projectService_db.updateProjectLinks(
        projectSaved.id,
        road_46503_rest_of_the_track_links_after_the_terminated_link.map(_.id).toSet,
        List(),
        linkStatus = LinkStatus.Transfer,
        userName = projectSaved.name,
        newRoadNumber = road_46503_rest_of_the_track_links_after_the_terminated_link.head.roadNumber,
        newRoadPartNumber = road_46503_rest_of_the_track_links_after_the_terminated_link.head.roadPartNumber,
        newTrackCode = road_46503_rest_of_the_track_links_after_the_terminated_link.head.track.value,
        userDefinedEndAddressM = None,
        roadType = road_46503_rest_of_the_track_links_after_the_terminated_link.head.roadType.value,
        discontinuity = road_46503_rest_of_the_track_links_after_the_terminated_link.head.discontinuity.value,
        ely = Some(road_46503_rest_of_the_track_links_after_the_terminated_link.head.ely),
        reversed = false,
        roadName = road_46503_rest_of_the_track_links_after_the_terminated_link.head.roadName,
        coordinates = projectSaved.coordinates
      )

      val road_46503_rest_of_the_track_links_after_the_second_terminated_link = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == road_46503_first_links_to_terminate.last.track && x.roadNumber == road_46503.head.roadNumber && x.startAddrMValue != 0).toList
      // Set status transfer to road_46503_rest_of_the_track_links_after_the_terminated_link
      projectService_db.updateProjectLinks(
        projectSaved.id,
        road_46503_rest_of_the_track_links_after_the_second_terminated_link.map(_.id).toSet,
        List(),
        linkStatus = LinkStatus.Transfer,
        userName = projectSaved.name,
        newRoadNumber = road_46503_rest_of_the_track_links_after_the_second_terminated_link.head.roadNumber,
        newRoadPartNumber = road_46503_rest_of_the_track_links_after_the_second_terminated_link.head.roadPartNumber,
        newTrackCode = road_46503_rest_of_the_track_links_after_the_second_terminated_link.head.track.value,
        userDefinedEndAddressM = None,
        roadType = road_46503_rest_of_the_track_links_after_the_second_terminated_link.head.roadType.value,
        discontinuity = road_46503_rest_of_the_track_links_after_the_second_terminated_link.head.discontinuity.value,
        ely = Some(road_46503_rest_of_the_track_links_after_the_second_terminated_link.head.ely),
        reversed = false,
        roadName = road_46503_rest_of_the_track_links_after_the_second_terminated_link.head.roadName,
        coordinates = projectSaved.coordinates
      )

      // Terminate 46503 second link
      projectService_db.updateProjectLinks(
        projectSaved.id,
        Set(road_46503_first_links_to_terminate.last.id),
        List(),
        linkStatus = LinkStatus.Terminated,
        userName = projectSaved.name,
        newRoadNumber = road_46503_first_links_to_terminate.last.roadNumber,
        newRoadPartNumber = road_46503_first_links_to_terminate.head.roadPartNumber,
        newTrackCode = road_46503_first_links_to_terminate.last.track.value,
        userDefinedEndAddressM = None,
        roadType = road_46503_first_links_to_terminate.last.roadType.value,
        discontinuity = road_46503_first_links_to_terminate.last.discontinuity.value,
        ely = Some(road_46503_first_links_to_terminate.last.ely),
        reversed = false,
        roadName = road_46503_first_links_to_terminate.last.roadName,
        coordinates = projectSaved.coordinates
      )

      val transfered_road_46503_rest_of_the_track_links_after_the_terminated_link = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1) && x.roadNumber == road_46503.head.roadNumber && x.status == LinkStatus.Transfer).toList
      // Keep status transfer to road_46503_rest_of_the_track_links_after_the_terminated_link
      // and change road number and road part number to 46612 2
      projectService_db.updateProjectLinks(
        projectSaved.id,
        transfered_road_46503_rest_of_the_track_links_after_the_terminated_link.map(_.id).toSet,
        List(),
        linkStatus = LinkStatus.Transfer,
        userName = projectSaved.name,
        newRoadNumber = 46612,
        newRoadPartNumber = 2,
        newTrackCode = transfered_road_46503_rest_of_the_track_links_after_the_terminated_link.head.track.value,
        userDefinedEndAddressM = None,
        roadType = transfered_road_46503_rest_of_the_track_links_after_the_terminated_link.head.roadType.value,
        discontinuity = Discontinuity.EndOfRoad.value,
        ely = Some(transfered_road_46503_rest_of_the_track_links_after_the_terminated_link.head.ely),
        reversed = false,
        roadName = transfered_road_46503_rest_of_the_track_links_after_the_terminated_link.head.roadName,
        coordinates = projectSaved.coordinates
      )

      val transfered_road_46503_rest_of_the_track_links_after_the_terminated_link_other_track = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2) && x.roadNumber == road_46503.head.roadNumber && x.status == LinkStatus.Transfer).toList
      // Keep status transfer to road_46503_rest_of_the_track_links_after_the_terminated_link
      // and change road number and road part number
      projectService_db.updateProjectLinks(
        projectSaved.id,
        transfered_road_46503_rest_of_the_track_links_after_the_terminated_link_other_track.map(_.id).toSet,
        List(),
        linkStatus = LinkStatus.Transfer,
        userName = projectSaved.name,
        newRoadNumber = 46612,
        newRoadPartNumber = 2,
        newTrackCode = transfered_road_46503_rest_of_the_track_links_after_the_terminated_link_other_track.head.track.value,
        userDefinedEndAddressM = None,
        roadType = transfered_road_46503_rest_of_the_track_links_after_the_terminated_link_other_track.head.roadType.value,
        discontinuity = Discontinuity.EndOfRoad.value,
        ely = Some(transfered_road_46503_rest_of_the_track_links_after_the_terminated_link_other_track.head.ely),
        reversed = false,
        roadName = transfered_road_46503_rest_of_the_track_links_after_the_terminated_link_other_track.head.roadName,
        coordinates = projectSaved.coordinates
      )

      /* Second road (46612,1) to tranfer */

      val road_46612_1_project_links = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.roadNumber == 46612 && x.roadPartNumber == 1).toList
      val road_46612_1_track_0 = road_46612_1_project_links.filter(_.track == Track.Combined)
      assert(road_46612_1_track_0.size == 2)
      val road_46612_1_track_1 = road_46612_1_project_links.filter(_.track == Track.RightSide)
      assert(road_46612_1_track_1.size == 6)
      val road_46612_1_track_2 = road_46612_1_project_links.filter(_.track == Track.LeftSide)
      assert(road_46612_1_track_2.size == 6)

     // val validationErrorsAfterReverse = projectService_db.validateProjectById(projectSaved.id)
     // validationErrorsAfterReverse.size should be(0)


      case class Test_config(
                              track_links: List[ProjectLink],
                              linkStatus: LinkStatus
                            )
      val road_46612_tracks_to_transfer = List(
                                      Test_config(road_46612_1_track_0, LinkStatus.Transfer),
                                      Test_config(road_46612_1_track_1, LinkStatus.Transfer),
                                      Test_config(road_46612_1_track_2, LinkStatus.Transfer)
                                    )
      // Set road 46612 part 1 status to transfer
      for (test_track <- road_46612_tracks_to_transfer) {
        println("Track: " + test_track.track_links.head.track)

        projectService_db.updateProjectLinks(
          projectSaved.id,
          test_track.track_links.map(_.id).toSet,
          List(),
          linkStatus = test_track.linkStatus,
          userName = projectSaved.name,
          newRoadNumber = test_track.track_links.head.roadNumber,
          newRoadPartNumber = test_track.track_links.head.roadPartNumber,
          newTrackCode = test_track.track_links.head.track.value,
          userDefinedEndAddressM = None,
          roadType = test_track.track_links.head.roadType.value,
          discontinuity = test_track.track_links.last.discontinuity.value,
          ely = Some(test_track.track_links.head.ely),
          reversed = false,
          roadName = test_track.track_links.head.roadName,
          coordinates = projectSaved.coordinates
        )
      }

      // Reverse road 46612 part 1
      val road_46612_1_track_0_after_transfer  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(0) && x.roadNumber == 46612 && x.roadPartNumber == 1).toList
      val linksToRevert = road_46612_1_track_0_after_transfer.map( pl => LinkToRevert(pl.id, pl.id, LinkStatus.Transfer.value, pl.geometry))
      println("linksToRevert")
      println(linksToRevert)

      projectService_db.changeDirection(
        projectSaved.id,
        road_46612_1_track_0_after_transfer.head.roadNumber,
        road_46612_1_track_0_after_transfer.head.roadPartNumber,
        linksToRevert,
        projectSaved.coordinates.get,
        projectSaved.name
      )

      // Change continuity of (46612,1) tracks after reverse
      val road_46612_1_project_links_after_reverse = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.roadNumber == 46612 && x.roadPartNumber == 1).toList

      val road_46612_1_track_1_after_reverse = road_46612_1_project_links_after_reverse.filter(_.track == Track.RightSide)
      val road_46612_1_track_2_after_reverse = road_46612_1_project_links_after_reverse.filter(_.track == Track.LeftSide)

        // Set 46612 1 track 1 discontinuity to continuous
        projectService_db.updateProjectLinks(
          projectSaved.id,
          road_46612_1_track_1_after_reverse.map(_.id).toSet,
          List(),
          linkStatus = road_46612_1_track_1_after_reverse.head.status,
          userName = projectSaved.name,
          newRoadNumber = road_46612_1_track_1_after_reverse.head.roadNumber,
          newRoadPartNumber = road_46612_1_track_1_after_reverse.head.roadPartNumber,
          newTrackCode = road_46612_1_track_1_after_reverse.head.track.value,
          userDefinedEndAddressM = None,
          roadType = road_46612_1_track_1_after_reverse.head.roadType.value,
          discontinuity = Discontinuity.Continuous.value,
          ely = Some(road_46612_1_track_1_after_reverse.head.ely),
          reversed = false,
          roadName = road_46612_1_track_1_after_reverse.head.roadName,
          coordinates = projectSaved.coordinates
        )

      // Set 46612 1 track 2 discontinuity to discontinous
      projectService_db.updateProjectLinks(
        projectSaved.id,
        road_46612_1_track_2_after_reverse.map(_.id).toSet,
        List(),
        linkStatus = road_46612_1_track_2_after_reverse.head.status,
        userName = projectSaved.name,
        newRoadNumber = road_46612_1_track_2_after_reverse.head.roadNumber,
        newRoadPartNumber = road_46612_1_track_2_after_reverse.head.roadPartNumber,
        newTrackCode = road_46612_1_track_2_after_reverse.head.track.value,
        userDefinedEndAddressM = None,
        roadType = road_46612_1_track_2_after_reverse.head.roadType.value,
        discontinuity = Discontinuity.Discontinuous.value,
        ely = Some(road_46612_1_track_2_after_reverse.head.ely),
        reversed = false,
        roadName = road_46612_1_track_2_after_reverse.head.roadName,
        coordinates = projectSaved.coordinates
      )

      val validationErrorsAfterReverseContinuousChecks = projectService_db.validateProjectById(projectSaved.id)
      validationErrorsAfterReverseContinuousChecks.size should be(0)
    }
  }

}

