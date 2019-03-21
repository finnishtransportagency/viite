package fi.liikennevirasto.viite.process


import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.NewRoadway
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Subsequent}
import fi.liikennevirasto.viite.dao._
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

object RoadwayFiller {

  private val logger = LoggerFactory.getLogger(getClass)
  private val roadwayAddressMapper = new RoadwayAddressMapper(new RoadwayDAO, new LinearLocationDAO)

  def generateNewRoadwaysWithHistory(changeSource: RoadwayChangeSection, changeTarget: RoadwayChangeSection, projectLinks: Seq[ProjectLink], currentRoadway: Roadway, newRoadwayNumber: Boolean, projectStartDate: DateTime): Seq[Roadway] = {
    val roadwayNumber = if (newRoadwayNumber) Sequences.nextRoadwayNumber else currentRoadway.roadwayNumber

    val historyRoadway = Roadway(NewRoadway, roadwayNumber, changeSource.roadNumber.get, changeSource.startRoadPartNumber.get, changeSource.roadType.get, Track.apply(changeSource.trackCode.get.toInt), changeSource.discontinuity.get,
      changeSource.startAddressM.get, changeSource.endAddressM.get, projectLinks.head.reversed, currentRoadway.startDate, Some(projectStartDate), createdBy = currentRoadway.createdBy, currentRoadway.roadName,
      currentRoadway.ely, NoTermination, currentRoadway.validFrom, currentRoadway.validTo)

    val newRoadway = Roadway(NewRoadway, roadwayNumber, changeTarget.roadNumber.get, changeTarget.startRoadPartNumber.get, changeTarget.roadType.get, Track.apply(changeTarget.trackCode.get.toInt), changeTarget.discontinuity.get,
      projectLinks.head.startAddrMValue, projectLinks.last.endAddrMValue, projectLinks.head.reversed, projectStartDate, None, createdBy = projectLinks.head.createdBy.get, currentRoadway.roadName,
      changeTarget.ely.get, NoTermination)

    Seq(historyRoadway, newRoadway)
  }

  private def applyUnchanged(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadways: Seq[Roadway],
                             historyRoadways: Seq[Roadway]): Seq[(Seq[Roadway], Seq[LinearLocation])] = {
    val changeSource = change.changeInfo.source
    val changeTarget = change.changeInfo.target
    currentRoadways.map { currentRoadway =>
      val projectLinksInRoadway = projectLinks.filter(_.roadwayId == currentRoadway.id).sortBy(_.startAddrMValue)
      val roadTypeDiscontinuityOrElyChanged = currentRoadway.roadType != changeTarget.roadType.get ||
        currentRoadway.discontinuity != changeTarget.discontinuity.get || currentRoadway.ely != changeTarget.ely.get
      val lengthChanged = currentRoadway.startAddrMValue != changeTarget.startAddressM.get ||
        currentRoadway.endAddrMValue != changeTarget.endAddressM.get
      val roadways = if (roadTypeDiscontinuityOrElyChanged || lengthChanged) {
        generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinksInRoadway, currentRoadway,
          newRoadwayNumber = lengthChanged, change.projectStartDate)
      } else {
        Seq(Roadway(NewRoadway, currentRoadway.roadwayNumber, changeTarget.roadNumber.get,
          changeTarget.startRoadPartNumber.get, changeTarget.roadType.get, Track.apply(changeTarget.trackCode.get.toInt),
          changeTarget.discontinuity.get, projectLinks.head.startAddrMValue, projectLinks.last.endAddrMValue,
          projectLinks.head.reversed, currentRoadway.startDate, None, createdBy = projectLinks.head.createdBy.get,
          currentRoadway.roadName, changeTarget.ely.get, NoTermination))
      }

      val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)
      val newHistoryRoadways = currentRoadwayHistoryRoadways.flatMap { historyRoadway =>
        val newStartAddressM = historyRoadway.startAddrMValue + roadways.head.startAddrMValue - currentRoadway.startAddrMValue
        val newEndAddressM = newStartAddressM + roadways.head.endAddrMValue - roadways.head.startAddrMValue
        if (historyRoadway.endAddrMValue - historyRoadway.startAddrMValue != roadways.head.endAddrMValue - roadways.head.startAddrMValue) {
          Seq(Roadway(NewRoadway, roadways.head.roadwayNumber, historyRoadway.roadNumber, historyRoadway.roadPartNumber,
            historyRoadway.roadType, historyRoadway.track, historyRoadway.discontinuity, newStartAddressM, newEndAddressM,
            historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy,
            historyRoadway.roadName, historyRoadway.ely, NoTermination))
        } else {
          Seq(historyRoadway)
        }
      }

      (roadways ++ newHistoryRoadways, roadwayAddressMapper.mapLinearLocations(roadways.head, projectLinksInRoadway))
    }
  }

  private def applyTransfer(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadways: Seq[Roadway], historyRoadways: Seq[Roadway]): Seq[(Seq[Roadway], Seq[LinearLocation])] = {
    val changeSource = change.changeInfo.source
    val changeTarget = change.changeInfo.target
    currentRoadways.map { currentRoadway =>
      val projectLinksInRoadway = projectLinks
        .filter(projectLink => projectLink.roadwayId == currentRoadway.id
          && projectLink.roadNumber == changeTarget.roadNumber.get
          && projectLink.roadPartNumber == changeTarget.startRoadPartNumber.get)
        .sortBy(_.startAddrMValue)
      val roadways =
        if (changeSource.roadNumber == changeTarget.roadNumber && changeSource.startRoadPartNumber == changeTarget.startRoadPartNumber && changeSource.trackCode == changeTarget.trackCode &&
          (projectLinksInRoadway.last.endAddrMValue - projectLinksInRoadway.head.startAddrMValue) == (currentRoadway.endAddrMValue - currentRoadway.startAddrMValue)) {
          Seq(Roadway(NewRoadway, currentRoadway.roadwayNumber, changeTarget.roadNumber.get, changeTarget.startRoadPartNumber.get, changeTarget.roadType.get, Track.apply(changeTarget.trackCode.get.toInt), changeTarget.discontinuity.get,
            projectLinks.head.startAddrMValue, projectLinks.last.endAddrMValue, projectLinks.head.reversed, projectLinks.head.startDate.get, None, createdBy = projectLinks.head.createdBy.get, currentRoadway.roadName,
            changeTarget.ely.get, NoTermination))
        }
        else {
          generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinksInRoadway, currentRoadway, newRoadwayNumber = true, change.projectStartDate)
        }

      val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)

      val newHistoryRoadways = currentRoadwayHistoryRoadways.map { historyRoadway =>
        if (historyRoadway.endAddrMValue - historyRoadway.startAddrMValue != roadways.head.endAddrMValue - roadways.head.startAddrMValue) {
          val newStartAddressM = historyRoadway.startAddrMValue + roadways.head.startAddrMValue - currentRoadway.startAddrMValue
          val newEndAddressM = newStartAddressM + roadways.head.endAddrMValue - roadways.head.startAddrMValue
          Roadway(NewRoadway, roadways.head.roadwayNumber, historyRoadway.roadNumber, historyRoadway.roadPartNumber, historyRoadway.roadType, historyRoadway.track, historyRoadway.discontinuity,
            newStartAddressM, newEndAddressM, historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName,
            historyRoadway.ely, NoTermination)
        }
        else {
          historyRoadway
        }
      }
      (roadways ++ newHistoryRoadways, roadwayAddressMapper.mapLinearLocations(roadways.find(_.endDate.isEmpty).getOrElse(throw new Exception), projectLinksInRoadway))
    }
  }

  private def applyTerminated(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadways: Seq[Roadway], historyRoadways: Seq[Roadway]): Seq[(Seq[Roadway], Seq[LinearLocation])] = {
    val sourceChange = change.changeInfo.source
    currentRoadways.map { currentRoadway =>
      val projectLinksInRoadway = projectLinks.filter(_.roadwayId == currentRoadway.id).sortBy(_.startAddrMValue)
      val newRoadwayNumber = if ((projectLinksInRoadway.last.endAddrMValue - projectLinksInRoadway.head.startAddrMValue) == (currentRoadway.endAddrMValue - currentRoadway.startAddrMValue)) currentRoadway.roadwayNumber else Sequences.nextRoadwayNumber
      val roadway = currentRoadway.copy(id = NewRoadway, roadwayNumber = newRoadwayNumber, endDate = projectLinks.head.endDate, terminated = TerminationCode.Termination, startAddrMValue = sourceChange.startAddressM.get, endAddrMValue = sourceChange.endAddressM.get)
      val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)

      val newHistoryRoadways = currentRoadwayHistoryRoadways.map { historyRoadway =>
        val newStartAddressM = historyRoadway.startAddrMValue + roadway.startAddrMValue - currentRoadway.startAddrMValue
        val newEndAddressM = newStartAddressM + roadway.endAddrMValue - roadway.startAddrMValue
        if (historyRoadway.endAddrMValue - historyRoadway.startAddrMValue != roadway.endAddrMValue - roadway.startAddrMValue) {
          Roadway(NewRoadway, roadway.roadwayNumber, historyRoadway.roadNumber, historyRoadway.roadPartNumber, historyRoadway.roadType, historyRoadway.track, historyRoadway.discontinuity,
            newStartAddressM, newEndAddressM, historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName,
            historyRoadway.ely, Subsequent)
        }
        else {
          historyRoadway.copy(id = NewRoadway, terminated = Subsequent)
        }
      }

      (Seq(roadway) ++ newHistoryRoadways, roadwayAddressMapper.mapLinearLocations(roadway, projectLinks))
    }
  }

  private def applyNew(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink]): Seq[(Seq[Roadway], Seq[LinearLocation])] = {
    val changeTarget = change.changeInfo.target
    val newRoadwayNumber = Sequences.nextRoadwayNumber
    val roadway = Roadway(NewRoadway, newRoadwayNumber, changeTarget.roadNumber.get, changeTarget.startRoadPartNumber.get, changeTarget.roadType.get, Track.apply(changeTarget.trackCode.get.toInt), changeTarget.discontinuity.get,
      changeTarget.startAddressM.get, changeTarget.endAddressM.get, change.changeInfo.reversed, startDate = projectLinks.head.startDate.get, endDate = projectLinks.head.endDate, createdBy = projectLinks.head.createdBy.get, projectLinks.head.roadName,
      projectLinks.head.ely, NoTermination)
    Seq((Seq(roadway), roadwayAddressMapper.mapLinearLocations(roadway, projectLinks)))
  }

  private def applyNumbering(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadways: Seq[Roadway], historyRoadways: Seq[Roadway]): Seq[(Seq[Roadway], Seq[LinearLocation])] = {
    val changeSource = change.changeInfo.source
    val changeTarget = change.changeInfo.target
    currentRoadways.map { currentRoadway =>
      val projectLinksInRoadway = projectLinks.filter(_.roadwayId == currentRoadway.id).sortBy(_.startAddrMValue)
      val roadways =
        if (currentRoadway.startAddrMValue == projectLinksInRoadway.head.startAddrMValue && currentRoadway.endAddrMValue == projectLinksInRoadway.last.endAddrMValue) {
          generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinksInRoadway, currentRoadway, newRoadwayNumber = false, change.projectStartDate)
        }
        else {
          generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinksInRoadway, currentRoadway, newRoadwayNumber = true, change.projectStartDate)
        }

      val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)
      val newHistoryRoadways = currentRoadwayHistoryRoadways.map { historyRoadway =>
        val newStartAddressM = historyRoadway.startAddrMValue + roadways.head.startAddrMValue - currentRoadway.startAddrMValue
        val newEndAddressM = newStartAddressM + roadways.head.endAddrMValue - roadways.head.startAddrMValue
        if (historyRoadway.endAddrMValue - historyRoadway.startAddrMValue != roadways.head.endAddrMValue - roadways.head.startAddrMValue) {
          Roadway(NewRoadway, roadways.head.roadwayNumber, historyRoadway.roadNumber, historyRoadway.roadPartNumber, historyRoadway.roadType, historyRoadway.track, historyRoadway.discontinuity,
            newStartAddressM, newEndAddressM, historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName,
            historyRoadway.ely, NoTermination)
        }
        else {
          historyRoadway
        }
      }
      (roadways ++ newHistoryRoadways, roadwayAddressMapper.mapLinearLocations(roadways.find(_.endDate.isEmpty).getOrElse(throw new Exception), projectLinksInRoadway))
    }
  }

  def fillRoadways(allRoadways: Map[Long, Roadway], historyRoadways: Map[Long, Roadway], changesWithProjectLinks: Seq[(ProjectRoadwayChange, Seq[ProjectLink])]): Seq[(Seq[Roadway], Seq[LinearLocation])] = {
    changesWithProjectLinks.flatMap {
      roadwayChange =>
        roadwayChange._1.changeInfo.changeType match {
          case AddressChangeType.Unchanged =>
            val currentRoadways = roadwayChange._2.map(_.roadwayId).distinct.map(roadwayNumber => allRoadways.getOrElse(roadwayNumber, throw new Exception))
            applyUnchanged(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue),
              currentRoadways, historyRoadways.filter(historyRoadway => historyRoadway._2.endDate.isDefined && currentRoadways.map(_.roadwayNumber).distinct.contains(historyRoadway._2.roadwayNumber)).values.toSeq)

          case AddressChangeType.New =>
            applyNew(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue))

          case AddressChangeType.Transfer =>
            val currentRoadways = roadwayChange._2.map(_.roadwayId).distinct.map(roadwayNumber => allRoadways.getOrElse(roadwayNumber, throw new Exception))
            applyTransfer(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue),
              currentRoadways, historyRoadways.filter(historyRoadway => historyRoadway._2.endDate.isDefined && currentRoadways.map(_.roadwayNumber).distinct.contains(historyRoadway._2.roadwayNumber)).values.toSeq)

          case AddressChangeType.ReNumeration =>
            val currentRoadways = roadwayChange._2.map(_.roadwayId).distinct.map(roadwayNumber => allRoadways.getOrElse(roadwayNumber, throw new Exception))
            applyNumbering(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue),
              currentRoadways, historyRoadways.filter(historyRoadway => historyRoadway._2.endDate.isDefined && currentRoadways.map(_.roadwayNumber).distinct.contains(historyRoadway._2.roadwayNumber)).values.toSeq)

          case AddressChangeType.Termination =>
            val currentRoadways = roadwayChange._2.map(_.roadwayId).distinct.map(roadwayNumber => allRoadways.getOrElse(roadwayNumber, throw new Exception))
            applyTerminated(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue),
              currentRoadways, historyRoadways.filter(historyRoadway => historyRoadway._2.endDate.isDefined && currentRoadways.map(_.roadwayNumber).distinct.contains(historyRoadway._2.roadwayNumber)).values.toSeq)

          case AddressChangeType.Unknown =>
            applyNew(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue))
          case AddressChangeType.NotHandled =>
            applyNew(roadwayChange._1, roadwayChange._2.sortBy(_.startAddrMValue))
        }
    }
  }
}

