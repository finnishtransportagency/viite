package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.LeftSide
import fi.liikennevirasto.viite.dao.BeforeAfter.{After, Before}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.{CalibrationPointLocation, CalibrationPointType}
import fi.liikennevirasto.viite.dao.NodePointType.RoadNodePoint
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.RoadAddressLink
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
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

  def addOrUpdate(node: Node, junctions: Seq[Junction], nodePoints: Seq[NodePoint], username: String = "-"): Unit = {
    def isObsoleteNode(junctions: Seq[Junction], nodePoints: Seq[NodePoint]): Boolean = {
      junctions.isEmpty && !nodePoints.exists(_.nodePointType == NodePointType.RoadNodePoint)
    }

    def updateNodePoints(nodePoints: Seq[NodePoint], values: Map[String, Any]): Unit = {
      //  This map `values` was added so other fields could be modified in the process
      val newNodeNumber = values.getOrElse("nodeNumber", None).asInstanceOf[Option[Long]]
      nodePointDAO.expireById(nodePoints.map(_.id))
      nodePointDAO.create(nodePoints.map(_.copy(id = NewIdValue, nodeNumber = newNodeNumber, createdBy = username)))
    }

      def updateJunctionsAndJunctionPoints(junctions: Seq[Junction], values: Map[String, Any]): Unit = {
      //  This map `values` was added so other fields could be modified in the process
      val newNodeNumber = values.getOrElse("nodeNumber", None).asInstanceOf[Option[Long]]
      val junctionNumber = values.getOrElse("junctionNumber", None).asInstanceOf[Option[Long]]
      val updateJunctionNumber = values.contains("junctionNumber")
      junctions.foreach { junction =>
        val newJunctionNumber = if (updateJunctionNumber) {
          junctionNumber
        } else { junction.junctionNumber }
        val junctionPoints = junctionPointDAO.fetchByJunctionIds(Seq(junction.id))
        junctionDAO.expireById(Seq(junction.id))
        junctionPointDAO.expireById(junctionPoints.map(_.id))
        val junctionId = junctionDAO.create(Seq(junction.copy(id = NewIdValue, nodeNumber = newNodeNumber, junctionNumber = newJunctionNumber, createdBy = username))).head
        junctionPointDAO.create(junctionPoints.map(_.copy(id = NewIdValue, junctionId = junctionId, createdBy = username)))
      }
    }

    withDynTransaction {
      val nodeNumber = addOrUpdateNode(node, isObsoleteNode(junctions, nodePoints), username)

      val currentNodePoints = nodePointDAO.fetchByNodeNumber(nodeNumber)
      val (_, nodePointsToDetach) = currentNodePoints.partition(nodePoint => nodePoints.map(_.id).contains(nodePoint.id))

      val roadNodePointsToDetach: Seq[NodePoint] = nodePointsToDetach.filter(_.nodePointType == NodePointType.RoadNodePoint)
      updateNodePoints(roadNodePointsToDetach, Map("nodeNumber" -> None))

      val nodePointsToAttach = nodePoints.filter(nodePoint => !currentNodePoints.map(_.id).contains(nodePoint.id))
      updateNodePoints(nodePointsToAttach, Map("nodeNumber" -> Some(nodeNumber)))

      val currentJunctions = junctionDAO.fetchJunctionByNodeNumber(nodeNumber)
      val (filteredJunctions, junctionsToDetach: Seq[Junction]) = currentJunctions.partition(junction => junctions.map(_.id).contains(junction.id))
      updateJunctionsAndJunctionPoints(junctionsToDetach, Map("nodeNumber" -> None, "junctionNumber" -> None))

      val junctionsToAttach = junctions.filter(junction => !currentJunctions.map(_.id).contains(junction.id))
      updateJunctionsAndJunctionPoints(junctionsToAttach, Map("nodeNumber" -> Some(nodeNumber)))

      val updatedJunctions = junctions.filterNot(junction => {
        filteredJunctions.exists { current =>
          current.id == junction.id && current.junctionNumber.getOrElse(-1) == junction.junctionNumber.getOrElse(-1)
        }
      }).filter(j => !junctionsToAttach.map(_.id).contains(j.id))

      updateJunctionsAndJunctionPoints(updatedJunctions, Map("nodeNumber" -> Some(nodeNumber)))
      if (isObsoleteNode(junctions, nodePoints)) {
        val old = nodeDAO.fetchById(node.id)
        nodeDAO.expireById(Seq(old.get.id))
        nodeDAO.create(Seq(old.get.copy(id = NewIdValue, endDate = Some(node.startDate.minusDays(1)))), username)
      } else {
        calculateNodePointsForNode(nodeNumber, username)
      }
      publishNodes(Seq(nodeNumber), username)
    }
  }

  def addOrUpdateNode(node: Node, isObsoleteNode: Boolean = false, username: String = "-"): Long = {

    withDynTransactionNewOrExisting {
      if (node.id == NewIdValue) {
        nodeDAO.create(Seq(node), username).headOption.get
      } else {
        val old = nodeDAO.fetchById(node.id)
        if (old.isDefined) {
          if (!isObsoleteNode) {
            val originalStartDate = old.get.startDate.withTimeAtStartOfDay
            val startDate = node.startDate.withTimeAtStartOfDay

            // Check that new start date is not earlier than before
            if (startDate.getMillis < originalStartDate.getMillis) {
              throw new Exception(NodeStartDateUpdateErrorMessage)
            }

            if (node.name != old.get.name || old.get.nodeType != node.nodeType || originalStartDate != startDate || old.get.coordinates != node.coordinates) {

              // Invalidate old one
              nodeDAO.expireById(Seq(old.get.id))

              if (old.get.nodeType != node.nodeType && originalStartDate != startDate) {
                // Create a new history layer when the node type has changed
                nodeDAO.create(Seq(old.get.copy(id = NewIdValue, endDate = Some(node.startDate.minusDays(1)))), username)
              }

              //  Create new node
              nodeDAO.create(Seq(node.copy(id = NewIdValue)), username)
            }
          }
          old.get.nodeNumber
        } else {
          throw new Exception(NodeNotFoundErrorMessage)
        }
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

  def enrichNodePointCoordinates(roadAddressLinks: Seq[RoadAddressLink], nodePoints: Seq[NodePoint]): Seq[NodePoint] = {
      nodePoints.map { np =>
        np.copy(coordinates = np.beforeAfter match {
          case Before if roadAddressLinks.exists(_.endAddressM == np.addrM) => roadAddressLinks.find(_.endAddressM == np.addrM).get.endPoint
          case After if roadAddressLinks.exists(_.startAddressM == np.addrM) => roadAddressLinks.find(_.startAddressM == np.addrM).get.startingPoint
          case _ => np.coordinates
        })
      }
  }

  def enrichJunctionPointCoordinates(roadAddressLinks: Seq[RoadAddressLink], jPoints: Seq[JunctionPoint]): Seq[JunctionPoint] = {
    jPoints.map { jp =>
      jp.copy(coordinates = jp.beforeAfter match {
        case Before if roadAddressLinks.exists(_.endAddressM == jp.addrM) => roadAddressLinks.find(_.endAddressM == jp.addrM).get.endPoint
        case After if roadAddressLinks.exists(_.startAddressM == jp.addrM) => roadAddressLinks.find(_.startAddressM == jp.addrM).get.startingPoint
        case _ => jp.coordinates
      })
    }
  }

  def getNodesWithJunctionByBoundingBox(boundingRectangle: BoundingRectangle, raLinks: Seq[RoadAddressLink]): Map[Node, (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]])] = {
    withDynSession {
      time(logger, "Fetch nodes with junctions") {
        val nodes = nodeDAO.fetchByBoundingBox(boundingRectangle)
        val nodePoints = nodePointDAO.fetchByNodeNumbers(nodes.map(_.nodeNumber))
        val junctions = junctionDAO.fetchJunctionsByNodeNumbers(nodes.map(_.nodeNumber))
        val junctionPoints = junctionPointDAO.fetchByJunctionIds(junctions.map(_.id))

        val groupedRoadLinks = raLinks.groupBy(_.roadwayNumber)
        val nodePointsWithCoords = nodePoints.groupBy(_.roadwayNumber).par.flatMap { case (k, v) =>
          groupedRoadLinks.get(k).map(rls => enrichNodePointCoordinates(rls, v)).getOrElse(v)
        }.toSeq.seq

        val junctionPointsWithCoords = junctionPoints.groupBy(_.roadwayNumber).par.flatMap { case (k, v) =>
          groupedRoadLinks.get(k).map(rls => enrichJunctionPointCoordinates(rls, v)).getOrElse(v)
        }.toSeq.seq

        nodes.map {
          node =>
            (node,
              (
                nodePointsWithCoords.filter(np => np.nodeNumber.isDefined && np.nodeNumber.get == node.nodeNumber),
                junctions.filter(j => j.nodeNumber.isDefined && j.nodeNumber.get == node.nodeNumber).map {
                  junction =>
                    (
                      junction, junctionPointsWithCoords.filter(_.junctionId == junction.id)
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
        nodePointDAO.fetchTemplates().filter(template => authorizedElys.contains(template.elyCode))
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

  def getJunctionTemplatesById(id: Long): Option[JunctionTemplate] = {
    withDynSession {
      time(logger, "Fetch junction template by id") {
        junctionDAO.fetchJunctionTemplateById(id)
      }
    }
  }

  def getJunctionTemplates(authorizedElys: Seq[Int]): Seq[JunctionTemplate] = {
    withDynSession {
      time(logger, "Fetch Junction templates") {
        junctionDAO.fetchTemplates().filter(jt => authorizedElys.contains(jt.elyCode))
      }
    }
  }

  def getTemplatesByBoundingBox(boundingRectangle: BoundingRectangle): (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]]) = {
    time(logger, "Fetch NodePoint and Junction + JunctionPoint templates") {
      val junctionPoints = junctionPointDAO.fetchByBoundingBox(boundingRectangle)
      val junctions = junctionDAO.fetchByIds(junctionPoints.map(_.junctionId))
      val nodePoints = nodePointDAO.fetchTemplatesByBoundingBox(boundingRectangle)
      (nodePoints, junctions.map { junction => (junction, junctionPoints.filter(_.junctionId == junction.id)) }.toMap)
    }
  }


  def handleJunctionTemplates(roadwayChanges: List[ProjectRoadwayChange], projectLinks: Seq[ProjectLink], mappedRoadwayNumbers: Seq[ProjectRoadLinkChange], username: String = "-"): Unit = {
    def getRoadwayPointId(roadwayNumber: Long, address: Long, username: String): Long = {
      val existingRoadwayPoint = roadwayPointDAO.fetch(roadwayNumber, address)
      val rwPoint = if (existingRoadwayPoint.nonEmpty) {
        existingRoadwayPoint.get.id
      } else {
        roadwayPointDAO.create(roadwayNumber, address, username)
      }
      rwPoint
    }

    /**
      * @return the junction id only if the road address r is connected to the road address projectLink
      */
    def getJunctionIdIfConnected(roadPoint: Point, projectLinkPoint: Point, roadwayNumber: Long, addr: Long, pos: BeforeAfter) = {
      if (roadPoint.connected(projectLinkPoint)) {
        junctionPointDAO.fetchByRoadwayPoint(roadwayNumber, addr, pos).map(_.junctionId)
      } else None
    }

    /**
      * Creates if needed: new roadway point, new junction, new junction point and new calibration point as JunctioncCP
      * @param target                road address for the new junction point and calibration point
      * @param pos              position of the junction point
      * @param addr             address of the junction poitn
      * @param roadsTo          roads ending in the current road address r
      * @param roadsFrom        roads starting in the current road address r
      */
    def createJunctionAndJunctionPointIfNeeded(target: BaseRoadAddress, existingJunctionId: Option[Long] = None, pos: BeforeAfter, addr: Long,
                                               roadsTo: Seq[BaseRoadAddress] = Seq.empty[BaseRoadAddress], roadsFrom: Seq[BaseRoadAddress] = Seq.empty[BaseRoadAddress]): Option[(Long, Long, CalibrationPointLocation)] = {
      val roadwayPointId = getRoadwayPointId(target.roadwayNumber, addr, username)
      val existingJunctionPoint = junctionPointDAO.fetchByRoadwayPoint(target.roadwayNumber, addr, pos)

      if (existingJunctionPoint.isEmpty) {
        val junctionId = existingJunctionId match {
          case Some(id) => id
          case _ =>
            roadsTo.flatMap(to => junctionPointDAO.fetchByRoadwayPoint(to.roadwayNumber, to.endAddrMValue, BeforeAfter.Before)).headOption.map(_.junctionId)
              .orElse(roadsFrom.flatMap(from => junctionPointDAO.fetchByRoadwayPoint(from.roadwayNumber, from.startAddrMValue, BeforeAfter.After)).headOption.map(_.junctionId)) match {
              case Some(id) => id
              case _ =>
                logger.info(s"Creating Junction for roadwayNumber: ${target.roadwayNumber}, (${target.roadNumber}, ${target.roadPartNumber}, ${target.track}, $addr)")
                junctionDAO.create(Seq(Junction(NewIdValue, None, None, target.startDate.get, None, DateTime.now, None, username, Some(DateTime.now)))).head
            }
        }

        logger.info(s"Creating JunctionPoint with junctionId: $junctionId, beforeAfter: ${pos.value}, addrM: $addr for roadwayNumber: ${target.roadwayNumber}, (${target.roadNumber}, ${target.roadPartNumber}, ${target.track}, $addr)")
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, pos, roadwayPointId, junctionId, None, None, DateTime.now, None, username, Some(DateTime.now), target.roadwayNumber, addr, target.roadNumber, target.roadPartNumber, target.track, target.discontinuity)))
        Some((roadwayPointId, target.linkId, CalibrationPointLocation.apply(pos)))
      } else {
        None
      }
    }

    time(logger, "Handling junction point templates") {
      val nonTerminatedLinks: Seq[BaseRoadAddress] = projectLinks.filter(pl => RoadClass.forJunctions.contains(pl.roadNumber.toInt) && pl.status != LinkStatus.Terminated)
      val junctionCalibrationPoints: Set[(Long, Long, CalibrationPointLocation)] = nonTerminatedLinks.flatMap { projectLink =>
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
              junctionPointDAO.fetchByRoadwayPoint(projectLink.roadwayNumber, projectLink.startAddrMValue, BeforeAfter.After)
            else {
              junctionPointDAO.fetchByRoadwayPoint(originalLink.get.originalRoadwayNumber, originalLink.get.newStartAddr, BeforeAfter.Before)
            }
          } else None
        }

        val existingLastJunctionPoint = {
          if (originalLink.nonEmpty) {
            if (!junctionReversed)
              junctionPointDAO.fetchByRoadwayPoint(projectLink.roadwayNumber, projectLink.startAddrMValue, BeforeAfter.Before)
            else {
              junctionPointDAO.fetchByRoadwayPoint(originalLink.get.originalRoadwayNumber, originalLink.get.newEndAddr, BeforeAfter.After)
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

        // handle junction points for each project links
        val startCp: Option[(Long, Long, CalibrationPointLocation)] = if ((roadsToHead ++ roadsFromHead).nonEmpty) {
          createJunctionAndJunctionPointIfNeeded(projectLink, pos = BeforeAfter.After, addr = projectLink.startAddrMValue,
            roadsTo = roadsToHead.filter(_.endPoint.connected(projectLink.startingPoint)),
            roadsFrom = roadsFromHead.filter(_.startingPoint.connected(projectLink.startingPoint)))
        } else { None }

        val endCp: Option[(Long, Long, CalibrationPointLocation)] = if ((roadsToTail ++ roadsFromTail).nonEmpty) {
          createJunctionAndJunctionPointIfNeeded(projectLink, pos = BeforeAfter.Before, addr = projectLink.endAddrMValue,
            roadsTo = roadsToTail.filter(_.endPoint.connected(projectLink.endPoint)),
            roadsFrom = roadsFromTail.filter(_.startingPoint.connected(projectLink.endPoint)))
        } else { None }

        // handle junction points for other roads, connected to each project link
        val toHeadCp = roadsToHead.flatMap { roadAddress: BaseRoadAddress =>
          val junctionId = getJunctionIdIfConnected(roadAddress.endPoint, projectLink.startingPoint, roadwayNumber = projectLink.roadwayNumber, addr = projectLink.startAddrMValue, pos = BeforeAfter.After)
          createJunctionAndJunctionPointIfNeeded(roadAddress, junctionId, BeforeAfter.Before, roadAddress.endAddrMValue)
        }

        val fromHeadCp = roadsFromHead.flatMap { roadAddress: BaseRoadAddress =>
          val junctionId = getJunctionIdIfConnected(roadAddress.startingPoint, projectLink.startingPoint, roadwayNumber = projectLink.roadwayNumber, addr = projectLink.startAddrMValue, pos = BeforeAfter.After)
          createJunctionAndJunctionPointIfNeeded(roadAddress, junctionId, BeforeAfter.After, roadAddress.startAddrMValue)
        }

        val toTailCp = roadsToTail.flatMap { roadAddress: BaseRoadAddress =>
          val junctionId = getJunctionIdIfConnected(roadAddress.endPoint, projectLink.endPoint, roadwayNumber = projectLink.roadwayNumber, addr = projectLink.endAddrMValue, pos = BeforeAfter.Before)
          createJunctionAndJunctionPointIfNeeded(roadAddress, junctionId, BeforeAfter.Before, roadAddress.endAddrMValue)
        }

        val fromTailCp = roadsFromTail.flatMap { roadAddress: BaseRoadAddress =>
          val junctionId = getJunctionIdIfConnected(roadAddress.startingPoint, projectLink.endPoint, roadwayNumber = projectLink.roadwayNumber, addr = projectLink.endAddrMValue, pos = BeforeAfter.Before)
          createJunctionAndJunctionPointIfNeeded(roadAddress, junctionId, BeforeAfter.After, roadAddress.startAddrMValue)
        }

        startCp ++ endCp ++ toHeadCp ++ fromHeadCp ++ toTailCp ++ fromTailCp
      }.toSet

      // handle junction calibration points
      junctionCalibrationPoints.foreach { case (rwPoint, linkId, calibrationPointLocation) =>
        CalibrationPointsUtils.createCalibrationPointIfNeeded(rwPoint, linkId, calibrationPointLocation, CalibrationPointType.JunctionPointCP, username)
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
  def handleNodePointTemplates(roadwayChanges: List[ProjectRoadwayChange], projectLinks: Seq[ProjectLink], mappedRoadwayNumbers: Seq[ProjectRoadLinkChange], username: String = "-"): Unit = {
    time(logger, "Handling node point templates") {
      try {
        @scala.annotation.tailrec
        def continuousNodeSections(seq: Seq[ProjectLink], roadTypesSection: Seq[Seq[ProjectLink]]): (Seq[ProjectLink], Seq[Seq[ProjectLink]]) = if (seq.isEmpty) {
          (Seq(), roadTypesSection)
        } else {
          val roadType = seq.headOption.map(_.roadType.value).getOrElse(0)
          val continuousProjectLinks = seq.takeWhile(pl => pl.roadType.value == roadType)
          continuousNodeSections(seq.drop(continuousProjectLinks.size), roadTypesSection :+ continuousProjectLinks)
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
              else roadwayPointDAO.create(headLink.roadwayNumber, headLink.startAddrMValue, username)
            }
            val lastRoadwayPointId = {
              val existingRoadwayPoint = roadwayPointDAO.fetch(lastLink.roadwayNumber, lastLink.endAddrMValue)
              if (existingRoadwayPoint.nonEmpty)
                existingRoadwayPoint.get.id
              else roadwayPointDAO.create(lastLink.roadwayNumber, lastLink.endAddrMValue, username)
            }

            /*  Handle update of NODE_POINT in reverse cases  */
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
                  nodePointDAO.fetchNodePointsTemplates(Set(originalLink.get.originalRoadwayNumber, headLink.roadwayNumber)).find(np => np.beforeAfter == After && np.addrM == headLink.startAddrMValue)
                } else {
                  nodePointDAO.fetchNodePointsTemplates(Set(originalLink.get.originalRoadwayNumber, headLink.roadwayNumber)).find(np => np.beforeAfter == Before && np.addrM == originalLink.get.newStartAddr)
                }
              } else None
            }

            val existingLastNodePoint = {
              val originalLink = mappedRoadwayNumbers.find(mpr => lastLink.startAddrMValue == mpr.newStartAddr && lastLink.endAddrMValue == mpr.newEndAddr && mpr.newRoadwayNumber == lastLink.roadwayNumber)
              if (originalLink.nonEmpty) {
                if (!endNodeReversed) {
                  nodePointDAO.fetchNodePointsTemplates(Set(originalLink.get.originalRoadwayNumber, lastLink.roadwayNumber)).find(np => np.beforeAfter == Before && np.addrM == lastLink.endAddrMValue)
                } else {
                  nodePointDAO.fetchNodePointsTemplates(Set(originalLink.get.originalRoadwayNumber, lastLink.roadwayNumber)).find(np => np.beforeAfter == After && np.addrM == originalLink.get.newEndAddr)
                }
              } else None
            }

            if (existingHeadNodePoint.nonEmpty) {
              if (startNodeReversed) {
                nodePointDAO.update(Seq(existingHeadNodePoint.head.copy(beforeAfter = BeforeAfter.switch(existingHeadNodePoint.head.beforeAfter))))
              }
            } else {
              nodePointDAO.create(Seq(NodePoint(NewIdValue, BeforeAfter.After, headRoadwayPointId, None, RoadNodePoint, None, None, DateTime.now(), None, username, Some(DateTime.now()), headLink.roadwayNumber, headLink.startAddrMValue, headLink.roadNumber, headLink.roadPartNumber, headLink.track, headLink.ely)))
            }

            if (existingLastNodePoint.nonEmpty) {
              if (endNodeReversed) {
                nodePointDAO.update(Seq(existingLastNodePoint.head.copy(beforeAfter = BeforeAfter.switch(existingLastNodePoint.head.beforeAfter))))
              }
            } else {
              nodePointDAO.create(Seq(NodePoint(NewIdValue, BeforeAfter.Before, lastRoadwayPointId, None, RoadNodePoint, None, None, DateTime.now(), None, username, Some(DateTime.now()), lastLink.roadwayNumber, lastLink.endAddrMValue, lastLink.roadNumber, lastLink.roadPartNumber, lastLink.track, lastLink.ely)))
            }
          }
        }.toSeq
      } catch {
        case ex: Exception =>
          logger.error("Failed to handle node points.", ex)
          throw ex
      }
    }
  }

  def getNodePointTemplatesByBoundingBox(boundingRectangle: BoundingRectangle, raLinks: Seq[RoadAddressLink]): Seq[NodePoint] = {
    withDynSession {
      time(logger, "Fetch nodes point templates") {

        val nodePointTemplate = nodePointDAO.fetchTemplatesByBoundingBox(boundingRectangle)
        val groupedRoadLinks = raLinks.groupBy(_.roadwayNumber)
        nodePointTemplate.groupBy(_.roadwayNumber).par.flatMap { case (k, v) =>
          groupedRoadLinks.get(k).map(rls => enrichNodePointCoordinates(rls, v)).getOrElse(v)
        }.toSeq.seq
      }
    }
  }

  def getJunctionTemplatesByBoundingBox(boundingRectangle: BoundingRectangle, raLinks: Seq[RoadAddressLink]): Map[JunctionTemplate, Seq[JunctionPoint]] = {
    withDynSession {
      time(logger, "Fetch junction templates") {
        val junctions: Seq[JunctionTemplate] = junctionDAO.fetchTemplatesByBoundingBox(boundingRectangle)
        val junctionPoints: Seq[JunctionPoint] = junctionPointDAO.fetchByJunctionIds(junctions.map(_.id))

        val groupedRoadLinks: Map[Long, Seq[RoadAddressLink]] = raLinks.groupBy(_.roadwayNumber)

        val junctionPointsWithCoords = junctionPoints.groupBy(_.roadwayNumber).par.flatMap { case (k, v) => 
          groupedRoadLinks.get(k).map(rls => enrichJunctionPointCoordinates(rls, v)).getOrElse(v)
        }.toSeq.seq

        junctions.map {
          junction => (junction, junctionPointsWithCoords.filter(_.junctionId == junction.id))
        }.toMap
      }
    }
  }

  def expireObsoleteNodesAndJunctions(projectLinks: Seq[ProjectLink], endDate: Option[DateTime], username: String = "-"): Seq[JunctionPoint] = {

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
      logger.info(s"Modified roadway number: ${roadwayNumbersSection.toList}")
      val roadwayPointIds = roadwayPointDAO.fetchByRoadwayNumbers(roadwayNumbersSection).map(_.id)
      val sortedRoadways = roadwayDAO.fetchAllByRoadwayNumbers(roadwayNumbersSection.toSet).filterNot(_.track == LeftSide).sortBy(_.startAddrMValue)
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
          case _ => Seq.empty[JunctionPoint]
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

    def continuousSectionByRoadType(section: Seq[ProjectLink], continuousSection: Seq[Seq[ProjectLink]] = Seq.empty): Seq[Seq[ProjectLink]] = {
      if (section.isEmpty)
        continuousSection
      else {
        val roadType = section.head.roadType
        val sectionByRoadType: Seq[ProjectLink] = section.takeWhile(p => p.roadType == roadType)
        continuousSectionByRoadType(section.drop(sectionByRoadType.size), continuousSection :+ sectionByRoadType)
      }
    }

    val filteredProjectLinks = projectLinks
      .filter(pl => RoadClass.forJunctions.contains(pl.roadNumber.toInt))

    val terminated = filteredProjectLinks.filter(_.status == LinkStatus.Terminated)

    val terminatedRoadwayNumbers = terminated.map(_.roadwayNumber).distinct
    val (terminatedNodePoints, terminatedJunctionPoints): (Seq[NodePoint], Seq[JunctionPoint]) = getNodePointsAndJunctionPointsByTerminatedRoadwayNumbers(terminatedRoadwayNumbers)

    val projectLinkSections = filteredProjectLinks.groupBy(projectLink => (projectLink.roadNumber, projectLink.roadPartNumber))
    val obsoletePointsFromModifiedRoadways: Seq[(Seq[NodePoint], Seq[JunctionPoint])] = projectLinkSections.mapValues { section: Seq[ProjectLink] =>
      continuousSectionByRoadType(section.sortBy(_.startAddrMValue)).map { continuousSection =>
        val modifiedRoadwayNumbers = continuousSection.map(_.roadwayNumber).distinct
        getNodePointsAndJunctionPointsByModifiedRoadwayNumbers(modifiedRoadwayNumbers, terminatedJunctionPoints)
      }
    }.values.flatten.toSeq

    val obsoleteNodePoints = terminatedNodePoints ++ obsoletePointsFromModifiedRoadways.flatMap(_._1)
    val obsoleteJunctionPoints = terminatedJunctionPoints ++ obsoletePointsFromModifiedRoadways.flatMap(_._2)
    val expiredJunctions = expireJunctionsAndJunctionPoints(obsoleteJunctionPoints)
    expireNodesAndNodePoints(obsoleteNodePoints, expiredJunctions)
    obsoleteJunctionPoints.distinct
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

  def publishNodes(nodeNumbers: Seq[Long], username: String): Unit = {
    logger.debug("setPublishingInfoForNodes: " + nodeNumbers.mkString(","))
    nodeNumbers.foreach(nodeNumber => {
      val id = nodeDAO.fetchLatestId(nodeNumber)
      nodeDAO.publish(id.getOrElse(0), username)
    })
  }

  def calculateNodePointsForNodes(nodeNumbers: Seq[Long], username: String): Unit = {
    logger.debug("calculateNodePointsForNodes: " + nodeNumbers.mkString(","))
    nodeNumbers.foreach(nodeNumber => {
      calculateNodePointsForNode(nodeNumber, username)
    })
  }

  /**
    * Calculates node points for all the road parts of the node.
    *
    * - Handle one node at the time
    * - Expire node points connected to node with type 2 (NODEPOINT.TYPE = 2)
    * - Go through the road parts (road numbers 1 - 19999 and 40000 - 69999) of the node one by one (JUNCTION -> JUNCTION_POINT -> ROADWAY_POINT -> ROADWAY)
    * - Fetch node points count for road and road part NODE -> NODE_POINT.TYPE = 1) >> ROADWAY_POINT >> ROADWAY.ROAD_NUMBER, ROADWAY.ROAD_PART_NUMBER
    * - If points exists, no calculated nodepoints. Go to the next road + roadpart.
    * - If no points exist, then calculated node point. Calculated node point only for lane 0 or 1.
    * - If road part is linked only to one(1) junction, calculated node points (before and after) are formed/based with handled
    *   road part's junctions (used same ROADWAY_POINT row)
    * - If node is linked with several junctions, calculated node points and corresponding roadway_point are formed (before and after)
    *   with average ADDR_M value, When calculating addrMValueAVG, also roadway_points in lane to are included to average.
    *
    * @param nodeNumber , username
    */
  def calculateNodePointsForNode(nodeNumber: Long, username: String): Option[String] = {
    nodePointDAO.expireByNodeNumberAndType(nodeNumber, NodePointType.CalculatedNodePoint)
    /* - Go through the road parts (road numbers 1-19999 and 40000-69999) of the node one by one
     */
    val roadPartInfos = nodePointDAO.fetchRoadPartsInfoForNode(nodeNumber)
    var nodePointCount = 0
    var lastRoadNumber = 0: Long
    var lastRoadPartNumber = 0: Long
    logger.debug("Start calculateNodePointsForNode: " + nodeNumber)
    roadPartInfos.foreach { roadPartInfo =>
      if (lastRoadNumber != roadPartInfo.roadNumber || lastRoadPartNumber != roadPartInfo.roadPartNumber) {
        // set nodePointCount to zero so that road or road part has changed in for loop and we need to create nodepoint
        nodePointCount = 0
      }
      lastRoadNumber = roadPartInfo.roadNumber
      lastRoadPartNumber = roadPartInfo.roadPartNumber
      val countNodePointsForRoadAndRoadPart = nodePointDAO.fetchNodePointsCountForRoadAndRoadPart(roadPartInfo.roadNumber, roadPartInfo.roadPartNumber, roadPartInfo.beforeAfter, nodeNumber)
      /*
         If the road part doesn't have any "road node points", calculate node point by taking the average of the
         addresses of all junction points on both tracks and add this "calculated node point" on track 0 or 1
       */
      if (countNodePointsForRoadAndRoadPart.get == 0 && (nodePointCount < 2)) {
        if (logger.isDebugEnabled) {
          // generate query debug for what are the input values for average calculation
          nodePointDAO.fetchAddrMForAverage(roadPartInfo.roadNumber, roadPartInfo.roadPartNumber)
        }
        val addrMValueAVG = nodePointDAO.fetchAverageAddrM(roadPartInfo.roadNumber, roadPartInfo.roadPartNumber, nodeNumber)
        val beforeAfterValue = if (roadPartInfo.endAddrM == addrMValueAVG) {
          BeforeAfter.Before
        } else if (roadPartInfo.startAddrM == addrMValueAVG) {
          BeforeAfter.After
        } else {
          BeforeAfter.UnknownBeforeAfter
        }
        val existingRoadwayPoint = roadwayPointDAO.fetch(roadPartInfo.roadwayNumber, addrMValueAVG)
        val rwPoint = if (existingRoadwayPoint.nonEmpty) {
          existingRoadwayPoint.get.id
        } else {
          roadwayPointDAO.create(roadPartInfo.roadwayNumber, addrMValueAVG, username)
        }
        if (beforeAfterValue == BeforeAfter.UnknownBeforeAfter) {
          nodePointDAO.insertCalculatedNodePoint(rwPoint, BeforeAfter.Before, nodeNumber, username)
          nodePointDAO.insertCalculatedNodePoint(rwPoint, BeforeAfter.After, nodeNumber, username)
          nodePointCount = nodePointCount + 2
        } else {
          nodePointDAO.insertCalculatedNodePoint(rwPoint, beforeAfterValue, nodeNumber, username)
          nodePointCount = nodePointCount + 1
        }
      }
    }
    None
  }

}
