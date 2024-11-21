package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.util.{MissingTrackException, RoadAddressException}
import fi.liikennevirasto.viite.MaxDistanceForConnectedLinks
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao._
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point, Vector3d}
import fi.vaylavirasto.viite.model.CalibrationPointType.RoadAddressCP
import fi.vaylavirasto.viite.model.{AddrMRange, CalibrationPointType, Discontinuity, RoadAddressChangeType, SideCode, Track}

import scala.annotation.tailrec


object TrackSectionOrder {

  /**
    * Find the end points of a continuous or discontinuous project link chain.
    * When the chain has a discontinuous the project links will be order by proximity.
    *
    * @param projectLinks The project link sequence
    * @return Return a map with end point and the related project link
    */
  def findChainEndpoints(projectLinks: Seq[ProjectLink]): Map[Point, ProjectLink] = {
    case class ProjectLinkNonConnectedDistance(projectLink: ProjectLink, point: Point, distance: Double)
    case class ProjectLinkChain(sortedProjectLinks: Seq[ProjectLink], startPoint: Point, endPoint: Point)

    @tailrec
    def recursiveFindNearestProjectLinks(projectLinkChain: ProjectLinkChain, unprocessed: Seq[ProjectLink]): ProjectLinkChain = {
      def mapDistances(point: Point)(pl: ProjectLink): ProjectLinkNonConnectedDistance = {
        val (startPointPL, endPointPL) = pl.getEndPoints // get the start- and endPoint of the project link
        val (distanceFromPointToPLStartPoint, distanceFromPointToPLEndPoint) = (startPointPL.distance2DTo(point), endPointPL.distance2DTo(point)) // calculate the distance from the point to the start- and endPoint of the projectLink
        if (distanceFromPointToPLStartPoint < distanceFromPointToPLEndPoint)
          ProjectLinkNonConnectedDistance(pl, endPointPL, distanceFromPointToPLStartPoint)
        else
          ProjectLinkNonConnectedDistance(pl, startPointPL, distanceFromPointToPLEndPoint)
      }

      val startPointMinDistance = unprocessed.map(mapDistances(projectLinkChain.startPoint)).minBy(_.distance)
      val endPointMinDistance = unprocessed.map(mapDistances(projectLinkChain.endPoint)).minBy(_.distance)
      val calculatedEndPoint = if (endPointMinDistance.projectLink.status == RoadAddressChangeType.New && endPointMinDistance.projectLink.addrMRange.end == 0) endPointMinDistance.point else endPointMinDistance.projectLink.endPoint
      val (resultProjectLinkChain, newUnprocessed) =
        if (startPointMinDistance.distance > endPointMinDistance.distance
          || endPointMinDistance.projectLink.addrMRange.start == projectLinkChain.sortedProjectLinks.last.addrMRange.end
          && endPointMinDistance.projectLink.addrMRange.end != 0
          && projectLinkChain.sortedProjectLinks.last.addrMRange.end != 0)
        (projectLinkChain.copy(sortedProjectLinks = projectLinkChain.sortedProjectLinks :+ endPointMinDistance.projectLink, endPoint = calculatedEndPoint), unprocessed.filterNot(pl => pl.id == endPointMinDistance.projectLink.id))
      else
        (projectLinkChain.copy(sortedProjectLinks = startPointMinDistance.projectLink +: projectLinkChain.sortedProjectLinks, startPoint = startPointMinDistance.point), unprocessed.filterNot(pl => pl.id == startPointMinDistance.projectLink.id))
      newUnprocessed match {
        case Seq() => resultProjectLinkChain
        case _ => recursiveFindNearestProjectLinks(resultProjectLinkChain, newUnprocessed)
      }
    }

    projectLinks.size match {
      case 0 => Map()
      case 1 =>
        val (startPoint, endPoint) = projectLinks.head.getEndPoints
        Map(startPoint -> projectLinks.head, endPoint -> projectLinks.head)
      case _ =>
        val (projectLinksWithValues, newLinks) = projectLinks.partition(_.addrMRange.end != 0)
        val projectLinkChain =
          if (projectLinksWithValues.nonEmpty) {
            val (startPoint, endPoint) = projectLinksWithValues.head.getEndPointsOnlyBySide
            recursiveFindNearestProjectLinks(ProjectLinkChain(Seq(projectLinksWithValues.head), startPoint, endPoint), projectLinksWithValues.tail ++ newLinks)
          } else {
            val (startPoint, endPoint) = newLinks.head.getEndPointsOnlyBySide
            recursiveFindNearestProjectLinks(ProjectLinkChain(Seq(newLinks.head), startPoint, endPoint), newLinks.tail)
          }
        Map(projectLinkChain.startPoint -> projectLinkChain.sortedProjectLinks.head, projectLinkChain.endPoint -> projectLinkChain.sortedProjectLinks.last)
    }
  }

  /** Group together points of the given ProjectLinks having the same (x,y) coordinates.*/
   /* Used for loop road case. */
  def groupConnectionPoints(seq: Seq[ProjectLink]): Map[(Double, Double), Seq[Point]] = {
    seq.flatMap(l => {
      val (p1, p2) = l.getEndPoints
      Seq(p1, p2)
    }).groupBy(p => (p.x, p.y))
  }

  /* Used for loop road case.*/
  def hasTripleConnectionPoint(seq: Seq[ProjectLink]): Boolean = {
    val pointMap = groupConnectionPoints(seq)
    pointMap.exists(_._2.size == 3)
  }

  /* Used for loop road case.*/
  def getTripleConnectionPoint(seq: Seq[ProjectLink]): Option[Point] = {
    val pointMap = groupConnectionPoints(seq)
    val triplePoint = pointMap.find(_._2.size == 3)
    if (triplePoint.isDefined)
      Some(Point(triplePoint.get._1._1, triplePoint.get._1._2))
    else
      None
  }

  /* Used for loop road case.*/
  def getUnConnectedPoint(seq: Seq[ProjectLink]): Option[Point] = {
    val pointMap = groupConnectionPoints(seq)
    val unConnectedPoint = pointMap.find(_._2.size == 1)
    if (unConnectedPoint.isDefined)
      Some(Point(unConnectedPoint.get._1._1, unConnectedPoint.get._1._2))
    else
      None
  }

  /**
   * Finds singly connected links in a sequence of BaseRoadAddress instances.
   * Singly connected links are those that are connected to only one other link in the sequence.
   *
   * @param seq An iterable collection of BaseRoadAddress instances representing road segments.
   * @return A map where each Point represents the start or end point of a road segment,
   *         mapped to the corresponding BaseRoadAddress instance that it is connected to.
   */
  def findSinglyConnectedLinks[T <: BaseRoadAddress](seq: Iterable[T]): Map[Point, T] = {

    // Creates a mapping of (startPoint -> BaseRoadAddress, endPoint -> BaseRoadAddress
    // Then groups it by points and reduces the mapped values to the distinct BaseRoadAddresses
    val pointMap = seq.flatMap(l => {
      val (p1, p2) = l.getEndPoints
      Seq(p1.copy(z = 0) -> l, p2.copy(z = 0) -> l)
    }).groupBy(_._1).mapValues(_.map(_._2).toSeq.distinct)
    pointMap.keys.map { p =>
      val links = pointMap.filterKeys(m => GeometryUtils.areAdjacent(p, m, MaxDistanceForConnectedLinks)).values.flatten
      p -> links
    }.toMap.filter(_._2.size == 1).mapValues(_.head)

  }

  def isRoundabout[T <: BaseRoadAddress](seq: Iterable[T]): Boolean = {
    seq.nonEmpty && seq.map(_.track).toSet.size == 1 && findSinglyConnectedLinks(seq).isEmpty && seq.forall(pl =>
      seq.count(pl2 =>
        GeometryUtils.areAdjacent(pl.geometry, pl2.geometry, MaxDistanceForConnectedLinks)) == 3) // the link itself and two connected
  }

  /**
    * A sequence of points is turning counterclockwise if every segment between them is turning left looking from the
    * center of the points
    *
    * @param seq Points
    * @return
    */
  def isCounterClockwise(seq: Seq[Point]): Boolean = {
    val midPoint = seq.tail.fold(seq.head) { case (p1, p2) => p1.copy(x = p1.x + p2.x, y = p1.y + p2.y, z = 0) }.toVector.scale(1.0 / seq.size)
    // Create vectors from midpoint to p and from p to next
    val extended = seq ++ Seq(seq.head) // Take the last point to be used as the ending point as well
    val vectors = extended.map(p => p.toVector - midPoint).zip(seq.zip(extended.tail).map { case (p1, p2) =>
      p2 - p1
    })
    vectors.forall { case (x, y) =>
      // Using cross product: Right hand rule -> z is positive if y is turning left in relative direction of x
      x.cross(y).z > 0.0
    }
  }

  def mValueRoundabout(seq: Seq[ProjectLink]): Seq[ProjectLink] = {
    /* None            = No change. Therefore for calibration points
       Some(None)      = Clear if there was any
       Some(Some(...)) = Set to this value
      */
    // TODO Check that this works with André's roundabout case
    def adjust(pl: ProjectLink, sideCode: Option[SideCode] = None, startAddrMValue: Option[Long] = None,
               endAddrMValue: Option[Long] = None,
               startCalibrationPoint: Option[Option[ProjectCalibrationPoint]] = None,
               endCalibrationPoint: Option[Option[ProjectCalibrationPoint]] = None) = {
      pl.copy(addrMRange = AddrMRange(startAddrMValue.getOrElse(pl.addrMRange.start), endAddrMValue.getOrElse(pl.addrMRange.end)), sideCode = sideCode.getOrElse(pl.sideCode), calibrationPointTypes = (pl.startCalibrationPointType, pl.endCalibrationPointType))
    }

    def firstPoint(pl: ProjectLink) = {
      pl.sideCode match {
        case SideCode.TowardsDigitizing => pl.geometry.head
        case SideCode.AgainstDigitizing => pl.geometry.last
        case _ => throw new InvalidGeometryException("SideCode was not decided")
      }
    }

    @tailrec
    def recursive(currentPoint: Point, ready: Seq[ProjectLink], unprocessed: Seq[ProjectLink]): Seq[ProjectLink] = {
      if (unprocessed.isEmpty) {
        // Put calibration point at the end
        val last = ready.last
        ready.init ++ Seq(adjust(last, startCalibrationPoint = Some(None), endCalibrationPoint = Some(Some(
          ProjectCalibrationPoint(last.linkId, if (last.sideCode ==  SideCode.AgainstDigitizing) 0.0 else last.geometryLength, last.addrMRange.end, last.endCalibrationPointType)))))
      }
      else {
        val hit = unprocessed.find(pl => GeometryUtils.areAdjacent(pl.geometry, currentPoint, MaxDistanceForConnectedLinks))
          .getOrElse(throw new InvalidGeometryException("Roundabout was not connected"))
        val nextPoint = getOppositeEnd(hit, currentPoint)
        val sideCode = if (hit.geometry.last == nextPoint) SideCode.TowardsDigitizing else SideCode.AgainstDigitizing
        val prevAddrM = ready.last.addrMRange.end
        val endAddrM = hit.status match {
          case RoadAddressChangeType.NotHandled | RoadAddressChangeType.Unchanged | RoadAddressChangeType.Transfer | RoadAddressChangeType.Renumeration =>
            ready.last.addrMRange.end + (hit.addrMRange.length)
          case RoadAddressChangeType.New =>
            prevAddrM + Math.round(hit.geometryLength)
          case _ =>
            hit.addrMRange.end
        }
        recursive(nextPoint, ready ++ Seq(adjust(hit, sideCode = Some(sideCode), startAddrMValue = Some(prevAddrM),
          endAddrMValue = Some(endAddrM), startCalibrationPoint = Some(None), endCalibrationPoint = Some(None))),
          unprocessed.filter(_ != hit))
      }
    }

    val firstLink = seq.head // First link is defined by end user and must be always first
    // Put calibration point at the beginning
    val ordered = recursive(firstLink.geometry.last, Seq(adjust(firstLink, sideCode = Some(SideCode.TowardsDigitizing),
      startAddrMValue = Some(0L), endAddrMValue =
        Some(if (firstLink.status == RoadAddressChangeType.New)
          Math.round(firstLink.geometryLength)
        else
          firstLink.addrMRange.length),
      startCalibrationPoint = Some(Some(ProjectCalibrationPoint(firstLink.linkId, 0.0, 0L, firstLink.startCalibrationPointType))),
      endCalibrationPoint = Some(None))), seq.tail)
    if (isCounterClockwise(ordered.map(firstPoint)))
      ordered
    else {
      val reOrdered = recursive(firstLink.geometry.head,

        Seq(adjust(firstLink, sideCode = Some(SideCode.AgainstDigitizing),
          startAddrMValue = Some(0L), endAddrMValue =
            Some(if (firstLink.status == RoadAddressChangeType.New)
              Math.round(firstLink.geometryLength)
            else
              firstLink.addrMRange.length),
          startCalibrationPoint = Some(Some(ProjectCalibrationPoint(firstLink.linkId, firstLink.geometryLength, 0L, firstLink.startCalibrationPointType))),
          endCalibrationPoint = Some(None))),
        seq.tail)
      if (isCounterClockwise(reOrdered.map(firstPoint)))
        reOrdered
      else
        throw new InvalidGeometryException("Roundabout was not round")
    }
  }

  private def getOppositeEnd(link: BaseRoadAddress, point: Point): Point = {
    val (startPoint, endPoint) = link.getEndPoints

    // Calculate distances from the provided point to both start and end points
    val distanceToStart = startPoint.distance2DTo(point)
    val distanceToEnd = endPoint.distance2DTo(point)

    // Determine the opposite end based on the shorter distance from the provided point
    if (distanceToStart < distanceToEnd) endPoint else startPoint
  }

  def orderProjectLinksTopologyByGeometry(startingPoints: (Point, Point), list: Seq[ProjectLink]): (Seq[ProjectLink], Seq[ProjectLink]) = {

    def getConnectionPoint(lastLink: ProjectLink, projectLinks: Seq[ProjectLink]): Point =
      GeometryUtils.connectionPoint(projectLinks.map(_.geometry) :+ lastLink.geometry, MaxDistanceForConnectedLinks).getOrElse(throw new Exception("Candidates should have at least one connection point"))

    def getGeometryFirstSegmentVector(connectionPoint: Point, projectLink: ProjectLink): (ProjectLink, Vector3d) =
      (projectLink, GeometryUtils.firstSegmentDirection(if (GeometryUtils.areAdjacent(projectLink.geometry.head, connectionPoint)) projectLink.geometry else projectLink.geometry.reverse))

    def getGeometryFirstSegmentVectors(connectionPoint: Point, projectLinks: Seq[ProjectLink]): Seq[(ProjectLink, Vector3d)] =
      projectLinks.map(pl => getGeometryFirstSegmentVector(connectionPoint, pl))

    def getGeometryLastSegmentVector(connectionPoint: Point, projectLink: ProjectLink): (ProjectLink, Vector3d) =
      (projectLink, GeometryUtils.lastSegmentDirection(if (GeometryUtils.areAdjacent(projectLink.geometry.last, connectionPoint)) projectLink.geometry else projectLink.geometry.reverse))

    def pickRightMost(lastLink: ProjectLink, candidates: Seq[ProjectLink]): ProjectLink = {
      val cPoint = GeometryUtils.connectionPoint(candidates.map(_.geometry) :+ lastLink.geometry, MaxDistanceForConnectedLinks).getOrElse(throw new Exception("Candidates should have at least one connection point"))
      val vectors = candidates.map(pl => (pl, GeometryUtils.firstSegmentDirection(if (GeometryUtils.areAdjacent(pl.geometry.head, cPoint)) pl.geometry else pl.geometry.reverse)))
      val (_, hVector) = vectors.head
      val (candidate, _) = vectors.maxBy { case (_, vector) => hVector.angleXYWithNegativeValues(vector) }
      candidate
    }

    def pickForwardMost(lastLink: ProjectLink, candidates: Seq[ProjectLink]): ProjectLink = {
      val cPoint = getConnectionPoint(lastLink, candidates)
      val candidateVectors = getGeometryFirstSegmentVectors(cPoint, candidates)
      val (_, lastLinkVector) = getGeometryLastSegmentVector(cPoint, lastLink)
      val (candidate, _) = candidateVectors.minBy { case (_, vector) => Math.abs(lastLinkVector.angleXYWithNegativeValues(vector)) }
      candidate
    }

    def pickSameTrack(lastLinkOption: Option[ProjectLink], candidates: Seq[ProjectLink]): Option[ProjectLink] = {
      val lastTrack = lastLinkOption.map(_.track)
      val connectedLinks = candidates.filter(link => lastTrack.contains(link.track))
      connectedLinks.size match {
        case 0 => None
        case 1 => connectedLinks.headOption
        case _ =>
          val nextCandidates = connectedLinks.filter(connectedLink => lastLinkOption.get.addrMRange.end == connectedLink.addrMRange.start && lastLinkOption.get.discontinuity == Discontinuity.Continuous)
          if (nextCandidates.nonEmpty && nextCandidates.size == 1) {
            nextCandidates.headOption
          }
          else
            None
      }
    }

    @tailrec
    def recursiveFindAndExtend(currentPoint: Point, ready: Seq[ProjectLink], unprocessed: Seq[ProjectLink], oppositeTrack: Seq[ProjectLink]): Seq[ProjectLink] = {
      if (unprocessed.isEmpty)
        ready
      else {
        val connected = unprocessed.filter(pl => GeometryUtils.minimumDistance(currentPoint,
          pl.getEndPoints) < MaxDistanceForConnectedLinks)

        val (nextPoint, nextLink): (Point, ProjectLink) = connected.size match {
          case 0 =>
            val subsetB = findSinglyConnectedLinks(unprocessed)
            if (subsetB.nonEmpty) {
              val (closestPoint, link) = subsetB.minBy(b => (currentPoint - b._1).length())
              (getOppositeEnd(link, closestPoint), link)
            }
            else { // Should be here only if the geometries are the same, i.e. in tests geometries may be all zeros -> just select the first.
              (unprocessed.head.getEndPoints._1, unprocessed.head)
            }
          case 1 =>
            (getOppositeEnd(connected.head, currentPoint), connected.head)
          case 2 => val nextLinkSameTrack = pickSameTrack(ready.lastOption, connected)
            if (nextLinkSameTrack.nonEmpty) {
              (getOppositeEnd(nextLinkSameTrack.get, currentPoint), nextLinkSameTrack.get)
            } else {
              if (findSinglyConnectedLinks(unprocessed).exists(b => {
                (currentPoint - b._1).length() <= fi.liikennevirasto.viite.MaxJumpForSection
              })) {
                val (nPoint, link) = findSinglyConnectedLinks(unprocessed).filter(b => {
                  (currentPoint - b._1).length() <= fi.liikennevirasto.viite.MaxJumpForSection
                }).minBy(b => {
                  (currentPoint - b._1).length()
                })
                (getOppositeEnd(link, nPoint), link)
              } else {
                val nextLinkTogo: Seq[ProjectLink] =
                  if (connected.forall(_.sideCode != SideCode.Unknown))
                    connected.filter(pl => GeometryUtils.to2DGeometry(pl.startingPoint) != GeometryUtils.to2DGeometry(currentPoint))
                  else Seq()
                val l = if (ready.isEmpty) connected.head else if (nextLinkTogo.nonEmpty) nextLinkTogo.head else pickRightMost(ready.last, connected)
                (getOppositeEnd(l, currentPoint), l)
              }
            }
          case _ =>
            val l = if (ready.isEmpty) connected.head else pickForwardMost(ready.last, connected)
            (getOppositeEnd(l, currentPoint), l)
        }
        // Check if link direction needs to be turned and choose next point
        val sideCode = (nextLink.geometry.last == nextPoint, nextLink.reversed && (unprocessed ++ ready).size == 1) match {
          case (false, false) | (true, true) =>
            SideCode.AgainstDigitizing
          case _ =>
            SideCode.TowardsDigitizing
        }
        /* Sets reverse flag if sidecode change occurs with road/roadpart change. */

        def setReverseFlag() = {
          if (sideCode != nextLink.sideCode && (nextLink.roadPart.roadNumber != nextLink.originalRoadPart.roadNumber || nextLink.roadPart.partNumber != nextLink.originalRoadPart.partNumber))
            !nextLink.reversed
          else
            nextLink.reversed
        }

        recursiveFindAndExtend(nextPoint, ready ++ Seq(nextLink.copy(sideCode = sideCode, reversed = setReverseFlag())), unprocessed.filterNot(pl => pl == nextLink), oppositeTrack)
      }
    }

    val track01 = list.filter(_.track != Track.LeftSide)
    val track02 = list.filter(_.track != Track.RightSide)

    (recursiveFindAndExtend(startingPoints._1, Seq(), track01, track02),
      recursiveFindAndExtend(startingPoints._2, Seq(), track02, track01))
  }

  /**
   * Creates combined sections from project links on the right and left tracks.
   *
   * This function groups project links into track sections and combines corresponding sections from
   * the right and left tracks into combined sections.
   *
   * @param rightLinks Sequence of project links on the right track.
   * @param leftLinks  Sequence of project links on the left track.
   * @return Sequence of combined sections derived from the input project links.
   * @throws InvalidAddressDataException if the input data is invalid or missing.
   */
  def createCombinedSections(rightLinks: Seq[ProjectLink], leftLinks: Seq[ProjectLink]): Seq[CombinedSection] = {
    def fromProjectLinks(s: Seq[ProjectLink]): TrackSection = {
      val pl = s.head
      TrackSection(pl.roadPart, pl.track, s.map(_.geometryLength).sum, s)
    }

    def groupIntoSections(seq: Seq[ProjectLink]): Seq[TrackSection] = {
      if (seq.isEmpty)
        throw new InvalidAddressDataException("Missing track")
      val changePoints = seq.zip(seq.tail).filter { case (pl1, pl2) => pl1.track != pl2.track }
      seq.foldLeft(Seq(Seq[ProjectLink]())) { case (tracks, pl) =>
        if (changePoints.exists(_._2 == pl)) {
          Seq(Seq(pl)) ++ tracks
        } else {
          Seq(tracks.head ++ Seq(pl)) ++ tracks.tail
        }
      }.reverse.map(fromProjectLinks)
    }

    val rightSections = groupIntoSections(rightLinks)
    val leftSections = groupIntoSections(leftLinks)
    createCombinedSectionss(rightSections, leftSections)
  }

  /**
   * Creates combined sections from sequences of track sections on the right and left tracks.
   *
   * This function combines corresponding track sections from the right and left tracks into
   * combined sections, considering their geometries and lengths.
   *
   * @param rightSections Sequence of track sections on the right track.
   * @param leftSections  Sequence of track sections on the left track.
   * @return Sequence of combined sections derived from the input track sections.
   * @throws MissingTrackException if the left track is missing starting project links for a combined section.
   * @throws RoadAddressException  if there is an incorrect track code encountered.
   */
  def createCombinedSectionss(rightSections: Seq[TrackSection], leftSections: Seq[TrackSection]): Seq[CombinedSection] = {

    def combineSections(rightSection: Seq[TrackSection], leftSection: Seq[TrackSection]): Seq[CombinedSection] = {
      rightSection.map { r =>
        r.track match {
          case Track.Combined =>
            // Average address values for track lengths:
            val l = leftSection.filter(_.track == Track.Combined).minBy(l =>
              Math.min(
                Math.min(l.startGeometry.distance2DTo(r.startGeometry), l.startGeometry.distance2DTo(r.endGeometry)),
                Math.min(l.endGeometry.distance2DTo(r.startGeometry), l.endGeometry.distance2DTo(r.endGeometry))))
            CombinedSection(r.startGeometry, r.endGeometry, r.geometryLength, l, r)
          case Track.RightSide => if (leftSection.exists(_.track == Track.LeftSide)) {
            val l = leftSection.filter(_.track == Track.LeftSide).minBy(l =>
              Math.min(
                Math.min(l.startGeometry.distance2DTo(r.startGeometry), l.startGeometry.distance2DTo(r.endGeometry)),
                Math.min(l.endGeometry.distance2DTo(r.startGeometry), l.endGeometry.distance2DTo(r.endGeometry))))
            CombinedSection(r.startGeometry, r.endGeometry,.5 * (r.geometryLength + l.geometryLength), l, r)
          } else
            throw new MissingTrackException("Missing left track starting project links")
          case Track.LeftSide => if (leftSection.exists(_.track == Track.RightSide)) {
            val l = leftSection.filter(_.track == Track.RightSide).minBy(l =>
              Math.min(
                Math.min(l.startGeometry.distance2DTo(r.startGeometry), l.startGeometry.distance2DTo(r.endGeometry)),
                Math.min(l.endGeometry.distance2DTo(r.startGeometry), l.endGeometry.distance2DTo(r.endGeometry))))
            CombinedSection(r.startGeometry, r.endGeometry,.5 * (r.geometryLength + l.geometryLength), l, r)
          } else
            throw new MissingTrackException("Missing left track starting project links")
          case _ =>
            throw new RoadAddressException(s"Incorrect track code ${r.track}")
        }
      }
    }

    combineSections(rightSections, leftSections)
  }

  /**
    * Re-adds the calibration points to the project links after the calculation. The calibration points are gotten via the information on our current linear locations.
    *
    * @param leftProjectLinks            : the result of the calculation - track 2
    * @param rightProjectLinks           : the result of the calculation - track 1
    * @param userDefinedCalibrationPoint : Map[Long, UserDefinedCalibrationPoint] - Map of linear location id -> UserDefinedCalibrationPoint
    * @return
    */
  def setCalibrationPoints(leftProjectLinks: Seq[ProjectLink], rightProjectLinks: Seq[ProjectLink], userDefinedCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): (Seq[ProjectLink], Seq[ProjectLink]) = {
    (setOnSideCalibrationPoints(leftProjectLinks, userDefinedCalibrationPoint), setOnSideCalibrationPoints(rightProjectLinks, userDefinedCalibrationPoint))
  }

  protected def setOnSideCalibrationPoints(initialProjectLinks: Seq[ProjectLink], userCalibrationPoint: Map[Long, UserDefinedCalibrationPoint]): Seq[ProjectLink] = {

    if (initialProjectLinks.head.status == RoadAddressChangeType.NotHandled)
      initialProjectLinks
    else
      initialProjectLinks.size match {
        case 0 => initialProjectLinks
        case 1 =>
          initialProjectLinks.map(pl => setCalibrationPoint(pl, userCalibrationPoint.get(pl.id),
            hasStartCP = true, hasEndCP = true, RoadAddressCP, RoadAddressCP))
        case _ =>
          val projectLinks = initialProjectLinks.map(p => p.copy(calibrationPointTypes =
                                                    (if (p.startCalibrationPointType != RoadAddressCP) p.startCalibrationPointType else CalibrationPointType.NoCP,
                                                      if (p.endCalibrationPointType != RoadAddressCP) p.endCalibrationPointType else CalibrationPointType.NoCP)))
          val projectLinksWithRoadAddressCPs = Seq(setCalibrationPoint(projectLinks.head, userCalibrationPoint.get(projectLinks.head.id),
            hasStartCP = true, hasEndCP = projectLinks.tail.head.calibrationPoints._1.isDefined,
            RoadAddressCP, projectLinks.tail.head.startCalibrationPointType)) ++ projectLinks.init.tail ++ Seq(setCalibrationPoint(projectLinks.last,
            userCalibrationPoint.get(projectLinks.last.id),
            projectLinks.init.last.calibrationPoints._2.isDefined, hasEndCP = true, projectLinks.init.last.endCalibrationPointType, RoadAddressCP))

          val linksWithCPs: Seq[ProjectLink] = projectLinksWithRoadAddressCPs.foldLeft(Seq.empty[ProjectLink]) { (list, pl) =>
            if (list.isEmpty) {
              Seq(pl)
            } else {
              if (list.last.administrativeClass != pl.administrativeClass || list.last.track != pl.track || list.last.roadPart != pl.roadPart ||
                list.last.discontinuity == Discontinuity.Discontinuous ||
                list.last.discontinuity == Discontinuity.MinorDiscontinuity) {
                val last = list.last
                list.dropRight(1) ++ Seq(setCalibrationPoint(last, None, last.calibrationPoints._1.nonEmpty, hasEndCP = true, last.startCalibrationPointType, RoadAddressCP),
                  setCalibrationPoint(pl, None, hasStartCP = true, hasEndCP = pl.calibrationPoints._2.nonEmpty, RoadAddressCP, pl.endCalibrationPointType))
              } else {
                list :+ pl
              }
            }
          }
          linksWithCPs
      }
  }

  protected def setCalibrationPoint(pl: ProjectLink, userCalibrationPoint: Option[UserDefinedCalibrationPoint],
                                    hasStartCP: Boolean, hasEndCP: Boolean,
                                    startType: CalibrationPointType, endType: CalibrationPointType): ProjectLink = {
    val startCP = if (hasStartCP) startType else CalibrationPointType.NoCP
    val endCP = if (hasEndCP) endType else CalibrationPointType.NoCP
    pl.copy(calibrationPointTypes = (startCP, endCP))
  }


}

class InvalidGeometryException(s: String) extends RuntimeException {
}
