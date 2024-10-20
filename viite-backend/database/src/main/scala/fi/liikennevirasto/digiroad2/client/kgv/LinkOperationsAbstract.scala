package fi.liikennevirasto.digiroad2.client.kgv

import fi.vaylavirasto.viite.geometry.BoundingRectangle
import fi.vaylavirasto.viite.model.LinkGeomSource
import org.json4s.{DefaultFormats, Formats}
import org.slf4j.LoggerFactory


trait LinkOperationsAbstract {
  type LinkType
  type Content
  protected lazy val logger = LoggerFactory.getLogger(getClass)
  protected val linkGeomSource: LinkGeomSource

  protected def restApiEndPoint: String

  protected implicit val jsonFormats: Formats = DefaultFormats

  protected def serviceName: String

  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int],
                                               filter: Option[String]): Seq[LinkType]

  protected def queryByMunicipality(municipality: Int, filter: Option[String] = None): Seq[LinkType]

  protected def queryByLinkIds[LinkType](linkIds: Set[String], filter: Option[String] = None): Seq[LinkType]

  protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[LinkType] = {
    queryByMunicipalitiesAndBounds(bounds, municipalities, None)
  }
  protected def queryByRoadNumbersAndMunicipality(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[LinkType]
}

case class LinkOperationError(content: String, statusCode:String, url:String = "") extends Exception(s"Content: $content, Status code: $statusCode, $url ")
