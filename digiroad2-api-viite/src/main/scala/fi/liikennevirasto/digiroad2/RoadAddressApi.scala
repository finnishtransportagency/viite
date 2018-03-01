package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.Digiroad2Context.roadLinkService
import fi.liikennevirasto.digiroad2.asset.{Modification, TimeStamps}
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.ScalatraServlet
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory


class RoadAddressApi extends  ScalatraServlet with JacksonJsonSupport with ViiteAuthenticationSupport {
  val logger = LoggerFactory.getLogger(getClass)
  protected implicit val jsonFormats: Formats = DefaultFormats

  case class AssetTimeStamps(created: Modification, modified: Modification) extends TimeStamps

  def clearCache() = {
    roadLinkService.clearCache()
  }

  before() {
    basicAuth
  }

  get("/road_address/roads"){
    Seq(1,2,3,4)
  }

  get("/road_address/:road"){
    params.get("tracks")
  }

  get("/road_address/:road/:roadPart"){
    params.get("tracks")
    //or
    params.get("tracks")
    params.get("startMeasure")
    params.get("endMeasure")
    params.get("withFloatings")
  }

  get("/road_address/:road/:roadPart/:address"){
    params.get("tracks")
  }

  get("/road_address/:road/:roadPart/:startAddress/:endAddress"){
    params.get("tracks")
  }

  get("/road_address/"){
    params.get("linkId")
    params.get("startMeasure")
    params.get("endMeasure")
    params.get("road")
  }
}
