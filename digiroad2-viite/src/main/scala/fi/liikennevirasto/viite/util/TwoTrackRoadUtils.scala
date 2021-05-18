package fi.liikennevirasto.viite.util

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{JunctionPointCP, NoCP, UserDefinedCP}
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.{CalibrationPointDAO, Discontinuity, LinearLocationDAO, LinkStatus, ProjectCalibrationPointDAO, ProjectLink, ProjectLinkDAO, RoadwayDAO}
import fi.liikennevirasto.viite.process.{RoadwayAddressMapper, TrackSectionOrder}
import org.slf4j.LoggerFactory

object TwoTrackRoadUtils {
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO: RoadwayDAO, linearLocationDAO: LinearLocationDAO)
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * SplitPlsAtStatusChange searches iteratively link status changes and checks
   * if other track needs to split at the change point and makes  splits to
   * the other side projectlinks and creates user defined calibration points.
   *
   * @note This is asymmetric such that a call must be made for both tracks.
   * @param trackToSearchStatusChanges A track to search for link status changes.
   * @param oppositeTrack              The opposite track projectlinks to split at status change.
   * @return Tuple3 updated projectlinks and user defined calibration points.
   *         (trackToSearchStatusChanges[ProjectLink], oppositeTrack[ProjectLink], udcp[Option[UserDefinedCalibrationPoint]])
   */
  def splitPlsAtStatusChange(trackToSearchStatusChanges: Seq[ProjectLink], oppositeTrack: Seq[ProjectLink]): (Seq[ProjectLink], Seq[ProjectLink], Seq[Option[UserDefinedCalibrationPoint]]) = {
    trackToSearchStatusChanges.tail.foldLeft((Seq(trackToSearchStatusChanges.head), oppositeTrack, Seq.empty[Option[UserDefinedCalibrationPoint]])) { (prevPl_othersidePls_udcps, currentPl) =>

      // Check if status changes and do spliting with user defined calibration point if links on both sides do not split at the same position.
      if (
//        (currentPl.status == LinkStatus.Terminated && trackToSearchStatusChanges.find(_.connected(currentPl.startingPoint)).getOrElse(false).asInstanceOf[ProjectLink].status != LinkStatus.Terminated) ||
//          (currentPl.status == LinkStatus.Terminated && trackToSearchStatusChanges.find(_.connected(currentPl.endPoint)).getOrElse(false).asInstanceOf[ProjectLink].status != LinkStatus.Terminated) ||
        (currentPl.status != prevPl_othersidePls_udcps._1.last.status)) {
        val splitted_pls = this.createCalibrationPointsAtStatusChange(Seq(prevPl_othersidePls_udcps._1.last), prevPl_othersidePls_udcps._2, currentPl.projectId, currentPl.createdBy.getOrElse("splitter"))
        if (splitted_pls._1.nonEmpty) {
          val pls = splitted_pls._1.get
          val splitted_pls_link_id = pls._1.linkId
          val splitted_pls_link_startAddr = pls._1.startAddrMValue
          ( if (splitted_pls._3.isDefined) prevPl_othersidePls_udcps._1.init :+ splitted_pls._3.get._1 :+ currentPl else prevPl_othersidePls_udcps._1 :+ currentPl,
            (prevPl_othersidePls_udcps._2.filterNot(rl => splitted_pls_link_id == rl.linkId && rl.startAddrMValue == splitted_pls_link_startAddr) ++ Seq(pls._1, pls._2)).sortBy
            (_.startAddrMValue),
            // update udcp:s if new defined
            if (!splitted_pls._2.isEmpty)
              prevPl_othersidePls_udcps._3 ++ splitted_pls._2
            else
              prevPl_othersidePls_udcps._3
          )
        } else
          if (splitted_pls._2.nonEmpty) { // Had same addresses
            (prevPl_othersidePls_udcps._1.init :+ splitted_pls._3.get._1 :+ currentPl,
              (prevPl_othersidePls_udcps._2.filterNot(rl => splitted_pls._3.get._2.linkId == rl.linkId && rl.startAddrMValue == splitted_pls._3.get._2.startAddrMValue) :+ splitted_pls._3.get._2).sortBy
              (_.startAddrMValue)
              , prevPl_othersidePls_udcps._3 ++ splitted_pls._2)
          }
          else
          (prevPl_othersidePls_udcps._1 :+ currentPl, prevPl_othersidePls_udcps._2, prevPl_othersidePls_udcps._3)
      } else
        (prevPl_othersidePls_udcps._1 :+ currentPl, prevPl_othersidePls_udcps._2, prevPl_othersidePls_udcps._3)
    }
  }

  def splitPlsAtRoadwayChange(trackToSearchStatusChanges: Seq[ProjectLink], oppositeTrack: Seq[ProjectLink]): (Seq[ProjectLink], Seq[ProjectLink]) = {
    trackToSearchStatusChanges.tail.foldLeft((Seq(trackToSearchStatusChanges.head), oppositeTrack)) { (prevPl_othersidePls_udcps, currentPl) =>

      // Check if status changes and do spliting with user defined calibration point if links on both sides do not split at the same position.
      if (
        (currentPl.roadwayNumber != prevPl_othersidePls_udcps._1.last.roadwayNumber)) {
        val splitted_pls = this.findAndCreateSplitAtRoadwayChange(Seq(prevPl_othersidePls_udcps._1.last), prevPl_othersidePls_udcps._2)
        if (splitted_pls.isDefined) {
          val pls                         = splitted_pls.get
          val splitted_pls_link_id        = pls._1.linkId
          val splitted_pls_link_startAddr = pls._1.startAddrMValue

          (prevPl_othersidePls_udcps._1 :+ currentPl,
          (prevPl_othersidePls_udcps._2.filterNot(rl => splitted_pls_link_id == rl.linkId && rl.startAddrMValue == splitted_pls_link_startAddr) ++ Seq(pls._1, pls._2)).sortBy(_.startAddrMValue))

        } else (prevPl_othersidePls_udcps._1 :+ currentPl, prevPl_othersidePls_udcps._2)

      } else (prevPl_othersidePls_udcps._1 :+ currentPl, prevPl_othersidePls_udcps._2)
    }
  }
  def findAndCreateSplitAtRoadwayChange(toUpdateLinks: Seq[ProjectLink], roadPartLinks: Seq[ProjectLink]): Option[(ProjectLink, ProjectLink)] = {
    val last = toUpdateLinks.maxBy(_.endAddrMValue)

    def splitAt(pl: ProjectLink, address: Long, endPoints: Map[Point, ProjectLink]): (ProjectLink, ProjectLink) = {
      val coefficient = (pl.endMValue - pl.startMValue) / (pl.endAddrMValue - pl.startAddrMValue)
      val splitMeasure = pl.startMValue + (math.abs(pl.startAddrMValue - address) * coefficient)
      val geom =  if (pl.geometry.last == endPoints.last._1)
        GeometryUtils.truncateGeometry2D(pl.geometry, startMeasure = 0, endMeasure = splitMeasure - pl.startMValue)
      else GeometryUtils.truncateGeometry2D(pl.geometry.reverse, startMeasure = 0, endMeasure = splitMeasure - pl.startMValue).reverse

      val new_geometry = if (pl.geometry.last == endPoints.last._1)
        GeometryUtils.truncateGeometry2D(pl.geometry, startMeasure = splitMeasure - pl.startMValue, endMeasure = pl.geometryLength)
      else
        GeometryUtils.truncateGeometry2D(pl.geometry.reverse, startMeasure = splitMeasure - pl.startMValue, endMeasure = pl.geometryLength).reverse

      val calsForFirstPart =
        if (pl.calibrationPoints._1.isDefined)
          (pl.calibrationPoints._1.get.typeCode, CalibrationPointDAO.CalibrationPointType.NoCP)
        else
          (CalibrationPointDAO.CalibrationPointType.NoCP, CalibrationPointDAO.CalibrationPointType.NoCP)
      val calsForSecondPart =
        if (pl.calibrationPoints._2.isDefined)
          (CalibrationPointDAO.CalibrationPointType.NoCP, pl.calibrationPoints._2.get.typeCode)
        else
          (CalibrationPointDAO.CalibrationPointType.NoCP, CalibrationPointDAO.CalibrationPointType.NoCP)
      val addressLength = pl.endAddrMValue - address
      val splittedOriginalEndAddrMValue = pl.originalEndAddrMValue - addressLength //Math.round(pl.originalStartAddrMValue + splitMeasure)

      val newProjectLinks =
        (
          pl.copy(endAddrMValue = address, startMValue = pl.startMValue, endMValue = splitMeasure, status = pl.status, geometry = geom, geometryLength = splitMeasure, connectedLinkId = Some(pl.linkId), calibrationPointTypes = calsForFirstPart, originalEndAddrMValue = splittedOriginalEndAddrMValue),
          pl.copy(id = NewIdValue, status = pl.status, startAddrMValue = address, endAddrMValue = pl.endAddrMValue, startMValue = splitMeasure, endMValue = pl.endMValue, geometry = new_geometry, geometryLength = pl.geometryLength - splitMeasure, connectedLinkId = Some(pl.linkId), calibrationPointTypes = calsForSecondPart, originalStartAddrMValue = splittedOriginalEndAddrMValue, originalEndAddrMValue = pl.originalEndAddrMValue)
        )

      val id: Seq[Long] = projectLinkDAO.create(Seq(newProjectLinks._2))
      (newProjectLinks._1, newProjectLinks._2.copy(id = id.head))
    }

    def splitAndSetCalibrationPointsAtEnd(last: ProjectLink, roadPartLinks: Seq[ProjectLink]): Option[(ProjectLink, ProjectLink)] = {
      if (last.track != Track.Combined && last.discontinuity == Discontinuity.Continuous) {

        val hasOtherSideLink = roadPartLinks.filter(pl =>
          pl.track != Track.Combined &&
          pl.track != last.track &&
          pl.status != LinkStatus.NotHandled &&
          pl.startAddrMValue < last.endAddrMValue &&
          pl.endAddrMValue >= last.endAddrMValue)

        if (hasOtherSideLink.nonEmpty) {
          val otherSideLink = hasOtherSideLink.head

          if (otherSideLink.endAddrMValue != last.endAddrMValue) {
            val endPoints = TrackSectionOrder.findChainEndpoints(roadPartLinks.filter(pl => pl.endAddrMValue <= otherSideLink.endAddrMValue && pl.track == otherSideLink.track))
            val (plPart1, plPart2) = splitAt(otherSideLink, last.endAddrMValue, endPoints)
            Some(plPart1, plPart2)
          } else {
            None
          }
        } else None
      } else None
    }

      splitAndSetCalibrationPointsAtEnd(last, roadPartLinks)
  }

  /* split start */
  def createCalibrationPointsAtStatusChange(toUpdateLinks: Seq[ProjectLink], roadPartLinks: Seq[ProjectLink], projectId: Long, userName: String): (Option[(ProjectLink, ProjectLink)], Seq[Option[UserDefinedCalibrationPoint]], Option[(ProjectLink, ProjectLink)]) = {
    val last = toUpdateLinks.maxBy(_.endAddrMValue)
    val roadPartCalibrationPoints = ProjectCalibrationPointDAO.fetchByRoadPart(projectId, last.roadNumber, last.roadPartNumber)
    //  val roadPartLinks = projectLinkDAO.fetchProjectLinksByProjectRoadPart(toUpdateLinks.head.roadNumber, toUpdateLinks.head.roadPartNumber, projectId)


    def splitAt(pl: ProjectLink, address: Long, endPoints: Map[Point, ProjectLink]): (ProjectLink, ProjectLink) = {
      val coefficient = (pl.endMValue - pl.startMValue) / (pl.endAddrMValue - pl.startAddrMValue)
      val splitMeasure = pl.startMValue + (math.abs(pl.startAddrMValue - address) * coefficient)
      val geom = if (pl.geometry.last == endPoints.last._1)
        GeometryUtils.truncateGeometry2D(pl.geometry, startMeasure = 0, endMeasure = splitMeasure - pl.startMValue)
      else GeometryUtils.truncateGeometry2D(pl.geometry.reverse, startMeasure = 0, endMeasure = splitMeasure - pl.startMValue).reverse

      val new_geometry = if (pl.geometry.last == endPoints.last._1)
        GeometryUtils.truncateGeometry2D(pl.geometry, startMeasure = splitMeasure - pl.startMValue, endMeasure = pl.geometryLength)
      else
        GeometryUtils.truncateGeometry2D(pl.geometry.reverse, startMeasure = splitMeasure - pl.startMValue, endMeasure = pl.geometryLength).reverse

      //        if (geom.last != new_geometry.head) new_geometry = new_geometry.reverse
      //pl.calibrationPoints
      val calsForFirstPart =
      if (pl.calibrationPoints._1.isDefined)
        (pl.calibrationPoints._1.get.typeCode, CalibrationPointDAO.CalibrationPointType.UserDefinedCP)
      else
        (CalibrationPointDAO.CalibrationPointType.NoCP, CalibrationPointDAO.CalibrationPointType.UserDefinedCP)
      val calsForSecondPart =
        if (pl.calibrationPoints._2.isDefined)
          (CalibrationPointDAO.CalibrationPointType.NoCP, pl.calibrationPoints._2.get.typeCode)
        else
          (CalibrationPointDAO.CalibrationPointType.NoCP, CalibrationPointDAO.CalibrationPointType.NoCP)

//      val splittedOriginalEndAddrMValue = Math.round(pl.originalStartAddrMValue + splitMeasure)
      val addressLength = pl.endAddrMValue - address
      val splittedOriginalEndAddrMValue = pl.originalEndAddrMValue - addressLength //Math.round(address - pl.originalStartAddrMValue)

      val newProjectLinks =
        (
          pl.copy(endAddrMValue = address, startMValue = pl.startMValue, endMValue = splitMeasure, status = pl.status, geometry = geom, geometryLength = splitMeasure, connectedLinkId = Some(pl.linkId), calibrationPointTypes = calsForFirstPart, originalEndAddrMValue = splittedOriginalEndAddrMValue),
          pl.copy(id = NewIdValue, status = pl.status, startAddrMValue = address, endAddrMValue = pl.endAddrMValue, startMValue = splitMeasure, endMValue = pl.endMValue, geometry = new_geometry, geometryLength = pl.geometryLength - splitMeasure, connectedLinkId = Some(pl.linkId), calibrationPointTypes = calsForSecondPart, originalStartAddrMValue = splittedOriginalEndAddrMValue, originalEndAddrMValue = pl.originalEndAddrMValue)
        )



      val id: Seq[Long] = projectLinkDAO.create(Seq(newProjectLinks._2))
      (newProjectLinks._1, newProjectLinks._2.copy(id = id.head))
    }

    def createCalibrationPoints(startCP: CalibrationPointDAO.CalibrationPointType, endCP: CalibrationPointDAO.CalibrationPointType, otherSideLinkStartCP: CalibrationPointDAO.CalibrationPointType, otherSideLinkEndCP: CalibrationPointDAO.CalibrationPointType, otherSideLink: ProjectLink) = {
      val leftCalibrationPoint = if (roadPartCalibrationPoints.filter(cp => cp.projectLinkId == last.id && cp.addressMValue == last.endAddrMValue).isEmpty) {
        logger.info(s"Splitting pl.id ${last.id}")
          val newUdcp = UserDefinedCalibrationPoint(NewIdValue, last.id, last.projectId, last.endMValue - last.startMValue, last.endAddrMValue)
//        ProjectCalibrationPointDAO.createCalibrationPoint(newUdcp)
        Some(newUdcp)
      } else None

      val rightCalibrationPoint =
        UserDefinedCalibrationPoint(NewIdValue, otherSideLink.id, otherSideLink.projectId, last.endAddrMValue - otherSideLink.startMValue, last.endAddrMValue)

      val newUcpId = if (roadPartCalibrationPoints.filter(cp => cp.projectLinkId == otherSideLink.id && cp.addressMValue == last.endAddrMValue).isEmpty) {
        true
//        ProjectCalibrationPointDAO.createCalibrationPoint(rightCalibrationPoint)
      } else false

//      val roadways = roadwayDAO.fetchAllByRoadwayId(Seq(last.roadwayId, otherSideLink.roadwayId))
//      val originalAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(roadways) //roadAddressService.getRoadAddressesByRoadwayIds(Seq(last.roadwayId, otherSideLink.roadwayId))

      val updatedCpToLast = last.copy(calibrationPointTypes = (startCP, endCP))
      val updatedCpToOtherSideLink = otherSideLink.copy(calibrationPointTypes = (otherSideLinkStartCP, otherSideLinkEndCP))

      projectLinkDAO.updateProjectLinkCalibrationPoints(updatedCpToLast, (startCP, endCP))
      projectLinkDAO.updateProjectLinkCalibrationPoints(updatedCpToOtherSideLink, (otherSideLinkStartCP, otherSideLinkEndCP))

//      projectLinkDAO.updateProjectLinks(
//        Seq(updatedCpToLast, updatedCpToOtherSideLink),
//        userName,
//        originalAddresses)

      if (newUcpId )
        (Seq(Some(rightCalibrationPoint), Some(leftCalibrationPoint.getOrElse())), Some(updatedCpToLast, updatedCpToOtherSideLink))
      else
        (Seq.empty[Option[UserDefinedCalibrationPoint]], None)
    }

    def findOtherShorterTrackStatusChangePoint(lastPl: ProjectLink) = {
      val ret = roadPartLinks.filter(pl =>
        pl.track != lastPl.track &&
          pl.track != Track.Combined &&
          pl.endAddrMValue < lastPl.endAddrMValue &&
          pl.status != LinkStatus.NotHandled)

      val x = if (ret.nonEmpty) {
        val nextLink = roadPartLinks.find(_.startAddrMValue == ret.maxBy(_.endAddrMValue))
        if (nextLink.isDefined && nextLink.get.status != lastPl.status) {
          Some(ret.maxBy(_.endAddrMValue))
        } else {
          None
        }
      } else {
        None
      }
      x
    }

    def splitAndSetCalibrationPointsAtEnd(last: ProjectLink, roadPartLinks: Seq[ProjectLink]): (Option[(ProjectLink, ProjectLink)], Seq[Option[UserDefinedCalibrationPoint]],
      Option[(ProjectLink, ProjectLink)]) = {
      if (last.track != Track.Combined && last.discontinuity == Discontinuity.Continuous) {

        val hasOtherSideLink = roadPartLinks.filter(pl =>
          pl.track != Track.Combined &&
            pl.track != last.track &&
            pl.status != LinkStatus.NotHandled &&
            //pl.status != LinkStatus.Terminated &&
            // pl.status != last.status &&
            pl.startAddrMValue < last.endAddrMValue &&
            pl.endAddrMValue >= last.endAddrMValue)

        // TODO: check section end is a status change point (--> remove calibration point if not).
        /* val isStatusChange = roadPartLinks.find(pl =>
                pl.track == last.track &&
                pl.startingPoint == last.endPoint
              )
             if (isStatusChange.isDefined) isStatusChange.get.status != last.status*/

        if (hasOtherSideLink.nonEmpty) {
          val startCP = last.startCalibrationPointType match {
            case JunctionPointCP => JunctionPointCP
            case UserDefinedCP => UserDefinedCP
            case _ => NoCP
          }
          val endCP = last.endCalibrationPointType match {
            case JunctionPointCP => JunctionPointCP
            case _ => UserDefinedCP
          }
          val otherSideLinkStartCP = last.startCalibrationPointType match {
            case JunctionPointCP => JunctionPointCP
            case UserDefinedCP => UserDefinedCP
            case _ => NoCP
          }
          val otherSideLinkEndCP = last.endCalibrationPointType match {
            case JunctionPointCP => JunctionPointCP
            case _ => UserDefinedCP
          }

          val otherSideLink = hasOtherSideLink.head

          if (otherSideLink.endAddrMValue != last.endAddrMValue) {
            val endPoints = TrackSectionOrder.findChainEndpoints(roadPartLinks.filter(pl => pl.endAddrMValue <= otherSideLink.endAddrMValue && pl.track == otherSideLink.track))
            val (plPart1, plPart2) = splitAt(otherSideLink, last.endAddrMValue, endPoints)
            val (newCP: Seq[Option[UserDefinedCalibrationPoint]], cpUpdatedPls) = createCalibrationPoints(startCP, endCP, otherSideLinkStartCP, otherSideLinkEndCP, plPart1)
            (Some(plPart1, plPart2), newCP, cpUpdatedPls)
          } else {
            val (newCP: Seq[Option[UserDefinedCalibrationPoint]], cpUpdatedPls) = createCalibrationPoints(startCP, endCP, otherSideLinkStartCP, otherSideLinkEndCP, otherSideLink)
            (None, newCP, cpUpdatedPls)
          }
        } else (None, Seq.empty[Option[UserDefinedCalibrationPoint]], None) //Seq(None)
      } else (None, Seq.empty[Option[UserDefinedCalibrationPoint]], None) //Seq(None)
    }

    // TODO: If other track is shorter, find its status changepoint if any and do split + calibration point.

    /* roadPartLinks? **/
    //      var ret_links = Array[Seq[ProjectLink]]()

    val otherSideLast = findOtherShorterTrackStatusChangePoint(last)
    if (otherSideLast.isDefined)
    //        ret_links :+=
      splitAndSetCalibrationPointsAtEnd(last, roadPartLinks)//.toSeq ++ splitAndSetCalibrationPointsAtEnd(otherSideLast.get, roadPartLinks).toSeq
    else
    //        ret_links :+=
      splitAndSetCalibrationPointsAtEnd(last, roadPartLinks)
  }
  /* split end */

}
