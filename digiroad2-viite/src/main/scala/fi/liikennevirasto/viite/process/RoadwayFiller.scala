package fi.liikennevirasto.viite.process


import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.Combined
import fi.liikennevirasto.viite.NewRoadway
import fi.liikennevirasto.viite.dao.AddressChangeType._
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao._
import org.slf4j.LoggerFactory

object RoadwayFiller {

  private val logger = LoggerFactory.getLogger(getClass)
  private val roadwayAddressMapper = new RoadwayAddressMapper(new RoadwayDAO, new LinearLocationDAO)

  def generateNewRoadwaysWithHistory(changeSource: RoadwayChangeSection, changeTarget: RoadwayChangeSection, projectLinks: Seq[ProjectLink], currentRoadway: Roadway, newRoadwayNumber: Boolean): Seq[Roadway] = {
    val roadwayNumber = if(newRoadwayNumber) Sequences.nextRoadwayNumber else currentRoadway.roadwayNumber

    val historyRoadway = Roadway(NewRoadway, roadwayNumber, changeSource.roadNumber.get, changeSource.startRoadPartNumber.get, changeSource.roadType.get, Track.apply(changeSource.trackCode.get.toInt), changeSource.discontinuity.get,
      changeSource.startAddressM.get, changeSource.endAddressM.get, projectLinks.head.reversed, currentRoadway.startDate, projectLinks.head.startDate, createdBy = currentRoadway.createdBy, currentRoadway.roadName,
      currentRoadway.ely, NoTermination, currentRoadway.validFrom, currentRoadway.validTo)

    val newRoadway = Roadway(NewRoadway, roadwayNumber, changeTarget.roadNumber.get, changeTarget.startRoadPartNumber.get, changeTarget.roadType.get, Track.apply(changeTarget.trackCode.get.toInt), changeTarget.discontinuity.get,
      changeTarget.startAddressM.get, changeTarget.endAddressM.get, projectLinks.head.reversed, projectLinks.head.startDate.get, None, createdBy = projectLinks.head.createdBy.get, currentRoadway.roadName,
      changeTarget.ely.get, NoTermination)

    Seq(historyRoadway, newRoadway)
  }

  private def applyUnchanged(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadway: Roadway): (Seq[Roadway], Seq[LinearLocation]) = {
    val changeSource = change.changeInfo.source
    val changeTarget = change.changeInfo.target
    val roadways =
        if(currentRoadway.startAddrMValue == changeSource.startAddressM.get && currentRoadway.endAddrMValue == changeSource.endAddressM.get ){
          generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinks, currentRoadway, newRoadwayNumber = false)
        }
        else{
          generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinks, currentRoadway, newRoadwayNumber = true)
        }
    (roadways, roadwayAddressMapper.mapLinearLocations(roadways.head, projectLinks /*setCalibrationPoints(projectLinks)*/ ))
  }

  private def applyTransfer(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadway: Roadway): (Seq[Roadway], Seq[LinearLocation]) = {
    val changeSource = change.changeInfo.source
    val changeTarget = change.changeInfo.target
    val roadways =
      if(changeSource.roadNumber == changeTarget.roadNumber && changeSource.startRoadPartNumber == changeTarget.startRoadPartNumber && changeSource.trackCode == changeTarget.trackCode &&
      (changeSource.startAddressM == changeTarget.startAddressM && changeSource.endAddressM == changeTarget.endAddressM
        || (changeSource.endAddressM.get-changeSource.startAddressM.get) == (currentRoadway.endAddrMValue - currentRoadway.startAddrMValue))){
          generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinks, currentRoadway, newRoadwayNumber = false)
      }
      else{
          generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinks, currentRoadway, newRoadwayNumber = true)
      }
    (roadways, roadwayAddressMapper.mapLinearLocations(roadways.head, projectLinks /*setCalibrationPoints(projectLinks)*/ ))
  }

  private def applyTerminated(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadway: Roadway): (Seq[Roadway], Seq[LinearLocation]) = {
    val changeSource = change.changeInfo.source
    val newRoadwayNumber = if((changeSource.endAddressM.get-changeSource.startAddressM.get) == (currentRoadway.endAddrMValue - currentRoadway.startAddrMValue)) currentRoadway.roadwayNumber else Sequences.nextRoadwayNumber

    val roadway = currentRoadway.copy(id = NewRoadway, roadwayNumber = newRoadwayNumber, endDate = projectLinks.head.endDate)
    (Seq(roadway), roadwayAddressMapper.mapLinearLocations(roadway, projectLinks /*setCalibrationPoints(projectLinks)*/ ))
  }

  private def applyNew(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink]): (Seq[Roadway], Seq[LinearLocation]) =  {
    val changeTarget = change.changeInfo.target
    val newRoadwayNumber = Sequences.nextRoadwayNumber
    val roadway = Roadway(NewRoadway, newRoadwayNumber, changeTarget.roadNumber.get, changeTarget.startRoadPartNumber.get, changeTarget.roadType.get, Track.apply(changeTarget.trackCode.get.toInt), changeTarget.discontinuity.get,
      changeTarget.startAddressM.get, changeTarget.endAddressM.get, change.changeInfo.reversed, startDate = projectLinks.head.startDate.get, endDate = projectLinks.head.endDate, createdBy = projectLinks.head.createdBy.get, projectLinks.head.roadName,
      projectLinks.head.ely, NoTermination)
    (Seq(roadway), roadwayAddressMapper.mapLinearLocations(roadway, projectLinks /*setCalibrationPoints(projectLinks)*/ ))
  }

  private def applyNumbering(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadway: Roadway):  (Seq[Roadway], Seq[LinearLocation]) = {
    val changeSource = change.changeInfo.source
    val changeTarget = change.changeInfo.target
    val roadways =
        if(changeSource.startAddressM == changeTarget.startAddressM && changeSource.endAddressM == changeTarget.endAddressM){
          generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinks, currentRoadway, newRoadwayNumber = false)
        }
        else{
          generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinks, currentRoadway, newRoadwayNumber = true)
        }
    (roadways, roadwayAddressMapper.mapLinearLocations(roadways.head, projectLinks /*setCalibrationPoints(projectLinks)*/ ) )
  }

  def fillRoadways(allRoadways: Seq[Roadway], changesWithProjectLinks: Seq[(ProjectRoadwayChange, Seq[ProjectLink])]): Seq[(Seq[Roadway], Seq[LinearLocation])] = {
    changesWithProjectLinks.map {
      roadwayChange => roadwayChange._1.changeInfo.changeType match {
        case AddressChangeType.Unchanged => applyUnchanged(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue), allRoadways.find(_.id == roadwayChange._2.head.roadwayId).get)
        case AddressChangeType.New => applyNew(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue))
        case AddressChangeType.Transfer => applyTransfer(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue), allRoadways.find(_.id == roadwayChange._2.head.roadwayId).get)
        case AddressChangeType.ReNumeration => applyNumbering(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue), allRoadways.find(_.id == roadwayChange._2.head.roadwayId).get)
        case AddressChangeType.Termination => applyTerminated(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue), allRoadways.find(_.id == roadwayChange._2.head.roadwayId).get)
        case AddressChangeType.Unknown => applyNew(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue))
        case AddressChangeType.NotHandled => applyNew(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue))
      }
    }
  }

//  private def setCalibrationPoints(projectLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
//    if (projectLinks.isEmpty) {
//      projectLinks
//    } else {
//      val startNeedsCP = projectLinks.head
//      val endNeedsCP = projectLinks.last
//
//      projectLinks.length match {
//        case 2 =>
//          Seq(fillCPs(startNeedsCP, atStart = true)) ++ Seq(fillCPs(endNeedsCP, atEnd = true))
//        case 1 =>
//          Seq(fillCPs(startNeedsCP, atStart = true, atEnd = true))
//        case _ =>
//          val middle = roadAddresses.drop(1).dropRight(1)
//          Seq(fillCPs(startNeedsCP, atStart = true)) ++ middle ++ Seq(fillCPs(endNeedsCP, atEnd = true))
//      }
//    }
//  }
}

