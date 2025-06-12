package fi.liikennevirasto.digiroad2.client.vkm

import fi.liikennevirasto.digiroad2.client.kgv.Extractor.logger
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.vaylavirasto.viite.dao.ComplementaryLink
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.RoadPart
import fi.vaylavirasto.viite.util.DateTimeFormatters.finnishDateFormatter
import fi.vaylavirasto.viite.util.ViiteException
import org.apache.hc.client5.http.classic.methods.{HttpGet, HttpPost}
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.StandardCookieSpec
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity
import org.apache.hc.client5.http.impl.classic.{CloseableHttpClient, HttpClientBuilder, HttpClients}
import org.apache.hc.core5.http.{ClassicHttpResponse, HttpStatus, NameValuePair}
import org.apache.hc.core5.http.io.HttpClientResponseHandler
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.apache.hc.core5.http.message.BasicNameValuePair
import org.apache.hc.core5.net.URIBuilder
import org.joda.time.DateTime
import org.json4s.{DefaultFormats, JNull, JValue}
import org.json4s.JsonAST.JArray
import org.json4s.jackson.JsonMethods._

import java.io.IOException
import scala.util.Try
import scala.util.control.NonFatal

case class TiekamuRoadLinkChange(oldLinkId: String,
                                 oldStartM: Double,
                                 oldEndM: Double,
                                 newLinkId: String,
                                 newStartM: Double,
                                 newEndM: Double,
                                 digitizationChange: Boolean)

case class TiekamuRoadLinkChangeError(errorMessage: String,
                                      change: TiekamuRoadLinkChange,
                                      metaData: TiekamuRoadLinkErrorMetaData)

case class TiekamuRoadLinkErrorMetaData(roadPart: RoadPart,
                                        roadwayNumber:Long,
                                        linearLocationIds: Seq[Long],
                                        linkId: String)

case class VKMError(content: Map[String, Any], url: String)

class VKMClient(endPoint: String, apiKey: String) {

  implicit val formats = DefaultFormats

  private val client = HttpClientBuilder.create()
    .setDefaultRequestConfig(RequestConfig.custom()
      .setCookieSpec(StandardCookieSpec.RELAXED).build()).build()

  def close(): Unit = {
    try {
      client.close()
    } catch {
      case e: IOException =>
        logger.warn("Failed to close VKM HTTP client", e)
    }
  }

  /**
   * Builds http query fom given parts, executes the query, and returns the result (or error if http>=400).
   * @param params query parameters. Parameters are expected to be unescaped.
   * @return Either the query result (Right) or a VKMError (Left) if the response status is >= 400.
   */
  def get(path: String, params: Map[String, String]): Either[VKMError, Any] = {

    val uriBuilder = new URIBuilder(endPoint + path)
    params.foreach {
      case (param, value) => if (value.nonEmpty) uriBuilder.addParameter(param, value)
    }
    val url = uriBuilder.build.toString

    val request = new HttpGet(url)
    request.addHeader("X-API-Key", apiKey)

    try {
      Right(client.execute(request, getResponseHandler(url)))
    } catch {
      case e: Exception => Left(VKMError(Map("error" -> e.getMessage), url))
    }
  }

  /** Return a response handler, with handleResponse implementation returning the response body parsed */
  def getResponseHandler(url: String): HttpClientResponseHandler[Either[VKMError, Any]] = {
    new HttpClientResponseHandler[Either[VKMError, Any]] {
      @throws[IOException]
      override def handleResponse(response: ClassicHttpResponse): Either[VKMError, Any]  = {
        if (response.getCode == HttpStatus.SC_OK) {
          val content: Any = parse(response.getEntity.getContent).values.asInstanceOf[Any]
          Right(content)
        } else {
          Left(VKMError(Map("error" -> "Request returned HTTP Error %d".format(response.getCode)), url))
        }
      }
    }
  }

  def fetchComplementaryLinkFromVKM(linkId: String): Option[ComplementaryLink] = {

    /** Extracts a ComplementaryLink object from a feature */
    def extractFeature(feature: JValue): ComplementaryLink = {
      // Use default JSON formats for extracting values
      implicit val formats: DefaultFormats.type = DefaultFormats

      // Extract the "properties" part of the feature
      val props = feature \ "properties"
      val attributes = props.extract[Map[String, Any]]

      // Extract and transform the geometry coordinates into Point objects
      val coordinates = (feature \ "geometry" \ "coordinates").extract[List[List[Double]]]
      val geometry: Seq[Point] = coordinates.map {
        case List(x, y, z) => Point(x, y, z)
        case List(x, y) => Point(x, y, 0.0)
        case _ => throw new Exception("Unexpected coordinate format")
      }
      // Helper to extract optional string values from the attribute map
      def optStr(key: String): Option[String] = attributes.get(key) match {
        case Some(null) => None
        case Some(value) if value == JNull => None
        case Some(value) =>
          val str = value.toString
          if (str == "null" || str.trim.isEmpty) None else Some(str)
        case None => None
      }

      // More helpers to safely extract various types (with fallbacks)
      def getInt(key: String): Int = Try(attributes(key).toString.toInt).getOrElse(0)
      def getDouble(key: String): Double = Try(attributes(key).toString.toDouble).getOrElse(0.0)
      def getDateTime(key: String): DateTime = Try(DateTime.parse(attributes(key).toString)).getOrElse(new DateTime(0))

      // Construct and return the Complementary Link object using extracted values
      ComplementaryLink(
        id = attributes("id").toString,
        datasource = getInt("datasource"),
        adminclass = getInt("adminclass"),
        municipalitycode = getInt("municipalitycode"),
        featureclass = getInt("featureclass"),
        roadclass = getInt("roadclass"),
        roadnamefin = optStr("roadnamefin"),
        roadnameswe = optStr("roadnameswe"),
        roadnamesme = optStr("roadnamesme"),
        roadnamesmn = optStr("roadnamesmn"),
        roadnamesms = optStr("roadnamesms"),
        roadnumber = getInt("roadnumber"),
        roadpartnumber = getInt("roadpartnumber"),
        surfacetype = getInt("surfacetype"),
        lifecyclestatus = getInt("lifecyclestatus"),
        directiontype = getInt("directiontype"),
        surfacerelation = getInt("surfacerelation"),
        xyaccuracy = getDouble("xyaccuracy"),
        zaccuracy = getDouble("zaccuracy"),
        horizontallength = getDouble("horizontallength"),
        addressfromleft = getInt("addressfromleft"),
        addresstoleft = getInt("addresstoleft"),
        addressfromright = getInt("addressfromright"),
        addresstoright = getInt("addresstoright"),
        starttime = getDateTime("starttime"),
        versionstarttime = getDateTime("versionstarttime"),
        sourcemodificationtime = getDateTime("sourcemodificationtime"),
        geometry = geometry,
        ajorata = getInt("ajorata"),
        vvh_id = attributes.getOrElse("vvh_id", "").toString
      )
    }

    /** Create a response handler, with handleResponse implementation returning the
     * response body in Right as Seq[RoadLink], or an Exception in Left. */
    def getResponseHandler(url: String) = {
      new HttpClientResponseHandler[Either[ViiteException, Seq[String]]] {
        @throws[IOException]
        override def handleResponse(response: ClassicHttpResponse): Either[ViiteException, Seq[String]] = {
          if (response.getCode == HttpStatus.SC_OK) {
            val entity = response.getEntity
            val responseString = EntityUtils.toString(entity, "UTF-8")
            Right(Seq(responseString))
          } else {
            Left(ViiteException(s"Request $url returned HTTP ${response.getCode}"))
          }
        }
      }
    }

    def getComplementaryLink(linkId: String): Option[ComplementaryLink] = {
      val params = s"/ogc/collections/lisageometrialinkit/items?filter=id%20in%20(%27${linkId}%27)"
      val url = endPoint ++ params
      val client = HttpClients.createDefault()
      val request = new HttpGet(url)
      request.addHeader("accept", "application/geo+json")
      request.addHeader("X-API-Key", apiKey)

      try {
        val response = client.execute(request, getResponseHandler(url))
        // Extract raw JSON string
        val rawJsonString = response match {
          case Right(list) => list.headOption.getOrElse("")
          case _ => ""
        }
        val json = parse(rawJsonString)
        // Extract the first feature
        (json \ "features") match {
          case JArray(features) if features.nonEmpty =>
            val complementaryLink = extractFeature(features.head)
            Some(complementaryLink)
          case _ =>
            logger.warn(s"No features found for complementary link ID: $linkId")
            None
        }
      } catch {
        case t: Throwable =>
          logger.error(s"Fetching complementary link failed. ${t}")
          throw t
      } finally {
        client.close()
      }
    }
    getComplementaryLink(linkId)
  }

  def getTiekamuRoadlinkChanges(previousDate: DateTime, newDate: DateTime): Seq[TiekamuRoadLinkChange] = {

    /** Create a response handler, with handleResponse implementation returning the
     * response body in Right as Seq[TiekamuRoadLinkChanges], or an Exception in Left. */
    def getResponseHandler(url: String) = {
      new HttpClientResponseHandler[Either[ViiteException, Seq[TiekamuRoadLinkChange]]] {
        @throws[IOException]
        override def handleResponse(response: ClassicHttpResponse): Either[ViiteException, Seq[TiekamuRoadLinkChange]]  = {
          if (response.getCode == HttpStatus.SC_OK) {
            val entity = response.getEntity
            val responseString = EntityUtils.toString(entity, "UTF-8")
            val tiekamuRoadLinkChanges = extractTiekamuRoadLinkChanges(responseString)
            Right(tiekamuRoadLinkChanges)
          } else {
            Left(ViiteException(s"Request $url returned HTTP ${response.getCode}"))
          }
        }
      }
    }

    def extractTiekamuRoadLinkChanges(responseString: String): Seq[TiekamuRoadLinkChange] = {
      def getDigitizationChangeValue(newStartMValue: Double, newEndMValue: Double): Boolean = {
        if (newEndMValue < newStartMValue)
          true
        else
          false
      }

      // Parse the JSON response
      val parsedJson = parse(responseString)

      // Extract the "features" field as a sequence of JValue
      val features = (parsedJson \ "features").asInstanceOf[JArray].arr
      // Map the JSON objects to the TiekamuRoadLinkChange class
      val tiekamuRoadLinkChanges = features.map { feature =>
        val properties = feature \ "properties"
        val newStartM = (properties \ "m_arvo_alku_kohdepvm").extract[Double]
        val newEndM = (properties \ "m_arvo_loppu_kohdepvm").extract[Double]
        TiekamuRoadLinkChange(
          (properties \ "link_id").extract[String],
          (properties \ "m_arvo_alku").extract[Double],
          (properties \ "m_arvo_loppu").extract[Double],
          (properties \ "link_id_kohdepvm").extract[String],
          if(newStartM > newEndM) newEndM else newStartM ,
          if(newEndM < newStartM) newStartM else newEndM,
          getDigitizationChangeValue(newStartM, newEndM)
        )
      }
      tiekamuRoadLinkChanges
    }

    time(logger, "Creating TiekamuRoadLinkChange sets") {
      try {
        val previousDateFinnishFormat = finnishDateFormatter.print(previousDate)
        val newDateFinnishFormat = finnishDateFormatter.print(newDate)
        val tiekamuEndpoint = endPoint ++ "/tiekamu?"
        val tiekamuDateParams = s"tilannepvm=${previousDateFinnishFormat}&asti=${newDateFinnishFormat}"
        val tiekamuReturnValueParam = "&palautusarvot=72" // For more info on the different return options, check the VKM/Tiekamu API docs
        val url = tiekamuEndpoint ++ tiekamuDateParams ++ tiekamuReturnValueParam

        val request = new HttpGet(url)
        request.addHeader("accept", "application/geo+json")
        request.addHeader("X-API-Key", apiKey)

        time(logger, "Fetching change set data from Tiekamu") {
          try {
            val tiekamuRoadLinkChanges = client.execute(request, getResponseHandler(url))
            tiekamuRoadLinkChanges match {
              case Left(t) => throw t
              case Right(tiekamuRoadLinkChanges) => tiekamuRoadLinkChanges
            }
          } catch {
            case t: Throwable =>
              logger.warn(s"Fetching $url failed. Throwable when fetching TiekamuRoadlinkChanges: ${t.getMessage}")
              Seq()
          }
        }
      } catch {
        case t: Throwable =>
          logger.warn(s"Creating query URL failed: ${t.getMessage}")
          Seq()
      }
    }
  }
}
