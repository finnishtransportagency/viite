package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

class NodesAndJunctionsService() {
  case class CompleteNode(node: Option[Node], nodePoints: Seq[NodePoint], junctions: Map[Junction, Seq[JunctionPoint]])

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)

  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  val nodeDAO = new NodeDAO
  val junctionDAO = new JunctionDAO
  val nodePointDAO = new NodePointDAO
  val junctionPointDAO = new JunctionPointDAO

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

  def handleJunctionPointTemplates(projectLinks: Seq[ProjectLink]): Unit = {
    val filteredLinks = projectLinks.filter(pl => RoadClass.nodeAndJunctionRoadClass.flatMap(_.roads).contains(pl.roadNumber.toInt))
    filteredLinks.foreach{ link =>
      val roadNumberLimits = Seq((0, 19999), (40001, 69999))
      val headRoads = roadwayAddressMapper.getRoadAddressesByBoundingBox(BoundingRectangle(link.getFirstPoint, link.getFirstPoint), roadNumberLimits).filterNot(rw => rw.roadNumber == link.roadNumber && rw.roadPartNumber == link.roadPartNumber)
      val tailRoads = roadwayAddressMapper.getRoadAddressesByBoundingBox(BoundingRectangle(link.getLastPoint, link.getLastPoint), roadNumberLimits).filterNot(rw => rw.roadNumber == link.roadNumber && rw.roadPartNumber == link.roadPartNumber)

      val roadsToHead = headRoads.filter(_.connected(link.getFirstPoint))
      val roadsFromHead = headRoads.filter(r => link.getFirstPoint.connected(r.getFirstPoint))

      val roadsFromTail = tailRoads.filter(r => link.getLastPoint.connected(r.getFirstPoint))
      val roadsToTail = tailRoads.filter(_.getLastPoint.connected(link.getLastPoint))




      /*
        R:road
        L:project link
        0:junction point
      */

      //  |--R-->0|0--L-->
      roadsToHead.foreach { r =>
        if (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.endAddrMValue).isEmpty){
          val rwPointId = {
            val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.endAddrMValue)
            if(existingRoadwayPoint.nonEmpty)
              existingRoadwayPoint.get.id
            else roadwayPointDAO.create(r.roadwayNumber, r.endAddrMValue, r.createdBy.getOrElse("-"))
            }
          junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPointId, 0L, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), r.roadwayNumber, r.endAddrMValue)))
          }
        if (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue).isEmpty){
          val rwPointId = {
            val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.startAddrMValue)
            if(existingRoadwayPoint.nonEmpty)
              existingRoadwayPoint.get.id
            else roadwayPointDAO.create(link.roadwayNumber, link.startAddrMValue, r.createdBy.getOrElse("-"))
            }
          junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPointId, 0L, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.startAddrMValue)))
        }
      }


      // <--R--0|0--L-->
      roadsFromHead.foreach { r =>
        if (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.startAddrMValue).isEmpty){
          val rwPointId = {
            val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.startAddrMValue)
            if(existingRoadwayPoint.nonEmpty)
              existingRoadwayPoint.get.id
            else roadwayPointDAO.create(r.roadwayNumber, r.startAddrMValue, r.createdBy.getOrElse("-"))
          }
          junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPointId, 0L, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), r.roadwayNumber, r.startAddrMValue)))
        }
        if (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue).isEmpty){
          val rwPointId = {
            val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.startAddrMValue)
            if(existingRoadwayPoint.nonEmpty)
              existingRoadwayPoint.get.id
            else roadwayPointDAO.create(link.roadwayNumber, link.startAddrMValue, r.createdBy.getOrElse("-"))
          }
          junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPointId, 0L, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.startAddrMValue)))
        }
      }

      // |--R--0>|<0--L--|
      roadsToTail.foreach { r =>
        if (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.endAddrMValue).isEmpty){
          val rwPointId = {
            val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.endAddrMValue)
            if(existingRoadwayPoint.nonEmpty)
              existingRoadwayPoint.get.id
            else roadwayPointDAO.create(r.roadwayNumber, r.endAddrMValue, r.createdBy.getOrElse("-"))
          }
          junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPointId, 0L, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), r.roadwayNumber, r.endAddrMValue)))
        }
        if (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue).isEmpty){
          val rwPointId = {
            val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.endAddrMValue)
            if(existingRoadwayPoint.nonEmpty)
              existingRoadwayPoint.get.id
            else roadwayPointDAO.create(link.roadwayNumber, link.endAddrMValue, r.createdBy.getOrElse("-"))
          }
          junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPointId, 0L, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.endAddrMValue)))
        }
      }

      // <--R--0|<0--L--|
      roadsFromTail.foreach { r =>
        if (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.startAddrMValue).isEmpty){
          val rwPointId = {
            val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.startAddrMValue)
            if(existingRoadwayPoint.nonEmpty)
              existingRoadwayPoint.get.id
            else roadwayPointDAO.create(r.roadwayNumber, r.startAddrMValue, r.createdBy.getOrElse("-"))
          }
          junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPointId, 0L, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), r.roadwayNumber, r.startAddrMValue)))
        }
        if (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue).isEmpty){
          val rwPointId = {
            val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.startAddrMValue)
            if(existingRoadwayPoint.nonEmpty)
              existingRoadwayPoint.get.id
            else roadwayPointDAO.create(link.roadwayNumber, link.startAddrMValue, r.createdBy.getOrElse("-"))
          }
          junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPointId, 0L, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.endAddrMValue)))
        }
      }

    }
  }

  /*
  1)  The nodes are created only for tracks 0 and 1
  2)  A node template is always created if :
    2.1)  road number is < 20000 or between 40000-70000
    2.2)  and at the beginning/end of each road part, ely borders, or when road type changes
    2.3)  on each junction with a road number (except number over 70 000)

   */
  def handleNodePointTemplates(projectLinks: Seq[ProjectLink]): Unit = {
    val filteredLinks = projectLinks.filter(pl => RoadClass.nodeAndJunctionRoadClass.flatMap(_.roads).contains(pl.roadNumber.toInt))
    filteredLinks.filterNot(_.track == Track.LeftSide).groupBy(l=> (l.roadNumber, l.roadPartNumber, l.roadType)).map{ group =>
      val headLink = group._2.head
      val lastLink = group._2.last
    val headRoadwayPointId = {
      val existingRoadwayPoint = roadwayPointDAO.fetch(headLink.roadwayNumber, headLink.startAddrMValue)
      if(existingRoadwayPoint.nonEmpty)
        existingRoadwayPoint.get.id
      else roadwayPointDAO.create(headLink.roadwayNumber, headLink.startAddrMValue, headLink.createdBy.getOrElse("-"))
    }
    val lastRoadwayPointId = {
      val existingRoadwayPoint = roadwayPointDAO.fetch(lastLink.roadwayNumber, lastLink.endAddrMValue)
      if(existingRoadwayPoint.nonEmpty)
        existingRoadwayPoint.get.id
      else roadwayPointDAO.create(lastLink.roadwayNumber, lastLink.endAddrMValue, lastLink.createdBy.getOrElse("-"))
    }

    val nodePoints = Seq(NodePoint(NewIdValue, BeforeAfter.After, headRoadwayPointId, None, DateTime.now(), None, DateTime.now(), None, headLink.createdBy, Some(DateTime.now()), headLink.roadwayNumber, headLink.startAddrMValue),
      NodePoint(NewIdValue, BeforeAfter.Before, lastRoadwayPointId, None, DateTime.now(), None, DateTime.now(), None, lastLink.createdBy, Some(DateTime.now()), lastLink.roadwayNumber, lastLink.endAddrMValue)
    )

    nodePointDAO.create(nodePoints)
    }.toSeq
  }

  def getNodeTemplatesByBoundingBox(boundingRectangle: BoundingRectangle): Seq[NodePoint] = {
    withDynSession {
      time(logger, "Fetch nodes point templates") {
        nodePointDAO.fetchTemplatesByBoundingBox(boundingRectangle)
      }
    }
  }

  def getJunctionTemplatesByBoundingBox(boundingRectangle: BoundingRectangle): Seq[JunctionPoint] = {
    withDynSession {
      time(logger, "Fetch nodes point templates") {
        junctionPointDAO.fetchTemplatesByBoundingBox(boundingRectangle)
      }
    }
  }

}
