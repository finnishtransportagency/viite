package fi.liikennevirasto.digiroad2.authentication

import javax.servlet.http.HttpServletRequest
import fi.liikennevirasto.digiroad2.user.{Configuration, User, UserProvider}
import fi.liikennevirasto.digiroad2.Digiroad2Context._
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithReadOnlySession
import org.slf4j.LoggerFactory

trait Authentication extends TestUserSupport {

  private val authLogger = LoggerFactory.getLogger(getClass)
  val viewerUser: User = User(0, "", Configuration(roles = Set("viewer")))

  // NOTE: maybe cache user data if required for performance reasons
  def authenticateForApi(request: HttpServletRequest)(implicit userProvider: UserProvider): Unit = {
    userProvider.clearCurrentUser()
    try {
      runWithReadOnlySession {
        userProvider.setCurrentUser(authenticate(request)(userProvider))
      }
    } catch {
      case ua: UnauthenticatedException =>
        if (authenticationTestModeEnabled) {
          authLogger.info("Remote user not found, falling back to test mode authentication")
          runWithReadOnlySession {
            userProvider.setCurrentUser(getTestUser(request)(userProvider).getOrElse(viewerUser))
          }
        } else {
          throw ua
        }
    }
  }

  def authenticate(request: HttpServletRequest)(implicit userProvider: UserProvider): User
}
