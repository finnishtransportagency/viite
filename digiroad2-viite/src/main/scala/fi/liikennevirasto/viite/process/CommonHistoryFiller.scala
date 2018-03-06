package fi.liikennevirasto.viite.process


import fi.liikennevirasto.viite.dao.{ProjectLink, RoadAddress}

object CommonHistoryFiller {
  private def applyUnchanged(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]) :Seq[RoadAddress]={
    // TODO
    newRoadAddresses
  }

  private def applyNew(projectLinks: Seq[ProjectLink], newRoadAddresses: Seq[RoadAddress]) :Seq[RoadAddress]={
    // TODO
    newRoadAddresses
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
