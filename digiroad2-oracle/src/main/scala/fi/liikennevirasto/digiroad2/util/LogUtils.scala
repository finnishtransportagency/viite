package fi.liikennevirasto.digiroad2.util

import org.slf4j.Logger

object LogUtils {
  /** Log only process durations that took more than this amount of time */
  val timeLoggingThresholdInMs = 0

  /** Logs the time it took function <i>f</i> to complete, when ever it took more than {@link timeLoggingThresholdInMs}.
    * In case <i>f</i> fails, log line is printed as an error, otherwise as debug level line. */
  def time[R](logger: Logger, operationName: String)(f: => R): R = {
    val begin = System.currentTimeMillis()
    try {
      val result = f
      val duration = System.currentTimeMillis() - begin
      if (duration >= timeLoggingThresholdInMs) {
        logger.debug(s"$operationName completed in $duration ms")
      }
      result
    } catch {
      case e: Exception =>
        logger.error(s"$operationName failed.")
        throw e
    }
  }

}
