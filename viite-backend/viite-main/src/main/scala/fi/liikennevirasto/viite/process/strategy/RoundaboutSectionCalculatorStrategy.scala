package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.dao.ProjectLink
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.process.{ProjectSectionMValueCalculator, TrackSectionOrder}
import fi.liikennevirasto.viite.NewIdValue
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.model.{AddrMRange, CalibrationPointType, RoadAddressChangeType}

class RoundaboutSectionCalculatorStrategy extends RoadAddressSectionCalculatorStrategy {

  override val name: String = "Roundabout"

  override def applicableStrategy(projectLinks: Seq[ProjectLink]): Boolean = {
    TrackSectionOrder.isRoundabout(projectLinks)
  }

  override def assignMValues(newProjectLinks: Seq[ProjectLink], oldProjectLinks: Seq[ProjectLink], userCalibrationPoints: Seq[UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
    val startingLink = oldProjectLinks.sortBy(_.addrMRange.startAddrM).headOption.orElse(
      newProjectLinks.find(pl => pl.addrMRange.endAddrM != 0 && pl.addrMRange.startAddrM == 0)).orElse(
      newProjectLinks.headOption).toSeq
    val rest = (newProjectLinks ++ oldProjectLinks).filterNot(startingLink.contains)
    val mValued = TrackSectionOrder.mValueRoundabout(startingLink ++ rest)
    val (newLinksWithoutRoadwayNumber, newLinkswithRoadwayNumber) = mValued.partition(npl => npl.status == RoadAddressChangeType.New && (npl.roadwayNumber == NewIdValue || npl.roadwayNumber == 0))
    var mValuedWithRwns = if (newLinksWithoutRoadwayNumber.nonEmpty) {
      val newRoadwayNumber = Sequences.nextRoadwayNumber
      (newLinksWithoutRoadwayNumber.map(_.copy(roadwayNumber = newRoadwayNumber)) ++ newLinkswithRoadwayNumber).sortBy(_.addrMRange.startAddrM)
    } else mValued

    val startPl = mValuedWithRwns.minBy(_.addrMRange.startAddrM)
    val endPl   = mValuedWithRwns.maxBy(_.addrMRange.endAddrM)

    mValuedWithRwns = startPl.copy(calibrationPointTypes = (CalibrationPointType.RoadAddressCP, startPl.endCalibrationPointType)) +: mValuedWithRwns.filterNot(pl => Seq(startPl.id,endPl.id).contains(pl.id)) :+ endPl.copy(calibrationPointTypes = (endPl.startCalibrationPointType,CalibrationPointType.RoadAddressCP))

    if (userCalibrationPoints.nonEmpty) {
      val withCalibration = mValuedWithRwns.map(pl =>
        userCalibrationPoints.filter(_.projectLinkId == pl.id) match {
          case s if s.size == 2 =>
            val (st, en) = (s.minBy(_.addressMValue), s.maxBy(_.addressMValue))
            val startCPType = pl.startCalibrationPointType
            val endCPType = if (pl.originalEndCalibrationPointType == CalibrationPointType.NoCP) CalibrationPointType.RoadAddressCP else pl.endCalibrationPointType
            pl.copy(addrMRange = AddrMRange(st.addressMValue, en.addressMValue), calibrationPointTypes = (startCPType, endCPType))
          case s if s.size == 1 && s.head.segmentMValue == 0.0 =>
            val startCPType =  pl.startCalibrationPointType
            val endCPType = if (pl.originalEndCalibrationPointType == CalibrationPointType.NoCP) CalibrationPointType.RoadAddressCP else pl.endCalibrationPointType
            pl.copy(addrMRange = AddrMRange(s.head.addressMValue, pl.addrMRange.endAddrM), calibrationPointTypes = (startCPType, endCPType))
          case s if s.size == 1 && s.head.segmentMValue != 0.0 =>
            val startCPType = pl.startCalibrationPointType
            val endCPType = if (pl.originalEndCalibrationPointType == CalibrationPointType.NoCP) CalibrationPointType.RoadAddressCP else pl.endCalibrationPointType
            pl.copy(addrMRange = AddrMRange(pl.addrMRange.startAddrM, s.head.addressMValue), calibrationPointTypes = (startCPType, endCPType))
          case _ =>
            pl.copy(calibrationPointTypes = (CalibrationPointType.NoCP, CalibrationPointType.NoCP))
        }
      )
      val factors = ProjectSectionMValueCalculator.calculateAddressingFactors(withCalibration)
      val coEff = (withCalibration.map(_.addrMRange.endAddrM).max - factors.unChangedLength - factors.transferLength) / factors.newLength
      val calMap = userCalibrationPoints.map(c => c.projectLinkId -> c).toMap
      ProjectSectionMValueCalculator.assignLinkValues(withCalibration, calMap, None, None, coEff)
    } else {
      mValuedWithRwns
    }
  }
}
