package fi.liikennevirasto.digiroad2.client.vvh

import java.io.IOException
import java.net.URLEncoder
import java.util

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.linearasset.{KMTKID, RoadLinkLike}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import org.apache.http.NameValuePair
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.{CloseableHttpResponse, HttpGet, HttpPost}
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

sealed trait FeatureClass
object FeatureClass {
  case object TractorRoad extends FeatureClass
  case object DrivePath extends FeatureClass
  case object CycleOrPedestrianPath extends FeatureClass
  case object WinterRoads extends FeatureClass
  case object AllOthers extends FeatureClass
}

case class VVHRoadlink(linkId: Long, kmtkId: KMTKID, municipalityCode: Int, geometry: Seq[Point],
                       administrativeClass: AdministrativeClass, trafficDirection: TrafficDirection,
                       featureClass: FeatureClass, modifiedAt: Option[DateTime] = None, attributes: Map[String, Any] = Map(),
                       constructionType: LifecycleStatus = LifecycleStatus.InUse, linkSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface, length: Double = 0.0) extends RoadLinkLike {
  def roadNumber: Option[String] = attributes.get("ROADNUMBER").map(_.toString)
  val timeStamp: Long = attributes.getOrElse("LAST_EDITED_DATE", attributes.getOrElse("CREATED_DATE", BigInt(0))).asInstanceOf[BigInt].longValue()
}

object VVHClient {
  val featureClassCodeToFeatureClass: Map[Int, FeatureClass] = Map(
    12316 -> FeatureClass.TractorRoad,
    12141 -> FeatureClass.DrivePath,
    12314 -> FeatureClass.CycleOrPedestrianPath,
    12312 -> FeatureClass.WinterRoads
  )
}

class VVHClient(vvhRestApiEndPoint: String) {
  lazy val complementaryData: VVHComplementaryClient = new VVHComplementaryClient(vvhRestApiEndPoint)
}

trait VVHClientOperations {

  type VVHType

  protected val linkGeomSource: LinkGeomSource

  protected def restApiEndPoint: String

  protected def serviceName: String

  protected val disableGeometry: Boolean

  case class VVHError(content: Map[String, Any], url: String)

  class VVHClientException(response: String) extends RuntimeException(response)

  protected implicit val jsonFormats: Formats = DefaultFormats

  protected def mapFields(content: Map[String, Any], url: String): Either[List[Map[String, Any]], VVHError]

  protected def defaultOutFields(): String

  protected def extractVVHFeature(feature: Map[String, Any]): VVHType

  lazy val logger: Logger = LoggerFactory.getLogger(getClass)

  protected def anyToDouble(number: Any): Option[Double] = number match {
    case bi: BigInt => Some(bi.toDouble)
    case i: Int => Some(i.toDouble)
    case l: Long => Some(l.toDouble)
    case d: Double => Some(d)
    case _ => None
  }

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

  protected def withMunicipalityFilter(municipalities: Set[Int]): String = {
    withFilter("MUNICIPALITYCODE", municipalities)
  }

  protected def combineFiltersWithAnd(filter1: String, filter2: String): String = {

    (filter1.isEmpty, filter2.isEmpty) match {
      case (true, true) => ""
      case (true, false) => filter2
      case (false, true) => filter1
      case (false, false) => "%s AND %s".format(filter1.dropRight(2), filter2.replace("\"where\":\"", ""))
    }
  }

  protected def combineFiltersWithAnd(filter1: String, filter2: Option[String]): String = {
    combineFiltersWithAnd(filter2.getOrElse(""), filter1)
  }

  protected def queryParameters(fetchGeometry: Boolean = true): String = {
    if (fetchGeometry && !disableGeometry) "returnGeometry=true&returnZ=true&returnM=true&geometryPrecision=3&f=json"
    else "returnGeometry=false&f=json"
  }

  protected def serviceUrl: String = restApiEndPoint + serviceName + "/FeatureServer/query"

  protected def serviceUrl(bounds: BoundingRectangle, definition: String, parameters: String): String = {
    serviceUrl +
      s"?layerDefs=$definition&geometry=" + bounds.leftBottom.x + "," + bounds.leftBottom.y + "," + bounds.rightTop.x + "," + bounds.rightTop.y +
      s"&geometryType=esriGeometryEnvelope&spatialRel=esriSpatialRelIntersects&$parameters"

  }

  protected def serviceUrl(definition: String, parameters: String): String = {
    serviceUrl +
      s"?layerDefs=$definition&" + parameters
  }

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

  protected def layerDefinition(filter: String, customFieldSelection: Option[String] = None): String = {
    URLEncoder.encode(layerDefinitionWithoutEncoding(filter, customFieldSelection), "UTF-8")
  }

  protected def fetchVVHFeatures(url: String): Either[List[Map[String, Any]], VVHError] = {
    time(logger, s"Fetch VVH features with url '$url'") {
      val request = new HttpGet(url)
      val client = HttpClientBuilder.create().build()
      var response: CloseableHttpResponse = null
      try {
        response = client.execute(request)
        mapFields(parse(StreamInput(response.getEntity.getContent)).values.asInstanceOf[Map[String, Any]], url)
      } catch {
        case _: IOException => Right(VVHError(Map(("VVH FETCH failure", "IO Exception during VVH fetch. Check connection to VVH")), url))
      } finally {
        if (response != null)
          response.close()
        if (response.getStatusLine.getStatusCode >= 300) {
          return Right(VVHError(Map(("VVH FETCH failure", "VVH response code was <300 (unsuccessful)")), url))
        }
      }
    }
  }

  protected def fetchFeaturesAndLog(url: String): Seq[VVHType] = {
    fetchVVHFeatures(url) match {
      case Left(features) => features.map(extractVVHFeature)
      case Right(error) =>
        logger.error("VVH error: " + error)
        throw new VVHClientException(error.toString)
    }
  }

  protected def fetchVVHFeatures(url: String, formparams: util.ArrayList[NameValuePair]): Either[List[Map[String, Any]], VVHError] = {
    time(logger, s"Fetch VVH features with url '$url'") {
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
          return  Right(VVHError(Map(("VVH FETCH failure", "VVH response code was <300 (unsuccessful)")), url))
          }
        }
      } catch {
        case _: IOException => Right(VVHError(Map(("VVH FETCH failure", "IO Exception during VVH fetch. Check connection to VVH")), url))
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
    * Extract double value from VVH data. Used for change info start and end measures.
    */
  protected def extractMeasure(value: Any): Option[Double] = {
    value match {
      case null => None
      case _ => Some(value.toString.toDouble)
    }
  }

  /**
    * Returns VVH road links by municipality.
    */
  protected def queryByMunicipality(municipality: Int, filter: Option[String] = None): Seq[VVHType] = {
    val definition = layerDefinition(combineFiltersWithAnd(withMunicipalityFilter(Set(municipality)), filter))
    val url = serviceUrl(definition, queryParameters())
    fetchFeaturesAndLog(url)
  }

  /**
    * Returns VVH road links in bounding box area. Municipalities are optional.
    */
  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int], filter: Option[String]): Seq[VVHType] = {
    val definition = layerDefinition(combineFiltersWithAnd(withMunicipalityFilter(municipalities), filter))
    val url = serviceUrl(bounds, definition, queryParameters())
    fetchFeaturesAndLog(url)
  }

  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[VVHType] = {
    queryByMunicipalitiesAndBounds(bounds, municipalities, None)
  }

}

class VVHRoadLinkClient(vvhRestApiEndPoint: String) extends VVHClientOperations {

  override type VVHType = VVHRoadlink

  protected override val restApiEndPoint: String = vvhRestApiEndPoint
  protected override val serviceName = "Roadlink_data"
  protected override val linkGeomSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface
  protected override val disableGeometry = false

  protected override def defaultOutFields(): String = {
    "MTKID,LINKID,MUNICIPALITYCODE,MTKCLASS,ADMINCLASS,DIRECTIONTYPE,CONSTRUCTIONTYPE,ROADNAME_FI,ROADNAME_SM,ROADNAME_SE,LAST_EDITED_DATE,ROADNUMBER,ROADPARTNUMBER,VALIDFROM,GEOMETRY_EDITED_DATE,CREATED_DATE,GEOMETRYLENGTH"
  }

  protected override def mapFields(content: Map[String, Any], url: String): Either[List[Map[String, Any]], VVHError] = {
    val optionalLayers = content.get("layers").map(_.asInstanceOf[List[Map[String, Any]]])
    val optionalFeatureLayer = optionalLayers.flatMap { layers => layers.find { layer => layer.contains("features") } }
    val optionalFeatures = optionalFeatureLayer.flatMap { featureLayer => featureLayer.get("features").map(_.asInstanceOf[List[Map[String, Any]]]) }
    optionalFeatures.map(_.filter(roadLinkStatusFilter)).map(Left(_)).getOrElse(Right(VVHError(content, url)))
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
    linkStatus == LifecycleStatus.InUse.value || linkStatus == LifecycleStatus.Planned.value || linkStatus == LifecycleStatus.UnderConstruction.value
  }

  /**
    * Returns VVH road links by municipality.
    * Used by VVHClient.fetchByMunicipalityAndRoadNumbersF(municipality, roadNumbers) and
    * RoadLinkService.getViiteRoadLinksFromVVH(municipality, roadNumbers).
    */
  def queryByRoadNumbersAndMunicipality(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[VVHRoadlink] = {
    val roadNumberFilters = withRoadNumbersFilter(roadNumbers, includeAllPublicRoads = true)
    val definition = layerDefinition(combineFiltersWithAnd(withMunicipalityFilter(Set(municipality)), roadNumberFilters))
    val url = serviceUrl(definition, queryParameters())
    fetchFeaturesAndLog(url)
  }

  /**
    * Returns VVH road links.
    */
  protected def queryByLinkIds[T](linkIds: Iterable[String],
                                  fieldSelection: Option[String],
                                  fetchGeometry: Boolean,
                                  resultTransition: (Map[String, Any], List[List[Double]]) => T,
                                  filter: Set[String] => String): Seq[T] = {
    val batchSize = 1000
    val idGroups: List[Set[String]] = linkIds.toSet.grouped(batchSize).toList
    idGroups.par.flatMap { ids =>
      val definition = layerDefinition(filter(ids), fieldSelection)
      val url = serviceUrl(definition, queryParameters(fetchGeometry))

      fetchVVHFeatures(url) match {
        case Left(features) => features.map { feature =>
          val attributes = extractFeatureAttributes(feature)
          val geometry = if (fetchGeometry) extractFeatureGeometry(feature) else Nil
          resultTransition(attributes, geometry)
        }
        case Right(error) =>
          logger.error("VVH error: " + error)
          throw new VVHClientException(error.toString)
      }
    }.toList
  }

  // Extract attributes methods

  protected override def extractVVHFeature(feature: Map[String, Any]): VVHRoadlink = {
    val attributes = extractFeatureAttributes(feature)
    val path = extractFeatureGeometry(feature)
    extractRoadLinkFeature(attributes, path)
  }

  protected def extractRoadLinkFeature(attributes: Map[String, Any], path: List[List[Double]]): VVHRoadlink = {
    val linkGeometry: Seq[Point] = path.map(point => {
      Point(point(0), point(1), extractMeasure(point(2)).get)
    })
    val linkGeometryForApi = Map("points" -> path.map(point => Map("x" -> point(0), "y" -> point(1), "z" -> point(2), "m" -> point(3))))
    val linkGeometryWKTForApi = Map("geometryWKT" -> ("LINESTRING ZM (" + path.map(point => point(0) + " " + point(1) + " " + point(2) + " " + point(3)).mkString(", ") + ")"))
    val linkId = attributes("LINKID").asInstanceOf[BigInt].longValue()
    val municipalityCode = attributes("MUNICIPALITYCODE").asInstanceOf[BigInt].toInt
    val mtkClass = attributes("MTKCLASS")
    val geometryLength = anyToDouble(attributes("GEOMETRYLENGTH")).getOrElse(0.0)

    val featureClassCode = if (mtkClass != null) // Complementary geometries have no MTK Class
      attributes("MTKCLASS").asInstanceOf[BigInt].intValue()
    else
      0
    val featureClass = VVHClient.featureClassCodeToFeatureClass.getOrElse(featureClassCode, FeatureClass.AllOthers)

    VVHRoadlink(0L, KMTKID(s"$linkId"), municipalityCode, linkGeometry, extractAdministrativeClass(attributes),
      extractTrafficDirection(attributes), featureClass, extractModifiedAt(attributes),
      extractAttributes(attributes) ++ linkGeometryForApi ++ linkGeometryWKTForApi, extractConstructionType(attributes), linkGeomSource, geometryLength)

  }

  protected def extractLinkIdFromVVHFeature(feature: Map[String, Any]): Long = {
    extractFeatureAttributes(feature)("LINKID").asInstanceOf[BigInt].longValue()
  }

  protected def extractAdministrativeClass(attributes: Map[String, Any]): AdministrativeClass = {
    Option(attributes("ADMINCLASS").asInstanceOf[BigInt])
      .map(_.toInt)
      .map(AdministrativeClass.apply)
      .getOrElse(Unknown)
  }

  protected def extractConstructionType(attributes: Map[String, Any]): LifecycleStatus = {
    Option(attributes("CONSTRUCTIONTYPE").asInstanceOf[BigInt])
      .map(_.toInt)
      .map(LifecycleStatus.apply)
      .getOrElse(LifecycleStatus.InUse)
  }

  protected def extractLinkGeomSource(attributes: Map[String, Any]): LinkGeomSource = {
    Option(attributes("LINK_SOURCE").asInstanceOf[BigInt])
      .map(_.toInt)
      .map(LinkGeomSource.apply)
      .getOrElse(LinkGeomSource.Unknown)
  }

  protected def extractTrafficDirection(attributes: Map[String, Any]): TrafficDirection = {
    Option(attributes("DIRECTIONTYPE").asInstanceOf[BigInt])
      .map(_.toInt)
      .map(vvhTrafficDirectionToTrafficDirection.getOrElse(_, TrafficDirection.UnknownDirection))
      .getOrElse(TrafficDirection.UnknownDirection)
  }

  protected def extractAttributes(attributesMap: Map[String, Any]): Map[String, Any] = {
    attributesMap.filterKeys { x =>
      Set(
        "MTKID",
        "MTKCLASS",
        "CONSTRUCTIONTYPE", //TODO Remove this attribute from here when VVHHistoryRoadLink have a different way to get the ConstructionType like VVHRoadlink
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

  // Query filters methods
  protected def withLinkIdFilter(linkIds: Set[String]): String = {
    withFilter("LINKID", linkIds)
  }

  protected def withMtkClassFilter(ids: Set[Long]): String = {
    withFilter("MTKCLASS", ids)
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

  protected val vvhTrafficDirectionToTrafficDirection: Map[Int, TrafficDirection] = Map(
    0 -> TrafficDirection.BothDirections,
    1 -> TrafficDirection.TowardsDigitizing,
    2 -> TrafficDirection.AgainstDigitizing)

  /**
    * Returns VVH road link by linkid
    * Used by Digiroad2Api.createMassTransitStop, Digiroad2Api.validateUserRights, Digiroad2Api &#47;manoeuvres DELETE endpoint, Digiroad2Api manoeuvres PUT endpoint,
    * CsvImporter.updateAssetByExternalIdLimitedByRoadType, RoadLinkService,getRoadLinkMiddlePointByLinkId, RoadLinkService.updateLinkProperties, RoadLinkService.getRoadLinkGeometry,
    * RoadLinkService.updateAutoGeneratedProperties, LinearAssetService.split, LinearAssetService.separate, MassTransitStopService.fetchRoadLink, PointAssetOperations.getById
    * and OracleLinearAssetDao.createSpeedLimit.
    */
  def fetchByLinkId(linkId: String): Option[VVHRoadlink] = fetchByLinkIds(Set(linkId)).headOption

  /**
    * Returns VVH road links by link ids.
    * Used by VVHClient.fetchByLinkId, RoadLinkService.fetchVVHRoadlinks, SpeedLimitService.purgeUnknown, PointAssetOperations.getFloatingAssets,
    * OracleLinearAssetDao.getLinksWithLengthFromVVH and OracleLinearAssetDao.getSpeedLimitLinksById
    */
  def fetchByLinkIds(linkIds: Iterable[String]): Seq[VVHRoadlink] = {
    queryByLinkIds(linkIds, None, fetchGeometry = true, extractRoadLinkFeature, withLinkIdFilter)
  }

  def fetchByLinkIdsF(linkIds: Iterable[String]): Future[Seq[VVHRoadlink]] = {
    Future(fetchByLinkIds(linkIds))
  }

  def fetchByMunicipalityF(municipality: Int): Future[Seq[VVHRoadlink]] = {
    Future(queryByMunicipality(municipality))
  }

  /**
    * Returns VVH road links. Uses Scala Future for concurrent operations.
    * Used by RoadLinkService.getRoadLinksAndChangesFromVVH(bounds, municipalities),
    * RoadLinkService.getViiteRoadLinksAndChangesFromVVH(bounds, roadNumbers, municipalities, everything, publicRoads).
    */
  def fetchByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[VVHRoadlink]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, municipalities))
  }

  /**
    * Returns a sequence of VVH Road Links. Uses Scala Future for concurrent operations.
    * Used by RoadLinkService.getViiteCurrentAndComplementaryRoadLinksFromVVH(municipality, roadNumbers).
    */
  def fetchByMunicipalityAndRoadNumbersF(municipality: Int, roadNumbers: Seq[(Int, Int)]): Future[Seq[VVHRoadlink]] = {
    Future(queryByRoadNumbersAndMunicipality(municipality, roadNumbers))
  }

}

class VVHComplementaryClient(vvhRestApiEndPoint: String) extends VVHRoadLinkClient(vvhRestApiEndPoint) {

  protected override val restApiEndPoint: String = vvhRestApiEndPoint
  protected override val serviceName = "Roadlink_complimentary"
  protected override val linkGeomSource: LinkGeomSource = LinkGeomSource.ComplementaryLinkInterface
  protected override val disableGeometry = false

  override def defaultOutFields(): String = {
    "MTKID,LINKID,MUNICIPALITYCODE,MTKCLASS,ADMINCLASS,DIRECTIONTYPE,ROADNAME_FI,ROADNAME_SM,ROADNAME_SE,LAST_EDITED_DATE,ROADNUMBER,ROADPARTNUMBER,VALIDFROM,GEOMETRY_EDITED_DATE,CREATED_DATE,SUBTYPE,CONSTRUCTIONTYPE,GEOMETRYLENGTH,TRACK_CODE"
  }

  def fetchWalkwaysByBoundsAndMunicipalities(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[VVHRoadlink] = {
    queryByMunicipalitiesAndBounds(bounds, municipalities, Some(withMtkClassFilter(Set(12314))))
  }

  def fetchComplementaryByMunicipalitiesF(municipality: Int): Future[Seq[VVHRoadlink]] =
    Future(queryByMunicipality(municipality))

}
