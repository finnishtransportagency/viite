package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.Track.LeftSide
import fi.liikennevirasto.digiroad2.util.{MissingTrackException, RoadAddressException, Track}
import fi.liikennevirasto.digiroad2.{Point, Vector3d}
import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process._
import org.slf4j.LoggerFactory

import scala.collection.immutable.ListMap

class DefaultSectionCalculatorStrategy extends RoadAddressSectionCalculatorStrategy {

  private val logger = LoggerFactory.getLogger(getClass)

  override val name: String = "Normal Section"

  val projectLinkDAO = new ProjectLinkDAO
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO: RoadwayDAO, linearLocationDAO: LinearLocationDAO)

  override def assignMValues(newProjectLinks: Seq[ProjectLink], oldProjectLinks: Seq[ProjectLink], userCalibrationPoints: Seq[UserDefinedCalibrationPoint]): Seq[ProjectLink] = {

    val groupedProjectLinks = newProjectLinks.groupBy(record => (record.roadNumber, record.roadPartNumber))
    val groupedOldLinks = oldProjectLinks.groupBy(record => (record.roadNumber, record.roadPartNumber))
    val group = (groupedProjectLinks.keySet ++ groupedOldLinks.keySet).map(k =>
      k -> (groupedProjectLinks.getOrElse(k, Seq()), groupedOldLinks.getOrElse(k, Seq())))
    group.flatMap { case (part, (projectLinks, oldLinks)) =>
      try {
        val oldRoadLinks = if (projectLinks.nonEmpty) {
          projectLinkDAO.fetchByProjectRoad(part._1, projectLinks.head.projectId).filterNot(l => l.roadPartNumber == part._2)
        } else {
          Seq.empty[ProjectLink]
        }
        val currStartPoints = findStartingPoints(projectLinks, oldLinks, oldRoadLinks, userCalibrationPoints)
        val (right, left) = TrackSectionOrder.orderProjectLinksTopologyByGeometry(currStartPoints, projectLinks ++ oldLinks)
        val ordSections = TrackSectionOrder.createCombinedSections(right, left)

        // TODO: userCalibrationPoints to Long -> Seq[UserDefinedCalibrationPoint] in method params
        val calMap = userCalibrationPoints.map(c => c.projectLinkId -> c).toMap

        val calculatedSections = calculateSectionAddressValues(ordSections, calMap)
        calculatedSections.flatMap { sec =>
          if (sec.right == sec.left)
            sec.right.links
          else {
            sec.right.links ++ sec.left.links
          }
        }
      } catch {
        case ex: MissingTrackException =>
          logger.info(ex.getMessage)
          projectLinks ++ oldLinks
        case ex: InvalidAddressDataException =>
          logger.info(s"Can't calculate road/road part ${part._1}/${part._2}: " + ex.getMessage)
          throw ex
        case ex: NoSuchElementException =>
          logger.info("Delta calculation failed: " + ex.getMessage, ex)
          throw ex
        case ex: NullPointerException =>
          logger.info("Delta calculation failed (NPE)", ex)
          throw ex
        case ex: Throwable =>
          logger.info("Delta calculation not possible: " + ex.getMessage)
          throw ex
      }
    }.toSeq
  }

  private def continuousWOutRoadwayNumberSection(seq: Seq[ProjectLink]): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val track = seq.headOption.map(_.track).getOrElse(Track.Unknown)
    val roadType = seq.headOption.map(_.roadType.value).getOrElse(0)
    val continuousProjectLinks = seq.takeWhile(pl => (pl.track == track && pl.track == Track.Combined) || (pl.track == track && pl.track != Track.Combined && pl.roadType.value == roadType))
    (continuousProjectLinks, seq.drop(continuousProjectLinks.size))
  }

  def assignProperRoadwayNumber(continuousProjectLinks: Seq[ProjectLink], givenRoadwayNumber: Long, originalHistorySection: Seq[ProjectLink]): (Long, Long) = {
    def getRoadAddressesByRoadwayIds(roadwayIds: Seq[Long]): Seq[RoadAddress] = {
      val roadways = roadwayDAO.fetchAllByRoadwayId(roadwayIds)
      val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadways)
      roadAddresses
    }

    val roadwayNumbers = if (continuousProjectLinks.nonEmpty && continuousProjectLinks.exists(_.status == LinkStatus.New)) {
      // then we now that for sure the addresses increased their length for the part => new roadwayNumber for the new sections
      (givenRoadwayNumber, Sequences.nextRoadwayNumber)
    } else if (continuousProjectLinks.nonEmpty && continuousProjectLinks.exists(_.status == LinkStatus.Numbering)) {
      // then we now that for sure the addresses didnt change the address length part, only changed the number of road or part => same roadwayNumber
      (continuousProjectLinks.headOption.map(_.roadwayNumber).get, givenRoadwayNumber)
    } else {
      val originalAddresses = getRoadAddressesByRoadwayIds(originalHistorySection.map(_.roadwayId))
      val isSameAddressLengthSection = (continuousProjectLinks.last.endAddrMValue - continuousProjectLinks.head.startAddrMValue) == (originalAddresses.last.endAddrMValue - originalAddresses.head.startAddrMValue)

      if (isSameAddressLengthSection)
        (continuousProjectLinks.headOption.map(_.roadwayNumber).get, givenRoadwayNumber)
      else
        (givenRoadwayNumber, Sequences.nextRoadwayNumber)
    }
    roadwayNumbers
  }

  private def assignRoadwayNumbersInContinuousSection(links: Seq[ProjectLink], givenRoadwayNumber: Long): Seq[ProjectLink] = {
    val roadwayNumber = links.headOption.map(_.roadwayNumber).getOrElse(NewIdValue)
    val firstLinkStatus = links.headOption.map(_.status).getOrElse(LinkStatus.Unknown)
    val originalHistorySection = if (firstLinkStatus == LinkStatus.New) Seq() else links.takeWhile(pl => pl.roadwayNumber == roadwayNumber)
    val continuousRoadwayNumberSection =
      if (firstLinkStatus == LinkStatus.New)
        links.takeWhile(pl => pl.status.equals(LinkStatus.New)).sortBy(_.startAddrMValue)
      else
        links.takeWhile(pl => pl.roadwayNumber == roadwayNumber).sortBy(_.startAddrMValue)

    val (assignedRoadwayNumber, nextRoadwayNumber) = assignProperRoadwayNumber(continuousRoadwayNumberSection, givenRoadwayNumber, originalHistorySection)
    val rest = links.drop(continuousRoadwayNumberSection.size)
    continuousRoadwayNumberSection.map(pl => pl.copy(roadwayNumber = assignedRoadwayNumber)) ++
      (if (rest.isEmpty) Seq() else assignRoadwayNumbersInContinuousSection(rest, nextRoadwayNumber))
  }

  private def continuousRoadwaySection(seq: Seq[ProjectLink], givenRoadwayNumber: Long): (Seq[ProjectLink], Seq[ProjectLink]) = {
    val track = seq.headOption.map(_.track).getOrElse(Track.Unknown)
    val roadType = seq.headOption.map(_.roadType.value).getOrElse(0)

    val continuousProjectLinks =
        seq.takeWhile(pl => pl.track == track && pl.roadType.value == roadType).sortBy(_.startAddrMValue)

    val assignedContinuousSection = assignRoadwayNumbersInContinuousSection(continuousProjectLinks, givenRoadwayNumber)
    (assignedContinuousSection, seq.drop(assignedContinuousSection.size))
  }

  private def calculateSectionAddressValues(sections: Seq[CombinedSection],
                                            userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): Seq[CombinedSection] = {

    def adjustTracksToMatch(leftLinks: Seq[ProjectLink], rightLinks: Seq[ProjectLink], previousStart: Option[Long]): (Seq[ProjectLink], Seq[ProjectLink]) = {

      def adjustTwoTrackRoadwayNumbers(firstRight: Seq[ProjectLink], restRight: Seq[ProjectLink], firstLeft: Seq[ProjectLink], restLeft: Seq[ProjectLink])
      : ((Seq[ProjectLink], Seq[ProjectLink]), (Seq[ProjectLink], Seq[ProjectLink])) = {
        val (transferLinks, newLinks) = if (firstRight.exists(_.status == LinkStatus.Transfer)) (firstRight, firstLeft) else (firstLeft, firstRight)
        val groupedTransfer: ListMap[Long, Seq[ProjectLink]] = ListMap(transferLinks.groupBy(_.roadwayNumber).toSeq.sortBy(r => r._2.minBy(_.startAddrMValue).startAddrMValue): _*)

          if(newLinks.size >= groupedTransfer.size && newLinks.size >= groupedTransfer.values.flatten.size){

            val (equatedNewLinks, restNewLinks) = groupedTransfer.foldLeft(Seq.empty[ProjectLink], newLinks) {
              case ((adjustedLinks, linksToProcess), group) =>
                val newRoadwayNumber = Sequences.nextRoadwayNumber
                val links = linksToProcess.take(group._2.size).map(l => l.copy(roadwayNumber = newRoadwayNumber))
                val rest = linksToProcess.drop(group._2.size)
                (adjustedLinks ++ links.init :+ links.last.copy(connectedLinkId = Some(links.last.linkId), endAddrMValue = group._2.last.endAddrMValue), if(rest.nonEmpty)
                  rest.head.copy(startAddrMValue = group._2.last.endAddrMValue) +: rest.tail else rest)
            }
            val adjustedNewLinks = equatedNewLinks ++ restNewLinks.map(l => l.copy(roadwayNumber = equatedNewLinks.last.roadwayNumber))

            val (right, left) = if (adjustedNewLinks.exists(_.track == Track.RightSide)) (adjustedNewLinks, transferLinks) else (transferLinks, adjustedNewLinks)
            ((right, restRight), (left, restLeft))

          } else if (newLinks.size >= groupedTransfer.size && newLinks.size < groupedTransfer.values.flatten.size){

            val transferLength: Double = groupedTransfer.values.flatten.map(l => l.endMValue - l.startMValue).sum
            val newLinksMValues = newLinks.map(l => l.endMValue - l.startMValue).sum
            val assignedNewLinks = specialCaseSplitLinks(groupedTransfer, Seq(), newLinks, Seq(), transferLength, newLinksMValues, groupedTransfer.size)

            val (right, left) = if (assignedNewLinks.exists(_.track == Track.RightSide)) (assignedNewLinks, transferLinks) else (transferLinks, assignedNewLinks)
            ((right, restRight), (left, restLeft))

          } else {

            val transferLength: Double = groupedTransfer.values.flatten.map(l => l.endMValue - l.startMValue).sum
            val newLinksMValues = newLinks.map(l => l.endMValue - l.startMValue).sum
            val splitedLinks = splitLinks(groupedTransfer, Seq(), newLinks, Seq(),
              transferLength, newLinksMValues, groupedTransfer.size-newLinks.size)

            val (right, left) = if (splitedLinks.exists(_.track == Track.RightSide)) (splitedLinks, transferLinks) else (transferLinks, splitedLinks)
            ((right, restRight), (left, restLeft))

          }
      }

      def specialCaseSplitLinks(remainingTransfer: ListMap[Long, Seq[ProjectLink]], processedTransfer: Seq[ProjectLink], remainingNew: Seq[ProjectLink], processedNew: Seq[ProjectLink], totalTransferMLength: Double, totalNewMLength: Double, missingRoadwayNumbers: Int) : Seq[ProjectLink] = {
        if(missingRoadwayNumbers == 0 || (remainingNew.nonEmpty && remainingTransfer.isEmpty)){
          val nextRoadwayNumber = Sequences.nextRoadwayNumber
          processedNew ++ remainingNew.map(_.copy(roadwayNumber = nextRoadwayNumber))
        } else if(remainingNew.isEmpty && remainingTransfer.isEmpty) {
          processedNew
        } else if (remainingNew.isEmpty && remainingTransfer.nonEmpty){
          //TODO cenas
          Seq()
        } else {
          val threshHoldMaxDistance = 2

          //transfer m length coeff
          val groupTransferMLength = remainingTransfer.head._2.map(l => l.endMValue - l.startMValue).sum

          val lowerAllowedTransferGroupCoeff = (groupTransferMLength-threshHoldMaxDistance)/totalTransferMLength
          val biggerAllowedTransferGroupCoeff = (groupTransferMLength+threshHoldMaxDistance)/totalTransferMLength

          //new m length coeff
          val processedNewToBeAssigned = processedNew.filter(_.roadwayNumber == NewIdValue)
          val processingLength: Double = if(processedNewToBeAssigned.isEmpty){ remainingNew.head.endMValue - remainingNew.head.startMValue
              } else {
                (remainingNew.head.endMValue - remainingNew.head.startMValue) +
                  processedNewToBeAssigned.map(l => l.endMValue - l.startMValue).sum
              }
          val currentNewLinksGroupCoeff: Double = processingLength/totalNewMLength
          if(lowerAllowedTransferGroupCoeff <= currentNewLinksGroupCoeff && currentNewLinksGroupCoeff <= biggerAllowedTransferGroupCoeff){
            specialCaseSplitLinks(remainingTransfer.tail, processedTransfer ++ remainingTransfer.head._2, remainingNew.tail, processedNew:+remainingNew.head,
              totalTransferMLength, totalNewMLength, missingRoadwayNumbers - 1)
          } else if(lowerAllowedTransferGroupCoeff > currentNewLinksGroupCoeff){
            specialCaseSplitLinks(remainingTransfer, processedTransfer, remainingNew.tail, processedNew:+remainingNew.head,
              totalTransferMLength, totalNewMLength, missingRoadwayNumbers)
          } else {
            /*
            calculate missing geometry left to fulfill the exactly groupTransfer coefficient
             */
            //by that we want to pick previous processedLinks
            val processedNewToBeAssigned = processedNew.filter(_.roadwayNumber == NewIdValue)
            val previousProcessed = if(processedNewToBeAssigned.isEmpty) Seq() else processedNewToBeAssigned
            val linkToBeSplited = remainingNew.head
            //get their length
            val previousProcessedLength: Double = if(previousProcessed.isEmpty){ remainingNew.head.endMValue - remainingNew.head.startMValue
            } else {
              previousProcessed.map(l => l.endMValue - l.startMValue).sum
            }

            //coeficient to be matched for current transfer group
            val perfectTransferGroupCoeff = groupTransferMLength/totalTransferMLength
            /*
              (previousProcessedLength+m)/totalNewMLength = perfectTransferGroupCoeff <=>
              <=> previousProcessedLength+m = perfectTransferGroupCoeff*totalNewMLength <=>
              <=> m = (perfectTransferGroupCoeff*totalNewMLength) - previousProcessedLength
             */
            val splitMValue = (perfectTransferGroupCoeff*totalNewMLength) - previousProcessedLength

            val firstSplitedEndAddr = remainingTransfer.head._2.last.endAddrMValue
            val firstSplitedLinkGeom = GeometryUtils.truncateGeometry2D(linkToBeSplited.geometry, 0.0, splitMValue)
            val secondSplitedLinkGeom = GeometryUtils.truncateGeometry2D(linkToBeSplited.geometry, splitMValue, linkToBeSplited.endMValue)

            //processedLinks without and with roadwayNumber
            val (unassignedRoadwayNumber, assignedRoadwayNumber) = processedNew.partition(_.roadwayNumber == NewIdValue)
            val nextRoadwayNumber = Sequences.nextRoadwayNumber
            val processedNewWithSplitedLink = assignedRoadwayNumber ++ unassignedRoadwayNumber.map(_.copy(roadwayNumber = nextRoadwayNumber)) :+linkToBeSplited.copy(endMValue = splitMValue,
              geometry = firstSplitedLinkGeom, geometryLength = GeometryUtils.geometryLength(firstSplitedLinkGeom), endAddrMValue = firstSplitedEndAddr,
              roadwayNumber = nextRoadwayNumber, connectedLinkId = Some(linkToBeSplited.linkId))

            specialCaseSplitLinks(remainingTransfer.tail, processedTransfer++remainingTransfer.head._2, linkToBeSplited.copy(id = NewIdValue, startMValue = splitMValue,
              geometry = secondSplitedLinkGeom, geometryLength = GeometryUtils.geometryLength(secondSplitedLinkGeom), startAddrMValue = firstSplitedEndAddr) +: remainingNew.tail , processedNewWithSplitedLink
              , totalTransferMLength, totalNewMLength, missingRoadwayNumbers-1)
          }
        }
      }

      /**
        *
        * @param remainingTransfer
        * @param processedTransfer
        * @param remainingNew
        * @param processedNew
        * @return New links with proper same different roadwayNumbers as the opposite Transfer track
        */
      def splitLinks(remainingTransfer: ListMap[Long, Seq[ProjectLink]], processedTransfer: Seq[ProjectLink], remainingNew: Seq[ProjectLink], processedNew: Seq[ProjectLink], totalTransferMLength: Double, totalNewMLength: Double, missingRoadwayNumbers: Int) : Seq[ProjectLink] = {
        if(missingRoadwayNumbers == 0){
          val nextRoadwayNumber = Sequences.nextRoadwayNumber
          processedNew ++ remainingNew.map(_.copy(roadwayNumber = nextRoadwayNumber))
        } else if(remainingNew.isEmpty && remainingTransfer.isEmpty) {
          processedNew
        } else if (remainingNew.isEmpty && remainingTransfer.nonEmpty){
          splitLinks(remainingTransfer, processedTransfer, remainingNew:+processedNew.last, processedNew.init,
            totalTransferMLength, totalNewMLength, missingRoadwayNumbers)
        } else {
          val threshHoldMaxDistance = 2

          //transfer m length coeff
          val groupTransferMLength = remainingTransfer.head._2.map(l => l.endMValue - l.startMValue).sum

          val transferGroupCoeff = (groupTransferMLength-threshHoldMaxDistance)/totalTransferMLength

          //new m length coeff
          val currentLinkLength: Double = remainingNew.head.endMValue - remainingNew.head.startMValue
          val currentLinkCoeff: Double = currentLinkLength/totalNewMLength

          if(transferGroupCoeff>currentLinkCoeff){
            splitLinks(remainingTransfer, processedTransfer, remainingNew.tail, processedNew:+remainingNew.head,
              totalTransferMLength, totalNewMLength, missingRoadwayNumbers)
          } else {
            val firstSplitedEndAddr = remainingTransfer.head._2.last.endAddrMValue
//            val secondSplitedEndAddr = remainingTransfer.tail.head._2.last.endAddrMValue
            val firstSplitedEndMValue = transferGroupCoeff*currentLinkLength
            val firstSplitedLinkGeom = GeometryUtils.truncateGeometry2D(remainingNew.head.geometry, 0.0, firstSplitedEndMValue)
            val secondSplitedLinkGeom = GeometryUtils.truncateGeometry2D(remainingNew.head.geometry, firstSplitedEndMValue, remainingNew.head.endMValue)

            splitLinks(remainingTransfer.tail, processedTransfer++remainingTransfer.head._2, remainingNew.tail, processedNew++Seq(
              remainingNew.head.copy(endMValue = firstSplitedEndMValue,
                geometry = firstSplitedLinkGeom, geometryLength = GeometryUtils.geometryLength(firstSplitedLinkGeom), endAddrMValue = firstSplitedEndAddr,
                roadwayNumber = Sequences.nextRoadwayNumber, connectedLinkId = Some(remainingNew.head.linkId)),
              remainingNew.head.copy(id = NewIdValue, startMValue = firstSplitedEndMValue,
                geometry = secondSplitedLinkGeom, geometryLength = GeometryUtils.geometryLength(secondSplitedLinkGeom), startAddrMValue = firstSplitedEndAddr,
                /*endAddrMValue = secondSplitedEndAddr,*/ roadwayNumber = Sequences.nextRoadwayNumber, connectedLinkId = Some(remainingNew.head.linkId))),
              totalTransferMLength, totalNewMLength, missingRoadwayNumbers-1)
          }
        }
      }

      def adjustableToRoadwayNumberAttribution(firstRight: Seq[ProjectLink], restRight: Seq[ProjectLink], firstLeft: Seq[ProjectLink], restLeft: Seq[ProjectLink]): Boolean = {
        ((firstRight.forall(_.status == LinkStatus.New) && firstLeft.forall(_.status == LinkStatus.Transfer))
          || (firstRight.forall(_.status == LinkStatus.Transfer) && firstLeft.forall(_.status == LinkStatus.New)))
      }

      if (rightLinks.isEmpty && leftLinks.isEmpty) {
        (Seq(), Seq())
      } else {
        if (rightLinks.isEmpty || leftLinks.isEmpty) {
          throw new MissingTrackException(s"Missing track, R: ${rightLinks.size}, L: ${leftLinks.size}")
        }

        val right = continuousWOutRoadwayNumberSection(rightLinks)
        val left = continuousWOutRoadwayNumberSection(leftLinks)

        val ((firstRight, restRight), (firstLeft, restLeft)): ((Seq[ProjectLink], Seq[ProjectLink]), (Seq[ProjectLink], Seq[ProjectLink])) =
          if (adjustableToRoadwayNumberAttribution(right._1, right._2, left._1, left._2)) {
              adjustTwoTrackRoadwayNumbers(right._1, right._2, left._1, left._2)
          } else {
            val newRoadwayNumber1 = Sequences.nextRoadwayNumber
            val newRoadwayNumber2 = if (rightLinks.head.track == Track.Combined || leftLinks.head.track == Track.Combined) newRoadwayNumber1 else Sequences.nextRoadwayNumber
            (continuousRoadwaySection(rightLinks, newRoadwayNumber1),
              continuousRoadwaySection(leftLinks, newRoadwayNumber2))
          }

        if (firstRight.isEmpty || firstLeft.isEmpty)
          throw new RoadAddressException(s"Mismatching tracks, R ${firstRight.size}, L ${firstLeft.size}")

        val strategy = TrackCalculatorContext.getStrategy(firstLeft, firstRight)
        val trackCalcResult = strategy.assignTrackMValues(previousStart, firstLeft, firstRight, userDefinedCalibrationPoint)

        val (adjustedRestRight, adjustedRestLeft) = adjustTracksToMatch(trackCalcResult.restLeft ++ restLeft, trackCalcResult.restRight ++ restRight, Some(trackCalcResult.endAddrMValue))

        (trackCalcResult.leftProjectLinks ++ adjustedRestRight, trackCalcResult.rightProjectLinks ++ adjustedRestLeft)
      }
    }

    val rightSections = sections.flatMap(_.right.links)
    val leftSections = sections.flatMap(_.left.links)
    val rightLinks = ProjectSectionMValueCalculator.calculateMValuesForTrack(rightSections, userDefinedCalibrationPoint)
    val leftLinks = ProjectSectionMValueCalculator.calculateMValuesForTrack(leftSections, userDefinedCalibrationPoint)
    //adjustedRight and adjustedLeft already ordered by geometry -> TrackSectionOrder.orderProjectLinksTopologyByGeometry
    val (adjustedLeft, adjustedRight) = adjustTracksToMatch(leftLinks, rightLinks, None)
    val (right, left) = TrackSectionOrder.setCalibrationPoints(adjustedRight, adjustedLeft, userDefinedCalibrationPoint)
    TrackSectionOrder.createCombinedSections(right, left)
  }

  /**
    * Find starting point(s) after adding new operation for links in project.
    *
    * @param newLinks new ProjectLinks
    * @param oldLinks non-terminated already existing ProjectLinks
    * @param otherRoadPartLinks
    * @param calibrationPoints
    * @return Right and left starting points
    */
  def findStartingPoints(newLinks: Seq[ProjectLink], oldLinks: Seq[ProjectLink], otherRoadPartLinks: Seq[ProjectLink],
                         calibrationPoints: Seq[UserDefinedCalibrationPoint]): (Point, Point) = {
    val (rightStartPoint, pl) = findStartingPoint(newLinks.filter(_.track != Track.LeftSide), oldLinks.filter(_.track != Track.LeftSide), otherRoadPartLinks, calibrationPoints, (newLinks ++ oldLinks).filter(_.track == LeftSide))

    if ((oldLinks ++ newLinks).exists(l => GeometryUtils.areAdjacent(l.geometry, rightStartPoint) && l.track == Track.Combined)) {
      (rightStartPoint, rightStartPoint)
    } else {
      // Get left track non-connected points and find the closest to right track starting point
      val (leftLinks, rightLinks) = (newLinks ++ oldLinks).filterNot(_.track == Track.Combined).partition(_.track == Track.LeftSide)
      val chainEndPoints = TrackSectionOrder.findChainEndpoints(leftLinks)

      if (chainEndPoints.isEmpty)
        throw new MissingTrackException("Missing left track starting project links")

      val oldFirst = TrackSectionOrder.findOnceConnectedLinks(leftLinks).values.find(link => link.startAddrMValue == 0 && link.endAddrMValue != 0)
      val endPointsWithValues = chainEndPoints.filter(link => link._2.startAddrMValue == 0 && link._2.endAddrMValue != 0)

      (rightStartPoint,
        if (endPointsWithValues.size == 1) {
          val (linksWithValues, linksWithoutValues) = leftLinks.partition(_.endAddrMValue != 0)
          val otherEndPoint = chainEndPoints.filterNot(_._2.id == endPointsWithValues.head._2.id)
          val onceConnectLinks = TrackSectionOrder.findOnceConnectedLinks(linksWithoutValues)
          if (endPointsWithValues.nonEmpty && onceConnectLinks.nonEmpty && linksWithValues.size == 1 &&
            onceConnectLinks.exists(connected => GeometryUtils.areAdjacent(connected._2.getEndPoints._2, endPointsWithValues.head._2.getEndPoints._1)
              || GeometryUtils.areAdjacent(connected._2.getEndPoints._1, endPointsWithValues.head._2.getEndPoints._1)))
            otherEndPoint.head._1
          else
            endPointsWithValues.head._1
        } else if (chainEndPoints.forall(_._2.endAddrMValue != 0) && oldFirst.isDefined) {
          oldFirst.get.getEndPoints._1
        } else {
          if (leftLinks.forall(_.endAddrMValue == 0) && rightLinks.nonEmpty && rightLinks.exists(_.endAddrMValue != 0)) {
            val rightStartPoint = TrackSectionOrder.findChainEndpoints(rightLinks).find(link => link._2.startAddrMValue == 0 && link._2.endAddrMValue != 0)
            chainEndPoints.minBy(p => p._2.geometry.head.distance2DTo(rightStartPoint.get._1))._1
          } else if (leftLinks.forall(_.endAddrMValue == 0) && rightLinks.forall(_.endAddrMValue == 0)) {
            val candidateEndPoint = chainEndPoints.minBy(p => p._1.distance2DTo(rightStartPoint))._1
            val rightSideEndPoint = Seq(pl.getEndPoints._1, pl.getEndPoints._2).filterNot(_ == rightStartPoint)
            val direction = Seq(pl).map(p => p.getEndPoints._2 - p.getEndPoints._1).fold(Vector3d(0, 0, 0)) { case (v1, v2) => v1 + v2 }.normalize2D()
            val candidateLeftStartPoint = TrackSectionOrder.findChainEndpoints(leftLinks).minBy(_._1.distance2DTo(rightStartPoint))
            val candidateLeftOppositeEnd = getOppositeEnd(candidateLeftStartPoint._2, candidateLeftStartPoint._1)
            val startingPointsVector = Vector3d(candidateLeftOppositeEnd.x - candidateLeftStartPoint._1.x, candidateLeftOppositeEnd.y - candidateLeftStartPoint._1.y, candidateLeftOppositeEnd.z - candidateLeftStartPoint._1.z)
            val angle = startingPointsVector.angleXYWithNegativeValues(direction)
            if (candidateEndPoint.distance2DTo(rightStartPoint) > candidateEndPoint.distance2DTo(rightSideEndPoint.head) && angle > 0) {
              chainEndPoints.filterNot(_._1 == candidateEndPoint).head._1
            } else {
              candidateEndPoint
            }
          } else {
            val startPoint1 = chainEndPoints.minBy(p => p._1.distance2DTo(rightStartPoint))._1
            val startPoint2 = chainEndPoints.maxBy(p => p._1.distance2DTo(rightStartPoint))._1
            val connectingPoint = otherRoadPartLinks.find(l => GeometryUtils.areAdjacent(l.getLastPoint, startPoint1) || GeometryUtils.areAdjacent(l.getFirstPoint, startPoint2))
            if (otherRoadPartLinks.isEmpty || connectingPoint.nonEmpty) {
              startPoint1
            } else {
              chainEndPoints.maxBy(p => p._1.distance2DTo(rightStartPoint))._1
            }
          }
        }
      )
    }
  }


  /**
    * Find a starting point for this road part.
    *
    * @param newLinks          Status = New links that need to have an address
    * @param oldLinks          Other non-terminated links that already existed before the current operation
    * @param calibrationPoints The calibration points set by user as fixed addresses
    * @return Starting point
    */
  private def findStartingPoint(newLinks: Seq[ProjectLink], oldLinks: Seq[ProjectLink], otherRoadPartLinks: Seq[ProjectLink],
                                calibrationPoints: Seq[UserDefinedCalibrationPoint], oppositeTrackLinks: Seq[ProjectLink]): (Point, ProjectLink) = {

    def calibrationPointToPoint(calibrationPoint: UserDefinedCalibrationPoint): Option[(Point, ProjectLink)] = {
      val link = oldLinks.find(_.id == calibrationPoint.projectLinkId).orElse(newLinks.find(_.id == calibrationPoint.projectLinkId))
      link.flatMap(pl => GeometryUtils.calculatePointFromLinearReference(pl.geometry, calibrationPoint.segmentMValue).map(p => (p, pl)))
    }

    // Pick the one with calibration point set to zero: or any old link with lowest address: or new links by direction
    calibrationPoints.find(_.addressMValue == 0).flatMap(calibrationPointToPoint).getOrElse(
      oldLinks.filter(_.status == LinkStatus.UnChanged).sortBy(_.startAddrMValue).headOption.map(pl => (pl.startingPoint, pl)).getOrElse {
        val remainLinks = oldLinks ++ newLinks
        if (remainLinks.isEmpty)
          throw new MissingTrackException("Missing right track starting project links")
        // Grab all the endpoints of the links
        val directionLinks = if (remainLinks.exists(_.sideCode != SideCode.Unknown)) remainLinks.filter(_.sideCode != SideCode.Unknown) else remainLinks

        val direction = directionLinks.map(p => p.getEndPoints._2 - p.getEndPoints._1).fold(Vector3d(0, 0, 0)) { case (v1, v2) => v1 + v2 }.normalize2D()

        val points = remainLinks.map(pl => pl.getEndPoints)

        // Approximate estimate of the mid point: averaged over count, not link length
        val midPoint = points.map(p => p._1 + (p._2 - p._1).scale(0.5)).foldLeft(Vector3d(0, 0, 0)) { case (x, p) =>
          (p - Point(0, 0)).scale(1.0 / points.size) + x
        }
        val chainEndPoints = TrackSectionOrder.findChainEndpoints(remainLinks)
        val (linksWithValues, linksWithoutValues) = remainLinks.partition(_.endAddrMValue != 0)
        val endPointsWithValues = ListMap(chainEndPoints.filter(link => link._2.startAddrMValue >= 0 && link._2.endAddrMValue != 0).toSeq
          .sortWith(_._2.startAddrMValue < _._2.startAddrMValue): _*)

        val foundConnectedLinks = TrackSectionOrder.findOnceConnectedLinks(remainLinks).values.filter(link => link.startAddrMValue == 0 && link.endAddrMValue != 0)

        // In case there is some old starting link, we want to prioritize the one that didn't change or was not treated yet.
        // We could have more than two starting link since one of them can be Transferred from any part to this one.
        val oldFirst: Option[ProjectLink] =
        if (foundConnectedLinks.nonEmpty) {
          foundConnectedLinks.find(_.status == LinkStatus.New).orElse(foundConnectedLinks.find(l => l.status == LinkStatus.UnChanged || l.status == LinkStatus.NotHandled))
            .orElse(foundConnectedLinks.headOption)
        } else {
          None
        }
        if (endPointsWithValues.size == 1) {
          val endLinkWithValues = endPointsWithValues.head._2
          val (currentEndPoint, otherEndPoint) = chainEndPoints.partition(_._2.id == endPointsWithValues.head._2.id)
          val onceConnectLinks = TrackSectionOrder.findOnceConnectedLinks(linksWithoutValues)
          val existsCloserProjectlink = linksWithValues.filter(pl => pl.startAddrMValue < endLinkWithValues.startAddrMValue && pl.id != endLinkWithValues.id)
          if (endPointsWithValues.nonEmpty && onceConnectLinks.nonEmpty && linksWithValues.nonEmpty
            && (oldFirst.isDefined && points.count(p => GeometryUtils.areAdjacent(p._1, oldFirst.get.startingPoint)
            || GeometryUtils.areAdjacent(p._2, oldFirst.get.startingPoint)) > 1) // New links before the old starting point
            && (onceConnectLinks.exists(connected => GeometryUtils.areAdjacent(connected._2.getEndPoints._2, endPointsWithValues.head._2.getEndPoints._1)
            || GeometryUtils.areAdjacent(connected._2.getEndPoints._1, endPointsWithValues.head._2.getEndPoints._1)
            || GeometryUtils.areAdjacent(linksWithValues.minBy(_.startAddrMValue).geometry, connected._2.getEndPoints._2)) || existsCloserProjectlink.nonEmpty)
          ) {
            otherEndPoint.head
          } else {
            if (currentEndPoint.head._1 == endPointsWithValues.head._2.endPoint)
              otherEndPoint.head
            else
              endPointsWithValues.head
          }
        } else if (chainEndPoints.forall(_._2.endAddrMValue != 0) && oldFirst.isDefined) {
          (oldFirst.get.getEndPoints._1, oldFirst.get)
        } else {
          if (remainLinks.forall(_.endAddrMValue == 0) && oppositeTrackLinks.nonEmpty && oppositeTrackLinks.exists(_.endAddrMValue != 0)) {
            val leftStartPoint = TrackSectionOrder.findChainEndpoints(oppositeTrackLinks).find(link => link._2.startAddrMValue == 0 && link._2.endAddrMValue != 0)
            chainEndPoints.minBy(p => p._2.geometry.head.distance2DTo(leftStartPoint.get._1))
          } else if (remainLinks.nonEmpty && oppositeTrackLinks.nonEmpty && remainLinks.forall(_.endAddrMValue == 0) && oppositeTrackLinks.forall(_.endAddrMValue == 0)) {
            val candidateRightStartPoint = chainEndPoints.minBy(p => direction.dot(p._1.toVector - midPoint))
            val candidateRightOppositeEnd = getOppositeEnd(candidateRightStartPoint._2, candidateRightStartPoint._1)
            val candidateLeftStartPoint = TrackSectionOrder.findChainEndpoints(oppositeTrackLinks).minBy(_._1.distance2DTo(candidateRightStartPoint._1))
            val candidateLeftOppositeEnd = getOppositeEnd(candidateLeftStartPoint._2, candidateLeftStartPoint._1)
            val startingPointsVector = Vector3d(candidateRightOppositeEnd.x - candidateLeftOppositeEnd.x, candidateRightOppositeEnd.y - candidateLeftOppositeEnd.y, candidateRightOppositeEnd.z - candidateLeftOppositeEnd.z)
            val angle =
              if (startingPointsVector == Vector3d(0.0, 0.0, 0.0)) {
                val startingPointVector = Vector3d(candidateRightStartPoint._1.x - candidateLeftStartPoint._1.x, candidateRightStartPoint._1.y - candidateLeftStartPoint._1.y, candidateRightStartPoint._1.z - candidateLeftStartPoint._1.z)
                startingPointVector.angleXYWithNegativeValues(direction)
              } else {
                startingPointsVector.angleXYWithNegativeValues(direction)
              }
            if (angle > 0) {
              chainEndPoints.filterNot(_._1.equals(candidateRightStartPoint._1)).head
            } else {
              candidateRightStartPoint
            }
          } else {
            val startPoint1 = chainEndPoints.minBy(p => direction.dot(p._1.toVector - midPoint))
            val startPoint2 = chainEndPoints.maxBy(p => direction.dot(p._1.toVector - midPoint))
            val connectingPoint = otherRoadPartLinks.find(l => GeometryUtils.areAdjacent(l.getLastPoint, startPoint1._1) || GeometryUtils.areAdjacent(l.getFirstPoint, startPoint2._1))
            if (otherRoadPartLinks.isEmpty || connectingPoint.nonEmpty) {
              startPoint1
            } else {
              chainEndPoints.maxBy(p => direction.dot(p._1.toVector - midPoint))
            }
          }
        }
      }
    )
  }

  private def getOppositeEnd(link: BaseRoadAddress, point: Point): Point = {
    val (st, en) = link.getEndPoints
    if (st.distance2DTo(point) < en.distance2DTo(point)) en else st
  }
}
