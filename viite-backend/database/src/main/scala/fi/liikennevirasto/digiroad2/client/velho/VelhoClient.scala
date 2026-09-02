package fi.liikennevirasto.digiroad2.client.velho

import org.slf4j.LoggerFactory
import org.apache.hc.client5.http.classic.methods.{HttpGet, HttpPost}
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.StandardCookieSpec
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import org.apache.hc.core5.http.{ClassicHttpRequest, ClassicHttpResponse, ContentType, HttpStatus}
import org.apache.hc.core5.http.io.HttpClientResponseHandler
import org.apache.hc.core5.http.io.entity.{EntityUtils, StringEntity}
import org.apache.hc.core5.http.message.BasicNameValuePair
import org.apache.hc.core5.net.URIBuilder
import org.json4s.{DefaultFormats, JValue}
import org.json4s.JsonAST.{JArray, JNothing, JNull, JString}
import org.json4s.jackson.JsonMethods._

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future, blocking}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

case class VelhoError(content: Map[String, Any], url: String)

private case class VelhoTokenResponse(access_token: String, expires_in: Int, token_type: String)

/* Docs for Velho API: 
  https://ohje.velho.vaylapilvi.fi/rajapinnat/ulkoisten-rajapintojen-kayttoohje-vaylapilvi/
  https://velho.vaylapilvi.fi/luokitusrekisteri/doc/v1/swagger/index.html
*/

/**
 * Client for the Velho API.
 * Authenticates with an OAuth2 then fetches object identifier (OID) lists and geometries for a given Velho object class ("targetClass").
 */
class VelhoClient(tokenUrl: String, apiUrl: String, clientId: String, clientSecret: String) {

  implicit val formats: DefaultFormats.type = DefaultFormats
  private val logger = LoggerFactory.getLogger(getClass)
  private val luokitusrekisteriApiUrl = s"${apiUrl.stripSuffix("/")}/luokitusrekisteri"

  // A single bulk-geometry request can return thousands of large GeometryCollections; raise the
  // pool size above the httpclient5 defaults (maxTotal=25, maxPerRoute=5) so concurrent batch
  // requests don't queue up waiting for a free connection.
  private val connectionManager = new PoolingHttpClientConnectionManager()
  connectionManager.setMaxTotal(50)
  connectionManager.setDefaultMaxPerRoute(50)

  private val client = HttpClientBuilder.create()
    .setConnectionManager(connectionManager)
    .setDefaultRequestConfig(RequestConfig.custom().setCookieSpec(StandardCookieSpec.RELAXED).build())
    .build()

  // Number of OIDs sent per bulk geometry request. Keeps individual request/response bodies reasonably sized
  private val GEOMETRY_BATCH_SIZE = 2000

  // How many bulk geometry requests may be in flight at once. Peak heap during a refresh is roughly
  // this times the size of one response body, so keep it low enough for the container's heap.
  private val CONCURRENT_GEOMETRY_BATCHES = 2

  // Parses responseBody as JSON, or throws an IOException with a body snippet if it isn't.
  private def parseJson(responseBody: String): JValue = {
    try {
      parse(responseBody)
    } catch {
      case e: Exception =>
        val snippet = responseBody.take(200).replaceAll("\\s+", " ").trim
        throw new IOException(s"Velho response was not valid JSON (${e.getMessage}); response started with: '$snippet'")
    }
  }

  private def executeRequest[T](
    request: ClassicHttpRequest,
    handler: HttpClientResponseHandler[Either[VelhoError, T]],
    url: String
  ): Either[VelhoError, T] = {
    try {
      client.execute(request, handler)
    } catch {
      case NonFatal(e) =>
        Left(VelhoError(Map("error" -> Option(e.getMessage).getOrElse(e.toString)), url))
    }
  }

  @volatile private var cachedToken: Option[(String, Long)] = None

  private def fetchAccessToken(): Either[VelhoError, String] = {
    val request = new HttpPost(tokenUrl)
    val credentials = Base64.getEncoder.encodeToString(s"$clientId:$clientSecret".getBytes(StandardCharsets.UTF_8))
    request.addHeader("Authorization", s"Basic $credentials")
    request.addHeader("accept", "application/json")
    request.setEntity(new UrlEncodedFormEntity(List(new BasicNameValuePair("grant_type", "client_credentials")).asJava))

    val handler = new HttpClientResponseHandler[Either[VelhoError, String]] {
      @throws[IOException]
      override def handleResponse(response: ClassicHttpResponse): Either[VelhoError, String] = {
        val responseBody = EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8)
        if (response.getCode == HttpStatus.SC_OK) {
          val token = parseJson(responseBody).extract[VelhoTokenResponse]
          // Cache in memory only, with a 30s safety margin before actual expiry.
          cachedToken = Some((token.access_token, System.currentTimeMillis() + math.max(token.expires_in - 30, 0) * 1000L))
          Right(token.access_token)
        } else {
          logger.warn(s"Velho token request failed, HTTP ${response.getCode}: $responseBody")
          Left(VelhoError(Map("error" -> s"Token request returned HTTP ${response.getCode}: $responseBody"), tokenUrl))
        }
      }
    }

    executeRequest(request, handler, tokenUrl)
  }

  private def getAccessToken: Either[VelhoError, String] = {
    cachedToken match {
      case Some((token, expiresAt)) if System.currentTimeMillis() < expiresAt => Right(token)
      case _ => fetchAccessToken()
    }
  }

  /**
   * @param namespace Velho namespace, e.g. "kansalliset-luokitukset"
   * @param targetClass Velho object class, e.g. "erikoiskuljetusreitit"
   * @return Either a VelhoError, or the list of object identifiers (OIDs) for the given class.
   */
  private def getObjectOids(namespace: String, targetClass: String): Either[VelhoError, Seq[String]] = {
    getAccessToken.flatMap { accessToken =>
      val url = new URIBuilder(s"$luokitusrekisteriApiUrl/api/v1/tunnisteet/$namespace/$targetClass").build.toString
      val request = new HttpGet(url)
      request.addHeader("accept", "application/json")
      request.addHeader("Authorization", s"Bearer $accessToken")

      val handler = new HttpClientResponseHandler[Either[VelhoError, Seq[String]]] {
        @throws[IOException]
        override def handleResponse(response: ClassicHttpResponse): Either[VelhoError, Seq[String]] = {
          val responseBody = EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8)
          if (response.getCode == HttpStatus.SC_OK) {
            Right(parseJson(responseBody).extract[Seq[String]])
          } else {
            logger.warn(s"Velho OID request failed, HTTP ${response.getCode}: $responseBody")
            Left(VelhoError(Map("error" -> s"Request returned HTTP ${response.getCode}: $responseBody"), url))
          }
        }
      }

      executeRequest(request, handler, url)
    }
  }

  /**
   * Splits a response body containing multiple concatenated top-level JSON objects
   * (e.g. `{...}{...}{...}`, with or without whitespace/newlines between them) into
   * the individual object substrings. Tracks brace depth while correctly skipping
   * over braces that appear inside JSON string literals (including escaped quotes).
   */
  private def splitConcatenatedJsonObjects(text: String): Seq[String] = {
    val objects = Seq.newBuilder[String]
    var depth = 0
    var objectStart = -1
    var inString = false
    var escaped = false

    for (i <- text.indices) {
      val c = text.charAt(i)

      if (inString) {
        if (escaped) escaped = false
        else if (c == '\\') escaped = true
        else if (c == '"') inString = false
      } else {
        c match {
          case '"' => inString = true
          case '{' =>
            if (depth == 0) objectStart = i
            depth += 1
          case '}' =>
            depth -= 1
            if (depth == 0 && objectStart >= 0) {
              objects += text.substring(objectStart, i + 1)
              objectStart = -1
            }
          case _ => // ignore
        }
      }
    }

    objects.result()
  }

  /**
   * Fetches geometries for a batch of OIDs in a single bulk request:
   *   POST /api/v1/kohteet?rikasta=geometriat
   * with the OIDs as a raw JSON array body, e.g. ["oid1","oid2",...].
   *
   * The response body is a sequence of concatenated top-level JSON objects, one per
   * enriched Velho object — split it before parsing each object.
   * Objects without a "geometrycollection" (e.g. an OID the API couldn't resolve) are
   * dropped rather than failing the whole batch.
   *
   * @param oids OIDs to fetch, at most GEOMETRY_BATCH_SIZE.
   * @return Either a VelhoError, or (oid, geometry) pairs for the OIDs that had geometry.
   */
  private def getGeometryBatch(oids: Seq[String], accessToken: String): Either[VelhoError, Seq[(String, JValue)]] = {
    val url = new URIBuilder(s"$luokitusrekisteriApiUrl/api/v1/kohteet")
      .addParameter("rikasta", "geometriat")
      .build.toString

    val request = new HttpPost(url)
    request.addHeader("accept", "application/json")
    request.addHeader("Authorization", s"Bearer $accessToken")
    request.setEntity(new StringEntity(compact(render(JArray(oids.map(JString).toList))), ContentType.APPLICATION_JSON))

    val handler = new HttpClientResponseHandler[Either[VelhoError, Seq[(String, JValue)]]] {
      @throws[IOException]
      override def handleResponse(response: ClassicHttpResponse): Either[VelhoError, Seq[(String, JValue)]] = {
        val responseBody = EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8)
        if (response.getCode == HttpStatus.SC_OK) {
              val geometries = splitConcatenatedJsonObjects(responseBody).flatMap { objectText =>
            val parsedObject = parseJson(objectText)
            (parsedObject \ "oid", parsedObject \ "geometrycollection") match {
              case (JString(oid), geometry) if geometry != JNothing && geometry != JNull => Some(oid -> geometry)
              case _ => None
            }
          }
          val missing = oids.size - geometries.size
          if (missing > 0) logger.warn(s"Velho bulk geometry request returned no geometry for $missing/${oids.size} requested OIDs")
          Right(geometries)
        } else {
          logger.warn(s"Velho bulk geometry request failed, HTTP ${response.getCode}: $responseBody")
          Left(VelhoError(Map("error" -> s"Request returned HTTP ${response.getCode}: $responseBody"), url))
        }
      }
    }

    executeRequest(request, handler, url)
  }

  /**
   * Fetches the OIDs for the given Velho object class, then resolves their geometries via the
   * bulk geometry endpoint, GEOMETRY_BATCH_SIZE OIDs per request. At most
   * CONCURRENT_GEOMETRY_BATCHES requests are in flight at a time.
   *
   * A batch that fails outright (bad HTTP status, network error) is logged and excluded from the
   * result rather than failing the whole call, so a single bad batch doesn't discard everything
   * that succeeded.
   *
   * @param namespace Velho namespace, e.g. "kansalliset-luokitukset"
   * @param targetClass Velho object class, e.g. "erikoiskuljetusreitit"
   * @return Either a VelhoError, or the (oid, geometry) pairs for the given class.
   */
  def getObjectsWithGeometry(namespace: String, targetClass: String): Either[VelhoError, Seq[(String, JValue)]] = {
    getObjectOids(namespace, targetClass).flatMap { oids =>
      getAccessToken.map { accessToken =>
        logger.info(s"Found ${oids.size} OIDs for $targetClass")

        val batches = oids.grouped(GEOMETRY_BATCH_SIZE).toSeq
        logger.info(s"Resolving $targetClass in ${batches.size} geometry batches of at most $GEOMETRY_BATCH_SIZE OIDs; concurrency=$CONCURRENT_GEOMETRY_BATCHES")

        val results = batches.zipWithIndex.grouped(CONCURRENT_GEOMETRY_BATCHES).zipWithIndex.flatMap {
          case (concurrentBatches, waveIndex) =>
          val batchNumbers = concurrentBatches.map { case (_, batchIndex) => batchIndex + 1 }.mkString(", ")
          logger.info(s"Starting geometry batch wave ${waveIndex + 1}/${math.ceil(batches.size.toDouble / CONCURRENT_GEOMETRY_BATCHES).toInt}: batches [$batchNumbers] in flight")

          val batchFutures = Future.traverse(concurrentBatches) { case (batch, batchIndex) =>
            Future {
              blocking {
                val startedAt = System.nanoTime()
                logger.info(s"Starting geometry batch ${batchIndex + 1}/${batches.size} for $targetClass (${batch.size} OIDs)")
                getGeometryBatch(batch, accessToken) match {
                  case Right(geometries) =>
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1000000
                    logger.info(s"Completed geometry batch ${batchIndex + 1}/${batches.size} for $targetClass: ${geometries.size}/${batch.size} geometries in ${elapsedMillis}ms")
                    geometries
                  case Left(error) =>
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1000000
                    logger.warn(s"Geometry batch ${batchIndex + 1}/${batches.size} for $targetClass failed after ${elapsedMillis}ms: ${error.content}")
                    Seq.empty
                }
              }
            }
          }
          val waveResults = Await.result(batchFutures, Duration.Inf)
          logger.info(s"Completed geometry batch wave ${waveIndex + 1}: ${waveResults.flatten.size} geometries returned from batches [$batchNumbers]")
          waveResults.flatten
        }.toList

        logger.info(s"Resolved ${results.size}/${oids.size} geometries for Velho class $targetClass")
        results
      }
    }
  }
}
