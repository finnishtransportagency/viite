package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset.{SideCode, TrafficDirection}
import fi.liikennevirasto.viite.{ChangedRoadAddress, RoadAddressService}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.{BadRequest, ScalatraServlet}
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory
import org.scalatra.swagger._


class ChangeApi(roadAddressService: RoadAddressService, implicit val swagger: Swagger) extends ScalatraServlet with JacksonJsonSupport with AuthenticationSupport with SwaggerSupport  {
  val logger = LoggerFactory.getLogger(getClass)
  val DateTimePropertyFormat = DateTimeFormat.forPattern("dd.MM.yyyy HH:mm:ss")

  protected implicit val jsonFormats: Formats = DefaultFormats
  protected val applicationDescription = "The user interface API "

  before() {
    basicAuth
    contentType = formats("json")
  }

  val roadNumberToGeoJson = (
    apiOperation[Map[String, Any]]("roadNumberToGeoJson")
      .parameters(
        queryParam[String]("since").description("Start date of the road addresses changes"),
        queryParam[String]("until").description("End date of the road addresses changes")
      )
      tags "ChangeAPI"
      summary "This will return all the changes found on the road addresses that are between the period defined by the \"since\" and  \"until\" parameters."
      notes ""
  )

  get("/road_numbers", operation(roadNumberToGeoJson)) {
    contentType = formats("json")
    val since = DateTime.parse(params.get("since").getOrElse(halt(BadRequest("Missing mandatory 'since' parameter"))))
    val until = DateTime.parse(params.get("until").getOrElse(halt(BadRequest("Missing mandatory 'until' parameter"))))

    time(logger, s"GET request for /road_numbers (since: $since, until: $until)") {
      roadNumberToGeoJson(since, roadAddressService.getChanged(since, until))
    }
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

  private def roadNumberToGeoJson(since: DateTime, changedRoadway: Seq[ChangedRoadAddress]) =
    Map(
      "type" -> "FeatureCollection",
      "features" ->
        changedRoadway.map { case ChangedRoadAddress(road, link) =>
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
                "endMeasure" -> road.endMValue,
                "createdBy" -> road.createdBy,
                "modifiedAt" -> road.validFrom.map(DateTimePropertyFormat.print(_)),
                "createdAt" -> road.validFrom.map(DateTimePropertyFormat.print(_)),
                "changeType" -> extractChangeType(since, road.isExpire, road.validFrom)
              )
          )
        }
    )
}
