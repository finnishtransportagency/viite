package fi.vaylavirasto.viite.util

case class ViiteException(message : String) extends RuntimeException {
  override def getMessage: String = message
}