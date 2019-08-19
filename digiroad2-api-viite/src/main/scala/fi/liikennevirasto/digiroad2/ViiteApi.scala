package fi.liikennevirasto.digiroad2

import java.text.SimpleDateFormat

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.authentication.RequestHeaderAuthentication
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.user.{User, UserProvider}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, RoadPartReservedException, Track}
import fi.liikennevirasto.viite.util.DigiroadSerializers
import fi.liikennevirasto.viite.AddressConsistencyValidator.AddressErrorDetails
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.ProjectState.SendingToTR
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model._
import fi.liikennevirasto.viite.util.SplitOptions
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.json4s._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.Swagger
import org.scalatra.{NotFound, _}
import org.slf4j.{Logger, LoggerFactory}
import org.scalatra.swagger._

import scala.util.parsing.json.JSON._
import scala.util.{Left, Right}

/**
  * Created by venholat on 25.8.2016.
  */

case class NewAddressDataExtracted(sourceIds: Set[Long], targetIds: Set[Long])

case class RevertSplitExtractor(projectId: Option[Long], linkId: Option[Long], coordinates: ProjectCoordinates)

case class RevertRoadLinksExtractor(projectId: Long, roadNumber: Long, roadPartNumber: Long, links: List[LinkToRevert], coordinates: ProjectCoordinates)

case class ProjectRoadAddressInfo(projectId: Long, roadNumber: Long, roadPartNumber: Long)

case class RoadAddressProjectExtractor(id: Long, projectEly: Option[Long], status: Long, name: String, startDate: String,
                                       additionalInfo: String, reservedPartList: List[RoadPartExtractor], formedPartList: List[RoadPartExtractor], resolution: Int)

case class RoadAddressProjectLinksExtractor(ids: Set[Long], linkIds: Seq[Long], linkStatus: Int, projectId: Long, roadNumber: Long,
                                            roadPartNumber: Long, trackCode: Int, discontinuity: Int, roadEly: Long,
                                            roadLinkSource: Int, roadType: Int, userDefinedEndAddressM: Option[Int],
                                            coordinates: ProjectCoordinates, roadName: Option[String], reversed: Option[Boolean])

case class roadDataExtractor(chainLinkIds: Seq[Long] )

case class RoadPartExtractor(roadNumber: Long, roadPartNumber: Long, ely: Long)

case class CutLineExtractor(linkId: Long, splitedPoint: Point)

class ViiteApi(val roadLinkService: RoadLinkService, val vVHClient: VVHClient,
               val roadAddressService: RoadAddressService,
               val projectService: ProjectService,
               val roadNetworkService: RoadNetworkService,
               val roadNameService: RoadNameService,
               val nodesAndJunctionsService: NodesAndJunctionsService,
               val userProvider: UserProvider = Digiroad2Context.userProvider,
               val deploy_date: String = Digiroad2Context.deploy_date,
               implicit val swagger: Swagger
              )
  extends ScalatraServlet
    with JacksonJsonSupport
    with CorsSupport
    with RequestHeaderAuthentication
    with ContentEncodingSupport
    with SwaggerSupport {

  protected val applicationDescription = "The user interface API "

  class Contains(r: Range) {
    def unapply(i: Int): Boolean = r contains i
  }

  private val dtf: DateTimeFormatter = DateTimeFormat.forPattern("dd/MM/yyyy")
  val DrawMainRoadPartsOnly = 1
  val DrawRoadPartsOnly = 2
  val DrawLinearPublicRoads = 3
  val DrawPublicRoads = 4
  val DrawAllRoads = 5

  val logger: Logger = LoggerFactory.getLogger(getClass)
  protected implicit val jsonFormats: Formats = DigiroadSerializers.jsonFormats
  globalNumberParser = {
    in =>
      try in.toLong catch {
        case _: NumberFormatException => in.toDouble
      }
  }

  before() {
    contentType = formats("json") + "; charset=utf-8"
    try {
      authenticateForApi(request)(userProvider)
      if (request.isWrite && !userProvider.getCurrentUser().hasViiteWriteAccess) {
        halt(Unauthorized("No write permissions"))
      }
    } catch {
      case ise: IllegalStateException => halt(Unauthorized("Authentication error: " + ise.getMessage))
    }
    response.setHeader(Digiroad2Context.Digiroad2ServerOriginatedResponseHeader, "true")
  }

  private val getStartupParameters: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getStartupParameters")
      tags "ViiteAPI - General"
      summary "Show all statup parameters"
      notes "Shows all the start. You can search it too.")

  get("/startupParameters", operation(getStartupParameters)) {
    time(logger, "GET request for /startupParameters") {
      val (east, north, zoom) = {
        val config = userProvider.getCurrentUser().configuration
        (config.east.map(_.toDouble), config.north.map(_.toDouble), config.zoom.map(_.toInt))
      }
      StartupParameters(east.getOrElse(DefaultLatitude), north.getOrElse(DefaultLongitude), zoom.getOrElse(DefaultZoomLevel), deploy_date)
    }
  }

  private val getUser: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[Map[String,Any]]("getUser")
      tags "ViiteAPI - General"
      summary "Shows the current user and it's roles."
      notes "Appears at the start of the application. One can search it too"
      )

  get("/user", operation(getUser)) {
    time(logger, "GET request for /user") {
      Map("userName" -> userProvider.getCurrentUser().username, "roles" -> userProvider.getCurrentUser().configuration.roles)
    }
  }

  private val getRoadAddress: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Seq[Seq[Map[String, Any]]]]("getRoadAddress")
      .parameters(
        queryParam[Int]("zoom").description("Current zoom level of the map"),
        queryParam[String]("bbox").description("String containing the 4 vertexes of a square, is akin to the viewport.\r\n" +
          "Format: Number,Number,Number,Number")
      )
      tags "ViiteAPI - RoadAddresses"
      summary "Returns all the road addresses that fit inside the viewport."
      notes getRoadAddressNotes
    )

  get("/roadaddress", operation(getRoadAddress)) {
    response.setHeader("Access-Control-Allow-Headers", "*")
    val zoom = chooseDrawType(params.getOrElse("zoom", "5"))
    time(logger, s"GET request for /roadlinks (zoom: $zoom)") {
      params.get("bbox")
        .map(getRoadAddressLinks(zoom))
        .getOrElse(BadRequest("Missing mandatory 'bbox' parameter"))
    }
  }

  private val getNodesAndJunctions: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Seq[Seq[Map[String, Any]]]]("getNodesAndJunctions")
      .parameters(
        queryParam[Int]("zoom").description("Current zoom level of the map"),
        queryParam[String]("bbox").description("String containing the 4 vertexes of a square, is akin to the viewport.\r\n" +
          "Format: Number,Number,Number,Number")
      )
      tags "ViiteAPI - NodesAndJunctions"
      summary "Returns all the road nodes that fit inside the viewport."
      notes getRoadAddressNotes
    )

  get("/nodesjunctions", operation(getNodesAndJunctions)) {
    response.setHeader("Access-Control-Allow-Headers", "*")
    val zoom = chooseDrawType(params.getOrElse("zoom", "5"))
    time(logger, s"GET request for /nodesAndJunctions") {
      params.get("bbox")
        .map(getNodesAndJunctions(zoomLevel = zoom))
        .getOrElse(BadRequest("Missing mandatory 'bbox' parameter"))
    }
  }

  private val getRoadAddressErrors: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[Long, List[Map[String, Long]]]]("getRoadAddressErrors")
      tags "ViiteAPI - RoadAddresses"
      summary "Returns all the road addresses that are in a error state."
      notes "The error states are:" +
      "OverlappingRoadAddresses \n" +
      "InconsistentTopology \n" +
      "InconsistentLrmHistory \n" +
      "Inconsistent2TrackCalibrationPoints \n" +
      "InconsistentContinuityCalibrationPoints \n" +
      "MissingEdgeCalibrationPoints \n" +
      "InconsistentAddressValues"
    )

  get("/roadaddress/errors/", operation(getRoadAddressErrors)) {
    time(logger, "GET request for /roadAddress/errors") {
      response.setHeader("Access-Control-Allow-Headers", "*")
      roadAddressService.getRoadAddressErrors().groupBy(_.ely).map(
        g => g._1 -> g._2.sortBy(ra => (ra.roadNumber, ra.roadPartNumber))
          .map(roadAddressErrorsToApi))
    }
  }

  private val getRoadAddressLinkByLinkId: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getRoadAddressLinkByLinkId")
      .parameters(
        pathParam[Long]("linkId").description("LinkId of a road address")
      )
      tags "ViiteAPI - RoadAddresses"
      summary "Returns the RoadAddressLink object of the given linkId"
      notes ""
    )


  get("/roadaddress/linkid/:linkId", operation(getRoadAddressLinkByLinkId)) {
    val linkId = params("linkId").toLong
    time(logger, s"GET request for /roadAddress/linkid/$linkId") {
      //TODO This process can be improved
      roadAddressService.getRoadAddressLink(linkId)
        .map(midPoint).headOption
        .getOrElse(Map("success" -> false, "reason" -> ("Link " + linkId + " not found")))
    }
  }

  get("/roadlinks/project/prefillfromvvh") {
    val linkId = params("linkId").toLong
    val currentProjectId = params("currentProjectId").toLong
    time(logger, s"GET request for /roadlinks/project/prefillfromvvh (linkId: $linkId, projectId: $currentProjectId)") {
      projectService.fetchPreFillFromVVH(linkId, currentProjectId) match {
        case Right(preFillInfo) =>
          Map("success" -> true, "roadNumber" -> preFillInfo.RoadNumber, "roadPartNumber" -> preFillInfo.RoadPart, "roadName" -> preFillInfo.roadName, "roadNameSource" -> preFillInfo.roadNameSource.value)
        case Left(failureMessage) => Map("success" -> false, "reason" -> failureMessage)
      }
    }
  }

  get("/roadlinks/midpoint/:linkId") {
    val linkId = params("linkId").toLong
    time(logger, s"GET request for /roadlinks/midpoint/$linkId") {
      roadLinkService.getMidPointByLinkId(linkId)
    }
  }

  get("/roadnames") {
    val roadNumber = params.get("roadNumber")
    val roadName = params.get("roadName")
    val startDate = params.get("startDate")
    val endDate = params.get("endDate")
    time(logger, s"GET request for /roadnames (roadNumber: $roadNumber, roadName: $roadName, startDate: $startDate, endDate: $endDate)") {
      roadNameService.getRoadNames(roadNumber, roadName, optionStringToDateTime(startDate), optionStringToDateTime(endDate)) match {
        case Right(roadNameList) => Map("success" -> true, "roadNameInfo" -> roadNameList.map(roadNameToApi))
        case Left(errorMessage) => Map("success" -> false, "reason" -> errorMessage)
      }
    }
  }

  private val saveRoadNamesByRoadNumber: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("saveRoadNamesByRoadNumber")
      .parameters(
        pathParam[Long]("roadNumber").description("Road Number of a road address"),
        bodyParam[Seq[RoadNameRow]]("RoadNameData").description(
          "Road Name data structure: \r\n" +
            roadNameRowStructure
        )
      )
      tags "ViiteAPI - RoadNames"
      summary "Submits one, or many, rows of RoadAddressNames to either be created or updated on the database."
      notes ""
    )

  put("/roadnames/:roadNumber", operation(saveRoadNamesByRoadNumber)) {
    val roadNumber = params("roadNumber").toLong
    time(logger, s"PUT request for /roadnames/$roadNumber") {
      val roadNames = parsedBody.extract[Seq[RoadNameRow]]
      val username = userProvider.getCurrentUser().username
      roadNameService.addOrUpdateRoadNames(roadNumber, roadNames, username) match {
        case Some(err) => Map("success" -> false, "errorMessage" -> err)
        case None => Map("success" -> true)
      }
    }
  }

  get("/roadlinks/checkproject/") {
    val projectId = params("projectId").toLong
    time(logger, s"GET request for /roadlinks/checkproject/ (projectId: $projectId)") {
      projectService.getProjectStatusFromTR(projectId)
    }
  }

  private val getProjectAddressLinksByLinkIds: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String,Any]]("getProjectAddressLinksByLinkIds")
      .parameters(
        pathParam[Long]("linkId").description("LinkId of a road address")
      )
      tags "ViiteAPI - Project"
      summary "Returns a sequence of all ProjectAddressLinks that share the same LinkId."
      notes ""
    )
  get("/project/roadaddress/linkid/:linkId") {
    val linkId = params("linkId").toLong
    time(logger, s"GET request for /project/roadAddress/linkid/$linkId") {
      val projectLinks = projectService.getProjectAddressLinksByLinkIds(Set(linkId))
      foldSegments(projectLinks)
        .map(midPoint)
        .getOrElse(Map("success" -> false, "reason" -> ("Link " + linkId + " not found")))
    }
  }

  private val createRoadAddressProject: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("createRoadAddressProject")
      .parameters(
        bodyParam[RoadAddressProjectExtractor]("RoadAddressProject").description("Full project object to create\r\n" +
          "Object Stucture: \r\n" + roadAddressProjectExtractorStructure + "\r\n\r\n" +
          "Project Status Structure: \r\n" + projectStatusStructure + "\r\n\r\n" +
          "Road Part Extractor Structure: \r\n" +roadPartExtractorStructure)
      )
      tags "ViiteAPI - Project"
      summary "This is responsible of creating a new Road address project."
      notes ""
    )
  post("/roadlinks/roadaddress/project") {
    time(logger, "POST request for /roadlinks/roadaddress/project") {
      val project = parsedBody.extract[RoadAddressProjectExtractor]
      val user = userProvider.getCurrentUser()
      val roadAddressProject = ProjectConverter.toRoadAddressProject(project, user)
      try {
        val projectSaved = projectService.createRoadLinkProject(roadAddressProject)
        val fetched = projectService.getSingleProjectById(projectSaved.id).get
        val latestPublishedNetwork = roadNetworkService.getLatestPublishedNetworkDate
        val firstAddress: Map[String, Any] =
          fetched.reservedParts.find(_.startingLinkId.nonEmpty).map(p => "projectAddresses" -> p.startingLinkId.get).toMap
        Map("project" -> roadAddressProjectToApi(fetched, projectService.getProjectEly(fetched.id)), "publishedNetworkDate" -> formatDateTimeToString(latestPublishedNetwork),
          "reservedInfo" -> fetched.reservedParts.map(projectReservedPartToApi), "formedInfo" -> fetched.formedParts.map(projectFormedPartToApi(Some(fetched.id))),
          "success" -> true) ++ firstAddress
      } catch {
        case _: IllegalArgumentException => BadRequest(s"A project with id ${project.id} has already been created")
        case e: MappingException =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
        case ex: RuntimeException => Map("success" -> false, "errorMessage" -> ex.getMessage)
        case ex: RoadPartReservedException => Map("success" -> false, "errorMessage" -> ex.getMessage)
        case ex: NameExistsException => Map("success" -> false, "errorMessage" -> ex.getMessage)
      }
    }
  }

  private val saveRoadAddressProject: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("saveRoadAddressProject")
      .parameters(
        bodyParam[RoadAddressProjectExtractor]("RoadAddressProject").description("Full project object to save \r\n" +
          "Object Stucture: \r\n" + roadAddressProjectExtractorStructure)
      )
      tags "ViiteAPI - Project"
      summary "This is responsible of saving any changes on a Road address project."
      notes ""
    )

  put("/roadlinks/roadaddress/project", operation(saveRoadAddressProject)) {
    time(logger, "PUT request for /roadlinks/roadaddress/project") {
      val project = parsedBody.extract[RoadAddressProjectExtractor]
      val user = userProvider.getCurrentUser()
      val roadAddressProject = ProjectConverter.toRoadAddressProject(project, user)
      try {
        val projectSaved = projectService.saveProject(roadAddressProject)
        val firstLink = projectService.getFirstProjectLink(projectSaved)
        Map("project" -> roadAddressProjectToApi(projectSaved, projectService.getProjectEly(projectSaved.id)), "projectAddresses" -> firstLink,
          "reservedInfo" -> projectSaved.reservedParts.map(projectReservedPartToApi), "formedInfo" -> projectSaved.formedParts.map(projectFormedPartToApi(Some(projectSaved.id))),
          "success" -> true, "projectErrors" -> projectService.validateProjectById(project.id).map(errorPartsToApi))
      } catch {
        case _: IllegalStateException => Map("success" -> false, "errorMessage" -> "Projekti ei ole enää muokattavissa")
        case _: IllegalArgumentException => NotFound(s"Project id ${project.id} not found")
        case e: MappingException =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
        case ex: RuntimeException => Map("success" -> false, "errorMessage" -> ex.getMessage)
        case ex: RoadPartReservedException => Map("success" -> false, "errorMessage" -> ex.getMessage)
      }
    }
  }

  private val deleteProjectById: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("deleteProjectById")
      .parameters(
        bodyParam[Long]("projectId").description("The id of the project to delete.")
      )
      tags "ViiteAPI - Project"
      summary "This will delete a project and all dependant information, that shares the given Id."
      notes ""
    )

  delete("/roadlinks/roadaddress/project", operation(deleteProjectById)) {
    val projectId = parsedBody.extract[Long]
    time(logger, s"DELETE request for /roadlinks/roadaddress/project (projectId: $projectId)") {
      try {
        if (projectService.deleteProject(projectId)) {
          Map("success" -> true)
        }
        else {
          Map("success" -> false, "errorMessage" -> "Projekti ei ole vielä luotu")
        }
      }
      catch {
        case ex: Exception => Map("success" -> false, "errorMessage" -> ex.getMessage)
      }
    }
  }

  private val sendProjectToTRByProjectId: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("sendProjectToTRByProjectId")
      .parameters(
        bodyParam[Long]("projectID").description("The id of the project to send to TR.")
      )
      tags "ViiteAPI - Project"
      summary "This will send a project and all dependant information, that shares the given ProjectId to TR for further analysis."
      notes "We assume that the project has no validation issues."

    )

  post("/roadlinks/roadaddress/project/sendToTR", operation(sendProjectToTRByProjectId)) {
    val projectID = (parsedBody \ "projectID").extract[Long]
    time(logger, s"POST request for /roadlinks/roadaddress/project/sendToTR (projectID: $projectID)") {
      val writableProjectService = projectService.projectWritableCheck(projectID)
      if (writableProjectService.isEmpty) {
        val sendStatus = projectService.publishProject(projectID)
        if (sendStatus.validationSuccess && sendStatus.sendSuccess)
          Map("sendSuccess" -> true)
        else if (sendStatus.errorMessage.getOrElse("").toLowerCase == FailedToSendToTRMessage.toLowerCase) {
          projectService.setProjectStatus(projectID, SendingToTR)
          Map("sendSuccess" -> false, "errorMessage" -> TrConnectionError)
        } else Map("sendSuccess" -> false, "errorMessage" -> sendStatus.errorMessage.getOrElse(""))
      }
      else {
        Map("sendSuccess" -> false, "errorMessage" -> writableProjectService.get)
      }
    }
  }

  private val changeDirection: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("changeDirection")
      .parameters(
        bodyParam[RevertRoadLinksExtractor]("RevertRoadLinks").description("Object that details what project links should be reversed \r\n" +
          "Object Stucture: \r\n" + revertRoadLinksExtractorStructure)
      )
      tags "ViiteAPI - Project"
      summary "This will send all the data necessary to perform the reversal of project links"
      notes ""
    )

  put("/project/reverse", operation(changeDirection)) {
    time(logger, "PUT request for /project/reverse") {
      val user = userProvider.getCurrentUser()
      try {
        val roadInfo = parsedBody.extract[RevertRoadLinksExtractor]
        projectService.changeDirection(roadInfo.projectId, roadInfo.roadNumber, roadInfo.roadPartNumber, roadInfo.links, roadInfo.coordinates, user.username) match {
          case Some(errorMessage) =>
            Map("success" -> false, "errorMessage" -> errorMessage)
          case None =>
            Map("success" -> true, "projectErrors" -> projectService.validateProjectById(roadInfo.projectId).map(errorPartsToApi))
        }
      } catch {
        case e: IllegalStateException => Map("success" -> false, "errorMessage" -> e.getMessage)
        case ex: RuntimeException => Map("success" -> false, "errorMessage" -> ex.getMessage)
        case e: MappingException =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
      }
    }
  }

  private val getAllRoadAddressProjects: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Seq[Map[String, Any]]]("getAllRoadAddressProjects")
      tags "ViiteAPI - Project"
      summary "Returns all the necessary information on all available projects to be shown on the project selection window."
      notes ""
    )

  get("/roadlinks/roadaddress/project/all", operation(getAllRoadAddressProjects)) {
    time(logger, "GET request for /roadlinks/roadaddress/project/all") {
      val (deletedProjs, currentProjs) = projectService.getAllProjects.map(p => {
        p.id -> (p, projectService.getProjectEly(p.id))
      }).partition(_._2._2.isEmpty)
      deletedProjs.map(p => roadAddressProjectToApi(p._2._1, p._2._2)) ++ currentProjs.sortBy(e => e._2._2.min).map(p => roadAddressProjectToApi(p._2._1, p._2._2))
    }
  }

  private val getSingleProjectById: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getSingleProjectById")
      .parameters(
        pathParam[Long]("id").description("The id of the project to send to TR.")
      )
      tags "ViiteAPI - Project"
      summary "This will retrive all the information of a specific project, identifiable by it's id."
      notes ""
    )

  get("/roadlinks/roadaddress/project/all/projectId/:id", operation(getSingleProjectById)) {
    val projectId = params("id").toLong
    time(logger, s"GET request for /roadlinks/roadaddress/project/all/projectId/$projectId") {
      try {
        projectService.getSingleProjectById(projectId) match {
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
      } catch {
        case e: Exception =>
          logger.error(e.toString, e)
          InternalServerError(e.toString)
      }
    }
  }

  private val checkRoadPartExistsAndReservable: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("checkRoadPartExistsAndReservable")
      .parameters(
        queryParam[Long]("roadNumber").description("Road number of a project Link"),
        queryParam[Long]("startPart").description("Start road part number of a project Link"),
        queryParam[Long]("endPart").description("End road part number of a project Link"),
        queryParam[String]("projDate").description("String representing a project start date")
      )
      tags "ViiteAPI - Project"
      summary "This will retrieve all the information of a specific project, identifiable by it's id."
      notes ""
    )

  get("/roadlinks/roadaddress/project/validatereservedlink/", operation(checkRoadPartExistsAndReservable)) {
    try {
      val roadNumber = params("roadNumber").toLong
      val startPart = params("startPart").toLong
      val endPart = params("endPart").toLong
      val projDate = DateTime.parse(params("projDate"))
      time(logger, s"GET request for /roadlinks/roadaddress/project/validatereservedlink/ (roadNumber: $roadNumber, startPart: $startPart, endPart: $endPart, projDate: $projDate)") {
        projectService.checkRoadPartExistsAndReservable(roadNumber, startPart, endPart, projDate) match {
          case Left(err) => Map("success" -> err)
          case Right((reservedparts, formedparts)) => Map("success" -> "ok", "reservedInfo" -> reservedparts.map(projectReservedPartToApi),
            "formedInfo" -> formedparts.map(projectFormedPartToApi()))
        }
      }
    } catch {
      case e: IllegalArgumentException => Map("success" -> e.getMessage)
    }
  }

  private val revertLinks: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("revertLinks")
      .parameters(
        bodyParam[RevertRoadLinksExtractor]("RevertRoadLinks").description("Object that details what project links should be reverted \r\n" +
          "Object Stucture: \r\n" + revertRoadLinksExtractorStructure)
      )
      tags "ViiteAPI - Project"
      summary "This will return all the supplied project links to their ininital state (LinkStatus.Unhandled in the case of already pre-existing ones and simple removal in the case of new project links)."
      notes ""
    )

  put("/roadlinks/roadaddress/project/revertchangesroadlink", operation(revertLinks)) {
    time(logger, "PUT request for /roadlinks/roadaddress/project/revertchangesroadlink") {
      try {
        val linksToRevert = parsedBody.extract[RevertRoadLinksExtractor]
        if (linksToRevert.links.nonEmpty) {
          val user = userProvider.getCurrentUser().username
          projectService.revertLinks(linksToRevert.projectId, linksToRevert.roadNumber, linksToRevert.roadPartNumber, linksToRevert.links, linksToRevert.coordinates, user) match {
            case None =>
              val projectErrors = projectService.validateProjectById(linksToRevert.projectId).map(errorPartsToApi)
              val project = projectService.getSingleProjectById(linksToRevert.projectId).get
              Map("success" -> true,
                "publishable" -> projectErrors.isEmpty,
                "projectErrors" -> projectErrors,
                "formedInfo" -> project.formedParts.map(projectFormedPartToApi(Some(project.id))))
            case Some(s) => Map("success" -> false, "errorMessage" -> s)
          }
        }
      } catch {
        case e: IllegalStateException => Map("success" -> false, "errorMessage" -> "Projekti ei ole enää muokattavissa")
        case e: MappingException =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
        case e: Exception =>
          logger.error(e.toString, e)
          InternalServerError(e.toString)
      }
    }
  }

  private val createProjectLinks: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("createProjectLinks")
      .parameters(
        bodyParam[RoadAddressProjectLinksExtractor]("RoadAddressProjectLinks").description("Object representing the projectLinks to create \r\n" +
          "Object structure:" + roadAddressProjectLinksExtractorStructure)
      )
      tags "ViiteAPI - Project"
      summary "This will receive all the project link data in order to be created."
      notes ""
    )

  post("/roadlinks/roadaddress/project/links") {
    time(logger, "POST request for /roadlinks/roadaddress/project/links") {
      val user = userProvider.getCurrentUser()
      try {
        val links = parsedBody.extract[RoadAddressProjectLinksExtractor]
        if (links.roadNumber == 0)
          throw RoadAndPartNumberException("Virheellinen tienumero")
        if (links.roadPartNumber == 0)
          throw RoadAndPartNumberException("Virheellinen tieosanumero")
        logger.debug(s"Creating new links: ${links.linkIds.mkString(",")}")
        val response = projectService.createProjectLinks(links.linkIds, links.projectId, links.roadNumber, links.roadPartNumber,
          Track.apply(links.trackCode), Discontinuity.apply(links.discontinuity), RoadType.apply(links.roadType),
          LinkGeomSource.apply(links.roadLinkSource), links.roadEly, user.username, links.roadName.getOrElse(halt(BadRequest("Road name is mandatory"))),
          Some(links.coordinates))
        response.get("success") match {
          case Some(true) =>
            val projectErrors = response.getOrElse("projectErrors", Seq).asInstanceOf[Seq[projectService.projectValidator.ValidationErrorDetails]].map(errorPartsToApi)
            Map("success" -> true,
              "publishable" -> response.get("projectErrors").isEmpty,
              "projectErrors" -> projectErrors)
          case _ => response
        }
      } catch {
        case e: RoadAndPartNumberException => Map("success" -> false, "errorMessage" -> e.getMessage)
        case e: IllegalStateException => Map("success" -> false, "errorMessage" -> "Projekti ei ole enää muokattavissa")
        case e: MappingException =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
        case e: Exception =>
          logger.error(e.toString, e)
          InternalServerError(e.toString)
      }
    }
  }

  private val updateProjectLinks: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("updateProjectLinks")
      .parameters(
        bodyParam[RoadAddressProjectLinksExtractor]("RoadAddressProjectLinks").description("Object representing the projectLinks to create \r\n" +
          "Object structure: \r\n" + roadAddressProjectLinksExtractorStructure)
      )
      tags "ViiteAPI - Project"
      summary "This will receive all the project link data with changes to be commited on the system."
      notes ""
    )

  put("/roadlinks/roadaddress/project/links", operation(updateProjectLinks)) {
    time(logger, "PUT request for /roadlinks/roadaddress/project/links") {
      val user = userProvider.getCurrentUser()
      try {
        val links = parsedBody.extract[RoadAddressProjectLinksExtractor]
        if (links.roadNumber == 0)
          throw RoadAndPartNumberException("Virheellinen tienumero")
        if (links.roadPartNumber == 0)
          throw RoadAndPartNumberException("Virheellinen tieosanumero")
        if (projectService.validateLinkTrack(links.trackCode)) {
          projectService.updateProjectLinks(links.projectId, links.ids, links.linkIds, LinkStatus.apply(links.linkStatus),
            user.username, links.roadNumber, links.roadPartNumber, links.trackCode, links.userDefinedEndAddressM,
            links.roadType, links.discontinuity, Some(links.roadEly), links.reversed.getOrElse(false), roadName = links.roadName,
            Some(links.coordinates)) match {
            case Some(errorMessage) => Map("success" -> false, "errorMessage" -> errorMessage)
            case None =>
              val projectErrors = projectService.validateProjectById(links.projectId).map(errorPartsToApi)
              val project = projectService.getSingleProjectById(links.projectId).get
              Map("success" -> true, "id" -> links.projectId,
                "publishable" -> projectErrors.isEmpty,
                "projectErrors" -> projectErrors,
                "formedInfo" -> project.formedParts.map(projectFormedPartToApi(Some(project.id))))
          }
        } else {
          Map("success" -> false, "errorMessage" -> "Ajoratakoodi puuttuu")
        }
      } catch {
        case e: RoadAndPartNumberException => Map("success" -> false, "errorMessage" -> e.getMessage)
        case _: IllegalStateException => Map("success" -> false, "errorMessage" -> "Projekti ei ole enää muokattavissa")
        case e: MappingException =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
        case e: Exception =>
          logger.error(e.toString, e)
          InternalServerError(e.toString)
      }
    }
  }

  private val getProjectLinksByBoundingBox: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Seq[Seq[Map[String, Any]]]]("getProjectLinksByBoundingBox")
      .parameters(
        queryParam[Int]("zoom").description("Current zoom level of the map"),
        queryParam[Int]("id").description("Id of the current active project"),
        queryParam[String]("bbox").description("String containing the 4 vertexes of a square, is akin to the viewport. \r\n" +
          "Format: Number,Number,Number,Number")
      )
      tags "ViiteAPI - Project"
      summary "Akin to the one used by the road addresses, this one will return all road addresses and project links that are within the viewport defined by the bounding box."
      notes ""
    )

  get("/project/roadlinks", operation(getProjectLinksByBoundingBox)) {
    response.setHeader("Access-Control-Allow-Headers", "*")
    val zoom = chooseDrawType(params.getOrElse("zoom", "5"))
    val id: Long = params.get("id") match {
      case Some(s) if s != "" && s.toLong != 0 => s.toLong
      case _ => 0L
    }
    time(logger, s"GET request for /project/roadlinks (zoom: $zoom, id: $id)") {
      userProvider.getCurrentUser()
      if (id == 0)
        BadRequest("Missing mandatory 'id' parameter")
      else
        params.get("bbox")
          .map(getProjectLinks(id, zoom))
          .getOrElse(BadRequest("Missing mandatory 'bbox' parameter"))
    }
  }

  private val getProjectLinksByProjectId: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getProjectLinksByProjectId")
      .parameters(
        pathParam[Long]("projectId").description("Id of a project")
      )
      tags "ViiteAPI - Project"
      summary "Akin to the one used by the road addresses, this one will return all road addresses and project links of a specific project.."
      notes ""
    )

  get("/project/links/:projectId", operation(getProjectLinksByProjectId)) {
    val id: Long = params.get("projectId") match {
      case Some(s) if s != "" && s.toLong != 0 => s.toLong
      case _ => 0L
    }
    time(logger, s"GET request for /project/links/$id)") {
      if (id == 0)
        BadRequest("Missing mandatory 'projectId' parameter")
      else {
        projectService.getProjectLinks(id)
      }
    }
  }

  private val removeRotatingTRIdByProjectId: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("removeRotatingTRIdByProjectId")
      .parameters(
        pathParam[Long]("projectId").description("Id of a project")
      )
      tags "ViiteAPI - Project"
      summary "This is a part of the re-opening of a project, this one will remove the TRId of a project that has the projectId supplied."
      notes ""
    )

  delete("/project/trid/:projectId", operation(removeRotatingTRIdByProjectId)) {
    val projectId = params("projectId").toLong
    time(logger, s"DELETE request for /project/trid/$projectId") {
      userProvider.getCurrentUser()
      val oError = projectService.removeRotatingTRId(projectId)
      oError match {
        case Some(error) =>
          Map("success" -> "false", "message" -> error)
        case None =>
          Map("success" -> "true", "message" -> "")
      }
    }
  }

  private val validateProjectAndReturnChangeTableById: SwaggerSupportSyntax.OperationBuilder =(
    apiOperation[Map[String, Any]]("validateProjectAndReturnChangeTableById")
      .parameters(
        pathParam[Long]("projectId").description("Id of a project")
      )
      tags "ViiteAPI - Project"
      summary "Given a valid projectId, this will run the validations to the project in question and it will also fetch all the changes made on said project."
      notes ""
    )

  get("/project/getchangetable/:projectId", operation(validateProjectAndReturnChangeTableById)) {
    val projectId = params("projectId").toLong
    time(logger, s"GET request for /project/getchangetable/$projectId") {
      val validationErrors = projectService.validateProjectById(projectId).map(mapValidationIssues)
      //TODO change UI to not override project validator errors on change table call
      val (changeProject, warningMessage) = projectService.getChangeProject(projectId)
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
      Map("changeTable" -> changeTableData, "validationErrors" -> validationErrors, "warningMessage" -> warningMessage)
    }
  }


//  private val splitSuravageLinkByLinkId: SwaggerSupportSyntax.OperationBuilder = (
//    apiOperation[Map[String, Any]]("splitSuravageLinkByLinkId")
//      .parameters(
//        pathParam[Long]("linkID").description("LinkId of a projectLink")
//      )
//      tags "ViiteAPI - Project - SuravageSplit"
//      summary "This effectively perform the split and save the results on the database."
//      notes ""
//    )
//
//  put("/project/split/:linkID", operation(splitSuravageLinkByLinkId)) {
//    val linkID = params.get("linkID")
//    time(logger, s"PUT request for /project/split/$linkID") {
//      val user = userProvider.getCurrentUser()
//      linkID.map(_.toLong) match {
//        case Some(link) =>
//          try {
//            val options = parsedBody.extract[SplitOptions]
//            val splitError = projectService.splitSuravageLink(options.trackCode.value, options.projectId, options.coordinates, link, user.username, options)
//            val projectErrors = projectService.validateProjectById(options.projectId).map(errorPartsToApi)
//            Map("success" -> splitError.isEmpty, "reason" -> splitError.orNull, "projectErrors" -> projectErrors)
//          } catch {
//            case e: IllegalStateException => Map("success" -> false, "errorMessage" -> e.getMessage)
//            case _: NumberFormatException => BadRequest("Missing mandatory data")
//          }
//        case _ => BadRequest("Missing Linkid from url")
//      }
//    }
//  }

  private val getRoadNamesByRoadNumberAndProjectId: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getRoadNamesByRoadNumberAndProjectId")
      .parameters(
        pathParam[Long]("roadNumber").description("Road Number of a project link"),
        pathParam[Long]("projectID").description("Id of a project")
      )
      tags "ViiteAPI - RoadNames"
      summary "Returns all the road names that are related to a certain project (referenced by the projectID) and within a certain roadNumber."
      notes ""
    )

  get("/roadlinks/roadname/:roadNumber/:projectID", operation(getRoadNamesByRoadNumberAndProjectId)) {
    val roadNumber = params.get("roadNumber").map(_.toLong)
    val projectId = params.get("projectID").map(_.toLong)
    time(logger, s"GET request for /roadlinks/roadname/$roadNumber/$projectId") {
      (roadNumber, projectId) match {
        case (Some(rNumber), Some(projectID)) =>
          try {
            roadNameService.getRoadNameByNumber(rNumber, projectID)
          } catch {
            case e: Exception => Map("success" -> false, "errorMessage" -> e.getMessage)
          }
        case _ => BadRequest("Missing road number from URL")
      }
    }
  }

  private val getRoadAddressesByRoadNumberPartNumberAndAddrMValue: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getRoadAddressesByRoadNumberPartNumberAndAddrMValue")
      .parameters(
        queryParam[Long]("road").description("Road Number of a road address"),
        queryParam[Long]("part").description("Road Part Number of a road address"),
        queryParam[Long]("addrMValue").description("Address M Value of a road address")
      )
      tags "ViiteAPI - RoadAddresses"
      summary "Returns all the road names that are identified by the road number, road part number and possibly addressM value."
      notes ""
    )

  get("/roadlinks/roadaddress", operation(getRoadAddressesByRoadNumberPartNumberAndAddrMValue)) {
    val roadNumber = params.get("road").map(_.toLong)
    val roadPartNumber = params.get("part").map(_.toLong)
    val addrMValue = params.get("addrMValue").map(_.toLong)
    time(logger, s"GET request for api/viite/roadlinks/roadaddress/$roadNumber/$roadPartNumber") {
      (roadNumber, roadPartNumber, addrMValue) match {
        case (Some(road), Some(part), None) =>
          roadAddressService.getRoadAddressWithRoadNumberParts(road, Set(part), Set(Track.Combined, Track.LeftSide, Track.RightSide)).sortBy(address => (address.roadPartNumber, address.startAddrMValue))
        case (Some(road), Some(part), Some(addrM)) =>
          roadAddressService.getRoadAddress(road, part, addrM, None).sortBy(address => (address.roadPartNumber, address.startAddrMValue))
        case (Some(road), _, _) =>
          roadAddressService.getRoadAddressWithRoadNumberAddress(road).sortBy(address => (address.roadPartNumber, address.startAddrMValue))
        case _ => BadRequest("Missing road number from URL")
      }
    }
  }

  val getNodesByRoadAttributes = (
    apiOperation[Map[String, Any]]("getNodesByRoadAttributes")
      .parameters(
        queryParam[Long]("roadNumber").description("Road Number of a road address"),
        queryParam[Long]("minRoadPartNumber").description("Road Part Number of a road address"),
        queryParam[Long]("maxRoadPartNumber").description("Road Part Number of a road address")
      )
      tags "ViiteAPI - Nodes"
      summary "Returns all the nodes belonging to the road number and possibly withing the given range of road part numbers."
      notes ""
    )

  get("/nodes", operation(getNodesByRoadAttributes)) {
    val roadNumber = params.get("roadNumber").map(_.toLong)
    val minRoadPartNumber = params.get("minRoadPartNumber").map(_.toLong)
    val maxRoadPartNumber = params.get("maxRoadPartNumber").map(_.toLong)
    time(logger, s"GET request for /nodes (roadNumber: ${roadNumber.get}, startRoadPartNumber: $minRoadPartNumber, endRoadPartNumber: $maxRoadPartNumber") {
      if (roadNumber.isDefined) {
        nodesAndJunctionsService.getNodesByRoadAttributes(roadNumber.get, minRoadPartNumber, maxRoadPartNumber) match {
          case Right(nodes) => Map("success" -> true, "nodes" -> nodes.map(nodeSearchToApi))
          case Left(errorMessage) => Map("success" -> false, "errorMessage" -> errorMessage)
        }
      } else {
        BadRequest("Missing mandatory 'roadNumber' parameter.")
      }
    }
  }

  get("/templates") {
    time(logger, s"GET request for /templates"){
      val authorizedElys = userProvider.getCurrentUser().getAuthorizedElys
      nodesAndJunctionsService.getNodePointTemplates(authorizedElys.toSeq).map(nodePointTemplateToApi) ++
      nodesAndJunctionsService.getJunctionTemplates(authorizedElys.toSeq).map(junctionTemplateToApi)
    }
  }

  private def getRoadAddressLinks(zoomLevel: Int)(bbox: String): Seq[Seq[Map[String, Any]]] = {
    val boundingRectangle = constructBoundingRectangle(bbox)
    val viiteRoadLinks = zoomLevel match {
      case DrawMainRoadPartsOnly =>
        Seq()
      case DrawRoadPartsOnly =>
        Seq()
      case DrawLinearPublicRoads => time(logger, operationName = "DrawLinearPublicRoads") {
        roadAddressService.getRoadAddressesWithLinearGeometry(boundingRectangle, Seq((1, 19999), (40000, 49999)))
      }
      case DrawPublicRoads => time(logger, operationName = "DrawPublicRoads") {
        roadAddressService.getRoadAddressLinksByBoundingBox(boundingRectangle, Seq((1, 19999), (40000, 49999)))
      }
      case DrawAllRoads => time(logger, operationName = "DrawAllRoads") {
        roadAddressService.getRoadAddressLinks(boundingRectangle, roadNumberLimits = Seq(), everything = true)
      }
      case _ => time(logger, operationName = "DrawRoads") {
        roadAddressService.getRoadAddressLinks(boundingRectangle, roadNumberLimits = Seq((1, 19999)))
      }
    }
    time(logger, operationName = "Partition road links") {
      val partitionedRoadLinks = RoadAddressLinkPartitioner.partition(viiteRoadLinks)
      partitionedRoadLinks.map {
        _.map(roadAddressLinkToApi)
      }
    }
  }

  private def getNodesAndJunctions(zoomLevel: Int)(bbox: String): Seq[Map[String, Any]] = {
    val boundingRectangle = constructBoundingRectangle(bbox)
    zoomLevel match {
      case DrawLinearPublicRoads | DrawLinearPublicRoads => time(logger, operationName = "nodes fetch ")
      {
        nodesAndJunctionsService.getNodesByBoundingBox(boundingRectangle).map(simpleNodeToApi)
      }
      case _ => time(logger, operationName = "nodes with junctions fetch") {
        nodesAndJunctionsService.getNodesWithJunctionByBoundingBox(boundingRectangle).toSeq.map(nodeToApi) ++
          nodesAndJunctionsService.getNodeTemplatesByBoundingBox(boundingRectangle).map(nodePointTemplateToApi) ++
          nodesAndJunctionsService.getJunctionTemplatesByBoundingBox(boundingRectangle).map(junctionPointTemplateToApi)
      }
    }
  }

  private def getProjectLinks(projectId: Long, zoomLevel: Int)(bbox: String): Seq[Seq[Map[String, Any]]] = {
    val boundingRectangle = constructBoundingRectangle(bbox)
    val startTime = System.currentTimeMillis()
    val viiteRoadLinks = zoomLevel match {
      case DrawMainRoadPartsOnly =>
        Seq()
      case DrawRoadPartsOnly =>
        Seq()
      case DrawLinearPublicRoads => projectService.getProjectLinksLinear(roadAddressService, projectId, boundingRectangle, Seq((1, 19999), (40000, 49999)), Set())
      case DrawPublicRoads => projectService.getProjectLinksWithoutSuravage(roadAddressService, projectId, boundingRectangle, Seq((1, 19999), (40000, 49999)), Set())
      case DrawAllRoads => projectService.getProjectLinksWithoutSuravage(roadAddressService, projectId, boundingRectangle, Seq(), Set(), everything = true)
      case _ => projectService.getProjectLinksWithoutSuravage(roadAddressService, projectId, boundingRectangle, Seq((1, 19999)), Set())
    }
    logger.info(s"End fetching data for id=$projectId project service (zoom level $zoomLevel) in ${(System.currentTimeMillis() - startTime) * 0.001}s")

    val partitionedRoadLinks = ProjectLinkPartitioner.partition(viiteRoadLinks.filter(_.length >= MinAllowedRoadAddressLength))
    val validRoadNumbers = partitionedRoadLinks.flatten.map(_.roadNumber).filter(value => value > 0).distinct
    if (validRoadNumbers.nonEmpty) {
      val roadNames = roadNameService.getCurrentRoadNames(validRoadNumbers)
      partitionedRoadLinks.map {
        _.map(address => projectAddressLinkToApi(address, roadNames))
      }
    }
    else {
      partitionedRoadLinks.map {
        _.map(address => projectAddressLinkToApi(address))
      }
    }


  }

  private def chooseDrawType(zoomLevel: String) = {
    val C1 = new Contains(-10 to 3)
    val C2 = new Contains(4 to 5)
    val C3 = new Contains(6 to 8)
    val C4 = new Contains(9 to 10)
    val C5 = new Contains(11 to 16)
    try {
      val level: Int = Math.round(zoomLevel.toDouble).toInt
      level match {
        case C1() => DrawMainRoadPartsOnly
        case C2() => DrawRoadPartsOnly
        case C3() => DrawLinearPublicRoads
        case C4() => DrawPublicRoads
        case C5() => DrawAllRoads
        case _ => DrawMainRoadPartsOnly
      }
    } catch {
      case _: NumberFormatException => DrawMainRoadPartsOnly
    }
  }

  private[this] def constructBoundingRectangle(bbox: String) = {
    val BBOXList = bbox.split(",").map(_.toDouble)
    BoundingRectangle(Point(BBOXList(0), BBOXList(1)), Point(BBOXList(2), BBOXList(3)))
  }

  private def mapValidationIssues(issue: projectService.projectValidator.ValidationErrorDetails): Map[String, Any] = {
    Map(
      "id" -> issue.projectId,
      "validationError" -> issue.validationError.value,
      "affectedIds" -> issue.affectedIds.toArray,
      "coordinates" -> issue.coordinates,
      "optionalInformation" -> issue.optionalInformation.getOrElse("")
    )
  }

  private def roadAddressLinkLikeToApi(roadAddressLink: RoadAddressLinkLike): Map[String, Any] = {
    Map(
      "success" -> true,
      "roadwayId" -> roadAddressLink.id,
      "roadwayNumber" -> roadAddressLink.roadwayNumber,
      "linearLocationId" -> roadAddressLink.linearLocationId,
      "linkId" -> roadAddressLink.linkId,
      "mmlId" -> roadAddressLink.attributes.get("MTKID"),
      "points" -> roadAddressLink.geometry,
      "calibrationCode" -> CalibrationCode.getFromAddressLinkLike(roadAddressLink).value,
      "calibrationPoints" -> Seq(calibrationPointToApi(roadAddressLink.geometry, roadAddressLink.startCalibrationPoint),
        calibrationPointToApi(roadAddressLink.geometry, roadAddressLink.endCalibrationPoint)),
      "administrativeClass" -> roadAddressLink.administrativeClass.toString,
      "roadClass" -> RoadClass.get(roadAddressLink.roadNumber.toInt),
      "roadTypeId" -> roadAddressLink.roadType.value,
      "modifiedAt" -> roadAddressLink.modifiedAt,
      "modifiedBy" -> roadAddressLink.modifiedBy,
      "municipalityCode" -> roadAddressLink.attributes.get("MUNICIPALITYCODE"),
      "municipalityName" -> roadAddressLink.municipalityName,
      "roadNameFi" -> roadAddressLink.attributes.get("ROADNAME_FI"),
      "roadNameSe" -> roadAddressLink.attributes.get("ROADNAME_SE"),
      "roadNameSm" -> roadAddressLink.attributes.get("ROADNAME_SM"),
      "roadNumber" -> roadAddressLink.roadNumber,
      "roadPartNumber" -> roadAddressLink.roadPartNumber,
      "elyCode" -> roadAddressLink.elyCode,
      "trackCode" -> roadAddressLink.trackCode,
      "startAddressM" -> roadAddressLink.startAddressM,
      "endAddressM" -> roadAddressLink.endAddressM,
      "discontinuity" -> roadAddressLink.discontinuity,
      "anomaly" -> roadAddressLink.anomaly.value,
      "constructionType" -> roadAddressLink.constructionType.value,
      "startMValue" -> roadAddressLink.startMValue,
      "endMValue" -> roadAddressLink.endMValue,
      "sideCode" -> roadAddressLink.sideCode.value,
      "linkType" -> roadAddressLink.linkType.value,
      "roadLinkSource" -> roadAddressLink.roadLinkSource.value,
      "roadName" -> roadAddressLink.roadName
    )
  }

  def roadAddressErrorsToApi(addressError: AddressErrorDetails): Map[String, Long] = {
    Map(
      "id" -> addressError.linearLocationId,
      "linkId" -> addressError.linkId,
      "roadNumber" -> addressError.roadNumber,
      "roadPartNumber" -> addressError.roadPartNumber,
      "errorCode" -> addressError.addressError.value,
      "ely" -> addressError.ely
    )
  }

  def roadAddressLinkToApi(roadAddressLink: RoadAddressLink): Map[String, Any] = {
    roadAddressLinkLikeToApi(roadAddressLink) ++
      Map(
        "startDate" -> roadAddressLink.startDate,
        "endDate" -> roadAddressLink.endDate,
        "newGeometry" -> roadAddressLink.newGeometry,
        "linearLocationId" -> roadAddressLink.linearLocationId //TODO This needs to be made inside the roadAddressLinkLikeToApi once the project links have the new structure
      )
  }

  def simpleNodeToApi(node: Node): Map[String, Any] = {
    Map("id" -> node.id,
      "nodeNumber" -> node.nodeNumber,
      "name" -> node.name,
      "coordX" -> node.coordinates.x,
      "coordY" -> node.coordinates.y,
      "type" -> node.nodeType.value,
      "createdBy" -> node.createdBy,
      "createdTime" -> node.createdTime
    )
  }

  def nodePointToApi(nodePoint: NodePoint) : Map[String, Any] = {
    //TODO
    Map("id" -> nodePoint.id,
      "nodeId" -> nodePoint.nodeId)
  }

  def nodePointTemplateToApi(nodePoint: NodePoint) : Map[String, Any] = {
    Map("nodePointTemplate" -> {
      Map("id" -> nodePoint.id,
        "nodeId" -> nodePoint.nodeId,
        "beforeAfter" -> nodePoint.beforeAfter.value,
        "roadwayPointId" -> nodePoint.roadwayPointId,
        "startDate" -> formatDateTimeToString(Some(nodePoint.startDate)),
        "endDate" -> formatDateTimeToString(nodePoint.endDate),
        "validFrom" -> formatDateTimeToString(Some(nodePoint.validFrom)),
        "validTo" -> formatDateTimeToString(nodePoint.validTo),
        "createdBy" -> nodePoint.createdBy,
        "roadwayNumber" -> nodePoint.roadwayNumber,
        "addrM" -> nodePoint.addrM,
        "elyCode" -> nodePoint.elyCode,
        "roadNumber" -> nodePoint.roadNumber,
        "roadPartNumber" -> nodePoint.roadPartNumber,
        "track" -> nodePoint.track
      )
    }
    )
  }

  def junctionTemplateToApi(junctionTemplate: JunctionTemplate) : Map[String, Any] = {
    Map("junctionTemplate" -> {
      Map(
        "junctionId" -> junctionTemplate.junctionId,
        "roadNumber" -> junctionTemplate.roadNumber,
        "roadPartNumber" -> junctionTemplate.roadPartNumber,
        "track" -> junctionTemplate.track,
        "addrM" -> junctionTemplate.addrM,
        "elyCode" -> junctionTemplate.elyCode
      )
    }
    )
  }

  def junctionPointTemplateToApi(junctionPoint: JunctionPoint) : Map[String, Any] = {
    Map("junctionPointTemplate" -> {
      Map("id" -> junctionPoint.id,
        "junctionId" -> junctionPoint.junctionId,
        "beforeAfter" -> junctionPoint.beforeAfter.value,
        "roadwayPointId" -> junctionPoint.roadwayPointId,
        "startDate" -> formatDateTimeToString(Some(junctionPoint.startDate)),
        "endDate" -> formatDateTimeToString(junctionPoint.endDate),
        "validFrom" -> formatDateTimeToString(Some(junctionPoint.validFrom)),
        "validTo" -> formatDateTimeToString(junctionPoint.validTo),
        "createdBy" -> junctionPoint.createdBy,
        "roadwayNumber" -> junctionPoint.roadwayNumber,
        "addrM" -> junctionPoint.addrM
      )
    }
    )
  }

  def junctionToApi(junction: (Junction, Seq[JunctionPoint])): Map[String, Any] = {
    Map("id" -> junction._1.id,
      "junctionNumber" -> junction._1.junctionNumber,
      "nodeId" -> junction._1.nodeId,
      "junctionPoints" -> junction._2.map(junctionPointToApi))
  }

  def junctionPointToApi(junctionPoint: JunctionPoint) : Map[String, Any] = {
    Map("id" -> junctionPoint.id,
      "junctionId" -> junctionPoint.junctionId,
      "roadwayNumber" -> junctionPoint.roadwayNumber,
      "addrM" -> junctionPoint.addrM,
      "beforeOrAfter" -> junctionPoint.beforeAfter.value)
  }

  def nodeToApi(node: (Option[Node], (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]]))) : Map[String, Any] = {

    (if(node._1.isDefined)
      simpleNodeToApi(node._1.get)
    else Map()) ++
      Map("nodePoints" -> node._2._1.map(nodePointToApi)) ++ Map("junctions" -> node._2._2.map(junctionToApi))
  }

  def roadNameToApi(roadName: RoadName): Map[String, Any] = {
    Map(
      "id" -> roadName.id,
      "roadNumber" -> roadName.roadNumber,
      "name" -> roadName.roadName,
      "startDate" -> formatDateTimeToString(roadName.startDate),
      "endDate" -> formatDateTimeToString(roadName.endDate)
    )
  }

  def projectAddressLinkToApi(projectAddressLink: ProjectAddressLink, roadNames: Seq[RoadName] = Seq()): Map[String, Any] = {
    roadAddressLinkLikeToApi(projectAddressLink) ++
      (Map(
        "id" -> projectAddressLink.id,
        "status" -> projectAddressLink.status.value,
        "reversed" -> projectAddressLink.reversed,
        "roadNameBlocked" -> (if (projectAddressLink.roadNumber != 0 && projectAddressLink.roadName.nonEmpty) roadNames.exists(_.roadNumber == projectAddressLink.roadNumber) else false)
      )
        ++
        (if (projectAddressLink.isSplit)
          Map(
            "connectedLinkId" -> projectAddressLink.connectedLinkId,
            "originalGeometry" -> projectAddressLink.originalGeometry,
            "middlePoint" -> GeometryUtils.midPointGeometry(projectAddressLink.geometry)
          )
        else
          Map())
        )
  }

  def projectLinkToApi(projectLink: ProjectLink): Map[String, Any] = {
    Map("id" -> projectLink.id,
      "linkId" -> projectLink.linkId,
      "geometry" -> projectLink.geometry,
      "middlePoint" -> GeometryUtils.midPointGeometry(projectLink.geometry),
      "startAddressM" -> projectLink.startAddrMValue,
      "endAddressM" -> projectLink.endAddrMValue,
      "status" -> projectLink.status.value,
      "roadTypeId" -> projectLink.roadType.value,
      "discontinuity" -> projectLink.discontinuity.value,
      "elyCode" -> projectLink.ely,
      "roadName" -> projectLink.roadName)
  }

  def roadAddressProjectToApi(roadAddressProject: Project, elysList: Seq[Long]): Map[String, Any] = {

    val elys = if (elysList.isEmpty) Seq(-1) else elysList

    Map(
      "id" -> roadAddressProject.id,
      "name" -> roadAddressProject.name,
      "createdBy" -> roadAddressProject.createdBy,
      "createdDate" -> formatToString(roadAddressProject.createdDate.toString),
      "dateModified" -> formatToString(roadAddressProject.dateModified.toString),
      "startDate" -> formatToString(roadAddressProject.startDate.toString),
      "modifiedBy" -> roadAddressProject.modifiedBy,
      "additionalInfo" -> roadAddressProject.additionalInfo,
      "status" -> roadAddressProject.status,
      "statusCode" -> roadAddressProject.status.value,
      "statusDescription" -> roadAddressProject.status.description,
      "statusInfo" -> roadAddressProject.statusInfo,
      "elys" -> elys,
      "coordX" -> roadAddressProject.coordinates.get.x,
      "coordY" -> roadAddressProject.coordinates.get.y,
      "zoomLevel" -> roadAddressProject.coordinates.get.zoom
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
          case _ => projectService.getRoadAddressesFromFormedRoadPart(formedRoadPart.roadNumber, formedRoadPart.roadPartNumber, projectId.get)
        }
      }
    )
  }

  def errorPartsToApi(errorParts: projectService.projectValidator.ValidationErrorDetails): Map[String, Any] = {
    Map("ids" -> errorParts.affectedIds,
      "errorCode" -> errorParts.validationError.value,
      "errorMessage" -> errorParts.validationError.message,
      "info" -> errorParts.optionalInformation,
      "coordinates" -> errorParts.coordinates,
      "priority" -> errorParts.validationError.priority
    )
  }

  def splitToApi(splittedLinks: ProjectLink): Map[String, Map[String, Any]] = {
    splittedLinks.status match {
      case LinkStatus.New =>
        Map("b" ->
          Map(
            "linkId" -> splittedLinks.linkId,
            "geometry" -> splittedLinks.geometry,
            "middlePoint" -> GeometryUtils.midPointGeometry(splittedLinks.geometry),
            "startAddressM" -> splittedLinks.startAddrMValue,
            "endAddressM" -> splittedLinks.endAddrMValue,
            "status" -> splittedLinks.status.value,
            "roadTypeId" -> splittedLinks.roadType.value,
            "discontinuity" -> splittedLinks.discontinuity.value,
            "elyCode" -> splittedLinks.ely,
            "roadName" -> splittedLinks.roadName.getOrElse(""),
            "roadLinkSource" -> splittedLinks.linkGeomSource.value
          ))
      case LinkStatus.Terminated =>
        Map("c" ->
          Map(
            "linkId" -> splittedLinks.linkId,
            "geometry" -> splittedLinks.geometry,
            "middlePoint" -> GeometryUtils.midPointGeometry(splittedLinks.geometry),
            "startAddressM" -> splittedLinks.startAddrMValue,
            "endAddressM" -> splittedLinks.endAddrMValue,
            "status" -> splittedLinks.status.value,
            "roadTypeId" -> splittedLinks.roadType.value,
            "discontinuity" -> splittedLinks.discontinuity.value,
            "elyCode" -> splittedLinks.ely,
            "roadName" -> splittedLinks.roadName.getOrElse(""),
            "roadLinkSource" -> splittedLinks.linkGeomSource.value
          ))
      case _ =>
        Map("a" ->
          Map(
            "linkId" -> splittedLinks.linkId,
            "geometry" -> splittedLinks.geometry,
            "middlePoint" -> GeometryUtils.midPointGeometry(splittedLinks.geometry),
            "startAddressM" -> splittedLinks.startAddrMValue,
            "endAddressM" -> splittedLinks.endAddrMValue,
            "status" -> splittedLinks.status.value,
            "roadTypeId" -> splittedLinks.roadType.value,
            "discontinuity" -> splittedLinks.discontinuity.value,
            "elyCode" -> splittedLinks.ely,
            "roadName" -> splittedLinks.roadName.getOrElse(""),
            "roadLinkSource" -> splittedLinks.linkGeomSource.value
          ))
    }
  }

  def nodeSearchToApi(nodeAndRoadAttr: (Node, RoadAttributes)): Map[String, Any] = {
    val (node, roadAttr) = nodeAndRoadAttr
    Map("id" -> node.id,
      "nodeNumber" -> node.nodeNumber,
      "coordX" -> node.coordinates.x,
      "coordY" -> node.coordinates.y,
      "name" -> node.name,
      "type" -> node.nodeType.displayValue,
      "roadNumber" -> roadAttr.roadNumber,
      "track" -> roadAttr.track,
      "roadPartNumber" -> roadAttr.roadPartNumber,
      "addrMValue" -> roadAttr.addrMValue)
  }

  /**
    * For checking date validity we convert string datre to datetime options
    *
    * @param dateString string formated date dd.mm.yyyy
    * @return Joda datetime
    */
  private def optionStringToDateTime(dateString: Option[String]): Option[DateTime] = {
    dateString match {
      case Some(date) => Some(dtf.parseDateTime(date))
      case _ => None
    }
  }

  // Fold segments on same link together
  // TODO: add here start / end dates unique values?
  private def foldSegments[T <: RoadAddressLinkLike](links: Seq[T]): Option[T] = {
    if (links.nonEmpty)
      Some(links.tail.foldLeft(links.head) {
        case (a: RoadAddressLink, b) =>
          a.copy(startAddressM = Math.min(a.startAddressM, b.startAddressM), endAddressM = Math.max(a.endAddressM, b.endAddressM),
            startMValue = Math.min(a.startMValue, b.endMValue)).asInstanceOf[T]
        case (a: ProjectAddressLink, b) =>
          a.copy(startAddressM = Math.min(a.startAddressM, b.startAddressM), endAddressM = Math.max(a.endAddressM, b.endAddressM),
            startMValue = Math.min(a.startMValue, b.endMValue)).asInstanceOf[T]
      })
    else
      None
  }

  private def midPoint(link: RoadAddressLinkLike) = {
    Map("middlePoint" -> GeometryUtils.calculatePointFromLinearReference(link.geometry, link.length / 2.0).getOrElse(Point(link.geometry.head.x, link.geometry.head.y))) ++ (link match {
      case l: RoadAddressLink => roadAddressLinkToApi(l)
      case l: ProjectAddressLink => projectAddressLinkToApi(l)
    })
  }

  def formatToString(entryDate: String): String = {
    val date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(entryDate)
    val formattedDate = new SimpleDateFormat("dd.MM.yyyy").format(date)
    formattedDate
  }

  private def formatDateTimeToString(dateOption: Option[DateTime]): Option[String] =
    dateOption.map { date => date.toString(DateTimeFormat.forPattern("dd.MM.yyyy, HH:mm:ss")) }

  private def calibrationPointToApi(geometry: Seq[Point], calibrationPoint: Option[CalibrationPoint]): Option[Map[String, Any]] = {
    calibrationPoint match {
      case Some(point) =>
        val calculatedPoint = GeometryUtils.calculatePointFromLinearReference(geometry, point.segmentMValue)
        val returningPoint = if (calculatedPoint.isDefined) {
          calculatedPoint
        } else {
          val atBeginning = point.segmentMValue == 0.0
          val (startPoint, endPoint) = GeometryUtils.geometryEndpoints(geometry)
          if (atBeginning) Some(startPoint) else Some(endPoint)
        }
        Option(Seq(("point", returningPoint), ("value", point.addressMValue)).toMap)
      case _ => None
    }
  }

  /* @throws(classOf[Exception])
   // TODO This method was removed previously for some reason. Is this still valid? At least many methods use this.
   private def projectWritable(projectId: Long): ProjectService = {
     val writable = projectService.isWritableState(projectId)
     if (!writable)
       throw new IllegalStateException("Projekti ei ole enää muokattavissa") //project is not in modifiable state
     projectService
   }*/

  case class StartupParameters(lon: Double, lat: Double, zoom: Int, deploy_date: String)
  case class RoadAndPartNumberException(private val message: String = "", private val cause: Throwable = None.orNull) extends Exception(message, cause)

}

case class ProjectFormLine(startingLinkId: Long, projectId: Long, roadNumber: Long, roadPartNumber: Long, roadLength: Long, ely: Long, discontinuity: String, isDirty: Boolean = false)

object ProjectConverter {
  def toRoadAddressProject(project: RoadAddressProjectExtractor, user: User): Project = {
    val formatter = DateTimeFormat.forPattern("dd.MM.yyyy")
    Project(project.id, ProjectState.apply(project.status),
      if (project.name.length > 32) project.name.substring(0, 32).trim else project.name.trim, //TODO the name > 32 should be a handled exception since the user can't insert names with this size
      user.username, DateTime.now(), user.username, formatter.parseDateTime(project.startDate), DateTime.now(),
      project.additionalInfo, project.reservedPartList.distinct.map(toReservedRoadPart), project.formedPartList.distinct.map(toReservedRoadPart), Option(project.additionalInfo))
  }

  def toReservedRoadPart(rp: RoadPartExtractor): ProjectReservedPart = {
    ProjectReservedPart(0L, rp.roadNumber, rp.roadPartNumber,
      None, None, Some(rp.ely),
      None, None, None, None)
  }
}
