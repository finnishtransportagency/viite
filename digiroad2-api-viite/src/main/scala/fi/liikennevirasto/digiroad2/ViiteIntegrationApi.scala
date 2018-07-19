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
import org.scalatra.{BadRequest, ScalatraBase, ScalatraServlet}
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

trait ViiteAuthenticationSupport extends ScentrySupport[BasicAuthUser] with BasicAuthSupport[BasicAuthUser] {
  self: ScalatraBase =>

  val realm = "Viite Integration API"

  protected def fromSession = { case id: String => BasicAuthUser(id)  }
  protected def toSession = { case user: BasicAuthUser => user.username }

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

class ViiteIntegrationApi(val roadAddressService: RoadAddressService, val roadNameService: RoadNameService) extends ScalatraServlet with JacksonJsonSupport with ViiteAuthenticationSupport {
  val logger = LoggerFactory.getLogger(getClass)
  protected implicit val jsonFormats: Formats = DefaultFormats

  case class AssetTimeStamps(created: Modification, modified: Modification) extends TimeStamps

  val dateFormat = DateTimeFormat.forPattern("yyyy-MM-dd")

  def clearCache() = {
    roadLinkService.clearCache()
  }

  before() {
    basicAuth
  }

  def geometryWKT(geometry: Seq[Point], startAddr: Long, endAddr: Long): (String, String) = {
    if (geometry.nonEmpty) {
      val segments = geometry.zip(geometry.tail)
      val factor = (endAddr - startAddr) / GeometryUtils.geometryLength(geometry)
      val runningSum = segments.scanLeft(0.0 + startAddr)((current, points) => current + points._1.distance2DTo(points._2) * factor)
      val mValuedGeometry = geometry.zip(runningSum.toList)
      val wktString = mValuedGeometry.map {
        case (p, newM) => "%.3f %.3f %.3f %.3f".formatLocal(Locale.US, p.x, p.y, p.z, newM)
      }.mkString(", ")
      "geometryWKT" -> ("LINESTRING ZM (" + wktString + ")")
    }
    else
      "geometryWKT" -> ""
  }

  def roadAddressLinksToApi(roadAddressLinks : Seq[RoadAddressLink]): Seq[Map[String, Any]] = {
    roadAddressLinks.map{
      roadAddressLink =>
        Map(
          "muokattu_viimeksi" -> roadAddressLink.modifiedAt.getOrElse(""),
          geometryWKT(
              if(roadAddressLink.sideCode == SideCode.BothDirections || roadAddressLink.sideCode == SideCode.AgainstDigitizing )
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
          "start_date" ->  roadAddressLink.startDate,
          "end_date" ->  roadAddressLink.endDate,
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

  get("/road_address") {
    time(logger, "GET request for /road_address") {
      contentType = formats("json")
      params.get("municipality").map { municipality =>
        val municipalityCode = municipality.toInt
        val knownAddressLinks = roadAddressService.getRoadAddressesLinkByMunicipality(municipalityCode)
          .filter(ral => ral.roadNumber > 0)
        roadAddressLinksToApi(knownAddressLinks)
      } getOrElse {
        BadRequest("Missing mandatory 'municipality' parameter")
      }
    }
  }

  /*
   * Example JSON:
   *
   * [
   *   {
   *     "tie": 1,
   *     "tienimet": [
   *       {
   *         "muutospvm": "2018-03-01",
   *         "tienimi": "HELSINKI-TAMPERE",
   *         "voimassaolo_alku": "2018-02-01",
   *         "voimassaolo_loppu": null
   *       },
   *       {
   *         "muutospvm": "2018-03-01",
   *         "tienimi": "HELSINKI-TAMPERE-OLD",
   *         "voimassaolo_alku": "2010-02-01",
   *         "voimassaolo_loppu": "2018-02-01"
   *       }
   *     ]
   *   },
   *   {
   *     "tie": 2,
   *     "tienimet": [
   *       {
   *         "muutospvm": "2018-03-01",
   *         "tienimi": "HELSINKI-TURKU",
   *         "voimassaolo_alku": "2018-02-01",
   *         "voimassaolo_loppu": null
   *       }
   *     ]
   *   }
   * ]
   *
   */
  get("/tienimi/paivitetyt") {
    contentType = formats("json")
    val muutospvm = params.get("muutospvm")
    time(logger, s"GET request for /tienimi/paivitetyt (muutospvm: $muutospvm)") {
      if (!muutospvm.isDefined || muutospvm.get.isEmpty) {
        val message = "Palvelun '/tienimi/paivitetyt' vaadittu parametri 'muutospvm' puuttuu."
        logger.warn(message)
        BadRequest(message)
      } else {
        try {
          val changesSince = DateTime.parse(muutospvm.get, dateFormat)
          val result = roadNameService.getUpdatedRoadNames(changesSince)
          if (result.isLeft) {
            BadRequest(result.left)
          } else if (result.isRight) {
            result.right.get.groupBy(_.roadNumber).values.map(
              names => Map(
                "tie" -> names.head.roadNumber,
                "tienimet" -> names.map(
                  name => Map(
                    "muutospvm" -> {
                      if (name.validFrom.isDefined) name.validFrom.get.toString("yyyy-MM-dd") else null
                    },
                    "tienimi" -> name.roadName,
                    "voimassaolo_alku" -> {
                      if (name.startDate.isDefined) name.startDate.get.toString("yyyy-MM-dd") else null
                    },
                    "voimassaolo_loppu" -> {
                      if (name.endDate.isDefined) name.endDate.get.toString("yyyy-MM-dd") else null
                    }
                  )
                ))
            )
          } else {
            Seq.empty[Any]
          }
        } catch {
          case e: IllegalArgumentException =>
            val message = "Palvelun '/tienimi/paivitetyt' parametri 'muutospvm' tulee olla muodossa: 'yyyy-MM-dd'."
            logger.warn(message)
            BadRequest(message)
          case e if NonFatal(e) =>
            logger.warn(e.getMessage, e)
            BadRequest(e.getMessage)
        }
      }
    }
  }

}
