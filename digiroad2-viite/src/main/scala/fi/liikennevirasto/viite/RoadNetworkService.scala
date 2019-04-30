package fi.liikennevirasto.viite

import java.sql.{SQLException, SQLIntegrityConstraintViolationException}

import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.{Combined, LeftSide}
import fi.liikennevirasto.viite.AddressConsistencyValidator.AddressError
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import com.github.nscala_time.time.OrderingImplicits.DateTimeOrdering
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

      val sectionLeft = if (l.isEmpty || l.size == 1) {
        Seq.empty[RoadNetworkError]
      } else {
        l.zip(l.tail).foldLeft(Seq.empty[RoadNetworkError])((errors, lws) =>
          checkAddressMValues(lws._1, lws._2, errors)
        )
      }

      val sectionRight = if (r.isEmpty || r.size == 1) {
        Seq.empty[RoadNetworkError]
      } else {
        r.zip(r.tail).foldLeft(Seq.empty[RoadNetworkError])((errors, rws) =>
          checkAddressMValues(rws._1, rws._2, errors)
        )
      }

      sectionLeft ++ sectionRight
    }

    def checkTwoTrackLinearLocations(allLocations: Seq[LinearLocation], roadways: Seq[Roadway]): Seq[RoadNetworkError] = {
      val errors: Seq[RoadNetworkError] =
        if (allLocations.isEmpty)
          Seq.empty[RoadNetworkError]
        else {
          val sortedLocations = allLocations.sortBy(_.orderNumber)

          val optHeadRoadway = roadways.find(_.roadwayNumber == sortedLocations.head.roadwayNumber)
          val startLocationErrors = if (sortedLocations.head.orderNumber != 1) {
            Seq(RoadNetworkError(0, if (optHeadRoadway.nonEmpty) optHeadRoadway.get.id else 0L, sortedLocations.head.id, AddressError.MissingStartingLink, System.currentTimeMillis(), options.currNetworkVersion))
          } else {
            if (sortedLocations.head.calibrationPoints._1.isEmpty) {
              Seq(RoadNetworkError(0, if (optHeadRoadway.nonEmpty) optHeadRoadway.get.id else 0L, sortedLocations.head.id, AddressError.MissingEdgeCalibrationPoints, System.currentTimeMillis(), options.currNetworkVersion))
            } else {
              Seq.empty[RoadNetworkError]
            }
          }

          val endLocationErrors = if (sortedLocations.last.calibrationPoints._2.isEmpty) {
            val optlastRoadway = roadways.find(_.roadwayNumber == sortedLocations.last.roadwayNumber)
            Seq(RoadNetworkError(0, if (optlastRoadway.nonEmpty) optlastRoadway.get.id else 0L, sortedLocations.last.id, AddressError.MissingEdgeCalibrationPoints, System.currentTimeMillis(), options.currNetworkVersion))
          } else {
            Seq.empty[RoadNetworkError]
          }

          val first = sortedLocations.head
          val last = sortedLocations.last

          val middleCalibrationPointsErrors: Seq[RoadNetworkError] =
            allLocations.filter(loc =>
              !allLocations.exists(l => (loc.calibrationPoints._2 == l.calibrationPoints._1) && l.id != loc.id) && loc.id != last.id
                ||
                !allLocations.exists(l => (loc.calibrationPoints._1 == l.calibrationPoints._2) && l.id != loc.id) && loc.id != first.id
            ).map { loc =>
              val optMidRoadway = roadways.find(_.roadwayNumber == loc.roadwayNumber)
              RoadNetworkError(0, if (optMidRoadway.nonEmpty) optMidRoadway.get.id else 0L, loc.id, AddressError.InconsistentContinuityCalibrationPoints, System.currentTimeMillis(), options.currNetworkVersion)
            }

          startLocationErrors ++ middleCalibrationPointsErrors ++ endLocationErrors
        }
      errors
    }

    def checkCombinedLinearLocations(allLocations: Seq[LinearLocation], roadways: Seq[Roadway]): Seq[RoadNetworkError] = {
      val errors: Seq[RoadNetworkError] =
        if (allLocations.isEmpty)
          Seq.empty[RoadNetworkError]
        else {
          val sortedLocations = allLocations.sortBy(_.orderNumber)

          val optHeadRoadway = roadways.find(_.roadwayNumber == sortedLocations.head.roadwayNumber)

          val startLocationErrors = if (sortedLocations.head.orderNumber != 1) {
            Seq(RoadNetworkError(0, if (optHeadRoadway.nonEmpty) optHeadRoadway.get.id else 0L, sortedLocations.head.id, AddressError.MissingStartingLink, System.currentTimeMillis(), options.currNetworkVersion))
          } else {
            if (sortedLocations.head.calibrationPoints._1.isEmpty) {
              Seq(RoadNetworkError(0, if (optHeadRoadway.nonEmpty) optHeadRoadway.get.id else 0L, sortedLocations.head.id, AddressError.MissingEdgeCalibrationPoints, System.currentTimeMillis(), options.currNetworkVersion))
            } else {
              Seq.empty[RoadNetworkError]
            }
          }

          val endLocationErrors = if (sortedLocations.last.calibrationPoints._2.isEmpty) {
            val optlastRoadway = roadways.find(_.roadwayNumber == sortedLocations.last.roadwayNumber)
            Seq(RoadNetworkError(0, if (optlastRoadway.nonEmpty) optlastRoadway.get.id else 0L, sortedLocations.last.id, AddressError.MissingEdgeCalibrationPoints, System.currentTimeMillis(), options.currNetworkVersion))
          } else {
            Seq.empty[RoadNetworkError]
          }

          val first = sortedLocations.head
          val last = sortedLocations.last

          val middleCalibrationPointsErrors: Seq[RoadNetworkError] =
            allLocations.filter(loc =>
              !allLocations.exists(l => (loc.calibrationPoints._2 == l.calibrationPoints._1) && l.id != loc.id) && loc.id != last.id
                ||
                !allLocations.exists(l => (loc.calibrationPoints._1 == l.calibrationPoints._2) && l.id != loc.id) && loc.id != first.id
            ).map { loc =>
              val optMidRoadway = roadways.find(_.roadwayNumber == loc.roadwayNumber)
              RoadNetworkError(0, if (optMidRoadway.nonEmpty) optMidRoadway.get.id else 0L, loc.id, AddressError.InconsistentContinuityCalibrationPoints, System.currentTimeMillis(), options.currNetworkVersion)
            }

          startLocationErrors ++ middleCalibrationPointsErrors ++ endLocationErrors
        }
      errors
    }

    def checkAddressMValues(rw1: Roadway, rw2: Roadway, errors: Seq[RoadNetworkError]): Seq[RoadNetworkError] = {
      if (rw1.endAddrMValue != rw2.startAddrMValue) {
        errors :+ RoadNetworkError(0, rw1.id, 0L, AddressError.InconsistentAddressValues, System.currentTimeMillis(), options.currNetworkVersion)
      } else {
        errors
      }
    }

    withDynTransaction {
      try {
        val roadsInChunk = roadwayDAO.fetchAllByRoadNumbers(options.roadNumbers)
        val distinctDateRoads = roadsInChunk.groupBy(r => (r.roadNumber, r.roadPartNumber)).flatMap { p =>
          p._2.groupBy(o => (o.track, o.startAddrMValue)).map { t =>
            t._2.minBy(_.startDate)
          }.toSeq
        }.toSeq
        val linearLocationsInChunk = linearLocationDAO.fetchByRoadways(distinctDateRoads.map(_.roadwayNumber).distinct.toSet).groupBy(_.roadwayNumber)
        val roadways = distinctDateRoads.groupBy(g => (g.roadNumber, g.roadPartNumber))
        val errors = roadways.flatMap { group =>
          val (section, roadway) = group

          val (combinedLeft, combinedRight) = (roadway.filter(t => t.track != Track.RightSide).sortBy(_.startAddrMValue), roadway.filter(t => t.track != Track.LeftSide).sortBy(_.startAddrMValue))
          val roadwaysErrors = checkRoadways(combinedLeft, combinedRight)
          logger.info(s" Found ${roadwaysErrors.size} roadway errors for RoadNumber ${section._1} and Part ${section._2}")

          val (combinedRoadways, twoTrackRoadways) = roadway.partition(_.track == Combined)
          val (leftRoadways, rightRoadways) = twoTrackRoadways.partition(_.track == LeftSide)

          val leftTrackLinearLocations = leftRoadways.flatMap(r => linearLocationsInChunk.get(r.roadwayNumber)).flatten
          val rightTrackLinearLocations = rightRoadways.flatMap(r => linearLocationsInChunk.get(r.roadwayNumber)).flatten
          val combinedLinearLocations = combinedRoadways.flatMap(r => linearLocationsInChunk.get(r.roadwayNumber)).flatten

          val leftTrackErrors = checkTwoTrackLinearLocations(leftTrackLinearLocations, leftRoadways)
          val rightTrackErrors = checkTwoTrackLinearLocations(rightTrackLinearLocations, rightRoadways)
          val combinedLeftErrors = checkCombinedLinearLocations(combinedLinearLocations ++ leftTrackLinearLocations, combinedRoadways ++ leftRoadways)
          val combinedRightErrors = checkCombinedLinearLocations(combinedLinearLocations ++ rightTrackLinearLocations, combinedRoadways ++ rightRoadways)
          val linearLocationErrors = leftTrackErrors ++ rightTrackErrors ++ combinedLeftErrors ++ combinedRightErrors

          logger.info(s" Found ${linearLocationErrors.size} linear locations errors for RoadNumber ${section._1} and Part ${section._2} (twoTrack: ${leftTrackErrors.size + rightTrackErrors.size}) , (combined: ${combinedLeftErrors.size + combinedRightErrors.size})")

          roadwaysErrors ++ linearLocationErrors
        }
        if (errors.nonEmpty) {
          val uniqueErrors = errors.groupBy(g => (g.roadwayId, g.linearLocationId, g.error, g.network_version)).map(_._2.head).toSeq
          val existingErrors = roadNetworkDAO.getRoadNetworkErrors

          val newErrors = uniqueErrors.filterNot(r => existingErrors.exists(e => e.roadwayId == r.roadwayId && e.linearLocationId == r.linearLocationId && e.error == r.error && e.network_version == r.network_version))
          newErrors.sortBy(_.roadwayId).foreach { e =>
            logger.info(s" Found error for roadway id ${e.roadwayId}, linear location id ${e.linearLocationId}")
            roadNetworkDAO.addRoadNetworkError(e.roadwayId, e.linearLocationId, e.error, e.network_version)
          }
        }

        /*
        * Used for actor cases only.
        * Batch should only deal with expiring and publishing of road network after run all chunks of the entire road network
        */
        if (options.throughActor) {
          if (options.currNetworkVersion.nonEmpty && !roadNetworkDAO.hasCurrentNetworkErrors) {
            logger.info(s"No errors found. Creating new publishable version for the road network")
            roadNetworkDAO.expireRoadNetwork
            roadNetworkDAO.createPublishedRoadNetwork(options.nextNetworkVersion)
            val newId = roadNetworkDAO.getLatestRoadNetworkVersionId
            roadwayDAO.fetchAllCurrentAndValidRoadwayIds.foreach(id => roadNetworkDAO.createPublishedRoadway(newId.get, id))
          } else {
            logger.info(s"Network errors found or current network version not found. Check road_network_error and published_road_network tables")
          }
        }

      } catch {
        case e: SQLIntegrityConstraintViolationException => logger.error("A road network check is already running")
        case e: SQLException =>
          logger.info("SQL Exception")
          println(s"\n" + e.getMessage + s"\n"+ e.printStackTrace)
          dynamicSession.rollback()
        case e: Exception =>
          println(s"\n" + e.getMessage + s"\n"+ e.printStackTrace)
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

case class RoadCheckOptions(roadways: Seq[Long], roadNumbers: Set[Long], currNetworkVersion: Option[Long], nextNetworkVersion: Long, throughActor: Boolean)
