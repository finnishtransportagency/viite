package fi.liikennevirasto.viite

import org.apache.hc.client5.http.classic.methods.{HttpGet, HttpPost}
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.StandardCookieSpec
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity
import org.apache.hc.client5.http.impl.classic.{CloseableHttpResponse, HttpClientBuilder}
import org.apache.hc.core5.http.NameValuePair
import org.apache.hc.core5.http.message.BasicNameValuePair
import org.apache.hc.core5.net.URIBuilder
import org.json4s.{DefaultFormats, StreamInput}
import org.json4s.jackson.JsonMethods.parse
import org.slf4j.LoggerFactory

import fi.liikennevirasto.digiroad2.util.ViiteProperties
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
  def get(path: String, params: Map[String, String]): Either[Any, VKMError] = {

    val builder = new URIBuilder(getRestEndPoint + path)

    params.foreach {
      case (param, value) => if (value.nonEmpty) builder.addParameter(param, value)
    }

    val url = builder.build.toString
    val request = new HttpGet(url)

    request.addHeader("X-API-Key", ViiteProperties.vkmApiKey)

    val response = client.execute(request)
    try {
      if (response.getCode >= 400)
        return Right(VKMError(Map("error" -> "Request returned HTTP Error %d".format(response.getCode)), url))
      val content: Any = parse(StreamInput(response.getEntity.getContent)).values.asInstanceOf[Any]
      Left(content)
    } catch {
      case e: Exception => Right(VKMError(Map("error" -> e.getMessage), url))
    } finally {
      response.close()
    }
  }

  def postFormUrlEncoded(urlPart: String, parameters: Map[String, String]): Any = {
    implicit val formats: DefaultFormats = DefaultFormats

    val post = new HttpPost(s"$getRestEndPoint$urlPart")
    var nameValuePairs = new java.util.ArrayList[NameValuePair]()
    parameters.foreach { case (key, value) =>
      nameValuePairs.add(new BasicNameValuePair(key, value))
    }
    post.setEntity(new UrlEncodedFormEntity(nameValuePairs, java.nio.charset.Charset.forName("UTF-8")))
    post.setHeader("Content-type", "application/x-www-form-urlencoded")

    var response: CloseableHttpResponse = null
    try {
      response = client.execute(post)
      parse(StreamInput(response.getEntity.getContent)).values.asInstanceOf[Any]
    } catch {
    case NonFatal(e) =>
      logger.error(s"VkmClient failed: ${e.getMessage} $getRestEndPoint$urlPart", e)
      Map(("results","Failed"))
    } finally {
      if (response != null)
        response.close()
    }
  }
}
