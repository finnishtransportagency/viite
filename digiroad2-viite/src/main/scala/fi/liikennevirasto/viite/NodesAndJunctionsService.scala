package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.BeforeAfter.{After, Before}
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
        case e if NonFatal(e) =>
          logger.error("Failed to fetch nodes.", e)
          Left(e.getMessage)
      }
    }
  }

  def getNodesByBoundingBox(boundingRectangle: BoundingRectangle): Seq[Node] = {
    withDynSession {
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
        (nodePoints, junctions.map { junction => (junction, junctionPoints.filter(_.junctionId == junction.id)) }.toMap)
      }
    }
  }

  def handleJunctionPointTemplates(projectLinks: Seq[ProjectLink]): Unit = {
    def getJunctionsInHead(link: ProjectLink, roadsToHead: Seq[RoadAddress], roadsFromHead: Seq[RoadAddress]): Seq[Junction] = {
      roadsToHead.flatMap { rh =>
        val jcPoints = (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.endAddrMValue, BeforeAfter.Before) ++
          junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After))
          .map(_.junctionId).toSeq
        junctionDAO.fetchByIds(jcPoints)
      } ++ roadsFromHead.flatMap { rh =>
        val jcPoints = (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.startAddrMValue, BeforeAfter.After) ++
          junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After)).map(_.junctionId).toSeq
        junctionDAO.fetchByIds(jcPoints)
      }
    }

    def getJunctionsToTail(link: ProjectLink, roadsToTail: Seq[RoadAddress], roadsFromTail: Seq[RoadAddress]): Seq[Junction] = {
      roadsToTail.flatMap { rh =>
        val jcPoints = (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.endAddrMValue, BeforeAfter.Before) ++
          junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)).map(_.junctionId).toSeq
        junctionDAO.fetchByIds(jcPoints)
      } ++ roadsFromTail.flatMap { rh =>
        val jcPoints = (junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.startAddrMValue, BeforeAfter.After) ++
          junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)).map(_.junctionId).toSeq
        junctionDAO.fetchByIds(jcPoints)
      }
    }

    def handleRoadsToHead(link: ProjectLink, junctionsInHead: Seq[Junction], roadAddress: RoadAddress): Unit = {
      val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadAddress.roadwayNumber, roadAddress.endAddrMValue, BeforeAfter.Before)
      val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
        val junctionId = if (junctionsInHead.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, 0, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
        else junctionsInHead.head.id
        val existingRoadwayPoint = roadwayPointDAO.fetch(roadAddress.roadwayNumber, roadAddress.endAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(roadAddress.roadwayNumber, roadAddress.endAddrMValue, roadAddress.createdBy.getOrElse("-"))
        }
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now), roadAddress.roadwayNumber, roadAddress.endAddrMValue))).head
        Some(junctionId)
      } else None

      val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After)
      if (linkJunctionPoint.isEmpty) {
        val junctionId = if (junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, 0, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
        else junctionIdentifier.getOrElse(junctionsInHead.head.id)
        val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.startAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(link.roadwayNumber, link.startAddrMValue, link.createdBy.getOrElse("-"))
        }
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.startAddrMValue))).head
      }
    }

    def handleRoadsFromHead(link: ProjectLink, newJunctionsInHead: Seq[Junction], junctionsInHead: Seq[Junction], roadAddress: RoadAddress): Unit = {
      val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadAddress.roadwayNumber, roadAddress.startAddrMValue, BeforeAfter.After)
      val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
        val junctionId = if (newJunctionsInHead.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, 0, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
        else newJunctionsInHead.head.id
        val existingRoadwayPoint = roadwayPointDAO.fetch(roadAddress.roadwayNumber, roadAddress.startAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(roadAddress.roadwayNumber, roadAddress.startAddrMValue, roadAddress.createdBy.getOrElse("-"))
        }
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now), roadAddress.roadwayNumber, roadAddress.startAddrMValue))).head
        Some(junctionId)
      } else None

      val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After)
      if (linkJunctionPoint.isEmpty) {
        val junctionId = if (junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, 0, None, link.startDate.get, link.endDate, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
        else junctionIdentifier.getOrElse(junctionsInHead.head.id)
        val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.startAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(link.roadwayNumber, link.startAddrMValue, roadAddress.createdBy.getOrElse("-"))
        }
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.startAddrMValue))).head
      }
    }

    def handleRoadsToTail(link: ProjectLink, junctionsToTail: Seq[Junction], junctionsInHead: Seq[Junction], roadAddress: RoadAddress): Unit = {
      val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadAddress.roadwayNumber, roadAddress.endAddrMValue, BeforeAfter.Before)
      val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
        val junctionId = if (junctionsToTail.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, 0, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
        else junctionsToTail.head.id
        val existingRoadwayPoint = roadwayPointDAO.fetch(roadAddress.roadwayNumber, roadAddress.endAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(roadAddress.roadwayNumber, roadAddress.endAddrMValue, roadAddress.createdBy.getOrElse("-"))
        }
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), roadAddress.roadwayNumber, roadAddress.endAddrMValue))).head
        Some(junctionId)
      } else None

      val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)
      if (linkJunctionPoint.isEmpty) {
        val junctionId = if (junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, 0, None, link.startDate.get, link.endDate, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
        else junctionIdentifier.getOrElse(junctionsInHead.head.id)
        val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.endAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(link.roadwayNumber, link.endAddrMValue, roadAddress.createdBy.getOrElse("-"))
        }
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.endAddrMValue))).head
      }
    }

    def handleRoadsFromTail(link: ProjectLink, newJunctionsToTail: Seq[Junction], junctionsInHead: Seq[Junction], roadAddress: RoadAddress): Unit = {
      val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(roadAddress.roadwayNumber, roadAddress.startAddrMValue, BeforeAfter.After)
      val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
        val junctionId = if (newJunctionsToTail.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, 0, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
        else newJunctionsToTail.head.id
        val existingRoadwayPoint = roadwayPointDAO.fetch(roadAddress.roadwayNumber, roadAddress.startAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(roadAddress.roadwayNumber, roadAddress.startAddrMValue, roadAddress.createdBy.getOrElse("-"))
        }
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), roadAddress.roadwayNumber, roadAddress.startAddrMValue))).head
        Some(junctionId)
      } else None

      val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)
      if (linkJunctionPoint.isEmpty) {
        val junctionId = if (junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, 0, None, link.startDate.get, link.endDate, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
        else junctionIdentifier.getOrElse(junctionsInHead.head.id)
        val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.endAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(link.roadwayNumber, link.endAddrMValue, roadAddress.createdBy.getOrElse("-"))
        }
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, DateTime.now, None, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.endAddrMValue))).head
      }
    }

    val filteredLinks = projectLinks.filter(pl => RoadClass.nodeAndJunctionRoadClass.flatMap(_.roads).contains(pl.roadNumber.toInt))
    filteredLinks.foreach { link =>
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
      val junctionsInHead: Seq[Junction] = getJunctionsInHead(link, roadsToHead, roadsFromHead)

      val junctionsToTail: Seq[Junction] = getJunctionsToTail(link, roadsToTail, roadsFromTail)

      //  |--R-->0|0--L-->
      roadsToHead.foreach { roadAddress: RoadAddress => handleRoadsToHead(link, junctionsInHead, roadAddress)}

      //need to get all new junctions in junctionInHead places since we can have this kind of cases:
      /*
             ^
             |
             L
             |
      |-R1->0|*-R2->|

      not getting again all new created junctions, we would create another junction in the place of *
       */
      val newJunctionsInHead = getJunctionsInHead(link, roadsToHead, roadsFromHead)

      // <--R--0|0--L-->
      roadsFromHead.foreach { roadAddress: RoadAddress => handleRoadsFromHead(link, newJunctionsInHead, junctionsInHead, roadAddress)}

      // |--R--0>|<0--L--|
      roadsToTail.foreach { roadAddress: RoadAddress => handleRoadsToTail(link, junctionsToTail, junctionsInHead, roadAddress)}

      val newJunctionsToTail = getJunctionsToTail(link, roadsToTail, roadsFromTail)

      // <--R--0|<0--L--|
      roadsFromTail.foreach { roadAddress: RoadAddress => handleRoadsFromTail(link, newJunctionsToTail, junctionsInHead, roadAddress)}
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

    def continuousNodeSections(seq: Seq[ProjectLink], roadTypesSection: Seq[Seq[ProjectLink]]): (Seq[ProjectLink], Seq[Seq[ProjectLink]]) = {
      if (seq.isEmpty) {
        (Seq(), roadTypesSection)
      } else {
        val roadType = seq.headOption.map(_.roadType.value).getOrElse(0)
        val continuousProjectLinks = seq.takeWhile(pl => pl.roadType.value == roadType)
        continuousNodeSections(seq.drop(continuousProjectLinks.size), roadTypesSection :+ continuousProjectLinks)
      }
    }

    try {
      val filteredLinks = projectLinks.filter(pl => RoadClass.nodeAndJunctionRoadClass.flatMap(_.roads).contains(pl.roadNumber.toInt) && pl.status != LinkStatus.Terminated)
        .filterNot(_.track == Track.LeftSide)
      val groupSections = filteredLinks.groupBy(l => (l.roadNumber, l.roadPartNumber))

      groupSections.mapValues { group =>
        val roadTypeSections: Seq[Seq[ProjectLink]] = continuousNodeSections(group.sortBy(_.startAddrMValue), Seq.empty[Seq[ProjectLink]])._2

        roadTypeSections.foreach { section =>

          val headLink = section.head
          val lastLink = section.last

          val headRoadwayPointId = {
            val existingRoadwayPoint = roadwayPointDAO.fetch(headLink.roadwayNumber, headLink.startAddrMValue)
            if (existingRoadwayPoint.nonEmpty)
              existingRoadwayPoint.get.id
            else roadwayPointDAO.create(headLink.roadwayNumber, headLink.startAddrMValue, headLink.createdBy.getOrElse("-"))
          }
          val lastRoadwayPointId = {
            val existingRoadwayPoint = roadwayPointDAO.fetch(lastLink.roadwayNumber, lastLink.endAddrMValue)
            if (existingRoadwayPoint.nonEmpty)
              existingRoadwayPoint.get.id
            else roadwayPointDAO.create(lastLink.roadwayNumber, lastLink.endAddrMValue, lastLink.createdBy.getOrElse("-"))
          }

          val existingHeadNodePoint = nodePointDAO.fetchNodePointTemplate(headLink.roadwayNumber).filter(np => np.beforeAfter == After && np.addrM == headLink.startAddrMValue)
          val existingLastNodePoint = nodePointDAO.fetchNodePointTemplate(lastLink.roadwayNumber).filter(np => np.beforeAfter == Before && np.addrM == lastLink.endAddrMValue)

          if (existingHeadNodePoint.isEmpty)
            nodePointDAO.create(Seq(NodePoint(NewIdValue, BeforeAfter.After, headRoadwayPointId, None, headLink.startDate.get, None, DateTime.now(), None, headLink.createdBy, Some(DateTime.now()), headLink.roadwayNumber, headLink.startAddrMValue)))

          if (existingLastNodePoint.isEmpty)
            nodePointDAO.create(Seq(NodePoint(NewIdValue, BeforeAfter.Before, lastRoadwayPointId, None, lastLink.startDate.get, None, DateTime.now(), None, lastLink.createdBy, Some(DateTime.now()), lastLink.roadwayNumber, lastLink.endAddrMValue)))

        }
      }.toSeq
    } catch {
      case ex: Exception =>
        println("Error in node points handler: ", ex.getMessage)
        println("Full stack trace: ", ex.getStackTrace)
    }
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

  def expireObsoleteNodesAndJunctions(roadwayChanges: Seq[ProjectRoadwayChange], endDate: Option[DateTime], username: String = "-"): Unit = {
    def obsoleteNodesAndJunctionsPointsOfTermination(terminatedRoadwayNumbers: Seq[Long]): (Seq[NodePoint], Seq[JunctionPoint]) = {
      val obsoleteRoadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(terminatedRoadwayNumbers)
      val obsoleteNodePoints = nodePointDAO.fetchByRoadwayPointIds(obsoleteRoadwayPoints.map(_.id))
      val obsoleteJunctionPoints = junctionPointDAO.fetchByRoadwayPointIds(obsoleteRoadwayPoints.map(_.id))
      (obsoleteNodePoints, obsoleteJunctionPoints)
    }

    def obsoleteNodesAndJunctionsPointsByRoadwayNumbers(affectedRoadwayNumbers: Seq[Long]): (Seq[NodePoint], Seq[JunctionPoint]) = {
      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(affectedRoadwayNumbers)
      val nodePoints = nodePointDAO.fetchByRoadwayPointIds(roadwayPoints.map(_.id))
      val junctionPoints = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints.map(_.id))

      val obsoleteJunctions = junctionDAO.fetchObsoleteById(junctionPoints.map(_.junctionId).distinct)
      val obsoleteJunctionPoints = junctionPointDAO.fetchJunctionPointsByJunctionIds(obsoleteJunctions.map(_.id))

      val obsoleteNodes = nodeDAO.fetchObsoleteById(nodePoints.map(_.nodeId.get).distinct)
      val obsoleteNodePoints = nodePointDAO.fetchNodePointsByNodeId(obsoleteNodes.map(_.id))

      (obsoleteNodePoints, obsoleteJunctionPoints)
    }

    def obsoleteNodesAndJunctionsPointsOfNew(newRoadwayNumbers: Seq[Long]): (Seq[NodePoint], Seq[JunctionPoint]) = {
      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(newRoadwayNumbers)
      val nodePoints = nodePointDAO.fetchByRoadwayPointIds(roadwayPoints.map(_.id))
        .groupBy(_.nodeId)
        .filter(p => {
          val changes = roadwayChanges
          val nodePoints = p._2
          nodePoints.size == 1 &&
            changes.filter(ch => ch.changeInfo.target.startAddressM.getOrElse(-1L) != nodePoints.head.addrM && ch.changeInfo.target.endAddressM.getOrElse(-1L) != nodePoints.head.addrM)
              .groupBy(ch => (ch.changeInfo.target.roadNumber, ch.changeInfo.target.startRoadPartNumber, ch.changeInfo.target.roadType)).values.size == 1
        })
        .values.toSeq.flatten

      val obsoleteNodes = nodeDAO.fetchObsoleteById(nodePoints.map(_.nodeId.get).distinct)
      val obsoleteNodePoints = nodePointDAO.fetchNodePointsByNodeId(obsoleteNodes.map(_.id)) ++ nodePoints

      (obsoleteNodePoints, Seq.empty[JunctionPoint])
    }

    def expireObsoleteJunctions(obsoleteJunctionPoints: Set[JunctionPoint]): Unit = {
      // Expire current junction points rows
      junctionPointDAO.expireById(obsoleteJunctionPoints.map(_.id))

      // Remove junctions that no longer have justification for the current network
      val obsoleteJunctions = junctionDAO.fetchObsoleteById(obsoleteJunctionPoints.map(_.junctionId))
      junctionDAO.expireById(obsoleteJunctions.map(_.id))
      val obsoleteJunctionPointsOfNowExpiredJunctions = junctionPointDAO.fetchJunctionPointsByJunctionIds(obsoleteJunctions.map(_.id))
      junctionPointDAO.expireById(obsoleteJunctionPointsOfNowExpiredJunctions.map(_.id))

      // Handle obsolete junction points of valid and obsolete junctions separately
      val (obsoleteJunctionPointsOfObsoleteJunctions, obsoleteJunctionPointsOfValidJunctions) = (obsoleteJunctionPoints ++ obsoleteJunctionPointsOfNowExpiredJunctions)
        .partition(jp => obsoleteJunctions.exists(j => j.id == jp.junctionId))

      // Create junction rows with end date and junction point rows with end date and new junction id
      obsoleteJunctions.foreach(j => {
        val newJunctionId = junctionDAO.create(Seq(j.copy(id = NewIdValue, endDate = endDate, createdBy = Some(username)))).head
        junctionPointDAO.create(obsoleteJunctionPointsOfObsoleteJunctions.map(_.copy(id = NewIdValue, endDate = endDate,
          junctionId = newJunctionId, createdBy = Some(username))))
      })

      // Create junction point rows of the valid junctions with end date
      junctionPointDAO.create(obsoleteJunctionPointsOfValidJunctions.map(_.copy(id = NewIdValue, endDate = endDate, createdBy = Some(username))))
    }

    def expireNodes(obsoleteNodePoints: Set[NodePoint], obsoleteJunctionPoints: Set[JunctionPoint]): Unit = {
      val obsoleteJunctions = junctionDAO.fetchObsoleteById(obsoleteJunctionPoints.map(_.junctionId))

      // Expire current node points rows
      nodePointDAO.expireById(obsoleteNodePoints.map(_.id))

      // Remove nodes that no longer have justification for the current network
      val obsoleteNodes = nodeDAO.fetchObsoleteById((obsoleteJunctions.filter(j => j.nodeId.isDefined).map(_.nodeId.get)
        ++ obsoleteNodePoints.filter(np => np.nodeId.isDefined).map(_.nodeId.get)).distinct)

      // Handle obsolete node points of valid and obsolete nodes separately
      val (obsoleteNodePointsOfObsoleteNodes, obsoleteNodePointsOfValidNodes) = obsoleteNodePoints
        .partition(np => obsoleteNodes.exists(n => n.id == np.nodeId.getOrElse(-1)))

      // Create node rows with end date and node point rows with end date and new node id
      obsoleteNodes.foreach(n => {
        val newNodeId = nodeDAO.create(Seq(n.copy(id = NewIdValue, endDate = endDate, createdBy = Some(username)))).head
        nodePointDAO.create(obsoleteNodePointsOfObsoleteNodes.map(_.copy(id = NewIdValue, endDate = endDate,
          nodeId = Some(newNodeId), createdBy = Some(username))))
      })

      // Create node point rows of the valid nodes with end date
      nodePointDAO.create(obsoleteNodePointsOfValidNodes.map(_.copy(id = NewIdValue, endDate = endDate, createdBy = Some(username))))

      nodeDAO.expireById(obsoleteNodes.map(_.id))
    }

    val (obsoleteNodePoints, obsoleteJunctionPoints) = roadwayChanges.foldLeft((Set.empty[NodePoint], Set.empty[JunctionPoint])) { case ((onp, ojp), rwc) =>
      val sourceRoadNumber = rwc.changeInfo.source.roadNumber.getOrElse(-1L)
      val sourceRoadPartNumber = rwc.changeInfo.source.startRoadPartNumber.getOrElse(-1L)
      val targetRoadNumber = rwc.changeInfo.target.roadNumber.getOrElse(-1L)
      val targetRoadPartNumber = rwc.changeInfo.target.startRoadPartNumber.getOrElse(-1L)

      val (nodePoints, junctionPoints) = rwc.changeInfo.changeType match {
        case AddressChangeType.Termination =>
          val terminatedRoadwayNumbers = roadwayDAO.fetchAllByRoadAndPart(sourceRoadNumber, sourceRoadPartNumber).map(_.roadwayNumber)
          obsoleteNodesAndJunctionsPointsOfTermination(terminatedRoadwayNumbers)
        case AddressChangeType.ReNumeration | AddressChangeType.Transfer =>
          val affectedRoadwayNumbers = roadwayDAO.fetchAllByRoadAndPart(targetRoadNumber, targetRoadPartNumber).map(_.roadwayNumber)
          obsoleteNodesAndJunctionsPointsByRoadwayNumbers(affectedRoadwayNumbers)
        case AddressChangeType.New =>
          val newRoadwayNumbers = roadwayDAO.fetchAllByRoadAndPart(targetRoadNumber, targetRoadPartNumber).map(_.roadwayNumber)
          obsoleteNodesAndJunctionsPointsOfNew(newRoadwayNumbers)
        case _ => (onp, ojp)
      }
      (onp ++ nodePoints, ojp ++ junctionPoints)
    }

    expireObsoleteJunctions(obsoleteJunctionPoints)
    expireNodes(obsoleteNodePoints, obsoleteJunctionPoints)
  }

}
