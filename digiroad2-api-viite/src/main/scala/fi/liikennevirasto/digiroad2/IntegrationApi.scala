package fi.liikennevirasto.digiroad2

import java.util.Locale

import fi.liikennevirasto.digiroad2.Digiroad2Context._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.dao.CalibrationPoint
import fi.liikennevirasto.viite.model.RoadAddressLink
import fi.liikennevirasto.viite.{RoadAddressService, RoadNameService}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.auth.strategy.BasicAuthSupport
import org.scalatra.auth.{ScentryConfig, ScentrySupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerSupport}
import org.scalatra.{BadRequest, ScalatraBase, ScalatraServlet}
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

trait ViiteAuthenticationSupport extends ScentrySupport[BasicAuthUser] with BasicAuthSupport[BasicAuthUser] {
  self: ScalatraBase =>

  val realm = "Viite Integration API"

  protected def fromSession = {
    case id: String => BasicAuthUser(id)
  }

  protected def toSession = {
    case user: BasicAuthUser => user.username
  }

  protected val scentryConfig = (new ScentryConfig {}).asInstanceOf[ScentryConfiguration]

  override protected def configureScentry = {
    scentry.unauthenticated {
      scentry.strategies("Basic").unauthenticated()
    }
  }

  override protected def registerAuthStrategies = {
    scentry.register("Basic", app => new IntegrationAuthStrategy(app, realm))
  }
}

import org.scalatra.ScalatraServlet

class IntegrationApi(val roadAddressService: RoadAddressService, val roadNameService: RoadNameService, implicit val swagger: Swagger) extends ScalatraServlet
  with JacksonJsonSupport with ViiteAuthenticationSupport with SwaggerSupport {

  val logger = LoggerFactory.getLogger(getClass)

  protected val applicationDescription = "The integration API "

  protected implicit val jsonFormats: Formats = DefaultFormats

  case class AssetTimeStamps(created: Modification, modified: Modification) extends TimeStamps

  def clearCache() = {
    roadLinkService.clearCache()
  }

  before() {
    basicAuth
  }

  val getRoadAddressesByMunicipality =
    (apiOperation[List[Map[String, Any]]]("getRoadAddressesByMunicipality")
      tags "Integration (kalpa)"
      summary "Shows all the road address non floating for a given municipalities."
      parameter queryParam[Int]("municipality").description("The municipality identifier"))

  get("/road_address", operation(getRoadAddressesByMunicipality)) {
    time(logger, "GET request for /road_address") {
      contentType = formats("json")
      params.get("municipality").map { municipality =>
        try {
          val municipalityCode = municipality.toInt
          try {
            val knownAddressLinks = roadAddressService.getAllByMunicipality(municipalityCode)
              .filter(ral => ral.roadNumber > 0)
            roadAddressLinksToApi(knownAddressLinks)
          } catch {
            case e: Exception =>
              val message = s"Failed to get road addresses for municipality $municipalityCode"
              logger.error(message, e)
              BadRequest(message)
          }
        } catch {
          case _: Exception =>
            val message = s"Invalid municipality code: $municipality"
            logger.error(message)
            BadRequest(message)
        }
      } getOrElse {
        BadRequest("Missing mandatory 'municipality' parameter")
      }
    }
  }

  get("/roadnames/changes") {
    contentType = formats("json")
    val sinceUnformatted = params.get("since").getOrElse(halt(BadRequest("Missing mandatory 'since' parameter")))
    val untilUnformatted = params.get("until")
    time(logger, s"GET request for /roadnames/changes (since: $sinceUnformatted; until: $untilUnformatted)") {
      if (sinceUnformatted == "") {
        val message = "Since parameter is empty"
        logger.warn(message)
        BadRequest(message)
      } else {
        try {
          val since = DateTime.parse(sinceUnformatted)
          fetchUpdatedRoadNames(since, untilUnformatted)
        } catch {
          case e: IllegalArgumentException =>
            val message = "The since /until parameter of the service should be in the form ISO8601"
            logger.warn(message)
            BadRequest(message)
          case e if NonFatal(e) =>
            logger.warn(e.getMessage, e)
            BadRequest(e.getMessage)
        }
      }
    }
  }

  def geometryWKT(geometry: Seq[Point], startAddr: Long, endAddr: Long): (String, String) = {
    if (geometry.nonEmpty) {
      val segments = geometry.zip(geometry.tail)
      val factor = (endAddr - startAddr) / GeometryUtils.geometryLength(geometry)
      val runningSum: Seq[Double] = segments.scanLeft(0.0 + startAddr)((current, points) => current + points._1.distance2DTo(points._2) * factor)
      val runningSumLastAdjusted = runningSum.init :+ endAddr.toDouble
      val mValuedGeometry = geometry.zip(runningSumLastAdjusted.toList)
      val wktString = mValuedGeometry.map {
        case (p, newM) => "%.3f %.3f %.3f %.3f".formatLocal(Locale.US, p.x, p.y, p.z, newM)
      }.mkString(", ")
      "geometryWKT" -> ("LINESTRING ZM (" + wktString + ")")
    }
    else
      "geometryWKT" -> ""
  }

  // TODO Should we add the roadway_id also here?
  def roadAddressLinksToApi(roadAddressLinks: Seq[RoadAddressLink]): Seq[Map[String, Any]] = {
    roadAddressLinks.map {
      roadAddressLink =>
        Map(
          "muokattu_viimeksi" -> roadAddressLink.modifiedAt.getOrElse(""),
          geometryWKT(
            if (roadAddressLink.sideCode == SideCode.BothDirections || roadAddressLink.sideCode == SideCode.AgainstDigitizing)
              roadAddressLink.geometry.reverse
            else
              roadAddressLink.geometry
            , roadAddressLink.startAddressM, roadAddressLink.endAddressM),
          "id" -> roadAddressLink.id,
          "link_id" -> roadAddressLink.linkId,
          "link_source" -> roadAddressLink.roadLinkSource.value,
          "road_number" -> roadAddressLink.roadNumber,
          "road_part_number" -> roadAddressLink.roadPartNumber,
          "track_code" -> roadAddressLink.trackCode,
          "side_code" -> roadAddressLink.sideCode.value,
          "start_addr_m" -> roadAddressLink.startAddressM,
          "end_addr_m" -> roadAddressLink.endAddressM,
          "ely_code" -> roadAddressLink.elyCode,
          "road_type" -> roadAddressLink.roadType.value,
          "discontinuity" -> roadAddressLink.discontinuity,
          "start_date" -> roadAddressLink.startDate,
          "end_date" -> roadAddressLink.endDate,
          "calibration_points" -> calibrationPoint(roadAddressLink.startCalibrationPoint, roadAddressLink.endCalibrationPoint)
        )
    }
  }

  private def calibrationPoint(startCalibrationPoint: Option[CalibrationPoint], endCalibrationPoint: Option[CalibrationPoint]) = {
    def calibrationPointMapper(calibrationPoint: Option[CalibrationPoint]) = {
      calibrationPoint.map(cp => Map("link_id" -> cp.linkId, "address_m_value" -> cp.addressMValue, "segment_m_value" -> cp.segmentMValue))
    }

    Map(
      "start" -> calibrationPointMapper(startCalibrationPoint),
      "end" -> calibrationPointMapper(endCalibrationPoint)
    )
  }

  private def fetchUpdatedRoadNames(since: DateTime, untilUnformatted: Option[String] = Option.empty[String]) = {
    val result = untilUnformatted match {
      case Some(until) => roadNameService.getUpdatedRoadNames(since, Some(DateTime.parse(until)))
      case _ => roadNameService.getUpdatedRoadNames(since, None)
    }
    if (result.isLeft) {
      BadRequest(result.left)
    } else if (result.isRight) {
      result.right.get.groupBy(_.roadNumber).values.map(
        names => Map(
          "road_number" -> names.head.roadNumber,
          "names" -> names.map(
            name => Map(
              "change_date" -> {
                if (name.validFrom.isDefined) name.validFrom.get.toString else null
              },
              "road_name" -> name.roadName,
              "start_date" -> {
                if (name.startDate.isDefined) name.startDate.get.toString else null
              },
              "end_date" -> {
                if (name.endDate.isDefined) name.endDate.get.toString else null
              }
            )
          ))
      )
    } else {
      Seq.empty[Any]
    }
  }

}
