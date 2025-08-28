package fi.vaylavirasto.viite.util

case class VKMException(message : String) extends RuntimeException {
  override def getMessage: String = message
}
