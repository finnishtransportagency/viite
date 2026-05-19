package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.dao.ProjectLink
import fi.liikennevirasto.viite.util.{SynchronizationUtils, TwoTrackRoadUtils}
import fi.vaylavirasto.viite.model.{AddrMRange, Discontinuity, RoadAddressChangeType, Track}
import fi.vaylavirasto.viite.util.ViiteException

// TODO: No longer used, safe to delete?

/**
 * This object is responsible for synchronizing road address measurements on
 * two-track road parts where administrative classes are being changed. Because physical measurements for Left and Right tracks often vary slightly,
 * this ensures that parallel tracks share identical address points at their boundaries to avoid validation errors.
 *
 * Example:
 *   Legend: Admin Class Changed: ~~> | Unchanged: -->
 *
 *   Before: (15, 25) -> Avg 20
 *   0     15        40
 *   ~~~~~~=>---------> (Admin class changed from position 0-15)
 *   ~~~~~~~~=>------->  (Admin class changed from position 0-25)
 *   0         25     40
 *
 *   After: Both tracks aligned at 20
 *   0     20        40
 *   ~~~~~~=>--------->
 *   ~~~~~~~~=>---------->
 *   0        20         40
 */
object AdministrativeClassTwoTrackSynchronizer {

  private def tooLargeDiffError(leftLinkId: String, rightLinkId: String): ViiteException =
    ViiteException(s"Linkkien $leftLinkId ja $rightLinkId etäisyysarvot eroavat toisistaan yli sallitun rajan (${SynchronizationUtils.maxDiffForTracks}). Yritä tehdä hallinnollisen luokan muutos kohdista, jotka ovat pituudeltaan lähempänä toisiaan, tai ota yhteyttä Viite tukeen.")

  /**
   * Adjusts two track administrative class change sections to match + the surrounding links if needed.
   * @param roadPartProjectLinksWithoutNewLinks Sequence of project links to adjust on a single road part (NOTE! RoadAddressChangeType.New links NOT allowed)
   */
  def adjustAdministrativeClassChanges(roadPartProjectLinksWithoutNewLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    def existsAdminClassChangesOnParallelTracks(projectLinks: Seq[ProjectLink]): Boolean = {
      projectLinks.exists(pl => pl.track == Track.RightSide && pl.administrativeClass != pl.originalAdministrativeClass) &&
      projectLinks.exists(pl => pl.track == Track.LeftSide && pl.administrativeClass != pl.originalAdministrativeClass)
    }

    // Some validations for the project links
    if (roadPartProjectLinksWithoutNewLinks.map(_.roadPart).distinct.size > 1) {
      throw ViiteException(s"Administrative class change two track project links can only be adjusted one road part at a time!")
    }

    if (roadPartProjectLinksWithoutNewLinks.exists(_.status == RoadAddressChangeType.New)) {
      throw ViiteException(s"New project links are not allowed for the process of adjusting administrative class change two track links!")
    }

    // Check that there are administrative class changes on both tracks
    val processedLinks = if (existsAdminClassChangesOnParallelTracks(roadPartProjectLinksWithoutNewLinks)) {
      val orderedProjectLinks = roadPartProjectLinksWithoutNewLinks.sortBy(_.addrMRange.start)

      // List of administrative class change cases
      val adminClassChangeCases: List[Seq[ProjectLink] => Seq[ProjectLink]] = List(
        roadPartStartAdminClassChanged,
        roadPartEndAdminClassChanged,
        roadPartMiddleAdminClassChanged
      )

      // Sequentially apply each case function
      adminClassChangeCases.foldLeft(orderedProjectLinks)((linksAccumulator, caseFunction) => caseFunction(linksAccumulator))

    } else {
      // else we return the links unchanged.
      roadPartProjectLinksWithoutNewLinks
    }

    processedLinks
  }

  /**
   * Takes in project links with administrative class changes and divides them into continuous sections.
   *
   * Continuous section has same roadPart, administrative class change status, track and each project link starts from the
   * same addrM where the previous project link ended.
   *
   * @param projectLinksWithAdminClassChange Project links that all have administrative class changes.
   */
  private def toContinuousSectionsByAdminClassChange(projectLinksWithAdminClassChange: Seq[ProjectLink]): Seq[Seq[ProjectLink]] = {
    SynchronizationUtils.toContinuousSections(projectLinksWithAdminClassChange, link => link.administrativeClass != link.originalAdministrativeClass)
  }

  // Divide administrative class changed project links into continuous two track sections.
  private def adminClassChangedLinksToContinuousTwoTrackSections(adminClassChangedLinks: Seq[ProjectLink]) : (Seq[Seq[ProjectLink]], Seq[Seq[ProjectLink]]) = {
    val adminClassChangedLeft = adminClassChangedLinks.filter(_.track == Track.LeftSide)
    val adminClassChangedRight = adminClassChangedLinks.filter(_.track == Track.RightSide)

    // Group administrative class changed links into continuous sections
    val leftAdminClassChangedSections = toContinuousSectionsByAdminClassChange(adminClassChangedLeft)
    val rightAdminClassChangedSections = toContinuousSectionsByAdminClassChange(adminClassChangedRight)
    (leftAdminClassChangedSections, rightAdminClassChangedSections)
  }

  /**
   * Checks if there is a two track administrative class change on road part start.
   * If there is, then adjusts the administrative class changed track addresses to match.
   * Else returns the project links unchanged.
   * @param projectLinks Project links to adjust
   */
  private def roadPartStartAdminClassChanged(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    val adminClassChangedLinks = projectLinks.filter(pl => pl.administrativeClass != pl.originalAdministrativeClass)

    val (leftAdminClassChangedSections, rightAdminClassChangedSections) = adminClassChangedLinksToContinuousTwoTrackSections(adminClassChangedLinks)

    val roadPartStartAdminClassChangedLefts = leftAdminClassChangedSections.find(section => section.exists(_.addrMRange.isRoadPartStart))
    val roadPartStartAdminClassChangedRights = rightAdminClassChangedSections.find(section => section.exists(_.addrMRange.isRoadPartStart))

    (roadPartStartAdminClassChangedLefts, roadPartStartAdminClassChangedRights) match {
      case (Some(leftSection), Some(rightSection)) =>
        handleTwoTrackRoadPartStartAdminClassChange(leftSection, rightSection, projectLinks)
      case _ =>
        projectLinks
    }
  }

  /**
   * Adjusts administrative class changed sections on two track road part start to have matching addresses (if reasonably close to each other) and
   * the first project links after the administrative class changed section.
   */
  private def handleTwoTrackRoadPartStartAdminClassChange(adminClassChangedLeftSection: Seq[ProjectLink], adminClassChangedRightSection: Seq[ProjectLink], projectLinks: Seq[ProjectLink]): Seq[ProjectLink]= {

    val lastAdminClassChangedOnLeft  = adminClassChangedLeftSection.maxBy(_.addrMRange.end)
    val lastAdminClassChangedOnRight = adminClassChangedRightSection.maxBy(_.addrMRange.end)

    // Compare original addresses here because we are modifying the starting situation
    val continuousAfterAdminClassChangedLeft   = SynchronizationUtils.findNextLink(projectLinks, lastAdminClassChangedOnLeft, Track.RightSide)
    val continuousAfterAdminClassChangedRight  = SynchronizationUtils.findNextLink(projectLinks, lastAdminClassChangedOnRight, Track.LeftSide)

    // Adjust only the intersection where the start-of-road admin class change ends.
    if (SynchronizationUtils.areTracksCloseEnoughOnEndAddrM(lastAdminClassChangedOnLeft, lastAdminClassChangedOnRight)) {
      val averageEnd = SynchronizationUtils.clampSharedEndAddrM(
        TwoTrackRoadUtils.calculateAverageAddrM(lastAdminClassChangedOnLeft.addrMRange.end, lastAdminClassChangedOnRight.addrMRange.end),
        Seq(lastAdminClassChangedOnLeft, lastAdminClassChangedOnRight),
        Seq(continuousAfterAdminClassChangedLeft, continuousAfterAdminClassChangedRight).flatten
      )

      val adjustedAdminClassChangedLeft = SynchronizationUtils.replaceEndsWith(lastAdminClassChangedOnLeft, averageEnd)
      val adjustedAdminClassChangedRight = SynchronizationUtils.replaceEndsWith(lastAdminClassChangedOnRight, averageEnd)

      (continuousAfterAdminClassChangedLeft, continuousAfterAdminClassChangedRight) match {
        case (Some(leftContinuous), Some(rightContinuous)) =>
          // Adjust both links after the admin class changed section
          val adjustedLeftAddrMRange = AddrMRange(averageEnd, leftContinuous.originalAddrMRange.end)
          val adjLeftContinuousAfterAdminClassChanged = leftContinuous.copy(
            addrMRange = adjustedLeftAddrMRange,
            originalAddrMRange = adjustedLeftAddrMRange
          )

          val adjustedRightAddrMRange = AddrMRange(averageEnd, rightContinuous.originalAddrMRange.end)
          val adjRightContinuousAfterAdminClassChanged = rightContinuous.copy(
            addrMRange = adjustedRightAddrMRange,
            originalAddrMRange = adjustedRightAddrMRange
          )

          // Update the project links list with the adjusted admin class changed links and the adjusted continuous-after-admin-class-change links
          SynchronizationUtils.updateProjectLinksList(Seq(adjustedAdminClassChangedLeft, adjustedAdminClassChangedRight, adjLeftContinuousAfterAdminClassChanged, adjRightContinuousAfterAdminClassChanged), projectLinks)

        case _ =>
          // Just update the admin class changed links
          SynchronizationUtils.updateProjectLinksList(Seq(adjustedAdminClassChangedLeft, adjustedAdminClassChangedRight), projectLinks)
      }
    } else {
        throw tooLargeDiffError(lastAdminClassChangedOnLeft.linkId, lastAdminClassChangedOnRight.linkId)
    }
  }

  // Helper methods for middle section administrative class change processing
  private def isMiddleSection(section: Seq[ProjectLink], maxAddrM: Long): Boolean =
    !section.exists(_.addrMRange.isRoadPartStart) &&
    !section.exists(_.originalAddrMRange.end == maxAddrM)

  private def adjustSection(firstLink: ProjectLink, lastLink: ProjectLink, avgStart: Long, avgEnd: Long): Seq[ProjectLink] = {
    if (firstLink.id == lastLink.id) {
      val r = AddrMRange(avgStart, avgEnd)
      Seq(firstLink.copy(addrMRange = r, originalAddrMRange = r))
    } else {
      Seq(
        SynchronizationUtils.replaceStartsWith(firstLink, avgStart),
        SynchronizationUtils.replaceEndsWith(lastLink, avgEnd)
      )
    }
  }

  private def findMinorDiscontinuityLinkPairs(minorDiscontinuityLinks: Seq[ProjectLink]): Seq[Seq[ProjectLink]] = {
    minorDiscontinuityLinks.filter(_.track == Track.LeftSide).flatMap { leftLink =>
      minorDiscontinuityLinks.filter { rightLink =>
        rightLink.track == Track.RightSide && SynchronizationUtils.areTracksCloseEnoughOnEndAddrM(leftLink, rightLink)
      }.map(rightLink => Seq(leftLink, rightLink))
    }
  }

  private def findNextLinkBasedOnOriginalAddresses(originalAddrMRange: AddrMRange, links: Seq[ProjectLink]): Option[ProjectLink] = {
    links.find(pl => originalAddrMRange.continuesTo(pl.originalAddrMRange))
  }

  private def findCurrentSection(section: Seq[ProjectLink], updatedLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    section.flatMap(link => updatedLinks.find(_.id == link.id))
  }

  /**
   * Adjusts the last administrative class changed project links to have matching end addresses.
   */
  private def adjustAdminClassChangedLinksToMatchAtTheEnd(adminClassChangedLeft: ProjectLink, adminClassChangedRight: ProjectLink): (ProjectLink, ProjectLink, Long) = {
    val averageEnd = TwoTrackRoadUtils.calculateAverageAddrM(adminClassChangedLeft.addrMRange.end, adminClassChangedRight.addrMRange.end)

    val adjustedLeft = SynchronizationUtils.replaceEndsWith(adminClassChangedLeft, averageEnd)
    val adjustedRight = SynchronizationUtils.replaceEndsWith(adminClassChangedRight, averageEnd)

    (adjustedLeft, adjustedRight, averageEnd)
  }

  private def roadPartMiddleAdminClassChanged(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {

    val adminClassChangedLinks = projectLinks.filter(pl => pl.administrativeClass != pl.originalAdministrativeClass)

    val (leftAdminClassChangedSections, rightAdminClassChangedSections) = adminClassChangedLinksToContinuousTwoTrackSections(adminClassChangedLinks)

    if (leftAdminClassChangedSections.nonEmpty && rightAdminClassChangedSections.nonEmpty) { // Check that there are admin class changed sections on both tracks
      handleTwoTrackMiddleAdminClassChange(leftAdminClassChangedSections, rightAdminClassChangedSections, projectLinks)
    } else {
      projectLinks
    }
  }

  /**
   * Adjusts two track administrative class change sections in the middle of a road part to have matching addresses.
   * Matches left and right changed sections by address proximity, then averages their start and end boundaries.
   * The preceding links (ending at the section start) and following links (starting at the section end) are
   * also adjusted to stay continuous. Works regardless of whether a MinorDiscontinuity link is present.
   */
  private def handleTwoTrackMiddleAdminClassChange(adminClassChangedLeftSections: Seq[Seq[ProjectLink]], adminClassChangedRightSections: Seq[Seq[ProjectLink]], projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {

    val maxAddrM = projectLinks.map(_.originalAddrMRange.end).max

    // Road-part-start and road-part-end sections are handled by their own dedicated cases; skip them here.
    val leftMiddleSections  = adminClassChangedLeftSections.filter(isMiddleSection(_, maxAddrM))
    val rightMiddleSections = adminClassChangedRightSections.filter(isMiddleSection(_, maxAddrM))

    var updatedProjectLinks = projectLinks
    var processedLeftSectionIds = Set.empty[Long]
    var processedRightSectionIds = Set.empty[Long]

    val minorDiscontinuityLinks = projectLinks.filter(_.discontinuity == Discontinuity.MinorDiscontinuity)
    val combinedMinorDiscontinuityLinks = minorDiscontinuityLinks.filter(_.track == Track.Combined)
    val minorDiscontinuityLinkPairs = findMinorDiscontinuityLinkPairs(minorDiscontinuityLinks)
    val minorDiscontinuitiesToProcess = minorDiscontinuityLinkPairs ++
      (if (combinedMinorDiscontinuityLinks.nonEmpty) Seq(combinedMinorDiscontinuityLinks) else Seq.empty)
    minorDiscontinuitiesToProcess.foreach { minorDiscontinuitySection =>
      val updatedAdminClassChangedLinks = updatedProjectLinks.filter(pl => pl.administrativeClass != pl.originalAdministrativeClass)
      val leftUpdatedAdminClassChangedLinks = updatedAdminClassChangedLinks.filter(_.track == Track.LeftSide)
      val rightUpdatedAdminClassChangedLinks = updatedAdminClassChangedLinks.filter(_.track == Track.RightSide)

      val (leftAdminClassChangedAfterMinorDisc, rightAdminClassChangedAfterMinorDisc) = minorDiscontinuitySection match {
        case Seq(combined) =>
          val leftAfter = findNextLinkBasedOnOriginalAddresses(combined.originalAddrMRange, leftUpdatedAdminClassChangedLinks)
          val rightAfter = findNextLinkBasedOnOriginalAddresses(combined.originalAddrMRange, rightUpdatedAdminClassChangedLinks)
          (leftAfter, rightAfter)
        case Seq(left, right) =>
          val leftAfter = findNextLinkBasedOnOriginalAddresses(left.originalAddrMRange, leftUpdatedAdminClassChangedLinks)
          val rightAfter = findNextLinkBasedOnOriginalAddresses(right.originalAddrMRange, rightUpdatedAdminClassChangedLinks)
          (leftAfter, rightAfter)
        case _ =>
          (None, None)
      }

      (leftAdminClassChangedAfterMinorDisc, rightAdminClassChangedAfterMinorDisc) match {
        case (Some(leftLink), Some(rightLink)) =>
          val leftSectionOpt = leftMiddleSections.find(_.exists(_.id == leftLink.id))
          val rightSectionOpt = rightMiddleSections.find(_.exists(_.id == rightLink.id))

          if (leftSectionOpt.isDefined && rightSectionOpt.isDefined) {
            val currentLeftSection = findCurrentSection(leftSectionOpt.get, updatedProjectLinks)
            val currentRightSection = findCurrentSection(rightSectionOpt.get, updatedProjectLinks)

            if (currentLeftSection.nonEmpty && currentRightSection.nonEmpty) {
              val firstLeft = currentLeftSection.minBy(_.addrMRange.start)
              val lastLeft = currentLeftSection.maxBy(_.addrMRange.end)
              val firstRight = currentRightSection.minBy(_.addrMRange.start)
              val lastRight = currentRightSection.maxBy(_.addrMRange.end)

              if (!SynchronizationUtils.areTracksCloseEnoughOnOriginalStartAddrM(firstLeft, firstRight)) {
                throw tooLargeDiffError(firstLeft.linkId, firstRight.linkId)
              }

              if (!SynchronizationUtils.areTracksCloseEnoughOnEndAddrM(lastLeft, lastRight)) {
                throw tooLargeDiffError(lastLeft.linkId, lastRight.linkId)
              }

              val prevLeftLink = SynchronizationUtils.findPreviousLink(updatedProjectLinks, firstLeft, Track.RightSide)
              val prevRightLink = SynchronizationUtils.findPreviousLink(updatedProjectLinks, firstRight, Track.LeftSide)

              val nextLeftLink = SynchronizationUtils.findNextLink(updatedProjectLinks, lastLeft, Track.RightSide)
              val nextRightLink = SynchronizationUtils.findNextLink(updatedProjectLinks, lastRight, Track.LeftSide)

              val averageStart = SynchronizationUtils.clampSharedStartAddrM(
                TwoTrackRoadUtils.calculateAverageAddrM(firstLeft.addrMRange.start, firstRight.addrMRange.start),
                Seq(firstLeft, firstRight),
                Seq(prevLeftLink, prevRightLink).flatten
              )

              val averageEnd = SynchronizationUtils.clampSharedEndAddrM(
                TwoTrackRoadUtils.calculateAverageAddrM(lastLeft.addrMRange.end, lastRight.addrMRange.end),
                Seq(lastLeft, lastRight),
                Seq(nextLeftLink, nextRightLink).flatten
              )

              val adjustedLeft = adjustSection(firstLeft, lastLeft, averageStart, averageEnd)
              val adjustedRight = adjustSection(firstRight, lastRight, averageStart, averageEnd)

              val adjustedPreceding = {
                (prevLeftLink, prevRightLink) match {
                  case (Some(prevLeft), Some(prevRight)) =>
                    Seq(
                      SynchronizationUtils.replaceEndsWith(prevLeft, averageStart),
                      SynchronizationUtils.replaceEndsWith(prevRight, averageStart)
                    )
                  case (Some(prevLeft), None) =>
                    Seq(SynchronizationUtils.replaceEndsWith(prevLeft, averageStart))
                  case (None, Some(prevRight)) =>
                    Seq(SynchronizationUtils.replaceEndsWith(prevRight, averageStart))
                  case _ =>
                    Seq.empty[ProjectLink]
                }
              }

              val adjustedFollowing = {
                (nextLeftLink, nextRightLink) match {
                  case (Some(nextLeft), Some(nextRight)) =>
                    Seq(
                      SynchronizationUtils.replaceStartsWith(nextLeft, averageEnd),
                      SynchronizationUtils.replaceStartsWith(nextRight, averageEnd)
                    )
                  case (Some(nextLeft), None) =>
                    Seq(SynchronizationUtils.replaceStartsWith(nextLeft, averageEnd))
                  case (None, Some(nextRight)) =>
                    Seq(SynchronizationUtils.replaceStartsWith(nextRight, averageEnd))
                  case _ =>
                    Seq.empty[ProjectLink]
                }
              }

              updatedProjectLinks = SynchronizationUtils.updateProjectLinksList(
                adjustedLeft ++ adjustedRight ++ adjustedPreceding ++ adjustedFollowing,
                updatedProjectLinks
              )

              processedLeftSectionIds ++= leftSectionOpt.get.map(_.id)
              processedRightSectionIds ++= rightSectionOpt.get.map(_.id)
            }
          }
        case _ =>
      }
    }

    leftMiddleSections.filterNot(_.exists(link => processedLeftSectionIds.contains(link.id))).foreach { leftSection =>
      val firstLeft = leftSection.minBy(_.addrMRange.start)
      val lastLeft  = leftSection.maxBy(_.addrMRange.end)
      val unprocessedRightSections = rightMiddleSections.filterNot(_.exists(link => processedRightSectionIds.contains(link.id)))

      // Find the right section whose start and end are within maxDiffForTracks of the left section.
      val matchedRightSection = unprocessedRightSections.find { rightSection =>
        val firstRight = rightSection.minBy(_.addrMRange.start)
        val lastRight  = rightSection.maxBy(_.addrMRange.end)
        SynchronizationUtils.areTracksCloseEnoughOnOriginalStartAddrM(firstLeft, firstRight) &&
        SynchronizationUtils.areTracksCloseEnoughOnEndAddrM(lastLeft, lastRight)
      }

      matchedRightSection.foreach { rightSection =>
        val firstRight = rightSection.minBy(_.addrMRange.start)
        val lastRight  = rightSection.maxBy(_.addrMRange.end)

        // Find the link ending where the admin-class section begins on each track.
        val prevLeftLink  = SynchronizationUtils.findPreviousLink(updatedProjectLinks, firstLeft, Track.RightSide)
        val prevRightLink = SynchronizationUtils.findPreviousLink(updatedProjectLinks, firstRight, Track.LeftSide)

        // Find the link starting where the admin-class section ends on each track.
        val nextLeftLink  = SynchronizationUtils.findNextLink(updatedProjectLinks, lastLeft, Track.RightSide)
        val nextRightLink = SynchronizationUtils.findNextLink(updatedProjectLinks, lastRight, Track.LeftSide)

        val averageStart = SynchronizationUtils.clampSharedStartAddrM(
          TwoTrackRoadUtils.calculateAverageAddrM(firstLeft.addrMRange.start, firstRight.addrMRange.start),
          Seq(firstLeft, firstRight),
          Seq(prevLeftLink, prevRightLink).flatten
        )

        val averageEnd = SynchronizationUtils.clampSharedEndAddrM(
          TwoTrackRoadUtils.calculateAverageAddrM(lastLeft.addrMRange.end, lastRight.addrMRange.end),
          Seq(lastLeft, lastRight),
          Seq(nextLeftLink, nextRightLink).flatten
        )

        val adjustedLeft  = adjustSection(firstLeft,  lastLeft,  averageStart, averageEnd)
        val adjustedRight = adjustSection(firstRight, lastRight, averageStart, averageEnd)

        val adjustedPreceding: Seq[ProjectLink] = (prevLeftLink, prevRightLink) match {
          case (Some(prevLeft), Some(prevRight)) =>
            Seq(
              SynchronizationUtils.replaceEndsWith(prevLeft,  averageStart),
              SynchronizationUtils.replaceEndsWith(prevRight, averageStart)
            )
          case (Some(prevLeft),  None) => Seq(SynchronizationUtils.replaceEndsWith(prevLeft,  averageStart))
          case (None, Some(prevRight)) => Seq(SynchronizationUtils.replaceEndsWith(prevRight, averageStart))
          case _                       => Seq.empty
        }

        val adjustedFollowing: Seq[ProjectLink] = (nextLeftLink, nextRightLink) match {
          case (Some(nextLeft), Some(nextRight)) =>
            Seq(
              SynchronizationUtils.replaceStartsWith(nextLeft,  averageEnd),
              SynchronizationUtils.replaceStartsWith(nextRight, averageEnd)
            )
          case (Some(nextLeft),  None) => Seq(SynchronizationUtils.replaceStartsWith(nextLeft,  averageEnd))
          case (None, Some(nextRight)) => Seq(SynchronizationUtils.replaceStartsWith(nextRight, averageEnd))
          case _                       => Seq.empty
        }

        updatedProjectLinks = SynchronizationUtils.updateProjectLinksList(
          adjustedLeft ++ adjustedRight ++ adjustedPreceding ++ adjustedFollowing,
          updatedProjectLinks
        )

        processedLeftSectionIds ++= leftSection.map(_.id)
        processedRightSectionIds ++= rightSection.map(_.id)
      }

      if (matchedRightSection.isEmpty && unprocessedRightSections.nonEmpty) {
        val rightSection = unprocessedRightSections.head

        val firstRight = rightSection.minBy(_.addrMRange.start)
        val lastRight  = rightSection.maxBy(_.addrMRange.end)

        if (!SynchronizationUtils.areTracksCloseEnoughOnOriginalStartAddrM(firstLeft, firstRight)) {
          throw tooLargeDiffError(firstLeft.linkId, firstRight.linkId)
        }

        if (!SynchronizationUtils.areTracksCloseEnoughOnEndAddrM(lastLeft, lastRight)) {
          throw tooLargeDiffError(lastLeft.linkId, lastRight.linkId)
        }
      }
    }

    updatedProjectLinks
  }

  private def roadPartEndAdminClassChanged(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    val adminClassChangedLinks = projectLinks.filter(pl => pl.administrativeClass != pl.originalAdministrativeClass)
    val adminClassChangedLeftLinks = adminClassChangedLinks.filter(pl => pl.track == Track.LeftSide)
    val adminClassChangedRightLinks = adminClassChangedLinks.filter(pl => pl.track == Track.RightSide)

    // If there are no admin class changes on both tracks, return unchanged
    if (adminClassChangedLeftLinks.isEmpty || adminClassChangedRightLinks.isEmpty) {
      projectLinks
    } else {
      val maxOriginalAddrM = projectLinks.map(_.originalAddrMRange.end).max
      val lastAdminClassChangedLeft  = adminClassChangedLeftLinks.maxBy(_.originalAddrMRange.end)
      val lastAdminClassChangedRight = adminClassChangedRightLinks.maxBy(_.originalAddrMRange.end)

      if (lastAdminClassChangedLeft.originalAddrMRange.end == maxOriginalAddrM &&
        lastAdminClassChangedRight.originalAddrMRange.end == maxOriginalAddrM) {
        // If road part end is two track and admin class changed
        val (leftAdminClassChangedSections, rightAdminClassChangedSections) = adminClassChangedLinksToContinuousTwoTrackSections(adminClassChangedLinks)
        val lastAdminClassChangedLeftSectionOpt   = leftAdminClassChangedSections.find(section => section.exists(_.id == lastAdminClassChangedLeft.id))
        val lastAdminClassChangedRightSectionOpt  = rightAdminClassChangedSections.find(section => section.exists(_.id == lastAdminClassChangedRight.id))

        (lastAdminClassChangedLeftSectionOpt, lastAdminClassChangedRightSectionOpt) match {
          case (Some(leftSection), Some(rightSection)) =>
            handleTwoTrackRoadPartEndAdminClassChange(leftSection, rightSection, projectLinks)
          case _ =>
            projectLinks
        }
      } else {
        projectLinks
      }
    }
  }

  private def handleTwoTrackRoadPartEndAdminClassChange(adminClassChangedLeftSection: Seq[ProjectLink], adminClassChangedRightSection: Seq[ProjectLink], projectLinks: Seq[ProjectLink]): Seq[ProjectLink]= {

    def adjustAdminClassChangedStartToMatch(adminClassChangedLeftLink: ProjectLink, adminClassChangedRightLink: ProjectLink): (ProjectLink, ProjectLink, Long) = {
      // Calculate the average for admin class changed section start
      val averageStart = TwoTrackRoadUtils.calculateAverageAddrM(adminClassChangedLeftLink.addrMRange.start, adminClassChangedRightLink.addrMRange.start)
      val adjustedAdminClassChangedLeft = SynchronizationUtils.replaceStartsWith(adminClassChangedLeftLink, averageStart)
      val adjustedAdminClassChangedRight = SynchronizationUtils.replaceStartsWith(adminClassChangedRightLink, averageStart)
      (adjustedAdminClassChangedLeft, adjustedAdminClassChangedRight, averageStart)
    }

    def adjustLinkEndsToMatch(leftLink: ProjectLink, rightLink: ProjectLink, addrMToAdjust: Long): (ProjectLink, ProjectLink) = {
      // Adjust the link end addresses to match
      (SynchronizationUtils.replaceEndsWith(leftLink, addrMToAdjust), SynchronizationUtils.replaceEndsWith(rightLink, addrMToAdjust))
    }

    val firstLinkOnLeftAdminClassChangedSection  = adminClassChangedLeftSection.minBy(_.addrMRange.start)
    val firstLinkOnRightAdminClassChangedSection =  adminClassChangedRightSection.minBy(_.addrMRange.start)

    if ((firstLinkOnLeftAdminClassChangedSection.addrMRange.start == firstLinkOnRightAdminClassChangedSection.addrMRange.start) ||
      !SynchronizationUtils.areTracksCloseEnoughOnOriginalStartAddrM(firstLinkOnLeftAdminClassChangedSection, firstLinkOnRightAdminClassChangedSection)) {
      // Return the project links unchanged
      projectLinks
    } else {
      // Find previous links if there are any
      val previousLeftLink  = SynchronizationUtils.findPreviousLink(projectLinks, firstLinkOnLeftAdminClassChangedSection, Track.RightSide)
      val previousRightLink = SynchronizationUtils.findPreviousLink(projectLinks, firstLinkOnRightAdminClassChangedSection, Track.LeftSide)

      val averageStartForAdminClassChanged = SynchronizationUtils.clampSharedStartAddrM(
        TwoTrackRoadUtils.calculateAverageAddrM(firstLinkOnLeftAdminClassChangedSection.addrMRange.start, firstLinkOnRightAdminClassChangedSection.addrMRange.start),
        Seq(firstLinkOnLeftAdminClassChangedSection, firstLinkOnRightAdminClassChangedSection),
        Seq(previousLeftLink, previousRightLink).flatten
      )

      val adjustedAdminClassChangedLeft = SynchronizationUtils.replaceStartsWith(firstLinkOnLeftAdminClassChangedSection, averageStartForAdminClassChanged)
      val adjustedAdminClassChangedRight = SynchronizationUtils.replaceStartsWith(firstLinkOnRightAdminClassChangedSection, averageStartForAdminClassChanged)

      (previousLeftLink, previousRightLink) match {
        case (Some(prevLeft), Some(prevRight)) =>
          // Update the previous link ends to match
          val (adjustedPreviousLeftLink, adjustedPreviousRightLink)  = adjustLinkEndsToMatch(prevLeft, prevRight, averageStartForAdminClassChanged)
          SynchronizationUtils.updateProjectLinksList(Seq(adjustedAdminClassChangedLeft, adjustedAdminClassChangedRight, adjustedPreviousLeftLink, adjustedPreviousRightLink), projectLinks)
        case _ =>
          // No need to update the previous links
          SynchronizationUtils.updateProjectLinksList(Seq(adjustedAdminClassChangedLeft, adjustedAdminClassChangedRight), projectLinks)
      }
    }
  }
}
