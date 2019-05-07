package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.dao.{Junction, JunctionPoint, Node, NodeDAO, NodePoint}
import org.slf4j.LoggerFactory

class NodesAndJunctionsService() {
  case class CompleteNode(node: Option[Node], nodePoints: Seq[NodePoint], junctions: Map[Junction, Seq[JunctionPoint]])

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)

  val nodeDAO = new NodeDAO

  def getNodesByRoadAttributes(roadNumber: Long, roadPartNumber: Option[Long], minAddrM: Option[Long], maxAddrM: Option[Long]): List[Node] = {
    withDynSession {
      nodeDAO.fetchByRoadAttributes(roadNumber, roadPartNumber, minAddrM, maxAddrM)
    }
  }

  def getNodesByBoundingBox(boundingRectangle: BoundingRectangle): List[Node] = {

  }

  def getNodesWithJunctionByBoundingBox(boundingRectangle: BoundingRectangle): List[CompleteNode] = {
    val nodesWithJunctions = withDynSession {
      time(logger, "Fetch nodes with junctions") {
        val nodes = nodeDAO.fetchByBoundingBox(boundingRectangle)
      }
    }
  }

}
