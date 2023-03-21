package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.util.DigiroadSerializers
import fi.liikennevirasto.viite.RoadAddressService
import fi.liikennevirasto.viite.dao.RoadAddress
import org.joda.time.DateTime
import org.json4s.{Formats}
import org.scalatra.{BadRequest, InternalServerError, ScalatraServlet}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, _}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.control.NonFatal

class SearchApi(roadAddressService: RoadAddressService,
                implicit val swagger: Swagger)
  extends ScalatraServlet
    with JacksonJsonSupport
    with SwaggerSupport {

  protected val applicationDescription = "The Search API "
  protected val XApiKeyDescription: String =
    "You need an API key to use Viite APIs.\n" +
    "Get your API key from the technical system owner (järjestelmävastaava)."
  protected val roadNumberDescription = "Road Number of a road address: 1-99999."
  protected val roadPartNumberDescription = "Road Part Number of a road address: 1-999."
  protected val trackNumberFilterDescription: String =
    "Track Number is 0, 1, or 2.\n" +
    "0: single track, both directions combined. " +
    "1: track in the direction of the growing road address. " +
    "2: track in the direction against the growing road address."

  val logger: Logger = LoggerFactory.getLogger(getClass)
  protected implicit val jsonFormats: Formats = DigiroadSerializers.jsonFormats

  before() {
    contentType = formats("json")
  }

  private val getRoadAddress: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[List[Map[String, Any]]]("getRoadAddress")
      .parameters(
        headerParam[String]("X-API-Key").description(XApiKeyDescription),
        queryParam[String]("linkId").required.description("(KGV) LinkId for the link whose addresses are be returned"),
        queryParam[Double]("startMeasure").optional.description("(Optional) Linear locations, whose endMeasure is before this <i>startMeasure</i>, are not returned."),
        queryParam[Double]("endMeasure").optional.description("(Optional) Linear locations, whose startMeasure is after this <i>endMeasure</i>, are not returned.")
      )
      tags "SearchAPI (Digiroad)"
      summary "Returns all the road addresses for the given single link. Return values are listed as linear locations. Linear locations can optionally be restricted by the link's measure values."
      description "Returns the road addresses of the given link, listed as linear locations." +
                  "<br />The returned Linear locations may be restricted by giving <i>startMeasure</i>, and/or <i>endMeasure</i>." +
                  "<br />A linear location must belong to the measure interval at least in one point, to be included in the returned results." +
                  "<br />2023-03 Better exception handling, and more explicit help for the caller. " +
                  "Also now, if given start measure > end measure, an error msg is returned, instead of a \"two-part\" return list "
    )
  get("/road_address/?", operation(getRoadAddress)) {
    val requestString = s"GET request for ${request.getRequestURI}?${request.getQueryString} (${getRoadAddress.operationId})"
    time(logger, requestString, params=Some(params)) {

      val linkId = params.getOrElse("linkId", halt(BadRequest("Missing mandatory query parameter 'linkId'"))).toString //TODO MAKE OTHER REQUIRED PARAMS LIKE THIS!

      try { // Check that the formats of the parameters are ok
        val startMeasureOption = params.get("startMeasure")
        if (startMeasureOption.isDefined) startMeasureOption.map(_.toDouble) else None
        val endMeasureOption = params.get("endMeasure")
        if (endMeasureOption.isDefined) endMeasureOption.map(_.toDouble) else None
      }
      catch {
        case ve: ViiteException =>
          logger.info(s"A malformed parameter in the query string: ${ve.getMessage}")
          halt(BadRequest(s"Check the parameters. ${ve.getMessage}"))
        case e: Exception =>
          val paramsDescr = s"?${request.getQueryString}"
          logger.info(s"A malformed parameter in the query string: $paramsDescr. $e")
          halt(BadRequest(
            s"At least one malformed parameter: 'startMeasure' or 'endMeasure'. Now got $paramsDescr.\n" +
            s"Double expected, 0.0 or greater, and startMeasure < endMeasure."
          ))
      }

      val startMeasure = params.get("startMeasure").map(_.toDouble)
      val endMeasure = params.get("endMeasure").map(_.toDouble)

      haltWith400IfInvalidLinkIds(Set(linkId))

      //start measure should not be <0, but... Naaah, don't wanna do this now. This is ugly.
      //val sm = params.getOrElse("startMeasure", 0).toString.toDouble
      //val em = params.getOrElse("endMeasure", Long.MaxValue).toString.toDouble
      //haltWith400IfInvalidAddress(sm.toLong, em.toLong) // The same rules go for link measures, than go for road address measures. But make the precision to be 1cm (with *100) instead of one meter, as Long cuts the decimals away
      (startMeasure, endMeasure) match {
        case(Some(sm), Some(em)) => haltWith400IfInvalidMeasure(sm, em) // check them against each other; size order
        case _ => None
      }

      try {
        roadAddressService.getRoadAddressWithLinkIdAndMeasure(linkId, startMeasure, endMeasure).map(roadAddressMapper)
      }
      catch {
        case throwable: Throwable => haltWithHTTP500WithLoggerError(requestString, throwable)
      }

    }
  }

  private val getRoadNumbers: SwaggerSupportSyntax.OperationBuilder =
    (apiOperation[Seq[Long]]("getRoadNumbers")
      tags "SearchAPI (Digiroad)"
      summary "Returns all the existing road numbers at the current Viite road network."
      description "Returns List of all the existing road numbers at the current Viite road network." +
              "The Viite current network may contain roadway number changes that will be in effect only in the future.\n"+
              "<br />2023-02-23 The list is now sorted. Better exception handling, and more explicit help for the caller."
      parameter headerParam[String]("X-API-Key").required.description(XApiKeyDescription)
    )
  // TODO: "?" in the end is useless; does not take query params
  get("/road_numbers?", operation(getRoadNumbers)) {
    val requestString = s"GET request for ${request.getRequestURI}  (${getRoadNumbers.operationId})"
    time(logger, requestString) {

      try {
        roadAddressService.getRoadNumbers.toList.sorted
      }
      catch {
        case throwable: Throwable => haltWithHTTP500WithLoggerError(requestString, throwable)
      }

    }
  }

  private val getRoadAddressWithRoadNumber: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressWithRoadNumber")
      .parameters(
        headerParam[String]("X-API-Key").required.description(XApiKeyDescription),
        pathParam[Long]("road").required.description(roadNumberDescription),
        queryParam[Long]("tracks").optional.description("(Optional) " + trackNumberFilterDescription + "\n" +
          "You may request multiple tracks at a time, by adding several tracks (e.g. ?tracks=1&tracks=2)\n" +
          "If omitted, any track is returned.\n" +
          "Also, any invalid 'tracks' entry is omitted. So with invalid only 'tracks' parameters you get unfiltered tracks.")
      )
      tags "SearchAPI (Digiroad)"
      summary "Returns the road addresses within the given road number, returned as linear location sized parts.\n" +
              "If track parameter(s) given, the results are filtered to those tracks."
      description "2023-03 Better exception handling, and more explicit help for the caller."
    )
  get("/road_address/:road/?", operation(getRoadAddressWithRoadNumber)) {
    val requestString = s"GET request for ${request.getRequestURI}?${request.getQueryString} (${getRoadAddressWithRoadNumber.operationId})"
    time(logger, requestString) {

      try {
        val roadNumber = roadNumberGetValidOrThrow("road")
        val trackCodes = if(params.get("tracks").isDefined) tracksGetValidFromURLOrThrow("tracks") else Seq()
        roadAddressService.getRoadAddressWithRoadNumber(roadNumber, Track.applyAll(trackCodes)).map(roadAddressMapper)
      }
      catch {
        case t: Throwable => handleCommonSearchAPIExceptions(t, getRoadAddressWithRoadNumber.operationId)
      }

    }
  }

  private val getRoadAddressesFiltered: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressesFiltered")
      .parameters(
        headerParam[String]("X-API-Key").description(XApiKeyDescription),
        pathParam[Long]("road").required.description(roadNumberDescription),
        pathParam[Long]("roadPart").required.description(roadPartNumberDescription)
      )
      tags "SearchAPI (Digiroad)"
      summary "Returns all the road addresses within the given road part, returned as linear location sized parts."
      description "Returns all the road addresses within the given road part (defined by road and road part numbers), " +
                  "returned as linear location sized parts." +
                  "<br />2023-03 Better exception handling, and more explicit help for the caller."
    )
  // TODO: "?" in the end is useless; does not take query params
  get("/road_address/:road/:roadPart/?", operation(getRoadAddressesFiltered)) {
    val requestString = s"GET request for ${request.getRequestURI} (${getRoadAddressesFiltered.operationId})"
    time(logger, requestString) {

      try {
        val roadNumber = roadNumberGetValidOrThrow("road")
        val roadPart = roadPartNumberGetValidOrThrow("roadPart")

        roadAddressService.getRoadAddressesFiltered(roadNumber, roadPart).map(roadAddressMapper)
      }
      catch {
        case t: Throwable => handleCommonSearchAPIExceptions(t, getRoadAddressWithRoadNumber.operationId)
      }

    }
  }

  private val getRoadAddressesFiltered2: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressesFiltered2")
      .parameters(
        headerParam[String]("X-API-Key").required.description(XApiKeyDescription),
        pathParam[Long]("road").required.description(roadNumberDescription),
        pathParam[Long]("roadPart").required.description(roadPartNumberDescription),
        pathParam[Long]("address").required.description("Road Measure, the metric address value, of a road address"),
        pathParam[Long]("track").optional.description("(Optional) " + trackNumberFilterDescription +
                                                    "\nIf omitted, any track is returned.")
      )
      tags "SearchAPI (Digiroad)"
      summary "Returns the road addresses within the given road part, returned as linear location sized parts.\n" +
              "Minimum address value must be given, and the results are filterable by track."
      description "Returns the road addresses within the given road number, road part number, and bigger than address value, " +
                  "returned as linear location sized parts. Also filterable by track." +
                  "<br />2023-03 Better exception handling, and more explicit help for the caller."
    )
  get("/road_address/:road/:roadPart/:address/?", operation(getRoadAddressesFiltered2)) {
    val requestString = s"GET request for ${request.getRequestURI}?${request.getQueryString} (${getRoadAddressesFiltered2.operationId})"
    time(logger, requestString) {

      try {
        val roadNumber = roadNumberGetValidOrThrow("road")
        val roadPart = roadPartNumberGetValidOrThrow("roadPart")
        val address = startAddressGetValidOrThrow("address")
        val track = trackOptionGetValidOrThrow("track")
        roadAddressService.getRoadAddress(roadNumber, roadPart, address, Track.applyOption(track)).map(roadAddressMapper)
      }
      catch {
        case t: Throwable => handleCommonSearchAPIExceptions(t, getRoadAddressesFiltered2.operationId)
      }

    }
  }

  private val getRoadAddressesFiltered3: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressesFiltered3")
      .parameters(
        headerParam[String]("X-API-Key").required.description(XApiKeyDescription),
        pathParam[Long]("road").required.description(roadNumberDescription),
        pathParam[Long]("roadPart").required.description(roadPartNumberDescription),
        pathParam[Long]("startAddress").required
          .description("Road start measure of a road address. >=0.\n" +
                       "Filters away the linear locations not hitting the range."),
        pathParam[Long]("endAddress").required
          .description("Road end measure of a road address. Should be given an address from the road part (not outside of it).\n" +
                       "Filters away the linear locations not hitting the range.")
      )
      tags "SearchAPI (Digiroad)"
      summary "Returns the road addresses within the given road number, road part number, and between given address values," +
              "returned as linear location sized parts."
      description "2023-03 Better exception handling, and more explicit help for the caller."
    )
  // TODO: "?" in the end is useless; does not take query params
  get("/road_address/:road/:roadPart/:startAddress/:endAddress/?", operation(getRoadAddressesFiltered3)) {
    val requestString = s"GET request for ${request.getRequestURI} (${getRoadAddressesFiltered3.operationId})"
    time(logger, requestString) {

      try {
        val roadNumber = roadNumberGetValidOrThrow("road")
        val roadPart = roadPartNumberGetValidOrThrow("roadPart")
        val startAddress = startAddressGetValidOrThrow("startAddress")
        val endAddress = endAddressGetValidOrThrow("endAddress", startAddress)

        roadAddressService.getRoadAddressesFiltered(roadNumber, roadPart, startAddress, endAddress).map(roadAddressMapper)
      }
      catch {
        case t: Throwable => handleCommonSearchAPIExceptions(t, getRoadAddressesFiltered3.operationId)
      }

    }
  }

  private val getRoadAddressByLinkIds: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressByLinkIds")
      .parameters(
        headerParam[String]("X-API-Key").required.description(XApiKeyDescription),
        bodyParam[Set[String]]("linkIds").required
          .description("List of LinkIds. Only the list, no name for the parameter, or other decorations.\n" +
                       "e.g. \"[\\\"36be5dec-0496-4292-b260-884664467174:1\\\",\\\"6ad00ce3-92ef-4952-91ae-dcb1bf45caf8:1\\\"]\"\n")
      )
      tags "SearchAPI (Digiroad)"
      summary "Returns all the road addresses for the given links. Return values are listed as linear locations."
      description "2023-03 Better exception handling, and more explicit help for the caller."
    )
  // TODO: "?" in the end is useless; does not take query params
  post("/road_address/?", operation(getRoadAddressByLinkIds)) {
    val requestString = s"POST request for ${request.getRequestURI} (${getRoadAddressByLinkIds.operationId})"
    time(logger, requestString, params=Some(Map("requestBody" -> request.body))) {

      val linkIds = try { // Check that the formats of the parameters are ok
        parsedBody.extract[Set[String]]
      } catch {
        case e: Exception =>
          logger.info(s"A malformed list of link ids in the body: ${request.body}. $e")
          halt(BadRequest(
            s"Malformed list of link ids in the body. Got '${request.body}'.\n" +
            s"The MML KGV style link ids expected in a list. '[link-id-1:n2,link-id-2:n2]'"
          ))
      }

      // Check the numeric validity of the parameters
      if(linkIds.isEmpty) // parsing to strings failed, or no links given
        halt(BadRequest(s"List of proper MML KGV link ids expected in the body, as a 'linkIds' list. body: '${request.body}'"))
      haltWith400IfInvalidLinkIds(linkIds)
      try {
        roadAddressService.getRoadAddressByLinkIds(linkIds).map(roadAddressMapper)
      }
      catch {
        case t: Throwable => handleCommonSearchAPIExceptions(t, getRoadAddressByLinkIds.operationId)
      }

    }
  }

  private val getRoadAddressWithRoadNumberParts: SwaggerSupportSyntax.OperationBuilder = (
    apiOperation[List[Map[String, Any]]]("getRoadAddressWithRoadNumberParts")
      .parameters(
        headerParam[String]("X-API-Key").description(XApiKeyDescription),
        pathParam[Long]("road").required.description(roadNumberDescription),
        bodyParam[Seq[Long]]("roadParts").required
          .description("List of roadParts for filtering the results. E.g. '\"roadParts\":[113,115]'. " +
                       "A road part number space is 1-999. If omitted, an empty list is returned. "),
        bodyParam[Seq[Int]]("tracks").required
          .description("List of track numbers for filtering the results. E.g. '\"tracks\":[1]'.\n" +
                       trackNumberFilterDescription + "\nIf omitted, an empty list is returned.")
      )
      tags "SearchAPI (Digiroad)"
      summary "Returns the road addresses within the given road number, returned as linear location sized parts.\n" +
              "If road parts, and/or tracks are given, the results are filtered to those road parts, and/or track numbers."
      description "2023-03 Better exception handling, and more explicit help for the caller."
    )
  // TODO: "?" in the end is useless; does not take query params
  post("/road_address/:road/?", operation(getRoadAddressWithRoadNumberParts)) {
    val requestString = s"POST request for ${request.getRequestURI} (${getRoadAddressWithRoadNumberParts.operationId})"
    time(logger, requestString, params=Some(params + ("requestBody" -> request.body))) {

      try {
        if ((parsedBody \ "tracks").toString.equals("JNothing") ||
          (parsedBody \ "roadParts").toString.equals("JNothing"))
          throw ViiteException(s"Missing mandatory body parameters, or a non-JSON body. Check 'tracks', and 'roadParts' lists, and correct formatting of the body.")

        val roadNumber = roadNumberGetValidOrThrow("road")
        val tracks = tracksGetValidFromBodyOrThrow("tracks")
        val roadParts = roadPartsGetValidOrThrow("roadParts")

        roadAddressService.getRoadAddressWithRoadNumberParts(roadNumber, roadParts.toSet, Track.applyAll(tracks)).map(roadAddressMapper)
      }
      catch {
        case t: Throwable => handleCommonSearchAPIExceptions(t, getRoadAddressWithRoadNumberParts.operationId)
      }

    }
  }

  /** Returns a valid road part list extracted from [[roadPartsParameterName]] in the body, if it is valid.
   *
   * @param roadPartsParameterName name of the list to be extracted from the body, and validated
   * @return Sequence of valid road part values.
   * @throws ViiteException, if the [[roadPartsParameterName]] list fails to comply with allowed track values, is missing, or malformed. */
  private def roadPartsGetValidOrThrow(roadPartsParameterName: String): Seq[Long] = {
    val roadPartsJson = (parsedBody \ roadPartsParameterName)
    if(roadPartsJson.toString.equals("JNothing"))
      throw ViiteException(s"Missing mandatory body parameters, or non-JSON  body. ($roadPartsParameterName)")

    val roadParts = try {
      roadPartsJson.extract[Seq[Long]]
    } catch {
      case me: org.json4s.MappingException =>
        throw ViiteException(s"Cannot parse '$roadPartsParameterName' list (in the body) to integer list. $roadPartNumberDescription")
    }

    if (roadParts.isEmpty)
      throw ViiteException(s"Empty '$roadPartsParameterName' list in the body. $roadPartNumberDescription")

    val roadPartNumbersAreValid = roadParts.forall(_ > 0) && roadParts.forall(_ < 1000)

    if (!roadPartNumbersAreValid)
      throw ViiteException(s"At least one invalid road part number. $roadPartNumberDescription Now got: $roadParts")
    else
      roadParts
  }

  private def linkIdsAreValid(linkIds: Set[String]) = {
    val linkIdRegex = """(^\w+-\w+-\w+-\w+-\w+:\d+$)""".r // Link UUID // in fact, hexa instead of \w

    // val allValid =
    linkIds.forall(linkIdRegex.findFirstIn(_).nonEmpty) // TODO "linkIdRegex.matches(_)" available from scala 2.13 and upward

   // logger.info(s"All the link id's are valid? ${allValid} (${linkIds}).")
   // allValid
  }

  private def haltWith400IfInvalidLinkIds(linkIds: Set[String]): Unit = {
    if (!linkIdsAreValid(linkIds)) {
      logger.info(s"[At least] a single malformed linkId: $linkIds")
      halt(BadRequest(s"[At least] a single malformed linkId. MML KGV link ids expected. Now got: ${linkIds.toList}"))
    }
  }

  private def haltWith400IfInvalidMeasure(startMeasure: Double, endMeasure: Double): Unit = {
    val measuresAreValid = startMeasure >= 0 && endMeasure > 0 && startMeasure < endMeasure
    if (!measuresAreValid) {
      logger.info(s"Invalid measure value, or mutual order: $startMeasure or $endMeasure")
      halt(BadRequest(s"Invalid value(s) in measure(s). A measure must be >=0, and start < end. Now got: ($startMeasure, $endMeasure)"))
    }
  }

  /** Fetches, and returns a validated long valued parameter if available in query parameter [[queryParameterName]],
   * or throws a [[ViiteException]], it there is a missing or faulty value.
   *
   * @param queryParameterName name of the path parameter to be fetched, and validated
   * @param minValue Smallest acceptable long value for the [[queryParameterName]] parameter
   * @param maxValue Biiggest acceptable long value for the [[queryParameterName]] parameter
   * @return A valid long value fitting within the given min-max llimits. */
  def longParamGetValidOrThrow(queryParameterName: String, minValue: Long, maxValue: Long): Long = { // TODO Ain't an Int enough for these all?

    val queryParameterString = params.get(s"$queryParameterName")
    queryParameterString.getOrElse(throw ViiteException(s"Missing mandatory '$queryParameterName' parameter"))

    if (queryParameterString.isEmpty || queryParameterString.get == "") // must have a value to parse
      throw ViiteException(s"Empty '$queryParameterName' parameter.")

    val longParameter: Long = try {
      queryParameterString.get.toLong // Existence checked above -> .get should never fail
    } catch {
      case nfe: NumberFormatException =>
        throw ViiteException(s"An integer expected. Now got '$queryParameterName=${queryParameterString.get}'")
    }

    if (longParameter < minValue || longParameter > maxValue)
      throw ViiteException(s"Range of '$queryParameterName' is $minValue - $maxValue. Now got '${queryParameterString.get}'. ")
    else
      longParameter
  }

  /** [[LongParamGetValidOrThrow]] function called with road number limits. */
  def roadNumberGetValidOrThrow(roadNumberParameterName: String): Long = {
    val minRoadNumber = 1
    val maxRoadNumber = 99999
    longParamGetValidOrThrow (roadNumberParameterName, minRoadNumber, maxRoadNumber )
  }

  /** [[LongParamGetValidOrThrow]] function called with road part number limits. */
  def roadPartNumberGetValidOrThrow(roadPartNumberParameterName: String): Long = {
    val minRoadPartNumber = 1
    val maxRoadPartNumber = 999
    longParamGetValidOrThrow(roadPartNumberParameterName, minRoadPartNumber, maxRoadPartNumber)
  }

  /** [[LongParamGetValidOrThrow]] function called with start address value limits. */
  def startAddressGetValidOrThrow(startAddressParameterName: String): Long = {
    val minStartAddress = 0
    val maxStartAddress = 1157000 // Finland, max. range in [m] :)
    longParamGetValidOrThrow(startAddressParameterName, minStartAddress, maxStartAddress)
  }

  /** [[LongParamGetValidOrThrow]] function called with end address limits.
   * Additionally checks, that the returned value is also bigger than given [[limitingStartAddress]]. */
  def endAddressGetValidOrThrow(endAddressParameterName: String, limitingStartAddress: Long): Long = {
    val minEndAddress = 0
    val maxEndAddress = 1157000 // Finland, max. range in [m] :)
    val endAddress = longParamGetValidOrThrow(endAddressParameterName, minEndAddress, maxEndAddress)
    if (endAddress <= limitingStartAddress)
      throw ViiteException(s"'$endAddressParameterName' (now $endAddress) must be bigger than start address (now $limitingStartAddress). ")
    else
      endAddress
  }

  /** Returns a valid track extracted from [[trackParameterName]] parameter, or None if [[trackParameterName]] parameter is not given.
   * Wraps [[tracksGetValidOrThrow]].
   *
   * @throws ViiteException, if the [[trackParameterName]] fails to comply with the allowed track values. */
  def trackOptionGetValidOrThrow(trackParameterName: String): Option[Int] = {
    val track = params.get(s"$trackParameterName")
    if (track.isDefined) {
      val trackSeq = {
        try { Seq(track.get.toInt) }
        catch { case e: Exception =>   throw ViiteException(s"An invalid track parameter: '$trackParameterName=$track'. $trackNumberFilterDescription") }
      }
      Some(tracksGetValidOrThrow(s"$trackParameterName", trackSeq).head)
    } else
      None
  }

  /** Returns a valid track list extracted from [[tracksParameterName]] in the body, if it is valid.
   * Wraps [[tracksGetValidOrThrow]].
   * @throws ViiteException, if the [[tracksParameterName]] list fails to comply with allowed track values, or there is no such list. */
  private def tracksGetValidFromBodyOrThrow(tracksParameterName: String): Seq[Int] = {
    val tracksJson = (parsedBody \ "tracks")
    if(tracksJson.toString.equals("JNothing"))
      throw ViiteException(s"Missing mandatory body parameters, or non-JSON  body. ($tracksParameterName)")

    val tracks = try {
      tracksJson.extract[Seq[Int]]
    } catch {
      case me: org.json4s.MappingException =>
        throw ViiteException(s"Cannot parse '$tracksParameterName' list (in the body) to integer list. $trackNumberFilterDescription")
    }

    if (tracks.isEmpty)
      throw ViiteException(s"Empty '$tracksParameterName' list in the body. $trackNumberFilterDescription")

    tracksGetValidOrThrow (tracksParameterName, tracks)
  }

  /** Returns a valid track list extracted from [[tracksParameterName]] parameters, if it is valid.
   * Wraps [[tracksGetValidOrThrow]].
   * @throws ViiteException, if the [[tracksParameterName]] params fail to comply with allowed track values, or there is no such list. */
  private def tracksGetValidFromURLOrThrow(tracksParameterName: String): Seq[Int] = {
    try {
      val tracks = multiParams.getOrElse(tracksParameterName, Seq()).map(_.toInt)
      tracksGetValidOrThrow (tracksParameterName, tracks)
    }
    catch {
      case e: java.lang.NumberFormatException =>
        throw ViiteException(s"A malformed field in the query path: $tracksParameterName=${multiParams(tracksParameterName).toList.mkString("[", "; ", "]")}.\n" +
          s"$trackNumberFilterDescription")
    }
  }

  /** Returns the given [[trackSeq]] back, if it contains, and contains only, valid track numbers.
   * @throws ViiteException, if the given Seq[Int] fails to comply with allowed track values, or the Seq is empty. */
  private def tracksGetValidOrThrow(tracksParameterName: String, trackSeq: Seq[Int]): Seq[Int] = {
    if (trackSeq.size < 1) {
      throw ViiteException(s"Empty parameter list '$tracksParameterName'.  A track's scope is from 0 to 2. Now got: ${trackSeq.toList}")
    }
    val trackNumbersAreValid = trackSeq.forall(_ >= 0) && trackSeq.forall(_ <= 2)
    if (!trackNumbersAreValid) {
      throw ViiteException(s"Check $tracksParameterName.  A track's scope is from 0 to 2. Now got: ${trackSeq.toList.mkString("[", "; ", "]")}")
    }
    else
      trackSeq
  }

  def BadRequestWithLoggerInfo(messageFor400: String, extraForLogger: String = ""): Unit = {
    logger.info(messageFor400 + "  " + extraForLogger)
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

  /** Handles [[ViiteException]]s, and generic [[NonFatal]] Throwables.
   * Intended usage in a catch block after your known function specific Exception cases.
   *
   * @throws Throwable if it is considered a fatal one. */
  def handleCommonSearchAPIExceptions(t: Throwable, operationId: Option[String]): Unit = {
    val requestString = s"GET request for ${request.getRequestURI}?${request.getQueryString} (${operationId.get})"
    t match {
      case ve: ViiteException =>
        BadRequestWithLoggerInfo(s"Check the given parameters. ${ve.getMessage}", s"${operationId.get}")
      case nf if NonFatal(nf) => {
        haltWithHTTP500WithLoggerError(requestString, nf)
      }
      case f if !NonFatal(f) => {
        haltWithHTTP500WithLoggerError("Fatal error. "+requestString, f)
      }
    }
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
      "sideCode" -> roadAddress.sideCode.value
    )
  }
}
