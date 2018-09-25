package fi.liikennevirasto.viite.process


import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.Combined
import fi.liikennevirasto.viite.NewRoadway
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectLink, RoadAddress}
import fi.liikennevirasto.viite.util.CalibrationPointsUtils.fillCPs
import org.slf4j.LoggerFactory

object RoadwayFiller {

  private val logger = LoggerFactory.getLogger(getClass)

  private def applyUnchanged(currentRoadAddresses: Seq[RoadAddress])(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
    val unchangedLinks = projectLinks.filter(_.status == LinkStatus.UnChanged)

    unchangedLinks.groupBy(pl => (pl.roadNumber, pl.roadPartNumber, pl.track, pl.roadType)).flatMap {
      case ((roadNumber, roadPartNumber, trackCode, roadType), groupedLinks) =>
        val roadAddressesToReturn = newRoadAddresses.filter(ra => groupedLinks.exists(_.roadwayId == ra.id))
        val roadAddressesToCompare = currentRoadAddresses.filter(ra => ra.roadNumber == roadNumber && ra.roadPartNumber == roadPartNumber && ra.track == trackCode)
        if (groupedLinks.nonEmpty && roadAddressesToCompare.nonEmpty && groupedLinks.lengthCompare(roadAddressesToCompare.length) == 0
          && (groupedLinks.last.endAddrMValue - groupedLinks.head.startAddrMValue == roadAddressesToCompare.last.endAddrMValue - roadAddressesToCompare.head.startAddrMValue)) {
          roadAddressesToReturn
        } else {
          roadAddressesToReturn.groupBy(_.roadwayNumber).values.toSeq.foldLeft(Seq.empty[RoadAddress]) { case (seqComplete, addresses) =>
            val currentsInsideGroup = roadAddressesToCompare.filter(ra => addresses.exists(_.id == ra.id))
            val groupWithSameRoadway = roadAddressesToCompare.filter(_.roadwayNumber == addresses.head.roadwayNumber)
            if (currentsInsideGroup.nonEmpty && groupWithSameRoadway.nonEmpty && currentsInsideGroup.lengthCompare(groupWithSameRoadway.length) == 0
              && (currentsInsideGroup.last.endAddrMValue - currentsInsideGroup.head.startAddrMValue == groupWithSameRoadway.last.endAddrMValue - groupWithSameRoadway.head.startAddrMValue)) {
              seqComplete ++ addresses
            } else {
              val changedAddresses = addresses.sortBy(_.startAddrMValue).foldLeft(Seq.empty[RoadAddress]) { case (seq, address) =>
                seq ++ Seq(fillRoadwayNumber(seq, address))
              }
              seqComplete ++ changedAddresses
            }
          }
        }
    }.toSeq ++ newRoadAddresses.filterNot(ra => unchangedLinks.exists(_.roadwayId == ra.id))
  }

  private def applyTerminated(currentRoadAddresses: Seq[RoadAddress])(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
    val terminatedLinks = projectLinks.filter(_.status == LinkStatus.Terminated)

    terminatedLinks.groupBy(pl => (pl.roadNumber, pl.roadPartNumber, pl.track, pl.roadType)).flatMap {
      case ((roadNumber, roadPartNumber, trackCode, roadType), groupedLinks) =>
        val roadAddressesToReturn = newRoadAddresses.filter(ra => groupedLinks.exists(_.roadwayId == ra.id))
        val roadAddressesToCompare = currentRoadAddresses.filter(ra => ra.roadNumber == roadNumber && ra.roadPartNumber == roadPartNumber && ra.track == trackCode && ra.roadType == roadType)
        if (groupedLinks.nonEmpty && roadAddressesToCompare.nonEmpty && groupedLinks.lengthCompare(roadAddressesToCompare.length) == 0
          && (groupedLinks.last.endAddrMValue - groupedLinks.head.startAddrMValue == roadAddressesToCompare.last.endAddrMValue - roadAddressesToCompare.head.startAddrMValue)) {
          roadAddressesToReturn
        } else {
          roadAddressesToReturn.groupBy(_.roadwayNumber).values.toSeq.foldLeft(Seq.empty[RoadAddress]) {
            case (seqComplete, addresses) =>
              val currentsInsideGroup = roadAddressesToCompare.filter(ra => addresses.exists(_.id == ra.id))
              val groupWithSameRoadway = roadAddressesToCompare.filter(_.roadwayNumber == addresses.head.roadwayNumber)
              if (currentsInsideGroup.nonEmpty && groupWithSameRoadway.nonEmpty && currentsInsideGroup.lengthCompare(groupWithSameRoadway.length) == 0
                && (currentsInsideGroup.last.endAddrMValue - currentsInsideGroup.head.startAddrMValue == groupWithSameRoadway.last.endAddrMValue - groupWithSameRoadway.head.startAddrMValue)) {
                seqComplete ++ addresses
              } else {
                val changedAddresses = addresses.sortBy(_.startAddrMValue).foldLeft(Seq.empty[RoadAddress]) {
                  case (seq, address) =>
                    seq ++ Seq(fillRoadwayNumber(seq, address))
                }
                seqComplete ++ changedAddresses
              }
          }
        }
    }.toSeq ++ newRoadAddresses.filterNot(ra => terminatedLinks.exists(_.roadwayId == ra.id))
  }

  private def applyNew(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
    val addressesGroups = newRoadAddresses.filter(_.id == NewRoadway)
    addressesGroups.groupBy(ra => (ra.roadNumber, ra.roadPartNumber, ra.track, ra.roadType)).flatMap { group =>
      val addressesInGroup = group._2
      addressesInGroup.sortBy(_.startAddrMValue).foldLeft(Seq.empty[RoadAddress]) { case (seq, address) =>
        val changedAddress = fillRoadwayNumber(seq, address)
        seq ++ Seq(changedAddress)
      }
    }.toSeq ++ newRoadAddresses.filterNot(_.id == NewRoadway)
  }

  private def applyTransfer(currentRoadAddresses: Seq[RoadAddress])(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
    val transferredLinks = projectLinks.filter(_.status == LinkStatus.Transfer)
    val (trackChangedLinks, trackUnchangedLinks) = transferredLinks.partition(pl => {
      val currentRoadAddress = currentRoadAddresses.find(ra => ra.id == pl.roadwayId)
      currentRoadAddress.nonEmpty && ((currentRoadAddress.get.track == Combined && pl.track != Combined) || (currentRoadAddress.get.track != Combined && pl.track == Combined))
    })
    generateValuesForTransfer(currentRoadAddresses, trackChangedLinks, newRoadAddresses) ++ generateValuesForTransfer(currentRoadAddresses, trackUnchangedLinks, newRoadAddresses) ++
      newRoadAddresses.filterNot(ra => transferredLinks.exists(_.roadwayId == ra.id))
  }

  /**
    * Copy the roadway id from opposite track code of a transferred part to the new part
    *
    * @param projectLinks     The project links
    * @param newRoadAddresses The new road address
    * @return
    */
  private def applyTransferToNew(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
    val transferredLinks = projectLinks.filter(pl => pl.status == LinkStatus.Transfer && (pl.track == Track.RightSide || pl.track == Track.LeftSide))
    val (newerRoadAddresses, rest) = newRoadAddresses.partition(ra => ra.id == NewRoadway && (ra.track == Track.RightSide || ra.track == Track.LeftSide))
    newerRoadAddresses.map {
      ra =>
        val transferredOption = transferredLinks.find(pl => pl.track == Track.switch(ra.track) && pl.liesInBetween(ra))
        transferredOption match {
          case Some(transferred) =>
            newRoadAddresses.find(cra => cra.id == transferred.roadwayId) match {
              case Some(roadAddress) => ra.copy(roadwayNumber = roadAddress.roadwayNumber)
              case _ => ra
            }

          case _ => ra
        }
    } ++ rest
  }

  private def generateValuesForTransfer(currentRoadAddresses: Seq[RoadAddress], transferredLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
    transferredLinks.groupBy(pl => (pl.roadNumber, pl.roadPartNumber, pl.track, pl.roadType)).flatMap {
      case ((_, _, _, _), groupedLinks) =>
        val roadAddressesToReturn = newRoadAddresses.filter(ra => groupedLinks.exists(_.roadwayId == ra.id) && ra.endDate.isEmpty)
        val roadAddressesToCompare = currentRoadAddresses.filter(ra => groupedLinks.exists(_.roadwayId == ra.id) && ra.endDate.isEmpty)

        //if the length of the transferred part is the same in road address table
        if (roadAddressesToReturn.nonEmpty && roadAddressesToCompare.nonEmpty
          && groupedLinks.last.endAddrMValue - groupedLinks.head.startAddrMValue == roadAddressesToCompare.last.endAddrMValue - roadAddressesToCompare.head.startAddrMValue) {
          val allExistingAddresses = currentRoadAddresses.filter(ra => ra.roadNumber == roadAddressesToCompare.head.roadNumber && ra.roadPartNumber == roadAddressesToCompare.head.roadPartNumber
            && ra.track == roadAddressesToCompare.head.track && ra.roadType == roadAddressesToCompare.head.roadType)

          if (allExistingAddresses.nonEmpty && allExistingAddresses.lengthCompare(roadAddressesToCompare.length) == 0) {
            roadAddressesToReturn
          } else {
            assignNewRoadwayNumbers(roadAddressesToReturn)
          }
        } else {
          assignNewRoadwayNumbers(roadAddressesToReturn)
        }
    }.toSeq
  }

  private def applyNumbering(currentRoadAddresses: Seq[RoadAddress])(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
    val renumberedLinks = projectLinks.filter(_.status == LinkStatus.Numbering)
    renumberedLinks.groupBy(pl => (pl.roadNumber, pl.roadPartNumber, pl.track, pl.roadType)).flatMap {
      case ((_, _, _, _), groupedLinks) =>
        val roadAddressesToReturn = newRoadAddresses.filter(ra => groupedLinks.exists(_.roadwayId == ra.id))
        val roadAddressesToCompare = currentRoadAddresses.filter(ra => groupedLinks.exists(_.roadwayId == ra.id))
        //if the length of the renumbering part is the same in road address table
        if (groupedLinks.nonEmpty && roadAddressesToCompare.nonEmpty
          && groupedLinks.last.endAddrMValue - groupedLinks.head.startAddrMValue == roadAddressesToCompare.last.endAddrMValue - roadAddressesToCompare.head.startAddrMValue) {
          roadAddressesToReturn
        } else {
          assignNewRoadwayNumbers(roadAddressesToReturn)
        }
    }.toSeq ++ newRoadAddresses.filterNot(ra => renumberedLinks.exists(_.roadwayId == ra.id))
  }

  def fillRoadway(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress], currentRoadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
    val fillOperations: Seq[(Seq[ProjectLink], Seq[RoadAddress]) => Seq[RoadAddress]] = Seq(
      applyUnchanged(currentRoadAddresses),
      applyNew,
      applyTransfer(currentRoadAddresses),
      applyTransferToNew, //This method should always be after transfer and new operations
      applyNumbering(currentRoadAddresses),
      applyTerminated(currentRoadAddresses)
    )

    val processedAddresses = fillOperations.foldLeft(newRoadAddresses) {
      case (pNewRoadAddresses, operation) =>
        operation(projectLinks, pNewRoadAddresses)
    }
    processedAddresses.groupBy(_.roadwayNumber).mapValues(addresses => {
      logger.info(s"Processing calibration points for roadway id ${addresses.head.roadwayNumber}")
      setCalibrationPoints(addresses.sortBy(_.startAddrMValue))
    }).values.flatten.toSeq
  }

  private def assignNewRoadwayNumbers(roadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
    roadAddresses.groupBy(_.roadwayNumber).mapValues { addresses =>
      val changedAddresses = addresses.sortBy(_.startAddrMValue).foldLeft(Seq.empty[RoadAddress]) { case (seq, address) =>
        seq ++ Seq(fillRoadwayNumber(seq, address))
      }
      changedAddresses
    }.values.flatten.toSeq
  }

  private def setCalibrationPoints(roadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
    if (roadAddresses.isEmpty) {
      roadAddresses
    } else {
      val startNeedsCP = roadAddresses.head
      val endNeedsCP = roadAddresses.last

      roadAddresses.length match {
        case 2 =>
          Seq(fillCPs(startNeedsCP, atStart = true)) ++ Seq(fillCPs(endNeedsCP, atEnd = true))
        case 1 =>
          Seq(fillCPs(startNeedsCP, atStart = true, atEnd = true))
        case _ =>
          val middle = roadAddresses.drop(1).dropRight(1)
          Seq(fillCPs(startNeedsCP, atStart = true)) ++ middle ++ Seq(fillCPs(endNeedsCP, atEnd = true))
      }
    }
  }


  private def fillRoadwayNumber(seq: Seq[RoadAddress], address: RoadAddress): RoadAddress = {
    if (seq.isEmpty) {
      val nextId = Sequences.nextRoadwaySeqValue
      address.copy(roadwayNumber = nextId)
    } else {
      if (address.startAddrMValue != seq.last.endAddrMValue) {
        val nextId = Sequences.nextRoadwaySeqValue
        address.copy(roadwayNumber = nextId)
      } else
        address.copy(roadwayNumber = seq.last.roadwayNumber)
    }
  }
}

