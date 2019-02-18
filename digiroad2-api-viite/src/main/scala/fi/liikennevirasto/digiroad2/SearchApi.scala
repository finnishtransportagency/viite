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


class SearchApi(roadAddressService: RoadAddressService) extends  ScalatraServlet with JacksonJsonSupport with ViiteAuthenticationSupport {
  val logger = LoggerFactory.getLogger(getClass)
  protected implicit val jsonFormats: Formats = DigiroadSerializers.jsonFormats

  case class AssetTimeStamps(created: Modification, modified: Modification) extends TimeStamps

  def clearCache() = {
    roadLinkService.clearCache()
  }

  before() {
    basicAuth
    contentType = formats("json")
  }

  get("/road_address/?") {
    val linkId = params.getOrElse("linkId", halt(BadRequest("Missing mandatory field linkId"))).toLong
    val startMeasure = params.get("startMeasure").map(_.toDouble)
    val endMeasure = params.get("endMeasure").map(_.toDouble)

    time(logger, s"GET request for /road_address/? (linkId: $linkId, startMeasure: $startMeasure, endMeasure: $endMeasure)") {
      roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId, startMeasure, endMeasure).map(roadAddressMapper)
    }
  }

  get("/road_numbers?") {
    time(logger, "GET request for /road_numbers?") {
      roadAddressService.getRoadNumbers
    }
  }

  get("/road_address/:road/?") {
    val roadNumber = params("road").toLong
    time(logger, s"GET request for /road_address/$roadNumber/?") {
      val trackCodes = multiParams.getOrElse("tracks", Seq()).map(_.toInt)
      roadAddressService.getRoadAddressWithRoadNumber(roadNumber, Track.applyAll(trackCodes)).map(roadAddressMapper)
    }
  }

  get("/road_address/:road/:roadPart/?") {
    val roadNumber = params("road").toLong
    val roadPart = params("roadPart").toLong
    time(logger, s"GET request for /road_address/$roadNumber/$roadPart/?") {
      roadAddressService.getRoadAddressesFiltered(roadNumber, roadPart).map(roadAddressMapper)
    }
  }

  get("/road_address/:road/:roadPart/:address/?") {
    val roadNumber = params("road").toLong
    val roadPart = params("roadPart").toLong
    val address = params("address").toLong
    val track = params.get("track").map(_.toInt)

    time(logger, s"GET request for /road_address/$roadNumber/$roadPart/$address/? (track: $track)") {
      roadAddressService.getRoadAddress(roadNumber, roadPart, address, Track.applyOption(track)).map(roadAddressMapper)
    }
  }

  get("/road_address/:road/:roadPart/:startAddress/:endAddress/?") {
    val roadNumber = params("road").toLong
    val roadPart = params("roadPart").toLong
    val startAddress = params("startAddress").toLong
    val endAddress = params("endAddress").toLong

    time(logger, s"GET request for /road_address/$roadNumber/$roadPart/$startAddress/$endAddress/?") {
      roadAddressService.getRoadAddressesFiltered(roadNumber, roadPart, startAddress, endAddress).map(roadAddressMapper)
    }
  }

  post("/road_address/?") {
    time(logger, s"POST request for /road_address/?") {
      val linkIds = parsedBody.extract[Set[Long]]
      roadAddressService.getRoadAddressByLinkIds(linkIds).map(roadAddressMapper)
    }
  }

  post("/road_address/:road/?") {
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
