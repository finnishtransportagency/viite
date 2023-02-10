package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset.{Modification, TimeStamps}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.util.DigiroadSerializers
import fi.liikennevirasto.viite.RoadAddressService
import fi.liikennevirasto.viite.dao.RoadAddress
import org.json4s.Formats
import org.scalatra.{BadRequest, ScalatraServlet}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, _}
import org.slf4j.{Logger, LoggerFactory}


class SearchApi(roadAddressService: RoadAddressService,
                implicit val swagger: Swagger)
  extends ScalatraServlet
    with JacksonJsonSupport
    with SwaggerSupport {
  protected val applicationDescription = "The Search API "
  protected val XApiKeyDescription =
    "You need an API key to use Viite APIs. " +
    "Get your API key from the responsible system owner (järjestelmävastaava)."

  val logger: Logger = LoggerFactory.getLogger(getClass)
  protected implicit val jsonFormats: Formats = DigiroadSerializers.jsonFormats

  case class AssetTimeStamps(created: Modification, modified: Modification) extends TimeStamps

  before() {
    contentType = formats("json")
  }

  private val getRoadAddress: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getRoadAddress")
      .parameters(
        headerParam[String]("X-API-Key").description(XApiKeyDescription),
        queryParam[String]("linkId").description("LinkId of a road address"),
        queryParam[Double]("startMeasure").description("startMeasure of a road address").optional,
        queryParam[Double]("endMeasure").description("endMeasure of a road address").optional
      )
      tags "SearchAPI (oth)"
      )
      summary "Returns all the road addresses for the given single link. Return values are listed as linear locations. Linear locations can optionally be restricted by the link's measure values."
      description "Returns the road addresses of the given link, listed as linear locations." +
                  "Linear locations may be restricted by giving <i>startMeasure</i>, and/or <i>endMeasure</i>. " +
                  "A linear location must belong to the measure interval at least in one point, to be included in the returned results."
    )

  get("/road_address/?", operation(getRoadAddress)) {
    val linkId = params.getOrElse("linkId", halt(BadRequest("Missing mandatory field linkId"))).toString
    val startMeasure = params.get("startMeasure").map(_.toDouble)
    val endMeasure = params.get("endMeasure").map(_.toDouble)

    time(logger, s"GET request for /road_address/?", params=Some(params)) {
      roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId, startMeasure, endMeasure).map(roadAddressMapper)
    }
  }

  private val getRoadNumbers: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[Seq[Long]]("getRoadNumbers")
      tags "SearchAPI (oth)"
      summary "Returns all the existing road numbers at the current Viite road network."
      description "Returns List of all the existing road numbers at the current Viite road network." +
              "The Viite current network may contain roadway number changes that will be in effect only in the future."
      parameter headerParam[String]("X-API-Key").description(XApiKeyDescription)
    )

  get("/road_numbers?", operation(getRoadNumbers)) {
    time(logger, "GET request for /road_numbers?") {
      roadAddressService.getRoadNumbers
    }
  }

  private val getRoadAddressWithRoadNumber: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressWithRoadNumber")
      .parameters(
        headerParam[String]("X-API-Key").description(XApiKeyDescription),
        pathParam[Long]("road").description("Road Number of a road address"),
        queryParam[Long]("tracks").description("Track Number (0,1,2) tracks=1&tracks=2 returns both left and right track").optional
      )
      tags "SearchAPI (oth)"
      summary "Returns the road addresses within the given road number, returned as linear location sized parts.\n" +
              "If track parameter given, the results are filtered to those tracks."
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
        headerParam[String]("X-API-Key").description(XApiKeyDescription),
        pathParam[Long]("road").description("Road Number of a road address"),
        pathParam[Long]("roadPart").description("Road Part Number of a road address")
      )
      tags "SearchAPI (oth)"
      summary "Returns all the road addresses within the given road part, returned as linear location sized parts."
      description "Returns all the road addresses within the given road part (defined by road and road part numbers), " +
                  "returned as linear location sized parts."
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
        headerParam[String]("X-API-Key").description(XApiKeyDescription),
        pathParam[Long]("road").description("Road Number of a road address"),
        pathParam[Long]("roadPart").description("Road Part Number of a road address"),
        pathParam[Long]("address").description("Road Measure of a road address"),
        pathParam[Long]("track").description("Road Track of a road address. Optional")
      )
      tags "SearchAPI (oth)"
      summary "Returns the road addresses within the given road part, returned as linear location sized parts.\n" +
              "Minimum address value must be given, and the results are filterable by track."
      description "Returns the road addresses within the given road number, road part number, and bigger than address value, " +
                  "returned as linear location sized parts. Also filterable by track."
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
        headerParam[String]("X-API-Key").description(XApiKeyDescription),
        pathParam[Long]("road").description("Road Number of a road address"),
        pathParam[Long]("roadPart").description("Road Part Number of a road address"),
        pathParam[Long]("startAddress").description("Road start measure of a road address"),
        pathParam[Long]("endAddress").description("Road end measure of a road address")
      )
      tags "SearchAPI (oth)"
      summary "Returns the road addresses within the given road number, road part number, and between given address values," +
      "returned as linear location sized parts."
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
        headerParam[String]("X-API-Key").description(XApiKeyDescription),
        bodyParam[Set[String]]("linkIds").description("List of LinkIds\r\n")
      )
      tags "SearchAPI (oth)"
      summary "Returns all the road addresses for the given links. Return values are listed as linear locations."
    )

  post("/road_address/?", operation(getRoadAddressByLinkIds)) {
    time(logger, s"POST request for /road_address/?", params=Some(Map("requestBody" -> request.body))) {
      val linkIds = parsedBody.extract[Set[String]]
      roadAddressService.getRoadAddressByLinkIds(linkIds).map(roadAddressMapper)
    }
  }

  private val getRoadAddressWithRoadNumberParts: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressWithRoadNumberParts")
      .parameters(
        headerParam[String]("X-API-Key").description(XApiKeyDescription),
        pathParam[Long]("road").description("Road Number of a road address"),
        bodyParam[Any]("getLists").description("List of roadParts and List of tracks\r\n")
      )
      tags "SearchAPI (oth)"
      summary "Returns the road addresses within the given road number, returned as linear location sized parts.\n" +
              "If road parts, and/or tracks are given, the results are filtered to those road parts, and/or track numbers."
    )

  post("/road_address/:road/?", operation(getRoadAddressWithRoadNumberParts)) {
    time(logger, s"POST request for /road_address/:road/?", params=Some(params + ("requestBody" -> request.body))){
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
