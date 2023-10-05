package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.vaylavirasto.viite.dynamicnetwork.DynamicRoadNetworkService
import org.scalatra._
import org.slf4j.{Logger, LoggerFactory}
import fi.vaylavirasto.viite.util.DateTimeFormatters.ISOdateFormatter
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DynamicRoadNetworkApi(dynamicRoadNetworkService: DynamicRoadNetworkService) extends ScalatraServlet{
  val logger: Logger = LoggerFactory.getLogger(getClass)

  def validateDateParams(previousDate: String, newDate: String): (DateTime, DateTime) = {
    val format = ISOdateFormatter
    (format.parseDateTime(previousDate), format.parseDateTime(newDate))
  }

  get("/create_changesets") {
    time(logger, "GET request for /create_changesets") {

      val previousDate = params.get("previousDate")
      val newDate = params.get("newDate")

      (previousDate, newDate) match {
        case (Some(previousDate), Some(newDate)) =>
          try {
            val (previousDateTimeObject, newDateTimeObject) = validateDateParams(previousDate, newDate)
            Future(dynamicRoadNetworkService.updateLinkNetwork(previousDateTimeObject, newDateTimeObject))
            "Samuutus käynnistetty"
          } catch {
            case ex: IllegalArgumentException =>
              logger.error("Creating changesets failed.", ex)
              BadRequest("Unable to parse date, the date should be in yyyy-mm-dd format.")
            case e: Exception =>
              logger.error("Creating changesets failed.", e)
              InternalServerError(s"Creating changesets failed: ${e.getMessage}")
          }
        case _ => BadRequest("Missing mandatory date parameter from the url - for example: /create_changesets?previousDate=2023-05-20&newDate=2023-05-23")
      }
    }
  }
}