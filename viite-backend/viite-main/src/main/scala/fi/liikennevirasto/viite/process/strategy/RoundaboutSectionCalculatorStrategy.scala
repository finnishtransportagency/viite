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

  override def assignAddrMValues(newProjectLinks: Seq[ProjectLink], oldProjectLinks: Seq[ProjectLink], userCalibrationPoints: Seq[UserDefinedCalibrationPoint]): Seq[ProjectLink] = {
    val startingLink = oldProjectLinks.sortBy(_.addrMRange.start).headOption.orElse(
      newProjectLinks.find(pl => pl.addrMRange.end != 0 && pl.addrMRange.start == 0)).orElse(
      newProjectLinks.headOption).toSeq
    val rest = (newProjectLinks ++ oldProjectLinks).filterNot(startingLink.contains)
    val mValued = TrackSectionOrder.mValueRoundabout(startingLink ++ rest)
    val (newProjectLinksWithAddressMValues,otherProjectLinks) = mValued.partition(pl => pl.status == RoadAddressChangeType.New)
    val (newLinksWithoutRoadwayNumber, newLinksWithRoadwayNumber) = newProjectLinksWithAddressMValues.partition(npl => npl.status == RoadAddressChangeType.New && (npl.roadwayNumber == NewIdValue || npl.roadwayNumber == 0))
    var mValuedWithRwns = {
      val newLinksWithAssignedRoadwayNumbers = if (newLinksWithoutRoadwayNumber.nonEmpty) {
        val roadwaySections = getContinuousRoadwaySections(newLinksWithoutRoadwayNumber)
        val linksWithAssignedRoadwayNumbers = roadwaySections.flatMap(section => {
          val newRoadwayNumber = Sequences.nextRoadwayNumber
          section.map(pl => pl.copy(roadwayNumber = newRoadwayNumber))
        })
        linksWithAssignedRoadwayNumbers ++ newLinksWithRoadwayNumber
      } else newLinksWithRoadwayNumber

      val otherLinksWithRoadwayNumbers = if(otherProjectLinks.size > 1) {
        setRoadwaysForOtherThanNewLinks(otherProjectLinks)
      } else otherProjectLinks
      newLinksWithAssignedRoadwayNumbers ++ otherLinksWithRoadwayNumbers
    }

    val startPl = mValuedWithRwns.minBy(_.addrMRange.start)
    val endPl   = mValuedWithRwns.maxBy(_.addrMRange.end)

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
            pl.copy(addrMRange = AddrMRange(s.head.addressMValue, pl.addrMRange.end), calibrationPointTypes = (startCPType, endCPType))
          case s if s.size == 1 && s.head.segmentMValue != 0.0 =>
            val startCPType = pl.startCalibrationPointType
            val endCPType = if (pl.originalEndCalibrationPointType == CalibrationPointType.NoCP) CalibrationPointType.RoadAddressCP else pl.endCalibrationPointType
            pl.copy(addrMRange = AddrMRange(pl.addrMRange.start, s.head.addressMValue), calibrationPointTypes = (startCPType, endCPType))
          case _ =>
            pl.copy(calibrationPointTypes = (CalibrationPointType.NoCP, CalibrationPointType.NoCP))
        }
      )
      val factors = ProjectSectionMValueCalculator.calculateAddressingFactors(withCalibration)
      val coEff = (withCalibration.map(_.addrMRange.end).max - factors.unChangedLength - factors.transferLength) / factors.newLength
      val calMap = userCalibrationPoints.map(c => c.projectLinkId -> c).toMap
      ProjectSectionMValueCalculator.assignLinkValues(withCalibration, calMap, None, coEff)
    } else {
      mValuedWithRwns
    }
  }

  def getContinuousRoadwaySections(projectLinks: Seq[ProjectLink]): Seq[Seq[ProjectLink]] = {
    if (projectLinks.isEmpty) {
      Seq.empty
    } else {
      projectLinks.tail.foldLeft(Seq(Seq(projectLinks.head))) {
        case (acc, pl) =>
          val lastSection = acc.last
          val lastLink = lastSection.last
          if (lastLink.addrMRange.end == pl.addrMRange.start && lastLink.administrativeClass == pl.administrativeClass && lastLink.ely == pl.ely) {
            acc.init :+ (lastSection :+ pl) // Add to the current section
          } else {
            acc :+ Seq(pl) // Start a new section
          }
      }
    }
  }

  def setRoadwaysForOtherThanNewLinks(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    val groupedByRoadwayNumber = projectLinks.groupBy(_.roadwayNumber)
    val projectLinksWithRoadwayNumbersAssigned = {
      groupedByRoadwayNumber.flatMap(grp => {
        val projectLinksOnSameRoadwayNumber = grp._2
        val continuousSections = getContinuousRoadwaySections(projectLinksOnSameRoadwayNumber)
        if(continuousSections.size == 1)
          projectLinksOnSameRoadwayNumber
        else {
          continuousSections.flatMap(continuousSection => {
            val newRoadwayNumber = Sequences.nextRoadwayNumber
            continuousSection.map(pl => pl.copy(roadwayNumber = newRoadwayNumber))
          })
        }
      })
    }
    projectLinksWithRoadwayNumbersAssigned.toSeq
  }
}
