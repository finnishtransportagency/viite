package fi.liikennevirasto.digiroad2.util

import org.joda.time.DateTime
import org.slf4j.Logger

object LogUtils {
  /** Log only process durations that took more than this amount of time */
  val timeLoggingThresholdInMs = 0

  /** Logs the time it took function <i>f</i> to complete, when ever it took more than {@link timeLoggingThresholdInMs}.
    * In case <i>f</i> fails, log line is printed as an error, otherwise as debug level line. */
  def time[R](logger: Logger, operationName: String, noFilter: Boolean = false, url :Option[String] = None)(f: => R): R = {
    val begin = System.currentTimeMillis()
    try {
      logger.info(s"$operationName started at ${DateTime.now()} ")
      val result    = f
      val duration  = System.currentTimeMillis() - begin
      val urlString = if (url.isDefined) {s"URL: ${url.get}"} else ""
      if (noFilter) {
        logger.info(s"$operationName completed in $duration ms and in second ${duration / 1000}, ${urlString}")
      } else {
        if (duration >= timeLoggingThresholdInMs) {
          logger.info(s"$operationName completed in $duration ms and in second ${duration / 1000}, ${urlString}")
        }
      }
      result
    } catch {
      case e: Exception =>
        logger.error(s"$operationName failed.")
        throw e
    }
  }

}
