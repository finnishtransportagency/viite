package fi.liikennevirasto.viite.process

import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao.{LinearLocation, LinearLocationDAO, ProjectCalibrationPoint, ProjectLink, RoadAddress, Roadway, RoadwayDAO}
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import fi.vaylavirasto.viite.geometry.BoundingRectangle
import fi.vaylavirasto.viite.model.{AddrMRange, ArealRoadMaintainer, Discontinuity, RoadPart, SideCode}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

class RoadwayAddressMapper(roadwayDAO: RoadwayDAO, linearLocationDAO: LinearLocationDAO) {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
    * Recalculate address value of all the history road address calibration points
    *
    * @param historyRoadwayAddress The history roadway address
    * @param linearLocations       The current linear locations
    * @return The linear location with recalculated calibration points
    */
  private def recalculateHistoryCalibrationPoints(historyRoadwayAddress: Roadway, linearLocations: Seq[LinearLocation]): Seq[LinearLocation] = {
    val currentRoadwayAddress = PostGISDatabaseScalikeJDBC.runWithReadOnlySession {
        roadwayDAO.fetchByRoadwayNumber(historyRoadwayAddress.roadwayNumber).getOrElse(
          throw new NoSuchElementException(s"Could not find any current road address for roadway ${historyRoadwayAddress.roadwayNumber}")
        )
      }

    //Fix calibration points in history road addresses
    val addressLength = historyRoadwayAddress.addrMRange.length

    assert(addressLength >= 0)

    linearLocations.map {
      linearLocation =>
        (linearLocation.startCalibrationPoint.addrM, linearLocation.endCalibrationPoint.addrM) match {
          case (None, None) =>
            linearLocation
          case _ =>
            val (stCp, enCp) = (linearLocation.startCalibrationPoint, linearLocation.endCalibrationPoint)
            linearLocation.copy(calibrationPoints = (
              stCp.copy(addrM = stCp.addrM.map(calculateAddressHistoryCalibrationPoint(currentRoadwayAddress, historyRoadwayAddress))),
              enCp.copy(addrM = enCp.addrM.map(calculateAddressHistoryCalibrationPoint(currentRoadwayAddress, historyRoadwayAddress)))))
        }
    }
  }

  /**
    * Calculate the history calibration point address using a coeficient between the current and the history roadway address
    *
    * @param currentRoadwayAddress     The current (endDate is null) road address
    * @param historyRoadwayAddress     The history road address
    * @param currentCalibrationAddress The calibration point address of the current to be calculated in the history
    * @return The address of the history calibration point
    */
  private def calculateAddressHistoryCalibrationPoint(currentRoadwayAddress: Roadway, historyRoadwayAddress: Roadway)(currentCalibrationAddress: Long): Long = {
    val currentAddressLength = currentRoadwayAddress.addrMRange.length
    val historyAddressLength = historyRoadwayAddress.addrMRange.length
    val calibrationLength = currentCalibrationAddress - currentRoadwayAddress.addrMRange.start

    historyRoadwayAddress.addrMRange.start + (historyAddressLength * calibrationLength / currentAddressLength)
  }

  /**
    * Map roadway address into road addresses using linear locations in between given start and end address values, i.e. range.
    *
    * @param roadway         The roadway address
    * @param linearLocations The linear locations in between given start and end address values
    * @param addrMRange      The boundary address range
    * @return Returns the mapped road addresses
    */
  private def boundaryAddressMap(roadway: Roadway, linearLocations: Seq[LinearLocation], addrMRange: AddrMRange): Seq[RoadAddress] = {


    def mappedAddressValues(remaining: Seq[LinearLocation], processed: Seq[LinearLocation],
                            startAddr: Double, endAddr: Double, /** Note! takes start, and end addresses in as <i>double</i> values! Cannot use AddrMRange! */
                            coef: Double, list: Seq[Long], increment: Int, depth: Int = 1): Seq[Long] = {
      if (remaining.isEmpty) {
        list
      } else {
        val location = remaining.head
        //increment can also be negative
        val previewValue = if (remaining.size == 1) {
          startAddr + Math.round((location.endMValue - location.startMValue) * coef) + increment
        } else {
          startAddr + (location.endMValue - location.startMValue) * coef + increment
        }

        if (depth > 100) {
          val message = s"mappedAddressValues got in infinite recursion. Roadway number = ${roadway.roadwayNumber}, location.id = ${location.id}, startMValue = ${location.startMValue}, endMValue = ${location.endMValue}, previewValue = $previewValue, remaining = ${remaining.length}"
          logger.error(message)
          if (depth > 105) throw new RuntimeException(message)
        }

        val adjustedList: Seq[Long] = if ((previewValue < addrMRange.end) && (previewValue > startAddr)) { // TODO VIITE-3349 refactoring to AddrMRange: mixed usage of startAddr, and addrMRange.end - is this what is intended?
          list :+ Math.round(previewValue)
        } else if (previewValue <= startAddr) {
          mappedAddressValues(Seq(remaining.head), processed, list.last, endAddr, coef, list, increment + 1, depth + 1)
        } else if (previewValue <= addrMRange.end) { // TODO VIITE-3349 refactoring to AddrMRange: mixed usage of startAddr two lines above, and addrMRange.end here - is this what is intended?
          mappedAddressValues(Seq(remaining.head), processed, list.last, endAddr, coef, list, increment - 1, depth + 1)
        } else {
          mappedAddressValues(processed.last +: remaining, processed.init, list.init.last, endAddr, coef, list.init, increment - 1, depth + 1)
        }
        mappedAddressValues(remaining.tail, processed :+ remaining.head, previewValue, endAddr, coef, adjustedList, increment, depth + 1)
      }

    }

    val coefficient = addrMRange.length / linearLocations.map(l => l.endMValue - l.startMValue).sum

    val sortedLinearLocations = linearLocations.sortBy(_.orderNumber)

    val addresses = mappedAddressValues(sortedLinearLocations.init, Seq(), addrMRange.start, addrMRange.end, coefficient, Seq(addrMRange.start), 0) :+ addrMRange.end

    sortedLinearLocations.zip(addresses.zip(addresses.tail)).map {
      case (linearLocation, (st, en)) =>
        val geometryLength = linearLocation.endMValue - linearLocation.startMValue
        val (startCP, endCP) = (linearLocation.startCalibrationPoint.addrM, linearLocation.endCalibrationPoint.addrM)

        val calibrationPoints = (
          startCP.map(address => ProjectCalibrationPoint(linearLocation.linkId,
            if (linearLocation.sideCode == SideCode.TowardsDigitizing) 0 else geometryLength,
            address, linearLocation.startCalibrationPointType)),
          endCP.map(address => ProjectCalibrationPoint(linearLocation.linkId,
            if (linearLocation.sideCode == SideCode.AgainstDigitizing) 0 else geometryLength,
            address, linearLocation.endCalibrationPointType))
        )

        RoadAddress(roadway.id, linearLocation.id, roadway.roadPart, roadway.administrativeClass, roadway.track, Discontinuity.Continuous, AddrMRange(st, en), Some(roadway.startDate), roadway.endDate, Some(roadway.createdBy), linearLocation.linkId, linearLocation.startMValue, linearLocation.endMValue, linearLocation.sideCode, linearLocation.adjustedTimestamp, calibrationPoints, linearLocation.geometry, linearLocation.linkGeomSource,
          roadway.arealRoadMaintainer.number, roadway.terminated, roadway.roadwayNumber, linearLocation.validFrom, linearLocation.validTo, roadway.roadName)
    }
  }

  /**
    * Recursively map road addresses in between calibration points
    *
    * @param roadway         The current roadway address
    * @param linearLocations The linear location in between the calibration points
    * @return Returns the mapped road addresses in between the calibration points
    */
  private def recursiveMapRoadAddresses(roadway: Roadway, linearLocations: Seq[LinearLocation]): Seq[RoadAddress] = {

    def getUntilCalibrationPoint(seq: Seq[LinearLocation]): (Seq[LinearLocation], Seq[LinearLocation]) = {
      val linearLocationsUntilCp = seq.takeWhile(l => l.endCalibrationPoint.isEmpty)
      val rest = seq.drop(linearLocationsUntilCp.size)
      if (rest.headOption.isEmpty)
        (linearLocationsUntilCp, rest)
      else
        (linearLocationsUntilCp :+ rest.head, rest.tail)
    }

    if (linearLocations.isEmpty)
      return Seq()

    val (toProcess, others) = getUntilCalibrationPoint(linearLocations.sortBy(_.orderNumber))

    val addrMRange = AddrMRange(
      if (toProcess.head.startCalibrationPoint.isDefined) toProcess.head.startCalibrationPoint.addrM.get else roadway.addrMRange.start,
      if (toProcess.last.endCalibrationPoint.isDefined)   toProcess.last.endCalibrationPoint.addrM.get   else roadway.addrMRange.end
    )

    boundaryAddressMap(roadway, toProcess, addrMRange) ++ recursiveMapRoadAddresses(roadway, others)
  }

  /**
    * Map roadway address into road addresses using given linear locations
    *
    * @param roadway         The current roadway address
    * @param linearLocations The roadway linear locations
    * @return Returns the mapped road addresses
    */
  def mapRoadAddresses(roadway: Roadway, linearLocations: Seq[LinearLocation]): Seq[RoadAddress] = {

    val groupedLinearLocations = linearLocations.groupBy(_.roadwayNumber)
    val roadwayLinearLocations = groupedLinearLocations.
      getOrElse(roadway.roadwayNumber, throw new NoSuchElementException("No linear locations found that belongs to the given roadway address"))

    //If is a roadway address history should recalculate all the calibration points
    val roadAddresses = recursiveMapRoadAddresses(roadway, if (roadway.endDate.nonEmpty && roadway.terminated == NoTermination) recalculateHistoryCalibrationPoints(roadway, linearLocations) else roadwayLinearLocations)

    //Set the discontinuity to the last road address
    roadAddresses.init :+ roadAddresses.last.copy(discontinuity = roadway.discontinuity)
  }

  def mapLinearLocations(roadway: Roadway, projectLinks: Seq[ProjectLink]): Seq[LinearLocation] = {
    projectLinks.sortBy(_.addrMRange.start).zip(1 to projectLinks.size).
      map {
        case (projectLink, key) =>
          LinearLocation(projectLink.linearLocationId, key, projectLink.linkId, projectLink.startMValue, projectLink.endMValue, projectLink.sideCode, projectLink.linkGeometryTimeStamp,
            (CalibrationPointsUtils.toCalibrationPointReference(projectLink.startCalibrationPoint),
              CalibrationPointsUtils.toCalibrationPointReference(projectLink.endCalibrationPoint)),
            projectLink.geometry, projectLink.linkGeomSource, roadway.roadwayNumber, Some(DateTime.now()))
    }
  }

  //TODO may be a good idea mode this method to road address service
  /**
    * Uses the RoadwayDAO to get the roadway information that is connected to the entries of given linearLocations.
    * Both information is then mixed and returned as fully fledged RoadAddress entries.
    *
    * @param linearLocations : Seq[LinearLocation] - The collection of Linear Locations entries
    * @return
    */
  def getRoadAddressesByLinearLocation(linearLocations: Seq[LinearLocation]): Seq[RoadAddress] = {
    val groupedLinearLocations = linearLocations.groupBy(_.roadwayNumber)

    val roadways = roadwayDAO.fetchAllByRoadwayNumbers(linearLocations.map(_.roadwayNumber).toSet)

    roadways.flatMap(r => mapRoadAddresses(r, groupedLinearLocations(r.roadwayNumber)))
  }

  def getCurrentRoadAddressesByLinearLocation(linearLocations: Seq[LinearLocation], situationDate: Option[DateTime] = None): Seq[RoadAddress] = {
    val groupedLinearLocations = linearLocations.groupBy(_.roadwayNumber)
    val roadwayAddresses = roadwayDAO.fetchAllByRoadwayNumbers(linearLocations.map(_.roadwayNumber).toSet, situationDate.getOrElse(new DateTime()))

    roadwayAddresses.flatMap(r => mapRoadAddresses(r, groupedLinearLocations(r.roadwayNumber)))
  }

  def getCurrentRoadAddressesBySection(roadPart: RoadPart): Seq[RoadAddress] = {
    val sectionRoadway = roadwayDAO.fetchAllBySection(roadPart)
    val linearLocations = linearLocationDAO.fetchByRoadwayNumber(sectionRoadway.map(_.roadwayNumber))
    val groupedLinearLocations = linearLocations.groupBy(_.roadwayNumber)

    sectionRoadway.flatMap(r => mapRoadAddresses(r, groupedLinearLocations(r.roadwayNumber)))
  }

  // TODO Might be a good idea to move this method to the RoadAddressService
  /**
    * Uses the LinearLocationDAO to get the linear location information that is connected to the entries of given Roadway entries.
    * Both information is then mixed and returned as fully fledged RoadAddress entries.
    *
    * @param roadwayAddresses : Seq[Roadway] - The collection of Roadway's entries
    * @return
    */
  def getRoadAddressesByRoadway(roadwayAddresses: Seq[Roadway]): Seq[RoadAddress] = {
    val linearLocations = linearLocationDAO.fetchByRoadways(roadwayAddresses.map(_.roadwayNumber).toSet)
    val groupedLinearLocations = linearLocations.groupBy(_.roadwayNumber)
    roadwayAddresses.flatMap(r => mapRoadAddresses(r, groupedLinearLocations(r.roadwayNumber)))
  }

  def getRoadAddressesByBoundingBox(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)]): Seq[RoadAddress] = {
    val linearLocations = linearLocationDAO.fetchLinearLocationByBoundingBox(boundingRectangle, roadNumberLimits)
    val groupedLinearLocations = linearLocations.groupBy(_.roadwayNumber)
    val roadways = roadwayDAO.fetchAllByRoadwayNumbers(linearLocations.map(_.roadwayNumber).toSet)
    roadways.flatMap(r => mapRoadAddresses(r, groupedLinearLocations(r.roadwayNumber)))
  }

}
