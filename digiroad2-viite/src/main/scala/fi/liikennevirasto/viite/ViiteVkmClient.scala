package fi.liikennevirasto.viite

import org.apache.http.{HttpHost, NameValuePair}
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.{CloseableHttpResponse, HttpGet, HttpPost}
import org.apache.http.client.utils.URIBuilder
import org.apache.http.impl.client.{HttpClientBuilder, HttpClients}
import org.apache.http.message.BasicNameValuePair
import org.json4s.{DefaultFormats, StreamInput}
import org.json4s.jackson.JsonMethods.parse
import org.slf4j.LoggerFactory
import java.net.URL
import java.util.Properties

import fi.liikennevirasto.digiroad2.util.ViiteProperties
import org.apache.http.client.config.{CookieSpecs, RequestConfig}
import org.apache.http.impl.conn.DefaultProxyRoutePlanner

import scala.util.control.NonFatal


class ViiteVkmClient {

  case class VKMError(content: Map[String, Any], url: String)

  val logger = LoggerFactory.getLogger(getClass)

  private def getRestEndPoint: String = {
    val loadedKeyString = ViiteProperties.vkmUrl
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing property: VKM URL")
    loadedKeyString
  }


  def proxyBuilder(): HttpClientBuilder = {

    val properties = new Properties()
    properties.load(getClass.getResourceAsStream("/digiroad2.properties"))

    val proxyHost = properties.getProperty("http.proxyHost")
    val proxyPort = properties.getProperty("http.proxyPort")
    val proxyStatus = properties.getProperty("http.proxySet", "false").toBoolean
    if (proxyStatus) {
      val hostP = new HttpHost(proxyHost, proxyPort.toInt)
      val routePlanner = new DefaultProxyRoutePlanner(hostP)
      val clientBuilder = HttpClients.custom()
      clientBuilder.setRoutePlanner(routePlanner)
    } else {
      HttpClientBuilder.create()
        .setDefaultRequestConfig(RequestConfig.custom()
          .setCookieSpec(CookieSpecs.STANDARD).build())
    }
  }

  /**
    * Builds http query fom given parts, executes the query, and returns the result (or error if http>=400).
    * @param params query parameters. Parameters are expected to be unescaped.
    * @return The query result, or VKMError in case the response was http>=400.
    */
  def get(path: String, params: Map[String, String]): Either[VKMError, Any] = {

    val builder = new URIBuilder(getRestEndPoint + path)

    params.foreach {
      case (param, value) => if (value.nonEmpty) builder.addParameter(param, value)
    }

    val url = builder.build.toString
    val request = new HttpGet(url)
    val client = proxyBuilder().build()

    if (builder.getHost == "localhost") {
      // allow ssh port forward for developing
      request.setHeader("Host", "api.vaylapilvi.fi")
    }

    request.addHeader("X-API-Key", ViiteProperties.vkmApiKey)

    val response = client.execute(request)
    try {
      if (response.getStatusLine.getStatusCode >= 400)
        return Left(VKMError(Map("error" -> "Request returned HTTP Error %d".format(response.getStatusLine.getStatusCode)), url))
      val content: Any = parse(StreamInput(response.getEntity.getContent)).values.asInstanceOf[Any]
      Right(content)
    } catch {
      case e: Exception => Left(VKMError(Map("error" -> e.getMessage), url))
    } finally {
      response.close()
    }
  }

  def postFormUrlEncoded(urlPart: String, parameters: Map[String, String]): Any = {
    implicit val formats = DefaultFormats
    val client = proxyBuilder().build()
    val post = new HttpPost(s"${getRestEndPoint}$urlPart")
    val nameValuePairs = new java.util.ArrayList[NameValuePair]()
    parameters.foreach { case (key, value) =>
      nameValuePairs.add(new BasicNameValuePair(key, value))
    }
    post.setEntity(new UrlEncodedFormEntity(nameValuePairs, "UTF-8"))

    post.setHeader("Content-type", "application/x-www-form-urlencoded")
    val url = new URL(getRestEndPoint)
    if (url.getHost == "localhost") {
      // allow ssh port forward for developing
      post.setHeader("Host", "api.vaylapilvi.fi")
    }
    var response: CloseableHttpResponse = null
    try {
      response = client.execute(post)
      parse(StreamInput(response.getEntity.getContent)).values.asInstanceOf[Any]
    } catch {
    case NonFatal(e) =>
      logger.error(s"VkmClient failed: ${e.getMessage} ${getRestEndPoint}$urlPart", e)
      Map(("results","Failed"))
    } finally {
      if (response != null)
        response.close()
    }
  }
}
