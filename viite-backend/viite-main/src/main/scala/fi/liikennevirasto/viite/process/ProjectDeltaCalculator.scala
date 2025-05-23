package fi.liikennevirasto.viite.process

import fi.liikennevirasto.viite.dao.{ProjectLink, _}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, ArealRoadMaintainer, CalibrationPointType, Discontinuity, RoadAddressChangeType, RoadPart, Track}
import org.joda.time.DateTime

import scala.annotation.tailrec

case class RoadwaySection(roadNumber: Long, roadPartNumberStart: Long, roadPartNumberEnd: Long, track: Track, addrMRange: AddrMRange,
                          discontinuity: Discontinuity, administrativeClass: AdministrativeClass, arealRoadMaintainer: ArealRoadMaintainer, reversed: Boolean,
                          roadwayNumber: Long, projectLinks: Seq[ProjectLink]) {
}

/**
  * Calculate the effective change between the project and the current road address data
  */
object ProjectDeltaCalculator {

  val MaxAllowedMValueError = 0.001
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)

  private def combineProjectLinks(pl1: ProjectLink, pl2: ProjectLink, allNonTerminatedProjectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    val sameStatus = pl1.status == pl2.status
    val bothNew = pl1.status == RoadAddressChangeType.New && pl2.status == RoadAddressChangeType.New && pl1.track != Track.Combined && pl2.track != Track.Combined
    val matchAddr = pl1.addrMRange.continuesTo(pl2.addrMRange)
    val matchContinuity = pl1.discontinuity == Discontinuity.Continuous
    val oppositePl1 = allNonTerminatedProjectLinks.filter( pl => pl.track != pl1.track && pl.track != Track.Combined && (pl.addrMRange.end == pl1.addrMRange.end || (pl.status != RoadAddressChangeType.New && pl1.status != RoadAddressChangeType.New && pl.originalAddrMRange.end == pl1.originalAddrMRange.end)))
    val oppositePl2 = allNonTerminatedProjectLinks.filter( pl => pl.track != pl1.track && pl.track != Track.Combined && (pl.addrMRange.start == pl1.addrMRange.end || (pl.status != RoadAddressChangeType.New && pl1.status != RoadAddressChangeType.New && pl.originalAddrMRange.end == pl1.originalAddrMRange.end)))
    val oppositeStatusChange = oppositePl1.nonEmpty && oppositePl2.nonEmpty && oppositePl1.last.status != oppositePl2.last.status
    val hasCalibrationPoint = ((pl1.status != RoadAddressChangeType.New && pl1.hasCalibrationPointAtEnd) && pl1.isEndCalibrationPointCreatedInProject && pl1.endCalibrationPointType != CalibrationPointType.JunctionPointCP) || (oppositePl1.nonEmpty && oppositePl1.head.hasCalibrationPointAtEnd && oppositePl1.head.isEndCalibrationPointCreatedInProject && oppositePl1.head.endCalibrationPointType != CalibrationPointType.JunctionPointCP)
    val trackNotUpdated = if (pl1.reversed && pl2.reversed && pl1.track != Track.Combined && pl2.track != Track.Combined) pl2.originalTrack != pl2.track else pl2.originalTrack == pl1.originalTrack && pl1.track == pl2.track
    val oppositeTrackNotUpdated = if (pl2.reversed) (oppositePl2.nonEmpty && (oppositePl2.head.originalTrack != oppositePl2.head.track || oppositePl2.head.status == RoadAddressChangeType.New)) || oppositePl2.isEmpty else (oppositePl2.nonEmpty && (oppositePl2.head.originalTrack == oppositePl2.head.track || oppositePl2.head.status == RoadAddressChangeType.New)) || oppositePl1.isEmpty
    val newLinks = Seq(pl1, pl2).forall(_.status == RoadAddressChangeType.New)
    val originalTrackContinuous = pl1.originalTrack == pl2.originalTrack
    val administrativeClassesMatch = pl1.administrativeClass == pl2.administrativeClass
    val originalAdministrativeClassContinuous = pl1.originalAdministrativeClass == pl2.originalAdministrativeClass

    val hasParallelLinkOnCalibrationPoint = hasCalibrationPoint && bothNew && matchContinuity && allNonTerminatedProjectLinks.exists(pl => {
      pl.roadPart == pl1.roadPart && pl.status != RoadAddressChangeType.Termination && pl.track != pl1.track && pl.track != Track.Combined && pl.addrMRange.end == pl1.addrMRange.end && pl.hasCalibrationPointAtEnd
    })
    val oppositeOriginalAddressLinks = allNonTerminatedProjectLinks.filter(pl => {
      pl.track != pl1.track && pl.track != Track.Combined && pl1.track != Track.Combined && (pl.originalAddrMRange.end == pl1.originalAddrMRange.end || pl.originalAddrMRange.start == pl1.originalAddrMRange.end)
    })

    val oppositeStatusNotChanged = if (oppositeOriginalAddressLinks.size == 2) oppositeOriginalAddressLinks.head.status == oppositeOriginalAddressLinks.last.status else true

    if (!oppositeStatusChange && (matchAddr && sameStatus && matchContinuity && administrativeClassesMatch && trackNotUpdated && originalTrackContinuous &&
      (newLinks || oppositeTrackNotUpdated) && !(hasCalibrationPoint || hasParallelLinkOnCalibrationPoint)) &&
        administrativeClassesMatch && oppositeStatusNotChanged && originalAdministrativeClassContinuous) {
      val minStartAddrMValue = Math.min(pl1.originalAddrMRange.start, pl2.originalAddrMRange.start)
      val maxEndAddrMValue = Math.max(pl1.originalAddrMRange.end, pl2.originalAddrMRange.end)
      val pl1OriginalAddressSet = {
        if (pl1.reversed)
          pl1.copy(originalAddrMRange = AddrMRange(minStartAddrMValue, maxEndAddrMValue), roadwayId = pl1.roadwayId)
        else
          pl1.copy(originalAddrMRange = AddrMRange(minStartAddrMValue, maxEndAddrMValue), roadwayId = pl2.roadwayId)
      }

      Seq(
        pl1OriginalAddressSet.copy(discontinuity = pl2.discontinuity, addrMRange = AddrMRange(pl1OriginalAddressSet.addrMRange.start, pl2.addrMRange.end),
          calibrationPointTypes = (pl1.startCalibrationPointType, pl2.endCalibrationPointType),
          originalCalibrationPointTypes = (pl1.originalCalibrationPointTypes._1, pl2.originalCalibrationPointTypes._2))
          )
    }
    else {
      Seq(pl2, pl1)
    }
  }

  private def combineTwo[R <: BaseRoadAddress, P <: BaseRoadAddress](tr1: (R, P), tr2: (R, P), allNonTerminatedProjectLinks: Seq[ProjectLink]): Seq[(R, P)] = {
    val (ra1, pl1) = (tr1._1, tr1._2.asInstanceOf[ProjectLink])
    val (ra2, pl2) = (tr2._1, tr2._2.asInstanceOf[ProjectLink])
    val matchAddr = pl1.addrMRange.continuesTo(pl2.addrMRange)
    val matchContinuity = pl1.discontinuity == Discontinuity.Continuous
    val oppositePl = allNonTerminatedProjectLinks.filter( pl => pl.track != pl1.track && pl.addrMRange.end == pl1.addrMRange.end).filter(_.status != RoadAddressChangeType.Termination)
    val hasCalibrationPoint = ((pl1.track == Track.Combined && pl1.hasCalibrationPointAtEnd) && pl1.hasCalibrationPointCreatedInProject) || (oppositePl.nonEmpty && oppositePl.head.hasCalibrationPointAtEnd && pl1.hasCalibrationPointAtEnd) // Opposite side has user cp
    if (matchAddr && matchContinuity && !hasCalibrationPoint &&
        ra1.administrativeClass == ra2.administrativeClass && pl1.administrativeClass == pl2.administrativeClass && pl1.reversed == pl2.reversed) {
      Seq((
            ra1.asInstanceOf[RoadAddress].copy(discontinuity = ra2.discontinuity, addrMRange = AddrMRange(ra1.asInstanceOf[RoadAddress].addrMRange.start, ra2.addrMRange.end)).asInstanceOf[R],
            pl1.copy(discontinuity = pl2.discontinuity, addrMRange = AddrMRange(pl1.addrMRange.start, pl2.addrMRange.end), calibrationPointTypes = (pl1.startCalibrationPointType, pl2.endCalibrationPointType), originalCalibrationPointTypes = (pl1.originalCalibrationPointTypes._1, pl2.originalCalibrationPointTypes._2)).asInstanceOf[P]
          ))
    }
    else {
      Seq(tr2, tr1)
    }
  }

  private def combineTwo(r1: ProjectLink, r2: ProjectLink, allNonTerminatedProjectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    val hasCalibrationPoint = r1.hasCalibrationPointAtEnd

    val hasParallelLinkOnCalibrationPoint =
      if (!hasCalibrationPoint && r1.track != Track.Combined) {
        val projectLinks = allNonTerminatedProjectLinks //projectLinkDAO.fetchProjectLinksByProjectRoadPart(r1.roadNumber, r1.roadPartNumber, r1.projectId)
        val parallelLastOnCalibrationPoint = projectLinks.filter(pl =>
          pl.status != RoadAddressChangeType.Termination &&
            pl.track != r1.track &&
            pl.track != Track.Combined &&
            pl.addrMRange.end == r1.addrMRange.end &&
            pl.hasCalibrationPointAtEnd)
        parallelLastOnCalibrationPoint.nonEmpty
      } else
        false
  val hasUdcp = r1.calibrationPointTypes._2 == CalibrationPointType.UserDefinedCP && r1.status == RoadAddressChangeType.New && r2.status == RoadAddressChangeType.New

    val openBasedOnSource = hasCalibrationPoint && r1.hasCalibrationPointCreatedInProject
    if (r1.addrMRange.continuesTo(r2.addrMRange))
      r1.status match {
        case RoadAddressChangeType.Termination =>
          if (hasCalibrationPoint && r1.roadwayNumber != r2.roadwayNumber)
            Seq(r2, r1)
          else if (openBasedOnSource)
            Seq(r2, r1)
          else
            Seq(r1.copy(discontinuity = r2.discontinuity, addrMRange = AddrMRange(r1.addrMRange.start, r2.addrMRange.end), calibrationPointTypes = r2.calibrationPointTypes))
        case RoadAddressChangeType.Unchanged =>
          if (!openBasedOnSource)
            Seq(r2, r1)
          else
            Seq(r1.copy(discontinuity = r2.discontinuity, addrMRange = AddrMRange(r1.addrMRange.start, r2.addrMRange.end), calibrationPointTypes = r2.calibrationPointTypes))
        case RoadAddressChangeType.New =>
          if (hasUdcp ||( !hasParallelLinkOnCalibrationPoint && !hasCalibrationPoint)) { // && !r1.isSplit
            Seq(r1.copy(discontinuity = r2.discontinuity, addrMRange = AddrMRange(r1.addrMRange.start, r2.addrMRange.end), calibrationPointTypes = r2.calibrationPointTypes, connectedLinkId = r2.connectedLinkId))
          } else {
            Seq(r2, r1)
          }
        case _ =>
          Seq(r2, r1)

      }
    else
      Seq(r2, r1)
  }

  @tailrec
  private def combineWithProjectLinks(projectLinkSeq: Seq[ProjectLink], result: Seq[ProjectLink] = Seq(), allNonTerminatedProjectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    if (projectLinkSeq.isEmpty)
      result.reverse
    else if (result.isEmpty)
      combineWithProjectLinks(projectLinkSeq.tail, Seq(projectLinkSeq.head), allNonTerminatedProjectLinks)
    else
      combineWithProjectLinks(projectLinkSeq.tail, combineProjectLinks(result.head, projectLinkSeq.head, allNonTerminatedProjectLinks) ++ result.tail, allNonTerminatedProjectLinks)
  }

  @tailrec
  private def combine(projectLinkSeq: Seq[ProjectLink], result: Seq[ProjectLink] = Seq(), allNonTerminatedProjectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    if (projectLinkSeq.isEmpty)
      result.reverse
    else if (result.isEmpty)
      combine(projectLinkSeq.tail, Seq(projectLinkSeq.head), allNonTerminatedProjectLinks: Seq[ProjectLink])
    else
      combine(projectLinkSeq.tail, combineTwo(result.head, projectLinkSeq.head, allNonTerminatedProjectLinks: Seq[ProjectLink]) ++ result.tail, allNonTerminatedProjectLinks: Seq[ProjectLink])
  }

  @tailrec
  private def combinePair[T <: BaseRoadAddress, R <: ProjectLink](combinedSeq: Seq[(T, R)], allNonTerminatedProjectLinks: Seq[ProjectLink], result: Seq[(T, R)] = Seq()): Seq[(T, R)] = {
    if (combinedSeq.isEmpty)
      result.reverse
    else if (result.isEmpty)
      combinePair(combinedSeq.tail, allNonTerminatedProjectLinks, Seq(combinedSeq.head))
    else
      combinePair(combinedSeq.tail, allNonTerminatedProjectLinks, combineTwo(result.head, combinedSeq.head, allNonTerminatedProjectLinks) ++ result.tail)
  }

  def createAverageValuesForTransferedStarts(starts: Map[RoadPart, Seq[ProjectLink]]): Seq[ProjectLink] = {
    starts.mapValues(pls => {
      if (pls.size == 2 && pls.forall(_.track != Track.Combined) && (pls.head.status == RoadAddressChangeType.Termination && pls.last.status == RoadAddressChangeType.Termination)) {
        val avg = Math.round(pls.map(_.originalAddrMRange.end).sum * 0.5)
        pls.map(pl => pl.copy(addrMRange = AddrMRange(pl.addrMRange.start, avg), originalAddrMRange = AddrMRange(pl.originalAddrMRange.start, avg)))
      }
      else
        if (pls.size == 2 && pls.forall(_.track != Track.Combined) && (pls.head.originalAddrMRange.start != 0 || pls.last.originalAddrMRange.start != 0L)) {
          val avg = Math.round(pls.map(_.originalAddrMRange.start).sum * 0.5)
          pls.map(pl => pl.copy(originalAddrMRange = AddrMRange(avg, pl.originalAddrMRange.end)))
        }
         else
      pls
    }).values.flatten.toSeq
  }

  private def sortAndTakeTerminated(pls: Seq[ProjectLink]): Seq[ProjectLink] = {
    if (pls.isEmpty) pls
    else {
      val sortedProjectLinks = pls.sortBy(_.originalAddrMRange.start)
      val terminatedProjectLinks = sortedProjectLinks.takeWhile {_.status == RoadAddressChangeType.Termination}
      if (terminatedProjectLinks.nonEmpty)
        Seq(terminatedProjectLinks.last)
      else
        Seq()
    }
  }

  /** Create change table rows
   *
   * @param projectLinks    ProjectLinks to process for change table rows
   * @param allProjectLinks All ProjectLinks in project for additional information
   * @return Changetable rows
   */
  def generateChangeTableRowsFromProjectLinks(projectLinks: Seq[ProjectLink], allProjectLinks: Seq[ProjectLink]): ChangeTableRows2 = {
    val startLinks = projectLinks.filter(pl => pl.addrMRange.isRoadPartStart).groupBy(pl => {
      (pl.roadPart)})
    val leftAndRightTrackProjectLinks = allProjectLinks.filter(pl => {pl.track != Track.Combined})
    val leftAndRightTrackProjectLinksGroupedByRoadPart = leftAndRightTrackProjectLinks.groupBy(pl => {pl.roadPart})
    val terminatedForAveraging = leftAndRightTrackProjectLinksGroupedByRoadPart.mapValues(pls => {
        if (pls.exists(pl => pl.status == RoadAddressChangeType.Termination && pl.originalAddrMRange.isRoadPartStart)) {
          val (r, l) = pls.partition(_.track == Track.RightSide)
          Seq(sortAndTakeTerminated(r),sortAndTakeTerminated(l)).flatten
      } else
          Seq()
      })


    val averagedStarts = if (projectLinks.forall(_.status == RoadAddressChangeType.Termination)) createAverageValuesForTransferedStarts(terminatedForAveraging) else createAverageValuesForTransferedStarts(startLinks)
    val averagedTerminated = createAverageValuesForTransferedStarts(terminatedForAveraging)

    def groupToSections(pl: ProjectLink): (RoadPart, Track, Boolean) = (pl.originalRoadPart, pl.originalTrack, pl.reversed)
    val grouped =
      if (allProjectLinks.exists(pl => pl.status == RoadAddressChangeType.Termination && pl.originalAddrMRange.isRoadPartStart)) {
        val projectLinksWithAveragedReplacements = (averagedStarts ++ projectLinks.filterNot(pl => averagedStarts.map(_.id).contains(pl.id)))
        projectLinksWithAveragedReplacements.sortBy(pl => (pl.roadPart.roadNumber, pl.roadPart.partNumber, pl.originalAddrMRange.start)).groupBy(groupToSections)
      } else
        projectLinks.sortBy(pl => (pl.roadPart.roadNumber, pl.roadPart.partNumber, pl.addrMRange.start)).groupBy(groupToSections)

    val allWithAveraged = if (allProjectLinks.exists(pl => pl.status == RoadAddressChangeType.Termination && pl.originalAddrMRange.isRoadPartStart))
      (averagedStarts ++ averagedTerminated ++ allProjectLinks.filterNot(pl => averagedStarts.map(_.id).contains(pl.id) || averagedTerminated.map(_.id).contains(pl.id)))
    else allProjectLinks

    val sectioned = grouped.mapValues((pls: Seq[ProjectLink]) => {

      combineWithProjectLinks(pls, Seq(), allWithAveraged.filter(pl => {
        pl.roadPart == pls.head.roadPart
      }))
    }).values.flatten.map(pl => {
      RoadwaySection(
        pl.originalRoadPart.roadNumber, pl.originalRoadPart.partNumber, pl.originalRoadPart.partNumber, pl.originalTrack, pl.originalAddrMRange,
        pl.originalDiscontinuity, pl.originalAdministrativeClass, pl.originalARM, pl.reversed, pl.roadwayNumber, Seq()
      ) ->
        RoadwaySection(pl.roadPart.roadNumber, pl.roadPart.partNumber, pl.roadPart.partNumber, pl.track, pl.addrMRange,
        pl.discontinuity, pl.administrativeClass, pl.arealRoadMaintainer, pl.reversed, pl.roadwayNumber, Seq()
        )
    }).toSeq

    val sections = sectioned.map(sect => {
      val (src, targetToMap) = sect
      val target = targetToMap.copy(projectLinks = projectLinks.filter(link => {
          link.roadPart == RoadPart(targetToMap.roadNumber, targetToMap.roadPartNumberEnd) &&
          link.track == targetToMap.track && link.arealRoadMaintainer == targetToMap.arealRoadMaintainer &&
          targetToMap.addrMRange.contains(link.addrMRange)
        }))

      (src,target)
    })

    ChangeTableRows2(adjustedSections = sections.map(_._2), originalSections = sections.map(_._1))
  }

  /** Create grouping for old address parts.
   * Utility function for two track terminated change table address matching.
   *
   * @param unChanged_roadway_sections   Roadway change mapping for Unchanged roadway sections
   * @param transferred_roadway_sections Roadway change mapping for Transferred roadway sections
   * @param terminated_roadway_sections  Roadway change mapping for Terminated roadway sections
   * @return RoadNumber, RoadPartNumber, Track grouping with `other, terminated´ labelings
   */
  def createTwoTrackOldAddressRoadParts(unChanged_roadway_sections: Iterable[(RoadwaySection, RoadwaySection)],transferred_roadway_sections: Iterable[(RoadwaySection, RoadwaySection)], terminated_roadway_sections: ChangeTableRows2): Map[RoadPart, Iterable[Seq[(RoadwaySection, String)]]] = {
    ((unChanged_roadway_sections ++ transferred_roadway_sections).map(roadwaySection => (roadwaySection._2, "other")).toSeq
     ++
     terminated_roadway_sections.adjustedSections.map(roadwaySection => (roadwaySection, "terminated")).toSeq)
    .filterNot(_._1.track == Track.Combined)
    .sortBy(_._1.addrMRange.start)
    .groupBy(p => (RoadPart(p._1.roadNumber, p._1.roadPartNumberStart)))
    .mapValues(p => p.groupBy(_._1.track).values)
  }

  /** Matches Terminated RoadwaySection to other RoadwaySection for Change table rows
   *
   * @param twoTrackOldAddressRoadParts RoadNumber, RoadPartNumber, Track grouping with `other, terminated´ labelings
   * @return Terminated two track RoadwaySections matching with Transferred part
   */
  def matchTerminatedRoadwaySections(twoTrackOldAddressRoadParts: Map[RoadPart, Iterable[Seq[(RoadwaySection, String)]]]): Map[Seq[RoadwaySection], Seq[RoadwaySection]] = {
    twoTrackOldAddressRoadParts.map(m => {
      val (longer_values, shorter_values) = if (m._2.head.size > m._2.last.size) (m._2.head, m._2.last) else if (m._2.head.size < m._2.last.size) (m._2.last, m._2.head) else (Seq.empty[(RoadwaySection, String)], Seq.empty[(RoadwaySection, String)])
      if (longer_values.nonEmpty && shorter_values.nonEmpty) {
        val matchedTerminatedTrackSections = matchTerminatedTracksOnRoadPart(longer_values, shorter_values)
        val t = matchTerminatedTracksOnRoadPart(matchedTerminatedTrackSections, longer_values)
        val FirstOfTwoTracks               = matchedTerminatedTrackSections.filter(_._2 == "terminated").map(_._1).filterNot(addr => {addr.addrMRange.start == addr.addrMRange.end}) // Todo trying to filter out yet undefined stuff?
        val otherOfTwoTracks               = t.filter(_._2 == "terminated").map(_._1).filterNot(addr => {addr.addrMRange.start == addr.addrMRange.end})                              // Todo trying to filter out yet undefined stuff?
        (FirstOfTwoTracks, otherOfTwoTracks)
      }
      else {
        ( m._2.head.filter(_._2 == "terminated").map(_._1).filterNot(addr => {addr.addrMRange.start == addr.addrMRange.end}),// Todo trying to filter out yet undefined stuff?
          m._2.last.filter(_._2 == "terminated").map(_._1).filterNot(addr => {addr.addrMRange.start == addr.addrMRange.end}))// Todo trying to filter out yet undefined stuff?
      }
    })
  }


  @tailrec
  private def matchTerminatedTracksOnRoadPart(
                                       longerTrackSeq         : Seq[(RoadwaySection, String)],
                                       shorterTrackSeq        : Seq[(RoadwaySection, String)],
                                       adjustedShorterTrackSeq: Seq[(RoadwaySection, String)] = Seq.empty[(RoadwaySection, String)]
                                     ): Seq[(RoadwaySection, String)] = {
    if (longerTrackSeq.isEmpty || shorterTrackSeq.isEmpty)
      adjustedShorterTrackSeq ++ shorterTrackSeq
    else { //TODO Refactor so that addrMRanges are in vals
      if (longerTrackSeq.head._1.addrMRange.start == shorterTrackSeq.head._1.addrMRange.start && longerTrackSeq.head._1.addrMRange.end < shorterTrackSeq.head._1.addrMRange.end && shorterTrackSeq.head._2 == "terminated") {
          val n = (shorterTrackSeq.head._1.copy(addrMRange = AddrMRange(longerTrackSeq.head._1.addrMRange.end, shorterTrackSeq.head._1.addrMRange.end)), "terminated")
          matchTerminatedTracksOnRoadPart(longerTrackSeq.tail, n +: shorterTrackSeq.tail, adjustedShorterTrackSeq ++ Seq((shorterTrackSeq.head._1.copy(addrMRange = AddrMRange(shorterTrackSeq.head._1.addrMRange.start, longerTrackSeq.head._1.addrMRange.end)), "terminated")))
      } else if (longerTrackSeq.head._1.addrMRange.start > shorterTrackSeq.head._1.addrMRange.start && longerTrackSeq.head._1.addrMRange.end == shorterTrackSeq.head._1.addrMRange.end && shorterTrackSeq.head._2 == "terminated") {
          val n = (shorterTrackSeq.head._1.copy(addrMRange = AddrMRange(shorterTrackSeq.head._1.addrMRange.start, longerTrackSeq.head._1.addrMRange.start)), "terminated")
          matchTerminatedTracksOnRoadPart(longerTrackSeq.tail, (shorterTrackSeq.head._1.copy(addrMRange = AddrMRange(longerTrackSeq.head._1.addrMRange.start,shorterTrackSeq.head._1.addrMRange.end)), shorterTrackSeq.head._2) +: shorterTrackSeq.tail, adjustedShorterTrackSeq ++ Seq(n))
      } else if (longerTrackSeq.head._1.addrMRange.isSameAs(shorterTrackSeq.head._1.addrMRange)) {
          matchTerminatedTracksOnRoadPart(longerTrackSeq.tail, shorterTrackSeq.tail, adjustedShorterTrackSeq ++ Seq(shorterTrackSeq.head))
      } else if (longerTrackSeq.head._1.addrMRange.start > shorterTrackSeq.head._1.addrMRange.end)
          matchTerminatedTracksOnRoadPart(longerTrackSeq, shorterTrackSeq.tail, adjustedShorterTrackSeq ++ Seq(shorterTrackSeq.head))
        else if (longerTrackSeq.head._1.addrMRange.start != shorterTrackSeq.head._1.addrMRange.start)
          matchTerminatedTracksOnRoadPart(longerTrackSeq.tail, shorterTrackSeq, adjustedShorterTrackSeq)
        else
          matchTerminatedTracksOnRoadPart(longerTrackSeq.tail, shorterTrackSeq.tail, adjustedShorterTrackSeq ++ Seq(shorterTrackSeq.head))
    }
  }
}

case class Delta(startDate: DateTime, newRoads: Seq[ProjectLink], terminations: Termination,
                 unChanged: Unchanged, transferred: Transferred, numbering: ReNumeration)


case class Termination(mapping: Seq[(RoadAddress, ProjectLink)])

case class Unchanged(mapping: Seq[(RoadAddress, ProjectLink)])

case class Transferred(mapping: Seq[(RoadAddress, ProjectLink)])

case class ReNumeration(mapping: Seq[(RoadAddress, ProjectLink)])
