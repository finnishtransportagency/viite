package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.{CalibrationPoint, ProjectLink}
import fi.liikennevirasto.viite.process.{ProjectSectionMValueCalculator, TrackSectionOrder}

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
            pl.copy(startAddrMValue = st.addressMValue, endAddrMValue = en.addressMValue, calibrationPoints = toCalibrationPoints(pl.linkId, Some(st), Some(en)))
          case s if s.size == 1 && s.head.segmentMValue == 0.0 =>
            pl.copy(startAddrMValue = s.head.addressMValue, calibrationPoints = toCalibrationPoints(pl.linkId, Some(s.head), None))
          case s if s.size == 1 && s.head.segmentMValue != 0.0 =>
            pl.copy(endAddrMValue = s.head.addressMValue, calibrationPoints = toCalibrationPoints(pl.linkId, None, Some(s.head)))
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
