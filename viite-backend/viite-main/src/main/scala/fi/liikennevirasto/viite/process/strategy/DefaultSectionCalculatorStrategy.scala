package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.{MissingRoadwayNumberException, MissingTrackException, RoadAddressException}
import fi.liikennevirasto.viite.{ContinuousAddressCapErrorMessage, LengthMismatchErrorMessage, NegativeLengthErrorMessage, NewIdValue, ProjectValidationException, ProjectValidator, UnsuccessfulRecalculationMessage}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.UserDefinedCP
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.process._
import fi.liikennevirasto.viite.process.strategy.FirstRestSections.{getUpdatedContinuousRoadwaySections, lengthCompare}
import fi.liikennevirasto.viite.util.TwoTrackRoadUtils
import fi.liikennevirasto.viite.util.TwoTrackRoadUtils._
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point, Vector3d}
import fi.vaylavirasto.viite.model.{Discontinuity, LinkStatus, SideCode, Track}
import org.slf4j.LoggerFactory

import scala.collection.immutable.ListMap

class DefaultSectionCalculatorStrategy extends RoadAddressSectionCalculatorStrategy {

  private val logger = LoggerFactory.getLogger(getClass)

  override val name: String = "Normal Section"

  val projectValidator = new ProjectValidator
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  // TODO: Check this need
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
            sec.right.links ++ sec.left.links.filterNot(_.track == Track.Combined) // Remove dublicated
          }
        }
      } catch {
        case ex @ (_: MissingTrackException | _: MissingRoadwayNumberException) =>
          logger.warn(ex.getMessage)
          throw ex
        case ex: InvalidAddressDataException =>
          logger.warn(s"Can't calculate road/road part ${part._1}/${part._2}: " + ex.getMessage)
          throw ex
        case ex: NoSuchElementException =>
          logger.error("Delta calculation failed: " + ex.getMessage, ex)
          throw ex
        case ex: NullPointerException =>
          logger.error("Delta calculation failed (NPE)", ex)
          throw ex
        case ex: Exception =>
          logger.error("Delta calculation not possible: " + ex.getMessage)
          throw ex
      }
    }.toSeq
  }

  def assignProperRoadwayNumber(continuousProjectLinks: Seq[ProjectLink], givenRoadwayNumber: Long, originalHistorySection: Seq[ProjectLink]): (Long, Long) = {
    def getRoadAddressesByRoadwayIds(roadwayIds: Seq[Long]): Option[Roadway] = {
      val roadways = roadwayDAO.fetchAllByRoadwayId(roadwayIds)
      if (roadways.size > 1) {
        throwExceptionWithErrorInfo(originalHistorySection, s"Expected 1 Roadway got: ${roadways.toList.map(_.id)}")
      }
      roadways.headOption
    }

    val roadwayNumbers = if (continuousProjectLinks.nonEmpty && continuousProjectLinks.exists(_.status == LinkStatus.New)) {
      // then we now that for sure the addresses increased their length for the part => new roadwayNumber for the new sections
      (givenRoadwayNumber, Sequences.nextRoadwayNumber)
    } else if (continuousProjectLinks.nonEmpty && continuousProjectLinks.exists(_.status == LinkStatus.Numbering)) {
      // then we know for sure that the addresses didn't change the address length part, only changed the number of road or part => same roadwayNumber
      (continuousProjectLinks.headOption.map(_.roadwayNumber).get, givenRoadwayNumber)
    } else {
      val originalAddresses = getRoadAddressesByRoadwayIds(originalHistorySection.map(_.roadwayId))

      val isSameAddressLengthSection = {
        if(originalAddresses.isEmpty || continuousProjectLinks.isEmpty) {
          false
        } else {
          (continuousProjectLinks.last.endAddrMValue - continuousProjectLinks.head.startAddrMValue) == (originalAddresses.get.endAddrMValue - originalAddresses.get.startAddrMValue)
        }
      }

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
    val track =
      seq.headOption.map(_.track).getOrElse(Track.Unknown)
    val discontinuity =
      seq.head.discontinuity
    val administrativeClass =
      seq.head.administrativeClass
    val continuousProjectLinks =
      seq.takeWhile(pl => pl.track == track && pl.discontinuity == discontinuity && pl.administrativeClass == administrativeClass)
    def canIncludeNonContinuous: Boolean = {
      val nextLink = seq(continuousProjectLinks.size)
      nextLink.track == track && nextLink.administrativeClass == administrativeClass && nextLink.discontinuity != Discontinuity.Continuous
    }
    val continuousProjectLinksWithDiscontinuity =
      if (seq.size > continuousProjectLinks.size && canIncludeNonContinuous)
        seq.take(continuousProjectLinks.size+1)
      else
        continuousProjectLinks
    val assignedContinuousSection =
      assignRoadwayNumbersInContinuousSection(continuousProjectLinksWithDiscontinuity, givenRoadwayNumber)
    (assignedContinuousSection, seq.drop(continuousProjectLinksWithDiscontinuity.size))
  }

  /***
   * Fetch project name by project id for logging purpose.
   * @param projectId
   * @return Project name for the projectId.
   */
  def getProjectNameForLogger(projectId: Long): String = {
    val projectDAO: ProjectDAO = new ProjectDAO
    val project                = projectDAO.fetchById(projectId)
    project match {
      case Some(p) => p.name
      case None => ""
    }
  }

  /***
   * Helper function for logging throwing an exception.
   * Used by calculation helper functions.
   * @param errorLinks ProjectLinks causing the error.
   * @param msg Error message for debug console.
   */
  def throwExceptionWithErrorInfo(errorLinks: Iterable[ProjectLink], msg: String): Nothing = {
    val projectId   = errorLinks.head.projectId
    val projectName = getProjectNameForLogger(projectId)
    logger.error(
      s"""$msg
         |$projectName $projectId
         | Error links:
         | $errorLinks""".stripMargin)
    throw new RoadAddressException(UnsuccessfulRecalculationMessage)
  }

  /***
   * Intented use after all calculation.
   * Checks combined track links have equal addressess after left side and right side travel.
   * @param leftAdj Calculated leftside ProjectLinks.
   * @param rightAdj Calculated leftside ProjectLinks.
   */
  def validateCombinedLinksEqualAddresses(leftAdj: Seq[ProjectLink], rightAdj: Seq[ProjectLink]): Unit = {
    val leftCombinedLinks  = leftAdj.filter(_.track == Track.Combined)
    val rightCombinedLinks = rightAdj.filter(_.track == Track.Combined)
    // Partition splitted and nonsplitted links. With splitted projectlinks id:s change and matching must be made by other values combined.
    val (allSplitted, nonSplitted) = (leftCombinedLinks ++ rightCombinedLinks).partition(_.isSplit)
    val groupedById        = nonSplitted.groupBy(pl => pl.id) ++ allSplitted.groupBy(pl => (pl.startAddrMValue, pl.endAddrMValue,pl.linkId))

    if (!groupedById.values.forall(_.size == 2)) {
      val falsePls = groupedById.filter(pls => pls._2.size != 2).values.flatten
      val msg      = "Combined links pairing mismatch in project"
      throwExceptionWithErrorInfo(falsePls, msg)
    }
    if (!groupedById.values.forall(pl => pl.head.startAddrMValue == pl.last.startAddrMValue)) {
      val falsePls = groupedById.filter(pls => pls._2.head.startAddrMValue != pls._2.last.startAddrMValue).values.flatten
      val msg      = "Combined track start address mismatch in project"
      throwExceptionWithErrorInfo(falsePls, msg)
    }
    if (!groupedById.values.forall(pl => pl.head.endAddrMValue == pl.last.endAddrMValue)) {
      val falsePls = groupedById.filter(pls => pls._2.head.endAddrMValue != pls._2.last.endAddrMValue).values.flatten
      val msg      = "Combined track end address mismatch in project"
      throwExceptionWithErrorInfo(falsePls, msg)
    }
  }

  /***
   * Checks splitted links have continuos m-values.
   * Splitting has not caused wrong ordering.
   * @param pls ProjectLinks of left or right side with combined track.
   */
  def validateMValuesOfSplittedLinks(pls: Seq[ProjectLink]): Unit = {
    if (pls.size > 1) {
      val it = pls.sliding(2)
      while (it.hasNext) {
        it.next() match {
          case Seq(first, next) => {
            if (first.connectedLinkId.isDefined && next.connectedLinkId.isDefined && first.connectedLinkId == next.connectedLinkId)
              if (!(first.endMValue == next.startMValue || first.startMValue == next.endMValue)) {
                val falsePls = Iterable(first, next)
                val msg      = "Discontinuity in splitted links endMValue and startMValue in project"
                throwExceptionWithErrorInfo(falsePls, msg)
            }
          }
        }
      }
    }
  }

  /***
   * Checks continuity of addresses, positivity of length and length is preserved.
   * @param pls Left or right side ProjectLinks with combined to check for continuity of addresses.
   */
  def validateAddresses(pls: Seq[ProjectLink]): Unit = {
    if (pls.size > 1 && pls.head.originalStartAddrMValue == 0) {
      val maxDiffForChange = 2 // i.e. caused by average calculation
      val it = pls.sliding(2)
      while (it.hasNext) {
        it.next() match {
          case Seq(curr, next) => {
            if (curr.endAddrMValue != next.startAddrMValue) {
              logger.error(s"Address not continuous: ${curr.endAddrMValue} ${next.startAddrMValue} linkIds: ${curr.linkId} ${next.linkId}")
              throw new RoadAddressException(ContinuousAddressCapErrorMessage)
            }
            if (!(curr.endAddrMValue > curr.startAddrMValue)) {
              logger.error(s"Address length negative. linkId: ${curr.linkId}")
              throw new RoadAddressException(NegativeLengthErrorMessage.format(curr.linkId))
            }
            if (curr.status != LinkStatus.New && (curr.originalTrack == curr.track || curr.track == Track.Combined) && !(Math.abs((curr.endAddrMValue - curr.startAddrMValue) - (curr.originalEndAddrMValue - curr.originalStartAddrMValue)) < maxDiffForChange)) {
              logger.error(s"Length mismatch. New: ${curr.startAddrMValue} ${curr.endAddrMValue} original: ${curr.originalStartAddrMValue} ${curr.originalEndAddrMValue} linkId: ${curr.linkId}")
              throw new RoadAddressException(LengthMismatchErrorMessage.format(curr.linkId, maxDiffForChange - 1))
            }
            /* VIITE-2957
            Replacing the above if-statement with the one commented out below enables a user to bypass the RoadAddressException.
            This may be needed in certain cases.
            Example:
              An administrative class change is done on a two-track road section where the original startAddrMValue of ProjectLinks on adjacent
              tracks differ by more than 2. As the administrative class must be unambiguous at any given road address, the startAddrMValue
              has to be adjusted to be equal on both of the tracks at the point of the administrative class change. This would cause a change
              in length that was greater than 1 on one of the tracks.
            if (curr.status != LinkStatus.New && (curr.originalTrack == curr.track ||
              curr.track == Track.Combined) &&
              !(Math.abs((curr.endAddrMValue - curr.startAddrMValue) - (curr.originalEndAddrMValue - curr.originalStartAddrMValue)) < maxDiffForChange)) {
              logger.warn(s"Length mismatch. " +
                s"Project id: ${curr.projectId} ${projectDAO.fetchById(projectId = curr.projectId).get.name} " +
                s"New: ${curr.startAddrMValue} ${curr.endAddrMValue} " +
                s"original: ${curr.originalStartAddrMValue} ${curr.originalEndAddrMValue} " +
                s"linkId: ${curr.linkId} " +
                s"length change ${(curr.endAddrMValue - curr.startAddrMValue) - (curr.originalEndAddrMValue - curr.originalStartAddrMValue)}")
            }
            */
          }
        }
      }
    }
  }

  /** TODO
   * Checks continuity of addresses and geometry.
   * @param pls Left or right side ProjectLinks with combined to check for continuity.
   */
  def validateAddressesWithGeometry(pls: Seq[ProjectLink]): Unit = {
    val it = pls.sliding(2)
    while (it.hasNext) {
      it.next() match {
        case Seq(curr, next) => {
          if (curr.discontinuity == Discontinuity.Continuous && !curr.connected(next)) {
            logger.error(s"Address geometry mismatch. linkIds: ${curr.linkId} ${next.linkId}")
          }
        }
        case _ =>
      }
    }
  }

  /**
    * Check validation errors for ProjectLinks.
    * Used here in case of calculation error to check if user has entered incompatible values.
    *
    * @param projectlinks ProjectLinks from calculation
    * @return sequence of ValidationErrorDetails if any
    */
  def checkForValidationErrors(projectlinks: Seq[ProjectLink]): Seq[projectValidator.ValidationErrorDetails] = {
    val validationErrors = projectValidator.projectLinksNormalPriorityValidation(projectDAO.fetchById(projectlinks.head.projectId).get, projectlinks)
    if (validationErrors.nonEmpty)
      validationErrors
    else Seq()
  }

  private def calculateSectionAddressValues(sections: Seq[CombinedSection],
                                            userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): Seq[CombinedSection] = {

    def adjustTracksToMatch(leftLinks: Seq[ProjectLink], rightLinks: Seq[ProjectLink], previousStart: Option[Long], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): (Seq[ProjectLink], Seq[ProjectLink]) = {
      if (rightLinks.isEmpty && leftLinks.isEmpty) {
        (Seq(), Seq())
      } else {
        if (rightLinks.isEmpty || leftLinks.isEmpty) {
          val validationErrors = checkForValidationErrors(rightLinks ++ leftLinks)
          if (validationErrors.nonEmpty)
            throw new ProjectValidationException(s"Validation errors", validationErrors.map(projectValidator.errorPartsToApi))
          else
            throw new MissingTrackException(s"Missing track, R: ${rightLinks.size}, L: ${leftLinks.size}")
        }

        val ((firstRight, restRight), (firstLeft, restLeft)): ((Seq[ProjectLink], Seq[ProjectLink]), (Seq[ProjectLink], Seq[ProjectLink])) = {
          val newRoadwayNumber1         = Sequences.nextRoadwayNumber
          val newRoadwayNumber2         = if (rightLinks.head.track == Track.Combined || leftLinks.head.track == Track.Combined) newRoadwayNumber1 else Sequences.nextRoadwayNumber
          val continuousRoadwaySections = (continuousRoadwaySection(rightLinks, newRoadwayNumber1), continuousRoadwaySection(leftLinks, newRoadwayNumber2))
          val rightSections             = FirstRestSections.apply _ tupled continuousRoadwaySections._1
          val leftSections              = FirstRestSections.apply _ tupled continuousRoadwaySections._2

          lengthCompare(leftSections, rightSections) match {
            case 0 => continuousRoadwaySections
            case 1 => getUpdatedContinuousRoadwaySections(rightSections, leftSections, true).getOrElse(continuousRoadwaySections)
            case 2 => getUpdatedContinuousRoadwaySections(leftSections, rightSections, false).getOrElse(continuousRoadwaySections)
          }
        }

        if (firstRight.isEmpty || firstLeft.isEmpty) {
          val validationErrors = checkForValidationErrors(rightLinks ++ leftLinks)
          if (validationErrors.nonEmpty)
            throw new ProjectValidationException(s"Validation errors", validationErrors.map(projectValidator.errorPartsToApi))
          else
            throw new RoadAddressException(s"Mismatching tracks, R ${firstRight.size}, L ${firstLeft.size}")
        }
        val strategy: TrackCalculatorStrategy = TrackCalculatorContext.getStrategy(firstLeft, firstRight)
        logger.info(s"${strategy.name} strategy")
        val trackCalcResult                       = strategy.assignTrackMValues(previousStart, firstLeft, firstRight, userDefinedCalibrationPoint)
        val restLefts                             = trackCalcResult.restLeft ++ restLeft
        val restRights                            = trackCalcResult.restRight ++ restRight
        val (adjustedRestRight, adjustedRestLeft) = adjustTracksToMatch(restLefts, restRights, Some(trackCalcResult.endAddrMValue), userDefinedCalibrationPoint)

        (trackCalcResult.leftProjectLinks ++ adjustedRestRight, trackCalcResult.rightProjectLinks ++ adjustedRestLeft)
      }
    }

    val rightSections     = sections.flatMap(_.right.links).distinct
    val leftSections      = sections.flatMap(_.left.links).distinct
    val rightLinks        = ProjectSectionMValueCalculator.calculateMValuesForTrack(rightSections, userDefinedCalibrationPoint)
    val leftLinks         = ProjectSectionMValueCalculator.calculateMValuesForTrack(leftSections, userDefinedCalibrationPoint)

    validateMValuesOfSplittedLinks(leftLinks.sortBy(_.startAddrMValue))
    validateMValuesOfSplittedLinks(rightLinks.sortBy(_.startAddrMValue))

    validateAddresses(leftLinks.sortBy(_.startAddrMValue))
    validateAddresses(rightLinks.sortBy(_.startAddrMValue))

    val allProjectLinks         = (projectLinkDAO.fetchProjectLinks(leftLinks.head.projectId, Some(LinkStatus.Terminated)) ++ leftLinks ++ rightLinks).sortBy(_.startAddrMValue)
    val twoTracksWithTerminated = allProjectLinks.filter(filterExistingLinks)

    val (rightOnlyWithTerminated, leftOnlyWithTerminated) = twoTracksWithTerminated.partition(_.track == Track.RightSide)
    val rightOnlyWithTerminatedEndAddresses               = getContinuousByStatus(rightOnlyWithTerminated)
    val leftOnlyWithTerminatedEndAddresses                = getContinuousByStatus(leftOnlyWithTerminated)
    val originalStatusEnds                                = (rightOnlyWithTerminatedEndAddresses ++ leftOnlyWithTerminatedEndAddresses).distinct.sorted

    /* Adjust addresses before splits, calibrationpoints after splits don't restrict calculation. */
    /* TODO: Check if userDefinedCalibrationPoint should be included here -> calculate with user given addresses. */
    val (adjustedLeftLinksBeforeStatusSplits, adjustedRightLinksBeforeStatusSplits) = adjustTracksToMatch(combineAndSort(leftLinks, splitByOriginalAddress(originalStatusEnds, leftLinks)), combineAndSort(rightLinks, splitByOriginalAddress(originalStatusEnds, rightLinks)), None, userDefinedCalibrationPoint)

    val (leftLinksWithUdcps, splittedRightLinks, udcpsFromRightSideSplits) = TwoTrackRoadUtils.splitPlsAtStatusChange(adjustedLeftLinksBeforeStatusSplits, adjustedRightLinksBeforeStatusSplits)
    val (rightLinksWithUdcps, splittedLeftLinks, udcpsFromLeftSideSplits) = TwoTrackRoadUtils.splitPlsAtStatusChange(splittedRightLinks, leftLinksWithUdcps)

    val udcpSplitsAtOriginalAddresses = (filterOldLinks(rightLinksWithUdcps) ++ filterOldLinks(splittedLeftLinks)).filter(_.endCalibrationPointType == UserDefinedCP).map(_.originalEndAddrMValue).filter(_ > 0)
    val sortedSplitOriginalAddresses = (getContinuousByStatus(rightLinksWithUdcps) ++ getContinuousByStatus(splittedLeftLinks) ++ udcpSplitsAtOriginalAddresses).distinct.sorted

    val leftLinksWithSplits  = splitByOriginalAddresses(splittedLeftLinks, sortedSplitOriginalAddresses)
    val rightLinksWithSplits = splitByOriginalAddresses(rightLinksWithUdcps, sortedSplitOriginalAddresses)

    def udcpIsDefined(udcp: Option[UserDefinedCalibrationPoint]) =
      udcp.isDefined && udcp.get.isInstanceOf[UserDefinedCalibrationPoint]

    val dups = (udcpsFromRightSideSplits ++ udcpsFromLeftSideSplits).filter(udcpIsDefined).groupBy(_.get.projectLinkId).filter(_._2.size > 1)

    /* Update udcp pl if splitted after second pass. */
    val updatedudcpsFromRightSideSplits = dups.foldLeft(udcpsFromRightSideSplits) { (udcpsToUpdate, cur) => {
      val splittedLeftLink       = splittedLeftLinks.find(_.id == cur._1)
      if (splittedLeftLink.isDefined && splittedLeftLink.get.connectedLinkId.isDefined) {
        val newLink = splittedLeftLinks.find(_.startAddrMValue == splittedLeftLink.get.endAddrMValue).get
        val maybeMaybePoint = udcpsToUpdate.find(_.get.projectLinkId == cur._1)
        udcpsToUpdate.filterNot(_.get.projectLinkId == cur._1) :+ Some(maybeMaybePoint.get.get.copy(projectLinkId = newLink.id))
      } else {
        udcpsToUpdate
      }
    }}

    def toUdcpMap(udcp: Seq[Option[UserDefinedCalibrationPoint]]) = udcp.filter(udcpIsDefined).map(_.get).map(c => c.projectLinkId -> c)

    val splitCreatedCpsFromRightSide = toUdcpMap(updatedudcpsFromRightSideSplits).toMap
    val splitCreatedCpsFromLeftSide  = toUdcpMap(udcpsFromLeftSideSplits).toMap

    val (adjustedLeft, adjustedRight) = (leftLinksWithSplits.filterNot(_.status == LinkStatus.Terminated), rightLinksWithSplits.filterNot(_.status == LinkStatus.Terminated))

    validateMValuesOfSplittedLinks(adjustedLeft.sortBy(_.startAddrMValue))
    validateMValuesOfSplittedLinks(adjustedRight.sortBy(_.startAddrMValue))

    validateAddresses(adjustedLeft.sortBy(_.startAddrMValue))
    validateAddresses(adjustedRight.sortBy(_.startAddrMValue))
    validateCombinedLinksEqualAddresses(adjustedLeft, adjustedRight)

    validateAddressesWithGeometry(adjustedLeft.sortBy(_.startAddrMValue))
    validateAddressesWithGeometry(adjustedRight.sortBy(_.startAddrMValue))

    val (right, left) = TrackSectionOrder.setCalibrationPoints(adjustedRight, adjustedLeft, userDefinedCalibrationPoint ++ splitCreatedCpsFromRightSide ++ splitCreatedCpsFromLeftSide)
    TrackSectionOrder.createCombinedSections(right, left)
  }

  /**
    * Find starting point(s) after adding new operation for the links in the project.
    *
    * @param newLinks new ProjectLinks
    * @param oldLinks non-terminated already existing ProjectLinks
    * @param otherRoadPartLinks
    * @param calibrationPoints
    * @return Right and left track starting points
    */
  def findStartingPoints(newLinks: Seq[ProjectLink], oldLinks: Seq[ProjectLink], otherRoadPartLinks: Seq[ProjectLink],
                         calibrationPoints: Seq[UserDefinedCalibrationPoint]): (Point, Point) = {
    val (rightStartPoint, pl) = findStartingPoint(newLinks.filter(_.track != Track.LeftSide), oldLinks.filter(_.track != Track.LeftSide), otherRoadPartLinks, calibrationPoints, (newLinks ++ oldLinks).filter(_.track == Track.LeftSide))

    if ((oldLinks ++ newLinks).exists(l => GeometryUtils.areAdjacent(l.geometry, rightStartPoint) && l.track == Track.Combined)) {
      (rightStartPoint, rightStartPoint)
    } else {
      // Get left track non-connected points and find the closest to right track starting point
      val (leftLinks, rightLinks) = (newLinks ++ oldLinks).filterNot(_.track == Track.Combined).partition(_.track == Track.LeftSide)
      val chainEndPoints = TrackSectionOrder.findChainEndpoints(leftLinks)

      if (chainEndPoints.isEmpty)
        throw new MissingTrackException("Missing left track starting project links")

      val remainLinks = oldLinks ++ newLinks
      val points = remainLinks.map(pl => pl.getEndPoints)

      val (linksWithValues, linksWithoutValues) = remainLinks.partition(_.endAddrMValue != 0)
      val endPointsWithValues = ListMap(chainEndPoints.filter(link => link._2.startAddrMValue >= 0 && link._2.endAddrMValue != 0).toSeq
        .sortWith(_._2.startAddrMValue < _._2.startAddrMValue): _*)

      val foundConnectedLinks = TrackSectionOrder.findOnceConnectedLinks(remainLinks)
        .values.filter(link => link.startAddrMValue == 0 && link.endAddrMValue != 0)

      // In case there is some old starting link, we want to prioritize the one that didn't change or was not treated yet.
      // We could have more than two starting link since one of them can be Transferred from any part to this one.
      val oldFirst: Option[ProjectLink] =
      if (foundConnectedLinks.nonEmpty) {
        foundConnectedLinks.find(_.status == LinkStatus.New)
          .orElse(foundConnectedLinks.find(l => l.status == LinkStatus.UnChanged))
          .orElse(foundConnectedLinks.headOption)
      } else {
        None
      }

      (rightStartPoint,
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
            otherEndPoint.head._1
          } else {
            if (currentEndPoint.head._1 == endPointsWithValues.head._2.endPoint)
              otherEndPoint.head._1
            else
              endPointsWithValues.head._1
          }
        } else {
          if (leftLinks.forall(_.endAddrMValue == 0) && rightLinks.nonEmpty && rightLinks.exists(_.endAddrMValue != 0)) {
            val rightStartPoint = TrackSectionOrder.findChainEndpoints(rightLinks).find(link => link._2.startAddrMValue == 0 && link._2.endAddrMValue != 0)
            chainEndPoints.minBy(p => p._1.distance2DTo(rightStartPoint.get._1))._1
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
              startPoint2
            }
          }
        }
      )
    }
  }

  /**
    * Find a starting point for this road part.
    *
    * @param newLinks          Status = New: links that need to have an address, not having one yet
    * @param oldLinks          Other non-terminated links that already existed before the current operation
    * @param calibrationPoints The calibration points set by the user as the fixed addresses
    * @return Starting point
    */
  private def findStartingPoint(newLinks: Seq[ProjectLink], oldLinks: Seq[ProjectLink], otherRoadPartLinks: Seq[ProjectLink],
                                calibrationPoints: Seq[UserDefinedCalibrationPoint], oppositeTrackLinks: Seq[ProjectLink]): (Point, ProjectLink) = {

    def calibrationPointToPoint(calibrationPoint: UserDefinedCalibrationPoint): Option[(Point, ProjectLink)] = {
      val link = oldLinks.find(_.id == calibrationPoint.projectLinkId).orElse(newLinks.find(_.id == calibrationPoint.projectLinkId))
      link.flatMap(pl => GeometryUtils.calculatePointFromLinearReference(pl.geometry, calibrationPoint.segmentMValue).map(p => (p, pl)))
    }
    // Get opposite end from roadpart by Discontinuity code if ending Discontinuity is defined
    def getStartPointByDiscontinuity(chainEndPoints: Map[Point, ProjectLink]): Option[(Point, ProjectLink)] = {
      val notEndOfRoad = Map(chainEndPoints.maxBy((_: (Point, ProjectLink))._2.discontinuity.value))
      if (notEndOfRoad.size == 1 && chainEndPoints.exists(_._2.discontinuity.value < Discontinuity.MinorDiscontinuity.value))
        Some(notEndOfRoad.head)
      else None
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

        val onceConnectedLinks = TrackSectionOrder.findOnceConnectedLinks(remainLinks)
        var foundConnectedLinks = onceConnectedLinks.values.filter(link => link.startAddrMValue == 0 && link.endAddrMValue != 0)
        /* Check if an existing road with loop end is reversed. */
        if (onceConnectedLinks.size == 1 && foundConnectedLinks.isEmpty && TrackSectionOrder.hasTripleConnectionPoint(remainLinks) && remainLinks.forall(pl => pl.status == LinkStatus.Transfer && pl.reversed))
          foundConnectedLinks = Iterable(remainLinks.maxBy(pl => pl.originalEndAddrMValue))

        // In case there is some old starting link, we want to prioritize the one that didn't change or was not treated yet.
        // We could have more than two starting link since one of them can be Transferred from any part to this one.
        val oldFirst: Option[ProjectLink] =
          if (foundConnectedLinks.nonEmpty) {
            foundConnectedLinks.find(_.status == LinkStatus.New)
              .orElse(foundConnectedLinks.find(l => l.status == LinkStatus.UnChanged))
              .orElse(foundConnectedLinks.headOption)
          } else {
            None
          }

        /* One saved link. */
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
          val otherEndPoint = chainEndPoints.filterNot(_._2.id == oldFirst.get.id)

          if (otherEndPoint.nonEmpty && otherEndPoint.head._2.endPoint.connected(oldFirst.get.startingPoint)) {
            // Check reversed status to select starting point
            if (otherEndPoint.head._2.reversed && oldFirst.get.reversed) {
              (oldFirst.get.endPoint, oldFirst.get)
            } else {
              (otherEndPoint.head._1, otherEndPoint.head._2)
            }
          } else if (otherEndPoint.isEmpty) { // Only oldFirst is defined
            (oldFirst.get.getEndPoints._1, oldFirst.get)
          }
          else {
            // Check reversed status to select starting point
            if (oldFirst.get.reversed) {
              (oldFirst.get.getEndPoints._2, oldFirst.get)
            } else {
              (oldFirst.get.getEndPoints._1, oldFirst.get)
            }
          }
        } else {
          if (remainLinks.forall(_.isNotCalculated) && oppositeTrackLinks.nonEmpty && oppositeTrackLinks.exists(_.endAddrMValue != 0)) {
            val leftStartPoint = TrackSectionOrder.findChainEndpoints(oppositeTrackLinks).find(link => link._2.startAddrMValue == 0 && link._2.endAddrMValue != 0)
            chainEndPoints.minBy(p => p._2.geometry.head.distance2DTo(leftStartPoint.get._1))
          } else if (remainLinks.nonEmpty && oppositeTrackLinks.nonEmpty && remainLinks.forall(_.isNotCalculated) && oppositeTrackLinks.forall(_.isNotCalculated)) {
                getStartPointByDiscontinuity(chainEndPoints).getOrElse {
                  val candidateRightStartPoint  = chainEndPoints.minBy(p => {
                    direction.dot(p._1.toVector - midPoint)
                  })
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
              }
          } else {
            getStartPointByDiscontinuity(chainEndPoints).getOrElse {
              val startPoint1 = chainEndPoints.minBy(p => direction.dot(p._1.toVector - midPoint))
              val startPoint2 = chainEndPoints.maxBy(p => direction.dot(p._1.toVector - midPoint))
              val connectingPoint = otherRoadPartLinks.find(l => GeometryUtils.areAdjacent(l.getLastPoint, startPoint1._1) || GeometryUtils.areAdjacent(l.getFirstPoint, startPoint2._1))
              val continuousLink = chainEndPoints.filter(_._2.discontinuity == Discontinuity.Continuous)
              if (continuousLink.nonEmpty) {
                continuousLink.head
              } else {
                if (otherRoadPartLinks.isEmpty || connectingPoint.nonEmpty) {
                  startPoint1
                } else {
                  startPoint2
                }
              }
            }
          }
        }
      }
    )
  }

  /**
    * Returns the more distant end point of the link compared to the given point
    * @param link  Link, whose opposite end point is to be found
    * @param point The point compared to the ends of the link
    * @return      That end of the given link that is the more distant to the given point
    */
  private def getOppositeEnd(link: BaseRoadAddress, point: Point): Point = {
    val (st, en) = link.getEndPoints
    if (st.distance2DTo(point) < en.distance2DTo(point)) en else st
  }

}

/***
 * Toolbox for adjustTracksToMatch().
 */
case class FirstRestSections(first:Seq[ProjectLink], rest: Seq[ProjectLink])
object FirstRestSections {
  def checkTheLastLinkInOppositeRange(sect: Seq[ProjectLink], oppositeSect: Seq[ProjectLink]): Boolean = {
    (sect.size > 1) && {
      val lastLengthDifference       = Math.abs(sect.last.endAddrMValue - oppositeSect.last.endAddrMValue)
      val secondLastLengthDifference = Math.abs(sect(sect.size - 2).endAddrMValue - oppositeSect.last.endAddrMValue)
      ((sect.last.discontinuity != Discontinuity.Continuous) || (oppositeSect.last.discontinuity != Discontinuity.Continuous)) &&
      lastLengthDifference > secondLastLengthDifference &&
      secondLastLengthDifference != 0 // Equal case should be found by lengthCompare().
    }
  }

  def getEqualRoadwaySections(sect: FirstRestSections, oppositeSect: FirstRestSections): ((Seq[ProjectLink], Seq[ProjectLink]), (Seq[ProjectLink], Seq[ProjectLink])) = {
    val newFirstSection = sect.first.takeWhile(_.endAddrMValue <= oppositeSect.first.last.endAddrMValue)
    val newRestSection  = sect.first.drop(newFirstSection.size) ++ sect.rest
    ((newFirstSection, newRestSection), FirstRestSections.unapply(oppositeSect).get)
  }

  def getUpdatedContinuousRoadwaySections(sect: FirstRestSections, oppositeSect: FirstRestSections, rightSideFirst: Boolean): Option[((Seq[ProjectLink], Seq[ProjectLink]), (Seq[ProjectLink], Seq[ProjectLink]))] = {
    if (oppositeSect.rest.nonEmpty && checkTheLastLinkInOppositeRange(sect.first, oppositeSect.first)) {
      val equalRoadwaySections = getEqualRoadwaySections(sect, oppositeSect)
      if (rightSideFirst) {
        Some(equalRoadwaySections)
      } else {
        Some(equalRoadwaySections.swap)
      }
    }
    else
      None
  }

  def lengthCompare(leftSections: FirstRestSections, rightSections: FirstRestSections): Int = {
    val firstRightEndAddress = rightSections.first.last.endAddrMValue
    val firstLeftEndAddress  = leftSections.first.last.endAddrMValue

    Seq(firstRightEndAddress == firstLeftEndAddress,
        firstRightEndAddress  > firstLeftEndAddress,
        firstRightEndAddress  < firstLeftEndAddress)
      .indexOf(true)
  }
}
