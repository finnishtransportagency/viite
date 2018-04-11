package fi.liikennevirasto.viite.dao

import java.sql.Date

import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.user.User
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class RoadName(id: Long, roadNumber: Long, roadName: String, startDate: Option[DateTime], endDate: Option[DateTime] = None,
                    validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None, createdBy: String)


object RoadNameDAO {
  private val logger = LoggerFactory.getLogger(getClass)
  private val roadsNameQueryBase =  s"""select id,road_number,road_Name,start_date,end_date,valid_from,valid_To,created_By
  from Road_Names"""
  implicit val getRoadNameRow = new GetResult[RoadName] {
    def apply(r: PositionedResult) = {
      val roadNameId = r.nextLong()
      val roadNumber = r.nextLong()
      val roadName = r.nextString()
      val startDate = r.nextDateOption().map(d => new DateTime(d.getTime))
      val endDate = r.nextDateOption().map(d => new DateTime(d.getTime))
      val validFrom = r.nextDateOption().map(d => new DateTime(d.getTime))
      val validTo = r.nextDateOption().map(d => new DateTime(d.getTime))
      val createdBy = r.nextString()

      RoadName(roadNameId, roadNumber, roadName, startDate, endDate, validFrom, validTo, createdBy)
    }
  }

  def dateParser(oDate: Option[DateTime]):String = {
    oDate match {
      case Some(date) => {
        val dtfOut: DateTimeFormatter = DateTimeFormat.forPattern("dd.MM.yyyy")
        dtfOut.print(date)
      }
      case _=>
        logger.error("Failed to parse date in RoadName search ")
        "01.01.1900"
    }
  }

  /**
    * For checking date validity we convert string datre to datetime options
    *
    * @param dateString string formated date dd.mm.yyyy
    * @return Joda datetime
    */
  private def optionStringToDateTime(dateString: Option[String]): Option[java.sql.Date] = {
    dateString match {
      case Some(dateString) => {
        val splitDate = dateString.split('.')
        Option(new java.sql.Date(splitDate(2).toInt, splitDate(1).toInt, splitDate(0).toInt))
      }
      case _ => None
    }
  }

  private def queryList(query: String) = {
    Q.queryNA[RoadName](query).iterator.toSeq
  }

  private def withHistoryFilter(query: String, startDate: Option[DateTime], endDate: Option[DateTime]): String = {
    val startDateQuery = if(startDate.isDefined) s" and start_date >= to_date('${dateParser(startDate)}', 'dd.MM.RRRR')" else ""
    val endDateQuery = if(endDate.isDefined) s" and end_date <= to_date('${dateParser(endDate)}', 'dd.MM.RRRR')" else ""
    s"""$query $startDateQuery $endDateQuery"""
  }

  def getCurrentRoadNames(roadNumbers: Set[Long]):  Seq[RoadName] = {
    val roadNumbersStr = roadNumbers.mkString(",")
    val query =
      s"""$roadsNameQueryBase Where road_number in ($roadNumbersStr) and valid_From <= sysdate and (valid_to > sysdate or valid_to is null) and start_date <= sysdate and (end_date > sysdate or end_date is null)"""
    queryList(query)
  }

  def getCurrentRoadName(roadNumber: Long): Option[RoadName] = {
    val query =
      s"""$roadsNameQueryBase Where road_number = ($roadNumber) and valid_From <= sysdate and (valid_to > sysdate or valid_to is null) and start_date <= sysdate and (end_date > sysdate or end_date is null)"""
    queryList(query).headOption
  }

  /**
    * Get roadNames ValidFrom - ValidTo determines database history vector and startDate and endDate determines data history
    */
  def getRoadNamesByRoadNumber(roadNumber: Long, oValidFrom: Option[DateTime] = None, oValidTo: Option[DateTime] = None,
                               oStartDate: Option[DateTime] = None, oEndDate: Option[DateTime] = None): Seq[RoadName] = {
    val validFromFilter = if (oValidFrom.isDefined) s"AND valid_From >= ${dateParser(oValidFrom)}" else " AND valid_From <= sysdate"
    val validToFilter = if (oValidTo.isDefined) s"AND valid_To <= ${dateParser(oValidTo)}" else " AND (valid_to > sysdate or valid_to is null)"
    val startDateFilter = if (oStartDate.isDefined) s"AND start_Date >= ${dateParser(oStartDate)}" else ""
    val endDateFilter = if (oEndDate.isDefined) s"AND end_Date <= ${dateParser(oEndDate)}" else ""

    val query =
      s"""$roadsNameQueryBase Where road_number = $roadNumber $validFromFilter $validToFilter $startDateFilter $endDateFilter"""
    queryList(query)
  }

  def getRoadNamesByRoadNumber(roadNumbers: Seq[Long]): Seq[RoadName] = {
    roadNumbers.flatMap(rn => getRoadNamesByRoadNumber(rn))
  }

  /**
    * Get current roadName by roadNumber (endDate is null)
    */
  def getCurrentRoadNamesByRoadNumber(roadNumber: Long): Seq[RoadName] = {
    val query =
      s"""$roadsNameQueryBase where road_number = $roadNumber and end_date is null and valid_to is null """
    queryList(query)
  }

  def getCurrentRoadNamesByRoadNumbers(roadNumbers: Seq[Long]): Seq[RoadName] = {
    val query =
      s"""$roadsNameQueryBase where road_number in (${roadNumbers.mkString(",")}) and end_date is null and valid_to is null """
    queryList(query)
  }

  /**
    * Return all the valid road names for the given road number
    * @param roadNumber
    */
  def getAllByRoadNumber(roadNumber: Long, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None): Seq[RoadName] = {
    val query =
      withHistoryFilter(s"""$roadsNameQueryBase Where road_number = $roadNumber and (valid_to is null or valid_to > sysdate)""", startDate, endDate)
    queryList(query)
  }

  /**
    * Return all the valid road names for the given road name
    * @param roadName
    */
  def getAllByRoadName(roadName: String, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None): Seq[RoadName] = {
    val query =
      withHistoryFilter(s"""$roadsNameQueryBase Where road_name = $roadName and (valid_to is null or valid_to > sysdate)""", startDate, endDate)
    queryList(query)
  }

  /**
    * Return all the valid road names for the given road name and road number
    * @param roadNumber
    * @param roadName
    */
  def getAllByRoadNumberAndName(roadNumber: Long, roadName: String, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None): Seq[RoadName] = {
    val query =
      withHistoryFilter(s"""$roadsNameQueryBase Where road_name = '$roadName' and road_number = $roadNumber and (valid_to is null or valid_to > sysdate)""", startDate, endDate)
    queryList(query)
  }

  /**
    * Fetches road names that are updated after the given date.
    *
    * @param since
    * @return
    */
  def getUpdatedRoadNames(since: DateTime): Seq[RoadName] = {
    if (since != null) {
      val sinceString = since.toString("yyyy-MM-dd")
      val query =
        s"""
        SELECT * FROM road_names
        WHERE road_number IN (
            SELECT DISTINCT road_number FROM road_names
            WHERE valid_to IS NULL AND valid_from >= TO_DATE('${sinceString}', 'RRRR-MM-dd')
          ) AND valid_to IS NULL
        ORDER BY road_number, start_date desc"""
      queryList(query)
    } else {
      Seq.empty[RoadName]
    }
  }

  def getRoadNamesById(id: Long): Option[RoadName] = {
    val query =
      s"""$roadsNameQueryBase Where id = $id AND valid_to IS NULL OR valid_to > sysdate"""
    queryList(query).headOption
  }

  def expire(id: Long, user: User) = {
    val query = s"""Update ROAD_NAMES Set valid_to = sysdate, created_by = '${user.username}' where id = $id"""
    Q.updateNA(query).first
  }

  def update(id: Long, fields: Map[String, String], user: User) = {
    val roadNumber = fields.get("roadNumber")
    val roadName = fields.get("roadName")
    val startDate = fields.get("startDate")
    val endDate = fields.get("endDate")

    val numberFilter = if (roadNumber.isDefined) s" road_number = ${roadNumber.get} " else ""
    val nameFilter = if (roadName.isDefined) s" road_name = '${roadName.get}' " else ""
    val startDateFilter = if (startDate.isDefined) s" start_date = to_date('${startDate.get}','dd.MM.YYYY') " else ""
    val endDateFilter = if (endDate.isDefined) s" end_date = to_date('${endDate.get}','dd.MM.YYYY') " else ""

    val filters = Seq(numberFilter, nameFilter, startDateFilter, endDateFilter).filterNot(_ == "")
    val query = s"""Update ROAD_NAMES Set ${filters.mkString(",")} where id = $id"""
    Q.updateNA(query).first
  }

  def create(roadName: RoadName): Long = {
    val (endDateDefString, endDateValString) = if (roadName.endDate.nonEmpty) {
      (s", end_date", s", ?")
    } else ("", "")

    val query = s"insert into ROAD_NAMES (id, road_number, road_name, start_date, valid_from, valid_to, created_by $endDateDefString) values " +
      s"(?, ?, ?, ?, ?, ?, ? $endDateValString)"

    val namesPS = dynamicSession.prepareStatement(query)
    val nextId = Sequences.nextViitePrimaryKeySeqValue
    namesPS.setLong(1, nextId)
    namesPS.setLong(2, roadName.roadNumber)
    namesPS.setString(3, roadName.roadName)
    namesPS.setDate(4, new Date(roadName.startDate.get.getMillis))
    namesPS.setDate(5, new Date(roadName.startDate.get.getMillis))
    namesPS.setString(6, "")
    namesPS.setString(7, roadName.createdBy)
    if (roadName.endDate.nonEmpty) {
      namesPS.setDate(8, new Date(roadName.endDate.get.getMillis))
    }
    namesPS.addBatch()
    namesPS.executeBatch()
    namesPS.close()
    nextId
  }

  def expireByRoadNumber(roadNumbers: Set[Long], endDate: Long): Unit = {
    val roads = roadNumbers.mkString(",")
    sqlu"""UPDATE ROAD_NAMES SET VALID_TO = ${new Date(endDate)} WHERE VALID_TO IS NULL AND ROAD_NUMBER in ($roads)""".execute
  }

}
