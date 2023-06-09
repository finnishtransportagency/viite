package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase.withDynSession
import fi.liikennevirasto.viite.dao.{InvalidRoadwayLength, MissingCalibrationPoint, MissingCalibrationPointFromJunction, MissingRoadwayPoint, RoadNetworkDAO, Roadway}
import fi.liikennevirasto.viite.process.RoadPart
import org.slf4j.LoggerFactory

class RoadNetworkValidator {
  val roadNetworkDAO = new RoadNetworkDAO
  private val logger = LoggerFactory.getLogger(getClass)

  def getMissingCalibrationPointsFromTheStart(): Seq[MissingCalibrationPoint] = {
    withDynSession {
      roadNetworkDAO.fetchMissingCalibrationPointsFromStart()
    }
  }

  def getMissingCalibrationPointsFromTheEnd(): Seq[MissingCalibrationPoint] = {
    withDynSession {
      roadNetworkDAO.fetchMissingCalibrationPointsFromEnd()
    }
  }

  def getMissingCalibrationPointsFromJunctions(): Seq[MissingCalibrationPointFromJunction] = {
    withDynSession {
      roadNetworkDAO.fetchMissingCalibrationPointsFromJunctions()
    }
  }

  def getMissingRoadwayPointsFromTheStart(): Seq[MissingRoadwayPoint] = {
    withDynSession {
      roadNetworkDAO.fetchMissingRoadwayPointsFromStart()
    }
  }

  def getMissingRoadwayPointsFromTheEnd(): Seq[MissingRoadwayPoint] = {
    withDynSession {
      roadNetworkDAO.fetchMissingRoadwayPointsFromEnd()
    }
  }

  def getInvalidRoadwayLengths(): Seq[InvalidRoadwayLength] = {
    withDynSession {
      roadNetworkDAO.fetchInvalidRoadwayLengths()
    }
  }

  def getOverlappingRoadways(): Seq[Roadway] = {
    withDynSession {
      roadNetworkDAO.fetchOverlappingRoadwaysInHistory()
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
    logger.info(s"Validating calibration points on road part:  ${roadNumber}/${roadPartNumber}")
    val missingCalibrationPointsFromStart = roadNetworkDAO.fetchMissingCalibrationPointsFromStart(roadNumber, roadPartNumber)
    val missingCalibrationPointFromTheEnd = roadNetworkDAO.fetchMissingCalibrationPointsFromEnd(roadNumber, roadPartNumber)
    val missingCalibrationPointFromJunction = roadNetworkDAO.fetchMissingCalibrationPointsFromJunctions(roadNumber, roadPartNumber)
    if (missingCalibrationPointsFromStart.nonEmpty) {
      logger.warn(s"Found missing calibration points for road part: ${roadNumber}/${roadPartNumber}")
      throw new RoadNetworkValidationException(MissingCalibrationPointFromTheStart)
    }
    else if (missingCalibrationPointFromTheEnd.nonEmpty) {
      logger.warn(s"Found missing calibration points for road part: ${roadNumber}/${roadPartNumber}")
      throw new RoadNetworkValidationException(MissingCalibrationPointFromTheEnd)
    }
    else if (missingCalibrationPointFromJunction.nonEmpty) {
      logger.warn(s"Found missing calibration points from junctions for road part: ${roadNumber}/${roadPartNumber}")
      throw new RoadNetworkValidationException(MissingCalibrationPointFromJunctions)
    }
    else {
      logger.info(s"Calibration points are valid for road part: ${roadNumber}/${roadPartNumber} ")
    }
  }

  def validateRoadwayPoints(roadNumber: Long, roadPartNumber: Long): Unit = {
    logger.info(s"Validating roadway points on road part:  ${roadNumber}/${roadPartNumber}")
    val missingRoadwayPointsFromTheStart = roadNetworkDAO.fetchMissingRoadwayPointsFromStart(roadNumber, roadPartNumber)
    val missingRoadwayPointFromTheEnd = roadNetworkDAO.fetchMissingRoadwayPointsFromEnd(roadNumber, roadPartNumber)
    if(missingRoadwayPointsFromTheStart.nonEmpty) {
      logger.warn(s"Found missing roadway points for road part: ${roadNumber}/${roadPartNumber}")
      throw new RoadNetworkValidationException(MissingRoadwayPointFromTheStart)
    } else if (missingRoadwayPointFromTheEnd.nonEmpty) {
      logger.warn(s"Found missing roadway points for road part: ${roadNumber}/${roadPartNumber}")
      throw new RoadNetworkValidationException(MissingRoadwayPointFromTheEnd)
    }
    else {
      logger.info(s"Roadway points are valid for road part: ${roadNumber}/${roadPartNumber} ")
    }

  }

  def validateRoadways(roadNumber: Long, roadPartNumber: Long): Unit = {
    logger.info(s"Validating roadways for road part: ${roadNumber}/${roadPartNumber}")
    val overlappingRoadwaysOnLinearLocations = roadNetworkDAO.fetchOverlappingRoadwaysOnLinearLocations(roadNumber, roadPartNumber)
    if (overlappingRoadwaysOnLinearLocations.nonEmpty) {
      logger.warn(s"Found overlapping roadways on linear locations")
      throw new RoadNetworkValidationException(OverlappingRoadwaysOnLinearLocation)
    } else {
      logger.info(s"No overlapping roadways on linear locations found for road part: ${roadNumber}/${roadPartNumber}")
    }
  }

  def validateRoadwayLengthThroughHistory(roadNumber: Long, roadPartNumber: Long): Unit = {
    logger.info(s"Validating roadway lengths through history for road part: ${roadNumber}/${roadPartNumber}")
    val invalidRoadwayLength = roadNetworkDAO.fetchInvalidRoadwayLengthTroughHistory(roadNumber, roadPartNumber)
    if (invalidRoadwayLength.nonEmpty) {
      logger.warn(s"Found invalid roadway lengths through history for road part: ${roadNumber}/${roadPartNumber}")
      throw new RoadNetworkValidationException(InvalidRoadwayLengthTroughHistory)
    } else {
      logger.info(s"Roadway lengths through history are valid for road part: ${roadNumber}/${roadPartNumber}")
    }
  }

  def validateOverlappingRoadwaysInHistory(roadNumber: Long, roadPartNumber: Long): Unit = {
    logger.info(s"Validating overlapping roadways in history")
    val overlappingRoadwaysInHistory = roadNetworkDAO.fetchOverlappingRoadwaysInHistory(roadNumber, roadPartNumber)
    if (overlappingRoadwaysInHistory.nonEmpty) {
      logger.warn(s"Found overlapping roadways in history for road part: ${roadNumber}/${roadPartNumber}")
      throw new RoadNetworkValidationException(OverlappingRoadwayInHistory)
    }
    else {
      logger.info(s"No overlapping roadways in history found for road part: ${roadNumber}/${roadPartNumber}")
    }
  }
}

class RoadNetworkValidationException(s: String) extends RuntimeException {
  override def getMessage: String = s
}
