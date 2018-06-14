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
import fi.liikennevirasto.viite.ProjectValidator.ValidationErrorDetails
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model._
import fi.liikennevirasto.viite.util.SplitOptions
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.json4s._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{NotFound, _}
import org.slf4j.LoggerFactory

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
                                            coordinates: ProjectCoordinates, roadName: Option[String])

case class RoadPartExtractor(roadNumber: Long, roadPartNumber: Long, ely: Long)

case class CutLineExtractor(linkId: Long, splitedPoint: Point)

class ViiteApi(val roadLinkService: RoadLinkService, val vVHClient: VVHClient,
               val roadAddressService: RoadAddressService,
               val projectService: ProjectService,
               val roadNetworkService: RoadNetworkService,
               val roadNameService: RoadNameService,
               val userProvider: UserProvider = Digiroad2Context.userProvider,
               val deploy_date: String = Digiroad2Context.deploy_date
              )
  extends ScalatraServlet
    with JacksonJsonSupport
    with CorsSupport
    with RequestHeaderAuthentication
    with GZipSupport {

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

  get("/startupParameters") {
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

  get("/roadlinks") {
    response.setHeader("Access-Control-Allow-Headers", "*")
    val zoom = chooseDrawType(params.getOrElse("zoom", "5"))
    time(logger, s"GET request for /roadlinks (zoom: $zoom)") {
      params.get("bbox")
        .map(getRoadAddressLinks(zoom))
        .getOrElse(BadRequest("Missing mandatory 'bbox' parameter"))
    }
  }

  get("/floatingRoadAddresses") {
    time(logger, "GET request for /floatingRoadAddresses") {
      response.setHeader("Access-Control-Allow-Headers", "*")
      roadAddressService.getFloatingAdresses().groupBy(_.ely).map(
        g => g._1 -> g._2.sortBy(ra => (ra.roadNumber, ra.roadPartNumber, ra.startAddrMValue))
          .map(floatingRoadAddressToApi))
    }
  }

  get("/roadAddressErrors") {
    time(logger, "GET request for /roadAddressErrors") {
      response.setHeader("Access-Control-Allow-Headers", "*")
      roadAddressService.getRoadAddressErrors().groupBy(_.ely).map(
        g => g._1 -> g._2.sortBy(ra => (ra.roadNumber, ra.roadPartNumber))
          .map(roadAddressErrorsToApi))
    }
  }

  get("/roadlinks/:linkId") {
    val linkId = params("linkId").toLong
    time(logger, s"GET request for /roadlinks/$linkId") {
      val roadLinks = roadAddressService.getRoadAddressLink(linkId) ++ roadAddressService.getSuravageRoadLinkAddressesByLinkIds(Set(linkId))
      val projectLinks = projectService.getProjectRoadLinksByLinkIds(Set(linkId))
      foldSegments(roadLinks).orElse(foldSegments(projectLinks)).map(midPoint).getOrElse(
        Map("success" -> false, "reason" -> ("Link " + linkId + " not found")))
    }
  }

  get("/roadlinks/project/prefillfromvvh/:linkId") {
    val linkId = params("linkId").toLong
    time(logger, s"GET request for /roadlinks/project/prefillfromvvh/$linkId") {
      projectService.fetchPreFillFromVVH(linkId) match {
        case Right(preFillInfo) => Map("success" -> true, "roadNumber" -> preFillInfo.RoadNumber, "roadPartNumber" -> preFillInfo.RoadPart, "roadName" -> preFillInfo.roadName)
        case Left(failureMessage) => Map("success" -> false, "reason" -> failureMessage)
      }
    }
  }

  get("/roadlinks/adjacent") {
    val data = JSON.parseFull(params.getOrElse("roadData", "{}")).get.asInstanceOf[Map[String, Any]]
    val chainLinks = data("selectedLinks").asInstanceOf[Seq[Long]].toSet
    val linkId = data("linkId").asInstanceOf[Long]
    val roadNumber = data("roadNumber").asInstanceOf[Long]
    val roadPartNumber = data("roadPartNumber").asInstanceOf[Long]
    val trackCode = data("trackCode").asInstanceOf[Long].toInt

    time(logger, s"GET request for /roadlinks/adjacent (chainLinks: $chainLinks, linkId: $linkId, roadNumber: $roadNumber, roadPartNumber: $roadPartNumber, trackCode: $trackCode)") {
      roadAddressService.getFloatingAdjacent(chainLinks, linkId, roadNumber, roadPartNumber, trackCode).map(roadAddressLinkToApi)
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
      val user = userProvider.getCurrentUser()
      roadNameService.addOrUpdateRoadNames(roadNumber, roadNames, user) match {
        case Some(err) => Map("success" -> false, "errorMessage" -> err)
        case None => Map("success" -> true)
      }
    }
  }

  get("/roadlinks/multiSourceAdjacents") {
    time(logger, "GET request for /roadlinks/multiSourceAdjacents") {
      val roadData = JSON.parseFull(params.getOrElse("roadData", "[]")).get.asInstanceOf[Seq[Map[String, Any]]]
      if (roadData.isEmpty) {
        Set.empty
      } else {
        val adjacents: Seq[RoadAddressLink] = {
          roadData.flatMap(rd => {
            val chainLinks = rd("selectedLinks").asInstanceOf[Seq[Long]].toSet
            val linkId = rd("linkId").asInstanceOf[Long]
            val roadNumber = rd("roadNumber").asInstanceOf[Long]
            val roadPartNumber = rd("roadPartNumber").asInstanceOf[Long]
            val trackCode = rd("trackCode").asInstanceOf[Long].toInt
            roadAddressService.getFloatingAdjacent(chainLinks, linkId,
              roadNumber, roadPartNumber, trackCode)
          })
        }
        val linkIds: Seq[Long] = roadData.map(rd => rd("linkId").asInstanceOf[Long])
        val result = adjacents.filter(adj => {
          !linkIds.contains(adj.linkId)
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

  put("/roadlinks/roadaddress") {
    time(logger, "PUT request for /roadlinks/roadaddress") {
      val data = parsedBody.extract[NewAddressDataExtracted]
      val sourceIds = data.sourceIds
      val targetIds = data.targetIds
      val user = userProvider.getCurrentUser()

      val roadAddresses = roadAddressService.getRoadAddressesAfterCalculation(sourceIds.toSeq.map(_.toString), targetIds.toSeq.map(_.toString), user)
      try {
        val transferredRoadAddresses = roadAddressService.transferFloatingToGap(sourceIds, targetIds, roadAddresses, user.username)
        transferredRoadAddresses
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
        if (project.id != 0) //we check if project is new. If it is then we check project for being in writable state
          projectWritable(roadAddressProject.id)
        val projectSaved = projectService.createRoadLinkProject(roadAddressProject)
        projectService.saveProjectCoordinates(projectSaved.id, projectService.calculateProjectCoordinates(projectSaved.id, project.resolution))
        val fetched = projectService.getRoadAddressSingleProject(projectSaved.id).get
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
        if (project.id != 0) { // we check if project is new. If it is then we check project for being in writable state
          projectWritable(roadAddressProject.id)
        }
        val reservationMessage = if (roadAddressProject.reservedParts.nonEmpty) {
          projectService.validateProjectDate(roadAddressProject.reservedParts, roadAddressProject.startDate) match {
            case Some(errMsg) => Some(errMsg)
            case None => None
          }
        } else {
          None
        }
        if (reservationMessage.isEmpty) {
          val projectSaved = projectService.saveProject(roadAddressProject)
          projectService.saveProjectCoordinates(projectSaved.id, projectService.calculateProjectCoordinates(projectSaved.id, project.resolution))
          val firstLink = projectService.getFirstProjectLink(projectSaved)
          Map("project" -> roadAddressProjectToApi(projectSaved), "projectAddresses" -> firstLink, "formInfo" ->
            projectSaved.reservedParts.map(reservedRoadPartToApi),
            "success" -> true, "projectErrors" -> projectService.validateProjectById(project.id).map(errorPartsToApi))
        } else {
          Map("success" -> false, "errorMessage" -> reservationMessage.get)
        }
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
      val writableProjectService = projectWritable(projectID)
      val sendStatus = writableProjectService.publishProject(projectID)
      if (sendStatus.validationSuccess && sendStatus.sendSuccess)
        Map("sendSuccess" -> true)
      else
        Map("sendSuccess" -> false, "errorMessage" -> sendStatus.errorMessage.getOrElse(""))
    }
  }

  put("/project/reverse") {
    time(logger, "PUT request for /project/reverse") {
      val user = userProvider.getCurrentUser()
      try {
        //check for validity
        val roadInfo = parsedBody.extract[RevertRoadLinksExtractor]
        val writableProjectService = projectWritable(roadInfo.projectId)
        writableProjectService.changeDirection(roadInfo.projectId, roadInfo.roadNumber, roadInfo.roadPartNumber, roadInfo.links, user.username) match {
          case Some(errorMessage) =>
            Map("success" -> false, "errorMessage" -> errorMessage)
          case None =>
            writableProjectService.saveProjectCoordinates(roadInfo.projectId, roadInfo.coordinates)
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
      projectService.getRoadAddressAllProjects.map(roadAddressProjectToApi)
    }
  }

  get("/roadlinks/roadaddress/project/all/projectId/:id") {
    val projectId = params("id").toLong
    time(logger, s"GET request for /roadlinks/roadaddress/project/all/projectId/$projectId") {
      try {
        projectService.getRoadAddressSingleProject(projectId) match {
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
    val roadNumber = params("roadNumber").toLong
    val startPart = params("startPart").toLong
    val endPart = params("endPart").toLong
    val projDate = params("projDate").toString
    time(logger, s"GET request for /roadlinks/roadaddress/project/validatereservedlink/ (roadNumber: $roadNumber, startPart: $startPart, endPart: $endPart, projDate: $projDate)") {
      val formatter = DateTimeFormat.forPattern("dd.MM.yyyy")
      val errorMessageOpt = projectService.checkRoadPartsExist(roadNumber, startPart, endPart)
      if (errorMessageOpt.isEmpty) {
        projectService.checkRoadPartsReservable(roadNumber, startPart, endPart) match {
          case Left(err) => Map("success" -> err, "roadparts" -> Seq.empty)
          case Right(reservedRoadParts) => {
            if (reservedRoadParts.isEmpty) {
              Map("success" -> s"Puuttuvan tielinkkidatan takia kyseistä tieosaa ei pystytä varaamaan.")
            } else {
              projectService.validateProjectDate(reservedRoadParts, formatter.parseDateTime(projDate)) match {
                case Some(errMsg) => Map("success" -> errMsg)
                case None => Map("success" -> "ok", "roadparts" -> reservedRoadParts.map(reservedRoadPartToApi))
              }
            }
          }
        }
      } else
        Map("success" -> errorMessageOpt.get)
    }
  }

  put("/roadlinks/roadaddress/project/revertchangesroadlink") {
    time(logger, "PUT request for /roadlinks/roadaddress/project/revertchangesroadlink") {
      try {
        val linksToRevert = parsedBody.extract[RevertRoadLinksExtractor]
        if (linksToRevert.links.nonEmpty) {
          val writableProject = projectWritable(linksToRevert.projectId)
          val user = userProvider.getCurrentUser().username
          writableProject.revertLinks(linksToRevert.projectId, linksToRevert.roadNumber, linksToRevert.roadPartNumber, linksToRevert.links, user) match {
            case None =>
              writableProject.saveProjectCoordinates(linksToRevert.projectId, linksToRevert.coordinates)
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
        val writableProject = projectWritable(links.projectId)
        val response = writableProject.createProjectLinks(links.linkIds, links.projectId, links.roadNumber, links.roadPartNumber,
          Track.apply(links.trackCode), Discontinuity.apply(links.discontinuity), RoadType.apply(links.roadType), LinkGeomSource.apply(links.roadLinkSource), links.roadEly, user.username, links.roadName.getOrElse(halt(BadRequest("Road name is mandatory"))))
        response.get("success") match {
          case Some(true) => {
            writableProject.saveProjectCoordinates(links.projectId, links.coordinates)
            val projectErrors = response.getOrElse("projectErrors", Seq).asInstanceOf[Seq[ValidationErrorDetails]].map(errorPartsToApi)
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
          val writableProject = projectWritable(links.projectId)
          writableProject.updateProjectLinks(links.projectId, links.ids, links.linkIds, LinkStatus.apply(links.linkStatus),
            user.username, links.roadNumber, links.roadPartNumber, links.trackCode, links.userDefinedEndAddressM,
            links.roadType, links.discontinuity, Some(links.roadEly), roadName = links.roadName) match {
            case Some(errorMessage) => Map("success" -> false, "errorMessage" -> errorMessage)
            case None =>
              writableProject.saveProjectCoordinates(links.projectId, links.coordinates)
              val projectErrors = projectService.validateProjectById(links.projectId).map(errorPartsToApi)
              Map("success" -> true, "id" -> links.projectId,
                "publishable" -> projectErrors.isEmpty,
                "projectErrors" -> projectErrors)
          }
        } else {
          Map("success" -> false, "errorMessage" -> "Invalid track code")
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
    try {
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
    }
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
            val writableProject = projectWritable(options.projectId)
            val (splitLinks, allTerminatedLinks, errorMessage, splitLine) = writableProject.preSplitSuravageLink(link, user.username, options)
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
            if (projectService.validateLinkTrack(options.trackCode.value)) {
              val writableProject = projectWritable(options.projectId)
              val splitError = writableProject.splitSuravageLink(link, user.username, options)
              writableProject.saveProjectCoordinates(options.projectId, options.coordinates)
              val projectErrors = projectService.validateProjectById(options.projectId).map(errorPartsToApi)
              Map("success" -> splitError.isEmpty, "reason" -> splitError.orNull, "projectErrors" -> projectErrors)
            } else {
              Map("success" -> false, "errorMessage" -> "Invalid track code")
            }
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
        case (Some(rNumber), Some(projectID)) => {
          try {
            roadNameService.getRoadNameByNumber(rNumber, projectID)
          } catch {
            case e: Exception => Map("success" -> false, "errorMessage" -> e.getMessage)
          }
        }
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
        roadAddressService.getRoadAddressLinksByLinkId(boundingRectangle, Seq((1, 19999), (40000, 49999)))
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
      case DrawPublicRoads => projectService.getProjectLinksWithSuravage(roadAddressService, projectId, boundingRectangle, Seq((1, 19999), (40000, 49999)), Set())
      case DrawAllRoads => projectService.getProjectLinksWithSuravage(roadAddressService, projectId, boundingRectangle, Seq(), Set(), everything = true)
      case _ => projectService.getProjectLinksWithSuravage(roadAddressService, projectId, boundingRectangle, Seq((1, 19999)), Set())
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

  private def mapValidationIssues(issue: ProjectValidator.ValidationErrorDetails): Map[String, Any] = {
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
      "segmentId" -> roadAddressLink.id,
      "id" -> roadAddressLink.id,
      "linkId" -> roadAddressLink.linkId,
      "mmlId" -> roadAddressLink.attributes.get("MTKID"),
      "points" -> roadAddressLink.geometry,
      "calibrationCode" -> CalibrationCode.getFromAddressLinkLike(roadAddressLink).value,
      "calibrationPoints" -> Seq(calibrationPoint(roadAddressLink.geometry, roadAddressLink.startCalibrationPoint),
        calibrationPoint(roadAddressLink.geometry, roadAddressLink.endCalibrationPoint)),
      "administrativeClass" -> roadAddressLink.administrativeClass.toString,
      "roadClass" -> roadAddressService.roadClass(roadAddressLink.roadNumber),
      "roadTypeId" -> roadAddressLink.roadType.value,
      "roadType" -> roadAddressLink.roadType.displayValue,
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
      "roadLinkType" -> roadAddressLink.roadLinkType.value,
      "constructionType" -> roadAddressLink.constructionType.value,
      "startMValue" -> roadAddressLink.startMValue,
      "endMValue" -> roadAddressLink.endMValue,
      "sideCode" -> roadAddressLink.sideCode.value,
      "linkType" -> roadAddressLink.linkType.value,
      "roadLinkSource" -> roadAddressLink.roadLinkSource.value,
      "blackUnderline" -> roadAddressLink.blackUnderline,
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
      "id" -> addressError.id,
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
        "newGeometry" -> roadAddressLink.newGeometry
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
      (if (projectAddressLink.isSplit)
        Map(
          "status" -> projectAddressLink.status.value,
          "connectedLinkId" -> projectAddressLink.connectedLinkId,
          "originalGeometry" -> projectAddressLink.originalGeometry,
          "reversed" -> projectAddressLink.reversed,
          "middlePoint" -> GeometryUtils.midPointGeometry(projectAddressLink.geometry),
          "roadNameBlocked" -> (if (projectAddressLink.roadNumber != 0 && projectAddressLink.roadName.nonEmpty) roadNames.exists(_.roadNumber == projectAddressLink.roadNumber) else false)
        )
      else
        Map(
          "status" -> projectAddressLink.status.value,
          "reversed" -> projectAddressLink.reversed,
          "roadNameBlocked" -> (if (projectAddressLink.roadNumber != 0 && projectAddressLink.roadName.nonEmpty) roadNames.exists(_.roadNumber == projectAddressLink.roadNumber) else false)
        ))
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

  def roadAddressProjectToApi(roadAddressProject: RoadAddressProject): Map[String, Any] = {
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

  def reservedRoadPartToApi(reservedRoadPart: ReservedRoadPart): Map[String, Any] = {
    Map("roadNumber" -> reservedRoadPart.roadNumber,
      "roadPartNumber" -> reservedRoadPart.roadPartNumber,
      "id" -> reservedRoadPart.id,
      "currentEly" -> reservedRoadPart.ely,
      "currentLength" -> reservedRoadPart.addressLength,
      "currentDiscontinuity" -> reservedRoadPart.discontinuity.map(_.description),
      "newEly" -> reservedRoadPart.newEly,
      "newLength" -> reservedRoadPart.newLength,
      "newDiscontinuity" -> reservedRoadPart.newDiscontinuity.map(_.description),
      "linkId" -> reservedRoadPart.startingLinkId,
      "isDirty" -> reservedRoadPart.isDirty
    )
  }

  def errorPartsToApi(errorParts: ValidationErrorDetails): Map[String, Any] = {
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
            "roadName" -> splittedLinks.roadName.getOrElse("")
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
            "roadName" -> splittedLinks.roadName.getOrElse("")
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
            "roadName" -> splittedLinks.roadName.getOrElse("")
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
    Map("middlePoint" -> GeometryUtils.calculatePointFromLinearReference(link.geometry,
      link.length / 2.0)) ++ (link match {
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
      case Some(point) =>
        Option(Seq(("point", GeometryUtils.calculatePointFromLinearReference(geometry, point.segmentMValue)), ("value", point.addressMValue)).toMap)
      case _ => None
    }
  }

  @throws(classOf[Exception])
  private def projectWritable(projectId: Long): ProjectService = {
    val writable = projectService.isWritableState(projectId)
    if (!writable)
      throw new IllegalStateException("Projekti ei ole enää muokattavissa") //project is not in modifiable state
    projectService
  }

  case class StartupParameters(lon: Double, lat: Double, zoom: Int, deploy_date: String)

}

case class ProjectFormLine(startingLinkId: Long, projectId: Long, roadNumber: Long, roadPartNumber: Long, roadLength: Long, ely: Long, discontinuity: String, isDirty: Boolean = false)

object ProjectConverter {
  def toRoadAddressProject(project: RoadAddressProjectExtractor, user: User): RoadAddressProject = {
    val formatter = DateTimeFormat.forPattern("dd.MM.yyyy")
    RoadAddressProject(project.id, ProjectState.apply(project.status),
      if (project.name.length > 32) project.name.substring(0, 32).trim else project.name.trim,
      user.username, DateTime.now(), user.username, formatter.parseDateTime(project.startDate), DateTime.now(),
      project.additionalInfo, project.roadPartList.map(toReservedRoadPart), Option(project.additionalInfo), project.projectEly,
      Some(ProjectCoordinates(DefaultLatitude, DefaultLongitude, DefaultZoomLevel)))
  }

  def toReservedRoadPart(rp: RoadPartExtractor): ReservedRoadPart = {
    ReservedRoadPart(0L, rp.roadNumber, rp.roadPartNumber,
      None, None, Some(rp.ely),
      None, None, None, None, false)
  }
}
