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

class Viite_13_218_spec extends FunSuite with Matchers with BeforeAndAfter {
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


  test("Test road_13_218 projectService.updateProjectLinks() "
  //  "When reserving a road part, try to renumber it to the same number Then it should produce an error."
  ) {
    runWithRollback {
//      reset(mockRoadLinkService)

      val road_13_218 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(13, 218).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue).toList

      val reservedRoadPart_1 = ProjectReservedPart(
                                                  road_13_218.head.id,
                                                  road_13_218.head.roadNumber,
                                                  road_13_218.head.roadPartNumber,
                                                  Some(road_13_218.head.endAddrMValue),
                                                  Some(road_13_218.head.discontinuity),
                                                  Some(road_13_218.head.ely),
                                                  newLength = None,
                                                  newDiscontinuity = None,
                                                  newEly = None,
                                                  startingLinkId = Some(road_13_218.head.linkId))

      val links = road_13_218.map(addressToRoadLink)
      when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
      when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(links)



      val rap = Project(0,
                        ProjectState.apply(1),
                        "13_218",
                        "test_code",
                        DateTime.now(),
                        "test_code",
                        DateTime.now(),
                        DateTime.now(),
                        null,
                        List(reservedRoadPart_1),
                        Seq(),
                        None, Some(ProjectCoordinates(512315,6838732,8))
      )

      val project = projectService_db.createRoadLinkProject(rap)
      val projectSaved = projectService_db.saveProject(project)


      case class Test_config(
                              track_to_test: List[ProjectLink],
                              discontinuity: Int,
                              linkStatus: LinkStatus
                            )

      case class Test_terminated_config(
                              track_to_test: List[ProjectLink],
                              linkStatus: LinkStatus
                            )

      val first_road_part_to_update = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(0) && x.endAddrMValue <= 1510).toList


//      val third_road_part_to_update  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(0)).toList


      val road_tracks_to_test_1 = List(
                                      Test_config(first_road_part_to_update, 5, LinkStatus.UnChanged)
                                    )

      for (test_track <- road_tracks_to_test_1) {
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
          discontinuity = test_track.discontinuity,
          ely = Some(test_track.track_to_test.head.ely),
          reversed = false,
          roadName = Some("Kokkola-Nuijamaa"),
          coordinates = projectSaved.coordinates
        )
      }

      val links_to_terminate = projectService_db.getProjectLinks(projectSaved.id).filter(x => List(11910502, 11910505, 3227478, 3227484, 3227486, 11910568, 11910533, 3227482, 3227480, 11910572, 11910587, 11910588, 12017340, 12017341).contains(x.linkId)).toList

      val road_tracks_to_test_2 = List(
        Test_terminated_config(links_to_terminate,  LinkStatus.Terminated)
      )

      for (test_track <- road_tracks_to_test_2) {
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
          reversed = false,
          roadName = test_track.track_to_test.last.roadName,
          coordinates = projectSaved.coordinates
        )
      }

      case class New_links_config(
        coordinates : Option[ProjectCoordinates],
        discontinuity:  Discontinuity,
        ids: Set[Long],
        linkIds: Seq[Long],
        linkStatus: LinkStatus,
        projectId: Long,
        roadEly: Long,
        roadLinkSource:  LinkGeomSource,
        roadName:  Option[String],
        roadNumber: Long,
        roadPartNumber: Long,
        roadType:  RoadType,
        trackCode: Track,
        userDefinedEndAddressM:  Option[Int])

  val new_links = New_links_config(
      coordinates = projectSaved.coordinates,
      discontinuity =  Discontinuity.MinorDiscontinuity,
      ids =  Set(),
      linkIds =  Seq(11910497, 11910547, 11910527),
      linkStatus =  LinkStatus.New,
      projectId =  projectSaved.id,
      roadEly =  8,
      roadLinkSource =  LinkGeomSource.FrozenLinkInterface,
      roadName =  Some("Kokkola-Nuijamaa"),
      roadNumber =  13,
      roadPartNumber =  218,
      roadType =  RoadType.PublicRoad,
      trackCode =  Track.RightSide,
      userDefinedEndAddressM = None)

      val response = projectService_db.createProjectLinks(new_links.linkIds, new_links.projectId, new_links.roadNumber, new_links.roadPartNumber,
        new_links.trackCode, new_links.discontinuity, new_links.roadType,
        new_links.roadLinkSource, new_links.roadEly, projectSaved.createdBy, new_links.roadName.get,
        new_links.coordinates)

      val new_links_2 = New_links_config(
        coordinates = projectSaved.coordinates,
        discontinuity =  Discontinuity.Continuous,
        ids =  Set(),
        linkIds =  Seq(11910567),
        linkStatus =  LinkStatus.New,
        projectId =  projectSaved.id,
        roadEly =  8,
        roadLinkSource =  LinkGeomSource.FrozenLinkInterface,
        roadName =  Some("Kokkola-Nuijamaa"),
        roadNumber =  13,
        roadPartNumber =  218,
        roadType =  RoadType.PublicRoad,
        trackCode =  Track.RightSide,
        userDefinedEndAddressM = None)

      projectService_db.createProjectLinks(new_links_2.linkIds, new_links_2.projectId, new_links_2.roadNumber, new_links_2.roadPartNumber,
        new_links_2.trackCode, new_links_2.discontinuity, new_links_2.roadType,
        new_links_2.roadLinkSource, new_links_2.roadEly, projectSaved.createdBy, new_links_2.roadName.get,
        new_links_2.coordinates)

      val transfer_1_links = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1) && x.linkId == 11910585).toList
      val transfer_1 = List(
        Test_config(transfer_1_links, 5, LinkStatus.Transfer)
      )

      for (test_track <- transfer_1) {
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
          discontinuity = test_track.discontinuity,
          ely = Some(test_track.track_to_test.head.ely),
          reversed = false,
          roadName = Some("Kokkola-Nuijamaa"),
          coordinates = projectSaved.coordinates
        )
      }

      val new_links_3 = New_links_config(
        coordinates = projectSaved.coordinates,
        discontinuity =  Discontinuity.Continuous,
        ids =  Set(),
        linkIds =  Seq(11910509),
        linkStatus =  LinkStatus.New,
        projectId =  projectSaved.id,
        roadEly =  8,
        roadLinkSource =  LinkGeomSource.FrozenLinkInterface,
        roadName =  Some("Kokkola-Nuijamaa"),
        roadNumber =  13,
        roadPartNumber =  218,
        roadType =  RoadType.PublicRoad,
        trackCode =  Track.RightSide,
        userDefinedEndAddressM = None)

      projectService_db.createProjectLinks(new_links_3.linkIds, new_links_3.projectId, new_links_3.roadNumber, new_links_3.roadPartNumber,
        new_links_3.trackCode, new_links_3.discontinuity, new_links_3.roadType,
        new_links_3.roadLinkSource, new_links_3.roadEly, projectSaved.createdBy, new_links_3.roadName.get,
        new_links_3.coordinates)

      val new_links_4 = New_links_config(
        coordinates = projectSaved.coordinates,
        discontinuity =  Discontinuity.Continuous,
        ids =  Set(),
        linkIds =  Seq(11910589),
        linkStatus =  LinkStatus.New,
        projectId =  projectSaved.id,
        roadEly =  8,
        roadLinkSource =  LinkGeomSource.FrozenLinkInterface,
        roadName =  Some("Kokkola-Nuijamaa"),
        roadNumber =  13,
        roadPartNumber =  218,
        roadType =  RoadType.PublicRoad,
        trackCode =  Track.Combined,
        userDefinedEndAddressM = None)

      projectService_db.createProjectLinks(new_links_4.linkIds, new_links_4.projectId, new_links_4.roadNumber, new_links_4.roadPartNumber,
        new_links_4.trackCode, new_links_4.discontinuity, new_links_4.roadType,
        new_links_4.roadLinkSource, new_links_4.roadEly, projectSaved.createdBy, new_links_4.roadName.get,
        new_links_4.coordinates)


      val transfer_2_links = projectService_db.getProjectLinks(projectSaved.id).filter(x => List(11910590, 3227503, 3227468, 3227469, 3227544, 3227541).contains(x.linkId)).toList
      println(transfer_2_links)
      val transfer_2 = List(
        Test_config(transfer_2_links, 2, LinkStatus.Transfer)
      )

      for (test_track <- transfer_2) {
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
          discontinuity = test_track.discontinuity,
          ely = Some(test_track.track_to_test.head.ely),
          reversed = false,
          roadName = Some("Kokkola-Nuijamaa"),
          coordinates = projectSaved.coordinates
        )
      }

      val new_links_5 = New_links_config(
        coordinates = projectSaved.coordinates,
        discontinuity =  Discontinuity.Continuous,
        ids =  Set(),
        linkIds =  Seq(11910501),
        linkStatus =  LinkStatus.New,
        projectId =  projectSaved.id,
        roadEly =  8,
        roadLinkSource =  LinkGeomSource.FrozenLinkInterface,
        roadName =  Some("Kokkola-Nuijamaa"),
        roadNumber =  13,
        roadPartNumber =  218,
        roadType =  RoadType.PublicRoad,
        trackCode =  Track.LeftSide,
        userDefinedEndAddressM = None)

      projectService_db.createProjectLinks(new_links_5.linkIds, new_links_5.projectId, new_links_5.roadNumber, new_links_5.roadPartNumber,
        new_links_5.trackCode, new_links_5.discontinuity, new_links_5.roadType,
        new_links_5.roadLinkSource, new_links_5.roadEly, projectSaved.createdBy, new_links_5.roadName.get,
        new_links_5.coordinates)


      val transfer_3_links = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.linkId == 11910540).toList
      val transfer_3 = List(
        Test_config(transfer_3_links, 5, LinkStatus.Transfer)
      )

      for (test_track <- transfer_3) {
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
          discontinuity = test_track.discontinuity,
          ely = Some(test_track.track_to_test.head.ely),
          reversed = false,
          roadName = Some("Kokkola-Nuijamaa"),
          coordinates = projectSaved.coordinates
        )
      }

      val new_links_6 = New_links_config(
        coordinates = projectSaved.coordinates,
        discontinuity =  Discontinuity.Continuous,
        ids =  Set(),
        linkIds =  Seq(11910530, 11910544, 11910546),
        linkStatus =  LinkStatus.New,
        projectId =  projectSaved.id,
        roadEly =  8,
        roadLinkSource =  LinkGeomSource.FrozenLinkInterface,
        roadName =  Some("Kokkola-Nuijamaa"),
        roadNumber =  13,
        roadPartNumber =  218,
        roadType =  RoadType.PublicRoad,
        trackCode =  Track.LeftSide,
        userDefinedEndAddressM = None)

      projectService_db.createProjectLinks(new_links_6.linkIds, new_links_6.projectId, new_links_6.roadNumber, new_links_6.roadPartNumber,
        new_links_6.trackCode, new_links_6.discontinuity, new_links_6.roadType,
        new_links_6.roadLinkSource, new_links_6.roadEly, projectSaved.createdBy, new_links_6.roadName.get,
        new_links_6.coordinates)

      val new_links_7 = New_links_config(
        coordinates = projectSaved.coordinates,
        discontinuity =  Discontinuity.Continuous,
        ids =  Set(),
        linkIds =  Seq(11910569),
        linkStatus =  LinkStatus.New,
        projectId =  projectSaved.id,
        roadEly =  8,
        roadLinkSource =  LinkGeomSource.FrozenLinkInterface,
        roadName =  Some("Kokkola-Nuijamaa"),
        roadNumber =  13,
        roadPartNumber =  218,
        roadType =  RoadType.PublicRoad,
        trackCode =  Track.LeftSide,
        userDefinedEndAddressM = None)

      projectService_db.createProjectLinks(new_links_7.linkIds, new_links_7.projectId, new_links_7.roadNumber, new_links_7.roadPartNumber,
        new_links_7.trackCode, new_links_7.discontinuity, new_links_7.roadType,
        new_links_7.roadLinkSource, new_links_7.roadEly, projectSaved.createdBy, new_links_7.roadName.get,
        new_links_7.coordinates)

      val new_links_8 = New_links_config(
        coordinates = projectSaved.coordinates,
        discontinuity =  Discontinuity.Continuous,
        ids =  Set(),
        linkIds =  Seq(11910511),
        linkStatus =  LinkStatus.New,
        projectId =  projectSaved.id,
        roadEly =  8,
        roadLinkSource =  LinkGeomSource.FrozenLinkInterface,
        roadName =  Some("Kokkola-Nuijamaa"),
        roadNumber =  13,
        roadPartNumber =  218,
        roadType =  RoadType.PublicRoad,
        trackCode =  Track.LeftSide,
        userDefinedEndAddressM = None)

      projectService_db.createProjectLinks(new_links_8.linkIds, new_links_8.projectId, new_links_8.roadNumber, new_links_8.roadPartNumber,
        new_links_8.trackCode, new_links_8.discontinuity, new_links_8.roadType,
        new_links_8.roadLinkSource, new_links_8.roadEly, projectSaved.createdBy, new_links_8.roadName.get,
        new_links_8.coordinates)


      val transfer_4_links = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2) && x.linkId == 11910586).toList
      val transfer_4 = List(
        Test_config(transfer_4_links, 5, LinkStatus.Transfer)
      )

      for (test_track <- transfer_4) {
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
          discontinuity = test_track.discontinuity,
          ely = Some(test_track.track_to_test.head.ely),
          reversed = false,
          roadName = Some("Kokkola-Nuijamaa"),
          coordinates = projectSaved.coordinates
        )
      }

    }//Rollback
  }

    }
