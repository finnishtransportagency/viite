package fi.liikennevirasto.digiroad2.client.kgv

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.kgv.FilterOgc.withLifecycleStatusFilter
import fi.liikennevirasto.digiroad2.dao.ComplementaryLinkDAO
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.vaylavirasto.viite.geometry.Point

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class HistoryRoadLink(linkId: String, municipalityCode: Int, geometry: Seq[Point], administrativeClass: AdministrativeClass,
                           trafficDirection: TrafficDirection, featureClass: FeatureClass, createdDate:BigInt, endDate: BigInt, attributes: Map[String, Any] = Map(),
                           lifecycleStatus : LifecycleStatus = LifecycleStatus.InUse, linkSource: LinkGeomSource = LinkGeomSource.NormalLinkInterface, length: Double = 0.0, modifiedAt: Option[String] = None, sourceId: String = "") extends RoadLinkLike {
  val roadLinkTimeStamp: Long = attributes.getOrElse("LAST_EDITED_DATE", createdDate).asInstanceOf[BigInt].longValue()
}

class ClientException(response: String) extends RuntimeException(response)

class KgvRoadLink {
  lazy val roadLinkData: KgvRoadLinkClient[RoadLink] = new KgvRoadLinkClient[RoadLink](Some(KgvCollection.UnFrozen), Some(LinkGeomSource.NormalLinkInterface))
  lazy val frozenTimeRoadLinkData: KgvRoadLinkClient[RoadLink] = new KgvRoadLinkClient[RoadLink](Some(KgvCollection.Frozen), Some(LinkGeomSource.FrozenLinkInterface))
  lazy val roadLinkChangeInfo: KgvRoadLinkClient[ChangeInfo] = new KgvRoadLinkClient[ChangeInfo](Some(KgvCollection.Changes), Some(LinkGeomSource.Change))
  lazy val complementaryData: ComplementaryLinkDAO           = new ComplementaryLinkDAO
}

class KgvRoadLinkClient[T](collection: Option[KgvCollection] = None, linkGeomSourceValue:Option[LinkGeomSource] = None) extends KgvOperation {

  override type LinkType = T
  override protected val serviceName = collection.getOrElse(throw new ClientException("Collection is not defined") ).value
  override protected val linkGeomSource: LinkGeomSource = linkGeomSourceValue.getOrElse(throw new ClientException("LinkGeomSource is not defined") )
  val restApiEndPoint: String = ViiteProperties.kgvEndpoint
  val filter:Filter = FilterOgc

  /**
    * Returns a sequence of Road Links. Uses Scala Future for concurrent operations.
    * Used by RoadLinkService.getCurrentAndComplementaryRoadLinksByMunicipality
    */
  def fetchByMunicipalityAndRoadNumbersF(municipality: Int, roadNumbers: Seq[(Int, Int)]): Future[Seq[LinkType]] = {
    Future(queryByRoadNumbersAndMunicipality(municipality, roadNumbers))
  }

  /**
    * Returns road links. Uses Scala Future for concurrent operations.
    */
  def fetchByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[LinkType]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, municipalities))
  }

  /**
    * Returns road links. Uses Scala Future for concurrent operations.
    */
  def fetchByRoadNumbersBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int], roadNumbers: Seq[(Int, Int)],
                                                 includeAllPublicRoads: Boolean = false): Future[Seq[LinkType]] = {
    Future(queryByMunicipalitiesAndBounds(bounds, roadNumbers, municipalities, includeAllPublicRoads))
  }

  /**
    * Returns road links in bounding box area. Municipalities are optional.
    */
  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, roadNumbers: Seq[(Int, Int)], municipalities: Set[Int] = Set(), includeAllPublicRoads: Boolean = false): Seq[LinkType] = {
    val roadNumberFilters = if (roadNumbers.nonEmpty || includeAllPublicRoads)
      Some(filter.withRoadNumbersFilter(roadNumbers, includeAllPublicRoads))
    else
      None
    queryByMunicipalitiesAndBounds(bounds, municipalities, roadNumberFilters)
  }

  def fetchByMunicipality(municipality: Int): Seq[LinkType] = {
    queryByMunicipality(municipality)
  }

  def fetchByMunicipalityF(municipality: Int): Future[Seq[LinkType]] = {
    Future(queryByMunicipality(municipality))
  }

  def fetchByLinkId(linkId: String): Option[LinkType] = fetchByLinkIds(Set(linkId)).headOption

  def fetchByLinkIds(linkIds: Set[String]): Seq[LinkType] = {
    queryByLinkIds[LinkType](linkIds)
  }

  def fetchUnderConstructionLinksById(linkIds: Set[String]): List[(Option[Long], Option[Long], Int)] =
    queryRoadAndPartWithFilter(linkIds, withLifecycleStatusFilter(Set(LifecycleStatus.UnderConstruction.value)))

  def fetchByLinkIdsF(linkIds: Set[String]): Future[Seq[T]] = Future(fetchByLinkIds(linkIds))

  def fetchBySourceId(sourceId: Long): Option[LinkType]= fetchBySourceIds(Set(sourceId)).headOption

  def fetchBySourceIds(sourceIds: Set[Long]): Seq[LinkType] = queryByFilter(Some(filter.withSourceIdFilter(sourceIds)))
}
