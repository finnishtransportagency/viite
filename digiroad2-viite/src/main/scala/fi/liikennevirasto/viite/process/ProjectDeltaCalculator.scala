package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.RightSide
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{NoCP, RoadAddressCP}
import fi.liikennevirasto.viite.dao.LinkStatus._
import fi.liikennevirasto.viite.dao.{ProjectLink, _}
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import fi.liikennevirasto.{GeometryUtils, viite}
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

  def delta(project: Project): Delta = {
    val projectLinksFetched = projectLinkDAO.fetchProjectLinks(project.id)
    val currentRoadAddresses = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllByRoadwayId(projectLinksFetched.map(_.roadwayId)).map(_.roadwayNumber).toSet))
    val currentAddresses = currentRoadAddresses.map(ra => ra.linearLocationId -> ra).toMap
    val newCreations = findNewCreations(projectLinksFetched)
    val terminations = Termination(findTerminations(projectLinksFetched, currentAddresses))
    val unChanged = Unchanged(findUnChanged(projectLinksFetched, currentAddresses))
    val transferred = Transferred(findTransfers(projectLinksFetched, currentAddresses))
    val numbering = ReNumeration(findNumbering(projectLinksFetched, currentAddresses))

    Delta(project.startDate, newCreations, terminations, unChanged, transferred, numbering)
  }

  private def adjustIfSplit(pl: ProjectLink, ra: Option[RoadAddress], connectedLink: Option[ProjectLink] = None): Option[RoadAddress] = {
    // Test if this link was a split case: if not, return original address, otherwise return a copy that is adjusted
    if (!pl.isSplit) {
      ra
    } else {
      ra.map(address => {
        val geom = GeometryUtils.truncateGeometry2D(address.geometry, pl.startMValue, pl.endMValue)
        pl.status match {
          case Transfer =>
            val termAddress = connectedLink.map(l => (l.startAddrMValue, l.endAddrMValue))
            termAddress.map { case (start, end) =>
              address.copy(startAddrMValue = if (start == address.startAddrMValue) end else address.startAddrMValue,
                endAddrMValue = if (end == address.endAddrMValue) start else address.endAddrMValue,
                startMValue = pl.startMValue,
                endMValue = pl.endMValue,
                geometry = geom)
            }.getOrElse(address)
          case Terminated =>
            address.copy(startAddrMValue = pl.startAddrMValue, endAddrMValue = pl.endAddrMValue, startMValue = pl.startMValue,
              endMValue = pl.endMValue, geometry = geom)
          case UnChanged =>
            address.copy(startAddrMValue = pl.startAddrMValue, endAddrMValue = pl.endAddrMValue, startMValue = pl.startMValue,
              endMValue = pl.endMValue, geometry = geom)
          case _ =>
            address
        }
      })
    }
  }

  private def findTerminations(projectLinks: Seq[ProjectLink], currentAddresses: Map[Long, RoadAddress]): Seq[(RoadAddress, ProjectLink)] = {
    val terminations = projectLinks.filter(_.status == LinkStatus.Terminated)
    terminations.map(pl =>
        adjustIfSplit(pl, currentAddresses.get(pl.linearLocationId)).get -> pl
    )
  }

  private def findUnChanged(projectLinks: Seq[ProjectLink], currentAddresses: Map[Long, RoadAddress]): Seq[(RoadAddress, ProjectLink)] = {
    projectLinks.filter(_.status == LinkStatus.UnChanged).map(pl =>
      adjustIfSplit(pl, currentAddresses.get(pl.linearLocationId)).get -> pl)
  }

  private def findTransfers(projectLinks: Seq[ProjectLink], currentAddresses: Map[Long, RoadAddress]): Seq[(RoadAddress, ProjectLink)] = {
    val (split, nonSplit) = projectLinks.filter(_.status == LinkStatus.Transfer).partition(_.isSplit)
    split.map(pl =>
      adjustIfSplit(pl, currentAddresses.get(pl.linearLocationId),
        projectLinks.sortBy(_.endAddrMValue).reverse.find(_.linkId == pl.connectedLinkId.get)).get -> pl) ++
      nonSplit.map(pl =>
        adjustIfSplit(pl, currentAddresses.get(pl.linearLocationId)).get -> pl)
  }

  private def findNumbering(projectLinks: Seq[ProjectLink], currentAddresses: Map[Long, RoadAddress]): Seq[(RoadAddress, ProjectLink)] = {
    projectLinks.filter(_.status == LinkStatus.Numbering).map(pl => currentAddresses(pl.linearLocationId) -> pl)
  }

  private def findNewCreations(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    projectLinks.filter(_.status == LinkStatus.New)
  }

  private def combineTwo[R <: BaseRoadAddress, P <: BaseRoadAddress](tr1: (R, P), tr2: (R, P), oppositeSections: Seq[RoadwaySection]): Seq[(R, P)] = {
    val (ra1, pl1) = tr1
    val (ra2, pl2) = tr2
    val matchAddr = (!pl1.reversed && pl1.endAddrMValue == pl2.startAddrMValue) ||
      (pl1.reversed && pl1.startAddrMValue == pl2.endAddrMValue)
    val matchContinuity = (pl1.reversed && pl2.discontinuity == Discontinuity.Continuous) ||
      (!pl1.reversed && pl1.discontinuity == Discontinuity.Continuous)

    val existing = oppositeSections.find(s => s.startMAddr == pl1.startAddrMValue && s.track != Track.Combined && s.track != pl1.track)

    val plLinks = projectLinkDAO.fetchProjectLinks(pl1.asInstanceOf[ProjectLink].projectId).filter( pl => pl.track != pl1.track && pl.endAddrMValue == pl1.endAddrMValue)

    val hasCalibrationPoint = if (existing.isDefined)
      (!pl1.reversed && existing.get.endMAddr == pl2.startAddrMValue) || (pl1.reversed && existing.get.endMAddr == pl2.endAddrMValue)
    else {
      pl1 match {
        case x: RoadAddress => (!x.reversed && x.hasCalibrationPointAtEnd && ra1.roadwayNumber != ra2.roadwayNumber) ||
          (x.reversed && x.hasCalibrationPointAtStart && ra1.roadwayNumber != ra2.roadwayNumber)
        case x: ProjectLink =>
          ((!x.reversed && x.hasCalibrationPointAtEnd || x.reversed && x.hasCalibrationPointAtStart) &&
            x.hasCalibrationPointCreatedInProject) ||
            (plLinks.nonEmpty && plLinks.head.hasCalibrationPointAtEnd && x.hasCalibrationPointAtEnd) // Opposite side has user cp
      }

    }
  //  tr1 = {Tuple2@143934} "(RoadAddress(66680,425970,5,129,PublicRoad,2,5 - Jatkuva,169,2083,Some(2015-10-01T00:00:00.000+03:00),None,Some(import),7094943,98.521,205.168,TowardsDigitizing,1600729985000,(None,None),List(Point(514506.475,6838673.87,0.0), Point(514589.163,6838741.131,0.0)),FrozenLinkInterface,8,NoTermination,126018012,Some(2016-10-20T00:00:00.000+03:00),None,Some(HELSINKI-SODANKYLÄ)),ProjectLink(1075112,5,129,2,5 - Jatkuva,169,2083,169,274,Some(2015-10-01T00:00:00.000+03:00),None,Some(silari),7094943,98.521,205.168,TowardsDigitizing,(NoCP,NoCP),(NoCP,NoCP),List(Point(514506.475,6838673.87,85.4262719571869), Point(514507.64,6838674.714,85.3910000000033), Point(514509.258,6838675.889,85.3669999999984), Point(514510.872,6838677.068,85.3469999999943), Point(514512.485,6838678.25,85.5160000000033), Point(514514.094,6838679.436,85.6729999999952), Point(514515.701,6838680.625,85.3619999999937), Point(514517.306,6838681.817,84.474000000002), Point(514518.908,6838683.013,83.7010000000009), Point(514520.508,"
  //  tr2 = {Tuple2@143930} "(RoadAddress(66680,425992,5,129,PublicRoad,2,5 - Jatkuva,2083,2146,Some(2015-10-01T00:00:00.000+03:00),None,Some(import),3226409,75.06,138.962,TowardsDigitizing,1600729985000,(Some(CalibrationPoint(3226409,0.0,2009,JunctionPointCP)),None),List(Point(516029.978,6839812.933,0.0), Point(516069.615,6839862.917,0.0)),FrozenLinkInterface,8,NoTermination,126018012,Some(2016-10-20T00:00:00.000+03:00),None,Some(HELSINKI-SODANKYLÄ)),ProjectLink(1075151,5,129,2,5 - Jatkuva,2083,2146,0,0,Some(2015-10-01T00:00:00.000+03:00),None,Some(silari),3226409,75.06,138.962,TowardsDigitizing,(JunctionPointCP,NoCP),(JunctionPointCP,NoCP),List(Point(516031.53,6839811.623,0.0), Point(516042.925,6839825.934,0.0), Point(516060.546,6839850.568,0.0), Point(516069.615,6839862.917,0.0)),1008319,UnChanged,PublicRoad,FrozenLinkInterface,63.902,66680,425992,8,false,Some(3226409),1600729985000,126018012,Some(HELSINKI-SODANKYLÄ),Some(0),Some(0),Some(0),Some(2),Some(5),Some(129)))"

//ra1 track 2 has hasCalibrationPoint == false

    if (matchAddr && matchContinuity && !hasCalibrationPoint &&
      ra1.endAddrMValue == ra2.startAddrMValue &&
      ra1.roadType == ra2.roadType && pl1.roadType == pl2.roadType && pl1.reversed == pl2.reversed)
      Seq((
        ra1 match {
          case x: RoadAddress => x.copy(endAddrMValue = ra2.endAddrMValue, discontinuity = ra2.discontinuity).asInstanceOf[R]
//          case x: ProjectLink if x.reversed => x.copy(startAddrMValue = ra2.startAddrMValue, discontinuity = ra1.discontinuity).asInstanceOf[R]
//          case x: ProjectLink => x.copy(endAddrMValue = ra2.endAddrMValue, discontinuity = ra2.discontinuity).asInstanceOf[R]
        },
        pl1 match {
//          case x: RoadAddress => x.copy(endAddrMValue = pl2.endAddrMValue, calibrationPoints = CalibrationPointsUtils.toCalibrationPoints(pl2.calibrationPoints)).asInstanceOf[P]
          case x: ProjectLink if x.reversed =>
            x.copy(startAddrMValue = pl2.startAddrMValue, discontinuity = pl1.discontinuity,
              calibrationPointTypes = (x.startCalibrationPointType, pl1.asInstanceOf[ProjectLink].endCalibrationPointType),
              originalCalibrationPointTypes = (x.originalCalibrationPointTypes._1, pl1.asInstanceOf[ProjectLink].originalCalibrationPointTypes._2)
            ).asInstanceOf[P]
          case x: ProjectLink =>
            x.copy(endAddrMValue = pl2.endAddrMValue, discontinuity = pl2.discontinuity,
              calibrationPointTypes = (x.startCalibrationPointType, pl2.asInstanceOf[ProjectLink].endCalibrationPointType),
              originalCalibrationPointTypes = (x.originalCalibrationPointTypes._1, pl2.asInstanceOf[ProjectLink].originalCalibrationPointTypes._2)
            ).asInstanceOf[P]
        }))
    else {
      Seq(tr2, tr1)
    }
  }

  private def combineTwo(r1: ProjectLink, r2: ProjectLink): Seq[ProjectLink] = {
    val hasCalibrationPoint = r1.hasCalibrationPointAtEnd

    val hasParallelLinkOnCalibrationPoint =
      if (!hasCalibrationPoint && r1.track != Track.Combined) {
        val projectLinks = projectLinkDAO.fetchProjectLinksByProjectRoadPart(r1.roadNumber, r1.roadPartNumber, r1.projectId)
        val parallelLastOnCalibrationPoint = projectLinks.filter(pl =>
          pl.status != LinkStatus.Terminated &&
            pl.track != r1.track &&
            pl.track != Track.Combined &&
            pl.endAddrMValue == r1.endAddrMValue &&
            pl.hasCalibrationPointAtEnd)
        !parallelLastOnCalibrationPoint.isEmpty
      } else
        false

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
          if ( !hasParallelLinkOnCalibrationPoint && !hasCalibrationPoint && r1.discontinuity.value != Discontinuity.ParallelLink.value ) { // && !r1.isSplit
            Seq(r1.copy(endAddrMValue = r2.endAddrMValue, discontinuity = r2.discontinuity, calibrationPointTypes = r2.calibrationPointTypes, connectedLinkId = r2.connectedLinkId))
          } else if (!hasCalibrationPoint && r1.discontinuity.value == Discontinuity.ParallelLink.value) {
            Seq(r2, r1)
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

  private def combine(projectLinkSeq: Seq[ProjectLink], result: Seq[ProjectLink] = Seq()): Seq[ProjectLink] = {
    if (projectLinkSeq.isEmpty)
      result.reverse
    else if (result.isEmpty)
      combine(projectLinkSeq.tail, Seq(projectLinkSeq.head))
    else
      combine(projectLinkSeq.tail, combineTwo(result.head, projectLinkSeq.head) ++ result.tail)
  }

  def getClosestOpposite[T <: BaseRoadAddress](startAddrMValue: Long, oppositeTracks: Seq[T]): Option[T] = {
    oppositeTracks match {
      case Nil => None
      case seq => Option(seq.minBy(v => math.abs(v.startAddrMValue - startAddrMValue)))
    }
  }

  private def combinePair[T <: BaseRoadAddress, R <: ProjectLink](combinedSeq: Seq[(T, R)], oppositeSections: Seq[RoadwaySection], result: Seq[(T, R)] = Seq()): Seq[(T, R)] = {
    if (combinedSeq.isEmpty)
      result.reverse
    else if (result.isEmpty)
      combinePair(combinedSeq.tail, oppositeSections, Seq(combinedSeq.head))
    else
      combinePair(combinedSeq.tail, oppositeSections, combineTwo(result.head, combinedSeq.head, oppositeSections) ++ result.tail)
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

  def partition[T <: BaseRoadAddress](projectLinks: Seq[ProjectLink]): Seq[RoadwaySection] = {
    val grouped = projectLinks.groupBy(ra => (ra.roadNumber, ra.roadPartNumber, ra.track, ra.roadType))
      .mapValues(v => combine(v.sortBy(_.startAddrMValue))).values.flatten.map(ra =>
      RoadwaySection(ra.roadNumber, ra.roadPartNumber, ra.roadPartNumber,
        ra.track, ra.startAddrMValue, ra.endAddrMValue, ra.discontinuity, ra.roadType, ra.ely, ra.reversed, ra.roadwayNumber, Seq())
    ).toSeq

    val paired = grouped.groupBy(section => (section.roadNumber, section.roadPartNumberStart, section.track, section.roadwayNumber))

    val result = paired.flatMap { case (key, targetToMap) =>
      val matches = matchingTracks(paired, key)
      val target = targetToMap.map(t => t.copy(projectLinks = projectLinks.filter(link => link.roadNumber == t.roadNumber && link.roadPartNumber == t.roadPartNumberEnd && link.track == t.track && link.roadType == t.roadType && link.ely == t.ely &&
        link.startAddrMValue >= t.startMAddr && link.endAddrMValue <= t.endMAddr)))
      if (matches.nonEmpty && matches.get.lengthCompare(target.length) == 0 && matchesFitOnTarget(target, matches.get)) {
        adjustTrack((target.sortBy(_.startMAddr), matches.get.sortBy(_.startMAddr)))
      } else
        target
    }.toSeq
    result
  }

  private def matchingTracks(map: Map[(Long, Long, Track, Long, Long, Long, Track, Long), (Seq[RoadwaySection], Seq[RoadwaySection])],
                             key: (Long, Long, Track, Long, Long, Long, Track, Long), oppositeSections: Seq[RoadwaySection] = Seq()): Option[(Seq[RoadwaySection], Seq[RoadwaySection])] = {
    map.get((key._1, key._2, Track.switch(key._3), key._4, key._5, key._6, Track.switch(key._7), key._8))
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

    def partition(transfers: Seq[(RoadAddress, ProjectLink)], oppositeSections: Seq[RoadwaySection] = Seq()): ChangeTableRows = {
      def toRoadAddressSection(transfers: Seq[(RoadAddress, ProjectLink)], o: Seq[BaseRoadAddress]): Seq[RoadwaySection] = {
        o.map(ra =>
          RoadwaySection(ra.roadNumber, ra.roadPartNumber, ra.roadPartNumber,
            ra.track, ra.startAddrMValue, ra.endAddrMValue, ra.discontinuity, ra.roadType, ra.ely, ra.reversed, ra.roadwayNumber, Seq()))
      }



       val trans_ =  transfers.groupBy(x => (x._1.roadNumber, x._1.roadPartNumber, x._1.track, x._1.roadwayNumber, x._2.roadNumber, x._2.roadPartNumber, x._2.track, x._2.roadwayNumber))
      val trans_mapped = trans_.mapValues(v => combinePair(v.sortBy(_._1.startAddrMValue), oppositeSections))
      val sectioned = trans_mapped.mapValues(v => {
        val (from, to) = v.unzip
        toRoadAddressSection(transfers, from) -> toRoadAddressSection(transfers, to)
      })

    //adjusted the end of sources
    val links = transfers.map(_._2)
//    val sections = sectioned.flatMap { case (key, (src, targetToMap)) =>
//      val matches = matchingTracks(sectioned, key, oppositeSections)
//      val target = targetToMap.map(t => t.copy(projectLinks = links.filter(link => link.roadwayNumber == t.roadwayNumber && link.roadNumber == t.roadNumber && link.roadPartNumber == t.roadPartNumberEnd && link.track == t.track && link.ely == t.ely &&
//        link.startAddrMValue >= t.startMAddr && link.endAddrMValue <= t.endMAddr)))
//      //exclusive 'or' operation, so we don't want to find a matching track when we want to reduce 2 tracks to track 0
//      if (matches.nonEmpty && !(key._3 == Track.Combined ^ key._7 == Track.Combined))
//        adjustTrack((src, matches.get._1)).zip(adjustTrack((target, matches.get._2)))
//      else
//        src.zip(target)
//    }
      // : Map[RoadwaySection, RoadwaySection]
    val sections = sectioned.map ( sect => {
        val (key, (src, targetToMap)) = sect
        val matches = matchingTracks(sectioned, key, oppositeSections)
        val target = targetToMap.map(t => t.copy(projectLinks = links.filter(link => link.roadwayNumber == t.roadwayNumber && link.roadNumber == t.roadNumber && link.roadPartNumber == t.roadPartNumberEnd && link.track == t.track && link.ely == t.ely &&
          link.startAddrMValue >= t.startMAddr && link.endAddrMValue <= t.endMAddr)))
        //  //exclusive 'or' operation, so we don't want to find a matching track when we want to reduce 2 tracks to track 0
          if (matches.nonEmpty && !(key._3 == Track.Combined ^ key._7 == Track.Combined))
            adjustTrack((src, matches.get._1)).zip(adjustTrack((target, matches.get._2)))
          else
        src.zip(target)
      }
      ).flatten

    //adjusted the end of sources
    val adjustedEndSourceSections = sections.map { case (src, target) =>
      val possibleExistingSameEndAddrMValue = sections.find(s => s._1.roadNumber == src.roadNumber && s._1.roadPartNumberStart == src.roadPartNumberStart && s._2.endMAddr == target.endMAddr
      && s._1.track != src.track)
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
    val adjustedStartSourceSections = sectionsAfterAdjust.map { case ((src, target), _) =>
      val possibleExistingSameStartAddrMValue = sections.find(s => s._1.roadNumber == src.roadNumber && s._1.roadPartNumberStart == src.roadPartNumberStart && s._1.roadwayNumber == src.roadwayNumber && s._2.roadwayNumber == target.roadwayNumber && s._1.endMAddr == src.startMAddr)
      if (possibleExistingSameStartAddrMValue.nonEmpty) {
        val oppositePairingTrack = sections.find(s => s._1.roadNumber == possibleExistingSameStartAddrMValue.get._1.roadNumber && s._1.roadPartNumberStart == possibleExistingSameStartAddrMValue.get._1.roadPartNumberStart && s._2.endMAddr == possibleExistingSameStartAddrMValue.get._2.endMAddr
          && s._1.track != possibleExistingSameStartAddrMValue.get._1.track)
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
    val warning = sectionsAfterAdjust.map(_._2).flatten.toSeq ++ adjustedStartSourceSections.map(_._2).flatten.toSeq
    (adjustedStartSourceSections.map(_._1).toMap, if (warning.nonEmpty) Option(warning.head) else None)
  }
}

case class Delta(startDate: DateTime, newRoads: Seq[ProjectLink], terminations: Termination,
                 unChanged: Unchanged, transferred: Transferred, numbering: ReNumeration)

case class RoadPart(roadNumber: Long, roadPartNumber: Long)

case class Termination(mapping: Seq[(RoadAddress, ProjectLink)])

case class Unchanged(mapping: Seq[(RoadAddress, ProjectLink)])

case class Transferred(mapping: Seq[(RoadAddress, ProjectLink)])

case class ReNumeration(mapping: Seq[(RoadAddress, ProjectLink)])
