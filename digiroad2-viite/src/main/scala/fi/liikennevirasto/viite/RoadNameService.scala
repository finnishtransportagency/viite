package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.dao.{RoadName, RoadNameDAO}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

class RoadNameService() {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  private val logger = LoggerFactory.getLogger(getClass)

  private def queryRoadNamesAndNumbers(oRoadNumber: Option[Long], oRoadName: Option[String],
                                       oStartDate: Option[DateTime] = None, oEndDate: Option[DateTime] = None): Seq[RoadName] = {
    RoadNameDAO.getRoadNamesByRoadNameAndRoadNumber(oRoadNumber, oRoadName, None, None, oStartDate, oEndDate)
  }

  def getRoadAddresses(oRoadNumber: Option[String], oRoadName: Option[String], oStartDate: Option[DateTime], oEndDate: Option[DateTime]): Either[String, Seq[RoadName]] = {
    withDynTransaction {
      getRoadAddressesInTX(oRoadNumber, oRoadName, oStartDate, oEndDate)
    }
  }

  /**
    * Searches road names by road number, road name and between history
    *
    * @param oRoadNumber Option road number
    * @param oRoadName   Option road name
    * @param oStartDate  Option start date
    * @param oEndDate    Option end date
    * @return Returns error message as left and right as seq of road names
    */
  def getRoadAddressesInTX(oRoadNumber: Option[String], oRoadName: Option[String], oStartDate: Option[DateTime], oEndDate: Option[DateTime]): Either[String, Seq[RoadName]] = {
    try {
      (oRoadNumber, oRoadName) match {
        case (Some(roadNumber), Some(roadName)) =>
          Right(queryRoadNamesAndNumbers(Some(roadNumber.toLong), Some(roadName), oStartDate, oEndDate))
        case (None, Some(roadName)) =>
          Right(queryRoadNamesAndNumbers(None, Some(roadName), oStartDate, oEndDate))
        case (Some(roadNumber), None) =>
          Right(RoadNameDAO.getRoadNamesByRoadNumber(roadNumber.toLong, None, None, oStartDate, oEndDate))
        case (None, None) => Left("Missing RoadNumber")
      }
    } catch {
      case longParsingException: NumberFormatException => Left("Could not parse road number")
      case e if NonFatal(e) => Left("Unknown error")
    }
  }

  def getUpdatedRoadNames(since: DateTime): Either[String, Seq[RoadName]] = {
    withDynTransaction {
      getUpdatedRoadNamesInTX(since)
    }
  }

  /**
    * Fetches road names that are updated after the given date.
    *
    * @param since
    * @return Returns error message as left and seq of road names as right
    */
  def getUpdatedRoadNamesInTX(since: DateTime): Either[String, Seq[RoadName]] = {
    try {
      Right(RoadNameDAO.getUpdatedRoadNames(since))
    } catch {
      case e if NonFatal(e) =>
        logger.error("Failed to fetch updated road names.", e)
        Left(e.getMessage)
    }
  }

}
