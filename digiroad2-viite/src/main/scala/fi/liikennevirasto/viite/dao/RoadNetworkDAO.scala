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

  def createPublishedRoadNetwork (publishedRoadNetworkId: Long = Sequences.nextPublishedRoadNetworkId): Unit = {
    sqlu"""INSERT INTO published_road_network (id, created) VALUES ($publishedRoadNetworkId, current_timestamp)""".execute
  }

  def expireRoadNetwork: Unit = {
    sqlu"""UPDATE published_road_network SET valid_to = current_timestamp WHERE id = (SELECT MAX(ID) FROM published_road_network)""".execute
  }

  def createPublishedRoadway(networkVersion: Long, roadwayId: Long): Unit = {
    sqlu"""INSERT INTO PUBLISHED_ROADWAY (network_id, ROADWAY_ID) VALUES ($networkVersion, $roadwayId)""".execute
  }

  def addRoadNetworkError(roadwayId: Long, linearLocationId: Long, addressError: AddressError, networkVersion: Option[Long]): Unit = {
    val timestamp = System.currentTimeMillis()
    val networkErrorPS = dynamicSession.prepareStatement(
      """INSERT INTO road_network_error (id, ROADWAY_ID, linear_location_id, error_code, error_timestamp, road_network_version)
      values (?, ?, ?, ?, ?, ?)""")
    val nextId = Sequences.nextRoadNetworkErrorId
    networkErrorPS.setLong(1, nextId)
    networkErrorPS.setLong(2, roadwayId)
    networkErrorPS.setLong(3, linearLocationId)
    networkErrorPS.setLong(4, addressError.value)
    networkErrorPS.setDouble(5, timestamp)
    networkVersion match {
      case Some(v) => networkErrorPS.setLong(6, v)
      case _ => networkErrorPS.setNull(6, java.sql.Types.BIGINT)
    }
    networkErrorPS.addBatch()
    networkErrorPS.executeBatch()
    networkErrorPS.close()
  }

  def removeNetworkErrors: Unit = {
    sqlu"""DELETE FROM road_network_error""".execute
  }

  def hasRoadNetworkErrors: Boolean = {
    sql"""SELECT COUNT(*) FROM road_network_error """.as[Long].first > 0
  }

  def hasCurrentNetworkErrors: Boolean = {
    sql"""SELECT COUNT(*) FROM road_network_error where ROAD_NETWORK_VERSION = (SELECT MAX(id) FROM published_road_network WHERE valid_to is NULL) """.as[Long].first > 0
  }

  def hasCurrentNetworkErrorsForOtherNumbers(roads: Set[Long]): Boolean = {
    val query =
      s"""SELECT COUNT(*) FROM road_network_error where ROAD_NETWORK_VERSION = (SELECT MAX(id) FROM published_road_network WHERE valid_to is NULL)
          and roadway_id not in (select id from roadway where road_number in (${roads.mkString(", ")})) """
    Q.queryNA[Long](query).list.head > 0
  }

  def getLatestRoadNetworkVersionId: Option[Long] = {
    sql"""SELECT MAX(id) FROM published_road_network WHERE valid_to is null""".as[Option[Long]].first
  }

  def getLatestPublishedNetworkDate: Option[DateTime] = {
    sql"""SELECT MAX(created) as created FROM published_road_network WHERE valid_to is null""".as[Option[DateTime]].first
  }

  def getRoadNetworkErrors: List[RoadNetworkError] = {
    val query =
      s"""SELECT id, roadway_id, linear_location_id, error_code, error_timestamp, road_network_version
         FROM road_network_error order by road_network_version desc""".stripMargin

    Q.queryNA[(Long, Long, Long, Int, Long, Option[Long])](query).list.map {
      case (id, roadwayId, linearLocationId, errorCode, timestamp, version) =>
        RoadNetworkError(id, roadwayId, linearLocationId, AddressError.apply(errorCode), timestamp, version)
    }
  }

  def getRoadNetworkErrors(error: AddressError): List[RoadNetworkError] = {
    val query =
      s"""SELECT id, roadway_id, linear_location_id, error_code, error_timestamp, road_network_version
         FROM road_network_error where error_code = ${error.value} order by road_network_version desc""".stripMargin

    Q.queryNA[(Long, Long, Long, Int, Long, Option[Long])](query).list.map {
      case (id, roadwayId, linearLocationId, errorCode, timestamp, version) =>
        RoadNetworkError(id, roadwayId, linearLocationId, AddressError.apply(errorCode), timestamp, version)
    }
  }

  def getRoadNetworkErrors(roadwayId: Long, error: AddressError): List[RoadNetworkError] = {
    val query =
      s"""SELECT id, roadway_id, linear_location_id, error_code, error_timestamp, road_network_version
         FROM road_network_error where ROADWAY_ID = $roadwayId and error_code = ${error.value} order by road_network_version desc""".stripMargin

    Q.queryNA[(Long, Long, Long, Int, Long, Option[Long])](query).list.map {
      case (id, roadwayId, linearLocationId, errorCode, timestamp, version) =>
        RoadNetworkError(id, roadwayId, linearLocationId, AddressError.apply(errorCode), timestamp, version)
    }
  }

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
