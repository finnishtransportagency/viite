package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.util.{MissingRoadwayNumberException, MissingTrackException}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.process.strategy.RoadAddressSectionCalculatorContext
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{AdministrativeClass, Discontinuity, RoadAddressChangeType, RoadPart, SideCode, Track}
import org.slf4j.LoggerFactory

object ProjectSectionCalculator {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
    * NOTE! Should be called from project service only at recalculate method - other places are usually wrong places
    * and may miss user given calibration points etc.
    * Recalculates the AddressMValues for project links. RoadAddressChangeType.New will get reassigned values and all
    * others will have the transfer/unchanged rules applied for them.
    * Terminated links will not be recalculated
    *
    * @param projectLinks List of addressed links in project
    * @return Sequence of project links with address values and calibration points.
    */
  def assignAddrMValues(projectLinks: Seq[ProjectLink], userGivenCalibrationPoints: Seq[UserDefinedCalibrationPoint] = Seq()): Seq[ProjectLink] = {
    logger.info(s"Starting MValue assignment for ${projectLinks.size} links")
    val others = projectLinks.filterNot(_.status == RoadAddressChangeType.Termination)
    val (newLinks, nonTerminatedLinks) = others.partition(l => l.status == RoadAddressChangeType.New)
    try {

      val calculator = RoadAddressSectionCalculatorContext.getStrategy(others)
      logger.info(s"${calculator.name} strategy")
      calculator.assignAddrMValues(newLinks, nonTerminatedLinks, userGivenCalibrationPoints)

    } finally {
      logger.info(s"Finished MValue assignment for ${projectLinks.size} links")
    }
  }
}

case class RoadwaySection(roadNumber: Long, roadPartNumberStart: Long, roadPartNumberEnd: Long, track: Track, startMAddr: Long, endMAddr: Long, discontinuity: Discontinuity, administrativeClass: AdministrativeClass, ely: Long, reversed: Boolean, roadwayNumber: Long, projectLinks: Seq[ProjectLink]) {
}

case class TrackSection(roadPart: RoadPart, track: Track,
                        geometryLength: Double, links: Seq[ProjectLink]) {

  lazy val startGeometry: Point = links.head.sideCode match {
    case  SideCode.AgainstDigitizing => links.head.geometry.last
    case _ => links.head.geometry.head
  }
  lazy val endGeometry: Point = links.last.sideCode match {
    case  SideCode.AgainstDigitizing => links.last.geometry.head
    case _ => links.last.geometry.last
  }
  lazy val startAddrM: Long = links.map(_.startAddrMValue).min
  lazy val endAddrM: Long = links.map(_.endAddrMValue).max

}

case class CombinedSection(startGeometry: Point, endGeometry: Point, geometryLength: Double, left: TrackSection, right: TrackSection) {
  lazy val sideCode: SideCode = {
    if (GeometryUtils.areAdjacent(startGeometry, right.links.head.geometry.head))
      right.links.head.sideCode
    else
      SideCode.apply(5 - right.links.head.sideCode.value)
  }

  lazy val roadAddressChangeType: RoadAddressChangeType = right.links.head.status


  lazy val endAddrM: Long = right.links.map(_.endAddrMValue).max

}

