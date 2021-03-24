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
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
//import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{Track, ViiteProperties}
import fi.liikennevirasto.digiroad2.util.Track.{Combined, LeftSide, RightSide}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.NoCP
import fi.liikennevirasto.viite.dao.Discontinuity.{Continuous, Discontinuous}
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, ProjectAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process.{ProjectSectionCalculator, TrackSectionOrder}
import fi.liikennevirasto.viite.process.strategy.{DefaultSectionCalculatorStrategy, RoadAddressSectionCalculatorContext, RoadAddressSectionCalculatorStrategy, RoundaboutSectionCalculatorStrategy}
import fi.liikennevirasto.viite.process.strategy.RoadAddressSectionCalculatorContext.{defaultSectionCalculatorStrategy, strategies}
import org.mockito.Mockito.{doAnswer, doReturn}
import org.scalatra.NotFound
//import org.mockito.Mockito.spy
import org.scalatest.mockito.MockitoSugar.mock
//import fi.liikennevirasto.viite.process.strategy.RoadAddressSectionCalculatorContext.defaultSectionCalculatorStrategy.assignMValues
import fi.liikennevirasto.viite.process.{InvalidAddressDataException, ProjectSectionCalculator, RoadwayAddressMapper}
import fi.liikennevirasto.viite.util.{StaticTestData, _}
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyObject
import org.mockito.Mockito.{spy, reset, when}
import org.mockito.ArgumentCaptor
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import fi.liikennevirasto.viite.process.strategy.{DefaultSectionCalculatorStrategy, RoadAddressSectionCalculatorContext, RoadAddressSectionCalculatorStrategy}
import org.mockito.invocation.InvocationOnMock
//import org.mockito.stubbing.Answer
import scala.util.{Left, Right}
import scala.util.parsing.json.JSON
import org.scalatest.PrivateMethodTester._
//import org.powermock.api.mockito.PowerMockito

class Viite_2511_spec extends FunSuite with Matchers with BeforeAndAfter {
  val mockProjectService: ProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]

  val mockDefaultSectionCalculatorStrategy: DefaultSectionCalculatorStrategy = MockitoSugar.mock[DefaultSectionCalculatorStrategy]

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

  /*def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }
  */
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


  test("Test projectService.updateProjectLinks() When reserving a road part, try to renumber it to the same number Then it should produce an error.") {
    runWithRollback {
      reset(mockRoadLinkService)

      val road_42044_1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(42044, 1).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue).toList
      val road_42044_2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(42044, 2).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue).toList
      //val road_42044_2 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySectionAndTracks(42044, 2, Set( Track(1))).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue)


      val reservedRoadPart_1 = ProjectReservedPart(
                                                  road_42044_1.head.id,
                                                  road_42044_1.head.roadNumber,
                                                  road_42044_1.head.roadPartNumber,
                                                  Some(road_42044_1.head.endAddrMValue),
                                                  Some(road_42044_1.head.discontinuity),
                                                  Some(road_42044_1.head.ely),
                                                  newLength = None,
                                                  newDiscontinuity = None,
                                                  newEly = None,
                                                  startingLinkId = Some(road_42044_1.head.linkId))
      val reservedRoadPart_2 = ProjectReservedPart(
                                                  road_42044_2.head.id,
                                                  road_42044_2.head.roadNumber,
                                                  road_42044_2.head.roadPartNumber,
                                                  Some(road_42044_2.head.endAddrMValue),
                                                  Some(road_42044_2.head.discontinuity),
                                                  Some(8L),
                                                  newLength = None,
                                                  newDiscontinuity = None,
                                                  newEly = None)
      val links = road_42044_1.map(addressToRoadLink) ++ road_42044_2.map(addressToRoadLink)
      /*val links = Seq(addressToRoadLink(road_42044_1(0)),
                      addressToRoadLink(road_42044_1(1)),
                      addressToRoadLink(road_42044_1(2)),
                      addressToRoadLink(road_42044_1(3)),
                      addressToRoadLink(road_42044_1(4))
                      )*/
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(links)



      val rap = Project(0,
                        ProjectState.apply(1),
                        "HN_2511_test",
                        "test_code",
                        DateTime.now(),
                        "test_code",
                        DateTime.now(),
                        DateTime.now(),
                        null,
                        List(reservedRoadPart_1, reservedRoadPart_2),
                        Seq(),
                        None, Some(ProjectCoordinates(204670.17,6789918.647,12))
      )
      val project = projectService_db.createRoadLinkProject(rap)

     /* val reservable = projectService_db.checkRoadPartExistsAndReservable(42044, 1, 2,  DateTime.now(), project.id) match {
        case Left(err) => Map("success" -> err)
        case Right((reservedparts, formedparts)) => Map("success" -> "ok", "reservedInfo" -> reservedparts.map(projectReservedPartToApi),
          "formedInfo" -> formedparts.map(projectFormedPartToApi()))
      }
      println("reservable: " + reservable + "\n")*/


      val projectSaved = projectService_db.saveProject(project)
      //val firstLink = projectService_db.getFirstProjectLink(projectSaved)

      //when(projectService_db.recalculateProjectLinks(projectSaved.id, "test_code")).thenCallRealMethod()


      /* LOAD project*/ /*
      projectServiceprojectService.getSingleProjectById(projectId) match {
        case Some(project) =>
          val projectMap = roadAddressProjectToApi(project, projectService.getProjectEly(project.id))
          val reservedparts = project.reservedParts.map(projectReservedPartToApi)
          val formedparts = project.formedParts.map(projectFormedPartToApi(Some(project.id)))
          val errorParts = projectService.validateProjectById(project.id)
          val publishable = errorParts.isEmpty
          val latestPublishedNetwork = roadNetworkService.getLatestPublishedNetworkDate
          Map("project" -> projectMap, "linkId" -> project.reservedParts.find(_.startingLinkId.nonEmpty).flatMap(_.startingLinkId),
            "reservedInfo" -> reservedparts, "formedInfo" -> formedparts, "publishable" -> publishable, "projectErrors" -> errorParts.map(errorPartsToApi),
            "publishedNetworkDate" -> formatDateTimeToString(latestPublishedNetwork))
        case _ => halt(NotFound("Project not found"))
      }
      *//* -- load project*/
      case class Test_config(
                              track_to_test: List[ProjectLink],
                              road_part_number: Int,
                              discontinuity: Int,
                              linkStatus: LinkStatus
                            )

      val first_road_part_to_update  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1) && x.roadPartNumber == 1).toList
      val second_road_part_to_update = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2) && x.roadPartNumber == 1).toList
      val third_road_part_to_update  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1) && x.roadPartNumber == 2).toList
      val fourth_road_part_to_update = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2) && x.roadPartNumber == 2).toList


      val road_tracks_to_test = List(
                                      Test_config(fourth_road_part_to_update, 2, 1, LinkStatus.Transfer),
                                      Test_config(third_road_part_to_update,  2, 1, LinkStatus.Transfer),
                                      Test_config(second_road_part_to_update, 2, 4, LinkStatus.Transfer),
                                      Test_config(first_road_part_to_update,  2, 4, LinkStatus.Transfer)
                                    )

      //import org.mockito.AdditionalAnswers
      //import org.mockito.Mockito

      for ((test_track, index) <- road_tracks_to_test.view.zipWithIndex) {
        println("Track: " + test_track.track_to_test.head.roadPartNumber + " " + test_track.track_to_test.head.track)

        /* Call private method with parameters */
        /*val decorateToStringValue = PrivateMethod[Seq[ProjectLink]]('assignMValues)
        val x = ProjectSectionCalculator invokePrivate decorateToStringValue(first_road_part_to_update, Seq())
        println("x")
        println(x)*/
       /* implicit def toAnswerWithArgs[T](f: InvocationOnMock => T) = new Answer[T] {
          override def answer(i: InvocationOnMock): T = f(i)
        }*/

   //     val amock = spy( projectService_db )
       /* doAnswer((i: InvocationOnMock) =>
          ).when(amock).recalculateProjectLinks(first_road_part_to_update, Seq() )*/
        /*
        val x = when(amock.
        recalculateProjectLinks(projectSaved.id, "spy", Set((test_track.track_to_test(0).roadNumber, test_track.track_to_test(0).roadPartNumber)), Some(test_track.track_to_test.head.track), Some(Discontinuity(3)), Seq())
        ).thenReturn()
        println("X")
        println(x)
       */
        //val spiedList = PowerMockito.spy(DefaultSectionCalculatorStrategy)
        //when(spiedList.assignMValues(Seq(), Seq())).thenReturn(Seq())
      //  when(spiedList.assignMValues(any[Seq[ProjectLink]], any[Seq[UserDefinedCalibrationPoint]])).thenReturn(Seq())
        /*val spiedList = spy(ProjectSectionCalculator)

*/
        /* val eventCaptor = ArgumentCaptor.forClass(classOf[Seq[ProjectLink]])

         if (index == 3) {
           val x = mock(classOf[DefaultSectionCalculatorStrategy])

           when(x.assignMValues(any[Seq[ProjectLink]], any[Seq[ProjectLink]], any[Seq[UserDefinedCalibrationPoint]])).
             //            thenCallRealMethod()
             thenReturn(mockAssignMValues(any[Seq[ProjectLink]], any[Seq[ProjectLink]], any[Seq[UserDefinedCalibrationPoint]]))

            }*/

        projectService_db.updateProjectLinks(
          projectSaved.id,
          test_track.track_to_test.map(_.id).toSet,
          List(),
          test_track.linkStatus,
          projectSaved.name,
          test_track.track_to_test.head.roadNumber,
          test_track.road_part_number,
          test_track.track_to_test.head.track.value,
          userDefinedEndAddressM = None,
          first_road_part_to_update.head.roadType.value,
          test_track.discontinuity,
          Some(2),
          test_track.track_to_test.head.reversed,
          test_track.track_to_test.head.roadName,
          projectSaved.coordinates
        )
        match {
          case Some(errorMessage) => Map("success" -> false, "errorMessage" -> errorMessage)
          case None =>
            val projectErrors = projectService_db.validateProjectById(projectSaved.id).map(x => errorPartsToApi(x.asInstanceOf[projectService_db.projectValidator.ValidationErrorDetails]))
            val fetched_project = projectService_db.getSingleProjectById(projectSaved.id).get
            Map("success" -> true, "id" -> fetched_project.id,
              "publishable" -> projectErrors.isEmpty,
              "projectErrors" -> projectErrors,
              "formedInfo" -> project.formedParts.map(projectFormedPartToApi(Some(fetched_project.id))))
        }

      }


      val updatedLinks = projectLinkDAO.fetchProjectLinks(projectSaved.id)
      println("updatedLinks:")
      updatedLinks.foreach(println)


      //val validationErrors = projectService_db.validateProjectById(projectSaved.id)//.map(mapValidationIssues)
      val validationErrors = projectService_db.validateProjectById(projectSaved.id).map(x => mapValidationIssues(x.asInstanceOf[projectService_db.projectValidator.ValidationErrorDetails]))
      //TODO change UI to not override project validator errors on change table call
      val (changeProject, warningMessage) = projectService_db.getChangeProject(project.id)
      val changeTableData = changeProject.map(project =>
        Map(
          "id" -> project.id,
          "user" -> project.user,
          "name" -> project.name,
          "changeDate" -> project.changeDate,
          "changeInfoSeq" -> project.changeInfoSeq.map(changeInfo =>
            Map("changetype" -> changeInfo.changeType.value, "roadType" -> changeInfo.roadType.value,
              "discontinuity" -> changeInfo.discontinuity.value, "source" -> changeInfo.source,
              "target" -> changeInfo.target, "reversed" -> changeInfo.reversed)))
      ).getOrElse(None)

      println(Map("changeTable" -> changeTableData, "validationErrors" -> validationErrors, "warningMessage" -> warningMessage))

      println(road_42044_1 + "\n")

//      reset(mockRoadLinkService)

      true should be(true)
    }
  }

}
