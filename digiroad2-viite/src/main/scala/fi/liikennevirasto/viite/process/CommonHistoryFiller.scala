package fi.liikennevirasto.viite.process


import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectLink, RoadAddress}

object CommonHistoryFiller {
  private def applyUnchanged(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]): Seq[RoadAddress] = {
      // TODO
      val (unchangedLinks, rest) = projectLinks.partition(_.status == LinkStatus.UnChanged)
      //    val groupedAddresses = unchangedLinks.flatMap(pl => groupedExistingAddresses.get(pl.roadAddressId).getOrElse(Seq.empty[RoadAddress]).headOption)
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
              Seq(changedAddress)
            }
          }
        }.toSeq
      } else newRoadAddresses
    newRoadAddresses.filterNot(nra =>commonHistoryForUnchanged.map(_.id).contains(nra.id))++commonHistoryForUnchanged
    }

  private def applyNew(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]) :Seq[RoadAddress]={
    // TODO

    val newLinks = projectLinks.filter(_.status == LinkStatus.New)
    val addresses = newLinks.flatMap(pl => newRoadAddresses.find(_.id == pl.roadAddressId))
    if(addresses.nonEmpty) {
      val groups = addresses.groupBy(ra => (ra.roadNumber, ra.roadPartNumber, ra.track, ra.roadType))


      //WIP
      newRoadAddresses
    }
    else newRoadAddresses
  }

  private def applyTransfer(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]) :Seq[RoadAddress]={
    // TODO
    newRoadAddresses
  }

  private def applyNumbering(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]) :Seq[RoadAddress]={
    // TODO
    newRoadAddresses
  }

  def fillCommonHistory(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]) : Seq[RoadAddress]= {
    val fillOperations: Seq[(Seq[ProjectLink], Seq[RoadAddress]) => Seq[RoadAddress]] = Seq(
      applyUnchanged,
      applyNew,
      applyTransfer
    )

    fillOperations.foldLeft(newRoadAddresses){
      case (pNewRoadAddresses, operation) =>
        operation(projectLinks, pNewRoadAddresses)
    }
  }
}
