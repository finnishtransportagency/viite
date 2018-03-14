package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.dao.{RoadName, RoadNameDAO}
import org.slf4j.LoggerFactory
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter

import scala.util.control.NonFatal

class RoadNameService() {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)


  private val logger = LoggerFactory.getLogger(getClass)
  private val dtf: DateTimeFormatter = DateTimeFormat.forPattern("dd/MM/yyyy")


  private def queryRoadNamesandNumbers(oRoadNumber: Option[Long], oRoadName: Option[String],
                                       oStartDate: Option[DateTime] = None, oEndDate: Option[DateTime] = None): Seq[RoadName] = {
    withDynTransaction {
      RoadNameDAO.getRoadNamesByRoadNameAndRoadNumber(oRoadNumber, oRoadName, None, None, oStartDate, oEndDate)
    }
  }


  /**
    * Searches roadnames by roadnumber, roadname and between history
    * @param oRoadNumber Option ruoadnumber
    * @param oRoadName option roadName
    * @param oStartDate optionStart date
    * @param oEndDate Option Endate
    * @return         Returns error message as left and right as seq of roadnames
    */
  def getRoadAddresses(oRoadNumber: Option[String], oRoadName: Option[String], oStartDate: Option[DateTime], oEndDate: Option[DateTime]): Either[String, Seq[RoadName]] = {
    try {
      (oRoadNumber, oRoadName) match {
        case (Some(roadNumber), Some(roadName)) =>
          Right(queryRoadNamesandNumbers(Some(roadNumber.toLong), Some(roadName), oStartDate, oEndDate))
        case (None, Some(roadName)) =>
          Right(queryRoadNamesandNumbers(None, Some(roadName), oStartDate, oEndDate))
        case (Some(roadNumber), None) =>
          withDynTransaction {
            Right(RoadNameDAO.getRoadNamesByRoadNumber(roadNumber.toLong, None, None, oStartDate, oEndDate))
          }
        case (None, None) => Left("Missing RoadNumber and RoadName")
      }
    }
    catch {
      case longparsingException: NumberFormatException =>
        Left("Could not parse roadnumber")
      case e if NonFatal(e) => Left("Unknown error")
    }
  }
}








