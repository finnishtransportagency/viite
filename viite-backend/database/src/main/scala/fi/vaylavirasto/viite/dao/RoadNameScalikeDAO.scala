package fi.vaylavirasto.viite.dao
import fi.vaylavirasto.viite.model.{RoadName, RoadNameForRoadAddressBrowser}
import fi.vaylavirasto.viite.util.DateTimeFormatters.finnishDateFormatter
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet


object RoadNameScalike extends SQLSyntaxSupport[RoadName] {
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

object RoadNameForRoadAddressBrowserScalike extends SQLSyntaxSupport[RoadNameForRoadAddressBrowser] {
  override val tableName = "ROAD_NAME"

  def apply(rs: WrappedResultSet): RoadNameForRoadAddressBrowser = RoadNameForRoadAddressBrowser(
    ely = rs.long("ely"),
    roadNumber = rs.long("road_number"),
    roadName = rs.string("road_name")
  )
}

object RoadNameScalikeDAO extends ScalikeJDBCBaseDAO {
  private val rn = RoadNameScalike.syntax("rn") // rn is an alias for RoadNameScalike object to be used in queries
  private val rw = RoadwayScalike.syntax("rw")

  // Base query to select all columns from ROAD_NAME table
  private val selectAllFromRoadNameQuery = sqls"""
  SELECT ${rn.id}, ${rn.roadNumber}, ${rn.roadName}, ${rn.startDate}, ${rn.endDate},
         ${rn.validFrom}, ${rn.validTo}, ${rn.createdBy}
  FROM ${RoadNameScalike.as(rn)}
"""

  // Method to run select queries for RoadName objects
  private def queryList(query: SQLSyntax): Seq[RoadName] = {
    runSelectQuery(sql"$query".map(RoadNameScalike.apply))
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
    val baseQuery = sqls"""$selectAllFromRoadNameQuery WHERE ${rn.roadName} = $roadName AND ${rn.validTo} IS NULL"""
    val query = withHistoryFilter(baseQuery, startDate, endDate)
    queryList(query)
  }

  def getAllByRoadNumberAndName(roadNumber: Long, roadName: String, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None): Seq[RoadName] = {
    val baseQuery = sqls"""$selectAllFromRoadNameQuery WHERE ${rn.roadName} = $roadName AND ${rn.roadNumber} = $roadNumber AND ${rn.validTo} IS NULL"""
    val query = withHistoryFilter(baseQuery, startDate, endDate)
    queryList(query)
  }

  // Helper method to add start and end date filters to a query
  private def withHistoryFilter(query: SQLSyntax, startDate: Option[DateTime], endDate: Option[DateTime]): SQLSyntax = {
    val startDateQuery = startDate.map(date => sqls"AND ${rn.startDate} >= $date").getOrElse(sqls"")
    val endDateQuery = endDate.map(date => sqls"AND ${rn.endDate} <= $date").getOrElse(sqls"")
    sqls"$query $startDateQuery $endDateQuery"
  }

  def getRoadNamesById(id: Long): Option[RoadName] = {
    val query = sqls"""
      $selectAllFromRoadNameQuery
      WHERE ${rn.id} = $id AND ${rn.validTo} IS NULL
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
    runUpdateToDbScalike(query)
  }

  def create(roadNames: Seq[RoadName]): List[Int] = {
    logger.debug(s"Creating road names: ${roadNames.map(_.roadName).mkString(", ")}")
    val column = RoadNameScalike.column

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

    runBatchUpdateToDbScalike(query, batchParams)
  }

  def getUpdatedRoadNames(since: DateTime, until: Option[DateTime]): Seq[RoadName] = {
    val baseQuery = sqls"""
      $selectAllFromRoadNameQuery
      WHERE ${rn.roadNumber} IN (
        SELECT DISTINCT ${rn.roadNumber}
        FROM ${RoadNameScalike.as(rn)}
        WHERE ${rn.validTo} IS NULL
        AND ${rn.column("created_time")} >= $since
        ${until.map(u => sqls"AND ${rn.column("created_time")} <= $u").getOrElse(sqls"")}
      )
      AND ${rn.validTo} IS NULL
      ORDER BY ${rn.roadNumber}, ${rn.startDate} DESC
    """

    queryList(baseQuery)
  }

  /**
   * Get current roadName by roadNumber (endDate is null)
   */
  def getCurrentRoadNamesByRoadNumber(roadNumber: Long): Seq[RoadName] = {
    val query = sqls"""
      $selectAllFromRoadNameQuery
      where ${rn.roadNumber} = $roadNumber
        and ${rn.endDate} is null
        and ${rn.validTo} is null """
    queryList(query)
  }

  /**
   * Get current roadNames by roadNumbers (endDate is null)
   */
  def getCurrentRoadNamesByRoadNumbers(roadNumbers: Seq[Long]): Seq[RoadName] = {
    val query = sqls"""
      $selectAllFromRoadNameQuery
      where ${rn.roadNumber} in (${roadNumbers})
        and ${rn.endDate} is null
        and ${rn.validTo} is null
    """

    queryList(query)
  }

  def getLatestRoadName(roadNumber: Long): Option[RoadName] = {
    val query =
      sqls"""$selectAllFromRoadNameQuery Where road_number = $roadNumber and valid_to is null and end_date is null"""
    queryList(query).headOption
  }

  def fetchRoadNamesForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long],
    minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[RoadNameForRoadAddressBrowser] = {

    val baseQuery = sqls"""
    SELECT DISTINCT ${rw.ely} AS ely, ${rw.column("road_number")} AS road_number, ${rn.roadName} AS road_name
    FROM ${RoadNameScalike.as(rn)}
  """

    def withOptionalParameters(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long],
                               minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long])(baseQuery: SQLSyntax): SQL[Nothing, NoExtractor] = {
      val dateCast = sqls"CAST(${situationDate.get} AS DATE)" // Scalike doesn't support directly casting to date TODO: Better way to do this as a more reusable solution?
      val rwDateCondition = sqls"AND ${rw.startDate} <= $dateCast AND (${rw.endDate} >= $dateCast OR ${rw.endDate} IS NULL)"
      val roadNameDateCondition = sqls"AND ${rn.startDate} <= $dateCast AND (${rn.endDate} > $dateCast OR ${rn.endDate} IS NULL)"

      val elyCondition = ely.map(e => sqls"AND ${rw.ely} = $e").getOrElse(sqls"")
      val roadNumberCondition = roadNumber.map(rn => sqls"AND ${rw.column("road_number")} = $rn").getOrElse(sqls"")

      val roadPartCondition = (minRoadPartNumber, maxRoadPartNumber) match {
        case (Some(minPart), Some(maxPart)) => sqls"AND ${rw.column("road_part_number")} BETWEEN $minPart AND $maxPart"
        case (None, Some(maxPart)) => sqls"AND ${rw.column("road_part_number")} <= $maxPart"
        case (Some(minPart), None) => sqls"AND ${rw.column("road_part_number")} >= $minPart"
        case _ => sqls""
      }

      sql"""
      $baseQuery
      JOIN ${RoadwayScalike.as(rw)}
        ON ${rw.column("road_number")} = ${rn.roadNumber}
        AND ${rw.validTo} IS NULL $rwDateCondition
      WHERE ${rn.validTo} IS NULL
      $roadNameDateCondition $elyCondition $roadNumberCondition $roadPartCondition
      ORDER BY ${rw.ely}, ${rw.column("road_number")}
    """
    }

    val fullQuery = withOptionalParameters(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)(baseQuery)

    // Use runSelectQuery from ScalikeJDBCBaseDAO to execute the query
    runSelectQuery(fullQuery.map(RoadNameForRoadAddressBrowserScalike.apply))
  }

}
