package fi.liikennevirasto.viite.process.strategy
import fi.liikennevirasto.viite.dao.ProjectLink
import fi.liikennevirasto.viite.util.{SynchronizationUtils, TwoTrackRoadUtils}
import fi.vaylavirasto.viite.model.{AddrMRange, Discontinuity, RoadAddressChangeType, RoadPart, Track}
import fi.vaylavirasto.viite.util.ViiteException

/*
This object is responsible for synchronizing road address measurements on
two-track road parts where tracks are being terminated. Because physical measurements for Left and Right tracks often vary slightly,
this synchronizer ensures that parallel tracks share identical address points at their boundaries to maintain network topology and avoid validation errors.
*/

object TerminatedTwoTrackSectionSynchronizer {

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
    SynchronizationUtils.toContinuousSections(projectLinksWithSameStatus, link => link.status == sectionStatus)
  }

  /**
   * Adjusts terminated project links to have matching end addresses.
   * @param terminatedLeft Left track terminated projectLink to adjust.
   * @param terminatedRight Right track terminated projectLink to adjust.
   * @return Adjusted project links and the new averaged endAddressM of the adjusted project links.
   */
  private def adjustTerminatedLinksToMatchAtTheEnd(terminatedLeft: ProjectLink, terminatedRight: ProjectLink): (ProjectLink, ProjectLink, Long) = {
    val averageEnd    = TwoTrackRoadUtils.calculateAverageAddrM(terminatedLeft.addrMRange.end, terminatedRight.addrMRange.end)

    val adjustedLeft  = terminatedLeft.copy(  addrMRange = AddrMRange(terminatedLeft.addrMRange.start, averageEnd),
                                      originalAddrMRange = AddrMRange(terminatedLeft.originalAddrMRange.start, averageEnd))

    val adjustedRight = terminatedRight.copy( addrMRange = AddrMRange(terminatedRight.addrMRange.start, averageEnd) ,
                                      originalAddrMRange = AddrMRange(terminatedRight.originalAddrMRange.start, averageEnd))

    (adjustedLeft, adjustedRight, averageEnd)
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
    val continuousAfterTerminatedLeft   = SynchronizationUtils.findNextLink(projectLinks, lastTerminatedOnLeftSideSection, Track.RightSide)
    val continuousAfterTerminatedRight  = SynchronizationUtils.findNextLink(projectLinks, lastTerminatedOnRightSideSection, Track.LeftSide)

    // Adjust terminated
    val processedLinks: Seq[ProjectLink] = {
      if (SynchronizationUtils.areTracksCloseEnoughOnEndAddrM(lastTerminatedOnLeftSideSection, lastTerminatedOnRightSideSection)) {
        val averageEndAddrM = SynchronizationUtils.clampSharedEndAddrM(
          TwoTrackRoadUtils.calculateAverageAddrM(lastTerminatedOnLeftSideSection.addrMRange.end, lastTerminatedOnRightSideSection.addrMRange.end),
          Seq(lastTerminatedOnLeftSideSection, lastTerminatedOnRightSideSection),
          Seq(continuousAfterTerminatedLeft, continuousAfterTerminatedRight).flatten
        )

        val adjustedTerminatedLeft = SynchronizationUtils.replaceEndsWith(lastTerminatedOnLeftSideSection, averageEndAddrM)
        val adjustedTerminatedRight = SynchronizationUtils.replaceEndsWith(lastTerminatedOnRightSideSection, averageEndAddrM)

        if (continuousAfterTerminatedLeft.isDefined && continuousAfterTerminatedRight.isDefined) {
          // Adjust both links after the terminated section
          val adjustedLeftAddrMRange = AddrMRange(averageEndAddrM, continuousAfterTerminatedLeft.get.originalAddrMRange.end)
          val adjLeftContinuousAfterTerminated = continuousAfterTerminatedLeft.get.copy(
            addrMRange = adjustedLeftAddrMRange,
            originalAddrMRange = adjustedLeftAddrMRange
          )

          val adjustedRightAddrMRange = AddrMRange(averageEndAddrM, continuousAfterTerminatedRight.get.originalAddrMRange.end)
          val adjRightContinuousAfterTerminated = continuousAfterTerminatedRight.get.copy(
            addrMRange = adjustedRightAddrMRange,
            originalAddrMRange = adjustedRightAddrMRange
          )
          // Update the project links list with the adjusted terminated links and the adjusted continuous-after-termination links
          SynchronizationUtils.updateProjectLinksList(Seq(adjustedTerminatedLeft, adjustedTerminatedRight, adjLeftContinuousAfterTerminated, adjRightContinuousAfterTerminated), projectLinks)
        } else {
          // Just update the terminated links
          SynchronizationUtils.updateProjectLinksList(Seq(adjustedTerminatedLeft, adjustedTerminatedRight), projectLinks)
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

    // Find pairs of minorDiscontinuity links on opposite tracks, reasonably close to each other.
    def findMinorDiscontinuityLinkPairs(minorDiscontinuityLinks: Seq[ProjectLink]): Seq[Seq[ProjectLink]] = {
      minorDiscontinuityLinks.filter(_.track == Track.LeftSide).flatMap { leftLink =>
        minorDiscontinuityLinks.filter(rightLink =>
          rightLink.track == Track.RightSide &&
            SynchronizationUtils.areTracksCloseEnoughOnEndAddrM(leftLink,rightLink)
        ).map(rightLink => Seq(leftLink, rightLink))
      }
    }

    val minorDiscontinuityLinks = projectLinks.filter(_.discontinuity == Discontinuity.MinorDiscontinuity)
    val combinedMinorDiscontinuityLinks = minorDiscontinuityLinks.filter(_.track == Track.Combined)
    val minorDiscontinuityLinkPairs = findMinorDiscontinuityLinkPairs(minorDiscontinuityLinks)
    val minorDiscontinuitiesToProcess = minorDiscontinuityLinkPairs ++ Seq(combinedMinorDiscontinuityLinks)

    val processedLinks = {
      var updatedProjectLinks = projectLinks
      if (minorDiscontinuitiesToProcess.nonEmpty) {
        // Process Minor discontinuity link sequences one by one
        minorDiscontinuitiesToProcess.foreach({ minorDiscontinuityLinks =>
          val leftUpdatedTerminatedLinks =  updatedProjectLinks.filter(pl => pl.status == RoadAddressChangeType.Termination && pl.track == Track.LeftSide)
          val rightUpdatedTerminatedLinks = updatedProjectLinks.filter(pl => pl.status == RoadAddressChangeType.Termination && pl.track == Track.RightSide)

          // Find links that come after the minor discontinuity links
          val (leftTerminatedAfterMinorDisc, rightTerminatedAfterMinorDisc) = minorDiscontinuityLinks match {
            case Seq(combined) =>
              val leftTerminatedAfterMinorDisc  = SynchronizationUtils.findNextLink(leftUpdatedTerminatedLinks, combined, Track.RightSide)
              val rightTerminatedAfterMinorDisc = SynchronizationUtils.findNextLink(rightUpdatedTerminatedLinks, combined, Track.LeftSide)
              (leftTerminatedAfterMinorDisc, rightTerminatedAfterMinorDisc)
            case Seq(left, right) =>
              val leftTerminatedAfterMinorDisc  = SynchronizationUtils.findNextLink(leftUpdatedTerminatedLinks, left, Track.RightSide)
              val rightTerminatedAfterMinorDisc = SynchronizationUtils.findNextLink(rightUpdatedTerminatedLinks, right, Track.LeftSide)
              (leftTerminatedAfterMinorDisc, rightTerminatedAfterMinorDisc)
            case _ => (None,None)
          }

          // If there were links after the minor discontinuity
          if (leftTerminatedAfterMinorDisc.isDefined && rightTerminatedAfterMinorDisc.isDefined) {

            // Get the terminated sections for both tracks
            val leftTermSect  = terminatedLeftSections.find( section => section.exists(_.id == leftTerminatedAfterMinorDisc.get.id))
            val rightTermSect = terminatedRightSections.find(section => section.exists(_.id == rightTerminatedAfterMinorDisc.get.id))

            val firstTerminatedLeft   = leftTermSect.get.minBy(_.addrMRange.start)
            val firstTerminatedRight  = rightTermSect.get.minBy(_.addrMRange.start)

            val lastTerminatedLeft  = leftTermSect.get.maxBy(_.addrMRange.end)
            val lastTerminatedRight = rightTermSect.get.maxBy(_.addrMRange.end)

            val afterLeftTerminatedSection = {
              SynchronizationUtils.findNextLink(updatedProjectLinks, lastTerminatedLeft, Track.RightSide)
            }

            val afterRightTerminatedSection = {
              SynchronizationUtils.findNextLink(updatedProjectLinks, lastTerminatedRight, Track.LeftSide)
            }

            val averageStartForTermSect = SynchronizationUtils.clampSharedStartAddrM(
              TwoTrackRoadUtils.calculateAverageAddrM(firstTerminatedLeft.addrMRange.start, firstTerminatedRight.addrMRange.start),
              Seq(firstTerminatedLeft, firstTerminatedRight),
              minorDiscontinuityLinks
            )

            val averageEndForTermSect = SynchronizationUtils.clampSharedEndAddrM(
              TwoTrackRoadUtils.calculateAverageAddrM(lastTerminatedLeft.addrMRange.end, lastTerminatedRight.addrMRange.end),
              Seq(lastTerminatedLeft, lastTerminatedRight),
              Seq(afterLeftTerminatedSection, afterRightTerminatedSection).flatten
            )

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

            // Update terminated links to match
            val adjustedTerminatedLeft  = adjustTerminatedLinks(firstTerminatedLeft, lastTerminatedLeft)
            val adjustedTerminatedRight = adjustTerminatedLinks(firstTerminatedRight, lastTerminatedRight)

            // Adjust links after terminated section
            val adjustedAfterTermination: Seq[ProjectLink] = {
              if (afterLeftTerminatedSection.isDefined && afterRightTerminatedSection.isDefined) {
                val adjustedLeftLink  = SynchronizationUtils.replaceStartsWith(afterLeftTerminatedSection.get, averageEndForTermSect)
                val adjustedRightLink = SynchronizationUtils.replaceStartsWith(afterRightTerminatedSection.get, averageEndForTermSect)
                Seq(adjustedLeftLink, adjustedRightLink)
              } else {
                Seq()
              }
            }

            // Adjust minor discontinuity links
            val updatedMinorDiscLinks = {
              minorDiscontinuityLinks.map(minorDiscLink => SynchronizationUtils.replaceEndsWith(minorDiscLink, averageStartForTermSect))
            }

            updatedProjectLinks = SynchronizationUtils.updateProjectLinksList(
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
      val averageStart = TwoTrackRoadUtils.calculateAverageAddrM(terminatedLeftLink.addrMRange.start, terminatedRightLink.addrMRange.start)
      val adjustedTermLeft = SynchronizationUtils.replaceStartsWith(terminatedLeftLink, averageStart)
      val adjustedTermRight = SynchronizationUtils.replaceStartsWith(terminatedRightLink, averageStart)
      (adjustedTermLeft, adjustedTermRight, averageStart)
    }

    def adjustLinkEndsToMatch(leftLink: ProjectLink, rightLink: ProjectLink, addrMToAdjust: Long): (ProjectLink, ProjectLink) = {
      // Adjust the link end addresses to match
      (SynchronizationUtils.replaceEndsWith(leftLink, addrMToAdjust), SynchronizationUtils.replaceEndsWith(rightLink, addrMToAdjust))
    }

    val firstLinkOnLeftTermSection  = terminatedLeftSection.minBy(_.addrMRange.start)
    val firstLinkOnRightTermSection =  terminatedRightSection.minBy(_.addrMRange.start)

    val processedLinks = {
      if ((firstLinkOnLeftTermSection.addrMRange.start == firstLinkOnRightTermSection.addrMRange.start) ||
        !SynchronizationUtils.areTracksCloseEnoughOnOriginalStartAddrM(firstLinkOnLeftTermSection, firstLinkOnRightTermSection)) {
        // Return the project links unchanged
        projectLinks
      } else {
        // Find previous links if there are any
        val previousLeftLink  = SynchronizationUtils.findPreviousLink(projectLinks, firstLinkOnLeftTermSection, Track.RightSide)
        val previousRightLink = SynchronizationUtils.findPreviousLink(projectLinks, firstLinkOnRightTermSection, Track.LeftSide)

        val averageStartForTerminated = SynchronizationUtils.clampSharedStartAddrM(
          TwoTrackRoadUtils.calculateAverageAddrM(firstLinkOnLeftTermSection.addrMRange.start, firstLinkOnRightTermSection.addrMRange.start),
          Seq(firstLinkOnLeftTermSection, firstLinkOnRightTermSection),
          Seq(previousLeftLink, previousRightLink).flatten
        )

        val adjustedTermLeft = SynchronizationUtils.replaceStartsWith(firstLinkOnLeftTermSection, averageStartForTerminated)
        val adjustedTermRight = SynchronizationUtils.replaceStartsWith(firstLinkOnRightTermSection, averageStartForTerminated)

        if (previousLeftLink.isDefined && previousRightLink.isDefined) {
          // Update the previous link starts to match
          val (adjustedPreviousLeftLink, adjustedPreviousRightLink)  = adjustLinkEndsToMatch(previousLeftLink.get, previousRightLink.get, averageStartForTerminated)
          SynchronizationUtils.updateProjectLinksList(Seq(adjustedTermLeft, adjustedTermRight, adjustedPreviousLeftLink, adjustedPreviousRightLink), projectLinks)
        } else {
          // No need to update the previous links
          SynchronizationUtils.updateProjectLinksList(Seq(adjustedTermLeft, adjustedTermRight), projectLinks)
        }
      }
    }
    processedLinks
  }
}

