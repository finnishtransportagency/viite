package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{NoCP, RoadAddressCP}
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.{CalibrationPoint, ProjectLink}
import fi.liikennevirasto.viite.process.{ProjectSectionMValueCalculator, TrackSectionOrder}
import fi.liikennevirasto.viite.util.CalibrationPointsUtils

class RoundaboutSectionCalculatorStrategy extends RoadAddressSectionCalculatorStrategy {

  override val name: String = "Roundabout"

  override def applicableStrategy(projectLinks: Seq[ProjectLink]): Boolean = {
    TrackSectionOrder.isRoundabout(projectLinks)
  }

  override def assignMValues(newProjectLinks: Seq[ProjectLink], oldProjectLinks: Seq[ProjectLink], userCalibrationPoints: Seq[UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
    def toCalibrationPoints(linkId: Long, st: Option[UserDefinedCalibrationPoint], en: Option[UserDefinedCalibrationPoint]) = {
      (st.map(cp => CalibrationPoint(linkId, cp.segmentMValue, cp.addressMValue)),
        en.map(cp => CalibrationPoint(linkId, cp.segmentMValue, cp.addressMValue)))
    }

    val startingLink = oldProjectLinks.sortBy(_.startAddrMValue).headOption.orElse(
      newProjectLinks.find(pl => pl.endAddrMValue != 0 && pl.startAddrMValue == 0)).orElse(
      newProjectLinks.headOption).toSeq
    val rest = (newProjectLinks ++ oldProjectLinks).filterNot(startingLink.contains)
    val mValued = TrackSectionOrder.mValueRoundabout(startingLink ++ rest)
    if (userCalibrationPoints.nonEmpty) {
      val withCalibration = mValued.map(pl =>
        userCalibrationPoints.filter(_.projectLinkId == pl.id) match {
          case s if s.size == 2 =>
            val (st, en) = (s.minBy(_.addressMValue), s.maxBy(_.addressMValue))
            val calibrationPoints = toCalibrationPoints(pl.linkId, Some(st), Some(en))
            val startCPType = if (pl.originalStartCalibrationPointType == NoCP) RoadAddressCP else pl.startCalibrationPointType
            val endCPType = if (pl.originalEndCalibrationPointType == NoCP) RoadAddressCP else pl.endCalibrationPointType
            pl.copy(startAddrMValue = st.addressMValue, endAddrMValue = en.addressMValue,
              calibrationPoints = CalibrationPointsUtils.toProjectLinkCalibrationPointsWithTypeInfo(calibrationPoints, startCPType, endCPType))
          case s if s.size == 1 && s.head.segmentMValue == 0.0 =>
            val calibrationPoints = toCalibrationPoints(pl.linkId, Some(s.head), None)
            val startCPType = if (pl.originalStartCalibrationPointType == NoCP) RoadAddressCP else pl.startCalibrationPointType
            val endCPType = if (pl.originalEndCalibrationPointType == NoCP) RoadAddressCP else pl.endCalibrationPointType
            pl.copy(startAddrMValue = s.head.addressMValue,
              calibrationPoints = CalibrationPointsUtils.toProjectLinkCalibrationPointsWithTypeInfo(calibrationPoints, startCPType, endCPType))
          case s if s.size == 1 && s.head.segmentMValue != 0.0 =>
            val calibrationPoints = toCalibrationPoints(pl.linkId, None, Some(s.head))
            val startCPType = if (pl.originalStartCalibrationPointType == NoCP) RoadAddressCP else pl.startCalibrationPointType
            val endCPType = if (pl.originalEndCalibrationPointType == NoCP) RoadAddressCP else pl.endCalibrationPointType
            pl.copy(endAddrMValue = s.head.addressMValue,
              calibrationPoints = CalibrationPointsUtils.toProjectLinkCalibrationPointsWithTypeInfo(calibrationPoints, startCPType, endCPType))
          case _ =>
            pl.copy(calibrationPoints = (None, None))
        }
      )
      val factors = ProjectSectionMValueCalculator.calculateAddressingFactors(withCalibration)
      val coEff = (withCalibration.map(_.endAddrMValue).max - factors.unChangedLength - factors.transferLength) / factors.newLength
      val calMap = userCalibrationPoints.map(c => c.projectLinkId -> c).toMap
      ProjectSectionMValueCalculator.assignLinkValues(withCalibration, calMap, None, None, coEff)
    } else {
      mValued
    }
  }
}
