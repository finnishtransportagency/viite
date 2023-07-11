package fi.liikennevirasto.viite.util

import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.process.{RoadwayAddressMapper, TrackSectionOrder}
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{CalibrationPointType, Discontinuity, RoadAddressChangeType, Track}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.language.postfixOps

object TwoTrackRoadUtils {
  val projectLinkDAO    = new ProjectLinkDAO
  val roadwayDAO        = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO

  val roadwayAddressMapper =
    new RoadwayAddressMapper(
      roadwayDAO:        RoadwayDAO,
      linearLocationDAO: LinearLocationDAO
    )
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
    *   The opposite track projectlinks to split at status change.
    * @return
    *   Tuple3 updated projectlinks and user defined calibration points.
    *   (trackToSearchStatusChanges[ProjectLink], oppositeTrack[ProjectLink],
    *   udcp[Option[UserDefinedCalibrationPoint] ])
    */
  def splitPlsAtStatusChange(
    trackToSearchStatusChanges: Seq[ProjectLink],
    oppositeTrack:              Seq[ProjectLink]
  ): (
    Seq[ProjectLink],
    Seq[ProjectLink],
    Seq[Option[UserDefinedCalibrationPoint]]
  ) = {
    trackToSearchStatusChanges.tail.foldLeft(
      (
        Seq(trackToSearchStatusChanges.head),
        oppositeTrack,
        Seq.empty[Option[UserDefinedCalibrationPoint]]
      )
    ) { (prevPl_othersidePls_udcps, currentPl) =>
      // Check if status changes and do spliting with user defined calibration point if links on both sides do not split at the same position.
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
  private def createCalibrationPointsAtStatusChange(
    toUpdateLinks: Seq[ProjectLink],
    roadPartLinks: Seq[ProjectLink],
    projectId:     Long
  ): (
    Option[(ProjectLink, ProjectLink)],
    Seq[Option[UserDefinedCalibrationPoint]],
    Option[(ProjectLink, ProjectLink)]
  ) = {
    val last = toUpdateLinks.maxBy(_.endAddrMValue)
    val roadPartCalibrationPoints =
      ProjectCalibrationPointDAO.fetchByRoadPart(
        projectId,
        last.roadNumber,
        last.roadPartNumber
      )

    def splitAt(
      pl:        ProjectLink,
      address:   Long,
      endPoints: Map[Point, ProjectLink]
    ): (ProjectLink, ProjectLink) = {
      val coefficient =
          (pl.endMValue - pl.startMValue) / (pl.endAddrMValue - pl.startAddrMValue)
      val splitMeasure =
          pl.startMValue + (math.abs(
            pl.startAddrMValue - address
          ) * coefficient)
      val geom         = getGeometryFromSplitMeasure(pl, endPoints, splitMeasure)
      val new_geometry = getNewGeometryFromSplitMeasure(pl, endPoints, splitMeasure)

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
      val addressLength =
       pl.endAddrMValue - address

      val splittedOriginalEndAddrMValue =
        pl.originalStartAddrMValue + (address - pl.startAddrMValue)

      val newPlId = Sequences.nextProjectLinkId
      val newProjectLinks = (
        pl.copy(discontinuity         = Discontinuity.Continuous, endAddrMValue         = address, originalEndAddrMValue = splittedOriginalEndAddrMValue, startMValue           = pl.startMValue, endMValue             = splitMeasure, calibrationPointTypes = calsForFirstPart, geometry              = geom, status                = pl.status, geometryLength        = splitMeasure - pl.startMValue, connectedLinkId       = Some(pl.linkId)),
        pl.copy(id = NewIdValue, startAddrMValue         = address, endAddrMValue           = pl.endAddrMValue, originalStartAddrMValue = splittedOriginalEndAddrMValue, originalEndAddrMValue   = pl.originalEndAddrMValue, startMValue             = splitMeasure, endMValue               = pl.endMValue, calibrationPointTypes   = calsForSecondPart, geometry                = new_geometry, status                  = pl.status, geometryLength          = pl.geometryLength - (splitMeasure - pl.startMValue), connectedLinkId         = Some(pl.linkId))
      )

      (newProjectLinks._1, newProjectLinks._2.copy(id = newPlId))
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

    def splitAndSetCalibrationPointsAtEnd(
      last:          ProjectLink,
      roadPartLinks: Seq[ProjectLink]
    ): (
      Option[(ProjectLink, ProjectLink)],
      Seq[Option[UserDefinedCalibrationPoint]],
      Option[(ProjectLink, ProjectLink)]
    ) = {
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
            val endPoints = TrackSectionOrder.findChainEndpoints(
              roadPartLinks.filter(pl =>
                pl.endAddrMValue <= otherSideLink.endAddrMValue && pl.track == otherSideLink.track
              )
            )
            val (plPart1, plPart2) =
                splitAt(otherSideLink, last.endAddrMValue, endPoints)
            val (
              newCP: Seq[Option[UserDefinedCalibrationPoint]],
              cpUpdatedPls
            ) =
              createCalibrationPoints(
                startCP,
                endCP,
                otherSideLinkStartCP,
                otherSideLinkEndCP,
                plPart1
              )
            (Some(plPart1, plPart2), newCP, cpUpdatedPls)
          } else {
            val (
              newCP: Seq[Option[UserDefinedCalibrationPoint]],
              cpUpdatedPls
            ) =
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

  def splitPlsAtRoadwayChange(
    trackToSearchStatusChanges: Seq[ProjectLink],
    oppositeTrack:              Seq[ProjectLink]
  ): (Seq[ProjectLink], Seq[ProjectLink]) = {
    trackToSearchStatusChanges.tail.foldLeft(
      (Seq(trackToSearchStatusChanges.head), oppositeTrack)
    ) { (prevPl_othersidePls_udcps, currentPl) =>
      // Check if status changes and do spliting with user defined calibration point if links on both sides do not split at the same position.
      if (currentPl.roadwayNumber != prevPl_othersidePls_udcps._1.last.roadwayNumber) {
        val splitted_pls = this.findAndCreateSplitAtRoadwayChange(
          Seq(prevPl_othersidePls_udcps._1.last),
          prevPl_othersidePls_udcps._2
        )
        if (splitted_pls.isDefined) {
          val pls                         = splitted_pls.get
          val splitted_pls_link_id        = pls._1.linkId
          val splitted_pls_link_startAddr = pls._1.startAddrMValue

          (
            prevPl_othersidePls_udcps._1 :+ currentPl,
            (prevPl_othersidePls_udcps._2.filterNot(rl => {
              splitted_pls_link_id == rl.linkId && rl.startAddrMValue == splitted_pls_link_startAddr
            }) ++ Seq(pls._1, pls._2)).sortBy(_.startAddrMValue)
          )

        } else
          (
            prevPl_othersidePls_udcps._1 :+ currentPl,
            prevPl_othersidePls_udcps._2
          )

      } else
        (
          prevPl_othersidePls_udcps._1 :+ currentPl,
          prevPl_othersidePls_udcps._2
        )
    }
  }

  def findAndCreateSplitAtRoadwayChange(
    toUpdateLinks: Seq[ProjectLink],
    roadPartLinks: Seq[ProjectLink]
  ): Option[(ProjectLink, ProjectLink)] = {
    val last = toUpdateLinks.maxBy(_.endAddrMValue)

    def splitAt(
      pl:        ProjectLink,
      address:   Long,
      endPoints: Map[Point, ProjectLink]
    ): (ProjectLink, ProjectLink) = {
      val coefficient =
        (pl.endMValue - pl.startMValue) / (pl.endAddrMValue - pl.startAddrMValue)
      val splitMeasure =
        pl.startMValue + (math.abs(pl.startAddrMValue - address) * coefficient)
      val geom = getGeometryFromSplitMeasure(
        pl,
        endPoints,
        splitMeasure
      )
      val new_geometry =
        getNewGeometryFromSplitMeasure(pl, endPoints, splitMeasure)

      val calsForFirstPart =
        if (pl.calibrationPoints._1.isDefined)
          (
            pl.calibrationPoints._1.get.typeCode,
            CalibrationPointType.NoCP
          )
        else
          (
            CalibrationPointType.NoCP,
            CalibrationPointType.NoCP
          )
      val calsForSecondPart = getCalibrationPointsForSecondPart(pl)
      val addressLength     = pl.endAddrMValue - address
      val splittedOriginalEndAddrMValue =
        pl.originalEndAddrMValue - addressLength

      val newPlId = Sequences.nextProjectLinkId
      val newProjectLinks = (
        pl.copy(endAddrMValue         = address, originalEndAddrMValue = splittedOriginalEndAddrMValue, startMValue           = pl.startMValue, endMValue             = splitMeasure, calibrationPointTypes = calsForFirstPart, geometry              = geom, status                = pl.status, geometryLength        = splitMeasure - pl.startMValue, connectedLinkId       = Some(pl.linkId)),
        pl.copy(id                      = newPlId, startAddrMValue         = address, endAddrMValue           = pl.endAddrMValue, originalStartAddrMValue = splittedOriginalEndAddrMValue, originalEndAddrMValue   = pl.originalEndAddrMValue, startMValue             = splitMeasure, endMValue               = pl.endMValue, calibrationPointTypes   = calsForSecondPart, geometry                = new_geometry, status                  = pl.status, geometryLength          = pl.geometryLength - (splitMeasure - pl.startMValue), connectedLinkId         = Some(pl.linkId))
      )

      (newProjectLinks._1, newProjectLinks._2.copy(id = newPlId))
    }

    def splitAndSetCalibrationPointsAtEnd(
      last:          ProjectLink,
      roadPartLinks: Seq[ProjectLink]
    ): Option[(ProjectLink, ProjectLink)] = {
      if (last.track != Track.Combined && last.discontinuity == Discontinuity.Continuous) {

        val hasOtherSideLink = roadPartLinks.filter(pl =>
          pl.track          != Track.Combined &&
          pl.track          != last.track &&
          pl.status         != RoadAddressChangeType.NotHandled &&
          pl.startAddrMValue < last.endAddrMValue &&
          pl.endAddrMValue  >= last.endAddrMValue
        )

        if (hasOtherSideLink.nonEmpty) {
          val otherSideLink = hasOtherSideLink.head

          if (otherSideLink.endAddrMValue != last.endAddrMValue) {
            val endPoints = TrackSectionOrder.findChainEndpoints(
              roadPartLinks.filter(pl =>
                pl.endAddrMValue <= otherSideLink.endAddrMValue && pl.track == otherSideLink.track
              )
            )
            val (plPart1, plPart2) =
              splitAt(otherSideLink, last.endAddrMValue, endPoints)
            Some(plPart1, plPart2)
          } else {
            None
          }
        } else None
      } else None
    }

    splitAndSetCalibrationPointsAtEnd(last, roadPartLinks)
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

  private def getGeometryFromSplitMeasure(
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
        startMeasure = 0,
        endMeasure   = splitMeasure - pl.startMValue
      )
    else
      GeometryUtils
        .truncateGeometry2D(
          pl.geometry.reverse,
          startMeasure = 0,
          endMeasure   = splitMeasure - pl.startMValue
        )
        .reverse
  }
  /* split end */


  def findAndCreateSplitsAtOriginalAddress(
                                         splitAddress: Long,
                                         roadPartLinks: Seq[ProjectLink]
                                       ): Option[(ProjectLink, ProjectLink)] = {
    def splitAt(
                 pl:        ProjectLink,
                 address:   Long,
                 endPoints: Map[Point, ProjectLink]
               ): (ProjectLink, ProjectLink) = {
      val coefficient =
        (pl.endMValue - pl.startMValue) / (pl.originalEndAddrMValue - pl.originalStartAddrMValue)
      val splitMeasure =
        pl.startMValue + (math.abs(pl.originalStartAddrMValue - address) * coefficient)
      val geom =
        getGeometryFromSplitMeasure(pl,endPoints, splitMeasure)
      val new_geometry =
        getNewGeometryFromSplitMeasure(pl, endPoints, splitMeasure)

      val calsForFirstPart =
        if (pl.calibrationPoints._1.isDefined)
          (pl.calibrationPoints._1.get.typeCode,
           CalibrationPointType.NoCP)
        else
          (CalibrationPointType.NoCP,
           CalibrationPointType.NoCP)
      val calsForSecondPart =
        getCalibrationPointsForSecondPart(pl)
      val splittedEndAddrMValue =
        pl.startAddrMValue + (address - pl.originalStartAddrMValue)

      val newPlId = Sequences.nextProjectLinkId
      val newProjectLinks = (
                              pl.copy(discontinuity         = Discontinuity.Continuous, endAddrMValue         = splittedEndAddrMValue, originalEndAddrMValue = address, startMValue           = pl.startMValue, endMValue             = splitMeasure, calibrationPointTypes = calsForFirstPart, geometry              = geom, status                = pl.status, geometryLength        = splitMeasure - pl.startMValue, connectedLinkId       = Some(pl.linkId)),
                              pl.copy(id                      = newPlId, startAddrMValue         = splittedEndAddrMValue, originalStartAddrMValue = address, startMValue             = splitMeasure, endMValue               = pl.endMValue, calibrationPointTypes   = calsForSecondPart, geometry                = new_geometry, status                  = pl.status, geometryLength          = pl.geometryLength - (splitMeasure - pl.startMValue), connectedLinkId         = Some(pl.linkId))
                            )

      (newProjectLinks._1, newProjectLinks._2)
    }

        val hasOtherSideLink = roadPartLinks.filter(pl =>
          pl.originalTrack          != Track.Combined &&
          pl.status                 != RoadAddressChangeType.NotHandled &&
          pl.status                 != RoadAddressChangeType.New &&
          pl.originalStartAddrMValue < splitAddress &&
          pl.originalEndAddrMValue  > splitAddress
        )

        if (hasOtherSideLink.nonEmpty) {
          val otherSideLink = hasOtherSideLink.head
            val endPoints = TrackSectionOrder.findChainEndpoints(
              roadPartLinks.filter(pl =>
                pl.originalEndAddrMValue <= otherSideLink.originalEndAddrMValue && pl.originalEndAddrMValue != 0 && pl.originalTrack == otherSideLink.originalTrack
              )
            )
            val (plPart1, plPart2) =
              splitAt(otherSideLink, splitAddress, endPoints)
            Some(plPart1, plPart2)
        } else None
    }

  @tailrec
  def getContinuousOriginalAddressSection(seq: Seq[ProjectLink], processed: Seq[ProjectLink] = Seq()): (Seq[ProjectLink], Seq[ProjectLink]) = {
    if (seq.isEmpty)
      (processed, seq)
    else if (processed.isEmpty)
      getContinuousOriginalAddressSection(seq.tail, Seq(seq.head))
    else {
      val head     = seq.head
      val track    = processed.last.originalTrack
      val road     = processed.last.originalRoadNumber
      val roadPart = processed.last.originalRoadPartNumber
      val address  = processed.last.originalEndAddrMValue
      val status   = processed.last.status
      if (head.originalTrack == track && head.originalRoadNumber == road && head.originalRoadPartNumber == roadPart && head.originalStartAddrMValue == address && head.status == status) {
        getContinuousOriginalAddressSection(seq.tail, processed :+ head)
      } else {
        (processed, seq)
      }
    }
  }

  def splitByOriginalAddress(twoTrackOnlyWithTerminated: Seq[Long], side: Seq[ProjectLink]): Seq[ProjectLink] = {
    twoTrackOnlyWithTerminated.foldLeft(side) {
      case (a, b) =>
        val res = TwoTrackRoadUtils.findAndCreateSplitsAtOriginalAddress(b, a)
        if (res.isDefined) (a.filterNot(_.id == res.get._1.id) :+ res.get._1 :+ res.get._2).sortBy(_.startAddrMValue) else a
    }
  }

  def toProjectLinkSeq(plsTupleOptions: Seq[Option[(ProjectLink, ProjectLink)]]): Seq[ProjectLink] = plsTupleOptions collect toSequence flatten

  def getContinuousByStatus(projectLinkSeq: Seq[ProjectLink]): Seq[Long] = {
    @tailrec
    def continuousByStatus(pls: Seq[ProjectLink], result: Seq[Long] = Seq()): Seq[Long] = {
      if (pls.isEmpty) result
      else {
        val continuousOriginalAddressSection = getContinuousOriginalAddressSection(pls)
        continuousByStatus(continuousOriginalAddressSection._2, addtomap(continuousOriginalAddressSection._1, result))
      }
    }
    val filterNew = projectLinkSeq.filter(_.status != RoadAddressChangeType.New)
    val filteredProjectLinks = if (filterNew.nonEmpty) filterNew.init else Seq()
    continuousByStatus(filteredProjectLinks.sortBy(_.originalStartAddrMValue))
  }
  def addtomap(pls: Seq[ProjectLink], res: Seq[Long]): Seq[Long] = res :+ pls.last.originalEndAddrMValue

  val toSequence: PartialFunction[Option[(ProjectLink, ProjectLink)], Seq[ProjectLink]] = {
    case x: Some[(ProjectLink, ProjectLink)] => Seq(x.get._1, x.get._2)
  }

  def combineAndSort(olds: Seq[ProjectLink], news: Seq[ProjectLink]): Seq[ProjectLink] = (olds.filterNot(pl => {
    news.map(_.id).contains(pl.id)
  }) ++ news).sortBy(_.startAddrMValue)

  def filterOldLinks(pls: Seq[ProjectLink]): Seq[ProjectLink] = {
    val filtered = pls.filter(_.status != RoadAddressChangeType.New)
    if (filtered.nonEmpty) filtered.init else Seq()
  }
  def filterExistingLinks(pl: ProjectLink): Boolean =
    pl.status != RoadAddressChangeType.New && pl.track != Track.Combined

  def splitByOriginalAddresses(pls: Seq[ProjectLink], originalAddressEnds: Seq[Long]): Seq[ProjectLink] = {
    originalAddressEnds.foldLeft(pls)((l1, l2) => {
      val s: Seq[Option[(ProjectLink, ProjectLink)]] = Seq(TwoTrackRoadUtils.findAndCreateSplitsAtOriginalAddress(l2, l1))
      combineAndSort(l1, toProjectLinkSeq(s))
    })
  }
}
