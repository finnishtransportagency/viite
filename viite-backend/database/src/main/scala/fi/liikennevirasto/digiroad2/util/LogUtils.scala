package fi.liikennevirasto.digiroad2.util

import org.joda.time.DateTime
import org.slf4j.Logger

object LogUtils {
  /** Log only process durations that took more than this amount of time */
  val timeLoggingThresholdInMs = 0

  /** Logs the time it took function <i>f</i> to complete, when ever it took more than {@link timeLoggingThresholdInMs}.
    * In case <i>f</i> fails, log line is printed as an error, otherwise as debug level line. */
  def time[R](logger: Logger, operationName: String, noFilter: Boolean = false, url :Option[String] = None, params: Option[Map[String, String]] = None)(f: => R): R = {
    val begin = System.currentTimeMillis()
    val paramString = if (params.nonEmpty) s"params = {${params.get.map(a => s"${a._1}: ${a._2}").mkString(", ")}}" else s""
    try {
      logger.info(s"$operationName started at ${DateTime.now()}")
      val result    = f
      val duration  = System.currentTimeMillis() - begin
      val urlString = if (url.isDefined) {s"URL: ${url.get}"} else ""
      if (noFilter || duration >= timeLoggingThresholdInMs) {
        logger.info(s"$operationName completed in ${duration / 1000}s ${duration%1000}ms. $urlString $paramString")
      }
      result
    } catch {
      case e: Exception =>
        val errorString = e.getStackTrace.find(st => st.getClassName.startsWith("fi.liikennevirasto")) match {
          case Some(lineFound) => s"at $lineFound"
          case _ => s""
        }
        logger.error(s"$operationName failed. $paramString ${e.getClass.getName}: ${e.getMessage} $errorString")
        throw e
    }
  }

}
