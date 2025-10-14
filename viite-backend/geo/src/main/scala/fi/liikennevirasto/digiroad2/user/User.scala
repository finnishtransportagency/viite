package fi.liikennevirasto.digiroad2.user

case class Configuration(
                        zoom: Option[Long] = None,
                        east: Option[Long] = None,
                        north: Option[Long] = None,
                        roles: Set[String] = Set(),
                        authorizedElys: Set[Int] = Set(),
                        authorizedEvks: Set[Int] = Set()
                        )
case class User(id: Long, username: String, configuration: Configuration) {

  def isViewer:            Boolean = configuration.roles(Role.Viewer)
  def isViiteUser:         Boolean = configuration.roles(Role.ViiteUser)
  def hasViiteWriteAccess: Boolean = configuration.roles(Role.ViiteUser)
  def isOperator:          Boolean = configuration.roles(Role.Operator)
  def isDev:               Boolean = configuration.roles(Role.Dev)

  def getAuthorizedElys : Set[Int] = {
    configuration.authorizedElys
  }

  def getAuthorizedEvks : Set[Int] = {
    configuration.authorizedEvks
  }

}

object Role {
  val Operator = "operator"
  val Viewer = "viewer"
  val ViiteUser = "viite"
  val Dev = "dev"
}
