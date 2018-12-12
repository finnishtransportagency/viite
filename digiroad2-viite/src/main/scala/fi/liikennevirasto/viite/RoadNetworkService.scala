package fi.liikennevirasto.viite

import java.sql.SQLIntegrityConstraintViolationException

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
    val currNetworkVersion = roadNetworkDAO.getLatestRoadNetworkVersionId

    def checkRoadways(c: Seq[Roadway], l: Seq[Roadway], r: Seq[Roadway]): Seq[RoadNetworkError] = {
      val sectionLeft = c.zip(l.tail).foldLeft(Seq.empty[RoadNetworkError])((errors, rws) =>
          checkAddressMValues(rws._1, rws._2, errors)
        )

      val sectionRight = c.zip(r.tail).foldLeft(Seq.empty[RoadNetworkError])((errors, rws) =>
        checkAddressMValues(rws._1, rws._2, errors)
      )

      sectionLeft ++ sectionRight
    }

    def checkTwoTrackLinearLocations(mapped: Map[Long, Option[Seq[LinearLocation]]]): Seq[RoadNetworkError] = {
      //TODO add test for this with empty Seq[LinearLocation] for any given roadwayId key
      val allLocations = mapped.values.flatten.flatten.toSeq

      val errors: Seq[RoadNetworkError] = mapped.map{ case(roadwayId, locations) =>
        locations.getOrElse(Seq()).map{ loc =>
          if(!allLocations.exists(l => (l.calibrationPoints._1 == loc.calibrationPoints._1) &&  l.id != loc.id)
            || !allLocations.exists(l => (l.calibrationPoints._2 == loc.calibrationPoints._2) &&  l.id != loc.id))
            RoadNetworkError(Sequences.nextRoadNetworkError, roadwayId, loc.id, AddressError.InconsistentTopology, System.currentTimeMillis(), currNetworkVersion)
        }.asInstanceOf[RoadNetworkError]
      }.toSeq
      errors
    }

    def checkCombinedLinearLocations(mapped: Map[Long, Option[Seq[LinearLocation]]]): Seq[RoadNetworkError] = {
      //TODO add test for this with empty Seq[LinearLocation] for any given roadwayId key
      val allLocations = mapped.values.flatten.flatten.toSeq
      val sortedLocations = allLocations.sortBy(_.orderNumber)
      val (first, last) = (sortedLocations.head, sortedLocations.last)

      val errors: Seq[RoadNetworkError] = mapped.map{ case(roadwayId, locations) =>
        locations.getOrElse(Seq()).map{ loc =>
          if(!allLocations.exists(l => (l.calibrationPoints._2 == loc.calibrationPoints._1) && l.id != loc.id && l.id != last.id))
            RoadNetworkError(Sequences.nextRoadNetworkError, roadwayId, loc.id, AddressError.InconsistentTopology, System.currentTimeMillis(), currNetworkVersion)
        }.asInstanceOf[RoadNetworkError]
      }.toSeq
      errors
    }

    def checkAddressMValues(rw1: Roadway, rw2: Roadway, errors: Seq[RoadNetworkError]): Seq[RoadNetworkError] = {
      rw1.endAddrMValue != rw2.startAddrMValue match{
        case true => {
            errors :+ RoadNetworkError(Sequences.nextRoadNetworkError, rw1.id, 0L, AddressError.InconsistentTopology, System.currentTimeMillis(), currNetworkVersion)
          }
        case _ => Seq()
      }
    }

//    withDynTransaction {
      try {
        val allRoads = roadwayDAO.fetchAllByRoadNumbers(options.roadNumbers)
        val roadways = allRoads.groupBy(g => (g.roadNumber, g.roadPartNumber, g.endDate))
        roadways.par.foreach { group =>
          val (sec, roadway) = group

          val (combined, leftSide, rightSide) = (roadway.filter(t => t.track == Track.Combined).sortBy(_.startAddrMValue), roadway.filter(t => t.track == Track.LeftSide).sortBy(_.startAddrMValue), roadway.filter(t => t.track == Track.RightSide).sortBy(_.startAddrMValue))
          val roadwaysErrors = checkRoadways(combined, leftSide, rightSide)
          logger.info(s" Found ${roadwaysErrors.size} roadway errors for RoadNumber ${sec._1}")

          val (combinedRoadways, twoTrackRoadways) = roadway.partition(_.track == Combined)

          println(s" start of fetch of linear locations")
          val combinedLocations = linearLocationDAO.fetchByRoadways(combinedRoadways.map(_.roadwayNumber).toSet).groupBy(_.roadwayNumber)
          val twoTrackLocations = linearLocationDAO.fetchByRoadways(twoTrackRoadways.map(_.roadwayNumber).toSet).groupBy(_.roadwayNumber)

          val mappedCombined = combinedRoadways.map(r => r.id -> combinedLocations.get(r.roadwayNumber)).toMap
          val mappedTwoTrack = twoTrackRoadways.map(r => r.id -> twoTrackLocations.get(r.roadwayNumber)).toMap

          val twoTrackErrors = checkTwoTrackLinearLocations(mappedTwoTrack)
          val combinedErrors = checkCombinedLinearLocations(mappedCombined)
          val linearLocationErrors = twoTrackErrors ++ combinedErrors
          logger.info(s" Found ${linearLocationErrors.size} linear locations errors for RoadNumber ${sec._1} (twoTrack: ${twoTrackErrors.size}) , (combined: ${combinedErrors.size})")

          (roadwaysErrors ++ linearLocationErrors).foreach { e =>
            logger.info(s" Found error for roadway id ${e.id}, linear location id ${e.linearLocationId}")
            roadNetworkDAO.addRoadNetworkError(e.id, e.linearLocationId, InconsistentTopology)
          }
        }

        //  .groupBy(_.roadNumber).flatMap(road => {
        //          val groupedRoadParts = road._2.groupBy(_.roadPartNumber).toSeq.sortBy(_._1)
        //          val lastRoadAddress = groupedRoadParts.last._2.maxBy(_.startAddrMValue)
        //          groupedRoadParts.map(roadPart => {
        //            if (roadPart._2.last.roadPartNumber == lastRoadAddress.roadPartNumber && lastRoadAddress.discontinuity != Discontinuity.EndOfRoad) {
        //              RoadNetworkDAO.addRoadNetworkError(lastRoadAddress.id, InconsistentTopology.value)
        //            }
        //            val sortedRoads = roadPart._2.sortBy(s => (s.track.value, s.startAddrMValue))
        //            sortedRoads.zip(sortedRoads.tail).foreach(r => {
        //              checkOverlapping(r._1, r._2)(sortedRoads)
        //              checkTopology(r._1, r._2)(sortedRoads)
        //            })
        //            roadPart
        //          })
        //        })
        //        if (!RoadNetworkDAO.hasRoadNetworkErrors) {
        //          RoadNetworkDAO.expireRoadNetwork
        //          RoadNetworkDAO.createPublishedRoadNetwork
        //          allRoads.foreach(r => r._2.foreach(p => RoadNetworkDAO.createPublishedRoadway(RoadNetworkDAO.getLatestRoadNetworkVersion.get, p.id)))
        //        }
        //        ExportLockDAO.delete
        //      } catch {
        //        case e: SQLIntegrityConstraintViolationException => logger.info("A road network check is already running")
        //        case _: Exception => {
        //          logger.error("Error during road address network check")
        //          dynamicSession.rollback()
        //          ExportLockDAO.delete
        //        }
        } catch {
                  case e: SQLIntegrityConstraintViolationException => logger.info("A road network check is already running")
                  case _: Exception => {
                    logger.error("Error during road address network check")
                    dynamicSession.rollback()
                    ExportLockDAO.delete
                  }
        }
//      }
    }

  def getLatestPublishedNetworkDate : Option[DateTime] = {
    withDynSession {
      roadNetworkDAO.getLatestPublishedNetworkDate
    }
  }
}
case class RoadCheckOptions(roadways: Seq[Long], roadNumbers: Seq[Long])
