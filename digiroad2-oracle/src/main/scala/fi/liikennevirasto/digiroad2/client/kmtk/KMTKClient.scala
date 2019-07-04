package fi.liikennevirasto.digiroad2.client.kmtk

import java.io.IOException
import java.net.URLEncoder
import java.util

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.linearasset.RoadLinkLike
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import org.apache.http.NameValuePair
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.json4s.jackson.JsonMethods.parse
import org.json4s.{DefaultFormats, Formats, StreamInput}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class KMTKID(uuid: String, version: Long)

case class KMTKRoadlink(linkId: Long, kmtkId: KMTKID = KMTKID("", 0), municipalityCode: Int, geometry: Seq[Point],
                        administrativeClass: AdministrativeClass, trafficDirection: TrafficDirection,
                        featureClass: FeatureClass, modifiedAt: Option[DateTime] = None, attributes: Map[String, Any] = Map(),
                        constructionType: ConstructionType = ConstructionType.InUse,
                        linkSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface, length: Double = 0.0) extends RoadLinkLike {

  def roadNumber: Option[String] = attributes.get("ROADNUMBER").map(_.toString)

  val timeStamp: Long = attributes.getOrElse("LAST_EDITED_DATE", attributes.getOrElse("CREATED_DATE", BigInt(0))).asInstanceOf[BigInt].longValue()
}

trait KMTKClientOperations {

  type KMTKType

  protected val linkGeomSource: LinkGeomSource

  protected def restApiEndPoint: String

  protected def serviceName: String

  protected val disableGeometry: Boolean

  case class KMTKError(content: Map[String, Any], url: String)

  class KMTKClientException(response: String) extends RuntimeException(response)

  protected implicit val jsonFormats: Formats = DefaultFormats

  protected def mapFields(content: Map[String, Any], url: String): Either[List[Map[String, Any]], KMTKError]

  protected def defaultOutFields(): String

  protected def extractKMTKFeature(feature: Map[String, Any]): KMTKType

  lazy val logger: Logger = LoggerFactory.getLogger(getClass)

  protected def anyToDouble(number: Any): Option[Double] = number match {
    case bi: BigInt => Some(bi.toDouble)
    case i: Int => Some(i.toDouble)
    case l: Long => Some(l.toDouble)
    case d: Double => Some(d)
    case _ => None
  }

  // TODO
  protected def withFilter[T](attributeName: String, ids: Set[T]): String = {
    val filter =
      if (ids.isEmpty) {
        ""
      } else {
        val query = ids.mkString(",")
        s""""where":"$attributeName IN ($query)","""
      }
    filter
  }

  // TODO
  protected def withLimitFilter(attributeName: String, low: Int, high: Int, includeAllPublicRoads: Boolean = false): String = {
    val filter =
      if (low < 0 || high < 0 || low > high) {
        ""
      } else {
        if (includeAllPublicRoads) {
          //TODO check if we can remove the adminclass in the future
          s""""where":"( ADMINCLASS = 1 OR $attributeName >= $low and $attributeName <= $high )","""
        } else {
          s""""where":"( $attributeName >= $low and $attributeName <= $high )","""
        }
      }
    filter
  }

  // TODO
  protected def withMunicipalityFilter(municipalities: Set[Int]): String = {
    withFilter("MUNICIPALITYCODE", municipalities)
  }

  // TODO
  protected def combineFiltersWithAnd(filter1: String, filter2: String): String = {

    (filter1.isEmpty, filter2.isEmpty) match {
      case (true, true) => ""
      case (true, false) => filter2
      case (false, true) => filter1
      case (false, false) => "%s AND %s".format(filter1.dropRight(2), filter2.replace("\"where\":\"", ""))
    }
  }

  // TODO
  protected def combineFiltersWithAnd(filter1: String, filter2: Option[String]): String = {
    combineFiltersWithAnd(filter2.getOrElse(""), filter1)
  }

  // TODO
  protected def queryParameters(fetchGeometry: Boolean = true): String = {
    if (fetchGeometry && !disableGeometry) "returnGeometry=true&returnZ=false&returnM=true&geometryPrecision=3&f=json"
    else "returnGeometry=false&f=json"
  }

  // TODO
  protected def serviceUrl: String = restApiEndPoint + serviceName + "/FeatureServer/query"

  // TODO
  protected def serviceUrl(bounds: BoundingRectangle, definition: String, parameters: String): String = {
    serviceUrl +
      s"?layerDefs=$definition&geometry=" + bounds.leftBottom.x + "," + bounds.leftBottom.y + "," + bounds.rightTop.x + "," + bounds.rightTop.y +
      s"&geometryType=esriGeometryEnvelope&spatialRel=esriSpatialRelIntersects&$parameters"

  }

  // TODO
  protected def serviceUrl(definition: String, parameters: String): String = {
    serviceUrl +
      s"?layerDefs=$definition&" + parameters
  }

  // TODO
  protected def layerDefinitionWithoutEncoding(filter: String, customFieldSelection: Option[String] = None): String = {
    val definitionStart = "[{"
    val layerSelection = """"layerId":0,"""
    val fieldSelection = customFieldSelection match {
      case Some(fs) => s""""outFields":"""" + fs + """,CONSTRUCTIONTYPE""""
      case _ => s""""outFields":"""" + defaultOutFields + """""""
    }
    val definitionEnd = "}]"
    definitionStart + layerSelection + filter + fieldSelection + definitionEnd
  }

  // TODO
  protected def layerDefinition(filter: String, customFieldSelection: Option[String] = None): String = {
    URLEncoder.encode(layerDefinitionWithoutEncoding(filter, customFieldSelection), "UTF-8")
  }

  protected def fetchKMTKFeatures(url: String): Either[List[Map[String, Any]], KMTKError] = {
    time(logger, s"Fetch KMTK features with url '$url'") {
      val request = new HttpGet(url)
      val client = HttpClientBuilder.create().build()
      try {
        val response = client.execute(request)
        try {
          mapFields(parse(StreamInput(response.getEntity.getContent)).values.asInstanceOf[Map[String, Any]], url)
        } finally {
          response.close()
          if (response.getStatusLine.getStatusCode >= 300) {
            return Right(KMTKError(Map(("KMTK FETCH failure", "KMTK response code was <300 (unsuccessful)")), url))
          }
        }
      } catch {
        case _: IOException => Right(KMTKError(Map(("KMTK FETCH failure", "IO Exception during KMTK fetch. Check connection to KMTK")), url))
      }
    }
  }

  protected def fetchFeaturesAndLog(url: String): Seq[KMTKType] = {
    fetchKMTKFeatures(url) match {
      case Left(features) => features.map(extractKMTKFeature)
      case Right(error) =>
        logger.error("KMTK error: " + error)
        throw new KMTKClientException(error.toString)
    }
  }

  protected def fetchKMTKFeatures(url: String, formparams: util.ArrayList[NameValuePair]): Either[List[Map[String, Any]], KMTKError] = {
    time(logger, s"Fetch KMTK features with url '$url'") {
      val request = new HttpPost(url)
      request.setEntity(new UrlEncodedFormEntity(formparams, "utf-8"))
      val client = HttpClientBuilder.create().build()
      try {
        val response = client.execute(request)
        try {
          mapFields(parse(StreamInput(response.getEntity.getContent)).values.asInstanceOf[Map[String, Any]], url)
        } finally {
          response.close()
          if (response.getStatusLine.getStatusCode >= 300) {
            return Right(KMTKError(Map(("KMTK FETCH failure", "KMTK response code was <300 (unsuccessful)")), url))
          }
        }
      } catch {
        case _: IOException => Right(KMTKError(Map(("KMTK FETCH failure", "IO Exception during KMTK fetch. Check connection to KMTK")), url))
      }
    }
  }

  protected def extractFeatureAttributes(feature: Map[String, Any]): Map[String, Any] = {
    feature("attributes").asInstanceOf[Map[String, Any]]
  }

  protected def extractFeatureGeometry(feature: Map[String, Any]): List[List[Double]] = {
    if (feature.contains("geometry")) {
      val geometry = feature("geometry").asInstanceOf[Map[String, Any]]
      val paths = geometry("paths").asInstanceOf[List[List[List[Double]]]]
      paths.reduceLeft((geom, nextPart) => geom ++ nextPart.tail)
    }
    else List.empty
  }


  protected def extractModifiedAt(attributes: Map[String, Any]): Option[DateTime] = {
    def compareDateMillisOptions(a: Option[Long], b: Option[Long]): Option[Long] = {
      (a, b) match {
        case (Some(firstModifiedAt), Some(secondModifiedAt)) =>
          Some(Math.max(firstModifiedAt, secondModifiedAt))
        case (Some(firstModifiedAt), None) => Some(firstModifiedAt)
        case (None, Some(secondModifiedAt)) => Some(secondModifiedAt)
        case (None, None) => None
      }
    }

    val validFromDate = Option(attributes("VALIDFROM").asInstanceOf[BigInt]).map(_.toLong)
    var lastEditedDate: Option[Long] = Option(0)
    if (attributes.contains("LAST_EDITED_DATE")) {
      lastEditedDate = Option(attributes("LAST_EDITED_DATE").asInstanceOf[BigInt]).map(_.toLong)
    }
    var geometryEditedDate: Option[Long] = Option(0)
    if (attributes.contains("GEOMETRY_EDITED_DATE")) {
      geometryEditedDate = Option(attributes("GEOMETRY_EDITED_DATE").asInstanceOf[BigInt]).map(_.toLong)
    }

    val latestDate = compareDateMillisOptions(lastEditedDate, geometryEditedDate)
    latestDate.orElse(validFromDate).map(modifiedTime => new DateTime(modifiedTime))
  }

  /**
    * Extract double value from KMTK data. Used for change info start and end measures.
    */
  protected def extractMeasure(value: Any): Option[Double] = {
    value match {
      case null => None
      case _ => Some(value.toString.toDouble)
    }
  }

  /**
    * Returns KMTK road links by municipality.
    */
  protected def queryByMunicipality(municipality: Int, filter: Option[String] = None): Seq[KMTKType] = {
    val definition = layerDefinition(combineFiltersWithAnd(withMunicipalityFilter(Set(municipality)), filter))
    val url = serviceUrl(definition, queryParameters())
    fetchFeaturesAndLog(url)
  }

  /**
    * Returns KMTK road links in bounding box area. Municipalities are optional.
    */
  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int], filter: Option[String]): Seq[KMTKType] = {
    val definition = layerDefinition(combineFiltersWithAnd(withMunicipalityFilter(municipalities), filter))
    val url = serviceUrl(bounds, definition, queryParameters())
    fetchFeaturesAndLog(url)
  }

  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[KMTKType] = {
    queryByMunicipalitiesAndBounds(bounds, municipalities, None)
  }

}

object KMTKClient {

  val featureClassCodeToFeatureClass: Map[Int, FeatureClass] = Map(
    12316 -> FeatureClass.TractorRoad,
    12141 -> FeatureClass.DrivePath,
    12314 -> FeatureClass.CycleOrPedestrianPath,
    12312 -> FeatureClass.WinterRoads
  )

}

class KMTKClient(kmtkRestApiEndPoint: String) {
  lazy val roadLinkData: KMTKRoadLinkClient = new KMTKRoadLinkClient(kmtkRestApiEndPoint)
  lazy val roadLinkChangeInfo: KMTKChangeInfoClient = new KMTKChangeInfoClient(kmtkRestApiEndPoint)

  def fetchRoadLinkByUuidAndVersion(id: KMTKID): Option[KMTKRoadlink] = {
    roadLinkData.fetchById(id)
  }

}

class KMTKRoadLinkClient(kmtkRestApiEndPoint: String) extends KMTKClientOperations {

  override type KMTKType = KMTKRoadlink

  protected override val restApiEndPoint: String = kmtkRestApiEndPoint
  protected override val serviceName = "Roadlink_data"
  protected override val linkGeomSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface
  protected override val disableGeometry = false

  // TODO
  protected override def defaultOutFields(): String = {
    "MTKID,UUID,VERSION,MUNICIPALITYCODE,MTKCLASS,ADMINCLASS,DIRECTIONTYPE,CONSTRUCTIONTYPE,ROADNAME_FI,ROADNAME_SM,ROADNAME_SE,LAST_EDITED_DATE,ROADNUMBER,ROADPARTNUMBER,VALIDFROM,GEOMETRY_EDITED_DATE,CREATED_DATE,GEOMETRYLENGTH"
  }

  protected override def mapFields(content: Map[String, Any], url: String): Either[List[Map[String, Any]], KMTKError] = {
    val optionalLayers = content.get("layers").map(_.asInstanceOf[List[Map[String, Any]]])
    val optionalFeatureLayer = optionalLayers.flatMap { layers => layers.find { layer => layer.contains("features") } }
    val optionalFeatures = optionalFeatureLayer.flatMap { featureLayer => featureLayer.get("features").map(_.asInstanceOf[List[Map[String, Any]]]) }
    optionalFeatures.map(_.filter(roadLinkStatusFilter)).map(Left(_)).getOrElse(Right(KMTKError(content, url)))
  }

  /**
    * Constructions Types Allows to return
    * In Use - 0
    * Under Construction - 1
    * Planned - 3
    */
  protected def roadLinkStatusFilter(feature: Map[String, Any]): Boolean = {
    val attributes = feature("attributes").asInstanceOf[Map[String, Any]]
    val linkStatus = extractAttributes(attributes).getOrElse("CONSTRUCTIONTYPE", BigInt(0)).asInstanceOf[BigInt]
    linkStatus == ConstructionType.InUse.value || linkStatus == ConstructionType.Planned.value || linkStatus == ConstructionType.UnderConstruction.value
  }


  /**
    * Returns KMTK road links in bounding box area. Municipalities are optional.
    */
  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, roadNumbers: Seq[(Int, Int)], municipalities: Set[Int] = Set(), includeAllPublicRoads: Boolean = false): Seq[KMTKRoadlink] = {
    val roadNumberFilters = if (roadNumbers.nonEmpty || includeAllPublicRoads)
      Some(withRoadNumbersFilter(roadNumbers, includeAllPublicRoads))
    else
      None
    queryByMunicipalitiesAndBounds(bounds, municipalities, roadNumberFilters)
  }

  /**
    * Returns KMTK road links by municipality.
    */
  def queryByRoadNumbersAndMunicipality(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[KMTKRoadlink] = {
    val roadNumberFilters = withRoadNumbersFilter(roadNumbers, includeAllPublicRoads = true)
    val definition = layerDefinition(combineFiltersWithAnd(withMunicipalityFilter(Set(municipality)), roadNumberFilters))
    val url = serviceUrl(definition, queryParameters())
    fetchFeaturesAndLog(url)
  }

  /**
    * Returns KMTK road links.
    */
  protected def queryByIds[T](ids: Set[KMTKID],
                              fieldSelection: Option[String],
                              fetchGeometry: Boolean,
                              resultTransition: (Map[String, Any], List[List[Double]]) => T,
                              filter: Set[KMTKID] => String): Seq[T] = {
    val batchSize = 1000
    val idGroups: List[Set[KMTKID]] = ids.grouped(batchSize).toList
    idGroups.par.flatMap { ids =>
      val definition = layerDefinition(filter(ids), fieldSelection)
      val url = serviceUrl(definition, queryParameters(fetchGeometry))

      fetchKMTKFeatures(url) match {
        case Left(features) => features.map { feature =>
          val attributes = extractFeatureAttributes(feature)
          val geometry = if (fetchGeometry) extractFeatureGeometry(feature) else Nil
          resultTransition(attributes, geometry)
        }
        case Right(error) =>
          logger.error("KMTK error: " + error)
          throw new KMTKClientException(error.toString)
      }
    }.toList
  }

  // Extract attributes methods

  protected override def extractKMTKFeature(feature: Map[String, Any]): KMTKRoadlink = {
    val attributes = extractFeatureAttributes(feature)
    val path = extractFeatureGeometry(feature)
    extractRoadLinkFeature(attributes, path)
  }

  // TODO
  protected def extractRoadLinkFeature(attributes: Map[String, Any], path: List[List[Double]]): KMTKRoadlink = {
    val linkGeometry: Seq[Point] = path.map(point => {
      Point(point(0), point(1))
    })
    val linkGeometryForApi = Map("points" -> path.map(point => Map("x" -> point(0), "y" -> point(1), "z" -> 0.0, "m" -> point(2))))
    val linkGeometryWKTForApi = Map("geometryWKT" -> ("LINESTRING ZM (" + path.map(point => point(0) + " " + point(1) + " " + 0.0 + " " + point(2)).mkString(", ") + ")"))
    val linkId = attributes("LINKID").asInstanceOf[BigInt].longValue()
    val municipalityCode = attributes("MUNICIPALITYCODE").asInstanceOf[BigInt].toInt
    val mtkClass = attributes("MTKCLASS")
    val geometryLength = anyToDouble(attributes("GEOMETRYLENGTH")).getOrElse(0.0)

    val featureClassCode = if (mtkClass != null) // Complementary geometries have no MTK Class
      attributes("MTKCLASS").asInstanceOf[BigInt].intValue()
    else
      0
    val featureClass = KMTKClient.featureClassCodeToFeatureClass.getOrElse(featureClassCode, FeatureClass.AllOthers)

    KMTKRoadlink(linkId, KMTKID("", 0), municipalityCode, linkGeometry, extractAdministrativeClass(attributes),
      extractTrafficDirection(attributes), featureClass, extractModifiedAt(attributes),
      extractAttributes(attributes) ++ linkGeometryForApi ++ linkGeometryWKTForApi, extractConstructionType(attributes), linkGeomSource, geometryLength)

  }

  // TODO
  protected def extractLinkIdFromKMTKFeature(feature: Map[String, Any]): Long = {
    extractFeatureAttributes(feature)("LINKID").asInstanceOf[BigInt].longValue()
  }

  // TODO
  protected def extractAdministrativeClass(attributes: Map[String, Any]): AdministrativeClass = {
    Option(attributes("ADMINCLASS").asInstanceOf[BigInt])
      .map(_.toInt)
      .map(AdministrativeClass.apply)
      .getOrElse(Unknown)
  }

  // TODO
  protected def extractConstructionType(attributes: Map[String, Any]): ConstructionType = {
    Option(attributes("CONSTRUCTIONTYPE").asInstanceOf[BigInt])
      .map(_.toInt)
      .map(ConstructionType.apply)
      .getOrElse(ConstructionType.InUse)
  }

  // TODO
  protected def extractLinkGeomSource(attributes: Map[String, Any]): LinkGeomSource = {
    Option(attributes("LINK_SOURCE").asInstanceOf[BigInt])
      .map(_.toInt)
      .map(LinkGeomSource.apply)
      .getOrElse(LinkGeomSource.Unknown)
  }

  // TODO
  protected def extractTrafficDirection(attributes: Map[String, Any]): TrafficDirection = {
    Option(attributes("DIRECTIONTYPE").asInstanceOf[BigInt])
      .map(_.toInt)
      .map(kmtkTrafficDirectionToTrafficDirection.getOrElse(_, TrafficDirection.UnknownDirection))
      .getOrElse(TrafficDirection.UnknownDirection)
  }

  // TODO
  protected def extractAttributes(attributesMap: Map[String, Any]): Map[String, Any] = {
    attributesMap.filterKeys { x =>
      Set(
        "MTKID",
        "MTKCLASS",
        "CONSTRUCTIONTYPE", //TODO Remove this attribute from here when KMTKHistoryRoadLink have a different way to get the ConstructionType like KMTKRoadlink
        "ROADNAME_FI",
        "ROADNAME_SM",
        "ROADNAME_SE",
        "ROADNUMBER",
        "ROADPARTNUMBER",
        "MUNICIPALITYCODE",
        "VALIDFROM",
        "GEOMETRY_EDITED_DATE",
        "CREATED_DATE",
        "LAST_EDITED_DATE",
        "SUBTYPE",
        "TRACK_CODE" // Used only with suravage and complementary in Viite
      ).contains(x)
    }.filter { case (_, value) =>
      value != null
    }
  }

  // TODO
  // Query filters methods
  protected def withRoadNumberFilter(roadNumbers: (Int, Int), includeAllPublicRoads: Boolean): String = {
    withLimitFilter("ROADNUMBER", roadNumbers._1, roadNumbers._2, includeAllPublicRoads)
  }

  // TODO
  protected def withIdFilter(ids: Set[KMTKID]): String = {
    withFilter("UUID", ids)
  }

  // TODO
  protected def withMmlIdFilter(mmlIds: Set[Long]): String = {
    withFilter("MTKID", mmlIds)
  }

  // TODO
  protected def withMtkClassFilter(ids: Set[Long]): String = {
    withFilter("MTKCLASS", ids)
  }

  protected def withLastEditedDateFilter(lowerDate: DateTime, higherDate: DateTime): String = {
    withDateLimitFilter("LAST_EDITED_DATE", lowerDate, higherDate)
  }

  protected def withDateLimitFilter(attributeName: String, lowerDate: DateTime, higherDate: DateTime): String = {
    val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
    val since = formatter.print(lowerDate)
    val until = formatter.print(higherDate)

    s""""where":"( $attributeName >=date '$since' and $attributeName <=date '$until' )","""
  }


  protected def withRoadNumbersFilter(roadNumbers: Seq[(Int, Int)], includeAllPublicRoads: Boolean, filter: String = ""): String = {
    if (roadNumbers.isEmpty)
      return s""""where":"($filter)","""
    if (includeAllPublicRoads)
      return withRoadNumbersFilter(roadNumbers, includeAllPublicRoads = false, "ADMINCLASS = 1")
    val limit = roadNumbers.head
    val filterAdd = s"""(ROADNUMBER >= ${limit._1} and ROADNUMBER <= ${limit._2})"""
    if (filter == "")
      withRoadNumbersFilter(roadNumbers.tail, includeAllPublicRoads, filterAdd)
    else
      withRoadNumbersFilter(roadNumbers.tail, includeAllPublicRoads, s"""$filter OR $filterAdd""")
  }

  protected val kmtkTrafficDirectionToTrafficDirection: Map[Int, TrafficDirection] = Map(
    0 -> TrafficDirection.BothDirections,
    1 -> TrafficDirection.TowardsDigitizing,
    2 -> TrafficDirection.AgainstDigitizing)

  /**
    * Returns KMTK road links. Obtain all RoadLinks changes between two given dates.
    */
  def fetchByChangesDates(lowerDate: DateTime, higherDate: DateTime): Seq[KMTKRoadlink] = {
    val definition = layerDefinition(withLastEditedDateFilter(lowerDate, higherDate))
    val url = serviceUrl(definition, queryParameters())
    fetchFeaturesAndLog(url)
  }

  /**
    * Returns KMTK road link by linkid
    */
  def fetchById(kmtkId: KMTKID): Option[KMTKRoadlink] = fetchByIds(Set(kmtkId)).headOption

  /**
    * Returns KMTK road links by link ids.
    */
  def fetchByIds(ids: Set[KMTKID]): Seq[KMTKRoadlink] = {
    queryByIds(ids, None, fetchGeometry = true, extractRoadLinkFeature, withIdFilter)
  }

  def fetchByIdsF(ids: Set[KMTKID]): Future[Seq[KMTKRoadlink]] = {
    Future(fetchByIds(ids))
  }

  def fetchByMunicipality(municipality: Int): Seq[KMTKRoadlink] = {
    queryByMunicipality(municipality)
  }

  def fetchByMunicipalityF(municipality: Int): Future[Seq[KMTKRoadlink]] = {
    Future(queryByMunicipality(municipality))
  }

  /**
    * Returns KMTK road links. Uses Scala Future for concurrent operations.
    */
  def fetchByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[KMTKRoadlink] = {
    queryByMunicipalitiesAndBounds(bounds, municipalities)
  }

  def fetchByBounds(bounds: BoundingRectangle): Seq[KMTKRoadlink] = {
    queryByMunicipalitiesAndBounds(bounds, Set[Int]())
  }

  /**
    * Returns KMTK road links. Uses Scala Future for concurrent operations.
    */
  def fetchByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[KMTKRoadlink]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, municipalities))
  }

  /**
    * Returns KMTK road links. Uses Scala Future for concurrent operations.
    */
  def fetchByRoadNumbersBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int], roadNumbers: Seq[(Int, Int)],
                                                 includeAllPublicRoads: Boolean = false): Future[Seq[KMTKRoadlink]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, roadNumbers, municipalities, includeAllPublicRoads))
  }

  /**
    * Returns a sequence of KMTK Road Links. Uses Scala Future for concurrent operations.
    */
  def fetchByMunicipalityAndRoadNumbersF(municipality: Int, roadNumbers: Seq[(Int, Int)]): Future[Seq[KMTKRoadlink]] = {
    Future(queryByRoadNumbersAndMunicipality(municipality, roadNumbers))
  }

  def fetchByMunicipalityAndRoadNumbers(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[KMTKRoadlink] = {
    queryByRoadNumbersAndMunicipality(municipality, roadNumbers)
  }

  /**
    * Returns KMTK road links.
    */
  def fetchKMTKRoadlinks[T](ids: Set[KMTKID],
                            fieldSelection: Option[String],
                            fetchGeometry: Boolean,
                            resultTransition: (Map[String, Any], List[List[Double]]) => T): Seq[T] =
    queryByIds(ids, fieldSelection, fetchGeometry, resultTransition, withIdFilter)

}

class KMTKChangeInfoClient(kmtkRestApiEndPoint: String) extends KMTKClientOperations {
  override type KMTKType = ChangeInfo

  protected override val restApiEndPoint: String = kmtkRestApiEndPoint
  protected override val serviceName = "Roadlink_ChangeInfo"
  protected override val linkGeomSource: LinkGeomSource.Unknown.type = LinkGeomSource.Unknown
  protected override val disableGeometry = true

  // TODO
  protected override def defaultOutFields(): String = {
    "OLD_ID,NEW_ID,MTKID,CHANGETYPE,OLD_START,OLD_END,NEW_START,NEW_END,CREATED_DATE,CONSTRUCTIONTYPE"
  }

  // TODO
  protected override def mapFields(content: Map[String, Any], url: String): Either[List[Map[String, Any]], KMTKError] = {
    val optionalLayers = content.get("layers").map(_.asInstanceOf[List[Map[String, Any]]])
    val optionalFeatureLayer = optionalLayers.flatMap { layers => layers.find { layer => layer.contains("features") } }
    val optionalFeatures = optionalFeatureLayer.flatMap { featureLayer => featureLayer.get("features").map(_.asInstanceOf[List[Map[String, Any]]]) }
    optionalFeatures.map(Left(_)).getOrElse(Right(KMTKError(content, url)))
  }

  // TODO
  protected override def extractKMTKFeature(feature: Map[String, Any]): ChangeInfo = {
    val attributes = extractFeatureAttributes(feature)

    val oldId = Option(attributes("OLD_ID").asInstanceOf[BigInt]).map(_.longValue())
    val newId = Option(attributes("NEW_ID").asInstanceOf[BigInt]).map(_.longValue())
    val mmlId = attributes("MTKID").asInstanceOf[BigInt].longValue()
    val changeType = attributes("CHANGETYPE").asInstanceOf[BigInt].intValue()
    val kmtkTimeStamp = Option(attributes("CREATED_DATE").asInstanceOf[BigInt]).map(_.longValue()).getOrElse(0L)
    val oldStartMeasure = extractMeasure(attributes("OLD_START"))
    val oldEndMeasure = extractMeasure(attributes("OLD_END"))
    val newStartMeasure = extractMeasure(attributes("NEW_START"))
    val newEndMeasure = extractMeasure(attributes("NEW_END"))

    ChangeInfo(oldId, newId, mmlId, ChangeType.apply(changeType), oldStartMeasure, oldEndMeasure, newStartMeasure, newEndMeasure, kmtkTimeStamp)
  }

  def fetchByBoundsAndMunicipalities(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[ChangeInfo] = {
    queryByMunicipalitiesAndBounds(bounds, municipalities)
  }

  def fetchByMunicipality(municipality: Int): Seq[ChangeInfo] = {
    queryByMunicipality(municipality)
  }

  def fetchByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[ChangeInfo]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, municipalities))
  }

  def fetchByMunicipalityF(municipality: Int): Future[Seq[ChangeInfo]] = {
    Future(queryByMunicipality(municipality))
  }

  def fetchByIdsF(ids: Set[KMTKID]): Future[Seq[ChangeInfo]] = {
    Future(fetchByIds(ids))
  }

  /**
    * Fetch change information where given link id is in the old_id list (source)
    *
    * @param ids Link ids to check as sources
    * @return ChangeInfo for given links
    */
  // TODO
  def fetchByIds(ids: Set[KMTKID]): Seq[ChangeInfo] = {
    queryByIds(ids, "OLD_ID")
  }

  /**
    * Fetch change information where given link id is in the new_id list (source)
    *
    * @param ids Link ids to check as sources
    * @return ChangeInfo for given links
    */
  // TODO
  def fetchByNewIds(ids: Set[KMTKID]): Seq[ChangeInfo] = {
    queryByIds(ids, "NEW_ID")
  }

  protected def queryByIds(ids: Set[KMTKID], field: String): Seq[ChangeInfo] = {
    val batchSize = 1000
    val idGroups: List[Set[KMTKID]] = ids.grouped(batchSize).toList
    idGroups.par.flatMap { ids =>
      val definition = layerDefinition(withFilter(field, ids))
      val url = serviceUrl(definition, queryParameters(false))
      fetchFeaturesAndLog(url)
    }.toList
  }
}