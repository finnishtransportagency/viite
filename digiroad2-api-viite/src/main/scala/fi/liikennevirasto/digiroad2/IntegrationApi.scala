package fi.liikennevirasto.digiroad2

import java.util.Locale

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.Digiroad2Context._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.RoadAddressLink
import fi.liikennevirasto.viite.{RoadAddressService, RoadNameService}
import org.joda.time.DateTime
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.auth.strategy.BasicAuthSupport
import org.scalatra.auth.{ScentryConfig, ScentrySupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerSupport, SwaggerSupportSyntax}
import org.scalatra.{BadRequest, ScalatraBase}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.control.NonFatal

trait ViiteAuthenticationSupport extends ScentrySupport[BasicAuthUser] with BasicAuthSupport[BasicAuthUser] {
  self: ScalatraBase =>

  val realm = "Viite Integration API"

  val dateFormat = "dd.MM.yyyy"

  protected def fromSession: PartialFunction[String, BasicAuthUser] = {
    case id: String => BasicAuthUser(id)
  }

  protected def toSession: PartialFunction[BasicAuthUser, String] = {
    case user: BasicAuthUser => user.username
  }

  protected val scentryConfig: ScentryConfiguration = new ScentryConfig {}.asInstanceOf[ScentryConfiguration]

  override protected def configureScentry: Unit = {
    scentry.unauthenticated {
      scentry.strategies("Basic").unauthenticated()
    }
  }

  override protected def registerAuthStrategies: Unit = {
    scentry.register("Basic", app => new IntegrationAuthStrategy(app, realm))
  }
}

import org.scalatra.ScalatraServlet

class IntegrationApi(val roadAddressService: RoadAddressService, val roadNameService: RoadNameService, implicit val swagger: Swagger) extends ScalatraServlet
  with JacksonJsonSupport with ViiteAuthenticationSupport with SwaggerSupport {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val applicationDescription = "The integration API "

  protected implicit val jsonFormats: Formats = DefaultFormats

  case class AssetTimeStamps(created: Modification, modified: Modification) extends TimeStamps

  def clearCache(): Int = {
    roadLinkService.clearCache()
  }

  before() {
    basicAuth
  }

  val getRoadAddressesByMunicipality: SwaggerSupportSyntax.OperationBuilder =
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


  val getRoadNameChanges: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getRoadNameChanges")
      tags "Integration (kalpa)"
      summary "Returns all the changes to road names between given dates."
      parameter queryParam[String]("since").description(" Date in format ISO8601. For example 2020-04-29T13:59:59")
      parameter queryParam[String]("until").description("Date in format ISO8601").optional)

  get("/roadnames/changes", operation(getRoadNameChanges)) {
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
          case _: IllegalArgumentException =>
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

  val getRoadwayChanges: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getRoadwayChanges")
      tags "Integration (kalpa)"
      summary "Returns all the changes to roadways after the given date (including the given date)."
      parameter queryParam[String]("since").description("Date in format ISO8601. For example 2020-04-29T13:59:59"))

  get("/roadway/changes", operation(getRoadwayChanges)) {
    contentType = formats("json")
    val sinceUnformatted = params.get("since").getOrElse(halt(BadRequest("Missing mandatory 'since' parameter")))
    time(logger, s"GET request for /roadway/changes (since: $sinceUnformatted)") {
      if (sinceUnformatted == "") {
        val message = "Since parameter is empty"
        logger.warn(message)
        BadRequest(message)
      } else {
        try {
          val since = DateTime.parse(sinceUnformatted)
          val roadways : Seq[Roadway] = fetchUpdatedRoadways(since)
          roadways.map(r => Map(
            "id" -> r.id,
            "roadwayNumber" -> r.roadwayNumber,
            "roadNumber" -> r.roadNumber,
            "roadPartNumber" -> r.roadPartNumber,
            "track" -> r.track.value,
            "startAddrMValue" -> r.startAddrMValue,
            "endAddrMValue" -> r.endAddrMValue,
            "discontinuity" -> r.discontinuity.value,
            "ely" -> r.ely,
            "roadType" -> r.roadType.value,
            "terminated" -> r.terminated.value,
            "reversed" -> r.reversed,
            "roadName" -> r.roadName,
            "startDate" -> formatDate(r.startDate),
            "endDate" -> formatDate(r.endDate),
            "validFrom" -> formatDate(r.validFrom),
            "validTo" -> formatDate(r.validTo),
            "createdBy" -> r.createdBy
          ))
        } catch {
          case _: IllegalArgumentException =>
            val message = "The since parameter of the service should be in the form ISO8601"
            logger.warn(message)
            BadRequest(message)
          case e if NonFatal(e) =>
            logger.warn(e.getMessage, e)
            BadRequest(e.getMessage)
        }
      }
    }
  }

  val getLinearLocationChanges: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getLinearLocationChanges")
      tags "Integration (kalpa)"
      summary "Returns all the changes to roadways after the given date (including the given date)."
      parameter queryParam[String]("since").description("Date in format ISO8601. For example 2020-04-29T13:59:59"))

  get("/linear_location/changes", operation(getLinearLocationChanges)) {
    contentType = formats("json")
    val sinceUnformatted = params.get("since").getOrElse(halt(BadRequest("Missing mandatory 'since' parameter")))
    time(logger, s"GET request for /linear_location/changes (since: $sinceUnformatted)") {
      if (sinceUnformatted == "") {
        val message = "Since parameter is empty"
        logger.warn(message)
        BadRequest(message)
      } else {
        try {
          val since = DateTime.parse(sinceUnformatted)
          val linearLocations: Seq[LinearLocation] = fetchUpdatedLinearLocations(since)
          linearLocations.map(l => Map(
            "id" -> l.id,
            "roadwayNumber" -> l.roadwayNumber,
            "linkId" -> l.linkId,
            "orderNumber" -> l.orderNumber,
            "side" -> l.sideCode.value,
            "linkGeomSource" -> l.linkGeomSource.value,
            "startMValue" -> l.startMValue,
            "endMValue" -> l.endMValue,
            "startCalibrationPoint" -> l.startCalibrationPoint.addrM,
            "endCalibrationPoint" -> l.endCalibrationPoint.addrM,
            "validFrom" -> formatDate(l.validFrom),
            "validTo" -> formatDate(l.validTo),
            "adjustedTimestamp" -> l.adjustedTimestamp
          ))
        } catch {
          case _: IllegalArgumentException =>
            val message = "The since parameter of the service should be in the form ISO8601"
            logger.warn(message)
            BadRequest(message)
          case e if NonFatal(e) =>
            logger.warn(e.getMessage, e)
            BadRequest(e.getMessage)
        }
      }
    }
  }

  val nodesToGeoJson: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("nodesToGeoJson")
      .parameters(
        queryParam[String]("since").description("Start date of nodes. Date in format ISO8601. For example 2020-04-29T13:59:59"),
        queryParam[String]("until").description("End date of the nodes. Date in format ISO8601").optional
      )
      tags "Integration (kalpa)"
      summary "This will return all the changes found on the nodes that are published between the period defined by the \"since\" and  \"until\" parameters."
    )

  get(transformers = "/nodes_junctions/changes", operation(nodesToGeoJson)) {
    contentType = formats("json")
    val since = DateTime.parse(params.get("since").getOrElse(halt(BadRequest("Missing mandatory 'since' parameter"))))
    val untilUnformatted = params.get("until")
    time(logger, s"GET request for /nodesAndJunctions (since: $since, until: $untilUnformatted)") {
      untilUnformatted match {
        case Some(u) => nodesAndJunctionsService.getNodesWithTimeInterval(since, Some(DateTime.parse(u))).map(node => nodeToApi(node))
        case _ => nodesAndJunctionsService.getNodesWithTimeInterval(since, None).map(node => nodeToApi(node))
      }
    }
  }

  def nodeToApi(node: (Option[Node], (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]]))) : Map[String, Any] = {
      simpleNodeToApi(node._1.get) ++
      Map("node_points" -> node._2._1.map(nodePointToApi)) ++
      Map("junctions" -> node._2._2.map(junctionToApi)
    )

  }

  def simpleNodeToApi(node: Node): Map[String, Any] = {
    Map(
      "node_number" -> node.nodeNumber,
      "change_date" -> node.publishedTime.get.toString,
      "x" -> node.coordinates.x,
      "y" -> node.coordinates.y,
      "name" -> node.name,
      "type" -> node.nodeType.value,
      "start_date" -> node.startDate.toString,
      "end_date" -> (if (node.endDate.isDefined) node.endDate.get.toString else null),
      "user" -> node.createdBy
    )
  }

  def nodePointToApi(nodePoint: NodePoint) : Map[String, Any] = {
    Map(
      "before_after" -> nodePoint.beforeAfter.acronym,
      "road" -> nodePoint.roadNumber,
      "road_part" -> nodePoint.roadPartNumber,
      "track" -> nodePoint.track.value,
      "distance" -> nodePoint.addrM,
      "start_date" -> nodePoint.startDate.toString,
      "end_date" -> (if (nodePoint.endDate.isDefined) nodePoint.endDate.get.toString else null),
      "user" -> nodePoint.createdBy
    )
  }

  def junctionToApi(junction: (Junction, Seq[JunctionPoint])): Map[String, Any] = {
    Map(
      "junction_number" -> (if (junction._1.junctionNumber.isDefined) junction._1.junctionNumber.get else null),
      "start_date" -> junction._1.startDate.toString,
      "end_date" -> (if (junction._1.endDate.isDefined) junction._1.endDate.get.toString else null),
      "user" -> junction._1.createdBy,
      "junction_points" -> junction._2.map(junctionPointToApi))
  }

  def junctionPointToApi(junctionPoint: JunctionPoint) : Map[String, Any] = {
    Map(
      "before_after" -> junctionPoint.beforeAfter.acronym,
      "road" -> junctionPoint.roadNumber,
      "road_part" -> junctionPoint.roadPartNumber,
      "track" -> junctionPoint.track.value,
      "distance" -> junctionPoint.addrM,
      "start_date" -> junctionPoint.startDate.toString,
      "end_date" -> (if (junctionPoint.endDate.isDefined) junctionPoint.endDate.get.toString else null),
      "user" -> junctionPoint.createdBy
    )
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
              },
              "created_by" -> name.createdBy
            )
          ))
      )
    } else {
      Seq.empty[Any]
    }
  }

  private def fetchUpdatedRoadways(since: DateTime): Seq[Roadway] = {
    val result = roadAddressService.getUpdatedRoadways(since)
    if (result.isLeft) {
      throw ViiteException(result.left.getOrElse("Error fetching updated roadways."))
    } else if (result.isRight) {
      result.right.get
    } else {
      Seq.empty[Roadway]
    }
  }

  private def fetchUpdatedLinearLocations(since: DateTime): Seq[LinearLocation] = {
    val result = roadAddressService.getUpdatedLinearLocations(since)
    if (result.isLeft) {
      throw ViiteException(result.left.getOrElse("Error fetching updated linear locations."))
    } else if (result.isRight) {
      result.right.get
    } else {
      Seq.empty[LinearLocation]
    }
  }

  def formatDate(date: DateTime): String = {
    date.toString(dateFormat)
  }

  def formatDate(date: Option[DateTime]): Option[String] = {
    if (date.isDefined) {
      Some(date.get.toString(dateFormat))
    } else {
      None
    }
  }

}
