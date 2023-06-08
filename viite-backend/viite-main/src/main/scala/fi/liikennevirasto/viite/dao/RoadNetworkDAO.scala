package fi.liikennevirasto.viite.dao

import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.AddressConsistencyValidator.AddressError
import org.slf4j.LoggerFactory
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class RoadNetworkError(id: Long, roadwayId: Long, linearLocationId: Long, error: AddressError, error_timestamp: Long, network_version: Option[Long])


/** Data type for /summary API data */
case class RoadwayNetworkSummaryRow
(
  roadNumber: Int, roadName: String,
  roadPartNumber: Int, elyCode: Int, administrativeClass: Int,
  track: Int, startAddressM: Int, endAddressM: Int, continuity: Int
)

class RoadNetworkDAO {

  protected def logger = LoggerFactory.getLogger(getClass)

  /**
    * Fetches the data required for /summary API result generation.
    * @return The road address information, and road names of the whole latest road network
    */
  def fetchRoadwayNetworkSummary(date: Option[DateTime] = None): Seq[RoadwayNetworkSummaryRow] = {
    time(logger, "Get whole network summary") {
      val dateFilter = date match {
        case Some(date) =>
          val dateString = date.toString("yyyy-MM-dd")
          s"r.START_DATE <= to_date('$dateString', 'yyyy-MM-dd') " +
          s"AND (r.END_DATE IS NULL OR to_date('$dateString', 'yyyy-MM-dd') <= r.END_DATE) "
        case None => "r.END_DATE IS NULL"
      }

      val roadnameTable = date match {
        case Some(date) =>
          val dateString = date.toString("yyyy-MM-dd")
          s"(SELECT * FROM ROAD_NAME rn WHERE rn.START_DATE <= to_date('$dateString', 'yyyy-MM-dd')" +
          s"AND (rn.END_DATE IS NULL OR to_date('$dateString', 'yyyy-MM-dd') <= rn.END_DATE) and rn.VALID_TO IS NULL)"
        case None => "(SELECT * FROM ROAD_NAME rn WHERE rn.END_DATE IS NULL)"
      }

      val query = s"""
         SELECT r.ROAD_NUMBER, n.ROAD_NAME,
                r.ROAD_PART_NUMBER, r.ELY, r.ADMINISTRATIVE_CLASS,
                r.TRACK, r.START_ADDR_M, r.END_ADDR_M, r.DISCONTINUITY
           FROM ROADWAY r
      LEFT JOIN $roadnameTable n ON n.ROAD_NUMBER = r.ROAD_NUMBER
          WHERE r.VALID_TO IS NULL AND $dateFilter    -- no debug rows, no history rows -> latest state
       ORDER BY r.ROAD_NUMBER, r.ROAD_PART_NUMBER, r.TRACK, r.START_ADDR_M
      ;
      """
      Q.queryNA[RoadwayNetworkSummaryRow](query).list
    }
  }

  /**
    * PositionResult iterator required by queryNA[RoadwayNetworkSummaryRow]
    * @return Returns the next row from the RoadwayNetworkSummary query results as a RoadwayNetworkSummaryRow.
    */
  private implicit val getRoadwayNetworkSummaryRow
  : GetResult[RoadwayNetworkSummaryRow] = new GetResult[RoadwayNetworkSummaryRow] {
    def apply(r: PositionedResult) = {

      val roadNumber = r.nextInt()
      val roadName = r.nextString()
      val roadPartNumber = r.nextInt()
      val elyCode = r.nextInt()
      val administrativeClass = r.nextInt()
      val track = r.nextInt()
      val startAddressM = r.nextInt()
      val endAddressM = r.nextInt()
      val continuity = r.nextInt()

      RoadwayNetworkSummaryRow(
        roadNumber, roadName,
        roadPartNumber, elyCode, administrativeClass,
        track, startAddressM, endAddressM, continuity)
    }
  }

}
