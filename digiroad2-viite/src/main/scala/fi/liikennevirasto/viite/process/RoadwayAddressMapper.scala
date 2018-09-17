package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.viite.dao._

class RoadwayAddressMapper(roadAddressDAO: RoadAddressDAO) {

  private def recalculateHistoryCalibrationPoints(roadAddress: RoadwayAddress, linearLocations: Seq[LinearLocation]): Seq[LinearLocation] = {
    val currentRoadwayAddress = roadAddressDAO.fetchByRoadwayId(roadAddress.roadwayId).
      getOrElse(throw new NoSuchElementException(s"Could not find any current road address for roadway ${roadAddress.roadwayId}"))

    //Fix calibration points in history road addresses
    val addressLength = roadAddress.endAddrMValue - roadAddress.startAddrMValue
    assert(addressLength >= 0)

    linearLocations.map {
      linearLocation =>
        linearLocation.calibrationPoints match {
          case (None, None) =>
            linearLocation
          case _ =>
            val (stCp, enCp) = linearLocation.calibrationPoints
            linearLocation.copy(calibrationPoints = (stCp.map(calculateHistoryCalibrationPoint(currentRoadwayAddress, roadAddress)), enCp.map(calculateHistoryCalibrationPoint(currentRoadwayAddress, roadAddress))))
        }
    }
  }

  private def calculateHistoryCalibrationPoint(currentRoadwayAddress: RoadwayAddress, historyRoadwayAddress: RoadwayAddress)(calibrationAddress: Long): Long = {
    val currentAddressLength = currentRoadwayAddress.endAddrMValue - currentRoadwayAddress.startAddrMValue
    val historyAddressLength = historyRoadwayAddress.endAddrMValue - historyRoadwayAddress.startAddrMValue
    val calibrationLength = calibrationAddress - currentRoadwayAddress.startAddrMValue

    historyRoadwayAddress.startAddrMValue + (historyAddressLength * calibrationLength / currentAddressLength)
  }

  private def recalculateBetweenCalibrationPoints(roadAddress: RoadwayAddress, segmentLinearLocations: Seq[LinearLocation], startAddress: Long, endAddress: Long) = {
    val coef = endAddress - startAddress / segmentLinearLocations.map(l => l.endMValue - l.startMValue).sum

    val sortedLinearLocations = segmentLinearLocations.sortBy(_.orderNumber)

    val addresses = sortedLinearLocations.init.scanLeft(startAddress) {
      case (address, location) =>
        address + Math.round((location.endMValue - location.startMValue) * coef)
    } :+ endAddress

    sortedLinearLocations.zip(addresses.zip(addresses.tail)).map {
      case (linearLocation, (st, en)) =>
        //TODO the start date and the created by should not be optional on the road address case class
        val geometryLength = linearLocation.endMValue - linearLocation.startMValue
        val (stCalibration, enCalibration) = linearLocation.calibrationPoints

        val calibrationPoints = (
          stCalibration.map(address => CalibrationPoint(linearLocation.linkId, if(linearLocation.sideCode == SideCode.TowardsDigitizing) 0 else geometryLength, address)),
          enCalibration.map(address => CalibrationPoint(linearLocation.linkId, if(linearLocation.sideCode == SideCode.AgainstDigitizing) 0 else geometryLength, address))
        )

        val sideCode = if(roadAddress.reverted) SideCode.switch(linearLocation.sideCode) else linearLocation.sideCode
        RoadAddress(roadAddress.id, linearLocation.id, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.roadType, roadAddress.track, Discontinuity.Continuous, st, en,
          Some(roadAddress.startDate), roadAddress.endDate, Some(roadAddress.createdBy), linearLocation.linkId, linearLocation.startMValue, linearLocation.endMValue, sideCode,
          linearLocation.adjustedTimestamp, calibrationPoints, linearLocation.floating, linearLocation.geometry, linearLocation.linkGeomSource, roadAddress.ely, roadAddress.terminated,
          roadAddress.roadwayId, linearLocation.validFrom, linearLocation.validTo, blackUnderline = false, roadAddress.roadName)
    }
  }

  private def recursiveRecalculator(roadAddress: RoadwayAddress, segmentLinearLocations: Seq[LinearLocation]) : Seq[RoadAddress] = {

    def getUntilCalibrationPoint(seq: Seq[LinearLocation]): (Seq[LinearLocation], Seq[LinearLocation]) = {
      val linearLocationsUntilCp = seq.takeWhile(l => l.calibrationPoints._2.nonEmpty)
      (linearLocationsUntilCp, seq.drop(linearLocationsUntilCp.size))
    }

    val (toProcess, others) =  getUntilCalibrationPoint(segmentLinearLocations)

    val startAddrMValue = if(toProcess.head.calibrationPoints._1.isDefined) toProcess.head.calibrationPoints._1.get else roadAddress.startAddrMValue
    val endAddrMValue = if(toProcess.last.calibrationPoints._2.isDefined) toProcess.last.calibrationPoints._1.get else roadAddress.endAddrMValue

    recalculateBetweenCalibrationPoints(roadAddress, toProcess, startAddrMValue, endAddrMValue)  ++ recursiveRecalculator(roadAddress, others)
  }

  def map(roadAddress: RoadwayAddress, linearLocations: Seq[LinearLocation]) : Seq[RoadAddress] = {

    val groupedLinearLocations = linearLocations.groupBy(_.roadwayId)
    val roadwayLinearLocations = groupedLinearLocations.getOrElse(roadAddress.roadwayId, throw new IllegalArgumentException("Linear locations belong to the road address roadway"))

    //If is a roadway address history should recalculate all the calibration points
    val roadAddresses = recursiveRecalculator(roadAddress, if(roadAddress.endDate.nonEmpty) recalculateHistoryCalibrationPoints(roadAddress, linearLocations) else roadwayLinearLocations)

    //Set the discontinuity to the last road address
    roadAddresses.init :+ roadAddresses.last.copy(discontinuity = roadAddress.discontinuity)
  }

}
