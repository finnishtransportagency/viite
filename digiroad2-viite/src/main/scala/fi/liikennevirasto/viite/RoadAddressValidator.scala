package fi.liikennevirasto.viite

import fi.liikennevirasto.viite.dao.{ProjectDAO, RoadAddressDAO, RoadAddressProject}
import org.joda.time.format.DateTimeFormat

object RoadAddressValidator {

  def checkAvailable(number: Long, part: Long, currentProject: RoadAddressProject): Unit = {
    if (RoadAddressDAO.isNotAvailableForProject(number, part, currentProject.id)) {
      val fmt = DateTimeFormat.forPattern("dd.MM.yyyy")
      throw new ProjectValidationException(RoadNotAvailableMessage.format(number, part, currentProject.startDate.toString(fmt)))
    }
  }

  def checkNotReserved(number: Long, part: Long, currentProject: RoadAddressProject): Unit = {
    val project = ProjectDAO.roadPartReservedByProject(number, part, currentProject.id, withProjectId = true)
    if (project.nonEmpty) {
      throw new ProjectValidationException(s"TIE $number OSA $part on jo varattuna projektissa ${project.get}, tarkista tiedot")
    }
  }

  def checkProjectExists(id: Long): Unit = {
    if (ProjectDAO.getRoadAddressProjectById(id).isEmpty)
      throw new ProjectValidationException("Projektikoodilla ei löytynyt projektia")
  }

}
