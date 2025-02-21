package fi.liikennevirasto.viite.process.strategy
import fi.liikennevirasto.viite.dao.ProjectLink
import fi.vaylavirasto.viite.model.{AddrMRange, Discontinuity, RoadAddressChangeType, RoadPart, Track}
import fi.vaylavirasto.viite.util.ViiteException

object TerminatedTwoTrackSectionSynchronizer {

  // The maximum difference that two different tracks (Left and Right) can have at the start or end of addrMRange to be considered parallel.
  private val maxDiffForTracks = 10 // This number is arbitrary and may require adjustments in the future.

  private def calculateAverageAddrM(addrM1: Long, addrM2: Long): Long = {
    // Since Math.round rounds to the nearest whole number, adding small constant (for example 0.1) ensures that minor precision errors (especially
    // due to floating-point calculations) do not cause unexpected results.
    val erroneousRoundingPreventionCoefficient = 0.1
    Math.round((0.5 * (addrM1 + addrM2)) + erroneousRoundingPreventionCoefficient)
  }

  /**
   * Adjusts two track terminated sections to match + the surrounding links if needed.
   * @param roadPartProjectLinksWithoutNewLinks Sequence of project links to adjust on a single road part (NOTE! RoadAddressChangeType.New links NOT allowed)
   */
  def adjustTerminations(roadPartProjectLinksWithoutNewLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    def existsTerminationsOnParallelTracks(projectLinks: Seq[ProjectLink]): Boolean = {
      projectLinks.exists(pl => pl.track == Track.RightSide && pl.status == RoadAddressChangeType.Termination) &&
      projectLinks.exists(pl => pl.track == Track.LeftSide && pl.status == RoadAddressChangeType.Termination)
    }

    // Some validations for the project links
    if (roadPartProjectLinksWithoutNewLinks.map(_.roadPart).distinct.size > 1) {
      throw ViiteException(s"Terminated two track project links can only be adjusted one road part at a time!")
    }

    if (roadPartProjectLinksWithoutNewLinks.exists(_.status == RoadAddressChangeType.New)) {
      throw ViiteException(s"New project links are not allowed for the process of adjusting terminated two track links!")
    }

    val processedLinks: Seq[ProjectLink] = {

      // Check that there are terminated links on both tracks
      if (existsTerminationsOnParallelTracks(roadPartProjectLinksWithoutNewLinks)) {
        val orderedProjectLinks = roadPartProjectLinksWithoutNewLinks.sortBy(_.addrMRange.start)

        // List of termination cases
        val terminationCases: List[Seq[ProjectLink] => Seq[ProjectLink]] = List(
          roadPartStartTerminated,
          roadPartEndTerminated,
          roadPartMiddleTerminated
        )

        // Sequentially apply each case function
        terminationCases.foldLeft(orderedProjectLinks)((linksAccumulator, caseFunction) => caseFunction(linksAccumulator))

      } else {
        // else we return the links unchanged.
        roadPartProjectLinksWithoutNewLinks
      }
    }
    processedLinks
  }

  /**
   * Takes in project links with same RoadAddressChangeType and divides them in to continuous sections.
   *
   * Continuous section has same roadPart, change type / status, track and each project link starts from the
   * same addrM where the previous project link ended.
   *
   * @param projectLinksWithSameStatus Sequence of project links that all share the same status / change type.
   * @param sectionStatus RoadAddressChangeType i.e. the change type / status of the project links.
   */
  private def toContinuousSectionsByStatus(projectLinksWithSameStatus: Seq[ProjectLink], sectionStatus: RoadAddressChangeType): Seq[Seq[ProjectLink]] = {
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
        if (link.roadPart == lastLink.roadPart &&
          link.status == sectionStatus &&
          lastLink.addrMRange.continuesTo(link.addrMRange) &&
          lastLink.track == link.track) {
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

  /**
   * Adjusts terminated project links to have matching averaged start, and end addresses.
   * @param terminatedLeft Left track terminated projectLink to adjust.
   * @param terminatedRight Right track terminated projectLink to adjust.
   * @returns Adjusted project links and the new averaged AddrMRange of the adjusted project links.
   */
  private def adjustTerminatedToMatch(terminatedLeft: ProjectLink, terminatedRight: ProjectLink): (ProjectLink, ProjectLink, AddrMRange) = {
    val averageStart  = calculateAverageAddrM(terminatedLeft.addrMRange.start, terminatedRight.addrMRange.start)
    val averageEnd    = calculateAverageAddrM(terminatedLeft.addrMRange.end, terminatedRight.addrMRange.end)

    val averagedAddrMRange = AddrMRange(averageStart, averageEnd)

    val adjustedLeft  = terminatedLeft.copy( addrMRange = averagedAddrMRange,originalAddrMRange = averagedAddrMRange)
    val adjustedRight = terminatedRight.copy(addrMRange = averagedAddrMRange,originalAddrMRange = averagedAddrMRange)

    (adjustedLeft, adjustedRight, averagedAddrMRange)
  }

  private def updateProjectLinksList(modifiedProjectLinks: Seq[ProjectLink], projectLinksToUpdate: Seq[ProjectLink]): Seq[ProjectLink] = {
    val modifiedLinksMap = modifiedProjectLinks.map(link => link.id -> link).toMap // Convert to Map for fast lookups
    projectLinksToUpdate.map(link => modifiedLinksMap.getOrElse(link.id, link))    // Replace if found, otherwise keep original
  }

  /**
   * Divide terminated project links in to continuous two track sections.
   * (Homogeneous by RoadPart, Track, Status and Continuous by address M values)
   */
  private def terminatedLinksToContinuousTwoTrackSections(terminatedLinks: Seq[ProjectLink]) : (Seq[Seq[ProjectLink]], Seq[Seq[ProjectLink]]) = {
    val terminatedLeft = terminatedLinks.filter(_.track == Track.LeftSide)
    val terminatedRight = terminatedLinks.filter(_.track == Track.RightSide)
    // Group terminated links into continuous sections by status
    val leftTerminatedSections = toContinuousSectionsByStatus(terminatedLeft, RoadAddressChangeType.Termination)
    val rightTerminatedSections = toContinuousSectionsByStatus(terminatedRight, RoadAddressChangeType.Termination)
    (leftTerminatedSections, rightTerminatedSections)
  }

  /**
   * Checks if there is a two track termination on road part start.
   * If there is, then adjusts the terminated track addresses to match + the originalAddrMRange start of the links right after the terminated segment.
   * Else returns the project links unchanged.
   * @param projectLinks Sequence of project links to adjust
   */
  private def roadPartStartTerminated(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    val terminatedLinks = projectLinks.filter(_.status == RoadAddressChangeType.Termination)

    val (leftTerminatedSections, rightTerminatedSections) = terminatedLinksToContinuousTwoTrackSections(terminatedLinks)

    val roadPartStartTerminatedLefts = leftTerminatedSections.find(section => section.exists(_.addrMRange.isRoadPartStart))
    val roadPartStartTerminatedRights = rightTerminatedSections.find(section => section.exists(_.addrMRange.isRoadPartStart))

    val processedLinks = if (roadPartStartTerminatedLefts.nonEmpty && roadPartStartTerminatedRights.nonEmpty) { // Check if road part start is terminated on both tracks
      handleTwoTrackRoadPartStartTermination(roadPartStartTerminatedLefts.get, roadPartStartTerminatedRights.get, projectLinks)
    } else
      projectLinks

    processedLinks
  }

  /**
   * Adjusts terminated sections on two track road part start to have matching addresses (if reasonably close to each other) and
   *  the project links that come after the terminated section.
   *
   * Example:
   *
   *  Terminated:     ==>
   *  Transferred:    -->
   *
   *  Before:
   *
   *  0       203  250   450
   *  ========>---->----->
   *  ======>------->---->
   *  0     200     260  450
   *
   *  After:
   *
   *  0       202  250   450
   *  ========>---->----->
   *  ======>------->---->
   *  0     202     260  450
   */
  private def handleTwoTrackRoadPartStartTermination(terminatedLeftSection: Seq[ProjectLink], terminatedRightSection: Seq[ProjectLink], projectLinks: Seq[ProjectLink]): Seq[ProjectLink]= {
    val lastTerminatedOnLeftSideSection = terminatedLeftSection.last
    val lastTerminatedOnRightSideSection = terminatedRightSection.last

    // Compare original addresses here because we are modifying the starting situation
    val continuousAfterTerminatedLeft   = projectLinks.find(pl => pl.track == Track.LeftSide && pl.originalAddrMRange.continuesFrom(lastTerminatedOnLeftSideSection.originalAddrMRange))
    val continuousAfterTerminatedRight  = projectLinks.find(pl => pl.track == Track.RightSide && pl.originalAddrMRange.continuesFrom(lastTerminatedOnRightSideSection.originalAddrMRange))

    // Adjust terminated
    val processedLinks: Seq[ProjectLink] = {
      if (areTracksCloseEnoughOnEndAddrM(lastTerminatedOnLeftSideSection, lastTerminatedOnRightSideSection)) {
        // Adjust the last terminated links on each track to match
        val (adjustedTerminatedLeft, adjustedTerminatedRight, averagedAddrMRange) = adjustTerminatedToMatch(lastTerminatedOnLeftSideSection, lastTerminatedOnRightSideSection)

        if (continuousAfterTerminatedLeft.isDefined && continuousAfterTerminatedRight.isDefined) {
          // Adjust both links after the terminated section
          val adjustedLeftAddrMRange = AddrMRange(averagedAddrMRange.end, continuousAfterTerminatedLeft.get.originalAddrMRange.end)
          val adjLeftContinuousAfterTerminated = continuousAfterTerminatedLeft.get.copy(
            originalAddrMRange = adjustedLeftAddrMRange
          )

          val adjustedRightAddrMRange = AddrMRange(averagedAddrMRange.end, continuousAfterTerminatedRight.get.originalAddrMRange.end)
          val adjRightContinuousAfterTerminated = continuousAfterTerminatedRight.get.copy(
            originalAddrMRange = adjustedRightAddrMRange
          )
          // Update the project links list with the adjusted terminated links and the adjusted continuous-after-termination links
          updateProjectLinksList(Seq(adjustedTerminatedLeft, adjustedTerminatedRight, adjLeftContinuousAfterTerminated, adjRightContinuousAfterTerminated), projectLinks)
        } else {
          // Just update the terminated links
          updateProjectLinksList(Seq(adjustedTerminatedLeft, adjustedTerminatedRight), projectLinks)
        }

      } else {
        projectLinks
      }
    }
    processedLinks
  }

  private def roadPartMiddleTerminated(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {

    val terminatedLinks = projectLinks.filter(_.status == RoadAddressChangeType.Termination)

    val (leftTerminatedSections, rightTerminatedSections) = terminatedLinksToContinuousTwoTrackSections(terminatedLinks)

    val processedLinks = if (leftTerminatedSections.nonEmpty && rightTerminatedSections.nonEmpty) { // Check that there are terminated sections on both tracks
      handleTwoTrackMiddleTermination(leftTerminatedSections, rightTerminatedSections, projectLinks)
    } else
      projectLinks

    processedLinks
  }

  /**
   * Adjusts two track terminated sections to match if the preceding link(s) has/have a Discontinuity.MinorDiscontinuity
   * The minor discontinuity links (links just before the terminated sections) will also be adjusted to match the start of the terminated section.
   * If there are links after the adjusted terminated section, those links will also be adjusted to match the end of the adjusted terminated section.
   *
   * Example:
   *
   *  Terminated:     ==>
   *  Nonterminated:  -->
   *
   *  Before:
   *
   *  0      202    250   450
   *  ------->======>----->
   *  ------>====>===>---->
   *  0     200  230 252   450
   *
   *  After:
   *
   *  0      201    251   450
   *  ------->======>----->
   *  ------>====>===>---->
   *  0     201  230 251  450
   */
  private def handleTwoTrackMiddleTermination(terminatedLeftSections: Seq[Seq[ProjectLink]], terminatedRightSections: Seq[Seq[ProjectLink]], projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    /**
     * Find pairs of minorDiscontinuity links on opposite tracks, reasonably close to each other.
     */
    def findMinorDiscontinuityLinkPairs(minorDiscontinuityLinks: Seq[ProjectLink]): Seq[Seq[ProjectLink]] = {
      minorDiscontinuityLinks.filter(_.track == Track.LeftSide).flatMap { leftLink =>
        minorDiscontinuityLinks.filter(rightLink =>
          rightLink.track == Track.RightSide &&
            areTracksCloseEnoughOnEndAddrM(leftLink,rightLink)
        ).map(rightLink => Seq(leftLink, rightLink))
      }
    }

    val minorDiscontinuityLinks = projectLinks.filter(_.discontinuity == Discontinuity.MinorDiscontinuity)
    val combinedMinorDiscontinuityLinks = minorDiscontinuityLinks.filter(_.track == Track.Combined)
    val minorDiscontinuityLinkPairs = findMinorDiscontinuityLinkPairs(minorDiscontinuityLinks)
    val minorDiscontinuitiesToProcess = minorDiscontinuityLinkPairs ++ Seq(combinedMinorDiscontinuityLinks)

    def findNextLinkBasedOnOriginalAddresses(originalAddrMRange: AddrMRange, projectLinks: Seq[ProjectLink]): Option[ProjectLink] = {
      projectLinks.find(pl => originalAddrMRange.continuesTo(pl.originalAddrMRange))
    }

    val processedLinks = {
      var updatedProjectLinks = projectLinks
      if (minorDiscontinuitiesToProcess.nonEmpty) {
        // Process Minor discontinuity link pairs one by one
        minorDiscontinuitiesToProcess.foreach({ minorDiscontinuityLinks =>
          val leftUpdatedTerminatedLinks =  updatedProjectLinks.filter(pl => pl.status == RoadAddressChangeType.Termination && pl.track == Track.LeftSide)
          val rightUpdatedTerminatedLinks = updatedProjectLinks.filter(pl => pl.status == RoadAddressChangeType.Termination && pl.track == Track.RightSide)
          // Find terminated sections that are located right after the minor discontinuity link(s)
          val (leftTerminatedAfterMinorDisc, rightTerminatedAfterMinorDisc) = minorDiscontinuityLinks match {
            case Seq(combined) =>
              val leftTerminatedAfterMinorDisc  = findNextLinkBasedOnOriginalAddresses(combined.originalAddrMRange, leftUpdatedTerminatedLinks)
              val rightTerminatedAfterMinorDisc = findNextLinkBasedOnOriginalAddresses(combined.originalAddrMRange, rightUpdatedTerminatedLinks)
              (leftTerminatedAfterMinorDisc, rightTerminatedAfterMinorDisc)
            case Seq(left, right) =>
              val leftTerminatedAfterMinorDisc  = findNextLinkBasedOnOriginalAddresses(left.originalAddrMRange,  leftUpdatedTerminatedLinks)
              val rightTerminatedAfterMinorDisc = findNextLinkBasedOnOriginalAddresses(right.originalAddrMRange, rightUpdatedTerminatedLinks)
              (leftTerminatedAfterMinorDisc, rightTerminatedAfterMinorDisc)
            case _ => (None,None)
          }

          if (leftTerminatedAfterMinorDisc.isDefined && rightTerminatedAfterMinorDisc.isDefined) {
            // Update terminated links to match, the discontinuity links to match and if there are links after terminated section, adjust those as well
            // Get the terminated sections for both tracks
            val leftTermSect  = terminatedLeftSections.find( section => section.exists(_.id == leftTerminatedAfterMinorDisc.get.id))
            val rightTermSect = terminatedRightSections.find(section => section.exists(_.id == rightTerminatedAfterMinorDisc.get.id))

            val firstTerminatedLeft   = leftTermSect.get.minBy(_.addrMRange.start)
            val firstTerminatedRight  = rightTermSect.get.minBy(_.addrMRange.start)

            val lastTerminatedLeft  = leftTermSect.get.maxBy(_.addrMRange.end)
            val lastTerminatedRight = rightTermSect.get.maxBy(_.addrMRange.end)

            val averageStartForTermSect = calculateAverageAddrM(firstTerminatedLeft.addrMRange.start, firstTerminatedRight.addrMRange.start)
            val averageEndForTermSect   = calculateAverageAddrM(lastTerminatedLeft.addrMRange.end, lastTerminatedRight.addrMRange.end)

            def adjustTerminatedLinks(firstTerminatedLink: ProjectLink, lastTerminatedLink: ProjectLink): Seq[ProjectLink] = {
              if (firstTerminatedLink == lastTerminatedLink) {
                // Same link so update the link from both ends
                val startAndEndAveraged = AddrMRange(averageStartForTermSect, averageEndForTermSect)
                val updatedLink = firstTerminatedLink.copy(addrMRange = startAndEndAveraged, originalAddrMRange = startAndEndAveraged)
                Seq(updatedLink)
              } else {
                // Update separately
                val startAveraged = AddrMRange(averageStartForTermSect, firstTerminatedLink.addrMRange.end)
                val endAveraged   = AddrMRange(lastTerminatedLink.addrMRange.start, averageEndForTermSect)

                val updatedFirstLink = firstTerminatedLink.copy(addrMRange = startAveraged, originalAddrMRange  = startAveraged)
                val updatedLastLink  = lastTerminatedLink.copy( addrMRange = endAveraged,   originalAddrMRange  = endAveraged)

                Seq(updatedFirstLink,updatedLastLink)
              }
            }

            val adjustedTerminatedLeft  = adjustTerminatedLinks(firstTerminatedLeft, lastTerminatedLeft)
            val adjustedTerminatedRight = adjustTerminatedLinks(firstTerminatedRight, lastTerminatedRight)

            val afterLeftTerminatedSection = {
              updatedProjectLinks.find(pl => pl.track != Track.RightSide && lastTerminatedLeft.originalAddrMRange.continuesTo(pl.originalAddrMRange))
            }

            val afterRightTerminatedSection = {
              updatedProjectLinks.find(pl => pl.track != Track.LeftSide && lastTerminatedRight.originalAddrMRange.continuesTo(pl.originalAddrMRange))
            }

            val adjustedAfterTermination: Seq[ProjectLink] = {
              if (afterLeftTerminatedSection.isDefined && afterRightTerminatedSection.isDefined) {
                val adjustedLeftLink  = replaceStartsWith(afterLeftTerminatedSection.get, averageEndForTermSect)
                val adjustedRightLink = replaceStartsWith(afterRightTerminatedSection.get, averageEndForTermSect)
                Seq(adjustedLeftLink, adjustedRightLink)
              } else {
                Seq()
              }
            }

            val updatedMinorDiscLinks = {
              minorDiscontinuityLinks.map(minorDiscLink => replaceEndsWith(minorDiscLink, averageStartForTermSect))
            }

            updatedProjectLinks = updateProjectLinksList(
              updatedMinorDiscLinks ++ adjustedTerminatedLeft ++ adjustedTerminatedRight ++ adjustedAfterTermination,
              updatedProjectLinks
            )
          }
        })
      }
      updatedProjectLinks
    }
    processedLinks
  }

  private def roadPartEndTerminated(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    val terminatedLinks = projectLinks.filter(_.status == RoadAddressChangeType.Termination)
    val maxOriginalAddrM = projectLinks.map(_.originalAddrMRange.end).max
    val lastTerminatedLeft  = terminatedLinks.filter(pl => pl.track == Track.LeftSide).maxBy(_.originalAddrMRange.end)
    val lastTerminatedRight = terminatedLinks.filter(pl => pl.track == Track.RightSide).maxBy(_.originalAddrMRange.end)

    val processedLinks = {
      if (lastTerminatedLeft.originalAddrMRange.end == maxOriginalAddrM &&
        lastTerminatedRight.originalAddrMRange.end == maxOriginalAddrM) {
        // If road part end is two track and terminated
        val (leftTerminatedSections, rightTerminatedSections) = terminatedLinksToContinuousTwoTrackSections(terminatedLinks)
        val lastTerminatedLeftSection   = leftTerminatedSections.find(section => section.exists(_.id == lastTerminatedLeft.id)).get
        val lastTerminatedRightSection  = rightTerminatedSections.find(section => section.exists(_.id == lastTerminatedRight.id)).get
        handleTwoTrackRoadPartEndTermination(lastTerminatedLeftSection, lastTerminatedRightSection, projectLinks)
      } else {
        projectLinks
      }
    }
    processedLinks
  }

  private def handleTwoTrackRoadPartEndTermination(terminatedLeftSection: Seq[ProjectLink], terminatedRightSection: Seq[ProjectLink], projectLinks: Seq[ProjectLink]): Seq[ProjectLink]= {

    def adjustTerminatedStartToMatch(terminatedLeftLink: ProjectLink, terminatedRightLink: ProjectLink): (ProjectLink, ProjectLink, Long) = {
      // Calculate the average for terminated section start
      val averageStart = calculateAverageAddrM(terminatedLeftLink.addrMRange.start, terminatedRightLink.addrMRange.start)
      val adjustedTermLeft = replaceStartsWith(terminatedLeftLink, averageStart)
      val adjustedTermRight = replaceStartsWith(terminatedRightLink, averageStart)
      (adjustedTermLeft, adjustedTermRight, averageStart)
    }

    def adjustPreviousLinkEndsToMatch(previousLeftLink: ProjectLink, previousRightLink: ProjectLink, addrMToAdjust: Long): (ProjectLink, ProjectLink) = {
      // Adjust the previous links' end addresses to match
      val adjustedPreviousLeftLink = replaceEndsWith(previousLeftLink, addrMToAdjust)
      val adjustedPreviousRightLink = replaceEndsWith(previousRightLink, addrMToAdjust)
      (adjustedPreviousLeftLink, adjustedPreviousRightLink)
    }

    val firstLinkOnLeftTermSection  = terminatedLeftSection.minBy(_.addrMRange.start)
    val firstLinkOnRightTermSection =  terminatedRightSection.minBy(_.addrMRange.start)

    val processedLinks = {
      if ((firstLinkOnLeftTermSection.addrMRange.start == firstLinkOnRightTermSection.addrMRange.start) || // Address starts match on first links of terminated section
        !areTracksCloseEnoughOnOriginalStartAddrM(firstLinkOnLeftTermSection, firstLinkOnRightTermSection)) { // Address starts are too far away each other
        // Return the project links unchanged
        projectLinks
      } else {
        // Update the first terminated links of the section to match at the start
        val (adjustedTermLeft, adjustedTermRight, averageStartForTerminated) = adjustTerminatedStartToMatch(firstLinkOnLeftTermSection, firstLinkOnRightTermSection)
        // Find previous links if there are any
        val previousLeftLink = projectLinks.find(pl => pl.track == Track.LeftSide && pl.originalAddrMRange.continuesTo(firstLinkOnLeftTermSection.originalAddrMRange))
        val previousRightLink = projectLinks.find(pl => pl.track == Track.RightSide && pl.originalAddrMRange.continuesTo(firstLinkOnRightTermSection.originalAddrMRange))
        if (previousLeftLink.isDefined && previousRightLink.isDefined) {
          // Update the previous link starts to match
          val (adjustedPreviousLeftLink, adjustedPreviousRightLink)  = adjustPreviousLinkEndsToMatch(previousLeftLink.get, previousRightLink.get, averageStartForTerminated)
          updateProjectLinksList(Seq(adjustedTermLeft, adjustedTermRight, adjustedPreviousLeftLink, adjustedPreviousRightLink), projectLinks)
        } else {
          // No need to update the previous links
          updateProjectLinksList(Seq(adjustedTermLeft, adjustedTermRight), projectLinks)
        }
      }
    }
    processedLinks
  }

  private def areTracksCloseEnoughOnOriginalStartAddrM(leftLink: ProjectLink, rightLink: ProjectLink): Boolean = {
    Math.abs(leftLink.originalAddrMRange.start - rightLink.originalAddrMRange.start) <= maxDiffForTracks
  }

  private def areTracksCloseEnoughOnEndAddrM(leftLink: ProjectLink, rightLink: ProjectLink): Boolean = {
    Math.abs(leftLink.addrMRange.end - rightLink.addrMRange.end) <= maxDiffForTracks
  }

  private def replaceStartsWith(projectLink: ProjectLink, replacingStartAddrM: Long): ProjectLink = {
    projectLink.copy(
      addrMRange          = AddrMRange(replacingStartAddrM, projectLink.addrMRange.end),
      originalAddrMRange  = AddrMRange(replacingStartAddrM, projectLink.originalAddrMRange.end)
    )
  }

  private def replaceEndsWith(projectLink: ProjectLink, replacingEndAddrM: Long): ProjectLink = {
    projectLink.copy(
      addrMRange          = AddrMRange(projectLink.addrMRange.start, replacingEndAddrM),
      originalAddrMRange  = AddrMRange(projectLink.originalAddrMRange.start, replacingEndAddrM)
    )
  }
}

