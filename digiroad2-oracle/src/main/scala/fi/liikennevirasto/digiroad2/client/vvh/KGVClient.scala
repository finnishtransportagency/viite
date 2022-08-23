package fi.liikennevirasto.digiroad2.client.vvh

import com.vividsolutions.jts.geom.Polygon
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.Filter.withMtkClassFilter
import fi.liikennevirasto.digiroad2.linearasset.RoadLinkLike
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.liikennevirasto.digiroad2.util.LogUtils
import org.apache.commons.codec.binary.Base64
import org.apache.http.NameValuePair
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.message.BasicNameValuePair
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.ArrayList

import fi.liikennevirasto.digiroad2.client.vvh.ChangeType.{CombinedModifiedPart, CombinedRemovedPart, DividedModifiedPart, DividedNewPart, LengthenedCommonPart, LengthenedNewPart, ShortenedCommonPart, ShortenedRemovedPart}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

sealed trait FeatureClass
object FeatureClass {
  case object TractorRoad extends FeatureClass
  case object DrivePath extends FeatureClass
  case object CycleOrPedestrianPath extends FeatureClass
  case object WinterRoads extends FeatureClass
  case object SpecialTransportWithoutGate extends FeatureClass
  case object SpecialTransportWithGate extends FeatureClass
  case object CarRoad_IIIa extends FeatureClass
  case object CarRoad_IIIb extends FeatureClass
  case object AllOthers extends FeatureClass
}

//class VVHClient(vvhRestApiEndPoint: String) {
//  def apply(vvhRestApiEndPoint: String): KgvRoadLinkClient = {
//    new KgvRoadLinkClient(Some(KgvCollection.UnFrozen), Some(LinkGeomSource.NormalLinkInterface))
//  }
//}

// Old: VVHRoadlink
case class RoadLinkFetched(linkId: String, municipalityCode: Int, geometry: Seq[Point],
                           administrativeClass: AdministrativeClass, trafficDirection: TrafficDirection,
                           featureClass: FeatureClass, modifiedAt: Option[DateTime] = None, attributes: Map[String, Any] = Map(),
                           constructionType: ConstructionType = ConstructionType.InUse, linkSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface, length: Double = 0.0) extends RoadLinkLike {
  def roadNumber: Option[String] = attributes.get("ROADNUMBER").map(_.toString)
  def verticalLevel: Option[String] = attributes.get("VERTICALLEVEL").map(_.toString)
  val vvhTimeStamp = attributes.getOrElse("LAST_EDITED_DATE", attributes.getOrElse("CREATED_DATE", BigInt(0))).asInstanceOf[BigInt].longValue()
}

case class ChangeInfo(oldId: Option[String], newId: Option[String], mmlId: Long, changeType: ChangeType, oldStartMeasure: Option[Double], oldEndMeasure: Option[Double], newStartMeasure: Option[Double], newEndMeasure: Option[Double], vvhTimeStamp: Long = 0L) {
  def isOldId(id: String): Boolean = {
    oldId.nonEmpty && oldId.get == id
  }
  def affects(id: String, assetVvhTimeStamp: Long): Boolean = {
    isOldId(id) && assetVvhTimeStamp < vvhTimeStamp
  }
}

case class HistoryRoadLink(linkId: String, municipalityCode: Int, geometry: Seq[Point], administrativeClass: AdministrativeClass,
                           trafficDirection: TrafficDirection, featureClass: FeatureClass, createdDate:BigInt, endDate: BigInt, attributes: Map[String, Any] = Map(),
                           constructionType: ConstructionType = ConstructionType.InUse, linkSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface, length: Double = 0.0) extends RoadLinkLike {
  def roadNumber: Option[String] = attributes.get("ROADNUMBER").map(_.toString)
  val vvhTimeStamp: Long = attributes.getOrElse("LAST_EDITED_DATE", createdDate).asInstanceOf[BigInt].longValue()
}

/**
 * Numerical values for change types from VVH ChangeInfo Api
 */
sealed trait ChangeType {
  def value: Int

  def isShortenedChangeType: Boolean = {
    ChangeType.apply(value) match {
      case ShortenedCommonPart => true
      case ShortenedRemovedPart => true
      case _ => false
    }
  }

  def isLengthenedChangeType: Boolean = {
    ChangeType.apply(value) match {
      case LengthenedCommonPart => true
      case LengthenedNewPart => true
      case _ => false
    }
  }

  def isDividedChangeType: Boolean = {
    ChangeType.apply(value) match {
      case DividedNewPart => true
      case DividedModifiedPart => true
      case _ => false
    }
  }

  def isCombinedChangeType: Boolean = {
    ChangeType.apply(value) match {
      case CombinedModifiedPart => true
      case CombinedRemovedPart => true
      case _ => false
    }
  }
}
object ChangeType {
  val values = Set(Unknown, CombinedModifiedPart, CombinedRemovedPart, LengthenedCommonPart, LengthenedNewPart, DividedModifiedPart, DividedNewPart, ShortenedCommonPart, ShortenedRemovedPart, Removed, New, ReplacedCommonPart, ReplacedNewPart, ReplacedRemovedPart)

  def apply(intValue: Int): ChangeType = {
    values.find(_.value == intValue).getOrElse(Unknown)
  }

  case object Unknown extends ChangeType { def value = 0 }
  case object CombinedModifiedPart extends ChangeType { def value = 1 }
  case object CombinedRemovedPart extends ChangeType { def value = 2 }
  case object LengthenedCommonPart extends ChangeType { def value = 3 }
  case object LengthenedNewPart extends ChangeType { def value = 4 }
  case object DividedModifiedPart extends ChangeType { def value = 5 }
  case object DividedNewPart extends ChangeType { def value = 6 }
  case object ShortenedCommonPart extends ChangeType { def value = 7 }
  case object ShortenedRemovedPart extends ChangeType { def value = 8 }
  case object Removed extends ChangeType { def value = 11 }
  case object New extends ChangeType { def value = 12 }
  case object ReplacedCommonPart extends ChangeType { def value = 13 }
  case object ReplacedNewPart extends ChangeType { def value = 14 }
  case object ReplacedRemovedPart extends ChangeType { def value = 15 } //TODO: Check value -> was 16 in Viite

  /**
   * Return true if this is a replacement where segment or part of it replaces another, older one
   * All changes should be of form (old_id, new_id, old_start, old_end, new_start, new_end) with non-null values
   *
   * @param changeInfo changeInfo object to check
   * @return true, if this is a replacement
   */
  def isReplacementChange(changeInfo: ChangeInfo) = { // Where asset geo location should be replaced with another
    changeInfo.changeType match {
      case CombinedModifiedPart => true
      case CombinedRemovedPart => true
      case LengthenedCommonPart => true
      case DividedModifiedPart => true
      case DividedNewPart => true
      case ShortenedCommonPart => true
      case ReplacedCommonPart => true
      case Unknown => false
      case LengthenedNewPart => false
      case ShortenedRemovedPart => false
      case Removed => false
      case New => false
      case ReplacedNewPart => false
      case ReplacedRemovedPart => false
    }
  }

  /**
   * Return true if this is an extension where segment or part of it has no previous entry
   * All changes should be of form (new_id, new_start, new_end) with non-null values and old_* fields must be null
   *
   * @param changeInfo changeInfo object to check
   * @return true, if this is an extension
   */
  def isExtensionChange(changeInfo: ChangeInfo) = { // Where asset geo location is a new extension (non-existing)
    changeInfo.changeType match {
      case LengthenedNewPart => true
      case ReplacedNewPart => true
      case _ => false
    }
  }

  def isDividedChange(changeInfo: ChangeInfo) = {
    changeInfo.changeType match {
      case DividedModifiedPart => true
      case DividedNewPart => true
      case _ => false
    }
  }

  /**
   * Return true if this is a removed segment or a piece of it. Only old id and m-values should be populated.
   *
   * @param changeInfo changeInfo object to check
   * @return true, if this is a removed segment
   */
  def isRemovalChange(changeInfo: ChangeInfo) = { // Where asset should be removed completely or partially
    changeInfo.changeType match {
      case Removed => true
      case ReplacedRemovedPart => true
      case ShortenedRemovedPart => true
      case _ => false
    }
  }

  /**
   * Return true if this is a new segment. Only new id and m-values should be populated.
   *
   * @param changeInfo changeInfo object to check
   * @return true, if this is a new segment
   */
  def isCreationChange(changeInfo: ChangeInfo) = { // Where asset geo location should be replaced with another
    changeInfo.changeType match {
      case New => true
      case _ => false
    }
  }

  def isUnknownChange(changeInfo: ChangeInfo): Boolean = {
    ChangeType.Unknown == changeInfo.changeType
  }
}

trait Filter {
  def withFilter[T](attributeName: String, ids: Set[T]): String

  def withMunicipalityFilter(municipalities: Set[Int]): String

  def withRoadNameFilter[T](attributeName: String, names: Set[T]): String

  def withRoadNumbersFilter(roadNumbers: Seq[(Int, Int)], includeAllPublicRoads: Boolean, filter: String = ""): String

  def combineFiltersWithAnd(filter1: String, filter2: String): String

  def combineFiltersWithAnd(filter1: String, filter2: Option[String]): String

  /**
   *
   * @param polygon to be converted to string
   * @return string compatible with VVH polygon query
   */
  def stringifyPolygonGeometry(polygon: Polygon): String

  // Query filters methods
  def withLinkIdFilter[T](linkIds: Set[T]): String

  def withFinNameFilter(roadNameSource: String)(roadNames: Set[String]): String

  def withMmlIdFilter(mmlIds: Set[Long]): String

  def withMtkClassFilter(ids: Set[Long]): String

  def withLastEditedDateFilter(lowerDate: DateTime, higherDate: DateTime): String

  def withDateLimitFilter(attributeName: String, lowerDate: DateTime, higherDate: DateTime): String

}

object Filter extends Filter {

  def anyToDouble(number: Any): Option[Double] = number match {
    case bi: BigInt => Some(bi.toDouble)
    case i: Int => Some(i.toDouble)
    case l: Long => Some(l.toDouble)
    case d: Double => Some(d)
    case _ => None
  }

  override def withFilter[T](attributeName: String, ids: Set[T]): String = {
    val filter =
      if (ids.isEmpty) {
        ""
      } else {
        val query = ids.mkString(",")
        s""""where":"$attributeName IN ($query)","""
      }
    filter
  }

  override def withMunicipalityFilter(municipalities: Set[Int]): String = {
    withFilter("MUNICIPALITYCODE", municipalities)
  }

  override def withRoadNameFilter[T](attributeName: String, names: Set[T]): String = {
    val filter =
      if (names.isEmpty) {
        ""
      } else {
        val query = names.mkString("','")
        s""""where":"$attributeName IN ('$query')","""
      }
    filter
  }

  override def withRoadNumbersFilter(roadNumbers: Seq[(Int, Int)], includeAllPublicRoads: Boolean, filter: String = ""): String = {
      if (roadNumbers.isEmpty)
        return s"""$filter""""
      if (includeAllPublicRoads)
        return withRoadNumbersFilter(roadNumbers, includeAllPublicRoads = false, "adminclass = 1")
      val limit = roadNumbers.head
      val filterAdd = s"""(roadnumber >= ${limit._1} and roadnumber <= ${limit._2})"""
      if (filter == "")
        withRoadNumbersFilter(roadNumbers.tail, includeAllPublicRoads, filterAdd)
      else
        withRoadNumbersFilter(roadNumbers.tail, includeAllPublicRoads, s"""$filter OR $filterAdd""")
    }

  override def combineFiltersWithAnd(filter1: String, filter2: String): String = {

    (filter1.isEmpty, filter2.isEmpty) match {
      case (true,true) => ""
      case (true,false) => filter2
      case (false,true) => filter1
      case (false,false) => "%s AND %s".format(filter1.dropRight(2), filter2.replace("\"where\":\"", ""))
    }
  }

  override def combineFiltersWithAnd(filter1: String, filter2: Option[String]): String = {
    combineFiltersWithAnd(filter2.getOrElse(""), filter1)
  }

  /**
   *
   * @param polygon to be converted to string
   * @return string compatible with VVH polygon query
   */
  override def stringifyPolygonGeometry(polygon: Polygon): String = {
    var stringPolygonList: String = ""
    var polygonString: String = "{rings:[["
    polygon.getCoordinates
    if (polygon.getCoordinates.length > 0) {
      for (point <- polygon.getCoordinates.dropRight(1)) {
        // drop removes duplicates
        polygonString += "[" + point.x + "," + point.y + "],"
      }
      polygonString = polygonString.dropRight(1) + "]]}"
      stringPolygonList += polygonString
    }
    stringPolygonList
  }

  // Query filters methods
  override def withLinkIdFilter[T](linkIds: Set[T]): String = {
    withFilter("LINKID", linkIds)
  }

  override def withFinNameFilter(roadNameSource: String)(roadNames: Set[String]): String = {
    withRoadNameFilter(roadNameSource, roadNames)
  }

  override def withMmlIdFilter(mmlIds: Set[Long]): String = {
    withFilter("MTKID", mmlIds)
  }

  override def withMtkClassFilter(ids: Set[Long]): String = {
    withFilter("MTKCLASS", ids)
  }

  override def withLastEditedDateFilter(lowerDate: DateTime, higherDate: DateTime): String = {
    withDateLimitFilter("LAST_EDITED_DATE", lowerDate, higherDate)
  }

  override def withDateLimitFilter(attributeName: String, lowerDate: DateTime, higherDate: DateTime): String = {
    val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
    val since = formatter.print(lowerDate)
    val until = formatter.print(higherDate)

    s""""where":"( $attributeName >=date '$since' and $attributeName <=date '$until' )","""
  }
}

object RoadLinkClient {
  /**
   * Create a pseudo VVH time stamp when an asset is created or updated and is on the current road geometry.
   * This prevents change info from being applied to the recently created asset. Resolution is one day.
   * @param offsetHours Offset to the timestamp. Defaults to 5 which reflects to VVH offset for batch runs.
   * @return VVH timestamp for current date
   */
  def createVVHTimeStamp(offsetHours: Int = 5): Long = {
    val oneHourInMs = 60 * 60 * 1000L
    val utcTime = DateTime.now().minusHours(offsetHours).getMillis
    val curr = utcTime + DateTimeZone.getDefault.getOffset(utcTime)
    curr - (curr % (24L*oneHourInMs))
  }
}

//class RoadLinkClient(vvhRestApiEndPoint: String) {
//  lazy val roadLinkData: OldVVHRoadLinkClient = new OldVVHRoadLinkClient(vvhRestApiEndPoint)
//  lazy val frozenTimeRoadLinkData: VVHFrozenTimeRoadLinkClientServicePoint = new VVHFrozenTimeRoadLinkClientServicePoint(vvhRestApiEndPoint)
//  lazy val roadLinkChangeInfo: VVHChangeInfoClient = new VVHChangeInfoClient(vvhRestApiEndPoint)
//  lazy val complementaryData: VVHComplementaryClient = new VVHComplementaryClient(vvhRestApiEndPoint)
//  lazy val historyData: VVHHistoryClient = new VVHHistoryClient(vvhRestApiEndPoint)
//
//  def fetchRoadLinkByLinkId(linkId: String): Option[RoadLinkFetched] = {
//    roadLinkData.fetchByLinkId(linkId) match {
//      case Some(vvhRoadLink) => Some(vvhRoadLink)
//      case None => complementaryData.fetchByLinkId(linkId)
//    }
//  }
//
//  def createVVHTimeStamp(offsetHours: Int = 5): Long = {
//    RoadLinkClient.createVVHTimeStamp(offsetHours)
//  }
//}

case class LinkOperationError(content: String, statusCode:String, url:String = "") extends Exception(s"Content: ${content}, Status code: ${statusCode}, ${url} ")
class ClientException(response: String) extends RuntimeException(response)

trait LinkOperationsAbstract {
  type LinkType
  type Content
  protected val linkGeomSource: LinkGeomSource
  protected def restApiEndPoint: String
  protected def serviceName: String

  protected implicit val jsonFormats: Formats = DefaultFormats

  lazy val logger = LoggerFactory.getLogger(getClass)

  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int],
                                               filter: Option[String]): Seq[LinkType]

  protected def queryByMunicipality(municipality: Int, filter: Option[String] = None): Seq[LinkType]

  protected def queryByPolygons(polygon: Polygon): Seq[LinkType]

  protected def queryLinksIdByPolygons(polygon: Polygon): Seq[String]

  protected def queryByLinkIds[LinkType](linkIds: Set[String], filter: Option[String] = None): Seq[LinkType]

  protected def queryByIds[LinkType](idSet: Set[String],filter:(Set[String])=>String): Seq[LinkType]

  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[LinkType] = {
    queryByMunicipalitiesAndBounds(bounds, municipalities, None)
  }
  protected def queryByRoadNumbersAndMunicipality(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[LinkType]
}

class VVHAuthPropertyReader {
  private def getUsername: String = {
    val loadedKeyString = ViiteProperties.vvhRestApiUsername
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing OAG username")
    loadedKeyString
  }

  private def getPassword: String = {
    val loadedKeyString = ViiteProperties.vvhRestApiPassword
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing OAG Password")
    loadedKeyString
  }

  def getAuthInBase64: String = {
    Base64.encodeBase64String((getUsername + ":" + getPassword).getBytes)
  }
}

class KgvRoadLink {
  def this(vvhRestApiEndPoint: String) = this()

    lazy val roadLinkData: KgvRoadLinkClient[RoadLinkFetched] = new KgvRoadLinkClient[RoadLinkFetched](Some(KgvCollection.UnFrozen), Some(LinkGeomSource.NormalLinkInterface))
    lazy val frozenTimeRoadLinkData: KgvRoadLinkClient[RoadLinkFetched] = new KgvRoadLinkClient[RoadLinkFetched](Some(KgvCollection.Frozen), Some(LinkGeomSource.FrozenLinkInterface))
    lazy val roadLinkChangeInfo: KgvRoadLinkClient[ChangeInfo] = new KgvRoadLinkClient[ChangeInfo](Some(KgvCollection.Changes), Some(LinkGeomSource.change))
    //lazy val linkCorrespondenceTable: KgvRoadLinkClient[RoadLinkFetched] = new KgvRoadLinkClient[RoadLinkFetched]Some(KgvCollection.LinkCorrespondenceTable), Some(LinkGeomSource.linkCorrespondenceTable))
//    lazy val historyData: KgvRoadLinkClient[HistoryRoadLink] = new KgvRoadLinkClient[HistoryRoadLink](Some(KgvCollection.LinkVersions), Some(LinkGeomSource.roadLinksVersions))
    //lazy val complementaryData: KgvRoadLinkClient[RoadLinkFetched] = new KgvRoadLinkClient[RoadLinkFetched](Some(KgvCollection.UnFrozen), Some(LinkGeomSource.ComplementaryLinkInterface))
    lazy val complementaryData: VVHComplementaryClient = new VVHComplementaryClient(ViiteProperties.vvhRestApiEndPoint)
}

class KgvRoadLinkClient[T](collection: Option[KgvCollection] = None, linkGeomSourceValue:Option[LinkGeomSource] = None) extends KgvOperation {

  override type LinkType = T
  val restApiEndPoint: String = ViiteProperties.kgvEndpoint
  override protected val serviceName = collection.getOrElse(throw new ClientException("Collection is not defined") ).value
  override protected val linkGeomSource: LinkGeomSource = linkGeomSourceValue.getOrElse(throw new ClientException("LinkGeomSource is not defined") )
  val filter:Filter = FilterOgc

  def createVVHTimeStamp(offsetHours: Int = 5): Long =  RoadLinkClient.createVVHTimeStamp(offsetHours)

//    protected def layerDefinition(filter: String, customFieldSelection: Option[String] = None): String = {
//      URLEncoder.encode(layerDefinitionWithoutEncoding(filter, customFieldSelection), "UTF-8")
//    }
//    /**
//      * Returns VVH road link history data in bounding box area. Municipalities are optional.
//      * Used by VVHClient.fetchVVHRoadlinksF, RoadLinkService.getVVHRoadLinks(bounds, municipalities), RoadLinkService.getVVHRoadLinks(bounds),
//      * PointAssetService.getByBoundingBox and ServicePointImporter.importServicePoints.
//      */
//    def fetchVVHRoadLinkByLinkIds(linkIds: Set[Long] = Set()): Seq[HistoryRoadLink] = {
//      if (linkIds.isEmpty)
//        Nil
//      else {
//        val batchSize = 1000
//        val idGroups: List[Set[Long]] = linkIds.grouped(batchSize).toList
//        idGroups.par.flatMap { ids =>
//          val definition = layerDefinition(filter.withLinkIdFilter(ids))
//          val url = serviceUrl(definition, queryParameters())
//
//          fetchVVHFeatures(url) match {
//            case Right(features) => features.map(extractVVHHistoricFeature)
//            case Left(error) =>
//              logger.error("VVH error: " + error)
//              throw new ClientException(error.toString)
//          }
//        }.toList
//      }
//    }
//
//    def fetchVVHRoadLinkByLinkIdsF(linkIds: Set[Long] = Set()): Future[Seq[HistoryRoadLink]] = {
//      Future(fetchVVHRoadLinkByLinkIds(linkIds))
//    }

  //TODO: CHECK NEED
      def fetchRoadLinkFetchedByLinkIdsF(linkIds: Set[String] = Set()): Future[Seq[HistoryRoadLink]] = {
        Future(Seq.empty[HistoryRoadLink])
      }


    /**
      * Returns a sequence of VVH Road Links. Uses Scala Future for concurrent operations.
      * Used by RoadLinkService.getViiteCurrentAndComplementaryRoadLinksFromVVH(municipality, roadNumbers).
      */
    def fetchByMunicipalityAndRoadNumbersF(municipality: Int, roadNumbers: Seq[(Int, Int)]): Future[Seq[LinkType]] = {
      Future(queryByRoadNumbersAndMunicipality(municipality, roadNumbers))
    }

    /**
      * Returns VVH road links. Uses Scala Future for concurrent operations.
      * Used by RoadLinkService.getRoadLinksAndChangesFromVVH(bounds, municipalities),
      * RoadLinkService.getViiteRoadLinksAndChangesFromVVH(bounds, roadNumbers, municipalities, everything, publicRoads).
      */
    def fetchByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[LinkType]] = {
      Future(queryByMunicipalitiesAndBounds(bounds, municipalities))
    }

    /**
      * Returns VVH road links. Uses Scala Future for concurrent operations.
      * Used by RoadLinkService.getRoadLinksAndChangesFromVVH(bounds, municipalities).
      */
    def fetchByRoadNumbersBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int], roadNumbers: Seq[(Int, Int)],
                                                   includeAllPublicRoads: Boolean = false): Future[Seq[LinkType]] = {
      Future(queryByMunicipalitiesAndBounds(bounds, roadNumbers, municipalities, includeAllPublicRoads))
    }

    /**
      * Returns VVH road links in bounding box area. Municipalities are optional.
      * Used by VVHClient.fetchByRoadNumbersBoundsAndMunicipalitiesF.
      */
    protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, roadNumbers: Seq[(Int, Int)], municipalities: Set[Int] = Set(), includeAllPublicRoads: Boolean = false): Seq[LinkType] = {
      val roadNumberFilters = if (roadNumbers.nonEmpty || includeAllPublicRoads)
        Some(filter.withRoadNumbersFilter(roadNumbers, includeAllPublicRoads))
      else
        None
      queryByMunicipalitiesAndBounds(bounds, municipalities, roadNumberFilters)
    }

    def fetchComplementaryByMunicipalitiesF(municipality: Int): Future[Seq[LinkType]] =
      Future(queryByMunicipality(municipality))

  def fetchByMunicipality(municipality: Int): Seq[LinkType] = {
    queryByMunicipality(municipality)
  }

  def fetchByMunicipalityF(municipality: Int): Future[Seq[LinkType]] = {
    Future(queryByMunicipality(municipality))
  }

  def fetchByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[LinkType] = {
    queryByMunicipalitiesAndBounds(bounds, municipalities)
  }

  def fetchByBounds(bounds: BoundingRectangle): Seq[LinkType] = {
    queryByMunicipalitiesAndBounds(bounds, Set[Int]())
  }

  def fetchByMunicipalitiesAndBoundsF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[LinkType]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, municipalities))
  }

  def fetchByLinkId(linkId: String): Option[LinkType] = fetchByLinkIds(Set(linkId)).headOption

  def fetchByLinkIds(linkIds: Set[String]): Seq[LinkType] = {
    queryByLinkIds[LinkType](linkIds)
  }

  def fetchByLinkIdsF(linkIds: Set[String]): Future[Seq[T]] = Future(fetchByLinkIds(linkIds))

  def fetchVVHRoadlinks[LinkType](linkIds: Set[String]): Seq[LinkType] =
    queryByLinkIds[LinkType](linkIds)

  def fetchByChangesDates(lowerDate: DateTime, higherDate: DateTime): Seq[LinkType] = {
    queryByLastEditedDate(lowerDate,higherDate)
  }

  def fetchByDatetime(lowerDate: DateTime, higherDate: DateTime): Seq[LinkType] = {
    queryByDatetimeAndFilter(lowerDate,higherDate)
  }

  def fetchByPolygonF(polygon : Polygon): Future[Seq[LinkType]] = {
    Future(queryByPolygons(polygon))
  }

  def fetchLinkIdsByPolygonF(polygon : Polygon): Future[Seq[String]] = {
    Future(queryLinksIdByPolygons(polygon))
  }
  def fetchWalkwaysByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[LinkType]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, municipalities, Some(filter.withMtkClassFilter(Set(12314)))))
  }

  def fetchWalkwaysByMunicipalitiesF(municipality: Int): Future[Seq[LinkType]] =
    Future(queryByMunicipality(municipality, Some(filter.withMtkClassFilter(Set(12314)))))

  def fetchByMmlIds(toSet: Set[Long]): Seq[LinkType] = queryByFilter(Some(filter.withMmlIdFilter(toSet)))

  def fetchByMmlId(mmlId: Long) : Option[LinkType]= fetchByMmlIds(Set(mmlId)).headOption
}
