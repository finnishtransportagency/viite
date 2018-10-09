package fi.liikennevirasto.viite

import fi.liikennevirasto.viite.dao._
import org.joda.time.format.DateTimeFormat

object RoadAddressValidator {
  val projectDAO = new ProjectDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  def checkReservedExistence(currentProject: RoadAddressProject, newRoadNumber: Long, newRoadPart: Long, linkStatus: LinkStatus, projectLinks: Seq[ProjectLink]): Unit = {
    throw new NotImplementedError("Will be implemented at VIITE-1539")
//    if (LinkStatus.New.value == linkStatus.value && RoadAddressDAO.fetchByRoadPart(newRoadNumber, newRoadPart, includeSuravage = true).nonEmpty) {
//      if (ProjectDAO.fetchReservedRoadParts(currentProject.id).find(p => p.roadNumber == newRoadNumber && p.roadPartNumber == newRoadPart).isEmpty) {
//        val fmt = DateTimeFormat.forPattern("dd.MM.yyyy")
//        throw new ProjectValidationException(RoadNotAvailableMessage.format(newRoadNumber, newRoadPart, currentProject.startDate.toString(fmt)))
//      }
//    }
  }

  def checkAvailable(number: Long, part: Long, currentProject: RoadAddressProject): Unit = {
    throw new NotImplementedError("Will be implemented at VIITE-1539")
//    if (RoadAddressDAO.isNotAvailableForProject(number, part, currentProject.id)) {
//      val fmt = DateTimeFormat.forPattern("dd.MM.yyyy")
//      throw new ProjectValidationException(RoadNotAvailableMessage.format(number, part, currentProject.startDate.toString(fmt)))
//    }
  }

  def checkNotReserved(number: Long, part: Long, currentProject: RoadAddressProject): Unit = {
    val project = projectReservedPartDAO.roadPartReservedByProject(number, part, currentProject.id, withProjectId = true)
    if (project.nonEmpty) {
      throw new ProjectValidationException(s"TIE $number OSA $part on jo varattuna projektissa ${project.get}, tarkista tiedot")
    }
  }

  def checkProjectExists(id: Long): Unit = {
    if (projectDAO.getRoadAddressProjectById(id).isEmpty)
      throw new ProjectValidationException("Projektikoodilla ei löytynyt projektia")
  }

}
