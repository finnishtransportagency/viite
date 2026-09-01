package fi.liikennevirasto.digiroad2

object ProductionServer extends App with DigiroadServer {
  override val viiteContextPath: String = sys.env.getOrElse("APP_BASE_PATH", "/")

  startServer()
}
