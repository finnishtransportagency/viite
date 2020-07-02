package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType.{NoCP, RoadAddressCP}
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.{ProjectLink}
import fi.liikennevirasto.viite.process.{ProjectSectionMValueCalculator, TrackSectionOrder}

class RoundaboutSectionCalculatorStrategy extends RoadAddressSectionCalculatorStrategy {

  override val name: String = "Roundabout"

  override def applicableStrategy(projectLinks: Seq[ProjectLink]): Boolean = {
    TrackSectionOrder.isRoundabout(projectLinks)
  }

  override def assignMValues(newProjectLinks: Seq[ProjectLink], oldProjectLinks: Seq[ProjectLink], userCalibrationPoints: Seq[UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
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
            val startCPType = if (pl.originalStartCalibrationPointType == NoCP) RoadAddressCP else pl.startCalibrationPointType
            val endCPType = if (pl.originalEndCalibrationPointType == NoCP) RoadAddressCP else pl.endCalibrationPointType
            pl.copy(startAddrMValue = st.addressMValue, endAddrMValue = en.addressMValue, calibrationPointTypes = (startCPType, endCPType))
          case s if s.size == 1 && s.head.segmentMValue == 0.0 =>
            val startCPType = if (pl.originalStartCalibrationPointType == NoCP) RoadAddressCP else pl.startCalibrationPointType
            val endCPType = if (pl.originalEndCalibrationPointType == NoCP) RoadAddressCP else pl.endCalibrationPointType
            pl.copy(startAddrMValue = s.head.addressMValue, calibrationPointTypes = (startCPType, endCPType))
          case s if s.size == 1 && s.head.segmentMValue != 0.0 =>
            val startCPType = if (pl.originalStartCalibrationPointType == NoCP) RoadAddressCP else pl.startCalibrationPointType
            val endCPType = if (pl.originalEndCalibrationPointType == NoCP) RoadAddressCP else pl.endCalibrationPointType
            pl.copy(endAddrMValue = s.head.addressMValue, calibrationPointTypes = (startCPType, endCPType))
          case _ =>
            pl.copy(calibrationPointTypes = (NoCP, NoCP))
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
