package fi.liikennevirasto.viite.dao

import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.viite.AddressConsistencyValidator.AddressError
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{StaticQuery => Q}

case class RoadNetworkError(id: Long, roadwayId: Long, error: AddressError, error_timestamp: Long, network_version: Option[Long])

//TODO add unit tests for it at VIITE-1543
class RoadNetworkDAO {

  def createPublishedRoadNetwork: Unit = {
    sqlu"""INSERT INTO published_road_network (id, created) VALUES (PUBLISHED_ROAD_NETWORK_SEQ.NEXTVAL, sysdate)""".execute
  }

  def expireRoadNetwork: Unit = {
    sqlu"""UPDATE published_road_network SET valid_to = sysdate WHERE id = (SELECT MAX(ID) FROM published_road_network)""".execute
  }

  def createPublishedRoadway(networkVersion: Long, roadwayId: Long): Unit = {
    sqlu"""INSERT INTO PUBLISHED_ROADWAY (network_id, ROADWAY_ID) VALUES ($networkVersion, $roadwayId)""".execute
  }

def addRoadNetworkError(roadwayId: Long, errorCode: Long): Unit = {
  val timestamp = System.currentTimeMillis()
  val lastVersion = getLatestRoadNetworkVersionId
      val networkErrorPS = dynamicSession.prepareStatement("INSERT INTO road_network_error (id, ROADWAY_ID, error_code, error_timestamp, road_network_version)" +
        " values (?, ?, ?, ?, ?)")
      val nextId =  Sequences.nextRoadNetworkErrorSeqValue
      networkErrorPS.setLong(1, nextId)
      networkErrorPS.setLong(2, roadwayId)
      networkErrorPS.setLong(3, errorCode)
      networkErrorPS.setDouble(4, timestamp)
  lastVersion match {
        case Some(v) => networkErrorPS.setLong(5, v)
        case _ => networkErrorPS.setString(5, null)
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

  def getLatestRoadNetworkVersion: Option[Long] = {
    sql"""SELECT MAX(id) FROM published_road_network""".as[Option[Long]].first
  }

  def getLatestRoadNetworkVersionId: Option[Long] = {
    sql"""SELECT id FROM published_road_network order by id desc""".as[Long].firstOption
  }

  def getLatestPublishedNetworkDate: Option[DateTime] = {
    sql"""SELECT MAX(created) as created FROM published_road_network""".as[Option[DateTime]].first
  }

  def getRoadNetworkError(addressId: Long, error: AddressError): Option[RoadNetworkError] = {

    val query = s"""SELECT * FROM road_network_error where ROADWAY_ID = $addressId and error_code = ${error.value} order by road_network_version desc"""

    Q.queryNA[(Long, Long, Int, Long, Option[Long])](query).list.headOption.map {
      case (id, roadwayId, errorCode, timestamp, version) =>
        RoadNetworkError(id, roadwayId, AddressError.apply(errorCode), timestamp, version)
    }
  }

}
