package fi.liikennevirasto.viite

import org.apache.http.NameValuePair
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.{CloseableHttpResponse, HttpPost}
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.message.BasicNameValuePair
import org.json4s.{DefaultFormats, StreamInput}
import org.json4s.jackson.JsonMethods.parse
import org.slf4j.LoggerFactory
import java.net.URL

import fi.liikennevirasto.digiroad2.util.ViiteProperties

import scala.util.control.NonFatal


class ViiteVkmClient {

  val logger = LoggerFactory.getLogger(getClass)

  private def getRestEndPoint: String = {
    val loadedKeyString = ViiteProperties.vkmUrl
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing property: VKM URL")
    loadedKeyString
  }

  private val client = HttpClientBuilder.create().build

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