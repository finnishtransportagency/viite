package fi.liikennevirasto.viite.util

import fi.liikennevirasto.digiroad2.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, BothDirections, TowardsDigitizing, Unknown}
import fi.liikennevirasto.viite.dao.CalibrationCode.{AtBeginning, AtBoth, AtEnd, No}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.{BaseCalibrationPoint, UserDefinedCalibrationPoint}
import fi.liikennevirasto.viite.dao._

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

  def toProjectLinkCalibrationPoint(originalCalibrationPoint: BaseCalibrationPoint, roadAddressId: Long = 0L): ProjectLinkCalibrationPoint = {
    roadAddressId match {
      case 0L => ProjectLinkCalibrationPoint(originalCalibrationPoint.linkId, originalCalibrationPoint.segmentMValue, originalCalibrationPoint.addressMValue, true)
      case _ => ProjectLinkCalibrationPoint(originalCalibrationPoint.linkId, originalCalibrationPoint.segmentMValue, originalCalibrationPoint.addressMValue, false)
    }
  }

  def toProjectLinkCalibrationPointWithSplitInfo(originalCalibrationPoint: BaseCalibrationPoint, splitValue: Boolean = false): ProjectLinkCalibrationPoint = {
    ProjectLinkCalibrationPoint(originalCalibrationPoint.linkId, originalCalibrationPoint.segmentMValue, originalCalibrationPoint.addressMValue, splitValue)
  }

  def toProjectLinkCalibrationPoints(originalCalibrationPoints: (Option[BaseCalibrationPoint], Option[BaseCalibrationPoint]), roadAddressId: Long = 0L): (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = {
    originalCalibrationPoints match {
      case (None, None) => (Option.empty[ProjectLinkCalibrationPoint], Option.empty[ProjectLinkCalibrationPoint])
      case (Some(cp1), None) => (Option(toProjectLinkCalibrationPoint(cp1, roadAddressId)), Option.empty[ProjectLinkCalibrationPoint])
      case (None, Some(cp1)) => (Option.empty[ProjectLinkCalibrationPoint], Option(toProjectLinkCalibrationPoint(cp1, roadAddressId)))
      case (Some(cp1),Some(cp2)) => (Option(toProjectLinkCalibrationPoint(cp1, roadAddressId)), Option(toProjectLinkCalibrationPoint(cp2, roadAddressId)))
    }
  }

  def toProjectLinkCalibrationPointsWithSplitInfo(originalCalibrationPoints: (Option[BaseCalibrationPoint], Option[BaseCalibrationPoint]), splitValue: Boolean = false): (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = {
    originalCalibrationPoints match {
      case (None, None) => (Option.empty[ProjectLinkCalibrationPoint], Option.empty[ProjectLinkCalibrationPoint])
      case (Some(cp1), None) => (Option(toProjectLinkCalibrationPointWithSplitInfo(cp1, splitValue)), Option.empty[ProjectLinkCalibrationPoint])
      case (None, Some(cp1)) => (Option.empty[ProjectLinkCalibrationPoint], Option(toProjectLinkCalibrationPointWithSplitInfo(cp1, splitValue)))
      case (Some(cp1),Some(cp2)) => (Option(toProjectLinkCalibrationPointWithSplitInfo(cp1, splitValue)), Option(toProjectLinkCalibrationPointWithSplitInfo(cp2, splitValue)))
    }
  }

  def toCalibrationPoint(ocp: BaseCalibrationPoint): CalibrationPoint = {
    CalibrationPoint(ocp.linkId, ocp.segmentMValue, ocp.addressMValue)
  }

  def toCalibrationPoints(ocp: (Option[BaseCalibrationPoint], Option[BaseCalibrationPoint])): (Option[CalibrationPoint] , Option[CalibrationPoint])= {
    ocp match {
      case (None, None) => (Option.empty[CalibrationPoint], Option.empty[CalibrationPoint])
      case (None, Some(cp1)) => (Option.empty[CalibrationPoint], Option(toCalibrationPoint(cp1)))
      case (Some(cp1), None) => (Option(toCalibrationPoint(cp1)) , Option.empty[CalibrationPoint])
      case (Some(cp1), Some(cp2)) => (Option(toCalibrationPoint(cp1)), Option(toCalibrationPoint(cp2)))
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
