package fi.liikennevirasto.viite

import fi.vaylavirasto.viite.dao.{ProjectLinkNameDAO, RoadNameDAO, RoadNameScalikeDAO}
import fi.vaylavirasto.viite.model.{RoadName, RoadNameForRoadAddressBrowser}
import fi.vaylavirasto.viite.postgis.PostGISDatabase
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import fi.vaylavirasto.viite.util.DateTimeFormatters.finnishDateFormatter
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import scalikejdbc.DBSession

import scala.util.control.NonFatal

case class RoadNameRow(id: Long, name: String, startDate: String, endDate: Option[String])

class RoadNameService {

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def runWithScalikeTransaction[T](f: DBSession => T): T = PostGISDatabaseScalikeJDBC.runWithTransaction(f)

  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)

  def runWithReadOnlySession[Result](readOnlyOperation: DBSession => Result): Result =
    PostGISDatabaseScalikeJDBC.runWithReadOnlySession(readOnlyOperation)

  private val logger = LoggerFactory.getLogger(getClass)

  def getRoadNames(oRoadNumber: Option[String], oRoadName: Option[String], oStartDate: Option[DateTime], oEndDate: Option[DateTime]): Either[String, Seq[RoadName]] = {
    runWithScalikeTransaction { implicit session =>
      getRoadNamesInTX(oRoadNumber, oRoadName, oStartDate, oEndDate)
    }
  }

  def addOrUpdateRoadNames(roadNumber: Long, roadNameRows: Seq[RoadNameRow], username: String): Option[String] = {
    withDynTransaction {
      addOrUpdateRoadNamesInTX(roadNumber, roadNameRows, username)
    }
  }

  def getRoadNamesForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[RoadNameForRoadAddressBrowser] = {
    runWithReadOnlySession { implicit session =>
      RoadNameScalikeDAO.fetchRoadNamesForRoadAddressBrowser(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)(session)
    }
  }

  def addOrUpdateRoadNamesInTX(roadNumber: Long, roadNameRows: Seq[RoadNameRow], username: String): Option[String] = {
    try {
      roadNameRows.foreach {
        roadNameRow =>
          val roadNameOption = if (roadNameRow.id == NewIdValue) None else RoadNameDAO.getRoadNamesById(roadNameRow.id)
          val endDate = roadNameRow.endDate match {
            case Some(dt) => Some(new DateTime(finnishDateFormatter.parseDateTime(dt)))
            case _ => None
          }
          val newRoadName = roadNameOption match {
            case Some(roadName) =>
              RoadNameDAO.expire(roadNameRow.id, username)
              roadName.copy(createdBy = username, roadName = roadNameRow.name, endDate = endDate)
            case _ =>
              val startDate = new DateTime(finnishDateFormatter.parseDateTime(roadNameRow.startDate))
              RoadName(NewIdValue, roadNumber, roadNameRow.name, Some(startDate), endDate, createdBy = username)
          }

          RoadNameDAO.create(Seq(newRoadName))
      }
      None
    } catch {
      case e: Exception => Some(e.getMessage)
      case e: RoadNameException => Some(e.getMessage)
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
  def getRoadNamesInTX(oRoadNumber: Option[String], oRoadName: Option[String], oStartDate: Option[DateTime], oEndDate: Option[DateTime])(implicit session: DBSession): Either[String, Seq[RoadName]] = {
    try {
      (oRoadNumber, oRoadName) match {
        case (Some(roadNumber), Some(roadName)) =>
          Right(RoadNameScalikeDAO.getAllByRoadNumberAndName(roadNumber.toLong, roadName, oStartDate, oEndDate))
        case (None, Some(roadName)) =>
          Right(RoadNameScalikeDAO.getAllByRoadName(roadName, oStartDate, oEndDate))
        case (Some(roadNumber), None) =>
          Right(RoadNameScalikeDAO.getAllByRoadNumber(roadNumber.toLong, oStartDate, oEndDate))
        case (None, None) => Left("Missing either RoadNumber or RoadName")
      }
    } catch {
      case _: NumberFormatException => Left("Could not parse road number")
      case e if NonFatal(e) => Left("Unknown error" + e)
    }
  }

  /**
    * Fetches road names that are updated after the given date.
    *
    * @param since tells from which date roads are wanted
    * @return Returns error message as left and seq of road names as right
    */
  def getUpdatedRoadNames(since: DateTime, until: Option[DateTime]): Either[String, Seq[RoadName]] = {
    withDynSession {
      try {
        Right(RoadNameDAO.getUpdatedRoadNames(since, until))
      } catch {
        case e if NonFatal(e) =>
          logger.error("Failed to fetch updated road names.", e)
          Left(e.getMessage)
      }
    }
  }

  def getUpdatedRoadNamesInTX(since: DateTime, until: Option[DateTime]): Either[String, Seq[RoadName]] = {
    try {
      Right(RoadNameDAO.getUpdatedRoadNames(since, until))
    } catch {
      case e if NonFatal(e) =>
        logger.error("Failed to fetch updated road names.", e)
        Left(e.getMessage)
    }
  }

  def getRoadNameByNumber(roadNumber: Long, projectID: Long): Map[String, Any] = {
    try {
      withDynSession {
        val currentRoadNames = RoadNameDAO.getCurrentRoadNamesByRoadNumber(roadNumber)
        if (currentRoadNames.isEmpty) {
          val projectRoadNames = ProjectLinkNameDAO.get(roadNumber, projectID)
          if (projectRoadNames.isEmpty) {
            Map("roadName" -> None, "isCurrent" -> false)
          }
          else {
            Map("roadName" -> projectRoadNames.get.roadName, "isCurrent" -> false)
          }
        }
        else
          Map("roadName" -> currentRoadNames.head.roadName, "isCurrent" -> true)
      }
    }
    catch {
      case _/*longParsingException*/: NumberFormatException => Map("error" -> "Could not parse road number")
      case e if NonFatal(e) => Map("error" -> "Unknown error")
    }
  }

  def getCurrentRoadNames(roadNumbers: Seq[Long]): Seq[RoadName] = {
    withDynSession {
      RoadNameDAO.getCurrentRoadNamesByRoadNumbers(roadNumbers)
    }
  }

}

class RoadNameException(string: String) extends RuntimeException {
  override def getMessage: String = string
}
