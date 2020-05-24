package fi.liikennevirasto.viite.admin

import fi.liikennevirasto.digiroad2.util.ViiteProperties
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.scalatra.ScalatraBase
import org.scalatra.auth.strategy.{BasicAuthStrategy, BasicAuthSupport}
import org.scalatra.auth.{ScentryConfig, ScentrySupport}

case class BasicAuthUser(username: String)

class AdminAuthStrategy(protected override val app: ScalatraBase, realm: String, baseAuth: String = "admin")
  extends BasicAuthStrategy[BasicAuthUser](app, realm) {

  def validate(username: String, password: String)(implicit request: HttpServletRequest, response: HttpServletResponse): Option[BasicAuthUser] = {
    if (username == ViiteProperties.getAuthenticationBasicUsername(baseAuth) && password == ViiteProperties.getAuthenticationBasicPassword(baseAuth))
      Some(BasicAuthUser(username))
    else
      None
  }

  def getUserId(user: BasicAuthUser)(implicit request: HttpServletRequest, response: HttpServletResponse): String = user.username
}

trait AuthenticationSupport extends ScentrySupport[BasicAuthUser] with BasicAuthSupport[BasicAuthUser] {
  self: ScalatraBase =>

  def baseAuth: String = ""

  val realm = "Viite Admin API"

  protected def fromSession = {
    case id: String => BasicAuthUser(id)
  }

  protected def toSession = {
    case user: BasicAuthUser => user.username
  }

  protected val scentryConfig = (new ScentryConfig {}).asInstanceOf[ScentryConfiguration]

  override protected def configureScentry = {
    scentry.unauthenticated {
      scentry.strategies("Basic").unauthenticated()
    }
  }

  override protected def registerAuthStrategies = {
    scentry.register("Basic", app => new AdminAuthStrategy(app, realm, baseAuth))
  }
}

