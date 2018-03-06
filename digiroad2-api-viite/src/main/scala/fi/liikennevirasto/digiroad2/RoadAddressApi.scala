package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.Digiroad2Context.roadLinkService
import fi.liikennevirasto.digiroad2.asset.{Modification, TimeStamps}
import fi.liikennevirasto.viite.dao.RoadAddress
import fi.liikennevirasto.digiroad2.util.DigiroadSerializers
import fi.liikennevirasto.viite.RoadAddressService
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.{BadRequest, ScalatraServlet}
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory


class RoadAddressApi(roadAddressService: RoadAddressService) extends  ScalatraServlet with JacksonJsonSupport with ViiteAuthenticationSupport {
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

  get("/road_address/?"){
    val linkId = params.getOrElse("linkId", halt(BadRequest("Missing mandatory field linkId"))).toLong
    val startMeasure = params.get("startMeasure").map(_.toLong)
    val endMeasure = params.get("endMeasure").map(_.toLong)

    roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId, startMeasure, endMeasure).map(roadAddressMapper)
  }

  get("/road_numbers?"){
    roadAddressService.getRoadNumbers()

  }

  get("/road_address/:road/?"){
    val roadNumber = params("road").toLong
    val trackCodes = multiParams.getOrElse("tracks", Seq()).map(_.toInt)

    roadAddressService.getRoadAddressWithRoadNumber(roadNumber, trackCodes).map(roadAddressMapper)
  }

  get("/road_address/:road/:roadPart/?"){
    val roadNumber = params("road").toLong
    val roadPart = params("roadPart").toLong
    val track = params.get("track").map(_.toInt)

    roadAddressService.getRoadAddressesFiltered(roadNumber, roadPart, None, None).map(roadAddressMapper)
  }

  get("/road_address/:road/:roadPart/:address/?"){
    val roadNumber = params("road").toLong
    val roadPart = params("roadPart").toLong
    val address = params("address").toDouble
    val track = params.get("track").map(_.toInt)

    roadAddressService.getRoadAddress(roadNumber, roadPart, track, Some(address)).map(roadAddressMapper)
  }

  get("/road_address/:road/:roadPart/:startAddress/:endAddress/?"){
    val roadNumber = params("road").toLong
    val roadPart = params("roadPart").toLong
    val startAddress = params("startAddress").toDouble
    val endAddress = params("endAddress").toDouble

    roadAddressService.getRoadAddressesFiltered(roadNumber, roadPart, Some(startAddress), Some(endAddress)).map(roadAddressMapper)
  }

  post("/road_address/?") {

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
      "floating" -> roadAddress.floating
    )
  }
}
