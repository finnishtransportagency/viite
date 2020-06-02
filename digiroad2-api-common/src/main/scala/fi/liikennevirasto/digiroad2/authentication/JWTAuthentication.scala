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

  def getUsername(jwt: String): String = {
    val jwtParts = jwt.split('.')
    val jwtHeaderBase64Encoded = jwtParts(0)
    val jwtPayloadBase64Encoded = jwtParts(1)

    // {"typ":"JWT","kid":"e38c2a2d-bf84-4c42-ba20-ad9eedfa9a47","alg":"ES256","iss":"https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_oNzPsiXEJ","client":"3ctc20d3i4ghv94ks0semt4e15","signer":"arn:aws:elasticloadbalancing:eu-west-1:083589282917:loadbalancer/app/Vayla-DMZ-ALB/9fc6c0992b74af07","exp":1591094805}
    val jwtHeaderJsonString = new String(Base64.getDecoder.decode(jwtHeaderBase64Encoded), StandardCharsets.UTF_8)

    val jwtHeaderJson = JSON.parseFull(jwtHeaderJsonString)
    val jwtHeader = jwtHeaderJson match {
      case Some(map: Map[String, Any]) => JWTHeader(map("typ").asInstanceOf[String], map("kid").asInstanceOf[String],
        map("alg").asInstanceOf[String], map("iss").asInstanceOf[String], map("client").asInstanceOf[String],
        map("signer").asInstanceOf[String], map("exp").asInstanceOf[Long])
      case None => {
        logger.error(s"Parsing of JWT header failed. JSON: $jwtHeaderJsonString")
        throw UnauthenticatedException()
      }
      case _ => {
        logger.error(s"Parsing of JWT header failed. Unknown data structure. JSON: $jwtHeaderJsonString")
        throw UnauthenticatedException()
      }
    }
    val jwtPayload = new String(Base64.getDecoder.decode(jwtPayloadBase64Encoded), StandardCharsets.UTF_8)
    logger.info(s"JWT Payload: $jwtPayload")
    getUsername(jwtPayload, jwtHeader.kid)
  }

  def getUsername(jwtPayload: String, kid: String, tryCount: Integer = 0): String = {
    val key: String = AWSPublicKeyRetriever.getPublicKey(kid)

    // {"custom:rooli":"int_kayttajat,Extranet_Kayttaja,arn:aws:iam::117531223221:role/ViiteAdmin\\,arn:aws:iam::117531223221:saml-provider/VaylaTestOAM","sub":"2b5a2b65-ca06-46e2-8a52-a5190b495d12","email_verified":"false","custom:uid":"K567997","email":"sami.kosonen@cgi.com","username":"vaylatestoam_sami.kosonen@cgi.com","exp":1591117019,"iss":"https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_oNzPsiXEJ"}
    val decoded = decodeToken(jwtPayload, key)

    if (decoded.isFailure && tryCount < 1) {
      AWSPublicKeyRetriever.refreshPublicKey(kid)
      val username = getUsername(jwtPayload, kid, tryCount = 1)
      username
    } else if (decoded.isFailure) {
      logger.error(s"Decoding AWS payload failed. Token: $jwtPayload, kid: $kid")
      throw UnauthenticatedException()
    } else {
      val jsonString = decoded.get._2
      val json = JSON.parseFull(jsonString)
      val username: String = json match {
        case Some(map: Map[String, String]) => map("custom:uid")
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
    }
  }

  def decodeToken(token: String, key: String): Try[(String, String, String)] = {

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
      if (response.getStatusLine.getStatusCode >= 400) {
        throw new Exception(s"Response status was: ${response.getStatusLine}")
      }
      val content = scala.io.Source.fromInputStream(response.getEntity.getContent)(Codec.UTF8).getLines().mkString

      // TODO Remove
      logger.info(s"Public key with beginning and end:\n $content")

      publicKey = content
        .replaceFirst("-----BEGIN PUBLIC KEY-----", "")
        .replaceFirst("-----END PUBLIC KEY-----", "")

      // TODO Remove
      logger.info(s"Public key:\n $publicKey")

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

// {"typ":"JWT","kid":"e38c2a2d-bf84-4c42-ba20-ad9eedfa9a47","alg":"ES256","iss":"https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_oNzPsiXEJ","client":"3ctc20d3i4ghv94ks0semt4e15","signer":"arn:aws:elasticloadbalancing:eu-west-1:083589282917:loadbalancer/app/Vayla-DMZ-ALB/9fc6c0992b74af07","exp":1591094805}
case class JWTHeader(typ: String, kid: String, alg: String, iss: String, client: String, signer: String, exp: Long) {
  override def toString() = {
    s"""{"typ":"$typ","kid":"$kid","alg":"$alg","iss":"$iss","client":"$client","signer":"$signer","exp":"$exp"}"""
  }
}

trait JWTAuthentication extends Authentication {
  private val jwtLogger = LoggerFactory.getLogger(getClass)

  val accessTokenHeader = "X-Iam-Accesstoken"
  val identityHeader = "X-Iam-Identity"
  val dataHeader = "X-Iam-Data"

  def authenticate(request: HttpServletRequest)(implicit userProvider: UserProvider): User = {

    // eyJ0eXAiOiJKV1QiLCJraWQiOiJlMzQ0NGNhOS0wNThiLTRmN2YtODFiYi1mMmU2ZTRlZTE3NTYiLCJhbGciOiJFUzI1NiIsImlzcyI6Imh0dHBzOi8vY29nbml0by1pZHAuZXUtd2VzdC0xLmFtYXpvbmF3cy5jb20vZXUtd2VzdC0xX29OelBzaVhFSiIsImNsaWVudCI6IjNjdGMyMGQzaTRnaHY5NGtzMHNlbXQ0ZTE1Iiwic2lnbmVyIjoiYXJuOmF3czplbGFzdGljbG9hZGJhbGFuY2luZzpldS13ZXN0LTE6MDgzNTg5MjgyOTE3OmxvYWRiYWxhbmNlci9hcHAvVmF5bGEtRE1aLUFMQi85ZmM2YzA5OTJiNzRhZjA3IiwiZXhwIjoxNTkwNzU5ODA5fQ==.eyJjdXN0b206cm9vbGkiOiJpbnRfa2F5dHRhamF0LEV4dHJhbmV0X0theXR0YWphLGFybjphd3M6aWFtOjoxMTc1MzEyMjMyMjE6cm9sZS9WaWl0ZUFkbWluXFwsYXJuOmF3czppYW06OjExNzUzMTIyMzIyMTpzYW1sLXByb3ZpZGVyL1ZheWxhVGVzdE9BTSIsInN1YiI6IjJiNWEyYjY1LWNhMDYtNDZlMi04YTUyLWE1MTkwYjQ5NWQxMiIsImVtYWlsX3ZlcmlmaWVkIjoiZmFsc2UiLCJjdXN0b206dWlkIjoiSzU2Nzk5NyIsImVtYWlsIjoic2FtaS5rb3NvbmVuQGNnaS5jb20iLCJ1c2VybmFtZSI6InZheWxhdGVzdG9hbV9zYW1pLmtvc29uZW5AY2dpLmNvbSIsImV4cCI6MTU5MDc1OTgwOSwiaXNzIjoiaHR0cHM6Ly9jb2duaXRvLWlkcC5ldS13ZXN0LTEuYW1hem9uYXdzLmNvbS9ldS13ZXN0LTFfb056UHNpWEVKIn0=.V98ZvUxOi5LvC_CxoVt628pO2ZBGkTSXXTdDaQ5DtjEj2SOC0LuSFzEV56rNkbmIvJ7elYayOTUBZlTZmVAqQw==
    val tokenHeaderValue = request.getHeader(dataHeader)

    val username = JWTReader.getUsername(tokenHeaderValue)

    // 2b5a2b65-ca06-46e2-8a52-a5190b495d12
    //val identity = request.getHeader(identityHeader)

    // eyJraWQiOiJaK3R2MTBSQXNDcnRjU2U0YTlUR3NPTTdHc3ltMEU2VUwzT3JLZm9BYVFrPSIsImFsZyI6IlJTMjU2In0.eyJzdWIiOiIyYjVhMmI2NS1jYTA2LTQ2ZTItOGE1Mi1hNTE5MGI0OTVkMTIiLCJjb2duaXRvOmdyb3VwcyI6WyJldS13ZXN0LTFfb056UHNpWEVKX1ZheWxhVGVzdE9BTSJdLCJ0b2tlbl91c2UiOiJhY2Nlc3MiLCJzY29wZSI6Im9wZW5pZCIsImF1dGhfdGltZSI6MTU5MDc1OTY4NywiaXNzIjoiaHR0cHM6XC9cL2NvZ25pdG8taWRwLmV1LXdlc3QtMS5hbWF6b25hd3MuY29tXC9ldS13ZXN0LTFfb056UHNpWEVKIiwiZXhwIjoxNTkwNzYzMjg3LCJpYXQiOjE1OTA3NTk2ODcsInZlcnNpb24iOjIsImp0aSI6IjFmMTVkNzQyLTY4YzctNGExYS04ODNhLWIxNDg5YWYzY2I0OCIsImNsaWVudF9pZCI6IjNjdGMyMGQzaTRnaHY5NGtzMHNlbXQ0ZTE1IiwidXNlcm5hbWUiOiJ2YXlsYXRlc3RvYW1fc2FtaS5rb3NvbmVuQGNnaS5jb20ifQ.N4Qg2ZfKGlWFwGZprBp_l3tzHX22k3IGqgv00ZJJckPtXfo87t88kAA-5itJq0-JCsAT9fzc0-syuCx_WbngzKppHH_De6NuAkMZuFk22AsOvsqcGldsX2z-wu8VM_GAYmCVTLV6Yf_6zWT5VtoxY_8Ng-hqEWDKra4-9UD8_iORE3kRX1SM3UXREjZ5I9nCh-CKrem0ea0BWAb4pTyNmAFFf7WlcWvIuaJEd-KknVGKrHQDx0cB-aUv0kRN6DeakQX6OCqoMbPFRvRMmKJAKa5RIISFsTI7m_BUxQ6vmN7JsN6mVgcprcQHNtfymngg3KmGJfew-W-_Zinf1lhCLQ
    //val accessTokenHeaderValue = request.getHeader(accessTokenHeader)

    // kid = Z+tv10RAsCrtcSe4a9TGsOM7Gsym0E6UL3OrKfoAaQk=
    // alg = RS256
    //val accessToken = JWTReader.getAccessToken(accessTokenHeaderValue)

    if (username == null || username.isEmpty) {
      jwtLogger.info(s"Authentication failed. Username was empty.")
      throw UnauthenticatedException()
    }
    jwtLogger.info(s"Authenticated Väylä user: $username. Checking user privileges in Viite.")

    val user = userProvider.getUser(username).getOrElse(viewerUser)
    if (user != null) {
      jwtLogger.info(s"User ${user.username} has role '${
        if (user.isViewer()) {
          "viewer"
        } else if (user.isOperator()) {
          "operator"
        } else if (user.isViiteUser()) {
          "user"
        } else {
          "unknown"
        }
      }' in Viite")
    } else {
      logger.warn("User is null.")
    }
    user
  }

}
