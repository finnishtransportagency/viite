package fi.liikennevirasto.viite.util

import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.{BaseCalibrationPoint, UserDefinedCalibrationPoint}
import fi.liikennevirasto.viite.dao._
import fi.vaylavirasto.viite.geometry.GeometryUtils
import fi.vaylavirasto.viite.model.{AddrMRange, CalibrationPointLocation, CalibrationPointType, SideCode}
import org.slf4j.LoggerFactory

object CalibrationPointsUtils {

  private val logger = LoggerFactory.getLogger(getClass)

  def toCalibrationPoints(startCalibrationPoint: CalibrationPointType, endCalibrationPoint: CalibrationPointType, linkId: String, startMValue: Double, endMValue: Double, addrMRange: AddrMRange, sideCode: SideCode):
  (Option[ProjectCalibrationPoint], Option[ProjectCalibrationPoint]) = {
    val length = endMValue - startMValue
    (sideCode: SideCode) match {
      case SideCode.TowardsDigitizing => (
        if (startCalibrationPoint != CalibrationPointType.NoCP) Some(ProjectCalibrationPoint(linkId, 0.0,    addrMRange.start, startCalibrationPoint)) else None,
        if (endCalibrationPoint   != CalibrationPointType.NoCP) Some(ProjectCalibrationPoint(linkId, length, addrMRange.end,   endCalibrationPoint  )) else None
      )
      case SideCode.AgainstDigitizing => (
        if (startCalibrationPoint != CalibrationPointType.NoCP) Some(ProjectCalibrationPoint(linkId, length, addrMRange.start, startCalibrationPoint)) else None,
        if (endCalibrationPoint   != CalibrationPointType.NoCP) Some(ProjectCalibrationPoint(linkId, 0.0,    addrMRange.end,   endCalibrationPoint  )) else None
      )
      case SideCode.Unknown => (None, None) // Invalid choice
    }
  }

  def toCalibrationPoint(ocp: BaseCalibrationPoint): ProjectCalibrationPoint = {
    ProjectCalibrationPoint(ocp.linkId(), ocp.segmentMValue, ocp.addressMValue)
  }

  def toCalibrationPoints(ocp: (Option[BaseCalibrationPoint], Option[BaseCalibrationPoint])): (Option[ProjectCalibrationPoint], Option[ProjectCalibrationPoint]) = {
    ocp match {
      case (None, None) => (Option.empty[ProjectCalibrationPoint], Option.empty[ProjectCalibrationPoint])
      case (None, Some(cp)) => (Option.empty[ProjectCalibrationPoint], Option(toCalibrationPoint(cp)))
      case (Some(cp), None) => (Option(toCalibrationPoint(cp)) , Option.empty[ProjectCalibrationPoint])
      case (Some(cp1), Some(cp2)) => (Option(toCalibrationPoint(cp1)), Option(toCalibrationPoint(cp2)))
    }
  }

  def makeStartCP(roadAddress: RoadAddress) = {
    Some(ProjectCalibrationPoint(roadAddress.linkId,
      if (roadAddress.sideCode == SideCode.TowardsDigitizing) 0.0
      else GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.addrMRange.start, roadAddress.startCalibrationPointType))
  }

  def makeStartCP(projectLink: ProjectLink) = {
    Some(ProjectCalibrationPoint(projectLink.linkId,
      if (projectLink.sideCode == SideCode.TowardsDigitizing) 0.0
      else projectLink.geometryLength, projectLink.addrMRange.start, projectLink.startCalibrationPointType))
  }

  def makeEndCP(roadAddress: RoadAddress) = {
    Some(ProjectCalibrationPoint(roadAddress.linkId,
      if (roadAddress.sideCode == SideCode.AgainstDigitizing) 0.0
      else GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.addrMRange.end, roadAddress.endCalibrationPointType))
  }

  def makeEndCP(projectLink: ProjectLink, userDefinedCalibrationPoint: Option[UserDefinedCalibrationPoint]) = {
    val segmentValue = if (projectLink.sideCode == SideCode.AgainstDigitizing) 0.0 else projectLink.geometryLength
    val addressValue = userDefinedCalibrationPoint match {
      case Some(userCalibrationPoint) => if (userCalibrationPoint.addressMValue < projectLink.addrMRange.start) projectLink.addrMRange.end else userCalibrationPoint.addressMValue
      case None => projectLink.addrMRange.end
    }
    Some(ProjectCalibrationPoint(projectLink.linkId, segmentValue, addressValue, projectLink.endCalibrationPointType))
  }

  def fillCPs(roadAddress: RoadAddress, atStart: Boolean = false, atEnd: Boolean = false): RoadAddress = {
    val startCP = makeStartCP(roadAddress)
    val endCP = makeEndCP(roadAddress)
    if (atStart && atEnd) {
      roadAddress.copy(calibrationPoints = (startCP, endCP))
    } else {
      if (atEnd) {
        roadAddress.copy(calibrationPoints = (None, endCP))
      } else if (atStart) {
        roadAddress.copy(calibrationPoints = (startCP, None))
      } else roadAddress
    }
  }

  def createCalibrationPointIfNeeded(rwPoint: Long, linkId: String, calibrationPointLocation: CalibrationPointLocation, calibrationPointType: CalibrationPointType, username: String): Unit = {
    val existing = CalibrationPointDAO.fetch(linkId, calibrationPointLocation.value)

    // The existing correct kind of calibration point has the same roadwayPointId and the same or a higher type.
    val (existingCorrect, existingWrong) = existing.partition(cp => cp.roadwayPointId == rwPoint && cp.typeCode >= calibrationPointType)
    
    if (existingWrong.nonEmpty) {
      CalibrationPointDAO.expireById(existingWrong.map(_.id))
    }
    if (existingCorrect.isEmpty) {
      logger.info(s"Creating CalibrationPoint with RoadwayPoint id : $rwPoint linkId : $linkId startOrEnd: ${calibrationPointLocation.value}")
      CalibrationPointDAO.create(rwPoint, linkId, calibrationPointLocation, calType = calibrationPointType, createdBy = username)
    }
  }

  def toCalibrationPointReference(cp: Option[ProjectCalibrationPoint]): CalibrationPointReference = {
    cp match {
      case Some(x) =>
        CalibrationPointReference(
          Some(x.addressMValue),
          Some(x.typeCode))
      case _ => CalibrationPointReference.None
    }
  }

  def updateCalibrationPointAddress(oldRwPoint: Long, newRwPoint: Long, username: String): Unit = {

    // User is allowed to update only user defined and junction point calibration points.
    // If the CalibrationPoint is RoadAddress calibration point, throw exception
    val oldCPs = CalibrationPointDAO.fetchByRoadwayPointId(oldRwPoint)
    if (oldCPs.exists(_.typeCode == CalibrationPointType.RoadAddressCP)) {
      logger.error(s"Updating the address of the road address calibration point is prohibited." + oldCPs.map( oldCP =>
        s" (roadwayNumber: ${oldCP.roadwayNumber}, address: ${oldCP.addrM}, linkId: ${oldCP.linkId}, startEnd: ${oldCP.startOrEnd.value})"
      ))
      throw new Exception(s"Tieosoitteen kalibrointipisteen osoitteen muokkaus ei ole sallittu.")
    }

    CalibrationPointDAO.expireById(oldCPs.map(_.id))
    oldCPs.foreach(oldCP => {
      val newCP = oldCP.copy(id = NewIdValue, roadwayPointId = newRwPoint, createdBy = username)
      val existingCPInPoint = CalibrationPointDAO.fetchByRoadwayPointId(newRwPoint)
      if (existingCPInPoint.nonEmpty && existingCPInPoint.length > 1) {
        logger.error(s"Cannot update calibration point ${oldCP.id} to a new address. There is already more than one calibration points at roadway point $newRwPoint.")
        throw new Exception(s"Kalibrointipisteen osoitteen muokkaus epäonnistui.")
      }
      CalibrationPointDAO.create(newCP.roadwayPointId, newCP.linkId, newCP.startOrEnd, newCP.typeCode, newCP.createdBy)
      logger.debug(s"Updated calibration point from roadway point $oldRwPoint to $newRwPoint.")
    })
  }

}
