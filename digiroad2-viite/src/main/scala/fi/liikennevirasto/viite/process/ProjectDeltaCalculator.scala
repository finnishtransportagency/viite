package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.GeometryUtils
import fi.liikennevirasto.digiroad2.util.Track.RightSide
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, Track}
import fi.liikennevirasto.viite.dao.CalibrationPointSource.{ProjectLinkSource, UnknownSource}
import fi.liikennevirasto.viite.dao.LinkStatus._
import fi.liikennevirasto.viite.dao.{ProjectLink, _}
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import fi.liikennevirasto.viite
import fi.liikennevirasto.viite.dao.Discontinuity.Continuous
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

/**
  * Calculate the effective change between the project and the current road address data
  */
object ProjectDeltaCalculator {

  val MaxAllowedMValueError = 0.001
  val checker = new ContinuityChecker(null) // We don't need road link service here
  lazy private val logger = LoggerFactory.getLogger(getClass)
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
    val hasCalibrationPoint = if (existing.isDefined)
      (!pl1.reversed && existing.get.endMAddr == pl2.startAddrMValue) || (pl1.reversed && existing.get.endMAddr == pl2.endAddrMValue)
    else {
      pl1 match {
        case x: RoadAddress => (!x.reversed && x.hasCalibrationPointAt(CalibrationCode.AtEnd) && ra1.roadwayNumber != ra2.roadwayNumber) || (x.reversed && x.hasCalibrationPointAt(CalibrationCode.AtBeginning) && ra1.roadwayNumber != ra2.roadwayNumber)
        case x: ProjectLink =>
          val (sourceL, sourceR) = x.getCalibrationSources
          (!x.reversed && x.hasCalibrationPointAt(CalibrationCode.AtEnd) || x.reversed && x.hasCalibrationPointAt(CalibrationCode.AtBeginning)) &&
            (sourceL.getOrElse(UnknownSource) == ProjectLinkSource || sourceR.getOrElse(UnknownSource) == ProjectLinkSource)
      }

    }

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
          case x: RoadAddress => x.copy(discontinuity = pl2.discontinuity, endAddrMValue = pl2.endAddrMValue, calibrationPoints = CalibrationPointsUtils.toCalibrationPoints(pl2.calibrationPoints)).asInstanceOf[P]
          case x: ProjectLink if x.reversed => x.copy(startAddrMValue = pl2.startAddrMValue, discontinuity = pl1.discontinuity, calibrationPoints = CalibrationPointsUtils.toProjectLinkCalibrationPoints(pl1.calibrationPoints, x.linearLocationId)).asInstanceOf[P]
          case x: ProjectLink => x.copy(endAddrMValue = pl2.endAddrMValue, discontinuity = pl2.discontinuity, calibrationPoints = CalibrationPointsUtils.toProjectLinkCalibrationPoints(pl2.calibrationPoints, x.linearLocationId)).asInstanceOf[P]
        }))
    else {
      Seq(tr2, tr1)
    }
  }

  private def combineTwo(r1: ProjectLink, r2: ProjectLink): Seq[ProjectLink] = {
    val hasCalibrationPoint = r1.hasCalibrationPointAt(CalibrationCode.AtEnd)
    val openBasedOnSource = hasCalibrationPoint && {
      val (sourceL, sourceR) = r1.getCalibrationSources
      sourceL.getOrElse(UnknownSource) == ProjectLinkSource || sourceR.getOrElse(UnknownSource) == ProjectLinkSource
    }
    if (r1.endAddrMValue == r2.startAddrMValue)
      r1.status match {
        case LinkStatus.Terminated =>
          if (hasCalibrationPoint && r1.roadwayNumber != r2.roadwayNumber)
            Seq(r2, r1)
          else if (openBasedOnSource)
            Seq(r2, r1)
          else
            Seq(r1.copy(discontinuity = r2.discontinuity, endAddrMValue = r2.endAddrMValue, calibrationPoints = r2.calibrationPoints))
        case LinkStatus.New =>
          if (!hasCalibrationPoint)
            Seq(r1.copy(endAddrMValue = r2.endAddrMValue, discontinuity = r2.discontinuity, calibrationPoints = r2.calibrationPoints))
          else
            Seq(r2, r1)
        case _ =>
          Seq(r2, r1)
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
      if (matches.nonEmpty && matches.get.lengthCompare(target.length) == 0 && matchesFitOnTarget(target, matches.get))
        adjustTrack((target, matches.get))
      else
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

      val sectioned = transfers.groupBy(x => (x._1.roadNumber, x._1.roadPartNumber, x._1.track, x._1.roadwayNumber, x._2.roadNumber, x._2.roadPartNumber, x._2.track, x._2.roadwayNumber))
      .mapValues(v => combinePair(v.sortBy(_._1.startAddrMValue), oppositeSections))
      .mapValues(v => {
        val (from, to) = v.unzip
        toRoadAddressSection(transfers, from) -> toRoadAddressSection(transfers, to)
      })

    //adjusted the end of sources
    val links = transfers.map(_._2)
    val sections = sectioned.flatMap { case (key, (src, targetToMap)) =>
      val matches = matchingTracks(sectioned, key, oppositeSections)
      val target = targetToMap.map(t => t.copy(projectLinks = links.filter(link => link.roadwayNumber == t.roadwayNumber && link.roadNumber == t.roadNumber && link.roadPartNumber == t.roadPartNumberEnd && link.track == t.track && link.ely == t.ely &&
        link.startAddrMValue >= t.startMAddr && link.endAddrMValue <= t.endMAddr)))
      //exclusive 'or' operation, so we don't want to find a matching track when we want to reduce 2 tracks to track 0
      if (matches.nonEmpty && !(key._3 == Track.Combined ^ key._7 == Track.Combined))
        adjustTrack((src, matches.get._1)).zip(adjustTrack((target, matches.get._2)))
      else
        src.zip(target)
    }

    //adjusted the end of sources
    val adjustedEndSourceSections = sections.map { case (src, target) =>
      val possibleExistingSameEndAddrMValue = sections.find(s => s._1.roadNumber == src.roadNumber && s._1.roadPartNumberStart == src.roadPartNumberStart && s._2.endMAddr == target.endMAddr
      && s._1.track != src.track)
      if (possibleExistingSameEndAddrMValue.nonEmpty) {
        val warningMessage = if (Math.abs(src.endMAddr - possibleExistingSameEndAddrMValue.head._1.endMAddr) > viite.MaxDistanceBetweenTracks)
          Some("Tarkista, että toimenpide vaihtuu samassa kohdassa.")
        else
          None
      ((src.copy(endMAddr = adjustAddressValues(src.endMAddr + possibleExistingSameEndAddrMValue.head._1.endMAddr, src.endMAddr, src.track)), target), warningMessage)
      } else {
        ((src, target), None)
      }
    }

      ChangeTableRows(adjustedSections = adjustedEndSourceSections, originalSections = sections)

  }

  def adjustStartSourceAddressValues(sectionsAfterAdjust: Map[(RoadwaySection, RoadwaySection), Option[String]], sections: Map[RoadwaySection, RoadwaySection]): (Map[RoadwaySection, RoadwaySection], Option[String]) = {
    //adjusted the start of sources
    val adjustedStartSourceSections = sectionsAfterAdjust.map { case ((src, target), warningM) =>
      val possibleExistingSameStartAddrMValue = sections.find(s => s._1.roadNumber == src.roadNumber && s._1.roadPartNumberStart == src.roadPartNumberStart && s._1.roadwayNumber == src.roadwayNumber && s._2.roadwayNumber == target.roadwayNumber && s._1.endMAddr == src.startMAddr)
      if (possibleExistingSameStartAddrMValue.nonEmpty) {
        val oppositePairingTrack = sections.find(s => s._1.roadNumber == possibleExistingSameStartAddrMValue.get._1.roadNumber && s._1.roadPartNumberStart == possibleExistingSameStartAddrMValue.get._1.roadPartNumberStart && s._2.endMAddr == possibleExistingSameStartAddrMValue.get._2.endMAddr
          && s._1.track != possibleExistingSameStartAddrMValue.get._1.track)
        if (oppositePairingTrack.nonEmpty) {
          val warningMessage = if (Math.abs(possibleExistingSameStartAddrMValue.head._1.endMAddr - oppositePairingTrack.head._1.endMAddr) > viite.MaxDistanceBetweenTracks)
            Some("Tarkista, että toimenpide vaihtuu samassa kohdassa.")
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
    val warning = sectionsAfterAdjust.values.flatten.toSeq ++ adjustedStartSourceSections.values.flatten.toSeq
    (adjustedStartSourceSections.keys.toMap, if (warning.nonEmpty) Option(warning.head) else None)
  }
}

case class Delta(startDate: DateTime, newRoads: Seq[ProjectLink], terminations: Termination,
                 unChanged: Unchanged, transferred: Transferred, numbering: ReNumeration)

case class RoadPart(roadNumber: Long, roadPartNumber: Long)

case class Termination(mapping: Seq[(RoadAddress, ProjectLink)])

case class Unchanged(mapping: Seq[(RoadAddress, ProjectLink)])

case class Transferred(mapping: Seq[(RoadAddress, ProjectLink)])

case class ReNumeration(mapping: Seq[(RoadAddress, ProjectLink)])
