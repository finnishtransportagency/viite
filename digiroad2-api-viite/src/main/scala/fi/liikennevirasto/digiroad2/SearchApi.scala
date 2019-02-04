package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.Digiroad2Context.roadLinkService
import fi.liikennevirasto.digiroad2.asset.{Modification, TimeStamps}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.{DigiroadSerializers, Track}
import fi.liikennevirasto.viite.RoadAddressService
import fi.liikennevirasto.viite.dao.RoadAddress
import org.json4s.Formats
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{BadRequest, ScalatraServlet}
import org.slf4j.LoggerFactory
import org.scalatra.swagger._


class SearchApi(roadAddressService: RoadAddressService, implicit val swagger: Swagger) extends  ScalatraServlet with JacksonJsonSupport with ViiteAuthenticationSupport with SwaggerSupport {

  val logger = LoggerFactory.getLogger(getClass)
  protected implicit val jsonFormats: Formats = DigiroadSerializers.jsonFormats

  protected val applicationDescription = "The SearchAPI"

  case class AssetTimeStamps(created: Modification, modified: Modification) extends TimeStamps

  def clearCache() = {
    roadLinkService.clearCache()
  }

  before() {
    basicAuth
    contentType = formats("json")
  }

  val getRoadAddressesByLinkIdAndMeasures = (
    apiOperation[Seq[RoadAddress]]("getRoadAddressesByLinkIdAndMeasures").parameters(
      queryParam[String]("linkId").description("Link Id of a road address"),
      queryParam[Seq[String]]("startMeasure").description("Start measure (MValue) of a road address"),
      queryParam[Seq[String]]("endMeasure").description("End measure (MValue) of a road address")
    )
    tags "SearchAPI"
    summary "^Returns a sequence of road addresses that are between the given Start and End Measures"
    notes "If start and end measures are not defined then it will pick up ALL from the link id."
  )

  get("/road_address/?", operation(getRoadAddressesByLinkIdAndMeasures)) {
    val linkId = params.getOrElse("linkId", halt(BadRequest("Missing mandatory field linkId"))).toLong
    val startMeasure = params.get("startMeasure").map(_.toDouble)
    val endMeasure = params.get("endMeasure").map(_.toDouble)

    time(logger, s"GET request for /road_address/? (linkId: $linkId, startMeasure: $startMeasure, endMeasure: $endMeasure)") {
      roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId, startMeasure, endMeasure).map(roadAddressMapper)
    }
  }

  val getAllRoadNumbers = (
    apiOperation("getAllRoadNumbers")
      tags "SearchAPI"
      summary "Returns a sequence of all road numbers"
      notes ""
    )

  get("/road_numbers?", operation(getAllRoadNumbers)) {
    time(logger, "GET request for /road_numbers?") {
      roadAddressService.getRoadNumbers
    }
  }

  val getRoadAddressWithRoadNumber = (
    apiOperation("getRoadAddressWithRoadNumber")
        .parameters(
          pathParam[String]("road").description("This is the road number of a set of road addresses.")
        )
      tags "SearchAPI"
      summary "Returns a sequence of all road addresses that share the given road number."
      notes ""
  )

  get("/road_address/:road/?", operation(getRoadAddressWithRoadNumber)) {
    val roadNumber = params("road").toLong
    time(logger, s"GET request for /road_address/$roadNumber/?") {
      val trackCodes = multiParams.getOrElse("tracks", Seq()).map(_.toInt)
      roadAddressService.getRoadAddressWithRoadNumber(roadNumber, Track.applyAll(trackCodes)).map(roadAddressMapper)
    }
  }

  val getRoadAddressesByPartAndNumber = (
    apiOperation("getRoadAddressesByPartAndNumber").parameters(
      pathParam[String]("road").description("Road number of a sequence of road addresses."),
      pathParam[String]("roadPart").description("Road part number of a sequence of road addresses.")
    )
      tags "SearchAPI"
      summary "Returns a sequence of all road addresses that share the given road number and road part."
      notes ""
  )

  get("/road_address/:road/:roadPart/?", operation(getRoadAddressesByPartAndNumber)) {
    val roadNumber = params("road").toLong
    val roadPart = params("roadPart").toLong
    time(logger, s"GET request for /road_address/$roadNumber/$roadPart/?") {
      roadAddressService.getRoadAddressesFiltered(roadNumber, roadPart).map(roadAddressMapper)
    }
  }

  val getRoadAddressesByPartNumberAndAddressM = (
    apiOperation("getRoadAddressesByPartNumberAndAddressM").parameters(
      pathParam[String]("road").description("Road number of a sequence of road addresses."),
      pathParam[String]("roadPart").description("Road part number of a sequence of road addresses."),
      pathParam[String]("address").description("Address M Value of a sequence of road addresses.")
    )
      tags "SearchAPI"
      summary "Returns a sequence of all road addresses that share the given road number, road part and addressMValue."
      notes ""
    )

  get("/road_address/:road/:roadPart/:address/?", operation(getRoadAddressesByPartNumberAndAddressM)) {
    val roadNumber = params("road").toLong
    val roadPart = params("roadPart").toLong
    val address = params("address").toLong
    val track = params.get("track").map(_.toInt)

    time(logger, s"GET request for /road_address/$roadNumber/$roadPart/$address/? (track: $track)") {
      roadAddressService.getRoadAddress(roadNumber, roadPart, address, Track.applyOption(track)).map(roadAddressMapper)
    }
  }

  val getRoadAddressesByPartNumberStartAddressMValueAndEndAddressMValue = (
    apiOperation("getRoadAddressesByPartNumberStartAddressMValueAndEndAddressMValue").parameters(
      pathParam[String]("road").description("Road number of a sequence of road addresses."),
      pathParam[String]("roadPart").description("Road part number of a sequence of road addresses."),
      pathParam[String]("startAddress").description("Start Address M Value of a sequence of road addresses."),
      pathParam[String]("endAddress").description("End Address M Value of a sequence of road addresses.")
    )
      tags "SearchAPI"
      summary "Returns a sequence of all road addresses that share the given road number, road part and are between the startAddress and endAddress."
      notes ""
    )

  get("/road_address/:road/:roadPart/:startAddress/:endAddress/?", operation(getRoadAddressesByPartNumberStartAddressMValueAndEndAddressMValue)) {
    val roadNumber = params("road").toLong
    val roadPart = params("roadPart").toLong
    val startAddress = params("startAddress").toLong
    val endAddress = params("endAddress").toLong

    time(logger, s"GET request for /road_address/$roadNumber/$roadPart/$startAddress/$endAddress/?") {
      roadAddressService.getRoadAddressesFiltered(roadNumber, roadPart, startAddress, endAddress).map(roadAddressMapper)
    }
  }

  val postRequestGetRoadAddressByLinkIds = (
    apiOperation[Map[String, Any]]("postRequestGetRoadAddressByLinkIds").parameters(
      queryParam[Set[Long]]("linkIds").description("Set of unique linkId's to fetch")
    )
    tags "SearchAPI"
    summary "Returns a sequence of road addresses that share the linkId's that were supplied"
    notes ""
  )

  post("/road_address/?", operation(postRequestGetRoadAddressByLinkIds)) {
    time(logger, s"POST request for /road_address/?") {
      val linkIds = (parsedBody).extract[Set[Long]]
      roadAddressService.getRoadAddressByLinkIds(linkIds).map(roadAddressMapper)
    }
  }

  val postRequestGetRoadAddressByRoadNumberPartsAndTracks = (
    apiOperation[Map[String, Any]]("postRequestGetRoadAddressByRoadNumber")
      .parameters(
        pathParam("road").description("Road Number of the road addresses"),
        queryParam("roadParts").description("Road Number of the road addresses"),
        queryParam("tracks").description("Road Number of the road addresses")
      )
      tags "SearchAPI"
      summary "Returns a sequence of road addresses that share the road number, the part number adn the tracks that are supplied on the request."
      notes ""
  )

  post("/road_address/:road/?", operation(postRequestGetRoadAddressByRoadNumberPartsAndTracks)) {
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
      "sideCode" -> roadAddress.sideCode.value,
      "floating" -> roadAddress.isFloating
    )
  }
}
