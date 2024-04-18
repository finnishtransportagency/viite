package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.{ChangedRoadAddress, NodesAndJunctionsService, RoadAddressService}
import fi.vaylavirasto.viite.model.{SideCode, TrafficDirection}
import fi.vaylavirasto.viite.util.DateTimeFormatters.finnishDateTimeFormatter
import org.joda.time.DateTime
import org.json4s.{DefaultFormats, Formats}
import org.postgresql.util.PSQLException
import org.scalatra.{BadRequest, InternalServerError, ScalatraServlet}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger._
import org.slf4j.{Logger, LoggerFactory}

import scala.util.control.NonFatal


class ChangeApi(roadAddressService: RoadAddressService, nodesAndJunctionsService: NodesAndJunctionsService, implicit val swagger: Swagger) extends ScalatraServlet with JacksonJsonSupport with SwaggerSupport  {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  private val XApiKeyDescription =
    "You need an API key to use Viite APIs.\n" +
    "Get your API key from the technical system owner (järjestelmävastaava)."
  val dateParamDescription =
    "Date in the ISO8601 date and time format, YYYY-MM[-DD[THH[:mm[:ss]]]], for example: <i>2020-02-20T01:23:45</i>"

  protected implicit val jsonFormats: Formats = DefaultFormats
  protected val applicationDescription = "The user interface API "

  before() {
    contentType = formats("json")
  }

  val roadNumberToGeoJson: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[Map[String, Any]]("roadNumberToGeoJson")
      .parameters(
        queryParam[String]("since").required.description("The earliest moment for the road address changes to be returned.\n" + dateParamDescription),
        queryParam[String]("until").required.description("The latest moment for the road address changes to be returned.\n" + dateParamDescription)
      )
      tags "ChangeAPI (TN-ITS)"
      summary "Returns all the changes made to the road addresses within the given time interval, in the TN-ITS-accepted format."
      parameter headerParam[String]("X-API-Key").description(XApiKeyDescription)
  )

  get("/road_numbers", operation(roadNumberToGeoJson)) {
    contentType = formats("json")

    val sinceUnformatted = params.get("since").getOrElse(halt(BadRequest("Missing mandatory 'since' parameter")))
    val untilUnformatted = params.get("until").getOrElse(halt(BadRequest("Missing mandatory 'until' parameter")))
    try {
      val since = DateTime.parse(sinceUnformatted)
      val until = DateTime.parse(untilUnformatted)
      if (since.compareTo(until) > 0) {
        BadRequestWithLoggerWarn(s"'since' cannot be later than 'until'. ${request.getQueryString}", s"(${request.getRequestURI})")
      }
      else {
        time(logger, s"GET request for /road_numbers", params = Some(params)) {
          roadNumberToGeoJson(since, roadAddressService.getChanged(since, until))
        }
      }
    } catch {
      case iae: IllegalArgumentException =>
        BadRequestWithLoggerWarn(s"Invalid 'since', or/and 'until' parameters. Got ${request.getQueryString} \n" +
          s" ${iae.getMessage}\n $dateParamDescription","")
      case nsee: NoSuchElementException =>
        BadRequestWithLoggerWarn(s"The data asked for has gap(s), result set generation unsuccessful for interval ${request.getQueryString}.",
          s"${nsee.getMessage}")

      case psqle: PSQLException =>
        BadRequestWithLoggerWarn(s"Date out of bounds, check the given dates: ${request.getQueryString}.", s"${psqle.getMessage}")
      case nf if NonFatal(nf) =>
        val requestString = s"GET request for ${request.getRequestURI}?${request.getQueryString} (${roadNumberToGeoJson.operationId})"
        haltWithHTTP500WithLoggerError(requestString, nf)
    }
  }

  private def haltWithHTTP500WithLoggerError(whatWasCalledWhenError: String, throwable: Throwable) = {
    val now = DateTime.now()
    logger.error(s"An unexpected error in '$whatWasCalledWhenError ($now)': $throwable")
    halt(InternalServerError(
      s"You hit an unexpected error. Contact system administrator, or Viite development team.\n" +
      s"Tell them to look for '$whatWasCalledWhenError ($now)'"
    ))
  }

  private def BadRequestWithLoggerWarn(messageFor400: String, extraForLogger: String) = {
    logger.warn(messageFor400 + "  " + extraForLogger)
    BadRequest(messageFor400)
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
                "modifiedAt" -> road.validFrom.map(finnishDateTimeFormatter.print(_)),
                "createdAt" -> road.validFrom.map(finnishDateTimeFormatter.print(_)),
                "changeType" -> extractChangeType(since, road.isExpire, road.validFrom)
              )
          )
        }
    )
}
