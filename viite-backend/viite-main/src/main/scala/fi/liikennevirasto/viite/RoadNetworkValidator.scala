package fi.liikennevirasto.viite

import fi.liikennevirasto.viite.dao.{InvalidRoadwayLength, MissingCalibrationPoint, MissingCalibrationPointFromJunction, MissingRoadwayPoint, OverlappingRoadwayOnLinearLocation, RoadNetworkDAO, Roadway}
import fi.liikennevirasto.viite.process.RoadPart
import fi.vaylavirasto.viite.postgis.PostGISDatabase.withDynSession
import org.slf4j.LoggerFactory

class RoadNetworkValidator {
  val roadNetworkDAO = new RoadNetworkDAO
  private val logger = LoggerFactory.getLogger(getClass)

  def getMissingCalibrationPointsFromTheStart: Seq[MissingCalibrationPoint] = {
    withDynSession {
      roadNetworkDAO.fetchMissingCalibrationPointsFromStart()
    }
  }

  def getMissingCalibrationPointsFromTheEnd: Seq[MissingCalibrationPoint] = {
    withDynSession {
      roadNetworkDAO.fetchMissingCalibrationPointsFromEnd()
    }
  }

  def getMissingCalibrationPointsFromJunctions: Seq[MissingCalibrationPointFromJunction] = {
    withDynSession {
      roadNetworkDAO.fetchMissingCalibrationPointsFromJunctions()
    }
  }

  def getMissingRoadwayPointsFromTheStart: Seq[MissingRoadwayPoint] = {
    withDynSession {
      roadNetworkDAO.fetchMissingRoadwayPointsFromStart()
    }
  }

  def getMissingRoadwayPointsFromTheEnd: Seq[MissingRoadwayPoint] = {
    withDynSession {
      roadNetworkDAO.fetchMissingRoadwayPointsFromEnd()
    }
  }

  def getInvalidRoadwayLengths: Seq[InvalidRoadwayLength] = {
    withDynSession {
      roadNetworkDAO.fetchInvalidRoadwayLengths()
    }
  }

  def getOverlappingRoadwaysInHistory: Seq[Roadway] = {
    withDynSession {
      roadNetworkDAO.fetchOverlappingRoadwaysInHistory()
    }
  }

  def getOverlappingRoadwaysOnLinearLocations: Seq[OverlappingRoadwayOnLinearLocation] = {
    withDynSession {
      roadNetworkDAO.fetchOverlappingRoadwaysOnLinearLocations()
    }
  }

  def validateRoadNetwork(roadParts: Seq[RoadPart]): Unit = {
    roadParts.foreach(roadPart => {
      validateCalibrationPoints(roadPart.roadNumber, roadPart.roadPartNumber)
      validateRoadwayPoints(roadPart.roadNumber, roadPart.roadPartNumber)
      validateRoadways(roadPart.roadNumber, roadPart.roadPartNumber)
      validateRoadwayLengthThroughHistory(roadPart.roadNumber, roadPart.roadPartNumber)
      validateOverlappingRoadwaysInHistory(roadPart.roadNumber, roadPart.roadPartNumber)
    })
  }

  def validateCalibrationPoints(roadNumber: Long, roadPartNumber: Long): Unit = {
    val roadPart = s"$roadNumber/$roadPartNumber"
    logger.info(s"Validating calibration points on road part:  $roadPart")
    val missingCalibrationPointsFromStart = roadNetworkDAO.fetchMissingCalibrationPointsFromStart(roadNumber, roadPartNumber)
    val missingCalibrationPointFromTheEnd = roadNetworkDAO.fetchMissingCalibrationPointsFromEnd(roadNumber, roadPartNumber)
    val missingCalibrationPointFromJunction = roadNetworkDAO.fetchMissingCalibrationPointsFromJunctions(roadNumber, roadPartNumber)
    if (missingCalibrationPointsFromStart.nonEmpty) {
      logger.warn(s"Found missing calibration points for road part start: $roadPart:\r ${missingCalibrationPointsFromStart.mkString("\r ")}")
      throw new RoadNetworkValidationException(s"$MissingCalibrationPointFromTheStart (tieosa $roadPart)")
    }
    else if (missingCalibrationPointFromTheEnd.nonEmpty) {
      logger.warn(s"Found missing calibration points for road part end: $roadPart:\r ${missingCalibrationPointFromTheEnd.mkString("\r ")}")
      throw new RoadNetworkValidationException(s"$MissingCalibrationPointFromTheEnd (tieosa $roadPart)")
    }
    else if (missingCalibrationPointFromJunction.nonEmpty) {
      logger.warn(s"Found missing calibration points from junctions for road part: $roadPart:\r ${missingCalibrationPointFromJunction.mkString("\r ")}")
      throw new RoadNetworkValidationException(s"$MissingCalibrationPointFromJunctions (tieosa $roadPart)")
    }
    else {
      logger.info(s"Calibration points are valid for road part: $roadPart ")
    }
  }

  def validateRoadwayPoints(roadNumber: Long, roadPartNumber: Long): Unit = {
    val roadPart = s"$roadNumber/$roadPartNumber"
    logger.info(s"Validating roadway points on road part:  $roadPart")
    val missingRoadwayPointsFromTheStart = roadNetworkDAO.fetchMissingRoadwayPointsFromStart(roadNumber, roadPartNumber)
    val missingRoadwayPointFromTheEnd = roadNetworkDAO.fetchMissingRoadwayPointsFromEnd(roadNumber, roadPartNumber)
    if(missingRoadwayPointsFromTheStart.nonEmpty) {
      logger.warn(s"Found missing roadway points for road part start: $roadPart:\r ${missingRoadwayPointsFromTheStart.mkString("\r ")}")
      throw new RoadNetworkValidationException(s"$MissingRoadwayPointFromTheStart (tieosa $roadPart)")
    } else if (missingRoadwayPointFromTheEnd.nonEmpty) {
      logger.warn(s"Found missing roadway points for road part end: $roadPart:\r ${missingRoadwayPointFromTheEnd.mkString("\r ")}")
      throw new RoadNetworkValidationException(s"$MissingRoadwayPointFromTheEnd (tieosa $roadPart)")
    }
    else {
      logger.info(s"Roadway points are valid for road part: $roadPart ")
    }

  }

  def validateRoadways(roadNumber: Long, roadPartNumber: Long): Unit = {
    val roadPart = s"$roadNumber/$roadPartNumber"
    logger.info(s"Validating roadways for road part: $roadPart")
    val overlappingRoadwaysOnLinearLocations = roadNetworkDAO.fetchOverlappingRoadwaysOnLinearLocations(roadNumber, roadPartNumber)
    if (overlappingRoadwaysOnLinearLocations.nonEmpty) {
        logger.warn(s"Found overlapping roadways on linear locations for road part: $roadPart\r: ${overlappingRoadwaysOnLinearLocations.mkString("\r ")}")
      throw new RoadNetworkValidationException(s"$OverlappingRoadwaysOnLinearLocation (tieosa $roadPart)")
    } else {
      logger.info(s"No overlapping roadways on linear locations found for road part: $roadPart")
    }
  }

  def validateRoadwayLengthThroughHistory(roadNumber: Long, roadPartNumber: Long): Unit = {
    val roadPart = s"$roadNumber/$roadPartNumber"
    logger.info(s"Validating roadway lengths through history for road part: $roadPart")
    val invalidRoadwayLength = roadNetworkDAO.fetchInvalidRoadwayLengths(roadNumber, roadPartNumber)
    if (invalidRoadwayLength.nonEmpty) {
      logger.warn(s"Found invalid roadway lengths through history for road part: $roadPart\r: ${invalidRoadwayLength.mkString("\r ")}")
      throw new RoadNetworkValidationException(s"$InvalidRoadwayLengthTroughHistory (tieosa $roadPart)")
    } else {
      logger.info(s"Roadway lengths through history are valid for road part: $roadPart")
    }
  }

  def validateOverlappingRoadwaysInHistory(roadNumber: Long, roadPartNumber: Long): Unit = {
    val roadPart = s"$roadNumber/$roadPartNumber"
    logger.info(s"Validating overlapping roadways in history for road part: $roadPart")
    val overlappingRoadwaysInHistory = roadNetworkDAO.fetchOverlappingRoadwaysInHistory(roadNumber, roadPartNumber)
    if (overlappingRoadwaysInHistory.nonEmpty) {
      logger.warn(s"Found overlapping roadways in history for road part: $roadPart")
      throw new RoadNetworkValidationException(s"$OverlappingRoadwayInHistory (tieosa $roadPart)")
    }
    else {
      logger.info(s"No overlapping roadways in history found for road part: $roadPart")
    }
  }
}

class RoadNetworkValidationException(s: String) extends RuntimeException {
  override def getMessage: String = s
}
