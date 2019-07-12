package fi.liikennevirasto.digiroad2.client.kmtk

import java.io.{IOException, InputStream}
import java.net.URLEncoder

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.linearasset.RoadLinkLike
import fi.liikennevirasto.digiroad2.util.KMTKAuthPropertyReader
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.{CloseableHttpClient, HttpClientBuilder}
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.json4s.jackson.JsonMethods.parse
import org.json4s.{DefaultFormats, Formats, StreamInput}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class KMTKID(uuid: String, version: Long)

case class KMTKGeometry(/*`type`: String, */ coordinates: Seq[Seq[Double]]) {
  def toPoints: Seq[Point] = {
    coordinates.map(c => if (c.isEmpty) {
      None
    } else {
      Some(Point(c.head, c(1), c(2)))
    }).filter(p => p.isDefined).map(p => p.get)
  }
}

case class KMTKRoadName(fin: Option[String], swe: Option[String], smn: Option[String], sms: Option[String], sme: Option[String])

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

case class KMTKProperties(id: KMTKID, adminClass: Option[Int], municipalityCode: Int, roadClass: Int, roadName: KMTKRoadName,
                          roadNumber: Option[Long], roadPartNumber: Option[Long], surfaceType: Long, lifespanStatus: Long, directionType: Int,
                          geometryLength: Double, geometryAttribute: Long, sourceStartDate: String, createdAt: String,
                          modifiedAt: Option[String], endedAt: Option[String], address: KMTKAddress, sourceInfo: Long, mtkGroup: Long,
                          sourceId: String, constructionStatus: Int, geoMetryFlip: Boolean, startNode: KMTKNode,
                          endNode: KMTKNode, xyAccuracy: Long, zAccuracy: Long) {

  def createdAtAsDateTime: Option[DateTime] = {
    parseDateOption(createdAt)
  }

  def modifiedAtAsDateTime: Option[DateTime] = {
    parseDateOption(modifiedAt)
  }

  def sourceStartDateAsDateTime: Option[DateTime] = {
    parseDateOption(sourceStartDate)
  }

  def endedAtAsDateTime: Option[DateTime] = {
    parseDateOption(endedAt)
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
                        constructionType: ConstructionType = ConstructionType.InUse,
                        linkSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface, length: Double = 0.0) extends RoadLinkLike {

  def roadNumber: Option[String] = attributes.get("ROADNUMBER").map(_.toString)

  val timeStamp: Long = attributes.getOrElse("LAST_EDITED_DATE", attributes.getOrElse("CREATED_DATE", BigInt(0))).asInstanceOf[BigInt].longValue()
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

  case class KMTKError(url: String, message: String)

  protected def kmtkFeatureToKMTKRoadLink(feature: KMTKFeature): KMTKRoadLink

  lazy val logger: Logger = LoggerFactory.getLogger(getClass)

  protected def anyToDouble(number: Any): Option[Double] = number match {
    case bi: BigInt => Some(bi.toDouble)
    case i: Int => Some(i.toDouble)
    case l: Long => Some(l.toDouble)
    case d: Double => Some(d)
    case _ => None
  }

  private def bboxParam(bounds: BoundingRectangle): String = {
    "bbox=" + URLEncoder.encode(
      s"""{"minX":${bounds.leftBottom.x},"minY":${bounds.leftBottom.y},"maxX":${bounds.rightTop.x},"maxY":${bounds.rightTop.y}}""",
      "UTF-8")
  }

  private def mcodeParam(municipalities: Iterable[Int]): String = {
    s"""mcode=${municipalities.mkString(",")}"""
  }

  private def linklistParam(kmtkIds: Iterable[KMTKID]): String = {
    "linklist=" + URLEncoder.encode(
      s"[${kmtkIds.map(k => s""""{"uuid":${k.uuid}","version":${k.version}}""").mkString(",")}]",
      "UTF-8")
  }

  private[kmtk] def timespanParam(dateRange: (DateTime, DateTime)): String = {
    val from = dateRange._1.toString(dateTimeFormatter)
    val to = dateRange._2.toString(dateTimeFormatter)
    "timespan=" + URLEncoder.encode(s"""{"from":"$from","to":"$to"}""", "UTF-8")
  }

  private def serviceUrlByBoundingBox(bounds: BoundingRectangle): String = {
    val bbox = bboxParam(bounds)
    restApiEndPoint + serviceName + s"?$bbox"
  }

  private def serviceUrlByMunicipality(municipalities: Iterable[Int]): String = {
    val mcode = mcodeParam(municipalities)
    restApiEndPoint + serviceName + s"?$mcode"
  }

  protected def serviceUrlByIds(kmtkIds: Iterable[KMTKID]): String = {
    val linklist: String = linklistParam(kmtkIds)
    restApiEndPoint + serviceName + s"?$linklist"
  }

  private def serviceUrlByBoundingBoxAndMunicipalities(bounds: BoundingRectangle, municipalities: Iterable[Int]): String = {
    val bbox = bboxParam(bounds)
    val mcode = if (municipalities.nonEmpty) "&" + mcodeParam(municipalities) else ""
    restApiEndPoint + serviceName + s"?$bbox$mcode"
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

  // TODO Remove or take in use
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
    * Returns KMTK road links by municipality.
    */
  protected def queryByMunicipality(municipality: Int): Seq[KMTKFeature] = {
    val url = serviceUrlByMunicipality(Set(municipality))
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
  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[KMTKFeature] = {
    val url = serviceUrlByBoundingBoxAndMunicipalities(bounds, municipalities)
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

  // TODO Change info client
  //lazy val roadLinkChangeInfo: KMTKChangeInfoClient = new KMTKChangeInfoClient(kmtkRestApiEndPoint)

  def fetchRoadLinkByUuidAndVersion(id: KMTKID): Option[KMTKRoadLink] = {
    roadLinkData.fetchById(id)
  }

}

class KMTKRoadLinkClient(kmtkRestApiEndPoint: String) extends KMTKClientOperations {

  override type KMTKType = KMTKRoadLink

  protected override val restApiEndPoint: String = kmtkRestApiEndPoint
  protected override val serviceName = "roadlink"
  protected override val linkGeomSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface

  /**
    * Constructions Types Allows to return
    * In Use - 0
    * Under Construction - 1
    * Planned - 3
    */
  // TODO Is this needed?
  protected def roadLinkStatusFilter(properties: KMTKProperties): Boolean = {
    val linkStatus = properties.constructionStatus
    linkStatus == ConstructionType.InUse.value || linkStatus == ConstructionType.Planned.value || linkStatus == ConstructionType.UnderConstruction.value
  }


  /**
    * Returns KMTK road links in bounding box area. Municipalities are optional.
    */
  // TODO filtering by road number ranges
  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, roadNumbers: Seq[(Int, Int)], municipalities: Set[Int] = Set(), includeAllPublicRoads: Boolean = false): Seq[KMTKFeature] = {
    /*val roadNumberFilters = */ if (roadNumbers.nonEmpty || includeAllPublicRoads)
      throw new NotImplementedError("Filtering by road number not supported yet.")
    // Some(withRoadNumbersFilter(roadNumbers, includeAllPublicRoads))
    else
      None
    queryByMunicipalitiesAndBounds(bounds, municipalities /*, roadNumberFilters*/)
  }

  /**
    * Returns KMTK road links.
    */
  protected def queryByIds[T](ids: Set[KMTKID], resultTransition: KMTKFeature => T): Seq[T] = {
    val batchSize = 1000
    val idGroups: List[Set[KMTKID]] = ids.grouped(batchSize).toList
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

  // TODO
  protected def kmtkFeatureToKMTKRoadLink(feature: KMTKFeature): KMTKRoadLink = {
    val linkGeometry: Seq[Point] = feature.geometry.toPoints
    val linkGeometryForApi = Map("points" -> linkGeometry.map(point => Map("x" -> point.x, "y" -> point.y, "z" -> 0.0, "m" -> 0.0))) // TODO We have to calculate m-value
    val linkGeometryWKTForApi = Map("geometryWKT" -> ("LINESTRING ZM (" + linkGeometry.map(point => point.x + " " + point.y + " " + point.z + " " + 0.0).mkString(", ") + ")")) // TODO We have to calculate m-value
    val kmtkId = feature.properties.id
    val municipalityCode = feature.properties.municipalityCode
    val roadClass = feature.properties.roadClass
    val geometryLength = feature.properties.geometryLength
    val administrativeClass = AdministrativeClass.apply(feature.properties.adminClass.getOrElse(0))
    val trafficDirection = TrafficDirection.apply(feature.properties.directionType)
    val modifiedAt = if (feature.properties.modifiedAt.isDefined) Some(DateTime.parse(feature.properties.modifiedAt.get)) else None // TODO If None, then should we take value from "createdAt"?
    val constructionType = ConstructionType.apply(feature.properties.constructionStatus) // TODO Is construction status same as construction type?

    val featureClass = KMTKClient.featureClassCodeToFeatureClass.getOrElse(roadClass, FeatureClass.AllOthers)

    KMTKRoadLink(0, kmtkId, municipalityCode, linkGeometry, administrativeClass, trafficDirection, featureClass, modifiedAt,
      extractAttributes(feature.properties) ++ linkGeometryForApi ++ linkGeometryWKTForApi, constructionType, linkGeomSource, geometryLength)

  }

  private def dateToEpoch(date: String): BigInt = {
    if (date != null) {
      val parsed = DateTime.parse(date, dateTimeFormatter)
      parsed.toDate.getTime
    } else {
      null
    }
  }

  protected def extractAttributes(properties: KMTKProperties): Map[String, Any] = {
    val roadName = properties.roadName
    val roadNameSm = if (roadName.sme.isDefined) {
      roadName.sme
    } else if (roadName.smn.isDefined) {
      roadName.smn
    } else {
      roadName.sms
    }
    Map[String, Any]("MTKID" -> properties.id, // TODO Do we have MTKID anymore?
      "MTKCLASS" -> properties.roadClass,
      "CONSTRUCTIONTYPE" -> properties.constructionStatus, // TODO Is this same as construction type?
      "ROADNAME_FI" -> roadName.fin.orNull,
      "ROADNAME_SM" -> roadNameSm.orNull,
      "ROADNAME_SE" -> roadName.swe.orNull,
      "ROADNUMBER" -> properties.roadNumber.orNull,
      "ROADPARTNUMBER" -> properties.roadPartNumber.orNull,
      "MUNICIPALITYCODE" -> properties.municipalityCode,
      "VALIDFROM" -> properties.sourceStartDate, // TODO Is this same as VALIDFROM?
      "GEOMETRY_EDITED_DATE" -> properties.modifiedAt.orNull, // TODO From where to get geometry edit date?
      "CREATED_DATE" -> dateToEpoch(properties.createdAt),
      "LAST_EDITED_DATE" -> dateToEpoch(properties.modifiedAt.orNull), // TODO If modifiedAt is null, should we use createdAt?
      "SUBTYPE" -> 0 // TODO What to put here?
      // Not including "TRACK_CODE", used only with suravage and complementary in Viite
    ).filter { case (_, value) =>
      value != null
    }
  }

  // TODO
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
  def fetchByIds(ids: Set[KMTKID]): Seq[KMTKRoadLink] = {
    queryByIds(ids, kmtkFeatureToKMTKRoadLink)
  }

  def fetchByIdsF(ids: Set[KMTKID]): Future[Seq[KMTKRoadLink]] = {
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
  def fetchByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[KMTKRoadLink] = {
    queryByMunicipalitiesAndBounds(bounds, municipalities).map(feature => kmtkFeatureToKMTKRoadLink(feature))
  }

  def fetchByBounds(bounds: BoundingRectangle): Seq[KMTKRoadLink] = {
    queryByBounds(bounds).map(feature => kmtkFeatureToKMTKRoadLink(feature))
  }

  /**
    * Returns KMTK road links. Uses Scala Future for concurrent operations.
    */
  def fetchByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[KMTKRoadLink]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, municipalities).map(feature => kmtkFeatureToKMTKRoadLink(feature)))
  }

  /**
    * Returns KMTK road links. Uses Scala Future for concurrent operations.
    */
  def fetchByRoadNumbersBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int], roadNumbers: Seq[(Int, Int)],
                                                 includeAllPublicRoads: Boolean = false): Future[Seq[KMTKRoadLink]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, roadNumbers, municipalities, includeAllPublicRoads).map(feature => kmtkFeatureToKMTKRoadLink(feature)))
  }

  /* TODO Is this needed?
    /**
      * Returns a sequence of KMTK Road Links. Uses Scala Future for concurrent operations.
      */
    def fetchByMunicipalityAndRoadNumbersF(municipality: Int, roadNumbers: Seq[(Int, Int)]): Future[Seq[KMTKRoadLink]] = {
      Future(queryByRoadNumbersAndMunicipality(municipality, roadNumbers).map(feature => kmtkFeatureToKMTKRoadLink(feature)))
    }

    def fetchByMunicipalityAndRoadNumbers(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[KMTKRoadLink] = {
      queryByRoadNumbersAndMunicipality(municipality, roadNumbers).map(feature => kmtkFeatureToKMTKRoadLink(feature))
    }
  */

}

/* TODO Change info client
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
*/
