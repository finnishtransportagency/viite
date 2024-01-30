package fi.liikennevirasto.digiroad2.client.kgv

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.kgv.FilterOgc.{combineFiltersWithAnd, withLinkIdFilter, withMunicipalityFilter, withRoadNumbersFilter}
import fi.liikennevirasto.digiroad2.util.{Parallel, ViiteProperties}
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import org.apache.http.HttpStatus
import org.apache.http.client.config.{CookieSpecs, RequestConfig}
import org.apache.http.client.methods.{CloseableHttpResponse, HttpGet, HttpRequestBase}
import org.apache.http.client.ClientProtocolException
import org.apache.http.impl.client.HttpClients
import org.json4s.{DefaultFormats, StreamInput}
import org.json4s.jackson.JsonMethods.parse

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.{Await, Future, TimeoutException}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.Try

sealed case class Link(title:String,`type`: String,rel:String,href:String)

sealed case class FeatureCollection(
                                     `type`          : String = "FeatureCollection",
                                     features        : List[Feature],
                                     crs             : String = "EPSG%3A3067", // Defined in url
                                     numberReturned  : Int    = 0,
                                     nextPageLink    : String = "",
                                     previousPageLink: String = ""
                                   )
sealed case class Feature(`type`: String, geometry: Geometry, properties: Map[String, Any])
sealed case class Geometry(`type`: String, coordinates: List[List[Double]])

trait KgvCollection {
  def value :String
}

object KgvCollection {
  case object Frozen                  extends KgvCollection { def value = "keskilinjavarasto:frozenlinks" }
  case object Changes                 extends KgvCollection { def value = "keskilinjavarasto:change" }
  case object UnFrozen                extends KgvCollection { def value = "keskilinjavarasto:road_links" }
  case object LinkVersions            extends KgvCollection { def value = "keskilinjavarasto:road_links_versions" }
  case object LinkCorrespondenceTable extends KgvCollection { def value = "keskilinjavarasto:frozenlinks_vastintaulu" }
}

trait KgvOperation extends LinkOperationsAbstract{
  type LinkType
  type Content = FeatureCollection

  protected val linkGeomSource: LinkGeomSource
  private val cqlLang                             = "cql-text"
  private val bboxCrsType                         = "EPSG%3A3067"
  private val crs                                 = "EPSG%3A3067"
  private val WARNING_LEVEL                 : Int = 10
  // This is way to bypass AWS API gateway 10MB limitation, tune it if item size increase or degrease
  private val BATCH_SIZE                    : Int = 4999
  // Limit size of url query, Too big query string result error 414
  private val BATCH_SIZE_FOR_SELECT_IN_QUERY: Int = 150

  protected def serviceName: String

  override protected implicit val jsonFormats = DefaultFormats.preservingEmptyValues

  protected def convertToFeature(content: Map[String, Any]): Feature = {
    val geometry = Geometry(`type` = content("geometry").asInstanceOf[Map[String, Any]]("type").toString,
      coordinates = content("geometry").asInstanceOf[Map[String, Any]]("coordinates").asInstanceOf[List[List[Double]]]
    )
    Feature(
      `type` = content("type").toString,
      geometry = geometry,
      properties = content("properties").asInstanceOf[Map[String, Any]]
    )
  }

  protected def encode(url: String): String = {
    URLEncoder.encode(url, "UTF-8")
  }

  protected def addHeaders(request: HttpRequestBase): Unit = {
    request.addHeader("X-API-Key", ViiteProperties.kgvApiKey)
    request.addHeader("accept","application/geo+json")
  }

  protected def fetchFeatures(url: String): Either[LinkOperationError, Option[FeatureCollection]] = {
    val MaxTries                                                      = 5
    var trycounter                                                    = 0 /// For do-while check. Up to MaxTries.
    var success                                                       = true /// For do-while check. Set to false when exception.
    var result: Either[LinkOperationError, Option[FeatureCollection]] = Left(LinkOperationError("Dummy start value", "nothing here", url))

    do {
      // Retry delay
      Thread.sleep(500 * trycounter)

      trycounter += 1
      success = true

      result = time(logger, s"Fetch roadLink features, (try $trycounter)", url = None) {

          val request = new HttpGet(url)
          addHeaders(request)

          var response: CloseableHttpResponse = null
          val client = HttpClients.custom()
                                  .setDefaultRequestConfig(
                                    RequestConfig.custom()
                                                 .setCookieSpec(CookieSpecs.STANDARD)
                                                 .build())
                                  .build()
          try {
            response = client.execute(request)
            val statusCode = response.getStatusLine.getStatusCode
            if (statusCode == HttpStatus.SC_OK) {
              val feature = parse(StreamInput(response.getEntity.getContent)).values.asInstanceOf[Map[String, Any]]
              Right(
                feature("type").toString match {
                  case "Feature" => Some(FeatureCollection(features = List(convertToFeature(feature))))
                  case "FeatureCollection" => {
                    val links        = feature("links").asInstanceOf[List[Map[String, Any]]].map(link =>
                      Link(link("title").asInstanceOf[String], link("type").asInstanceOf[String], link("rel").asInstanceOf[String], link("href").asInstanceOf[String]))
                    val nextLink     = Try(links.find(_.title == "Next page").get.href).getOrElse("")
                    val previousLink = Try(links.find(_.title == "Previous page").get.href).getOrElse("")
                    val features     = feature("features").asInstanceOf[List[Map[String, Any]]].map(convertToFeature)
                    Some(
                      FeatureCollection(
                        features = features,
                        numberReturned = feature("numberReturned").asInstanceOf[BigInt].toInt,
                        nextPageLink = nextLink,
                        previousPageLink = previousLink
                      )
                    )
                  }
                  case _ => None
                }
              )
            } else {
              Left(LinkOperationError(response.getStatusLine.getReasonPhrase, response.getStatusLine.getStatusCode.toString, url))
            }
          } catch {
                    case e: ClientProtocolException => {
                      success = false
                      logger.error(e.getStackTrace().mkString(";")) // log to one line with ";" sepator
                      Left(LinkOperationError(e.toString + s"\nURL: $url", ""))
                    }
                    case e: IOException => {
                      success = false
                      if (trycounter < MaxTries) {
                        logger.warn(s"fetching $url failed, try $trycounter. IO Exception during KGV fetch. Exception: $e")
                        Left(LinkOperationError(s"KGV FETCH failure, try $trycounter. IO Exception during KGV fetch. Trying again.", url))
                      } else // basically, if(trycounter == MaxTries)
                        Left(LinkOperationError("KGV FETCH failure, tried ten (10) times, giving up. IO Exception during KGV fetch. Check connection to KGV", url))
                    }
                    case e: Exception => {
                      success = false
                      logger.warn(s"fetching $url failed, try $trycounter. Exception during KGV fetch. Exception: $e")
                      Left(LinkOperationError(e.toString, ""))
                    }
                  } finally {
                      if (response != null) {
                        response.close()
                      }
                  }
      }
      result match {
        case Left(_) => time(logger, s"entering round ${trycounter + 1}", true, None){}
        case Right(_) => Unit
      }
    } while (trycounter < MaxTries && !success)
    result
  }

  private def paginationRequest(base: String, limit: Int, startIndex: Int = 0): (String, Int) = {
    (s"${base}&limit=${limit}&startIndex=${startIndex}", startIndex+limit)
  }

  private def queryWithPaginationThreaded(baseUrl: String = ""): Seq[LinkType] = {
    val MAX_WAIT_TIME_MINUTES = 5 // How long we shall wait for the Future to return with the pagination data

    val pageAllReadyFetched: mutable.HashSet[String] = new mutable.HashSet()

    @tailrec
    def paginateAtomic(finalResponse: Set[FeatureCollection] = Set(), baseUrl: String = "", limit: Int, position: Int,counter:Int =0): Set[FeatureCollection] = {
      val (url, newPosition) = paginationRequest(baseUrl, limit, startIndex = position)
      if (!pageAllReadyFetched.contains(url)) {
        val result = fetchFeatures(url) match {
          case Right(features) => features
          case Left(error) => throw new ClientException(error.toString)
        }
        pageAllReadyFetched.add(url)
        result match {
          case Some(feature) if feature.numberReturned == 0 => finalResponse
          case Some(feature) if feature.numberReturned != 0 =>
            if ( counter == WARNING_LEVEL) logger.warn(s"Getting the result is taking very long time, URL was : $url")
            if(feature.nextPageLink.nonEmpty) {
              paginateAtomic(finalResponse ++ Set(feature), baseUrl, limit, newPosition, counter+1)
            }else {
              finalResponse ++ Set(feature)
            }
          case None => finalResponse
        }
      } else {
        paginateAtomic(finalResponse, baseUrl, limit, newPosition,counter+1)
      }
    }

    logger.info(s"queryWithPaginationThreaded - Future starting for $baseUrl")
    val resultF = for { fut1 <- Future(paginateAtomic(baseUrl = baseUrl, limit = BATCH_SIZE, position = 0))
                        fut2 <- Future(paginateAtomic(baseUrl = baseUrl, limit = BATCH_SIZE, position = BATCH_SIZE * 2))
                        fut3 <- Future(paginateAtomic(baseUrl = baseUrl, limit = BATCH_SIZE, position = BATCH_SIZE * 3))
                      } yield (fut1, fut2, fut3)

    val items: (Set[FeatureCollection], Set[FeatureCollection], Set[FeatureCollection]) =
      time(logger, "Fetch KGV bounding box data") {
        try {
          val result = Await.result(resultF, Duration.apply(MAX_WAIT_TIME_MINUTES, TimeUnit.MINUTES))
          logger.info(s"queryWithPaginationThreaded - Future waiting completed for $baseUrl")
          result
        }
        catch {
          case te: TimeoutException => logger.error(s"queryWithPaginationThreaded - Future TIMEOUTed for $baseUrl")
            throw (te)
        }
      }
    (items._1 ++ items._2 ++ items._3).flatMap(_.features.par.map(feature=>
      Extractor.extractFeature(feature, linkGeomSource).asInstanceOf[LinkType])).toList
  }

  /**
   * Returns road links by municipality.
   * Used by fetchByMunicipalityAndRoadNumbersF(municipality, roadNumbers) and
   * RoadLinkService.getViiteRoadLinks(municipality, roadNumbers).
   */
  override protected def queryByRoadNumbersAndMunicipality(municipality: Int, roadNumbers: Seq[(Int, Int)]): Seq[LinkType] = {
    val roadNumberFilters = withRoadNumbersFilter(roadNumbers, includeAllPublicRoads = true)
    val filterString  = s"filter=${combineFiltersWithAnd(withMunicipalityFilter(Set(municipality)), roadNumberFilters)})"
    val queryString = s"?${filterString}&filter-lang=${cqlLang}&crs=${crs}"

    fetchFeatures(s"${restApiEndPoint}/${serviceName}/items/${queryString}") match {
      case Right(features) => features.get.features.map(t => Extractor.extractFeature(t, linkGeomSource).asInstanceOf[LinkType])
      case Left(error) => throw new ClientException(error.toString)
    }
  }

  override protected def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int],
                                                        filter: Option[String]): Seq[LinkType] = {
    val bbox = s"${bounds.leftBottom.x},${bounds.leftBottom.y},${bounds.rightTop.x},${bounds.rightTop.y}"
    val filterString  = if (municipalities.nonEmpty || filter.isDefined){
      s"filter=${encode(combineFiltersWithAnd(withMunicipalityFilter(municipalities), filter))}"
    }else {
      ""
    }
    fetchFeatures(s"$restApiEndPoint/${serviceName}/items?bbox=$bbox&filter-lang=$cqlLang&bbox-crs=$bboxCrsType&crs=$crs&$filterString")
    match {
      case Right(features) =>features.get.features.map(feature=>
        Extractor.extractFeature(feature, linkGeomSource).asInstanceOf[LinkType])
      case Left(error) => throw new ClientException(error.toString)
    }
  }

  override protected def queryByMunicipality(municipality: Int, filter: Option[String] = None): Seq[LinkType] = {
    val filterString  = s"filter=${encode(combineFiltersWithAnd(withMunicipalityFilter(Set(municipality)), filter))}"
    queryWithPaginationThreaded(s"${restApiEndPoint}/${serviceName}/items?${filterString}&filter-lang=${cqlLang}&crs=${crs}")
  }

  override protected def queryByLinkIds[LinkType](linkIds: Set[String], filter: Option[String] = None): Seq[LinkType] = {
    new Parallel().operation(linkIds.grouped(BATCH_SIZE_FOR_SELECT_IN_QUERY).toList.par, 4) {
      _.flatMap(ids => {
        queryByLinkIdsUsingFilter(ids, filter)
      }).toList
    }
  }

  protected def queryByLinkIdsUsingFilter[LinkType](linkIds: Set[String],filter: Option[String]): Seq[LinkType] = {
    queryByFilter(Some(combineFiltersWithAnd(withLinkIdFilter(linkIds), filter)))
  }

  protected def queryRoadAndPartWithFilter(linkIds: Set[String], filter: String): List[(Option[Long], Option[Long], Int)] = {
    val filterString = s"&filter=${encode(combineFiltersWithAnd(withLinkIdFilter(linkIds), filter))}"
    val url          = s"${restApiEndPoint}/${serviceName}/items?filter-lang=${cqlLang}&crs=${crs}${filterString}"
    fetchFeatures(url) match {
      case Right(features) => features.get.features.map(feature => Extractor.extractRoadNumberAndPartFeature(feature))
      case Left(error) => throw new ClientException(error.toString)
    }
  }

  protected def queryByFilter[LinkType](filter:Option[String],pagination:Boolean = false): Seq[LinkType] = {
    val filterString  = if (filter.nonEmpty) s"&filter=${encode(filter.get)}" else ""
    val url = s"${restApiEndPoint}/${serviceName}/items?filter-lang=${cqlLang}&crs=${crs}${filterString}"
    if(!pagination){
      fetchFeatures(url)
      match {
        case Right(features) => features.get.features.map(feature=>
          Extractor.extractFeature(feature, linkGeomSource).asInstanceOf[LinkType])
        case Left(error) => throw new ClientException(error.toString)
      }
    }else {
      queryWithPaginationThreaded(url).asInstanceOf[Seq[LinkType]]
    }
  }

  override def queryByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[LinkType] = {
    queryByMunicipalitiesAndBounds(bounds, municipalities, None)
  }
}
