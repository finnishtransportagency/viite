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
        val nodePoints = nodePointDAO.fetchNodePointsByNodeNumber(nodes.map(_.nodeNumber))
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

  def getNodesWithTimeInterval(sinceDate: DateTime, untilDate: Option[DateTime]) : Map[Option[Node], (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]])] = {
    withDynSession {
      val nodes = nodeDAO.fetchAllByDateRange(sinceDate, untilDate)
      val nodePoints = nodePointDAO.fetchNodePointsByNodeNumber(nodes.map(_.nodeNumber))
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
    withDynSession{
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
    withDynSession{
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

  def handleJunctionPointTemplates(roadwayChanges: List[ProjectRoadwayChange], projectLinks: Seq[ProjectLink], mappedRoadwayNumbers: Seq[RoadwayNumbersLinkChange]): Unit = {
    def getJunctionsInHead(link: BaseRoadAddress, roadsToHead: Seq[BaseRoadAddress], roadsFromHead: Seq[BaseRoadAddress]): Seq[Junction] = {
      val junctionIds = {
        val linkHeadJunction = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After)
        val roadsToHeadJunction = roadsToHead.flatMap(rh => junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.endAddrMValue, BeforeAfter.Before))
        val roadsFromHeadJunction = roadsFromHead.flatMap(rh => junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.startAddrMValue, BeforeAfter.After))
        (linkHeadJunction++roadsToHeadJunction++roadsFromHeadJunction).map(_.junctionId).toSeq.distinct
      }
      junctionDAO.fetchByIds(junctionIds)
    }

    def getJunctionsToTail(link: BaseRoadAddress, roadsToTail: Seq[BaseRoadAddress], roadsFromTail: Seq[BaseRoadAddress]): Seq[Junction] = {
      val junctionIds = {
        val linkHeadJunction = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)
        val roadsToTailJunction = roadsToTail.flatMap(rh => junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.endAddrMValue, BeforeAfter.Before))
        val roadsFromTailJunction = roadsFromTail.flatMap(rh => junctionPointDAO.fetchJunctionPointsByRoadwayPoints(rh.roadwayNumber, rh.startAddrMValue, BeforeAfter.After))
        (linkHeadJunction++roadsToTailJunction++roadsFromTailJunction).map(_.junctionId).toSeq.distinct
      }
      junctionDAO.fetchByIds(junctionIds)
    }

    def handleRoadsToHead(link: BaseRoadAddress, junctionsInHead: Seq[Junction], r: BaseRoadAddress): Unit = {
      val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.endAddrMValue, BeforeAfter.Before)
      val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
        val junctionId = if (junctionsInHead.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
        else junctionsInHead.head.id
        val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.endAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(r.roadwayNumber, r.endAddrMValue, r.createdBy.getOrElse("-"))
        }
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, DateTime.now, None, link.createdBy, Some(DateTime.now), r.roadwayNumber, r.endAddrMValue, r.roadNumber, r.roadPartNumber, r.track))).head
        Some(junctionId)
      } else Some(roadJunctionPoint.head.junctionId)

      val junctionId = if (junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
        junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
      else junctionIdentifier.getOrElse(junctionsInHead.head.id)
      val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.startAddrMValue)
      val rwPoint = if (existingRoadwayPoint.nonEmpty) {
        existingRoadwayPoint.get.id
      } else {
        roadwayPointDAO.create(link.roadwayNumber, link.startAddrMValue, link.createdBy.getOrElse("-"))
      }
      val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After)
      if(linkJunctionPoint.isEmpty)
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.startAddrMValue, link.roadNumber, link.roadPartNumber, link.track)))
    }

    def handleRoadsFromHead(link: BaseRoadAddress, newJunctionsInHead: Seq[Junction], junctionsInHead: Seq[Junction], r: BaseRoadAddress): Unit = {
      val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.startAddrMValue, BeforeAfter.After)
      val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
        val junctionId = if (newJunctionsInHead.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
        else newJunctionsInHead.head.id
        val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.startAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(r.roadwayNumber, r.startAddrMValue, r.createdBy.getOrElse("-"))
        }
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, DateTime.now, None, link.createdBy, Some(DateTime.now), r.roadwayNumber, r.startAddrMValue, r.roadNumber, r.roadPartNumber, r.track))).head
        Some(junctionId)
      } else Some(roadJunctionPoint.head.junctionId)

      val junctionId = if (junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
        junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, link.endDate, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
      else junctionIdentifier.getOrElse(junctionsInHead.head.id)
      val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.startAddrMValue)
      val rwPoint = if (existingRoadwayPoint.nonEmpty) {
        existingRoadwayPoint.get.id
      } else {
        roadwayPointDAO.create(link.roadwayNumber, link.startAddrMValue, r.createdBy.getOrElse("-"))
      }
      val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.startAddrMValue, BeforeAfter.After)
      if(linkJunctionPoint.isEmpty)
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.startAddrMValue, link.roadNumber, link.roadPartNumber, link.track)))
    }

    def handleRoadsToTail(link: BaseRoadAddress, junctionsToTail: Seq[Junction], junctionsInHead: Seq[Junction], r: BaseRoadAddress): Unit = {
      val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.endAddrMValue, BeforeAfter.Before)
      val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
        val junctionId = if (junctionsToTail.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
        else junctionsToTail.head.id
        val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.endAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(r.roadwayNumber, r.endAddrMValue, r.createdBy.getOrElse("-"))
        }
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, DateTime.now, None, link.createdBy, Some(DateTime.now), r.roadwayNumber, r.endAddrMValue, r.roadNumber, r.roadPartNumber, r.track))).head
        Some(junctionId)
      } else Some(roadJunctionPoint.head.junctionId)

      val junctionId = if (junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
        junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, link.endDate, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
      else junctionIdentifier.getOrElse(junctionsInHead.head.id)
      val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.endAddrMValue)
      val rwPoint = if (existingRoadwayPoint.nonEmpty) {
        existingRoadwayPoint.get.id
      } else {
        roadwayPointDAO.create(link.roadwayNumber, link.endAddrMValue, r.createdBy.getOrElse("-"))
      }
      val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)
      if(linkJunctionPoint.isEmpty)
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.endAddrMValue, link.roadNumber, link.roadPartNumber, link.track)))
    }

    def handleRoadsFromTail(link: BaseRoadAddress, newJunctionsToTail: Seq[Junction], junctionsInHead: Seq[Junction], r: BaseRoadAddress): Unit = {
      val roadJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(r.roadwayNumber, r.startAddrMValue, BeforeAfter.After)
      val junctionIdentifier = if (roadJunctionPoint.isEmpty) {
        val junctionId = if (newJunctionsToTail.isEmpty)
          junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, None, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
        else newJunctionsToTail.head.id
        val existingRoadwayPoint = roadwayPointDAO.fetch(r.roadwayNumber, r.startAddrMValue)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(r.roadwayNumber, r.startAddrMValue, r.createdBy.getOrElse("-"))
        }
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.After, rwPoint, junctionId, DateTime.now, None, link.createdBy, Some(DateTime.now), r.roadwayNumber, r.startAddrMValue, r.roadNumber, r.roadPartNumber, r.track))).head
        Some(junctionId)
      } else Some(roadJunctionPoint.head.junctionId)

      val junctionId = if (junctionsInHead.isEmpty && junctionIdentifier.isEmpty)
        junctionDAO.create(Seq(Junction(NewIdValue, None, None, link.startDate.get, link.endDate, DateTime.now, None, link.createdBy, Some(DateTime.now)))).head
      else junctionIdentifier.getOrElse(junctionsInHead.head.id)
      val existingRoadwayPoint = roadwayPointDAO.fetch(link.roadwayNumber, link.endAddrMValue)
      val rwPoint = if (existingRoadwayPoint.nonEmpty) {
        existingRoadwayPoint.get.id
      } else {
        roadwayPointDAO.create(link.roadwayNumber, link.endAddrMValue, r.createdBy.getOrElse("-"))
      }
      val linkJunctionPoint = junctionPointDAO.fetchJunctionPointsByRoadwayPoints(link.roadwayNumber, link.endAddrMValue, BeforeAfter.Before)
      if(linkJunctionPoint.isEmpty)
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, BeforeAfter.Before, rwPoint, junctionId, DateTime.now, None, link.createdBy, Some(DateTime.now), link.roadwayNumber, link.endAddrMValue, link.roadNumber, link.roadPartNumber, link.track)))
    }

    time(logger, "Handling junction point templates") {
      val filteredLinks:Seq[BaseRoadAddress] = projectLinks.filter(pl => pl.status != LinkStatus.Terminated)
      filteredLinks.foreach { projectLink =>
        val roadNumberLimits = Seq((RoadClass.forJunctions.start, RoadClass.forJunctions.end))

        val roadsInFirstPoint:Seq[BaseRoadAddress] = roadwayAddressMapper.getRoadAddressesByBoundingBox(BoundingRectangle(projectLink.startingPoint, projectLink.startingPoint), roadNumberLimits).filterNot(_.linearLocationId == projectLink.linearLocationId)
        val roadsInLastPoint:Seq[BaseRoadAddress] = roadwayAddressMapper.getRoadAddressesByBoundingBox(BoundingRectangle(projectLink.endPoint, projectLink.endPoint), roadNumberLimits).filterNot(_.linearLocationId == projectLink.linearLocationId)
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
              case Discontinuity.EndOfRoad | Discontinuity.Discontinuous =>
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
              val head = if(filteredLinks.exists(fl=>RoadAddressFilters.continuousRoadPart(fl)(projectLink) && RoadAddressFilters.discontinuousTopology(fl)(projectLink)))
                roadsInFirstPoint
              else roadsInFirstPoint.filterNot(RoadAddressFilters.sameRoad(projectLink))
              val tail = roadsInLastPoint
              (head, tail)
            case Discontinuity.Discontinuous | Discontinuity.MinorDiscontinuity =>
              // Discontinuity cases for same road number
              val head = if(filteredLinks.exists(fl=>RoadAddressFilters.continuousRoadPart(fl)(projectLink) && RoadAddressFilters.discontinuousTopology(fl)(projectLink)))
                roadsInFirstPoint
              else
                roadsInFirstPoint filterNot RoadAddressFilters.sameRoad(projectLink)
              //even if there are no connecting points (for e.g. in case of a geometry jump), the discontinuous links should have one junction point in the ending point
              val tail:Seq[BaseRoadAddress] = roadsInLastPoint :+ projectLink
              (head, tail)
            case _ =>
              val head = if(filteredLinks.exists(fl=>RoadAddressFilters.halfContinuousHalfDiscontinuous(fl)(projectLink)))
                roadsInFirstPoint
              else
                roadsInFirstPoint.filterNot(RoadAddressFilters.sameRoad(projectLink))

              val tail =  if(filteredLinks.exists(fl=>RoadAddressFilters.halfContinuousHalfDiscontinuous(projectLink)(fl)))
                roadsInLastPoint
              else
                roadsInLastPoint.filterNot(RoadAddressFilters.sameRoad(projectLink))

              (head, tail)
            }
          }
        }

        val roadsToHead = headRoads.filter(_.endPoint.connected(projectLink.startingPoint))
        val roadsFromHead = headRoads.filter(r => projectLink.startingPoint.connected(r.startingPoint))

        val roadsToTail = tailRoads.filter(_.endPoint.connected(projectLink.endPoint))
        val roadsFromTail = tailRoads.filter(r => projectLink.endPoint.connected(r.startingPoint))

        /*  handle creation of JUNCTION_POINT in reverse cases */
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
        //passed by collections "junctions in Head/ToTail" need to be passed for every iteration
        //  |--R-->0|0--L-->
        roadsToHead.foreach { roadAddress: BaseRoadAddress => handleRoadsToHead(projectLink, getJunctionsInHead(projectLink, roadsToHead, roadsFromHead), roadAddress) }

        //  <--R--0|0--L-->
        roadsFromHead.foreach { roadAddress: BaseRoadAddress => handleRoadsFromHead(projectLink, getJunctionsInHead(projectLink, roadsToHead, roadsFromHead), getJunctionsInHead(projectLink, roadsToHead, roadsFromHead), roadAddress) }

        //  |--R--0>|<0--L--|
        roadsToTail.foreach { roadAddress: BaseRoadAddress => handleRoadsToTail(projectLink, getJunctionsToTail(projectLink, roadsToTail, roadsFromTail), getJunctionsInHead(projectLink, roadsToHead, roadsFromHead), roadAddress) }

        //  <--R--0|<0--L--|
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
  def handleNodePointTemplates(roadwayChanges: List[ProjectRoadwayChange], projectLinks: Seq[ProjectLink], mappedRoadwayNumbers: Seq[RoadwayNumbersLinkChange]): Unit = {
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
              nodePointDAO.create(Seq(NodePoint(NewIdValue, BeforeAfter.After, headRoadwayPointId, None, RoadNodePoint, DateTime.now(), None, headLink.createdBy, Some(DateTime.now()), headLink.roadwayNumber, headLink.startAddrMValue, headLink.roadNumber, headLink.roadPartNumber, headLink.track, headLink.ely)))
            }

            if (existingLastNodePoint.nonEmpty) {
              if (endNodeReversed) {
                nodePointDAO.update(Seq(existingLastNodePoint.head.copy(beforeAfter = BeforeAfter.switch(existingLastNodePoint.head.beforeAfter))))
              }
            } else {
              nodePointDAO.create(Seq(NodePoint(NewIdValue, BeforeAfter.Before, lastRoadwayPointId, None, RoadNodePoint, DateTime.now(), None, lastLink.createdBy, Some(DateTime.now()), lastLink.roadwayNumber, lastLink.endAddrMValue, lastLink.roadNumber, lastLink.roadPartNumber, lastLink.track, lastLink.ely)))
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
    def nodePointsAndJunctionPointsToExpire(terminatedRoadwayNumbers: Seq[Long]): (Seq[NodePoint], Seq[JunctionPoint]) = {
      val obsoleteRoadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(terminatedRoadwayNumbers)
      val obsoleteNodePoints = nodePointDAO.fetchByRoadwayPointIds(obsoleteRoadwayPoints.map(_.id))
      val obsoleteJunctionPoints = junctionPointDAO.fetchByRoadwayPointIds(obsoleteRoadwayPoints.map(_.id))
      (obsoleteNodePoints, obsoleteJunctionPoints)
    }

    def obsoleteNodePointsAndJunctionPoints(roadwayNumbersSection: Seq[Long]): (Seq[NodePoint], Seq[JunctionPoint]) = {
      val roadwayPointIds = roadwayPointDAO.fetchByRoadwayNumbers(roadwayNumbersSection).map(_.id)
      val startAddrMValue = roadwayDAO.fetchAllByRoadwayNumbers(roadwayNumbersSection.toSet).minBy(_.startAddrMValue).startAddrMValue
      val endAddrMValue = roadwayDAO.fetchAllByRoadwayNumbers(roadwayNumbersSection.toSet).maxBy(_.endAddrMValue).endAddrMValue

      val obsoleteNodePoints = nodePointDAO.fetchByRoadwayPointIds(roadwayPointIds)
        .filter(_.nodePointType == NodePointType.RoadNodePoint)
        .filterNot(n => (n.beforeAfter == BeforeAfter.After && n.addrM == startAddrMValue) || (n.beforeAfter == BeforeAfter.Before && n.addrM == endAddrMValue))

      // TODO Missing discontinuity cases
      val junctions = junctionDAO.fetchByIds(junctionPointDAO.fetchByRoadwayPointIds(roadwayPointIds).map(_.junctionId))
      val obsoleteJunctionPoints = junctions.flatMap { junction =>
        junctionPointDAO.fetchByJunctionIds(Seq(junction.id)) match {
          case junctionPoints if junctionPoints.exists(jp => RoadClass.RampsAndRoundaboutsClass.roads.contains(jp.roadNumber)) =>
            if (junctionPoints.groupBy(jp => (jp.roadNumber, jp.roadPartNumber)).keys.size == 1) junctionPoints
            else Seq.empty[JunctionPoint]
          case junctionPoints if !junctionPoints.forall(jp => RoadClass.RampsAndRoundaboutsClass.roads.contains(jp.roadNumber)) =>
            if (junctionPoints.groupBy(_.roadNumber).keys.size == 1) junctionPoints
            else Seq.empty[JunctionPoint]
        }
      }

      (obsoleteNodePoints, obsoleteJunctionPoints)
    }

    def expireJunctionsAndJunctionPoints(junctionPoints: Seq[JunctionPoint]): Seq[Junction] = {
      // Expire current junction points rows
      val junctionPointsIds = junctionPoints.map(_.id)
      logger.info(s"Expiring junction points : $junctionPointsIds")
      junctionPointDAO.expireById(junctionPointsIds)

      // Remove junctions that no longer have justification for the current network
      val obsoleteJunctions = junctionDAO.fetchByIds(junctionPointsIds).filter { junction =>
        RoadAddressFilters.obsoleteJunctions(junctionPointDAO.fetchByJunctionIds(Seq(junction.id)))
      }
      logger.info(s"Expiring junctions : ${obsoleteJunctions.map(_.id)}")
      junctionDAO.expireById(obsoleteJunctions.map(_.id))
      val obsoleteJunctionPointsOfNowExpiredJunctions = junctionPointDAO.fetchByJunctionIds(obsoleteJunctions.map(_.id))
      logger.info(s"Expiring junction points of expired junctions: ${obsoleteJunctionPointsOfNowExpiredJunctions.map(_.id)}")
      junctionPointDAO.expireById(obsoleteJunctionPointsOfNowExpiredJunctions.map(_.id))

      // Handle obsolete junction points of valid and obsolete junctions separately
      val (obsoleteJunctionPointsOfObsoleteJunctions, obsoleteJunctionPointsOfValidJunctions) =
        (junctionPoints ++ obsoleteJunctionPointsOfNowExpiredJunctions)
          .partition(jp => obsoleteJunctions.exists(j => j.id == jp.junctionId))

      // Create junction rows with end date and junction point rows with new junction id
      obsoleteJunctions.foreach(j => {
        val newJunctionId = junctionDAO.create(Seq(j.copy(id = NewIdValue, endDate = endDate, createdBy = Some(username)))).head
        junctionPointDAO.create(obsoleteJunctionPointsOfObsoleteJunctions.map(_.copy(id = NewIdValue,
          junctionId = newJunctionId, createdBy = Some(username))))
      })

      // Create junction point rows of the valid junctions
      // TODO Would be better not to expire these in the first place, since now we are creating exact copy of the previous ones
      junctionPointDAO.create(obsoleteJunctionPointsOfValidJunctions.map(_.copy(id = NewIdValue, createdBy = Some(username))))

      obsoleteJunctions
    }

    def expireNodes(nodePoints: Seq[NodePoint], junctions: Seq[Junction]): Unit = {

      // Expire obsolete node points
      logger.info(s"Expiring node points : ${nodePoints.map(_.id)}")
      nodePointDAO.expireById(nodePoints.map(_.id))

      val nodeNumbersToCheck = (junctions.filter(j => j.nodeNumber.isDefined).map(_.nodeNumber.get)
        ++ nodePoints.filter(np => np.nodeNumber.isDefined).map(_.nodeNumber.get)).distinct

      // Remove nodes that no longer have justification for the current network
      val obsoleteNodes = nodeDAO.fetchEmptyNodes(nodeNumbersToCheck)

      // Create node rows with end date
      obsoleteNodes.foreach(n => {
        nodeDAO.create(Seq(n.copy(id = NewIdValue, endDate = endDate, createdBy = Some(username)))).head
      })

      logger.info(s"Expiring nodes: ${obsoleteNodes.map(_.nodeNumber)}")
      nodeDAO.expireById(obsoleteNodes.map(_.id))
    }

    val (terminated, modified) = projectLinks
      .filter(pl => RoadClass.forJunctions.contains(pl.roadNumber.toInt))
      .partition(_.status == LinkStatus.Terminated)

    val terminatedRoadwayNumbers = terminated.map(_.roadwayNumber).distinct
    logger.info(s"Terminated roadway numbers : $terminatedRoadwayNumbers")
    val obsoletePointsFromTerminatedRoadways: (Seq[NodePoint], Seq[JunctionPoint]) = nodePointsAndJunctionPointsToExpire(terminatedRoadwayNumbers)

    val modifiedSections = modified.groupBy(projectLink => (projectLink.roadNumber, projectLink.roadPartNumber, projectLink.roadType))
    val obsoletePointsFromModifiedRoadways: Seq[(Seq[NodePoint], Seq[JunctionPoint])] = modifiedSections.mapValues { section: Seq[ProjectLink] =>
      val modifiedRoadwayNumbers = section.map(_.roadwayNumber).distinct
      logger.info(s"Modified roadway numbers: $modifiedRoadwayNumbers")
      obsoleteNodePointsAndJunctionPoints(modifiedRoadwayNumbers)
    }.values.toSeq

    val obsoleteNodePoints = obsoletePointsFromTerminatedRoadways._1 ++ obsoletePointsFromModifiedRoadways.flatMap(_._1)
    val obsoleteJunctionPoints = obsoletePointsFromTerminatedRoadways._2 ++ obsoletePointsFromModifiedRoadways.flatMap(_._2)

    logger.info(s"Obsolete node points : ${obsoleteNodePoints.map(_.id)}")
    logger.info(s"Obsolete junction points : ${obsoleteJunctionPoints.map(_.id)}")
    val obsoleteJunctions = expireJunctionsAndJunctionPoints(obsoleteJunctionPoints)
    expireNodes(obsoleteNodePoints, obsoleteJunctions)
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
          val junction = j.copy(id = NewIdValue, junctionNumber = None, nodeNumber = None, createdBy = Some(username))
          val newJunctionId = junctionDAO.create(Seq(junction)).head

          // Expire the current junction points
          val junctionPointsToExpire = junctionPointDAO.fetchByJunctionIds(Seq(j.id))
          junctionPointDAO.expireById(junctionPointsToExpire.map(_.id))

          // Create new junction points with new junction id
          junctionPointDAO.create(junctionPointsToExpire.map(_.copy(id = NewIdValue, junctionId = newJunctionId,
            createdBy = Some(username))))
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
    val nodePoints = nodePointDAO.fetchNodePointsByNodeNumber(Seq(nodeNumber))
    if (nodePoints.isEmpty) {
      val node = nodeDAO.fetchByNodeNumber(nodeNumber)
      if (node.isDefined) {

        // Terminate Node
        nodeDAO.expireById(Seq(node.get.id))
        nodeDAO.create(Seq(node.get.copy(id = NewIdValue, endDate = Some(DateTime.now), createdBy = Some(username))))

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
          nodePointDAO.create(Seq(np.copy(id = NewIdValue, nodeNumber = None, createdBy = Some(username))))

        }

        // If there are no node points left under the node, node can be terminated
        val nodeNumber = nodePointsToDetach.head.nodeNumber.get
        terminateNodeIfNoNodePoints(nodeNumber, username)

      }
      None
    }
  }

}
