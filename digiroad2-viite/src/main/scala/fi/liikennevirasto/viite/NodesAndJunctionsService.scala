package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

class NodesAndJunctionsService(roadwayDAO: RoadwayDAO, roadwayPointDAO: RoadwayPointDAO, linearLocationDAO: LinearLocationDAO, nodeDAO: NodeDAO, nodePointDAO: NodePointDAO, junctionDAO: JunctionDAO, junctionPointDAO: JunctionPointDAO) {
  case class CompleteNode(node: Option[Node], nodePoints: Seq[NodePoint], junctions: Map[Junction, Seq[JunctionPoint]])

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)

  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)

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
        val nodesAndJunctions = nodes.map {
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
        } ++ Seq((None, getTemplatesByBoundingBox(boundingRectangle)))
        nodesAndJunctions.toMap
      }
    }
  }

  def getTemplatesByBoundingBox(boundingRectangle: BoundingRectangle): (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]]) = {
    withDynSession {
      time(logger, "Fetch NodePoint and Junction + JunctionPoint templates") {
        val junctionPoints = junctionPointDAO.fetchTemplatesByBoundingBox(boundingRectangle)
        val junctions = junctionDAO.fetchByIds(junctionPoints.map(_.junctionId))
        val nodePoints = nodePointDAO.fetchTemplatesByBoundingBox(boundingRectangle)
        (nodePoints, junctions.map {junction => (junction, junctionPoints.filter(_.junctionId == junction.id))}.toMap)
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
      val junctionsInHead = roadsToHead.flatMap { rh =>
        val jcPoints = (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.endAddrMValue, BeforeAfter.Before) ++
          junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After))
          .map(_.junctionId).toSeq
        junctionDAO.fetchByIds(jcPoints)
      } ++ roadsFromHead.flatMap { rh =>
        val jcPoints = (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.startAddrMValue, BeforeAfter.After) ++
          junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After)).map(_.junctionId).toSeq
        junctionDAO.fetchByIds(jcPoints)
      }

      val junctionsToTail = roadsToTail.flatMap { rh =>
        val jcPoints = (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.endAddrMValue, BeforeAfter.Before) ++
          junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)).map(_.junctionId).toSeq
        junctionDAO.fetchByIds(jcPoints)
      } ++ roadsFromTail.flatMap { rh =>
        val jcPoints = (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.startAddrMValue, BeforeAfter.After) ++
          junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)).map(_.junctionId).toSeq
        junctionDAO.fetchByIds(jcPoints)
      }

      //  |--R-->0|0--L-->
      roadsToHead.foreach { r =>
        val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.endAddrMValue, BeforeAfter.Before)
        val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
          val junctionId = if(junctionsInHead.isEmpty)
            junctionDAO.create(Seq(Junction(NewIdValue, NewIdValue, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
            else junctionsInHead.head.id
            val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.endAddrMValue)
            val rwPoint = if (existingRoadwayPoint.nonEmpty) {
              existingRoadwayPoint.get.id
            } else {
              roadwayPointDAO.create(r.roadwayNumber, r.endAddrMValue, r.createdBy.getOrElse("-"))
            }
            junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now), r.roadwayNumber, r.endAddrMValue))).head
          Some(junctionId)
        } else None

        val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After)
        if (linkJunctionPoint.isEmpty) {
          val junctionId = if(junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
            junctionDAO.create(Seq(Junction(NewIdValue, NewIdValue, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
          else junctionIdentifier.getOrElse(junctionsInHead.head.id)
          val rwPointId = {
            val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.startAddrMValue)
            val rwPoint = if (existingRoadwayPoint.nonEmpty) {
              existingRoadwayPoint.get.id
            } else {
              roadwayPointDAO.create(link.roadwayNumber, link.startAddrMValue, link.createdBy.getOrElse("-"))
            }
            junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.startAddrMValue))).head
            rwPoint
          }
        }
      }

      //need to get all new junctions in junctionInHead places since we can have this kind of cases:
      /*
             ^
             |
             L
             |
      |-R1->0|*-R2->|

      not getting again all new created junctions, we would create another junction in the place of *
       */
      val newJunctionsInHead = roadsToHead.flatMap { rh =>
        val jcPoints = (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.endAddrMValue, BeforeAfter.Before) ++
          junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After))
          .map(_.junctionId).toSeq
        junctionDAO.fetchByIds(jcPoints)
      } ++ roadsFromHead.flatMap { rh =>
        val jcPoints = (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.startAddrMValue, BeforeAfter.After) ++
          junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After)).map(_.junctionId).toSeq
        junctionDAO.fetchByIds(jcPoints)
      }

      // <--R--0|0--L-->
      roadsFromHead.foreach { r =>
        val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.startAddrMValue, BeforeAfter.After)
        val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
          val junctionId = if(newJunctionsInHead.isEmpty)
            junctionDAO.create(Seq(Junction(NewIdValue, NewIdValue, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
          else newJunctionsInHead.head.id
          val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.startAddrMValue)
          val rwPoint = if (existingRoadwayPoint.nonEmpty) {
            existingRoadwayPoint.get.id
          } else {
            roadwayPointDAO.create(r.roadwayNumber, r.startAddrMValue, r.createdBy.getOrElse("-"))
          }
          junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now), r.roadwayNumber, r.startAddrMValue))).head
          Some(junctionId)
        } else None

        val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After)
        if (linkJunctionPoint.isEmpty) {
          val junctionId = if(junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
            junctionDAO.create(Seq(Junction(NewIdValue, NewIdValue, None, link.startDate.get, link.endDate, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
          else junctionIdentifier.getOrElse(junctionsInHead.head.id)
            val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.startAddrMValue)
            val rwPoint = if (existingRoadwayPoint.nonEmpty) {
              existingRoadwayPoint.get.id
            } else {
              roadwayPointDAO.create(link.roadwayNumber, link.startAddrMValue, r.createdBy.getOrElse("-"))
            }
            junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.startAddrMValue))).head
        }
      }

      // |--R--0>|<0--L--|
      roadsToTail.foreach { r =>
        val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.endAddrMValue, BeforeAfter.Before)
        val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
          val junctionId = if(junctionsToTail.isEmpty)
            junctionDAO.create(Seq(Junction(NewIdValue, NewIdValue, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
          else junctionsToTail.head.id
          val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.endAddrMValue)
          val rwPoint = if (existingRoadwayPoint.nonEmpty) {
            existingRoadwayPoint.get.id
          } else {
            roadwayPointDAO.create(r.roadwayNumber, r.endAddrMValue, r.createdBy.getOrElse("-"))
          }
          junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), r.roadwayNumber, r.endAddrMValue))).head
          Some(junctionId)
        } else None

        val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)
        if (linkJunctionPoint.isEmpty) {
          val junctionId = if(junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
            junctionDAO.create(Seq(Junction(NewIdValue, NewIdValue, None, link.startDate.get, link.endDate, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
          else junctionIdentifier.getOrElse(junctionsInHead.head.id)
          val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.endAddrMValue)
          val rwPoint = if (existingRoadwayPoint.nonEmpty) {
            existingRoadwayPoint.get.id
          } else {
            roadwayPointDAO.create(link.roadwayNumber, link.endAddrMValue, r.createdBy.getOrElse("-"))
          }
          junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.endAddrMValue))).head
        }
      }

      val newJunctionsToTail = roadsToTail.flatMap { rh =>
        val jcPoints = (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.endAddrMValue, BeforeAfter.Before) ++
          junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)).map(_.junctionId).toSeq
        junctionDAO.fetchByIds(jcPoints)
      } ++ roadsFromTail.flatMap { rh =>
        val jcPoints = (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.startAddrMValue, BeforeAfter.After) ++
          junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)).map(_.junctionId).toSeq
        junctionDAO.fetchByIds(jcPoints)
      }

      // <--R--0|<0--L--|
      roadsFromTail.foreach { r =>
        val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.startAddrMValue, BeforeAfter.After)
        val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
          val junctionId = if(newJunctionsToTail.isEmpty)
            junctionDAO.create(Seq(Junction(NewIdValue, NewIdValue, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
          else newJunctionsToTail.head.id
          val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.startAddrMValue)
          val rwPoint = if (existingRoadwayPoint.nonEmpty) {
            existingRoadwayPoint.get.id
          } else {
            roadwayPointDAO.create(r.roadwayNumber, r.startAddrMValue, r.createdBy.getOrElse("-"))
          }
          junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), r.roadwayNumber, r.startAddrMValue))).head
          Some(junctionId)
        } else None

        val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)
        if (linkJunctionPoint.isEmpty) {
          val junctionId = if(junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
            junctionDAO.create(Seq(Junction(NewIdValue, NewIdValue, None, link.startDate.get, link.endDate, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
          else junctionIdentifier.getOrElse(junctionsInHead.head.id)
          val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.endAddrMValue)
          val rwPoint = if (existingRoadwayPoint.nonEmpty) {
            existingRoadwayPoint.get.id
          } else {
            roadwayPointDAO.create(link.roadwayNumber, link.endAddrMValue, r.createdBy.getOrElse("-"))
          }
          junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.endAddrMValue))).head
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
    val groups = filteredLinks.filterNot(_.track == Track.LeftSide).groupBy(l=> (l.roadNumber, l.roadPartNumber, l.track, l.roadType))

    groups.mapValues{ group =>
      val sortedGroup = group.sortBy(s => (s.roadNumber, s.roadPartNumber, s.startAddrMValue, s.track.value))
      val headLink = sortedGroup.head
      val lastLink = sortedGroup.last
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

    val nodePoints = Seq(NodePoint(NewIdValue, BeforeAfter.After, headRoadwayPointId, None, headLink.startDate.get, None, DateTime.now(), None, headLink.createdBy, Some(DateTime.now()), headLink.roadwayNumber, headLink.startAddrMValue),
      NodePoint(NewIdValue, BeforeAfter.Before, lastRoadwayPointId, None, lastLink.startDate.get, None, DateTime.now(), None, lastLink.createdBy, Some(DateTime.now()), lastLink.roadwayNumber, lastLink.endAddrMValue)
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

  def removeObsoleteJunctions(projectLinks: Seq[ProjectLink], username: String = "-"): Unit = {
    val endDate = projectLinks.head.endDate
    val terminatedLinks = projectLinks.filter(pl => pl.endDate.isDefined)
    val terminatedRoadwayNumbers = terminatedLinks.map(_.roadwayNumber).distinct
    val currentRoadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(terminatedRoadwayNumbers)
    val obsoleteJunctionPoints = junctionPointDAO.fetchByRoadwayPointIds(currentRoadwayPoints.map(_.id))

    // Expire current junction point rows
    junctionPointDAO.expireById(obsoleteJunctionPoints.map(_.id))

    // Remove junctions without junction points
    val obsoleteJunctions = junctionDAO.fetchWithoutJunctionPointsById(obsoleteJunctionPoints.map(_.junctionId).distinct)
    junctionDAO.expireById(obsoleteJunctions.map(_.id))

    // Handle obsolete junction points of valid and obsolete junctions separately
    val (obsoleteJunctionPointsOfValidJunctions, obsoleteJunctionPointsOfObsoleteJunctions) = obsoleteJunctionPoints.partition(
      jp => obsoleteJunctions.filter(j => j.id == jp.junctionId).isEmpty)

    // Create junction rows with end date and junction point rows with end date and new junction id
    obsoleteJunctions.foreach(j => {
      val newJunctionId = junctionDAO.create(Seq(j.copy(id = NewIdValue, endDate = endDate, createdBy = Some(username)))).head
      junctionPointDAO.create(obsoleteJunctionPointsOfObsoleteJunctions.map(_.copy(id = NewIdValue, endDate = endDate,
        junctionId = newJunctionId, createdBy = Some(username))))
    })

    // Create junction point rows of the valid junctions with end date
    junctionPointDAO.create(obsoleteJunctionPointsOfValidJunctions.map(_.copy(id = NewIdValue, endDate = endDate, createdBy = Some(username))))

  }

}
