package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.util.ViiteProperties
import org.apache.hc.client5.http.classic.methods.{HttpGet, HttpPost}
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.StandardCookieSpec
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.apache.hc.core5.http.{ClassicHttpResponse, HttpStatus, NameValuePair}
import org.apache.hc.core5.http.io.HttpClientResponseHandler
import org.apache.hc.core5.http.message.BasicNameValuePair
import org.apache.hc.core5.net.URIBuilder
import org.json4s.{DefaultFormats, StreamInput}
import org.json4s.jackson.JsonMethods.parse
import org.slf4j.LoggerFactory

import java.io.IOException
import scala.util.control.NonFatal

class ViiteVkmClient {

  case class VKMError(content: Map[String, Any], url: String)

  private val logger = LoggerFactory.getLogger(getClass)

  private def getRestEndPoint: String = {
    val loadedKeyString = ViiteProperties.vkmUrl
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing property: VKM URL")
    loadedKeyString
  }

  private val client = HttpClientBuilder.create()
    .setDefaultRequestConfig(RequestConfig.custom()
      .setCookieSpec(StandardCookieSpec.RELAXED).build()).build()

  /**
    * Builds http query fom given parts, executes the query, and returns the result (or error if http>=400).
    * @param params query parameters. Parameters are expected to be unescaped.
    * @return The query result, or VKMError in case the response was http>=400.
    */
  def get(path: String, params: Map[String, String]): Either[VKMError, Any] = {

    val uriBuilder = new URIBuilder(getRestEndPoint + path)
    params.foreach {
      case (param, value) => if (value.nonEmpty) uriBuilder.addParameter(param, value)
    }
    val url = uriBuilder.build.toString

    val request = new HttpGet(url)
    request.addHeader("X-API-Key", ViiteProperties.vkmApiKey)

    try {
      client.execute(request, getResponseHandler(url))
    } catch {
      case e: Exception => Right(VKMError(Map("error" -> e.getMessage), url))
    }
  }

  def postFormUrlEncoded(urlPart: String, parameters: Map[String, String]): Any = {
    implicit val formats: DefaultFormats = DefaultFormats

    val url = s"$getRestEndPoint$urlPart"
    val post = new HttpPost(url)
    var paramList = new java.util.ArrayList[NameValuePair]()
    parameters.foreach { case (key, value) =>
      paramList.add(new BasicNameValuePair(key, value))
    }
    post.setEntity(new UrlEncodedFormEntity(paramList, java.nio.charset.Charset.forName("UTF-8")))
    post.setHeader("Content-type", "application/x-www-form-urlencoded")

    try {
      client.execute(post, getResponseHandler(url))
    } catch {
      case NonFatal(e) =>
        logger.error(s"VkmClient failed: ${e.getMessage} $url", e)
        Map(("results","Failed"))
    }
  }

  /** Return a response handler, with handleResponse implementation returning the response body parsed */
  def getResponseHandler(url: String) = {
    new HttpClientResponseHandler[Either[VKMError, Any]] {
      @throws[IOException]
      override def handleResponse(response: ClassicHttpResponse): Either[VKMError, Any]  = {
        if (response.getCode == HttpStatus.SC_OK) {
          val content: Any = parse(StreamInput(response.getEntity.getContent)).values.asInstanceOf[Any]
          Right(content)
        } else {
          Left(VKMError(Map("error" -> "Request returned HTTP Error %d".format(response.getCode)), url))
        }
      }
    }
  }
}
