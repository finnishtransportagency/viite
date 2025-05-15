package fi.liikennevirasto.digiroad2.authentication

case class UnauthenticatedException(message: String = "") extends RuntimeException
