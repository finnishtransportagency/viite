package fi.liikennevirasto.viite.process


import fi.liikennevirasto.digiroad2.asset.AdministrativeClass
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Subsequent}
import fi.liikennevirasto.viite.dao._
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

object RoadwayFiller {
  case class RwChanges(currentRoadway: Roadway, historyRoadways: Seq[Roadway], projectLinks: Seq[ProjectLink])

  val projectDAO = new ProjectDAO
  val logger = LoggerFactory.getLogger(getClass)

  def applyRoadwayChanges(rwChanges: Seq[RwChanges]): Seq[Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])]] = {
    def discontinuityChanged(pls: Seq[ProjectLink]) = {
      val maxLink = pls.maxBy(_.endAddrMValue)
      maxLink.discontinuity != maxLink.originalDiscontinuity
    }

    def trackChanged(pls: Seq[ProjectLink], currentRoadway: Roadway): Boolean = {
      val track = pls.groupBy(_.track)
      if (track.keySet.size != 1)
        logger.error("Multiple tracks on roadway.")
      track.keySet.head != currentRoadway.track
    }

    def adminClassChanged(currentRoadway: Roadway, adminClassRoadwayNumber: AdminClassRwn): Boolean = {
      adminClassRoadwayNumber.administrativeClass != currentRoadway.administrativeClass
    }

    def roadwayHasChanges(currentRoadway: Roadway,
                          adminClassRoadwayNumber: AdminClassRwn,
                          projectLinkSeq         : Seq[ProjectLink]
                         ): Boolean = {
                                        trackChanged(projectLinkSeq, currentRoadway) ||
                                        discontinuityChanged(projectLinkSeq) ||
                                        adminClassChanged(currentRoadway, adminClassRoadwayNumber) ||
                                        projectLinkSeq.exists(pl => pl.reversed)
    }

    def createRoadwaysWithLinearlocationsAndProjectLinks( currentRoadway: Roadway,
                                                          project       : Option[Project],
                                                          projectLinkSeq: Seq[ProjectLink]
                                                        ): GeneratedRoadway = {
      val generatedNewRoadways      = generateNewRoadwaysWithHistory2(projectLinkSeq, currentRoadway, project.get.startDate, projectLinkSeq.head.roadwayNumber)
      val (newRoadway, oldRoadway) = generatedNewRoadways.partition(_.endDate.isEmpty)
      val roadwaysWithLinearlocationsAndProjectLinkSeqs = newRoadway.map(nrw => {
        val projectLinksWithGivenAttributes = projectLinkSeq.map(pl => {
          pl.copy(linearLocationId = Sequences.nextLinearLocationId, roadwayNumber = nrw.roadwayNumber)
        })
        (Seq(nrw) ++ oldRoadway, roadwayAddressMapper.mapLinearLocations(nrw, projectLinksWithGivenAttributes), projectLinksWithGivenAttributes)
      })
      GeneratedRoadway(roadwaysWithLinearlocationsAndProjectLinkSeqs.flatMap(_._1),
                       roadwaysWithLinearlocationsAndProjectLinkSeqs.flatMap(_._2),
                       roadwaysWithLinearlocationsAndProjectLinkSeqs.flatMap(_._3)
                      )
    }

    rwChanges.map(changes => {
      val currentRoadway                   = changes.currentRoadway
      val historyRoadways                  = changes.historyRoadways
      val projectLinksInRoadway            = changes.projectLinks
      val (terminatedProjectLinks, others) = projectLinksInRoadway.partition(_.status == LinkStatus.Terminated)
      val elyChanged                       = if (others.nonEmpty) currentRoadway.ely != others.head.ely else false
      val addressChanged                   = if (others.nonEmpty) others.last.endAddrMValue != currentRoadway.endAddrMValue || (others.head.startAddrMValue) != currentRoadway.startAddrMValue else false
      val adminClassed                     = others.groupBy(pl => AdminClassRwn(pl.administrativeClass, pl.roadwayNumber))
      val project                          = projectDAO.fetchById(projectLinksInRoadway.head.projectId)

      val roadways = adminClassed.map{ case (adminClassRoadwayNumber, projectLinkSeq) => {
        if (roadwayHasChanges(currentRoadway, adminClassRoadwayNumber, projectLinkSeq) ||
            elyChanged ||
            addressChanged)
          createRoadwaysWithLinearlocationsAndProjectLinks(currentRoadway, project, projectLinkSeq)
        else if (projectLinkSeq.nonEmpty) {
          val headPl                          = projectLinkSeq.head
          val lastPl                          = projectLinkSeq.last
          val existingRoadway                 = Seq(Roadway(NewIdValue, headPl.roadwayNumber, headPl.roadNumber, headPl.roadPartNumber, headPl.administrativeClass, headPl.track, lastPl.discontinuity, headPl.startAddrMValue, lastPl.endAddrMValue, headPl.reversed, currentRoadway.startDate, None, createdBy = headPl.createdBy.get, currentRoadway.roadName, headPl.ely, NoTermination)) ++ historyRoadways.toList
          val projectLinksWithGivenAttributes = projectLinkSeq.map(pl =>
            pl.copy(linearLocationId = Sequences.nextLinearLocationId, roadwayNumber = existingRoadway.head.roadwayNumber)
          )
          GeneratedRoadway(existingRoadway, roadwayAddressMapper.mapLinearLocations(existingRoadway.head, projectLinksWithGivenAttributes), projectLinksWithGivenAttributes)
        } else GeneratedRoadway(Seq(), Seq(), Seq())
      }}.toSeq

      val roadwaysWithLinearlocations = (roadways.flatMap(_.roadway), roadways.flatMap(_.linearLocations), roadways.flatMap(_.projectLinks))
      val historyRowsOfTerminatedRoadway = terminatedHistory(historyRoadways, currentRoadway, terminatedProjectLinks)
      val oldTerminatedRoadway = historyRowsOfTerminatedRoadway.find(_.terminated == TerminationCode.Termination)

      val createdTerminatedHistoryRoadways = if (oldTerminatedRoadway.isDefined) {
        val terminatedProjectLinksWithGivenAttributes = terminatedProjectLinks.map(pl => {
          pl.copy(linearLocationId = Sequences.nextLinearLocationId, roadwayNumber = oldTerminatedRoadway.get.roadwayNumber)
        })
        (historyRowsOfTerminatedRoadway, roadwayAddressMapper.mapLinearLocations(oldTerminatedRoadway.get, terminatedProjectLinksWithGivenAttributes), terminatedProjectLinksWithGivenAttributes)
      } else (Seq(), Seq(), Seq())

      val existingHistoryRoadways = if (projectLinksInRoadway.forall(pl => pl.status != LinkStatus.Terminated)) {
        (historyRoadways.map(rw => rw.copy(id = NewIdValue, roadwayNumber = roadwaysWithLinearlocations._1.head.roadwayNumber)), Seq(), Seq())
      } else {
        (Seq(), Seq(), Seq())
      }

      Seq(roadwaysWithLinearlocations, createdTerminatedHistoryRoadways, existingHistoryRoadways)
    })
  }

  def applyNewLinks(projectLinks: Seq[ProjectLink]): List[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])] = {
    val rwGroupedProjectLinks = projectLinks.groupBy(_.roadwayNumber)
    rwGroupedProjectLinks.map { case (roadwayNumber, pls) =>
      val head_pl                         = pls.head
      val roadway                         = Roadway(NewIdValue, roadwayNumber, head_pl.roadNumber, head_pl.roadPartNumber, head_pl.administrativeClass, head_pl.track, pls.last.discontinuity, head_pl.startAddrMValue, pls.last.endAddrMValue, head_pl.reversed, startDate = head_pl.startDate.get, endDate = head_pl.endDate, createdBy = head_pl.createdBy.get, head_pl.roadName, head_pl.ely, NoTermination)
      val projectLinksWithGivenAttributes = pls.map(pl => {
        pl.copy(linearLocationId = if (pl.linearLocationId == 0 || pl.linearLocationId == NewIdValue) Sequences.nextLinearLocationId else pl.linearLocationId, roadwayNumber = roadway.roadwayNumber)
      })
      (Seq(roadway), roadwayAddressMapper.mapLinearLocations(roadway, projectLinksWithGivenAttributes), projectLinksWithGivenAttributes)
    }.toList
  }

  def generateNewRoadwaysWithHistory2(projectLinks    : Seq[ProjectLink],
                                      currentRoadway  : Roadway,
                                      projectStartDate: DateTime,
                                      roadwayNumber   : Long
                                     ): Seq[Roadway] = {
    val headProjectLink  = projectLinks.head
    val lastProjectLink  = projectLinks.last
    val reversed         = projectLinks.forall(pl => pl.reversed)
    val newStartAddressM = if (reversed) lastProjectLink.originalStartAddrMValue else headProjectLink.originalStartAddrMValue
    val newEndAddressM   = if (reversed) headProjectLink.originalEndAddrMValue else lastProjectLink.originalEndAddrMValue
    val oldAdministrativeClass = headProjectLink.originalAdministrativeClass

    val historyRoadway   = Roadway(
                            NewIdValue,
                            roadwayNumber,
                            currentRoadway.roadNumber,
                            currentRoadway.roadPartNumber,
                            oldAdministrativeClass,
                            currentRoadway.track,
                            lastProjectLink.originalDiscontinuity,
                            newStartAddressM,
                            newEndAddressM,
                            reversed,
                            currentRoadway.startDate,
                            Some(projectStartDate.minusDays(1)),
                            createdBy = currentRoadway.createdBy,
                            currentRoadway.roadName,
                            currentRoadway.ely,
                            NoTermination,
                            currentRoadway.validFrom,
                            currentRoadway.validTo
                          )
    val newRoadway       = Roadway(
                            NewIdValue,
                            roadwayNumber,
                            headProjectLink.roadNumber,
                            headProjectLink.roadPartNumber,
                            headProjectLink.administrativeClass,
                            headProjectLink.track,
                            projectLinks.last.discontinuity,
                            projectLinks.head.startAddrMValue,
                            projectLinks.last.endAddrMValue,
                            headProjectLink.reversed,
                            projectStartDate,
                            None,
                            createdBy = headProjectLink.createdBy.get,
                            currentRoadway.roadName,
                            headProjectLink.ely,
                            NoTermination
                          )
    Seq(historyRoadway, newRoadway)
  }

  private def terminatedHistory(
                                 historyRoadways      : Seq[Roadway],
                                 currentRoadway       : Roadway,
                                 terminatedProjectLinksInRoadway: Seq[ProjectLink]
                               ): Seq[Roadway] = {
    if (terminatedProjectLinksInRoadway.isEmpty) Seq.empty[Roadway] else {
      val continuousParts   = terminatedProjectLinksInRoadway.tail.foldLeft((Seq(Seq.empty[ProjectLink]), Seq(terminatedProjectLinksInRoadway.head))) { (x, y) =>
        if (x._2.last.endAddrMValue == y.startAddrMValue) (x._1, x._2 :+ y) else (x._1 :+ x._2, Seq(y))
      }
      val continuousGrouped = (continuousParts._1 :+ continuousParts._2).tail
      continuousGrouped.map(pls => {
        val newRoadwayNumber              = if ((pls.last.endAddrMValue - pls.head.startAddrMValue) == (currentRoadway.endAddrMValue - currentRoadway.startAddrMValue)) currentRoadway.roadwayNumber else pls.head.roadwayNumber
        val roadway                       = currentRoadway.copy(id = NewIdValue, roadwayNumber = newRoadwayNumber, endDate = pls.head.endDate, terminated = TerminationCode.Termination, startAddrMValue = pls.head.startAddrMValue, endAddrMValue = pls.last.endAddrMValue, discontinuity = pls.last.discontinuity)
        val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)

        val newHistoryRoadways = currentRoadwayHistoryRoadways.map { historyRoadway =>
          val newStartAddressM = historyRoadway.startAddrMValue + roadway.startAddrMValue - currentRoadway.startAddrMValue
          val newEndAddressM   = newStartAddressM + roadway.endAddrMValue - roadway.startAddrMValue
          if (historyRoadway.endAddrMValue - historyRoadway.startAddrMValue != roadway.endAddrMValue - roadway.startAddrMValue) {
            Roadway(NewIdValue, roadway.roadwayNumber, historyRoadway.roadNumber, historyRoadway.roadPartNumber, historyRoadway.administrativeClass, historyRoadway.track, historyRoadway.discontinuity, newStartAddressM, newEndAddressM, historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName, historyRoadway.ely, Subsequent)
          } else {
            historyRoadway.copy(id = NewIdValue, terminated = Subsequent)
          }
        }
        (Seq(roadway), newHistoryRoadways)
      }).flatMap(r => {
        r._1 ++ r._2
      })
    }
  }

  private val roadwayAddressMapper = new RoadwayAddressMapper(new RoadwayDAO, new LinearLocationDAO)

  def generateNewRoadwaysWithHistory(changeSource: RoadwayChangeSection, changeTarget: RoadwayChangeSection, projectLinks: Seq[ProjectLink], currentRoadway: Roadway, projectStartDate: DateTime): Seq[Roadway] = {
    val roadwayNumber = projectLinks.head.roadwayNumber
    val historyRoadway = Roadway(NewIdValue, roadwayNumber, changeSource.roadNumber.get, changeSource.startRoadPartNumber.get, changeSource.administrativeClass.get, Track.apply(changeSource.trackCode.get.toInt), changeSource.discontinuity.get, changeSource.startAddressM.get, changeSource.endAddressM.get, projectLinks.head.reversed, currentRoadway.startDate, Some(projectStartDate.minusDays(1)), createdBy = currentRoadway.createdBy, currentRoadway.roadName, currentRoadway.ely, NoTermination, currentRoadway.validFrom, currentRoadway.validTo)
    val newRoadway =     Roadway(NewIdValue, roadwayNumber, changeTarget.roadNumber.get, changeTarget.startRoadPartNumber.get, changeTarget.administrativeClass.get, Track.apply(changeTarget.trackCode.get.toInt), changeTarget.discontinuity.get, projectLinks.head.startAddrMValue, projectLinks.last.endAddrMValue, projectLinks.head.reversed, projectStartDate, None, createdBy = projectLinks.head.createdBy.get, currentRoadway.roadName, changeTarget.ely.get, NoTermination)
    Seq(historyRoadway, newRoadway)
  }

  private def applyUnchanged(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadways: Seq[Roadway],
                             historyRoadways: Seq[Roadway]): Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])] = {
    val changeSource = change.changeInfo.source
    val changeTarget = change.changeInfo.target
    currentRoadways.map { currentRoadway =>
      val projectLinksInRoadway = projectLinks.filter(_.roadwayId == currentRoadway.id).sortBy(_.startAddrMValue)
      val administrativeClassDiscontinuityOrElyChanged = currentRoadway.administrativeClass != changeTarget.administrativeClass.get ||
        currentRoadway.discontinuity != changeTarget.discontinuity.get || currentRoadway.ely != changeTarget.ely.get
      val lengthChanged = currentRoadway.startAddrMValue != projectLinksInRoadway.head.startAddrMValue ||
                    currentRoadway.endAddrMValue != projectLinksInRoadway.last.endAddrMValue
      val roadways = if (administrativeClassDiscontinuityOrElyChanged || lengthChanged) {
        generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinksInRoadway, currentRoadway,
          change.projectStartDate)
      } else {
        Seq(Roadway(NewIdValue, projectLinksInRoadway.head.roadwayNumber, changeTarget.roadNumber.get, changeTarget.startRoadPartNumber.get, changeTarget.administrativeClass.get, Track.apply(changeTarget.trackCode.get.toInt), changeTarget.discontinuity.get, projectLinks.head.startAddrMValue, projectLinks.last.endAddrMValue, projectLinks.head.reversed, currentRoadway.startDate, None, createdBy = projectLinks.head.createdBy.get, currentRoadway.roadName, changeTarget.ely.get, NoTermination))
      }

      val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)
      val newHistoryRoadways = currentRoadwayHistoryRoadways.flatMap { historyRoadway =>
        val newStartAddressM = historyRoadway.startAddrMValue + roadways.head.startAddrMValue - currentRoadway.startAddrMValue
        val newEndAddressM = newStartAddressM + roadways.head.endAddrMValue - roadways.head.startAddrMValue
        if (historyRoadway.endAddrMValue - historyRoadway.startAddrMValue != roadways.head.endAddrMValue - roadways.head.startAddrMValue) {
          Seq(Roadway(NewIdValue, roadways.head.roadwayNumber, historyRoadway.roadNumber, historyRoadway.roadPartNumber, historyRoadway.administrativeClass, historyRoadway.track, historyRoadway.discontinuity, newStartAddressM, newEndAddressM, historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName, historyRoadway.ely, NoTermination))
        } else {
          Seq(historyRoadway)
        }
      }
      val projectLinksWithGivenAttributes = projectLinks.map(pl => pl.copy(linearLocationId = Sequences.nextLinearLocationId, roadwayNumber = roadways.head.roadwayNumber))
      (roadways ++ newHistoryRoadways, roadwayAddressMapper.mapLinearLocations(roadways.head, projectLinksWithGivenAttributes), projectLinksWithGivenAttributes)
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
          Roadway(NewIdValue, roadways.head.roadwayNumber, historyRoadway.roadNumber, historyRoadway.roadPartNumber, historyRoadway.administrativeClass, historyRoadway.track, historyRoadway.discontinuity, newStartAddressM, newEndAddressM, historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName, historyRoadway.ely, NoTermination)
        }
        else {
          historyRoadway
        }
      }
      val projectLinksWithGivenAttributes = projectLinks.map(pl => pl.copy(linearLocationId = Sequences.nextLinearLocationId, roadwayNumber = roadways.head.roadwayNumber))
      (roadways ++ newHistoryRoadways, roadwayAddressMapper.mapLinearLocations(roadways.find(_.endDate.isEmpty).getOrElse(throw new Exception), projectLinksWithGivenAttributes), projectLinksWithGivenAttributes)
    }
  }

  private def applyTerminated(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadways: Seq[Roadway], historyRoadways: Seq[Roadway]): Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])] = {
    val sourceChange = change.changeInfo.source
    currentRoadways.map { currentRoadway =>
      val projectLinksInRoadway = projectLinks.filter(_.roadwayId == currentRoadway.id).sortBy(_.startAddrMValue)
      val newRoadwayNumber = if ((projectLinksInRoadway.last.endAddrMValue - projectLinksInRoadway.head.startAddrMValue) == (currentRoadway.endAddrMValue - currentRoadway.startAddrMValue)) currentRoadway.roadwayNumber else projectLinksInRoadway.head.roadwayNumber
      val roadway = currentRoadway.copy(id = NewIdValue, roadwayNumber = newRoadwayNumber, endDate = projectLinks.head.endDate, terminated = TerminationCode.Termination, startAddrMValue = sourceChange.startAddressM.get, endAddrMValue = sourceChange.endAddressM.get, discontinuity = projectLinksInRoadway.last.discontinuity)
      val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)

      val newHistoryRoadways = currentRoadwayHistoryRoadways.map { historyRoadway =>
        val newStartAddressM = historyRoadway.startAddrMValue + roadway.startAddrMValue - currentRoadway.startAddrMValue
        val newEndAddressM = newStartAddressM + roadway.endAddrMValue - roadway.startAddrMValue
        if (historyRoadway.endAddrMValue - historyRoadway.startAddrMValue != roadway.endAddrMValue - roadway.startAddrMValue) {
          Roadway(NewIdValue, roadway.roadwayNumber, historyRoadway.roadNumber, historyRoadway.roadPartNumber, historyRoadway.administrativeClass, historyRoadway.track, historyRoadway.discontinuity, newStartAddressM, newEndAddressM, historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName, historyRoadway.ely, Subsequent)
        }
        else {
          historyRoadway.copy(id = NewIdValue, terminated = Subsequent)
        }
      }
      val projectLinksWithGivenAttributes = projectLinks.map(pl => pl.copy(linearLocationId = Sequences.nextLinearLocationId, roadwayNumber = roadway.roadwayNumber))
      (Seq(roadway) ++ newHistoryRoadways, roadwayAddressMapper.mapLinearLocations(roadway, projectLinksWithGivenAttributes), projectLinksWithGivenAttributes)
    }
  }

  private def applyNew(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink]): Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])] = {
    val changeTarget = change.changeInfo.target
    val roadwayNumber = if (projectLinks.head.roadwayNumber == NewIdValue || projectLinks.head.roadwayNumber == 0) Sequences.nextRoadwayNumber else projectLinks.head.roadwayNumber
    val roadway = Roadway(NewIdValue, roadwayNumber, changeTarget.roadNumber.get, changeTarget.startRoadPartNumber.get, changeTarget.administrativeClass.get, Track.apply(changeTarget.trackCode.get.toInt), changeTarget.discontinuity.get, changeTarget.startAddressM.get, changeTarget.endAddressM.get, change.changeInfo.reversed, startDate = projectLinks.head.startDate.get, endDate = projectLinks.head.endDate, createdBy = projectLinks.head.createdBy.get, projectLinks.head.roadName, projectLinks.head.ely, NoTermination)
   val projectLinksWithGivenAttributes = projectLinks.map(pl => pl.copy(linearLocationId = if(pl.linearLocationId == 0 || pl.linearLocationId == NewIdValue) Sequences.nextLinearLocationId else pl.linearLocationId, roadwayNumber = roadway.roadwayNumber))
    Seq((Seq(roadway), roadwayAddressMapper.mapLinearLocations(roadway, projectLinksWithGivenAttributes), projectLinksWithGivenAttributes))
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
          Roadway(NewIdValue, roadways.head.roadwayNumber, historyRoadway.roadNumber, historyRoadway.roadPartNumber, historyRoadway.administrativeClass, historyRoadway.track, historyRoadway.discontinuity, newStartAddressM, newEndAddressM, historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName, historyRoadway.ely, NoTermination)
        }
        else {
          historyRoadway
        }
      }
      val projectLinksWithGivenAttributes = projectLinks.map(pl => pl.copy(linearLocationId = Sequences.nextLinearLocationId, roadwayNumber = roadways.head.roadwayNumber))
      (roadways ++ newHistoryRoadways, roadwayAddressMapper.mapLinearLocations(roadways.find(_.endDate.isEmpty).getOrElse(throw new Exception), projectLinksWithGivenAttributes), projectLinksWithGivenAttributes)
    }
  }

  def mergeRoadwayChanges(changesWithLinks: Seq[(ProjectRoadwayChange, Seq[ProjectLink])]): Seq[(ProjectRoadwayChange, Seq[ProjectLink])] = {
    def groupedSections(changes: Seq[(ProjectRoadwayChange, Seq[ProjectLink])]) = {
      changes.groupBy(c => (c._1.changeInfo.source.roadNumber, c._1.changeInfo.source.startRoadPartNumber, c._1.changeInfo.source.trackCode, c._1.changeInfo.source.administrativeClass, c._1.changeInfo.source.ely,
        c._1.changeInfo.target.roadNumber, c._1.changeInfo.target.startRoadPartNumber, c._1.changeInfo.target.trackCode, c._1.changeInfo.target.administrativeClass, c._1.changeInfo.target.ely))
        .flatMap {
        case (_, section) =>
          val sortedSections = section.sortBy(s => (s._1.changeInfo.changeType.value, s._1.changeInfo.target.startAddressM))
          sortedSections.foldLeft(Seq.empty[(ProjectRoadwayChange, Seq[ProjectLink])]) {(changeList, section) =>
            if (changeList.isEmpty)
              Seq(section)
            else if (changeList.last._1.changeInfo.target.endAddressM == section._1.changeInfo.target.startAddressM &&
              changeList.last._2.head.roadwayNumber == section._2.head.roadwayNumber) {
                val adjustedSource = changeList.last._1.changeInfo.source.copy(endAddressM = section._1.changeInfo.source.endAddressM)
                val adjustedTarget = changeList.last._1.changeInfo.target.copy(endAddressM = section._1.changeInfo.target.endAddressM)
                val lastChangeInfo = changeList.last._1.changeInfo.copy(source = adjustedSource, target = adjustedTarget, discontinuity = section._1.changeInfo.discontinuity)
                changeList.init :+ (changeList.last._1.copy(changeInfo = lastChangeInfo), changeList.last._2 ++ section._2)
            }
            else changeList :+ section
          }
        case _ => Seq.empty[(ProjectRoadwayChange, Seq[ProjectLink])]
      }
    }

    val (operationsToCheck, rest) = changesWithLinks.partition(c => List(AddressChangeType.Unchanged, AddressChangeType.Transfer, AddressChangeType.ReNumeration, AddressChangeType.Termination).contains(c._1.changeInfo.changeType))
    (groupedSections(operationsToCheck).toSeq ++ rest).sortBy(_._1.changeInfo.orderInChangeTable)
  }


  case class AdminClassRwn(administrativeClass  : AdministrativeClass, roadwayNumber: Long)
  case class GeneratedRoadway(roadway: Seq[Roadway], linearLocations: Seq[LinearLocation], projectLinks: Seq[ProjectLink])
}

