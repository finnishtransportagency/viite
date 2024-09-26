package fi.vaylavirasto.viite.dao
import fi.vaylavirasto.viite.model.{RoadName, RoadNameForRoadAddressBrowser}
import fi.vaylavirasto.viite.util.DateTimeFormatters.finnishDateFormatter
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet


object RoadNameScalike extends SQLSyntaxSupport[RoadName] {
  override val tableName = "ROAD_NAME"

  def apply(rs: WrappedResultSet): RoadName = new RoadName(
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
      sqls"""$selectAllFromRoadNameQuery WHERE ${rn.roadNumber} = $roadNumber AND ${rn.validTo} IS NULL""",
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

  private def withHistoryFilter(query: SQLSyntax, startDate: Option[DateTime], endDate: Option[DateTime]): SQLSyntax = {
    val startDateQuery = startDate.map(date => sqls"AND ${rn.startDate} >= $date").getOrElse(sqls"")
    val endDateQuery = endDate.map(date => sqls"AND ${rn.endDate} <= $date").getOrElse(sqls"")
    sqls"$query $startDateQuery $endDateQuery"
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

    def fetchRoadNames(queryFilter: SQLSyntax => SQL[Nothing, NoExtractor]): Seq[RoadNameForRoadAddressBrowser] = {
      val query = queryFilter(baseQuery)

      query.map(rs => RoadNameForRoadAddressBrowser(
        rs.long("ely"),
        rs.long("road_number"),
        rs.string("road_name")
      )).list.apply()
    }

    fetchRoadNames(withOptionalParameters(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber))
  }

}
