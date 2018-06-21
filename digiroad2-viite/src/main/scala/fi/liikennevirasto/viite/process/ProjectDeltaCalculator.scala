package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.GeometryUtils
import fi.liikennevirasto.digiroad2.util.Track.RightSide
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, Track}
import fi.liikennevirasto.viite.dao.Discontinuity.MinorDiscontinuity
import fi.liikennevirasto.viite.dao.LinkStatus._
import fi.liikennevirasto.viite.dao.{ProjectLink, _}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

/**
  * Calculate the effective change between the project and the current road address data
  */
object ProjectDeltaCalculator {

  val MaxAllowedMValueError = 0.001
  val checker = new ContinuityChecker(null) // We don't need road link service here
  lazy private val logger = LoggerFactory.getLogger(getClass)

  def delta(project: RoadAddressProject): Delta = {
    val projectLinksFetched = ProjectDAO.getProjectLinks(project.id)
    val projectLinks = projectLinksFetched.groupBy(l => RoadPart(l.roadNumber,l.roadPartNumber))
    val currentAddresses = RoadAddressDAO.fetchByIdMassQuery(projectLinksFetched.map(pl => pl.roadAddressId).toSet,
      includeFloating = true).map(ra => ra.id -> ra).toMap
    val terminations = findTerminations(projectLinks, currentAddresses)
    val newCreations = findNewCreations(projectLinks)
    val unChanged = Unchanged(findUnChanged(projectLinksFetched, currentAddresses))
    val transferred = Transferred(findTransfers(projectLinksFetched, currentAddresses))
    val numbering = ReNumeration(findNumbering(projectLinksFetched, currentAddresses))

    Delta(project.startDate, terminations, newCreations, unChanged, transferred, numbering)
  }

  private def adjustIfSplit(pl: ProjectLink, ra: Option[RoadAddress], connectedLink: Option[ProjectLink] = None) = {
    // Test if this link was a split case: if not, return original address, otherwise return a copy that is adjusted
    if (!pl.isSplit)
      ra
    else
      ra.map(address => {
        val geom = GeometryUtils.truncateGeometry2D(address.geometry, pl.startMValue, pl.endMValue)
        pl.status match {
          case Transfer =>
            val termAddress = connectedLink.map(l => (l.startAddrMValue, l.endAddrMValue))
            termAddress.map { case (start, end) =>
              address.copy(startAddrMValue = end,
                endAddrMValue = if (end == address.endAddrMValue) start else address.endAddrMValue, startMValue = pl.startMValue,
                endMValue = pl.endMValue, geometry = geom)
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

  private def findTerminations(projectLinks: Map[RoadPart, Seq[ProjectLink]], currentAddresses: Map[Long, RoadAddress]) = {
    val terminations = projectLinks.mapValues(pll =>
      pll.filter(_.status == LinkStatus.Terminated).flatMap(pl =>
        adjustIfSplit(pl, currentAddresses.get(pl.roadAddressId))
      )
    )
    terminations.filterNot(t => t._2.isEmpty).values.foreach(validateTerminations)
    terminations.values.flatten.toSeq
  }

  private def findUnChanged(projectLinks: Seq[ProjectLink], currentAddresses: Map[Long, RoadAddress]) = {
    projectLinks.filter(_.status == LinkStatus.UnChanged).map(pl =>
      adjustIfSplit(pl, currentAddresses.get(pl.roadAddressId)).get -> pl)
  }

  private def findTransfers(projectLinks: Seq[ProjectLink], currentAddresses: Map[Long, RoadAddress]): Seq[(RoadAddress, ProjectLink)] = {
    val (split, nonSplit) = projectLinks.filter(_.status == LinkStatus.Transfer).partition(_.isSplit)
    split.map(pl =>
      adjustIfSplit(pl, currentAddresses.get(pl.roadAddressId),
        projectLinks.sortBy(_.endAddrMValue).reverse.find(_.linkId == pl.connectedLinkId.get)).get -> pl) ++
      nonSplit.map(pl =>
        adjustIfSplit(pl, currentAddresses.get(pl.roadAddressId)).get -> pl)
  }

  private def findNumbering(projectLinks: Seq[ProjectLink], currentAddresses: Map[Long, RoadAddress]) = {
    projectLinks.filter(_.status == LinkStatus.Numbering).map(pl => currentAddresses(pl.roadAddressId) -> pl)
  }

  private def findNewCreations(projectLinks: Map[RoadPart, Seq[ProjectLink]]) = {
    projectLinks.values.flatten.filter(_.status == LinkStatus.New).toSeq
  }

  private def validateTerminations(roadAddresses: Seq[RoadAddress]) = {
    if (roadAddresses.groupBy(ra => (ra.roadNumber, ra.roadPartNumber)).keySet.size != 1)
      throw new RoadAddressException("Multiple or no road parts present in one termination set")
   }

  private def combineTwo[R <: BaseRoadAddress, P <: BaseRoadAddress](tr1: (R,P), tr2: (R,P), oppositeSections: Seq[RoadAddressSection]): Seq[(R,P)] = {
    val (ra1,pl1) = tr1
    val (ra2,pl2) = tr2
    val matchAddr = (!pl1.reversed && pl1.endAddrMValue == pl2.startAddrMValue) ||
      (pl1.reversed && pl1.startAddrMValue == pl2.endAddrMValue)
    val matchContinuity = (pl1.reversed && pl2.discontinuity == Discontinuity.Continuous) ||
      (!pl1.reversed && pl1.discontinuity == Discontinuity.Continuous)

    val existing = oppositeSections.find(s => s.startMAddr == pl1.startAddrMValue && s.track == Track.switch(pl1.track))
    val hasCalibrationPoint = if(existing.isDefined)
      (!pl1.reversed && existing.get.endMAddr == pl2.startAddrMValue) || (pl1.reversed && existing.get.endMAddr == pl2.endAddrMValue)
    else
      (!pl1.reversed && pl1.hasCalibrationPointAt(CalibrationCode.AtEnd)) || (pl1.reversed && pl1.hasCalibrationPointAt(CalibrationCode.AtBeginning))

    if (matchAddr && matchContinuity && !hasCalibrationPoint &&
      ra1.endAddrMValue == ra2.startAddrMValue &&
      ra1.roadType == ra2.roadType && pl1.roadType == pl2.roadType && pl1.reversed == pl2.reversed)
      Seq((
        ra1 match {
          case x: RoadAddress => x.copy(discontinuity = ra2.discontinuity, endAddrMValue = ra2.endAddrMValue).asInstanceOf[R]
          case x: ProjectLink if x.reversed => x.copy(startAddrMValue = ra2.startAddrMValue, discontinuity = ra1.discontinuity).asInstanceOf[R]
          case x: ProjectLink => x.copy(endAddrMValue = ra2.endAddrMValue, discontinuity = ra2.discontinuity).asInstanceOf[R]
      },
        pl1 match {
          case x: RoadAddress => x.copy(discontinuity = pl2.discontinuity, endAddrMValue = pl2.endAddrMValue, calibrationPoints = pl2.calibrationPoints).asInstanceOf[P]
          case x: ProjectLink if x.reversed => x.copy(startAddrMValue = pl2.startAddrMValue, discontinuity = pl1.discontinuity, calibrationPoints = pl1.calibrationPoints).asInstanceOf[P]
          case x: ProjectLink => x.copy(endAddrMValue = pl2.endAddrMValue, discontinuity = pl2.discontinuity, calibrationPoints = pl2.calibrationPoints).asInstanceOf[P]
      }))
    else {
      Seq(tr2, tr1)
    }
  }

  private def combineTwo[T <: BaseRoadAddress](r1: T, r2: T): Seq[T] = {
    val hasCalibrationPoint = (!r1.reversed && r1.hasCalibrationPointAt(CalibrationCode.AtEnd)) || (r1.reversed && r1.hasCalibrationPointAt(CalibrationCode.AtBeginning))
    if (r1.endAddrMValue == r2.startAddrMValue && !hasCalibrationPoint)
      r1 match {
        case x: RoadAddress => Seq(x.copy(discontinuity = r2.discontinuity, endAddrMValue = r2.endAddrMValue, calibrationPoints = r2.calibrationPoints).asInstanceOf[T])
        case x: ProjectLink => Seq(x.copy(endAddrMValue = r2.endAddrMValue, discontinuity = r2.discontinuity, calibrationPoints = r2.calibrationPoints).asInstanceOf[T])
      }
    else
      Seq(r2, r1)
  }

  private def combine[T <: BaseRoadAddress](roadAddressSeq: Seq[T], result: Seq[T] = Seq()): Seq[T] = {
    if (roadAddressSeq.isEmpty)
      result.reverse
    else if (result.isEmpty)
      combine(roadAddressSeq.tail, Seq(roadAddressSeq.head))
    else
      combine(roadAddressSeq.tail, combineTwo(result.head, roadAddressSeq.head) ++ result.tail)
  }

  private def combinePair[T <: BaseRoadAddress, R <: ProjectLink](combinedSeq: Seq[(T,R)], oppositeSections: Seq[RoadAddressSection],  result: Seq[(T,R)] = Seq()): Seq[(T,R)] = {
    if (combinedSeq.isEmpty)
      result.reverse
    else if (result.isEmpty)
      combinePair(combinedSeq.tail, oppositeSections, Seq(combinedSeq.head))
    else
      combinePair(combinedSeq.tail, oppositeSections, combineTwo(result.head, combinedSeq.head, oppositeSections) ++ result.tail)
  }

  def adjustAddrValues(addrMValues: Long, mValue: Long, track: Track): Long = {
    val fusedValues = addrMValues%2 match {
      case 0 => addrMValues/2
      case _ =>
        if(track == RightSide ^ (mValue * 2 < addrMValues)){
          (addrMValues+1)/2
        } else {
          addrMValues/2
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
  def matchesFitOnTarget(target: Seq[RoadAddressSection], matchers: Seq[RoadAddressSection]): Boolean = {
    val (targetStartMAddr, targetEndMAddr) = (target.map(_.startMAddr).min, target.map(_.endMAddr).max)
    val (matcherStartMAddr, matcherEndMAddr) = (matchers.map(_.startMAddr).min, matchers.map(_.endMAddr).max)
    (targetStartMAddr <= matcherStartMAddr && targetEndMAddr >= matcherStartMAddr) || (targetStartMAddr <= matcherEndMAddr && targetEndMAddr >= matcherEndMAddr) ||
      (matcherStartMAddr <= targetStartMAddr && matcherEndMAddr >= targetStartMAddr) || (matcherStartMAddr <= targetEndMAddr && matcherEndMAddr >= targetEndMAddr)
  }

  def partition[T <: BaseRoadAddress](roadAddresses: Seq[T]): Seq[RoadAddressSection] = {
    val grouped = roadAddresses.groupBy(ra => (ra.roadNumber, ra.roadPartNumber, ra.track, ra.roadType))
    .mapValues(v => combine(v.sortBy(_.startAddrMValue))).values.flatten.map(ra =>
    RoadAddressSection(ra.roadNumber, ra.roadPartNumber, ra.roadPartNumber,
        ra.track, ra.startAddrMValue, ra.endAddrMValue, ra.discontinuity, ra.roadType, ra.ely, ra.reversed, ra.commonHistoryId)
    ).toSeq

    val paired = grouped.groupBy(section => (section.roadNumber, section.roadPartNumberStart, section.track, section.commonHistoryId))

    val result = paired.flatMap { case (key, target) =>
      val matches = matchingTracks(paired, key)
      if (matches.nonEmpty && matches.get.lengthCompare(target.length) == 0 && matchesFitOnTarget(target, matches.get))
        adjustTrackEndAddr((target, matches.get))
      else
        target
    }.toSeq

    result
  }

  def pair(roadAddress: Seq[RoadAddress], projectLink: Map[Long, Seq[ProjectLink]]): Seq[(RoadAddress,ProjectLink)] = {
    roadAddress.foldLeft(List.empty[(RoadAddress,ProjectLink)]) { case (p, a) =>
      val options = projectLink.getOrElse(a.id, Seq())
      options.size match {
        case 1 =>
          p :+ (a, options.head)
        case 0 =>
          logger.error(s"Unmatched road address ${a.id}: ${a.roadNumber}/${a.roadPartNumber}/${a.track.value}/${a.startAddrMValue}-${a.endAddrMValue}")
          p
        case _ =>
          logger.info(s"${options.size} matches for road address ${a.id}: ${a.roadNumber}/${a.roadPartNumber}/${a.track.value}/${a.startAddrMValue}-${a.endAddrMValue}")
          p
      }
    }
  }

  private def matchingTracks(map: Map[(Long,Long,Track,Long,Long,Track), (Seq[RoadAddressSection],Seq[RoadAddressSection])],
                             key: (Long,Long,Track,Long,Long,Track)): Option[(Seq[RoadAddressSection],Seq[RoadAddressSection])] = {
    map.get((key._1, key._2, Track.switch(key._3), key._4, key._5, Track.switch(key._6)))
  }

  private def matchingTracks(map: Map[(Long,Long,Track,Long), Seq[RoadAddressSection]],
                             key: (Long,Long,Track,Long)): Option[Seq[RoadAddressSection]] = {
    map.get((key._1, key._2, Track.switch(key._3), key._4))
  }

  private def adjustTrack(group: (Seq[RoadAddressSection], Seq[RoadAddressSection])): Seq[RoadAddressSection] = {
    group._1.zip(group._2).map {
      case (e1, e2) =>
        e1.copy(startMAddr = adjustAddrValues(e1.startMAddr + e2.startMAddr, e1.startMAddr, e1.track),
          endMAddr = adjustAddrValues(e1.endMAddr + e2.endMAddr, e1.endMAddr, e1.track))
    }
  }

  private def adjustTrackEndAddr(group: (Seq[RoadAddressSection], Seq[RoadAddressSection])): Seq[RoadAddressSection] = {
    group._1.zip(group._2).map {
      case (e1, e2) =>
        e1.copy(endMAddr = adjustAddrValues(e1.endMAddr + e2.endMAddr, e1.endMAddr, e1.track))
    }
  }

  def partition(transfers: Seq[(RoadAddress, ProjectLink)], oppositeSections: Seq[RoadAddressSection] = Seq()): Map[RoadAddressSection, RoadAddressSection] = {
    def toRoadAddressSection(o: Seq[BaseRoadAddress]): Seq[RoadAddressSection] = {
      o.map(ra =>
        RoadAddressSection(ra.roadNumber, ra.roadPartNumber, ra.roadPartNumber,
          ra.track, ra.startAddrMValue, ra.endAddrMValue, ra.discontinuity, ra.roadType, ra.ely, ra.reversed, ra.commonHistoryId))
    }

    val sectioned = transfers.groupBy(x => (x._1.roadNumber, x._1.roadPartNumber, x._1.track, x._2.roadNumber, x._2.roadPartNumber, x._2.track))
      .mapValues(v => combinePair(v.sortBy(_._1.startAddrMValue), oppositeSections))
      .mapValues(v => {
        val (from, to) = v.unzip
        toRoadAddressSection(from) -> toRoadAddressSection(to)
      })
    sectioned.flatMap { case (key, (src, target)) =>
      val matches = matchingTracks(sectioned, key)
      //exclusive 'or' operation, so we don't want to find a matching track when we want to reduce 2 tracks to track 0
      if (matches.nonEmpty && !(key._3 == Track.Combined ^ key._6 == Track.Combined))
        adjustTrack((src, matches.get._1)).zip(adjustTrack((target, matches.get._2)))
      else
        src.zip(target)
    }
  }
}

case class Delta(startDate: DateTime, terminations: Seq[RoadAddress], newRoads: Seq[ProjectLink],
                 unChanged: Unchanged, transferred: Transferred, numbering : ReNumeration)

case class RoadPart(roadNumber: Long, roadPartNumber: Long)

case class Unchanged(mapping: Seq[(RoadAddress, ProjectLink)])

case class Transferred(mapping: Seq[(RoadAddress, ProjectLink)])

case class ReNumeration(mapping: Seq[(RoadAddress, ProjectLink)])
