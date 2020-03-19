package fi.liikennevirasto.digiroad2.authentication

import javax.servlet.http.HttpServletRequest
import fi.liikennevirasto.digiroad2.user.{User, UserProvider}

trait TestUserSupport {
  def getTestUser(request: HttpServletRequest)(implicit userProvider: UserProvider): Option[User] = {
    userProvider.getUser(fi.liikennevirasto.digiroad2.Digiroad2Context.authenticationTestModeUser)
  }
}