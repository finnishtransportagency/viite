package fi.liikennevirasto.digiroad2

import java.util.Locale
import fi.liikennevirasto.digiroad2.Digiroad2Context._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.RoadAddressLink
import fi.liikennevirasto.viite.{RoadAddressService, RoadNameService}
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerSupport, SwaggerSupportSyntax}
import org.scalatra.BadRequest
import org.slf4j.{Logger, LoggerFactory}

import scala.util.control.NonFatal
import org.scalatra.ScalatraServlet

class IntegrationApi(val roadAddressService: RoadAddressService, val roadNameService: RoadNameService, implicit val swagger: Swagger) extends ScalatraServlet
  with JacksonJsonSupport with SwaggerSupport {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  val dateFormat = "dd.MM.yyyy"

  val apiId = "integration-api"

  protected val applicationDescription = "The integration API "

  protected implicit val jsonFormats: Formats = DefaultFormats

  case class AssetTimeStamps(created: Modification, modified: Modification) extends TimeStamps

  val getRoadAddressesByMunicipality: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getRoadAddressesByMunicipality")
      tags "Integration (kalpa, Digiroad, Viitekehysmuunnin, ...)"
      summary "Shows all the road address non floating for a given municipalities."
      parameter queryParam[Int]("municipality").description("The municipality identifier")
      parameter queryParam[String]("situationDate").description("Date in format ISO8601. For example 2020-04-29T13:59:59").optional)

  get("/road_address", operation(getRoadAddressesByMunicipality)) {
    contentType = formats("json")
    ApiUtils.avoidRestrictions(apiId, request, params) { params =>
      params.get("municipality").map { municipality =>
        try {
          val municipalityCode = municipality.toInt
          val searchDate = parseIsoDate(params.get("situationDate"))
          try {
            val knownAddressLinks = roadAddressService.getAllByMunicipality(municipalityCode, searchDate)
              .filter(ral => ral.roadNumber > 0)
            roadAddressLinksToApi(knownAddressLinks)
          } catch {
            case e: Exception =>
              val message = s"Failed to get road addresses for municipality $municipalityCode"
              logger.error(message, e)
              BadRequest(message)
          }
        } catch {
          case nfe: NumberFormatException =>
            val message = s"Incorrectly formatted municipality code: " + nfe.getMessage
            logger.error(message)
            BadRequest(message)
          case iae: IllegalArgumentException =>
            val message = s"Incorrectly formatted date: " + iae.getMessage
            logger.error(message)
            BadRequest(message)
          case e: Exception =>
            val message = s"Invalid municipality code: $municipality"
            logger.error(message)
            BadRequest(message)
        }
      } getOrElse {
        BadRequest("Missing mandatory 'municipality' parameter")
      }
    }
  }


  val getRoadNetworkSummary: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadNetworkSummary")
      tags "Integration (Velho)"
      summary "Returns current state (\"summary\") of the road network addresses, containing all the latest changes " +
      "to every part of any road found in Viite. Offered JSON contains data about: road number, road name, " +
      "road part number, ely code, administrative class, track, start address, end address, and discontinuity."
      parameter queryParam[String]("date").description("Date in format ISO8601. For example 2020-04-29T13:59:59").optional
  )
  /** @return The JSON formatted whole road network address space of the latest versions of the network. */
  get("/summary", operation(getRoadNetworkSummary)) {
    contentType = formats("json")

    time(logger, s"Summary:  GET request for /summary", params=Some(params)) {

        try {
          val roadNetworkSummary = params.get("date") match {
            case Some(date) =>
              val parsedDate = DateTime.parse(date)
              roadAddressService.getRoadwayNetworkSummary(Some(parsedDate))
            case None =>
              roadAddressService.getRoadwayNetworkSummary()

          }
          currentRoadNetworkSummaryToAPI(roadNetworkSummary)
        } catch {
          case _: IllegalArgumentException =>
            val message = "The date parameter should be in the ISO8601 date and time format"
            logger.warn(message)
            BadRequest(message)
          case e if NonFatal(e) =>
            logger.warn(e.getMessage, e)
            BadRequest(e.getMessage)
        }
    }
  }
  /**
    * Formats the given <i>roadNetworkSummary</i> sequence to a structured List[Map[....]], suitable for JSON printout.
    *
    * @version Initial version for /summary API 2022-02
    * @param roadNetworkSummary list of <i>RoadwayNetworkSummaryRow</i>s containing all of the latest network roadway, and roadName info
    * @return Structured list of roads (defined by their road_numbers) of the valid network addresses of the whole road network
    */
  private def currentRoadNetworkSummaryToAPI(roadNetworkSummary: Seq[RoadwayNetworkSummaryRow]): List[Map[String, Any]] = {
    logger.info("Summary: fetchCurrentRoadNetworkSummary")

    val roadnumberMap: Map[Int, Seq[RoadwayNetworkSummaryRow]] = roadNetworkSummary.groupBy(_.roadNumber)

    roadnumberMap.toList.sortBy(_._1).map { // foreach roadnumber, handle the sequence of rows
      case(key_RoadNumber,uniqueRoadnumberMap) => {
        Map(
          "roadnumber" -> key_RoadNumber,
          "roadname" -> uniqueRoadnumberMap.head.roadName, // each row in the road number seq has the same roadName; take any (here: first)
          "roadparts" ->
            parseRoadpartsForSummary( uniqueRoadnumberMap.groupBy(_.roadPartNumber) )
        )
      }
    }
  }
  /**
    * Formats the given <i>uniqueRoadnumberMap</i> map to a structured List[Map[....]], suitable for JSON printout.
    * The rows are grouped primarily by roadPartNumbers, and secondarily by administrativeClasses.
    *
    * @version Initial version for sub functionality of /summary API, 2022-02
    * @param uniqueRoadnumberMap list of <i>RoadwayNetworkSummaryRow</i>s belonging to a single road (defined by a road number)
    * @return List of <i>road part + administrative group</i> defined items containing the valid network addresses of that
    *         part of the road network.
    */
  private def parseRoadpartsForSummary(uniqueRoadnumberMap: Map[Int, Seq[RoadwayNetworkSummaryRow]]): List[Map[String, Any]] = {
    val roadPARTnumberMap: Map[Int, Seq[RoadwayNetworkSummaryRow]] = uniqueRoadnumberMap
    roadPARTnumberMap.toList.sortBy(_._1).flatMap { // foreach roadpartnumber, handle the sequence of rows
      case(key_RoadPARTNumber,uniqueRoadPARTMap) => {

        val admClassWithinRoadPARTMap: Map[Int, Seq[RoadwayNetworkSummaryRow]] = uniqueRoadPARTMap.groupBy(_.administrativeClass)
        admClassWithinRoadPARTMap.toList.sortBy(_._1).map {
          case(key_AdmClassWithinRoadPART,uniqueAdmClassWithinRoadPARTMap) => {
            Map(
              "roadpartnumber" -> key_RoadPARTNumber,
              "ely" -> uniqueAdmClassWithinRoadPARTMap.head.elyCode, // each row in the road part seq has the same roadName; take any (here: first)
              "administrative_class" -> uniqueAdmClassWithinRoadPARTMap.head.administrativeClass, //   -"-    seq has the same adm.class; take any (here: first)
              "tracks" ->
                parseTracksForSummary( uniqueAdmClassWithinRoadPARTMap )
            )
          }
        }
      }
    }
  }
  /**
    * Formats the given <i>uniqueAdmClassWithinRoadPARTMap</i> to a structured List[Map[....]], suitable for JSON printout.
    * The rows are ordered primarily by startAddresses, and secondarily by tracks.
    *
    * @version Updated version with functionality to combine continuous roadways by track for /summary API, 2022-05
    * @param uniqueAdmClassWithinRoadPARTMap list of <i>RoadwayNetworkSummaryRow</i>s belonging to a single administrative class
    *                                        within a road part (defined by a road part number, and administrative class)
    * @return List of <i>start addresses + track</i> defined items containing the valid network addresses of that
    *         part of the road network.
    */
  private def parseTracksForSummary(uniqueAdmClassWithinRoadPARTMap:Seq[RoadwayNetworkSummaryRow]): List[Map[String, Int]] = {
    val addressMMap: Seq[RoadwayNetworkSummaryRow] = uniqueAdmClassWithinRoadPARTMap

    addressMMap.sortBy(_.startAddressM).groupBy(_.track).flatMap {
      case(track, roadwaysWithTrack) => {
        roadwaysWithTrack.foldLeft(Seq[Map[String, Int]]())((combinedRoadways, roadwayTrack) => {
          if (combinedRoadways.isEmpty || combinedRoadways.last.contains("continuity") || combinedRoadways.last("endaddressM") != roadwayTrack.startAddressM) {
            roadwayTrack.continuity match {
              case 5 => combinedRoadways :+ Map("track" -> track, "startaddressM" -> roadwayTrack.startAddressM, "endaddressM" -> roadwayTrack.endAddressM) //continuous
              case _ => combinedRoadways :+ Map("track" -> track, "startaddressM" -> roadwayTrack.startAddressM, "endaddressM" -> roadwayTrack.endAddressM, "continuity" -> roadwayTrack.continuity)
            }
          } else {
            val last = combinedRoadways.last
            roadwayTrack.continuity match {
              case 5 => combinedRoadways.dropRight (1) :+ Map("track" -> track, "startaddressM" -> last ("startaddressM"), "endaddressM" -> roadwayTrack.endAddressM) //continuous
              case _ => combinedRoadways.dropRight (1) :+ Map("track" -> track, "startaddressM" -> last ("startaddressM"), "endaddressM" -> roadwayTrack.endAddressM, "continuity" -> roadwayTrack.continuity)
            }
          }
        })
      }
    }.toList.sortBy(_.get("track")).sortBy(_.get("startaddressM"))
  }

  val getRoadNameChanges: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getRoadNameChanges")
      tags "Integration (kalpa, Digiroad, Viitekehysmuunnin, ...)"
      summary "Returns all the changes to road names between given dates."
      parameter queryParam[String]("since").description(" Date in format ISO8601. For example 2020-04-29T13:59:59")
      parameter queryParam[String]("until").description("Date in format ISO8601").optional)

  get("/roadnames/changes", operation(getRoadNameChanges)) {
    contentType = formats("json")
    val sinceUnformatted = params.get("since").getOrElse(halt(BadRequest("Missing mandatory 'since' parameter")))
    val untilUnformatted = params.get("until")
    time(logger, s"GET request for /roadnames/changes", params=Some(params)) {
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
            val message = "The since / until parameters should be in the ISO8601 date and time format"
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
      tags "Integration (kalpa, Digiroad, Viitekehysmuunnin, ...)"
      summary "Returns all the changes to roadways after the given date (including the given date)."
      parameter queryParam[String]("since").description("Date in format ISO8601. For example 2020-04-29T13:59:59"))

  get("/roadway/changes", operation(getRoadwayChanges)) {
    contentType = formats("json")
    val sinceUnformatted = params.get("since").getOrElse(halt(BadRequest("Missing mandatory 'since' parameter")))
    time(logger, s"GET request for /roadway/changes", params=Some(params)) {
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
            "roadType" -> r.administrativeClass.asRoadTypeValue,
            "administrativeClass" -> r.administrativeClass.value,
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
            val message = "The since parameter should be in the ISO8601 date and time format"
            logger.warn(message)
            BadRequest(message)
          case e if NonFatal(e) =>
            logger.warn(e.getMessage, e)
            BadRequest(e.getMessage)
        }
      }
    }
  }

  val getRoadwayChangesChanges: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getRoadwayChangesChanges")
      .parameters(
        queryParam[String]("since")
          .description("Restricts the returned changes to the ones that have been saved to Viite at this timestamp or later. \n" +
            "Date in the ISO8601 date and time format, for example: <i>2020-04-29T13:59:59</i>"),
        queryParam[String]("until")
          .description("(Optional) Restricts the returned changes to the ones that have been saved to Viite at this timestamp or earlier. \n" +
            "Date in the ISO8601 date and time format.")
          .optional
      )
      tags "Integration (kalpa, Digiroad, Velho, Viitekehysmuunnin, ...)"
      summary "Returns the Roadway_change changes after the <i>since</> timestamp.\n" +
      "2021-10: Change within the return value: 'muutospaiva' -> 'voimaantulopaiva'."
      )

  get("/roadway_changes/changes", operation(getRoadwayChangesChanges)) {
    contentType = formats("json")
    try {
      val since = parseIsoDate(params.get("since"))
      val until = parseIsoDate(params.get("until"))
      time(logger, s"GET request for /roadway_changes/changes", params=Some(params)) {
        roadwayChangesToApi(roadAddressService.fetchUpdatedRoadwayChanges(since.get, until))
      }
    } catch {
      case error: IllegalArgumentException =>
        val message = "The since / until parameters should be in the ISO8601 date and time format. " + error.getMessage
        logger.warn(message,error)
        BadRequest(message)
      case error: Exception  =>
        logger.error(error.getMessage,error)
        BadRequest(error.getMessage)
    }
  }

  private def roadwayChangesToApi(roadwayChangesInfos: Seq[RoadwayChangesInfo]) =
    Map(
      "muutos_tieto" ->
        roadwayChangesInfos.map { roadwayChangesInfo =>
          Map(
            "muutostunniste" -> roadwayChangesInfo.roadwayChangeId,
            "voimaantulopaiva" -> formatDateTimeToIsoString(Option(roadwayChangesInfo.startDate)),
            "projektin_hyvaksymispaiva" -> formatDateTimeToIsoString(Option(roadwayChangesInfo.acceptedDate)),
            "muutostyyppi" -> roadwayChangesInfo.change_type,
            "kaannetty" -> roadwayChangesInfo.reversed,
            "lahde" ->
              Map(
                "tie" -> roadwayChangesInfo.old_road_number,
                "osa" -> roadwayChangesInfo.old_road_part_number,
                "ajorata" -> roadwayChangesInfo.old_TRACK,
                "etaisyys" -> roadwayChangesInfo.old_start_addr_m,
                "etaisyys_loppu" -> roadwayChangesInfo.old_end_addr_m,
                "jatkuvuuskoodi" -> roadwayChangesInfo.old_discontinuity,
                "tietyyppi" -> AdministrativeClass.apply(roadwayChangesInfo.old_administrative_class).asRoadTypeValue,
                "hallinnollinen_luokka" -> roadwayChangesInfo.old_administrative_class,
                "ely" -> roadwayChangesInfo.old_ely
              ),
            "kohde" ->
              Map(
                "tie" -> roadwayChangesInfo.new_road_number,
                "osa" -> roadwayChangesInfo.new_road_part_number,
                "ajorata" -> roadwayChangesInfo.new_TRACK,
                "etaisyys" -> roadwayChangesInfo.new_start_addr_m,
                "etaisyys_loppu" -> roadwayChangesInfo.new_end_addr_m,
                "jatkuvuuskoodi" -> roadwayChangesInfo.new_discontinuity,
                "tietyyppi" -> AdministrativeClass.apply(roadwayChangesInfo.new_administrative_class).asRoadTypeValue,
                "hallinnollinen_luokka" -> roadwayChangesInfo.new_administrative_class,
                "ely" -> roadwayChangesInfo.new_ely
              )
          )
        }
    )

  val getLinearLocationChanges: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getLinearLocationChanges")
      tags "Integration (kalpa, Digiroad, Viitekehysmuunnin, ...)"
      summary "Returns all the changes to roadways after the given date (including the given date)."
      parameter queryParam[String]("since").description("Date in format ISO8601. For example 2020-04-29T13:59:59"))

  get("/linear_location/changes", operation(getLinearLocationChanges)) {
    contentType = formats("json")
    val sinceUnformatted = params.get("since").getOrElse(halt(BadRequest("Missing mandatory 'since' parameter")))
    time(logger, s"GET request for /linear_location/changes", params=Some(params)) {
      if (sinceUnformatted == "") {
        val message = "Since parameter is empty"
        logger.warn(message)
        BadRequest(message)
      } else {
        try {
          val since = DateTime.parse(sinceUnformatted)
          val linearLocations: Seq[LinearLocation] = fetchUpdatedLinearLocations(since)

          val roadaddresses: Seq[RoadAddress] = PostGISDatabase.withDynTransaction {
            roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocations.filter(_.validTo.isEmpty))
          }

          val addrValuesMap: scala.collection.mutable.Map[Long,(Long, Long)] = scala.collection.mutable.Map()
          roadaddresses.foreach(r => addrValuesMap += (r.linearLocationId -> (r.startAddrMValue, r.endAddrMValue)))
          logger.info("linear locations size {}, roadaddresses size {}", linearLocations.size, roadaddresses.size)

          linearLocations.map(l => Map(
            "id" -> l.id,
            "roadwayNumber" -> l.roadwayNumber,
            "linkId" -> l.linkId,
            "orderNumber" -> l.orderNumber,
            "side" -> l.sideCode.value,
            "linkGeomSource" -> l.linkGeomSource.value,
            "startAddrValue" -> addrValuesMap.getOrElse(l.id, (None, None))._1,
            "endAddrValue" -> addrValuesMap.getOrElse(l.id, (None, None))._2,
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
            val message = "The since parameter should be in the ISO8601 date and time format"
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
      tags "Integration (kalpa, Digiroad, Viitekehysmuunnin, ...)"
      summary "This will return all the changes found on the nodes that are published between the period defined by the \"since\" and  \"until\" parameters."
    )

  get(transformers = "/nodes_junctions/changes", operation(nodesToGeoJson)) {
    contentType = formats("json")
    val since = DateTime.parse(params.get("since").getOrElse(halt(BadRequest("Missing mandatory 'since' parameter"))))
    val untilUnformatted = params.get("until")
    time(logger, s"GET request for /nodesAndJunctions", params=Some(params)) {
      untilUnformatted match {
        case Some(u) => nodesAndJunctionsService.getNodesWithTimeInterval(since, Some(DateTime.parse(u))).map(node => nodeToApi(node))
        case _ => nodesAndJunctionsService.getNodesWithTimeInterval(since, None).map(node => nodeToApi(node))
      }
    }
  }

  def nodeToApi(node: (Option[Node], (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]]))): Map[String, Any] = {
    simpleNodeToApi(node._1.get) ++ {
      if (node._1.get.endDate.isEmpty) {
        Map("node_points" -> node._2._1.map(nodePointToApi)) ++
          Map("junctions" -> node._2._2.map(junctionToApi))
      } else Map.empty[String, Any]
    }
  }

  def simpleNodeToApi(node: Node): Map[String, Any] = {
    Map(
      "node_number" -> node.nodeNumber,
      "change_date" -> node.registrationDate.toString, // TODO: change_date should be changed to registration_date
      "published_date" -> (if (node.publishedTime.isDefined) node.publishedTime.get.toString else null),
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
      "start_date" -> (if (nodePoint.startDate.isDefined) nodePoint.startDate.get.toString else null),
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
      "start_date" -> (if (junctionPoint.startDate.isDefined) junctionPoint.startDate.get.toString else null),
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
          "road_type" -> roadAddressLink.administrativeClass.asRoadTypeValue,
          "administrative_class" -> roadAddressLink.administrativeClass.value,
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

  private def formatDateTimeToIsoString(dateOption: Option[DateTime]): Option[String] =
  dateOption.map { date => ISODateTimeFormat.dateTimeNoMillis().print(date) }

  private def formatDateTimeToIsoUtcString(dateOption: Option[DateTime]): Option[String] =
    dateOption.map { date => ISODateTimeFormat.dateTimeNoMillis().print(date.withZone(DateTimeZone.UTC)) }

  private def parseIsoDate(dateString: Option[String]): Option[DateTime] = {
    var dateTime = None: Option[DateTime]
    if (dateString.nonEmpty) {
      try {
        dateTime = Option(ISODateTimeFormat.dateTime.parseDateTime(dateString.get))
      } catch {
        case _: Exception =>
          try {
            dateTime = Option(ISODateTimeFormat.dateTimeNoMillis().parseDateTime(dateString.get))
          } catch {
            case _: Exception =>
                dateTime = Option(DateTime.parse(dateString.get))
          }
      }
    }
    dateTime
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
