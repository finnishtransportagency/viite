package fi.liikennevirasto.viite.process.strategy
import fi.liikennevirasto.viite.dao.ProjectLink
import fi.vaylavirasto.viite.model.{AddrMRange, Discontinuity, RoadAddressChangeType, RoadPart, Track}
import fi.vaylavirasto.viite.util.ViiteException

object TerminatedTwoTrackSectionSynchronizer {

  /**
   * Adjusts two track terminated sections to match + the surrounding links if needed.
   * @param projectLinks Sequence of project links to adjust (NOTE! RoadAddressChangeType.New links NOT allowed)
   */
  def adjustTerminations(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    def existsTerminationsOnParallelTracks(projectLinks: Seq[ProjectLink]): Boolean = {
      projectLinks.exists(pl => pl.track == Track.RightSide && pl.status == RoadAddressChangeType.Termination) &&
        projectLinks.exists(pl => pl.track == Track.LeftSide && pl.status == RoadAddressChangeType.Termination)
    }

    val processedLinks: Seq[ProjectLink] = {

      if (projectLinks.exists(_.status == RoadAddressChangeType.New))
        throw ViiteException(s"New links are not allowed for the process of adjusting terminated two track links!")

      // Check that there are terminated links on both tracks
      if (existsTerminationsOnParallelTracks(projectLinks)) {
        val orderedProjectLinks = projectLinks.sortBy(_.addrMRange.start)

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
        projectLinks
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
   * Adjusts terminated links to match at both ends by averaging the start- and endAddrMs' of the project links.
   * @param terminatedLeft Left track projectLink to adjust.
   * @param terminatedRight Right track projectLink adjust.
   * @returns Adjusted project links and the new AddrMRange of the adjusted project links.
   */
  def adjustTerminatedToMatch(terminatedLeft: ProjectLink, terminatedRight: ProjectLink): (ProjectLink, ProjectLink, AddrMRange) = {
    val averageStart = Math.round((terminatedLeft.addrMRange.start + terminatedRight.addrMRange.start).toDouble / 2)
    val averageEnd = Math.round((terminatedLeft.addrMRange.end + terminatedRight.addrMRange.end).toDouble / 2)

    val averagedAddrMRange = AddrMRange(averageStart, averageEnd)

    val adjustedLeft  = terminatedLeft.copy( addrMRange = averagedAddrMRange,originalAddrMRange = averagedAddrMRange)
    val adjustedRight = terminatedRight.copy(addrMRange = averagedAddrMRange,originalAddrMRange = averagedAddrMRange)

    (adjustedLeft, adjustedRight, averagedAddrMRange)
  }

  def updateProjectLinksList(modifiedProjectLinks: Seq[ProjectLink], projectLinksToUpdate: Seq[ProjectLink]): Seq[ProjectLink] = {
    val modifiedLinksMap = modifiedProjectLinks.map(link => link.id -> link).toMap // Convert to Map for fast lookups
    projectLinksToUpdate.map(link => modifiedLinksMap.getOrElse(link.id, link))    // Replace if found, otherwise keep original
  }

  /**
   * Divide terminated project links in to continuous two track sections.
   * (Homogeneous by RoadPart, Track, Status and Continuous by address M values)
   */
  def terminatedLinksToToContinuousTwoTrackSections(terminatedLinks: Seq[ProjectLink]) : (Seq[Seq[ProjectLink]], Seq[Seq[ProjectLink]]) = {
    val terminatedLeft = terminatedLinks.filter(_.track == Track.LeftSide)
    val terminatedRight = terminatedLinks.filter(_.track == Track.RightSide)
    // Group terminated links into continuous sections by status
    val leftTerminatedSections = toContinuousSectionsByStatus(terminatedLeft, RoadAddressChangeType.Termination)
    val rightTerminatedSections = toContinuousSectionsByStatus(terminatedRight, RoadAddressChangeType.Termination)
    (leftTerminatedSections, rightTerminatedSections)
  }

  /**
   * Checks if there is a two track termination on road part start.
   * If there is, then adjusts the terminated tracks to match + the originalAddrMRange start of the links right after the terminated segment.
   * Else returns the project links unchanged.
   * @param projectLinks Sequence of project links to adjust
   */
  def roadPartStartTerminated(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    val terminatedLinks = projectLinks.filter(_.status == RoadAddressChangeType.Termination)

    val (leftTerminatedSections, rightTerminatedSections) = terminatedLinksToToContinuousTwoTrackSections(terminatedLinks)

    val roadPartStartTerminatedLefts = leftTerminatedSections.find(section => section.exists(_.addrMRange.isRoadPartStart))
    val roadPartStartTerminatedRights = rightTerminatedSections.find(section => section.exists(_.addrMRange.isRoadPartStart))

    val processedLinks = if (roadPartStartTerminatedLefts.nonEmpty && roadPartStartTerminatedRights.nonEmpty) { // Check if road part start is terminated on both tracks
      handleTwoTrackRoadPartStartTermination(roadPartStartTerminatedLefts.get, roadPartStartTerminatedRights.get, projectLinks)
    } else
      projectLinks

    processedLinks
  }

  /**
   * Adjusts terminated sections on two track road part start to match (if reasonably close to each other) and
   *  the links that come after the terminated section.
   *
   * Example:
   *
   *  Terminated:     ==>
   *  Nonterminated:  -->
   *
   *  Before:
   *
   *  0       202  250   450
   *  ========>---->----->
   *  ======>------->---->
   *  0     200     260  450
   *
   *  After:
   *
   *  0       201  250   450
   *  ========>---->----->
   *  ======>------->---->
   *  0     201     260  450
   */
  def handleTwoTrackRoadPartStartTermination(terminatedLeftSection: Seq[ProjectLink], terminatedRightSection: Seq[ProjectLink], projectLinks: Seq[ProjectLink]): Seq[ProjectLink]= {
    val maxDiffForTrackEnds = 10 // This number is arbitrary and may require adjustments in the future.
    val lastTerminatedOnLeftSideSection = terminatedLeftSection.last
    val lastTerminatedOnRightSideSection = terminatedRightSection.last

    val continuousAfterTerminatedLeft   = projectLinks.find(pl => pl.track == Track.LeftSide && pl.originalAddrMRange.continuesFrom(lastTerminatedOnLeftSideSection.originalAddrMRange))
    val continuousAfterTerminatedRight  = projectLinks.find(pl => pl.track == Track.RightSide && pl.originalAddrMRange.continuesFrom(lastTerminatedOnRightSideSection.originalAddrMRange))

    // Adjust terminated
    val processedLinks: Seq[ProjectLink] = {
      if (Math.abs(lastTerminatedOnLeftSideSection.addrMRange.end - lastTerminatedOnRightSideSection.addrMRange.end) <= maxDiffForTrackEnds) {
        // Adjust the last terminated links on each track to match
        val (adjustedTerminatedLeft, adjustedTerminatedRight, averagedAddrMRange) = adjustTerminatedToMatch(lastTerminatedOnLeftSideSection, lastTerminatedOnRightSideSection)

        if (continuousAfterTerminatedLeft.isDefined && continuousAfterTerminatedRight.isDefined) {
          // Adjust both links and update the project links list with the adjusted terminated links and the adjusted continuous-after-termination links
          val adjustedLeftAddrMRange = AddrMRange(averagedAddrMRange.end, continuousAfterTerminatedLeft.get.addrMRange.end)
          val adjLeftContinuousAfterTerminated = continuousAfterTerminatedLeft.get.copy(addrMRange = adjustedLeftAddrMRange,originalAddrMRange = adjustedLeftAddrMRange)

          val adjustedRightAddrMRange = AddrMRange(averagedAddrMRange.end, continuousAfterTerminatedRight.get.addrMRange.end)
          val adjRightContinuousAfterTerminated = continuousAfterTerminatedRight.get.copy(addrMRange = adjustedRightAddrMRange, originalAddrMRange = adjustedRightAddrMRange)

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

  def roadPartMiddleTerminated(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {

    val terminatedLinks = projectLinks.filter(_.status == RoadAddressChangeType.Termination)

    val (leftTerminatedSections, rightTerminatedSections) = terminatedLinksToToContinuousTwoTrackSections(terminatedLinks)

    val processedLinks = if (leftTerminatedSections.nonEmpty && rightTerminatedSections.nonEmpty) { // Check if road part start is terminated on both tracks
      handleTwoTrackMiddleTermination(leftTerminatedSections, rightTerminatedSections, projectLinks)
    } else
      projectLinks

    processedLinks
  }

  /**
   * Adjusts two track terminated sections to match if the termination creates a minor discontinuity.
   * The minor discontinuity links will also be adjusted to match the start of the terminated section.
   * if there are links after the adjusted terminated section, those links will also be adjusted to match the end of the adjusted terminated section.
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
  def handleTwoTrackMiddleTermination(terminatedLeftSections: Seq[Seq[ProjectLink]], terminatedRightSections: Seq[Seq[ProjectLink]], projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    /**
     * Find pairs of minorDiscontinuity links on opposite tracks, reasonably close to each other.
     */
    def findMinorDiscontinuityLinkPairs(minorDiscontinuityLinks: Seq[ProjectLink]): Seq[(ProjectLink, ProjectLink)] = {
      val maxDiffForTrackEnds = 10 // This number is arbitrary and may require adjustments in the future.
      minorDiscontinuityLinks.filter(_.track == Track.LeftSide).flatMap { leftLink =>
        minorDiscontinuityLinks.filter(rightLink =>
          rightLink.track == Track.RightSide &&
            Math.abs(leftLink.addrMRange.end - rightLink.addrMRange.end) <= maxDiffForTrackEnds
        ).map(rightLink => (leftLink, rightLink))
      }
    }

    val minorDiscontinuityLinks = projectLinks.filter(_.discontinuity == Discontinuity.MinorDiscontinuity)
    val minorDiscontinuityLinkPairs = findMinorDiscontinuityLinkPairs(minorDiscontinuityLinks)

    val processedLinks = {
      var updatedProjectLinks = projectLinks
      if (minorDiscontinuityLinkPairs.nonEmpty) {
        // Process Minor discontinuity link pairs one by one
        minorDiscontinuityLinkPairs.foreach({ case (leftLink, rightLink) =>
          // Check if there is a terminated section after the minor discontinuity ON BOTH TRACKS
          val leftTerminatedAfterMinorDisc = updatedProjectLinks.find(pl => pl.status == RoadAddressChangeType.Termination && pl.track == leftLink.track && leftLink.originalAddrMRange.continuesTo(pl.originalAddrMRange))
          val rightTerminatedAfterMinorDisc = updatedProjectLinks.find(pl => pl.status == RoadAddressChangeType.Termination && pl.track == rightLink.track && rightLink.originalAddrMRange.continuesTo(pl.originalAddrMRange))

          if (leftTerminatedAfterMinorDisc.isDefined && rightTerminatedAfterMinorDisc.isDefined) {
            // Update terminated links to match, the discontinuity links to match and if there are links after terminated section, adjust those as well
            // Get the terminated sections for both tracks
            val leftTermSect  = terminatedLeftSections.find( section => section.exists(_.id == leftTerminatedAfterMinorDisc.get.id))
            val rightTermSect = terminatedRightSections.find(section => section.exists(_.id == rightTerminatedAfterMinorDisc.get.id))

            val firstTerminatedLeft   = leftTermSect.get.minBy(_.addrMRange.start)
            val firstTerminatedRight  = rightTermSect.get.minBy(_.addrMRange.start)

            val lastTerminatedLeft  = leftTermSect.get.maxBy(_.addrMRange.end)
            val lastTerminatedRight = rightTermSect.get.maxBy(_.addrMRange.end)

            val averageStartForTermSect = Math.round((firstTerminatedLeft.addrMRange.start + firstTerminatedRight.addrMRange.start).toDouble / 2)
            val averageEndForTermSect   = Math.round((lastTerminatedLeft.addrMRange.end + lastTerminatedRight.addrMRange.end).toDouble / 2)

            val adjustedMinorDiscLeft = leftLink.copy(
              addrMRange          = AddrMRange(leftLink.addrMRange.start, averageStartForTermSect),
              originalAddrMRange  = AddrMRange(leftLink.originalAddrMRange.start, averageStartForTermSect)
            )

            val adjustedMinorDiscRight = rightLink.copy(
              addrMRange          = AddrMRange(rightLink.addrMRange.start, averageStartForTermSect),
              originalAddrMRange  = AddrMRange(rightLink.originalAddrMRange.start, averageStartForTermSect)
            )

            val adjustedTerminatedLeft = {
              if (firstTerminatedLeft == lastTerminatedLeft) {
                // Same link so update the link from both ends
                val adjustedAddrMRange = AddrMRange(averageStartForTermSect, averageEndForTermSect)
                val updatedLeft = firstTerminatedLeft.copy(
                  addrMRange          = adjustedAddrMRange,
                  originalAddrMRange  = adjustedAddrMRange)
                Seq(updatedLeft)
              } else {
                // Update separately
                val startAveraged = AddrMRange(averageStartForTermSect, firstTerminatedLeft.addrMRange.end)
                val firstLeftUpdated = firstTerminatedLeft.copy(
                  addrMRange          = startAveraged,
                  originalAddrMRange  = startAveraged)

                val endAveraged = AddrMRange(lastTerminatedLeft.addrMRange.start, averageEndForTermSect)
                val lastLeftUpdated = lastTerminatedLeft.copy(
                  addrMRange          = endAveraged,
                  originalAddrMRange  = endAveraged
                )

                Seq(firstLeftUpdated,lastLeftUpdated)
              }
            }

            val adjustedTerminatedRight = {
              if (firstTerminatedRight == lastTerminatedRight) {
                // Same link so update only the on link from both ends
                val updatedRight = firstTerminatedRight.copy(
                  addrMRange          = AddrMRange(averageStartForTermSect, averageEndForTermSect),
                  originalAddrMRange  = AddrMRange(averageStartForTermSect, averageEndForTermSect)
                )

                Seq(updatedRight)
              } else {
                // Update separately
                val startAveraged = AddrMRange(averageStartForTermSect, firstTerminatedRight.addrMRange.end)
                val firstRightUpdated = firstTerminatedRight.copy(
                  addrMRange          = startAveraged,
                  originalAddrMRange  = startAveraged
                )

                val endAveraged = AddrMRange(lastTerminatedRight.addrMRange.start, averageEndForTermSect)
                val lastRightUpdated = lastTerminatedRight.copy(
                  addrMRange          = endAveraged,
                  originalAddrMRange  = endAveraged
                )

                Seq(firstRightUpdated,lastRightUpdated)
              }
            }

            val afterLeftTerminatedSection = {
              updatedProjectLinks.find(pl => pl.track != Track.RightSide && lastTerminatedLeft.originalAddrMRange.continuesTo(pl.originalAddrMRange))
            }

            val afterRightTerminatedSection = {
              updatedProjectLinks.find(pl => pl.track != Track.LeftSide && lastTerminatedRight.originalAddrMRange.continuesTo(pl.originalAddrMRange))
            }

            val adjustedAfterTermination: Seq[ProjectLink] = {
              if (afterLeftTerminatedSection.isDefined && afterRightTerminatedSection.isDefined) {
                val adjustedLeftLink = afterLeftTerminatedSection.get.copy(
                  addrMRange          = AddrMRange(averageEndForTermSect, afterLeftTerminatedSection.get.addrMRange.end),
                  originalAddrMRange  = AddrMRange(averageEndForTermSect, afterLeftTerminatedSection.get.originalAddrMRange.end)
                )
                val adjustedRightLink = afterRightTerminatedSection.get.copy(
                  addrMRange          = AddrMRange(averageEndForTermSect, afterRightTerminatedSection.get.addrMRange.end),
                  originalAddrMRange  = AddrMRange(averageEndForTermSect, afterRightTerminatedSection.get.originalAddrMRange.end)
                )
                Seq(adjustedLeftLink, adjustedRightLink)
              } else {
                Seq()
              }
            }

            updatedProjectLinks = updateProjectLinksList(
              Seq(adjustedMinorDiscLeft, adjustedMinorDiscRight) ++ adjustedTerminatedLeft ++ adjustedTerminatedRight ++ adjustedAfterTermination,
              updatedProjectLinks
            )
            updatedProjectLinks
          } else {
            // Nothing to update
            updatedProjectLinks == updatedProjectLinks
          }
        })
      } else {
        updatedProjectLinks
      }
      updatedProjectLinks
    }
    processedLinks
  }

  def roadPartEndTerminated(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    val terminatedLinks = projectLinks.filter(_.status == RoadAddressChangeType.Termination)
    val maxOriginalAddrM = projectLinks.map(_.originalAddrMRange.end).max
    val lastTerminatedLeft  = terminatedLinks.filter(pl => pl.track == Track.LeftSide).maxBy(_.originalAddrMRange.end)
    val lastTerminatedRight = terminatedLinks.filter(pl => pl.track == Track.RightSide).maxBy(_.originalAddrMRange.end)

    val processedLinks = {
      if (lastTerminatedLeft.originalAddrMRange.end == maxOriginalAddrM &&
        lastTerminatedRight.originalAddrMRange.end == maxOriginalAddrM) {
        // If road part end is two track and terminated
        val (leftTerminatedSections, rightTerminatedSections) = terminatedLinksToToContinuousTwoTrackSections(terminatedLinks)
        val lastTerminatedLeftSection   = leftTerminatedSections.find(section => section.exists(_.id == lastTerminatedLeft.id)).get
        val lastTerminatedRightSection  = rightTerminatedSections.find(section => section.exists(_.id == lastTerminatedRight.id)).get
        handleTwoTrackRoadPartEndTermination(lastTerminatedLeftSection, lastTerminatedRightSection, projectLinks)
      } else {
        projectLinks
      }
    }
    processedLinks
  }

  def handleTwoTrackRoadPartEndTermination(terminatedLeftSection: Seq[ProjectLink], terminatedRightSection: Seq[ProjectLink], projectLinks: Seq[ProjectLink]): Seq[ProjectLink]= {

    def adjustTerminatedStartToMatch(terminatedLeftLink: ProjectLink, terminatedRightLink: ProjectLink): (ProjectLink, ProjectLink, Long) = {
      // Calculate the average for terminated section start
      val averageStart = Math.round((terminatedLeftLink.addrMRange.start + terminatedRightLink.addrMRange.start).toDouble / 2)
      val adjustedTermLeft = terminatedLeftLink.copy(
        addrMRange          = AddrMRange(averageStart, terminatedLeftLink.addrMRange.end),
        originalAddrMRange  = AddrMRange(averageStart, terminatedLeftLink.originalAddrMRange.end)
      )
      val adjustedTermRight = terminatedRightLink.copy(
        addrMRange         = AddrMRange(averageStart, terminatedRightLink.addrMRange.end),
        originalAddrMRange = AddrMRange(averageStart, terminatedRightLink.originalAddrMRange.end)
      )
      (adjustedTermLeft, adjustedTermRight, averageStart)
    }

    def adjustPreviousLinkEndsToMatch(previousLeftLink: ProjectLink, previousRightLink: ProjectLink, addrMToAdjust: Long): (ProjectLink, ProjectLink) = {
      // Adjust the previous links' end addresses to match
      val adjustedPreviousLeftLink = previousLeftLink.copy(
        addrMRange          = AddrMRange(previousLeftLink.addrMRange.start, addrMToAdjust),
        originalAddrMRange  = AddrMRange(previousLeftLink.originalAddrMRange.start, addrMToAdjust)
      )
      val adjustedPreviousRightLink = previousRightLink.copy(
        addrMRange          = AddrMRange(previousRightLink.addrMRange.start, addrMToAdjust),
        originalAddrMRange  = AddrMRange(previousRightLink.originalAddrMRange.start, addrMToAdjust)
      )
      (adjustedPreviousLeftLink, adjustedPreviousRightLink)
    }

    val maxDiffForTrackStarts = 10 // This number is arbitrary and may require adjustments in the future.
    val firstLinkOnLeftTermSection  = terminatedLeftSection.minBy(_.addrMRange.start)
    val firstLinkOnRightTermSection =  terminatedRightSection.minBy(_.addrMRange.start)

    val processedLinks = {
      if ((firstLinkOnLeftTermSection.addrMRange.start == firstLinkOnRightTermSection.addrMRange.start) || // Address starts' match on first links of terminated section
        (Math.abs(firstLinkOnLeftTermSection.originalAddrMRange.start - firstLinkOnRightTermSection.originalAddrMRange.start) > maxDiffForTrackStarts)) { // Address starts' are too far away each other
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
}

