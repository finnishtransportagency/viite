package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset.{Modification, TimeStamps}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.util.DigiroadSerializers
import fi.liikennevirasto.viite.RoadAddressService
import fi.liikennevirasto.viite.dao.RoadAddress
import org.json4s.Formats
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, _}
import org.scalatra.{BadRequest, HttpMethod, Post, ScalatraServlet}
import org.slf4j.{Logger, LoggerFactory}


class SearchApi(roadAddressService: RoadAddressService,
                implicit val swagger: Swagger)
  extends ScalatraServlet
    with JacksonJsonSupport
    with ViiteAuthenticationSupport
    with SwaggerSupport {
  protected val applicationDescription = "The Search API "

  val logger: Logger = LoggerFactory.getLogger(getClass)
  protected implicit val jsonFormats: Formats = DigiroadSerializers.jsonFormats

  case class AssetTimeStamps(created: Modification, modified: Modification) extends TimeStamps

  before() {
    basicAuth
    contentType = formats("json")
  }

  private val getRoadAddress: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getRoadAddress")
      .parameters(
        queryParam[Long]("linkId").description("LinkId of a road address"),
        queryParam[Double]("startMeasure").description("startMeasure of a road address").optional,
        queryParam[Double]("endMeasure").description("endMeasure of a road address").optional
      )
      tags "SearchAPI (oth)"
      summary "Gets all the road addresses in between the given linear location."
      )

  get("/road_address/?", operation(getRoadAddress)) {
    val linkId = params.getOrElse("linkId", halt(BadRequest("Missing mandatory field linkId"))).toLong
    val startMeasure = params.get("startMeasure").map(_.toDouble)
    val endMeasure = params.get("endMeasure").map(_.toDouble)

    time(logger, s"GET request for /road_address/? (linkId: $linkId, startMeasure: $startMeasure, endMeasure: $endMeasure)") {
      roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId, startMeasure, endMeasure).map(roadAddressMapper)
    }
  }

  private val getRoadNumbers: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[Seq[Long]]("getRoadNumbers")
      tags "SearchAPI (oth)"
      summary "Gets all the existing road numbers at the current road network."
      )

  get("/road_numbers?", operation(getRoadNumbers)) {
    time(logger, "GET request for /road_numbers?") {
      roadAddressService.getRoadNumbers
    }
  }

  private val getRoadAddressWithRoadNumber: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressWithRoadNumber")
      .parameters(
        pathParam[Long]("road").description("Road Number of a road address"),
        queryParam[Long]("tracks").description("Track Number (0,1,2) tracks=1&tracks=2 returns both left and right track").optional
      )
      tags "SearchAPI (oth)"
      summary "Gets all the road addresses in the same road number and track codes."
    )

  get("/road_address/:road/?", operation(getRoadAddressWithRoadNumber)) {
    val roadNumber = params("road").toLong
    time(logger, s"GET request for /road_address/$roadNumber/?") {
      val trackCodes = multiParams.getOrElse("tracks", Seq()).map(_.toInt)
      roadAddressService.getRoadAddressWithRoadNumber(roadNumber, Track.applyAll(trackCodes)).map(roadAddressMapper)
    }
  }

  private val getRoadAddressesFiltered: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressesFiltered")
      .parameters(
        pathParam[Long]("road").description("Road Number of a road address"),
        pathParam[Long]("roadPart").description("Road Part Number of a road address")
      )
      tags "SearchAPI (oth)"
      summary "Gets all the road address in the given road number and road part"
    )

  get("/road_address/:road/:roadPart/?", operation(getRoadAddressesFiltered)) {
    val roadNumber = params("road").toLong
    val roadPart = params("roadPart").toLong
    time(logger, s"GET request for /road_address/$roadNumber/$roadPart/?") {
      roadAddressService.getRoadAddressesFiltered(roadNumber, roadPart).map(roadAddressMapper)
    }
  }

  private val getRoadAddressesFiltered2: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressesFiltered2")
      .parameters(
        pathParam[Long]("road").description("Road Number of a road address"),
        pathParam[Long]("roadPart").description("Road Part Number of a road address"),
        pathParam[Long]("address").description("Road Measure of a road address"),
        pathParam[Long]("track").description("Road Track of a road address. Optional")
      )
      tags "SearchAPI (oth)"
      summary "Gets all the road addresses in the same road number, road part number with start address less than the given address measure. If trackOption parameter is given it will also filter by track code."
    )

  get("/road_address/:road/:roadPart/:address/?", operation(getRoadAddressesFiltered2)) {
    val roadNumber = params("road").toLong
    val roadPart = params("roadPart").toLong
    val address = params("address").toLong
    val track = params.get("track").map(_.toInt)

    time(logger, s"GET request for /road_address/$roadNumber/$roadPart/$address/? (track: $track)") {
      roadAddressService.getRoadAddress(roadNumber, roadPart, address, Track.applyOption(track)).map(roadAddressMapper)
    }
  }

  private val getRoadAddressesFiltered3: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressesFiltered3")
      .parameters(
        pathParam[Long]("road").description("Road Number of a road address"),
        pathParam[Long]("roadPart").description("Road Part Number of a road address"),
        pathParam[Long]("startAddress").description("Road start measure of a road address"),
        pathParam[Long]("endAddress").description("Road end measure of a road address")
      )
      tags "SearchAPI (oth)"
      summary "Gets all the road addresses in given road number, road part number and between given address measures. The road address measures should be in [startAddrM, endAddrM]"
    )

  get("/road_address/:road/:roadPart/:startAddress/:endAddress/?", operation(getRoadAddressesFiltered3)) {
    val roadNumber = params("road").toLong
    val roadPart = params("roadPart").toLong
    val startAddress = params("startAddress").toLong
    val endAddress = params("endAddress").toLong

    time(logger, s"GET request for /road_address/$roadNumber/$roadPart/$startAddress/$endAddress/?") {
      roadAddressService.getRoadAddressesFiltered(roadNumber, roadPart, startAddress, endAddress).map(roadAddressMapper)
    }
  }

  private val getRoadAddressByLinkIds: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressByLinkIds")
      .parameters(
        bodyParam[Set[Long]]("linkIds").description("List of LinkIds\r\n")
      )
      tags "SearchAPI (oth)"
      summary "Gets all the road addresses on top of given road links."
    )

  post("/road_address/?", operation(getRoadAddressByLinkIds)) {
    time(logger, s"POST request for /road_address/?") {
      val linkIds = parsedBody.extract[Set[Long]]
      roadAddressService.getRoadAddressByLinkIds(linkIds).map(roadAddressMapper)
    }
  }

  private val getRoadAddressWithRoadNumberParts: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressWithRoadNumberParts")
      .parameters(
        pathParam[Long]("road").description("Road Number of a road address"),
        bodyParam[Any]("getLists").description("List of roadParts and List of tracks\r\n")
      )
      tags "SearchAPI (oth)"
      summary "Gets all the road addresses in the same road number, road parts and track codes. If the road part number sequence or track codes sequence is empty."
    )

  post("/road_address/:road/?", operation(getRoadAddressWithRoadNumberParts)) {
    time(logger, s"POST request for /road_address/:road/?"){
      val roadNumber = params("road").toLong
      val roadParts = (parsedBody \ "roadParts").extract[Seq[Long]]
      val tracks = (parsedBody \ "tracks").extract[Seq[Int]]
      roadAddressService.getRoadAddressWithRoadNumberParts(roadNumber, roadParts.toSet, Track.applyAll(tracks)).map(roadAddressMapper)
    }
  }

  private def roadAddressMapper(roadAddress : RoadAddress) = {
    Map(
      "id" -> roadAddress.id,
      "roadNumber" -> roadAddress.roadNumber,
      "roadPartNumber" -> roadAddress.roadPartNumber,
      "track" -> roadAddress.track,
      "startAddrM" -> roadAddress.startAddrMValue,
      "endAddrM" -> roadAddress.endAddrMValue,
      "linkId" -> roadAddress.linkId,
      "startMValue" -> roadAddress.startMValue,
      "endMValue" -> roadAddress.endMValue,
      "sideCode" -> roadAddress.sideCode.value
    )
  }
}