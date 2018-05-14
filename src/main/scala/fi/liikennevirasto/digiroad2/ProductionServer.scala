package fi.liikennevirasto.digiroad2

object ProductionServer extends App with DigiroadServer {
  override val viiteContextPath: String = "/viite"

  startServer()
}
