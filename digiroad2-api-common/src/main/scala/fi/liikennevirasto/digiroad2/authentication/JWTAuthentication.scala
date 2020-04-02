package fi.liikennevirasto.digiroad2.authentication

import fi.liikennevirasto.digiroad2.user.{User, UserProvider}
import javax.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory

trait JWTAuthentication extends Authentication {
  private val jwtLogger = LoggerFactory.getLogger(getClass)

  val accessTokenHeader = "x-amzn-oidc-accesstoken"
  val identityHeader = "x-amzn-oidc-identity"
  val dataHeader = "x-amzn-oidc-data"

  def authenticate(request: HttpServletRequest)(implicit userProvider: UserProvider): User = {
    val remoteUser = request.getHeader(identityHeader) // Assuming that username comes here
    jwtLogger.info("Authenticate request (JWT), remote user = " + remoteUser)
    if (remoteUser == null || remoteUser.isEmpty) {
      throw UnauthenticatedException()
    }

    userProvider.getUser(remoteUser).getOrElse(viewerUser)
  }
}
