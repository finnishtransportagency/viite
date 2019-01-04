package fi.liikennevirasto.viite.dao

import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.viite.AddressConsistencyValidator.AddressError
import org.slf4j.LoggerFactory
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{StaticQuery => Q}

case class RoadNetworkError(id: Long, roadwayId: Long, linearLocationId: Long, error: AddressError, error_timestamp: Long, network_version: Option[Long])

class RoadNetworkDAO {

  protected def logger = LoggerFactory.getLogger(getClass)

  def createPublishedRoadNetwork: Unit = {
    sqlu"""INSERT INTO published_road_network (id, created) VALUES (PUBLISHED_ROAD_NETWORK_SEQ.NEXTVAL, sysdate)""".execute
  }

  def expireRoadNetwork: Unit = {
    sqlu"""UPDATE published_road_network SET valid_to = sysdate WHERE id = (SELECT MAX(ID) FROM published_road_network)""".execute
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
      case _ => networkErrorPS.setString(6, null)
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
    sql"""SELECT COUNT(*) FROM road_network_error where ROAD_NETWORK_VERSION = (SELECT MAX(id) FROM published_road_network WHERE valid_to is NULL) and roadway_id not in (select id from roadway where road_number in (${roads.mkString(",")})) """.as[Long].first > 0
  }

  def getLatestRoadNetworkVersionId: Option[Long] = {
    sql"""SELECT MAX(id) FROM published_road_network WHERE valid_to is null""".as[Option[Long]].first
  }

  def getLatestPublishedNetworkDate: Option[DateTime] = {
    sql"""SELECT MAX(created) as created FROM published_road_network WHERE valid_to is null""".as[Option[DateTime]].first
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

}
