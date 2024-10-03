package fi.vaylavirasto.viite.dao

object SequencesScalikeJDBC extends ScalikeJDBCBaseDAO {

  def nextRoadwayId: Long = {
    runSelectSingle(QueriesScalikeJDBC.nextRoadwayId.map(_.long(1))).getOrElse {
      throw new NoSuchElementException("No value returned for nextRoadwayId")
    }
  }

  def nextRoadNameId: Long = {
    runSelectSingle(QueriesScalikeJDBC.nextRoadNameId.map(_.long(1))).getOrElse {
      throw new NoSuchElementException("No value returned for nextRoadNameId")
    }
  }

  def nextRoadwayNumber: Long = {
    runSelectSingle(QueriesScalikeJDBC.nextRoadwayNumber.map(_.long(1))).getOrElse {
      throw new NoSuchElementException("No value returned for nextRoadwayNumber")
    }
  }

  def fetchRoadwayIds(len: Int): List[Long] = {
  runSelectQuery(QueriesScalikeJDBC.fetchRoadwayIds(len))
  }
}
