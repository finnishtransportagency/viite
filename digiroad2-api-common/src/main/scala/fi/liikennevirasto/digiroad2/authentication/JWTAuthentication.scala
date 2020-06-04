package fi.liikennevirasto.digiroad2.authentication

import java.nio.charset.StandardCharsets
import java.util.Base64

import fi.liikennevirasto.digiroad2.user.{User, UserProvider}
import fi.liikennevirasto.viite.ViiteTierekisteriClient.logger
import javax.servlet.http.HttpServletRequest
import org.json4s
import org.json4s.jackson.JsonMethods._
import org.slf4j.LoggerFactory

object JWTReader {

  private val logger = LoggerFactory.getLogger(getClass)

  implicit lazy val formats = org.json4s.DefaultFormats

  def getUsername(jwt: String): String = {
    val jwtParts = jwt.split('.')
    val jwtPayloadBase64Encoded = jwtParts(1)
    val jwtPayload = new String(Base64.getDecoder.decode(jwtPayloadBase64Encoded), StandardCharsets.UTF_8)
    logger.debug(s"JWT Payload: $jwtPayload")
    parseUsernameFromJWTPayloadJSONString(jwtPayload)
  }

  def parseUsernameFromJWTPayloadJSONString(jsonString: String): String = {
    val json: json4s.JValue = parse(jsonString)
    (json \ "custom:uid").extractOrElse("")
  }

}

trait JWTAuthentication extends Authentication {
  private val jwtLogger = LoggerFactory.getLogger(getClass)

  val dataHeader = "X-Iam-Data"

  def authenticate(request: HttpServletRequest)(implicit userProvider: UserProvider): User = {

    // Example value:
    // eyJ0eXAiOiJKV1QiLCJraWQiOiJlMzQ0NGNhOS0wNThiLTRmN2YtODFiYi1mMmU2ZTRlZTE3NTYiLCJhbGciOiJFUzI1NiIsImlzcyI6Imh0dHBzOi8vY29nbml0by1pZHAuZXUtd2VzdC0xLmFtYXpvbmF3cy5jb20vZXUtd2VzdC0xX29OelBzaVhFSiIsImNsaWVudCI6IjNjdGMyMGQzaTRnaHY5NGtzMHNlbXQ0ZTE1Iiwic2lnbmVyIjoiYXJuOmF3czplbGFzdGljbG9hZGJhbGFuY2luZzpldS13ZXN0LTE6MDgzNTg5MjgyOTE3OmxvYWRiYWxhbmNlci9hcHAvVmF5bGEtRE1aLUFMQi85ZmM2YzA5OTJiNzRhZjA3IiwiZXhwIjoxNTkwNzU5ODA5fQ==.eyJjdXN0b206cm9vbGkiOiJpbnRfa2F5dHRhamF0LEV4dHJhbmV0X0theXR0YWphLGFybjphd3M6aWFtOjoxMTc1MzEyMjMyMjE6cm9sZS9WaWl0ZUFkbWluXFwsYXJuOmF3czppYW06OjExNzUzMTIyMzIyMTpzYW1sLXByb3ZpZGVyL1ZheWxhVGVzdE9BTSIsInN1YiI6IjJiNWEyYjY1LWNhMDYtNDZlMi04YTUyLWE1MTkwYjQ5NWQxMiIsImVtYWlsX3ZlcmlmaWVkIjoiZmFsc2UiLCJjdXN0b206dWlkIjoiSzU2Nzk5NyIsImVtYWlsIjoic2FtaS5rb3NvbmVuQGNnaS5jb20iLCJ1c2VybmFtZSI6InZheWxhdGVzdG9hbV9zYW1pLmtvc29uZW5AY2dpLmNvbSIsImV4cCI6MTU5MDc1OTgwOSwiaXNzIjoiaHR0cHM6Ly9jb2duaXRvLWlkcC5ldS13ZXN0LTEuYW1hem9uYXdzLmNvbS9ldS13ZXN0LTFfb056UHNpWEVKIn0=.V98ZvUxOi5LvC_CxoVt628pO2ZBGkTSXXTdDaQ5DtjEj2SOC0LuSFzEV56rNkbmIvJ7elYayOTUBZlTZmVAqQw==
    val tokenHeaderValue = request.getHeader(dataHeader)

    val username = JWTReader.getUsername(tokenHeaderValue)

    if (username.isEmpty) {
      jwtLogger.warn(s"Authentication failed. Missing username in JWT payload.")
      throw UnauthenticatedException()
    }
    jwtLogger.info(s"Authenticated Väylä user: $username. Checking user privileges in Viite.")

    val user = userProvider.getUser(username).getOrElse(viewerUser)
    if (user != null) {
      jwtLogger.info(s"User ${user.username} has role '${
        if (user.isViiteUser()) {
          "user"
        } else if (user.isOperator()) {
          "operator"
        } else if (user.isViewer()) {
          "viewer"
        } else {
          "unknown"
        }
      }' in Viite.")
    } else {
      logger.warn("User is null.")
    }
    user
  }

}
