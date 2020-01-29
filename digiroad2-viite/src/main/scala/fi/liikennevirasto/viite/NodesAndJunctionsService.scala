package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.BeforeAfter.{After, Before}
import fi.liikennevirasto.viite.dao.NodePointType.RoadNodePoint
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

class NodesAndJunctionsService(roadwayDAO: RoadwayDAO, roadwayPointDAO: RoadwayPointDAO, linearLocationDAO: LinearLocationDAO, nodeDAO: NodeDAO, nodePointDAO: NodePointDAO, junctionDAO: JunctionDAO, junctionPointDAO: JunctionPointDAO, roadwayChangesDAO: RoadwayChangesDAO) {

  case class CompleteNode(node: Option[Node], nodePoints: Seq[NodePoint], junctions: Map[Junction, Seq[JunctionPoint]])

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynTransactionNewOrExisting[T](f: => T): T = OracleDatabase.withDynTransactionNewOrExisting(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val logger = LoggerFactory.getLogger(getClass)

  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)

  def update(node: Node, junctionsIds: Seq[Long], nodePointIds: Seq[Long], username: String = "-"): Option[String] = {
    withDynTransaction {
      try {
        addOrUpdateNode(node, username) match {
          case Some(err) => return Some(err)
          case _ => None
        }
        detachJunctionsFromNode(junctionsIds, username)
        detachNodePointsFromNode(nodePointIds, username)
      } catch {
        case e: Exception => Some(e.getMessage)
      }
    }
  }

  def addOrUpdateNode(node: Node, username: String = "-"): Option[String] = {
    withDynTransactionNewOrExisting {
      try {
        if (node.id == NewIdValue) {
          nodeDAO.create(Seq(node), username)
        } else {
          val old = nodeDAO.fetchById(node.id)
          if (old.isDefined) {
            val oldStartDate = old.get.startDate.withTimeAtStartOfDay
            val newStartDate = node.startDate.withTimeAtStartOfDay
            if (node.name != old.get.name || old.get.nodeType != node.nodeType || oldStartDate != newStartDate
              || old.get.coordinates != node.coordinates) {

              // Update the node information
              if (old.get.nodeType != node.nodeType && oldStartDate != newStartDate) {

                // Check that new start date is not earlier than before
                if (newStartDate.getMillis < oldStartDate.getMillis) {
                  return Some(NodeStartDateUpdateErrorMessage)
                }

                // Create a new history layer when the node type has changed
                nodeDAO.create(Seq(old.get.copy(id = NewIdValue, endDate = Some(node.startDate.minusDays(1)))), username)
                nodeDAO.create(Seq(node.copy(id = NewIdValue)), username)

              } else {
                nodeDAO.create(Seq(node.copy(id = NewIdValue)), username)
              }
              nodeDAO.expireById(Seq(node.id))
            }
          } else {
            return Some(NodeNotFoundErrorMessage)
          }
        }
        None
      } catch {
        case e: Exception => Some(e.getMessage)
      }
    }
  }

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

  def getJunctionPointsByJunctionIds(junctionIds: Seq[Long]): Seq[JunctionPoint] = {
    withDynSession {
      junctionPointDAO.fetchByJunctionIds(junctionIds)
    }
  }

  def getNodesWithJunctionByBoundingBox(boundingRectangle: BoundingRectangle): Map[Node, (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]])] = {
    withDynSession {
      time(logger, "Fetch nodes with junctions") {
        val nodes = nodeDAO.fetchByBoundingBox(boundingRectangle)
        val nodePoints = nodePointDAO.fetchByNodeNumbers(nodes.map(_.nodeNumber))
        val junctions = junctionDAO.fetchJunctionsByNodeNumbers(nodes.map(_.nodeNumber))
        val junctionPoints = junctionPointDAO.fetchByJunctionIds(junctions.map(_.id))
        nodes.map {
          node =>
            (node,
              (
                nodePoints.filter(np => np.nodeNumber.isDefined && np.nodeNumber.get == node.nodeNumber),
                junctions.filter(j => j.nodeNumber.isDefined && j.nodeNumber.get == node.nodeNumber).map {
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

  def getNodesWithTimeInterval(sinceDate: DateTime, untilDate: Option[DateTime]): Map[Option[Node], (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]])] = {
    withDynSession {
      val nodes = nodeDAO.fetchAllByDateRange(sinceDate, untilDate)
      val nodePoints = nodePointDAO.fetchByNodeNumbers(nodes.map(_.nodeNumber))
      val junctions = junctionDAO.fetchJunctionsByNodeNumbers(nodes.map(_.nodeNumber))
      val junctionPoints = junctionPointDAO.fetchByJunctionIds(junctions.map(_.id))
      nodes.map {
        node =>
          (Option(node),
            (
              nodePoints.filter(np => np.nodeNumber.isDefined && np.nodeNumber.get == node.nodeNumber),
              junctions.filter(j => j.nodeNumber.isDefined && j.nodeNumber.get == node.nodeNumber).map {
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

  def getNodePointTemplates(authorizedElys: Seq[Int]): Seq[NodePoint] = {
    withDynSession {
      time(logger, "Fetch node point templates") {
        val allNodePointTemplates = nodePointDAO.fetchTemplates()
        allNodePointTemplates.filter(template => authorizedElys.contains(template.elyCode))
      }
    }
  }

  def getNodePointTemplateById(id: Long): Option[NodePoint] = {
    withDynSession {
      time(logger, "Fetch node point template by id") {
        nodePointDAO.fetchNodePointTemplateById(id)
      }
    }
  }

  def getJunctionTemplates(authorizedElys: Seq[Int]): Seq[JunctionTemplate] = {
    withDynSession {
      time(logger, "Fetch Junction templates") {
        val allJunctionTemplates = junctionDAO.fetchTemplates()
        allJunctionTemplates.filter(jt => jt.roadNumber != 0 && authorizedElys.contains(jt.elyCode)).groupBy(_.id).map(junctionTemplate => {
          junctionTemplate._2.minBy(jt => (jt.roadNumber, jt.roadPartNumber, jt.addrM))
        }).toSeq
      }
    }
  }

  def getTemplatesByBoundingBox(boundingRectangle: BoundingRectangle): (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]]) = {
    time(logger, "Fetch NodePoint and Junction + JunctionPoint templates") {
      val junctionPoints = junctionPointDAO.fetchTemplatesByBoundingBox(boundingRectangle)
      val junctions = junctionDAO.fetchByIds(junctionPoints.map(_.junctionId))
      val nodePoints = nodePointDAO.fetchTemplatesByBoundingBox(boundingRectangle)
      (nodePoints, junctions.map { junction => (junction, junctionPoints.filter(_.junctionId == junction.id)) }.toMap)
    }
  }

  def handleJunctionPointTemplates(roadwayChanges: List[ProjectRoadwayChange], projectLinks: Seq[ProjectLink], mappedRoadwayNumbers: Seq[ProjectRoadLinkChange]): Unit = {
    def getJunctionsInHead(link: BaseRoadAddress, roadsToHead: Seq[BaseRoadAddress], roadsFromHead: Seq[BaseRoadAddress]): Seq[Junction] = {
      val junctionIds = {
        val linkHeadJunction = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After)
        val roadsToHeadJunction = roadsToHead.flatMap(rh => junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.endAddrMValue, BeforeAfter.Before))
        val roadsFromHeadJunction = roadsFromHead.flatMap(rh => junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.startAddrMValue, BeforeAfter.After))
        (linkHeadJunction ++ roadsToHeadJunction ++ roadsFromHeadJunction).map(_.junctionId).toSeq.distinct
      }
      junctionDAO.fetchByIds(junctionIds)
    }

    def getJunctionsToTail(link: BaseRoadAddress, roadsToTail: Seq[BaseRoadAddress], roadsFromTail: Seq[BaseRoadAddress]): Seq[Junction] = {
      val junctionIds = {
        val linkHeadJunction = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)
        val roadsToTailJunction = roadsToTail.flatMap(rh => junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.endAddrMValue, BeforeAfter.Before))
        val roadsFromTailJunction = roadsFromTail.flatMap(rh => junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.startAddrMValue, BeforeAfter.After))
        (linkHeadJunction ++ roadsToTailJunction ++ roadsFromTailJunction).map(_.junctionId).toSeq.distinct
      }
      junctionDAO.fetchByIds(junctionIds)
    }

    def handleRoadsToHead(link: BaseRoadAddress, junctionsInHead: Seq[Junction], r: BaseRoadAddress): Unit = {
      val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.endAddrMValue, BeforeAfter.Before)
      val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
        val junctionId = if (junctionsInHead.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, None, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now)))).head
        else junctionsInHead.head.id
        val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.endAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(r.roadwayNumber, r.endAddrMValue, r.createdBy.getOrElse("-"))
        }
        logger.info(s"Creating JunctionPoint with roadwayNumber : ${r.roadwayNumber} addrM: ${r.endAddrMValue} beforeAfter: ${BeforeAfter.Before.value}, junctionId: $junctionId")
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, None, None, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now), r.roadwayNumber, r.endAddrMValue, r.roadNumber, r.roadPartNumber, r.track, r.discontinuity))).head
        Some(junctionId)
      } else Some(roadJunctionPoint.head.junctionId)

      val junctionId = if (junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
        junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, None, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now)))).head
      else junctionIdentifier.getOrElse(junctionsInHead.head.id)
      val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.startAddrMValue)
      val rwPoint = if (existingRoadwayPoint.nonEmpty) {
        existingRoadwayPoint.get.id
      } else {
        roadwayPointDAO.create(link.roadwayNumber, link.startAddrMValue, link.createdBy.getOrElse("-"))
      }
      val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After)
      if (linkJunctionPoint.isEmpty) {
        logger.info(s"Creating JunctionPoint with roadwayNumber : ${link.roadwayNumber} addrM: ${link.startAddrMValue} beforeAfter: ${BeforeAfter.After.value}, junctionId: $junctionId")
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, None, None, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now), link.roadwayNumber, link.startAddrMValue, link.roadNumber, link.roadPartNumber, link.track, link.discontinuity)))
      }
    }

    def handleRoadsFromHead(link: BaseRoadAddress, newJunctionsInHead: Seq[Junction], junctionsInHead: Seq[Junction], r: BaseRoadAddress): Unit = {
      val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.startAddrMValue, BeforeAfter.After)
      val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
        val junctionId = if (newJunctionsInHead.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, None, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now)))).head
        else newJunctionsInHead.head.id
        val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.startAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(r.roadwayNumber, r.startAddrMValue, r.createdBy.getOrElse("-"))
        }
        logger.info(s"Creating JunctionPoint with roadwayNumber : ${r.roadwayNumber} addrM: ${r.startAddrMValue} beforeAfter: ${BeforeAfter.After.value}, junctionId: $junctionId")
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, None, None, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now), r.roadwayNumber, r.startAddrMValue, r.roadNumber, r.roadPartNumber, r.track, r.discontinuity))).head
        Some(junctionId)
      } else Some(roadJunctionPoint.head.junctionId)

      val junctionId = if (junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
        junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, link.endDate, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now)))).head
      else junctionIdentifier.getOrElse(junctionsInHead.head.id)
      val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.startAddrMValue)
      val rwPoint = if (existingRoadwayPoint.nonEmpty) {
        existingRoadwayPoint.get.id
      } else {
        roadwayPointDAO.create(link.roadwayNumber, link.startAddrMValue, r.createdBy.getOrElse("-"))
      }
      val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After)
      if (linkJunctionPoint.isEmpty) {
        logger.info(s"Creating JunctionPoint with roadwayNumber : ${link.roadwayNumber} addrM: ${link.startAddrMValue} beforeAfter: ${BeforeAfter.After.value}, junctionId: $junctionId")
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, None, None, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now), link.roadwayNumber, link.startAddrMValue, link.roadNumber, link.roadPartNumber, link.track, link.discontinuity)))
      }
    }

    def handleRoadsToTail(link: BaseRoadAddress, junctionsToTail: Seq[Junction], junctionsInHead: Seq[Junction], r: BaseRoadAddress): Unit = {
      val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.endAddrMValue, BeforeAfter.Before)
      val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
        val junctionId = if (junctionsToTail.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, None, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now)))).head
        else junctionsToTail.head.id
        val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.endAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(r.roadwayNumber, r.endAddrMValue, r.createdBy.getOrElse("-"))
        }
        logger.info(s"Creating JunctionPoint with roadwayNumber : ${r.roadwayNumber} addrM: ${r.endAddrMValue} beforeAfter: ${BeforeAfter.Before.value}, junctionId: $junctionId")
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, None, None, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now), r.roadwayNumber, r.endAddrMValue, r.roadNumber, r.roadPartNumber, r.track, r.discontinuity))).head
        Some(junctionId)
      } else Some(roadJunctionPoint.head.junctionId)

      val junctionId = if (junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
        junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, link.endDate, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now)))).head
      else junctionIdentifier.getOrElse(junctionsInHead.head.id)
      val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.endAddrMValue)
      val rwPoint = if (existingRoadwayPoint.nonEmpty) {
        existingRoadwayPoint.get.id
      } else {
        roadwayPointDAO.create(link.roadwayNumber, link.endAddrMValue, r.createdBy.getOrElse("-"))
      }
      val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)
      if (linkJunctionPoint.isEmpty) {
        logger.info(s"Creating JunctionPoint with roadwayNumber : ${link.roadwayNumber} addrM: ${link.endAddrMValue} beforeAfter: ${BeforeAfter.Before.value}, junctionId: $junctionId")
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, None, None, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now), link.roadwayNumber, link.endAddrMValue, link.roadNumber, link.roadPartNumber, link.track, link.discontinuity)))
      }
    }

    def handleRoadsFromTail(link: BaseRoadAddress, newJunctionsToTail: Seq[Junction], junctionsInHead: Seq[Junction], r: BaseRoadAddress): Unit = {
      val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.startAddrMValue, BeforeAfter.After)
      val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
        val junctionId = if (newJunctionsToTail.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, None, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now)))).head
        else newJunctionsToTail.head.id
        val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.startAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(r.roadwayNumber, r.startAddrMValue, r.createdBy.getOrElse("-"))
        }
        logger.info(s"Creating JunctionPoint with roadwayNumber : ${r.roadwayNumber} addrM: ${r.startAddrMValue} beforeAfter: ${BeforeAfter.After.value}, junctionId: $junctionId")
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, None, None, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now), r.roadwayNumber, r.startAddrMValue, r.roadNumber, r.roadPartNumber, r.track, r.discontinuity))).head
        Some(junctionId)
      } else Some(roadJunctionPoint.head.junctionId)

      val junctionId = if (junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
        junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, link.endDate, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now)))).head
      else junctionIdentifier.getOrElse(junctionsInHead.head.id)
      val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.endAddrMValue)
      val rwPoint = if (existingRoadwayPoint.nonEmpty) {
        existingRoadwayPoint.get.id
      } else {
        roadwayPointDAO.create(link.roadwayNumber, link.endAddrMValue, r.createdBy.getOrElse("-"))
      }
      val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)
      if (linkJunctionPoint.isEmpty) {
        logger.info(s"Creating JunctionPoint with roadwayNumber : ${link.roadwayNumber} addrM: ${link.endAddrMValue} beforeAfter: ${BeforeAfter.Before.value}, junctionId: $junctionId")
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, None, None, DateTime.now, None, link.createdBy.getOrElse("-"), Some(DateTime.now), link.roadwayNumber, link.endAddrMValue, link.roadNumber, link.roadPartNumber, link.track, link.discontinuity)))
      }
    }

    time(logger, "Handling junction point templates") {
      val nonTerminatedLinks: Seq[BaseRoadAddress] = projectLinks.filter(pl => pl.status != LinkStatus.Terminated)
      nonTerminatedLinks.foreach { projectLink =>
        val roadNumberLimits = Seq((RoadClass.forJunctions.start, RoadClass.forJunctions.end))

        val roadsInFirstPoint: Seq[BaseRoadAddress] = roadwayAddressMapper.getRoadAddressesByBoundingBox(BoundingRectangle(projectLink.startingPoint, projectLink.startingPoint), roadNumberLimits).filterNot(_.linearLocationId == projectLink.linearLocationId)
        val roadsInLastPoint: Seq[BaseRoadAddress] = roadwayAddressMapper.getRoadAddressesByBoundingBox(BoundingRectangle(projectLink.endPoint, projectLink.endPoint), roadNumberLimits).filterNot(_.linearLocationId == projectLink.linearLocationId)
        /*
          * RoadAddresses where a junction will be created or updated if there's one already
          * * Ramps;
          * * Roundabouts
          * * Discontinuity cases for same road number;
          * * Discontinuous links that are connected to project links;
         */
        val (headRoads, tailRoads): (Seq[BaseRoadAddress], Seq[BaseRoadAddress]) = {
          if (RoadClass.RampsAndRoundaboutsClass.roads.contains(projectLink.roadNumber.toInt)) {
            val head = roadsInFirstPoint filterNot RoadAddressFilters.sameRoadPart(projectLink)
            val tail = projectLink.discontinuity match {
              case Discontinuity.EndOfRoad | Discontinuity.Discontinuous | Discontinuity.MinorDiscontinuity =>
                // Roundabouts (20001 to 39999) : at the last link of the round lane - EndOfRoad or Discontinuous
                roadsInLastPoint
              case _ =>
                // Ramps : where road number OR road part number change
                roadsInLastPoint filterNot RoadAddressFilters.sameRoadPart(projectLink)
            }
            (head, tail)
          } else {
            projectLink.discontinuity match {
              case Discontinuity.EndOfRoad =>
                // Discontinuity EndOfRoad for same road number - The road ends in itself.
                val head = roadsInFirstPoint.filterNot(RoadAddressFilters.sameRoad(projectLink))
                val tail = roadsInLastPoint
                (head, tail)
              case Discontinuity.MinorDiscontinuity =>
                // Discontinuity cases for same road number

                val head = if (roadsInFirstPoint.exists(fl => RoadAddressFilters.endingOfRoad(fl)(projectLink)))
                  roadsInFirstPoint
                else
                  roadsInFirstPoint filterNot RoadAddressFilters.sameRoad(projectLink)
                //even if there are no connecting points (for e.g. in case of a geometry jump), the discontinuous links should have one junction point in the ending point in middle of the part (MinorDiscontinuity)
                val tail: Seq[BaseRoadAddress] = if (roadsInLastPoint.exists(fl => RoadAddressFilters.endingOfRoad(fl)(projectLink)))
                  roadsInLastPoint
                else
                  roadsInLastPoint.filter(r => RoadAddressFilters.continuousTopology(projectLink)(r) || RoadAddressFilters.connectingBothTails(r)(projectLink))
                (head, tail)
              case _ =>
                val head = if (roadsInFirstPoint.exists(fl => RoadAddressFilters.endingOfRoad(fl)(projectLink)))
                  roadsInFirstPoint.filter(r => RoadAddressFilters.continuousTopology(r)(projectLink) || RoadAddressFilters.connectingBothHeads(r)(projectLink))
                else if (nonTerminatedLinks.exists(fl => RoadAddressFilters.continuousRoadPartTrack(fl)(projectLink) && RoadAddressFilters.discontinuousTopology(fl)(projectLink)))
                  roadsInFirstPoint.filterNot(RoadAddressFilters.sameRoad(projectLink)) ++ nonTerminatedLinks.filter(fl => fl.id != projectLink.id && (RoadAddressFilters.halfContinuousHalfDiscontinuous(fl)(projectLink) || projectLink.startingPoint.connected(fl.startingPoint)))
                else
                  roadsInFirstPoint.filterNot(RoadAddressFilters.sameRoad(projectLink))

                val tail = if (roadsInLastPoint.exists(fl => RoadAddressFilters.endingOfRoad(fl)(projectLink)))
                  roadsInLastPoint
                else if (roadsInLastPoint.exists(fl => RoadAddressFilters.halfContinuousHalfDiscontinuous(projectLink)(fl)))
                  roadsInLastPoint
                else
                  roadsInLastPoint.filterNot(RoadAddressFilters.sameRoad(projectLink))

                (head, tail)
            }
          }
        }

        val roadsToHead = headRoads.filter(hr => RoadAddressFilters.continuousTopology(hr)(projectLink) || RoadAddressFilters.afterDiscontinuousJump(hr)(projectLink))
        val roadsFromHead = headRoads.filter(hr => RoadAddressFilters.connectingBothHeads(hr)(projectLink))

        val roadsToTail = tailRoads.filter(tr => RoadAddressFilters.connectingBothTails(tr)(projectLink))
        val roadsFromTail = tailRoads.filter(tr => RoadAddressFilters.continuousTopology(projectLink)(tr))

        /* handle creation of JUNCTION_POINT in reverse cases */
        val junctionReversed = roadwayChanges.exists(ch => ch.changeInfo.target.startAddressM.nonEmpty && projectLink.startAddrMValue >= ch.changeInfo.target.startAddressM.get
          && ch.changeInfo.target.endAddressM.nonEmpty && projectLink.endAddrMValue <= ch.changeInfo.target.endAddressM.get && ch.changeInfo.reversed)

        val originalLink = mappedRoadwayNumbers.find(mpr => projectLink.startAddrMValue == mpr.newStartAddr && projectLink.endAddrMValue == mpr.newEndAddr && mpr.newRoadwayNumber == projectLink.roadwayNumber)

        val existingHeadJunctionPoint = {
          if (originalLink.nonEmpty) {
            if (!junctionReversed)
              junctionPointDAO.fetchJunctionPointsByRoadwayPointNumbers(Set(originalLink.get.oldRoadwayNumber, projectLink.roadwayNumber), projectLink.startAddrMValue, BeforeAfter.After)
            else {
              junctionPointDAO.fetchJunctionPointsByRoadwayPointNumbers(Set(originalLink.get.oldRoadwayNumber, projectLink.roadwayNumber), originalLink.get.newStartAddr, BeforeAfter.Before)
            }
          } else None
        }

        val existingLastJunctionPoint = {
          if (originalLink.nonEmpty) {
            if (!junctionReversed)
              junctionPointDAO.fetchJunctionPointsByRoadwayPointNumbers(Set(originalLink.get.oldRoadwayNumber, projectLink.roadwayNumber), projectLink.startAddrMValue, BeforeAfter.Before)
            else {
              junctionPointDAO.fetchJunctionPointsByRoadwayPointNumbers(Set(originalLink.get.oldRoadwayNumber, projectLink.roadwayNumber), originalLink.get.newEndAddr, BeforeAfter.After)
            }
          } else None
        }

        if (existingHeadJunctionPoint.nonEmpty) {
          if (junctionReversed) {
            junctionPointDAO.update(Seq(existingHeadJunctionPoint.head.copy(beforeAfter = BeforeAfter.switch(existingHeadJunctionPoint.head.beforeAfter))))
          }
        }

        if (existingLastJunctionPoint.nonEmpty) {
          if (junctionReversed) {
            junctionPointDAO.update(Seq(existingLastJunctionPoint.head.copy(beforeAfter = BeforeAfter.switch(existingLastJunctionPoint.head.beforeAfter))))
          }
        }

        /*
          * R:road
          * L:project link
          * 0:junction point
        */
        // passed by collections "junctions in Head/ToTail" need to be passed for every iteration
        roadsToHead.foreach { roadAddress: BaseRoadAddress => handleRoadsToHead(projectLink, getJunctionsInHead(projectLink, roadsToHead, roadsFromHead), roadAddress) }

        roadsFromHead.foreach { roadAddress: BaseRoadAddress => handleRoadsFromHead(projectLink, getJunctionsInHead(projectLink, roadsToHead, roadsFromHead), getJunctionsInHead(projectLink, roadsToHead, roadsFromHead), roadAddress) }

        roadsToTail.foreach { roadAddress: BaseRoadAddress => handleRoadsToTail(projectLink, getJunctionsToTail(projectLink, roadsToTail, roadsFromTail), getJunctionsInHead(projectLink, roadsToHead, roadsFromHead), roadAddress) }

        roadsFromTail.foreach { roadAddress: BaseRoadAddress => handleRoadsFromTail(projectLink, getJunctionsToTail(projectLink, roadsToTail, roadsFromTail), getJunctionsInHead(projectLink, roadsToHead, roadsFromHead), roadAddress) }
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
  def handleNodePointTemplates(roadwayChanges: List[ProjectRoadwayChange], projectLinks: Seq[ProjectLink], mappedRoadwayNumbers: Seq[ProjectRoadLinkChange]): Unit = {
    time(logger, "Handling node point templates") {
      try {
        def continuousNodeSections(seq: Seq[ProjectLink], roadTypesSection: Seq[Seq[ProjectLink]]): (Seq[ProjectLink], Seq[Seq[ProjectLink]]) = {
          if (seq.isEmpty) {
            (Seq(), roadTypesSection)
          } else {
            val roadType = seq.headOption.map(_.roadType.value).getOrElse(0)
            val continuousProjectLinks = seq.takeWhile(pl => pl.roadType.value == roadType)
            continuousNodeSections(seq.drop(continuousProjectLinks.size), roadTypesSection :+ continuousProjectLinks)
          }
        }

        val filteredLinks = projectLinks.filter(pl => RoadClass.forNodes.contains(pl.roadNumber.toInt) && pl.status != LinkStatus.Terminated).filterNot(_.track == Track.LeftSide)
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

            /*
      handle update of NODE_POINT in reverse cases
    */
            val (startNodeReversed, endNodeReversed) =
              (roadwayChanges.exists(ch =>
                ch.changeInfo.target.startAddressM.nonEmpty && headLink.startAddrMValue == ch.changeInfo.target.startAddressM.get && ch.changeInfo.reversed
              ),
                roadwayChanges.exists(ch =>
                  ch.changeInfo.target.endAddressM.nonEmpty && lastLink.endAddrMValue == ch.changeInfo.target.endAddressM.get && ch.changeInfo.reversed
                ))


            val existingHeadNodePoint = {
              val originalLink = mappedRoadwayNumbers.find(mpr => headLink.startAddrMValue == mpr.newStartAddr && headLink.endAddrMValue == mpr.newEndAddr && mpr.newRoadwayNumber == headLink.roadwayNumber)
              if (originalLink.nonEmpty) {
                if (!startNodeReversed) {
                  nodePointDAO.fetchNodePointsTemplates(Set(originalLink.get.oldRoadwayNumber, headLink.roadwayNumber)).find(np => np.beforeAfter == After && np.addrM == headLink.startAddrMValue)
                } else {
                  nodePointDAO.fetchNodePointsTemplates(Set(originalLink.get.oldRoadwayNumber, headLink.roadwayNumber)).find(np => np.beforeAfter == Before && np.addrM == originalLink.get.newStartAddr)
                }
              } else None
            }

            val existingLastNodePoint = {
              val originalLink = mappedRoadwayNumbers.find(mpr => lastLink.startAddrMValue == mpr.newStartAddr && lastLink.endAddrMValue == mpr.newEndAddr && mpr.newRoadwayNumber == lastLink.roadwayNumber)
              if (originalLink.nonEmpty) {
                if (!endNodeReversed) {
                  nodePointDAO.fetchNodePointsTemplates(Set(originalLink.get.oldRoadwayNumber, lastLink.roadwayNumber)).find(np => np.beforeAfter == Before && np.addrM == lastLink.endAddrMValue)
                } else {
                  nodePointDAO.fetchNodePointsTemplates(Set(originalLink.get.oldRoadwayNumber, lastLink.roadwayNumber)).find(np => np.beforeAfter == After && np.addrM == originalLink.get.newEndAddr)
                }
              } else None
            }

            if (existingHeadNodePoint.nonEmpty) {
              if (startNodeReversed) {
                nodePointDAO.update(Seq(existingHeadNodePoint.head.copy(beforeAfter = BeforeAfter.switch(existingHeadNodePoint.head.beforeAfter))))
              }
            } else {
              nodePointDAO.create(Seq(NodePoint(NewIdValue, BeforeAfter.After, headRoadwayPointId, None, RoadNodePoint, None, None, DateTime.now(), None, headLink.createdBy.getOrElse("-"), Some(DateTime.now()), headLink.roadwayNumber, headLink.startAddrMValue, headLink.roadNumber, headLink.roadPartNumber, headLink.track, headLink.ely)))
            }

            if (existingLastNodePoint.nonEmpty) {
              if (endNodeReversed) {
                nodePointDAO.update(Seq(existingLastNodePoint.head.copy(beforeAfter = BeforeAfter.switch(existingLastNodePoint.head.beforeAfter))))
              }
            } else {
              nodePointDAO.create(Seq(NodePoint(NewIdValue, BeforeAfter.Before, lastRoadwayPointId, None, RoadNodePoint, None, None, DateTime.now(), None, lastLink.createdBy.getOrElse("-"), Some(DateTime.now()), lastLink.roadwayNumber, lastLink.endAddrMValue, lastLink.roadNumber, lastLink.roadPartNumber, lastLink.track, lastLink.ely)))
            }
          }
        }.toSeq
      } catch {
        case ex: Exception => {
          println("Failed nodepoints: ", ex.printStackTrace())
        }
      }
    }
  }

  def getNodePointTemplatesByBoundingBox(boundingRectangle: BoundingRectangle): Seq[NodePoint] = {
    withDynSession {
      time(logger, "Fetch nodes point templates") {
        nodePointDAO.fetchTemplatesByBoundingBox(boundingRectangle)
      }
    }
  }

  def getJunctionTemplatesByBoundingBox(boundingRectangle: BoundingRectangle): Map[JunctionTemplate, Seq[JunctionPoint]] = {
    withDynSession {
      time(logger, "Fetch junction templates") {
        val junctions: Seq[JunctionTemplate] = junctionDAO.fetchTemplatesByBoundingBox(boundingRectangle)
        val junctionPoints: Seq[JunctionPoint] = junctionPointDAO.fetchTemplatesByBoundingBox(boundingRectangle)
        junctions.map {
          junction =>
            (junction,
              junctionPoints.filter(_.junctionId == junction.id))
        }.toMap
      }
    }
  }

  def expireObsoleteNodesAndJunctions(projectLinks: Seq[ProjectLink], endDate: Option[DateTime], username: String = "-"): Unit = {

    def getNodePointsAndJunctionPointsByTerminatedRoadwayNumbers(terminatedRoadwayNumbers: Seq[Long]): (Seq[NodePoint], Seq[JunctionPoint]) = {
      logger.info(s"Terminated roadway numbers : $terminatedRoadwayNumbers")
      val roadwayPointIds = roadwayPointDAO.fetchByRoadwayNumbers(terminatedRoadwayNumbers).map(_.id)
      logger.info(s"Roadway points for terminated roadways : $roadwayPointIds")
      val nodePointsToTerminate = nodePointDAO.fetchByRoadwayPointIds(roadwayPointIds)
      val junctionPointsToTerminate = junctionPointDAO.fetchByRoadwayPointIds(roadwayPointIds)

      logger.info(s"Node points to Expire : ${nodePointsToTerminate.map(_.id)}")
      logger.info(s"Junction points to Expire : ${junctionPointsToTerminate.map(_.id)}")
      (nodePointsToTerminate, junctionPointsToTerminate)
    }

    def getNodePointsAndJunctionPointsByModifiedRoadwayNumbers(roadwayNumbersSection: Seq[Long], terminatedJunctionPoints: Seq[JunctionPoint]): (Seq[NodePoint], Seq[JunctionPoint]) = {
      logger.info(s"Modified roadway number: $roadwayNumbersSection")
      val roadwayPointIds = roadwayPointDAO.fetchByRoadwayNumbers(roadwayNumbersSection).map(_.id)
      val sortedRoadways = roadwayDAO.fetchAllByRoadwayNumbers(roadwayNumbersSection.toSet).sortBy(_.startAddrMValue)
      val obsoleteNodePoints = if (sortedRoadways.nonEmpty) {
        val (startAddrMValue, endAddrMValue) = (sortedRoadways.head.startAddrMValue, sortedRoadways.last.endAddrMValue)
        nodePointDAO.fetchByRoadwayPointIds(roadwayPointIds)
          .filter(_.nodePointType == NodePointType.RoadNodePoint)
          .filterNot(n => (n.beforeAfter == BeforeAfter.After && n.addrM == startAddrMValue) || (n.beforeAfter == BeforeAfter.Before && n.addrM == endAddrMValue))

      } else Seq.empty[NodePoint]
      val groupedTerminatedJunctionPoints = terminatedJunctionPoints.groupBy(_.junctionId)

      val junctions = junctionDAO.fetchByIds(junctionPointDAO.fetchByRoadwayPointIds(roadwayPointIds).map(_.junctionId))
      val obsoleteJunctionPoints: Seq[JunctionPoint] = junctions.flatMap { junction =>
        val terminatedJunctionPoints = groupedTerminatedJunctionPoints.getOrElse(junction.id, Seq.empty[JunctionPoint])
        val affectedJunctionsPoints = junctionPointDAO.fetchByJunctionIds(Seq(junction.id)) match {
          case junctionPoints if junctionPoints.exists(jp => RoadClass.RampsAndRoundaboutsClass.roads.contains(jp.roadNumber)) =>
            val junctionPointsToCheck = junctionPoints.filterNot(jp => terminatedJunctionPoints.map(_.id).contains(jp.id))
            if (junctionPointsToCheck.size <= 1) {
              // basic rule
              junctionPointsToCheck
            } else if (ObsoleteJunctionPointFilters.rampsAndRoundaboutsDisContinuityInSameOwnRoadNumber(junctionPointsToCheck) ||
              junctionPoints.groupBy(jp => (jp.roadNumber, jp.roadPartNumber)).keys.size > 1)
              Seq.empty[JunctionPoint]
            else junctionPointsToCheck
          case junctionPoints if !junctionPoints.forall(jp => RoadClass.RampsAndRoundaboutsClass.roads.contains(jp.roadNumber)) =>

            val junctionPointsToCheck = junctionPoints.filterNot(jp => terminatedJunctionPoints.map(_.id).contains(jp.id))
            // check if the terminated junction Points are the unique ones in the Junction to avoid further complex validations
            if (junctionPointsToCheck.size <= 1) {
              // basic rule
              junctionPointsToCheck
            } else if (ObsoleteJunctionPointFilters.multipleRoadNumberIntersection(junctionPointsToCheck) ||
              ObsoleteJunctionPointFilters.multipleTrackIntersection(junctionPointsToCheck) ||
              ObsoleteJunctionPointFilters.sameRoadAddressIntersection(junctionPointsToCheck) ||
              ObsoleteJunctionPointFilters.roadEndingInSameOwnRoadNumber(junctionPointsToCheck)
            )
              Seq.empty[JunctionPoint]
            else
              junctionPointsToCheck
        }
        affectedJunctionsPoints
      }
      logger.info(s"Obsolete node points : ${obsoleteNodePoints.map(_.id).toSet}")
      logger.info(s"Obsolete junction points : ${obsoleteJunctionPoints.map(_.id).toSet}")
      (obsoleteNodePoints, obsoleteJunctionPoints)
    }

    def expireJunctionsAndJunctionPoints(junctionPoints: Seq[JunctionPoint]): Seq[Junction] = {
      // Expire current junction points rows
      val junctionPointsIds = junctionPoints.map(_.id)
      logger.info(s"Expiring junction points : ${junctionPointsIds.toSet}")
      junctionPointDAO.expireById(junctionPointsIds)
      val junctionIdsToCheck = junctionPoints.map(_.junctionId).distinct

      // Remove junctions that no longer have valid junction Points
      val junctionsToExpireIds = junctionIdsToCheck.filter { id =>
        junctionDAO.fetchJunctionByIdWithValidPoints(id).isEmpty
      }

      logger.info(s"Expiring junctions : ${junctionsToExpireIds.toSet}")
      junctionDAO.expireById(junctionsToExpireIds)

      // Create junction rows with end date and junction point rows with new junction id
      val junctionsToExpire = junctionDAO.fetchAllByIds(junctionsToExpireIds)
      junctionsToExpire.foreach(j => {
        val newJunctionId = junctionDAO.create(Seq(j.copy(id = NewIdValue, endDate = endDate, createdBy = username))).head
        junctionPointDAO.create(junctionPoints
          .filter(_.junctionId == j.id)
          .map(_.copy(id = NewIdValue, junctionId = newJunctionId, createdBy = username)))
      })

      junctionsToExpire
    }

    def expireNodesAndNodePoints(nodePoints: Seq[NodePoint], junctions: Seq[Junction]): Unit = {

      // Expire obsolete node points
      logger.info(s"Expiring node points : ${nodePoints.map(_.id).toSet}")
      nodePointDAO.expireById(nodePoints.map(_.id))

      val nodeNumbersToCheck = (junctions.filter(j => j.nodeNumber.isDefined).map(_.nodeNumber.get)
        ++ nodePoints.filter(np => np.nodeNumber.isDefined).map(_.nodeNumber.get)).distinct

      // Remove nodes that no longer have justification for the current network
      val nodesToExpire = nodeDAO.fetchEmptyNodes(nodeNumbersToCheck)
      val nodeNumbersToExpire = nodesToExpire.map(_.nodeNumber)

      logger.info(s"Expiring nodes: $nodeNumbersToExpire")
      nodeDAO.expireById(nodesToExpire.map(_.id))

      // Create node rows with end date
      nodesToExpire.foreach(n => {
        nodeDAO.create(Seq(n.copy(id = NewIdValue, endDate = endDate, createdBy = username))).head
      })

      val calculatedNodePointsOfExpiredNodes = nodePointDAO.fetchByNodeNumbers(nodeNumbersToExpire)
      logger.info(s"Expiring node points of expired nodes : $calculatedNodePointsOfExpiredNodes")
      nodePointDAO.expireById(calculatedNodePointsOfExpiredNodes.map(_.id))
    }

    val filteredProjectLinks = projectLinks
      .filter(pl => RoadClass.forJunctions.contains(pl.roadNumber.toInt))

    val terminated = filteredProjectLinks.filter(_.status == LinkStatus.Terminated)

    val terminatedRoadwayNumbers = terminated.map(_.roadwayNumber).distinct
    val (terminatedNodePoints, terminatedJunctionPoints): (Seq[NodePoint], Seq[JunctionPoint]) = getNodePointsAndJunctionPointsByTerminatedRoadwayNumbers(terminatedRoadwayNumbers)

    val projectLinkSections = filteredProjectLinks.groupBy(projectLink => (projectLink.roadNumber, projectLink.roadPartNumber, projectLink.roadType))
    val obsoletePointsFromModifiedRoadways: Seq[(Seq[NodePoint], Seq[JunctionPoint])] = projectLinkSections.mapValues { section: Seq[ProjectLink] =>
      val modifiedRoadwayNumbers = section.map(_.roadwayNumber).distinct
      getNodePointsAndJunctionPointsByModifiedRoadwayNumbers(modifiedRoadwayNumbers, terminatedJunctionPoints)
    }.values.toSeq

    val obsoleteNodePoints = terminatedNodePoints ++ obsoletePointsFromModifiedRoadways.flatMap(_._1)
    val obsoleteJunctionPoints = obsoletePointsFromModifiedRoadways.flatMap(_._2) ++ terminatedJunctionPoints

    val expiredJunctions = expireJunctionsAndJunctionPoints(obsoleteJunctionPoints)
    expireNodesAndNodePoints(obsoleteNodePoints, expiredJunctions)
  }

  def getJunctionInfoByJunctionId(junctionIds: Seq[Long]): Option[JunctionInfo] = {
    withDynSession {
      junctionDAO.fetchJunctionInfoByJunctionId(junctionIds)
    }
  }

  def detachJunctionsFromNode(junctionIds: Seq[Long], username: String = "-"): Option[String] = {
    withDynTransactionNewOrExisting {
      val junctionsToDetach = junctionDAO.fetchByIds(junctionIds).filter(_.nodeNumber.isDefined)
      if (junctionsToDetach.nonEmpty) {

        // Expire the current junction
        junctionDAO.expireById(junctionsToDetach.map(_.id))

        junctionsToDetach.foreach { j =>
          // Create a new junction template
          val junction = j.copy(id = NewIdValue, junctionNumber = None, nodeNumber = None, createdBy = username)
          val newJunctionId = junctionDAO.create(Seq(junction)).head

          // Expire the current junction points
          val junctionPointsToExpire = junctionPointDAO.fetchByJunctionIds(Seq(j.id))
          junctionPointDAO.expireById(junctionPointsToExpire.map(_.id))

          // Create new junction points with new junction id
          junctionPointDAO.create(junctionPointsToExpire.map(_.copy(id = NewIdValue, junctionId = newJunctionId, createdBy = username)))
        }

        // TODO Calculate node points again (implemented in VIITE-1862)

        // If there are no node points left under the node, node can be terminated
        val nodeNumber = junctionsToDetach.head.nodeNumber.get
        terminateNodeIfNoNodePoints(nodeNumber, username)
      }
      None
    }
  }

  private def terminateNodeIfNoNodePoints(nodeNumber: Long, username: String) = {
    val nodePoints = nodePointDAO.fetchByNodeNumbers(Seq(nodeNumber))
    if (nodePoints.isEmpty) {
      val node = nodeDAO.fetchByNodeNumber(nodeNumber)
      if (node.isDefined) {

        // Terminate Node
        nodeDAO.expireById(Seq(node.get.id))
        nodeDAO.create(Seq(node.get.copy(id = NewIdValue, endDate = Some(DateTime.now), createdBy = username)))

      } else {
        throw new Exception(s"Could not find node with number $nodeNumber")
      }
    }
  }

  def detachNodePointsFromNode(nodePointIds: Seq[Long], username: String = "-"): Option[String] = {
    withDynTransactionNewOrExisting {
      val nodePointsToDetach = nodePointDAO.fetchByIds(nodePointIds).filter(n => n.nodeNumber.isDefined && n.nodePointType == NodePointType.RoadNodePoint)
      if (nodePointsToDetach.nonEmpty) {

        // Expire the current node point and create a new template
        nodePointDAO.expireById(nodePointsToDetach.map(_.id))

        nodePointsToDetach.foreach { np =>

          // Create a new node point template
          nodePointDAO.create(Seq(np.copy(id = NewIdValue, nodeNumber = None, createdBy = username)))

        }

        // If there are no node points left under the node, node can be terminated
        val nodeNumber = nodePointsToDetach.head.nodeNumber.get
        terminateNodeIfNoNodePoints(nodeNumber, username)

      }
      None
    }
  }

  object ObsoleteJunctionPointFilters {

    def multipleRoadNumberIntersection(junctionPointsToCheck: Seq[JunctionPoint]): Boolean = {
      junctionPointsToCheck.groupBy(_.roadNumber).keys.size > 1
    }

    def multipleTrackIntersection(junctionPointsToCheck: Seq[JunctionPoint]): Boolean = {
      val tracks = junctionPointsToCheck.groupBy(_.track)
      !tracks.contains(Track.Combined) && tracks.contains(Track.LeftSide) && tracks.contains(Track.RightSide)
    }

    def sameRoadAddressIntersection(junctionPointsToCheck: Seq[JunctionPoint]): Boolean = {

      def isRoadPartIntersection(curr: JunctionPoint, rest: Seq[JunctionPoint]): Boolean = {
        val junctionPointsInSameAddrAndPart = rest.filter(jp => curr.roadNumber == jp.roadNumber && curr.roadPartNumber == jp.roadPartNumber && curr.addrM == jp.addrM)
        val (before, after) = (junctionPointsInSameAddrAndPart :+ curr).partition(_.beforeAfter == Before)
        val junctionPointsInSamePart = rest.filter(jp => curr.roadNumber == jp.roadNumber && curr.roadPartNumber == jp.roadPartNumber)
        val combinedIntersection = junctionPointsInSamePart.map(_.addrM).exists(addr => addr != curr.addrM)
        (before.size > 1 && after.nonEmpty && !twoTrackToCombined(before, after)) || (before.nonEmpty && after.size > 1 && !combinedToTwoTrack(before, after)) || combinedIntersection
      }

      def twoTrackToCombined(before: Seq[JunctionPoint], after: Seq[JunctionPoint]): Boolean = {
        before.forall(_.track != Track.Combined) && after.forall(_.track == Track.Combined)
      }

      def combinedToTwoTrack(before: Seq[JunctionPoint], after: Seq[JunctionPoint]): Boolean = {
        before.forall(_.track == Track.Combined) && after.forall(_.track != Track.Combined)
      }

      junctionPointsToCheck.exists { jpc =>
        isRoadPartIntersection(jpc, junctionPointsToCheck.filter(_.id != jpc.id))
      }
    }

    def roadEndingInSameOwnRoadNumber(junctionPointsToCheck: Seq[JunctionPoint]): Boolean = {

      def isRoadEndingInItself(curr: JunctionPoint, rest: Seq[JunctionPoint]): Boolean = {
        rest.exists(jp => curr.roadNumber == jp.roadNumber && curr.discontinuity == Discontinuity.EndOfRoad && jp.discontinuity != Discontinuity.EndOfRoad && curr.beforeAfter == Before)
      }

      junctionPointsToCheck.exists { jpc =>
        isRoadEndingInItself(jpc, junctionPointsToCheck.filter(_.id != jpc.id))
      }
    }

    def rampsAndRoundaboutsDisContinuityInSameOwnRoadNumber(junctionPointsToCheck: Seq[JunctionPoint]): Boolean = {
      val validEndingDiscontinuityForRamps = List(Discontinuity.EndOfRoad, Discontinuity.Discontinuous, Discontinuity.MinorDiscontinuity)

      def isRoadEndingInItself(curr: JunctionPoint, rest: Seq[JunctionPoint]): Boolean = {
        rest.exists(jp => curr.roadNumber == jp.roadNumber && jp.addrM == 0 && validEndingDiscontinuityForRamps.contains(curr.discontinuity) && curr.beforeAfter == Before)
      }

      junctionPointsToCheck.exists { jpc =>
        isRoadEndingInItself(jpc, junctionPointsToCheck.filter(_.id != jpc.id))
      }
    }

  }

}
