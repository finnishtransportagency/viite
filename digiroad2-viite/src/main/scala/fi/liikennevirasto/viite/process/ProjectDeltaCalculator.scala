package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.RightSide
import fi.liikennevirasto.viite
import fi.liikennevirasto.viite.dao.{ProjectLink, _}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType
import org.joda.time.DateTime

/**
  * Calculate the effective change between the project and the current road address data
  */
object ProjectDeltaCalculator {

  val MaxAllowedMValueError = 0.001
  val checker = new ContinuityChecker(null) // We don't need road link service here
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)

//  def delta(project: Project): Delta = {
//    val projectLinksFetched  = projectLinkDAO.fetchProjectLinks(project.id)
//    val currentRoadAddresses = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllByRoadwayId(projectLinksFetched.map(_
//      .roadwayId)).map(_.roadwayNumber).toSet))
//    val currentAddresses     = currentRoadAddresses.map(ra => {ra.linearLocationId -> ra}).toMap
//    val newCreations         = findNewCreations(projectLinksFetched)
//    val numbering            = ReNumeration(findNumbering(projectLinksFetched, currentAddresses))
//    val terminations         = Termination(findTerminatedChanges(projectLinksFetched, currentAddresses, LinkStatus.Terminated))
//    val unChanged            = Unchanged(findChanges(projectLinksFetched, currentAddresses, LinkStatus.UnChanged))
//    val transferred          = Transferred(findChanges(projectLinksFetched, currentAddresses, LinkStatus.Transfer))
//
//    Delta(project.startDate, newCreations, terminations, unChanged, transferred, numbering)
//  }

//  private def adjustIfSplit(
//                             pl                   : ProjectLink,
//                             ra                   : Option[RoadAddress],
//                             connectedLink        : Option[ProjectLink] = None,
//                             referenceStartAddress: Option[RoadAddress] = None,
//                             referenceEndAddress  : Option[RoadAddress] = None
//                           ): Option[RoadAddress] = {
//    // Test if this link was a split case: if not, return original address, otherwise return a copy that is adjusted
//    if (!pl.isSplit) {
//      if (referenceStartAddress.nonEmpty) Some(ra.get.copy(startAddrMValue = referenceStartAddress.get.endAddrMValue))
//      else ra
//    } else {
//      ra.map(address => {
//        pl.status match {
//          case Transfer | UnChanged | Terminated =>
//            address.copy(startAddrMValue = if (referenceStartAddress.nonEmpty) referenceStartAddress.get.endAddrMValue else address.startAddrMValue,
//              endAddrMValue = if (referenceEndAddress.nonEmpty) referenceEndAddress.get.endAddrMValue else address.endAddrMValue)
//          case _ =>
//            address
//        }
//      })
//    }
//  }
//
//  private def findChanges(
//                           projectLinks    : Seq[ProjectLink],
//                           currentAddresses: Map[Long, RoadAddress],
//                           changedStatus   : LinkStatus
//                         ): Seq[(RoadAddress, ProjectLink)] = {
//
//    projectLinks.filter(_.status == changedStatus).toList.map { pl =>
//      val splittedLinkBefore = projectLinks.find(l => {
//        l.roadNumber == pl.roadNumber && l.roadPartNumber == pl.roadPartNumber && l.endAddrMValue == pl.startAddrMValue && l.isSplit && l.track == pl.track
//      })
//      val referenceOppositeStartAddress = if (splittedLinkBefore.nonEmpty) projectLinks.find(op => {
//        op.roadNumber == splittedLinkBefore.get.roadNumber && op.roadPartNumber == splittedLinkBefore.get.roadPartNumber && op.endAddrMValue == splittedLinkBefore
//          .get.endAddrMValue && op.track == Track.switch(splittedLinkBefore.get.track)
//      }) else None
//      val referenceOppositeEndAddress   = if (pl.isSplit) projectLinks
//        .find(op => {
//          op.roadNumber == pl.roadNumber && op.roadPartNumber == pl.roadPartNumber && op.endAddrMValue == pl.endAddrMValue && op.track == Track.switch(pl.track)
//        }) else None
//      adjustIfSplit(pl, currentAddresses.get(pl.linearLocationId),
//        referenceStartAddress = if (referenceOppositeStartAddress.nonEmpty) currentAddresses.get(referenceOppositeStartAddress.get.linearLocationId) else None,
//        referenceEndAddress = if (referenceOppositeEndAddress.nonEmpty) currentAddresses.get(referenceOppositeEndAddress.get.linearLocationId) else None).get -> pl
//    }
//  }
//  private def findTerminatedChanges(
//                           projectLinks    : Seq[ProjectLink],
//                           currentAddresses: Map[Long, RoadAddress],
//                           changedStatus   : LinkStatus
//                         ): Seq[(RoadAddress, ProjectLink)] = {
//
//    projectLinks.filter(_.status == changedStatus).toList.map { pl =>
//      currentAddresses.get(pl.linearLocationId).get -> pl
//    }
//  }
//
//
//  private def findNumbering(projectLinks: Seq[ProjectLink], currentAddresses: Map[Long, RoadAddress]): Seq[(RoadAddress, ProjectLink)] = {
//    projectLinks.filter(_.status == LinkStatus.Numbering).map(pl => currentAddresses(pl.linearLocationId) -> pl)
//  }
//
//  private def findNewCreations(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
//    projectLinks.filter(_.status == LinkStatus.New)
//  }

  private def combineProjectLinks(pl1: ProjectLink, pl2: ProjectLink, allNonTerminatedProjectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    val sameStatus = pl1.status == pl2.status
    val bothNew = pl1.status == LinkStatus.New && pl2.status == LinkStatus.New && pl1.track != Track.Combined && pl2.track != Track.Combined
    val matchAddr = pl1.endAddrMValue == pl2.startAddrMValue
    val matchContinuity = pl1.discontinuity == Discontinuity.Continuous
    val oppositePl1 = allNonTerminatedProjectLinks.filter( pl => pl.track != pl1.track && pl.track != Track.Combined && (pl.endAddrMValue == pl1.endAddrMValue || (pl.status != LinkStatus.New && pl1.status != LinkStatus.New && pl.originalEndAddrMValue == pl1.originalEndAddrMValue)))
    val oppositePl2 = allNonTerminatedProjectLinks.filter( pl => pl.track != pl1.track && pl.track != Track.Combined && (pl.startAddrMValue == pl1.endAddrMValue || (pl.status != LinkStatus.New && pl1.status != LinkStatus.New && pl.originalEndAddrMValue == pl1.originalEndAddrMValue)))
    val oppositeStatusChange = oppositePl1.nonEmpty && oppositePl2.nonEmpty && oppositePl1.last.status != oppositePl2.last.status
    val hasCalibrationPoint = ((pl1.status != LinkStatus.New && pl1.hasCalibrationPointAtEnd) && pl1.isEndCalibrationPointCreatedInProject && pl1.endCalibrationPointType != CalibrationPointType.JunctionPointCP) || (oppositePl1.nonEmpty && oppositePl1.head.hasCalibrationPointAtEnd && oppositePl1.head.isEndCalibrationPointCreatedInProject && oppositePl1.head.endCalibrationPointType != CalibrationPointType.JunctionPointCP)
    val trackNotUpdated = if (pl1.reversed && pl2.reversed && pl1.track != Track.Combined && pl2.track != Track.Combined) pl2.originalTrack != pl2.track else pl2.originalTrack == pl1.originalTrack && pl1.track == pl2.track
    val oppositeTrackNotUpdated = if (pl2.reversed) (oppositePl2.nonEmpty && (oppositePl2.head.originalTrack != oppositePl2.head.track || oppositePl2.head.status == LinkStatus.New)) || oppositePl2.isEmpty else (oppositePl2.nonEmpty && (oppositePl2.head.originalTrack == oppositePl2.head.track || oppositePl2.head.status == LinkStatus.New)) || oppositePl1.isEmpty
    val newLinks = Seq(pl1, pl2).forall(_.status == LinkStatus.New)
    val originalTrackContinuous = pl1.originalTrack == pl2.originalTrack
    val administrativeClassesMatch = pl1.administrativeClass == pl2.administrativeClass
    val originalAdministrativeClassContinuous = pl1.originalAdministrativeClass == pl2.originalAdministrativeClass

    val hasParallelLinkOnCalibrationPoint = hasCalibrationPoint && bothNew && matchContinuity && allNonTerminatedProjectLinks.exists(pl => {
      pl.roadNumber == pl1.roadNumber && pl.roadPartNumber == pl1.roadPartNumber && pl.status != LinkStatus.Terminated && pl.track != pl1.track && pl.track != Track.Combined && pl.endAddrMValue == pl1.endAddrMValue && pl.hasCalibrationPointAtEnd
    })
    val oppositeOriginalAddressLinks = allNonTerminatedProjectLinks.filter(pl => {
      pl.track != pl1.track && pl.track != Track.Combined && pl1.track != Track.Combined && (pl.originalEndAddrMValue == pl1.originalEndAddrMValue || pl.originalStartAddrMValue == pl1.originalEndAddrMValue)
    })

    val oppositeStatusNotChanged = if (oppositeOriginalAddressLinks.size == 2) oppositeOriginalAddressLinks.head.status == oppositeOriginalAddressLinks.last.status else true

    if (!oppositeStatusChange && (matchAddr && sameStatus && matchContinuity && administrativeClassesMatch && trackNotUpdated && originalTrackContinuous &&
      (newLinks || oppositeTrackNotUpdated) && !(hasCalibrationPoint || hasParallelLinkOnCalibrationPoint)) &&
        administrativeClassesMatch && oppositeStatusNotChanged && originalAdministrativeClassContinuous) {
      val pl1OriginalAddressSet =
        pl1.copy(originalEndAddrMValue = Math.max(pl1.originalEndAddrMValue, pl2.originalEndAddrMValue),
          originalStartAddrMValue = Math.min(pl1.originalStartAddrMValue, pl2.originalStartAddrMValue), roadwayId = pl2.roadwayId)

      Seq(
        pl1OriginalAddressSet.copy(discontinuity = pl2.discontinuity, endAddrMValue = pl2.endAddrMValue,
          calibrationPointTypes = (pl1.startCalibrationPointType, pl2.endCalibrationPointType),
          originalCalibrationPointTypes = (pl1.originalCalibrationPointTypes._1, pl2.originalCalibrationPointTypes._2))
          )
    }
    else {
      Seq(pl2, pl1)
    }
  }
  private def combineTerminated(pl1: ProjectLink, pl2: ProjectLink): Seq[ProjectLink] = {
    if (pl1.hasCalibrationPointAtEnd && pl1.roadwayNumber != pl1.roadwayNumber)
      Seq(pl2, pl1)
    else
      Seq(pl1.copy(discontinuity = pl2.discontinuity, endAddrMValue = pl2.endAddrMValue, calibrationPointTypes = pl2.calibrationPointTypes))
  }

  private def combineTwo[R <: BaseRoadAddress, P <: BaseRoadAddress](tr1: (R, P), tr2: (R, P), allNonTerminatedProjectLinks: Seq[ProjectLink]): Seq[(R, P)] = {
    val (ra1, pl1) = (tr1._1, tr1._2.asInstanceOf[ProjectLink])
    val (ra2, pl2) = (tr2._1, tr2._2.asInstanceOf[ProjectLink])
    val matchAddr = pl1.endAddrMValue == pl2.startAddrMValue
    val matchContinuity = pl1.discontinuity == Discontinuity.Continuous
    val oppositePl = allNonTerminatedProjectLinks.filter( pl => pl.track != pl1.track && pl.endAddrMValue == pl1.endAddrMValue).filter(_.status != LinkStatus.Terminated)
    val hasCalibrationPoint = ((pl1.track == Track.Combined && pl1.hasCalibrationPointAtEnd) && pl1.hasCalibrationPointCreatedInProject) || (oppositePl.nonEmpty && oppositePl.head.hasCalibrationPointAtEnd && pl1.hasCalibrationPointAtEnd) // Opposite side has user cp
    if (matchAddr && matchContinuity && !hasCalibrationPoint &&
        ra1.administrativeClass == ra2.administrativeClass && pl1.administrativeClass == pl2.administrativeClass && pl1.reversed == pl2.reversed) {
      Seq((
            ra1.asInstanceOf[RoadAddress].copy(discontinuity = ra2.discontinuity, endAddrMValue = ra2.endAddrMValue).asInstanceOf[R],
            pl1.copy(discontinuity = pl2.discontinuity, endAddrMValue = pl2.endAddrMValue, calibrationPointTypes = (pl1.startCalibrationPointType, pl2.asInstanceOf[ProjectLink].endCalibrationPointType), originalCalibrationPointTypes = (pl1.originalCalibrationPointTypes._1, pl2.asInstanceOf[ProjectLink].originalCalibrationPointTypes._2)).asInstanceOf[P]
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
          pl.status != LinkStatus.Terminated &&
            pl.track != r1.track &&
            pl.track != Track.Combined &&
            pl.endAddrMValue == r1.endAddrMValue &&
            pl.hasCalibrationPointAtEnd)
        parallelLastOnCalibrationPoint.nonEmpty
      } else
        false
  val hasUdcp = r1.calibrationPointTypes._2 == CalibrationPointDAO.CalibrationPointType.UserDefinedCP && r1.status == LinkStatus.New && r2.status == LinkStatus.New

    val openBasedOnSource = hasCalibrationPoint && r1.hasCalibrationPointCreatedInProject
    if (r1.endAddrMValue == r2.startAddrMValue)
      r1.status match {
        case LinkStatus.Terminated =>
          if (hasCalibrationPoint && r1.roadwayNumber != r2.roadwayNumber)
            Seq(r2, r1)
          else if (openBasedOnSource)
            Seq(r2, r1)
          else
            Seq(r1.copy(discontinuity = r2.discontinuity, endAddrMValue = r2.endAddrMValue, calibrationPointTypes = r2.calibrationPointTypes))
        case LinkStatus.UnChanged =>
          if (!openBasedOnSource)
            Seq(r2, r1)
          else
            Seq(r1.copy(discontinuity = r2.discontinuity, endAddrMValue = r2.endAddrMValue, calibrationPointTypes = r2.calibrationPointTypes))
        case LinkStatus.New =>
          if (hasUdcp ||( !hasParallelLinkOnCalibrationPoint && !hasCalibrationPoint)) { // && !r1.isSplit
            Seq(r1.copy(discontinuity = r2.discontinuity, endAddrMValue = r2.endAddrMValue, calibrationPointTypes = r2.calibrationPointTypes, connectedLinkId = r2.connectedLinkId))
          } else {
            Seq(r2, r1)
          }
        case _ => {
          Seq(r2, r1)
        }

      }
    else
      Seq(r2, r1)
  }
  private def combineWithProjectLinks(projectLinkSeq: Seq[ProjectLink], result: Seq[ProjectLink] = Seq(), allNonTerminatedProjectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    if (projectLinkSeq.isEmpty)
      result.reverse
    else if (result.isEmpty)
      combineWithProjectLinks(projectLinkSeq.tail, Seq(projectLinkSeq.head), allNonTerminatedProjectLinks)
    else
      combineWithProjectLinks(projectLinkSeq.tail, combineProjectLinks(result.head, projectLinkSeq.head, allNonTerminatedProjectLinks) ++ result.tail, allNonTerminatedProjectLinks)
  }

  private def combineTerminatedLinks(projectLinkSeq: Seq[ProjectLink], result: Seq[ProjectLink] = Seq(), allNonTerminatedProjectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    if (projectLinkSeq.isEmpty)
      result.reverse
    else if (result.isEmpty)
      combineTerminatedLinks(projectLinkSeq.tail, Seq(projectLinkSeq.head), allNonTerminatedProjectLinks: Seq[ProjectLink])
    else
      combineTerminatedLinks(projectLinkSeq.tail, combineTerminated(result.head, projectLinkSeq.head) ++ result.tail, allNonTerminatedProjectLinks: Seq[ProjectLink])
  }

  private def combine(projectLinkSeq: Seq[ProjectLink], result: Seq[ProjectLink] = Seq(), allNonTerminatedProjectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    if (projectLinkSeq.isEmpty)
      result.reverse
    else if (result.isEmpty)
      combine(projectLinkSeq.tail, Seq(projectLinkSeq.head), allNonTerminatedProjectLinks: Seq[ProjectLink])
    else
      combine(projectLinkSeq.tail, combineTwo(result.head, projectLinkSeq.head, allNonTerminatedProjectLinks: Seq[ProjectLink]) ++ result.tail, allNonTerminatedProjectLinks: Seq[ProjectLink])
  }

  def getClosestOpposite[T <: BaseRoadAddress](startAddrMValue: Long, oppositeTracks: Seq[T]): Option[T] = {
    oppositeTracks match {
      case Nil => None
      case seq => Option(seq.minBy(v => math.abs(v.startAddrMValue - startAddrMValue)))
    }
  }

  private def combinePair[T <: BaseRoadAddress, R <: ProjectLink](combinedSeq: Seq[(T, R)], allNonTerminatedProjectLinks: Seq[ProjectLink], result: Seq[(T, R)] = Seq()): Seq[(T, R)] = {
    if (combinedSeq.isEmpty)
      result.reverse
    else if (result.isEmpty)
      combinePair(combinedSeq.tail, allNonTerminatedProjectLinks, Seq(combinedSeq.head))
    else
      combinePair(combinedSeq.tail, allNonTerminatedProjectLinks, combineTwo(result.head, combinedSeq.head, allNonTerminatedProjectLinks) ++ result.tail)
  }

  def adjustAddressValues(addressMValues: Long, mValue: Long, track: Track): Long = {
    val fusedValues = addressMValues % 2 match {
      case 0 => addressMValues / 2
      case _ =>
        if (track == RightSide ^ (mValue * 2 < addressMValues)) {
          (addressMValues + 1) / 2
        } else {
          addressMValues / 2
        }
    }
    fusedValues.toLong
  }

  /**
    * Check if the matches (opposite side of right or left track) road address measures fit on the target road address sections
    *
    * @param target   The target road address sections
    * @param matchers The matcher road address sections
    * @return
    */
  def matchesFitOnTarget(target: Seq[RoadwaySection], matchers: Seq[RoadwaySection]): Boolean = {
    val (targetStartMAddress, targetEndMAddress) = (target.map(_.startMAddr).min, target.map(_.endMAddr).max)
    val (matcherStartMAddress, matcherEndMAddress) = (matchers.map(_.startMAddr).min, matchers.map(_.endMAddr).max)
    (targetStartMAddress <= matcherStartMAddress && targetEndMAddress >= matcherStartMAddress) || (targetStartMAddress <= matcherEndMAddress && targetEndMAddress >= matcherEndMAddress) ||
      (matcherStartMAddress <= targetStartMAddress && matcherEndMAddress >= targetStartMAddress) || (matcherStartMAddress <= targetEndMAddress && matcherEndMAddress >= targetEndMAddress)
  }

  def partition[T <: BaseRoadAddress](projectLinks: Seq[ProjectLink], allNonTerminatedProjectLinks: Seq[ProjectLink]): Seq[RoadwaySection] = {
    val grouped = projectLinks.groupBy(ra => (ra.roadNumber, ra.roadPartNumber, ra.track, ra.administrativeClass))
      .mapValues(v => combine(v.sortBy(_.startAddrMValue), Seq(), allNonTerminatedProjectLinks.filter(pl => {
        pl.roadNumber == v.head.roadNumber && pl.roadPartNumber == v.head.roadPartNumber}))).values.flatten.map(ra =>
      RoadwaySection(ra.roadNumber, ra.roadPartNumber, ra.roadPartNumber,
        ra.track, ra.startAddrMValue, ra.endAddrMValue, ra.discontinuity, ra.administrativeClass, ra.ely, ra.reversed, ra.roadwayNumber, Seq())
    ).toSeq

    val paired = grouped.groupBy(section => (section.roadNumber, section.roadPartNumberStart, section.track, section.roadwayNumber))

    val result = paired.flatMap { case (key, targetToMap) =>
      val matches = matchingTracks(paired, key)
      val target = targetToMap.map(t => t.copy(projectLinks = projectLinks.filter(link => link.roadNumber == t.roadNumber && link.roadPartNumber == t.roadPartNumberEnd && link.track == t.track && link.administrativeClass == t.administrativeClass && link.ely == t.ely &&
        link.startAddrMValue >= t.startMAddr && link.endAddrMValue <= t.endMAddr)))
      if (matches.nonEmpty && matches.get.lengthCompare(target.length) == 0 && matchesFitOnTarget(target, matches.get)) {
        adjustTrack((target.sortBy(_.startMAddr), matches.get.sortBy(_.startMAddr)))
      } else
        target
    }.toSeq
    result
  }

  def partitionTerminatedWithProjectLinks[T <: BaseRoadAddress](projectLinks: Seq[ProjectLink], allNonTerminatedProjectLinks: Seq[ProjectLink]): ChangeTableRows3 = {

    val groupedToSections = projectLinks.groupBy(ra => (ra.roadNumber, ra.roadPartNumber, ra.track, ra.administrativeClass, ra.roadwayNumber))
    val sectioned    = groupedToSections
                                .mapValues(v => combineTerminatedLinks(v.sortBy(_.startAddrMValue), Seq(), allNonTerminatedProjectLinks.filter(pl => {
                                  pl.roadNumber == v.head.roadNumber && pl.roadPartNumber == v.head.roadPartNumber && pl.administrativeClass == v.head.administrativeClass}))).values.flatten.map(ra =>
      RoadwaySection(ra.roadNumber, ra.roadPartNumber, ra.roadPartNumber,
        ra.track, ra.originalStartAddrMValue, ra.originalEndAddrMValue, ra.discontinuity, ra.administrativeClass, ra.ely, ra.reversed, ra.roadwayNumber, Seq())
    ).toSeq
    val sections     = sectioned.map(sect => {
      val (src) = sect
      val target                    = src.copy(projectLinks = projectLinks.filter(link => {
        link.roadNumber == src.roadNumber &&
        link.roadPartNumber == src.roadPartNumberEnd && link.track == src.track && link.ely == src.ely &&
        link.startAddrMValue >= src.startMAddr && link.endAddrMValue <= src.endMAddr
      }))

      (src,target)
    }
    )

    ChangeTableRows3(terminatedSections = sections.map(_._2))
  }

  def createAverageValuesForTransferedStarts(starts: Map[(Long, Long), Seq[ProjectLink]]): Seq[ProjectLink] = {
    starts.mapValues(pls => {
      if (pls.size == 2 && pls.forall(_.track != Track.Combined) && (pls.head.status == LinkStatus.Terminated && pls.last.status == LinkStatus.Terminated)) {
        val avg = Math.round(pls.map(_.originalEndAddrMValue).sum * 0.5)
        pls.map(_.copy(endAddrMValue = avg, originalEndAddrMValue = avg))
      }
      else
        if (pls.size == 2 && pls.forall(_.track != Track.Combined) && (pls.head.originalStartAddrMValue != 0 || pls.last.originalStartAddrMValue != 0L)) {
          val avg = Math.round(pls.map(_.originalStartAddrMValue).sum * 0.5)
          pls.map(_.copy(originalStartAddrMValue = avg))
        }
         else
      pls
    }).values.flatten.toSeq
  }

  private def sortAndTakeTerminated(pls: Seq[ProjectLink]): Seq[ProjectLink] = {
    if (pls.isEmpty) pls
    else
      Seq(pls.sortBy(_.originalStartAddrMValue).takeWhile {_.status == LinkStatus.Terminated}.last)
  }

  /** Create change table rows
   *
   * @param projectLinks    ProjectLinks to process for change table rows
   * @param allProjectLinks All ProjectLinks in project for additional information
   * @return Changetable rows
   */
  def generateChangeTableRowsFromProjectLinks(projectLinks: Seq[ProjectLink], allProjectLinks: Seq[ProjectLink]): ChangeTableRows2 = {
    val startLinks = projectLinks.filter(pl => pl.startAddrMValue == 0).groupBy(pl => {
      (pl.roadNumber, pl.roadPartNumber)})
    val terminatedForAveraging =
      allProjectLinks.filter(pl => {
        pl.track != Track.Combined
      }).groupBy(pl => {
        (pl.roadNumber, pl.roadPartNumber)
      }).mapValues(pls => {
        if (pls.exists(pl => pl.status == LinkStatus.Terminated && pl.originalStartAddrMValue == 0)) {
        val (r, l) = pls.partition(_.track == Track.RightSide)
        Seq(sortAndTakeTerminated(r),sortAndTakeTerminated(l)).flatten
      } else Seq()
      })


    val averagedStarts = if (projectLinks.forall(_.status == LinkStatus.Terminated)) createAverageValuesForTransferedStarts(terminatedForAveraging) else createAverageValuesForTransferedStarts(startLinks)
    val averagedTerminated = createAverageValuesForTransferedStarts(terminatedForAveraging)

    def groupToSections(pl: ProjectLink): (Long, Long, Track, Boolean) = (pl.originalRoadNumber, pl.originalRoadPartNumber, pl.originalTrack, pl.reversed)
    val grouped =
      if (allProjectLinks.exists(pl => pl.status == LinkStatus.Terminated && pl.originalStartAddrMValue == 0))
        (averagedStarts ++ projectLinks.filterNot(pl => averagedStarts.map(_.id).contains(pl.id))).sortBy(pl => (pl.originalRoadPartNumber, pl.originalStartAddrMValue)).groupBy(groupToSections)
      else
        projectLinks.sortBy(pl => (pl.roadPartNumber, pl.startAddrMValue)).groupBy(groupToSections)

    val allWithAveraged = if (allProjectLinks.exists(pl => pl.status == LinkStatus.Terminated && pl.originalStartAddrMValue == 0))
      (averagedStarts ++ averagedTerminated ++ allProjectLinks.filterNot(pl => averagedStarts.map(_.id).contains(pl.id) || averagedTerminated.map(_.id).contains(pl.id)))
    else allProjectLinks

    val sectioned = grouped.mapValues((pls: Seq[ProjectLink]) => {

      combineWithProjectLinks(pls, Seq(), allWithAveraged.filter(pl => {
        pl.roadNumber == pls.head.roadNumber && pl.roadPartNumber == pls.head.roadPartNumber
      }))
    }).values.flatten.map(pl => {
      RoadwaySection(pl.originalRoadNumber, pl.originalRoadPartNumber, pl.originalRoadPartNumber, pl.originalTrack, pl.originalStartAddrMValue, pl.originalEndAddrMValue, pl.originalDiscontinuity, pl.originalAdministrativeClass, pl.originalEly, pl.reversed, pl.roadwayNumber, Seq()) -> RoadwaySection(pl.roadNumber, pl.roadPartNumber, pl.roadPartNumber, pl.track, pl.startAddrMValue, pl.endAddrMValue, pl.discontinuity, pl.administrativeClass, pl.ely, pl.reversed, pl.roadwayNumber, Seq())
    }).toSeq

    val sections = sectioned.map(sect => {
      val (src, targetToMap) = sect
      val target                    = targetToMap.copy(projectLinks = projectLinks.filter(link => {
          link.roadNumber == targetToMap.roadNumber &&
          link.roadPartNumber == targetToMap.roadPartNumberEnd && link.track == targetToMap.track && link.ely == targetToMap.ely &&
          link.startAddrMValue >= targetToMap.startMAddr && link.endAddrMValue <= targetToMap.endMAddr
        }))

      (src,target)
    }
    )

    ChangeTableRows2(adjustedSections = sections.map(_._2), originalSections = sections.map(_._1))
  }

  private def matchingTracks(map: Map[(Long, Long, Track, Long), Seq[RoadwaySection]],
                             key: (Long, Long, Track, Long)): Option[Seq[RoadwaySection]] = {
    map.get((key._1, key._2, Track.switch(key._3), key._4))
  }

  private def adjustTrack(group: (Seq[RoadwaySection], Seq[RoadwaySection])): Seq[RoadwaySection] = {
    group._1.zip(group._2).map {
      case (e1, e2) =>
        e1.copy(startMAddr = adjustAddressValues(e1.startMAddr + e2.startMAddr, e1.startMAddr, e1.track),
          endMAddr = adjustAddressValues(e1.endMAddr + e2.endMAddr, e1.endMAddr, e1.track))
    }
  }

  def partition(transfers: Seq[(RoadAddress, ProjectLink)], allNonTerminatedProjectLinks: Seq[ProjectLink] = Seq()): ChangeTableRows = {
    def toRoadAddressSection(o: Seq[BaseRoadAddress]) = {
      o.map(ra =>
        RoadwaySection(ra.roadNumber, ra.roadPartNumber, ra.roadPartNumber,
          ra.track, ra.startAddrMValue, ra.endAddrMValue, ra.discontinuity, ra.administrativeClass, ra.ely, ra.reversed, ra.roadwayNumber, Seq()))
    }

      val trans_ =  transfers.groupBy(x => (x._1.roadNumber, x._1.roadPartNumber, x._1.track, x._2.roadNumber, x._2.roadPartNumber, x._2.track, x._1.id))
      val trans_mapped = trans_.mapValues(v => {
        // If roadpart was reversed, process old roadaddresses in reverse direction.
        val trans_directed = if (v.exists(_._2.reversed)) v.map(_._1).reverse.zip(v.map(_._2)) else v
        combinePair(trans_directed.sortBy(_._2.startAddrMValue), allNonTerminatedProjectLinks.filter(pl => {
          pl.roadNumber == v.head._2.roadNumber && pl.roadPartNumber == v.head._2.roadPartNumber
        }))
      })

        val sectioned = trans_mapped.mapValues(v => {
        val (from, to) = v.unzip
        toRoadAddressSection(from) -> toRoadAddressSection(to)
      })

    //adjusted the end of sources
    val links    = transfers.map(_._2)
    val sections = sectioned.map(sect => {
      val (_, (srcToMap, targetToMap)) = sect
      val target                    = targetToMap.map(t => {
        t.copy(projectLinks = links.filter(link => {
          link.roadwayNumber == t.roadwayNumber && link.roadNumber == t.roadNumber &&
          link.roadPartNumber == t.roadPartNumberEnd && link.track == t.track && link.ely == t.ely &&
          link.startAddrMValue >= t.startMAddr && link.endAddrMValue <= t.endMAddr
        }))
      })
      val src                    = srcToMap.map(s => {
        s.copy(projectLinks = links.filter(link => {
          link.roadwayNumber == s.roadwayNumber && link.roadNumber == s.roadNumber &&
          link.roadPartNumber == s.roadPartNumberStart && link.track == s.track && link.ely == s.ely &&
          link.originalStartAddrMValue >= s.startMAddr && link.originalEndAddrMValue <= s.endMAddr
        }))
      })
      src.zip(target)
    }
    ).flatten


    //  adjusted the end of sources
    val adjustedEndSourceSections = sections.map { case (src, target) =>
      val possibleExistingSameEndAddrMValue = sections.find {
        case (_, t) => t.roadNumber == target.roadNumber && t.roadPartNumberStart == target.roadPartNumberStart && t.endMAddr == target.endMAddr && t.track == Track.switch(target.track)
      }
      if (possibleExistingSameEndAddrMValue.nonEmpty) {
        val warningMessage = if (Math.abs(src.endMAddr - possibleExistingSameEndAddrMValue.head._1.endMAddr) > viite.MaxDistanceBetweenTracks)
          Some(viite.MaxDistanceBetweenTracksWarningMessage)
        else
          None
        ((src.copy(endMAddr = adjustAddressValues(src.endMAddr + possibleExistingSameEndAddrMValue.head._1.endMAddr, src.endMAddr, src.track)), target), warningMessage)
      } else {
        ((src, target), None)
      }
    }

    ChangeTableRows(adjustedSections = adjustedEndSourceSections, originalSections = sections)
  }

  def adjustStartSourceAddressValues(sectionsAfterAdjust: Iterable[((RoadwaySection, RoadwaySection), Option[String])], sections: Iterable[(RoadwaySection, RoadwaySection)]): (Iterable[(RoadwaySection, RoadwaySection)], Option[String]) = {
    //adjusted the start of sources
    val adjustedStartSourceSections = sectionsAfterAdjust.map { case ((src, target), _) =>{
      val possibleExistingSameStartAddrMValue      = sections.find(s => s._1.roadNumber == src.roadNumber && s._1.roadPartNumberStart == src.roadPartNumberStart && s._1.roadwayNumber == src.roadwayNumber && s._1.endMAddr == src.startMAddr)
      if (possibleExistingSameStartAddrMValue.nonEmpty) {
        val oppositePairingTrack = sections.find(s => s._1.roadNumber == possibleExistingSameStartAddrMValue.get._1.roadNumber && s._1.roadPartNumberStart == possibleExistingSameStartAddrMValue.get._1.roadPartNumberStart && s._2.endMAddr == possibleExistingSameStartAddrMValue.get._2.endMAddr && s._1.track != possibleExistingSameStartAddrMValue.get._1.track)
        if (oppositePairingTrack.nonEmpty) {
          val warningMessage = if (Math.abs(possibleExistingSameStartAddrMValue.head._1.endMAddr - oppositePairingTrack.head._1.endMAddr) > viite.MaxDistanceBetweenTracks)
            Some(viite.MaxDistanceBetweenTracksWarningMessage)
          else
            None
          ((src.copy(startMAddr = adjustAddressValues(possibleExistingSameStartAddrMValue.head._1.endMAddr + oppositePairingTrack.head._1.endMAddr, possibleExistingSameStartAddrMValue.head._1.endMAddr, src.track)), target), warningMessage)
        } else {
          ((src, target), None)
        }
      } else {
        ((src, target), None)
      }
    }
    }
    val warning = sectionsAfterAdjust.flatMap(_._2).toSeq ++ adjustedStartSourceSections.flatMap(_._2).toSeq
    (adjustedStartSourceSections.map(_._1).toMap, if (warning.nonEmpty) Option(warning.head) else None)
  }

  def buildTwoTrackOldAddressRoadParts(unchanged: ChangeTableRows, transferred: ChangeTableRows, numbering: ChangeTableRows, terminated: ChangeTableRows): Map[(Long, Long), Iterable[Seq[(RoadwaySection, String)]]] = {
    (unchanged.originalSections.map(roadwaySection => {
      (roadwaySection._1, "unchanged")
    }).toSeq ++ transferred.originalSections.map(roadwaySection => {
      (roadwaySection._1, "transferred")
    }).toSeq ++ numbering.originalSections.map(roadwaySection => {
      (roadwaySection._1, "numbering")
    }).toSeq ++ terminated.originalSections.map(roadwaySection => {
      (roadwaySection._1, "terminated")
    }).toSeq).filterNot(_._1.track == Track.Combined).sortBy(_._1.startMAddr).groupBy(p => {
      (p._1.roadNumber, p._1.roadPartNumberStart)
    }).map(p => {
      p._1 -> p._2.groupBy(_._1.track).values
    })
  }

  /** Create grouping for old address parts.
   * Utility function for two track terminated change table address matching.
   *
   * @param unChanged_roadway_sections   Roadway change mapping for Unchanged roadway sections
   * @param transferred_roadway_sections Roadway change mapping for Transferred roadway sections
   * @param terminated_roadway_sections  Roadway change mapping for Terminated roadway sections
   * @return RoadNumber, RoadPartNumber, Track grouping with `other, terminated´ labelings
   */
  def createTwoTrackOldAddressRoadParts(unChanged_roadway_sections: Iterable[(RoadwaySection, RoadwaySection)],transferred_roadway_sections: Iterable[(RoadwaySection, RoadwaySection)], terminated_roadway_sections: ChangeTableRows2): Map[(Long, Long), Iterable[Seq[(RoadwaySection, String)]]] = {
    ((unChanged_roadway_sections ++ transferred_roadway_sections).map(roadwaySection => (roadwaySection._2, "other")).toSeq
     ++
     terminated_roadway_sections.adjustedSections.map(roadwaySection => (roadwaySection, "terminated")).toSeq)
    .filterNot(_._1.track == Track.Combined)
    .sortBy(_._1.startMAddr)
    .groupBy(p => (p._1.roadNumber, p._1.roadPartNumberStart))
    .mapValues(p => p.groupBy(_._1.track).values)
  }

  /** Matches Terminated RoadwaySection to other RoadwaySection for Change table rows
   *
   * @param twoTrackOldAddressRoadParts RoadNumber, RoadPartNumber, Track grouping with `other, terminated´ labelings
   * @return Terminated two track RoadwaySections matching with Transferred part
   */
  def matchTerminatedRoadwaySections(twoTrackOldAddressRoadParts: Map[(Long, Long), Iterable[Seq[(RoadwaySection, String)]]]): Map[Seq[RoadwaySection], Seq[RoadwaySection]] = {
    twoTrackOldAddressRoadParts.map(m => {
      val (longer_values, shorter_values) = if (m._2.head.size > m._2.last.size) (m._2.head, m._2.last) else if (m._2.head.size < m._2.last.size) (m._2.last, m._2.head) else (Seq.empty[(RoadwaySection, String)], Seq.empty[(RoadwaySection, String)])
      if (longer_values.nonEmpty && shorter_values.nonEmpty) {
        val matchedTerminatedTrackSections = matchTerminatedTracksOnRoadPart(longer_values, shorter_values)
        val t = matchTerminatedTracksOnRoadPart(matchedTerminatedTrackSections, longer_values)
        val FirstOfTwoTracks               = matchedTerminatedTrackSections.filter(_._2 == "terminated").map(_._1).filterNot(addr => {addr.startMAddr == addr.endMAddr})
        val otherOfTwoTracks               = t.filter(_._2 == "terminated").map(_._1).filterNot(addr => {addr.startMAddr == addr.endMAddr})
        (FirstOfTwoTracks, otherOfTwoTracks)
      }
      else {
        (m._2.head.filter(_._2 == "terminated").map(_._1).filterNot(addr => {addr.startMAddr == addr.endMAddr}),m._2.last.filter(_._2 == "terminated").map(_._1).filterNot(addr => {addr.startMAddr == addr.endMAddr}))
      }
    })
  }


  private def matchTerminatedTracksOnRoadPart(
                                       longerTrackSeq         : Seq[(RoadwaySection, String)],
                                       shorterTrackSeq        : Seq[(RoadwaySection, String)],
                                       adjustedShorterTrackSeq: Seq[(RoadwaySection, String)] = Seq.empty[(RoadwaySection, String)]
                                     ): Seq[(RoadwaySection, String)] = {
    if (longerTrackSeq.isEmpty || shorterTrackSeq.isEmpty) adjustedShorterTrackSeq ++ shorterTrackSeq else {
      if (longerTrackSeq.head._1.startMAddr == shorterTrackSeq.head._1.startMAddr && longerTrackSeq.head._1.endMAddr < shorterTrackSeq.head._1.endMAddr && shorterTrackSeq.head._2 == "terminated") {
          val n = (shorterTrackSeq.head._1.copy(startMAddr = longerTrackSeq.head._1.endMAddr), "terminated")
          matchTerminatedTracksOnRoadPart(longerTrackSeq.tail, n +: shorterTrackSeq.tail, adjustedShorterTrackSeq ++ Seq((shorterTrackSeq.head._1.copy(endMAddr = longerTrackSeq.head._1.endMAddr), "terminated")))
      } else if (longerTrackSeq.head._1.startMAddr > shorterTrackSeq.head._1.startMAddr && longerTrackSeq.head._1.endMAddr == shorterTrackSeq.head._1.endMAddr && shorterTrackSeq.head._2 == "terminated") {
          val n = (shorterTrackSeq.head._1.copy(endMAddr = longerTrackSeq.head._1.startMAddr), "terminated")
          matchTerminatedTracksOnRoadPart(longerTrackSeq.tail, (shorterTrackSeq.head._1.copy(startMAddr = longerTrackSeq.head._1.startMAddr), shorterTrackSeq.head._2) +: shorterTrackSeq.tail, adjustedShorterTrackSeq ++ Seq(n))
      } else if (longerTrackSeq.head._1.startMAddr == shorterTrackSeq.head._1.startMAddr && longerTrackSeq.head._1.endMAddr == shorterTrackSeq.head._1.endMAddr) {
          matchTerminatedTracksOnRoadPart(longerTrackSeq.tail, shorterTrackSeq.tail, adjustedShorterTrackSeq ++ Seq(shorterTrackSeq.head))
      } else if (longerTrackSeq.head._1.startMAddr > shorterTrackSeq.head._1.endMAddr)
          matchTerminatedTracksOnRoadPart(longerTrackSeq, shorterTrackSeq.tail, adjustedShorterTrackSeq ++ Seq(shorterTrackSeq.head))
        else if (longerTrackSeq.head._1.startMAddr != shorterTrackSeq.head._1.startMAddr)
          matchTerminatedTracksOnRoadPart(longerTrackSeq.tail, shorterTrackSeq, adjustedShorterTrackSeq)
        else
          matchTerminatedTracksOnRoadPart(longerTrackSeq.tail, shorterTrackSeq.tail, adjustedShorterTrackSeq ++ Seq(shorterTrackSeq.head))
    }
  }
}

case class Delta(startDate: DateTime, newRoads: Seq[ProjectLink], terminations: Termination,
                 unChanged: Unchanged, transferred: Transferred, numbering: ReNumeration)

case class RoadPart(roadNumber: Long, roadPartNumber: Long)

case class Termination(mapping: Seq[(RoadAddress, ProjectLink)])

case class Unchanged(mapping: Seq[(RoadAddress, ProjectLink)])

case class Transferred(mapping: Seq[(RoadAddress, ProjectLink)])

case class ReNumeration(mapping: Seq[(RoadAddress, ProjectLink)])
