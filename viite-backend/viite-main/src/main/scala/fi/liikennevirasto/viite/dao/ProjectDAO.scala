package fi.liikennevirasto.viite.dao

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite._
import org.joda.time.DateTime
import org.postgresql.jdbc.PgArray
import org.slf4j.{Logger, LoggerFactory}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import slick.jdbc.StaticQuery.interpolation

//TODO naming SQL conventions

sealed trait ProjectState {
  def value: Int

  def description: String
}

object ProjectState {

  val values: Set[ProjectState] = Set(Incomplete, Deleted, ErrorInViite, InUpdateQueue, UpdatingToRoadNetwork, Accepted, Unknown)

  // These states are final
  val finalProjectStates: Set[Int] = Set(ProjectState.Accepted.value)

  def apply(value: Long): ProjectState = {
    values.find(_.value == value).getOrElse(Unknown)
  }
  case object ErrorInViite extends ProjectState {def value = 0; def description = "Virhe Viite-sovelluksessa"}
  case object Incomplete extends ProjectState {def value = 1; def description = "Keskeneräinen"}
  case object Deleted extends ProjectState {def value = 7; def description = "Poistettu projekti"}
  case object InUpdateQueue extends ProjectState {def value = 10; def description = "Odottaa tieverkolle päivittämistä"}
  case object UpdatingToRoadNetwork extends ProjectState {def value = 11; def description = "Päivitetään tieverkolle"}
  case object Accepted extends ProjectState {def value = 12; def description = "Hyväksytty"}
  case object Unknown extends ProjectState {def value = 99; def description = "Tuntematon"}
}

case class Project(id            : Long,
                   projectState  : ProjectState,
                   name          : String,
                   createdBy     : String,
                   createdDate   : DateTime,
                   modifiedBy    : String,
                   startDate     : DateTime,
                   dateModified  : DateTime,
                   additionalInfo: String,
                   reservedParts : Seq[ProjectReservedPart],
                   formedParts   : Seq[ProjectReservedPart],
                   statusInfo    : Option[String],
                   coordinates   : Option[ProjectCoordinates] = Some(ProjectCoordinates()),
                   elys          : Set[Int] = Set()
                  ) {
  def isReserved(roadNumber: Long, roadPartNumber: Long): Boolean = {
    reservedParts.exists(p => p.roadNumber == roadNumber && p.roadPartNumber == roadPartNumber)
  }
  def isFormed(roadNumber: Long, roadPartNumber: Long): Boolean = {
    formedParts.exists(p => p.roadNumber == roadNumber && p.roadPartNumber == roadPartNumber)
  }
}

case class ProjectCoordinates(x: Double = DefaultLatitude, y: Double = DefaultLongitude, zoom: Int = DefaultZoomLevel)

class ProjectDAO {
  val projectReservedPartDAO = new ProjectReservedPartDAO
  private def logger: Logger = LoggerFactory.getLogger(getClass)

  def create(project: Project): Unit = {
    sqlu"""
         insert into project (id, state, name, created_by, created_date, start_date ,modified_by, modified_date, add_info, status_info)
         values (${project.id}, ${project.projectState.value}, ${project.name}, ${project.createdBy}, current_timestamp, ${project.startDate}, ${project.createdBy}, current_timestamp, ${project.additionalInfo}, ${project.statusInfo})
         """.execute
  }

  def fetchAllIdsByLinkId(linkId: String): Seq[Long] =
    time(logger, """Get projects with given link id""") {
    val query =
      s"""SELECT P.ID
             FROM PROJECT P
            JOIN PROJECT_LINK PL ON P.ID=PL.PROJECT_ID
            WHERE P.STATE = ${ProjectState.Incomplete.value} AND PL.LINK_ID='$linkId'"""
    Q.queryNA[Long](query).list
  }

  def update(roadAddressProject: Project): Unit = {
    sqlu"""
         update project set state = ${roadAddressProject.projectState.value}, name = ${roadAddressProject.name}, modified_by = ${roadAddressProject.modifiedBy} ,modified_date = current_timestamp, add_info=${roadAddressProject.additionalInfo}, start_date=${roadAddressProject.startDate} where id = ${roadAddressProject.id}
         """.execute
  }

  def fetchProjectElyById(projectId: Long): Seq[Long] = {
    val query =
      s"""
         SELECT DISTINCT ELY
         FROM project_link
         WHERE project_id=$projectId
         union
         SELECT DISTINCT ELY
         FROM project_link_history
         WHERE project_id=$projectId
       """
    Q.queryNA[Long](query).list
  }

  @deprecated ("Tierekisteri connection has been removed from Viite. TRId to be removed, too.")
  def fetchByTRId(trProjectId: Long): Option[Project] = {
    time(logger, "Fetch project by tr_id") {
      fetch(query => s"""$query where tr_id = $trProjectId""").headOption
    }
  }

  def fetchById(projectId: Long, withNullElyFilter: Boolean = false): Option[Project] = {
    time(logger, "Fetch project by id") {
      if(withNullElyFilter)
        fetch(query => s"""$query where id =$projectId and ely is null""").headOption
      else
        fetch(query => s"""$query where id =$projectId""").headOption
    }
  }

  def fetchAllWithoutDeletedFilter(): List[Map[String, Any]] = {
    time(logger, s"Fetch all projects ") {
      fetchProjects(query => s"""$query WHERE state != ${ProjectState.Deleted.value} order by name, id, elys""")
    }
  }

  def fetchAllActiveProjects(): List[Map[String, Any]] = {
    time(logger, s"Fetch all active projects ") {
      fetchProjects(query => s"""$query WHERE state=${ProjectState.InUpdateQueue.value} OR state=${ProjectState.UpdatingToRoadNetwork.value} OR state=${ProjectState.ErrorInViite.value} OR state=${ProjectState.Incomplete.value} OR (state=${ProjectState.Accepted.value} and modified_date > now() - INTERVAL '2 DAY') order by name, id, elys """)
    }
  }

  def fetchProjectStatus(projectID: Long): Option[ProjectState] = {
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

  def fetchProjectStates(projectID: Set[Int]): List[(Int,Int)] = {
    val query =
      s""" SELECT id,state
            FROM project
            WHERE id in (${projectID.mkString(",")})
       """
    Q.queryNA[(Int,Int)](query).list
  }

  @deprecated ("Tierekisteri connection has been removed from Viite. TRId to be removed, too.")
  def assignNewProjectTRId(projectId: Long): Unit = {
    Q.updateNA(s"UPDATE PROJECT SET TR_ID = nextval('viite_project_seq') WHERE ID= $projectId").execute
  }

  @deprecated ("Tierekisteri connection has been removed from Viite. TRId to be removed, too.")
  def removeProjectTRId(projectId: Long): Unit = {
    Q.updateNA(s"UPDATE PROJECT SET TR_ID = NULL WHERE ID= $projectId").execute
  }

  def updateProjectStateInfo(stateInfo: String, projectId: Long): Unit = {
    Q.updateNA(s"UPDATE PROJECT SET STATUS_INFO = '$stateInfo' WHERE ID= $projectId").execute
  }

  def updateProjectCoordinates(projectId: Long, coordinates: ProjectCoordinates): Unit = {
    Q.updateNA(s"UPDATE PROJECT SET COORD_X = ${coordinates.x},COORD_Y = ${coordinates.y}, ZOOM = ${coordinates.zoom} WHERE ID= $projectId").execute
  }

  @deprecated ("Tierekisteri connection has been removed from Viite. TRId to be removed, too.")
  def fetchTRIdByProjectId(projectId: Long): Option[Long] = {
    Q.queryNA[Long](s"Select tr_id From Project WHERE Id=$projectId AND tr_id IS NOT NULL ").list.headOption
  }

  def updateProjectStatus(projectID: Long, state: ProjectState): Unit = {
    sqlu""" update project set state=${state.value} WHERE id=$projectID""".execute
  }

  def changeProjectStatusToAccepted(projectID: Long): Unit = {
    sqlu""" update project set state=${ProjectState.Accepted.value}, accepted_date=current_timestamp WHERE id=$projectID""".execute
  }

  /** Returns an id of a single project waiting for being updated to the road network. */
  def fetchSingleProjectIdWithInUpdateQueueStatus: Option[Long] = {
    val query =
      s"""
         SELECT id
         FROM project
         WHERE state=${ProjectState.InUpdateQueue.value}
         LIMIT 1
       """
    Q.queryNA[Long](query).firstOption
  }

  /** @return projects, that are currently at either <i>InUpdateQueue</i>, or in <i>UpdatingToRoadNetwork</i> ProjectState */
  def fetchProjectIdsWithToBePreservedStatus: List[Long] = {
    val query =
      s"""
         SELECT id
         FROM project
         WHERE state=${ProjectState.InUpdateQueue.value} OR state=${ProjectState.UpdatingToRoadNetwork.value}
       """
    Q.queryNA[Long](query).list
  }

  def isUniqueName(projectId: Long, projectName: String): Boolean = {
    val query =
      s"""
         SELECT *
         FROM project
         WHERE UPPER(name)=UPPER('$projectName') and state<>7
         LIMIT 1
       """
    val projects = Q.queryNA[Long](query).list
    projects.isEmpty || projects.contains(projectId)
  }

  //TODO: Add accepted date if we want to display it in the user interface
  private def fetch(queryFilter: String => String): Seq[Project] = {
    val query =
      s"""SELECT id, state, name, created_by, created_date, start_date, modified_by, COALESCE(modified_date, created_date),
           add_info, status_info, coord_x, coord_y, zoom
           FROM project"""

    Q.queryNA[(Long, Long, String, String, DateTime, DateTime, String, DateTime, String, Option[String], Double, Double, Int)](queryFilter(query)).list.map {
      case (id, state, name, createdBy, createdDate, start_date, modifiedBy, modifiedDate, addInfo, statusInfo, coordX, coordY, zoom) =>
        val projectState = ProjectState.apply(state)
        Project(id, projectState, name, createdBy, createdDate, modifiedBy, start_date, modifiedDate,
          addInfo, Seq(), Seq(), statusInfo, Some(ProjectCoordinates(coordX, coordY, zoom)), Set())
    }
  }

  implicit val getProjectMap: GetResult[Map[String, Any]] = new GetResult[Map[String, Any]] {
    def apply(r: PositionedResult): Map[String, Any] = {
      val status = r.nextLong()
      Map(
        "statusCode" -> status,
        "status" -> ProjectState(status),
        "statusDescription" -> ProjectState(status).description
      ) ++
      Map(
        "id" -> r.nextLong(),
        "name" -> r.nextString(),
        "createdBy" -> r.nextString(),
        "createdDate" -> r.nextDate().toString,
        "modifiedBy" -> r.nextString(),
        "dateModified" -> r.nextDate().toString,
        "additionalInfo" -> r.nextString(),
        "startDate" -> r.nextDate().toString,
        "statusInfo" -> r.nextStringOption(),
        "coordX" -> r.nextDouble(),
        "coordY" -> r.nextDouble(),
        "zoomLevel" -> r.nextInt(),
        // Elys list from db to Set()
        "elys" -> r.nextObjectOption().map(_.asInstanceOf[PgArray].getArray.asInstanceOf[Array[Integer]].toSet.asInstanceOf[Set[Int]]).getOrElse(Set())
      )
    }
  }
  private def fetchProjects(queryFilter: String => String): List[Map[String, Any]] = {
    val query =
      s"""SELECT state,id,"name",created_by,created_date,modified_by,COALESCE(modified_date, created_date),add_info,start_date,status_info,coord_x,coord_y,zoom,elys FROM project"""
    Q.queryNA[Map[String, Any]](queryFilter(query)).list
  }

  def updateProjectElys(projectId: Long, elys : Seq[Long]): Int = {
    time(logger, s"Update elys for project $projectId.") {
      if (elys.nonEmpty) {
        val query   = s"""UPDATE project p set elys = array[${elys.sorted.mkString(",")}] WHERE p.id = $projectId"""
        Q.updateNA(query).first
      } else -1
    }
  }
}
