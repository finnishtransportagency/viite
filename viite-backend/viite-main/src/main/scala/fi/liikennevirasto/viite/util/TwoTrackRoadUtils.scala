package fi.liikennevirasto.viite.util

import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.process.TrackSectionOrder
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{CalibrationPointType, Discontinuity, RoadAddressChangeType, Track}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.language.postfixOps

object TwoTrackRoadUtils {
  val projectLinkDAO    = new ProjectLinkDAO
  val linearLocationDAO = new LinearLocationDAO

  private val logger = LoggerFactory.getLogger(getClass)

  /** SplitPlsAtStatusChange searches iteratively link status changes and checks if other track
    * needs to split at the change point and makes splits to the other side projectlinks and creates
    * user defined calibration points.
    *
    * @note
    *   This is asymmetric such that a call must be made for both tracks.
    * @param trackToSearchStatusChanges
    *   A track to search for link status changes.
    * @param oppositeTrack
    *   The opposite track project links to split at status change.
    * @return
    *   Tuple3 updated project links and user defined calibration points.
    *   (trackToSearchStatusChanges[ProjectLink], oppositeTrack[ProjectLink],
    *   udcp[Option[UserDefinedCalibrationPoint] ])
    */
  def splitPlsAtStatusChange(trackToSearchStatusChanges: Seq[ProjectLink], oppositeTrack: Seq[ProjectLink]): (Seq[ProjectLink], Seq[ProjectLink], Seq[Option[UserDefinedCalibrationPoint]]) = {
    trackToSearchStatusChanges.tail.foldLeft(
      (
        Seq(trackToSearchStatusChanges.head),
        oppositeTrack,
        Seq.empty[Option[UserDefinedCalibrationPoint]]
      )
    ) { (prevPl_othersidePls_udcps, currentPl) =>
      // Check if status changes and do splitting with user defined calibration point if links on both sides do not split at the same position.
      if (currentPl.status != prevPl_othersidePls_udcps._1.last.status) {
        val splitted_pls = this.createCalibrationPointsAtStatusChange(
          Seq(prevPl_othersidePls_udcps._1.last),
          prevPl_othersidePls_udcps._2,
          currentPl.projectId
        )
        if (splitted_pls._1.nonEmpty) {
          val pls                         = splitted_pls._1.get
          val splitted_pls_link_id        = pls._1.linkId
          val splitted_pls_link_startAddr = pls._1.startAddrMValue
          (
            if (splitted_pls._3.isDefined)
              prevPl_othersidePls_udcps._1.init :+ splitted_pls._3.get._1 :+ currentPl
            else prevPl_othersidePls_udcps._1 :+ currentPl,
            (prevPl_othersidePls_udcps._2.filterNot(rl => {
              splitted_pls_link_id == rl.linkId && rl.startAddrMValue == splitted_pls_link_startAddr
            }) ++ Seq(pls._1, pls._2))
              .sortBy(_.startAddrMValue), // update udcp:s if new defined
            if (splitted_pls._2.nonEmpty)
              prevPl_othersidePls_udcps._3 ++ splitted_pls._2
            else prevPl_othersidePls_udcps._3
          )
        } else if (splitted_pls._2.nonEmpty) {
          (
            prevPl_othersidePls_udcps._1.init :+ splitted_pls._3.get._1 :+ currentPl,
            (prevPl_othersidePls_udcps._2.filterNot(rl => {
              splitted_pls._3.get._2.linkId == rl.linkId && rl.startAddrMValue == splitted_pls._3.get._2.startAddrMValue
            }) :+ splitted_pls._3.get._2).sortBy(_.startAddrMValue),
            prevPl_othersidePls_udcps._3 ++ splitted_pls._2
          )
        } else
          (
            prevPl_othersidePls_udcps._1 :+ currentPl,
            prevPl_othersidePls_udcps._2,
            prevPl_othersidePls_udcps._3
          )
      } else
        (
          prevPl_othersidePls_udcps._1 :+ currentPl,
          prevPl_othersidePls_udcps._2,
          prevPl_othersidePls_udcps._3
        )
    }
  }

  /* split start */
  private def createCalibrationPointsAtStatusChange(toUpdateLinks: Seq[ProjectLink], roadPartLinks: Seq[ProjectLink], projectId: Long): (Option[(ProjectLink, ProjectLink)], Seq[Option[UserDefinedCalibrationPoint]], Option[(ProjectLink, ProjectLink)]) = {
    val last = toUpdateLinks.maxBy(_.endAddrMValue)
    val roadPartCalibrationPoints =
      ProjectCalibrationPointDAO.fetchByRoadPart(
        projectId,
        last.roadPart
      )

    def splitAt(pl: ProjectLink, address: Long, endPoints: Map[Point, ProjectLink]): (ProjectLink, ProjectLink) = {
      val coefficient = (pl.endMValue - pl.startMValue) / (pl.endAddrMValue - pl.startAddrMValue)
      val splitMeasure = pl.startMValue + (math.abs(pl.startAddrMValue - address) * coefficient)
      val geometryBeforeSplitPoint  = getGeometryFromSplitMeasure(pl, endPoints, splitMeasure)
      val geometryAfterSplitPoint   = getNewGeometryFromSplitMeasure(pl, endPoints, splitMeasure)

      val calsForFirstPart =
        if (pl.calibrationPoints._1.isDefined)
          (
            pl.calibrationPoints._1.get.typeCode,
            CalibrationPointType.UserDefinedCP
          )
        else
          (
            CalibrationPointType.NoCP,
            CalibrationPointType.UserDefinedCP
          )
      val calsForSecondPart = getCalibrationPointsForSecondPart(pl)

      val splittedOriginalEndAddrMValue =
        pl.originalStartAddrMValue + (address - pl.startAddrMValue)

      val newProjectLinkBeforeSplit = pl.copy(
        discontinuity         = Discontinuity.Continuous,
        endAddrMValue         = address,
        originalEndAddrMValue = splittedOriginalEndAddrMValue,
        startMValue           = pl.startMValue,
        endMValue             = splitMeasure,
        calibrationPointTypes = calsForFirstPart,
        geometry              = geometryBeforeSplitPoint,
        status                = pl.status,
        geometryLength        = splitMeasure - pl.startMValue,
        connectedLinkId       = Some(pl.linkId)
      )

      val newProjectLinkAfterSplit = pl.copy(
        id                      = Sequences.nextProjectLinkId,
        startAddrMValue         = address,
        endAddrMValue           = pl.endAddrMValue,
        originalStartAddrMValue = splittedOriginalEndAddrMValue,
        originalEndAddrMValue   = pl.originalEndAddrMValue,
        startMValue             = splitMeasure,
        endMValue               = pl.endMValue,
        calibrationPointTypes   = calsForSecondPart,
        geometry                = geometryAfterSplitPoint,
        status                  = pl.status,
        geometryLength          = pl.geometryLength - (splitMeasure - pl.startMValue),
        connectedLinkId         = Some(pl.linkId)
      )

      (newProjectLinkBeforeSplit, newProjectLinkAfterSplit)
    }

    /** Create calibration points of type user defined
     *
     * @note
     *   Updates projectlinks' calibration point types to database.
     * @param startCP
     *   Start calibration point.
     * @param endCP
     *   End calibration point.
     * @param otherSideLinkStartCP
     *   The opposite track projectlink start calibration point.
     * @param otherSideLinkEndCP
     *   The opposite track projectlink end calibration point.
     * @param otherSideLink
     *   The opposite track projectlink.
     * @return
     *   Tuple2 (Seq[Option[UserDefinedCalibrationPoint] ], Option[(ProjectLink, ProjectLink)])
     *   A new calibration point for opposite track and a new for current track
     *   if does not exists, and corresponding projectlinks.
     *
     */
    def createCalibrationPoints(
      startCP:              CalibrationPointType,
      endCP:                CalibrationPointType,
      otherSideLinkStartCP: CalibrationPointType,
      otherSideLinkEndCP:   CalibrationPointType,
      otherSideLink:        ProjectLink
    ): (Seq[Option[UserDefinedCalibrationPoint]], Option[(ProjectLink, ProjectLink)]) = {
      val leftCalibrationPoint =
        if (
          !roadPartCalibrationPoints
            .exists(cp => cp.projectLinkId == last.id && cp.addressMValue == last.endAddrMValue)
        ) {
          logger.info(s"Splitting pl.id ${last.id}")
          val newUdcp = UserDefinedCalibrationPoint(
            NewIdValue,
            last.id,
            last.projectId,
            last.endMValue - last.startMValue,
            last.endAddrMValue
          )
          Some(newUdcp)
        } else None

      val rightCalibrationPoint =
        UserDefinedCalibrationPoint(
          NewIdValue,
          otherSideLink.id,
          otherSideLink.projectId,
          last.endAddrMValue - otherSideLink.startMValue,
          last.endAddrMValue
        )

      val newUcpId = !roadPartCalibrationPoints.exists(cp => {
        cp.projectLinkId == otherSideLink.id && cp.addressMValue == last.endAddrMValue
      })

      val updatedCpToLast = last.copy(calibrationPointTypes = (startCP, endCP))
      val updatedCpToOtherSideLink =
        otherSideLink.copy(calibrationPointTypes = (otherSideLinkStartCP, otherSideLinkEndCP))

      projectLinkDAO.updateProjectLinkCalibrationPoints(
        updatedCpToLast,
        (startCP, endCP)
      )
      projectLinkDAO.updateProjectLinkCalibrationPoints(
        updatedCpToOtherSideLink,
        (otherSideLinkStartCP, otherSideLinkEndCP)
      )

      if (newUcpId)
        (
          Seq(
            Some(rightCalibrationPoint),
            leftCalibrationPoint
          ),
          Some(updatedCpToLast, updatedCpToOtherSideLink)
        )
      else
        (Seq.empty[Option[UserDefinedCalibrationPoint]], None)
    }

    def splitAndSetCalibrationPointsAtEnd(last: ProjectLink, roadPartLinks: Seq[ProjectLink]): (Option[(ProjectLink, ProjectLink)], Seq[Option[UserDefinedCalibrationPoint]], Option[(ProjectLink, ProjectLink)]) = {
      if (last.track != Track.Combined && last.discontinuity == Discontinuity.Continuous) {

        val hasOtherSideLink = roadPartLinks.filter(pl =>
          pl.track  != Track.Combined &&
          pl.track  != last.track &&
          pl.status != RoadAddressChangeType.NotHandled &&
          (pl.startAddrMValue < last.endAddrMValue && pl.endAddrMValue >= last.endAddrMValue)
        )

        if (hasOtherSideLink.nonEmpty) {
          val mins = hasOtherSideLink.zipWithIndex.map(pl => (Math.abs(pl._1.endAddrMValue - last.endAddrMValue), pl._2))
          val indexOfMin = mins.minBy(_._1)._2
          val otherSideLink = hasOtherSideLink(indexOfMin)

          val startCP = last.startCalibrationPointType match {
            case CalibrationPointType.JunctionPointCP => CalibrationPointType.JunctionPointCP
            case CalibrationPointType.UserDefinedCP   => CalibrationPointType.UserDefinedCP
            case _               => CalibrationPointType.NoCP
          }
          val endCP = last.endCalibrationPointType match {
            case CalibrationPointType.JunctionPointCP => CalibrationPointType.JunctionPointCP
            case _               => CalibrationPointType.UserDefinedCP
          }
          val otherSideLinkStartCP = otherSideLink.startCalibrationPointType match {
            case CalibrationPointType.JunctionPointCP => CalibrationPointType.JunctionPointCP
            case CalibrationPointType.UserDefinedCP   => CalibrationPointType.UserDefinedCP
            case _               => CalibrationPointType.NoCP
          }
          val otherSideLinkEndCP = otherSideLink.endCalibrationPointType match {
            case CalibrationPointType.JunctionPointCP => CalibrationPointType.JunctionPointCP
            case _               => CalibrationPointType.UserDefinedCP
          }

          if (otherSideLink.endAddrMValue != last.endAddrMValue) {
            val section = findConnectedSection(otherSideLink, roadPartLinks.filter(pl => pl.endAddrMValue <= otherSideLink.endAddrMValue && pl.track == otherSideLink.track))
            val endPoints = TrackSectionOrder.findChainEndpoints(section)
            val (plPart1, plPart2) = splitAt(otherSideLink, last.endAddrMValue, endPoints)
            val (newCP: Seq[Option[UserDefinedCalibrationPoint]], cpUpdatedPls) =
              createCalibrationPoints(
                startCP,
                endCP,
                otherSideLinkStartCP,
                otherSideLinkEndCP,
                plPart1
              )
            (Some(plPart1, plPart2), newCP, cpUpdatedPls)
          } else {
            val (newCP: Seq[Option[UserDefinedCalibrationPoint]], cpUpdatedPls) =
              createCalibrationPoints(
                startCP,
                endCP,
                otherSideLinkStartCP,
                otherSideLinkEndCP,
                otherSideLink
              )
            (None, newCP, cpUpdatedPls)
          }
        } else (None, Seq.empty[Option[UserDefinedCalibrationPoint]], None)
      } else (None, Seq.empty[Option[UserDefinedCalibrationPoint]], None)
    }

    splitAndSetCalibrationPointsAtEnd(last, roadPartLinks)
  }

  def findConnectedSection(startLink: ProjectLink, allLinks: Seq[ProjectLink]): List[ProjectLink] = {
    def findSection(currentLink: ProjectLink, section: List[ProjectLink]): List[ProjectLink] = {
      val currentIndex = allLinks.indexOf(currentLink)
      val nextIndex = currentIndex - 1

      if (nextIndex < allLinks.length && nextIndex > 0) {
        val nextLink = allLinks(nextIndex)

        if (GeometryUtils.areAdjacent(currentLink.startingPoint, nextLink.endPoint))
          findSection(nextLink, currentLink :: section)
        else {
          currentLink :: section
        }
      } else {
        currentLink :: section
      }
    }

    findSection(startLink, List.empty[ProjectLink])
  }

  private def getCalibrationPointsForSecondPart(
    pl: _root_.fi.liikennevirasto.viite.dao.ProjectLink
  ) = {
    if (pl.calibrationPoints._2.isDefined)
      (
        CalibrationPointType.NoCP,
        pl.calibrationPoints._2.get.typeCode
      )
    else
      (
        CalibrationPointType.NoCP,
        CalibrationPointType.NoCP
      )
  }

  private def getNewGeometryFromSplitMeasure(
    pl: _root_.fi.liikennevirasto.viite.dao.ProjectLink,
    endPoints: _root_.scala.Predef.Map[
      _root_.fi.vaylavirasto.viite.geometry.Point,
      _root_.fi.liikennevirasto.viite.dao.ProjectLink
    ],
    splitMeasure: Double
  ): Seq[Point] = {
    if (pl.geometry.last == endPoints.last._1)
      GeometryUtils.truncateGeometry2D(
        pl.geometry,
        startMeasure = splitMeasure - pl.startMValue,
        endMeasure   = GeometryUtils.geometryLength(pl.geometry)
      )
    else
      GeometryUtils
        .truncateGeometry2D(
          pl.geometry.reverse,
          startMeasure = splitMeasure - pl.startMValue,
          endMeasure   = GeometryUtils.geometryLength(pl.geometry)
        )
        .reverse
  }

  private def getGeometryFromSplitMeasure(projectLink: ProjectLink, endPoints: Map[Point, ProjectLink], splitMeasure: Double): Seq[Point] = {
    if (projectLink.geometry.last == endPoints.last._1)
      GeometryUtils.truncateGeometry2D(
        projectLink.geometry,
        startMeasure = 0,
        endMeasure   = splitMeasure - projectLink.startMValue
      )
    else
      GeometryUtils
        .truncateGeometry2D(
          projectLink.geometry.reverse,
          startMeasure = 0,
          endMeasure   = splitMeasure - projectLink.startMValue
        )
        .reverse
  }
  /* split end */

  //  TODO VIITE-3120 The commented code below seems obsolete in current Viite app, commented out so they are available if needed after all (if you are deleting these lines, be sure to delete the other functions and code tagged with "TODO VIITE-3120")
//  /**
//   * Finds and creates splits in project links at the specified original address.
//   *
//   * This function splits a project link at the given original address and creates two new project links.
//   * The splitting operation is performed based on the provided original address and the sequence of project links.
//   *
//   * @param splitAddress The original address where the split should occur.
//   * @param projectLinks The sequence of ProjectLinks.
//   * @return An optional tuple containing two ProjectLink objects representing the split parts
//   *         if a split is found, otherwise None.
//   */
//  def findAndCreateSplitsAtOriginalAddress(splitAddress: Long, projectLinks: Seq[ProjectLink]): Option[(ProjectLink, ProjectLink)] = {
//    /**
//     * Splits a project link at the specified address and creates two new project links.
//     *
//     * This function splits a project link into two parts at the specified address.
//     * It calculates the split measure, geometries, calibration points, and other attributes for the split parts.
//     *
//     * @param projectLink        The ProjectLink object to be split.
//     * @param address   The address where the split should occur.
//     * @param endPoints A map containing endpoints of the connected chain sections.
//     * @return A tuple containing two ProjectLink objects representing the split parts.
//     */
//    def splitAt(projectLink: ProjectLink, address: Long, endPoints: Map[Point, ProjectLink]): (ProjectLink, ProjectLink) = {
//      val coefficient = (projectLink.endMValue - projectLink.startMValue) / (projectLink.originalEndAddrMValue - projectLink.originalStartAddrMValue)
//      val splitMeasure = projectLink.startMValue + (math.abs(projectLink.originalStartAddrMValue - address) * coefficient)
//      val geometryBeforeSplitPoint = getGeometryFromSplitMeasure(projectLink,endPoints, splitMeasure)
//      val geometryAfterSplitPoint = getNewGeometryFromSplitMeasure(projectLink, endPoints, splitMeasure)
//
//      val calsForFirstPart =
//        if (projectLink.calibrationPoints._1.isDefined)
//          (projectLink.calibrationPoints._1.get.typeCode,
//           CalibrationPointType.NoCP)
//        else
//          (CalibrationPointType.NoCP,
//           CalibrationPointType.NoCP)
//      val calsForSecondPart =
//        getCalibrationPointsForSecondPart(projectLink)
//      val splitEndAddrMValue =
//        projectLink.startAddrMValue + (address - projectLink.originalStartAddrMValue)
//
//      val newProjectLinkId = Sequences.nextProjectLinkId
//      val newProjectLinks = (
//                              projectLink.copy(
//                                discontinuity         = Discontinuity.Continuous,
//                                endAddrMValue         = splitEndAddrMValue,
//                                originalEndAddrMValue = address,
//                                startMValue           = projectLink.startMValue,
//                                endMValue             = splitMeasure,
//                                calibrationPointTypes = calsForFirstPart,
//                                geometry              = geometryBeforeSplitPoint,
//                                status                = projectLink.status,
//                                geometryLength        = splitMeasure - projectLink.startMValue,
//                                connectedLinkId       = Some(projectLink.linkId)
//                              ),
//                              projectLink.copy(
//                                id                      = newProjectLinkId,
//                                startAddrMValue         = splitEndAddrMValue,
//                                originalStartAddrMValue = address,
//                                startMValue             = splitMeasure,
//                                endMValue               = projectLink.endMValue,
//                                calibrationPointTypes   = calsForSecondPart,
//                                geometry                = geometryAfterSplitPoint,
//                                status                  = projectLink.status,
//                                geometryLength          = projectLink.geometryLength - (splitMeasure - projectLink.startMValue),
//                                connectedLinkId         = Some(projectLink.linkId)
//                              )
//                            )
//
//      (newProjectLinks._1, newProjectLinks._2)
//    }
//
//        val hasOtherSideLink = projectLinks.filter(pl =>
//          pl.originalTrack          != Track.Combined &&
//          pl.status                 != RoadAddressChangeType.NotHandled &&
//          pl.status                 != RoadAddressChangeType.New &&
//          pl.originalStartAddrMValue < splitAddress &&
//          pl.originalEndAddrMValue  > splitAddress
//        )
//
//        if (hasOtherSideLink.nonEmpty) {
//          val otherSideLink = hasOtherSideLink.head
//          val section = findConnectedSection(otherSideLink, projectLinks.filter(pl => pl.originalEndAddrMValue <= otherSideLink.originalEndAddrMValue && pl.originalEndAddrMValue != 0 && pl.originalTrack == otherSideLink.originalTrack))
//          val endPoints = TrackSectionOrder.findChainEndpoints(section)
//          if (otherSideLink.startAddrMValue + (splitAddress - otherSideLink.originalStartAddrMValue) == otherSideLink.endAddrMValue)
//            None // prevent creating project links with length of zero meters i.e. do not split in this scenario
//          else {
//            val (plPart1, plPart2) = splitAt(otherSideLink, splitAddress, endPoints)
//            Some(plPart1, plPart2)
//          }
//        } else None
//    }

  /**
   * This function recursively identifies and extracts a continuous segment of project links
   * with consistent original address properties from the projectLinksToProcess. It starts
   * by comparing the original address properties (track, road number, road part number,
   * address M values, and status) of the first project link with the last processed
   * project link. If they match, the current project link is added to the processed sequence.
   * If not, the function returns the processed sequence and the remaining sequence. This
   * process continues until the entire sequence is processed. The resulting tuple contains
   * the continuous original address section and the remaining project links.
   *
   * @param projectLinksToProcess       The sequence of project links to process.
   * @param processed The sequence of project links that have been processed.
   * @return A tuple containing the continuous original address section and the
   *         remaining project links.
   */
  @tailrec
  def getContinuousOriginalAddressSection(projectLinksToProcess: Seq[ProjectLink], processed: Seq[ProjectLink] = Seq()): (Seq[ProjectLink], Seq[ProjectLink]) = {
    if (projectLinksToProcess.isEmpty)
      (processed, projectLinksToProcess)
    else if (processed.isEmpty)
      getContinuousOriginalAddressSection(projectLinksToProcess.tail, Seq(projectLinksToProcess.head))
    else {
      val head     = projectLinksToProcess.head
      val track    = processed.last.originalTrack
      val roadPart = processed.last.originalRoadPart
      val address  = processed.last.originalEndAddrMValue
      val status   = processed.last.status
      if (head.originalTrack == track && head.originalRoadPart == roadPart && head.originalStartAddrMValue == address && head.status == status) {
        getContinuousOriginalAddressSection(projectLinksToProcess.tail, processed :+ head)
      } else {
        (processed, projectLinksToProcess)
      }
    }
  }

  def toProjectLinkSeq(plsTupleOptions: Seq[Option[(ProjectLink, ProjectLink)]]): Seq[ProjectLink] = plsTupleOptions collect toSequence flatten

  /**
   * Retrieves continuous original address sections by status from a sequence of project links.
   *
   * This function iterates over the project links, filters out links with 'New' status,
   * and retrieves continuous original address sections from the filtered links. The resulting
   * sequence contains the original end address M values of the continuous sections sorted
   * by their original start address M value.
   *
   * @param projectLinks The sequence of project links from which to retrieve continuous
   *                     original address sections by status.
   * @return A sequence of original end address M values representing continuous
   *         segments of project links filtered by status.
   */
  def findOriginalEndAddressesOfContinuousSectionsExcludingNewLinks(projectLinks: Seq[ProjectLink]): Seq[Long] = {
    @tailrec
    def findOriginalEndAddressesOfContinuousSections(pls: Seq[ProjectLink], result: Seq[Long] = Seq()): Seq[Long] = {
      if (pls.isEmpty) result
      else {
        val (continuousSection, remainingLinks) = getContinuousOriginalAddressSection(pls)
        findOriginalEndAddressesOfContinuousSections(remainingLinks, appendOriginalEndAddrMValue(continuousSection, result))
      }
    }
    val withoutNewLinks = projectLinks.filter(_.status != RoadAddressChangeType.New)
    val filteredProjectLinks = if (withoutNewLinks.nonEmpty) withoutNewLinks.init else Seq()
    findOriginalEndAddressesOfContinuousSections(filteredProjectLinks.sortBy(_.originalStartAddrMValue))
  }
  def appendOriginalEndAddrMValue(projectLinks: Seq[ProjectLink], sequenceOfAddrMValues: Seq[Long]): Seq[Long] = sequenceOfAddrMValues :+ projectLinks.last.originalEndAddrMValue

  val toSequence: PartialFunction[Option[(ProjectLink, ProjectLink)], Seq[ProjectLink]] = {
    case x: Some[(ProjectLink, ProjectLink)] => Seq(x.get._1, x.get._2)
  }

  //  TODO VIITE-3120 The commented code below seems obsolete in current Viite app, commented out so they are available if needed after all (if you are deleting these lines, be sure to delete the other functions and code tagged with "TODO VIITE-3120")
//  /**
//   * Combines and sorts the sequences of old and new project links.
//   *
//   * This function takes two sequences of project links, 'oldLinks' and 'newLinks', and combines them
//   * while ensuring there are no duplicate project links based on their IDs. Finally, it sorts
//   * the combined sequence based on the start address M value of each project link.
//   *
//   * @param oldLinks The sequence of old project links.
//   * @param newLinks The sequence of new project links.
//   * @return A sorted sequence of combined project links, where duplicates from 'oldLinks' have
//   *         been filtered out and the resulting sequence is sorted by start address M value.
//   */
//  def combineAndSort(oldLinks: Seq[ProjectLink], newLinks: Seq[ProjectLink]): Seq[ProjectLink] = (oldLinks.filterNot(pl => {
//    newLinks.map(_.id).contains(pl.id)
//  }) ++ newLinks).sortBy(_.startAddrMValue)

  /**
   * Filters out project links with a change type of "New".
   *
   * @param projectLinks The sequence of ProjectLink objects to filter.
   * @return A sequence of ProjectLink objects excluding those with a status of "New".
   */
  def filterOutNewLinks(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    val filtered = projectLinks.filter(_.status != RoadAddressChangeType.New)
    if (filtered.nonEmpty) filtered.init else Seq()
  }
  def filterOutNewAndCombinedLinks(pl: ProjectLink): Boolean =
    pl.status != RoadAddressChangeType.New && pl.track != Track.Combined

//  TODO VIITE-3120 The commented code below seems obsolete in current Viite app, commented out so they are available if needed after all (if you are deleting these lines, be sure to delete the other functions and code tagged with "TODO VIITE-3120")
//  /**
//   * Splits project links by original addresses.
//   *
//   * Given a sequence of project links and a sequence of original address ends,
//   * this function splits the project links at each original address end point.
//   * It adds the resulting splits to the projectLink sequence and sorts the sequence based on original addresses.
//   *
//   * @param projectLinks                  The sequence of ProjectLink objects to split.
//   * @param originalEndAddresses          The sequence of original address end points.
//   * @return                              A sequence of ProjectLink objects split by original addresses.
//   */
//  def splitByOriginalAddresses(projectLinks: Seq[ProjectLink], originalEndAddresses: Seq[Long]): Seq[ProjectLink] = {
//    originalEndAddresses.foldLeft(projectLinks) { (currentLinks, originalEndAddress) =>
//      val splitResultOption: Seq[Option[(ProjectLink, ProjectLink)]] = Seq(
//        TwoTrackRoadUtils.findAndCreateSplitsAtOriginalAddress(originalEndAddress, currentLinks)
//      )
//      combineAndSort(currentLinks, toProjectLinkSeq(splitResultOption))
//    }
//  }
}
