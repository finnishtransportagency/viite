package fi.liikennevirasto.viite.util

import fi.liikennevirasto.digiroad2.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, BothDirections, TowardsDigitizing, Unknown}
import fi.liikennevirasto.viite.dao.CalibrationCode.{AtBeginning, AtBoth, AtEnd, No}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.{CalibrationCode, CalibrationPoint, ProjectLink, RoadAddress}

object CalibrationPointsUtils {

  def calibrations(calibrationCode: CalibrationCode, projectLink: ProjectLink): (Option[CalibrationPoint], Option[CalibrationPoint]) = {
    calibrations(calibrationCode, projectLink.linkId, projectLink.startMValue, projectLink.endMValue, projectLink.startAddrMValue, projectLink.endAddrMValue, projectLink.sideCode)
  }

  def calibrations(calibrationCode: CalibrationCode, linkId: Long, startMValue: Double, endMValue: Double,
                           startAddrMValue: Long, endAddrMValue: Long, sideCode: SideCode): (Option[CalibrationPoint], Option[CalibrationPoint]) = {
    sideCode match {
      case BothDirections => (None, None) // Invalid choice
      case TowardsDigitizing => calibrations(calibrationCode, linkId, 0.0, endMValue - startMValue, startAddrMValue, endAddrMValue)
      case AgainstDigitizing => calibrations(calibrationCode, linkId, endMValue - startMValue, 0.0, startAddrMValue, endAddrMValue)
      case Unknown => (None, None)  // Invalid choice
    }
  }

  def calibrations(calibrationCode: CalibrationCode, linkId: Long, segmentStartMValue: Double, segmentEndMValue: Double,
                           startAddrMValue: Long, endAddrMValue: Long): (Option[CalibrationPoint], Option[CalibrationPoint]) = {
    calibrationCode match {
      case No => (None, None)
      case AtEnd => (None, Some(CalibrationPoint(linkId, segmentEndMValue, endAddrMValue)))
      case AtBeginning => (Some(CalibrationPoint(linkId, segmentStartMValue, startAddrMValue)), None)
      case AtBoth => (Some(CalibrationPoint(linkId, segmentStartMValue, startAddrMValue)),
        Some(CalibrationPoint(linkId, segmentEndMValue, endAddrMValue)))
    }
  }

  def makeStartCP(roadAddress: RoadAddress) = {
    Some(CalibrationPoint(roadAddress.linkId, if (roadAddress.sideCode == TowardsDigitizing)
      0.0 else
      GeometryUtils.geometryLength(roadAddress.geometry),
      roadAddress.startAddrMValue))
  }

  def makeStartCP(projectLink: ProjectLink) = {
    Some(CalibrationPoint(projectLink.linkId, if (projectLink.sideCode == TowardsDigitizing) 0.0 else projectLink.geometryLength, projectLink.startAddrMValue))
  }

  def makeEndCP(roadAddress: RoadAddress) = {
    Some(CalibrationPoint(roadAddress.linkId, if (roadAddress.sideCode == AgainstDigitizing)
      0.0 else
      GeometryUtils.geometryLength(roadAddress.geometry),
      roadAddress.endAddrMValue))
  }

  def makeEndCP(projectLink: ProjectLink, userDefinedCalibrationPoint: Option[UserDefinedCalibrationPoint]) = {
    val segmentValue = if (projectLink.sideCode == AgainstDigitizing) 0.0 else projectLink.geometryLength
    val addressValue = userDefinedCalibrationPoint match {
      case Some(userCalibrationPoint) => if (userCalibrationPoint.addressMValue < projectLink.startAddrMValue) projectLink.endAddrMValue else userCalibrationPoint.addressMValue
      case None => projectLink.endAddrMValue
    }
    Some(CalibrationPoint(projectLink.linkId, segmentValue, addressValue))
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

}
