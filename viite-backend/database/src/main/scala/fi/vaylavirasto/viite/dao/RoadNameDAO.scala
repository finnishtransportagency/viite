package fi.vaylavirasto.viite.dao

import fi.vaylavirasto.viite.util.DateTimeFormatters.finnishDateFormatter
import java.sql.{Date, Timestamp}
import com.github.tototoshi.slick.MySQLJodaSupport._ // Required for implicit functions' usage slick.jdbc.SetParameter[org.joda.time.DateTime] sql"""
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import slick.jdbc.StaticQuery.interpolation

case class RoadName(id: Long, roadNumber: Long, roadName: String, startDate: Option[DateTime], endDate: Option[DateTime] = None,
                    validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None, createdBy: String)

case class RoadNameForRoadAddressBrowser(ely: Long, roadNumber: Long, roadName: String)

object RoadNameDAO extends BaseDAO {

  private val roadsNameQueryBase =
    s"""select id,road_number,road_Name,start_date,end_date,valid_from,valid_To,created_By
        from ROAD_NAME"""

  implicit val getRoadNameRow: GetResult[RoadName] = new GetResult[RoadName] {
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

  implicit val getRoadNameForRoadAddressBrowser: GetResult[RoadNameForRoadAddressBrowser] = new GetResult[RoadNameForRoadAddressBrowser] {
    def apply(r: PositionedResult) = {
      val ely = r.nextLong()
      val roadNumber = r.nextLong()
      val roadName = r.nextString()

      RoadNameForRoadAddressBrowser(ely, roadNumber, roadName)
    }
  }

  def dateParser(oDate: Option[DateTime]): String = {
    oDate match {
      case Some(date) =>
        finnishDateFormatter.print(date)

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
      case Some(dateString) =>
        val splitDate = dateString.split('.')
        Option(new java.sql.Date(splitDate(2).toInt, splitDate(1).toInt, splitDate(0).toInt))
      case _ => None
    }
  }

  private def queryList(query: String) = {
    Q.queryNA[RoadName](query).iterator.toSeq
  }

  private def withHistoryFilter(query: String, startDate: Option[DateTime], endDate: Option[DateTime]): String = {
    val startDateQuery = if (startDate.isDefined) s" and start_date >= to_date('${dateParser(startDate)}', 'dd.MM.YYYY')" else ""
    val endDateQuery = if (endDate.isDefined) s" and end_date <= to_date('${dateParser(endDate)}', 'dd.MM.YYYY')" else ""
    s"""$query $startDateQuery $endDateQuery"""
  }

  def getLatestRoadName(roadNumber: Long): Option[RoadName] = {
    val query =
      s"""$roadsNameQueryBase Where road_number = $roadNumber and valid_to is null and end_date is null"""
    queryList(query).headOption
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
    *
    * @param roadNumber
    */
  def getAllByRoadNumber(roadNumber: Long, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None): Seq[RoadName] = {
    val query =
      withHistoryFilter(s"""$roadsNameQueryBase Where road_number = $roadNumber and valid_to is null""", startDate, endDate)
    queryList(query)
  }

  /**
    * Return all the valid road names for the given road name
    *
    * @param roadName
    */
  def getAllByRoadName(roadName: String, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None): Seq[RoadName] = {
    val query =
      withHistoryFilter(s"""$roadsNameQueryBase Where road_name = $roadName and valid_to is null""", startDate, endDate)
    queryList(query)
  }

  /**
    * Return all the valid road names for the given road name and road number
    *
    * @param roadNumber
    * @param roadName
    */
  def getAllByRoadNumberAndName(roadNumber: Long, roadName: String, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None): Seq[RoadName] = {
    val query =
      withHistoryFilter(s"""$roadsNameQueryBase Where road_name = '$roadName' and road_number = $roadNumber and valid_to is null""", startDate, endDate)
    queryList(query)
  }

  /**
    * Fetches road names that are updated after the given date.
    *
    * @param since
    * @return
    */
  def getUpdatedRoadNames(since: DateTime, until: Option[DateTime]): Seq[RoadName] = {
    val untilString = if (until.nonEmpty) s"AND CREATED_TIME <= to_timestamp('${new Timestamp(until.get.getMillis)}', 'YYYY-MM-DD HH24:MI:SS.FF')" else s""
        sql"""
        SELECT * FROM ROAD_NAME
        WHERE road_number IN (
            SELECT DISTINCT road_number FROM ROAD_NAME
            WHERE valid_to IS NULL AND CREATED_TIME >= $since #$untilString
          ) AND valid_to IS NULL
        ORDER BY road_number, start_date desc
      """.as[RoadName].list
  }

  def getRoadNamesById(id: Long): Option[RoadName] = {
    val query =
      s"""$roadsNameQueryBase Where id = $id AND valid_to IS NULL"""
    queryList(query).headOption
  }

  def expire(id: Long, username: String): Int = {
    val query = s"""Update ROAD_NAME Set valid_to = current_timestamp, created_by = '$username' where id = $id"""
    runUpdateToDb(query)
  }

  def expireAndCreateHistory(idToExpire: Long, username: String, historyRoadName: RoadName): Unit = {
    expire(idToExpire, username)
    create(Seq(historyRoadName))
  }

  def update(id: Long, fields: Map[String, String]): Int = {
    val roadNumber = fields.get("roadNumber")
    val roadName = fields.get("roadName")
    val startDate = fields.get("startDate")
    val endDate = fields.get("endDate")

    val numberFilter = if (roadNumber.isDefined) s" road_number = ${roadNumber.get} " else ""
    val nameFilter = if (roadName.isDefined) s" road_name = '${roadName.get}' " else ""
    val startDateFilter = if (startDate.isDefined) s" start_date = to_date('${startDate.get}','dd.MM.YYYY') " else ""
    val endDateFilter = if (endDate.isDefined) s" end_date = to_date('${endDate.get}','dd.MM.YYYY') " else ""

    val filters = Seq(numberFilter, nameFilter, startDateFilter, endDateFilter).filterNot(_ == "")
    val query = s"""Update ROAD_NAME Set ${filters.mkString(",")} where id = $id"""
    runUpdateToDb(query)
  }

  def create(roadNames: Seq[RoadName]): Unit = {
    val query = s"insert into ROAD_NAME (id, road_number, road_name, start_date, created_by, end_date) values " +
      s"(?, ?, ?, ?, ?, ?)"

    val namesPS = dynamicSession.prepareStatement(query)

    roadNames.foreach(roadName => {
      val nextId = Sequences.nextRoadNameId
      namesPS.setLong(1, nextId)
      namesPS.setLong(2, roadName.roadNumber)
      namesPS.setString(3, roadName.roadName)
      namesPS.setDate(4, new Date(roadName.startDate.get.getMillis))
      namesPS.setString(5, roadName.createdBy)
      if (roadName.endDate.isDefined) {
        namesPS.setDate(6, new Date(roadName.endDate.get.getMillis))
      } else {
        namesPS.setNull(6, java.sql.Types.DATE)
      }
      namesPS.addBatch()
    })
    namesPS.executeBatch()
    namesPS.close()
  }

  /**
    * generates required number of ? for preparedstatement when using (in) clause
    *
    * @param roads
    * @return
    */
  protected def qMarksGenerator(roads: Set[Long]): String = {
    val inClause = new StringBuilder
    for (i <- roads) {
      inClause.append("?, ")
    }
    inClause.dropRight(2).toString()
  }


  def expireByRoadNumber(roadNumbers: Set[Long], validTo: Long): Unit = {
    if (roadNumbers.isEmpty) return // dont even bother with empty set
    val query = s" UPDATE ROAD_NAME SET VALID_TO = ? WHERE VALID_TO IS NULL AND ROAD_NUMBER in (${qMarksGenerator(roadNumbers)})"
    val roadNamesPS = dynamicSession.prepareStatement(query)
    roadNamesPS.setDate(1, new Date(validTo))
    var index = 2
    for (roadNumber <- roadNumbers) {
      roadNamesPS.setLong(index, roadNumber)
      index += 1
    }
    roadNamesPS.addBatch()
    roadNamesPS.executeBatch()
    roadNamesPS.close()
  }

  def fetchRoadNamesForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long],
                                          minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]) : Seq[RoadNameForRoadAddressBrowser]= {
    def withOptionalParameters(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long],
                               minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long])(query: String): String = {
      val rwDateCondition = "AND rw.START_DATE <='" + situationDate.get + "' AND (rw.END_DATE >= '" + situationDate.get + "' OR rw.END_DATE IS NULL)"

      /**
        * if the situationDate == RoadNameHistoryRow.startDate AND situationDate == RoadNameCurrentRow.endDate then RoadNameCurrentRow is picked
        * example:
        * situationDate = 15.11.2022
        * historyRow  RoadName(name: "old road name", startDate: 1.12.2015, endDate: 15.11.2022)
        * currentRow  RoadName(name: "new road name", startDate: 15.11.2022, endDate: NULL) <- currentRow is picked
        */
      val roadNameDateCondition = "AND rn.START_DATE <='" + situationDate.get + "' AND (rn.END_DATE > '" + situationDate.get + "' OR rn.END_DATE IS NULL)"

      val elyCondition = {
        if (ely.nonEmpty)
          s" AND rw.ely = ${ely.get}"
        else
          ""
      }

      val roadNumberCondition = {
        if (roadNumber.nonEmpty)
          s" AND rw.road_number = ${roadNumber.get}"
        else
          ""
      }

      val roadPartCondition = {
        val parts = (minRoadPartNumber, maxRoadPartNumber)
        parts match {
          case (Some(minPart), Some(maxPart)) => s"AND rw.road_part_number BETWEEN $minPart AND $maxPart"
          case (None, Some(maxPart)) => s"AND rw.road_part_number <= $maxPart"
          case (Some(minPart), None) => s"AND rw.road_part_number >= $minPart"
          case _ => ""
        }
      }

      s"""$query
          JOIN ROADWAY rw ON rw.road_number = rn.road_number AND rw.valid_to IS NULL $rwDateCondition
          WHERE rn.valid_to IS NULL
          $roadNameDateCondition $elyCondition $roadNumberCondition $roadPartCondition
          ORDER BY rw.ely, rw.road_number """.stripMargin
    }

    def fetchRoadNames(queryFilter: String => String): Seq[RoadNameForRoadAddressBrowser] = {
      val query =
        """
      SELECT DISTINCT rw.ely, rw.road_number, rn.road_name
        FROM road_name rn
      """
      val filteredQuery = queryFilter(query)
      Q.queryNA[RoadNameForRoadAddressBrowser](filteredQuery).iterator.toSeq
    }
    fetchRoadNames(withOptionalParameters(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber))
  }
}
