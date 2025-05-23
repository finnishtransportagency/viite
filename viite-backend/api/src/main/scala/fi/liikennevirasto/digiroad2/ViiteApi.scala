package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.authentication.JWTAuthentication
import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.user.{User, UserProvider}
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, RoadPartReservedException}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.Digiroad2Context.{dynamicRoadNetworkService, projectLinkDAO}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model._
import fi.liikennevirasto.viite.util.DigiroadSerializers
import fi.vaylavirasto.viite.dao.{RoadName, RoadNameForRoadAddressBrowser}
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, BeforeAfter, Discontinuity, LinkGeomSource, NodePointType, NodeType, RoadAddressChangeType, RoadPart, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import fi.vaylavirasto.viite.util.DateTimeFormatters.{ISOdateFormatter, dateSlashFormatter, finnishDateCommaTimeFormatter, finnishDateFormatter}
import fi.vaylavirasto.viite.util.DateUtils.parseStringToDateTime
import org.joda.time.DateTime
import org.json4s._
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger._
import org.slf4j.{Logger, LoggerFactory}

import java.text.SimpleDateFormat
import scala.concurrent.Future
import scala.util.{Left, Right}
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by venholat on 25.8.2016.
  */

case class RevertRoadLinksExtractor(projectId: Long, /*roadPart: RoadPart,*/roadNumber: Long, roadPartNumber: Long, links: List[LinkToRevert], coordinates: ProjectCoordinates)

case class RoadAddressProjectExtractor(id: Long, projectEly: Option[Long], status: Long, name: String, startDate: String,
                                       additionalInfo: String, reservedPartList: List[RoadPartElyExtractor], formedPartList: List[RoadPartElyExtractor], resolution: Int)

case class RoadAddressProjectLinksExtractor(ids: Set[Long], linkIds: Seq[String], roadAddressChangeType: Int, projectId: Long,
                                            /*roadPart: RoadPart,*/ roadNumber: Long, roadPartNumber: Long, trackCode: Int, discontinuity: Int, roadEly: Long, roadLinkSource: Int, administrativeClass: Int,
                                            userDefinedEndAddressM: Option[Int], coordinates: ProjectCoordinates, roadName: Option[String], reversed: Option[Boolean], devToolData: Option[ProjectLinkDevToolData])

case class RoadPartElyExtractor(roadNumber: Long, roadPartNumber: Long, ely: Long)

case class NodePointExtractor(id: Long, beforeAfter: Int, roadwayPointId: Long, nodeNumber: Option[Long], `type`: Int = NodePointType.UnknownNodePointType.value,
                              startDate: Option[String], endDate: Option[String], validFrom: String, validTo: Option[String],
                              createdBy: String, createdTime: Option[String], roadwayNumber: Long, addrM : Long,
                              /*roadPart: RoadPart,*/roadNumber: Long, roadPartNumber: Long, track: Int, elyCode: Long)

case class JunctionExtractor(id: Long, junctionNumber: Option[Long], nodeNumber: Option[Long],
                             junctionPoints: List[JunctionPointExtractor], startDate: String, endDate: Option[String],
                             validFrom: Option[String], validTo: Option[String], createdBy: Option[String], createdTime: Option[String])

case class JunctionPointExtractor(id: Long, beforeAfter: Long, junctionId: Long, nodeNumber: Option[Long], validFrom: Option[String],
                                  validTo: Option[String], createdBy: Option[String], createdTime: Option[String],
                                  roadwayNumber: Long, roadwayPointId: Long, addrM: Long, /*roadPart: RoadPart,*/roadNumber: Long, roadPartNumber: Long, track: Track)

case class NodeExtractor(id: Long = NewIdValue, nodeNumber: Long = NewIdValue, coordinates: Point, name: Option[String], `type`: Int, startDate: String, endDate: Option[String], validFrom: Option[String], validTo: Option[String],
                         createdTime: Option[String], editor: Option[String] = None, publishedTime: Option[DateTime] = None, registrationDate: Option[String] = None,
                         junctions: List[JunctionExtractor], nodePoints: List[NodePointExtractor])

class ViiteApi(val roadLinkService: RoadLinkService,           val KGVClient: KgvRoadLink,
               val roadAddressService: RoadAddressService,     val projectService: ProjectService,
               val roadNameService: RoadNameService,           val nodesAndJunctionsService: NodesAndJunctionsService,
               val roadNetworkValidator: RoadNetworkValidator, val userProvider: UserProvider = Digiroad2Context.userProvider,
               val deploy_date: String = Digiroad2Context.deploy_date,
               implicit val swagger: Swagger)
  extends ScalatraServlet
    with JacksonJsonSupport
    with CorsSupport
    with JWTAuthentication
    with SwaggerSupport {

  protected val applicationDescription = "The user interface API "

  class Contains(r: Range) {
    def unapply(i: Int): Boolean = r contains i
  }

  /*  Roads */
  val DrawMainRoadPartsOnly = 1
  val DrawRoadPartsOnly = 2
  val DrawLinearPublicRoads = 3
  val DrawPublicRoads = 4
  val DrawAllRoads = 5

  /*  Nodes */
  val DrawNone = 0
  val DrawNodes = 1
  val DrawAll = 2

  val logger: Logger = LoggerFactory.getLogger(getClass)
  protected implicit val jsonFormats: Formats = DigiroadSerializers.jsonFormats

  before() {
    contentType = formats("json") + "; charset=utf-8"
    try {
      authenticateForApi(request)(userProvider)
      if (request.isWrite && !userProvider.getCurrentUser.hasViiteWriteAccess) {
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
      summary "Show all startup parameters")
  get("/startupParameters", operation(getStartupParameters)) {
    time(logger, "GET request for /startupParameters") {
      val (east, north, zoom, roles) = {
        val config = userProvider.getCurrentUser.configuration
        (config.east.map(_.toDouble), config.north.map(_.toDouble), config.zoom.map(_.toInt), config.roles)
      }
      StartupParameters(east.getOrElse(DefaultLatitude), north.getOrElse(DefaultLongitude), zoom.getOrElse(DefaultZoomLevel), deploy_date, roles)
    }
  }

  private val getUser: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[Map[String,Any]]("getUser")
      tags "ViiteAPI - General"
      summary "Shows the current user's username and it's roles."
      )
  get("/user", operation(getUser)) {
    time(logger, "GET request for /user") {
      Map("userName" -> userProvider.getCurrentUser.username, "roles" -> userProvider.getCurrentUser.configuration.roles)
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
    )
  get("/roadaddress", operation(getRoadAddress)) {
    response.setHeader("Access-Control-Allow-Headers", "*")
    val zoom = chooseDrawType(params.getOrElse("zoom", "5"))
    time(logger, s"GET request for /roadlinks (zoom: $zoom)") {
      params.get("bbox").map(b => getRoadAddressLinks(zoom)(b)._1).getOrElse(BadRequest("Missing mandatory 'bbox' parameter"))
    }
  }

  private val getRoadLinksOfWholeRoadPart: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Seq[Seq[Map[String, Any]]]]("getRoadAddress")
      .parameters(
        queryParam[Long]("roadnumber").description("Road number of the road address"),
        queryParam[Long]("roadpart").description("Road part number of the road address")
      )
      tags "ViiteAPI - RoadAddresses"
      summary "Returns all the road addresses of the road part"
    )
  get("/roadlinks/wholeroadpart/", operation(getRoadLinksOfWholeRoadPart)) {
    response.setHeader("Access-Control-Allow-Headers", "*")
      val roadNumber: Long = params.get("roadnumber") match {
        case Some(s) if s != "" && s.toLong != 0 => s.toLong
        case _ => 0L
      }
      val roadPartNumber: Long = params.get("roadpart") match {
        case Some(s) if s != "" && s.toLong != 0 => s.toLong
        case _ => 0L
      }

    time(logger, s"GET request for /roadlinks (roadnumber: $roadNumber) (roadpart: $roadPartNumber)") {
      if (roadNumber == 0){
        BadRequest("Missing mandatory 'roadnumber' parameter")
      }
      else if (roadPartNumber == 0) {
        BadRequest("Missing mandatory 'roadpart' parameter")
      }
      else {
        getRoadAddressLinksByRoadPartNumber(RoadPart(roadNumber,roadPartNumber))
      }
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
    )
  get("/nodesjunctions", operation(getNodesAndJunctions)) {
    response.setHeader("Access-Control-Allow-Headers", "*")
    val zoomLinks = chooseDrawType(params.getOrElse("zoom", "5"))
    val zoomNodes = chooseNodesDrawType(params.getOrElse("zoom", "1"))
    time(logger, s"GET request for /nodesAndJunctions") {
      val bbox = params.get("bbox")
      val map: Option[(Seq[Seq[Map[String, Any]]], Seq[RoadAddressLink])] = bbox.map(b => getRoadAddressLinks(zoomLinks)(b))
      val nodesJunctions = bbox.map(getNodesAndJunctions(zoomLevel = zoomNodes, map.get._2)).getOrElse(BadRequest("Missing mandatory 'bbox' parameter"))
      Map("fetchedNodes" -> nodesJunctions, "fetchedRoadLinks" -> map.get._1)
    }
  }

  private val getRoadAddressLinkByLinkId: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getRoadAddressLinkByLinkId")
      .parameters(
        pathParam[String]("linkId").description("LinkId of a road address")
      )
      tags "ViiteAPI - RoadAddresses"
      summary "Returns the RoadAddressLink object of the given linkId"
    )
  get("/roadaddress/linkid/:linkId", operation(getRoadAddressLinkByLinkId)) {
    val linkId = params("linkId")
    time(logger, s"GET request for /roadAddress/linkid/$linkId") {
      //TODO This process can be improved
      roadAddressService.getRoadAddressLink(linkId)
        .map(midPoint).headOption
        .getOrElse(Map("success" -> false, "reason" -> ("Link " + linkId + " not found")))
    }
  }

  private val fetchPreFill: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("fetchPreFillData")
      .parameters(
        queryParam[String]("linkId").description("LinkId of a road address"),
        queryParam[Long]("currentProjectId").description("currentProjectId")
      )
      tags "ViiteAPI - Project"
      summary "Fetch prefill information like roadNumber, roadPartNumber, roadName, roadNameSource"
    )
  get("/roadlinks/project/prefill", operation(fetchPreFill)) {
    val linkId = params("linkId")
    val currentProjectId = params("currentProjectId").toLong
    time(logger, s"GET request for /roadlinks/project/prefill (linkId: $linkId, projectId: $currentProjectId)") {
      projectService.fetchPreFillData(linkId, currentProjectId) match {
        case Right(preFillInfo) =>
          Map("success" -> true, "roadNumber" -> preFillInfo.RoadNumber, "roadPartNumber" -> preFillInfo.PartNumber, "roadName" -> preFillInfo.roadName, "roadNameSource" -> preFillInfo.roadNameSource.value, "ely" -> preFillInfo.ely)
        case Left(failureMessage) => Map("success" -> false, "reason" -> failureMessage)
      }
    }
  }

  private val getMidPointByLinkId: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getMidPointByLinkId")
      .parameters(
        pathParam[String]("linkId").description("LinkId of a road address")
      )
      tags "ViiteAPI - RoadAddresses"
      summary "getMidPointByLinkId"
    )
  get("/roadlinks/midpoint/:linkId", operation(getMidPointByLinkId)) {
    val linkId: String = params("linkId")
    time(logger, s"GET request for /roadlinks/midpoint/$linkId") {
      roadLinkService.getMidPointByLinkId(linkId)
    }
  }

  private val getRoadLinkMiddlePointByMtkId: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getRoadLinkMiddlePointByMtkId")
      .parameters(
        pathParam[Long]("mtkId").description("mtkId of a road address")
      )
      tags "ViiteAPI - RoadAddresses"
      summary "getRoadLinkMiddlePointByMtkId"
    )
  get("/roadlinks/mtkid/:mtkId", operation(getRoadLinkMiddlePointByMtkId)) {
    val mtkId: Long = params("mtkId").toLong
    time(logger, s"GET request for /roadlinks/mtkid/$mtkId") {
      roadLinkService.getRoadLinkMiddlePointBySourceId(mtkId)
    }
  }

  private val getRoadNames: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getRoadNames")
      .parameters(
        queryParam[Long]("roadNumber").description("Road Number of a road address"),
        queryParam[Long]("roadName").description("Road Name of a road address"),
        queryParam[String]("startDate").description("startDate"),
        queryParam[String]("endDate").description("endDate")
      )
      tags "ViiteAPI - RoadAddresses"
      summary "Searches road names by road number, road name and between history"
    )
  get("/roadnames", operation(getRoadNames)) {
    val roadNumber = params.get("roadNumber")
    val roadName = params.get("roadName")
    val startDate = params.get("startDate")
    val endDate = params.get("endDate")
    time(logger, s"GET request for /roadnames", params=Some(params.toMap)) {
      roadNameService.getRoadNames(roadNumber, roadName, optionStringToDateTime(startDate), optionStringToDateTime(endDate)) match {
        case Right(roadNameList) => Map("success" -> true, "roadNameInfo" -> roadNameList.map(roadNameToApi))
        case Left(errorMessage) => Map("success" -> false, "reason" -> errorMessage)
      }
    }
  }

  private val getJunctionPointsByJunctionIds: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getJunctionPointsByJunctionIds")
      .parameters(
        pathParam[Long]("id").description("Id of junction")
      )
      tags "ViiteAPI - NodesAndJunctions"
      summary "Get junctionpoints by ids of junctions"
    )
  get("/junctions/:id/junction-points", operation(getJunctionPointsByJunctionIds)) {
    val junctionId = params("id").toLong
    val x: Seq[Long] = Seq(junctionId)
    time(logger, s"GET request for /junctions/$junctionId/junction-points") {
      nodesAndJunctionsService.getJunctionPointsByJunctionIds(x).map(junctionPointsToApi)
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
      tags "ViiteAPI - RoadAddresses"
      summary "Submits one, or many, rows of RoadAddressNames to either be created or updated on the database."
    )
  put("/roadnames/:roadNumber", operation(saveRoadNamesByRoadNumber)) {
    val roadNumber = params("roadNumber").toLong
    time(logger, s"PUT request for /roadnames/$roadNumber") {
      val roadNames = parsedBody.extract[Seq[RoadNameRow]]
      val username = userProvider.getCurrentUser.username
      roadNameService.addOrUpdateRoadNames(roadNumber, roadNames, username) match {
        case Some(err) => Map("success" -> false, "errorMessage" -> err)
        case None => Map("success" -> true)
      }
    }
  }

  private val getRoadNetworkErrors: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("roadnetworkerrors")
      tags "ViiteAPI - RoadNetworkErrors"
      summary "Runs road network integrity checks, returning all the found errors, e.g. missing or extra points (roadway p., calibration p.), or roadways' integrity errors"
    )
  get("/roadnetworkerrors", operation(getRoadNetworkErrors)) {
    time(logger, s"GET request for /roadnetworkerrors") {
      try {
        val missingCalibrationPointsFromTheStart = roadNetworkValidator.getMissingCalibrationPointsFromTheStart
        val missingCalibrationPointsFromTheEnd = roadNetworkValidator.getMissingCalibrationPointsFromTheEnd
        val missingCalibrationPointsFromJunctions = roadNetworkValidator.getMissingCalibrationPointsFromJunctions
        val linksWithExtraCalibrationPoints = roadNetworkValidator.getLinksWithExtraCalibrationPoints
        val linksWithExtraCalibrationPointsOnSameRoadway = roadNetworkValidator.getLinksWithExtraCalibrationPointsOnSameRoadway
        val missingRoadwayPointsFromTheStart = roadNetworkValidator.getMissingRoadwayPointsFromTheStart
        val missingRoadwayPointsFromTheEnd = roadNetworkValidator.getMissingRoadwayPointsFromTheEnd
        val invalidRoadwayLengths = roadNetworkValidator.getInvalidRoadwayLengths
        val overlappingRoadways = roadNetworkValidator.getOverlappingRoadwaysInHistory
        val overlappingRoadwaysOnLinearLocations = roadNetworkValidator.getOverlappingRoadwaysOnLinearLocations

        Map("success" -> true,
          "missingCalibrationPointsFromStart" -> missingCalibrationPointsFromTheStart.map(cp => missingCalibrationPointToApi(cp)),
          "missingCalibrationPointsFromEnd" -> missingCalibrationPointsFromTheEnd.map(cp => missingCalibrationPointToApi(cp)),
          "missingCalibrationPointsFromJunctions" -> missingCalibrationPointsFromJunctions.map(cp => missingCalibrationPointFromJunctionToApi(cp)),
          "linksWithExtraCalibrationPoints" -> linksWithExtraCalibrationPoints.map(l => linksWithExtraCalibrationPointsToApi(l)),
          "linksWithExtraCalibrationPointsOnSameRoadway" -> linksWithExtraCalibrationPointsOnSameRoadway.map(l => linksWithExtraCalibrationPointsOnSameRoadwayToApi(l)),
          "missingRoadwayPointsFromStart" -> missingRoadwayPointsFromTheStart.map(rwp => missingRoadwayPointToApi(rwp)),
          "missingRoadwayPointsFromEnd" -> missingRoadwayPointsFromTheEnd.map(rwp => missingRoadwayPointToApi(rwp)),
          "invalidRoadwayLengths" -> invalidRoadwayLengths.map(rw => invalidRoadwayLengthToApi(rw)),
          "overlappingRoadways" -> overlappingRoadways.map(rw => roadwayToApi(rw)),
          "overlappingRoadwaysOnLinearLocations" -> overlappingRoadwaysOnLinearLocations.map(rw => overlappingRoadwayOnLinearLocationToApi(rw)))

      } catch {
        case e: Throwable =>
          logger.error(s"Fetching road network errors failed. $e")
          Map("success" -> false, "error" -> "Tieverkon virheiden haku epäonnistui, ota yhteys Viite tukeen.")
      }
    }
  }

  def missingCalibrationPointToApi(cp: MissingCalibrationPoint): Map[String, Any] = {
    Map(
      "roadNumber" -> cp.roadPart.roadNumber,
      "roadPartNumber" -> cp.roadPart.partNumber,
      "track" -> cp.track,
      "addrM" -> cp.addrM,
      "createdTime" -> cp.createdTime,
      "createdBy" -> cp.createdBy
    )
  }

  def missingCalibrationPointFromJunctionToApi(cp: MissingCalibrationPointFromJunction): Map[String, Any] = {
    Map(
      "roadNumber" -> cp.missingCalibrationPoint.roadPart.roadNumber,
      "roadPartNumber" -> cp.missingCalibrationPoint.roadPart.partNumber,
      "track" -> cp.missingCalibrationPoint.track,
      "addrM" -> cp.missingCalibrationPoint.addrM,
      "createdTime" -> cp.missingCalibrationPoint.createdTime,
      "createdBy" -> cp.missingCalibrationPoint.createdBy,
      "junctionPointId" -> cp.junctionPointId,
      "junctionNumber"-> cp.junctionNumber,
      "nodeNumber" -> cp.nodeNumber,
      "beforeAfter" -> cp.beforeAfter
    )
  }

  def linksWithExtraCalibrationPointsToApi(link: LinksWithExtraCalibrationPoints): Map[String, Any] = {
    Map(
      "linkId" -> link.linkId,
      "roadNumber" -> link.roadPart.roadNumber,
      "roadPartNumber" -> link.roadPart.partNumber,
      "startEnd" -> link.startEnd,
      "calibrationPointCount" -> link.calibrationPointCount,
      "calibrationPoints" -> link.calibrationPointIds
    )
  }

  def linksWithExtraCalibrationPointsOnSameRoadwayToApi(link: LinksWithExtraCalibrationPoints): Map[String, Any] = {
    Map(
      "linkId" -> link.linkId,
      "roadNumber" -> link.roadPart.roadNumber,
      "roadPartNumber" -> link.roadPart.partNumber,
      "startEnd" -> link.startEnd,
      "calibrationPointCount" -> link.calibrationPointCount,
      "calibrationPoints" -> link.calibrationPointIds
    )
  }

  def missingRoadwayPointToApi(rwp: MissingRoadwayPoint): Map[String, Any] = {
    Map(
      "roadNumber" -> rwp.roadPart.roadNumber,
      "roadPartNumber" -> rwp.roadPart.partNumber,
      "track" -> rwp.track,
      "addrM" -> rwp.addrM,
      "createdTime" -> rwp.createdTime,
      "createdBy" -> rwp.createdBy
    )
  }

  def invalidRoadwayLengthToApi(rw: InvalidRoadwayLength): Map[String, Any] = {
    Map(
      "roadwayNumber" -> rw.roadwayNumber,
      "startDate" -> rw.startDate,
      "endDate" -> rw.endDate,
      "roadNumber" -> rw.roadPart.roadNumber,
      "roadPartNumber" -> rw.roadPart.partNumber,
      "track" -> rw.track,
      "addrMRange" -> rw.addrMRange,
      "length" -> rw.length,
      "createdBy" -> rw.createdBy,
      "createdTime" -> rw.createdTime
    )
  }

  def roadwayToApi(rw: Roadway): Map[String, Any] = {
    Map(
      "roadwayId" -> rw.id,
      "roadwayNumber" -> rw.roadwayNumber,
      "roadNumber" -> rw.roadPart.roadNumber,
      "roadPartNumber" -> rw.roadPart.partNumber,
      "track" -> rw.track,
      "addrMRange" -> rw.addrMRange,
      "reversed" -> rw.reversed,
      "discontinuity" -> rw.discontinuity,
      "startDate" -> rw.startDate,
      "endDate" -> rw.endDate,
      "createdBy" -> rw.createdBy,
      "administrativeClass" -> rw.administrativeClass,
      "ely" -> rw.ely,
      "terminated" -> rw.terminated,
      "validFrom" -> rw.validFrom,
      "validTo" -> rw.validTo
    )
  }

  def overlappingRoadwayOnLinearLocationToApi(rw: OverlappingRoadwayOnLinearLocation): Map[String, Any] = {
    Map(
      "roadwayId" -> rw.roadway.id,
      "roadwayNumber" -> rw.roadway.roadwayNumber,
      "roadNumber" -> rw.roadway.roadPart.roadNumber,
      "roadPartNumber" -> rw.roadway.roadPart.partNumber,
      "track" -> rw.roadway.track,
      "addrMRange" -> rw.roadway.addrMRange,
      "reversed" -> rw.roadway.reversed,
      "discontinuity" -> rw.roadway.discontinuity,
      "startDate" -> rw.roadway.startDate,
      "endDate" -> rw.roadway.endDate,
      "createdBy" -> rw.roadway.createdBy,
      "administrativeClass" -> rw.roadway.administrativeClass,
      "ely" -> rw.roadway.ely,
      "terminated" -> rw.roadway.terminated,
      "validFrom" -> rw.roadway.validFrom,
      "validTo" -> rw.roadway.validTo,
      "linearLocationId" -> rw.linearLocationId,
      "linkId" -> rw.linkId,
      "linearLocationRoadwayNumber" -> rw.linearLocationRoadwayNumber,
      "linearLocationStartMeasure" -> rw.linearLocationStartMeasure,
      "linearLocationEndMeasure" -> rw.linearLocationEndMeasure,
      "linearLocationCreatedTime" -> rw.linearLocationCreatedTime,
      "linearLocationCreatedBy" -> rw.linearLocationCreatedBy
    )
  }

  private val getDataForRoadAddressBrowser: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String,Any]]("getDataForRoadAddressBrowser").parameters(
      queryParam[String]("situationDate").description("Situation date (yyyy-MM-dd)"),
      queryParam[String]("target").description("What data to fetch (Tracks, RoadParts, Nodes, Junctions, RoadNames)"),
      queryParam[Long]("ely").description("Ely number of a road address").optional,
      queryParam[Long]("roadNumber").description("Road Number of a road address").optional,
      queryParam[Long]("minRoadPartNumber").description("Min Road Part Number of a road address").optional,
      queryParam[Long]("maxRoadPartNumber").description("Max Road Part Number of a road address").optional
    )
      tags "ViiteAPI - Road Address Browser"
      summary "Returns data for road address browser based on the search criteria"
    )
  get("/roadaddressbrowser", operation(getDataForRoadAddressBrowser)) {
    time(logger, s"GET request for /roadaddressbrowser", params=Some(params.toMap)) {
      def validateInputs(situationDate: Option[String], target: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Boolean = {
        def parseDate(dateString: Option[String]): Option[DateTime] = {
          try {
            if (dateString.isDefined) {
              Some(ISOdateFormatter.parseDateTime(dateString.get))
            } else
              None
          } catch {
            case _: IllegalArgumentException => None
          }
        }

        val mandatoryInputsDefinedAndValid =
          parseDate(situationDate).isDefined && // situationDate is mandatory for all targets
          target.isDefined && // target is always mandatory
            ((ely.isDefined && ely.get > 0L && ely.get <= 14L) || (roadNumber.isDefined && roadNumber.get > 0L && roadNumber.get <= 99999L)) || //either ely OR road number is required
            target.get == "RoadNames" || //  unless target is RoadNames
            target.get == "Nodes" || // unless target is Nodes
            target.get == "Junctions" // unless target is Junctions
        val optionalRoadPartInputsValid = (minRoadPartNumber, maxRoadPartNumber) match {
          case (Some(minPart), Some(maxPart)) => minPart >= 1 && minPart <= 999 && maxPart >= 1 && maxPart <= 999 && minPart <= maxPart
          case (Some(minPart), None) => minPart >= 1 && minPart <= 999
          case (None, Some(maxPart)) => maxPart >= 1 && maxPart <= 999
          case (None, None) => true
        }

        mandatoryInputsDefinedAndValid && optionalRoadPartInputsValid
      }

      val situationDate = params.get("situationDate")
      val target = params.get("target")
      val ely = params.get("ely").map(_.toLong)
      val roadNumber = params.get("roadNumber").map(_.toLong)
      val minRoadPartNumber = params.get("minRoadPartNumber").map(_.toLong)
      val maxRoadPartNumber = params.get("maxRoadPartNumber").map(_.toLong)

      try {
        if (validateInputs(situationDate, target, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)) {
          target match {
            case Some("Tracks") =>
              val tracksForRoadAddressBrowser = roadAddressService.getTracksForRoadAddressBrowser(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)
              Map("success" -> true, "results" -> tracksForRoadAddressBrowser.map(roadAddressBrowserTracksToApi))
            case Some("RoadParts") =>
              val roadPartsForRoadAddressBrowser = roadAddressService.getRoadPartsForRoadAddressBrowser(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)
              Map("success" -> true, "results" -> roadPartsForRoadAddressBrowser.map(roadAddressBrowserRoadPartsToApi))
            case Some("Nodes") =>
              val nodesForRoadAddressBrowser = nodesAndJunctionsService.getNodesForRoadAddressBrowser(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)
              Map("success" -> true, "results" -> nodesForRoadAddressBrowser.map(roadAddressBrowserNodesToApi))
            case Some("Junctions") =>
              val junctionsForRoadAddressBrowser = nodesAndJunctionsService.getJunctionsForRoadAddressBrowser(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)
              Map("success" -> true, "results" -> junctionsForRoadAddressBrowser.map(roadAddressBrowserJunctionsToApi))
            case Some("RoadNames") =>
              val roadNamesForRoadAddressBrowser = roadNameService.getRoadNamesForRoadAddressBrowser(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)
              Map("success" -> true, "results" -> roadNamesForRoadAddressBrowser.map(roadAddressBrowserRoadNamesToApi))
            case _ => Map("success" -> false, "error" -> "Tieosoitteiden haku epäonnistui, haun kohdearvo puuttuu tai on väärin syötetty")
          }
        } else
          Map("success" -> false, "error" -> "Tieosotteiden haku epäonnistui, tarkista syöttämäsi tiedot")

      } catch {
        case e: Throwable =>
          logger.error(s"Error fetching data for road address browser $e")
          Map("success" -> false, "error" -> "Tieosoitteiden haku epäonnistui, ota yhteys Viite tukeen")
      }
    }
  }

  private val getDataForRoadAddressChangesBrowser: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String,Any]]("getDataForRoadAddressChangesBrowser").parameters(
      queryParam[String]("startDate").description("Start date (yyyy-MM-dd)"),
      queryParam[String]("endDate").description("End date (yyyy-MM-dd)"),
      queryParam[String]("dateTarget").description("What start and end dates are used for"),
      queryParam[Long]("ely").description("Ely number of a road address").optional,
      queryParam[Long]("roadNumber").description("Road Number of a road address").optional,
      queryParam[Long]("minRoadPartNumber").description("Min Road Part Number of a road address").optional,
      queryParam[Long]("maxRoadPartNumber").description("Max Road Part Number of a road address").optional
    )
      tags "ViiteAPI - Road Address Changes Browser"
      summary "Returns change info for road address changes browser based on the search criteria"
    )
  get("/roadaddresschangesbrowser", operation(getDataForRoadAddressChangesBrowser)) {
    time(logger, s"GET request for /roadaddresschangesbrowser") {
      def validateInputs(startDate: Option[String], endDate: Option[String], dateTarget: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Boolean = {
        def parseDate(dateString: Option[String]): Option[DateTime] = {
          try {
            if (dateString.isDefined) {
              Some(ISOdateFormatter.parseDateTime(dateString.get))
            } else
              None
          } catch {
            case _: IllegalArgumentException => None
          }
        }

        def roadPartInputsValid(minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]) = {
          (minRoadPartNumber, maxRoadPartNumber) match {
            case (Some(minPart), Some(maxPart)) => minPart >= 1 && minPart <= 999 && maxPart >= 1 && maxPart <= 999 && minPart <= maxPart
            case (Some(minPart), None) => minPart >= 1 && minPart <= 999
            case (None, Some(maxPart)) => maxPart >= 1 && maxPart <= 999
            case (None, None) => true
          }
        }

        val mandatoryInputsDefinedAndValid = parseDate(startDate).isDefined &&  dateTarget.isDefined
        val optionalInputsValid: Boolean =  {
          val endDateValid = {
            if (endDate.isDefined)
              parseDate(endDate).isDefined
            else
              true
          }
          val elyValid = {
            if (ely.isDefined)
              ely.get > 0L && ely.get <= 14L
            else
              true
          }
          val roadNumberValid = {
            if (roadNumber.isDefined)
              roadNumber.get > 0L && roadNumber.get <= 99999L
            else
              true
          }
          endDateValid && elyValid && roadNumberValid && roadPartInputsValid(minRoadPartNumber, maxRoadPartNumber)
        }

        mandatoryInputsDefinedAndValid && optionalInputsValid
      }

      val startDate = params.get("startDate")
      val endDate = params.get("endDate")
      val dateTarget = params.get("dateTarget")
      val ely = params.get("ely").map(_.toLong)
      val roadNumber = params.get("roadNumber").map(_.toLong)
      val minRoadPartNumber = params.get("minRoadPartNumber").map(_.toLong)
      val maxRoadPartNumber = params.get("maxRoadPartNumber").map(_.toLong)

      try {
        if (validateInputs(startDate, endDate, dateTarget, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)) {
          val changeInfosForRoadAddressChangesBrowser = roadAddressService.getChangeInfosForRoadAddressChangesBrowser(startDate, endDate, dateTarget, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)
          Map("success" -> true, "changeInfos" -> changeInfosForRoadAddressChangesBrowser.map(roadAddressChangeInfoToApi))
        } else
          Map("success" -> false, "error" -> "Tieosoitemuutosten haku epäonnistui, tarkista syöttämäsi tiedot")

      } catch {
        case e: Throwable =>
          logger.error(s"Error fetching data for road address changes browser $e")
          Map("success" -> false, "error" -> "Tieosoitemuutosten haku epäonnistui, ota yhteys Viite tukeen")
      }
    }
  }

  private val getProjectAddressLinksByLinkIds: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String,Any]]("getProjectAddressLinksByLinkIds")
      .parameters(
        pathParam[String]("linkId").description("LinkId of a road address")
      )
      tags "ViiteAPI - Project"
      summary "Returns a sequence of all ProjectAddressLinks that share the same LinkId."
    )
  get("/project/roadaddress/linkid/:linkId", operation(getProjectAddressLinksByLinkIds)) {
    val linkId = params("linkId")
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
    )
  post("/roadlinks/roadaddress/project",operation(createRoadAddressProject)) {
    time(logger, "POST request for /roadlinks/roadaddress/project") {
      val project = parsedBody.extract[RoadAddressProjectExtractor]
      val user = userProvider.getCurrentUser
      val roadAddressProject = ProjectConverter.toRoadAddressProject(project, user)
      try {
        val projectSaved = projectService.createRoadLinkProject(roadAddressProject)
        val fetched = projectService.getSingleProjectById(projectSaved.id).get
        val firstAddress: Map[String, Any] =
          fetched.reservedParts.find(_.startingLinkId.nonEmpty).map(p => "projectAddresses" -> p.startingLinkId.get).toMap
        Map("project" -> roadAddressProjectToApi(fetched, projectService.getProjectEly(fetched.id)),
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
    )
  put("/roadlinks/roadaddress/project", operation(saveRoadAddressProject)) {
    time(logger, "PUT request for /roadlinks/roadaddress/project") {
      val project = parsedBody.extract[RoadAddressProjectExtractor]
      val user = userProvider.getCurrentUser
      val roadAddressProject = ProjectConverter.toRoadAddressProject(project, user)
      try {
        val projectSaved = projectService.saveProject(roadAddressProject)
        val firstLink = projectService.getFirstProjectLink(projectSaved)
        Map("project" -> roadAddressProjectToApi(projectSaved, projectService.getProjectEly(projectSaved.id)), "projectAddresses" -> firstLink,
          "reservedInfo" -> projectSaved.reservedParts.map(projectReservedPartToApi),
          "formedInfo" -> projectSaved.formedParts.map(projectFormedPartToApi(Some(projectSaved.id))),
          "success" -> true,
          "projectErrors" -> projectService.validateProjectById(project.id).map(projectService.projectValidator.errorPartsToApi))
      } catch {
        case _: IllegalStateException       => Map("success" -> false, "errorMessage" -> "Projekti ei ole enää muokattavissa")
        case _: IllegalArgumentException    => NotFound(s"Project id ${project.id} not found")
        case e: MappingException            =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
        case ex: RuntimeException           => Map("success" -> false, "errorMessage" -> ex.getMessage)
        case ex: RoadPartReservedException  => Map("success" -> false, "errorMessage" -> ex.getMessage)
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

  private val sendProjectChangesToViiteByProjectId: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("sendProjectToTRByProjectId")
      .parameters(
        bodyParam[Long]("projectID").description("The id of the project whose changes are to be accepted to the road network.")
      )
      tags "ViiteAPI - Project"
      summary "This will send a project and all dependant information, that shares the given ProjectId to Viite for further analysis, and for saving to Road Network. We assume that the project has no validation issues."
    )
  post("/roadlinks/roadaddress/project/sendProjectChangesToViite", operation(sendProjectChangesToViiteByProjectId)) {
    val projectID = (parsedBody \ "projectID").extract[Long]
    time(logger, s"POST request for /roadlinks/roadaddress/project/sendProjectChangesToViite (projectID: $projectID)") {
      val projectWritableError = projectService.projectWritableCheck(projectID)
      if (projectWritableError.isEmpty) { // empty error if project is writable
        val sendStatus = projectService.publishProject(projectID)
        if (sendStatus.validationSuccess && sendStatus.sendSuccess) {
          Map("sendSuccess" -> true)
        } else {
          logger.error(s"Failed to append project $projectID to road network. Error: ${sendStatus.errorMessage.getOrElse("-")}")
          Map("sendSuccess" -> false, "errorMessage" -> sendStatus.errorMessage.getOrElse(ProjectCouldNotBeAppendedToRoadNetwork))
        }
      } else {
        logger.error(s"Cannot append project $projectID to the road network. Error: ${projectWritableError.get}")
        Map("sendSuccess" -> false, "errorMessage" -> projectWritableError.get)
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
    )
  put("/project/reverse", operation(changeDirection)) {
    time(logger, "PUT request for /project/reverse") {
      val user = userProvider.getCurrentUser
      try {
        val roadInfo = parsedBody.extract[RevertRoadLinksExtractor]
        projectService.changeDirection(roadInfo.projectId, RoadPart(roadInfo.roadNumber, roadInfo.roadPartNumber), roadInfo.links, roadInfo.coordinates, user.username) match {
          case Some(errorMessage) =>
            Map("success" -> false, "errorMessage" -> errorMessage)
          case None =>
            Map("success" -> true, "projectErrors" -> projectService.validateProjectById(roadInfo.projectId).map(projectService.projectValidator.errorPartsToApi))
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

  private val getRoadAddressProjects: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Seq[Map[String, Any]]]("getAllRoadAddressProjects")
      .parameters(
        pathParam[Boolean]("onlyActive").description("Boolean value (true/false) whether you only want the active projects (active status = UpdatingToRoadNetwork, InUpdateQueue, ErrorInViite, Incomplete OR (Accepted in the last 48 hours))")
      )
      tags "ViiteAPI - Project"
      summary "Returns all the necessary information on all or only the active projects to be shown on the project selection window."
    )
  get("/roadlinks/roadaddress/project/all/:onlyActive", operation(getRoadAddressProjects)) {
    time(logger, "GET request for /roadlinks/roadaddress/project/all/:onlyActive") {
      val onlyActive = params("onlyActive").toBoolean
      if (onlyActive) {
        projectService.getActiveProjects
      } else {
        projectService.getAllProjects
      }
    }
  }

  private val getRoadAddressProjectStates: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Seq[(Int, Int)]]("getRoadAddressProjectStates")
      .parameters(
        pathParam[Int]("projectIDs").description("List of project ids to fetch states for.")
      )
      tags "ViiteAPI - Project states"
      summary "Returns state codes for the requested project ids."
    )
  /** Gets the project information for the project list only for the given projects (projectIDs</>). */
  get("/roadlinks/roadaddress/project/states/:projectIDs", operation(getRoadAddressProjectStates)) {
    time(logger, "GET request for /roadlinks/roadaddress/project/states/:projectIDs") {
      val projectIDs: Set[Int] = params("projectIDs").split(",").map(_.toInt).toSet
      projectService.getProjectStates(projectIDs)
    }
  }

  private val getSingleProjectById: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getSingleProjectById")
      .parameters(
        pathParam[Long]("id").description("The id of the project to send to TR.")
      )
      tags "ViiteAPI - Project"
      summary "This will retrive all the information of a specific project, identifiable by it's id."
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
            Map("project" -> projectMap, "linkId" -> project.reservedParts.find(_.startingLinkId.nonEmpty).flatMap(_.startingLinkId),
              "reservedInfo" -> reservedparts, "formedInfo" -> formedparts, "publishable" -> publishable, "projectErrors" -> errorParts.map(projectService.projectValidator.errorPartsToApi))
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
        queryParam[String]("projDate").description("String representing a project start date"),
        queryParam[Long]("projectId").description("Project id")
      )
      tags "ViiteAPI - Project"
      summary "This will retrieve all the information of a specific project, identifiable by it's id."
    )
  get("/roadlinks/roadaddress/project/validatereservedlink/", operation(checkRoadPartExistsAndReservable)) {
    try {
      val roadNumber = params("roadNumber").toLong
      val startPart = params("startPart").toLong
      val endPart = params("endPart").toLong
      val projDate = DateTime.parse(params("projDate"))
      val projectId = params("projectId").toLong
      time(logger, s"GET request for /roadlinks/roadaddress/project/validatereservedlink/", params=Some(params.toMap)) {
        projectService.checkRoadPartExistsAndReservable(roadNumber, startPart, endPart, projDate, projectId) match {
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
      summary "This will return all the supplied project links to their ininital state (RoadAddressChangeType.Unhandled in the case of already pre-existing ones and simple removal in the case of new project links)."
    )
  put("/roadlinks/roadaddress/project/revertchangesroadlink", operation(revertLinks)) {
    time(logger, "PUT request for /roadlinks/roadaddress/project/revertchangesroadlink") {
      try {
        val linksToRevert = parsedBody.extract[RevertRoadLinksExtractor]
        if (linksToRevert.links.nonEmpty) {
          val user = userProvider.getCurrentUser.username
          projectService.revertLinks(linksToRevert.projectId, RoadPart(linksToRevert.roadNumber, linksToRevert.roadPartNumber), linksToRevert.links, linksToRevert.coordinates, user) match {
            case None =>
              val projectErrors = projectService.validateProjectByIdHighPriorityOnly(linksToRevert.projectId).map(projectService.projectValidator.errorPartsToApi)
              val project = projectService.getSingleProjectById(linksToRevert.projectId).get
              Map("success" -> true,
                "publishable" -> projectErrors.isEmpty,
                "projectErrors" -> projectErrors,
                "formedInfo" -> project.formedParts.map(projectFormedPartToApi(Some(project.id))))
            case Some(s) => Map("success" -> false, "errorMessage" -> s)
          }
        }
      } catch {
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

  private val createProjectLinks: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("createProjectLinks")
      .parameters(
        bodyParam[RoadAddressProjectLinksExtractor]("RoadAddressProjectLinks").description("Object representing the projectLinks to create \r\n" +
          "Object structure:" + roadAddressProjectLinksExtractorStructure)
      )
      tags "ViiteAPI - Project"
      summary "This will receive all the project link data in order to be created."
    )
  post("/roadlinks/roadaddress/project/links", operation(createProjectLinks)) {
    time(logger, "POST request for /roadlinks/roadaddress/project/links") {
      val user = userProvider.getCurrentUser
      try {
        val links = parsedBody.extract[RoadAddressProjectLinksExtractor]
        if (links.roadNumber == 0)
          throw RoadPartException("Virheellinen tienumero")
        if (links.roadPartNumber == 0)
          throw RoadPartException("Virheellinen tieosanumero")
        logger.debug(s"Creating new links: ${links.linkIds.mkString(",")}")
        val response = projectService.createProjectLinks(links.linkIds, links.projectId, RoadPart(links.roadNumber, links.roadPartNumber), Track.apply(links.trackCode), Discontinuity.apply(links.discontinuity), AdministrativeClass.apply(links.administrativeClass), LinkGeomSource.apply(links.roadLinkSource), links.roadEly, user.username, links.roadName.getOrElse(halt(BadRequest("Road name is mandatory"))), Some(links.coordinates), links.devToolData)
        response.get("success") match {
          case Some(true) =>
            val projectErrors = response.getOrElse("projectErrors", Seq).asInstanceOf[Seq[projectService.projectValidator.ValidationErrorDetails]].map(projectService.projectValidator.errorPartsToApi)
            Map("success" -> true,
              "publishable" -> !response.contains("projectErrors"),
              "projectErrors" -> projectErrors,
              "errorMessage" -> response.get("errorMessage"))
          case _ => response
        }
      } catch {
        case e: RoadPartException => Map("success" -> false, "errorMessage" -> e.getMessage)
        case _: IllegalStateException => Map("success" -> false, "errorMessage" -> "Projekti ei ole enää muokattavissa")
        case e: MappingException =>
          logger.warn("Exception treating road links", e)
          BadRequest("Missing mandatory ProjectLink parameter")
        case e: Exception =>
          logger.error(e.toString, e)
          Map("success" -> false, "errorMessage" -> e.toString)
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
    )
  put("/roadlinks/roadaddress/project/links", operation(updateProjectLinks)) {
    time(logger, "PUT request for /roadlinks/roadaddress/project/links") {
      val user = userProvider.getCurrentUser
      try {
        val links = parsedBody.extract[RoadAddressProjectLinksExtractor]
        if (links.roadNumber == 0)
          throw RoadPartException("Virheellinen tienumero")
        if (links.roadPartNumber == 0)
          throw RoadPartException("Virheellinen tieosanumero")
        if (projectService.validateLinkTrack(links.trackCode)) {
          projectService.updateProjectLinks(links.projectId, links.ids, links.linkIds, RoadAddressChangeType.apply(links.roadAddressChangeType), user.username, RoadPart(links.roadNumber, links.roadPartNumber), links.trackCode, links.userDefinedEndAddressM, links.administrativeClass, links.discontinuity, Some(links.roadEly), links.reversed.getOrElse(false), roadName = links.roadName, Some(links.coordinates), links.devToolData) match {
            case Some(errorMessage) => Map("success" -> false, "errorMessage" -> errorMessage)
            case None =>
              val projectErrors = projectService.validateProjectByIdHighPriorityOnly(links.projectId).map(projectService.projectValidator.errorPartsToApi)
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
        case e: RoadPartException => Map("success" -> false, "errorMessage" -> e.getMessage)
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
    )
  get("/project/roadlinks", operation(getProjectLinksByBoundingBox)) {
    response.setHeader("Access-Control-Allow-Headers", "*")
    val zoom = chooseDrawType(params.getOrElse("zoom", "5"))
    val id: Long = params.get("id") match {
      case Some(s) if s != "" && s != "undefined" && s.toLong != 0 => s.toLong
      case _ => 0L
    }
    time(logger, s"GET request for /project/roadlinks (zoom: $zoom, id: $id)") {
      userProvider.getCurrentUser
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

  private val reOpenProject: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("reOpenProject")
      .parameters(
        pathParam[Long]("projectId").description("Id of a project")
      )
      tags "ViiteAPI - Project"
      summary "This is a part of the re-opening of a project, this one will update the status of a project that has the projectId supplied."
    )
  post("/project/id/:projectId", operation(reOpenProject)) {
    val projectId = params("projectId").toLong
    time(logger, s"POST request for /project/id/$projectId") {
      userProvider.getCurrentUser
      val oError = projectService.reOpenProject(projectId)
      oError match {
        case Some(error) =>
          Map("success" -> "false", "message" -> error)
        case None =>
          Map("success" -> "true", "message" -> "")
      }
    }
  }

  private val returnChangeTableById: SwaggerSupportSyntax.OperationBuilder =(
    apiOperation[Map[String, Any]]("returnChangeTableById")
      .parameters(
        pathParam[Long]("projectId").description("Id of a project")
      )
      tags "ViiteAPI - Project"
      summary "Given a valid projectId, this will fetch all the changes made on said project."
    )
  get("/project/getchangetable/:projectId", operation(returnChangeTableById)) {
    val projectId = params("projectId").toLong

    time(logger, s"GET request for /project/getchangetable/$projectId") {
      val (changeProject, warningMessage) = projectService.getChangeProject(projectId)
      val changeTableData = changeProject.map(project =>
        Map(
          "id" -> project.id,
          "user" -> project.user,
          "name" -> project.name,
          "changeDate" -> project.changeDate,
          "changeInfoSeq" -> project.changeInfoSeq.map(changeInfo =>
            Map("changetype" -> changeInfo.changeType.value, "roadType" -> changeInfo.administrativeClass.asRoadTypeValue,
              "discontinuity" -> changeInfo.discontinuity.value, "source" -> changeInfo.source,
              "target" -> changeInfo.target, "reversed" -> changeInfo.reversed)))
      ).getOrElse(None)
      Map("changeTable" -> changeTableData, "warningMessage" -> warningMessage)
    }
  }

 /**
  * This is for the dev tool VIITE-3203
  * For running validations without recalculation of the project links
  * (validations from the recalculation phase included)
  * */
  private val validateProject: SwaggerSupportSyntax.OperationBuilder =(
    apiOperation[Map[String, Any]]("validateProject")
      .parameters(
        pathParam[Long]("projectId").description("Id of a project")
      )
      tags "ViiteAPI - Project"
      summary "Given a valid projectId, this will run validations to the project in question."
    )
  get("/project/validateProject/:projectId", operation(validateProject)) {
    val projectId = params("projectId").toLong
    time(logger, s"GET request for /project/validateProject/$projectId") {
      try {
        projectService.runOtherValidations(projectId) // these same are ran in calculation so good to run them here as well
        val validationErrors = projectService.validateProjectById(projectId).map(projectService.projectValidator.errorPartsToApi)
        // return validation errors
        Map("success" -> true, "validationErrors" -> validationErrors)
      } catch {
        case ex: ProjectValidationException =>
          Map("success" -> false, "errorMessage" -> ex.getMessage, "validationErrors" -> ex.getValidationErrors)
        case ex: Exception =>
          Map("success" -> false, "errorMessage" -> ex.getMessage)
      }
    }
  }

  private val recalculateAndValidateProject: SwaggerSupportSyntax.OperationBuilder =(
    apiOperation[Map[String, Any]]("recalculateAndValidateProject")
      .parameters(
        pathParam[Long]("projectId").description("Id of a project")
      )
      tags "ViiteAPI - Project"
      summary "Given a valid projectId, this will run recalculation and the validations to the project in question."
    )
  get("/project/recalculateProject/:projectId", operation(recalculateAndValidateProject)) {
    val projectId = params("projectId").toLong
    time(logger, s"GET request for /project/recalculateProject/$projectId") {
      try {
        val invalidUnchangedLinkErrors = PostGISDatabaseScalikeJDBC.runWithTransaction {
          val project = projectService.fetchProjectById(projectId).get
          val invalidUnchangedLinkErrors = projectService.projectValidator.checkForInvalidUnchangedLinks(project, projectLinkDAO.fetchProjectLinks(projectId))
          if (invalidUnchangedLinkErrors.isEmpty) {
            projectService.recalculateProjectLinks(projectId, project.modifiedBy)
          }
          invalidUnchangedLinkErrors
        }
        val validationErrors = if (invalidUnchangedLinkErrors.nonEmpty)
          invalidUnchangedLinkErrors.map(projectService.projectValidator.errorPartsToApi)
        else
          projectService.validateProjectById(projectId).map(projectService.projectValidator.errorPartsToApi)

        // return validation errors
        Map("success" -> true, "validationErrors" -> validationErrors)
      } catch {
        case ex: RoadAddressException =>
          logger.info("Road address Exception: " + ex.getMessage)
          Map("success" -> false, "errorMessage" -> ex.getMessage)
        case ex: ProjectValidationException =>
          Map("success" -> false, "errorMessage" -> ex.getMessage, "validationErrors" -> ex.getValidationErrors)
        case ex: Exception =>
          Map("success" -> false, "errorMessage" -> ex.getMessage)
      }
    }
  }

  private val getEditableStatusOfJunctionPoints: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getEditableStatusOfJunctionPoints")
      .parameters(
        queryParam[Long]("ids").description("Junction point id:s")
      )
      tags "ViiteAPI - NodesAndJunctions"
      summary "Validates the junction points' editability."
    )
  get("/junctions/getEditableStatusOfJunctionPoints", operation(getEditableStatusOfJunctionPoints)) {
    response.setHeader("Access-Control-Allow-Headers", "*")
    val ids: Seq[Long] = params.get("ids") match {
      case Some(s) if s != "" => s.split("-").map(_.trim.toLong).toSeq
      case _ => Seq()
    }
    time(logger, s"GET request for /junctions/getEditableStatusOfJunctionPoints/ (junctionPointIds: $ids)") {
      val isOnAdministrativeClassChangingSpot = nodesAndJunctionsService.areJunctionPointsOnAdministrativeClassChangingSpot(ids)
      val isOnReservedPart  = nodesAndJunctionsService.areJunctionPointsOnReservedRoadPart(ids)
      val isOnRoadwayChangingSpot = nodesAndJunctionsService.areJunctionPointsOnRoadwayChangingSpot(ids) // TODO remove this check when VIITE-2524 gets implemented
      val isEditableAndValidationMessage = {
        if (isOnReservedPart)
          (false, "Liittymäkohta sijaitsee tieosalla joka on varattuna tieosoiteprojektiin, liittymäkohdan etäisyyden muokkaus ei ole juuri nyt mahdollista.")
        else if (isOnAdministrativeClassChangingSpot)
          (false, "Liittymäkohta sijaitsee hallinnollisen luokan vaihtumiskohdassa, liittymäkohdan etäisyyden muokkaus ei ole sallittua.")
        else if (isOnRoadwayChangingSpot) // TODO remove this when VIITE-2524 gets implemented
          (false, "Tämän liittymäkohdan etäisyyden muokkaus ei ole mahdollista tien tietojen sisäisestä rakenteesta johtuen. Jos etäisyys on välttämätön muuttaa, ota yhteys Viitteen tukeen.")
        else
          (true, "")
      }
      Map("isEditable" -> isEditableAndValidationMessage._1, "validationMessage" -> isEditableAndValidationMessage._2)
    }
  }


  private val getRoadNamesByRoadNumberAndProjectId: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getRoadNamesByRoadNumberAndProjectId")
      .parameters(
        pathParam[Long]("roadNumber").description("Road Number of a project link"),
        pathParam[Long]("projectID").description("Id of a project")
      )
      tags "ViiteAPI - RoadAddresses"
      summary "Returns a road name that is related to a certain roadNumber or a certain project (referenced by the projectID)."
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

  private val getCoordinatesForSearch: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getRoadAddressesByRoadNumberPartNumberAndAddrMValue")
      .parameters {
        queryParam[String]("search").description("" +
          "1. Road name,\r\n" +
          "2. Road address:\r\n" +
          "a) Road Number and Road Part Number;\r\n" +
          "b) Road Number, Road Part Number and Distance value;\r\n" +
          "c) Road Number, Road Part Number, Distance value and Track;\r\n" +
          "3. linkId or mtkId")
      }
      tags "ViiteAPI - General"
      summary "Returns coordinates to support single box search."
      description ""
    )
  get("/roadlinks/search", operation(getCoordinatesForSearch)) {
    val searchString = params.get("search")
    roadAddressService.getSearchResults(searchString)
  }

  private val getRoadLinkDate: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getRoadLinkDate")
      tags "ViiteAPI - General"
      summary "Get maximum of adjusted timestamp of Road Link Date from projectService."
    )
  get("/getRoadLinkDate", operation(getRoadLinkDate)) {
    time(logger, s"GET request for getRoadLinkDate"){
      projectService.getRoadLinkDate
    }
  }

  post("/startLinkNetworkUpdate") {
    time(logger, "POST request for /startLinkNetworkUpdate") {

      try {
        val sourceDate      = (parsedBody \ "sourceDate").extract[String]
        val targetDate      = (parsedBody \ "targetDate").extract[String]
        val processPerDay   = (parsedBody \ "processPerDay").extract[Boolean]

        val (previousDateTimeObject, newDateTimeObject) = (parseStringToDateTime(sourceDate), parseStringToDateTime(targetDate))
        Future(dynamicRoadNetworkService.initiateLinkNetworkUpdates(previousDateTimeObject, newDateTimeObject, processPerDay))
        Map("success" -> true, "message" -> "Tielinkkiverkon päivitys käynnistetty onnistuneesti!")

      } catch {
        case e: MappingException =>
          logger.error("Missing or invalid date field in JSON", e)
          Map("success" -> false, "message" -> s"Missing or invalid date parameter")
        case ex: IllegalArgumentException =>
          logger.error("Updating link network failed due to bad date format.", ex)
          Map("success" -> false, "message" -> s"Unable to parse date, the date should be in yyyy-MM-dd format.")
        case e: Exception =>
          logger.error("Updating link network failed.", e)
          Map("success" -> false, "message" -> s"Updating link network failed: ${e.getMessage}")
      }
    }
  }


  private val getNodesByRoadAttributes: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getNodesByRoadAttributes")
      .parameters(
        queryParam[Long]("roadNumber").description("Road Number of a road address"),
        queryParam[Long]("minRoadPartNumber").description("Road Part Number of a road address"),
        queryParam[Long]("maxRoadPartNumber").description("Road Part Number of a road address")
      )
      tags "ViiteAPI - NodesAndJunctions"
      summary "Returns all the nodes belonging to the road number and possibly withing the given range of road part numbers."
    )
  get("/nodes", operation(getNodesByRoadAttributes)) {
    val roadNumber = params.get("roadNumber").map(_.toLong)
    val minRoadPartNumber = params.get("minRoadPartNumber").map(_.toLong)
    val maxRoadPartNumber = params.get("maxRoadPartNumber").map(_.toLong)
    time(logger, s"GET request for /nodes", params=Some(params.toMap)) {
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

  private val getNodePointAndJunctionTemplates: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getNodePointAndJunctionTemplates")
      tags "ViiteAPI - NodesAndJunctions"
      summary "Get NodePoint And JunctionTemplates"
    )
  get("/templates", operation(getNodePointAndJunctionTemplates)) {
    time(logger, s"GET request for /templates") {
      val authorizedElys = userProvider.getCurrentUser.getAuthorizedElys
      Map("nodePointTemplates" -> nodesAndJunctionsService.getNodePointTemplates(authorizedElys.toSeq).map(nodePointTemplateToApi),
        "junctionTemplates" -> nodesAndJunctionsService.getJunctionTemplates(authorizedElys.toSeq).map(junctionTemplateToApi))
    }
  }

  private val getNodePointTemplateById: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getNodePointTemplateById")
      .parameters(
        pathParam[Long]("id").description("id")
      )
      tags "ViiteAPI - NodesAndJunctions"
      summary "Get NodePointTemplate by Id"
    )
  get("/node-point-templates/:id", operation(getNodePointTemplateById)) {
    val id = params("id").toLong
    time(logger, s"GET request for /node-point-templates/$id") {
      nodesAndJunctionsService.getNodePointTemplateById(id) match {
        case None => halt(NotFound("Node Points Template not found"))
        case Some(nodePoint) => nodePointTemplateToApi(nodePoint)
      }
    }
  }

  private val getJunctionTemplatesById: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("getJunctionTemplatesById")
      .parameters(
        pathParam[Long]("id").description("id")
      )
      tags "ViiteAPI - NodesAndJunctions"
      summary "Get JunctionTemplates by Id"
    )
  get("/junction-templates/:id", operation(getJunctionTemplatesById)) {
    val id = params("id").toLong
    time(logger, s"GET request for /junction-templates/$id") {
      nodesAndJunctionsService.getJunctionTemplatesById(id) match {
        case None => halt(NotFound("Junction Template not found"))
        case Some(junctionTemplate) => junctionTemplateToApi(junctionTemplate)
      }
    }
  }

  private val addOrUpdate: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("addOrUpdate")
      .parameters(
        bodyParam[NodeExtractor]("nodeData")
      )
      tags "ViiteAPI - NodesAndJunctions"
      summary "Add or update nodes, junctions and nodepoints."
    )
  post("/nodes", operation(addOrUpdate)) {
    time(logger, s"POST request for /nodes") {
      val username = userProvider.getCurrentUser.username
      try {
        val nodeInfo = parsedBody.extract[NodeExtractor]
        val node: Node = NodesAndJunctionsConverter.toNode(nodeInfo, username)
        val junctions = NodesAndJunctionsConverter.toJunctions(nodeInfo.junctions)
        val nodePoints = NodesAndJunctionsConverter.toNodePoints(nodeInfo.nodePoints)
        nodesAndJunctionsService.addOrUpdate(node, junctions, nodePoints, username)
        Map("success" -> true)
      } catch {
        case ex: Exception =>
          logger.error(s"Request POST /nodes failed. ${ex.getMessage}")
          Map("success" -> false, "errorMessage" -> ex.getMessage)
      }
    }
  }

  private val update: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("update")
      .parameters(
        pathParam[Long]("id").description("id"),
        bodyParam[NodeExtractor]("nodeData")
      )
      tags "ViiteAPI - NodesAndJunctions"
      summary "Update nodes, junctions and nodepoints."
    )
  put("/nodes/:id", operation(update)) {
    val id = params("id").toLong
    time(logger, s"PUT request for /nodes/$id") {
      val username = userProvider.getCurrentUser.username
      try {
        val nodeInfo = parsedBody.extract[NodeExtractor]
        val node: Node = NodesAndJunctionsConverter.toNode(nodeInfo, username)
        val junctions = NodesAndJunctionsConverter.toJunctions(nodeInfo.junctions)
        val nodePoints = NodesAndJunctionsConverter.toNodePoints(nodeInfo.nodePoints)
        nodesAndJunctionsService.addOrUpdate(node, junctions, nodePoints, username)
        Map("success" -> true)
      } catch {
        case ex: Exception =>
          logger.error("Request PUT /nodes/$id failed.", ex)
          Map("success" -> false, "errorMessage" -> ex.getMessage)
      }
    }
  }

  private def getRoadAddressLinks(zoomLevel: Int)(bbox: String): (Seq[Seq[Map[String, Any]]], Seq[RoadAddressLink]) = {
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
      val partitionedRoadLinks = RoadAddressLinkPartitioner.groupByHomogeneousSection(viiteRoadLinks)
      (partitionedRoadLinks.map{_.map(roadAddressLinkToApi)}, viiteRoadLinks)
    }
  }

  private def getRoadAddressLinksByRoadPartNumber(roadPart: RoadPart): Seq[Seq[Map[String, Any]]] = {
    val viiteRoadLinks = Seq(roadAddressService.getRoadAddressLinksOfWholeRoadPart(roadPart))
    viiteRoadLinks.map{_.map(roadAddressLinkToApi)}
  }

  private def getNodesAndJunctions(zoomLevel: Int, raLinks: Seq[RoadAddressLink])(bbox: String): Map[String, Any] = {
    val boundingRectangle = constructBoundingRectangle(bbox)

    zoomLevel match {
      case zoom if zoom >= DrawAll => time(logger, operationName = "nodes with junctions fetch") {
        val nodes = nodesAndJunctionsService.getNodesWithJunctionByBoundingBox(boundingRectangle, raLinks).toSeq.map(nodeToApi)
        val nodePointTemplates = nodesAndJunctionsService.getNodePointTemplatesByBoundingBox(boundingRectangle, raLinks).map(nodePointTemplateToApi)
        val junctionTemplates = nodesAndJunctionsService.getJunctionTemplatesByBoundingBox(boundingRectangle, raLinks).map(junctionTemplatesWithPointsToApi)

        Map("nodes" -> nodes,
          "nodePointTemplates" -> nodePointTemplates,
          "junctionTemplates" -> junctionTemplates)
      }
      case zoom if zoom >= DrawNodes => time(logger, operationName = "nodes fetch ") {
        Map("nodes" -> nodesAndJunctionsService.getNodesWithJunctionByBoundingBox(boundingRectangle, raLinks).toSeq.map(nodeToApi))
      }
      case _ => Map("nodes" -> nodesAndJunctionsService.getNodesByBoundingBox(boundingRectangle).map(simpleNodeToApi))
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
      case DrawLinearPublicRoads => projectService.getProjectLinksLinear(projectId, boundingRectangle, Seq((1, 19999), (40000, 49999)), Set())
      case DrawPublicRoads => projectService.getProjectLinksWithoutSuravage(projectId, boundingRectangle, Seq((1, 19999), (40000, 49999)), Set())
      case DrawAllRoads => projectService.getProjectLinksWithoutSuravage(projectId, boundingRectangle, Seq(), Set(), everything = true)
      case _ => projectService.getProjectLinksWithoutSuravage(projectId, boundingRectangle, Seq((1, 19999)), Set())
    }
    logger.info(s"End fetching data for id=$projectId project service (zoom level $zoomLevel) in ${(System.currentTimeMillis() - startTime) * 0.001}s")

    val partitionedRoadLinks = ProjectLinkPartitioner.partition(viiteRoadLinks.filter(_.length >= MinAllowedRoadAddressLength))
    val validRoadNumbers = partitionedRoadLinks.flatten.map(_.roadPart.roadNumber).filter(value => value > 0).distinct
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

  private def chooseNodesDrawType(zoomLevel: String) = {
    val C1 = new Contains(-10 to 8)
    val C2 = new Contains(9 to 11)
    val C3 = new Contains(12 to 16)
    try {
      val level: Int = Math.round(zoomLevel.toDouble).toInt
      level match {
        case C1() => DrawNone
        case C2() => DrawNodes
        case C3() => DrawAll
        case _ => DrawNone
      }
    } catch {
      case _: NumberFormatException => DrawMainRoadPartsOnly
    }
  }

  private[this] def constructBoundingRectangle(bbox: String) = {
    val BBOXList = bbox.split(",").map(_.toDouble)
    BoundingRectangle(Point(BBOXList(0), BBOXList(1)), Point(BBOXList(2), BBOXList(3)))
  }

  private def addrMRangeToApi(addrMRange: AddrMRange): Map[String, Long] = {
    Map(
        "start" -> addrMRange.start,
        "end"   -> addrMRange.end
    )
  }

  private def roadAddressLinkLikeToApi(roadAddressLink: RoadAddressLinkLike): Map[String, Any] = {
    Map(
      "success" -> true,
      "roadwayId" -> roadAddressLink.id,
      "roadwayNumber" -> roadAddressLink.roadwayNumber,
      "linearLocationId" -> roadAddressLink.linearLocationId,
      "linkId" -> roadAddressLink.linkId,
      "mmlId" -> roadAddressLink.sourceId,
      "points" -> roadAddressLink.geometry,
      "calibrationCode" -> CalibrationCode.getFromAddressLinkLike(roadAddressLink).value,
      "calibrationPoints" -> Seq(calibrationPointToApi(roadAddressLink.geometry, roadAddressLink.startCalibrationPoint),
        calibrationPointToApi(roadAddressLink.geometry, roadAddressLink.endCalibrationPoint)),
      "administrativeClassMML" -> roadAddressLink.administrativeClassMML.toString,
      "roadClass" -> RoadClass.get(roadAddressLink.roadPart.roadNumber.toInt),
      "administrativeClassId" -> roadAddressLink.administrativeClass.value,
      "modifiedAt" -> roadAddressLink.modifiedAt,
      "modifiedBy" -> roadAddressLink.modifiedBy,
      "municipalityCode" -> roadAddressLink.municipalityCode,
      "municipalityName" -> roadAddressLink.municipalityName,
      "roadNameFi" -> "",
      "roadNameSe" -> "",
      "roadNumber" -> roadAddressLink.roadPart.roadNumber,
      "roadPartNumber" -> roadAddressLink.roadPart.partNumber,
      "elyCode" -> roadAddressLink.elyCode,
      "trackCode" -> roadAddressLink.trackCode,
      "addrMRange" -> addrMRangeToApi(roadAddressLink.addrMRange),
      "discontinuity" -> roadAddressLink.discontinuity,
      "lifecycleStatus" -> roadAddressLink.lifecycleStatus.value,
      "startMValue" -> roadAddressLink.startMValue,
      "endMValue" -> roadAddressLink.endMValue,
      "sideCode" -> roadAddressLink.sideCode.value,
      "roadLinkSource" -> roadAddressLink.roadLinkSource.value,
      "roadName" -> roadAddressLink.roadName
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
      "coordinates" -> Map(
        "x" -> node.coordinates.x,
        "y" -> node.coordinates.y),
      "type" -> node.nodeType.value,
      "startDate" -> formatToString(node.startDate.toString),
      "createdBy" -> node.createdBy,
      "createdTime" -> node.createdTime,
      "registrationDate" -> node.registrationDate.toString
    )
  }

  def nodePointToApi(nodePoint: NodePoint) : Map[String, Any] = {
    Map("id" -> nodePoint.id,
      "nodeNumber" -> nodePoint.nodeNumber,
      "roadNumber" -> nodePoint.roadPart.roadNumber,
      "roadPartNumber" -> nodePoint.roadPart.partNumber,
      "addrM" -> nodePoint.addrM,
      "roadwayNumber" -> nodePoint.roadwayNumber,
      "beforeAfter" -> nodePoint.beforeAfter.value,
      "type" -> nodePoint.nodePointType.value,
      "roadwayPointId" -> nodePoint.roadwayPointId,
      "validFrom" -> formatToString(nodePoint.validFrom.toString),
      "validTo" -> formatDateTimeToString(nodePoint.validTo),
      "createdBy" -> nodePoint.createdBy,
      "createdTime" -> nodePoint.createdTime,
      "track" -> nodePoint.track.value,
      "elyCode" -> nodePoint.elyCode,
      "coordinates" -> Map(
        "x" ->  nodePoint.coordinates.x,
        "y" ->  nodePoint.coordinates.y)
    )
  }

  def nodePointTemplateToApi(nodePoint: NodePoint) : Map[String, Any] = {
    Map("id" -> nodePoint.id,
      "beforeAfter" -> nodePoint.beforeAfter.value,
      "roadwayPointId" -> nodePoint.roadwayPointId,
      "type" -> nodePoint.nodePointType.value,
      "validFrom" -> formatToString(nodePoint.validFrom.toString),
      "validTo" -> formatDateTimeToString(nodePoint.validTo),
      "createdBy" -> nodePoint.createdBy,
      "roadwayNumber" -> nodePoint.roadwayNumber,
      "addrM" -> nodePoint.addrM,
      "elyCode" -> nodePoint.elyCode,
      "roadNumber" -> nodePoint.roadPart.roadNumber,
      "roadPartNumber" -> nodePoint.roadPart.partNumber,
      "track" -> nodePoint.track,
      "coordinates" -> Map(
        "x" ->  nodePoint.coordinates.x,
        "y" ->  nodePoint.coordinates.y)
    )
  }

  def junctionTemplateToApi(junctionTemplate: JunctionTemplate) : Map[String, Any] = {
    Map(
      "id" -> junctionTemplate.id,
      "junctionNumber" -> null,
      "startDate" -> formatToString(junctionTemplate.startDate.toString),
      "roadNumber" -> junctionTemplate.roadPart.roadNumber,
      "roadPartNumber" -> junctionTemplate.roadPart.partNumber,
      "track" -> junctionTemplate.track,
      "addrM" -> junctionTemplate.addrM,
      "elyCode" -> junctionTemplate.elyCode)
  }

  def junctionTemplatesWithPointsToApi(junctionPointTemplate: (JunctionTemplate, Seq[JunctionPoint])) : Map[String, Any] = {
    junctionTemplateToApi(junctionPointTemplate._1) ++
      Map("junctionPoints" -> junctionPointTemplate._2.map(junctionPointsToApi))
  }

  def junctionPointsToApi(junctionPoint: JunctionPoint) : Map[String, Any] = {
    Map("id" -> junctionPoint.id,
      "junctionId" -> junctionPoint.junctionId,
      "beforeAfter" -> junctionPoint.beforeAfter.value,
      "roadwayPointId" -> junctionPoint.roadwayPointId,
      "startDate" -> formatDateTimeToString(junctionPoint.startDate),
      "endDate" -> formatDateTimeToString(junctionPoint.endDate),
      "validFrom" -> formatToString(junctionPoint.validFrom.toString),
      "validTo" -> formatDateTimeToString(junctionPoint.validTo),
      "createdBy" -> junctionPoint.createdBy,
      "roadwayNumber" -> junctionPoint.roadwayNumber,
      "addrM" -> junctionPoint.addrM,
      "roadNumber" -> junctionPoint.roadPart.roadNumber,
      "roadPartNumber" -> junctionPoint.roadPart.partNumber,
      "track" -> junctionPoint.track,
      "coordinates" -> Map(
        "x" ->  junctionPoint.coordinates.x,
        "y" ->  junctionPoint.coordinates.y)
    )
  }

  def junctionToApi(junction: (Junction, Seq[JunctionPoint])): Map[String, Any] = {
    Map("id" -> junction._1.id,
      "junctionNumber" -> junction._1.junctionNumber.orNull,
      "nodeNumber" -> junction._1.nodeNumber,
      "startDate" -> formatToString(junction._1.startDate.toString),
      "endDate" -> (if (junction._1.endDate.isDefined) junction._1.endDate.get.toString else null),
      "validFrom" -> formatToString(junction._1.validFrom.toString),
      "validTo" -> (if (junction._1.validTo.isDefined) junction._1.validTo.get.toString else null),
      "createdBy" -> junction._1.createdBy,
      "createdTime" -> junction._1.createdTime,
      "junctionPoints" -> junction._2.map(junctionPointToApi)
    )
  }

  def junctionPointToApi(junctionPoint: JunctionPoint) : Map[String, Any] = {
    Map("id" -> junctionPoint.id,
      "junctionId" -> junctionPoint.junctionId,
      "roadwayNumber" -> junctionPoint.roadwayNumber,
      "roadwayPointId" -> junctionPoint.roadwayPointId,
      "roadNumber" -> junctionPoint.roadPart.roadNumber,
      "roadPartNumber" -> junctionPoint.roadPart.partNumber,
      "track" -> junctionPoint.track.value,
      "addrM" -> junctionPoint.addrM,
      "beforeAfter" -> junctionPoint.beforeAfter.value,
      "coordinates" -> Map(
        "x" ->  junctionPoint.coordinates.x,
        "y" ->  junctionPoint.coordinates.y)
    )
  }

  def nodeToApi(node: (Node, (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]]))) : Map[String, Any] = {
    simpleNodeToApi(node._1) ++
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

  def roadAddressBrowserTracksToApi(track: TrackForRoadAddressBrowser): Map[String, Any] = {
    Map(
      "ely" -> track.ely,
      "roadNumber" -> track.roadPart.roadNumber,
      "track" -> track.track,
      "roadPartNumber" -> track.roadPart.partNumber,
      "addrMRange" -> addrMRangeToApi(track.addrMRange),
      "lengthAddrM" -> track.roadAddressLengthM,
      "administrativeClass" -> track.administrativeClass,
      "startDate" -> new SimpleDateFormat("dd.MM.yyyy").format(track.startDate.toDate)
    )
  }

  def roadAddressBrowserRoadPartsToApi(roadPart: RoadPartForRoadAddressBrowser): Map[String, Any] = {
    Map(
      "ely" -> roadPart.ely,
      "roadNumber" -> roadPart.roadPart.roadNumber,
      "roadPartNumber" -> roadPart.roadPart.partNumber,
      "addrMRange" -> addrMRangeToApi(roadPart.addrMRange),
      "lengthAddrM" -> roadPart.roadAddressLengthM,
      "startDate" -> new SimpleDateFormat("dd.MM.yyyy").format(roadPart.startDate.toDate)
    )
  }

  def roadAddressBrowserNodesToApi(node: NodeForRoadAddressBrowser): Map[String, Any] = {
    Map(
      "ely" -> node.ely,
      "roadNumber" -> node.roadPart.roadNumber,
      "roadPartNumber" -> node.roadPart.partNumber,
      "addrM" -> node.addrM,
      "startDate" -> new SimpleDateFormat("dd.MM.yyyy").format(node.startDate.toDate),
      "nodeType" -> node.nodeType.displayValue,
      "nodeName" -> node.name,
      "nodeCoordinates" -> node.nodeCoordinates,
      "nodeNumber" -> node.nodeNumber
    )
  }

  def roadAddressBrowserJunctionsToApi(junction :JunctionForRoadAddressBrowser): Map[String, Any] = {
    Map(
      "nodeNumber" -> junction.nodeNumber,
      "nodeCoordinates" -> junction.nodeCoordinates,
      "nodeName" -> junction.nodeName,
      "nodeType" -> junction.nodeType.displayValue,
      "startDate" -> new SimpleDateFormat("dd.MM.yyyy").format(junction.startDate.toDate),
      "junctionNumber" -> junction.junctionNumber,
      "roadNumber" -> junction.roadPart.roadNumber,
      "track" -> junction.track,
      "roadPartNumber" -> junction.roadPart.partNumber,
      "addrM" -> junction.addrM,
      "beforeAfter" -> junction.beforeAfter
    )
  }

  def roadAddressBrowserRoadNamesToApi(roadName :RoadNameForRoadAddressBrowser): Map[String, Any] = {
    Map(
      "ely" -> roadName.ely,
      "roadNumber" -> roadName.roadNumber,
      "roadName" -> roadName.roadName
    )
  }

  def roadAddressChangeInfoToApi(changeInfo: ChangeInfoForRoadAddressChangesBrowser): Map[String, Any] = {
    val oldPart = changeInfo.oldRoadAddress.roadPart
    Map(
      "startDate" -> new SimpleDateFormat("dd.MM.yyyy").format(changeInfo.startDate.toDate),
      "changeType" -> changeInfo.changeType,
      "reversed" -> changeInfo.reversed,
      "roadName" -> changeInfo.roadName.getOrElse(""),
      "projectName" -> changeInfo.projectName,
      "projectAcceptedDate" -> new SimpleDateFormat("dd.MM.yyyy").format(changeInfo.projectAcceptedDate.toDate),
      "oldEly" -> changeInfo.oldRoadAddress.ely,
      "oldRoadNumber"     -> (if(oldPart.nonEmpty) oldPart.get.roadNumber else ""),
      "oldTrack" -> changeInfo.oldRoadAddress.track.getOrElse(""),
      "oldRoadPartNumber" -> (if(oldPart.nonEmpty) oldPart.get.partNumber else ""),
      "oldStartAddrM" -> changeInfo.oldRoadAddress.getStartOption.getOrElse(""), //TODO to addrMRange?
      "oldEndAddrM"   -> changeInfo.oldRoadAddress.getEndOption.getOrElse(""),
      "oldLength" -> changeInfo.oldRoadAddress.length.getOrElse(""),
      "oldAdministrativeClass" -> changeInfo.oldRoadAddress.administrativeClass,
      "newEly" -> changeInfo.newRoadAddress.ely,
      "newRoadNumber" -> changeInfo.newRoadAddress.roadPart.roadNumber,
      "newTrack" -> changeInfo.newRoadAddress.track,
      "newRoadPartNumber" -> changeInfo.newRoadAddress.roadPart.partNumber,
      "newAddrMRange" -> addrMRangeToApi(changeInfo.newRoadAddress.addrMRange),
      "newLength" -> changeInfo.newRoadAddress.length,
      "newAdministrativeClass" -> changeInfo.newRoadAddress.administrativeClass
    )
  }

  def projectAddressLinkToApi(projectAddressLink: ProjectAddressLink, roadNames: Seq[RoadName] = Seq()): Map[String, Any] = {
    val originalAddrMRange = {
      if (projectAddressLink.originalAddrMRange.isDefined) {
        AddrMRange(projectAddressLink.originalAddrMRange.get.start, projectAddressLink.originalAddrMRange.get.end)
      } else {
        AddrMRange(0,0) // projectAddressLinks might be "outside of project scope" i.e. not reserved to project and they dont have originalAddrMRange 'cause their addresses wont change
      }
    }
    val roadAddressPart = projectAddressLink.roadAddressRoadPart
    (Map(
        "success" -> true,
        "roadwayId" -> projectAddressLink.roadwayId,
        "roadwayNumber" -> projectAddressLink.roadwayNumber,
        "linearLocationId" -> projectAddressLink.linearLocationId,
        "linkId" -> projectAddressLink.linkId,
        "mmlId" -> projectAddressLink.sourceId,
        "points" -> projectAddressLink.geometry,
        "calibrationCode" -> CalibrationCode.getFromAddressLinkLike(projectAddressLink).value,
        "calibrationPoints" -> Seq(calibrationPointToApi(projectAddressLink.geometry, projectAddressLink.startCalibrationPoint),
          calibrationPointToApi(projectAddressLink.geometry, projectAddressLink.endCalibrationPoint)),
        "administrativeClassMML" -> projectAddressLink.administrativeClassMML.toString,
        "roadClass" -> RoadClass.get(projectAddressLink.roadPart.roadNumber.toInt),
        "administrativeClassId" -> projectAddressLink.administrativeClass.value,
        "modifiedAt" -> projectAddressLink.modifiedAt,
        "modifiedBy" -> projectAddressLink.modifiedBy,
        "municipalityCode" -> projectAddressLink.municipalityCode,
        "municipalityName" -> projectAddressLink.municipalityName,
        "roadNameFi" -> "",
        "roadNameSe" -> "",
        "roadNumber"     -> projectAddressLink.roadPart.roadNumber,
        "roadPartNumber" -> projectAddressLink.roadPart.partNumber,
        "elyCode" -> projectAddressLink.elyCode,
        "trackCode" -> projectAddressLink.trackCode,
        "addrMRange" -> addrMRangeToApi(projectAddressLink.addrMRange),
        "originalStartAddressM" -> originalAddrMRange.start,
        "originalEndAddressM" -> originalAddrMRange.end,
        "discontinuity" -> projectAddressLink.discontinuity,
        "lifecycleStatus" -> projectAddressLink.lifecycleStatus.value,
        "startMValue" -> projectAddressLink.startMValue,
        "endMValue" -> projectAddressLink.endMValue,
        "sideCode" -> projectAddressLink.sideCode.value,
        "roadLinkSource" -> projectAddressLink.roadLinkSource.value,
        "roadName" -> projectAddressLink.roadName,
        "id" -> projectAddressLink.id,
        "status" -> projectAddressLink.status.value,
        "reversed" -> projectAddressLink.reversed,
        "roadNameBlocked" -> (if (projectAddressLink.roadPart.roadNumber != 0 && projectAddressLink.roadName.nonEmpty) roadNames.exists(_.roadNumber == projectAddressLink.roadPart.roadNumber) else false),
        "roadAddressRoadNumber" -> (if(roadAddressPart.nonEmpty) roadAddressPart.get.roadNumber else ""),
        "roadAddressRoadPart"   -> (if(roadAddressPart.nonEmpty) roadAddressPart.get.partNumber else "")
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
      "status" -> roadAddressProject.projectState,
      "statusCode" -> roadAddressProject.projectState.value,
      "statusDescription" -> roadAddressProject.projectState.description,
      "statusInfo" -> roadAddressProject.statusInfo,
      "elys" -> elys,
      "coordX" -> roadAddressProject.coordinates.get.x,
      "coordY" -> roadAddressProject.coordinates.get.y,
      "zoomLevel" -> roadAddressProject.coordinates.get.zoom
    )
  }

  def projectReservedPartToApi(reservedRoadPart: ProjectReservedPart): Map[String, Any] = {
    Map("roadNumber" -> reservedRoadPart.roadPart.roadNumber,
      "roadPartNumber" -> reservedRoadPart.roadPart.partNumber,
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
    Map("roadNumber" -> formedRoadPart.roadPart.roadNumber,
      "roadPartNumber" -> formedRoadPart.roadPart.partNumber,
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
          case _ => projectService.getRoadAddressesFromFormedRoadPart(formedRoadPart.roadPart, projectId.get)
        }
      }
    )
  }

  def nodeSearchToApi(nodeAndRoadAttr: (Node, RoadAttributes)): Map[String, Any] = {
    val (node, roadAttr) = nodeAndRoadAttr
    Map("id" -> node.id,
      "nodeNumber" -> node.nodeNumber,
      "coordinates" -> Map(
        "x" -> node.coordinates.x,
        "y" -> node.coordinates.y),
      "name" -> node.name,
      "type" -> node.nodeType.displayValue,
      "roadNumber" -> roadAttr.roadPart.roadNumber,
      "roadPartNumber" -> roadAttr.roadPart.partNumber,
      "addrMValue" -> roadAttr.addrMValue)
  }

  /**
    * For checking date validity we convert string date to datetime options
    *
    * @param dateString string formated date dd.mm.yyyy
    * @return Joda datetime
    */
  private def optionStringToDateTime(dateString: Option[String]): Option[DateTime] = {
    dateString match {
      case Some(date) => Some(dateSlashFormatter.parseDateTime(date))
      case _ => None
    }
  }

  // Fold segments on same link together
  // TODO: add here start / end dates unique values?
  private def foldSegments[T <: RoadAddressLinkLike](links: Seq[T]): Option[T] = {
    if (links.nonEmpty)
      Some(links.tail.foldLeft(links.head) {
        case (a: RoadAddressLink, b) =>
          a.copy(addrMRange = AddrMRange(Math.min(a.addrMRange.start, b.addrMRange.start), Math.max(a.addrMRange.end, b.addrMRange.end)), startMValue = Math.min(a.startMValue, b.endMValue), sourceId = "").asInstanceOf[T]
        case (a: ProjectAddressLink, b) =>
          a.copy(addrMRange = AddrMRange(Math.min(a.addrMRange.start, b.addrMRange.start), Math.max(a.addrMRange.end, b.addrMRange.end)), startMValue = Math.min(a.startMValue, b.endMValue), sourceId = "").asInstanceOf[T]
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
    dateOption.map { date => date.toString(finnishDateCommaTimeFormatter) }

  private def calibrationPointToApi(geometry: Seq[Point], calibrationPoint: Option[ProjectCalibrationPoint]): Option[Map[String, Any]] = {
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

  case class StartupParameters(lon: Double, lat: Double, zoom: Int, deploy_date: String, roles: Set[String])
  case class RoadPartException(private val message: String = "", private val cause: Throwable = None.orNull) extends Exception(message, cause)

}

object ProjectConverter {
  def toRoadAddressProject(project: RoadAddressProjectExtractor, user: User): Project = {
    Project(project.id, ProjectState.apply(project.status),
      if (project.name.length > 32) project.name.substring(0, 32).trim else project.name.trim, //TODO the name > 32 should be a handled exception since the user can't insert names with this size
      user.username, DateTime.now(), user.username, finnishDateFormatter.parseDateTime(project.startDate), DateTime.now(),
      project.additionalInfo, project.reservedPartList.distinct.map(toReservedRoadPartEly), project.formedPartList.distinct.map(toReservedRoadPartEly), Option(project.additionalInfo), elys = Set())
  }

  def toReservedRoadPartEly(rp: RoadPartElyExtractor): ProjectReservedPart = {
    ProjectReservedPart(0L, RoadPart(rp.roadNumber, rp.roadPartNumber), None, None, Some(rp.ely), None, None, None, None)
  }
}

object NodesAndJunctionsConverter {

  def toNode(node: NodeExtractor, username: String) : Node = {
    val endDate          = if (node.endDate.isDefined)     Option(finnishDateFormatter.parseDateTime(node.endDate.get))     else None
    val validFrom        = if (node.validFrom.isDefined)          finnishDateFormatter.parseDateTime(node.validFrom.get)    else new DateTime()
    val validTo          = if (node.validTo.isDefined)     Option(finnishDateFormatter.parseDateTime(node.validTo.get))     else None
    val createdTime      = if (node.createdTime.isDefined) Option(finnishDateFormatter.parseDateTime(node.createdTime.get)) else None
    val registrationDate = if (node.registrationDate.isDefined) DateTime.parse(node.registrationDate.get)                   else new DateTime()

    Node(node.id, node.nodeNumber, node.coordinates, node.name, NodeType.apply(node.`type`),
      finnishDateFormatter.parseDateTime(node.startDate), endDate, validFrom, validTo, username, createdTime, registrationDate = registrationDate)
  }

  def toJunctions(junctions: Seq[JunctionExtractor]): Seq[Junction] = {
    junctions.map { junction =>
      val validFrom   = if (junction.validFrom.isDefined)          finnishDateFormatter.parseDateTime(junction.validFrom.get)    else new DateTime()
      val validTo     = if (junction.validTo.isDefined)     Option(finnishDateFormatter.parseDateTime(junction.validTo.get))     else None
      val startDate   =                                            finnishDateFormatter.parseDateTime(junction.startDate)
      val endDate     = if (junction.endDate.isDefined)     Option(finnishDateFormatter.parseDateTime(junction.endDate.get))     else None
      val createdTime = if (junction.createdTime.isDefined) Option(finnishDateFormatter.parseDateTime(junction.createdTime.get)) else None

      val junctionPoints = toJunctionPoints(junction, startDate, endDate)

      Junction(junction.id, junction.junctionNumber, junction.nodeNumber, startDate, endDate,
        validFrom, validTo, junction.createdBy.getOrElse("-"), createdTime, Some(junctionPoints))
    }
  }

  private def toJunctionPoints(junction: JunctionExtractor, startDate: DateTime, endDate: Option[DateTime]): List[JunctionPoint] = {
    junction.junctionPoints.map { jp =>
      val beforeAfter = BeforeAfter.apply(jp.beforeAfter)
      val validFrom   = if (jp.validFrom.isDefined)          finnishDateFormatter.parseDateTime(jp.validFrom.get)    else new DateTime()
      val validTo     = if (jp.validTo.isDefined)     Option(finnishDateFormatter.parseDateTime(jp.validTo.get))     else None
      val createdTime = if (jp.createdTime.isDefined) Option(finnishDateFormatter.parseDateTime(jp.createdTime.get)) else None

      JunctionPoint(jp.id, beforeAfter, jp.roadwayPointId, jp.junctionId, Some(startDate), endDate, validFrom, validTo,
        jp.createdBy.getOrElse("-"), createdTime, jp.roadwayNumber, jp.addrM, RoadPart(jp.roadNumber, jp.roadPartNumber),
        jp.track, Discontinuity.Continuous)
    }
  }

  def toNodePoints(nodePoints: Seq[NodePointExtractor]): Seq[NodePoint] = {
    nodePoints.map { nodePoint =>
      val validTo     = if (nodePoint.validTo.isDefined)     Option(finnishDateFormatter.parseDateTime(nodePoint.validTo.get))     else None
      val startDate   = if (nodePoint.startDate.isDefined)   Option(finnishDateFormatter.parseDateTime(nodePoint.startDate.get))   else None
      val endDate     = if (nodePoint.endDate.isDefined)     Option(finnishDateFormatter.parseDateTime(nodePoint.endDate.get))     else None
      val createdTime = if (nodePoint.createdTime.isDefined) Option(finnishDateFormatter.parseDateTime(nodePoint.createdTime.get)) else None

      NodePoint(nodePoint.id, BeforeAfter.apply(nodePoint.beforeAfter), nodePoint.roadwayPointId, nodePoint.nodeNumber, NodePointType.apply(nodePoint.`type`),
        startDate, endDate, finnishDateFormatter.parseDateTime(nodePoint.validFrom), validTo,
        nodePoint.createdBy, createdTime, nodePoint.roadwayNumber, nodePoint.addrM,
        RoadPart(nodePoint.roadNumber, nodePoint.roadPartNumber), Track.apply(nodePoint.track), nodePoint.elyCode)
    }
  }
}
