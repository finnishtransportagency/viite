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

class Viite_PM1_spec extends FunSuite with Matchers with BeforeAndAfter {
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


  test("Test PM 1 projectService.updateProjectLinks() "
  //  "When reserving a road part, try to renumber it to the same number Then it should produce an error."
  ) {
    runWithRollback {
//      reset(mockRoadLinkService)

      val road_420921 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(40921, 1).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue).toList

      val reservedRoadPart_1 = ProjectReservedPart(
                                                  road_420921.head.id,
                                                  road_420921.head.roadNumber,
                                                  road_420921.head.roadPartNumber,
                                                  Some(road_420921.head.endAddrMValue),
                                                  Some(road_420921.head.discontinuity),
                                                  Some(road_420921.head.ely),
                                                  newLength = None,
                                                  newDiscontinuity = None,
                                                  newEly = None,
                                                  startingLinkId = Some(road_420921.head.linkId))

      val links = road_420921.map(addressToRoadLink)

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(links)



      val rap = Project(0,
                        ProjectState.apply(1),
                        "PM1-test",
                        "test_code",
                        DateTime.now(),
                        "test_code",
                        DateTime.now(),
                        DateTime.now(),
                        null,
                        List(reservedRoadPart_1),
                        Seq(),
                        None, Some(ProjectCoordinates(370619,6670932,12))
      )

      val project = projectService_db.createRoadLinkProject(rap)
      val projectSaved = projectService_db.saveProject(project)

      case class Test_config(
                              track_to_test: List[ProjectLink],
                              discontinuity: Int,
                              linkStatus: LinkStatus
                            )

      val first_road_part_to_update  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1)).toList
      val second_road_part_to_update = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2)).toList
      val third_road_part_to_update  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(0)).toList


      val road_tracks_to_test = List(
                                      Test_config(third_road_part_to_update,   5, LinkStatus.Transfer),
                                      Test_config(second_road_part_to_update,  5, LinkStatus.Transfer),
                                      Test_config(first_road_part_to_update,   5, LinkStatus.Transfer)
                                    )

      for (test_track <- road_tracks_to_test) {
        println("Track: " + test_track.track_to_test.head.track)

        projectService_db.updateProjectLinks(
          projectSaved.id,
          test_track.track_to_test.map(_.id).toSet,
          List(),
          linkStatus = test_track.linkStatus,
          userName = projectSaved.name,
          newRoadNumber = test_track.track_to_test.head.roadNumber,
          newRoadPartNumber = 1, //test_track.track_to_test.head.roadPartNumber,
          newTrackCode = test_track.track_to_test.head.track.value,
          userDefinedEndAddressM = None,
          roadType = first_road_part_to_update.head.roadType.value,
          discontinuity = test_track.discontinuity,
          ely = Some(test_track.track_to_test.head.ely),
          reversed = false,
          roadName = test_track.track_to_test.head.roadName,
          coordinates = projectSaved.coordinates
        )
      }

//      case class Test_config_2(
//                              track_to_test: List[ProjectLink],
//                              discontinuity: Int
//                            )
// Set validation errors of discontinuity from to continuous

      // There should be two links 145101 and 145677 with discontinuity as Continuous to
      // be changed to Minordiscontinuity.

      val validationErrorsBeforeReverse = projectService_db.validateProjectById(projectSaved.id)
      val discontinuosLinksBeforeReverse = validationErrorsBeforeReverse.filter(_.validationError.value == 1)
      val discontinuosLinkIdsBeforeReverse = discontinuosLinksBeforeReverse.flatMap(_.affectedIds)
      val discontinuosLinksToContinuousBeforeReverse = projectService_db.getProjectLinks(projectSaved.id).filter(x => discontinuosLinkIdsBeforeReverse.contains(x.id)).toList

      for (test_track <- discontinuosLinksToContinuousBeforeReverse) {
        println("linkId: " + test_track.linkId)

        projectService_db.updateProjectLinks(
          projectSaved.id,
          Set(test_track.id),
          List(),
          linkStatus = test_track.status,
          userName = projectSaved.name,
          newRoadNumber = test_track.roadNumber,
          newRoadPartNumber = test_track.roadPartNumber,
          newTrackCode = test_track.track.value,
          userDefinedEndAddressM = None,
          roadType = test_track.roadType.value,
          discontinuity = Discontinuity.MinorDiscontinuity.value,
          ely = Some(test_track.ely),
          reversed = test_track.reversed,
          roadName = test_track.roadName,
          coordinates = projectSaved.coordinates
        )
      }
//      val link_to_change_continuity_1  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2) && x.endAddrMValue == 4832).toList
//      val link_to_change_continuity_2  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(0) && x.endAddrMValue == 6850).toList
//
//      val tracks_to_correct_continuity = List(
//        Test_config_2(link_to_change_continuity_1,  4),
//        Test_config_2(link_to_change_continuity_2,  4)
//        )
//
//      for ((test_track, index) <- tracks_to_correct_continuity.view.zipWithIndex) {
//        println("Track: " + test_track.track_to_test.head.track)
//
//        projectService_db.updateProjectLinks(
//          projectSaved.id,
//          test_track.track_to_test.map(_.id).toSet,
//          List(),
//          linkStatus = test_track.track_to_test.head.status,
//          userName = projectSaved.name,
//          newRoadNumber = test_track.track_to_test.head.roadNumber,
//          newRoadPartNumber = test_track.track_to_test.head.roadPartNumber,
//          newTrackCode = test_track.track_to_test.head.track.value,
//          userDefinedEndAddressM = None,
//          roadType = test_track.track_to_test.head.roadType.value,
//          discontinuity = test_track.discontinuity,
//          ely = Some(test_track.track_to_test.head.ely),
//          reversed = test_track.track_to_test.head.reversed,
//          roadName = test_track.track_to_test.head.roadName,
//          coordinates = projectSaved.coordinates
//          )
//      }

//      val updatedLinks = projectLinkDAO.fetchProjectLinks(projectSaved.id)
//      println("updatedLinks:")
//      updatedLinks.foreach(println)


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
      println("validationErrors")
      println(validationErrors)

      validationErrors should have size 0

      case class Test_config_3(
                                links_to_reverse: List[ProjectLink],
                                reversed: Boolean
                              )
      val some_links_to_reverse_whole_road  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1) && x.endAddrMValue <= 339).toList
      val linksToRevert = some_links_to_reverse_whole_road.map( pl => LinkToRevert(pl.id, pl.id, LinkStatus.Transfer.value, pl.geometry))
      println("linksToRevert")
      println(linksToRevert)

        projectService_db.changeDirection(
          projectSaved.id,
          some_links_to_reverse_whole_road.head.roadNumber,
          some_links_to_reverse_whole_road.head.roadPartNumber,
          linksToRevert,
          projectSaved.coordinates.get,
          projectSaved.name
        )

      val validationErrorsAfterReverse = projectService_db.validateProjectById(projectSaved.id)

      println("validationErrorsAfterReverse")
      println(validationErrorsAfterReverse)

      val discontinuosLinks = validationErrorsAfterReverse.filter(_.validationError.value == 9)
      val discontinuosLinkIds = discontinuosLinks.flatMap(_.affectedIds)
      val discontinuosLinksToContinuous = projectService_db.getProjectLinks(projectSaved.id).filter(x => discontinuosLinkIds.contains(x.id)).toList

      for (test_track <- discontinuosLinksToContinuous) {
        println("linkId: " + test_track.linkId)

        projectService_db.updateProjectLinks(
          projectSaved.id,
          Set(test_track.id),
          List(),
          linkStatus = test_track.status,
          userName = projectSaved.name,
          newRoadNumber = test_track.roadNumber,
          newRoadPartNumber = test_track.roadPartNumber,
          newTrackCode = test_track.track.value,
          userDefinedEndAddressM = None,
          roadType = first_road_part_to_update.head.roadType.value,
          discontinuity = Discontinuity.Continuous.value,
          ely = Some(test_track.ely),
          reversed = test_track.reversed,
          roadName = test_track.roadName,
          coordinates = projectSaved.coordinates
        )
      }

      val lastLinkContinuityToUpdateTrack1  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1)).maxBy(_.endAddrMValue)
      val lastLinkContinuityToUpdateTrack2  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2)).maxBy(_.endAddrMValue)

      for (test_track <- Seq(lastLinkContinuityToUpdateTrack1, lastLinkContinuityToUpdateTrack2)) {
        println("linkId: " + test_track.linkId)

        projectService_db.updateProjectLinks(
          projectSaved.id,
          Set(test_track.id),
          List(),
          linkStatus = test_track.status,
          userName = projectSaved.name,
          newRoadNumber = test_track.roadNumber,
          newRoadPartNumber = test_track.roadPartNumber,
          newTrackCode = test_track.track.value,
          userDefinedEndAddressM = None,
          roadType = first_road_part_to_update.head.roadType.value,
          discontinuity = Discontinuity.Discontinuous.value,
          ely = Some(test_track.ely),
          reversed = test_track.reversed,
          roadName = test_track.roadName,
          coordinates = projectSaved.coordinates
        )
      }

      val validationErrorsAfterReverseContinuousChecks = projectService_db.validateProjectById(projectSaved.id)
      validationErrorsAfterReverseContinuousChecks.size should be(0)
    }
  }

  test("Test PM 2 projectService.updateProjectLinks() ") {
    runWithRollback {
      val road_40922_1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(40922, 1).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue).toList

      val reservedRoadPart_1 = ProjectReservedPart(
        road_40922_1.head.id,
        road_40922_1.head.roadNumber,
        road_40922_1.head.roadPartNumber,
        Some(road_40922_1.head.endAddrMValue),
        Some(road_40922_1.last.discontinuity),
        Some(road_40922_1.head.ely),
        newLength = None,
        newDiscontinuity = None,
        newEly = None,
        startingLinkId = Some(road_40922_1.head.linkId))

      val links = road_40922_1.map(addressToRoadLink)

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(links)

      val rap = Project(0,
        ProjectState.apply(1),
        "PM2-test",
        "test_code",
        DateTime.now(),
        "test_code",
        DateTime.now(),
        DateTime.now(),
        null,
        List(reservedRoadPart_1),
        Seq(),
        None, Some(ProjectCoordinates(382856, 6671237, 12))
      )

      val project = projectService_db.createRoadLinkProject(rap)
      val projectSaved = projectService_db.saveProject(project)

            val track_zero_begin  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(0) && x.endAddrMValue <= 1712).toList
            val track_one_middle  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1) && x.endAddrMValue <= 1963).toList
            val track_two_middle  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2) && x.endAddrMValue <= 1963).toList
            val track_zero_middle = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(0) && x.startAddrMValue >= 1963).toList
            val track_one_end     = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1) && x.startAddrMValue >= 2555).toList
            val track_two_end     = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2) && x.startAddrMValue >= 2555).toList

            case class Test_config(
                                    track_to_test: List[ProjectLink],
                                    linkStatus: LinkStatus
                                  )

            val road_tracks_to_test = List(
              Test_config(track_zero_begin, LinkStatus.Transfer),
              Test_config(track_one_middle, LinkStatus.Transfer),
              Test_config(track_two_middle, LinkStatus.Transfer),
              Test_config(track_zero_middle,LinkStatus.Transfer),
              Test_config(track_one_end,    LinkStatus.Transfer),
              Test_config(track_two_end,    LinkStatus.Transfer)
            )

            for (test_track <- road_tracks_to_test) {
              println("Track: " + test_track.track_to_test.head.track)

              projectService_db.updateProjectLinks(
                projectSaved.id,
                test_track.track_to_test.map(_.id).toSet,
                List(),
                linkStatus = test_track.linkStatus,
                userName = projectSaved.name,
                newRoadNumber = test_track.track_to_test.head.roadNumber,
                newRoadPartNumber = test_track.track_to_test.head.roadPartNumber,
                newTrackCode = test_track.track_to_test.head.track.value,
                userDefinedEndAddressM = None,
                roadType = test_track.track_to_test.head.roadType.value,
                discontinuity = test_track.track_to_test.last.discontinuity.value,
                ely = Some(test_track.track_to_test.head.ely),
                reversed = test_track.track_to_test.head.reversed,
                roadName = test_track.track_to_test.head.roadName,
                coordinates = projectSaved.coordinates
              )
            }


      val linksToReverse = track_zero_begin.map( pl => LinkToRevert(pl.id, pl.id, LinkStatus.Transfer.value, pl.geometry))

      projectService_db.changeDirection(
        projectSaved.id,
        track_zero_begin.head.roadNumber,
        track_zero_begin.head.roadPartNumber,
        linksToReverse,
        projectSaved.coordinates.get,
        projectSaved.name
      )

      val allProjectLink = projectService_db.getProjectLinks(projectSaved.id).toList
      allProjectLink.forall(pl => pl.reversed) should be(true)

      val validationErrorsAfterReverse = projectService_db.validateProjectById(projectSaved.id)
      validationErrorsAfterReverse.size should be(0)
    }
  }

  test("Test PM 3 projectService.updateProjectLinks() ") {
    runWithRollback {
      val road_40923_1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(40923, 1).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue).toList

      val reservedRoadPart_1 = ProjectReservedPart(
        road_40923_1.head.id,
        road_40923_1.head.roadNumber,
        road_40923_1.head.roadPartNumber,
        Some(road_40923_1.head.endAddrMValue),
        Some(road_40923_1.last.discontinuity),
        Some(road_40923_1.head.ely),
        newLength = None,
        newDiscontinuity = None,
        newEly = None,
        startingLinkId = Some(road_40923_1.head.linkId))

      val links = road_40923_1.map(addressToRoadLink)

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(links)

      val rap = Project(0,
        ProjectState.apply(1),
        "PM3-test",
        "test_code",
        DateTime.now(),
        "test_code",
        DateTime.now(),
        DateTime.now(),
        null,
        List(reservedRoadPart_1),
        Seq(),
        None, Some(ProjectCoordinates(372159, 6671813, 12))
      )

      val project = projectService_db.createRoadLinkProject(rap)
      val projectSaved = projectService_db.saveProject(project)

      val track_zero_begin = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(0) && x.endAddrMValue <= 4250).toList

      val track_one_middle_before_roundabout  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1) && x.endAddrMValue <= 4735).toList
      val track_two_middle_before_roundabout = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2) && x.endAddrMValue <= 4735).toList

      val track_one_middle_after_roundabout  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1) && x.endAddrMValue >= 4735 && x.endAddrMValue <= 5145).toList
      val track_two_middle_after_roundabout  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2) && x.endAddrMValue >= 4735 && x.endAddrMValue <= 5145).toList

      val track_zero_between_two_tracks = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(0) && x.startAddrMValue >= 5145 && x.endAddrMValue <= 5900).toList

      val track_one_end = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1) && x.startAddrMValue >= 5900 && x.endAddrMValue <= 6355).toList
      val track_two_end = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2) && x.startAddrMValue >= 5900 && x.endAddrMValue <= 6355).toList

      val track_zero_last = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(0) && x.startAddrMValue >= 6355).toList

      case class Test_config(
                              track_to_test: List[ProjectLink],
                              linkStatus: LinkStatus
                            )

      val road_tracks_to_test = List(
        Test_config(track_zero_begin, LinkStatus.Transfer),
        Test_config(track_one_middle_before_roundabout, LinkStatus.Transfer),
        Test_config(track_two_middle_before_roundabout, LinkStatus.Transfer),
        Test_config(track_one_middle_after_roundabout, LinkStatus.Transfer),
        Test_config(track_two_middle_after_roundabout, LinkStatus.Transfer),
        Test_config(track_zero_between_two_tracks, LinkStatus.Transfer),
        Test_config(track_one_end, LinkStatus.Transfer),
        Test_config(track_two_end, LinkStatus.Transfer),
        Test_config(track_zero_last, LinkStatus.Transfer)
      )

      for (test_track <- road_tracks_to_test) {
        println("Track: " + test_track.track_to_test.head.track)

        projectService_db.updateProjectLinks(
          projectSaved.id,
          test_track.track_to_test.map(_.id).toSet,
          List(),
          linkStatus = test_track.linkStatus,
          userName = projectSaved.name,
          newRoadNumber = test_track.track_to_test.head.roadNumber,
          newRoadPartNumber = test_track.track_to_test.head.roadPartNumber,
          newTrackCode = test_track.track_to_test.head.track.value,
          userDefinedEndAddressM = None,
          roadType = test_track.track_to_test.head.roadType.value,
          discontinuity = test_track.track_to_test.last.discontinuity.value,
          ely = Some(test_track.track_to_test.head.ely),
          reversed = test_track.track_to_test.head.reversed,
          roadName = test_track.track_to_test.head.roadName,
          coordinates = projectSaved.coordinates
        )
      }

      val validationErrorsAfterTransferStatus = projectService_db.validateProjectById(projectSaved.id)
      println("validationErrorsAfterTransferStatus")
      println(validationErrorsAfterTransferStatus)

      val continuosLinksInFrontOfRoundabout = validationErrorsAfterTransferStatus.filter(_.validationError.value == 1)
      val continuosLinksToMinorDiscontinuityIds = continuosLinksInFrontOfRoundabout.flatMap(_.affectedIds)
      val continuosLinksToMinorDiscontinuity = projectService_db.getProjectLinks(projectSaved.id).filter(x => continuosLinksToMinorDiscontinuityIds.contains(x.id)).toList

      for (test_track <- continuosLinksToMinorDiscontinuity) {
        println("linkId: " + test_track.linkId)
        projectService_db.updateProjectLinks(
          projectSaved.id,
          Set(test_track.id),
          List(),
          linkStatus = test_track.status,
          userName = projectSaved.name,
          newRoadNumber = test_track.roadNumber,
          newRoadPartNumber = test_track.roadPartNumber,
          newTrackCode = test_track.track.value,
          userDefinedEndAddressM = None,
          roadType = test_track.roadType.value,
          discontinuity = Discontinuity.MinorDiscontinuity.value,
          ely = Some(test_track.ely),
          reversed = test_track.reversed,
          roadName = test_track.roadName,
          coordinates = projectSaved.coordinates
        )
      }
      val validationErrorsAfterContinuityCorrection = projectService_db.validateProjectById(projectSaved.id)
      validationErrorsAfterContinuityCorrection.size should be(0)

      val linksToReverse = track_zero_begin.map( pl => LinkToRevert(pl.id, pl.id, LinkStatus.Transfer.value, pl.geometry))

      projectService_db.changeDirection(
        projectSaved.id,
        track_zero_begin.head.roadNumber,
        track_zero_begin.head.roadPartNumber,
        linksToReverse,
        projectSaved.coordinates.get,
        projectSaved.name
      )
      val allProjectLink = projectService_db.getProjectLinks(projectSaved.id).toList
      allProjectLink.forall(pl => pl.reversed) should be(true)

      val validationErrorsAfterReverse = projectService_db.validateProjectById(projectSaved.id)
      val discontinuosLinks = validationErrorsAfterReverse.filter(_.validationError.value == 9)
      val discontinuosLinkIds = discontinuosLinks.flatMap(_.affectedIds)
      val discontinuosLinksToContinuous = projectService_db.getProjectLinks(projectSaved.id).filter(x => discontinuosLinkIds.contains(x.id)).toList

      for (test_track <- discontinuosLinksToContinuous) {
        println("linkId: " + test_track.linkId)

        projectService_db.updateProjectLinks(
          projectSaved.id,
          Set(test_track.id),
          List(),
          linkStatus = test_track.status,
          userName = projectSaved.name,
          newRoadNumber = test_track.roadNumber,
          newRoadPartNumber = test_track.roadPartNumber,
          newTrackCode = test_track.track.value,
          userDefinedEndAddressM = None,
          roadType = test_track.roadType.value,
          discontinuity = Discontinuity.Continuous.value,
          ely = Some(test_track.ely),
          reversed = test_track.reversed,
          roadName = test_track.roadName,
          coordinates = projectSaved.coordinates
        )
      }

      val validationErrorsAfterReverseContinuousChecks = projectService_db.validateProjectById(projectSaved.id)
      validationErrorsAfterReverseContinuousChecks.size should be(0)

    }
  }

  test("Test PM 4 projectService.updateProjectLinks() ") {
    runWithRollback {
      val road_99998_1 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(99998, 1).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue).toList

      val reservedRoadPart_1 = ProjectReservedPart(
        road_99998_1.head.id,
        road_99998_1.head.roadNumber,
        road_99998_1.head.roadPartNumber,
        Some(road_99998_1.head.endAddrMValue),
        Some(road_99998_1.last.discontinuity),
        Some(road_99998_1.head.ely),
        newLength = None,
        newDiscontinuity = None,
        newEly = None,
        startingLinkId = Some(road_99998_1.head.linkId))

      val links = road_99998_1.map(addressToRoadLink)

      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(links)

      val rap = Project(0,
        ProjectState.apply(1),
        "PM4-test",
        "test_code",
        DateTime.now(),
        "test_code",
        DateTime.now(),
        DateTime.now(),
        null,
        List(reservedRoadPart_1),
        Seq(),
        None, Some(ProjectCoordinates(371746, 6669692, 12))
      )

      val project = projectService_db.createRoadLinkProject(rap)
      val projectSaved = projectService_db.saveProject(project)

      val road_99998_1_projectlinks  = projectService_db.getProjectLinks(projectSaved.id)

      // Set project links to transfer status
      for (test_track <- road_99998_1_projectlinks) {
        println("linkId: " + test_track.linkId)

        projectService_db.updateProjectLinks(
          projectSaved.id,
          Set(test_track.id),
          List(),
          linkStatus = LinkStatus.Transfer,
          userName = projectSaved.name,
          newRoadNumber = test_track.roadNumber,
          newRoadPartNumber = test_track.roadPartNumber,
          newTrackCode = test_track.track.value,
          userDefinedEndAddressM = None,
          roadType = test_track.roadType.value,
          discontinuity = test_track.discontinuity.value,
          ely = Some(test_track.ely),
          reversed = test_track.reversed,
          roadName = test_track.roadName,
          coordinates = projectSaved.coordinates
        )
      }
      val road_99998_1_projectlinks_transfered  = projectService_db.getProjectLinks(projectSaved.id)
      val linksToReverse = road_99998_1_projectlinks_transfered.map( pl => LinkToRevert(pl.id, pl.id, LinkStatus.Transfer.value, pl.geometry))

      projectService_db.changeDirection(
        projectSaved.id,
        road_99998_1_projectlinks_transfered.head.roadNumber,
        road_99998_1_projectlinks_transfered.head.roadPartNumber,
        linksToReverse,
        projectSaved.coordinates.get,
        projectSaved.name
      )

      val allProjectLinkAfterReverse = projectService_db.getProjectLinks(projectSaved.id).toList
      allProjectLinkAfterReverse.forall(pl => pl.reversed) should be(true)

      val validationErrorsAfterReverse = projectService_db.validateProjectById(projectSaved.id)
      validationErrorsAfterReverse.size should be(0)

    }//Rollback
  }

    }
