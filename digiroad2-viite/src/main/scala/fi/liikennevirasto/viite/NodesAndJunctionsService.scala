package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.dao.{Junction, JunctionDAO, JunctionPoint, Node, NodeDAO, NodePoint}
import org.slf4j.LoggerFactory

class NodesAndJunctionsService() {
  case class CompleteNode(node: Option[Node], nodePoints: Seq[NodePoint], junctions: Map[Junction, Seq[JunctionPoint]])

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)

  val nodeDAO = new NodeDAO

  val junctionDAO = new JunctionDAO

  def getNodesByRoadAttributes(roadNumber: Long, roadPartNumber: Option[Long], minAddrM: Option[Long], maxAddrM: Option[Long]): List[Node] = {
    withDynSession {
      nodeDAO.fetchByRoadAttributes(roadNumber, roadPartNumber, minAddrM, maxAddrM)
    }
  }

  def getNodesByBoundingBox(boundingRectangle: BoundingRectangle): Seq[Node] = {
    withDynSession{
      time(logger, "Fetch nodes with junctions") {
        nodeDAO.fetchByBoundingBox(boundingRectangle)
      }
    }
  }

  def getNodesWithJunctionByBoundingBox(boundingRectangle: BoundingRectangle): Map[Option[Node], (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]])] = {
    withDynSession {
      time(logger, "Fetch nodes with junctions") {
        val nodes = nodeDAO.fetchByBoundingBox(boundingRectangle)
        val nodePoints = nodeDAO.fetchNodePointsByNodeId(nodes.map(_.id))
        val junctions = junctionDAO.fetchJunctionByNodeIds(nodes.map(_.id))
        val junctionPoints = junctionDAO.fetchJunctionPointsByJunctionIds(junctions.map(_.id))
        (nodes.map {
          node =>
            (Option(node),
              (
                nodePoints.filter(np => np.nodeId.isDefined && np.nodeId.get == node.id),
                junctions.filter(j => j.nodeId.isDefined && j.nodeId.get == node.id).map {
                  junction =>
                    (
                      junction, junctionPoints.filter(_.junctionId == junction.id)
                    )
                }.toMap
              )
            )
        } ++
          Seq(
            (
            None,
            (
              nodePoints.filter(_.nodeId.isEmpty),
              junctions.filter(j => j.nodeId.isEmpty).map {
                junction => (junction, junctionPoints.filter(_.junctionId == junction.id))
              }.toMap
            )
          )
          )
          ).toMap
      }
    }
  }

}
