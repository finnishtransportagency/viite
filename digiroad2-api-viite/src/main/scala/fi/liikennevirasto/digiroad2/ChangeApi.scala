package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset.{SideCode, TrafficDirection}
import fi.liikennevirasto.viite.{ChangedRoadAddress, RoadAddressService}
import org.joda.time.DateTime
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.{BadRequest, ScalatraServlet}
import org.scalatra.json.JacksonJsonSupport


class ChangeApi(roadAddressService: RoadAddressService) extends ScalatraServlet with JacksonJsonSupport with AuthenticationSupport {
  protected implicit val jsonFormats: Formats = DefaultFormats

  before() {
    basicAuth
    contentType = formats("json")
  }

  get("/road_numbers") {
    contentType = formats("json")
    val since = DateTime.parse(params.get("since").getOrElse(halt(BadRequest("Missing mandatory 'since' parameter"))))
    val until = DateTime.parse(params.get("until").getOrElse(halt(BadRequest("Missing mandatory 'until' parameter"))))

    roadNumberToGeoJson(since, roadAddressService.getChanged(since, until))
  }

  private def extractChangeType(since: DateTime, expired: Boolean, createdDateTime: Option[DateTime]) = {
    if (expired) {
      "Remove"
    } else if (createdDateTime.exists(_.isAfter(since))) {
      "Add"
    } else {
      "Modify"
    }
  }

  private def roadNumberToGeoJson(since: DateTime, changedRoadAddress: Seq[ChangedRoadAddress]) =
    Map(
      "type" -> "FeatureCollection",
      "features" ->
        changedRoadAddress.map { case ChangedRoadAddress(road, link) =>
          Map(
            "type" -> "Feature",
            "id" -> road.id,
            "geometry" -> Map(
              "type" -> "LineString",
              "coordinates" -> road.geometry.map(p => Seq(p.x, p.y, p.z))
            ),
            "properties" ->
              Map(
                "value" -> road.roadNumber,
                "link" -> Map(
                  "type" -> "Feature",
                  "id" -> link.linkId,
                  "geometry" -> Map(
                    "type" -> "LineString",
                    "coordinates" -> link.geometry.map(p => Seq(p.x, p.y, p.z))
                  ),
                  "properties" -> Map(
                    "functionalClass" -> link.functionalClass,
                    "type" -> link.linkType.value,
                    "length" -> link.length
                  )
                ),
                "sideCode" -> (link.trafficDirection match {
                  case TrafficDirection.AgainstDigitizing =>
                    SideCode.AgainstDigitizing.value
                  case TrafficDirection.TowardsDigitizing =>
                    SideCode.TowardsDigitizing.value
                  case _ =>
                    road.sideCode.value
                }),
                "startMeasure" -> road.startMValue,
                "endMeasure" -> road.endMValue/*,
                "createdBy" -> road.createdBy,
                "modifiedAt" -> road.modifiedDate.map(DateTimePropertyFormat.print(_)),
                "createdAt" -> road.createdDate.map(DateTimePropertyFormat.print(_)),
                "changeType" -> extractChangeType(since, road.expired, road.createdDate)*/
              )
          )
        }
    )
}
