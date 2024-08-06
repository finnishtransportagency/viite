package fi.liikennevirasto.digiroad2

import java.util.Locale
import fi.liikennevirasto.digiroad2.Digiroad2Context._
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.RoadAddressLink
import fi.liikennevirasto.viite.{RoadAddressService, RoadNameService}
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{AdministrativeClass, SideCode}
import fi.vaylavirasto.viite.postgis.PostGISDatabase
import fi.vaylavirasto.viite.util.DateTimeFormatters.dateTimeNoMillisFormatter
import fi.vaylavirasto.viite.util.ViiteException
import org.joda.time.DateTime
import org.json4s.{DefaultFormats, Formats}
import org.postgresql.util.PSQLException
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerSupport, SwaggerSupportSyntax}
import org.scalatra.{BadRequest, InternalServerError, Params, ScalatraServlet}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

class IntegrationApi(val roadAddressService: RoadAddressService, val roadNameService: RoadNameService, implicit val swagger: Swagger) extends ScalatraServlet
  with JacksonJsonSupport with SwaggerSupport {

  val logger: Logger = LoggerFactory.getLogger(getClass)

  val dateFormat = "dd.MM.yyyy"

  val apiId = "integration-api"

  private val XApiKeyDescription =
    "You need an API key to use Viite APIs.\n" +
    "Get your API key from the technical system owner (järjestelmävastaava)."
  val ISOdateTimeDescription =
    "Date in ISO8601 dateTime format, 'YYYY[-MM[-DD]][THH[:mm[:ss[.sss]]][Z]]' (e.g. 2025-10-23, or 2025-01-23T12:34:56.789Z)"

  protected val applicationDescription = "The integration API "

  protected implicit val jsonFormats: Formats = DefaultFormats

  val getRoadAddress: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getRoadAddress")
      tags "Integration (kalpa, Digiroad, Viitekehysmuunnin, ...)"
      summary "Returns all the road addresses of the municipality stated as the municipality parameter.\n"
      description "Returns all the road addresses of the queried <i>municipality</i>.\n" +
              "Returns the newest information possible (may contain partially future addresses) by default if <i>situationDate</i> is omitted, " +
              "or the road address network valid at <i>situationDate</i>, when <i>situationDate</i> is given.\n" +
              "Uses HTTP redirects for the heavier queries, to address some timeout issues."
      parameter headerParam[String]("X-API-Key").required.description(XApiKeyDescription)
      parameter queryParam[Int]("municipality").required
        .description("The municipality identifier.\nFor the list, see https://www2.tilastokeskus.fi/fi/luokitukset/kunta/.")
      parameter queryParam[String]("situationDate").optional
        .description("(Optional) The road address information is returned from this exact moment (instead of the newest data).\n" + ISOdateTimeDescription)
    )
  /** TODO better name e.g. "road_addresses_of_municipality" */
  get("/road_address", operation(getRoadAddress)) {
    contentType = formats("json")

    ApiUtils.avoidRestrictions(apiId, request, params) { params =>
      params.get("municipality").map { municipality =>
        try {
          val municipalityCode = municipality.toInt
          val searchDate: Option[DateTime] =
            if (params.get("situationDate").isDefined) {
              //Some(DateTime.parse(params.get("situationDate").getOrElse("!!!!"))) // Existence checked -> should never go to else
              Some(dateParameterGetValidOrThrow("situationDate", Some(params)))
            } else {
              None
            }

          try {
            val knownAddressLinks = roadAddressService.getAllByMunicipality(municipalityCode, searchDate)
              .filter(ral => ral.roadPart.roadNumber > 0)
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
            val message = e.getMessage
            logger.error(message)
            BadRequest(message)
        }
      } getOrElse {
        BadRequest("Missing mandatory 'municipality' parameter")
      }
    }
  }

  val getRoadAddressesByMunicipality: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getRoadAddressesByMunicipality")
      tags "Integration (kalpa, Digiroad, Viitekehysmuunnin, ...)"
      summary "Experimental BULLETPROOFED VERSION of get(\"/road_address). Returns all the road addresses of the municipality stated as the municipality parameter.\n"
      description "Returns all the road addresses of the queried <i>municipality</i>.\n" +
              "Returns the newest information possible (may contain partially future addresses) by default if <i>situationDate</i> is omitted, " +
              "or the road address network valid at <i>situationDate</i>, when <i>situationDate</i> is given.\n" +
              "Uses HTTP redirects for the heavier queries, to address some timeout issues."
      parameter headerParam[String]("X-API-Key").required.description(XApiKeyDescription)
      parameter queryParam[Int]("municipality").required
        .description("The municipality identifier.\nFor the list, see https://www2.tilastokeskus.fi/fi/luokitukset/kunta/.")
      parameter queryParam[String]("situationDate").optional
        .description("(Optional) The road address information is returned from this exact moment (instead of the newest data).\n" + ISOdateTimeDescription)
    )
  /** BULLETPROOFED VERSION of get("/road_address) - PROBLEMS WITH PROD-environment; implicitly available 'request', and
    * 'param' may not be available, when calling from another thread. */
  get("/road_addresses_by_municipality", operation(getRoadAddressesByMunicipality)) {
    contentType = formats("json")
println("Threading print test: Going to avoidRestrictions")
    ApiUtils.avoidRestrictions(apiId, request, params) { params =>
println("Threading print test: Now in avoidRestrictions")
//logger.info(s"GET request for ${request.getRequestURI}?${request.getQueryString} --RUNNING--") // Information logged from ApiUtils, distinct Future thread. Cannot use here; 'request' not available in the Future thread.
      val municipality = params.get("municipality").getOrElse(
        {
          //halt(BadRequest("Missing mandatory 'municipality' parameter"))     // Use if _not_ using avoidRestrictions
          throw ViiteException("Missing mandatory 'municipality' parameter") // Use if _using_ avoidRestrictions
        }
      )

      try {
        val municipalityCode = municipality.toInt // may throw java.lang.NumberFormatException
        val searchDate = dateParameterOptionGetValidOrThrow("situationDate", Some(params))
        val knownAddressLinks = roadAddressService.getAllByMunicipality(municipalityCode, searchDate)
          .filter(ral => ral.roadPart.roadNumber > 0)
        roadAddressLinksToApi(knownAddressLinks)
//logger.info(s"GET request for ${request.getRequestURI}?${request.getQueryString} --FINISHED--") // Information logged from ApiUtils, distinct Future thread. Cannot use here; 'request' not available in the Future thread.

      }
      catch {
        case nfe: NumberFormatException =>
          //BadRequestWithLoggerWarn(s"Incorrectly formatted municipality code: '${municipality}'", nfe.getMessage)  // Use if _not_ using avoidRestrictions
          BadRequest(nfe.getMessage)                                                                                 // Use if _using_ avoidRestrictions
      // TODO Leaving as comment for now... but... This is unexpected generic exception; rather point to telling to the dev team -> handleCommonIntegrationAPIExceptions? -> Remove from here
        case e: Exception =>
          //BadRequestWithLoggerWarn(s"Failed to get road addresses for municipality $municipality", e.getMessage)  // Use if _not_ using avoidRestrictions
          BadRequest(e.getMessage)                                                                                  // Use if _using_ avoidRestrictions
        case t: Throwable =>
          //handleCommonIntegrationAPIExceptions(t, getRodAddressesByMunicipality.operationId)  // Use if _not_ using avoidRestrictions
          BadRequest(t.getMessage)                                                              // Use if _using_ avoidRestrictions
      }
    }
  }


  val getRoadNetworkSummary: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadNetworkSummary")
      tags "Integration (Velho)"
      summary "Returns the whole road network address listing (\"summary\") for current, or historical road network."
      description "Returns the current state of the whole road network address space that contains all the latest changes " +
              "to every part of any road found in Viite (also those addresses that will be valid not until in the future).\n" +
              "The returned JSON contains data about: road number, road name, " +
              "road part number, ely code, administrative class, track, start address, end address, and discontinuity.\n" +
              "Or, with the optional <i>date</i> parameter, a historical summary state can be requested."
      parameter headerParam[String]("X-API-Key").required.description(XApiKeyDescription)
      parameter queryParam[String]("date").optional
        .description("(Optional) Date for the summary info, if the summary data for a history date is required.\n" + ISOdateTimeDescription)
  )
  /** @return The JSON formatted whole road network address space of the latest versions of the network. */
  get("/summary", operation(getRoadNetworkSummary)) {
    contentType = formats("json")

    time(logger, s"Summary:  GET request for /summary", params=Some(params.toMap)) {

      try {
        val dateOption = dateParameterOptionGetValidOrThrow("date")
        val roadNetworkSummary = {
          roadAddressService.getRoadwayNetworkSummary(dateOption)
        }
        currentRoadNetworkSummaryToAPI(roadNetworkSummary)
      } catch {
        case t: Throwable =>
          handleCommonIntegrationAPIExceptions(t, Some(getRoadNetworkSummary.operationId))
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

    val roadnumberMap: Map[Long, Seq[RoadwayNetworkSummaryRow]] = roadNetworkSummary.groupBy(_.roadPart.roadNumber)

    roadnumberMap.toList.sortBy(_._1).map { // foreach roadnumber, handle the sequence of rows
      case(key_RoadNumber,uniqueRoadnumberMap) =>
        Map(
          "roadnumber" -> key_RoadNumber,
          "roadname" -> uniqueRoadnumberMap.head.roadName, // each row in the road number seq has the same roadName; take any (here: first)
          "roadparts" ->
            parseRoadpartsForSummary( uniqueRoadnumberMap.groupBy(_.roadPart.partNumber) )
        )
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
  private def parseRoadpartsForSummary(uniqueRoadnumberMap: Map[Long, Seq[RoadwayNetworkSummaryRow]]): List[Map[String, Any]] = {
    val roadPARTnumberMap: Map[Long, Seq[RoadwayNetworkSummaryRow]] = uniqueRoadnumberMap
    roadPARTnumberMap.toList.sortBy(_._1).flatMap { // foreach roadpartnumber, handle the sequence of rows
      case(key_RoadPARTNumber,uniqueRoadPARTMap) => {

        val admClassWithinRoadPARTMap: Map[Long, Seq[RoadwayNetworkSummaryRow]] = uniqueRoadPARTMap.groupBy(_.administrativeClass)
        admClassWithinRoadPARTMap.toList.sortBy(_._1).map {
          case(_/*key_AdmClassWithinRoadPART*/,uniqueAdmClassWithinRoadPARTMap) =>
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
      summary "Returns all the road name changes made after given time (or within the given time interval)."
      description "Returns all the road name changes made between <i>since</i> and <i>until</i>."
      parameter headerParam[String]("X-API-Key").required.description(XApiKeyDescription)
      parameter queryParam[String]("since").required.description("The earliest date-time of a change to be listed. \n" + ISOdateTimeDescription)
      parameter queryParam[String]("until").optional.description("(Optional) The latest date-time of a change to be listed. \n" + ISOdateTimeDescription)
    )

  get("/roadnames/changes", operation(getRoadNameChanges)) {
    contentType = formats("json")

    try {
      val since: DateTime = dateParameterGetValidOrThrow("since")
      val untilOption: Option[DateTime] = dateParameterOptionGetValidOrThrow("until")
      if(untilOption.isDefined) {
        datesInCorrectOrderOrThrow(since, untilOption.get)
      }
      time(logger, s"GET request for /roadnames/changes", params = Some(params.toMap)) {
        fetchUpdatedRoadNames(since, untilOption)
      }
    } catch {
      case t: Throwable =>
        handleCommonIntegrationAPIExceptions(t, Some(getRoadNameChanges.operationId))
    }
  }

  val getRoadwayChanges: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getRoadwayChanges")
      tags "Integration (kalpa, Digiroad, Viitekehysmuunnin, ...)"
      summary "Returns all the changes made to the roadways after and including the given date."
      parameter headerParam[String]("X-API-Key").required.description(XApiKeyDescription)
      parameter queryParam[String]("since").required
        .description("Restricts the listed changes to those made at or after this moment.\n" + ISOdateTimeDescription)
    )

  get("/roadway/changes", operation(getRoadwayChanges)) {
    contentType = formats("json")

    time(logger, s"GET request for /roadway/changes", params=Some(params.toMap)) {
      try {
        val since: DateTime = dateParameterGetValidOrThrow("since")
        val roadways : Seq[Roadway] = fetchUpdatedRoadways(since)
        roadways.map(r => Map(
          "id" -> r.id,
          "roadwayNumber" -> r.roadwayNumber,
          "roadNumber" -> r.roadPart.roadNumber,
          "roadPartNumber" -> r.roadPart.partNumber,
          "track" -> r.track.value,
          "startAddrMValue" -> r.addrMRange.start,
          "endAddrMValue"   -> r.addrMRange.end,
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
        case t: Throwable =>
          handleCommonIntegrationAPIExceptions(t, Some(getRoadwayChanges.operationId))
      }
    }
  }

  val getRoadwayChangesChanges: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getRoadwayChangesChanges")
      .parameters(
        queryParam[String]("since").required
          .description("Restricts the returned changes to the ones saved to Viite at this timestamp or later. \n" + ISOdateTimeDescription),
        queryParam[String]("until").optional
          .description("(Optional) Restricts the returned changes to the ones saved to Viite at this timestamp or earlier. \n" + ISOdateTimeDescription)
      )
      tags "Integration (kalpa, Digiroad, Velho, Viitekehysmuunnin, ...)"
      summary "Returns the Roadway_change changes after the given since parameter."
      description "Returns the Roadway_change changes after <i>since</i>.\n" +
                  "Changes can be restricted to those made before <i>until</i>.\n" +
                  "2021-10: Change within the return value structure: 'muutospaiva' -> 'voimaantulopaiva'."
      parameter headerParam[String]("X-API-Key").required.description(XApiKeyDescription)
    )

  get("/roadway_changes/changes", operation(getRoadwayChangesChanges)) {
    contentType = formats("json")

    try {
      val since: DateTime = dateParameterGetValidOrThrow("since")
      val untilOption: Option[DateTime] = dateParameterOptionGetValidOrThrow("until")
      if (untilOption.isDefined) {
        datesInCorrectOrderOrThrow(since, untilOption.get)
      }

      time(logger, s"GET request for /roadway_changes/changes", params=Some(params.toMap)) {
        roadwayChangesToApi(roadAddressService.fetchUpdatedRoadwayChanges(since, untilOption))
      }
    } catch {
      case t: Throwable =>
        handleCommonIntegrationAPIExceptions(t, Some(getRoadwayChangesChanges.operationId))
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

  val getValidNodes: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getValidNodes")
      tags "Integration"
      summary "Returns all valid nodes"
      )

  get("/nodes/valid", operation(getValidNodes)) {
    contentType = formats("json")
    time(logger, s"GET request for /nodes/valid") {
      ApiUtils.avoidRestrictions(apiId, request, params) { params =>
        try {
          time(logger, s"fetchAllValidNodesWithJunctions in /nodes/valid") {
println(s"fetchAllValidNodesWithJunctions in /nodes/valid") // TODO remove when debugging is done. This is ugly!
            val fetchedNodesWithJunctions = fetchAllValidNodesWithJunctions()
            time(logger, s"validNodesWithJunctionsToApi in /nodes/valid") {
println(s"validNodesWithJunctionsToApi in /nodes/valid") // TODO remove when debugging is done. This is ugly!
              validNodesWithJunctionsToApi(fetchedNodesWithJunctions)
            }
          }
        } catch {
          case t: Throwable =>
            //handleCommonIntegrationAPIExceptions(t, getValidNodes.operationId)  // Use if _not_ using avoidRestrictions
            InternalServerError(t.getMessage)                                     // Use if _using_ avoidRestrictions
        }
      }
    }
  }

  private def validNodesWithJunctionsToApi(fetchedNodes: Seq[NodeWithJunctions]): Seq[Map[String, Any]] = {
    val nodesWithJunctions: Seq[NodeWithJunctions] = fetchedNodes

    def beforeAfterToLetter(l: Long): String = {
      val result = l match {
        case 1 => "E"
        case 2 => "J"
      }
      result
    }

    val mappedNodes: Seq[Map[String, Any]] = nodesWithJunctions.map { n =>
      val mappedJunctions = n.junctionsWithCoordinates.map { j =>
        val mappedCrossingRoads = j.crossingRoads.map { cr =>
          ListMap(
            "roadNumber" -> cr.roadPart.roadNumber,
            "roadPartNumber" -> cr.roadPart.partNumber,
            "track" -> cr.track,
            "addrM" -> cr.addrM,
            "beforeAfter" -> beforeAfterToLetter(cr.beforeAfter)
          )
        }
        ListMap(
          "startDate" -> j.startDate.toString(),
          "junctionNumber" -> j.junctionNumber.getOrElse("N/A"),
          "junctionCoordinateX" -> Option(j.xCoord).filter(_ != 0).map(_.toLong).getOrElse("N/A"), // If the value is 0 (coordinates could not be calculated), API returns "N/A"
          "junctionCoordinateY" -> Option(j.yCoord).filter(_ != 0).map(_.toLong).getOrElse("N/A"), // If the value is 0 (coordinates could not be calculated), API returns "N/A"
          "road_address" -> mappedCrossingRoads
        )
      }
      ListMap(
        "nodeNumber" -> n.node.nodeNumber,
        "startDate" -> n.node.startDate.toString(),
        "type" -> n.node.nodeType.value,
        "name" -> n.node.name,
        "nodeCoordinateX" -> n.node.coordinates.x.toLong,
        "nodeCoordinateY" -> n.node.coordinates.y.toLong,
        "junctions" -> mappedJunctions
      )
    }
    mappedNodes
  }

  private def fetchAllValidNodesWithJunctions(): Seq[NodeWithJunctions] = {
    val result: Seq[NodeWithJunctions] = APIServiceForNodesAndJunctions.getAllValidNodesWithJunctions
    if (result.isEmpty) {
println(s"fetchAllValidNodesWithJunctions RETURNED EMPTY")                     // TODO remove when debugging is done. This is ugly!
      Seq.empty[NodeWithJunctions]
    } else {
println(s"fetchAllValidNodesWithJunctions GOT RESULT, of size ${result.size}") // TODO remove when debugging is done. This is ugly!
      result
    }
  }

  val getLinearLocationChanges: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getLinearLocationChanges")
      tags "Integration (kalpa, Digiroad, Viitekehysmuunnin, ...)"
      summary "Returns the changes of the linear locations dated after (and including) the given date."
      parameter headerParam[String]("X-API-Key").required.description(XApiKeyDescription)
      parameter queryParam [String]("since").required
        .description("The earliest moment, from where the linear location changes are listed.\n" + ISOdateTimeDescription))

  get("/linear_location/changes", operation(getLinearLocationChanges)) {
    contentType = formats("json")

    time(logger, s"GET request for /linear_location/changes", params=Some(params.toMap)) {
        try {
          val since = dateParameterGetValidOrThrow("since")
          val linearLocations: Seq[LinearLocation] = fetchUpdatedLinearLocations(since)

          val roadaddresses: Seq[RoadAddress] = PostGISDatabase.withDynTransaction {
            roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocations.filter(_.validTo.isEmpty))
          }

          val addrValuesMap: scala.collection.mutable.Map[Long,(Long, Long)] = scala.collection.mutable.Map()
          roadaddresses.foreach(r => addrValuesMap += (r.linearLocationId -> (r.addrMRange.start, r.addrMRange.end)))
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
        case t: Throwable =>
          handleCommonIntegrationAPIExceptions(t, Some(getLinearLocationChanges.operationId))
      }
    }
  }

  val nodesToGeoJson: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("nodesToGeoJson")
      .parameters(
        queryParam[String]("since").required.description("Restrict the returned nodes to the ones changed at or after this moment.\n" + ISOdateTimeDescription),
        queryParam[String]("until").optional.description("Restrict the returned nodes to the ones changed at or before this moment.\n"+ ISOdateTimeDescription)
      )
      tags "Integration (kalpa, Digiroad, Viitekehysmuunnin, ...)"
      summary "Returns the nodes changed after the given moment. May be restricted to an interval, too."
      description "Returns the nodes changed after or at <i>since</i> (and before or at <i>until</i>, if given).\n" +
                  "The results contain the whole node info, containing the node, related junctions', and node point info."
      parameter headerParam[String]("X-API-Key").description(XApiKeyDescription)
    )

  get(transformers = "/nodes_junctions/changes", operation(nodesToGeoJson)) {
    contentType = formats("json")

    time(logger, s"GET request for /nodes_junctions/changes", params=Some(params.toMap)) {
      try {
        val since: DateTime = dateParameterGetValidOrThrow("since")
        val untilOption: Option[DateTime] = dateParameterOptionGetValidOrThrow("until")
        if (untilOption.isDefined) {
          datesInCorrectOrderOrThrow(since, untilOption.get)
        }

        nodesAndJunctionsService.getNodesWithTimeInterval(since, untilOption).map(node => nodeToApi(node))
      } catch {
        case t: Throwable =>
          handleCommonIntegrationAPIExceptions(t, Some(nodesToGeoJson.operationId))
      }
    }
  }

  /** Validates that the given query parameter <i>dateParameterName</i> contains a valid DateTime string.
   *  More than a hundred years in the future is considered as an invalid date, too (for preventing Date overflow possibility, but a Date
   *  very much in the future does not make sense anyway).
   *  Assumes that the parameter is defined, and throws an exception, if there is no such <i>dateParameterName</i> query parameter available.
   *  An empty value also causes an exception.
   *
   * @param dateParameterName name of the query parameter to be fetched, and validated
   * @param explicitParams Optional. Give the http query parameters explicitly, when they are not available implicitly.
   *                       (That is, when the execution is run from a thread distinct to the original that received the http call. (e.g. from a scala Future))
   * @return A readily parsed ISO DateTime, if valid. If not valid, throws an exception.
   * @throws ViiteException if the <i>dateParameterName</i> does not contain a valid ISO8601 date string .*/
  def dateParameterGetValidOrThrow(dateParameterName: String, explicitParams: Option[Params] = None): DateTime = {
    val aHundreadYearsInTheFuture = DateTime.now().plusYears(100)

    val dateString: Option[String] =
      if(explicitParams.isDefined) { Option(explicitParams.get(s"$dateParameterName")) } // use the explicit parameters for printing (assume there is no other usable variables in the context)
      else { params.get(s"$dateParameterName") }                                         // otherwise assume that the implicit 'request' is available

    dateString.getOrElse(throw ViiteException(s"Missing mandatory '$dateParameterName' parameter"))

    if (dateString.isEmpty || dateString.get == "") {     // must have a value to parse
      throw ViiteException(s"Empty '$dateParameterName' parameter.")
    }
    else {
      try {
    //  val dateParameter: DateTime = parseIsoDate(params.get("dateParameterName")).get // Existence checked -> should never go to else // TODO Check: is the parseIsoDate function useful? Use it instead?
        val dateParameter: DateTime = DateTime.parse(dateString.getOrElse("!!!!"))      // Existence checked -> should never go to else
        if (dateParameter.compareTo(aHundreadYearsInTheFuture) > 0)
          throw ViiteException(s"No data that far in the future, check '$dateParameterName' ($dateParameter)")
        else
          dateParameter
      }
      catch {
        case _: IllegalArgumentException =>
          throw ViiteException(s"$ISOdateTimeDescription. Now got $dateParameterName='${dateString.get}'.") //, iae.getMessage) // TODO more accurate message for logging? -> e.g. ViiteAPIException class with an additional field?
        case _: PSQLException =>
          val queryString =
            if(explicitParams.isDefined)  { s"$dateParameterName=dateString" } // use the explicit parameters for printing (assume there is no other usable variables in the context)
            else { s" ${request.getQueryString}" }                             // otherwise assume that the implicit 'request' is available
          throw ViiteException(s"Date out of bounds, check the given dates: $queryString.") //, s"${psqle.getMessage}")
      }
    }
  }

  /** Fetches, and returns a validated DateTime object if available in query parameter <i>dateParameterName</i>,
   *  or None, if there is no such thing given.
   *  Wrapping [[dateParameterGetValidOrThrow]] to get an Option[DateTime] for an optional query parameter.
   *
   * @param dateParameterName name of the query parameter to be fetched, and validated
   * @param explicitParams    Optional. Give the http query parameters explicitly, when they are not available implicitly.
   *                          (That is, when the execution is run from a thread distinct to the original that received the http call. (e.g. from a scala Future))
   * @return A valid DateTime object, or none
   * @throws ViiteException from [[dateParameterGetValidOrThrow]] */
  def dateParameterOptionGetValidOrThrow(dateParameterName: String, explicitParams: Option[Params] = None): Option[DateTime] = {
    if (params.get(s"$dateParameterName").isDefined) {
      Some(dateParameterGetValidOrThrow(dateParameterName, explicitParams))
    } else {
      None
    }
  }

  /** Compares the two given dates for correct timely ordering.
   * Does not return anything, but throws a [[ViiteException]], if <i>until</i> is before <i>since</i>. */
  def datesInCorrectOrderOrThrow(since: DateTime, until: DateTime): Unit = {
    if (since.compareTo(until) > 0)
      throw ViiteException(s"'Since' must not be later date than 'until' (${request.getQueryString}).")
    else
      Unit
  }

  /** Handles [[ViiteException]]s, [[IllegalArgumentException]]s, [[PSQLException]]s, and generic [[NonFatal]] Throwables.
   * Intended usage in a catch block after your known function specific Exception cases
   * @throws Throwable if it is considered a fatal one. */
  def handleCommonIntegrationAPIExceptions(t: Throwable, operationId: Option[String]): Unit = {
    val requestLogString = s"GET request for ${request.getRequestURI}?${request.getQueryString} (${operationId})"
    logger.info(s"$requestLogString --ENDED in ${t.getClass}: ${t.getMessage}--")
    t match {
      case ve: ViiteException =>
        BadRequestWithLoggerWarn(s"Check the given parameters. ${ve.getMessage}", "")
      case iae: IllegalArgumentException =>
        BadRequestWithLoggerWarn(s"$ISOdateTimeDescription. Now got '${request.getQueryString}''", iae.getMessage)
      case psqle: PSQLException =>
        Option(request.getQueryString) match {
          case None =>   // an api call without parameters - that is, the user has no possibilities to change the outcome
            BadRequestWithLoggerWarn(s"Data integrity error found by ${request.getRequestURI}: ${psqle.getMessage}")
          case _ =>
            // TODO remove? This applies when biiiig year (e.g. 2000000) given to DateTime parser. But year now restricted to be less than 100 years in checks before giving to dateTime parsing
            BadRequestWithLoggerWarn(s"Date out of bounds, check the given dates: ${request.getQueryString}.", s"${psqle.getMessage}")
        }
      case nf if NonFatal(nf) =>
        val requestString = s"GET request for ${request.getRequestURI}?${request.getQueryString} ($operationId)"
        haltWithHTTP500WithLoggerError(requestString, nf)
      case t if !NonFatal(t) =>
        throw t
    }
  }

  def BadRequestWithLoggerWarn(messageFor400: String, extraForLogger: String=""): Unit = {
    logger.warn(messageFor400 + "  " + extraForLogger)
    halt(BadRequest(messageFor400))
  }

  private def haltWithHTTP500WithLoggerError(whatWasCalledWhenError: String, throwable: Throwable) = {
    val now = DateTime.now()
    logger.error(s"An unexpected error in '$whatWasCalledWhenError ($now)': $throwable")
    halt(InternalServerError(
      s"You hit an unexpected error. Contact system administrator, or Viite development team.\n" +
        s"Tell them to look for '$whatWasCalledWhenError ($now)'"
    ))
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
      "road" -> nodePoint.roadPart.roadNumber,
      "road_part" -> nodePoint.roadPart.partNumber,
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
      "road" -> junctionPoint.roadPart.roadNumber,
      "road_part" -> junctionPoint.roadPart.partNumber,
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
            if (roadAddressLink.sideCode == SideCode.AgainstDigitizing)
              roadAddressLink.geometry.reverse
            else
              roadAddressLink.geometry
            , roadAddressLink.addrMRange.start, roadAddressLink.addrMRange.end),
          "id" -> roadAddressLink.id,
          "link_id" -> roadAddressLink.linkId,
          "link_source" -> roadAddressLink.roadLinkSource.value,
          "road_number" -> roadAddressLink.roadPart.roadNumber,
          "road_part_number" -> roadAddressLink.roadPart.partNumber,
          "track_code" -> roadAddressLink.trackCode,
          "side_code" -> roadAddressLink.sideCode.value,
          "start_addr_m" -> roadAddressLink.addrMRange.start,
          "end_addr_m"   -> roadAddressLink.addrMRange.end,
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

  private def calibrationPoint(startCalibrationPoint: Option[ProjectCalibrationPoint], endCalibrationPoint: Option[ProjectCalibrationPoint]) = {
    def calibrationPointMapper(calibrationPoint: Option[ProjectCalibrationPoint]) = {
      calibrationPoint.map(cp => Map("link_id" -> cp.linkId, "address_m_value" -> cp.addressMValue, "segment_m_value" -> cp.segmentMValue))
    }

    Map(
      "start" -> calibrationPointMapper(startCalibrationPoint),
      "end" -> calibrationPointMapper(endCalibrationPoint)
    )
  }

  private def fetchUpdatedRoadNames(since: DateTime, untilOption: Option[DateTime]) = {
    val result = roadNameService.getUpdatedRoadNames(since, untilOption)
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
  dateOption.map { date => dateTimeNoMillisFormatter.print(date) }

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
