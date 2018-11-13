package fi.liikennevirasto.viite.dao

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.ProjectState.{Incomplete, Saved2TR}
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{StaticQuery => Q}

//TODO naming SQL conventions

sealed trait ProjectState {
  def value: Int

  def description: String
}

object ProjectState {

  val values = Set(Closed, Incomplete, Sent2TR, ErrorInTR, TRProcessing, Saved2TR,
    Failed2GenerateTRIdInViite, Deleted, ErrorInViite, SendingToTR, Unknown)

  // These states are final
  val nonActiveStates = Set(ProjectState.Closed.value, ProjectState.Saved2TR.value)

  def apply(value: Long): ProjectState = {
    values.find(_.value == value).getOrElse(Closed)
  }

  case object Closed extends ProjectState {def value = 0; def description = "Suljettu"}
  case object Incomplete extends ProjectState {def value = 1; def description = "Keskeneräinen"}
  case object Sent2TR extends ProjectState {def value = 2; def description = "Lähetetty tierekisteriin"}
  case object ErrorInTR extends ProjectState {def value = 3; def description = "Virhe tierekisterissä"}
  case object TRProcessing extends ProjectState {def value = 4; def description = "Tierekisterissä käsittelyssä"}
  case object Saved2TR extends ProjectState{def value = 5; def description = "Viety tierekisteriin"}
  case object Failed2GenerateTRIdInViite extends ProjectState {def value = 6; def description = "Tierekisteri ID:tä ei voitu muodostaa"}
  case object Deleted extends ProjectState {def value = 7; def description = "Poistettu projekti"}
  case object ErrorInViite extends ProjectState {def value = 8; def description = "Virhe Viite-sovelluksessa"}
  case object SendingToTR extends ProjectState {def value = 9; def description = "Lähettää Tierekisteriin"}
  case object Unknown extends ProjectState {def value = 99; def description = "Tuntematon"}
}

case class RoadAddressProject(id: Long, status: ProjectState, name: String, createdBy: String, createdDate: DateTime,
                              modifiedBy: String, startDate: DateTime, dateModified: DateTime, additionalInfo: String,
                              reservedParts: Seq[ProjectReservedPart], statusInfo: Option[String], ely: Option[Long] = None, coordinates: Option[ProjectCoordinates] = Some(ProjectCoordinates())) {
  def isReserved(roadNumber: Long, roadPartNumber: Long): Boolean = {
    reservedParts.exists(p => p.roadNumber == roadNumber && p.roadPartNumber == roadPartNumber)
  }
}

case class ProjectCoordinates(x: Double = DefaultLatitude, y: Double = DefaultLongitude, zoom: Int = DefaultZoomLevel)

class ProjectDAO {
  val projectReservedPartDAO = new ProjectReservedPartDAO
  private def logger: Logger = LoggerFactory.getLogger(getClass)

  def createRoadAddressProject(roadAddressProject: RoadAddressProject): Unit = {
    sqlu"""
         insert into project (id, state, name, ely, created_by, created_date, start_date ,modified_by, modified_date, add_info, status_info)
         values (${roadAddressProject.id}, ${roadAddressProject.status.value}, ${roadAddressProject.name}, null, ${roadAddressProject.createdBy}, sysdate, ${roadAddressProject.startDate}, '-' , sysdate, ${roadAddressProject.additionalInfo}, ${roadAddressProject.statusInfo})
         """.execute
  }
  //Should be only one
  def getProjectsWithGivenLinkId(linkId: Long): Seq[Long] =
    time(logger, """Get projects with given link id""") {
    val query =
      s"""SELECT P.ID
             FROM PROJECT P
            JOIN PROJECT_LINK PL ON P.ID=PL.PROJECT_ID
            WHERE P.STATE = ${Incomplete.value} AND PL.LINK_ID=$linkId"""
    Q.queryNA[Long](query).list
  }

  def updateRoadAddressProject(roadAddressProject: RoadAddressProject): Unit = {
    sqlu"""
         update project set state = ${roadAddressProject.status.value}, name = ${roadAddressProject.name}, modified_by = '-' ,modified_date = sysdate, add_info=${roadAddressProject.additionalInfo}, start_date=${roadAddressProject.startDate}, ely = ${roadAddressProject.ely} where id = ${roadAddressProject.id}
         """.execute
  }

  def getProjectEly(roadAddressProjectId: Long): Option[Long] = {
    val query =
      s"""
         SELECT ELY
         FROM project
         WHERE id=$roadAddressProjectId
       """
    Q.queryNA[Option[Long]](query).firstOption.flatten
  }

  def updateProjectEly(roadAddressProjectId: Long, ely: Long): Unit = {
    sqlu"""
       update project set ely = $ely, modified_date = sysdate where id =  ${roadAddressProjectId}
      """.execute
  }

  def getRoadAddressProjectById(projectId: Long): Option[RoadAddressProject] = {
    time(logger, "Get road address project by id") {
      val where = s""" where id =$projectId"""
      val query =
        s"""SELECT id, state, name, created_by, created_date, start_date, modified_by, COALESCE(modified_date, created_date),
           add_info, ely, status_info, coord_x, coord_y, zoom
           FROM project $where"""
      Q.queryNA[(Long, Long, String, String, DateTime, DateTime, String, DateTime, String, Option[Long], Option[String], Double, Double, Int)](query).list.map {
        case (id, state, name, createdBy, createdDate, start_date, modifiedBy, modifiedDate, addInfo,
        ely, statusInfo, coordX, coordY, zoom) if ely.contains(-1L) =>
          RoadAddressProject(id, ProjectState.apply(state), name, createdBy, createdDate, modifiedBy, start_date, modifiedDate,
            addInfo, projectReservedPartDAO.fetchReservedRoadParts(id), statusInfo, None, Some(ProjectCoordinates(coordX, coordY, zoom)))
        case (id, state, name, createdBy, createdDate, start_date, modifiedBy, modifiedDate, addInfo,
        ely, statusInfo, coordX, coordY, zoom) =>
          RoadAddressProject(id, ProjectState.apply(state), name, createdBy, createdDate, modifiedBy, start_date, modifiedDate,
            addInfo, projectReservedPartDAO.fetchReservedRoadParts(id), statusInfo, ely, Some(ProjectCoordinates(coordX, coordY, zoom)))
      }.headOption
    }
  }

  def getProjects(projectId: Long = 0, withNullElyFilter: Boolean = false): List[RoadAddressProject] = {
    time(logger, "Get road address projects") {
      val filter = projectId match {
        case 0 => if (withNullElyFilter) s""" where ELY IS NULL """ else ""
        case _ => if (withNullElyFilter) s""" where id =$projectId AND ELY IS NULL """ else s""" where id =$projectId """
      }

      val query =
        s"""SELECT id, state, name, created_by, created_date, start_date, modified_by, COALESCE(modified_date, created_date),
            add_info, status_info, ely, coord_x, coord_y, zoom
           FROM project $filter order by ely nulls first, name, id """
      Q.queryNA[(Long, Long, String, String, DateTime, DateTime, String, DateTime, String, Option[String], Option[Long], Double, Double, Int)](query).list.map {
        case (id, state, name, createdBy, createdDate, start_date, modifiedBy, modifiedDate, addInfo, statusInfo, ely, coordX, coordY, zoom) => {
          val projectState = ProjectState.apply(state)
          val reservedRoadParts = if (projectState == Saved2TR)
            projectReservedPartDAO.fetchHistoryRoadParts(id).distinct
          else if (projectId != 0)
            projectReservedPartDAO.fetchReservedRoadParts(id).distinct
          else
            Seq()
          RoadAddressProject(id, projectState, name, createdBy, createdDate, modifiedBy, start_date,
            modifiedDate, addInfo, reservedRoadParts, statusInfo, ely, Some(ProjectCoordinates(coordX, coordY, zoom)))
        }
      }
    }
  }

  def getProjectStatus(projectID: Long): Option[ProjectState] = {
    val query =
      s""" SELECT state
            FROM project
            WHERE id=$projectID
   """
    Q.queryNA[Long](query).firstOption match {
      case Some(statenumber) => Some(ProjectState.apply(statenumber))
      case None => None
    }
  }

  def addRotatingTRProjectId(projectId: Long): Unit = {
    Q.updateNA(s"UPDATE PROJECT SET TR_ID = VIITE_PROJECT_SEQ.nextval WHERE ID= $projectId").execute
  }

  def removeRotatingTRProjectId(projectId: Long): Unit = {
    Q.updateNA(s"UPDATE PROJECT SET TR_ID = NULL WHERE ID= $projectId").execute
  }

  def updateProjectStateInfo(stateInfo: String, projectId: Long): Unit = {
    Q.updateNA(s"UPDATE PROJECT SET STATUS_INFO = '$stateInfo' WHERE ID= $projectId").execute
  }

  def updateProjectCoordinates(projectId: Long, coordinates: ProjectCoordinates): Unit = {
    Q.updateNA(s"UPDATE PROJECT SET COORD_X = ${coordinates.x},COORD_Y = ${coordinates.y}, ZOOM = ${coordinates.zoom} WHERE ID= $projectId").execute
  }

  def getRotatingTRProjectId(projectId: Long): Seq[Long] = {
    Q.queryNA[Long](s"Select tr_id From Project WHERE Id=$projectId AND tr_id IS NOT NULL ").list
  }

  def updateProjectStatus(projectID: Long, state: ProjectState) {
    sqlu""" update project set state=${state.value} WHERE id=$projectID""".execute
  }

  def getProjectsWithWaitingTRStatus: List[Long] = {
    val query =
      s"""
         SELECT id
         FROM project
         WHERE state=${ProjectState.Sent2TR.value} OR state=${ProjectState.TRProcessing.value}
       """
    Q.queryNA[Long](query).list
  }

  def getProjectsWithSendingToTRStatus: List[Long] = {
    val query =
      s"""
         SELECT id
         FROM project
         WHERE state=${ProjectState.SendingToTR.value}
       """
    Q.queryNA[Long](query).list
  }

  def uniqueName(projectId: Long, projectName: String): Boolean = {
    val query =
      s"""
         SELECT *
         FROM project
         WHERE UPPER(name)=UPPER('$projectName') and state<>7 and ROWNUM=1
       """
    val projects = Q.queryNA[Long](query).list
    projects.isEmpty || projects.contains(projectId)
  }
}
