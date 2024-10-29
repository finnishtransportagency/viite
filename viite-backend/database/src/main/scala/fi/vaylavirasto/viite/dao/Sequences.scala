package fi.vaylavirasto.viite.dao

object Sequences extends BaseDAO {

  def nextViitePrimaryKeySeqValue: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextViitePrimaryKeyId).getOrElse(
      throw new NoSuchElementException("No value returned for nextViitePrimaryKeyId")
    )
  }

  def nextViiteProjectId: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextViiteProjectId).getOrElse {
      throw new NoSuchElementException("No value returned for nextViiteProjectId")
    }
  }

  def nextProjectLinkId: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextProjectLinkId).getOrElse {
      throw new NoSuchElementException("No value returned for nextProjectLinkId")
    }
  }

  def fetchProjectLinkIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchProjectLinkIds(len))
  }

  def nextRoadwayId: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextRoadwayId).getOrElse {
      throw new NoSuchElementException("No value returned for nextRoadwayId")
    }
  }

  def nextRoadNameId: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextRoadNameId).getOrElse {
      throw new NoSuchElementException("No value returned for nextRoadNameId")
    }
  }

  def nextRoadwayNumber: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextRoadwayNumber).getOrElse {
      throw new NoSuchElementException("No value returned for nextRoadwayNumber")
    }
  }

  def nextRoadwayPointId: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextRoadwayPointId).getOrElse {
      throw new NoSuchElementException("No value returned for nextRoadwayPointId")
    }
  }

  def fetchRoadwayIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchRoadwayIds(len))
  }

  def nextLinearLocationId: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextLinearLocationId).getOrElse {
      throw new NoSuchElementException("No value returned for nextLinearLocationId")
    }
  }

  def fetchLinearLocationIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchLinearLocationIds(len))
  }

  def nextNodeId: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextNodeId).getOrElse {
      throw new NoSuchElementException("No value returned for nextNodeId")
    }
  }

  def fetchNodeIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchNodeIds(len))
  }

  def nextNodePointId: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextNodePointId).getOrElse {
      throw new NoSuchElementException("No value returned for nextNodePointId")
    }
  }

  def nextNodeNumber: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextNodeNumber).getOrElse {
      throw new NoSuchElementException("No value returned for nextNodeNumber")
    }
  }

  def nextJunctionId: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextJunctionId).getOrElse {
      throw new NoSuchElementException("No value returned for nextJunctionId")
    }
  }

  def fetchJunctionIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchJunctionIds(len))
  }

  def nextJunctionPointId: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextJunctionPointId).getOrElse {
      throw new NoSuchElementException("No value returned for nextJunctionPointId")
    }
  }

  def fetchJunctionPointIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchJunctionPointIds(len))
  }

  def fetchNodePointIds(len: Int): List[Long] = {
    runSelectQuery(Queries.fetchNodePointIds(len))
  }

  def nextCalibrationPointId: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextCalibrationPointId).getOrElse {
      throw new NoSuchElementException("No value returned for nextCalibrationPointId")
    }
  }

  def nextRoadwayChangeLink: Long = {
    runSelectSingleFirstOptionWithType[Long](Queries.nextRoadwayChangeLink).getOrElse {
      throw new NoSuchElementException("No value returned for nextRoadwayChangeLink")
    }
  }

}
