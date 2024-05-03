package fi.liikennevirasto.viite

import fi.liikennevirasto.viite.dao.{LinksWithExtraCalibrationPoints, InvalidRoadwayLength, MissingCalibrationPoint, MissingCalibrationPointFromJunction, MissingRoadwayPoint, OverlappingRoadwayOnLinearLocation, RoadNetworkDAO, Roadway}
import fi.vaylavirasto.viite.model.RoadPart
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

  def getLinksWithExtraCalibrationPoints: Seq[LinksWithExtraCalibrationPoints] = {
    withDynSession {
      roadNetworkDAO.fetchLinksWithExtraCalibrationPoints()
    }
  }

  def getLinksWithExtraCalibrationPointsOnSameRoadway: Seq[LinksWithExtraCalibrationPoints] = {
    withDynSession {
      roadNetworkDAO.fetchLinksWithExtraCalibrationPointsWithSameRoadwayNumber()
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
      validateCalibrationPoints(roadPart)
      validateRoadwayPoints(roadPart)
      validateRoadways(roadPart)
      validateRoadwayLengthThroughHistory(roadPart)
      validateOverlappingRoadwaysInHistory(roadPart)
    })
  }

  def validateCalibrationPoints(roadPart: RoadPart): Unit = {
    logger.info(s"Validating calibration points on road part:  $roadPart")
    val missingCalibrationPointsFromStart = roadNetworkDAO.fetchMissingCalibrationPointsFromStart(roadPart)
    val missingCalibrationPointFromTheEnd = roadNetworkDAO.fetchMissingCalibrationPointsFromEnd(roadPart)
    val missingCalibrationPointFromJunction = roadNetworkDAO.fetchMissingCalibrationPointsFromJunctions(roadPart)
    val extraCalibrationPoints = roadNetworkDAO.fetchLinksWithExtraCalibrationPointsByRoadPart(roadPart)
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
    else if (extraCalibrationPoints.nonEmpty) {
      logger.warn(s"Found extra calibration points for road part: $roadPart:\r ${extraCalibrationPoints.map(_.toString).mkString("\r ")}")
      throw new RoadNetworkValidationException(s"$LinkHasExtraCalibrationPoints (tieosa $roadPart)")
    }
    else {
      logger.info(s"Calibration points are valid for road part: $roadPart ")
    }
  }

  def validateRoadwayPoints(roadPart: RoadPart): Unit = {
    logger.info(s"Validating roadway points on road part:  $roadPart")
    val missingRoadwayPointsFromTheStart = roadNetworkDAO.fetchMissingRoadwayPointsFromStart(roadPart)
    val missingRoadwayPointFromTheEnd = roadNetworkDAO.fetchMissingRoadwayPointsFromEnd(roadPart)
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

  def validateRoadways(roadPart: RoadPart): Unit = {
    logger.info(s"Validating roadways for road part: $roadPart")
    val overlappingRoadwaysOnLinearLocations = roadNetworkDAO.fetchOverlappingRoadwaysOnLinearLocations(roadPart)
    if (overlappingRoadwaysOnLinearLocations.nonEmpty) {
        logger.warn(s"Found overlapping roadways on linear locations for road part: $roadPart\r: ${overlappingRoadwaysOnLinearLocations.mkString("\r ")}")
      throw new RoadNetworkValidationException(s"$OverlappingRoadwaysOnLinearLocation (tieosa $roadPart)")
    } else {
      logger.info(s"No overlapping roadways on linear locations found for road part: $roadPart")
    }
  }

  def validateRoadwayLengthThroughHistory(roadPart: RoadPart): Unit = {
    logger.info(s"Validating roadway lengths through history for road part: $roadPart")
    val invalidRoadwayLength = roadNetworkDAO.fetchInvalidRoadwayLengths(roadPart)
    if (invalidRoadwayLength.nonEmpty) {
      logger.warn(s"Found invalid roadway lengths through history for road part: $roadPart\r: ${invalidRoadwayLength.mkString("\r ")}")
      throw new RoadNetworkValidationException(s"$InvalidRoadwayLengthTroughHistory (tieosa $roadPart)")
    } else {
      logger.info(s"Roadway lengths through history are valid for road part: $roadPart")
    }
  }

  def validateOverlappingRoadwaysInHistory(roadPart: RoadPart): Unit = {
    logger.info(s"Validating overlapping roadways in history for road part: $roadPart")
    val overlappingRoadwaysInHistory = roadNetworkDAO.fetchOverlappingRoadwaysInHistory(roadPart)
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
