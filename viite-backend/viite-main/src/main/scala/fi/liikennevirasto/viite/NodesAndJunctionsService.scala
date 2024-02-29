package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.RoadAddressFilters.{connectingBothHeads, continuousTopology, reversedConnectingBothHeads, reversedContinuousTopology}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.RoadAddressLink
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.model.{BeforeAfter, CalibrationPointLocation, CalibrationPointType, Discontinuity, NodePointType, NodeType, RoadAddressChangeType, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabase
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.util.control.NonFatal

class NodesAndJunctionsService(roadwayDAO: RoadwayDAO, roadwayPointDAO: RoadwayPointDAO, linearLocationDAO: LinearLocationDAO, nodeDAO: NodeDAO, nodePointDAO: NodePointDAO, junctionDAO: JunctionDAO, junctionPointDAO: JunctionPointDAO, roadwayChangesDAO: RoadwayChangesDAO, projectReservedPartDAO: ProjectReservedPartDAO) {

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def withDynTransactionNewOrExisting[T](f: => T): T = PostGISDatabase.withDynTransactionNewOrExisting(f)

  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)

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
        } else {
          junction.junctionNumber
        }
        val oldJunctionPoints = junctionPointDAO.fetchByJunctionIds(Seq(junction.id))
        junctionDAO.expireById(Seq(junction.id))
        junctionPointDAO.expireById(oldJunctionPoints.map(_.id))
        val junctionId = junctionDAO.create(Seq(junction.copy(id = NewIdValue, nodeNumber = newNodeNumber, junctionNumber = newJunctionNumber, createdBy = username))).head
        val updatedJunctionPoints =
          if (junction.junctionPoints.isDefined) {
            junction.junctionPoints.get.map { jp =>
              val oldJunctionPoint = oldJunctionPoints.find(oldJunctionPoint => oldJunctionPoint.id == jp.id)
              if (oldJunctionPoint.isDefined && oldJunctionPoint.get.addrM != jp.addrM) {
                handleJunctionPointUpdate(junctionId, oldJunctionPoint.get, jp, username)
              } else {
                jp.copy(id = NewIdValue, junctionId = junctionId, createdBy = username)
              }
            }
          } else {
            oldJunctionPoints.map(ojp => ojp.copy(id = NewIdValue, junctionId = junctionId, createdBy = username))
          }
        junctionPointDAO.create(updatedJunctionPoints)
      }
    }

    def updateJunctionPoints(junctionPoints: Iterable[JunctionPoint]): Unit = {
      val junctionPointsByJunction = junctionPoints.groupBy(_.junctionId)
      junctionPointsByJunction.keys.map(junctionId => {
        val junctionPoints = junctionPointsByJunction(junctionId)
        val oldJunctionPoints = junctionPointDAO.fetchByJunctionIds(Seq(junctionId))
        junctionPoints.map(jp => {
          val oldJunctionPoint = oldJunctionPoints.find(ojp => ojp.id == jp.id).getOrElse({
            logger.error(s"Failed to update junction point ${jp.toStringWithFields}. Old junction point not found.")
            throw new Exception("Liittymäkohdan osoitteen päivitys epäonnistui. Päivitettävää liittymäkohtaa ei löytynyt.")
          })
          if (oldJunctionPoint.addrM != jp.addrM) {
            junctionPointDAO.expireById(Seq(oldJunctionPoint.id))
            val updated = handleJunctionPointUpdate(junctionId, oldJunctionPoint, jp, username)
            junctionPointDAO.create(Seq(updated))
          }
        })
      })
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

      val unchangedJunctions = junctions.filterNot(junction => (junctionsToDetach ++ junctionsToAttach ++ updatedJunctions).map(_.id).contains(junction.id))
      val unchangedJunctionsJunctionPoints = unchangedJunctions.flatMap(junction => junction.junctionPoints.get)
      updateJunctionPoints(unchangedJunctionsJunctionPoints)

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

  /**
    * Check if any of the junction points is on a reserved road part (VIITE-2518).
    * @param junctionPointIds Ids of the junction points to be checked.
    * @return True if any of the checked junction points belongs to a reserved road part. False else.
    */

  def areJunctionPointsOnReservedRoadPart(junctionPointIds: Seq[Long]): Boolean = {
    withDynSession {
      val junctionPoints = junctionPointDAO.fetchByIds(junctionPointIds)
      val roadwayPoints = junctionPoints.map(jp => roadwayPointDAO.fetch(jp.roadwayPointId))
      val roadwayNumbers = roadwayPoints.map(rwp => rwp.roadwayNumber).toSet
      val roadways = roadwayDAO.fetchAllByRoadwayNumbers(roadwayNumbers)
      val reservedRoads = roadways.map(roadway => projectReservedPartDAO.fetchReservedRoadPart(roadway.roadPart))
      !reservedRoads.forall(_.isEmpty)
    }
  }

  // TODO remove this function and its usages when VIITE-2524 gets implemented
  def areJunctionPointsOnRoadwayChangingSpot(junctionPointIds: Seq[Long]): Boolean = {
    withDynSession {
      val junctionPoints = junctionPointDAO.fetchByIds(junctionPointIds)
      val roadwayNumbers = junctionPoints.map(jp => jp.roadwayNumber).toSet
      if (roadwayNumbers.size < 2)
        false
      else
        true
    }
  }

  def areJunctionPointsOnAdministrativeClassChangingSpot(junctionPointIds: Seq[Long]): Boolean = {
    withDynSession {
      val junctionPoints = junctionPointDAO.fetchByIds(junctionPointIds)
      val roadwayNumbers = junctionPoints.map(jp => jp.roadwayNumber).toSet
      if (roadwayNumbers.size < 2)
        false
      else {
        val roadways = roadwayDAO.fetchAllByRoadwayNumbers(roadwayNumbers)
        val adminClasses = roadways.map(rw => rw.administrativeClass).toSet
        if (adminClasses.size > 1)
          true
        else
          false
      }
    }
  }

  private def handleJunctionPointUpdate(junctionId: Long, oldJunctionPoint: JunctionPoint, newJunctionPoint: JunctionPoint, username: String): JunctionPoint = {
    // Update JunctionPoint and CalibrationPoint addresses by pointing them to another RoadwayPoint
    val roadwayPointId = getRoadwayPointId(newJunctionPoint.roadwayNumber, newJunctionPoint.addrM, username)
    CalibrationPointsUtils.updateCalibrationPointAddress(oldJunctionPoint.roadwayPointId, roadwayPointId, username)

    newJunctionPoint.copy(id = NewIdValue, junctionId = junctionId, roadwayPointId = roadwayPointId, createdBy = username)
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

  def getNodesForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[NodeForRoadAddressBrowser] = {
    withDynSession {
      nodeDAO.fetchNodesForRoadAddressBrowser(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)
    }
  }

  def getJunctionsForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[JunctionForRoadAddressBrowser] = {
    withDynSession {
      junctionDAO.fetchJunctionsForRoadAddressBrowser(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)
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
          case BeforeAfter.Before if roadAddressLinks.exists(_.endAddressM == np.addrM) => roadAddressLinks.find(_.endAddressM == np.addrM).get.endPoint
          case BeforeAfter.After if roadAddressLinks.exists(_.startAddressM == np.addrM) => roadAddressLinks.find(_.startAddressM == np.addrM).get.startingPoint
          case _ => np.coordinates
        })
      }
  }

  def enrichJunctionPointCoordinates(roadAddressLinks: Seq[RoadAddressLink], jPoints: Seq[JunctionPoint]): Seq[JunctionPoint] = {
    jPoints.map { jp =>
      jp.copy(coordinates = jp.beforeAfter match {
        case BeforeAfter.Before if roadAddressLinks.exists(_.endAddressM == jp.addrM) => roadAddressLinks.find(_.endAddressM == jp.addrM).get.endPoint
        case BeforeAfter.After if roadAddressLinks.exists(_.startAddressM == jp.addrM) => roadAddressLinks.find(_.startAddressM == jp.addrM).get.startingPoint
        case _ => jp.coordinates
      })
    }
  }

  def getNodesWithJunctionByBoundingBox(boundingRectangle: BoundingRectangle, raLinks: Seq[RoadAddressLink]): Map[Node, (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]])] = {
    withDynSession {
      time(logger, "Fetch nodes with junctions") {
        val junctionsByBoundingBox = getJunctionsByBoundingBox(boundingRectangle, raLinks)
        val nodesByBoundingBox = nodeDAO.fetchByBoundingBox(boundingRectangle)

        val nodes = (nodesByBoundingBox ++ junctionsByBoundingBox.flatMap { case (junction, _) if junction.nodeNumber.isDefined => nodeDAO.fetchByNodeNumber(junction.nodeNumber.get) }).distinct

        val nodePoints = nodePointDAO.fetchByNodeNumbers(nodes.map(_.nodeNumber))
        val junctions = junctionDAO.fetchJunctionsByValidNodeNumbers(nodes.map(_.nodeNumber))
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
      val junctions = junctionDAO.fetchJunctionsByValidNodeNumbers(nodes.map(_.nodeNumber))
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


  def getRoadwayPointId(roadwayNumber: Long, address: Long, username: String): Long = {
    val existingRoadwayPoint = roadwayPointDAO.fetch(roadwayNumber, address)
    val rwPoint = if (existingRoadwayPoint.nonEmpty) {
      existingRoadwayPoint.get.id
    } else {
      roadwayPointDAO.create(roadwayNumber, address, username)
    }
    rwPoint
  }

  def handleJunctionAndJunctionPoints(roadwayChanges: List[ProjectRoadwayChange], projectLinks: Seq[ProjectLink], mappedRoadwayNumbers: Seq[ProjectRoadLinkChange], username: String = "-"): Unit = {

    def getExistingJunctionIds(roadwayPointIds: Seq[Long]): Seq[Long] = {
      junctionPointDAO.fetchByRoadwayPointIds(roadwayPointIds).map(_.junctionId)
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
      * @param addr             address of the junction point
      * @param roadsTo          roads ending in the current road address r
      * @param roadsFrom        roads starting in the current road address r
      */
    def createJunctionAndJunctionPointIfNeeded(target: BaseRoadAddress, existingJunctionId: Option[Long] = None, pos: BeforeAfter, addr: Long,
                                               roadsTo: Seq[BaseRoadAddress] = Seq.empty[BaseRoadAddress], roadsFrom: Seq[BaseRoadAddress] = Seq.empty[BaseRoadAddress]): Option[(Long, String, CalibrationPointLocation)] = {
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
                logger.info(s"Creating Junction for roadwayNumber: ${target.roadwayNumber}, (${target.roadPart}, ${target.track}, $addr)")
                junctionDAO.create(Seq(Junction(NewIdValue, None, None, target.startDate.get, None, DateTime.now, None, username, Some(DateTime.now)))).head
            }
        }

        logger.info(s"Creating JunctionPoint with junctionId: $junctionId, beforeAfter: ${pos.value}, addrM: $addr for roadwayNumber: ${target.roadwayNumber}, (${target.roadPart}, ${target.track}, $addr)")
        junctionPointDAO.create(Seq(JunctionPoint(NewIdValue, pos, roadwayPointId, junctionId, None, None, DateTime.now, None, username, Some(DateTime.now), target.roadwayNumber, addr, target.roadPart, target.track, target.discontinuity)))
        Some((roadwayPointId, target.linkId, CalibrationPointLocation.apply(pos)))
      } else {
        None
      }
    }

    time(logger, "Handling junction point templates") {
      val nonTerminatedLinks: Seq[BaseRoadAddress] = projectLinks.filter(pl => RoadClass.forJunctions.contains(pl.roadPart.roadNumber) && pl.status != RoadAddressChangeType.Termination)
      // Update junctionPoint Before/After if projectLink is reversed
      nonTerminatedLinks.map { projectLink =>

        val junctionReversed = roadwayChanges.exists(ch => ch.changeInfo.target.startAddressM.nonEmpty && projectLink.startAddrMValue >= ch.changeInfo.target.startAddressM.get
          && ch.changeInfo.target.endAddressM.nonEmpty && projectLink.endAddrMValue <= ch.changeInfo.target.endAddressM.get && ch.changeInfo.reversed)

        val originalLink = mappedRoadwayNumbers.find(mpr => projectLink.startAddrMValue == mpr.newStartAddr && projectLink.endAddrMValue == mpr.newEndAddr && mpr.newRoadwayNumber == projectLink.roadwayNumber)

        val existingHeadJunctionPoint = {
          if (originalLink.nonEmpty) {
            if (!junctionReversed)
              junctionPointDAO.fetchByRoadwayPoint(projectLink.roadwayNumber, projectLink.startAddrMValue, BeforeAfter.After)
            else {
              junctionPointDAO.fetchByRoadwayPoint(originalLink.get.newRoadwayNumber, originalLink.get.newStartAddr, BeforeAfter.Before)
            }
          } else None
        }

        val existingLastJunctionPoint = {
          if (originalLink.nonEmpty) {
            if (!junctionReversed)
              junctionPointDAO.fetchByRoadwayPoint(projectLink.roadwayNumber, projectLink.startAddrMValue, BeforeAfter.Before)
            else {
              junctionPointDAO.fetchByRoadwayPoint(originalLink.get.newRoadwayNumber, originalLink.get.newEndAddr, BeforeAfter.After)
            }
          } else None
        }

        if (existingHeadJunctionPoint.nonEmpty) {
          if (junctionReversed) {
            logger.info(s"updating junctionPoints before after (${projectLink.linkId}) ${existingHeadJunctionPoint.head.id} ${existingHeadJunctionPoint.head.beforeAfter} ${existingHeadJunctionPoint.head.roadwayPointId}")
            junctionPointDAO.update(Seq(existingHeadJunctionPoint.head.copy(beforeAfter = BeforeAfter.switch(existingHeadJunctionPoint.head.beforeAfter))))
          }
        }

        if (existingLastJunctionPoint.nonEmpty) {
          if (junctionReversed) {
            logger.info(s"updating junctionPoints before after (${projectLink.linkId}) ${existingLastJunctionPoint.head.id} ${existingLastJunctionPoint.head.beforeAfter} ${existingLastJunctionPoint.head.roadwayPointId}")
            junctionPointDAO.update(Seq(existingLastJunctionPoint.head.copy(beforeAfter = BeforeAfter.switch(existingLastJunctionPoint.head.beforeAfter))))
          }
        }
      }
      val junctionCalibrationPoints: Set[(Long, String, CalibrationPointLocation)] = nonTerminatedLinks.flatMap { projectLink =>
        val roadNumberLimits = Seq((RoadClass.forJunctions.start, RoadClass.forJunctions.end))

        // Reversed flag to determine if projectLink has been reversed in project
        val reversed = roadwayChanges.exists(ch => ch.changeInfo.target.startAddressM.nonEmpty && projectLink.startAddrMValue >= ch.changeInfo.target.startAddressM.get
          && ch.changeInfo.target.endAddressM.nonEmpty && projectLink.endAddrMValue <= ch.changeInfo.target.endAddressM.get && ch.changeInfo.reversed)

        // Get roads that are connected to projectLinks starting- or endPoint
        // If projectLink has been reversed in project the starting- and endPoints are fetched from another source
        val roadsInFirstPoint: Seq[BaseRoadAddress] = if(reversed) {
          roadwayAddressMapper.getRoadAddressesByBoundingBox(BoundingRectangle(projectLink.newStartingPoint, projectLink.newStartingPoint), roadNumberLimits).filterNot(_.linearLocationId == projectLink.linearLocationId)
        }else {
          roadwayAddressMapper.getRoadAddressesByBoundingBox(BoundingRectangle(projectLink.startingPoint, projectLink.startingPoint), roadNumberLimits).filterNot(_.linearLocationId == projectLink.linearLocationId)
        }
        val roadsInLastPoint: Seq[BaseRoadAddress] = if(reversed) {
          roadwayAddressMapper.getRoadAddressesByBoundingBox(BoundingRectangle(projectLink.newEndPoint, projectLink.newEndPoint), roadNumberLimits).filterNot(_.linearLocationId == projectLink.linearLocationId)
        }else {
          roadwayAddressMapper.getRoadAddressesByBoundingBox(BoundingRectangle(projectLink.endPoint, projectLink.endPoint), roadNumberLimits).filterNot(_.linearLocationId == projectLink.linearLocationId)
        }

        /*
          * RoadAddresses where a junction will be created or updated if there's one already
          * * Ramps;
          * * Roundabouts
          * * Discontinuity cases for same road number;
          * * Discontinuous links that are connected to project links;
         */
        val (headRoads, tailRoads): (Seq[BaseRoadAddress], Seq[BaseRoadAddress]) = {
          if (RoadClass.RampsAndRoundaboutsClass.roads.contains(projectLink.roadPart.roadNumber)) {
            projectLink.discontinuity match {
              case Discontinuity.Discontinuous | Discontinuity.MinorDiscontinuity =>
                val head = roadsInFirstPoint filterNot RoadAddressFilters.sameRoadPart(projectLink)
                val tail = roadsInLastPoint
                (head, tail)
              case Discontinuity.EndOfRoad =>
                // Get the roads with same road number only if they are connected to the "End of road" - projectlinks' endPoint (for loop road junction points)
                // otherwise only get the roads that have different road number
                val head = roadsInFirstPoint filter RoadAddressFilters.connectedToEndOrDifferentRoad(projectLink)
                val tail = roadsInLastPoint filter RoadAddressFilters.connectedToEndOrDifferentRoad(projectLink)
                (head, tail)
              case _ =>
                // Ramps : where road number OR road part number change
                val head = roadsInFirstPoint filterNot RoadAddressFilters.sameRoadPart(projectLink)
                val tail = roadsInLastPoint filterNot RoadAddressFilters.sameRoadPart(projectLink)
                (head, tail)
            }
          } else {
            projectLink.discontinuity match {
              case Discontinuity.EndOfRoad =>
                // Get the roads with same road number only if they are connected to the "End of road" - projectlinks' endPoint (for loop road junction points)
                // otherwise only get the roads that have different road number
                val head = roadsInFirstPoint filter RoadAddressFilters.connectedToEndOrDifferentRoad(projectLink)
                val tail = roadsInLastPoint filter RoadAddressFilters.connectedToEndOrDifferentRoad(projectLink)
                (head, tail)
              case Discontinuity.MinorDiscontinuity =>
                // Discontinuity cases for same road number

                val head = if (roadsInFirstPoint.exists(fl => if(reversed) RoadAddressFilters.reversedEndingOfRoad(fl)(projectLink) else RoadAddressFilters.endingOfRoad(fl)(projectLink)))
                  roadsInFirstPoint
                else
                  roadsInFirstPoint filterNot RoadAddressFilters.sameRoad(projectLink)
                //even if there are no connecting points (for e.g. in case of a geometry jump), the discontinuous links should have one junction point in the ending point in middle of the part (MinorDiscontinuity)
                val tail: Seq[BaseRoadAddress] = if (roadsInLastPoint.exists(fl =>
                  if(reversed) RoadAddressFilters.reversedEndingOfRoad(fl)(projectLink)
                  else RoadAddressFilters.endingOfRoad(fl)(projectLink))) {
                  roadsInLastPoint
                }
                else {
                  roadsInLastPoint.filter(r =>
                    if(reversed) RoadAddressFilters.reversedContinuousTopology(projectLink)(r) || RoadAddressFilters.reversedConnectingBothTails(r)(projectLink)
                    else RoadAddressFilters.continuousTopology(projectLink)(r) || RoadAddressFilters.connectingBothTails(r)(projectLink))
                }
                (head, tail)
              case _ =>
                val head = if (roadsInFirstPoint.exists(fl =>
                  if(reversed) {
                    RoadAddressFilters.reversedEndingOfRoad(fl)(projectLink) || RoadAddressFilters.reversedHalfContinuousHalfDiscontinuous(fl)(projectLink) || RoadAddressFilters.discontinuousPartHeadIntersection(fl)(roadsInFirstPoint.filter(r => reversedContinuousTopology(r)(projectLink) || reversedConnectingBothHeads(r)(projectLink)))
                  } else {
                  RoadAddressFilters.endingOfRoad(fl)(projectLink) || RoadAddressFilters.halfContinuousHalfDiscontinuous(fl)(projectLink) || RoadAddressFilters.discontinuousPartHeadIntersection(fl)(roadsInFirstPoint.filter(r => continuousTopology(r)(projectLink) || connectingBothHeads(r)(projectLink)))
                  }
                ))
                  roadsInFirstPoint
                else if (nonTerminatedLinks.exists(fl =>
                  if(reversed) {
                    RoadAddressFilters.continuousRoadPartTrack(fl)(projectLink) && RoadAddressFilters.discontinuousTopology(fl)(projectLink)
                  } else {
                    RoadAddressFilters.continuousRoadPartTrack(fl)(projectLink) && RoadAddressFilters.reversedDiscontinuousTopology(fl)(projectLink)
                  })) {
                  if(reversed) {
                    roadsInFirstPoint.filterNot(RoadAddressFilters.sameRoad(projectLink)) ++ nonTerminatedLinks.filter(fl => fl.id != projectLink.id && (RoadAddressFilters.reversedHalfContinuousHalfDiscontinuous(fl)(projectLink) || projectLink.newStartingPoint.connected(fl.newStartingPoint)))
                  }else {
                    roadsInFirstPoint.filterNot(RoadAddressFilters.sameRoad(projectLink)) ++ nonTerminatedLinks.filter(fl => fl.id != projectLink.id && (RoadAddressFilters.halfContinuousHalfDiscontinuous(fl)(projectLink) || projectLink.startingPoint.connected(fl.startingPoint)))
                  }
                }
                else
                  roadsInFirstPoint.filterNot(RoadAddressFilters.sameRoad(projectLink))

                val tail = if (roadsInLastPoint.exists(fl =>
                  if(reversed){
                    RoadAddressFilters.reversedEndingOfRoad(projectLink)(fl) || RoadAddressFilters.reversedHalfContinuousHalfDiscontinuous(projectLink)(fl) || RoadAddressFilters.reversedDiscontinuousPartTailIntersection(projectLink)(fl)
                  }else {
                  RoadAddressFilters.endingOfRoad(projectLink)(fl) || RoadAddressFilters.halfContinuousHalfDiscontinuous(projectLink)(fl) || RoadAddressFilters.discontinuousPartTailIntersection(projectLink)(fl)
                  }))
                  roadsInLastPoint
                else
                  roadsInLastPoint.filterNot(RoadAddressFilters.sameRoad(projectLink))
                (head, tail)
            }
          }
        }

        val roadsToHead = headRoads.filter(hr =>
          if(reversed) {
            RoadAddressFilters.reversedContinuousTopology(hr)(projectLink) || RoadAddressFilters.afterDiscontinuousJump(hr)(projectLink)
          } else {
          RoadAddressFilters.continuousTopology(hr)(projectLink) || RoadAddressFilters.afterDiscontinuousJump(hr)(projectLink)
          })

        val roadsFromHead = headRoads.filter(hr =>
          if(reversed){
            RoadAddressFilters.reversedConnectingBothHeads(hr)(projectLink)
          }else {
            RoadAddressFilters.connectingBothHeads(hr)(projectLink)
          })

        val roadsToTail = tailRoads.filter(tr =>
          if(reversed){
            RoadAddressFilters.reversedConnectingBothTails(tr)(projectLink)
          } else {
            RoadAddressFilters.connectingBothTails(tr)(projectLink)
          })

        val roadsFromTail = tailRoads.filter(tr =>
          if(reversed){
            RoadAddressFilters.reversedContinuousTopology(projectLink)(tr)
          }else {
            RoadAddressFilters.continuousTopology(projectLink)(tr)
          })

        // handle junction points for each project links
        val startCp: Option[(Long, String, CalibrationPointLocation)] = if ((roadsToHead ++ roadsFromHead).nonEmpty) {
          val roadsTo = {
            if (reversed) {
              roadsToHead.filter(_.newEndPoint.connected(projectLink.newStartingPoint))
            } else {
              roadsToHead.filter(_.endPoint.connected(projectLink.startingPoint))
            }
          }
          val roadsFrom = {
            if (reversed) {
              roadsFromHead.filter(_.newStartingPoint.connected(projectLink.newStartingPoint))
            } else {
              roadsFromHead.filter(_.startingPoint.connected(projectLink.startingPoint))
            }
          }

          // VIITE-3043 find the existing junction for "loop road" aka "lenkkitie" that ends in itself.
          // This is only for the last project link with EndOfRoad Discontinuity otherwise its going to return nothing.
          val existingJunctionId = if (projectLink.discontinuity == Discontinuity.EndOfRoad && roadsTo.forall(road => road.roadPart == projectLink.roadPart)
            && roadsFrom.forall(road => road.roadPart == projectLink.roadPart)) {
            val junctionIds = {
              val roadsToRoadwayPointIds = roadsTo.flatMap(r => roadwayPointDAO.fetch(r.roadwayNumber, r.endAddrMValue).map(_.id).toSeq)
              val roadsFromRoadwayPointIds = roadsFrom.flatMap(r => roadwayPointDAO.fetch(r.roadwayNumber, r.startAddrMValue).map(_.id).toSeq)
              val roadwayPointIds = roadsToRoadwayPointIds ++ roadsFromRoadwayPointIds
              if (roadwayPointIds.nonEmpty) {
                getExistingJunctionIds(roadwayPointIds)
              } else
                Seq()
            }
            junctionIds.headOption
          } else {None}

          createJunctionAndJunctionPointIfNeeded(projectLink, existingJunctionId = existingJunctionId, pos = BeforeAfter.After, addr = projectLink.startAddrMValue, roadsTo = roadsTo, roadsFrom = roadsFrom)
        } else { None }

        val endCp: Option[(Long, String, CalibrationPointLocation)] = if ((roadsToTail ++ roadsFromTail).nonEmpty) {
          val roadsTo = {
            if (reversed) {
              roadsToTail.filter(_.newEndPoint.connected(projectLink.newEndPoint))
            } else {
              roadsToTail.filter(_.endPoint.connected(projectLink.endPoint))
            }
          }

          val roadsFrom = {
            if (reversed) {
              roadsFromTail.filter(_.newStartingPoint.connected(projectLink.newEndPoint))
            } else {
              roadsFromTail.filter(_.startingPoint.connected(projectLink.endPoint))
            }
          }

          // VIITE-3043 find the existing junction for "loop road" aka "lenkkitie" that ends in itself.
          // This is only for the last project link with EndOfRoad Discontinuity otherwise its going to return nothing.
          val existingJunctionId = if (roadsTo.forall(road => road.roadPart == projectLink.roadPart && projectLink.discontinuity == Discontinuity.EndOfRoad)
            && roadsFrom.forall(road => road.roadPart == projectLink.roadPart && projectLink.discontinuity == Discontinuity.EndOfRoad)) {
            val junctionIds = {
              val roadsToRoadwayPointIds = roadsTo.flatMap(r => roadwayPointDAO.fetch(r.roadwayNumber, r.endAddrMValue).map(_.id).toSeq)
              val roadsFromRoadwayPointIds = roadsFrom.flatMap(r => roadwayPointDAO.fetch(r.roadwayNumber, r.startAddrMValue).map(_.id).toSeq)
              val roadwayPointIds = roadsToRoadwayPointIds ++ roadsFromRoadwayPointIds
              if (roadwayPointIds.nonEmpty) {
                getExistingJunctionIds(roadwayPointIds)
              } else
                Seq()
            }
            junctionIds.headOption
          } else {
            None
          }
          createJunctionAndJunctionPointIfNeeded(projectLink, existingJunctionId = existingJunctionId, pos = BeforeAfter.Before, addr = projectLink.endAddrMValue,roadsTo = roadsTo,roadsFrom = roadsFrom)
        } else { None }

        // handle junction points for other roads, connected to each project link
        val toHeadCp = roadsToHead.flatMap { roadAddress: BaseRoadAddress =>
          val junctionId = getJunctionIdIfConnected(roadAddress.endPoint, if(reversed)projectLink.newStartingPoint else projectLink.startingPoint, roadwayNumber = projectLink.roadwayNumber, addr = projectLink.startAddrMValue, pos = BeforeAfter.After)
          createJunctionAndJunctionPointIfNeeded(roadAddress, junctionId, BeforeAfter.Before, roadAddress.endAddrMValue)
        }

        val fromHeadCp = roadsFromHead.flatMap { roadAddress: BaseRoadAddress =>
          val junctionId = getJunctionIdIfConnected(roadAddress.startingPoint, if(reversed)projectLink.newStartingPoint else projectLink.startingPoint, roadwayNumber = projectLink.roadwayNumber, addr = projectLink.startAddrMValue, pos = BeforeAfter.After)
          createJunctionAndJunctionPointIfNeeded(roadAddress, junctionId, BeforeAfter.After, roadAddress.startAddrMValue)
        }

        val toTailCp = roadsToTail.flatMap { roadAddress: BaseRoadAddress =>
          val junctionId = getJunctionIdIfConnected(roadAddress.endPoint, if(reversed)projectLink.newEndPoint else projectLink.endPoint, roadwayNumber = projectLink.roadwayNumber, addr = projectLink.endAddrMValue, pos = BeforeAfter.Before)
          createJunctionAndJunctionPointIfNeeded(roadAddress, junctionId, BeforeAfter.Before, roadAddress.endAddrMValue)
        }

        val fromTailCp = roadsFromTail.flatMap { roadAddress: BaseRoadAddress =>
          val junctionId = getJunctionIdIfConnected(roadAddress.startingPoint, if(reversed)projectLink.newEndPoint else projectLink.endPoint, roadwayNumber = projectLink.roadwayNumber, addr = projectLink.endAddrMValue, pos = BeforeAfter.Before)
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
      2.2)  and at the beginning/end of each road part, ely borders, or when administrative class changes
      2.3)  on each junction with a road number (except number over 70 000)
     */
  def handleNodePoints(roadwayChanges: List[ProjectRoadwayChange], projectLinks: Seq[ProjectLink], mappedRoadwayNumbers: Seq[ProjectRoadLinkChange], username: String = "-"): Unit = {
    @tailrec
    def continuousNodeSections(seq: Seq[ProjectLink], administrativeClassesSection: Seq[Seq[ProjectLink]] = Seq.empty[Seq[ProjectLink]]): (Seq[ProjectLink], Seq[Seq[ProjectLink]]) = {
      if (seq.isEmpty) {
        (Seq(), administrativeClassesSection)
      } else {
        val administrativeClass = seq.headOption.map(_.administrativeClass.value).getOrElse(0)
        val continuousProjectLinks = seq.takeWhile(pl => pl.administrativeClass.value == administrativeClass)
        continuousNodeSections(seq.drop(continuousProjectLinks.size), administrativeClassesSection :+ continuousProjectLinks)
      }
    }

    def createNodePointIfNeeded(projectLink: ProjectLink, addrM: Long, pos: BeforeAfter, reversed: Boolean, currentNodePoint: Option[NodePoint]): Unit = {
      val roadwayPointId = roadwayPointDAO.fetch(projectLink.roadwayNumber, addrM).map(_.id)
        .getOrElse(roadwayPointDAO.create(projectLink.roadwayNumber, addrM, username))

      val (existingCorrect, existingWrong) = currentNodePoint.partition { np => np.roadwayPointId == roadwayPointId && np.beforeAfter == pos && np.addrM == addrM }

      if (existingWrong.nonEmpty) {
        nodePointDAO.expireById(existingWrong.map(_.id))
      }

      if (existingCorrect.isEmpty) {
        nodePointDAO.create {
          Seq(NodePoint(NewIdValue, pos, roadwayPointId, None, NodePointType.RoadNodePoint, None, None, DateTime.now(), None, username, Some(DateTime.now()), projectLink.roadwayNumber, addrM, projectLink.roadPart, projectLink.track, projectLink.ely))
        }
      }
    }

    time(logger, "Handling node point templates") {
      try {
        val filteredLinks = projectLinks.filter(pl => RoadClass.forNodes.contains(pl.roadPart.roadNumber.toInt) && pl.status != RoadAddressChangeType.Termination).filterNot(_.track == Track.LeftSide)
        val groupSections = filteredLinks.groupBy(l => (l.roadPart))

        groupSections.values.foreach { group =>
          val administrativeClassSections: Seq[Seq[ProjectLink]] = continuousNodeSections(group.sortBy(_.startAddrMValue))._2
          administrativeClassSections.foreach { section =>

            val headProjectLink = section.head
            val headReversed = roadwayChanges.exists(ch => ch.changeInfo.target.startAddressM.nonEmpty && headProjectLink.startAddrMValue == ch.changeInfo.target.startAddressM.get
              && ch.changeInfo.target.endAddressM.nonEmpty && headProjectLink.endAddrMValue == ch.changeInfo.target.endAddressM.get && ch.changeInfo.reversed)

            val headNodePoint: Option[NodePoint] = mappedRoadwayNumbers.find { rl =>
              headProjectLink.startAddrMValue == rl.newStartAddr && headProjectLink.endAddrMValue == rl.newEndAddr && headProjectLink.roadwayNumber == rl.newRoadwayNumber
            }.flatMap { rl =>
              if (headReversed) {
                nodePointDAO.fetchRoadAddressNodePoints(Seq(rl.originalRoadwayNumber, headProjectLink.roadwayNumber))
                  .find(np => np.beforeAfter == BeforeAfter.Before && np.addrM == rl.newStartAddr)
              } else {
                nodePointDAO.fetchRoadAddressNodePoints(Seq(rl.originalRoadwayNumber, headProjectLink.roadwayNumber).distinct)
                  .find(np => np.beforeAfter == BeforeAfter.After && np.addrM == headProjectLink.startAddrMValue)
              }
            }

            createNodePointIfNeeded(headProjectLink, headProjectLink.startAddrMValue, BeforeAfter.After, headReversed, headNodePoint)

            val lastLink = section.last
            val lastReversed = roadwayChanges.exists(ch => ch.changeInfo.target.endAddressM.nonEmpty && lastLink.endAddrMValue == ch.changeInfo.target.endAddressM.get && ch.changeInfo.reversed)

            val lastNodePoint = mappedRoadwayNumbers.find { rl =>
              lastLink.startAddrMValue == rl.newStartAddr && lastLink.endAddrMValue == rl.newEndAddr && lastLink.roadwayNumber == rl.newRoadwayNumber
            }.flatMap { rl =>
              if (lastReversed) {
                nodePointDAO.fetchRoadAddressNodePoints(Seq(rl.originalRoadwayNumber, lastLink.roadwayNumber))
                  .find(np => np.beforeAfter == BeforeAfter.After && np.addrM == rl.newEndAddr)
              } else {
                nodePointDAO.fetchRoadAddressNodePoints(Seq(rl.originalRoadwayNumber, lastLink.roadwayNumber))
                  .find(np => np.beforeAfter == BeforeAfter.Before && np.addrM == lastLink.endAddrMValue)
              }
            }

            createNodePointIfNeeded(lastLink, lastLink.endAddrMValue, BeforeAfter.Before, lastReversed, lastNodePoint)
          }
        }
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

  def getJunctionsByBoundingBox(boundingRectangle: BoundingRectangle, raLinks: Seq[RoadAddressLink]): Map[Junction, Seq[JunctionPoint]] = {
    withDynTransactionNewOrExisting {
      time(logger, "Fetch junctions") {
        val junctions: Seq[Junction] = junctionDAO.fetchByBoundingBox(boundingRectangle)
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
      val nodePointsToTerminate = nodePointDAO.fetchByRoadwayPointIds(roadwayPointIds) ++  // get still eligible nodepoints
                                  nodePointDAO.fetchRoadwiseOrphansByRoadwayPointIds(roadwayPointIds) // get oprhaned  nodePoints
      val junctionPointsToTerminate = junctionPointDAO.fetchByRoadwayPointIds(roadwayPointIds)

      logger.info(s"Node points to Expire : ${nodePointsToTerminate.map(_.id)}")
      logger.info(s"Junction points to Expire : ${junctionPointsToTerminate.map(_.id)}")
      (nodePointsToTerminate, junctionPointsToTerminate)
    }

    def getObsoleteNodePointsAndJunctionPointsByModifiedRoadwayNumbers(roadwayNumbersSection: Seq[Long], terminatedJunctionPoints: Seq[JunctionPoint]): (Seq[NodePoint], Seq[JunctionPoint]) = {
      logger.info(s"Modified roadway number: ${roadwayNumbersSection.toList}")
      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(roadwayNumbersSection)
      val sortedRoadways = roadwayDAO.fetchAllByRoadwayNumbers(roadwayNumbersSection.toSet).sortBy(_.startAddrMValue)

      val (startAddrMValue, endAddrMValue) = (sortedRoadways.headOption.map(_.startAddrMValue), sortedRoadways.lastOption.map(_.endAddrMValue))

      val obsoleteNodePoints = sortedRoadways.flatMap { rw =>
        val nodePoints = nodePointDAO.fetchByRoadwayPointIds(roadwayPoints.filter(_.roadwayNumber == rw.roadwayNumber).map(_.id))
          .filter(_.nodePointType == NodePointType.RoadNodePoint)
        rw.track match {
          case Track.LeftSide => nodePoints
          case _ => nodePoints.filterNot { n =>
            (n.beforeAfter == BeforeAfter.After && startAddrMValue.contains(n.addrM)) || (n.beforeAfter == BeforeAfter.Before && endAddrMValue.contains(n.addrM))
          }
        }
      }

      val groupedTerminatedJunctionPoints = terminatedJunctionPoints.groupBy(_.junctionId)

      val junctions = junctionDAO.fetchByIds(junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints.map(_.id)).map(_.junctionId))
      val obsoleteJunctionPoints: Seq[JunctionPoint] = junctions.flatMap { junction =>
        val terminatedJunctionPoints = groupedTerminatedJunctionPoints.getOrElse(junction.id, Seq.empty[JunctionPoint])
        val affectedJunctionsPoints = junctionPointDAO.fetchByJunctionIds(Seq(junction.id)) match {
          case junctionPoints if junctionPoints.exists(jp => RoadClass.RampsAndRoundaboutsClass.roads.contains(jp.roadPart.roadNumber)) =>
            val junctionPointsToCheck = junctionPoints.filterNot(jp => terminatedJunctionPoints.map(_.id).contains(jp.id))
            if (junctionPointsToCheck.size <= 1) {
              // basic rule
              junctionPointsToCheck
            } else if (ObsoleteJunctionPointFilters.rampsAndRoundaboutsDiscontinuityInSameOwnRoadNumber(junctionPointsToCheck) ||
              junctionPoints.groupBy(jp => (jp.roadPart)).keys.size > 1)
              Seq.empty[JunctionPoint]
            else junctionPointsToCheck
          case junctionPoints if !junctionPoints.forall(jp => RoadClass.RampsAndRoundaboutsClass.roads.contains(jp.roadPart.roadNumber)) =>

            val junctionPointsToCheck = junctionPoints.filterNot(jp => terminatedJunctionPoints.map(_.id).contains(jp.id))
            // check if the terminated junction Points are the unique ones in the Junction to avoid further complex validations
            if (junctionPointsToCheck.size <= 1) {
              // basic rule
              junctionPointsToCheck
            } else if (ObsoleteJunctionPointFilters.multipleRoadNumberIntersection(junctionPointsToCheck) ||
              ObsoleteJunctionPointFilters.multipleTrackIntersection(junctionPointsToCheck) ||
              ObsoleteJunctionPointFilters.sameRoadAddressIntersection(junctionPointsToCheck) ||
              ObsoleteJunctionPointFilters.roadEndingInSameOwnRoadNumber(junctionPointsToCheck) ||
              ObsoleteJunctionPointFilters.discontinuousPartIntersection(junctionPointsToCheck)
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

      // Create junction rows with end date and junction point rows with new junction id and expire the newly created junction point rows
      val junctionsToExpire = junctionDAO.fetchAllByIds(junctionsToExpireIds)
      junctionsToExpire.foreach(j => {
        val newJunctionId = junctionDAO.create(Seq(j.copy(id = NewIdValue, endDate = endDate, createdBy = username))).head
        val newJunctionPointRows = junctionPointDAO.create(junctionPoints
          .filter(_.junctionId == j.id)
          .map(_.copy(id = NewIdValue, junctionId = newJunctionId, createdBy = username)))
        junctionPointDAO.expireById(newJunctionPointRows)
        newJunctionPointRows
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

      val nodePointsOfExpiredNodes = nodePointDAO.fetchByNodeNumbers(nodeNumbersToExpire)
      logger.info(s"Expiring node points of expired nodes : $nodePointsOfExpiredNodes")
      nodePointDAO.expireById(nodePointsOfExpiredNodes.map(_.id))
    }

    @tailrec
    def continuousSectionByAdministrativeClass(section: Seq[ProjectLink], continuousSection: Seq[Seq[ProjectLink]] = Seq.empty): Seq[Seq[ProjectLink]] = {
      if (section.isEmpty)
        continuousSection
      else {
        val administrativeClass = section.head.administrativeClass
        val sectionByAdministrativeClass: Seq[ProjectLink] = section.takeWhile(p => p.administrativeClass == administrativeClass)
        continuousSectionByAdministrativeClass(section.drop(sectionByAdministrativeClass.size), continuousSection :+ sectionByAdministrativeClass)
      }
    }

    val filteredProjectLinks = projectLinks
      .filter(pl => RoadClass.forJunctions.contains(pl.roadPart.roadNumber))

    val terminated = filteredProjectLinks.filter(_.status == RoadAddressChangeType.Termination)

    val terminatedRoadwayNumbers = terminated.map(_.roadwayNumber).distinct
    val (terminatedNodePoints, terminatedJunctionPoints): (Seq[NodePoint], Seq[JunctionPoint]) =
      getNodePointsAndJunctionPointsByTerminatedRoadwayNumbers(terminatedRoadwayNumbers)

    val projectLinkSections = filteredProjectLinks.groupBy(projectLink => (projectLink.roadPart))
    val obsoletePointsFromModifiedRoadways: Seq[(Seq[NodePoint], Seq[JunctionPoint])] = projectLinkSections.mapValues { section: Seq[ProjectLink] =>
      continuousSectionByAdministrativeClass(section.sortBy(_.startAddrMValue)).map { continuousSection =>
        val modifiedRoadwayNumbers = continuousSection.map(_.roadwayNumber).distinct
        getObsoleteNodePointsAndJunctionPointsByModifiedRoadwayNumbers(modifiedRoadwayNumbers, terminatedJunctionPoints)
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
      junctionPointsToCheck.groupBy(_.roadPart.roadNumber).keys.size > 1
    }

    def multipleTrackIntersection(junctionPointsToCheck: Seq[JunctionPoint]): Boolean = {
      val tracks = junctionPointsToCheck.groupBy(_.track)
      !tracks.contains(Track.Combined) && tracks.contains(Track.LeftSide) && tracks.contains(Track.RightSide)
    }

    def sameRoadAddressIntersection(junctionPointsToCheck: Seq[JunctionPoint]): Boolean = {

      def isRoadPartIntersection(curr: JunctionPoint, rest: Seq[JunctionPoint]): Boolean = {
        val junctionPointsInSameAddrAndPart = rest.filter(jp => curr.roadPart == jp.roadPart && curr.addrM == jp.addrM)
        val (before, after) = (junctionPointsInSameAddrAndPart :+ curr).partition(_.beforeAfter == BeforeAfter.Before)
        val junctionPointsInSamePart = rest.filter(jp => curr.roadPart == jp.roadPart)
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
        rest.exists(jp => curr.roadPart.roadNumber == jp.roadPart.roadNumber && curr.discontinuity == Discontinuity.EndOfRoad && jp.discontinuity != Discontinuity.EndOfRoad && curr.beforeAfter == BeforeAfter.Before)
      }

      junctionPointsToCheck.exists { jpc =>
        isRoadEndingInItself(jpc, junctionPointsToCheck.filter(_.id != jpc.id))
      }
    }

    def discontinuousPartIntersection(junctionPointsToCheck: Seq[JunctionPoint]): Boolean = {
      junctionPointsToCheck.groupBy(jp => (jp.roadPart, jp.addrM, jp.beforeAfter)).keys.size > 2 ||
        junctionPointsToCheck.filter(_.beforeAfter == BeforeAfter.Before).exists(jp => List(Discontinuity.Discontinuous, Discontinuity.MinorDiscontinuity).contains(jp.discontinuity))
    }

    def rampsAndRoundaboutsDiscontinuityInSameOwnRoadNumber(junctionPointsToCheck: Seq[JunctionPoint]): Boolean = {
      val validEndingDiscontinuityForRamps = List(Discontinuity.EndOfRoad, Discontinuity.Discontinuous, Discontinuity.MinorDiscontinuity)

      def isRoadEndingInItself(curr: JunctionPoint, rest: Seq[JunctionPoint]): Boolean = {
        rest.exists(jp => curr.roadPart.roadNumber == jp.roadPart.roadNumber && validEndingDiscontinuityForRamps.contains(curr.discontinuity) && curr.beforeAfter == BeforeAfter.Before)
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
      if (lastRoadNumber != roadPartInfo.roadPart.roadNumber || lastRoadPartNumber != roadPartInfo.roadPart.partNumber) {
        // set nodePointCount to zero so that road or road part has changed in for loop and we need to create nodepoint
        nodePointCount = 0
      }
      lastRoadNumber = roadPartInfo.roadPart.roadNumber
      lastRoadPartNumber = roadPartInfo.roadPart.partNumber
      val countNodePointsForRoadAndRoadPart = nodePointDAO.fetchNodePointsCountForRoadAndRoadPart(roadPartInfo.roadPart, roadPartInfo.beforeAfter, nodeNumber)
      /*
         If the road part doesn't have any "road node points", calculate node point by taking the average of the
         addresses of all junction points on both tracks and add this "calculated node point" on track 0 or 1
       */
      if (countNodePointsForRoadAndRoadPart.getOrElse(0) == 0 && (nodePointCount < 2)) {
        if (logger.isDebugEnabled) {
          // generate query debug for what are the input values for average calculation
          nodePointDAO.fetchAddrMForAverage(roadPartInfo.roadPart)
        }
        val addrMValueAVG = nodePointDAO.fetchAverageAddrM(roadPartInfo.roadPart, nodeNumber)
        roadwayDAO
          .fetchAllBySectionAndTracks(roadPartInfo.roadPart, Set(Track.Combined, Track.RightSide))
          .filter(r => r.startAddrMValue <= addrMValueAVG && addrMValueAVG <= r.endAddrMValue)
          .foreach(avgRoadway => {
            val beforeAfterValue = if (avgRoadway.endAddrMValue == addrMValueAVG) {
              BeforeAfter.Before
            } else if (avgRoadway.startAddrMValue == addrMValueAVG) {
              BeforeAfter.After
            } else {
              BeforeAfter.UnknownBeforeAfter
            }
            val existingRoadwayPoint = roadwayPointDAO.fetch(avgRoadway.roadwayNumber, addrMValueAVG)
            val rwPoint = if (existingRoadwayPoint.nonEmpty) {
              existingRoadwayPoint.get.id
            } else {
              roadwayPointDAO.create(avgRoadway.roadwayNumber, addrMValueAVG, username)
            }
            if (beforeAfterValue == BeforeAfter.UnknownBeforeAfter) {
              nodePointDAO.insertCalculatedNodePoint(rwPoint, BeforeAfter.Before, nodeNumber, username)
              nodePointDAO.insertCalculatedNodePoint(rwPoint, BeforeAfter.After, nodeNumber, username)
              nodePointCount = nodePointCount + 2
            } else {
              val nodePointTemplates = nodePointDAO.fetchNodePointTemplateByRoadwayPointId(rwPoint).headOption
              if (nodePointTemplates.nonEmpty) {
                val nodePointTemplate = nodePointTemplates.head

                // Check what is the type of the TEMPLATE and insert a equivalent nodePoint, then delete any duplicate TEMPLATES

                if (nodePointTemplate.nodePointType == NodePointType.CalculatedNodePoint) {
                  nodePointDAO.insertCalculatedNodePoint(rwPoint, beforeAfterValue, nodeNumber, username)
                  nodePointCount = nodePointCount + 1
                  //expire duplicate nodePointTemplate if there is one with the same roadwayPoint
                  nodePointDAO.expireById(List(nodePointTemplate.id))
                }
                else if (nodePointTemplate.nodePointType == NodePointType.RoadNodePoint) {
                  nodePointDAO.insertRoadNodePoint(rwPoint, beforeAfterValue, nodeNumber, username)
                  nodePointCount = nodePointCount + 1
                  //expire duplicate nodePointTemplate if there is one with the same roadwayPoint
                  nodePointDAO.expireById(List(nodePointTemplate.id))
                }
              } else {
                nodePointDAO.insertCalculatedNodePoint(rwPoint, beforeAfterValue, nodeNumber, username)
                nodePointCount = nodePointCount + 1
              }
            }
          })
      }
    }
    None
  }

}
