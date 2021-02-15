package fi.liikennevirasto.viite

import org.apache.http.NameValuePair
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.{CloseableHttpResponse, HttpGet, HttpPost}
import org.apache.http.client.utils.URIBuilder
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.message.BasicNameValuePair
import org.json4s.{DefaultFormats, StreamInput}
import org.json4s.jackson.JsonMethods.parse
import org.slf4j.LoggerFactory
import java.net.URL

import fi.liikennevirasto.digiroad2.util.ViiteProperties

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

  private val client = HttpClientBuilder.create().build

  def get(path: String, params: Map[String, String]): Either[Any, VKMError] = {

    val builder = new URIBuilder(getRestEndPoint + path)

    params.foreach {
      case (param, value) => if (value.nonEmpty) builder.addParameter(param, value)
    }

    val url = builder.build.toString
    val request = new HttpGet(url)

    if (builder.getHost == "localhost") {
      // allow ssh port forward for developing
      request.setHeader("Host", "testioag.vayla.fi")
    }

    val response = client.execute(request)
    try {
      if (response.getStatusLine.getStatusCode >= 400)
        return Right(VKMError(Map("error" -> "Request returned HTTP Error %d".format(response.getStatusLine.getStatusCode)), url))
      val content: Any = parse(StreamInput(response.getEntity.getContent)).values.asInstanceOf[Any]
      Left(content)
    } catch {
      case e: Exception => Right(VKMError(Map("error" -> e.getMessage), url))
    } finally {
      response.close()
    }
  }

  def postFormUrlEncoded(urlPart: String, parameters: Map[String, String]): Any = {
    implicit val formats = DefaultFormats

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
      post.setHeader("Host", "oag.liikennevirasto.fi")
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
