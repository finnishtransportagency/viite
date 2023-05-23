package fi.liikennevirasto.digiroad2

case class ViiteException(message : String) extends RuntimeException {
  override def getMessage: String = message
};