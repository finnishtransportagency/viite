package fi.liikennevirasto.viite

import java.sql.SQLIntegrityConstraintViolationException

import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.AddressConsistencyValidator.AddressError.{InconsistentTopology, OverlappingRoadAddresses}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.digiroad2.util.Track
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

    def checkCalibrationPoints(road1: RoadAddress, road2: RoadAddress): Boolean = {
      (road1.track != road2.track && road1.calibrationPoints._1.isEmpty && road1.calibrationPoints._2.isEmpty &&
        road2.calibrationPoints._1.isEmpty && road2.calibrationPoints._2.isEmpty) ||
        (road1.startAddrMValue == 0 && road1.calibrationPoints._1.isEmpty && road1.calibrationPoints._2.isEmpty ||
          road2.startAddrMValue == 0 && road2.calibrationPoints._1.isEmpty && road2.calibrationPoints._2.isEmpty )
    }

    def checkAddrMValues(road1: RoadAddress, road2: RoadAddress): Boolean = {
      !GeometryUtils.areAdjacent(road1.geometry, road2.geometry, MaxDistanceForConnectedLinks) &&
        (road1.discontinuity != Discontinuity.MinorDiscontinuity && road1.discontinuity != Discontinuity.Discontinuous ||
          road2.discontinuity != Discontinuity.MinorDiscontinuity && road2.discontinuity != Discontinuity.Discontinuous)
    }

    def checkOverlapping(road1: RoadAddress, road2: RoadAddress)(sortedRows: Seq[RoadAddress]) = {
      road1.endAddrMValue != road2.startAddrMValue && road1.track.value == road2.track.value &&
        !sortedRows.exists(s => s.track != road1.track && s.startAddrMValue == road1.endAddrMValue) match {
        case true => {
          roadNetworkDAO.addRoadNetworkError(road1.id, road1.linearLocationId, OverlappingRoadAddresses)
        }
        case _ => None
      }
    }

    def checkTopology(road1: RoadAddress, road2: RoadAddress, errors: Seq[Long]): Seq[Long] = {
        checkAddrMValues(road1, road2) ||
        checkCalibrationPoints(road1, road2)
      match {
        case true => {
          roadNetworkDAO.addRoadNetworkError(road1.id, road1.linearLocationId, InconsistentTopology)
          errors :+ road1.roadwayNumber
        }
        case _ => None
          errors
      }
    }

    def checkTrack(ra: Seq[RoadAddress]) =
      if(ra.size > 1){
        ra.zip(ra.tail).foldLeft(Seq.empty[Long])((errors, ra) =>
          checkTopology(ra._1, ra._2, errors)
        )
      } else Seq()


//    def checkAddressMValues(road1: RoadAddress, road2: RoadAddress, errors: Seq[Long]): Seq[Long] = {
//
//    }

    withDynTransaction {
      try {
        val allRoads = roadwayDAO.fetchAllByRoadNumbers(options.roadNumbers)
        val roadAddresses = roadwayAddressMapper.getRoadAddressesByRoadway(allRoads).groupBy(g => (g.roadNumber, g.roadPartNumber, g.endDate))
        roadAddresses.par.foreach{ road =>

          val (rightCombined, leftCombined) = (road._2.filter(t => t.track != Track.LeftSide).sortBy(_.startAddrMValue), road._2.filter(t => t.track != Track.RightSide).sortBy(_.startAddrMValue))
          /*Checking addrMValues continuously about checkTopology*/

          val roadways: Seq[Long] = checkTrack(rightCombined) ++ checkTrack(leftCombined)


//              .foldLeft(Seq.empty[ValidationErrorDetails])((errorDetails, road) =>
//                errorDetails :+ ValidationErrorDetails(project.id, ValidationErrorList.HasNotHandledLinks,
//                  Seq(road._2.size), road._2.map { l =>
//                    val point = GeometryUtils.midPointGeometry(l.geometry)
//                    ProjectCoordinates(point.x, point.y, 12)
//                  },
//                  Some(HasNotHandledLinksMessage.format(road._2.size, road._1._1, road._1._2)))
//              )

//            checkTopology(rightCombined)

          /*Checking Calibrations points, whenever there is linear location with ending Calibration point, then next one should start with same Calibration Point*/
          //make code here about checkCalibrationPoints()

          val (right, left) = (rightCombined.filter(_.track == Track.RightSide), leftCombined.filter(_.track == Track.LeftSide))
          /*Checking matching 2-tracks addrMValues Hint: its not enough to only check if beginning and end addrMValues are the same in both tracks.
            We could have multiple 2-tracks sections within same part
          */


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
       }
      }
    }

  def getLatestPublishedNetworkDate : Option[DateTime] = {
    withDynSession {
      roadNetworkDAO.getLatestPublishedNetworkDate
    }
  }
}
case class RoadCheckOptions(roadways: Seq[Long], roadNumbers: Seq[Long])
