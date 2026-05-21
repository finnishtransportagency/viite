package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.dao.ProjectLink
import fi.liikennevirasto.viite.util.{SynchronizationUtils, TwoTrackRoadUtils}
import fi.vaylavirasto.viite.model.{RoadAddressChangeType, Track}
import fi.vaylavirasto.viite.util.ViiteException

object TwoTrackAverager {

	private case class TrackSection(track: Track, links: Seq[ProjectLink]) {
		val first: ProjectLink = links.minBy(_.originalAddrMRange.start)
		val last: ProjectLink = links.maxBy(_.originalAddrMRange.end)
		val originalStart: Long = first.originalAddrMRange.start
		val originalEnd: Long = last.originalAddrMRange.end
	}

	/* 
  Averages boundaries between paired left and right road sections that are not 
  new or renumbered, and slides the following links to match the new values. 
	*/
	def averageTwoTrackBoundaries(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
		val averageable = projectLinks.filter(link =>
			link.status != RoadAddressChangeType.New && link.status != RoadAddressChangeType.Renumeration && (link.track == Track.LeftSide || link.track == Track.RightSide)
		)
		val rightLinks = averageable.filter(_.track == Track.RightSide)
		val leftLinks = averageable.filter(_.track == Track.LeftSide)

		if (rightLinks.isEmpty || leftLinks.isEmpty) {
			projectLinks
		} else {
			val rightSections = toSections(rightLinks)
			val leftSections = toSections(leftLinks)

			val sectionPairs = pairSections(rightSections, leftSections)
			averageSectionEnds(projectLinks, sectionPairs)
		}
	}

	// Detects when a new section should begin by checking if road part, status, or administrative attributes change.
	// Used to group consecutive links into sections that can be independently synchronized
	private def startsNewSection(previous: ProjectLink, current: ProjectLink): Boolean = {
		val roadPartChanged = previous.roadPart != current.roadPart
		val statusChanged = previous.status != current.status
		val administrativeClassChanged = previous.administrativeClass != current.administrativeClass
		val notContiguous = previous.addrMRange.end != current.addrMRange.start

		roadPartChanged ||
			statusChanged ||
			administrativeClassChanged ||
			notContiguous
	}

	// Groups links into continuous sections by road part, status, and administrative class.
	// Each section represents a contiguous stretch of road that can be paired with its counterpart track
  private def toSections(trackLinks: Seq[ProjectLink]): Seq[TrackSection] = {

    // Nothing to process
    if (trackLinks.isEmpty) {
      Seq.empty

    } else {

      // Sort links into deterministic road order so adjacent links
      // belonging to the same physical section appear next to each other.
      val ordered = trackLinks.sortBy(link => (
        link.roadPart.roadNumber,
        link.roadPart.partNumber,
        link.addrMRange.start,
        link.addrMRange.end,
        link.originalAddrMRange.start,
        link.originalAddrMRange.end,
        link.id
      ))

      // Completed sections collected during iteration. Vector is used for efficient appends
      val sections = collection.mutable.ArrayBuffer.empty[Vector[ProjectLink]]

      // The section currently being built. Start with the first ordered link
      var current = Vector(ordered.head)

      // Process remaining links one by one
      for (link <- ordered.tail) {

        // If this link does not belong to the current contiguous section, close the current section and start a new one
        if (startsNewSection(current.last, link)) {

          sections += current
          current = Vector(link)

        } else {

          // Link belongs to the current section, so add it to the end
          current :+= link
        }
      }

      // Add the final in-progress section after iteration completes
      sections += current

      // Convert grouped links into domain objects
      sections
        .map(links => TrackSection(links.head.track, links))
        .toSeq
    }
  }

	/*
  Pairs right and left sections by matching road parts and comparing original address ranges.
	Ensures one-to-one correspondence and throws if pairing fails, as unpaired sections indicate data issues
	*/
  private def pairSections(
    rightSections: Seq[TrackSection],
    leftSections: Seq[TrackSection]
  ): Seq[(TrackSection, TrackSection)] = {

    var remainingLeft = leftSections

    val pairs = rightSections.flatMap { rightSection =>

      // Build section candidates: (leftSection, startDiff)
      val candidates =
        remainingLeft.flatMap { leftSection =>

          val sameRoadPart =
            leftSection.first.roadPart == rightSection.first.roadPart

          if (!sameRoadPart) {
            None
          } else {
            val startDiff =
              Math.abs(rightSection.originalStart - leftSection.originalStart)

            if (startDiff <= SynchronizationUtils.maxDiffForTracks)
              Some((leftSection, startDiff))
            else
              None
          }
        }

      if (candidates.isEmpty) {
        None
      } else {
        val (matchedLeft, _) =
          candidates.minBy(_._2)

        remainingLeft =
          remainingLeft.filterNot(_.eq(matchedLeft))

        Some((rightSection, matchedLeft))
      }
    }

    pairs
  }

	/*
  Averages paired section boundaries and updates following links that start at the averaged position.
	Selectively updates originalAddrMRange: for internal boundaries (where followers exist), both current and original
	are averaged; for terminal section ends, only current is averaged to preserve transfer recalculation idempotence.
	*/
  private def averageSectionEnds( initialLinks: Seq[ProjectLink], sectionPairs: Seq[(TrackSection, TrackSection)]): Seq[ProjectLink] = {
		def replaceCurrentEnd(link: ProjectLink, endAddrM: Long, updateOriginal: Boolean): ProjectLink = {
			val updatedCurrent = fi.vaylavirasto.viite.model.AddrMRange(link.addrMRange.start, endAddrM)
			val updatedOriginal =
				if (updateOriginal) fi.vaylavirasto.viite.model.AddrMRange(link.originalAddrMRange.start, endAddrM)
				else link.originalAddrMRange

			link.copy(addrMRange = updatedCurrent, originalAddrMRange = updatedOriginal)
		}

		def replaceCurrentStart(link: ProjectLink, startAddrM: Long, updateOriginal: Boolean): ProjectLink = {
			val updatedCurrent = fi.vaylavirasto.viite.model.AddrMRange(startAddrM, link.addrMRange.end)
			val updatedOriginal =
				if (updateOriginal) fi.vaylavirasto.viite.model.AddrMRange(startAddrM, link.originalAddrMRange.end)
				else link.originalAddrMRange

			link.copy(addrMRange = updatedCurrent, originalAddrMRange = updatedOriginal)
		}

		sectionPairs.foldLeft(initialLinks) { case (currentLinks, (rightSection, leftSection)) =>
			val currentRightLast = currentLinks.find(_.id == rightSection.last.id).getOrElse(rightSection.last)
			val currentLeftLast = currentLinks.find(_.id == leftSection.last.id).getOrElse(leftSection.last)

			val rightFollower = SynchronizationUtils.findNextLink(currentLinks, currentRightLast, Track.LeftSide)
			val leftFollower = SynchronizationUtils.findNextLink(currentLinks, currentLeftLast, Track.RightSide)

      // Slide only if the follower link is contiguous with the current last link, otherwise it indicates a section boundary
			val slideableRightFollower = rightFollower.filter(f => currentRightLast.addrMRange.continuesTo(f.addrMRange))
			val slideableLeftFollower = leftFollower.filter(f => currentLeftLast.addrMRange.continuesTo(f.addrMRange))

			val averagedEnd = SynchronizationUtils.clampSharedEndAddrM(
				TwoTrackRoadUtils.calculateAverageAddrM(currentRightLast.addrMRange.end, currentLeftLast.addrMRange.end),
				Seq(currentRightLast, currentLeftLast),
				Seq(slideableRightFollower, slideableLeftFollower).flatten
			)

			val isInternalBoundary = slideableRightFollower.isDefined || slideableLeftFollower.isDefined

			val adjustedRightLast = replaceCurrentEnd(currentRightLast, averagedEnd, updateOriginal = isInternalBoundary)
			val adjustedLeftLast = replaceCurrentEnd(currentLeftLast, averagedEnd, updateOriginal = isInternalBoundary)

			val followerUpdates = Seq(slideableRightFollower, slideableLeftFollower).flatten.map { follower =>
				replaceCurrentStart(follower, averagedEnd, updateOriginal = true)
			}

			SynchronizationUtils.updateProjectLinksList(
				Seq(adjustedRightLast, adjustedLeftLast) ++ followerUpdates,
				currentLinks
			)
		}
	}
}
