package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.user.User
import fi.liikennevirasto.viite.dao.{RoadName, RoadNameDAO}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

case class RoadNameRows(roadId: Long, editions: Seq[RoadNameEditions])

case class RoadNameEditions(editedField: String, value: String)

class RoadNameService() {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  private val logger = LoggerFactory.getLogger(getClass)

  private def queryRoadNamesAndNumbers(oRoadNumber: Option[Long], oRoadName: Option[String],
                                       oStartDate: Option[DateTime] = None, oEndDate: Option[DateTime] = None): Seq[RoadName] = {
    RoadNameDAO.getRoadNamesByRoadNameAndRoadNumber(oRoadNumber, oRoadName, None, None, oStartDate, oEndDate)
  }

  def getRoadAddressesInTx(oRoadNumber: Option[String], oRoadName: Option[String], oStartDate: Option[DateTime], oEndDate: Option[DateTime]): Either[String, Seq[RoadName]] = {
    withDynTransaction {
      getRoadAddresses(oRoadNumber, oRoadName, oStartDate, oEndDate)
    }
  }

  def addOrUpdateRoadNamesInTx(roadNames: Seq[RoadNameRows], user: User, newTransaction: Boolean = true): Option[String] = {
    if(newTransaction)
    withDynTransaction {
      addOrUpdateRoadNames(roadNames, user)
    } else
      addOrUpdateRoadNames(roadNames, user)
    }

  def addOrUpdateRoadNames(roadNames: Seq[RoadNameRows], user: User): Option[String] = {
    try {
      roadNames.foreach(rn => {
        val fieldMaps = decodeFields(rn.editions)
        if (rn.roadId == NewRoadName) {
          //TODO validate all non-optional fields in row creation
          RoadNameDAO.create(rn.roadId, fieldMaps, user)
        } else {
          RoadNameDAO.update(rn.roadId, fieldMaps, user)
        }
      })
      None
    } catch {
      case e: Exception => Some("some error to be define in case there is already one name not expired for some dateinterval")
      case e: RoadNameException => Some(e.getMessage)
    }
  }

  private def decodeFields(editions: Seq[RoadNameEditions]) = {
    editions.foldLeft(Map.empty[String, String]) { (map, edit) =>
      CombineMaps.combine(map, Map(edit.editedField -> edit.value))
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
  def getRoadAddresses(oRoadNumber: Option[String], oRoadName: Option[String], oStartDate: Option[DateTime], oEndDate: Option[DateTime]): Either[String, Seq[RoadName]] = {
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


  def getRoadNameByNumber(roadNumber: Long) : String= {
    try{
      withDynTransaction{
        val roadNames = RoadNameDAO.getCurrentRoadNamesByRoadNumber(roadNumber)
        if(roadNames.isEmpty)
          ""
        else
          roadNames.head.roadName
      }
    }
    catch {
      case longParsingException: NumberFormatException => "Could not parse road number"
      case e if NonFatal(e) => "Unknown error"
    }
  }
}

  class RoadNameException(string: String) extends RuntimeException {
    override def getMessage: String = string
  }
