package fi.vaylavirasto.viite.dao

object Sequences extends BaseDAO {

  def nextViitePrimaryKeySeqValue: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextViitePrimaryKeyId)
  }

  def nextViiteProjectId: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextViiteProjectId)
  }

  def nextProjectLinkId: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextProjectLinkId)
  }

  def fetchProjectLinkIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchProjectLinkIds(len))
  }

  def nextRoadwayId: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextRoadwayId)
  }

  def nextRoadNameId: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextRoadNameId)
  }

  def nextRoadwayNumber: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextRoadwayNumber)
  }

  def nextRoadwayPointId: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextRoadwayPointId)
  }

  def fetchRoadwayIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchRoadwayIds(len))
  }

  def nextLinearLocationId: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextLinearLocationId)
  }

  def fetchLinearLocationIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchLinearLocationIds(len))
  }

  def nextNodeId: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextNodeId)
  }

  def fetchNodeIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchNodeIds(len))
  }

  def nextNodePointId: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextNodePointId)
  }

  def nextNodeNumber: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextNodeNumber)
  }

  def nextJunctionId: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextJunctionId)
  }

  def fetchJunctionIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchJunctionIds(len))
  }

  def nextJunctionPointId: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextJunctionPointId)
  }

  def fetchJunctionPointIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchJunctionPointIds(len))
  }

  def fetchNodePointIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchNodePointIds(len))
  }

  def nextCalibrationPointId: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextCalibrationPointId)
  }

  def nextProjectCalibrationPointId: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextProjectCalibrationPointId)
  }

  def nextRoadwayChangeLink: Long = {
    runSelectSingleFirstWithType[Long](Queries.nextRoadwayChangeLink)
  }

}
