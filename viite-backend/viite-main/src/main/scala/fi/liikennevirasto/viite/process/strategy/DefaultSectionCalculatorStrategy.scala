package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.digiroad2.util.{MissingRoadwayNumberException, MissingTrackException, RoadAddressException}
import fi.liikennevirasto.viite.{ContinuousAddressCapErrorMessage, LengthMismatchErrorMessage, NegativeLengthErrorMessage, NewIdValue, ProjectValidationException, ProjectValidator, UnsuccessfulRecalculationMessage}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.process._
import fi.liikennevirasto.viite.process.strategy.FirstRestSections.{getUpdatedContinuousRoadwaySections, lengthCompare}
import fi.liikennevirasto.viite.util.TwoTrackRoadUtils
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point, Vector3d}
import fi.vaylavirasto.viite.model.{AddrMRange, CalibrationPointType, Discontinuity, RoadAddressChangeType, SideCode, Track}
import fi.vaylavirasto.viite.util.ViiteException
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

  override def assignAddrMValues(newProjectLinks: Seq[ProjectLink], oldProjectLinks: Seq[ProjectLink], userCalibrationPoints: Seq[UserDefinedCalibrationPoint]): Seq[ProjectLink] = {

    def updateProjectLinkList(projectLinkListToUpdate: Seq[ProjectLink], adjustedProjectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
      (projectLinkListToUpdate.filterNot(projectLink => adjustedProjectLinks.map(_.id).contains(projectLink.id)) ++ adjustedProjectLinks).sortBy(pl => pl.addrMRange.start)
    }

    /**
     * Sorts project link sequence in to "sections" (i.e. smaller project link sections) that are continuous on a road address level and have the same RoadAddressChangeType.
     *
     * @param projectLinksWithSameStatus Seq[ProjectLink]
     * @return Continuous sections by RoadAddressChangeType Seq[Seq[ProjectLink]]
     * */
    def toContinuousSectionsByStatus(projectLinksWithSameStatus: Seq[ProjectLink], sectionStatus: RoadAddressChangeType): Seq[Seq[ProjectLink]] = {
      var sections = Seq.empty[Seq[ProjectLink]]
      var currentSection = Seq.empty[ProjectLink]

      for (link <- projectLinksWithSameStatus) {
        if (currentSection.isEmpty) {
          // Start a new section with the given status
          if (link.status == sectionStatus) {
            currentSection :+= link
          }
        } else {
          // Check if the current link continues the section
          val lastLink = currentSection.last
          if (link.status == sectionStatus && lastLink.addrMRange.end == link.addrMRange.start && lastLink.track == link.track) {
            currentSection :+= link
          } else {
            // If it doesn't match, finalize the current section and start a new one
            sections :+= currentSection
            currentSection = Seq.empty
            if (link.status == sectionStatus) {
              currentSection :+= link
            }
          }
        }
      }
      // Add the last section if it exists
      if (currentSection.nonEmpty)
        sections :+= currentSection

      sections
    }

    def addOppositeTrackLinksToTerminatedSection(leftSection: Seq[ProjectLink], rightTerminatedSections: Seq[Seq[ProjectLink]], rightTransferredSections: Seq[Seq[ProjectLink]]): Seq[ProjectLink] = {
      def getOppositeSectionsInRange(sectionStart: Long, sectionEnd: Long, maxAddrDiff: Long, oppositeSections: Seq[Seq[ProjectLink]]): Seq[Seq[ProjectLink]] = {
        oppositeSections.filter(section =>
          (Math.abs(sectionStart - section.head.addrMRange.start) < maxAddrDiff) &&
          (Math.abs(sectionEnd - section.last.addrMRange.end) < maxAddrDiff)
        )
      }

      if (leftSection.forall(_.track == Track.LeftSide)) {
        val maxAddrDiff = 10L
        val sectionStart = leftSection.minBy(_.addrMRange.start).addrMRange.start
        val sectionEnd = leftSection.maxBy(_.addrMRange.end).addrMRange.end
        val minStartForOppTrack = sectionStart - maxAddrDiff
        val maxEndForOppTrack = sectionEnd + maxAddrDiff

        val oppTrackTerminatedSectionsInRange = getOppositeSectionsInRange(sectionStart, sectionEnd, maxAddrDiff, rightTerminatedSections)
        val oppTrackTransferredSectionsInRange = getOppositeSectionsInRange(sectionStart, sectionEnd, maxAddrDiff, rightTransferredSections)

        if(oppTrackTerminatedSectionsInRange.size == 1) { // if the opposite section is terminated
          leftSection ++ oppTrackTerminatedSectionsInRange.head
        } else if (oppTrackTransferredSectionsInRange.size == 1 && oppTrackTransferredSectionsInRange.head.forall(_.track == Track.Combined)) { // if the opposite section was transferred to combined section
          leftSection ++ oppTrackTransferredSectionsInRange.head
        } else if (oppTrackTerminatedSectionsInRange.size > 1) { // if there were more than one opposite terminated sections
          throw ViiteException(s"Vasemmalle ajoradalle löytyi enemmän kuin yksi vastin pari  lakkautettuja osuuksia vastakkaiselta ajoradalta. Tarkista projekti linkit väliltä ${minStartForOppTrack} - ${maxEndForOppTrack}")
        }  else if (oppTrackTransferredSectionsInRange.size > 1) {
          throw ViiteException(s"Vasemmalle ajoradalle löytyi enemmän kuin yksi vastin pari  siirrettyjä osuuksia vastakkaiselta ajoradalta. Tarkista projekti linkit väliltä ${minStartForOppTrack} - ${maxEndForOppTrack}")
        } else {
          throw ViiteException(s"Vasemmalle ajoradalle ei löytynyt vastin paria (=lakkautettu tai siirretty osuus) vastakkaiselta ajoradalta. Tarkista projekti linkit väliltä ${minStartForOppTrack} - ${maxEndForOppTrack}")
        }
      } else {
        leftSection
      }
    }

    def adjustTerminatedSectionAddressesToMatch(terminatedSection: Seq[ProjectLink]): Seq[ProjectLink] = {
      // if there are right track links and left track links to be adjusted to match
      if (terminatedSection.exists(pl => pl.track == Track.RightSide)  && (terminatedSection.exists(pl => pl.track == Track.LeftSide))) {
        val leftLinksInSection = terminatedSection.filter(_.track == Track.LeftSide)
        val rightLinksInSection = terminatedSection.filter(_.track == Track.RightSide)

        val leftMin = leftLinksInSection.minBy(_.addrMRange.start)
        val rightMin = rightLinksInSection.minBy(_.addrMRange.start)

        val rightMax = rightLinksInSection.maxBy(_.addrMRange.end)
        val leftMax = leftLinksInSection.maxBy(_.addrMRange.end)

        val averageEndAddrM = (rightMax.addrMRange.end + leftMax.addrMRange.end) / 2
        val averageStartAddrM = (rightMin.addrMRange.start + leftMin.addrMRange.start) / 2

        val updatedLeftLinks: Seq[ProjectLink] = {
          if (leftLinksInSection.size > 1) {
            val updatedFirstLinkLeft = leftMin.copy(addrMRange = AddrMRange(averageStartAddrM, leftMin.addrMRange.end), originalAddrMRange = AddrMRange(averageStartAddrM, leftMin.addrMRange.end))
            val updatedLastLinkLeft = leftMax.copy(addrMRange = AddrMRange(leftMax.addrMRange.start, averageEndAddrM), originalAddrMRange = AddrMRange(leftMax.originalAddrMRange.start, averageEndAddrM))
            Seq(updatedFirstLinkLeft, updatedLastLinkLeft)
          } else if (leftLinksInSection.size == 1){
            Seq(leftLinksInSection.head.copy(addrMRange = AddrMRange(averageStartAddrM, averageEndAddrM), originalAddrMRange = AddrMRange(averageStartAddrM, averageEndAddrM)))
          } else
            Seq()
        }

        val updatedRightLinks: Seq[ProjectLink] = {
          if (rightLinksInSection.size > 1) {
            val updatedFirstLinkRight = rightMin.copy(addrMRange = AddrMRange(averageStartAddrM, rightMin.addrMRange.end), originalAddrMRange = AddrMRange(averageStartAddrM, rightMin.addrMRange.end))
            val updatedLastLinkRight = rightMax.copy(addrMRange = AddrMRange(rightMax.addrMRange.start, averageEndAddrM), originalAddrMRange = AddrMRange(rightMax.originalAddrMRange.start, averageEndAddrM))
            Seq(updatedFirstLinkRight, updatedLastLinkRight)
          } else if (rightLinksInSection.size == 1)
            Seq(rightLinksInSection.head.copy(addrMRange = AddrMRange(averageStartAddrM, averageEndAddrM), originalAddrMRange = AddrMRange(averageStartAddrM, averageEndAddrM)))
          else
            Seq()
        }

        updateProjectLinkList(terminatedSection, updatedLeftLinks ++ updatedRightLinks)
      } else {
        // nothing to be adjusted
        Seq()
      }
    }

    def adjustLinksBeforeAndAfterTerminatedSection(leftLinksToAdjust: Seq[ProjectLink], rightLinksToAdjust: Seq[ProjectLink], leftSideTerminatedSections: Seq[Seq[ProjectLink]], terminatedRightLinks: Seq[ProjectLink], terminatedLeftLinks: Seq[ProjectLink]) : (Seq[ProjectLink], Seq[ProjectLink], Seq[ProjectLink], Seq[ProjectLink]) = {
      def adjustAddrMRange(projectLink: ProjectLink, addrMRange: Option[AddrMRange], originalAddrMRange: Option[AddrMRange]): ProjectLink = {
        projectLink.copy(
          addrMRange = if (addrMRange.isDefined) addrMRange.get else projectLink.addrMRange,
          originalAddrMRange = if (originalAddrMRange.isDefined) originalAddrMRange.get else projectLink.originalAddrMRange
        )
      }

      // initialize project link lists that will be updated during the process
      var processedLeftProjectLinks = leftLinksToAdjust
      var processedRightProjectLinks = rightLinksToAdjust
      var processedTerminatedLeftLinks = terminatedLeftLinks
      var processedTerminatedRightLinks = terminatedRightLinks

      leftSideTerminatedSections.foreach(leftTerminatedSection => {
        val firstLinkInSection = leftTerminatedSection.minBy(_.addrMRange.start)
        val lastLinkInSection = leftTerminatedSection.maxBy(_.addrMRange.end)

        // check if the terminated segment needs to be adjusted to same address value at the end of the segment
        // The logic:
        // if the terminated segment creates a minor discontinuity
        // Or
        // if the terminated segment is at the start of the road part
        // then the segment is to be adjusted to match on both tracks at the start and end of the segment
        val precedingLink = leftLinksToAdjust.find(pl => (pl.originalAddrMRange.end == firstLinkInSection.originalAddrMRange.start) || (pl.status == RoadAddressChangeType.New && pl.addrMRange.end == firstLinkInSection.originalAddrMRange.start))
        val followingLink = leftLinksToAdjust.find(pl => (pl.originalAddrMRange.start == lastLinkInSection.originalAddrMRange.end) || (pl.status == RoadAddressChangeType.New && pl.addrMRange.start == lastLinkInSection.originalAddrMRange.end))


        if ((precedingLink.nonEmpty && precedingLink.get.discontinuity == Discontinuity.MinorDiscontinuity) || (precedingLink.isEmpty && followingLink.nonEmpty && firstLinkInSection.addrMRange.start == 0)) {
          val rightTerminatedSections = toContinuousSectionsByStatus(terminatedRightLinks, RoadAddressChangeType.Termination)
          val rightTransferredSections = toContinuousSectionsByStatus(rightLinksToAdjust, RoadAddressChangeType.Transfer)
          val completeSection = addOppositeTrackLinksToTerminatedSection(leftTerminatedSection, rightTerminatedSections, rightTransferredSections)
          val adjustedTerminatedSection = adjustTerminatedSectionAddressesToMatch(completeSection)

          // if the sections' start/end addresses were adjusted then there might be links before/after the section that need to be adjusted as well
          if (adjustedTerminatedSection.nonEmpty) {
            // find the original end address so we can use it to search for the link that comes after the adjusted terminated section
            val lastTerminatedRightLinkOnSection = adjustedTerminatedSection.filter(_.track == Track.RightSide).maxBy(_.addrMRange.end)
            val lastTerminatedLeftLinkOnSection = adjustedTerminatedSection.filter(_.track == Track.LeftSide).maxBy(_.addrMRange.end)

            val firstTerminatedRightLinkOnSection = adjustedTerminatedSection.filter(_.track == Track.RightSide).minBy(_.addrMRange.start)
            val firstTerminatedLeftLinkOnSection = adjustedTerminatedSection.filter(_.track == Track.LeftSide).minBy(_.addrMRange.start)

            val origStartAddrOfFirstTerminatedRightLink = terminatedRightLinks.find(_.id == firstTerminatedRightLinkOnSection.id).get.originalAddrMRange.start
            val origStartAddrOfFirstTerminatedLeftLink = terminatedLeftLinks.find(_.id == firstTerminatedLeftLinkOnSection.id).get.originalAddrMRange.start

            val origEndAddrOfLastRightLink = terminatedRightLinks.find(_.id == lastTerminatedRightLinkOnSection.id).get.originalAddrMRange.end
            val origEndAddrOfLastLeftLink = terminatedLeftLinks.find(_.id == lastTerminatedLeftLinkOnSection.id).get.originalAddrMRange.end

            val adjustedTerminatedLeftLinks = adjustedTerminatedSection.filter(pl => pl.track == Track.LeftSide)
            val adjustedTerminatedRightLinks = adjustedTerminatedSection.filter(pl => pl.track == Track.RightSide)

            val leftLinkBeforeTerminationSegment = leftLinksToAdjust.find(pl => pl.originalAddrMRange.end == origStartAddrOfFirstTerminatedLeftLink && pl.status != RoadAddressChangeType.New)
            val rightLinkBeforeTerminationSegment = rightLinksToAdjust.find(pl => pl.originalAddrMRange.end == origStartAddrOfFirstTerminatedRightLink && pl.status != RoadAddressChangeType.New)

            val leftLinkAfterTerminationSegment = leftLinksToAdjust.find(pl => pl.originalAddrMRange.start == origEndAddrOfLastLeftLink && pl.status != RoadAddressChangeType.New)
            val rightLinkAfterTerminationSegment = rightLinksToAdjust.find(pl => pl.originalAddrMRange.start == origEndAddrOfLastRightLink && pl.status != RoadAddressChangeType.New)


            if (leftLinkBeforeTerminationSegment.nonEmpty) {
              // set addrMRange and originalAddrMRange values for the previous link before termination segment
              val addrMRange = if (leftLinkBeforeTerminationSegment.get.status == RoadAddressChangeType.Unchanged)
                AddrMRange(leftLinkBeforeTerminationSegment.get.addrMRange.start, firstTerminatedLeftLinkOnSection.originalAddrMRange.start)
               else
                leftLinkBeforeTerminationSegment.get.addrMRange

              val originalAddrMRange = AddrMRange(leftLinkBeforeTerminationSegment.get.originalAddrMRange.start, firstTerminatedLeftLinkOnSection.originalAddrMRange.start)
              val adjustedPrecedingLeftLink = adjustAddrMRange(leftLinkBeforeTerminationSegment.get, Some(addrMRange), Some(originalAddrMRange))

              processedLeftProjectLinks = updateProjectLinkList(processedLeftProjectLinks, Seq(adjustedPrecedingLeftLink))
            }

            if (rightLinkBeforeTerminationSegment.nonEmpty) {
              // set addrMRange and originalAddrMRange values for the previous link before termination segment
              val addrMRange = if (rightLinkBeforeTerminationSegment.get.status == RoadAddressChangeType.Unchanged)
                AddrMRange(rightLinkBeforeTerminationSegment.get.addrMRange.start, firstTerminatedRightLinkOnSection.originalAddrMRange.start)
              else
                rightLinkBeforeTerminationSegment.get.addrMRange

              val originalAddrMRange = AddrMRange(rightLinkBeforeTerminationSegment.get.originalAddrMRange.start, firstTerminatedRightLinkOnSection.originalAddrMRange.start)
              val adjustedPrecedingRightLink = adjustAddrMRange(rightLinkBeforeTerminationSegment.get, Some(addrMRange), Some(originalAddrMRange))

              processedRightProjectLinks = updateProjectLinkList(processedRightProjectLinks, Seq(adjustedPrecedingRightLink))
            }

            if (leftLinkAfterTerminationSegment.nonEmpty) {
              // adjust the link after the termination to have original addrMRange.start value to be the same as the adjusted terminated addrMRange.end value
              val originalAddrMRange = AddrMRange(lastTerminatedLeftLinkOnSection.originalAddrMRange.end, leftLinkAfterTerminationSegment.get.originalAddrMRange.end)
              val adjustedFollowingLeftLink = adjustAddrMRange(leftLinkAfterTerminationSegment.get, None, Some(originalAddrMRange))

              processedLeftProjectLinks = updateProjectLinkList(processedLeftProjectLinks, Seq(adjustedFollowingLeftLink))
            }

            if (rightLinkAfterTerminationSegment.nonEmpty) {
              // adjust the link after the termination to have original addrMRange.start value to be the same as the adjusted terminated addrMRange.end value
              val originalAddrMRange = AddrMRange(lastTerminatedRightLinkOnSection.originalAddrMRange.end, rightLinkAfterTerminationSegment.get.originalAddrMRange.end)
              val adjustedFollowingRightLink = adjustAddrMRange(rightLinkAfterTerminationSegment.get, None, Some(originalAddrMRange))

              processedRightProjectLinks = updateProjectLinkList(processedRightProjectLinks, Seq(adjustedFollowingRightLink))
            }
            processedTerminatedLeftLinks = updateProjectLinkList(processedTerminatedLeftLinks, adjustedTerminatedLeftLinks)
            processedTerminatedRightLinks = updateProjectLinkList(processedTerminatedRightLinks, adjustedTerminatedRightLinks)
          }
        }
      })

      (processedLeftProjectLinks, processedRightProjectLinks, processedTerminatedLeftLinks, processedTerminatedRightLinks)
    }


    // Group new and old project links by road part
    val groupedNewLinks = newProjectLinks.groupBy(projectLink => (projectLink.roadPart))
    val groupedOldLinks = oldProjectLinks.groupBy(projectLink => (projectLink.roadPart))

    // Combine grouped project links
    val group = (groupedNewLinks.keySet ++ groupedOldLinks.keySet).map(k =>
      k -> (groupedNewLinks.getOrElse(k, Seq()), groupedOldLinks.getOrElse(k, Seq())))

    // Process each road part separately
    group.flatMap { case (part, (newLinks, oldLinks)) =>

      try {
        val plsOnSameRoadDifferentPart = if (newLinks.nonEmpty) {
          projectLinkDAO.fetchByProjectRoad(part.roadNumber, newLinks.head.projectId).filterNot(l => l.roadPart.partNumber == part.partNumber)

        } else {
          Seq.empty[ProjectLink]
        }

        // Find starting points for project links
       val currStartPoints = findStartingPoints(newLinks, oldLinks, plsOnSameRoadDifferentPart, userCalibrationPoints)

        // Order project links by topology
        var (right, left) = TrackSectionOrder.orderProjectLinksTopologyByGeometry(currStartPoints, newLinks ++ oldLinks)

        // Fetch terminated project links (the addressMValues of terminated links will be adjusted if the project link addrMValues are slid between calibration points)
        val terminated = projectLinkDAO.fetchProjectLinks(left.head.projectId, Some(RoadAddressChangeType.Termination)).filter(_.roadPart == part)
        var terminatedRightLinks = terminated.filter(_.track != Track.LeftSide).sortBy(_.addrMRange.start)
        var terminatedLeftLinks = terminated.filter(_.track != Track.RightSide).sortBy(_.addrMRange.start)

        val leftSideTerminatedSections = toContinuousSectionsByStatus(terminatedLeftLinks, RoadAddressChangeType.Termination)

        if (leftSideTerminatedSections.nonEmpty) {
          val (adjustedLeft, adjustedRight, adjustedTerminatedLeft, adjustedTerminatedRight) = adjustLinksBeforeAndAfterTerminatedSection(left, right, leftSideTerminatedSections, terminatedRightLinks, terminatedLeftLinks)
          left = adjustedLeft
          right = adjustedRight
          terminatedLeftLinks = adjustedTerminatedLeft
          terminatedRightLinks = adjustedTerminatedRight
        }

        // Create combined sections

        val ordSections = TrackSectionOrder.createCombinedSections(right, left)
        val rightSections = ordSections.flatMap(_.right.links).distinct
        val leftSections = ordSections.flatMap(_.left.links).distinct

        // Map user-defined calibration points to a map
        val userDefinedCalibrationPointsMap = userCalibrationPoints.map(c => c.projectLinkId -> c).toMap

        // Calculate addressMValues for tracks
        val rightLinks = ProjectSectionMValueCalculator.calculateAddressMValuesForTrack(rightSections, userDefinedCalibrationPointsMap)
        val leftLinks = ProjectSectionMValueCalculator.calculateAddressMValuesForTrack(leftSections, userDefinedCalibrationPointsMap)

        // Calculate section address values
        // (creates splits at status changing spots on opposite tracks, adjusts calibration points etc)
        val calculatedSections = calculateSectionAddressValues(leftLinks, rightLinks, userDefinedCalibrationPointsMap)

        runCalculationValidations(calculatedSections.flatMap(_.left.links), calculatedSections.flatMap(_.right.links))

        val projectLinksResult = {
          if (calculatedSections.flatMap(_.right.links) == calculatedSections.flatMap(_.left.links))
            calculatedSections.flatMap(_.right.links)
          else {
            calculatedSections.flatMap(_.right.links) ++ calculatedSections.flatMap(_.left.links).filterNot(_.track == Track.Combined) // Remove duplicated
          }
        }
        projectLinksResult ++ terminatedLeftLinks ++ terminatedRightLinks
      } catch {
        case ex @ (_: MissingTrackException | _: MissingRoadwayNumberException) =>
          logger.warn(ex.getMessage)
          throw ex
        case ex: InvalidAddressDataException =>
          logger.warn(s"Can't calculate road part ${part}: " + ex.getMessage)
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

  def runCalculationValidations(leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink]) = {
    validateMValuesOfSplittedLinks(leftProjectLinks)
    validateMValuesOfSplittedLinks(rightProjectLinks)

    validateAddresses(leftProjectLinks)
    validateAddresses(rightProjectLinks)
    validateCombinedLinksEqualAddresses(leftProjectLinks, rightProjectLinks)

    validateAddressesWithGeometry(leftProjectLinks)
    validateAddressesWithGeometry(rightProjectLinks)
  }

  def assignProperRoadwayNumber(continuousProjectLinks: Seq[ProjectLink], givenRoadwayNumber: Long, originalHistorySection: Seq[ProjectLink]): (Long, Long) = {
    def getRoadAddressesByRoadwayIds(roadwayIds: Seq[Long]): Option[Roadway] = {
      val roadways = roadwayDAO.fetchAllByRoadwayId(roadwayIds)
      if (roadways.size > 1) {
        throwExceptionWithErrorInfo(originalHistorySection, s"Expected 1 Roadway got: ${roadways.toList.map(_.id)}")
      }
      roadways.headOption
    }

    val roadwayNumbers = if (continuousProjectLinks.nonEmpty && continuousProjectLinks.exists(_.status == RoadAddressChangeType.New)) {
      // then we now that for sure the addresses increased their length for the part => new roadwayNumber for the new sections
      (givenRoadwayNumber, Sequences.nextRoadwayNumber)
    } else if (continuousProjectLinks.nonEmpty && continuousProjectLinks.exists(_.status == RoadAddressChangeType.Renumeration)) {
      // then we know for sure that the addresses didn't change the address length part, only changed the number of road or part => same roadwayNumber
      (continuousProjectLinks.headOption.map(_.roadwayNumber).get, givenRoadwayNumber)
    } else {
      val originalAddresses = getRoadAddressesByRoadwayIds(originalHistorySection.map(_.roadwayId))

      val isSameAddressLengthSection = {
        if(originalAddresses.isEmpty || continuousProjectLinks.isEmpty) {
          false
        } else {
          (continuousProjectLinks.last.addrMRange.end - continuousProjectLinks.head.addrMRange.start) == (originalAddresses.get.addrMRange.end - originalAddresses.get.addrMRange.start)
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
    val firstLinkOperationType = links.headOption.map(_.status).getOrElse(RoadAddressChangeType.Unknown)
    val originalHistorySection = if (firstLinkOperationType == RoadAddressChangeType.New) Seq() else links.takeWhile(pl => pl.roadwayNumber == roadwayNumber)
    val continuousRoadwayNumberSection =
      if (firstLinkOperationType == RoadAddressChangeType.New)
        links.takeWhile(pl => pl.status.equals(RoadAddressChangeType.New)).sortBy(_.addrMRange.start)
      else
        links.takeWhile(pl => pl.roadwayNumber == roadwayNumber).sortBy(_.addrMRange.start)

    val (assignedRoadwayNumber, nextRoadwayNumber) = assignProperRoadwayNumber(continuousRoadwayNumberSection, givenRoadwayNumber, originalHistorySection)
    val rest = links.drop(continuousRoadwayNumberSection.size)
    continuousRoadwayNumberSection.map(pl => pl.copy(roadwayNumber = assignedRoadwayNumber)) ++
      (if (rest.isEmpty) Seq() else assignRoadwayNumbersInContinuousSection(rest, nextRoadwayNumber))
  }

  /**
   * Retrieves the continuous roadway section from the given sequence of project links starting from the specified roadway number.
   *
   * @param projectLinks        The sequence of project links representing the roadway section.
   * @param givenRoadwayNumber  The starting roadway number.
   * @return                    A tuple containing two sequences of project links:
   *                            - The continuous roadway section.
   *                            - The remaining project links after the continuous section.
   */
  private def getContinuousRoadwaySection(projectLinks: Seq[ProjectLink], givenRoadwayNumber: Long): (Seq[ProjectLink], Seq[ProjectLink]) = {
    def pickGeometricallyConnectedSection(startLink: ProjectLink, allLinks: Seq[ProjectLink]): List[ProjectLink] = {
      def getSection(currentLink: ProjectLink, section: List[ProjectLink]): List[ProjectLink] = {
        val currentIndex = allLinks.indexOf(currentLink)
        val nextIndex = currentIndex + 1

        if (nextIndex < allLinks.length) {
          val nextLink = allLinks(nextIndex)

          if (GeometryUtils.areGeometriesConnected(currentLink.startingPoint, currentLink.endPoint, nextLink.startingPoint, nextLink.endPoint)) {
            getSection(nextLink, section :+ currentLink)
          } else {
            section :+ currentLink
          }
        } else {
          section :+ currentLink
        }
      }

      getSection(startLink, List.empty[ProjectLink])
    }
    val firstProjectLink = projectLinks.head
    val track = firstProjectLink.track
    val administrativeClass = firstProjectLink.administrativeClass
    val connectedSection = pickGeometricallyConnectedSection(firstProjectLink, projectLinks)

    val linksAfterFirst =
      if (connectedSection.tail.nonEmpty)
        connectedSection.tail.takeWhile(pl => pl.track == track && pl.discontinuity == Discontinuity.Continuous && pl.administrativeClass == administrativeClass)
      else Seq()

    val continuousProjectLinks: Seq[ProjectLink] = Seq(firstProjectLink) ++ linksAfterFirst

    def canIncludeNonContinuous: Boolean = {
      val lastContinuousLink = projectLinks(continuousProjectLinks.size - 1)
      val nextLink = projectLinks(continuousProjectLinks.size)
      nextLink.track == track &&
      nextLink.administrativeClass == administrativeClass &&
      nextLink.discontinuity != Discontinuity.Continuous &&
      GeometryUtils.areGeometriesConnected(nextLink.startingPoint,
                                           nextLink.endPoint,
                                           lastContinuousLink.startingPoint,
                                           lastContinuousLink.endPoint)
    }

    val continuousProjectLinksWithDiscontinuity =
      if (projectLinks.size > continuousProjectLinks.size && canIncludeNonContinuous)
        projectLinks.take(continuousProjectLinks.size+1)
      else
        continuousProjectLinks

    val assignedContinuousSection = assignRoadwayNumbersInContinuousSection(continuousProjectLinksWithDiscontinuity, givenRoadwayNumber)

    (assignedContinuousSection, projectLinks.drop(continuousProjectLinksWithDiscontinuity.size))
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
    val groupedById        = nonSplitted.groupBy(pl => pl.id) ++ allSplitted.groupBy(pl => (pl.addrMRange.start, pl.addrMRange.end,pl.linkId))

    if (!groupedById.values.forall(_.size == 2)) {
      val falsePls = groupedById.filter(pls => pls._2.size != 2).values.flatten
      val msg      = "Combined links pairing mismatch in project"
      throwExceptionWithErrorInfo(falsePls, msg)
    }
    if (!groupedById.values.forall(pl => pl.head.addrMRange.start == pl.last.addrMRange.start)) {
      val falsePls = groupedById.filter(pls => pls._2.head.addrMRange.start != pls._2.last.addrMRange.start).values.flatten
      val msg      = "Combined track start address mismatch in project"
      throwExceptionWithErrorInfo(falsePls, msg)
    }
    if (!groupedById.values.forall(pl => pl.head.addrMRange.end == pl.last.addrMRange.end)) {
      val falsePls = groupedById.filter(pls => pls._2.head.addrMRange.end != pls._2.last.addrMRange.end).values.flatten
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
          case Seq(first, next) =>
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

  /***
   * Checks continuity of addresses, positivity of length and length is preserved.
   * @param pls Left or right side ProjectLinks with combined to check for continuity of addresses.
   */
  def validateAddresses(pls: Seq[ProjectLink]): Unit = {
    if (pls.size > 1 && pls.head.originalAddrMRange.start == 0) {
      val maxDiffForChange = 2 // i.e. caused by average calculation
      val it = pls.sliding(2)
      while (it.hasNext) {
        it.next() match {
          case Seq(curr, next) => {
            if (curr.addrMRange.end != next.addrMRange.start) {
              logger.error(s"Address not continuous: ${curr.addrMRange.end} ${next.addrMRange.start} linkIds: ${curr.linkId} ${next.linkId}")
              throw new RoadAddressException(ContinuousAddressCapErrorMessage)
            }
            if (!(curr.addrMRange.end > curr.addrMRange.start)) {
              logger.error(s"Address length negative. linkId: ${curr.linkId}")
              throw new RoadAddressException(NegativeLengthErrorMessage.format(curr.linkId))
            }
            if (curr.status != RoadAddressChangeType.New && (curr.originalTrack == curr.track || curr.track == Track.Combined) && !(Math.abs((curr.addrMRange.end - curr.addrMRange.start) - (curr.originalAddrMRange.end - curr.originalAddrMRange.start)) < maxDiffForChange)) {
              val discontinuityErrors = projectValidator.checkProjectContinuity(projectDAO.fetchById(pls.head.projectId).get, pls)
              if (discontinuityErrors.nonEmpty) {
                val erroneousLinkIds = discontinuityErrors.flatMap(err => err.affectedLinkIds)
                throw ViiteException(s"Tarkista jatkuvuus -koodit linkeiltä: ${erroneousLinkIds}")
              } else {
                logger.error(s"Length mismatch. New: ${curr.addrMRange.start} ${curr.addrMRange.end} original: ${curr.originalAddrMRange.start} ${curr.originalAddrMRange.end} linkId: ${curr.linkId}")
                throw new RoadAddressException(LengthMismatchErrorMessage.format(curr.linkId, maxDiffForChange - 1))
              }
            }
            /* VIITE-2957
            Replacing the above if-statement with the one commented out below enables a user to bypass the RoadAddressException.
            This may be needed in certain cases.
            Example:
              An administrative class change is done on a two-track road section where the original addrMRange.start of ProjectLinks on adjacent
              tracks differ by more than 2. As the administrative class must be unambiguous at any given road address, the addrMRange.start
              has to be adjusted to be equal on both of the tracks at the point of the administrative class change. This would cause a change
              in length that was greater than 1 on one of the tracks.
            if (curr.status != RoadAddressChangeType.New && (curr.originalTrack == curr.track ||
              curr.track == Track.Combined) &&
              !(Math.abs((curr.addrMRange.end - curr.addrMRange.start) - (curr.originalEndAddrMValue - curr.originalStartAddrMValue)) < maxDiffForChange)) {
              logger.warn(s"Length mismatch. " +
                s"Project id: ${curr.projectId} ${projectDAO.fetchById(projectId = curr.projectId).get.name} " +
                s"New: ${curr.addrMRange.start} ${curr.addrMRange.end} " +
                s"original: ${curr.originalStartAddrMValue} ${curr.originalEndAddrMValue} " +
                s"linkId: ${curr.linkId} " +
                s"length change ${(curr.addrMRange.end - curr.addrMRange.start) - (curr.originalEndAddrMValue - curr.originalStartAddrMValue)}")
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
        case Seq(curr, next) =>
          if (curr.discontinuity == Discontinuity.Continuous && !curr.connected(next)) {
            logger.error(s"Address geometry mismatch. linkIds: ${curr.linkId} ${next.linkId}")
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

  /**
   * Adjusts track alignments to match between left and right tracks, ensuring consistency in project link alignments.
   *
   * @param leftLinks                   The sequence of ProjectLinks on the left track.
   * @param rightLinks                  The sequence of ProjectLinks on the right track.
   * @param previousStartAddrM          The previous start address M value.
   * @param userDefinedCalibrationPoint The mapping of project link ID to UserDefinedCalibrationPoint.
   * @return A tuple containing adjusted ProjectLinks for the left and right tracks.
   */
  def adjustTracksToMatch(leftLinks: Seq[ProjectLink], rightLinks: Seq[ProjectLink], previousStartAddrM: Option[Long], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): (Seq[ProjectLink], Seq[ProjectLink]) = {
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
        val newRoadwayNumber1 = Sequences.nextRoadwayNumber
        val newRoadwayNumber2 = if (rightLinks.head.track == Track.Combined || leftLinks.head.track == Track.Combined) newRoadwayNumber1 else Sequences.nextRoadwayNumber
        val continuousRoadwaySections = (getContinuousRoadwaySection(rightLinks, newRoadwayNumber1), getContinuousRoadwaySection(leftLinks, newRoadwayNumber2))
        val rightSections = FirstRestSections.apply _ tupled continuousRoadwaySections._1
        val leftSections = FirstRestSections.apply _ tupled continuousRoadwaySections._2

        lengthCompare(leftSections, rightSections) match {
          case 0 => continuousRoadwaySections
          case 1 => getUpdatedContinuousRoadwaySections(rightSections, leftSections, rightSideFirst = true).getOrElse(continuousRoadwaySections)
          case 2 => getUpdatedContinuousRoadwaySections(leftSections, rightSections, rightSideFirst = false).getOrElse(continuousRoadwaySections)
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
      val trackCalcResult = strategy.assignTrackMValues(previousStartAddrM, firstLeft, firstRight, userDefinedCalibrationPoint)
      val restLefts = trackCalcResult.restLeft ++ restLeft
      val restRights = trackCalcResult.restRight ++ restRight
      val (adjustedRestRight, adjustedRestLeft) = adjustTracksToMatch(restLefts, restRights, Some(trackCalcResult.addrMRange.end), userDefinedCalibrationPoint)

      (trackCalcResult.leftProjectLinks ++ adjustedRestRight, trackCalcResult.rightProjectLinks ++ adjustedRestLeft)
    }
  }

  def getProjectLinksInSameRoadwayUntilCalibrationPoint(projectLinks: Seq[ProjectLink]): (Seq[ProjectLink], Seq[ProjectLink]) = {
    if (projectLinks.head.endCalibrationPoint.isEmpty) {
      val untilCp = Seq(projectLinks.head) ++ projectLinks.tail.takeWhile(pl => pl.endCalibrationPoint.isEmpty && pl.startCalibrationPoint.isEmpty && pl.roadwayNumber == projectLinks.head.roadwayNumber)
      val rest = projectLinks.drop(untilCp.size)

      if (rest.nonEmpty && rest.head.startCalibrationPoint.isEmpty && rest.head.roadwayNumber == untilCp.last.roadwayNumber) {
        (untilCp :+ rest.head, rest.tail)
      } else
        (untilCp, rest)
    }
    else
      (Seq(projectLinks.head),projectLinks.tail)
  }

//  /**
//   * Adjusts the address values of a sequence of project links and returns the updated sequence.
//   *
//   * This function processes a given sequence of project links (`projectLinks`) by first sorting them based on their
//   * starting address values (`startAddrMValue`). It then recursively adjusts the address values within segments of
//   * project links that belong to the same roadway until a calibration point is reached. The adjusted address values
//   * are spread across the project links, including handling terminated links (`terminatedLinks`).
//   *
//   * The function performs the following steps:
//   * 1. Sorts the `projectLinks` by `startAddrMValue`.
//   * 2. If there are no project links to process, returns an empty sequence.
//   * 3. Divides the sorted project links into a segment to process and the remaining links.
//   * 4. Determines the start and end address values for the segment to process.
//   * 5. Spreads the address values across the links in the segment and recursively processes the remaining links.
//   * 6. Partitions the adjusted links into terminated and non-terminated links, ensuring that terminated links are
//   *    distinct.
//   * 7. Combines and returns the adjusted non-terminated and distinct terminated links.
//   *
//   * @param projectLinks    Project links to be processed and adjusted. (The project links are expected to have addrMValues assigned to them already)
//   * @param terminatedLinks Terminated project links that are on the same road part and need to be considered
//   *                        during the adjustment.
//   * @return A sequence of `ProjectLink` objects with adjusted address values, including distinct terminated links.
//   */
//  def mapAddressValuesForProjectLinks(projectLinks: Seq[ProjectLink], terminatedLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
//    val sortedProjectLinks = projectLinks.sortBy(_.addrMRange.start)
//
//    if (sortedProjectLinks.isEmpty)
//      return Seq()
//
//    val (toProcess, others) = getProjectLinksInSameRoadwayUntilCalibrationPoint(sortedProjectLinks)
//
//    val startAddrMValue = toProcess.head.addrMRange.start
//    val endAddrMValue = toProcess.last.addrMRange.end
//
//    val adjustedLinks = spreadAddrMValuesToProjectLinks(startAddrMValue, endAddrMValue, toProcess, terminatedLinks) ++ mapAddressValuesForProjectLinks(others, terminatedLinks)
//
//    val (adjustedTerminated, adjustedNonTerminated) = adjustedLinks.partition(_.status == RoadAddressChangeType.Termination)
//    val distinctTerminated = adjustedTerminated.distinct
//
//    adjustedNonTerminated ++ distinctTerminated
//  }

//  def spreadAddrMValuesToProjectLinks(startAddrM: Long, endAddrM: Long, projectLinks: Seq[ProjectLink], terminatedLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
//    /**
//     * Computes mapped address values based on remaining project links and other parameters.
//     *
//     * This function iteratively processes project links to compute mapped address values within a specified range.
//     * The function calculates a preview value based on the start and end address, coefficient, and increment provided.
//     * It handles cases where the preview value falls within or outside the specified address range.
//     * If the function detects potential infinite recursion (depth exceeding 100), it logs an error and optionally throws an exception.
//     *
//     * @param remaining Sequence of project links yet to be processed
//     * @param processed Sequence of project links already processed
//     * @param startAddr Starting address value
//     * @param endAddr   Ending address value
//     * @param coef      Coefficient used for calculation
//     * @param list      Sequence of long values representing mapped addresses
//     * @param increment Increment value for computing mapped addresses
//     * @param depth     Depth of recursion, defaults to 1
//     * @return Sequence of long values representing computed mapped addresses
//     */
//    def mappedAddressValues(remaining: Seq[ProjectLink], processed: Seq[ProjectLink], startAddr: Double, endAddr: Double, coef: Double, list: Seq[Long], increment: Int, depth: Int = 1): Seq[Long] = {
//      if (remaining.isEmpty) {
//        list
//      } else {
//        val currentProjectLink = remaining.head
//        //increment can also be negative
//        val previewValue = if (remaining.size == 1) {
//          startAddr + Math.round((currentProjectLink.endMValue - currentProjectLink.startMValue) * coef) + increment
//        } else {
//          startAddr + (currentProjectLink.endMValue - currentProjectLink.startMValue) * coef + increment
//        }
//
//        if (depth > 100) {
//          val message = s"mappedAddressValues got in infinite recursion. ProjectLink id = ${currentProjectLink.id}, startMValue = ${currentProjectLink.startMValue}, endMValue = ${currentProjectLink.endMValue}, previewValue = $previewValue, remaining = ${remaining.length}"
//          logger.error(message)
//          if (depth > 105) throw new ViiteException(message)
//        }
//
//        val adjustedList: Seq[Long] = if ((previewValue < endAddrM) && (previewValue > startAddr)) {
//          list :+ Math.round(previewValue)
//        } else if (previewValue <= startAddr) {
//          mappedAddressValues(Seq(remaining.head), processed, list.last, endAddr, coef, list, increment + 1, depth + 1)
//        } else if (previewValue <= endAddrM) {
//          mappedAddressValues(Seq(remaining.head), processed, list.last, endAddr, coef, list, increment - 1, depth + 1)
//        } else {
//          mappedAddressValues(processed.last +: remaining, processed.init, list.init.last, endAddr, coef, list.init, increment - 1, depth + 1)
//        }
//        mappedAddressValues(remaining.tail, processed :+ remaining.head, previewValue, endAddr, coef, adjustedList, increment, depth + 1)
//      }
//    }
//
//
//    val coefficient = (endAddrM - startAddrM) / projectLinks.map(pl => pl.endMValue - pl.startMValue).sum
//
//    val addresses = mappedAddressValues(projectLinks.init, Seq(), startAddrM, endAddrM, coefficient, Seq(startAddrM), 0) :+ endAddrM
//
//    var adjustedTerminated = Seq.empty[ProjectLink]
//
//    val adjustedProjectLinks = projectLinks.zip(addresses.zip(addresses.tail)).map {
//      case (projectLink, (st, en)) =>
//        val terminatedLinkAfterUnchangedProjectLink = terminatedLinks.find(terminated => terminated.originalAddrMRange.start == projectLink.originalAddrMRange.end && projectLink.status == RoadAddressChangeType.Unchanged)
//
//        if (terminatedLinkAfterUnchangedProjectLink.nonEmpty) {
//          val termLink = terminatedLinkAfterUnchangedProjectLink.get
//          val adjustedTerminatedLink = termLink.copy(addrMRange = AddrMRange(en, termLink.addrMRange.end), originalAddrMRange = AddrMRange(en, termLink.originalAddrMRange.end))
//          val index = terminatedLinks.indexOf(terminatedLinkAfterUnchangedProjectLink.get)
//          adjustedTerminated = terminatedLinks.updated(index, adjustedTerminatedLink)
//        }
//
//        projectLink.status match {
//          case RoadAddressChangeType.Transfer |
//               RoadAddressChangeType.Renumeration |
//               RoadAddressChangeType.New =>
//            projectLink.copy(addrMRange = AddrMRange(st, en))
//
//          case RoadAddressChangeType.Unchanged =>
//            val originalAddrMRange = if (projectLink.addrMRange.start == st && projectLink.addrMRange.end == en) projectLink.originalAddrMRange else AddrMRange(st, en)
//            projectLink.copy(addrMRange = AddrMRange(st, en), originalAddrMRange = originalAddrMRange)
//
//          case _ => projectLink
//        }
//    }
//    adjustedProjectLinks ++ adjustedTerminated
//  }

  /**
   * Adjusts project links on right and left tracks to ensure alignment and continuity.
   *
   * This function adjusts the address measures of project links on right and left tracks separately,
   * ensuring proper alignment and continuity based on original end addresses of continuous sections.
   *
   * @param rightLinks                     Sequence of project links on the right track.
   * @param leftLinks                      Sequence of project links on the left track.
   * @param userDefinedCalibrationPointMap Map containing calibration points, where keys are link IDs.
   * @return A tuple containing adjusted project links for the left and right tracks, respectively.
   */
  def adjustLinksOnTracks(rightLinks: Seq[ProjectLink], leftLinks: Seq[ProjectLink], userDefinedCalibrationPointMap: Map[Long, UserDefinedCalibrationPoint]): (Seq[ProjectLink], Seq[ProjectLink]) = {

    //  TODO VIITE-3120 The commented code below seems obsolete in current Viite app, commented out so they are available if needed after all (if you are deleting these lines, be sure to delete the other functions and code tagged with "TODO VIITE-3120")
    /* Adjust addresses before splits, calibration points after splits don't restrict calculation. */
    //val twoTrackLinksWithoutNew = (leftLinks ++ rightLinks).filter(filterOutNewAndCombinedLinks)
    //val (rightTrackLinksWithoutNew, leftTrackLinksWithoutNew) = twoTrackLinksWithoutNew.partition(_.track == Track.RightSide)
    //val rightTrackOriginalEndAddresses = findOriginalEndAddressesOfContinuousSectionsExcludingNewLinks(rightTrackLinksWithoutNew)
    //val leftTrackOriginalEndAddresses = findOriginalEndAddressesOfContinuousSectionsExcludingNewLinks(leftTrackLinksWithoutNew)
    //val distinctOriginalEndAddresses = (rightTrackOriginalEndAddresses ++ leftTrackOriginalEndAddresses).distinct.sorted
    //val leftLinksSplitByOriginalAddress = splitByOriginalAddresses(leftLinks, distinctOriginalEndAddresses)
    //val rightLinksSplitByOriginalAddress = splitByOriginalAddresses(rightLinks, distinctOriginalEndAddresses)
    //val (adjustedLeftLinks, adjustedRightLinks) = adjustTracksToMatch(leftLinksSplitByOriginalAddress, rightLinksSplitByOriginalAddress, None, userDefinedCalibrationPointMap)

    val (adjustedLeftLinks, adjustedRightLinks) = adjustTracksToMatch(leftLinks, rightLinks, None, userDefinedCalibrationPointMap)

    (adjustedLeftLinks, adjustedRightLinks)
  }

  /**
   * Calculates section address values for combined sections by adjusting tracks and performing various validations.
   *
   * @param sections                    The combined sections to process.
   * @param userDefinedCalibrationPoint A map containing user-defined calibration points indexed by their project link ID.
   * @return The combined sections with adjusted address values.
   */
  private def calculateSectionAddressValues(leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink],
                                            userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): Seq[CombinedSection] = {

    def handleUserDefinedCalibrationPoints(udcpsFromRightSideSplits: Seq[Option[UserDefinedCalibrationPoint]],
                                           udcpsFromLeftSideSplits: Seq[Option[UserDefinedCalibrationPoint]],
                                           splittedLeftLinks: Seq[ProjectLink]):  (Map[Long, UserDefinedCalibrationPoint],  Map[Long, UserDefinedCalibrationPoint]) = {

      def isUserDefinedCalibrationPointDefined(udcp: Option[UserDefinedCalibrationPoint]) = {
      udcp.isDefined && udcp.get.isInstanceOf[UserDefinedCalibrationPoint]
      }
      def toUdcpMap(udcp: Seq[Option[UserDefinedCalibrationPoint]]) = {
        udcp.filter(isUserDefinedCalibrationPointDefined).map(_.get).map(c => c.projectLinkId -> c)
      }

    // Combine UserDefinedCalibrationPoints from right and left side splits,
    // filter out those that are not defined or not instances of UserDefinedCalibrationPoint,
    // group them by project link ID, and filter out groups with only one UDCP.
    val duplicateUDCPs = (udcpsFromRightSideSplits ++ udcpsFromLeftSideSplits)
      .filter(isUserDefinedCalibrationPointDefined)
      .groupBy(_.get.projectLinkId)
      .filter(_._2.size > 1)

    /* Update udcp project link if split after second pass. */
    val updatedUDCPsFromRightSideSplits = duplicateUDCPs.foldLeft(udcpsFromRightSideSplits) { (udcps, cur) => {
      val splittedLeftLink       = splittedLeftLinks.find(_.id == cur._1)
      // Check if the left link is split and has a connected link
      if (splittedLeftLink.isDefined && splittedLeftLink.get.connectedLinkId.isDefined) {
        // Find the new link created after the split
        val newLink = splittedLeftLinks.find(_.addrMRange.start == splittedLeftLink.get.addrMRange.end).get

        val udcpToUpdate = udcps.find(_.get.projectLinkId == cur._1)
        udcps.filterNot(_.get.projectLinkId == cur._1) :+ Some(udcpToUpdate.get.get.copy(projectLinkId = newLink.id))
      } else {
        udcps
      }
    }}

      (toUdcpMap(updatedUDCPsFromRightSideSplits).toMap, toUdcpMap(udcpsFromLeftSideSplits).toMap)
    }

    val leftLinksWithAddrMValues = ProjectSectionMValueCalculator.assignLinkValues(leftProjectLinks.filter(_.status != RoadAddressChangeType.Unchanged), userDefinedCalibrationPoint,
      leftProjectLinks.filter(pl => pl.status == RoadAddressChangeType.Unchanged).map(_.addrMRange.end.toDouble).sorted.lastOption)

    val rightLinksWithAddrMValues = ProjectSectionMValueCalculator.assignLinkValues(rightProjectLinks.filter(_.status != RoadAddressChangeType.Unchanged), userDefinedCalibrationPoint,
      rightProjectLinks.filter(pl => pl.status == RoadAddressChangeType.Unchanged).map(_.addrMRange.end.toDouble).sorted.lastOption)

    // combine the unchanged links and the adjusted links
    val leftLinks = leftProjectLinks.filter(_.status == RoadAddressChangeType.Unchanged) ++ leftLinksWithAddrMValues
    val rightLinks = rightProjectLinks.filter(_.status == RoadAddressChangeType.Unchanged) ++ rightLinksWithAddrMValues

    // adjusts tracks to match
    val (trackAdjustedLeftLinks, trackAdjustedRightLinks) = adjustLinksOnTracks(rightLinks, leftLinks, userDefinedCalibrationPoint)

    val (leftLinksWithUdcps, splittedRightLinks, udcpsFromRightSideSplits) = TwoTrackRoadUtils.splitPlsAtStatusChange(trackAdjustedLeftLinks, trackAdjustedRightLinks)
    val (rightLinksWithUdcps, splittedLeftLinks, udcpsFromLeftSideSplits) = TwoTrackRoadUtils.splitPlsAtStatusChange(splittedRightLinks, leftLinksWithUdcps)

    val (splitCreatedCpsFromRightSide, splitCreatedCpsFromLeftSide) = handleUserDefinedCalibrationPoints(udcpsFromRightSideSplits, udcpsFromLeftSideSplits, splittedLeftLinks)

    //  TODO VIITE-3120 The commented code below seems obsolete in current Viite app, commented out so they are available if needed after all (if you are deleting these lines, be sure to delete the other functions aswell tagged with "TODO VIITE-3120")
    //val udcpSplitsAtOriginalAddresses = (filterOutNewLinks(rightLinksWithUdcps) ++ filterOutNewLinks(splittedLeftLinks)).filter(_.endCalibrationPointType == CalibrationPointType.UserDefinedCP).map(_.originalAddrMRange.end).filter(_ > 0)
    //val sortedSplitOriginalAddresses = (findOriginalEndAddressesOfContinuousSectionsExcludingNewLinks(rightLinksWithUdcps) ++ findOriginalEndAddressesOfContinuousSectionsExcludingNewLinks(splittedLeftLinks) ++ udcpSplitsAtOriginalAddresses).distinct.sorted
    //val leftLinksWithSplits  = splitByOriginalAddresses(splittedLeftLinks, sortedSplitOriginalAddresses)
    //val rightLinksWithSplits = splitByOriginalAddresses(rightLinksWithUdcps, sortedSplitOriginalAddresses)
    //val (adjustedLeftWithoutTerminated, adjustedRightWithoutTerminated) = (leftLinksWithSplits.filterNot(_.status == RoadAddressChangeType.Termination).sortBy(_.addrMRange.start), rightLinksWithSplits.filterNot(_.status == RoadAddressChangeType.Termination).sortBy(_.startAddrMValue))

    val (adjustedLeftWithoutTerminated, adjustedRightWithoutTerminated) = (splittedLeftLinks.filterNot(_.status == RoadAddressChangeType.Termination).sortBy(_.addrMRange.start), rightLinksWithUdcps.filterNot(_.status == RoadAddressChangeType.Termination).sortBy(_.addrMRange.start))

    val (right, left) = TrackSectionOrder.setCalibrationPoints(adjustedRightWithoutTerminated, adjustedLeftWithoutTerminated, userDefinedCalibrationPoint ++ splitCreatedCpsFromRightSide ++ splitCreatedCpsFromLeftSide)
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

    if ((oldLinks ++ newLinks).exists(pl => GeometryUtils.areAdjacent(pl.geometry, rightStartPoint) && pl.track == Track.Combined)) {
      (rightStartPoint, rightStartPoint)
    } else {
      // Get left track non-connected points and find the closest to right track starting point
      val (leftLinks, rightLinks) = (newLinks ++ oldLinks).filterNot(_.track == Track.Combined).partition(_.track == Track.LeftSide)
      val chainEndPoints = TrackSectionOrder.findChainEndpoints(leftLinks)

      if (chainEndPoints.isEmpty)
        throw new MissingTrackException("Tieosalta puuttuu vasen ajorata")

      val remainLinks = oldLinks ++ newLinks
      val points = remainLinks.map(pl => pl.getEndPoints)

      val (linksWithValues, linksWithoutValues) = remainLinks.partition(_.addrMRange.end != 0)
      val endPointsWithValues = ListMap(chainEndPoints.filter(link => link._2.addrMRange.start >= 0 && link._2.addrMRange.end != 0).toSeq
        .sortWith(_._2.addrMRange.start < _._2.addrMRange.start): _*)

      val foundConnectedLinks = TrackSectionOrder.findSinglyConnectedLinks(remainLinks)
        .values.filter(link => link.addrMRange.start == 0 && link.addrMRange.end != 0)

      // In case there is some old starting link, we want to prioritize the one that didn't change or was not treated yet.
      // We could have more than two starting link since one of them can be Transferred from any part to this one.
      val oldFirst: Option[ProjectLink] =
      if (foundConnectedLinks.nonEmpty) {
        foundConnectedLinks.find(_.status == RoadAddressChangeType.New)
          .orElse(foundConnectedLinks.find(l => l.status == RoadAddressChangeType.Unchanged))
          .orElse(foundConnectedLinks.headOption)
      } else {
        None
      }

      val leftStartPoint = {
        if (endPointsWithValues.size == 1) {
          val endLinkWithValues = endPointsWithValues.head._2
          val (currentEndPoint, otherEndPoint) = chainEndPoints.partition(_._2.id == endPointsWithValues.head._2.id)
          val singlyConnectLinks = TrackSectionOrder.findSinglyConnectedLinks(linksWithoutValues)
          val existsCloserProjectlink = linksWithValues.filter(pl => pl.addrMRange.start < endLinkWithValues.addrMRange.start && pl.id != endLinkWithValues.id)
          if (endPointsWithValues.nonEmpty && singlyConnectLinks.nonEmpty && linksWithValues.nonEmpty
            && (oldFirst.isDefined && points.count(p => GeometryUtils.areAdjacent(p._1, oldFirst.get.startingPoint)
            || GeometryUtils.areAdjacent(p._2, oldFirst.get.startingPoint)) > 1) // New links before the old starting point
            && (singlyConnectLinks.exists(connected => GeometryUtils.areAdjacent(connected._2.getEndPoints._2, endPointsWithValues.head._2.getEndPoints._1)
            || GeometryUtils.areAdjacent(connected._2.getEndPoints._1, endPointsWithValues.head._2.getEndPoints._1)
            || GeometryUtils.areAdjacent(linksWithValues.minBy(_.addrMRange.start).geometry, connected._2.getEndPoints._2)) || existsCloserProjectlink.nonEmpty)
          ) {
            otherEndPoint.head._1
          } else {
            if (currentEndPoint.head._1 == endPointsWithValues.head._2.endPoint)
              otherEndPoint.head._1
            else
              endPointsWithValues.head._1
          }
        } else {
          if (leftLinks.forall(_.addrMRange.end == 0) && rightLinks.nonEmpty && rightLinks.exists(_.addrMRange.end != 0)) {
            val rightStartPoint = TrackSectionOrder.findChainEndpoints(rightLinks).find(link => link._2.addrMRange.start == 0 && link._2.addrMRange.end != 0)
            chainEndPoints.minBy(p => p._1.distance2DTo(rightStartPoint.get._1))._1
          } else if (leftLinks.forall(_.addrMRange.end == 0) && rightLinks.forall(_.addrMRange.end == 0)) {
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
            // find the left start point normally
            findStartingPoint(newLinks.filter(_.track != Track.RightSide), oldLinks.filter(_.track != Track.RightSide), otherRoadPartLinks, calibrationPoints, (newLinks ++ oldLinks).filter(_.track == Track.RightSide))._1
            }
          }
        }
      (rightStartPoint, leftStartPoint)
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
      oldLinks.filter(_.status == RoadAddressChangeType.Unchanged).sortBy(_.addrMRange.start).headOption.map(pl => (pl.startingPoint, pl)).getOrElse {
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
        val (linksWithValues, linksWithoutValues) = remainLinks.partition(_.addrMRange.end != 0)
        val endPointsWithValues = ListMap(chainEndPoints.filter(link => link._2.addrMRange.start >= 0 && link._2.addrMRange.end != 0).toSeq
          .sortWith(_._2.addrMRange.start < _._2.addrMRange.start): _*)

        val singlyConnectedLinks = TrackSectionOrder.findSinglyConnectedLinks(remainLinks)
        var foundConnectedLinks = singlyConnectedLinks.values.filter(link => link.addrMRange.start == 0 && link.addrMRange.end != 0)
        /* Check if an existing road with loop end is reversed. */
        if (singlyConnectedLinks.size == 1 && foundConnectedLinks.isEmpty && TrackSectionOrder.hasTripleConnectionPoint(remainLinks) && remainLinks.forall(pl => pl.status == RoadAddressChangeType.Transfer && pl.reversed))
          foundConnectedLinks = Iterable(remainLinks.maxBy(pl => pl.originalAddrMRange.end))

        // In case there is some old starting link, we want to prioritize the one that didn't change or was not treated yet.
        // We could have more than two starting link since one of them can be Transferred from any part to this one.
        val oldFirst: Option[ProjectLink] =
          if (foundConnectedLinks.nonEmpty) {
            foundConnectedLinks.find(_.status == RoadAddressChangeType.New)
              .orElse(foundConnectedLinks.find(l => l.status == RoadAddressChangeType.Unchanged))
              .orElse(foundConnectedLinks.headOption)
          } else {
            None
          }

        /* One saved link. */
        if (endPointsWithValues.size == 1) {
          val endLinkWithValues = endPointsWithValues.head._2
          val (currentEndPoint, otherEndPoint) = chainEndPoints.partition(_._2.id == endPointsWithValues.head._2.id)
          val singlyConnectLinks = TrackSectionOrder.findSinglyConnectedLinks(linksWithoutValues)
          val existsCloserProjectlink = linksWithValues.filter(pl => pl.addrMRange.start < endLinkWithValues.addrMRange.start && pl.id != endLinkWithValues.id)
          if (endPointsWithValues.nonEmpty && singlyConnectLinks.nonEmpty && linksWithValues.nonEmpty
            && (oldFirst.isDefined && points.count(p => GeometryUtils.areAdjacent(p._1, oldFirst.get.startingPoint)
            || GeometryUtils.areAdjacent(p._2, oldFirst.get.startingPoint)) > 1) // New links before the old starting point
            && (singlyConnectLinks.exists(connected => GeometryUtils.areAdjacent(connected._2.getEndPoints._2, endPointsWithValues.head._2.getEndPoints._1)
            || GeometryUtils.areAdjacent(connected._2.getEndPoints._1, endPointsWithValues.head._2.getEndPoints._1)
            || GeometryUtils.areAdjacent(linksWithValues.minBy(_.addrMRange.start).geometry, connected._2.getEndPoints._2)) || existsCloserProjectlink.nonEmpty)
          ) {
            otherEndPoint.head
          } else {
            if (currentEndPoint.head._1 == endPointsWithValues.head._2.endPoint)
              otherEndPoint.head
            else
              endPointsWithValues.head
          }
        } else if (chainEndPoints.forall(_._2.addrMRange.end != 0) && oldFirst.isDefined) {
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
          if (remainLinks.forall(_.isNotCalculated) && oppositeTrackLinks.nonEmpty && oppositeTrackLinks.exists(_.addrMRange.end != 0)) {
            val leftStartPoint = TrackSectionOrder.findChainEndpoints(oppositeTrackLinks).find(link => link._2.addrMRange.start == 0 && link._2.addrMRange.end != 0)
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
      val lastLengthDifference       = Math.abs(sect.last.addrMRange.end - oppositeSect.last.addrMRange.end)
      val secondLastLengthDifference = Math.abs(sect(sect.size - 2).addrMRange.end - oppositeSect.last.addrMRange.end)
      ((sect.last.discontinuity != Discontinuity.Continuous) || (oppositeSect.last.discontinuity != Discontinuity.Continuous)) &&
      lastLengthDifference > secondLastLengthDifference &&
      secondLastLengthDifference != 0 // Equal case should be found by lengthCompare().
    }
  }

  def getEqualRoadwaySections(sect: FirstRestSections, oppositeSect: FirstRestSections): ((Seq[ProjectLink], Seq[ProjectLink]), (Seq[ProjectLink], Seq[ProjectLink])) = {
    val newFirstSection = {
      val closestEndPl = sect.first.minBy(pl => Math.abs(pl.addrMRange.end - oppositeSect.first.last.addrMRange.end))
      sect.first.takeWhile(pl => pl.addrMRange.end <= closestEndPl.addrMRange.end)
    }
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
    val firstRightEndAddress = rightSections.first.last.addrMRange.end
    val firstLeftEndAddress  = leftSections.first.last.addrMRange.end

    Seq(firstRightEndAddress == firstLeftEndAddress,
        firstRightEndAddress  > firstLeftEndAddress,
        firstRightEndAddress  < firstLeftEndAddress)
      .indexOf(true)
  }
}
