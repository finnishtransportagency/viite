package fi.liikennevirasto.digiroad2.dao

import fi.liikennevirasto.digiroad2.dao.Queries._
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession

object Sequences {

  def nextViitePrimaryKeySeqValue: Long = {
    nextViitePrimaryKeyId.as[Long].first
  }

  def nextViiteProjectId: Long = {
    Queries.nextViiteProjectId.as[Long].first
  }

  def nextRoadwayId: Long = {
    Queries.nextRoadwayId.as[Long].first
  }

  def nextLinearLocationId: Long = {
    Queries.nextLinearLocationId.as[Long].first
  }

  def fetchViitePrimaryKeySeqValues(len: Int): List[Long] = {
    fetchViitePrimaryKeyId(len)
  }

  def fetchRoadwayIds(len: Int): List[Long] = {
    Queries.fetchRoadwayIds(len)
  }

  def fetchLinearLocationIds(len: Int): List[Long] = {
    Queries.fetchLinearLocationIds(len)
  }

  def nextRoadwayNumber: Long = {
    Queries.nextRoadwayNumber.as[Long].first
  }

  def nextRoadNetworkErrorId: Long = {
    Queries.nextRoadNetworkErrorId.as[Long].first
  }

  def nextProjectId: Long = {
    Queries.nextProjectId.as[Long].first
  }

  def nextRoadwayChangeLink: Long = {
    Queries.nextRoadwayChangeLink.as[Long].first
  }

  def nextPublishedRoadNetworkId: Long = {
    Queries.nextPublishedRoadNetworkId.as[Long].first
  }

  def nextRoadwayPointId: Long = {
    Queries.nextRoadwayPointId.as[Long].first
  }

  def nextCalibrationPointId: Long = {
    Queries.nextCalibrationPointId.as[Long].first
  }

  def nextNodeId: Long = {
    Queries.nextNodeId.as[Long].first
  }

  def nextNodeNumber: Long = {
    Queries.nextNodeNumber.as[Long].first
  }

  def fetchNodeIds(len: Int): List[Long] = {
    Queries.fetchNodeIds(len)
  }

  def nextNodePointId: Long = {
    Queries.nextNodePointId.as[Long].first
  }

  def fetchJunctionPointIds(len: Int): List[Long] = {
    Queries.fetchJunctionPointIds(len)
  }

  def fetchNodePointIds(len: Int): List[Long] = {
    Queries.fetchNodePointIds(len)
  }

  def nextJunctionId: Long = {
    Queries.nextJunctionId.as[Long].first
  }

  def fetchJunctionIds(len: Int): List[Long] = {
    Queries.fetchJunctionIds(len)
  }

  def nextJunctionPointId: Long = {
    Queries.nextJunctionPointId.as[Long].first
  }

  def nextJunctionNumber: Long = {
    Queries.nextJunctionNumber.as[Long].first
  }
}
