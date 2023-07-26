package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.util.{MissingRoadwayNumberException, MissingTrackException}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.ProjectCalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.process.strategy.RoadAddressSectionCalculatorContext
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{AdministrativeClass, Discontinuity, RoadAddressChangeType, SideCode, Track}
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
  def assignMValues(projectLinks: Seq[ProjectLink], userGivenCalibrationPoints: Seq[UserDefinedCalibrationPoint] = Seq()): Seq[ProjectLink] = {
    logger.info(s"Starting MValue assignment for ${projectLinks.size} links")
    val others = projectLinks.filterNot(_.status == RoadAddressChangeType.Termination)
    val (newLinks, nonTerminatedLinks) = others.partition(l => l.status == RoadAddressChangeType.New)
    try {

      val calculator = RoadAddressSectionCalculatorContext.getStrategy(others)
      logger.info(s"${calculator.name} strategy")
      calculator.assignMValues(newLinks, nonTerminatedLinks, userGivenCalibrationPoints)

    } finally {
      logger.info(s"Finished MValue assignment for ${projectLinks.size} links")
    }
  }

  def assignTerminatedMValues(terminated: Seq[ProjectLink], nonTerminatedLinks: Seq[ProjectLink]) : Seq[ProjectLink] = {
    logger.info(s"Starting MValue assignment for ${terminated.size} terminated links")
    try {

      val allProjectLinks = nonTerminatedLinks.filter(_.status != RoadAddressChangeType.New) ++ terminated
      val group = allProjectLinks.groupBy {
        pl => (pl.roadAddressRoadNumber.getOrElse(pl.roadNumber), pl.roadAddressRoadPart.getOrElse(pl.roadPartNumber))
      }

      group.flatMap { case (part, projectLinks) =>
        try {
          calculateSectionAddressValues(part, projectLinks)
        } catch {
          case ex @ (_: MissingTrackException | _: MissingRoadwayNumberException) =>
            logger.warn(ex.getMessage)
            terminated
          case ex: InvalidAddressDataException =>
            logger.info(s"Can't calculate terminated road/road part ${part._1}/${part._2}: " + ex.getMessage)
            terminated
          case ex: NoSuchElementException =>
            logger.info("Delta terminated calculation failed: " + ex.getMessage, ex)
            terminated
          case ex: NullPointerException =>
            logger.info("Delta terminated calculation failed (NPE)", ex)
            terminated
          case ex: Throwable =>
            logger.info("Delta terminated calculation not possible: " + ex.getMessage)
            terminated
        }
      }.toSeq

    } finally {
      logger.info(s"Finished MValue assignment for ${terminated.size} terminated links")
    }
  }

  private def calculateSectionAddressValues(part: (Long, Long), projectLinks: Seq[ProjectLink]) : Seq[ProjectLink] = {

    val left = projectLinks.filter(pl => pl.roadAddressTrack.getOrElse(pl.track) != Track.RightSide).sortBy(_.roadAddressStartAddrM)
    val right = projectLinks.filter(pl => pl.roadAddressTrack.getOrElse(pl.track) != Track.LeftSide).sortBy(_.roadAddressStartAddrM)
    if (left.isEmpty || right.isEmpty) {
      Seq[ProjectLink]()
    } else {
      val leftTerminated = left.filter(_.status == RoadAddressChangeType.Termination)
      val rightTerminated = right.filter(_.status == RoadAddressChangeType.Termination)
      val nonTerminatedSortedByStartAddr: Seq[ProjectLink] = projectLinks.filterNot(_.status == RoadAddressChangeType.Termination).sortBy(_.startAddrMValue)

      /* Set terminated link heads to new calculated values. */
      val leftReassignedStart: Seq[ProjectLink] = leftTerminated.map(leftTerminatedpl => {
        val startingPointLink = nonTerminatedSortedByStartAddr.find(pl => {
          pl.id != leftTerminatedpl.id && pl.startingPoint.connected(leftTerminatedpl.startingPoint)
        })
        if (startingPointLink.isDefined) leftTerminatedpl.copy(startAddrMValue = startingPointLink.get.startAddrMValue)
        else {
          val endingPointLink = nonTerminatedSortedByStartAddr.find(pl => {
            pl.id != leftTerminatedpl.id && pl.endPoint.connected(leftTerminatedpl.startingPoint)
          })
          if (endingPointLink.isDefined) leftTerminatedpl.copy(startAddrMValue = endingPointLink.get.endAddrMValue)
          else leftTerminatedpl
        }
      })

      val leftReassignedEnd: Seq[ProjectLink] = leftReassignedStart.map(leftTerminatedpl => {
        val startingPointLink = nonTerminatedSortedByStartAddr.find(pl =>
          pl.id != leftTerminatedpl.id && pl.startingPoint.connected(leftTerminatedpl.endPoint)
        )
        if (startingPointLink.isDefined) leftTerminatedpl.copy(endAddrMValue = startingPointLink.get.startAddrMValue)
        else {
          val endPointLink = nonTerminatedSortedByStartAddr.find(pl =>
            pl.id != leftTerminatedpl.id && pl.endPoint.connected(leftTerminatedpl.endPoint)
          )
          if (endPointLink.isDefined) leftTerminatedpl.copy(endAddrMValue = endPointLink.get.endAddrMValue)
          else leftTerminatedpl
        }
      })

      val rightReassignedStart: Seq[ProjectLink] = rightTerminated.map(rightTerminatedpl => {
        val startingPointLink = nonTerminatedSortedByStartAddr.find(pl =>
          pl.id != rightTerminatedpl.id && pl.startingPoint.connected(rightTerminatedpl.startingPoint)
        )
        if (startingPointLink.isDefined) rightTerminatedpl.copy(startAddrMValue = startingPointLink.get.startAddrMValue)
        else {
          val endingPointLink = nonTerminatedSortedByStartAddr.find(pl =>
            pl.id != rightTerminatedpl.id && pl.endPoint.connected(rightTerminatedpl.startingPoint)
          )
          if (endingPointLink.isDefined) rightTerminatedpl.copy(startAddrMValue = endingPointLink.get.endAddrMValue)
          else rightTerminatedpl
        }
      })

      val rightReassignedEnd: Seq[ProjectLink] = rightReassignedStart.map(rightTerminatedpl => {
        val startingPointLink = nonTerminatedSortedByStartAddr.find(pl => {
          pl.id != rightTerminatedpl.id && pl.startingPoint.connected(rightTerminatedpl.endPoint)
        })
        if (startingPointLink.isDefined) rightTerminatedpl.copy(endAddrMValue = startingPointLink.get.startAddrMValue)
        else {
          val endPointLink = nonTerminatedSortedByStartAddr.find(pl => {
            pl.id != rightTerminatedpl.id && pl.endPoint.connected(rightTerminatedpl.endPoint)
          })
          if (endPointLink.isDefined) rightTerminatedpl.copy(endAddrMValue = endPointLink.get.endAddrMValue)
          else rightTerminatedpl
        }
      })

      def retval: Seq[ProjectLink] = {
        if (rightReassignedEnd == leftReassignedEnd)
          rightReassignedEnd
        else
          rightReassignedEnd ++ leftReassignedEnd
      }
      def checkAverage: Seq[ProjectLink] =  {
        val maxrpl      = rightReassignedEnd.maxBy(_.endAddrMValue)
        val maxlpl      = leftReassignedEnd.maxBy(_.endAddrMValue)
        val endAddrDiff = Math.abs(maxrpl.endAddrMValue - maxlpl.endAddrMValue)
        val minLength   = Math.min(maxrpl.geometryLength, maxrpl.geometryLength)
        // Ensure difference is not too great, i.e. links are about aligned.
        if (endAddrDiff < minLength) {
          val avg = Math.round((maxrpl.endAddrMValue + maxlpl.endAddrMValue) / 2)
          (rightReassignedEnd.filterNot(_.id == maxrpl.id) :+ maxrpl.copy(endAddrMValue = avg)) ++
          (leftReassignedEnd.filterNot(_.track == Track.Combined).filterNot(_.id == maxlpl.id) :+ maxlpl.copy(endAddrMValue = avg))
        } else
          retval
      }

      /* Average end values for change table when terminated links are the last links on the road part. */
      if (rightReassignedEnd.nonEmpty && leftReassignedEnd.nonEmpty) {
        val rightReassignedMax = rightReassignedEnd.maxBy(_.endAddrMValue).endAddrMValue
        val leftReassignedMax  = leftReassignedEnd.maxBy(_.endAddrMValue).endAddrMValue
        val leftmax            = left.maxBy(_.endAddrMValue).endAddrMValue
        val rightmax           = right.maxBy(_.endAddrMValue).endAddrMValue

        if (rightReassignedMax != leftReassignedMax && leftmax <= leftReassignedMax && rightmax <= rightReassignedMax)
          checkAverage
        else
          retval
      }
      else
        retval
      }
  }
}

case class RoadwaySection(roadNumber: Long, roadPartNumberStart: Long, roadPartNumberEnd: Long, track: Track, startMAddr: Long, endMAddr: Long, discontinuity: Discontinuity, administrativeClass: AdministrativeClass, ely: Long, reversed: Boolean, roadwayNumber: Long, projectLinks: Seq[ProjectLink]) {
}

case class TrackSection(roadNumber: Long, roadPartNumber: Long, track: Track,
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

