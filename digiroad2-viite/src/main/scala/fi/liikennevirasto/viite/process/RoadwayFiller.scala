package fi.liikennevirasto.viite.process


import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Subsequent}
import fi.liikennevirasto.viite.dao._
import org.joda.time.DateTime

object RoadwayFiller {
  private val roadwayAddressMapper = new RoadwayAddressMapper(new RoadwayDAO, new LinearLocationDAO)

  def generateNewRoadwaysWithHistory(changeSource: RoadwayChangeSection, changeTarget: RoadwayChangeSection, projectLinks: Seq[ProjectLink], currentRoadway: Roadway, projectStartDate: DateTime): Seq[Roadway] = {
    val roadwayNumber = projectLinks.head.roadwayNumber

    val historyRoadway = Roadway(NewIdValue, roadwayNumber, changeSource.roadNumber.get, changeSource.startRoadPartNumber.get, changeSource.roadType.get, Track.apply(changeSource.trackCode.get.toInt), changeSource.discontinuity.get,
      changeSource.startAddressM.get, changeSource.endAddressM.get, projectLinks.head.reversed, currentRoadway.startDate, Some(projectStartDate.minusDays(1)), createdBy = currentRoadway.createdBy, currentRoadway.roadName,
      currentRoadway.ely, NoTermination, currentRoadway.validFrom, currentRoadway.validTo)

    val newRoadway = Roadway(NewIdValue, roadwayNumber, changeTarget.roadNumber.get, changeTarget.startRoadPartNumber.get, changeTarget.roadType.get, Track.apply(changeTarget.trackCode.get.toInt), changeTarget.discontinuity.get,
      projectLinks.head.startAddrMValue, projectLinks.last.endAddrMValue, projectLinks.head.reversed, projectStartDate, None, createdBy = projectLinks.head.createdBy.get, currentRoadway.roadName,
      changeTarget.ely.get, NoTermination)

    Seq(historyRoadway, newRoadway)
  }

  private def applyUnchanged(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadways: Seq[Roadway],
                             historyRoadways: Seq[Roadway]): Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])] = {
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
          change.projectStartDate)
      } else {
        Seq(Roadway(NewIdValue, projectLinksInRoadway.head.roadwayNumber, changeTarget.roadNumber.get,
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
          Seq(Roadway(NewIdValue, roadways.head.roadwayNumber, historyRoadway.roadNumber, historyRoadway.roadPartNumber,
            historyRoadway.roadType, historyRoadway.track, historyRoadway.discontinuity, newStartAddressM, newEndAddressM,
            historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy,
            historyRoadway.roadName, historyRoadway.ely, NoTermination))
        } else {
          Seq(historyRoadway)
        }
      }

      (roadways ++ newHistoryRoadways, roadwayAddressMapper.mapLinearLocations(roadways.head, projectLinksInRoadway), projectLinksInRoadway.map(_.copy(roadwayNumber = roadways.head.roadwayNumber)))
    }
  }

  private def applyTransfer(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadways: Seq[Roadway], historyRoadways: Seq[Roadway]): Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])] = {
    val changeSource = change.changeInfo.source
    val changeTarget = change.changeInfo.target
    currentRoadways.map { currentRoadway =>
      val projectLinksInRoadway = projectLinks
        .filter(projectLink => projectLink.roadwayId == currentRoadway.id
          && projectLink.roadNumber == changeTarget.roadNumber.get
          && projectLink.roadPartNumber == changeTarget.startRoadPartNumber.get)
        .sortBy(_.startAddrMValue)
      val roadways = generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinksInRoadway, currentRoadway, change.projectStartDate)

      val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)

      val newHistoryRoadways = currentRoadwayHistoryRoadways.map { historyRoadway =>
        if (historyRoadway.endAddrMValue - historyRoadway.startAddrMValue != roadways.head.endAddrMValue - roadways.head.startAddrMValue) {
          val newStartAddressM = historyRoadway.startAddrMValue + roadways.head.startAddrMValue - currentRoadway.startAddrMValue
          val newEndAddressM = newStartAddressM + roadways.head.endAddrMValue - roadways.head.startAddrMValue
          Roadway(NewIdValue, roadways.head.roadwayNumber, historyRoadway.roadNumber, historyRoadway.roadPartNumber, historyRoadway.roadType, historyRoadway.track, historyRoadway.discontinuity,
            newStartAddressM, newEndAddressM, historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName,
            historyRoadway.ely, NoTermination)
        }
        else {
          historyRoadway
        }
      }
      (roadways ++ newHistoryRoadways, roadwayAddressMapper.mapLinearLocations(roadways.find(_.endDate.isEmpty).getOrElse(throw new Exception), projectLinksInRoadway), projectLinksInRoadway.map(_.copy(roadwayNumber = roadways.head.roadwayNumber)))
    }
  }

  private def applyTerminated(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadways: Seq[Roadway], historyRoadways: Seq[Roadway]): Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])] = {
    val sourceChange = change.changeInfo.source
    currentRoadways.map { currentRoadway =>
      val projectLinksInRoadway = projectLinks.filter(_.roadwayId == currentRoadway.id).sortBy(_.startAddrMValue)
      val newRoadwayNumber = if ((projectLinksInRoadway.last.endAddrMValue - projectLinksInRoadway.head.startAddrMValue) == (currentRoadway.endAddrMValue - currentRoadway.startAddrMValue)) currentRoadway.roadwayNumber else projectLinksInRoadway.head.roadwayNumber
      val roadway = currentRoadway.copy(id = NewIdValue, roadwayNumber = newRoadwayNumber, endDate = projectLinks.head.endDate, terminated = TerminationCode.Termination, startAddrMValue = sourceChange.startAddressM.get, endAddrMValue = sourceChange.endAddressM.get)
      val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)

      val newHistoryRoadways = currentRoadwayHistoryRoadways.map { historyRoadway =>
        val newStartAddressM = historyRoadway.startAddrMValue + roadway.startAddrMValue - currentRoadway.startAddrMValue
        val newEndAddressM = newStartAddressM + roadway.endAddrMValue - roadway.startAddrMValue
        if (historyRoadway.endAddrMValue - historyRoadway.startAddrMValue != roadway.endAddrMValue - roadway.startAddrMValue) {
          Roadway(NewIdValue, roadway.roadwayNumber, historyRoadway.roadNumber, historyRoadway.roadPartNumber, historyRoadway.roadType, historyRoadway.track, historyRoadway.discontinuity,
            newStartAddressM, newEndAddressM, historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName,
            historyRoadway.ely, Subsequent)
        }
        else {
          historyRoadway.copy(id = NewIdValue, terminated = Subsequent)
        }
      }

      (Seq(roadway) ++ newHistoryRoadways, roadwayAddressMapper.mapLinearLocations(roadway, projectLinks), projectLinksInRoadway.map(_.copy(roadwayNumber = roadway.roadwayNumber)))
    }
  }

  private def applyNew(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink]): Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])] = {
    val changeTarget = change.changeInfo.target
    val roadwayNumber = if (projectLinks.head.roadwayNumber == NewIdValue || projectLinks.head.roadwayNumber == 0) Sequences.nextRoadwayNumber else projectLinks.head.roadwayNumber
    val roadway = Roadway(NewIdValue, roadwayNumber, changeTarget.roadNumber.get, changeTarget.startRoadPartNumber.get, changeTarget.roadType.get, Track.apply(changeTarget.trackCode.get.toInt), changeTarget.discontinuity.get,
      changeTarget.startAddressM.get, changeTarget.endAddressM.get, change.changeInfo.reversed, startDate = projectLinks.head.startDate.get, endDate = projectLinks.head.endDate, createdBy = projectLinks.head.createdBy.get, projectLinks.head.roadName,
      projectLinks.head.ely, NoTermination)
    val projectLinksWithGivenAttributes = projectLinks.map(pl => pl.copy(roadwayNumber = roadway.roadwayNumber, linearLocationId = if(pl.linearLocationId == 0 || pl.linearLocationId == NewIdValue) Sequences.nextLinearLocationId else pl.linearLocationId))
    Seq((Seq(roadway), roadwayAddressMapper.mapLinearLocations(roadway, projectLinksWithGivenAttributes, newLink = true), projectLinksWithGivenAttributes))
  }

  private def applyNumbering(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadways: Seq[Roadway], historyRoadways: Seq[Roadway]): Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])] = {
    val changeSource = change.changeInfo.source
    val changeTarget = change.changeInfo.target
    currentRoadways.map { currentRoadway =>
      val projectLinksInRoadway = projectLinks.filter(_.roadwayId == currentRoadway.id).sortBy(_.startAddrMValue)
      val roadways = generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinksInRoadway, currentRoadway, change.projectStartDate)

      val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)
      val newHistoryRoadways = currentRoadwayHistoryRoadways.map { historyRoadway =>
        val newStartAddressM = historyRoadway.startAddrMValue + roadways.head.startAddrMValue - currentRoadway.startAddrMValue
        val newEndAddressM = newStartAddressM + roadways.head.endAddrMValue - roadways.head.startAddrMValue
        if (historyRoadway.endAddrMValue - historyRoadway.startAddrMValue != roadways.head.endAddrMValue - roadways.head.startAddrMValue) {
          Roadway(NewIdValue, roadways.head.roadwayNumber, historyRoadway.roadNumber, historyRoadway.roadPartNumber, historyRoadway.roadType, historyRoadway.track, historyRoadway.discontinuity,
            newStartAddressM, newEndAddressM, historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName,
            historyRoadway.ely, NoTermination)
        }
        else {
          historyRoadway
        }
      }
      (roadways ++ newHistoryRoadways, roadwayAddressMapper.mapLinearLocations(roadways.find(_.endDate.isEmpty).getOrElse(throw new Exception), projectLinksInRoadway), projectLinksInRoadway.map(_.copy(roadwayNumber = roadways.head.roadwayNumber)))
    }
  }

  def fillRoadways(allRoadways: Map[Long, Roadway], historyRoadways: Map[Long, Roadway], changesWithProjectLinks: Seq[(ProjectRoadwayChange, Seq[ProjectLink])]): Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])] = {
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

