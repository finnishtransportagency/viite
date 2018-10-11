package fi.liikennevirasto.viite

import fi.liikennevirasto.viite.dao._
import org.joda.time.format.DateTimeFormat

object RoadAddressValidator {
  val projectDAO = new ProjectDAO
  val roadwayDAO = new RoadwayDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  def checkReservedExistence(currentProject: RoadAddressProject, newRoadNumber: Long, newRoadPart: Long, linkStatus: LinkStatus, projectLinks: Seq[ProjectLink]): Unit = {
    if (LinkStatus.New.value == linkStatus.value && roadwayDAO.fetchAllByRoadAndPart(newRoadNumber, newRoadPart).nonEmpty) {
      if (!projectReservedPartDAO.fetchReservedRoadParts(currentProject.id).exists(p => p.roadNumber == newRoadNumber && p.roadPartNumber == newRoadPart)) {
        val fmt = DateTimeFormat.forPattern("dd.MM.yyyy")
        throw new ProjectValidationException(RoadNotAvailableMessage.format(newRoadNumber, newRoadPart, currentProject.startDate.toString(fmt)))
      }
    }
  }

  def checkAvailable(number: Long, part: Long, currentProject: RoadAddressProject): Unit = {
    if (projectReservedPartDAO.isNotAvailableForProject(number, part, currentProject.id)) {
      val fmt = DateTimeFormat.forPattern("dd.MM.yyyy")
      throw new ProjectValidationException(RoadNotAvailableMessage.format(number, part, currentProject.startDate.toString(fmt)))
    }
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
