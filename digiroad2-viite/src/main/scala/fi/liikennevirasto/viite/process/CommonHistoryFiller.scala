package fi.liikennevirasto.viite.process


import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.viite.NewRoadAddress
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectLink, RoadAddress}

object CommonHistoryFiller {
  private def applyUnchanged(currentRoadAddresses : Seq[RoadAddress])(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
    val unchangedLinks = projectLinks.filter(_.status == LinkStatus.UnChanged)

    if(unchangedLinks.nonEmpty){
      unchangedLinks.groupBy(pl => (pl.roadNumber, pl.roadPartNumber, pl.track, pl.roadType)).flatMap {
        case((roadNumber, roadPartNumber, trackCode, roadType), groupedLinks) =>
          val roadAddressesToReturn = newRoadAddresses.filter(ra => groupedLinks.sortBy(_.startAddrMValue).map(_.roadAddressId).contains (ra.id))

          val roadAddressesToCompare = currentRoadAddresses.filter(ra => ra.roadNumber == roadNumber && ra.roadPartNumber == roadPartNumber && ra.track == trackCode && ra.roadType == roadType).sortBy(_.startAddrMValue)
          if(groupedLinks.nonEmpty && roadAddressesToCompare.nonEmpty && groupedLinks.lengthCompare(roadAddressesToCompare.length) == 0 && (groupedLinks.last.endAddrMValue - groupedLinks.head.startAddrMValue == roadAddressesToCompare.last.endAddrMValue - roadAddressesToCompare.head.startAddrMValue)){
            roadAddressesToReturn
          }
          else{
            roadAddressesToReturn.groupBy(_.commonHistoryId).values.toSeq.foldLeft(Seq.empty[RoadAddress]) { case (seqComplete, addresses) =>
              val currentsInsideGroup = roadAddressesToCompare.filter(ra => addresses.map(_.id).contains(ra.id))
              val groupWithSameCommonHistory = roadAddressesToCompare.filter(_.commonHistoryId == addresses.head.commonHistoryId)
              if(currentsInsideGroup.nonEmpty && groupWithSameCommonHistory.nonEmpty && currentsInsideGroup.lengthCompare(groupWithSameCommonHistory.length) == 0 && (currentsInsideGroup.last.endAddrMValue - currentsInsideGroup.head.startAddrMValue == groupWithSameCommonHistory.last.endAddrMValue - groupWithSameCommonHistory.head.startAddrMValue)){
                seqComplete ++ addresses
              }
              else{
                val changedAddresses = addresses.sortBy(_.startAddrMValue).foldLeft(Seq.empty[RoadAddress]) { case (seq, address) =>
                  val changedAddress = if (seq.isEmpty) {
                    val nextId = Sequences.nextCommonHistorySeqValue
                    address.copy(commonHistoryId = nextId)
                  } else {
                    if (address.startAddrMValue != seq.last.endAddrMValue) {
                      val nextId = Sequences.nextCommonHistorySeqValue
                      address.copy(commonHistoryId = nextId)
                    } else
                      address.copy(commonHistoryId = seq.last.commonHistoryId)
                  }
                  seq ++ Seq(changedAddress)
                }
                seqComplete ++ changedAddresses
              }
            }
          }
      }.toSeq ++ newRoadAddresses.filterNot(ra => unchangedLinks.map(_.roadAddressId).contains(ra.id ))
    }
    else
      newRoadAddresses
    }

  private def applyNew(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]) :Seq[RoadAddress]={
    val addressesGroups = newRoadAddresses.filter(_.id == NewRoadAddress)
    if(addressesGroups.nonEmpty) {
      addressesGroups.groupBy(ra => (ra.roadNumber, ra.roadPartNumber, ra.track, ra.roadType)).flatMap{ group =>
        val addressesInGroup = group._2
        addressesInGroup.sortBy(_.startAddrMValue).foldLeft(Seq.empty[RoadAddress]){ case (seq, address) =>
          val changedAddress = if(seq.isEmpty) {
            val nextId = Sequences.nextCommonHistorySeqValue
            address.copy(commonHistoryId = nextId)
          } else {
            if(address.startAddrMValue != seq.last.endAddrMValue ){
              val nextId = Sequences.nextCommonHistorySeqValue
              address.copy(commonHistoryId = nextId)
            } else
              address.copy(commonHistoryId = seq.last.commonHistoryId)
          }
          seq ++ Seq(changedAddress)
        }
      }.toSeq ++ newRoadAddresses.filterNot(_.id == NewRoadAddress)
    }
    else newRoadAddresses
  }

  private def applyTransfer(currentRoadAddresses : Seq[RoadAddress])(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]) :Seq[RoadAddress]={
    val transferredLinks = projectLinks.filter(_.status == LinkStatus.Transfer)
    if(transferredLinks.nonEmpty){
      transferredLinks.groupBy(pl => (pl.roadNumber, pl.roadPartNumber, pl.track, pl.roadType)).flatMap {
        case((_, _, _, _), groupedLinks) =>
          val roadAddressesToReturn = newRoadAddresses.filter(ra => groupedLinks.sortBy(_.startAddrMValue).map(_.roadAddressId).contains(ra.id ) && ra.endDate.isEmpty)
          val roadAddressesToCompare = currentRoadAddresses.filter(ra => groupedLinks.map(_.roadAddressId).contains(ra.id ) && ra.endDate.isEmpty).sortBy(_.startAddrMValue)

          //if the length of the transferred part is the same in road address table
          if(roadAddressesToReturn.nonEmpty && roadAddressesToCompare.nonEmpty && groupedLinks.last.endAddrMValue - groupedLinks.head.startAddrMValue == roadAddressesToCompare.last.endAddrMValue - roadAddressesToCompare.head.startAddrMValue){
            val allExistingAddresses = currentRoadAddresses.filter(ra=> ra.roadNumber == roadAddressesToCompare.head.roadNumber && ra.roadPartNumber == roadAddressesToCompare.head.roadPartNumber
              && ra.track == roadAddressesToCompare.head.track && ra.roadType == roadAddressesToCompare.head.roadType)

            if(allExistingAddresses.nonEmpty && allExistingAddresses.lengthCompare(roadAddressesToCompare.length) == 0){
              roadAddressesToReturn
            }
            else
              assignNewCommonHistoryIds(roadAddressesToReturn)
          }
          else{
            assignNewCommonHistoryIds(roadAddressesToReturn)
          }
      }
    }.toSeq ++ newRoadAddresses.filterNot(ra => transferredLinks.map(_.roadAddressId).contains(ra.id ))
    else{
      newRoadAddresses
    }
  }

  private def applyNumbering(currentRoadAddresses : Seq[RoadAddress])(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]) :Seq[RoadAddress]={
    val renumberedLinks = projectLinks.filter(_.status == LinkStatus.Numbering)
    if(renumberedLinks.nonEmpty){
      renumberedLinks.groupBy(pl => (pl.roadNumber, pl.roadPartNumber, pl.track, pl.roadType)).flatMap {
        case((_, _, _, _), groupedLinks) =>
          val roadAddressesToReturn = newRoadAddresses.filter(ra => groupedLinks.map(_.roadAddressId).contains(ra.id )).sortBy(_.startAddrMValue)
          val roadAddressesToCompare = currentRoadAddresses.filter(ra => groupedLinks.map(_.roadAddressId).contains(ra.id )).sortBy(_.startAddrMValue)
          //if the length of the renumbering part is the same in road address table
          if(groupedLinks.nonEmpty && roadAddressesToCompare.nonEmpty && groupedLinks.last.endAddrMValue - groupedLinks.head.startAddrMValue == roadAddressesToCompare.last.endAddrMValue - roadAddressesToCompare.head.startAddrMValue){
            roadAddressesToReturn
          }
          else{
            assignNewCommonHistoryIds(roadAddressesToReturn)
          }
      }
    }.toSeq ++ newRoadAddresses.filterNot(ra => renumberedLinks.map(_.roadAddressId).contains(ra.id ))
    else{
      newRoadAddresses
    }
  }

  def fillCommonHistory(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress], currentRoadAddresses: Seq[RoadAddress]) : Seq[RoadAddress]= {
    val fillOperations: Seq[(Seq[ProjectLink], Seq[RoadAddress]) => Seq[RoadAddress]] = Seq(
      applyUnchanged(currentRoadAddresses),
      applyNew,
      applyTransfer(currentRoadAddresses),
      applyNumbering(currentRoadAddresses)
    )

    fillOperations.foldLeft(newRoadAddresses){
      case (pNewRoadAddresses, operation) =>
        operation(projectLinks, pNewRoadAddresses)
    }
  }

  def assignNewCommonHistoryIds(roadAddresses: Seq[RoadAddress]) : Seq[RoadAddress] = {
    roadAddresses.groupBy(_.commonHistoryId).mapValues{ addresses =>
      val changedAddresses = addresses.sortBy(_.startAddrMValue).foldLeft(Seq.empty[RoadAddress]) { case (seq, address) =>
        val changedAddress = if (seq.isEmpty) {
          val nextId = Sequences.nextCommonHistorySeqValue
          address.copy(commonHistoryId = nextId)
        } else {
          if (address.startAddrMValue != seq.last.endAddrMValue) {
            val nextId = Sequences.nextCommonHistorySeqValue
            address.copy(commonHistoryId = nextId)
          } else
            address.copy(commonHistoryId = seq.last.commonHistoryId)
        }
        seq ++ Seq(changedAddress)
      }
      changedAddresses
    }
  }.values.flatten.toSeq
}
