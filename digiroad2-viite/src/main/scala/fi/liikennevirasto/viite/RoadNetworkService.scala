package fi.liikennevirasto.viite

import java.sql.SQLIntegrityConstraintViolationException

import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.AddressConsistencyValidator.AddressError.{InconsistentTopology, OverlappingRoadAddresses}
import fi.liikennevirasto.viite.dao._
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession



class RoadNetworkService {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  val logger = LoggerFactory.getLogger(getClass)
  val roadNetworkDAO: RoadNetworkDAO = new RoadNetworkDAO

  def checkRoadAddressNetwork(options: RoadCheckOptions): Unit = {

    def checkCalibrationPoints(road1: RoadAddress, road2: RoadAddress): Boolean = {
      (road1.track != road2.track && road1.calibrationPoints._1.isEmpty && road1.calibrationPoints._2.isEmpty &&
        road2.calibrationPoints._1.isEmpty && road2.calibrationPoints._2.isEmpty) ||
        (road1.startAddrMValue == 0 && road1.calibrationPoints._1.isEmpty && road1.calibrationPoints._2.isEmpty ||
          road2.startAddrMValue == 0 && road2.calibrationPoints._1.isEmpty && road2.calibrationPoints._2.isEmpty )
    }

    def checkOverlapping(road1: RoadAddress, road2: RoadAddress)(sortedRows: Seq[RoadAddress]) = {
      road1.endAddrMValue != road2.startAddrMValue && road1.track.value == road2.track.value &&
        !sortedRows.exists(s => s.track != road1.track && s.startAddrMValue == road1.endAddrMValue) match {
        case true => {
          roadNetworkDAO.addRoadNetworkError(road1.id, OverlappingRoadAddresses.value)
        }
        case _ => None
      }
    }

    def checkTopology(road1: RoadAddress, road2: RoadAddress)(sortedRows: Seq[RoadAddress]) = {
      (!GeometryUtils.areAdjacent(road1.geometry, road2.geometry, MaxDistanceForConnectedLinks) &&
        (road1.discontinuity != Discontinuity.MinorDiscontinuity && road1.discontinuity != Discontinuity.Discontinuous ||
          road2.discontinuity != Discontinuity.MinorDiscontinuity && road2.discontinuity != Discontinuity.Discontinuous) &&
        sortedRows.maxBy(_.endAddrMValue).endAddrMValue != road1.endAddrMValue &&
          !sortedRows.exists(s => s.startAddrMValue == road1.endAddrMValue && s.track != road1.track &&
            GeometryUtils.areAdjacent(road1.geometry, s.geometry, MaxDistanceForConnectedLinks))) ||
        checkCalibrationPoints(road1, road2)
      match {
        case true => {
          roadNetworkDAO.addRoadNetworkError(road1.id, InconsistentTopology.value)
        }
        case _ => None
      }
    }
    throw new NotImplementedError("Will be implemented at RoadNetworkService")
//    withDynTransaction {
//      try {
//        ExportLockDAO.insert
//        RoadAddressDAO.lockRoadAddressWriting
//        val allRoads = RoadAddressDAO.fetchAllCurrentRoads(options).groupBy(_.roadNumber).flatMap(road => {
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
//      }
//    }
  }

  def getLatestPublishedNetworkDate : Option[DateTime] = {
    withDynSession {
      roadNetworkDAO.getLatestPublishedNetworkDate
    }
  }
}
case class RoadCheckOptions(roadNumbers: Seq[Long])
