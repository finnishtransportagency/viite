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
