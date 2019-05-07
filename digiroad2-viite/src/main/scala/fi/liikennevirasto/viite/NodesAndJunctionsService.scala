package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.dao.{Node, NodeDAO}
import org.slf4j.LoggerFactory

class NodesAndJunctionsService() {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)

  val nodeDAO = new NodeDAO

  def getNodesByRoadAttributes(roadNumber: Long, startRoadPartNumber: Option[Long], endRoadPartNumber: Option[Long]): Seq[Node] = {
    withDynSession {
      nodeDAO.fetchByRoadAttributes(roadNumber, startRoadPartNumber, endRoadPartNumber)
    }
  }
}
