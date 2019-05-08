package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.dao.{Node, NodeDAO}
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

class NodesAndJunctionsService() {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)

  val nodeDAO = new NodeDAO

  def getNodesByRoadAttributes(roadNumber: Long, minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Either[String, Seq[Node]] = {
    withDynSession {
      try {
        val nodes = nodeDAO.fetchByRoadAttributes(roadNumber, minRoadPartNumber, maxRoadPartNumber)
        Right(nodes)
      } catch {
        case e if NonFatal(e) => {
          logger.error("Failed to fetch nodes.", e)
          Left(e.getMessage)
        }
      }
    }
  }
}
