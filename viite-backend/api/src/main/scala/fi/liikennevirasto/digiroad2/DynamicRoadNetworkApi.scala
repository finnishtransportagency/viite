package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.util.DynamicRoadNetworkService
import org.scalatra._
import org.slf4j.{Logger, LoggerFactory}

import java.text.{ParseException, SimpleDateFormat}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class DynamicRoadNetworkApi(dynamicRoadNetworkService: DynamicRoadNetworkService) extends ScalatraServlet{
  val logger: Logger = LoggerFactory.getLogger(getClass)

  get("/create_changesets") {
    time(logger, "GET request for /create_changesets") {

      val previousDate = params.get("previousDate")
      val newDate = params.get("newDate")

      (previousDate, newDate) match {
        case (Some(previousDate), Some(newDate)) =>
          try {
            val format = new SimpleDateFormat("yyyy-MM-dd")
            val previousDateObject = format.parse(previousDate)
            val newDateObject = format.parse(newDate)
            Future(dynamicRoadNetworkService.createRoadLinkChangeSets(previousDateObject, newDateObject))
            "Samuutus käynnistetty"
          } catch {
            case ex: ParseException =>
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


