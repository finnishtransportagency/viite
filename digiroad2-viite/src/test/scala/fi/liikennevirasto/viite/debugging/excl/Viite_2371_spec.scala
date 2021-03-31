package fi.liikennevirasto.viite.debugging.excl

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.{NodesAndJunctionsService, ProjectService, RoadAddressService, RoadType}
//import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{Track, ViiteProperties}
import fi.liikennevirasto.viite.dao._
//import org.mockito.Mockito.spy
//import fi.liikennevirasto.viite.process.strategy.RoadAddressSectionCalculatorContext
// .defaultSectionCalculatorStrategy.assignMValues
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import fi.liikennevirasto.viite.process.strategy.DefaultSectionCalculatorStrategy
import fi.liikennevirasto.viite.util._
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, spy, when}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
//import org.mockito.stubbing.Answer
//import org.powermock.api.mockito.PowerMockito
class Viite_2371_spec extends FunSuite with Matchers with BeforeAndAfter {
	val mockProjectService: ProjectService = MockitoSugar.mock[ProjectService]
	val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]

	val mockDefaultSectionCalculatorStrategy: DefaultSectionCalculatorStrategy = MockitoSugar
      .mock[DefaultSectionCalculatorStrategy]

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


	/* db digiroad2Context.scala*/ val junctionDAO_db: JunctionDAO = {
		new JunctionDAO
	}
	val nodesAndJunctionsService_db: NodesAndJunctionsService = {
		new NodesAndJunctionsService(roadwayDAO, roadwayPointDAO, linearLocationDAO, nodeDAO, nodePointDAO,
                                     junctionDAO_db, junctionPointDAO, roadwayChangesDAO)
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
	val roadAddressService_db: RoadAddressService = new RoadAddressService(roadLinkService_db, roadwayDAO,
                                                                           linearLocationDAO, roadNetworkDAO,
                                                                           roadwayPointDAO, nodePointDAO,
                                                                           junctionPointDAO, roadwayAddressMapper,
                                                                           eventbus_db, true)

	val projectService_db: ProjectService = spy(new ProjectService(roadAddressService_db, roadLinkService_db,
                                                                   nodesAndJunctionsService_db, roadwayDAO,
                                                                   roadwayPointDAO, linearLocationDAO, projectDAO,
                                                                   projectLinkDAO, nodeDAO, nodePointDAO,
                                                                   junctionPointDAO, projectReservedPartDAO,
                                                                   roadwayChangesDAO, roadwayAddressMapper,
                                                                   eventbus_db, frozenTimeVVHAPIServiceEnabled = true))

	/* ---db */
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
	}*/

	/* update errors*/ def errorPartsToApi(errorParts: projectService_db.projectValidator.ValidationErrorDetails)
    : Map[String, Any] = {
		Map("ids" -> errorParts.affectedIds, "errorCode" -> errorParts.validationError.value, "errorMessage" ->
                                                                                              errorParts
                                                                                                .validationError
                                                                                                .message, "info" ->
                                                                                                          errorParts
                                                                                                            .optionalInformation, "coordinates" -> errorParts.coordinates, "priority" -> errorParts.validationError.priority)
	}

	def projectFormedPartToApi(projectId: Option[Long] = None)(formedRoadPart: ProjectReservedPart): Map[String, Any]
    = {
		Map("roadNumber" -> formedRoadPart.roadNumber, "roadPartNumber" -> formedRoadPart.roadPartNumber, "id" ->
                                                                                                          formedRoadPart.id, "currentEly" -> formedRoadPart.ely, "currentLength" -> formedRoadPart.addressLength, "currentDiscontinuity" -> formedRoadPart.discontinuity.map(_.description), "newEly" -> formedRoadPart.newEly, "newLength" -> formedRoadPart.newLength, "newDiscontinuity" -> formedRoadPart.newDiscontinuity.map(_.description), "startingLinkId" -> formedRoadPart.startingLinkId, "roadAddresses" -> {
			projectId match {
				case None => Seq.empty
				case _    => projectService_db.getRoadAddressesFromFormedRoadPart(formedRoadPart.roadNumber,
                                                                                  formedRoadPart.roadPartNumber,
                                                                                  projectId.get)
			}
		})
	}

	def projectReservedPartToApi(reservedRoadPart: ProjectReservedPart): Map[String, Any] = {
		Map("roadNumber" -> reservedRoadPart.roadNumber, "roadPartNumber" -> reservedRoadPart.roadPartNumber, "id" ->
                                                                                                              reservedRoadPart.id, "currentEly" -> reservedRoadPart.ely, "currentLength" -> reservedRoadPart.addressLength, "currentDiscontinuity" -> reservedRoadPart.discontinuity.map(_.description), "newEly" -> reservedRoadPart.newEly, "newLength" -> reservedRoadPart.newLength, "newDiscontinuity" -> reservedRoadPart.newDiscontinuity.map(_.description), "startingLinkId" -> reservedRoadPart.startingLinkId)
	}

	private def addressToRoadLink(ral: RoadAddress): RoadLink = {
		val geomLength = GeometryUtils.geometryLength(ral.geometry)
		val adminClass = ral.roadType match {
			case RoadType.FerryRoad              => AdministrativeClass.apply(1)
			case RoadType.PublicRoad             => AdministrativeClass.apply(1)
			case RoadType.MunicipalityStreetRoad => AdministrativeClass.apply(2)
			case RoadType.PrivateRoadType        => AdministrativeClass.apply(3)
			case _                               => AdministrativeClass.apply(99)
		}
		RoadLink(ral.linkId, ral.geometry, geomLength, adminClass, 1, extractTrafficDirection(ral.sideCode, ral.track)
                 , LinkType.apply(99), Option(ral.startDate.toString), ral.createdBy, Map("MUNICIPALITYCODE" ->
                                                                                          BigInt(749),
                                                                                          "VERTICALLEVEL" -> BigInt
                                                                                                             (1),
                                                                                          "SURFACETYPE" -> BigInt(1),
                                                                                          "ROADNUMBER" -> BigInt(ral
                                                                                                                   .roadNumber), "ROADPARTNUMBER" -> BigInt(ral.roadPartNumber)), ConstructionType.InUse, ral.linkGeomSource)
	}

	private def extractTrafficDirection(sideCode: SideCode, track: Track): TrafficDirection = {
		(sideCode, track) match {
			case (_, Track.Combined)                  => TrafficDirection.BothDirections
			case (TowardsDigitizing, Track.RightSide) => TrafficDirection.TowardsDigitizing
			case (TowardsDigitizing, Track.LeftSide)  => TrafficDirection.AgainstDigitizing
			case (AgainstDigitizing, Track.RightSide) => TrafficDirection.AgainstDigitizing
			case (AgainstDigitizing, Track.LeftSide)  => TrafficDirection.TowardsDigitizing
			case (_, _)                               => TrafficDirection.UnknownDirection
		}
	}

	private def mapValidationIssues(issue: projectService_db.projectValidator.ValidationErrorDetails): Map[String,
      Any] = {
		Map("id" -> issue.projectId, "validationError" -> issue.validationError.value, "affectedIds" -> issue
          .affectedIds.toArray, "coordinates" -> issue.coordinates, "optionalInformation" -> issue
          .optionalInformation.getOrElse(""))
	}


	test("Test projectService.updateProjectLinks() for Viite-2371 " + "When reserving a road part, try to renumber it " +
         "to the same number Then" + " it should produce an error.") {
		runWithRollback {
			reset(mockRoadLinkService)

			val road_4_426 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways
            (roadwayDAO.fetchAllBySection(4, 426).map(_.roadwayNumber).toSet)).sortBy(_.startAddrMValue).toList //val

			val roadToReserve = road_4_426

			val reservedRoadPart = ProjectReservedPart(roadToReserve.head.id, roadToReserve.head.roadNumber,
                                                       roadToReserve.head.roadPartNumber,
                                                       Some(roadToReserve.head.endAddrMValue),
                                                       Some(roadToReserve.head.discontinuity),
                                                       Some(roadToReserve.head.ely),
                                                       newLength = None,
                                                       newDiscontinuity = None,
                                                       newEly = None,
                                                       startingLinkId = Some(roadToReserve.head.linkId))

			val links = roadToReserve.map(addressToRoadLink)

			when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
			when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenReturn(links)

			val rap = Project(0, ProjectState.apply(1), "HN_2371_road_4_426_siirto_test", "from_test_code", DateTime.now(),
                              "from_test_code", DateTime.now(), DateTime.now(), null, List(reservedRoadPart), Seq(),
                              None, //Some(ProjectCoordinates(390186.848, 6698403.667,12)
			                  Some(ProjectCoordinates(386778, 7298993, 12)))
			val project = projectService_db.createRoadLinkProject(rap)

			/*val reservable = projectService_db.checkRoadPartExistsAndReservable(
				road_43510_1.head.roadNumber,
				road_43510_1.head.roadPartNumber,
				road_43510_1.head.roadPartNumber,
				DateTime.now(),
				project.id) match {
			  case Left(err) => Map("success" -> err)
			  case Right((reservedparts, formedparts)) => Map("success" -> "ok", "reservedInfo" -> reservedparts.map
			  (projectReservedPartToApi),
				"formedInfo" -> formedparts.map(projectFormedPartToApi()))
			}
			println("reservable: " + reservable + "\n")*/
			val projectSaved = projectService_db.saveProject(project) //val firstLink = projectService_db
            // .getFirstProjectLink(projectSaved)
			//when(projectService_db.recalculateProjectLinks(projectSaved.id, "test_code")).thenCallRealMethod()
			/* LOAD project*/
			/*
				 projectServiceprojectService.getSingleProjectById(projectId) match {
				   case Some(project) =>
					 val projectMap = roadAddressProjectToApi(project, projectService.getProjectEly(project.id))
					 val reservedparts = project.reservedParts.map(projectReservedPartToApi)
					 val formedparts = project.formedParts.map(projectFormedPartToApi(Some(project.id)))
					 val errorParts = projectService.validateProjectById(project.id)
					 val publishable = errorParts.isEmpty
					 val latestPublishedNetwork = roadNetworkService.getLatestPublishedNetworkDate
					 Map("project" -> projectMap, "linkId" -> project.reservedParts.find(_.startingLinkId.nonEmpty)
					 .flatMap(_.startingLinkId),
					   "reservedInfo" -> reservedparts, "formedInfo" -> formedparts, "publishable" -> publishable,
					   "projectErrors" -> errorParts.map(errorPartsToApi),
					   "publishedNetworkDate" -> formatDateTimeToString(latestPublishedNetwork))
				   case _ => halt(NotFound("Project not found"))
				 }
				 *//* -- load project*/

			case class Test_config(
			                        track_to_test: List[ProjectLink],
			                        discontinuity: Int,
			                        linkStatus: LinkStatus,
			                        roadPartNumber: Int
			                      )

			val first_road_part_to_update = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track ==
			                                                                                               Track(1)
			                                                                                               && x.roadPartNumber == 426 && x.endAddrMValue <= 3780).toList
			val second_road_part_to_update = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track ==
			                                                                                                Track(2)
			                                                                                                && x.roadPartNumber == 426 && x.endAddrMValue <= 3780).toList
			val third_road_part_to_update  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1) && x.roadPartNumber == 426 && x.endAddrMValue > 3780 && x.endAddrMValue <= 7900).toList
			val fourth_road_part_to_update = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2) && x.roadPartNumber == 426 && x.endAddrMValue > 3780 && x.endAddrMValue <= 7900).toList
//			val third_road_part_to_update  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1) && x.roadPartNumber == 426 && x.endAddrMValue > 3780 && x.endAddrMValue < 7900).toList
//			val fourth_road_part_to_update = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2) && x.roadPartNumber == 426 && x.endAddrMValue > 3780 && x.endAddrMValue < 7900).toList
//			val fifth_road_part_to_update  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(1) && x.roadPartNumber == 426 && x.endAddrMValue == 7900).toList
//			val sixth_road_part_to_update  = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track == Track(2) && x.roadPartNumber == 426 && x.endAddrMValue == 7900).toList

/*
      val fourth_road_part_to_update = projectService_db.getProjectLinks(projectSaved.id).filter(x => x.track ==
      Track(2) && x.roadPartNumber == 2).toList
*/
			val road_tracks_to_test = List(
				Test_config(first_road_part_to_update,  5, LinkStatus.UnChanged, 426),
				Test_config(second_road_part_to_update, 5, LinkStatus.UnChanged, 426),
				Test_config(third_road_part_to_update,  2, LinkStatus.Transfer, 427),
				Test_config(fourth_road_part_to_update, 2, LinkStatus.Transfer, 427)
//				Test_config(fifth_road_part_to_update,  2, LinkStatus.Transfer),
//			    Test_config(sixth_road_part_to_update,  2, LinkStatus.Transfer)
				)

			//import org.mockito.AdditionalAnswers
			//import org.mockito.Mockito
			for ((test_track, index) <- road_tracks_to_test.view.zipWithIndex) {
				println("Track: " + test_track.track_to_test.head.roadPartNumber + " " + test_track.track_to_test.head
                                                                                                   .track + " index: " + index)
				if (index==2 )
					println("2")

				projectService_db.updateProjectLinks(
													 projectSaved.id,
													 test_track.track_to_test.map(_.id).toSet,
													 List(),
													 test_track.linkStatus,
													 projectSaved.name,
                                                     newRoadNumber      = test_track.track_to_test.head.roadNumber,
                                                     newRoadPartNumber  = test_track.roadPartNumber,
													 newTrackCode       = test_track.track_to_test.head.track.value,
                                                     userDefinedEndAddressM = None,
													 first_road_part_to_update.head.roadType.value,
													 test_track.discontinuity,
													 Some(first_road_part_to_update.head.ely),
													 test_track.track_to_test.head.reversed,
													 test_track.track_to_test.head.roadName,
													 projectSaved.coordinates)
				match {
					case Some(errorMessage) => Map("success" -> false, "errorMessage" -> errorMessage)
					case None               => val projectErrors = projectService_db.validateProjectById(projectSaved.id).map(x => errorPartsToApi(x.asInstanceOf[projectService_db.projectValidator.ValidationErrorDetails]))
						val fetched_project = projectService_db.getSingleProjectById(projectSaved.id).get
						Map("success" -> true, "id" -> fetched_project.id, "publishable" -> projectErrors.isEmpty,
                            "projectErrors" -> projectErrors, "formedInfo" -> project.formedParts.map
                            (projectFormedPartToApi(Some(fetched_project.id))))
				}

			}

			val updatedLinks = projectLinkDAO.fetchProjectLinks(projectSaved.id)
			println("updatedLinks:")
			updatedLinks.foreach(println)

			//val validationErrors = projectService_db.validateProjectById(projectSaved.id)//.map(mapValidationIssues)
			val validationErrors = projectService_db.validateProjectById(projectSaved.id).map(x => mapValidationIssues
                                                                                                   (x.asInstanceOf[projectService_db.projectValidator.ValidationErrorDetails]))
			//TODO change UI to not override project validator errors on change table call
			val (changeProject, warningMessage) = projectService_db.getChangeProject(project.id)
			val changeTableData = changeProject.map(project => Map("id" -> project.id, "user" -> project.user, "name" -> project.name, "changeDate" -> project.changeDate, "changeInfoSeq" -> project.changeInfoSeq.map(changeInfo => Map("changetype" -> changeInfo.changeType.value, "roadType" -> changeInfo.roadType.value, "discontinuity" -> changeInfo.discontinuity.value, "source" -> changeInfo.source, "target" -> changeInfo.target, "reversed" -> changeInfo.reversed)))).getOrElse(None)

			println(Map("changeTable" -> changeTableData, "validationErrors" -> validationErrors, "warningMessage" ->
                                                                                                  warningMessage))

			true should be(true)
		}
	}

}
