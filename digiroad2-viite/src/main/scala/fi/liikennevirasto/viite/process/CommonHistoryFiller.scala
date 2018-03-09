package fi.liikennevirasto.viite.process


import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.viite.NewRoadAddress
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectLink, RoadAddress}

object CommonHistoryFiller {
  private def applyUnchanged(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
      val (unchangedLinks, rest) = projectLinks.partition(_.status == LinkStatus.UnChanged)
      val addresses = unchangedLinks.flatMap(pl => newRoadAddresses.find(_.id == pl.roadAddressId))
      val commonHistoryForUnchanged = if(addresses.nonEmpty) {
        //check if whole length of the road has changed track or road type => same commonHistoryId
        addresses.groupBy(ra => (ra.roadNumber, ra.commonHistoryId)).flatMap { group =>
          val groupAddresses = group._2
          groupAddresses.groupBy(c => (c.track, c.roadType)).size match {
            //in case they have same track and road type
            case 1 => groupAddresses
            case _ => groupAddresses.sortBy(_.startAddrMValue).foldLeft(Seq.empty[RoadAddress]){ case (seq, address) =>

              val changedAddress = if(seq.isEmpty) {
                val nextId = Sequences.nextCommonHistorySeqValue
                address.copy(commonHistoryId = nextId)
              } else {
                if(address.track != seq.last.track || address.roadType != seq.last.roadType){
                  val nextId = Sequences.nextCommonHistorySeqValue
                  address.copy(commonHistoryId = nextId)
                } else
                  address.copy(commonHistoryId = seq.last.commonHistoryId)
              }
              seq++Seq(changedAddress)
            }
          }
        }.toSeq
      } else newRoadAddresses
    newRoadAddresses.filterNot(nra =>commonHistoryForUnchanged.map(_.id).contains(nra.id))++commonHistoryForUnchanged
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
    val transferedLinks = projectLinks.filter(_.status == LinkStatus.Transfer)
    if(transferedLinks.nonEmpty){
      transferedLinks.groupBy(pl => (pl.roadNumber, pl.roadPartNumber, pl.track, pl.roadType)).flatMap {
        case((roadNumber, roadPartNumber, trackCode, roadType), groupedLinks) =>
          val roadAddressesToReturn = newRoadAddresses.filter(ra => groupedLinks.sortBy(_.startAddrMValue).map(_.roadAddressId).contains(ra.id ))
          val roadAddressesToCompare = currentRoadAddresses.filter(ra => ra.roadNumber == roadNumber && ra.roadPartNumber == roadPartNumber && ra.track == trackCode && ra.roadType == roadType).sortBy(_.startAddrMValue)
          //if the length of the transferred part is the same in road address table
          if(groupedLinks.last.endAddrMValue - groupedLinks.head.startAddrMValue == roadAddressesToCompare.last.endAddrMValue - roadAddressesToCompare.head.startAddrMValue){
            roadAddressesToReturn
          }
          else{
            val nextId = Sequences.nextCommonHistorySeqValue
            roadAddressesToReturn.map(_.copy(commonHistoryId = nextId))
          }
      }
    }.toSeq ++ newRoadAddresses.filterNot(ra => transferedLinks.map(_.roadAddressId).contains(ra.id ))
    else{
      newRoadAddresses
    }
  }

  private def applyNumbering(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]) :Seq[RoadAddress]={
    // TODO
    newRoadAddresses
  }

  def fillCommonHistory(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress], currentRoadAddresses: Seq[RoadAddress]) : Seq[RoadAddress]= {
    val fillOperations: Seq[(Seq[ProjectLink], Seq[RoadAddress]) => Seq[RoadAddress]] = Seq(
      applyUnchanged,
      applyNew,
      applyTransfer(currentRoadAddresses)
    )

    fillOperations.foldLeft(newRoadAddresses){
      case (pNewRoadAddresses, operation) =>
        operation(projectLinks, pNewRoadAddresses)
    }
  }
}
