package fi.liikennevirasto.viite.util

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, BothDirections, TowardsDigitizing, Unknown}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.{CalibrationPointLocation, CalibrationPointType}
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.{BaseCalibrationPoint, UserDefinedCalibrationPoint}
import fi.liikennevirasto.viite.dao._
import org.slf4j.LoggerFactory

object CalibrationPointsUtils {

  private val logger = LoggerFactory.getLogger(getClass)

  def toCalibrationPoints(startCalibrationPoint: CalibrationPointType, endCalibrationPoint: CalibrationPointType,
                          linkId: Long, startMValue: Double, endMValue: Double, startAddrMValue: Long,
                          endAddrMValue: Long, sideCode: SideCode):
  (Option[CalibrationPoint], Option[CalibrationPoint]) = {
    (sideCode: SideCode) match {
      case BothDirections => (None, None) // Invalid choice
      case TowardsDigitizing => (
        if ((startCalibrationPoint: CalibrationPointType) != CalibrationPointType.NoCP) Some(CalibrationPoint(linkId: Long, 0.0, startAddrMValue: Long, startCalibrationPoint: CalibrationPointType)) else None,
        if ((endCalibrationPoint: CalibrationPointType) != CalibrationPointType.NoCP) Some(CalibrationPoint(linkId: Long, (endMValue: Double) - (startMValue: Double), endAddrMValue: Long, endCalibrationPoint: CalibrationPointType)) else None
      )
      case AgainstDigitizing => (
        if ((startCalibrationPoint: CalibrationPointType) != CalibrationPointType.NoCP) Some(CalibrationPoint(linkId: Long, (endMValue: Double) - (startMValue: Double), startAddrMValue: Long, startCalibrationPoint: CalibrationPointType)) else None,
        if ((endCalibrationPoint: CalibrationPointType) != CalibrationPointType.NoCP) Some(CalibrationPoint(linkId: Long, 0.0, endAddrMValue: Long, endCalibrationPoint: CalibrationPointType)) else None
      )
      case Unknown => (None, None) // Invalid choice
    }
  }

  def toCalibrationPoint(ocp: BaseCalibrationPoint): CalibrationPoint = {
    CalibrationPoint(ocp.linkId, ocp.segmentMValue, ocp.addressMValue)
  }

  def toCalibrationPoints(ocp: (Option[BaseCalibrationPoint], Option[BaseCalibrationPoint])): (Option[CalibrationPoint], Option[CalibrationPoint]) = {
    ocp match {
      case (None, None) => (Option.empty[CalibrationPoint], Option.empty[CalibrationPoint])
      case (None, Some(cp)) => (Option.empty[CalibrationPoint], Option(toCalibrationPoint(cp)))
      case (Some(cp), None) => (Option(toCalibrationPoint(cp)) , Option.empty[CalibrationPoint])
      case (Some(cp1), Some(cp2)) => (Option(toCalibrationPoint(cp1)), Option(toCalibrationPoint(cp2)))
    }
  }

  def makeStartCP(roadAddress: RoadAddress) = {
    Some(CalibrationPoint(roadAddress.linkId,
      if (roadAddress.sideCode == TowardsDigitizing) 0.0
      else GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.startAddrMValue, roadAddress.startCalibrationPointType))
  }

  def makeStartCP(projectLink: ProjectLink) = {
    Some(CalibrationPoint(projectLink.linkId,
      if (projectLink.sideCode == TowardsDigitizing) 0.0
      else projectLink.geometryLength, projectLink.startAddrMValue, projectLink.startCalibrationPointType))
  }

  def makeEndCP(roadAddress: RoadAddress) = {
    Some(CalibrationPoint(roadAddress.linkId,
      if (roadAddress.sideCode == AgainstDigitizing) 0.0
      else GeometryUtils.geometryLength(roadAddress.geometry), roadAddress.endAddrMValue, roadAddress.endCalibrationPointType))
  }

  def makeEndCP(projectLink: ProjectLink, userDefinedCalibrationPoint: Option[UserDefinedCalibrationPoint]) = {
    val segmentValue = if (projectLink.sideCode == AgainstDigitizing) 0.0 else projectLink.geometryLength
    val addressValue = userDefinedCalibrationPoint match {
      case Some(userCalibrationPoint) => if (userCalibrationPoint.addressMValue < projectLink.startAddrMValue) projectLink.endAddrMValue else userCalibrationPoint.addressMValue
      case None => projectLink.endAddrMValue
    }
    Some(CalibrationPoint(projectLink.linkId, segmentValue, addressValue, projectLink.endCalibrationPointType))
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

  def createCalibrationPointIfNeeded(rwPoint: Long, linkId: Long, calibrationPointLocation: CalibrationPointLocation,
                                     calibrationPointType: CalibrationPointType, username: String): Unit = {
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

  def toCalibrationPointReference(cp: Option[CalibrationPoint]): CalibrationPointReference = {
    cp match {
      case Some(x) =>
        CalibrationPointReference(
          Some(x.addressMValue),
          Some(x.typeCode))
      case _ => CalibrationPointReference.None
    }
  }

}
