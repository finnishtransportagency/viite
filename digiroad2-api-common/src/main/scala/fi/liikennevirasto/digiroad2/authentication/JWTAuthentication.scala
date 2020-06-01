package fi.liikennevirasto.digiroad2.authentication

import java.nio.charset.StandardCharsets
import java.util.Base64

import fi.liikennevirasto.digiroad2.user.{User, UserProvider}
import fi.liikennevirasto.viite.ViiteTierekisteriClient.logger
import javax.servlet.http.HttpServletRequest
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.json4s.DefaultFormats
import org.slf4j.LoggerFactory
import pdi.jwt.{Jwt, JwtAlgorithm}

import scala.io.Codec
import scala.util.Try
import scala.util.control.NonFatal
import scala.util.parsing.json.JSON

object JWTReader {

  private val logger = LoggerFactory.getLogger(getClass)

  def getUsername(token: String, accessToken: AccessToken, tryCount: Integer = 0): String = {
    val key: String = AWSPublicKeyRetriever.getPublicKey(accessToken.kid)
    val decoded = getUsername(token, key)
    if (decoded.isFailure && tryCount < 1) {
      AWSPublicKeyRetriever.refreshPublicKey(accessToken.kid)
      val jsonString = getUsername(token, accessToken, tryCount = 1)
      val json = JSON.parseFull(jsonString)
      val username: String = json match {
        case Some(map: Map[String, String]) => map("user")
        case None => {
          logger.error(s"Parsing of username failed. JSON: $jsonString")
          throw UnauthenticatedException()
        }
        case _ => {
          logger.error(s"Parsing of username failed. Unknown data structure. JSON: $jsonString")
          throw UnauthenticatedException()
        }
      }
      username
    } else if (decoded.isFailure) {
      logger.error(s"Decoding AWS token failed. Token: $token, accessToken: $accessToken")
      throw UnauthenticatedException()
    } else {
      decoded.get._2
    }
  }

  def getUsername(token: String, key: String): Try[(String, String, String)] = {

    // Assuming that the JWT Algorithm will always be the same
    Jwt.decodeRawAll(token, key, Seq(JwtAlgorithm.RS256))

  }

  def getAccessToken(accessTokenHeader: String): AccessToken = {
    val accessTokenJsonBase64Encoded = accessTokenHeader.split('.').head

    // {"kid":"Z+tv10RAsCrtcSe4a9TGsOM7Gsym0E6UL3OrKfoAaQk=","alg":"RS256"}
    val accessTokenJsonString = new String(Base64.getDecoder.decode(accessTokenJsonBase64Encoded), StandardCharsets.UTF_8)

    val json = JSON.parseFull(accessTokenJsonString)
    val accessToken = json match {
      case Some(map: Map[String, String]) => AccessToken(map("kid"), map("alg"))
      case None => {
        logger.error(s"Parsing of access token header failed. JSON: $accessTokenJsonString")
        throw UnauthenticatedException()
      }
      case _ => {
        logger.error(s"Parsing of access token header failed. Unknown data structure. JSON: $accessTokenJsonString")
        throw UnauthenticatedException()
      }
    }
    accessToken
  }
}

// Assuming that the kid remains the same across different requests of different users
object AWSPublicKeyRetriever {

  private val client = HttpClientBuilder.create().build

  var publicKey: String = ""

  def getPublicKey(kid: String): String = {
    if (publicKey.isEmpty) {
      refreshPublicKey(kid)
    }
    publicKey
  }

  def refreshPublicKey(kid: String): Unit = {
    val url = "https://public-keys.auth.elb.eu-west-1.amazonaws.com/" + kid
    logger.info(s"Refreshing JWT public key from url: $url")
    implicit val formats = DefaultFormats
    val request = new HttpGet(url)
    val response = client.execute(request)
    try {
      publicKey = scala.io.Source.fromInputStream(response.getEntity.getContent)(Codec.UTF8).getLines().mkString
    } catch {
      case NonFatal(e) =>
        logger.error(s"Failed to get the public key from url: $url", e)
        throw UnauthenticatedException()
    } finally {
      response.close()
    }
  }

}

case class AccessToken(kid: String, alg: String) {
  override def toString() = {
    s"""{"kid":"$kid","alg":"$alg"}"""
  }
}

trait JWTAuthentication extends Authentication {
  private val jwtLogger = LoggerFactory.getLogger(getClass)

  val accessTokenHeader = "X-Iam-Accesstoken"
  val identityHeader = "X-Iam-Identity"
  val dataHeader = "X-Iam-Data"

  def authenticate(request: HttpServletRequest)(implicit userProvider: UserProvider): User = {

    // eyJ0eXAiOiJKV1QiLCJraWQiOiJlMzQ0NGNhOS0wNThiLTRmN2YtODFiYi1mMmU2ZTRlZTE3NTYiLCJhbGciOiJFUzI1NiIsImlzcyI6Imh0dHBzOi8vY29nbml0by1pZHAuZXUtd2VzdC0xLmFtYXpvbmF3cy5jb20vZXUtd2VzdC0xX29OelBzaVhFSiIsImNsaWVudCI6IjNjdGMyMGQzaTRnaHY5NGtzMHNlbXQ0ZTE1Iiwic2lnbmVyIjoiYXJuOmF3czplbGFzdGljbG9hZGJhbGFuY2luZzpldS13ZXN0LTE6MDgzNTg5MjgyOTE3OmxvYWRiYWxhbmNlci9hcHAvVmF5bGEtRE1aLUFMQi85ZmM2YzA5OTJiNzRhZjA3IiwiZXhwIjoxNTkwNzU5ODA5fQ==.eyJjdXN0b206cm9vbGkiOiJpbnRfa2F5dHRhamF0LEV4dHJhbmV0X0theXR0YWphLGFybjphd3M6aWFtOjoxMTc1MzEyMjMyMjE6cm9sZS9WaWl0ZUFkbWluXFwsYXJuOmF3czppYW06OjExNzUzMTIyMzIyMTpzYW1sLXByb3ZpZGVyL1ZheWxhVGVzdE9BTSIsInN1YiI6IjJiNWEyYjY1LWNhMDYtNDZlMi04YTUyLWE1MTkwYjQ5NWQxMiIsImVtYWlsX3ZlcmlmaWVkIjoiZmFsc2UiLCJjdXN0b206dWlkIjoiSzU2Nzk5NyIsImVtYWlsIjoic2FtaS5rb3NvbmVuQGNnaS5jb20iLCJ1c2VybmFtZSI6InZheWxhdGVzdG9hbV9zYW1pLmtvc29uZW5AY2dpLmNvbSIsImV4cCI6MTU5MDc1OTgwOSwiaXNzIjoiaHR0cHM6Ly9jb2duaXRvLWlkcC5ldS13ZXN0LTEuYW1hem9uYXdzLmNvbS9ldS13ZXN0LTFfb056UHNpWEVKIn0=.V98ZvUxOi5LvC_CxoVt628pO2ZBGkTSXXTdDaQ5DtjEj2SOC0LuSFzEV56rNkbmIvJ7elYayOTUBZlTZmVAqQw==
    val token = request.getHeader(dataHeader)

    // 2b5a2b65-ca06-46e2-8a52-a5190b495d12
    val identity = request.getHeader(identityHeader)

    // eyJraWQiOiJaK3R2MTBSQXNDcnRjU2U0YTlUR3NPTTdHc3ltMEU2VUwzT3JLZm9BYVFrPSIsImFsZyI6IlJTMjU2In0.eyJzdWIiOiIyYjVhMmI2NS1jYTA2LTQ2ZTItOGE1Mi1hNTE5MGI0OTVkMTIiLCJjb2duaXRvOmdyb3VwcyI6WyJldS13ZXN0LTFfb056UHNpWEVKX1ZheWxhVGVzdE9BTSJdLCJ0b2tlbl91c2UiOiJhY2Nlc3MiLCJzY29wZSI6Im9wZW5pZCIsImF1dGhfdGltZSI6MTU5MDc1OTY4NywiaXNzIjoiaHR0cHM6XC9cL2NvZ25pdG8taWRwLmV1LXdlc3QtMS5hbWF6b25hd3MuY29tXC9ldS13ZXN0LTFfb056UHNpWEVKIiwiZXhwIjoxNTkwNzYzMjg3LCJpYXQiOjE1OTA3NTk2ODcsInZlcnNpb24iOjIsImp0aSI6IjFmMTVkNzQyLTY4YzctNGExYS04ODNhLWIxNDg5YWYzY2I0OCIsImNsaWVudF9pZCI6IjNjdGMyMGQzaTRnaHY5NGtzMHNlbXQ0ZTE1IiwidXNlcm5hbWUiOiJ2YXlsYXRlc3RvYW1fc2FtaS5rb3NvbmVuQGNnaS5jb20ifQ.N4Qg2ZfKGlWFwGZprBp_l3tzHX22k3IGqgv00ZJJckPtXfo87t88kAA-5itJq0-JCsAT9fzc0-syuCx_WbngzKppHH_De6NuAkMZuFk22AsOvsqcGldsX2z-wu8VM_GAYmCVTLV6Yf_6zWT5VtoxY_8Ng-hqEWDKra4-9UD8_iORE3kRX1SM3UXREjZ5I9nCh-CKrem0ea0BWAb4pTyNmAFFf7WlcWvIuaJEd-KknVGKrHQDx0cB-aUv0kRN6DeakQX6OCqoMbPFRvRMmKJAKa5RIISFsTI7m_BUxQ6vmN7JsN6mVgcprcQHNtfymngg3KmGJfew-W-_Zinf1lhCLQ
    val accessTokenHeaderValue = request.getHeader(accessTokenHeader)

    // kid = Z+tv10RAsCrtcSe4a9TGsOM7Gsym0E6UL3OrKfoAaQk=
    // alg = RS256
    val accessToken = JWTReader.getAccessToken(accessTokenHeaderValue)

    // TODO Remove
    jwtLogger.info(s"Token: $token")
    jwtLogger.info(s"Identity: $identity")
    jwtLogger.info(s"Accesstoken: ${accessToken.toString()}")

    val username = JWTReader.getUsername(token, accessToken)
    jwtLogger.info(s"Authenticate request (JWT). Identity: $username, Data: $dataHeader")
    if (username == null || username.isEmpty) {
      throw UnauthenticatedException()
    }

    userProvider.getUser(username).getOrElse(viewerUser)
  }

}
