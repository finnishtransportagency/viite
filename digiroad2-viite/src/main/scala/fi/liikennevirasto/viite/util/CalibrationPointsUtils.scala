package fi.liikennevirasto.viite.util

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, BothDirections, TowardsDigitizing, Unknown}
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.NoCP
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.{CalibrationPointLocation, CalibrationPointType}
import fi.liikennevirasto.viite.dao.CalibrationPointSource.{NoCalibrationPoint, ProjectLinkSource, RoadAddressSource}
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.{BaseCalibrationPoint, UserDefinedCalibrationPoint}
import fi.liikennevirasto.viite.dao._
import org.slf4j.LoggerFactory

object CalibrationPointsUtils {

  private val logger = LoggerFactory.getLogger(getClass)

  def calibrations(startCalibrationPoint: CalibrationPointType, endCalibrationPoint: CalibrationPointType, linkId: Long, startMValue: Double, endMValue: Double,
                   startAddrMValue: Long, endAddrMValue: Long, sideCode: SideCode): (Option[CalibrationPoint], Option[CalibrationPoint]) = {
    sideCode match {
      case BothDirections => (None, None) // Invalid choice
      case TowardsDigitizing => (
        if (startCalibrationPoint != CalibrationPointType.NoCP) Some(CalibrationPoint(linkId, 0.0, startAddrMValue, startCalibrationPoint)) else None,
        if (endCalibrationPoint != CalibrationPointType.NoCP) Some(CalibrationPoint(linkId, endMValue - startMValue, endAddrMValue, endCalibrationPoint)) else None
      )
      case AgainstDigitizing => (
        if (startCalibrationPoint != CalibrationPointType.NoCP) Some(CalibrationPoint(linkId, endMValue - startMValue, startAddrMValue, startCalibrationPoint)) else None,
        if (endCalibrationPoint != CalibrationPointType.NoCP) Some(CalibrationPoint(linkId, 0.0, endAddrMValue, endCalibrationPoint)) else None
      )
      case Unknown => (None, None)  // Invalid choice
    }
  }

  def toProjectLinkCalibrationPoint(originalCalibrationPoint: BaseCalibrationPoint, roadwayId: Long = 0L): ProjectLinkCalibrationPoint = {
    roadwayId match {
      case 0L => ProjectLinkCalibrationPoint(originalCalibrationPoint.linkId, originalCalibrationPoint.segmentMValue, originalCalibrationPoint.addressMValue, ProjectLinkSource)
      case _ => ProjectLinkCalibrationPoint(originalCalibrationPoint.linkId, originalCalibrationPoint.segmentMValue, originalCalibrationPoint.addressMValue, RoadAddressSource)
    }
  }

  def toProjectLinkCalibrationPointWithSourceInfo(originalCalibrationPoint: BaseCalibrationPoint, source: CalibrationPointSource): ProjectLinkCalibrationPoint = {
    ProjectLinkCalibrationPoint(originalCalibrationPoint.linkId, originalCalibrationPoint.segmentMValue, originalCalibrationPoint.addressMValue, source)
  }

  def toProjectLinkCalibrationPointWithTypeInfo(originalCalibrationPoint: BaseCalibrationPoint, typeCode: CalibrationPointType): ProjectLinkCalibrationPoint = {
    val source = typeCodeToSource(typeCode)
    ProjectLinkCalibrationPoint(originalCalibrationPoint.linkId, originalCalibrationPoint.segmentMValue, originalCalibrationPoint.addressMValue, source)
  }

  def typeCodeToSource(pointType: CalibrationPointDAO.CalibrationPointType): CalibrationPointSource = {
    pointType match {
      case NoCP => NoCalibrationPoint
      case CalibrationPointType.UserDefinedCP => CalibrationPointSource.RoadAddressSource
      case CalibrationPointType.JunctionPointCP => CalibrationPointSource.JunctionPointSource
      case CalibrationPointType.RoadAddressCP => CalibrationPointSource.RoadAddressSource
      case CalibrationPointType.ProjectCP => CalibrationPointSource.ProjectLinkSource
      case _ => CalibrationPointSource.UnknownSource
    }
  }

  def toProjectLinkCalibrationPointWithTypeInfo(originalCalibrationPoint: BaseCalibrationPoint, source: CalibrationPointSource): ProjectLinkCalibrationPoint = {
    ProjectLinkCalibrationPoint(originalCalibrationPoint.linkId, originalCalibrationPoint.segmentMValue, originalCalibrationPoint.addressMValue, source)
  }

  def toProjectLinkCalibrationPoints(originalCalibrationPoints: (Option[BaseCalibrationPoint], Option[BaseCalibrationPoint]), roadwayId: Long = 0L): (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = {
    originalCalibrationPoints match {
      case (None, None) => (Option.empty[ProjectLinkCalibrationPoint], Option.empty[ProjectLinkCalibrationPoint])
      case (Some(cp1), None) => (Option(toProjectLinkCalibrationPoint(cp1, roadwayId)), Option.empty[ProjectLinkCalibrationPoint])
      case (None, Some(cp1)) => (Option.empty[ProjectLinkCalibrationPoint], Option(toProjectLinkCalibrationPoint(cp1, roadwayId)))
      case (Some(cp1),Some(cp2)) => (Option(toProjectLinkCalibrationPoint(cp1, roadwayId)), Option(toProjectLinkCalibrationPoint(cp2, roadwayId)))
    }
  }

  def toProjectLinkCalibrationPointsWithSourceInfo(originalCalibrationPoints: (Option[CalibrationPoint], Option[CalibrationPoint])):
  (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = {

    originalCalibrationPoints match {
      case (None, None) => (Option.empty[ProjectLinkCalibrationPoint], Option.empty[ProjectLinkCalibrationPoint])
      case (Some(cp), None) => (Option(toProjectLinkCalibrationPointWithSourceInfo(cp, cp.source)), Option.empty[ProjectLinkCalibrationPoint])
      case (None, Some(cp)) => (Option.empty[ProjectLinkCalibrationPoint], Option(toProjectLinkCalibrationPointWithSourceInfo(cp, cp.source)))
      case (Some(cp1), Some(cp2)) => (Option(toProjectLinkCalibrationPointWithSourceInfo(cp1, cp1.source)), Option(toProjectLinkCalibrationPointWithSourceInfo(cp2, cp2.source)))
    }
  }

  def toProjectLinkCalibrationPointsWithTypeInfo(originalCalibrationPoints: (Option[BaseCalibrationPoint], Option[BaseCalibrationPoint]), typeCode: CalibrationPointType): (Option[ProjectLinkCalibrationPoint], Option[ProjectLinkCalibrationPoint]) = {
    originalCalibrationPoints match {
      case (None, None) => (Option.empty[ProjectLinkCalibrationPoint], Option.empty[ProjectLinkCalibrationPoint])
      case (Some(cp1), None) => (Option(toProjectLinkCalibrationPointWithTypeInfo(cp1, typeCode)), Option.empty[ProjectLinkCalibrationPoint])
      case (None, Some(cp1)) => (Option.empty[ProjectLinkCalibrationPoint], Option(toProjectLinkCalibrationPointWithTypeInfo(cp1, typeCode)))
      case (Some(cp1),Some(cp2)) => (Option(toProjectLinkCalibrationPointWithTypeInfo(cp1, typeCode)), Option(toProjectLinkCalibrationPointWithTypeInfo(cp2, typeCode)))
    }
  }

  def toCalibrationPoint(ocp: BaseCalibrationPoint): CalibrationPoint = {
    CalibrationPoint(ocp.linkId, ocp.segmentMValue, ocp.addressMValue)
  }

  def toCalibrationPoints(ocp: (Option[BaseCalibrationPoint], Option[BaseCalibrationPoint])): (Option[CalibrationPoint], Option[CalibrationPoint]) = {
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

  def toCalibrationPointReference(cp: Option[ProjectLinkCalibrationPoint]): CalibrationPointReference = {
    cp match {
      case Some(x) =>
        CalibrationPointReference(
          Some(x.addressMValue),
          Some(CalibrationPointType.RoadAddressCP)) // TODO type from project must be handled if needed in the future.
      case _ => CalibrationPointReference.None
    }
  }

}
