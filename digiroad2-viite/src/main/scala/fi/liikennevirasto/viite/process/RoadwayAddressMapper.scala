package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.dao._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class RoadwayAddressMapper(roadAddressDAO: RoadAddressDAO) {

  /**
    * Recalculate address value of all the history road address calibration points
    * @param historyRoadwayAddress The history roadway address
    * @param linearLocations The current linear locations
    * @return The linear location with recalculated calibration points
    */
  private def recalculateHistoryCalibrationPoints(historyRoadwayAddress: RoadwayAddress, linearLocations: Seq[LinearLocation]): Seq[LinearLocation] = {
    val currentRoadwayAddress = roadAddressDAO.fetchByRoadwayId(historyRoadwayAddress.roadwayId).
      getOrElse(throw new NoSuchElementException(s"Could not find any current road address for roadway ${historyRoadwayAddress.roadwayId}"))

    //Fix calibration points in history road addresses
    val addressLength = historyRoadwayAddress.endAddrMValue - historyRoadwayAddress.startAddrMValue

    assert(addressLength >= 0)

    linearLocations.map {
      linearLocation =>
        linearLocation.calibrationPoints match {
          case (None, None) =>
            linearLocation
          case _ =>
            val (stCp, enCp) = linearLocation.calibrationPoints
            linearLocation.copy(calibrationPoints = (
              stCp.map(calculateAddressHistoryCalibrationPoint(currentRoadwayAddress, historyRoadwayAddress)),
              enCp.map(calculateAddressHistoryCalibrationPoint(currentRoadwayAddress, historyRoadwayAddress)))
            )
        }
    }
  }

  /**
    * Calculate the history calibration point address using a coeficient between the current and the history roadway address
    * @param currentRoadwayAddress The current (endDate is null) road address
    * @param historyRoadwayAddress The history road address
    * @param currentCalibrationAddress The calibration point address of the current to be calculated in the history
    * @return The address of the history calibration point
    */
  private def calculateAddressHistoryCalibrationPoint(currentRoadwayAddress: RoadwayAddress, historyRoadwayAddress: RoadwayAddress)(currentCalibrationAddress: Long): Long = {
    val currentAddressLength = currentRoadwayAddress.endAddrMValue - currentRoadwayAddress.startAddrMValue
    val historyAddressLength = historyRoadwayAddress.endAddrMValue - historyRoadwayAddress.startAddrMValue
    val calibrationLength = currentCalibrationAddress - currentRoadwayAddress.startAddrMValue

    historyRoadwayAddress.startAddrMValue + (historyAddressLength * calibrationLength / currentAddressLength)
  }

  /**
    * Map roadway address into road addresses using linear locations in between given start and end address values
    * @param roadwayAddress The roadway address
    * @param linearLocations The linear locations in between given start and end address values
    * @param startAddress The boundary start address value
    * @param endAddress The boundary end address value
    * @return Returns the mapped road addresses
    */
  private def boundaryAddressMap(roadwayAddress: RoadwayAddress, linearLocations: Seq[LinearLocation], startAddress: Long, endAddress: Long) : Seq[RoadAddress] = {

    val coef = (endAddress - startAddress) / linearLocations.map(l => l.endMValue - l.startMValue).sum

    val sortedLinearLocations = linearLocations.sortBy(_.orderNumber)

    val addresses = sortedLinearLocations.init.scanLeft(startAddress) {
      case (address, location) =>
        address + Math.round((location.endMValue - location.startMValue) * coef)
    } :+ endAddress

    sortedLinearLocations.zip(addresses.zip(addresses.tail)).map {
      case (linearLocation, (st, en)) =>
        val geometryLength = linearLocation.endMValue - linearLocation.startMValue
        val (stCalibration, enCalibration) = linearLocation.calibrationPoints

        val calibrationPoints = (
          stCalibration.map(address => CalibrationPoint(linearLocation.linkId, if(linearLocation.sideCode == SideCode.TowardsDigitizing) 0 else geometryLength, address)),
          enCalibration.map(address => CalibrationPoint(linearLocation.linkId, if(linearLocation.sideCode == SideCode.AgainstDigitizing) 0 else geometryLength, address))
        )

        val sideCode = if(roadwayAddress.reverted) SideCode.switch(linearLocation.sideCode) else linearLocation.sideCode
        RoadAddress(roadwayAddress.id, linearLocation.id, roadwayAddress.roadNumber, roadwayAddress.roadPartNumber, roadwayAddress.roadType, roadwayAddress.track, Discontinuity.Continuous, st, en,
          Some(roadwayAddress.startDate), roadwayAddress.endDate, Some(roadwayAddress.createdBy), linearLocation.linkId, linearLocation.startMValue, linearLocation.endMValue, sideCode,
          linearLocation.adjustedTimestamp, calibrationPoints, linearLocation.floating, linearLocation.geometry, linearLocation.linkGeomSource, roadwayAddress.ely, roadwayAddress.terminated,
          roadwayAddress.roadwayId, linearLocation.validFrom, linearLocation.validTo, roadwayAddress.roadName)
    }
  }

  /**
    * Recursively map road addresses in between calibration points
    * @param roadwayAddress The current roadway address
    * @param linearLocations The linear location in between the calibration points
    * @return Returns the mapped road addresses in between the calibration points
    */
  private def recursiveMapRoadAddresses(roadwayAddress: RoadwayAddress, linearLocations: Seq[LinearLocation]) : Seq[RoadAddress] = {

    def getUntilCalibrationPoint(seq: Seq[LinearLocation]): (Seq[LinearLocation], Seq[LinearLocation]) = {
      val linearLocationsUntilCp = seq.takeWhile(l => l.calibrationPoints._2.isEmpty)
      val rest = seq.drop(linearLocationsUntilCp.size)
      if(rest.headOption.isEmpty)
        (linearLocationsUntilCp, rest)
      else
        (linearLocationsUntilCp :+ rest.head, rest.tail)
    }

    if(linearLocations.isEmpty)
      return Seq()

    val (toProcess, others) =  getUntilCalibrationPoint(linearLocations.sortBy(_.orderNumber))

    val startAddrMValue = if(toProcess.head.calibrationPoints._1.isDefined) toProcess.head.calibrationPoints._1.get else roadwayAddress.startAddrMValue
    val endAddrMValue = if(toProcess.last.calibrationPoints._2.isDefined) toProcess.last.calibrationPoints._2.get else roadwayAddress.endAddrMValue

    boundaryAddressMap(roadwayAddress, toProcess, startAddrMValue, endAddrMValue) ++ recursiveMapRoadAddresses(roadwayAddress, others)
  }

  /**
    * Map roadway address into road addresses using given linear locations
    * @param roadwayAddress The current roadway address
    * @param linearLocations The roadway linear locations
    * @return Returns the mapped road addresses
    */
  def mapRoadAddresses(roadwayAddress: RoadwayAddress, linearLocations: Seq[LinearLocation]) : Seq[RoadAddress] = {

    val groupedLinearLocations = linearLocations.groupBy(_.roadwayId)
    val roadwayLinearLocations = groupedLinearLocations.
      getOrElse(roadwayAddress.roadwayId, throw new IllegalArgumentException("Any linear locations found that belongs to the given roadway address"))

    //If is a roadway address history should recalculate all the calibration points
    val roadAddresses = recursiveMapRoadAddresses(roadwayAddress, if(roadwayAddress.endDate.nonEmpty) recalculateHistoryCalibrationPoints(roadwayAddress, linearLocations) else roadwayLinearLocations)

    //Set the discontinuity to the last road address
    roadAddresses.init :+ roadAddresses.last.copy(discontinuity = roadwayAddress.discontinuity)
  }

  def getRoadAddresses(linearLocations: Seq[LinearLocation]) : Seq[RoadAddress] = {
//TODO check if this can be a improvement
//    val roadwayAddressesF = Future(roadAddressDAO.fetchByRoadwayIds(linearLocations.map(_.roadwayId).toSet))
//
//    val groupedLinearLocations = linearLocations.groupBy(_.roadwayId)
//
//    val roadwayAddresses = Await.result(roadwayAddressesF, Duration.Inf)

    val groupedLinearLocations = linearLocations.groupBy(_.roadwayId)
    val roadwayAddresses = OracleDatabase.withDynSession {
      roadAddressDAO.fetchByRoadwayIds(linearLocations.map(_.roadwayId).toSet)
    }

    roadwayAddresses.flatMap(r => mapRoadAddresses(r, groupedLinearLocations(r.roadwayId)))
  }

}
