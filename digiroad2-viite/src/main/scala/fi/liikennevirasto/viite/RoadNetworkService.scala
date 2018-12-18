package fi.liikennevirasto.viite

import java.sql.{SQLException, SQLIntegrityConstraintViolationException}

import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.AddressConsistencyValidator.AddressError.{InconsistentTopology, OverlappingRoadAddresses}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.Combined
import fi.liikennevirasto.viite.AddressConsistencyValidator.AddressError
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession


class RoadNetworkService {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  val logger = LoggerFactory.getLogger(getClass)
  val roadNetworkDAO = new RoadNetworkDAO
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)

  def checkRoadAddressNetwork(options: RoadCheckOptions): Unit = {

    def checkRoadways(l: Seq[Roadway], r: Seq[Roadway]): Seq[RoadNetworkError] = {

      val sectionLeft = l.zip(l.tail).foldLeft(Seq.empty[RoadNetworkError])((errors, rws) =>
        checkAddressMValues(rws._1, rws._2, errors)
      )

      val sectionRight = r.zip(r.tail).foldLeft(Seq.empty[RoadNetworkError])((errors, rws) =>
        checkAddressMValues(rws._1, rws._2, errors)
      )

      sectionLeft ++ sectionRight
    }

    def checkTwoTrackLinearLocations(mapped: Map[Long, Option[Seq[LinearLocation]]]): Seq[RoadNetworkError] = {
      val allLocations = mapped.values.flatten.flatten.toSeq
      if (allLocations.isEmpty)
        Seq.empty[RoadNetworkError]
      else {
        val errors: Seq[RoadNetworkError] = mapped.flatMap { case (roadwayId, locations) =>
          val locationsError: Seq[LinearLocation] = locations.get.filter(loc =>
            (!allLocations.exists(l => (l.calibrationPoints._1 == loc.calibrationPoints._1) && l.id != loc.id)
              || !allLocations.exists(l => (l.calibrationPoints._2 == loc.calibrationPoints._2) && l.id != loc.id))
          )
          locationsError.map { loc =>
            RoadNetworkError(options.nextNetworkVersion, roadwayId, loc.id, AddressError.InconsistentTopology, System.currentTimeMillis(), options.currNetworkVersion)
          }
        }.toSeq
        errors
      }
    }

    def checkCombinedLinearLocations(mapped: Map[Long, Option[Seq[LinearLocation]]]): Seq[RoadNetworkError] = {
      val allLocations = mapped.values.flatten.flatten.toSeq
      val errors: Seq[RoadNetworkError] = if (allLocations.isEmpty) {
        Seq.empty[RoadNetworkError]
      } else if (allLocations.size == 1) {
        mapped.flatMap { case (roadwayId, locations) =>
          val locationsError: Seq[LinearLocation] = locations.get.filter(loc =>
            allLocations.head.calibrationPoints._1.isEmpty || allLocations.head.calibrationPoints._2.isEmpty
          )
          locationsError.map { loc =>
            RoadNetworkError(options.nextNetworkVersion, roadwayId, loc.id, AddressError.InconsistentTopology, System.currentTimeMillis(), options.currNetworkVersion)
          }
        }.toSeq
      } else {

        mapped.flatMap { case (roadwayId, locations) =>
          val sortedLocations = locations.get.sortBy(_.orderNumber)
          val (first, last) = (sortedLocations.head, sortedLocations.last)

          val edgeCalibrationPointsError: Seq[LinearLocation] = Seq(first, last).filter(edge =>
            edge.id == first.id && edge.calibrationPoints._1.isEmpty || edge.id == last.id && edge.calibrationPoints._2.isEmpty
          )
          val middleCalibrationPointsError: Seq[LinearLocation] = sortedLocations.filter(loc =>
            !sortedLocations.exists(l => (loc.calibrationPoints._2 == l.calibrationPoints._1) && l.id != loc.id) && loc.id != last.id
          )

          (middleCalibrationPointsError ++ edgeCalibrationPointsError).map { loc =>
            RoadNetworkError(options.nextNetworkVersion, roadwayId, loc.id, AddressError.InconsistentTopology, System.currentTimeMillis(), options.currNetworkVersion)
          }
        }.toSeq
      }
      errors

    }

    def checkAddressMValues(rw1: Roadway, rw2: Roadway, errors: Seq[RoadNetworkError]): Seq[RoadNetworkError] = {
      rw1.endAddrMValue != rw2.startAddrMValue match {
        case true => {
          errors :+ RoadNetworkError(options.nextNetworkVersion, rw1.id, 0L, AddressError.InconsistentTopology, System.currentTimeMillis(), options.currNetworkVersion)
        }
        case _ => Seq()
      }
    }

    try {
      val roadsInChunk = roadwayDAO.fetchAllByRoadNumbers(options.roadNumbers.toSet)
      val linearLocationsInChunk = linearLocationDAO.fetchByRoadways(roadsInChunk.map(_.roadwayNumber).toSet).groupBy(_.roadwayNumber)
      val roadways = roadsInChunk.groupBy(g => (g.roadNumber, g.roadPartNumber))
      roadways.par.foreach { group =>
        val (section, roadway) = group

        val (combinedLeft, combinedRight) = (roadway.filter(t => t.track != Track.RightSide).sortBy(_.startAddrMValue), roadway.filter(t => t.track != Track.LeftSide).sortBy(_.startAddrMValue))
        val roadwaysErrors = checkRoadways(combinedLeft, combinedRight)
        logger.info(s" Found ${roadwaysErrors.size} roadway errors for RoadNumber ${section._1} and Part ${section._2}")

        val (combinedRoadways, twoTrackRoadways) = roadway.partition(_.track == Combined)

        val mappedCombined = combinedRoadways.map(r => r.id -> linearLocationsInChunk.get(r.roadwayNumber)).toMap
        val mappedTwoTrack = twoTrackRoadways.map(r => r.id -> linearLocationsInChunk.get(r.roadwayNumber)).toMap

        val twoTrackErrors = checkTwoTrackLinearLocations(mappedTwoTrack)
        val combinedErrors = checkCombinedLinearLocations(mappedCombined)
        val linearLocationErrors = twoTrackErrors ++ combinedErrors

        logger.info(s" Found ${linearLocationErrors.size} linear locations errors for RoadNumber ${section._1} and Part ${section._2} (twoTrack: ${twoTrackErrors.size}) , (combined: ${combinedErrors.size})")

        val roadErrors = roadwaysErrors ++ linearLocationErrors

        if (roadErrors.nonEmpty) {
          val existingErrors = roadNetworkDAO.getRoadNetworkErrors(AddressError.InconsistentTopology)
          val newErrors = roadErrors.filterNot(r => existingErrors.exists(e => e.roadwayId == r.roadwayId && e.linearLocationId == r.linearLocationId && e.error == r.error && e.network_version == r.network_version))
          newErrors.foreach { e =>
            logger.info(s" Found error for roadway id ${e.roadwayId}, linear location id ${e.linearLocationId}")
            roadNetworkDAO.addRoadNetworkError(e.roadwayId, e.linearLocationId, InconsistentTopology)
          }
        }
      }
    } catch {
      case e: SQLIntegrityConstraintViolationException => logger.error("A road network check is already running")
      case e: SQLException => {
        logger.info("SQL Exception")
        logger.error(e.getMessage)
        dynamicSession.rollback()
      }
      case e: Exception => {
        logger.error(e.getMessage)
        dynamicSession.rollback()
      }
    }
  }

  def getLatestPublishedNetworkDate: Option[DateTime] = {
    withDynSession {
      roadNetworkDAO.getLatestPublishedNetworkDate
    }
  }
}

case class RoadCheckOptions(roadways: Seq[Long], roadNumbers: Seq[Long], currNetworkVersion: Option[Long], nextNetworkVersion: Long)
