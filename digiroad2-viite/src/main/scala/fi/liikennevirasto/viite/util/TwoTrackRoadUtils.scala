package fi.liikennevirasto.viite.util

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{JunctionPointCP, NoCP, UserDefinedCP}
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.{RoadwayAddressMapper, TrackSectionOrder}
import org.slf4j.LoggerFactory

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
    *   udcp[Option[UserDefinedCalibrationPoint]])
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
        if (pl.status == LinkStatus.New)
          (pl.endMValue - pl.startMValue) / (pl.endAddrMValue - pl.startAddrMValue)
        else
          (pl.endMValue - pl.startMValue) / (pl.originalEndAddrMValue - pl.originalStartAddrMValue)
      val splitMeasure =
        if (pl.status == LinkStatus.New)
          pl.startMValue + (math.abs(
            pl.startAddrMValue - address
          ) * coefficient)
        else
          pl.startMValue + (math.abs(
            pl.originalStartAddrMValue - address
          ) * coefficient)
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
            CalibrationPointDAO.CalibrationPointType.UserDefinedCP
          )
        else
          (
            CalibrationPointDAO.CalibrationPointType.NoCP,
            CalibrationPointDAO.CalibrationPointType.UserDefinedCP
          )
      val calsForSecondPart = getCalibrationPointsForSecondPart(pl)
      val addressLength =
        if (pl.status == LinkStatus.New) pl.endAddrMValue - address
        else pl.originalEndAddrMValue - address
      val splittedOriginalEndAddrMValue =
        pl.originalEndAddrMValue - addressLength

      val newPlId = Sequences.nextProjectLinkId
      val newProjectLinks = (
        pl.copy(
          endAddrMValue =
            if (pl.status == LinkStatus.New) address
            else pl.endAddrMValue - addressLength,
          originalEndAddrMValue = splittedOriginalEndAddrMValue,
          startMValue           = pl.startMValue,
          endMValue             = splitMeasure,
          calibrationPointTypes = calsForFirstPart,
          geometry              = geom,
          status                = pl.status,
          geometryLength        = splitMeasure - pl.startMValue,
          connectedLinkId       = Some(pl.linkId),
          discontinuity         = Discontinuity.Continuous
        ),
        pl.copy(
          id = NewIdValue,
          startAddrMValue =
            if (pl.status == LinkStatus.New) address
            else pl.endAddrMValue - addressLength,
          endAddrMValue           = pl.endAddrMValue,
          originalStartAddrMValue = splittedOriginalEndAddrMValue,
          originalEndAddrMValue   = pl.originalEndAddrMValue,
          startMValue             = splitMeasure,
          endMValue               = pl.endMValue,
          calibrationPointTypes   = calsForSecondPart,
          geometry                = new_geometry,
          status                  = pl.status,
          geometryLength          = pl.geometryLength - (splitMeasure - pl.startMValue),
          connectedLinkId         = Some(pl.linkId)
        )
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
     *   Tuple2 (Seq[Option[UserDefinedCalibrationPoint]], Option[(ProjectLink, ProjectLink)])
     *   A new calibration point for opposite track and a new for current track
     *   if does not exists, and corresponding projectlinks.
     *
     */
    def createCalibrationPoints(
      startCP:              CalibrationPointDAO.CalibrationPointType,
      endCP:                CalibrationPointDAO.CalibrationPointType,
      otherSideLinkStartCP: CalibrationPointDAO.CalibrationPointType,
      otherSideLinkEndCP:   CalibrationPointDAO.CalibrationPointType,
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
          pl.status != LinkStatus.NotHandled &&
          ((pl.status == LinkStatus.New && pl.startAddrMValue < last.endAddrMValue && pl.endAddrMValue >= last.endAddrMValue)
          ||
          (pl.status != LinkStatus.New && pl.originalStartAddrMValue < last.originalEndAddrMValue && pl.originalEndAddrMValue >= last.originalEndAddrMValue))
        )

        if (hasOtherSideLink.nonEmpty) {
          val startCP = last.startCalibrationPointType match {
            case JunctionPointCP => JunctionPointCP
            case UserDefinedCP   => UserDefinedCP
            case _               => NoCP
          }
          val endCP = last.endCalibrationPointType match {
            case JunctionPointCP => JunctionPointCP
            case _               => UserDefinedCP
          }
          val otherSideLinkStartCP = last.startCalibrationPointType match {
            case JunctionPointCP => JunctionPointCP
            case UserDefinedCP   => UserDefinedCP
            case _               => NoCP
          }
          val otherSideLinkEndCP = last.endCalibrationPointType match {
            case JunctionPointCP => JunctionPointCP
            case _               => UserDefinedCP
          }

          val otherSideLink = hasOtherSideLink.head

          if (otherSideLink.endAddrMValue != last.endAddrMValue) {
            val endPoints = TrackSectionOrder.findChainEndpoints(
              roadPartLinks.filter(pl =>
                pl.endAddrMValue <= otherSideLink.endAddrMValue && pl.track == otherSideLink.track
              )
            )
            val (plPart1, plPart2) =
              if (otherSideLink.status == LinkStatus.New)
                splitAt(otherSideLink, last.endAddrMValue, endPoints)
              else splitAt(otherSideLink, last.originalEndAddrMValue, endPoints)
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
            CalibrationPointDAO.CalibrationPointType.NoCP
          )
        else
          (
            CalibrationPointDAO.CalibrationPointType.NoCP,
            CalibrationPointDAO.CalibrationPointType.NoCP
          )
      val calsForSecondPart = getCalibrationPointsForSecondPart(pl)
      val addressLength     = pl.endAddrMValue - address
      val splittedOriginalEndAddrMValue =
        pl.originalEndAddrMValue - addressLength

      val newPlId = Sequences.nextProjectLinkId
      val newProjectLinks = (
        pl.copy(
          endAddrMValue         = address,
          originalEndAddrMValue = splittedOriginalEndAddrMValue,
          startMValue           = pl.startMValue,
          endMValue             = splitMeasure,
          calibrationPointTypes = calsForFirstPart,
          geometry              = geom,
          status                = pl.status,
          geometryLength        = splitMeasure - pl.startMValue,
          connectedLinkId       = Some(pl.linkId)
        ),
        pl.copy(
          id                      = newPlId,
          startAddrMValue         = address,
          endAddrMValue           = pl.endAddrMValue,
          originalStartAddrMValue = splittedOriginalEndAddrMValue,
          originalEndAddrMValue   = pl.originalEndAddrMValue,
          startMValue             = splitMeasure,
          endMValue               = pl.endMValue,
          calibrationPointTypes   = calsForSecondPart,
          geometry                = new_geometry,
          status                  = pl.status,
          geometryLength          = pl.geometryLength - (splitMeasure - pl.startMValue),
          connectedLinkId         = Some(pl.linkId)
        )
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
          pl.status         != LinkStatus.NotHandled &&
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
        CalibrationPointDAO.CalibrationPointType.NoCP,
        pl.calibrationPoints._2.get.typeCode
      )
    else
      (
        CalibrationPointDAO.CalibrationPointType.NoCP,
        CalibrationPointDAO.CalibrationPointType.NoCP
      )
  }

  private def getNewGeometryFromSplitMeasure(
    pl: _root_.fi.liikennevirasto.viite.dao.ProjectLink,
    endPoints: _root_.scala.Predef.Map[
      _root_.fi.liikennevirasto.digiroad2.Point,
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
      _root_.fi.liikennevirasto.digiroad2.Point,
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
}