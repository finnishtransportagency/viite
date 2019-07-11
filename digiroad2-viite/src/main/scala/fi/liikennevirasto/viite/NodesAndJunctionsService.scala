package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.dao._
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

class NodesAndJunctionsService(nodeDAO: NodeDAO, junctionDAO: JunctionDAO, nodePointDAO: NodePointDAO, junctionPointDAO: JunctionPointDAO) {
  case class CompleteNode(node: Option[Node], nodePoints: Seq[NodePoint], junctions: Map[Junction, Seq[JunctionPoint]])

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)

  def getNodesByRoadAttributes(roadNumber: Long, minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Either[String, Seq[(Node, RoadAttributes)]] = {
    withDynSession {
      try {
        // if the result set has more than 50 rows but the road attributes can't be narrowed down, it shows the results anyway
        nodeDAO.fetchByRoadAttributes(roadNumber, minRoadPartNumber, maxRoadPartNumber) match {
          case nodes
            if nodes.size <= MaxAllowedNodes ||
              minRoadPartNumber.isDefined && maxRoadPartNumber.isDefined && minRoadPartNumber.get == maxRoadPartNumber.get ||
              minRoadPartNumber.isDefined && maxRoadPartNumber.isEmpty || minRoadPartNumber.isEmpty && maxRoadPartNumber.isDefined => Right(nodes)
          case _ => Left(ReturnedTooManyNodesErrorMessage)
        }
      } catch {
        case e if NonFatal(e) => {
          logger.error("Failed to fetch nodes.", e)
          Left(e.getMessage)
        }
      }
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
        val nodePoints = nodePointDAO.fetchNodePointsByNodeId(nodes.map(_.id))
        val junctions = junctionDAO.fetchJunctionByNodeIds(nodes.map(_.id))
        val junctionPoints = junctionPointDAO.fetchJunctionPointsByJunctionIds(junctions.map(_.id))
        nodes.map {
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
        }.toMap
      }
    }
  }

  def getNodesWithTimeInterval(sinceDate: DateTime, untilDate: Option[DateTime]) : Map[Option[Node], (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]])] = {
    withDynSession {
      val nodes = nodeDAO.fetchAllByDateRange(sinceDate, untilDate)
      val nodePoints = nodePointDAO.fetchNodePointsByNodeId(nodes.map(_.id))
      val junctions = junctionDAO.fetchJunctionByNodeIds(nodes.map(_.id))
      val junctionPoints = junctionPointDAO.fetchJunctionPointsByJunctionIds(junctions.map(_.id))
      nodes.map {
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
      }.toMap
    }
  }

  def getNodeById(nodeId: Long): Option[Node] = {
    withDynSession{
      time(logger, "Fetch node by ID") {
        nodeDAO.fetchById(nodeId)
      }
    }
  }

}
