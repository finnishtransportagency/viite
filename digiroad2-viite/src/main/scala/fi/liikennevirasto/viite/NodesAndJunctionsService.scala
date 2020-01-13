package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.BeforeAfter.{After, Before}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType
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
      junctionPointDAO.fetchJunctionPointsByJunctionIds(junctionIds)
    }
  }

  def getNodesWithJunctionByBoundingBox(boundingRectangle: BoundingRectangle): Map[Node, (Seq[NodePoint], Map[Junction, Seq[JunctionPoint]])] = {
    withDynSession {
      time(logger, "Fetch nodes with junctions") {
        val nodes = nodeDAO.fetchByBoundingBox(boundingRectangle)
        val nodePoints = nodePointDAO.fetchNodePointsByNodeNumber(nodes.map(_.nodeNumber))
        val junctions = junctionDAO.fetchJunctionsByNodeNumbers(nodes.map(_.nodeNumber))
        val junctionPoints = junctionPointDAO.fetchJunctionPointsByJunctionIds(junctions.map(_.id))
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
      val junctionPoints = junctionPointDAO.fetchJunctionPointsByJunctionIds(junctions.map(_.id))
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
        allJunctionTemplates.filter(jt => jt.roadNumber != 0 && authorizedElys.contains(jt.elyCode))
          .groupBy(_.id).map(junctionTemplate => {
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

    def handleRoadsToHead(link: ProjectLink, junctionsInHead: Seq[Junction], r: RoadAddress): Unit = {
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
        if(CalibrationPointDAO.fetch(r.linkId, 1, rwPoint).isEmpty){
          CalibrationPointDAO.create(rwPoint, r.linkId, startOrEnd = 1, calType = CalibrationPointType.Mandatory, createdBy = link.createdBy.get)
        }
        Some(junctionId)
      } else None

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
      if(CalibrationPointDAO.fetch(link.linkId, 0, rwPoint).isEmpty){
        CalibrationPointDAO.create(rwPoint, link.linkId, startOrEnd = 0, calType = CalibrationPointType.Mandatory, createdBy = link.createdBy.get)
      }
    }

    def handleRoadsFromHead(link: ProjectLink, newJunctionsInHead: Seq[Junction], junctionsInHead: Seq[Junction], r: RoadAddress): Unit = {
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
        if(CalibrationPointDAO.fetch(r.linkId, 0, rwPoint).isEmpty){
          CalibrationPointDAO.create(rwPoint, r.linkId, startOrEnd = 0, calType = CalibrationPointType.Mandatory, createdBy = link.createdBy.get)
        }
        Some(junctionId)
      } else None

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
      if(CalibrationPointDAO.fetch(link.linkId, 0, rwPoint).isEmpty){
        CalibrationPointDAO.create(rwPoint, link.linkId, startOrEnd = 0, calType = CalibrationPointType.Mandatory, createdBy = link.createdBy.get)
      }
    }

    def handleRoadsToTail(link: ProjectLink, junctionsToTail: Seq[Junction], junctionsInHead: Seq[Junction], r: RoadAddress): Unit = {
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
        if(CalibrationPointDAO.fetch(r.linkId, 1, rwPoint).isEmpty){
          CalibrationPointDAO.create(rwPoint, r.linkId, startOrEnd = 1, calType = CalibrationPointType.Mandatory, createdBy = link.createdBy.get)
        }
        Some(junctionId)
      } else None

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
      if(CalibrationPointDAO.fetch(link.linkId, 1, rwPoint).isEmpty){
        CalibrationPointDAO.create(rwPoint, link.linkId, startOrEnd = 1, calType = CalibrationPointType.Mandatory, createdBy = link.createdBy.get)
      }
    }

    def handleRoadsFromTail(link: ProjectLink, newJunctionsToTail: Seq[Junction], junctionsInHead: Seq[Junction], r: RoadAddress): Unit = {
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
        if(CalibrationPointDAO.fetch(r.linkId, 0, rwPoint).isEmpty){
          CalibrationPointDAO.create(rwPoint, r.linkId, startOrEnd = 0, calType = CalibrationPointType.Mandatory, createdBy = link.createdBy.get)
        }
        Some(junctionId)
      } else None

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
      if(CalibrationPointDAO.fetch(link.linkId, 1, rwPoint).isEmpty){
        CalibrationPointDAO.create(rwPoint, link.linkId, startOrEnd = 1, calType = CalibrationPointType.Mandatory, createdBy = link.createdBy.get)
      }
    }

    time(logger, "Handling junction point templates") {
      val filteredLinks = projectLinks.filter(pl => RoadClass.nodeAndJunctionRoadClass.flatMap(_.roads).contains(pl.roadNumber.toInt) && pl.status != LinkStatus.Terminated)
      filteredLinks.foreach { link =>
        val roadNumberLimits = Seq((0, 19999), (40001, 69999))
        val headRoads = roadwayAddressMapper.getRoadAddressesByBoundingBox(BoundingRectangle(link.getFirstPoint, link.getFirstPoint), roadNumberLimits).filterNot(rw => rw.roadNumber == link.roadNumber)
        val tailRoads = roadwayAddressMapper.getRoadAddressesByBoundingBox(BoundingRectangle(link.getLastPoint, link.getLastPoint), roadNumberLimits).filterNot(rw => rw.roadNumber == link.roadNumber)

        val roadsToHead = headRoads.filter(_.connected(link.getFirstPoint))
        val roadsFromHead = headRoads.filter(r => link.getFirstPoint.connected(r.getFirstPoint))

        val roadsFromTail = tailRoads.filter(r => link.getLastPoint.connected(r.getFirstPoint))
        val roadsToTail = tailRoads.filter(_.getLastPoint.connected(link.getLastPoint))

        /*
      handle creation of JUNCTION_POINT in reverse cases
      */

        val junctionReversed = roadwayChanges.exists(ch => ch.changeInfo.target.startAddressM.nonEmpty && link.startAddrMValue >= ch.changeInfo.target.startAddressM.get
          && ch.changeInfo.target.endAddressM.nonEmpty && link.endAddrMValue <= ch.changeInfo.target.endAddressM.get && ch.changeInfo.reversed)

        val originalLink = mappedRoadwayNumbers.find(mpr => link.startAddrMValue == mpr.newStartAddr && link.endAddrMValue == mpr.newEndAddr && mpr.newRoadwayNumber == link.roadwayNumber)

        val existingHeadJunctionPoint = {
          if (originalLink.nonEmpty) {
            if (!junctionReversed)
              junctionPointDAO.fetchJunctionPointsByRoadwayPointNumbers(Set(originalLink.get.oldRoadwayNumber, link.roadwayNumber), link.startAddrMValue, BeforeAfter.After)
            else {
              junctionPointDAO.fetchJunctionPointsByRoadwayPointNumbers(Set(originalLink.get.oldRoadwayNumber, link.roadwayNumber), originalLink.get.newStartAddr, BeforeAfter.Before)
            }
          } else None
        }

        val existingLastJunctionPoint = {
          if (originalLink.nonEmpty) {
            if (!junctionReversed)
              junctionPointDAO.fetchJunctionPointsByRoadwayPointNumbers(Set(originalLink.get.oldRoadwayNumber, link.roadwayNumber), link.startAddrMValue, BeforeAfter.Before)
            else {
              junctionPointDAO.fetchJunctionPointsByRoadwayPointNumbers(Set(originalLink.get.oldRoadwayNumber, link.roadwayNumber), originalLink.get.newEndAddr, BeforeAfter.After)
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
        R:road
        L:project link
        0:junction point
      */
        val junctionsInHead: Seq[Junction] = getJunctionsInHead(link, roadsToHead, roadsFromHead)

        val junctionsToTail: Seq[Junction] = getJunctionsToTail(link, roadsToTail, roadsFromTail)

        //  |--R-->0|0--L-->
        roadsToHead.foreach { roadAddress: RoadAddress => handleRoadsToHead(link, junctionsInHead, roadAddress) }

        //need to get all new junctions in junctionInHead places since we can have this kind of cases:
        /*
             ^
             |
             L
             |
      |-R1->0|(*)-R2->|

      if we dont get again all new created junctions, we would badly create one duplicate junction in the place of (*)
       */
        val newJunctionsInHead = getJunctionsInHead(link, roadsToHead, roadsFromHead)

        // <--R--0|0--L-->
        roadsFromHead.foreach { roadAddress: RoadAddress => handleRoadsFromHead(link, newJunctionsInHead, junctionsInHead, roadAddress) }

        // |--R--0>|<0--L--|
        roadsToTail.foreach { roadAddress: RoadAddress => handleRoadsToTail(link, junctionsToTail, junctionsInHead, roadAddress) }

        val newJunctionsToTail = getJunctionsToTail(link, roadsToTail, roadsFromTail)

        // <--R--0|<0--L--|
        roadsFromTail.foreach { roadAddress: RoadAddress => handleRoadsFromTail(link, newJunctionsToTail, junctionsInHead, roadAddress) }
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

        val filteredLinks = projectLinks.filter(pl => RoadClass.nodeAndJunctionRoadClass.flatMap(_.roads).contains(pl.roadNumber.toInt) && pl.status != LinkStatus.Terminated).filterNot(_.track == Track.LeftSide)
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
    def obsoleteNodesAndJunctionsPointsByRoadwayNumbers(terminatedRoadwayNumbers: Seq[Long]): (Seq[NodePoint], Seq[JunctionPoint]) = {
      val obsoleteRoadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(terminatedRoadwayNumbers)
      val obsoleteNodePoints = nodePointDAO.fetchByRoadwayPointIds(obsoleteRoadwayPoints.map(_.id))
      val obsoleteJunctionPoints = junctionPointDAO.fetchByRoadwayPointIds(obsoleteRoadwayPoints.map(_.id))
      (obsoleteNodePoints, obsoleteJunctionPoints)
    }

    def obsoleteNodesAndJunctionsPoints(affectedRoadwayNumbers: Seq[Long]): (Seq[NodePoint], Seq[JunctionPoint]) = {
      val roadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(affectedRoadwayNumbers)
      val startAddrMValue: Long = roadwayDAO.fetchAllByRoadwayNumbers(affectedRoadwayNumbers.toSet).minBy(_.startAddrMValue).startAddrMValue
      val endAddrMValue = roadwayDAO.fetchAllByRoadwayNumbers(affectedRoadwayNumbers.toSet).maxBy(_.endAddrMValue).endAddrMValue

      val nodePoints = nodePointDAO.fetchByRoadwayPointIds(roadwayPoints.map(_.id)).filter(_.nodeNumber.isDefined)
      val obsoleteNodes = nodeDAO.fetchObsoleteByNodeNumbers(nodePoints.map(_.nodeNumber.get).distinct)
      val obsoleteNodePoints = nodePointDAO.fetchNodePointsByNodeNumber(obsoleteNodes.map(_.nodeNumber)) ++
        nodePoints.filterNot(n => (n.beforeAfter == BeforeAfter.After && n.addrM == startAddrMValue) || (n.beforeAfter == BeforeAfter.Before && n.addrM == endAddrMValue))

      val junctionPoints = junctionPointDAO.fetchByRoadwayPointIds(roadwayPoints.map(_.id))
      val obsoleteJunctions = junctionDAO.fetchObsoleteById(junctionPoints.map(_.junctionId).distinct)
      val obsoleteJunctionPoints = junctionPointDAO.fetchJunctionPointsByJunctionIds(obsoleteJunctions.map(_.id))

      (obsoleteNodePoints, obsoleteJunctionPoints)
    }

    def expireObsoleteJunctions(obsoleteJunctionPoints: Set[JunctionPoint]): Seq[Junction] = {

      // Expire current junction points rows
      logger.info(s"Expiring junction points : ${obsoleteJunctionPoints.map(_.id)}")
      junctionPointDAO.expireById(obsoleteJunctionPoints.map(_.id))

      // Remove junctions that no longer have justification for the current network
      val obsoleteJunctions = junctionDAO.fetchObsoleteById(obsoleteJunctionPoints.map(_.junctionId))
      logger.info(s"Expiring junctions : ${obsoleteJunctions.map(_.id)}")
      junctionDAO.expireById(obsoleteJunctions.map(_.id))
      val obsoleteJunctionPointsOfNowExpiredJunctions = junctionPointDAO.fetchJunctionPointsByJunctionIds(obsoleteJunctions.map(_.id))
      logger.info(s"Expiring junction points : ${obsoleteJunctionPointsOfNowExpiredJunctions.map(_.id)}")
      junctionPointDAO.expireById(obsoleteJunctionPointsOfNowExpiredJunctions.map(_.id))

      // Handle obsolete junction points of valid and obsolete junctions separately
      val (obsoleteJunctionPointsOfObsoleteJunctions, obsoleteJunctionPointsOfValidJunctions) =
        (obsoleteJunctionPoints ++ obsoleteJunctionPointsOfNowExpiredJunctions)
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

    def expireNodes(obsoleteNodePoints: Set[NodePoint], obsoleteJunctions: Seq[Junction]): Unit = {

      // Expire obsolete node points
      nodePointDAO.expireById(obsoleteNodePoints.map(_.id))

      val nodeNumbersToCheck = (obsoleteJunctions.filter(j => j.nodeNumber.isDefined).map(_.nodeNumber.get)
        ++ obsoleteNodePoints.filter(np => np.nodeNumber.isDefined).map(_.nodeNumber.get)).distinct

      // Remove nodes that no longer have justification for the current network
      val obsoleteNodes = nodeDAO.fetchObsoleteByNodeNumbers(nodeNumbersToCheck)

      // Create node rows with end date
      obsoleteNodes.foreach(n => {
        nodeDAO.create(Seq(n.copy(id = NewIdValue, endDate = endDate, createdBy = Some(username)))).head
      })

      logger.info(s"Expiring nodes: ${obsoleteNodes.map(_.nodeNumber)}")
      nodeDAO.expireById(obsoleteNodes.map(_.id))
    }

    val (terminatedLinks, affectedLinks): (Seq[ProjectLink], Seq[ProjectLink]) = projectLinks
      .filter(pl => RoadClass.nodeAndJunctionRoadClass.flatMap(_.roads).contains(pl.roadNumber.toInt))
      .partition(_.status == LinkStatus.Terminated)

    val terminatedRoadwayNumbers = terminatedLinks.map(_.roadwayNumber).distinct
    logger.info(s"Terminated roadway numbers : $terminatedRoadwayNumbers")
    val terminatedPoints: (Seq[NodePoint], Seq[JunctionPoint]) = obsoleteNodesAndJunctionsPointsByRoadwayNumbers(terminatedRoadwayNumbers)

    val affectedGroupSections = affectedLinks.groupBy(l => (l.roadNumber, l.roadPartNumber))
    val affectedPoints = affectedGroupSections.mapValues { group =>
      val affectedRoadwayNumbers = group.map(_.roadwayNumber).distinct
      logger.info(s"Affected roadway numbers : $affectedRoadwayNumbers")
      obsoleteNodesAndJunctionsPoints(affectedRoadwayNumbers)
    }.values

    val obsoleteNodePoints = (terminatedPoints._1 ++ affectedPoints.flatMap(_._1)).toSet
    val obsoleteJunctionPoints = (terminatedPoints._2 ++ affectedPoints.flatMap(_._2)).toSet

    logger.info(s"Obsolete node points : ${obsoleteNodePoints.map(_.id)}")
    logger.info(s"Obsolete junction points : ${obsoleteJunctionPoints.map(_.id)}")
    val obsoleteJunctions = expireObsoleteJunctions(obsoleteJunctionPoints)
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
          val junctionPointsToExpire = junctionPointDAO.fetchJunctionPointsByJunctionIds(Seq(j.id))
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
