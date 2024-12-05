package fi.vaylavirasto.viite.dao

import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet

import java.sql.Date


case class RoadName(id: Long, roadNumber: Long, roadName: String, startDate: Option[DateTime], endDate: Option[DateTime] = None,
                    validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None, createdBy: String)


object RoadName extends SQLSyntaxSupport[RoadName] {
  override val tableName = "ROAD_NAME"

  def apply(rs: WrappedResultSet): RoadName = RoadName(
    id = rs.long("id"),
    roadNumber = rs.long("road_number"),
    roadName = rs.string("road_name"),
    startDate = rs.jodaDateTimeOpt("start_date"),
    endDate = rs.jodaDateTimeOpt("end_date"),
    validFrom = rs.jodaDateTimeOpt("valid_from"),
    validTo = rs.jodaDateTimeOpt("valid_to"),
    createdBy = rs.string("created_by")
  )
}
case class RoadNameForRoadAddressBrowser(ely: Long, roadNumber: Long, roadName: String)

object RoadNameForRoadAddressBrowserScalike extends SQLSyntaxSupport[RoadNameForRoadAddressBrowser] {
  override val tableName = "ROAD_NAME"

  def apply(rs: WrappedResultSet): RoadNameForRoadAddressBrowser = RoadNameForRoadAddressBrowser(
    ely = rs.long("ely"),
    roadNumber = rs.long("road_number"),
    roadName = rs.string("road_name")
  )
}

object RoadNameDAO extends BaseDAO {
  private val rn = RoadName.syntax("rn") // rn is an alias for RoadNameScalike object to be used in queries

  // Base query to select all columns from ROAD_NAME table
  private val selectAllFromRoadNameQuery = sqls"""
  SELECT ${rn.id}, ${rn.roadNumber}, ${rn.roadName}, ${rn.startDate}, ${rn.endDate},
         ${rn.validFrom}, ${rn.validTo}, ${rn.createdBy}
  FROM ${RoadName.as(rn)}
"""

  // Method to run select queries for RoadName objects
  private def queryList(query: SQL[Nothing, NoExtractor]): Seq[RoadName] = {
    runSelectQuery(query.map(RoadName.apply))
  }

  /**
   * Get all road names from ROAD_NAME table with road number and optional start and end dates
   *
   * @param startDate Optional start date for the road names
   * @param endDate   Optional end date for the road names
   * @return List of RoadName objects
   */
  def getAllByRoadNumber(roadNumber: Long, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None): Seq[RoadName] = {
    val query = withHistoryFilter(
      sqls"""
            $selectAllFromRoadNameQuery
            WHERE ${rn.roadNumber} = $roadNumber
            AND ${rn.validTo} IS NULL
            """,
      startDate,
      endDate
    )
    queryList(query)
  }

  /**
   * Get all road names from ROAD_NAME table with road name and optional start and end dates
   *
   * @param startDate Optional start date for the road names
   * @param endDate   Optional end date for the road names
   * @return List of RoadName objects
   */
  def getAllByRoadName(roadName: String, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None): Seq[RoadName] = {
    val baseQuery =
      sqls"""
        $selectAllFromRoadNameQuery
        WHERE ${rn.roadName} = $roadName
        AND ${rn.validTo} IS NULL
        """
    val query = withHistoryFilter(baseQuery, startDate, endDate)
    queryList(query)
  }

  def getAllByRoadNumberAndName(roadNumber: Long, roadName: String, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None): Seq[RoadName] = {
    val baseQuery =
      sqls"""
        $selectAllFromRoadNameQuery
        WHERE ${rn.roadName} = $roadName
        AND ${rn.roadNumber} = $roadNumber
        AND ${rn.validTo} IS NULL
        """
    val query = withHistoryFilter(baseQuery, startDate, endDate)
    queryList(query)
  }

  // Helper method to add start and end date filters to a query
  private def withHistoryFilter(query: SQLSyntax, startDate: Option[DateTime], endDate: Option[DateTime]): SQL[Nothing, NoExtractor] = {
    val startDateQuery = startDate.map(date => sqls"AND ${rn.startDate} >= $date").getOrElse(sqls"")
    val endDateQuery = endDate.map(date => sqls"AND ${rn.endDate} <= $date").getOrElse(sqls"")
    sql"$query $startDateQuery $endDateQuery"
  }

  def getRoadNamesById(id: Long): Option[RoadName] = {
    val query = sql"""
      $selectAllFromRoadNameQuery
      WHERE ${rn.id} = $id
      AND ${rn.validTo}
      IS NULL
    """
    queryList(query).headOption
  }

  def expire(id: Long, username: String): Int = {
    logger.debug(s"Expiring road name with id: $id, username: $username")
    val query = sql"""
      UPDATE ROAD_NAME
      SET valid_to = CURRENT_TIMESTAMP, created_by = $username
      WHERE id = $id
    """
    runUpdateToDb(query)
  }

  def expireAndCreateHistory(idToExpire: Long, username: String, historyRoadName: RoadName): Unit = {
    expire(idToExpire, username)
    create(Seq(historyRoadName))
  }

  def create(roadNames: Seq[RoadName]): List[Int] = {
    logger.debug(s"Creating road names: ${roadNames.map(_.roadName).mkString(", ")}")
    val column = RoadName.column

    val batchParams: Seq[Seq[Any]] = roadNames.map { roadName =>
      Seq(
        roadName.roadNumber,
        roadName.roadName,
        roadName.startDate.orNull,
        roadName.createdBy,
        roadName.endDate.orNull
      )
    }

    val query = sql"""
      INSERT INTO ROAD_NAME (
        ${column.roadNumber},
        ${column.roadName},
        ${column.startDate},
        ${column.createdBy},
        ${column.endDate}
      ) VALUES (?, ?, ?, ?, ?)
    """

    runBatchUpdateToDb(query, batchParams)
  }


  def expireByRoadNumber(roadNumbers: Set[Long], validTo: Long): Unit = {
    if (roadNumbers.isEmpty) return // dont even bother with empty set

    val updateQuery =
      sql"""
             UPDATE ROAD_NAME
             SET VALID_TO = ?
             WHERE VALID_TO IS NULL
             AND ROAD_NUMBER in ($roadNumbers))
         """

    val batchParams = roadNumbers.map(roadNumber =>
      Seq(new Date(validTo), roadNumber)
    ).toSeq

    runBatchUpdateToDb(updateQuery, batchParams)
  }

  def getUpdatedRoadNames(since: DateTime, until: Option[DateTime]): Seq[RoadName] = {
    val query = sql"""
      $selectAllFromRoadNameQuery
      WHERE ${rn.roadNumber} IN (
        SELECT DISTINCT ${rn.roadNumber}
        FROM ${RoadName.as(rn)}
        WHERE ${rn.validTo} IS NULL
        AND ${rn.column("created_time")} >= $since
        ${until.map(u => sqls"AND ${rn.column("created_time")} <= $u").getOrElse(sqls"")}
      )
      AND ${rn.validTo} IS NULL
      ORDER BY ${rn.roadNumber}, ${rn.startDate} DESC
    """

    queryList(query)
  }

  /**
   * Get current roadName by roadNumber (endDate is null)
   */
  def getCurrentRoadNamesByRoadNumber(roadNumber: Long): Seq[RoadName] = {
    val query = sql"""
      $selectAllFromRoadNameQuery
      WHERE ${rn.roadNumber} = $roadNumber
        AND ${rn.endDate} IS NULL
        AND ${rn.validTo} IS NULL """
    queryList(query)
  }

  /**
   * Get current roadNames by roadNumbers (endDate is null)
   */
  def getCurrentRoadNamesByRoadNumbers(roadNumbers: Seq[Long]): Seq[RoadName] = {
    val query = sql"""
      $selectAllFromRoadNameQuery
      WHERE ${rn.roadNumber} IN ($roadNumbers)
        AND ${rn.endDate} IS NULL
        AND ${rn.validTo} IS NULL
    """

    queryList(query)
  }

  def getLatestRoadName(roadNumber: Long): Option[RoadName] = {
    val query =
      sql"""
           $selectAllFromRoadNameQuery
           WHERE road_number = $roadNumber
           AND valid_to IS NULL
           AND end_date IS NULL
           """
    queryList(query).headOption
  }

  def fetchRoadNamesForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long],
                                          minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[RoadNameForRoadAddressBrowser] = {

    val baseQuery = sqls"""
    SELECT DISTINCT rw.ely AS ely, rw.road_number AS road_number, ${rn.roadName} AS road_name
    FROM ${RoadName.as(rn)}
  """

    def withOptionalParameters(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long],
                               minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long])(baseQuery: SQLSyntax): SQL[Nothing, NoExtractor] = {
      val rwDateCondition = sqls"AND rw.start_date <= $situationDate::date AND (rw.end_date >= $situationDate::date OR rw.end_date IS NULL)"

      val roadNameDateCondition = sqls"AND ${rn.startDate} <= $situationDate::date AND (${rn.endDate} > $situationDate::date OR ${rn.endDate} IS NULL)"

      val elyCondition = ely.map(e => sqls"AND rw.ely = $e").getOrElse(sqls"")
      val roadNumberCondition = roadNumber.map(rn => sqls"AND rw.road_number = $rn").getOrElse(sqls"")

      val roadPartCondition = (minRoadPartNumber, maxRoadPartNumber) match {
        case (Some(minPart), Some(maxPart)) => sqls"AND rw.road_part_number BETWEEN $minPart AND $maxPart"
        case (None, Some(maxPart)) => sqls"AND rw.road_part_number <= $maxPart"
        case (Some(minPart), None) => sqls"AND rw.road_part_number >= $minPart"
        case _ => sqls""
      }

      sql"""
      $baseQuery
      JOIN ROADWAY rw
        ON rw.road_number = ${rn.roadNumber}
        AND rw.valid_to IS NULL $rwDateCondition
      WHERE ${rn.validTo} IS NULL
      $roadNameDateCondition $elyCondition $roadNumberCondition $roadPartCondition
      ORDER BY rw.ely, rw.road_number
    """
    }

    val fullQuery = withOptionalParameters(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)(baseQuery)

    // Use runSelectQuery from ScalikeJDBCBaseDAO to execute the query
    runSelectQuery(fullQuery.map(RoadNameForRoadAddressBrowserScalike.apply))
  }
}
