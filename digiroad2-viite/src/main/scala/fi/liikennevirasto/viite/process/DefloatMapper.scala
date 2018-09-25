package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.viite.switchSideCode
import fi.liikennevirasto.viite.dao.{Discontinuity, RoadAddress}
import fi.liikennevirasto.viite.model.RoadAddressLink
import fi.liikennevirasto.viite.{MaxDistanceDiffAllowed, MaxDistanceForConnectedLinks, MinAllowedRoadAddressLength}

object DefloatMapper extends RoadAddressMapper {

  def adjustRoadAddresses(roadAddress: Seq[RoadAddress], current: Seq[RoadAddress]): Seq[RoadAddress] = {
    def overrideStartAddrM(ra: RoadAddress, address: Long) : RoadAddress = {
      ra.copy(startAddrMValue = address, calibrationPoints = (ra.calibrationPoints._1.map(_.copy(addressMValue = address)), ra.calibrationPoints._2))
    }
    def overrideEndAddrM(ra: RoadAddress, address: Long): RoadAddress = {
      ra.copy(endAddrMValue = address, calibrationPoints = (ra.calibrationPoints._1, ra.calibrationPoints._2.map(_.copy(addressMValue = address))))
    }
    def overrideBothAddrM(ra: RoadAddress, startAddr: Long, endAddr: Long) : RoadAddress = {
      ra.copy(startAddrMValue = startAddr, endAddrMValue = endAddr, calibrationPoints = (ra.calibrationPoints._1.map(_.copy(addressMValue = startAddr)), ra.calibrationPoints._2.map(_.copy(addressMValue = endAddr))))
    }
    def adjustTwo(address: (RoadAddress, RoadAddress)): RoadAddress = {
      val (previousRoadAddress, nextRoadAddress) = address
      if (previousRoadAddress.endAddrMValue != nextRoadAddress.startAddrMValue)
        overrideStartAddrM(nextRoadAddress, previousRoadAddress.endAddrMValue)
      else
        nextRoadAddress
    }
    val (minAddr, maxAddr) = (current.map(_.startAddrMValue).min, current.map(_.endAddrMValue).max)

    roadAddress.size match {
      case 0 => Seq()
      case 1 => Seq(overrideBothAddrM(roadAddress.head, minAddr, maxAddr))
      case _ =>
        val ordered = roadAddress.sortBy(ra => (ra.endAddrMValue, ra.startAddrMValue))
        val overridden = ordered.head +: ordered.zip(ordered.tail).map(adjustTwo)
        overrideStartAddrM(overridden.head, minAddr) +: overridden.init.tail :+ overrideEndAddrM(overridden.last, maxAddr)
    }
  }

  def createAddressMap(sources: Seq[RoadAddressLink], targets: Seq[RoadAddressLink]): Seq[RoadAddressMapping] = {
    def formMapping(startSourceLink: RoadAddressLink, startSourceM: Double,
                    endSourceLink: RoadAddressLink, endSourceM: Double,
                    startTargetLink: RoadAddressLink, startTargetM: Double,
                    endTargetLink: RoadAddressLink, endTargetM: Double): RoadAddressMapping = {
      if (startSourceM > endSourceM)
        formMapping(startSourceLink, endSourceM, endSourceLink, startSourceM,
          startTargetLink, endTargetM, endTargetLink, startTargetM)
      else
        RoadAddressMapping(startSourceLink.linkId, startTargetLink.linkId, startSourceLink.id, startSourceM,
          if (startSourceLink.linkId == endSourceLink.linkId) endSourceM else Double.NaN,
          startTargetM,
          if (startTargetLink.linkId == endTargetLink.linkId) endTargetM else Double.NaN,
          Seq(GeometryUtils.calculatePointFromLinearReference(startSourceLink.geometry, startSourceM).getOrElse(Point(Double.NaN, Double.NaN)),
            GeometryUtils.calculatePointFromLinearReference(endSourceLink.geometry, endSourceM).getOrElse(Point(Double.NaN, Double.NaN))),
          Seq(GeometryUtils.calculatePointFromLinearReference(startTargetLink.geometry, startTargetM).getOrElse(Point(Double.NaN, Double.NaN)),
            GeometryUtils.calculatePointFromLinearReference(endTargetLink.geometry, endTargetM).getOrElse(Point(Double.NaN, Double.NaN)))
        )
    }
    /* For mapping purposes we have to fuse all road addresses on link to get it right. Otherwise start of a segment
       is assumed to be the start of a road link
     */
    def fuseAddressByLinkId(s: Seq[RoadAddressLink], fused: Seq[RoadAddressLink] = Seq()): Seq[RoadAddressLink] = {
      if (s.isEmpty)
      // We collect them in reverse order because we're interested in the last one and .head is so much better
        fused.reverse
      else {
        val next = s.head
        val previousOpt = fused.headOption.filter(_.linkId == next.linkId)
        if (previousOpt.nonEmpty && !((previousOpt.get.endCalibrationPoint.isDefined && next.startCalibrationPoint.isDefined ) || (previousOpt.get.startCalibrationPoint.isDefined && next.endCalibrationPoint.isDefined))) {
          // At this point we know that the chain is continuous, all are on same road part and track and ordered
          val edited = previousOpt.get.copy(startAddressM = Math.min(previousOpt.get.startAddressM, next.startAddressM),
            endAddressM = Math.max(previousOpt.get.endAddressM, next.endAddressM),
            startMValue = Math.min(previousOpt.get.startMValue, next.startMValue),
            endMValue = Math.max(previousOpt.get.endMValue, next.endMValue),
            geometry =
              if (next.sideCode == SideCode.AgainstDigitizing)
                next.geometry ++ previousOpt.get.geometry
              else
                previousOpt.get.geometry ++ next.geometry,
            length = previousOpt.get.length + next.length
          )
          fuseAddressByLinkId(s.tail, Seq(edited) ++ fused.tail)
        } else {
          fuseAddressByLinkId(s.tail, Seq(next) ++ fused)
        }
      }
    }

    val (orderedSource, orderedTarget) = orderRoadAddressLinks(sources, targets)
    // VIITE-1469 We cannot merge road addresses anymore before mapping, because we can have the need to map multiple roadways as target
    // The lengths may not be exactly equal: coefficient is to adjust that we advance both chains at the same relative speed

    /**
      * TargetCoefficient =>
      * Purpose of floating transfer is that source geometries are incorrect, so we should not trust in its geometry length, neither in its geometry
      * And so TargetCoefficient should be calculated through mValues (!= geometryLength) relation coefficient.
      */
    val targetCoeff = orderedSource.map(s => s.endMValue - s.startMValue).sum / orderedTarget.map(t => t.endMValue - t.startMValue).sum
    val runningLength = (orderedSource.scanLeft(0.0)((len, link) => len + (link.endMValue - link.startMValue)) ++
      orderedTarget.scanLeft(0.0)((len, link) => {
        len + targetCoeff * (link.endMValue - link.startMValue)
      })).map(setPrecision).distinct.sorted
    val pairs = runningLength.zip(runningLength.tail).map { case (st, end) =>
      val startSource = findStartLinearLocation(st, orderedSource)
      val endSource = findEndLinearLocationSource(end, orderedSource, startSource._1.id)
      val startTarget = findStartLinearLocation(st / targetCoeff, orderedTarget)
      val endTarget = findEndLinearLocationTarget(end / targetCoeff, orderedTarget, startTarget._1.linkId)
      (startSource, endSource, startTarget, endTarget)
    }
    pairs.map(x => formMapping(x._1._1, x._1._2, x._2._1, x._2._2, x._3._1, x._3._2, x._4._1, x._4._2))
  }

  private def setPrecision(d: Double) = {
    BigDecimal(d).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble
  }

  private def findStartLinearLocation(mValue: Double, links: Seq[RoadAddressLink]): (RoadAddressLink, Double) = {
    if (links.isEmpty)
      throw new InvalidAddressDataException(s"Unable to map linear locations $mValue beyond links end")
    val current = links.head
    if (Math.abs(current.length - mValue) < MinAllowedRoadAddressLength) {
      if (links.tail.nonEmpty)
        findStartLinearLocation(0.0, links.tail)
      else
        (current, setPrecision(applySideCode(current.length, current.length, current.sideCode)))
    } else if (current.length < mValue) {
      findStartLinearLocation(mValue - current.length, links.tail)
    } else {
      val dist = applySideCode(mValue, current.length, current.sideCode)
      (current, setPrecision(Math.min(Math.max(0.0, dist), current.length)))
    }
  }

  private def findEndLinearLocationSource(mValue: Double, links: Seq[RoadAddressLink], id: Long): (RoadAddressLink, Double) = {
    if (links.isEmpty)
      throw new InvalidAddressDataException(s"Unable to map linear locations $mValue beyond links end")
    val current = links.head
    if (current.id != id)
      findEndLinearLocationSource(mValue - current.length, links.tail, id)
    else {
      if (Math.abs(current.length - mValue) < MaxDistanceDiffAllowed) {
        (current, setPrecision(applySideCode(mValue, mValue, current.sideCode)))
      } else {
        val dist = applySideCode(mValue, current.length, current.sideCode)
        (current, setPrecision(Math.min(Math.max(0.0, dist), current.length)))
      }
    }
  }

  private def findEndLinearLocationTarget(mValue: Double, links: Seq[RoadAddressLink], linkId: Long): (RoadAddressLink, Double) = {
    if (links.isEmpty)
      throw new InvalidAddressDataException(s"Unable to map linear locations $mValue beyond links end")
    val current = links.head
    if (current.linkId != linkId)
      findEndLinearLocationTarget(mValue - current.length, links.tail, linkId)
    else {
      if (Math.abs(current.length - mValue) < MaxDistanceDiffAllowed) {
        (current, setPrecision(applySideCode(mValue, mValue, current.sideCode)))
      } else {
        val dist = applySideCode(mValue, current.length, current.sideCode)
        (current, setPrecision(Math.min(Math.max(0.0, dist), current.length)))
      }
    }
  }

  private def applySideCode(mValue: Double, linkLength: Double, sideCode: SideCode) = {
    sideCode match {
      case SideCode.AgainstDigitizing => linkLength - mValue
      case SideCode.TowardsDigitizing => mValue
      case _ => throw new InvalidAddressDataException(s"Unhandled sidecode $sideCode")
    }
  }

  /**
    * Take two sequences of road address links and order them so that the sequence covers the same road geometry
    * and logical addressing in the same order
    * @param sources Source road address links (floating)
    * @param targets Target road address links (missing addresses)
    * @return
    */
  def orderRoadAddressLinks(sources: Seq[RoadAddressLink], targets: Seq[RoadAddressLink]): (Seq[RoadAddressLink], Seq[RoadAddressLink]) = {
    def countTouching(p: Point, points: Seq[Point]) = {
      points.count(x => (x-p).to2D().length() < MaxDistanceDiffAllowed)
    }
    def hasIntersection(roadLinks: Seq[RoadAddressLink]): Boolean = {
      val endPoints = roadLinks.map(rl =>GeometryUtils.geometryEndpoints(rl.geometry))
      val flattened = endPoints.flatMap(pp => Seq(pp._1, pp._2))
      !endPoints.forall(ep =>
        countTouching(ep._1, flattened) < 3 && countTouching(ep._2, flattened) < 3
      )
    }
    def extending(link: RoadAddressLink, ext: RoadAddressLink) = {
      link.roadNumber == ext.roadNumber && link.roadPartNumber == ext.roadPartNumber &&
        link.trackCode == ext.trackCode && link.endAddressM == ext.startAddressM
    }
    def extendChainByAddress(ordered: Seq[RoadAddressLink], unordered: Seq[RoadAddressLink]): Seq[RoadAddressLink] = {
      if (ordered.isEmpty)
        return extendChainByAddress(Seq(unordered.head), unordered.tail)
      if (unordered.isEmpty)
        return ordered
      val (next, rest) = unordered.partition(u => extending(ordered.last, u))
      if (next.nonEmpty)
        extendChainByAddress(ordered ++ next, rest)
      else {
        val (previous, rest) = unordered.partition(u => extending(u, ordered.head))
        if (previous.isEmpty)
          throw new IllegalArgumentException("Non-contiguous road addressing")
        else
          extendChainByAddress(previous ++ ordered, rest)
      }
    }
    def extendChainByGeometry(ordered: Seq[RoadAddressLink], unordered: Seq[RoadAddressLink], sideCode: SideCode): Seq[RoadAddressLink] = {
      // First link gets the assigned side code
      if (ordered.isEmpty)
        return extendChainByGeometry(Seq(unordered.head.copy(sideCode=sideCode)), unordered.tail, sideCode)
      if (unordered.isEmpty) {
        return ordered
      }
      // Find a road address link that continues from current last link
      unordered.find(ral => GeometryUtils.areAdjacent(ral.geometry, ordered.last.geometry)) match {
        case Some(link) =>
          val sideCode = if (isSideCodeChange(link.geometry, ordered.last.geometry))
            switchSideCode(ordered.last.sideCode)
          else
            ordered.last.sideCode
          extendChainByGeometry(ordered ++ Seq(link.copy(sideCode=sideCode)), unordered.filterNot(link.equals), sideCode)
        case _ => throw new InvalidAddressDataException("Non-contiguous road target geometry")
      }
    }

    def sortLinks(startingPoint: Point, orderedSources: Seq[RoadAddressLink]): Seq[RoadAddressLink] = {
      if (isRoundabout(targets)) {
        val initSortLinks = targets.sortBy(t => minDistanceBetweenEndPoints(Seq(startingPoint), t.geometry))
        val startEndLinks = initSortLinks.take(2) //Takes the start and end links
        val vectors = startEndLinks.map(l => (l, GeometryUtils.firstSegmentDirection(if (GeometryUtils.areAdjacent(l.geometry.head, startingPoint)) l.geometry else l.geometry.reverse)))
        val (_, hVector) = vectors.head
        val (roundaboutEnd, _) = vectors.maxBy { case (_, vector) => hVector.angleXYWithNegativeValues(vector) }
        val roundaboutStart = startEndLinks.filterNot(_.linkId == roundaboutEnd.linkId).head
        val middleLinks = initSortLinks.filterNot(ad => ad.linkId == roundaboutStart.linkId || ad.linkId == roundaboutEnd.linkId)
        Seq(roundaboutStart) ++ middleLinks ++ Seq(roundaboutEnd)
      } else {
        // Partition target links by counting adjacency: anything that touches only the neighbor (and itself) is a starting or ending link
        val (endingLinks, middleLinks) = targets.partition(t => targets.count(t2 => GeometryUtils.areAdjacent(t.geometry, t2.geometry)) < 3)
        val sortedEndingLinks = endingLinks.sortBy(l => minDistanceBetweenEndPoints(Seq(startingPoint), l.geometry))
        if (distanceOfRoadAddressLinks(orderedSources, sortedEndingLinks)) {
          sortedEndingLinks.reverse ++ middleLinks
        } else {
          sortedEndingLinks ++ middleLinks
        }
      }
    }

      def distanceOfRoadAddressLinks(sourceLinks: Seq[RoadAddressLink], targetLinks: Seq[RoadAddressLink]): Boolean = {
        val movedGeom1 = getMovedGeomForAddresses(sourceLinks)
        val movedGeom2 = getMovedGeomForAddresses(targetLinks)
        (minDistanceBetweenEndPoints(movedGeom1.head.geometry, movedGeom2.head.geometry) + minDistanceBetweenEndPoints(movedGeom1.last.geometry, movedGeom2.last.geometry)) >
          (minDistanceBetweenEndPoints(movedGeom1.head.geometry, movedGeom2.last.geometry) + minDistanceBetweenEndPoints(movedGeom1.last.geometry, movedGeom2.head.geometry))
      }

    def getMovedGeomForAddresses(list: Seq[RoadAddressLink]): Seq[RoadAddressLink] = {
      val point = list.flatMap(_.geometry).minBy(p => p.distance2DTo(Point(0, 0)))
      list.map(link => link.copy(geometry = link.geometry.map(p => p.minus(point))))
    }


    val orderedSources = extendChainByAddress(Seq(sources.head), sources.tail)
    val startingPoint = orderedSources.head.sideCode match {
      case SideCode.TowardsDigitizing => orderedSources.head.geometry.head
      case SideCode.AgainstDigitizing => orderedSources.head.geometry.last
      case _ => throw new InvalidAddressDataException("Bad sidecode on source")
    }

    if (hasIntersection(targets))
      throw new IllegalArgumentException("Non-contiguous road addressing")

    val preSortedTargets = sortLinks(startingPoint, orderedSources)
    val startingSideCode = if (isDirectionMatch(orderedSources.head.geometry, preSortedTargets.head.geometry))
      orderedSources.head.sideCode
    else
      switchSideCode(orderedSources.head.sideCode)
    (orderedSources, extendChainByGeometry(Seq(), preSortedTargets, startingSideCode))
  }

  def invalidMapping(roadAddressMapping: RoadAddressMapping): Boolean = {
    roadAddressMapping.sourceStartM.isNaN || roadAddressMapping.sourceEndM.isNaN ||
      roadAddressMapping.targetStartM.isNaN || roadAddressMapping.targetEndM.isNaN
  }

  def findOnceConnectedLinks[T <: RoadAddressLink](seq: Iterable[T]): Map[Point, T] = {
    val pointMap = seq.flatMap(l => {
      val (p1, p2) = GeometryUtils.geometryEndpoints(l.geometry)
      Seq(p1 -> l, p2 -> l)
    }).groupBy(_._1).mapValues(_.map(_._2).toSeq.distinct)
    pointMap.keys.map{ p =>
      val links = pointMap.filterKeys(m => GeometryUtils.areAdjacent(p, m, MaxDistanceForConnectedLinks)).values.flatten
      p -> links
    }.toMap.filter(_._2.size == 1).mapValues(_.head)
  }

  def isRoundabout[T <: RoadAddressLink](seq: Iterable[T]): Boolean = {
    seq.nonEmpty && seq.map(_.trackCode).toSet.size == 1 && findOnceConnectedLinks(seq).isEmpty && seq.forall(pl =>
      seq.count(pl2 =>
        GeometryUtils.areAdjacent(pl.geometry, pl2.geometry, MaxDistanceForConnectedLinks)) == 3) // the link itself and two connected
  }
}

case class RoadAddressMapping(sourceLinkId: Long, targetLinkId: Long, sourceId: Long, sourceStartM: Double, sourceEndM: Double,
                              targetStartM: Double, targetEndM: Double, sourceGeom: Seq[Point], targetGeom: Seq[Point],
                              vvhTimeStamp: Option[Long] = None) {
  override def toString: String = {
    s"$sourceLinkId -> $targetLinkId: $sourceStartM-$sourceEndM ->  $targetStartM-$targetEndM, $sourceGeom -> $targetGeom"
  }

  /**
    * Test if this mapping matches the road address: Road address is on source link and overlap match is at least 99,9%
    * (overlap amount is the overlapping length divided by the smallest length)
    */
  def matches(roadAddress: RoadAddress, allRoadAddresses: Seq[RoadAddress]): Boolean = {
    // Transfer floatings that have sourceId defined
    if (allRoadAddresses.count(_.linkId == roadAddress.linkId) > 1 && sourceId > 0) {
      sourceId == roadAddress.id
    }
    // Apply changes has always sourceId = 0
    else {
      sourceLinkId == roadAddress.linkId &&
        (vvhTimeStamp.isEmpty || roadAddress.adjustedTimestamp < vvhTimeStamp.get) &&
        GeometryUtils.overlapAmount((roadAddress.startMValue, roadAddress.endMValue), (sourceStartM, sourceEndM)) > 0.001
    }
  }

  val sourceDelta = sourceEndM - sourceStartM
  val targetDelta = targetEndM - targetStartM
  val sourceLen: Double = Math.abs(sourceDelta)
  val targetLen: Double = Math.abs(targetDelta)
  val coefficient: Double = targetDelta/sourceDelta
  /**
    * interpolate location mValue on starting measure to target location
    * @param mValue Source M value to map
    */
  def interpolate(mValue: Double): Double = {
    if (DefloatMapper.withinTolerance(mValue, sourceStartM))
      targetStartM
    else if (DefloatMapper.withinTolerance(mValue, sourceEndM))
      targetEndM
    else {
      // Affine transformation: y = ax + b
      val a = coefficient
      val b = targetStartM - sourceStartM
      a * mValue + b
    }
  }
}
