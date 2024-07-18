package fi.liikennevirasto.viite.process

import fi.liikennevirasto.viite.NewIdValue
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Subsequent}
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, Discontinuity, RoadAddressChangeType, RoadPart, Track}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

object RoadwayFiller {
  case class RwChanges(currentRoadway: Roadway, historyRoadways: Seq[Roadway], projectLinks: Seq[ProjectLink])

  val projectDAO = new ProjectDAO
  private val logger = LoggerFactory.getLogger(getClass)


  /**
   * This function adjusts the historyRows to have appropriate AddrMValues based on the Project Links that are used to create a new Roadway.
   * @param projectLinks The ProjectLinks that make up a new roadway
   *                       70------100
   *                               100------150
   * @param currentRoadway The roadway that the ProjectLinks are originally from
   *    4         0-------------------------150
   * @param historyRows History Rows from the Roadway table for the currentRoadway
   *    3         0-------------------------150
   *    2         0-------------------------150
   *    1         230-----------------------380
   * @return historyRows that have adjusted AddrMValues based on the ProjectLinks
   *    3                  70---------------150
   *    2                  70---------------150
   *    1                  300--------------380
   */
  def updateAddrMValuesOfHistoryRows(projectLinks: Seq[ProjectLink], currentRoadway: Roadway, historyRows: Seq[Roadway]):Seq[Roadway] = {

    //TODO minBy, maxBy -> map().min
    val plMinAddrM = projectLinks.minBy(_.originalAddrMRange.start).originalAddrMRange.start
    val plMaxAddrM = projectLinks.maxBy(_.originalAddrMRange.end).originalAddrMRange.end

    val minAddrM = plMinAddrM - currentRoadway.addrMRange.start
    val maxAddrM = plMaxAddrM - currentRoadway.addrMRange.start

    historyRows.map(hr => {
      if (plMaxAddrM != currentRoadway.addrMRange.end) {
        //The roadway has been split and we're handling history rows that aren't at the end of the roadway so the discontinuity has to be Continuous
        hr.copy(addrMRange = AddrMRange(hr.addrMRange.start + minAddrM, hr.addrMRange.start + maxAddrM), discontinuity = Discontinuity.Continuous)
      } else {
        hr.copy(addrMRange = AddrMRange(hr.addrMRange.start + minAddrM, hr.addrMRange.start + maxAddrM))
      }
    })
  }

  def applyRoadwayChanges(rwChanges: Seq[RwChanges]): Seq[Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])]] = {
    def discontinuityChanged(pls: Seq[ProjectLink]) = {
      val maxLink = pls.maxBy(_.addrMRange.end)
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

    def roadNumberChanged(currentRoadway: Roadway, pls: Seq[ProjectLink]): Boolean = {
      pls.exists(pl => pl.roadPart.roadNumber != currentRoadway.roadPart.roadNumber)
    }

    def roadPartNumberChanged(currentRoadway: Roadway, pls: Seq[ProjectLink]): Boolean = {
      pls.exists(pl => pl.roadPart.roadNumber == currentRoadway.roadPart.roadNumber && pl.roadPart.partNumber != currentRoadway.roadPart.partNumber)
    }

    def roadwayHasChanges(currentRoadway: Roadway,
                          adminClassRoadwayNumber: AdminClassRwn,
                          projectLinkSeq         : Seq[ProjectLink]
                         ): Boolean = {
                                        trackChanged(projectLinkSeq, currentRoadway) ||
                                        discontinuityChanged(projectLinkSeq) ||
                                        adminClassChanged(currentRoadway, adminClassRoadwayNumber) ||
                                        projectLinkSeq.exists(pl => pl.reversed) ||
                                        roadNumberChanged(currentRoadway, projectLinkSeq) ||
                                        roadPartNumberChanged(currentRoadway, projectLinkSeq)
    }

    def createRoadwaysWithLinearlocationsAndProjectLinks( currentRoadway: Roadway,
                                                          project       : Option[Project],
                                                          projectLinkSeq: Seq[ProjectLink],
                                                          historyRoadways: Seq[Roadway]
                                                        ): GeneratedRoadway = {
      val generatedNewRoadways     = generateNewRoadwaysWithHistory2(projectLinkSeq, currentRoadway, project.get.startDate, projectLinkSeq.head.roadwayNumber,historyRoadways)
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
      val currentRoadway                = changes.currentRoadway
      val historyRoadways: Seq[Roadway] = changes.historyRoadways
      val projectLinksInRoadway         = changes.projectLinks
      val (terminatedProjectLinks, others) = projectLinksInRoadway.partition(_.status == RoadAddressChangeType.Termination)
      val elyChanged                       = if (others.nonEmpty) currentRoadway.ely != others.head.ely else false
      val addressChanged                   = if (others.nonEmpty) others.last.addrMRange.end != currentRoadway.addrMRange.end || others.head.addrMRange.start != currentRoadway.addrMRange.start else false
      val adminClassed                     = others.groupBy(pl => AdminClassRwn(pl.administrativeClass, pl.roadwayNumber))
      val project                          = projectDAO.fetchById(projectLinksInRoadway.head.projectId)

      val roadways = adminClassed.map { case (adminClassRoadwayNumber, projectLinkSeq) =>
        if (roadwayHasChanges(currentRoadway, adminClassRoadwayNumber, projectLinkSeq) || elyChanged || addressChanged) {
            createRoadwaysWithLinearlocationsAndProjectLinks(currentRoadway, project, projectLinkSeq, historyRoadways)
        } else if (projectLinkSeq.nonEmpty) {
          val headPl                          = projectLinkSeq.head
          val lastPl                          = projectLinkSeq.last
          val existingRoadway = Seq(Roadway(NewIdValue, headPl.roadwayNumber, headPl.roadPart, headPl.administrativeClass, headPl.track, lastPl.discontinuity, AddrMRange(headPl.addrMRange.start, lastPl.addrMRange.end), headPl.reversed, currentRoadway.startDate, None, createdBy = headPl.createdBy.get, currentRoadway.roadName, headPl.ely, NoTermination)) ++ updateAddrMValuesOfHistoryRows(projectLinkSeq, currentRoadway, historyRoadways).map { historyRoadway =>
            historyRoadway.copy(id = NewIdValue, roadwayNumber = headPl.roadwayNumber)
          }

          val projectLinksWithGivenAttributes = projectLinkSeq.map(pl =>
            pl.copy(linearLocationId = Sequences.nextLinearLocationId, roadwayNumber = existingRoadway.head.roadwayNumber)
          )
          GeneratedRoadway(existingRoadway, roadwayAddressMapper.mapLinearLocations(existingRoadway.head, projectLinksWithGivenAttributes), projectLinksWithGivenAttributes)
        } else GeneratedRoadway(Seq(), Seq(), Seq())
      }.toSeq

      val roadwaysWithLinearlocations = (roadways.flatMap(_.roadway).distinct, roadways.flatMap(_.linearLocations), roadways.flatMap(_.projectLinks))
      val historyRowsOfTerminatedRoadway = terminatedHistory(historyRoadways, currentRoadway, terminatedProjectLinks)
      val oldTerminatedRoadway = historyRowsOfTerminatedRoadway.find(_.terminated == TerminationCode.Termination)

      val createdTerminatedHistoryRoadways = if (oldTerminatedRoadway.isDefined) {
        val terminatedProjectLinksWithGivenAttributes = terminatedProjectLinks.map(pl => {
          pl.copy(linearLocationId = Sequences.nextLinearLocationId, roadwayNumber = oldTerminatedRoadway.get.roadwayNumber)
        })
        (historyRowsOfTerminatedRoadway, roadwayAddressMapper.mapLinearLocations(oldTerminatedRoadway.get, terminatedProjectLinksWithGivenAttributes), terminatedProjectLinksWithGivenAttributes)
      } else (Seq(), Seq(), Seq())

      Seq(roadwaysWithLinearlocations, createdTerminatedHistoryRoadways)
    })
  }

  def applyNewLinks(projectLinks: Seq[ProjectLink]): List[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])] = {
    val rwGroupedProjectLinks = projectLinks.groupBy(_.roadwayNumber)
    rwGroupedProjectLinks.map { case (roadwayNumber, pls) =>
      val head_pl                         = pls.head
      val roadway                         = Roadway(NewIdValue, roadwayNumber, head_pl.roadPart, head_pl.administrativeClass, head_pl.track, pls.last.discontinuity, AddrMRange(head_pl.addrMRange.start, pls.last.addrMRange.end), head_pl.reversed, startDate = head_pl.startDate.get, endDate = head_pl.endDate, createdBy = head_pl.createdBy.get, head_pl.roadName, head_pl.ely, NoTermination)
      val projectLinksWithGivenAttributes = pls.map(pl => {
        pl.copy(linearLocationId = if (pl.linearLocationId == 0 || pl.linearLocationId == NewIdValue) Sequences.nextLinearLocationId else pl.linearLocationId, roadwayNumber = roadway.roadwayNumber)
      })
      (Seq(roadway), roadwayAddressMapper.mapLinearLocations(roadway, projectLinksWithGivenAttributes), projectLinksWithGivenAttributes)
    }.toList
  }

  def generateNewRoadwaysWithHistory2(projectLinks    : Seq[ProjectLink],
                                      currentRoadway  : Roadway,
                                      projectStartDate: DateTime,
                                      roadwayNumber   : Long,
                                      historyRoadways: Seq[Roadway]
                                     ): Seq[Roadway] = {
    val headProjectLink  = projectLinks.head
    val lastProjectLink  = projectLinks.last
    val reversed         = projectLinks.forall(pl => pl.reversed)
    val newStartAddressM = if (reversed) lastProjectLink.originalAddrMRange.start else headProjectLink.originalAddrMRange.start
    val newEndAddressM   = if (reversed) headProjectLink.originalAddrMRange.end   else lastProjectLink.originalAddrMRange.end
    val oldAdministrativeClass = headProjectLink.originalAdministrativeClass
    val noChanges        =  headProjectLink.roadPart == currentRoadway.roadPart &&
                            headProjectLink.track == currentRoadway.track &&
                            headProjectLink.addrMRange.start == newStartAddressM &&
                            lastProjectLink.addrMRange.end == newEndAddressM &&
                            !reversed &&
                            lastProjectLink.discontinuity == lastProjectLink.originalDiscontinuity &&
                            headProjectLink.administrativeClass == oldAdministrativeClass &&
                            headProjectLink.ely == currentRoadway.ely

    val newRoadwayStartDate = if (noChanges) currentRoadway.startDate else projectStartDate

    val newRoadway: Roadway = Roadway(
                            NewIdValue,
                            roadwayNumber,
                            headProjectLink.roadPart,
                            headProjectLink.administrativeClass,
                            headProjectLink.track,
                            lastProjectLink.discontinuity,
                            AddrMRange(headProjectLink.addrMRange.start, lastProjectLink.addrMRange.end),
                            false,
                            newRoadwayStartDate,
                            None,
                            createdBy = headProjectLink.createdBy.get,
                            currentRoadway.roadName,
                            headProjectLink.ely,
                            NoTermination
                          )

    val historyRoadway: Seq[Roadway] = {
      if (reversed) {
        currentRoadway.copy(id = NewIdValue, endDate = Some(projectStartDate.minusDays(1)), reversed = true) +: historyRoadways.map(hr => {
          hr.copy(id = NewIdValue, reversed = !hr.reversed)
        })
      }
      else if (noChanges) {
        // if there is no need for new history row then return empty Seq() and add existing history to it
        Seq() ++ updateAddrMValuesOfHistoryRows(projectLinks, currentRoadway, historyRoadways).map { historyRoadway =>
          historyRoadway.copy(id = NewIdValue, roadwayNumber = newRoadway.roadwayNumber, createdBy = currentRoadway.createdBy, validFrom = newRoadway.validFrom)
        }
      }
      else {
        // create new history row and add existing history to it
        Seq(Roadway(NewIdValue, roadwayNumber, currentRoadway.roadPart, oldAdministrativeClass, currentRoadway.track, lastProjectLink.originalDiscontinuity, AddrMRange(newStartAddressM, newEndAddressM), reversed, currentRoadway.startDate, Some(projectStartDate.minusDays(1)), createdBy = currentRoadway.createdBy, currentRoadway.roadName, currentRoadway.ely, NoTermination, currentRoadway.validFrom, currentRoadway.validTo)) ++ updateAddrMValuesOfHistoryRows(projectLinks, currentRoadway, historyRoadways).map { historyRoadway =>
          historyRoadway.copy(id = NewIdValue, roadwayNumber = newRoadway.roadwayNumber, createdBy = currentRoadway.createdBy, validFrom = newRoadway.validFrom)
        }
      }
    }

    newRoadway +: historyRoadway
  }

  private def terminatedHistory(
                                 historyRoadways      : Seq[Roadway],
                                 currentRoadway       : Roadway,
                                 terminatedProjectLinksInRoadway: Seq[ProjectLink]
                               ): Seq[Roadway] = {
    if (terminatedProjectLinksInRoadway.isEmpty) Seq.empty[Roadway] else {
      val continuousParts   = terminatedProjectLinksInRoadway.tail.foldLeft((Seq(Seq.empty[ProjectLink]), Seq(terminatedProjectLinksInRoadway.head))) { (x, y) =>
        if (x._2.last.addrMRange.end == y.addrMRange.start) (x._1, x._2 :+ y) else (x._1 :+ x._2, Seq(y))
      }
      val continuousGrouped = (continuousParts._1 :+ continuousParts._2).tail
      continuousGrouped.map(pls => {
        val newRoadwayNumber              = if ((pls.last.addrMRange.end - pls.head.addrMRange.start) == (currentRoadway.addrMRange.end - currentRoadway.addrMRange.start)) currentRoadway.roadwayNumber else pls.head.roadwayNumber
        val roadway                       = currentRoadway.copy(id = NewIdValue, roadwayNumber = newRoadwayNumber, endDate = pls.head.endDate, terminated = TerminationCode.Termination, addrMRange= AddrMRange(pls.head.addrMRange.start, pls.last.addrMRange.end), discontinuity = pls.last.discontinuity)
        val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)

        val newHistoryRoadways = currentRoadwayHistoryRoadways.map { historyRoadway =>
          val newStartAddressM = historyRoadway.addrMRange.start + roadway.addrMRange.start - currentRoadway.addrMRange.start
          val newEndAddressM   = newStartAddressM + roadway.addrMRange.end - roadway.addrMRange.start
          if (historyRoadway.addrMRange.end - historyRoadway.addrMRange.start != roadway.addrMRange.end - roadway.addrMRange.start) {
            Roadway(NewIdValue, roadway.roadwayNumber, historyRoadway.roadPart, historyRoadway.administrativeClass, historyRoadway.track, roadway.discontinuity, AddrMRange(newStartAddressM, newEndAddressM), historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName, historyRoadway.ely, Subsequent)
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
    val sourceRoadPart = RoadPart(changeSource.roadNumber.get, changeSource.startRoadPartNumber.get)
    val targetRoadPart = RoadPart(changeTarget.roadNumber.get, changeTarget.startRoadPartNumber.get)
    val historyRoadway = Roadway(NewIdValue, roadwayNumber, sourceRoadPart, changeSource.administrativeClass.get, Track.apply(changeSource.trackCode.get.toInt), changeSource.discontinuity.get, AddrMRange(changeSource.startAddressM.get,     changeSource.endAddressM.get    ), projectLinks.head.reversed, currentRoadway.startDate, Some(projectStartDate.minusDays(1)), createdBy = currentRoadway.createdBy, currentRoadway.roadName, currentRoadway.ely, NoTermination, currentRoadway.validFrom, currentRoadway.validTo)
    val newRoadway =     Roadway(NewIdValue, roadwayNumber, targetRoadPart, changeTarget.administrativeClass.get, Track.apply(changeTarget.trackCode.get.toInt), changeTarget.discontinuity.get, AddrMRange(projectLinks.head.addrMRange.start, projectLinks.last.addrMRange.end), projectLinks.head.reversed, projectStartDate, None, createdBy = projectLinks.head.createdBy.get, currentRoadway.roadName, changeTarget.ely.get, NoTermination)
    Seq(historyRoadway, newRoadway)
  }

  private def applyUnchanged(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadways: Seq[Roadway],
                             historyRoadways: Seq[Roadway]): Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])] = {
    val changeSource = change.changeInfo.source
    val changeTarget = change.changeInfo.target
    currentRoadways.map { currentRoadway =>
      val projectLinksInRoadway = projectLinks.filter(_.roadwayId == currentRoadway.id).sortBy(_.addrMRange.start)
      val administrativeClassDiscontinuityOrElyChanged = currentRoadway.administrativeClass != changeTarget.administrativeClass.get ||
        currentRoadway.discontinuity != changeTarget.discontinuity.get || currentRoadway.ely != changeTarget.ely.get
      val lengthChanged = currentRoadway.addrMRange.start != projectLinksInRoadway.head.addrMRange.start ||
                    currentRoadway.addrMRange.end != projectLinksInRoadway.last.addrMRange.end
      val roadways = if (administrativeClassDiscontinuityOrElyChanged || lengthChanged) {
        generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinksInRoadway, currentRoadway,
          change.projectStartDate)
      } else {
        val targetRoadPart = RoadPart(changeTarget.roadNumber.get, changeTarget.startRoadPartNumber.get)
        Seq(Roadway(NewIdValue, projectLinksInRoadway.head.roadwayNumber, targetRoadPart, changeTarget.administrativeClass.get, Track.apply(changeTarget.trackCode.get.toInt), changeTarget.discontinuity.get, AddrMRange(projectLinks.head.addrMRange.start, projectLinks.last.addrMRange.end), projectLinks.head.reversed, currentRoadway.startDate, None, createdBy = projectLinks.head.createdBy.get, currentRoadway.roadName, changeTarget.ely.get, NoTermination))
      }

      val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)
      val newHistoryRoadways = currentRoadwayHistoryRoadways.flatMap { historyRoadway =>
        val newStartAddressM = historyRoadway.addrMRange.start + roadways.head.addrMRange.start - currentRoadway.addrMRange.start
        val newEndAddressM = newStartAddressM + roadways.head.addrMRange.end - roadways.head.addrMRange.start
        if (historyRoadway.addrMRange.end - historyRoadway.addrMRange.start != roadways.head.addrMRange.end - roadways.head.addrMRange.start) {
          Seq(Roadway(NewIdValue, roadways.head.roadwayNumber, historyRoadway.roadPart, historyRoadway.administrativeClass, historyRoadway.track, historyRoadway.discontinuity, AddrMRange(newStartAddressM, newEndAddressM), historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName, historyRoadway.ely, NoTermination))
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
          && projectLink.roadPart.roadNumber == changeTarget.roadNumber.get
          && projectLink.roadPart.partNumber == changeTarget.startRoadPartNumber.get)
        .sortBy(_.addrMRange.start)
      val roadways = generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinksInRoadway, currentRoadway, change.projectStartDate)

      val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)

      val newHistoryRoadways = currentRoadwayHistoryRoadways.map { historyRoadway =>
        if (historyRoadway.addrMRange.end - historyRoadway.addrMRange.start != roadways.head.addrMRange.end - roadways.head.addrMRange.start) {
          val newStartAddressM = historyRoadway.addrMRange.start + roadways.head.addrMRange.start - currentRoadway.addrMRange.start
          val newEndAddressM = newStartAddressM + roadways.head.addrMRange.end - roadways.head.addrMRange.start
          Roadway(NewIdValue, roadways.head.roadwayNumber, historyRoadway.roadPart, historyRoadway.administrativeClass, historyRoadway.track, historyRoadway.discontinuity, AddrMRange(newStartAddressM, newEndAddressM), historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName, historyRoadway.ely, NoTermination)
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
      val projectLinksInRoadway = projectLinks.filter(_.roadwayId == currentRoadway.id).sortBy(_.addrMRange.start)
      val newRoadwayNumber = if ((projectLinksInRoadway.last.addrMRange.end - projectLinksInRoadway.head.addrMRange.start) == (currentRoadway.addrMRange.end - currentRoadway.addrMRange.start)) currentRoadway.roadwayNumber else projectLinksInRoadway.head.roadwayNumber
      val roadway = currentRoadway.copy(id = NewIdValue, roadwayNumber = newRoadwayNumber, endDate = projectLinks.head.endDate, terminated = TerminationCode.Termination, addrMRange = AddrMRange(sourceChange.startAddressM.get, sourceChange.endAddressM.get), discontinuity = projectLinksInRoadway.last.discontinuity)
      val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)

      val newHistoryRoadways = currentRoadwayHistoryRoadways.map { historyRoadway =>
        val newStartAddressM = historyRoadway.addrMRange.start + roadway.addrMRange.start - currentRoadway.addrMRange.start
        val newEndAddressM = newStartAddressM + roadway.addrMRange.end - roadway.addrMRange.start
        if (historyRoadway.addrMRange.end - historyRoadway.addrMRange.start != roadway.addrMRange.end - roadway.addrMRange.start) {
          Roadway(NewIdValue, roadway.roadwayNumber, historyRoadway.roadPart, historyRoadway.administrativeClass, historyRoadway.track, historyRoadway.discontinuity, AddrMRange(newStartAddressM, newEndAddressM), historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName, historyRoadway.ely, Subsequent)
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
    val targetRoadPart = RoadPart(changeTarget.roadNumber.get, changeTarget.startRoadPartNumber.get)
    val roadway = Roadway(NewIdValue, roadwayNumber, targetRoadPart, changeTarget.administrativeClass.get, Track.apply(changeTarget.trackCode.get.toInt), changeTarget.discontinuity.get, AddrMRange(changeTarget.startAddressM.get, changeTarget.endAddressM.get), change.changeInfo.reversed, startDate = projectLinks.head.startDate.get, endDate = projectLinks.head.endDate, createdBy = projectLinks.head.createdBy.get, projectLinks.head.roadName, projectLinks.head.ely, NoTermination)
   val projectLinksWithGivenAttributes = projectLinks.map(pl => pl.copy(linearLocationId = if(pl.linearLocationId == 0 || pl.linearLocationId == NewIdValue) Sequences.nextLinearLocationId else pl.linearLocationId, roadwayNumber = roadway.roadwayNumber))
    Seq((Seq(roadway), roadwayAddressMapper.mapLinearLocations(roadway, projectLinksWithGivenAttributes), projectLinksWithGivenAttributes))
  }

  private def applyNumbering(change: ProjectRoadwayChange, projectLinks: Seq[ProjectLink], currentRoadways: Seq[Roadway], historyRoadways: Seq[Roadway]): Seq[(Seq[Roadway], Seq[LinearLocation], Seq[ProjectLink])] = {
    val changeSource = change.changeInfo.source
    val changeTarget = change.changeInfo.target
    currentRoadways.map { currentRoadway =>
      val projectLinksInRoadway = projectLinks.filter(_.roadwayId == currentRoadway.id).sortBy(_.addrMRange.start)
      val roadways = generateNewRoadwaysWithHistory(changeSource, changeTarget, projectLinksInRoadway, currentRoadway, change.projectStartDate)

      val currentRoadwayHistoryRoadways = historyRoadways.filter(_.roadwayNumber == currentRoadway.roadwayNumber)
      val newHistoryRoadways = currentRoadwayHistoryRoadways.map { historyRoadway =>
        val newStartAddressM = historyRoadway.addrMRange.start + roadways.head.addrMRange.start - currentRoadway.addrMRange.start
        val newEndAddressM = newStartAddressM + roadways.head.addrMRange.end - roadways.head.addrMRange.start
        if (historyRoadway.addrMRange.end - historyRoadway.addrMRange.start != roadways.head.addrMRange.end - roadways.head.addrMRange.start) {
          Roadway(NewIdValue, roadways.head.roadwayNumber, historyRoadway.roadPart, historyRoadway.administrativeClass, historyRoadway.track, historyRoadway.discontinuity, AddrMRange(newStartAddressM, newEndAddressM), historyRoadway.reversed, historyRoadway.startDate, historyRoadway.endDate, historyRoadway.createdBy, historyRoadway.roadName, historyRoadway.ely, NoTermination)
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

    val (operationsToCheck, rest) = changesWithLinks.partition(c => List(
      RoadAddressChangeType.Unchanged, RoadAddressChangeType.Transfer,
      RoadAddressChangeType.Renumeration, RoadAddressChangeType.Termination
    ).contains(c._1.changeInfo.changeType))
    (groupedSections(operationsToCheck).toSeq ++ rest).sortBy(_._1.changeInfo.orderInChangeTable)
  }


  case class AdminClassRwn(administrativeClass  : AdministrativeClass, roadwayNumber: Long)
  case class GeneratedRoadway(roadway: Seq[Roadway], linearLocations: Seq[LinearLocation], projectLinks: Seq[ProjectLink])
}

