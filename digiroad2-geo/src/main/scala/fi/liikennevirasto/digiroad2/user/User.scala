package fi.liikennevirasto.digiroad2.user

case class Configuration(
                        zoom: Option[Long] = None,
                        east: Option[Long] = None,
                        north: Option[Long] = None,
                        municipalityNumber: Option[Int]  = None,
                        authorizedMunicipalities: Set[Int] = Set(),
                        roles: Set[String] = Set(),
                        authorizedElys: Set[Int] = Set()
                        )
case class User(id: Long, username: String, configuration: Configuration) {
  def hasWriteAccess() = !isViewer()

  def isViewer() = configuration.roles(Role.Viewer)

  def isViiteUser(): Boolean = configuration.roles(Role.ViiteUser)

  def hasViiteWriteAccess(): Boolean = configuration.roles(Role.ViiteUser)

  def isOperator(): Boolean = {
    configuration.roles(Role.Operator)
  }

  def hasEarlyAccess(): Boolean = {
    configuration.roles(Role.Premium) || configuration.roles(Role.Operator)
  }

  def isAuthorizedToRead(municipalityCode: Int): Boolean = isAuthorizedFor(municipalityCode) || isViewer()

  def isAuthorizedToWrite(municipalityCode: Int): Boolean = isAuthorizedFor(municipalityCode)

  private def isAuthorizedFor(municipalityCode: Int): Boolean =
    isOperator() || configuration.authorizedMunicipalities.contains(municipalityCode)

  def getAuthorizedElys : Set[Int] = {
    configuration.authorizedElys
  }
}

object Role {
  val Operator = "operator"
  val Administrator = "administrator"
  val Premium = "premium"
  val Viewer = "viewer"
  val ViiteUser = "viite"
}