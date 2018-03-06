package fi.liikennevirasto.viite.process


import fi.liikennevirasto.viite.dao.{ProjectLink, RoadAddress}

object CommonHistoryFiller {
  private def applyTerminated(projectLinks: Seq[ProjectLink], existingRoadAddresses: Seq[RoadAddress], newRoadAddresses: Seq[RoadAddress]) :(Seq[RoadAddress], Seq[RoadAddress])={
    // TODO
    (existingRoadAddresses, newRoadAddresses)
  }

  private def applyUnchanged(projectLinks: Seq[ProjectLink], existingRoadAddresses: Seq[RoadAddress], newRoadAddresses: Seq[RoadAddress]) :(Seq[RoadAddress], Seq[RoadAddress])={
    // TODO
    (existingRoadAddresses, newRoadAddresses)
  }

  private def applyNew(projectLinks: Seq[ProjectLink], existingRoadAddresses: Seq[RoadAddress], newRoadAddresses: Seq[RoadAddress]) :(Seq[RoadAddress], Seq[RoadAddress])={
    // TODO
    (existingRoadAddresses, newRoadAddresses)
  }

  private def applyTransfer(projectLinks: Seq[ProjectLink], existingRoadAddresses: Seq[RoadAddress], newRoadAddresses: Seq[RoadAddress]) :(Seq[RoadAddress], Seq[RoadAddress])={
    // TODO
    (existingRoadAddresses, newRoadAddresses)
  }

  private def applyNumbering(projectLinks: Seq[ProjectLink], existingRoadAddresses: Seq[RoadAddress], newRoadAddresses: Seq[RoadAddress]) :(Seq[RoadAddress], Seq[RoadAddress])={
    // TODO
    (existingRoadAddresses, newRoadAddresses)
  }

  def fillCommonHistory(projectLinks: Seq[ProjectLink], existingRoadAddresses: Seq[RoadAddress], newRoadAddresses: Seq[RoadAddress]) : (Seq[RoadAddress], Seq[RoadAddress])= {
    val fillOperations: Seq[(Seq[ProjectLink], Seq[RoadAddress], Seq[RoadAddress]) => (Seq[RoadAddress], Seq[RoadAddress])] = Seq(
      applyTerminated,
      applyUnchanged,
      applyNew,
      applyTransfer
    )

    fillOperations.foldLeft((existingRoadAddresses, newRoadAddresses)){
      case ((pExistingRoadAddress, pNewRoadAddress), operation) =>
        operation(projectLinks, pExistingRoadAddress, pNewRoadAddress)
    }
  }
}
