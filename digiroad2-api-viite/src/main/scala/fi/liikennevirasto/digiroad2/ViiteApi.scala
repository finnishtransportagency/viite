package fi.liikennevirasto.digiroad2

import java.text.SimpleDateFormat

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.authentication.RequestHeaderAuthentication
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.user.{User, UserProvider}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.{DigiroadSerializers, RoadAddressException, RoadPartReservedException, Track}
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
import org.slf4j.LoggerFactory
import org.scalatra.swagger._

import scala.util.parsing.json._
import scala.util.{Left, Right}

/**
  * Created by venholat on 25.8.2016.
  */

case class NewAddressDataExtracted(sourceIds: Set[Long], targetIds: Set[Long])

case class RevertSplitExtractor(projectId: Option[Long], linkId: Option[Long], coordinates: ProjectCoordinates)

case class RevertRoadLinksExtractor(projectId: Long, roadNumber: Long, roadPartNumber: Long, links: List[LinkToRevert], coordinates: ProjectCoordinates)

case class ProjectRoadAddressInfo(projectId: Long, roadNumber: Long, roadPartNumber: Long)

case class RoadAddressProjectExtractor(id: Long, projectEly: Option[Long], status: Long, name: String, startDate: String,
                                       additionalInfo: String, roadPartList: List[RoadPartExtractor], resolution: Int)

case class RoadAddressProjectLinksExtractor(ids: Set[Long], linkIds: Seq[Long], linkStatus: Int, projectId: Long, roadNumber: Long,
                                            roadPartNumber: Long, trackCode: Int, discontinuity: Int, roadEly: Long,
                                            roadLinkSource: Int, roadType: Int, userDefinedEndAddressM: Option[Int],
                                            coordinates: ProjectCoordinates, roadName: Option[String], reversed: Option[Boolean])

case class RoadPartExtractor(roadNumber: Long, roadPartNumber: Long, ely: Long)

case class CutLineExtractor(linkId: Long, splitedPoint: Point)

class ViiteApi(val roadLinkService: RoadLinkService, val vVHClient: VVHClient,
               val roadAddressService: RoadAddressService,
               val projectService: ProjectService,
               val roadNetworkService: RoadNetworkService,
               val roadNameService: RoadNameService,
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
  private val  dtf: DateTimeFormatter = DateTimeFormat.forPattern("dd/MM/yyyy")
  val DrawMainRoadPartsOnly = 1
  val DrawRoadPartsOnly = 2
  val DrawLinearPublicRoads = 3
  val DrawPublicRoads = 4
  val DrawAllRoads = 5

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  val logger = LoggerFactory.getLogger(getClass)
  protected implicit val jsonFormats: Formats = DigiroadSerializers.jsonFormats
  JSON.globalNumberParser = {
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

  val getStartupParameters =
    (apiOperation[List[Map[String, Any]]]("getStartupParameters")
      tags "User interface"
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

  get("/user") {
    time(logger, "GET request for /user") {
      Map("userName" -> userProvider.getCurrentUser().username, "roles" -> userProvider.getCurrentUser().configuration.roles)
    }
  }

  get("/roadaddress") {
    response.setHeader("Access-Control-Allow-Headers", "*")
    val zoom = chooseDrawType(params.getOrElse("zoom", "5"))
    time(logger, s"GET request for /roadlinks (zoom: $zoom)") {
      params.get("bbox")
        .map(getRoadAddressLinks(zoom))
        .getOrElse(BadRequest("Missing mandatory 'bbox' parameter"))
    }
  }

  get("/roadaddress/floatings") {
    time(logger, "GET request for /roadAddress/floatings") {
      response.setHeader("Access-Control-Allow-Headers", "*")
      roadAddressService.getFloatingAdresses().groupBy(_.ely).map(
        g => g._1 -> g._2.sortBy(ra => (ra.roadNumber, ra.roadPartNumber, ra.startAddrMValue))
          .map(floatingRoadAddressToApi))
    }
  }

  get("/roadaddress/errors") {
    time(logger, "GET request for /roadAddress/errors") {
      response.setHeader("Access-Control-Allow-Headers", "*")
      roadAddressService.getRoadAddressErrors().groupBy(_.ely).map(
        g => g._1 -> g._2.sortBy(ra => (ra.roadNumber, ra.roadPartNumber))
          .map(roadAddressErrorsToApi))
    }
  }

  get("/roadaddress/linkid/:linkId") {
    val linkId = params("linkId").toLong
    time(logger, s"GET request for /roadAddress/linkid/$linkId") {
      //TODO This process can be improved
      roadAddressService.getRoadAddressLink(linkId)
        .map(midPoint).headOption
        .getOrElse(Map("success" -> false, "reason" -> ("Link " + linkId + " not found")))
    }
  }

  get("/roadaddress/:id") {
    val id = params("id").toLong
    time(logger, s"GET request for /roadAddress/$id") {
      //TODO BUG: suravage links should be included here
      val roadLinks = roadAddressService.getRoadAddressLinkById(id)
        foldSegments(roadLinks)
          .map(midPoint)
          .getOrElse(Map("success" -> false, "reason" -> ("ID:" + id + " not found")))
    }

  }

  get("/roadlinks/project/prefillfromvvh") {
    val linkId = params("linkId").toLong
    val currentProjectId = params("currentProjectId").toLong
    time(logger, s"GET request for /roadlinks/project/prefillfromvvh (linkId: $linkId, projectId: $currentProjectId)") {
      projectService.fetchPreFillFromVVH(linkId, currentProjectId) match {
        case Right(preFillInfo) => {
          Map("success" -> true, "roadNumber" -> preFillInfo.RoadNumber, "roadPartNumber" -> preFillInfo.RoadPart, "roadName" -> preFillInfo.roadName, "roadNameSource" -> preFillInfo.roadNameSource.value)
        }
        case Left(failureMessage) => Map("success" -> false, "reason" -> failureMessage)
      }
    }
  }

  get("/roadlinks/adjacent") {
    val data = JSON.parseFull(params.getOrElse("roadData", "{}")).get.asInstanceOf[Map[String, Any]]
    val chainLinkIds = data("selectedLinks").asInstanceOf[Seq[Long]].toSet
    val chainIds = data("selectedIds").asInstanceOf[Seq[Long]].toSet
    val linkId = data("linkId").asInstanceOf[Long]
    val id = data("id").asInstanceOf[Long]
    val roadNumber = data("roadNumber").asInstanceOf[Long]
    val roadPartNumber = data("roadPartNumber").asInstanceOf[Long]
    val trackCode = data("trackCode").asInstanceOf[Long].toInt

    time(logger, s"GET request for /roadlinks/adjacent (chainLinks: $chainLinkIds, linkId: $linkId, roadNumber: $roadNumber, roadPartNumber: $roadPartNumber, trackCode: $trackCode)") {
      roadAddressService.getFloatingAdjacent(chainLinkIds, chainIds, linkId, id, roadNumber, roadPartNumber, trackCode).map(roadAddressLinkToApi)
    }
  }

  get("/roadlinks/midpoint/:linkId") {
    val linkId = params("linkId").toLong
    time(logger, s"GET request for /roadlinks/midpoint/$linkId") {
      roadLinkService.getMidPointByLinkId(linkId)
    }
  }

  get("/roadlinks/adjacent/target") {
    val data = JSON.parseFull(params.getOrElse("roadData", "{}")).get.asInstanceOf[Map[String, Any]]
    val chainLinks = data("selectedLinks").asInstanceOf[Seq[Long]].toSet
    val linkId = data("linkId").asInstanceOf[Long]

    time(logger, s"GET request for /roadlinks/adjacent/target (chainLinks: $chainLinks, linkId: $linkId)") {
      roadAddressService.getAdjacent(chainLinks, linkId).map(roadAddressLinkToApi)
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

  put("/roadnames/:roadNumber") {
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

  get("/roadlinks/adjacent/multiSource") {
    time(logger, "GET request for /roadlinks/adjacent/multiSource") {
      val roadData = JSON.parseFull(params.getOrElse("roadData", "[]")).get.asInstanceOf[Seq[Map[String, Any]]]
      if (roadData.isEmpty) {
        Set.empty
      } else {
        val adjacents: Seq[RoadAddressLink] = {
          roadData.flatMap(rd => {
            val chainLinks = rd("selectedLinks").asInstanceOf[Seq[Long]].toSet
            val chainIds = rd("selectedIds").asInstanceOf[Seq[Long]].toSet
            val linkId = rd("linkId").asInstanceOf[Long]
            val id = rd("id").asInstanceOf[Long]
            val roadNumber = rd("roadNumber").asInstanceOf[Long]
            val roadPartNumber = rd("roadPartNumber").asInstanceOf[Long]
            val trackCode = rd("trackCode").asInstanceOf[Long].toInt
            roadAddressService.getFloatingAdjacent(chainLinks, chainIds, linkId, id,
              roadNumber, roadPartNumber, trackCode)
          })
        }
        val linkIds: Seq[Long] = roadData.map(rd => rd("linkId").asInstanceOf[Long])
        val ids: Seq[Long] = roadData.map(rd => rd("id").asInstanceOf[Long])
        val result = adjacents.filter(adj => {
          if (ids.nonEmpty) {
            !ids.contains(adj.id)
          } else {
            !linkIds.contains(adj.linkId)
          }
        }).distinct
        result.map(roadAddressLinkToApi)
      }
    }
  }

  get("/roadlinks/checkproject/") {
    val projectId = params("projectId").toLong
    time(logger, s"GET request for /roadlinks/checkproject/ (projectId: $projectId)") {
      projectService.getProjectStatusFromTR(projectId)
    }
  }

  get("/roadlinks/transferRoadLink") {
    time(logger, "GET request for /roadlinks/transferRoadLink") {
      val (sources, targets) = roadlinksData()
      val user = userProvider.getCurrentUser()
      try {
        val result = roadAddressService.getRoadAddressLinksAfterCalculation(sources, targets, user)
        result.map(roadAddressLinkToApi)
      }
      catch {
        case e: IllegalArgumentException =>
          logger.warn("Invalid transfer attempted: " + e.getMessage, e)
          BadRequest("Invalid transfer attempted: " + e.getMessage)
        case e: Exception =>
          logger.warn(e.getMessage, e)
          InternalServerError("An unexpected error occurred while processing this action.")
      }
    }
  }

  get("/project/roadaddress/linkid/:linkId") {
    val linkId = params("linkId").toLong
    time(logger, s"GET request for /project/roadAddress/linkid/$linkId") {
      val projectLinks = projectService.getProjectAddressLinksByLinkIds(Set(linkId))
      foldSegments(projectLinks)
        .map(midPoint)
        .getOrElse(Map("success" -> false, "reason" -> ("Link " + linkId + " not found")))
    }
  }

  put("/roadlinks/roadaddress") {
    time(logger, "PUT request for /roadlinks/roadaddress") {
      val data = parsedBody.extract[NewAddressDataExtracted]
      val sourceIds = data.sourceIds
      val targetIds = data.targetIds
      val user = userProvider.getCurrentUser()

      try {
        val roadAddresses = roadAddressService.getRoadAddressesAfterCalculation(sourceIds.toSeq.map(_.toString), targetIds.toSeq.map(_.toString), user)
        roadAddressService.transferFloatingToGap(sourceIds, targetIds, roadAddresses, user.username)
      }
      catch {
        case e: RoadAddressException =>
          logger.warn(e.getMessage)
          InternalServerError("An unexpected error occurred while processing this action.")
        case e: MappingException =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
        case e: Exception =>
          logger.warn("Exception", e)
          BadRequest("An unexpected error occurred while processing this action.")
      }
    }
  }

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
        Map("project" -> roadAddressProjectToApi(fetched), "publishedNetworkDate" -> formatDateTimeToString(latestPublishedNetwork),
          "formInfo" ->
            fetched.reservedParts.map(reservedRoadPartToApi), "success" -> true) ++ firstAddress
      } catch {
        case ex: IllegalArgumentException => BadRequest(s"A project with id ${project.id} has already been created")
        case e: MappingException =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
        case ex: RuntimeException => Map("success" -> false, "errorMessage" -> ex.getMessage)
        case ex: RoadPartReservedException => Map("success" -> false, "errorMessage" -> ex.getMessage)
        case ex: NameExistsException => Map("success" -> false, "errorMessage" -> ex.getMessage)
      }
    }
  }

  put("/roadlinks/roadaddress/project") {
    time(logger, "PUT request for /roadlinks/roadaddress/project") {
      val project = parsedBody.extract[RoadAddressProjectExtractor]
      val user = userProvider.getCurrentUser()
      val roadAddressProject = ProjectConverter.toRoadAddressProject(project, user)
      try {
        val projectSaved = projectService.saveProject(roadAddressProject)
        val firstLink = projectService.getFirstProjectLink(projectSaved)
        Map("project" -> roadAddressProjectToApi(projectSaved), "projectAddresses" -> firstLink, "formInfo" ->
          projectSaved.reservedParts.map(reservedRoadPartToApi),
          "success" -> true, "projectErrors" -> projectService.validateProjectById(project.id).map(errorPartsToApi))
      } catch {
        case e: IllegalStateException => Map("success" -> false, "errorMessage" -> "Projekti ei ole enää muokattavissa")
        case ex: IllegalArgumentException => NotFound(s"Project id ${project.id} not found")
        case e: MappingException =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
        case ex: RuntimeException => Map("success" -> false, "errorMessage" -> ex.getMessage)
        case ex: RoadPartReservedException => Map("success" -> false, "errorMessage" -> ex.getMessage)
      }
    }
  }

  delete("/roadlinks/roadaddress/project") {
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

  post("/roadlinks/roadaddress/project/sendToTR") {
    val projectID = (parsedBody \ "projectID").extract[Long]
    time(logger, s"POST request for /roadlinks/roadaddress/project/sendToTR (projectID: $projectID)") {
      val writableProjectService = projectService.projectWritableCheck(projectID)
      if(writableProjectService.isEmpty){
        val sendStatus = projectService.publishProject(projectID)
        if (sendStatus.validationSuccess && sendStatus.sendSuccess)
          Map("sendSuccess" -> true)
        else if (sendStatus.errorMessage.getOrElse("").toLowerCase == failedToSendToTRMessage.toLowerCase) {
          projectService.setProjectStatus(projectID, SendingToTR)
          Map("sendSuccess" -> false, "errorMessage" -> trConnectionError)
        } else Map("sendSuccess" -> false, "errorMessage" -> sendStatus.errorMessage.getOrElse(""))
      }
      else{
        Map("sendSuccess" -> false, "errorMessage" -> writableProjectService.get)
      }
    }
  }

  put("/project/reverse") {
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

  get("/roadlinks/roadaddress/project/all") {
    time(logger, "GET request for /roadlinks/roadaddress/project/all") {
      projectService.getAllProjects.map(roadAddressProjectToApi)
    }
  }

  get("/roadlinks/roadaddress/project/all/projectId/:id") {
    val projectId = params("id").toLong
    time(logger, s"GET request for /roadlinks/roadaddress/project/all/projectId/$projectId") {
      try {
        projectService.getSingleProjectById(projectId) match {
          case Some(project) =>
            val projectMap = roadAddressProjectToApi(project)
            val parts = project.reservedParts.map(reservedRoadPartToApi)
            val errorParts = projectService.validateProjectById(project.id)
            val publishable = errorParts.isEmpty
            val latestPublishedNetwork = roadNetworkService.getLatestPublishedNetworkDate
            Map("project" -> projectMap, "linkId" -> project.reservedParts.find(_.startingLinkId.nonEmpty).flatMap(_.startingLinkId),
              "projectLinks" -> parts, "publishable" -> publishable, "projectErrors" -> errorParts.map(errorPartsToApi),
              "publishedNetworkDate" -> formatDateTimeToString(latestPublishedNetwork))
          case _ => halt(NotFound("Project not found"))
        }
      } catch {
        case e: Exception => {
          logger.error(e.toString, e)
          InternalServerError(e.toString)
        }
      }
    }
  }

  get("/roadlinks/roadaddress/project/validatereservedlink/") {
    try {
      val roadNumber = params("roadNumber").toLong
      val startPart = params("startPart").toLong
      val endPart = params("endPart").toLong
      val projDate = DateTime.parse(params("projDate"))
      time(logger, s"GET request for /roadlinks/roadaddress/project/validatereservedlink/ (roadNumber: $roadNumber, startPart: $startPart, endPart: $endPart, projDate: $projDate)") {
        projectService.checkRoadPartExistsAndReservable(roadNumber, startPart, endPart, projDate) match {
          case Left(err) => Map("success" -> err)
          case Right(reservedRoadParts) => Map("success" -> "ok", "roadparts" -> reservedRoadParts.map(reservedRoadPartToApi))
        }
      }
    } catch {
      case e: IllegalArgumentException => Map("success" -> e.getMessage)
    }
  }

  put("/roadlinks/roadaddress/project/revertchangesroadlink") {
    time(logger, "PUT request for /roadlinks/roadaddress/project/revertchangesroadlink") {
      try {
        val linksToRevert = parsedBody.extract[RevertRoadLinksExtractor]
        if (linksToRevert.links.nonEmpty) {
          val user = userProvider.getCurrentUser().username
          projectService.revertLinks(linksToRevert.projectId, linksToRevert.roadNumber, linksToRevert.roadPartNumber, linksToRevert.links, linksToRevert.coordinates, user) match {
            case None =>
              val projectErrors = projectService.validateProjectById(linksToRevert.projectId).map(errorPartsToApi)
              Map("success" -> true,
                "publishable" -> projectErrors.isEmpty,
                "projectErrors" -> projectErrors)
            case Some(s) => Map("success" -> false, "errorMessage" -> s)
          }
        }
      } catch {
        case e: IllegalStateException => Map("success" -> false, "errorMessage" -> "Projekti ei ole enää muokattavissa")
        case e: MappingException =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
        case e: Exception => {
          logger.error(e.toString, e)
          InternalServerError(e.toString)
        }
      }
    }
  }

  post("/roadlinks/roadaddress/project/links") {
    time(logger, "POST request for /roadlinks/roadaddress/project/links") {
      val user = userProvider.getCurrentUser()
      try {
        val links = parsedBody.extract[RoadAddressProjectLinksExtractor]
        logger.debug(s"Creating new links: ${links.linkIds.mkString(",")}")
        val response = projectService.createProjectLinks(links.linkIds, links.projectId, links.roadNumber, links.roadPartNumber,
          Track.apply(links.trackCode), Discontinuity.apply(links.discontinuity), RoadType.apply(links.roadType),
          LinkGeomSource.apply(links.roadLinkSource), links.roadEly, user.username, links.roadName.getOrElse(halt(BadRequest("Road name is mandatory"))),
          Some(links.coordinates))
        response.get("success") match {
          case Some(true) => {
            val projectErrors = response.getOrElse("projectErrors", Seq).asInstanceOf[Seq[projectService.projectValidator.ValidationErrorDetails]].map(errorPartsToApi)
            Map("success" -> true,
              "publishable" -> response.get("projectErrors").isEmpty,
              "projectErrors" -> projectErrors)
          }
          case _ => response
        }
      } catch {
        case e: IllegalStateException => Map("success" -> false, "errorMessage" -> "Projekti ei ole enää muokattavissa")
        case e: MappingException =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
        case e: Exception => {
          logger.error(e.toString, e)
          InternalServerError(e.toString)
        }
      }
    }
  }

  put("/roadlinks/roadaddress/project/links") {
    time(logger, "PUT request for /roadlinks/roadaddress/project/links") {
      val user = userProvider.getCurrentUser()
      try {
        val links = parsedBody.extract[RoadAddressProjectLinksExtractor]
        if (projectService.validateLinkTrack(links.trackCode)) {
          projectService.updateProjectLinks(links.projectId, links.ids, links.linkIds, LinkStatus.apply(links.linkStatus),
            user.username, links.roadNumber, links.roadPartNumber, links.trackCode, links.userDefinedEndAddressM,
            links.roadType, links.discontinuity, Some(links.roadEly), links.reversed.getOrElse(false), roadName = links.roadName,
            Some(links.coordinates)) match {
            case Some(errorMessage) => Map("success" -> false, "errorMessage" -> errorMessage)
            case None =>
              val projectErrors = projectService.validateProjectById(links.projectId).map(errorPartsToApi)
              Map("success" -> true, "id" -> links.projectId,
                "publishable" -> projectErrors.isEmpty,
                "projectErrors" -> projectErrors)
          }
        } else {
          Map("success" -> false, "errorMessage" -> "Ajoratakoodi puuttuu")
        }
      } catch {
        case e: IllegalStateException => Map("success" -> false, "errorMessage" -> "Projekti ei ole enää muokattavissa")
        case e: MappingException =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
        case e: Exception => {
          logger.error(e.toString, e)
          InternalServerError(e.toString)
        }
      }
    }
  }

  get("/project/roadlinks") {
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

  get("/project/links/:projectId") {
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

  delete("/project/trid/:projectId") {
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

  get("/project/getchangetable/:projectId") {
    val projectId = params("projectId").toLong
    time(logger, s"GET request for /project/getchangetable/$projectId") {
      val validationErrors = projectService.validateProjectById(projectId).map(mapValidationIssues)
      //TODO change UI to not override project validator errors on change table call
      val changeTableData = projectService.getChangeProject(projectId).map(project =>
        Map(
          "id" -> project.id,
          "ely" -> project.ely,
          "user" -> project.user,
          "name" -> project.name,
          "changeDate" -> project.changeDate,
          "changeInfoSeq" -> project.changeInfoSeq.map(changeInfo =>
            Map("changetype" -> changeInfo.changeType.value, "roadType" -> changeInfo.roadType.value,
              "discontinuity" -> changeInfo.discontinuity.value, "source" -> changeInfo.source,
              "target" -> changeInfo.target, "reversed" -> changeInfo.reversed)))
      ).getOrElse(None)
      Map("changeTable" -> changeTableData, "validationErrors" -> validationErrors)
    }
  }

  post("/project/publish") {
    //TODO VIITE-1551
    /*try {
      val projectId = params("projectId").toLong
      time(logger, s"POST request for /project/publish (projectId: $projectId)") {
        val writableProject = projectWritable(projectId)
        val publishResult = writableProject.publishProject(projectId)
        if (publishResult.sendSuccess && publishResult.validationSuccess)
          Map("status" -> "ok")
        PreconditionFailed(publishResult.errorMessage.getOrElse("Unknown error"))
      }
    } catch {
      case e: IllegalStateException => Map("success" -> false, "errorMessage" -> "Projekti ei ole enää muokattavissa")
      case e: MappingException =>
        logger.warn("Exception treating road links", e)
        BadRequest("Missing mandatory ProjectLink parameter")
    }*/
  }

  post("/project/getCutLine") {
    time(logger, "POST request for /project/getCutLine") {
      try {
        val splitLine = parsedBody.extract[CutLineExtractor]
        if (splitLine.linkId == 0)
          BadRequest("Missing mandatory 'linkId' parameter")
        roadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(Set(Math.abs(splitLine.linkId))).headOption match {
          case Some(suravage) =>
            val splitGeom = GeometryUtils.calculatePointAndHeadingOnGeometry(suravage.geometry, splitLine.splitedPoint)
            splitGeom match {
              case Some(x) => val (p, v) = x
                val cutGeom = Seq(p + v.rotateLeft().scale(3.0), p + v.rotateRight().scale(3.0))
                Map("success" -> true, "response" -> Map("geometry" -> cutGeom))
              case _ => Map("success" -> false, "errorMessage" -> "Error during splitting calculation")
            }
          case _ => Map("success" -> false, "errorMessage" -> ErrorSuravageLinkNotFound)
        }
      } catch {
        case e: SplittingException => Map("success" -> false, "errorMessage" -> e.getMessage)
      }
    }
  }

  put("/project/presplit/:linkID") {
    val linkID = params.get("linkID")
    time(logger, s"PUT request for /project/presplit/$linkID") {
      val user = userProvider.getCurrentUser()
      linkID.map(_.toLong) match {
        case Some(link) =>
          try {
            val options = parsedBody.extract[SplitOptions]
            val (splitLinks, allTerminatedLinks, errorMessage, splitLine) = projectService.preSplitSuravageLink(link, user.username, options)
            val cutGeom = splitLine match {
              case Some(x) => val (p, v) = x
                Seq(p + v.rotateLeft().scale(3.0), p + v.rotateRight().scale(3.0))
              case _ => Seq()
            }
            if (errorMessage.nonEmpty) {
              Map("success" -> false, "errorMessage" -> errorMessage.get)
            } else if (splitLinks.isEmpty) {
              Map("success" -> false, "errorMessage" -> "Linkin jako ei onnistunut tuntemattomasta syystä")
            } else {
              val roadWithInfo = splitLinks.get.filter(_.status == LinkStatus.Terminated).head
              val split: Map[String, Any] = Map(
                "roadNumber" -> roadWithInfo.roadNumber,
                "roadPartNumber" -> roadWithInfo.roadPartNumber,
                "trackCode" -> roadWithInfo.track,
                "terminatedLinks" -> allTerminatedLinks.map(projectLinkToApi),
                "roadLinkSource" -> roadWithInfo.linkGeomSource.value,
                "split" -> Map(
                  "geometry" -> cutGeom
                )
              ) ++ splitLinks.get.flatMap(splitToApi)
              Map("success" -> splitLinks.nonEmpty, "response" -> split)
            }
          } catch {
            case e: IllegalStateException => Map("success" -> false, "errorMessage" -> e.getMessage)
            case e: SplittingException => Map("success" -> false, "errorMessage" -> e.getMessage)
            case _: NumberFormatException => BadRequest("Missing mandatory data")
          }
        case _ => BadRequest("Missing Linkid from url")
      }
    }
  }

  put("/project/split/:linkID") {
    val linkID = params.get("linkID")
    time(logger, s"PUT request for /project/split/$linkID") {
      val user = userProvider.getCurrentUser()
      linkID.map(_.toLong) match {
        case Some(link) =>
          try {
            val options = parsedBody.extract[SplitOptions]
              val splitError = projectService.splitSuravageLink(options.trackCode.value, options.projectId, options.coordinates, link, user.username, options)
              val projectErrors = projectService.validateProjectById(options.projectId).map(errorPartsToApi)
              Map("success" -> splitError.isEmpty, "reason" -> splitError.orNull, "projectErrors" -> projectErrors)
          } catch {
            case e: IllegalStateException => Map("success" -> false, "errorMessage" -> e.getMessage)
            case _: NumberFormatException => BadRequest("Missing mandatory data")
          }
        case _ => BadRequest("Missing Linkid from url")
      }
    }
  }

  get("/roadlinks/roadname/:roadNumber/:projectID") {
    val roadNumber = params.get("roadNumber").map(_.toLong)
    val projectID = params.get("projectID").map(_.toLong)
    time(logger, s"GET request for /roadlinks/roadname/$roadNumber/$projectID") {
      (roadNumber, projectID) match {
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

  get("/roadlinks/roadaddress") {
    val roadNumber = params.get("road").map(_.toLong)
    val roadPartNumber = params.get("part").map(_.toLong)
    val addrMValue = params.get("addrMValue").map(_.toLong)
    time(logger, s"GET request for api/viite/roadlinks/roadaddress/$roadNumber/$roadPartNumber") {
      (roadNumber, roadPartNumber,addrMValue) match {
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

  delete("/project/split") {
    time(logger, "DELETE request for /project/split") {
      val user = userProvider.getCurrentUser()
      try {
        val data = parsedBody.extract[RevertSplitExtractor]
        val projectId = data.projectId
        val linkId = data.linkId
        val coordinates = data.coordinates
        (projectId, linkId) match {
          case (Some(project), Some(link)) =>
            val error = projectService.revertSplit(project, link, user.username)
            projectService.saveProjectCoordinates(project, coordinates)
            Map("success" -> error.isEmpty, "message" -> error)
          case _ => BadRequest("Missing mandatory 'projectId' or 'linkId' parameter from URI: /project/split/:projectId/:linkId")
        }
      } catch {
        case _: NumberFormatException => BadRequest("'projectId' or 'linkId' parameter given could not be parsed as an integer number")
      }
    }
  }

  put("/roadlinks/roadaddress/tofloating/:linkId") {
    time(logger, "PUT request for /roadaddress/tofloating") {
      val linkId = params("linkId").toLong
      try{
        roadAddressService.convertRoadAddressToFloating(linkId)
      }
      catch{
        case _: Exception => BadRequest(s"an error occurred when trying to convert linkId $linkId to floating")
      }
    }
  }

  private def roadlinksData(): (Seq[String], Seq[String]) = {
    val data = JSON.parseFull(params.get("data").get).get.asInstanceOf[Map[String, Any]]
    val sources = data("sourceLinkIds").asInstanceOf[Seq[String]]
    val targets = data("targetLinkIds").asInstanceOf[Seq[String]]
    (sources, targets)
  }

  private def getRoadAddressLinks(zoomLevel: Int)(bbox: String): Seq[Seq[Map[String, Any]]] = {
    val boundingRectangle = constructBoundingRectangle(bbox)
    val viiteRoadLinks = zoomLevel match {
      //TODO: When well-performing solution for main parts and road parts is ready
      case DrawMainRoadPartsOnly =>
        //        roadAddressService.getCoarseRoadParts(boundingRectangle, Seq((1, 99)))
        Seq()
      case DrawRoadPartsOnly =>
        //        roadAddressService.getRoadParts(boundingRectangle, Seq((1, 19999)))
        Seq()
      case DrawLinearPublicRoads => time(logger, "DrawLinearPublicRoads") {
        roadAddressService.getRoadAddressesWithLinearGeometry(boundingRectangle, Seq((1, 19999), (40000, 49999)))
      }
      case DrawPublicRoads => time(logger, "DrawPublicRoads") {
        roadAddressService.getRoadAddressLinksByBoundingBox(boundingRectangle, Seq((1, 19999), (40000, 49999)))
      }
      case DrawAllRoads => time(logger, "DrawAllRoads") {
        roadAddressService.getRoadAddressLinksWithSuravage(boundingRectangle, roadNumberLimits = Seq(), everything = true)
      }
      case _ => time(logger, "DrawRoads") {
        roadAddressService.getRoadAddressLinksWithSuravage(boundingRectangle, roadNumberLimits = Seq((1, 19999)))
      }
    }
    time(logger, "Partition road links") {
      val partitionedRoadLinks = RoadAddressLinkPartitioner.partition(viiteRoadLinks)
      partitionedRoadLinks.map {
        _.map(roadAddressLinkToApi)
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
      case ex: NumberFormatException => DrawMainRoadPartsOnly
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
      "linearLocationId" -> roadAddressLink.linearLocationId,
      "linkId" -> roadAddressLink.linkId,
      "mmlId" -> roadAddressLink.attributes.get("MTKID"),
      "points" -> roadAddressLink.geometry,
      "calibrationCode" -> CalibrationCode.getFromAddressLinkLike(roadAddressLink).value,
      "calibrationPoints" -> Seq(calibrationPoint(roadAddressLink.geometry, roadAddressLink.startCalibrationPoint),
        calibrationPoint(roadAddressLink.geometry, roadAddressLink.endCalibrationPoint)),
      "administrativeClass" -> roadAddressLink.administrativeClass.toString,
      "roadClass" -> RoadClass.get(roadAddressLink.roadNumber.toInt),
      "roadTypeId" -> roadAddressLink.roadType.value,
      "modifiedAt" -> roadAddressLink.modifiedAt,
      "modifiedBy" -> roadAddressLink.modifiedBy,
      "municipalityCode" -> roadAddressLink.attributes.get("MUNICIPALITYCODE"),
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

  def floatingRoadAddressToApi(roadAddress: RoadAddress): Map[String, Any] = {
    Map(
      "id" -> roadAddress.id,
      "linkId" -> roadAddress.linkId,
      "roadNumber" -> roadAddress.roadNumber,
      "roadPartNumber" -> roadAddress.roadPartNumber,
      "trackCode" -> roadAddress.track.value,
      "startAddressM" -> roadAddress.startAddrMValue,
      "endAddressM" -> roadAddress.endAddrMValue,
      "startMValue" -> roadAddress.startMValue,
      "endMValue" -> roadAddress.endMValue,
      "ely" -> roadAddress.ely
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
        "linearLocationId" -> roadAddressLink.linearLocationId, //TODO This needs to be made inside the roadAddressLinkLikeToApi once the project links have the new structure
        "floating" -> roadAddressLink.floatingAsInt
      )
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
      "roadName" -> projectLink.roadName,
      "floating" -> projectLink.floating)
  }

  def roadAddressProjectToApi(roadAddressProject: Project): Map[String, Any] = {
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
      "ely" -> roadAddressProject.ely.getOrElse(-1),
      "coordX" -> roadAddressProject.coordinates.get.x,
      "coordY" -> roadAddressProject.coordinates.get.y,
      "zoomLevel" -> roadAddressProject.coordinates.get.zoom
    )
  }

  def reservedRoadPartToApi(reservedRoadPart: ProjectReservedPart): Map[String, Any] = {
    Map("roadNumber" -> reservedRoadPart.roadNumber,
      "roadPartNumber" -> reservedRoadPart.roadPartNumber,
      "id" -> reservedRoadPart.id,
      "currentEly" -> reservedRoadPart.ely,
      "currentLength" -> reservedRoadPart.addressLength,
      "currentDiscontinuity" -> reservedRoadPart.discontinuity.map(_.description),
      "newEly" -> reservedRoadPart.newEly,
      "newLength" -> reservedRoadPart.newLength,
      "newDiscontinuity" -> reservedRoadPart.newDiscontinuity.map(_.description),
      "startingLinkId" -> reservedRoadPart.startingLinkId,
      "isDirty" -> reservedRoadPart.isDirty
    )
  }

  def errorPartsToApi(errorParts: projectService.projectValidator.ValidationErrorDetails): Map[String, Any] = {
    Map("ids" -> errorParts.affectedIds,
      "errorCode" -> errorParts.validationError.value,
      "errorMessage" -> errorParts.validationError.message,
      "info" -> errorParts.optionalInformation,
      "coordinates" -> errorParts.coordinates
    )
  }

  def splitToApi(splittedLinks: ProjectLink): Map[String, Map[String, Any]] = {
    splittedLinks.status match {
      case LinkStatus.New => {
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
      }
      case LinkStatus.Terminated => {
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
      }
      case _ => {
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
  }

  /**
    * For checking date validity we convert string datre to datetime options
    * @param dateString string formated date dd.mm.yyyy
    * @return  Joda datetime
    */
  private def optionStringToDateTime(dateString:Option[String]): Option[DateTime] = {
    dateString match {
      case Some(dateString) => Some(dtf.parseDateTime(dateString))
      case _=> None
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

  private def formatDateTimeToString(dateOption: Option[DateTime]) : Option[String] =
    dateOption.map { date => date.toString(DateTimeFormat.forPattern("dd.MM.yyyy, HH:mm:ss")) }

  private def calibrationPoint(geometry: Seq[Point], calibrationPoint: Option[CalibrationPoint]) = {
    calibrationPoint match {
      case Some(point) => {
        val calculatedPoint = GeometryUtils.calculatePointFromLinearReference(geometry, point.segmentMValue)
        val returningPoint = if (calculatedPoint.isDefined) {
          calculatedPoint
        } else {
          val atBeginning = point.segmentMValue == 0.0
          val (startPoint, endPoint) = GeometryUtils.geometryEndpoints(geometry)
          if (atBeginning) Some(startPoint) else Some(endPoint)
        }
        Option(Seq(("point", returningPoint), ("value", point.addressMValue)).toMap)
      }
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

}

case class ProjectFormLine(startingLinkId: Long, projectId: Long, roadNumber: Long, roadPartNumber: Long, roadLength: Long, ely: Long, discontinuity: String, isDirty: Boolean = false)

object ProjectConverter {
  def toRoadAddressProject(project: RoadAddressProjectExtractor, user: User): Project = {
    val formatter = DateTimeFormat.forPattern("dd.MM.yyyy")
    Project(project.id, ProjectState.apply(project.status),
      if (project.name.length > 32) project.name.substring(0, 32).trim else project.name.trim, //TODO the name > 32 should be a handled exception since the user can't insert names with this size
      user.username, DateTime.now(), user.username, formatter.parseDateTime(project.startDate), DateTime.now(),
      project.additionalInfo, project.roadPartList.map(toReservedRoadPart), Option(project.additionalInfo), project.projectEly)
  }

  def toReservedRoadPart(rp: RoadPartExtractor): ProjectReservedPart = {
    ProjectReservedPart(0L, rp.roadNumber, rp.roadPartNumber,
      None, None, Some(rp.ely),
      None, None, None, None, false)
  }
}
