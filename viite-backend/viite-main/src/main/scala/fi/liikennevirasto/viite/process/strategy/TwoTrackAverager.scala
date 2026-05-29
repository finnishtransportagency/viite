package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.dao.ProjectLink
import fi.liikennevirasto.viite.util.{SynchronizationUtils, TwoTrackRoadUtils}
import fi.vaylavirasto.viite.model.{AddrMRange, RoadAddressChangeType, RoadPart, Track}

object TwoTrackAverager {

	// Selects which coordinate space sectioning uses (current vs original)
	private sealed trait AddressSpace {
		def roadPart(link: ProjectLink): RoadPart
		def start(link: ProjectLink): Long
		def end(link: ProjectLink): Long
	}

	private object CurrentAddressSpace extends AddressSpace {
		override def roadPart(link: ProjectLink): RoadPart = link.roadPart
		override def start(link: ProjectLink): Long = link.addrMRange.start
		override def end(link: ProjectLink): Long = link.addrMRange.end
	}

	private object OriginalAddressSpace extends AddressSpace {
		override def roadPart(link: ProjectLink): RoadPart = link.originalRoadPart
		override def start(link: ProjectLink): Long = link.originalAddrMRange.start
		override def end(link: ProjectLink): Long = link.originalAddrMRange.end
	}

	private case class TrackSection(track: Track, links: Seq[ProjectLink], first: ProjectLink, last: ProjectLink, sectionStart: Long, sectionEnd: Long)

	private def createTrackSection(space: AddressSpace, links: Seq[ProjectLink]): TrackSection = {
		val first = links.minBy(space.start)
		val last = links.maxBy(space.end)
		TrackSection(
			track = links.head.track,
			links = links,
			first = first,
			last = last,
			sectionStart = space.start(first),
			sectionEnd = space.end(last)
		)
	}

	/* 
  Averages boundaries between paired left and right road sections that are not 
  new or renumbered, and slides the following links to match the new values. 
	*/
	def averageTwoTrackBoundaries(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {

		val averageable = projectLinks.filter(link =>
			link.status != RoadAddressChangeType.New && link.status != RoadAddressChangeType.Renumeration
		)

		val rightLinks = averageable.filter(_.track == Track.RightSide)
		val leftLinks = averageable.filter(_.track == Track.LeftSide)

		if (rightLinks.isEmpty || leftLinks.isEmpty) {
			projectLinks
		} else {
			val currentRightSections = toSections(CurrentAddressSpace, rightLinks)
			val currentLeftSections = toSections(CurrentAddressSpace, leftLinks)

			val currentSectionPairs = pairSections(
				currentRightSections,
				currentLeftSections
			)

			val originalRightSections = toSections(OriginalAddressSpace, rightLinks)
			val originalLeftSections = toSections(OriginalAddressSpace, leftLinks)

			val originalSectionPairs = pairSections(
				originalRightSections,
				originalLeftSections
			)

			val currentAveraged = averageCurrentSectionEnds(projectLinks, currentSectionPairs)
			averageOriginalSectionEnds(currentAveraged, originalSectionPairs)
		}
	}

	// Detects when a new section should begin by checking if road part, status, or administrative attributes change.
	// Used to group consecutive links into sections that can be independently synchronized
	private def startsNewSection(space: AddressSpace, previous: ProjectLink, current: ProjectLink): Boolean = {
		val currentRoadPartChanged = previous.roadPart != current.roadPart
		val originalRoadPartChanged = previous.originalRoadPart != current.originalRoadPart
		val roadPartChanged = currentRoadPartChanged || originalRoadPartChanged
		val statusChanged = previous.status != current.status
		val administrativeClassChanged = previous.administrativeClass != current.administrativeClass
		val notContiguous = space.end(previous) != space.start(current)

		roadPartChanged ||
			statusChanged ||
			administrativeClassChanged ||
			notContiguous
	}

	// Groups links into continuous sections by road part, status, and administrative class.
	// Each section represents a contiguous stretch of road that can be paired with its counterpart track
	private def toSections(space: AddressSpace, trackLinks: Seq[ProjectLink]): Seq[TrackSection] = {

    // Nothing to process
    if (trackLinks.isEmpty) {
      Seq.empty

    } else {

      // Sort links into deterministic road order so adjacent links
      // belonging to the same physical section appear next to each other.
			val ordered = trackLinks.sortBy(link => (
				space.roadPart(link).roadNumber,
				space.roadPart(link).partNumber,
				space.start(link),
				space.end(link),
				link.id
			))

      // Completed sections collected during iteration. Vector is used for efficient appends
      val sections = collection.mutable.ArrayBuffer.empty[Vector[ProjectLink]]

      // The section currently being built. Start with the first ordered link
      var current = Vector(ordered.head)

      // Process remaining links one by one
      for (link <- ordered.tail) {

        // If this link does not belong to the current contiguous section, close the current section and start a new one
	        if (startsNewSection(space, current.last, link)) {
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
				.map(links => createTrackSection(space, links))
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
            val startDiff = Math.abs(rightSection.sectionStart - leftSection.sectionStart)

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
  Updates both current and original address ranges for the section end links. Follower links are slid only when they
  have a non-zero original start address (i.e. they are not new links or links on a new road part).
	*/
	private def averageCurrentSectionEnds(initialLinks: Seq[ProjectLink], sectionPairs: Seq[(TrackSection, TrackSection)]): Seq[ProjectLink] = {
		def replaceCurrentEnd(link: ProjectLink, currentEndAddrM: Long): ProjectLink = {
			link.copy(addrMRange = AddrMRange(link.addrMRange.start, currentEndAddrM))
		}

		def replaceCurrentStart(link: ProjectLink, currentStartAddrM: Long): ProjectLink = {
			link.copy(addrMRange = AddrMRange(currentStartAddrM, link.addrMRange.end))
		}

		sectionPairs.foldLeft(initialLinks) { case (currentLinks, (rightSection, leftSection)) =>
			val currentRightLast = currentLinks.find(_.id == rightSection.last.id).getOrElse(rightSection.last)
			val currentLeftLast = currentLinks.find(_.id == leftSection.last.id).getOrElse(leftSection.last)

			val rightFollower = SynchronizationUtils.findNextLink(currentLinks, currentRightLast, Track.LeftSide, useOriginalAddrMRange = false)
			val leftFollower = SynchronizationUtils.findNextLink(currentLinks, currentLeftLast, Track.RightSide, useOriginalAddrMRange = false)
			val followers = Seq(rightFollower, leftFollower).flatten

			val rawCurrentAverage = TwoTrackRoadUtils.calculateAverageAddrM(
				currentRightLast.addrMRange.end,
				currentLeftLast.addrMRange.end
			)

			val averagedEnd = SynchronizationUtils.clampSharedEndAddrM(
				rawCurrentAverage,
				Seq(currentRightLast, currentLeftLast),
				followers
			)

			val wouldOverflowFollower = followers.exists { follower =>
				averagedEnd >= follower.addrMRange.end
			}

			if (wouldOverflowFollower) {
        println(s"Skipping averaging for section ending at ${currentRightLast.addrMRange.end} / ${currentLeftLast.addrMRange.end} because averaged end $averagedEnd would overflow follower link with end ${followers.map(_.addrMRange.end)}")
				currentLinks
			} else {
				val averagedRightLast = replaceCurrentEnd(currentRightLast, averagedEnd)
				val averagedLeftLast = replaceCurrentEnd(currentLeftLast, averagedEnd)

				val slidFollowers = followers.flatMap { follower =>
					if (follower.originalAddrMRange.start != 0) Some(replaceCurrentStart(follower, averagedEnd))
					else None
				}

				SynchronizationUtils.updateProjectLinksList(
					Seq(averagedRightLast, averagedLeftLast) ++ slidFollowers,
					currentLinks
				)
			}
		}
	}

	private def averageOriginalSectionEnds(initialLinks: Seq[ProjectLink], sectionPairs: Seq[(TrackSection, TrackSection)]): Seq[ProjectLink] = {
		def clampOriginalEndAddrM(averageEnd: Long, sectionLinks: Seq[ProjectLink], followingLinks: Seq[ProjectLink]): Long = {
			val minAverageEnd = sectionLinks.map(_.originalAddrMRange.start + 1).max
			val maxAverageEnd = followingLinks.map(_.originalAddrMRange.end - 1).reduceOption(_ min _).getOrElse(averageEnd)

			if (minAverageEnd > maxAverageEnd) averageEnd
			else math.max(minAverageEnd, math.min(averageEnd, maxAverageEnd))
		}

		def replaceOriginalEnd(link: ProjectLink, originalEndAddrM: Long): ProjectLink = {
			link.copy(
				originalAddrMRange = AddrMRange(link.originalAddrMRange.start, originalEndAddrM)
			)
		}

		def replaceOriginalStart(link: ProjectLink, originalStartAddrM: Long): ProjectLink = {
			link.copy(
				originalAddrMRange = AddrMRange(originalStartAddrM, link.originalAddrMRange.end)
			)
		}

		sectionPairs.foldLeft(initialLinks) { case (currentLinks, (rightSection, leftSection)) =>
			val currentRightLast = currentLinks.find(_.id == rightSection.last.id).getOrElse(rightSection.last)
			val currentLeftLast = currentLinks.find(_.id == leftSection.last.id).getOrElse(leftSection.last)

			val rightFollower = SynchronizationUtils.findNextLink(currentLinks, currentRightLast, Track.LeftSide)
			val leftFollower = SynchronizationUtils.findNextLink(currentLinks, currentLeftLast, Track.RightSide)
			val followers = Seq(rightFollower, leftFollower).flatten

			val rawOriginalAverage = TwoTrackRoadUtils.calculateAverageAddrM(
				currentRightLast.originalAddrMRange.end,
				currentLeftLast.originalAddrMRange.end
			)

			val averagedOriginalEnd = clampOriginalEndAddrM(
				rawOriginalAverage,
				Seq(currentRightLast, currentLeftLast),
				followers
			)

			// Discard the averaged values entirely if they would push past any follower's end,
			// which would produce a negative range when the follower is slid.
			val wouldOverflowFollower = followers.exists(averagedOriginalEnd >= _.originalAddrMRange.end)

			if (wouldOverflowFollower) {
        println(s"Skipping original averaging for section ending at ${currentRightLast.originalAddrMRange.end} / ${currentLeftLast.originalAddrMRange.end} because averaged end $averagedOriginalEnd would overflow follower link with end ${followers.map(_.originalAddrMRange.end)}")
				currentLinks
			} else {
				val averagedRightLast = replaceOriginalEnd(currentRightLast, averagedOriginalEnd)
				val averagedLeftLast = replaceOriginalEnd(currentLeftLast, averagedOriginalEnd)

				val slidFollowers = followers.flatMap { follower =>
					if (follower.originalAddrMRange.start != 0) { // Don't slide when road part changes (start value is 0)
						Some(replaceOriginalStart(follower, averagedOriginalEnd))
					} else
						None
				}

				SynchronizationUtils.updateProjectLinksList(
					Seq(averagedRightLast, averagedLeftLast) ++ slidFollowers,
					currentLinks
				)
			}
		}
	}
}

