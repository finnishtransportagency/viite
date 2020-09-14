package fi.liikennevirasto.digiroad2.client.kmtk

import java.io.{IOException, InputStream}
import java.net.URLEncoder

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.client.kmtk._
import fi.liikennevirasto.digiroad2.linearasset.{KMTKID, RoadLinkLike}
import fi.liikennevirasto.digiroad2.util.KMTKAuthPropertyReader
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.{Point, PointM}
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.{CloseableHttpClient, HttpClientBuilder}
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.json4s.jackson.JsonMethods.parse
import org.json4s.{DefaultFormats, Formats, StreamInput}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class KMTKGeometry(/*`type`: String, */ coordinates: Seq[Seq[Double]]) {

  def toPoints: Seq[Point] = {
    coordinates.map(c => if (c.isEmpty) {
      None
    } else {
      Some(Point(c.head, c(1))) // Ignore z-value, since that data has errors
    }).filter(p => p.isDefined).map(p => p.get)
  }

  def toPointsWithM: Seq[PointM] = {
    val points = coordinates.map(c => if (c.isEmpty) {
      None
    } else {
      Some(PointM(c.head, c(1))) // Ignore z-value, since that data has errors
    }).filter(p => p.isDefined).map(p => p.get)

    // Calculate m-values
    for (i <- points.indices) {
      points(i).m = if (i > 0) {
        points(i - 1).m + points(i - 1).distance2DTo(points(i))
      } else {
        0.0
      }
    }

    points
  }

}

case class KMTKAddress(fromLeft: Option[Long], toLeft: Option[Long], fromRight: Option[Long], toRight: Option[Long])

case class KMTKNode(/*`type`: String, */ coordinates: Seq[Double]) {
  def toPoint: Option[Point] = {
    if (coordinates.isEmpty) {
      None
    } else {
      Some(Point(coordinates.head, coordinates(1), coordinates(2)))
    }
  }
}

case class KMTKProperties(featureClass: String, kmtkId: String, version: Long, startTime: String, endTime: Option[String],
                          versionStartTime: String, versionEndTime: Option[String], updateReason: Option[String],
                          state: Option[String], dataSource: Long, municipalityCode: Int, adminClass: Option[Int],
                          roadNumber: Option[Long], roadPartNumber: Option[Long], roadClass: Int,
                          roadNameFin: Option[String], roadNameSwe: Option[String], roadNameSmn: Option[String], roadNameSms: Option[String], roadNameSme: Option[String],
                          surfaceType: Int, surfaceRelation: Int, lifecycleStatus: Int, directionType: Int,
                          sourceModificationTime: String, addressFromLeft: Option[Long], addressToLeft: Option[Long],
                          addressFromRight: Option[Long], addressToRight: Option[Long], sourceId: String,
                          geometryFlip: Boolean, horizontalLength: Double, widthType: Option[Int], xyAccuracy: Long, zAccuracy: Long) {

  def createdAtAsDateTime: Option[DateTime] = {
    parseDateOption(versionStartTime)
  }

  def modifiedAtAsDateTime: Option[DateTime] = {
    parseDateOption(versionStartTime)
  }

  def sourceStartDateAsDateTime: Option[DateTime] = {
    parseDateOption(startTime)
  }

  def endedAtAsDateTime: Option[DateTime] = {
    parseDateOption(endTime)
  }

  private def parseDateOption(date: Option[String]): Option[DateTime] = {
    if (date.isDefined) {
      parseDateOption(date.get)
    } else {
      None
    }
  }

  private def parseDateOption(date: String): Option[DateTime] = {
    try {
      Some(DateTime.parse(date))
    } catch {
      case _: Exception => None
    }
  }
}

case class KMTKFeature(geometry: KMTKGeometry, properties: KMTKProperties)

case class KMTKFeatureCollection(/*`type`: String, */ features: Seq[KMTKFeature])

case class KMTKRoadLink(linkId: Long, kmtkId: KMTKID = KMTKID("", 0), municipalityCode: Int, geometry: Seq[Point],
                        administrativeClass: AdministrativeClass, trafficDirection: TrafficDirection,
                        featureClass: FeatureClass, modifiedAt: Option[DateTime] = None, attributes: Map[String, Any] = Map(),
                        lifecycleStatus: LifecycleStatus = LifecycleStatus.InUse,
                        linkSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface, length: Double = 0.0) extends RoadLinkLike {

  def roadNumber: Option[String] = attributes.get("ROADNUMBER").map(_.toString)

  val timeStamp: Long = attributes.getOrElse("LAST_EDITED_DATE", attributes.getOrElse("CREATED_DATE", BigInt(0))).asInstanceOf[BigInt].longValue()
}

case class KMTKHistoryRoadLink(linkId: Long, kmtkId: KMTKID, municipalityCode: Int, geometry: Seq[Point], administrativeClass: AdministrativeClass,
                               trafficDirection: TrafficDirection, featureClass: FeatureClass, createdDate: BigInt, endDate: BigInt, attributes: Map[String, Any] = Map(),
                               lifecycleStatus: LifecycleStatus = LifecycleStatus.InUse, linkSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface, length: Double = 0.0) extends RoadLinkLike {
  def roadNumber: Option[String] = attributes.get("ROADNUMBER").map(_.toString)

  val timeStamp: Long = attributes.getOrElse("LAST_EDITED_DATE", createdDate).asInstanceOf[BigInt].longValue()
}

class KMTKClientException(message: String) extends RuntimeException(message)

trait KMTKClientOperations {

  type KMTKType

  protected implicit val jsonFormats: Formats = DefaultFormats

  protected val dateTimeFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSZZ" // 2019-06-15T15:22:26.762+03:00

  protected val dateTimeFormatter: DateTimeFormatter = DateTimeFormat.forPattern(dateTimeFormat)

  private val auth = new KMTKAuthPropertyReader

  protected val linkGeomSource: LinkGeomSource

  protected def restApiEndPoint: String

  protected def serviceName: String

  case class KMTKError(url: String, message: String) {
    override def toString(): String = s"$message URL: '$url'"
  }

  lazy val logger: Logger = LoggerFactory.getLogger(getClass)

  protected def anyToDouble(number: Any): Option[Double] = number match {
    case bi: BigInt => Some(bi.toDouble)
    case i: Int => Some(i.toDouble)
    case l: Long => Some(l.toDouble)
    case d: Double => Some(d)
    case _ => None
  }

  protected def dateToEpoch(date: String): BigInt = {
    if (date != null) {
      val parsed = DateTime.parse(date, dateTimeFormatter)
      parsed.toDate.getTime
    } else {
      null
    }
  }

  private def bboxParam(bounds: BoundingRectangle): String = {
    "bbox=" + URLEncoder.encode(
      s"""{"minX":${bounds.leftBottom.x},"minY":${bounds.leftBottom.y},"maxX":${bounds.rightTop.x},"maxY":${bounds.rightTop.y}}""",
      "UTF-8")
  }

  private def mcodeParam(municipality: Int): String = {
    s"""municipalityCode=$municipality"""
  }

  private def linklistParam(kmtkIds: Iterable[KMTKID]): String = {
    "links=" + URLEncoder.encode(
      s"[${kmtkIds.map(k => s"""{"kmtkId":"${k.uuid}"}""").mkString(",")}]",
      "UTF-8")
  }

  private def serviceUrlByBoundingBox(bounds: BoundingRectangle): String = {
    val bbox = bboxParam(bounds)
    restApiEndPoint + serviceName + s"/items?$bbox&bbox-crs=http://www.opengis.net/def/crs/EPSG/0/3067&crs=http://www.opengis.net/def/crs/EPSG/0/3067"
  }

  private def serviceUrlByMunicipality(municipality: Int): String = {
    val mcode = mcodeParam(municipality)
    restApiEndPoint + serviceName + s"/items?$mcode"
  }

  protected def serviceUrlByIds(kmtkIds: Iterable[KMTKID]): String = {
    val linklist: String = linklistParam(kmtkIds)
    restApiEndPoint + serviceName + s"/items?$linklist"
  }

  private def serviceUrlByBoundingBoxAndMunicipalities(bounds: BoundingRectangle, municipality: Option[Int]): String = {
    val bbox = bboxParam(bounds)
    val mcode = if (municipality.isDefined) "&" + mcodeParam(municipality.get) else ""
    restApiEndPoint + serviceName + s"/items?$bbox&bbox-crs=http://www.opengis.net/def/crs/EPSG/0/3067&crs=http://www.opengis.net/def/crs/EPSG/0/3067$mcode"
  }

  protected def createHttpClient: CloseableHttpClient = {
    HttpClientBuilder.create().build()
  }

  protected def fetchKMTKFeatures(url: String): Either[KMTKFeatureCollection, KMTKError] = {
    time(logger, s"Fetch KMTK features with url '$url'") {
      val request = new HttpGet(url)
      request.addHeader("Authorization", "Basic " + auth.getAuthInBase64)
      val client = createHttpClient
      try {
        val response = client.execute(request)
        try {
          val content = response.getEntity.getContent
          val featureCollection: Option[KMTKFeatureCollection] = inputStreamToFeatureCollection(content)
          if (featureCollection.isDefined) {
            Left(featureCollection.get)
          } else {
            Right(KMTKError(url, "Failed to parse FeatureCollection from KMTK response."))
          }
        } finally {
          response.close()
          if (response.getStatusLine.getStatusCode >= 300) {
            return Right(KMTKError(url,
              s"KMTK FETCH failure. KMTK response code was ${response.getStatusLine.getStatusCode} (${response.getStatusLine.getReasonPhrase})"))
          }
        }
      } catch {
        case _: IOException => Right(KMTKError(url, "KMTK FETCH failure. IO Exception during KMTK fetch. Check connection to KMTK"))
      }
    }
  }

  private[kmtk] def inputStreamToFeatureCollection(content: InputStream): Option[KMTKFeatureCollection] = {
    val json = parse(StreamInput(content))
    json.extractOpt[KMTKFeatureCollection]
  }

  protected def fetchFeaturesAndLog(url: String): Seq[KMTKFeature] = {
    fetchKMTKFeatures(url) match {
      case Left(featureCollection) => featureCollection.features
      case Right(error) =>
        logger.error("KMTK error: " + error)
        throw new KMTKClientException(error.toString)
    }
  }

  /**
    * Returns KMTK road links by municipality.
    */
  protected def queryByMunicipality(municipality: Int): Seq[KMTKFeature] = {
    val url = serviceUrlByMunicipality(municipality)
    fetchFeaturesAndLog(url)
  }

  /**
    * Returns KMTK road links by bounding box.
    *
    * @param bounds BoundingRectangle
    * @return
    */
  protected def queryByBounds(bounds: BoundingRectangle): Seq[KMTKFeature] = {
    val url = serviceUrlByBoundingBox(bounds)
    fetchFeaturesAndLog(url)
  }

  /**
    * Returns KMTK road links in bounding box area. Municipalities are optional.
    */
  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipality: Option[Int]): Seq[KMTKFeature] = {
    val url = serviceUrlByBoundingBoxAndMunicipalities(bounds, municipality)
    fetchFeaturesAndLog(url)
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

  def fetchRoadLinkByUuidAndVersion(id: KMTKID): Option[KMTKRoadLink] = {
    roadLinkData.fetchById(id)
  }

}

class KMTKRoadLinkClient(kmtkRestApiEndPoint: String) extends KMTKClientOperations {

  override type KMTKType = KMTKRoadLink

  protected override val restApiEndPoint: String = kmtkRestApiEndPoint
  protected override val serviceName = "road_links"
  protected override val linkGeomSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface

  def fetchByMtkId(mtkId: Long): Seq[KMTKRoadLink] = {
    throw new NotImplementedError("Fetching by MTKID not implemented yet.")
  }

  /**
    * Constructions Types Allows to return
    * In Use - 0
    * Under Construction - 1
    * Planned - 3
    */
  // TODO Is this needed?
//  protected def roadLinkStatusFilter(properties: KMTKProperties): Boolean = {
//    val linkStatus = properties.lifecycleStatus
//    linkStatus == LifecycleStatus.InUse.value || linkStatus == LifecycleStatus.Planned.value || linkStatus == LifecycleStatus.UnderConstruction.value
//  }

  /**
    * Returns KMTK road links in bounding box area. Municipalities are optional.
    */
  // TODO filtering by road number ranges
  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, roadNumbers: Seq[(Int, Int)], municipality: Option[Int] = None, includeAllPublicRoads: Boolean = false): Seq[KMTKFeature] = {
    /*val roadNumberFilters = */ if (roadNumbers.nonEmpty || includeAllPublicRoads)
      throw new NotImplementedError("Filtering by road number not supported yet.")
    // Some(withRoadNumbersFilter(roadNumbers, includeAllPublicRoads))
    else
      None
    queryByMunicipalitiesAndBounds(bounds, municipality /*, roadNumberFilters*/)
  }

  // TODO
  def queryByRoadNumbersAndMunicipality(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[KMTKRoadLink] = {
    throw new NotImplementedError()
    //val roadNumberFilters = withRoadNumbersFilter(roadNumbers, includeAllPublicRoads = true)
    //val definition = layerDefinition(combineFiltersWithAnd(withMunicipalityFilter(Set(municipality)), roadNumberFilters))
    //val url = serviceUrl(definition, queryParameters())
    //fetchFeaturesAndLog(url)
  }

  /**
    * Returns KMTK road links.
    */
  protected def queryByIds[T](ids: Iterable[KMTKID], resultTransition: KMTKFeature => T): Seq[T] = {
    val batchSize = 10
    val idGroups: List[Set[KMTKID]] = ids.toSet.grouped(batchSize).toList
    idGroups.par.flatMap { ids =>
      val url = serviceUrlByIds(ids)
      fetchKMTKFeatures(url) match {
        case Left(featureCollection) => featureCollection.features.map { feature =>
          resultTransition(feature)
        }
        case Right(error) =>
          logger.error("KMTK error: " + error)
          throw new KMTKClientException(error.toString)
      }
    }.toList
  }

  protected def kmtkFeatureToKMTKRoadLink(feature: KMTKFeature): KMTKRoadLink = {
    val linkGeometryWithM: Seq[PointM] = feature.geometry.toPointsWithM
    val linkGeometryForApi = Map("points" -> linkGeometryWithM.map(point => Map("x" -> point.x, "y" -> point.y, "z" -> point.z, "m" -> point.m)))
    val linkGeometryWKTForApi = Map("geometryWKT" -> ("LINESTRING ZM (" + linkGeometryWithM.map(point => point.x + " " + point.y + " " + point.z + " " + point.m).mkString(", ") + ")"))
    val linkGeometry: Seq[Point] = feature.geometry.toPoints
    val horizontalLength = feature.properties.horizontalLength
    val kmtkId = feature.properties.kmtkId
    val version = feature.properties.version
    val municipalityCode = feature.properties.municipalityCode
    val roadClass = feature.properties.roadClass
    val administrativeClass = AdministrativeClass.apply(feature.properties.adminClass.getOrElse(0))
    val trafficDirection = TrafficDirection.apply(feature.properties.directionType)
    val modifiedAt = feature.properties.modifiedAtAsDateTime.orElse(feature.properties.sourceStartDateAsDateTime)
    val lifecycleStatus = LifecycleStatus.apply(feature.properties.lifecycleStatus)

    val featureClass = KMTKClient.featureClassCodeToFeatureClass.getOrElse(roadClass, FeatureClass.AllOthers)

    KMTKRoadLink(0, KMTKID(kmtkId, version), municipalityCode, linkGeometry, administrativeClass, trafficDirection, featureClass, modifiedAt,
      extractAttributes(feature.properties) ++ linkGeometryForApi ++ linkGeometryWKTForApi, lifecycleStatus, linkGeomSource, horizontalLength)

  }

  protected def extractAttributes(properties: KMTKProperties): Map[String, Any] = {
    val roadNameFin = properties.roadNameFin
    val roadNameSwe = properties.roadNameSwe
    val roadNameSme = properties.roadNameSme
    val roadNameSmn = properties.roadNameSmn
    val roadNameSms = properties.roadNameSms
    val roadNameSm = if (roadNameSme.isDefined) {
      roadNameSme
    } else if (roadNameSmn.isDefined) {
      roadNameSmn
    } else {
      roadNameSms
    }
    Map[String, Any]("MTKID" -> properties.sourceId,
      "MTKCLASS" -> properties.roadClass,
      "LIFECYCLESTATUS" -> properties.lifecycleStatus,
      "ROADNAME_FI" -> roadNameFin.orNull,
      "ROADNAME_SM" -> roadNameSm.orNull,
      "ROADNAME_SE" -> roadNameSwe.orNull,
      "ROADNUMBER" -> properties.roadNumber.orNull,
      "ROADPARTNUMBER" -> properties.roadPartNumber.orNull,
      "MUNICIPALITYCODE" -> properties.municipalityCode,
      "VALIDFROM" -> dateToEpoch(properties.sourceModificationTime),
      "CREATED_DATE" -> dateToEpoch(properties.startTime),
      "LAST_EDITED_DATE" -> dateToEpoch(properties.versionStartTime)
    ).filter { case (_, value) =>
      value != null
    }
  }

  // TODO Implement this when KMTK supports filtering with road numbers
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
  def fetchByChangesDates(lowerDate: DateTime, higherDate: DateTime): Seq[KMTKRoadLink] = {
    throw new NotImplementedError("KMTK doesn't support yet searching only by a date range.")
    // This feature has been requested from KMTK.
    // TODO When the KMTK support is ready, implement this.
  }

  /**
    * Returns KMTK road link by linkid
    */
  def fetchById(kmtkId: KMTKID): Option[KMTKRoadLink] = fetchByIds(Set(kmtkId)).headOption

  /**
    * Returns KMTK road links by link ids.
    */
  def fetchByIds(ids: Iterable[KMTKID]): Seq[KMTKRoadLink] = {
    queryByIds(ids, kmtkFeatureToKMTKRoadLink)
  }

  def fetchByIdsF(ids: Iterable[KMTKID]): Future[Seq[KMTKRoadLink]] = {
    Future(fetchByIds(ids))
  }

  def fetchByMunicipality(municipality: Int): Seq[KMTKRoadLink] = {
    queryByMunicipality(municipality).map(feature => kmtkFeatureToKMTKRoadLink(feature))
  }

  def fetchByMunicipalityF(municipality: Int): Future[Seq[KMTKRoadLink]] = {
    Future(queryByMunicipality(municipality).map(feature => kmtkFeatureToKMTKRoadLink(feature)))
  }

  /**
    * Returns KMTK road links. Uses Scala Future for concurrent operations.
    */
  def fetchByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipality: Option[Int]): Seq[KMTKRoadLink] = {
    queryByMunicipalitiesAndBounds(bounds, municipality).map(feature => kmtkFeatureToKMTKRoadLink(feature))
  }

  def fetchByBounds(bounds: BoundingRectangle): Seq[KMTKRoadLink] = {
    queryByBounds(bounds).map(feature => kmtkFeatureToKMTKRoadLink(feature))
  }

  /**
    * Returns KMTK road links. Uses Scala Future for concurrent operations.
    */
  def fetchByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipality: Option[Int]): Future[Seq[KMTKRoadLink]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, municipality).map(feature => kmtkFeatureToKMTKRoadLink(feature)))
  }

  def fetchByBoundsAndMunicipalities(bounds: BoundingRectangle, municipality: Option[Int]): Seq[KMTKRoadLink] = {
    queryByMunicipalitiesAndBounds(bounds, municipality).map(feature => kmtkFeatureToKMTKRoadLink(feature))
  }

  /**
    * Returns KMTK road links. Uses Scala Future for concurrent operations.
    */
  // TODO Rename method to have singular municipality or change parameter municipality to be a list
  def fetchByRoadNumbersBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipality: Option[Int], roadNumbers: Seq[(Int, Int)],
                                                 includeAllPublicRoads: Boolean = false): Future[Seq[KMTKRoadLink]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, roadNumbers, municipality, includeAllPublicRoads).map(feature => kmtkFeatureToKMTKRoadLink(feature)))
  }

  // TODO Is this needed?
  /**
    * Returns a sequence of KMTK Road Links. Uses Scala Future for concurrent operations.
    */
  def fetchByMunicipalityAndRoadNumbersF(municipality: Int, roadNumbers: Seq[(Int, Int)]): Future[Seq[KMTKRoadLink]] = {
    Future(queryByRoadNumbersAndMunicipality(municipality, roadNumbers))
  }

  def fetchByMunicipalityAndRoadNumbers(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[KMTKRoadLink] = {
    queryByRoadNumbersAndMunicipality(municipality, roadNumbers)
  }

}

class KMTKChangeInfoClient(kmtkRestApiEndPoint: String) extends KMTKClientOperations {
  override type KMTKType = ChangeInfo

  protected override val restApiEndPoint: String = kmtkRestApiEndPoint
  protected override val serviceName = "Roadlink_ChangeInfo"
  protected override val linkGeomSource: LinkGeomSource.Unknown.type = LinkGeomSource.Unknown

  /*
    protected override val disableGeometry = true

    // TODO
    protected override def defaultOutFields(): String = {
      "OLD_ID,NEW_ID,MTKID,CHANGETYPE,OLD_START,OLD_END,NEW_START,NEW_END,CREATED_DATE,LIFECYCLE"
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
  */

  // TODO
  def fetchByBoundsAndMunicipalities(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[ChangeInfo] = {
    throw new NotImplementedError("fetchByBoundsAndMunicipalities")
    // queryByMunicipalitiesAndBounds(bounds, municipalities)
  }

  // TODO
  def fetchByMunicipality(municipality: Int): Seq[ChangeInfo] = {
    throw new NotImplementedError()
    // queryByMunicipality(municipality)
  }

  // TODO
  def fetchByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[ChangeInfo]] = {
    throw new NotImplementedError()
    // Future(queryByMunicipalitiesAndBounds(bounds, municipalities))
  }

  // TODO
  def fetchByMunicipalityF(municipality: Int): Future[Seq[ChangeInfo]] = {
    throw new NotImplementedError()
    // Future(queryByMunicipality(municipality))
  }

  // TODO
  def fetchByIdsF(ids: Iterable[KMTKID]): Future[Seq[ChangeInfo]] = {
    throw new NotImplementedError()
    // Future(fetchByIds(ids))
  }

  /**
    * Fetch change information where given link id is in the old_id list (source)
    *
    * @param ids Link ids to check as sources
    * @return ChangeInfo for given links
    */
  // TODO
  def fetchByIds(ids: Set[KMTKID]): Seq[ChangeInfo] = {
    throw new NotImplementedError()
    // queryByIds(ids, "OLD_ID")
  }

  /**
    * Fetch change information where given link id is in the new_id list (source)
    *
    * @param ids Link ids to check as sources
    * @return ChangeInfo for given links
    */
  // TODO
  def fetchByNewIds(ids: Set[KMTKID]): Seq[ChangeInfo] = {
    throw new NotImplementedError()
    // queryByIds(ids, "NEW_ID")
  }
/*
  protected def queryByIds(ids: Set[KMTKID], field: String): Seq[ChangeInfo] = {
    val batchSize = 1000
    val idGroups: List[Set[KMTKID]] = ids.grouped(batchSize).toList
    idGroups.par.flatMap { ids =>
      val definition = layerDefinition(withFilter(field, ids))
      val url = serviceUrl(definition, queryParameters(false))
      fetchFeaturesAndLog(url)
    }.toList
  }
  */
}
