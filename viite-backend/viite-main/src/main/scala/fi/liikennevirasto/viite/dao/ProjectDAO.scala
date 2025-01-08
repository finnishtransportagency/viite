package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite._
import fi.vaylavirasto.viite.dao.BaseDAO
import fi.vaylavirasto.viite.model.RoadPart
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet


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
  def isReserved(roadPart: RoadPart): Boolean = {
    reservedParts.exists(p => p.roadPart == roadPart)
  }
  def isFormed(roadPart: RoadPart): Boolean = {
    formedParts.exists(p => p.roadPart == roadPart)
  }
}

case class ProjectCoordinates(x: Double = DefaultLatitude, y: Double = DefaultLongitude, zoom: Int = DefaultZoomLevel)

class ProjectDAO extends BaseDAO {
  val projectReservedPartDAO = new ProjectReservedPartDAO

  def create(project: Project): Unit = {
    runUpdateToDb(sql"""
         INSERT INTO project (id, state, name, created_by, created_date, start_date ,modified_by, modified_date, add_info, status_info)
         VALUES (${project.id}, ${project.projectState.value}, ${project.name}, ${project.createdBy}, current_timestamp,
                ${project.startDate}, ${project.createdBy}, current_timestamp, ${project.additionalInfo},
                ${project.statusInfo.getOrElse("")})
         """)
  }

  def fetchAllIdsByLinkId(linkId: String): Seq[Long] =
    time(logger, """Get projects with given link id""") {
    val query =
      sql"""
            SELECT p.id
            FROM project p
            JOIN project_link pl ON p.id=pl.project_id
            WHERE p.state = ${ProjectState.Incomplete.value} AND pl.link_id=$linkId
            """
    runSelectQuery(query.map(_.long(1)))
  }

  def update(roadAddressProject: Project): Unit = {
    runUpdateToDb(sql"""
         UPDATE project
         SET state = ${roadAddressProject.projectState.value}, name = ${roadAddressProject.name},
                            modified_by = ${roadAddressProject.modifiedBy}, modified_date = current_timestamp,
                            add_info = ${roadAddressProject.additionalInfo}, start_date=${roadAddressProject.startDate}
         WHERE id = ${roadAddressProject.id}
         """)
  }

  def fetchProjectElyById(projectId: Long): Seq[Long] = {
    val query =
      sql"""
         SELECT DISTINCT ely
         FROM project_link
         WHERE project_id=$projectId
         UNION
         SELECT DISTINCT ely
         FROM project_link_history
         WHERE project_id=$projectId
       """
    runSelectQuery(query.map(_.long(1)))
  }

  def fetchById(projectId: Long, withNullElyFilter: Boolean = false): Option[Project] = {
    time(logger, "Fetch project by id") {
      val whereClause = if (withNullElyFilter) {
        sqls"""WHERE id =$projectId AND ely is null"""
      } else {
        sqls"""WHERE id =$projectId"""
      }

      fetchByCondition(whereClause).headOption
    }
  }

  def fetchAllWithoutDeletedFilter(): List[Map[String, Any]] = {
    time(logger, s"Fetch all projects ") {
      fetchProjects(query => sqls"""$query WHERE state != ${ProjectState.Deleted.value} ORDER BY name, id, elys""")
    }
  }

  def fetchAllActiveProjects(): List[Map[String, Any]] = {
    time(logger, s"Fetch all active projects ") {
      fetchProjects(query =>
        sqls"""$query
            WHERE state=${ProjectState.InUpdateQueue.value}
            OR state=${ProjectState.UpdatingToRoadNetwork.value}
            OR state=${ProjectState.ErrorInViite.value}
            OR state=${ProjectState.Incomplete.value}
            OR (state=${ProjectState.Accepted.value} and modified_date > now() - INTERVAL '2 DAY')
            ORDER BY name, id, elys
           """)
    }
  }

  def fetchProjectStatus(projectId: Long): Option[ProjectState] = {
    val query =
      sql"""
            SELECT state
            FROM project
            WHERE id=$projectId
   """
    runSelectSingleFirstOptionWithType[Long](query).map(ProjectState.apply)
  }

  def fetchProjectStates(projectIds: Set[Int]): List[(Int,Int)] = {
    val query =
      sql"""
            SELECT id,state
            FROM project
            WHERE id in ($projectIds)
       """
       runSelectQuery(query.map(rs => (rs.int(1),rs.int(2))))
  }

  def updateProjectStateInfo(stateInfo: String, projectId: Long): Unit = {
    runUpdateToDb(
      sql"""
           UPDATE project
           SET status_info = $stateInfo
           WHERE id = $projectId
           """
    )
  }

  def updateProjectCoordinates(projectId: Long, coordinates: ProjectCoordinates): Unit = {
    runUpdateToDb(sql"""
                    UPDATE project
                    SET coord_x = ${coordinates.x},coord_y = ${coordinates.y}, zoom = ${coordinates.zoom}
                    WHERE id = $projectId
                    """
                    )
  }


  def updateProjectStatus(projectId: Long, state: ProjectState): Unit = {
    runUpdateToDb(
      sql"""
           UPDATE project
           SET state=${state.value}
           WHERE id=$projectId
           """
    )
  }

  def updateProjectStatusWithInfo(projectId: Long, state: ProjectState, statusInfo: String): Unit = {
    runUpdateToDb(
      sql"""
           UPDATE project
           SET state=${state.value}, STATUS_INFO=$statusInfo
           WHERE id=$projectId
           """
    )
  }

  def changeProjectStatusToAccepted(projectId: Long): Unit = {
    runUpdateToDb(
      sql"""
           UPDATE project
           SET state=${ProjectState.Accepted.value}, accepted_date=current_timestamp
           WHERE id=$projectId
           """
    )
  }

  /** Returns an id of a single project waiting for being updated to the road network. */
  def fetchSingleProjectIdWithInUpdateQueueStatus: Option[Long] = {
    val query =
      sql"""
         SELECT id
         FROM project
         WHERE state=${ProjectState.InUpdateQueue.value}
         LIMIT 1
       """
    runSelectSingleFirstOptionWithType[Long](query)
  }

  /** @return projects, that are currently at either <i>InUpdateQueue</i>, or in <i>UpdatingToRoadNetwork</i> ProjectState */
  def fetchProjectIdsWithToBePreservedStatus: List[Long] = {
    val query =
      sql"""
         SELECT id
         FROM project
         WHERE state=${ProjectState.InUpdateQueue.value} OR state=${ProjectState.UpdatingToRoadNetwork.value}
       """
    runSelectQuery(query.map(_.long(1)))
  }

  def isUniqueName(projectId: Long, projectName: String): Boolean = {
    val query =
      sql"""
         SELECT id
         FROM project
         WHERE UPPER(name)=UPPER($projectName)
         AND state <> ${ProjectState.Deleted.value}
         LIMIT 1
       """
    val projects = runSelectQuery(query.map(_.long(1)))
    projects.isEmpty || projects.contains(projectId)
  }

  //TODO: Add accepted date if we want to display it in the user interface
  private def fetchByCondition(whereClause: SQLSyntax): Seq[Project] = {
    val query =
      sql"""
            SELECT id, state, name, created_by, created_date, start_date, modified_by,
              COALESCE(modified_date, created_date) AS modified_date,
              add_info, status_info, coord_x, coord_y, zoom
            FROM project
            $whereClause
           """

    runSelectQuery(query.map(rs => Project(
      id             = rs.long("id"),
      projectState   = ProjectState(rs.long("state")),
      name           = rs.string("name"),
      createdBy      = rs.string("created_by"),
      createdDate    = rs.jodaDateTime("created_date"),
      startDate      = rs.jodaDateTime("start_date"),
      modifiedBy     = rs.string("modified_by"),
      dateModified   = rs.jodaDateTime("modified_date"),
      additionalInfo = rs.string("add_info"),
      reservedParts  = Seq(),
      formedParts    = Seq(),
      statusInfo     = rs.stringOpt("status_info"),
      coordinates    = {
        val x        = rs.doubleOpt("coord_x").getOrElse(0.0)
        val y        = rs.doubleOpt("coord_y").getOrElse(0.0)
        val z        = rs.intOpt("zoom").getOrElse(0)
        Some(ProjectCoordinates(x, y, z))
      },
      elys           = Set()
    )))
  }


  private def toProjectMap(rs: WrappedResultSet): Map[String, Any] = {
    val status = rs.long("state")

    Map(
      "statusCode" -> status,
      "status" -> ProjectState(status),
      "statusDescription" -> ProjectState(status).description,
      "id" -> rs.long("id"),
      "name" -> rs.string("name"),
      "createdBy" -> rs.string("created_by"),
      "createdDate" -> rs.date("created_date").toString,
      "modifiedBy" -> rs.string("modified_by"),
      "dateModified" -> rs.date("modified_date").toString,
      "additionalInfo" -> rs.string("add_info"),
      "startDate" -> rs.date("start_date").toString,
      "statusInfo" -> rs.stringOpt("status_info"),
      "coordX" -> rs.doubleOpt("coord_x").getOrElse(0.0),
      "coordY" -> rs.doubleOpt("coord_y").getOrElse(0.0),
      "zoomLevel" -> rs.intOpt("zoom").getOrElse(0),
      "elys" -> Option(rs.array("elys"))
        .flatMap(arr => Option(arr.getArray))
        .map {
          case a: Array[Array[Integer]] => a(0).map(_.intValue).toSet // Take first array from nested array
          case a: Array[Integer] => a.map(_.intValue).toSet // Handle normal array
        }
        .getOrElse(Set.empty[Int])
    )
  }

  private def fetchProjects(queryFilter: SQLSyntax => SQLSyntax): List[Map[String, Any]] = {
    val baseQuery =
      sqls"""
           SELECT state,id,"name",created_by,created_date,modified_by,
            COALESCE(modified_date, created_date) as modified_date,
            add_info,start_date,status_info,coord_x,coord_y,zoom,elys
           FROM project
           """
    val query = sql"$baseQuery ${queryFilter(SQLSyntax.empty)}"

    runSelectQuery(query.map(toProjectMap))
  }

  def updateProjectElys(projectId: Long, elys : Seq[Long]): Int = {
    time(logger, s"Update elys for project $projectId.") {
      if (elys.nonEmpty) {
        val query   =
          sql"""
               UPDATE project p
               SET elys = ARRAY[${elys.sorted.toArray}]::integer[]
               WHERE p.id = $projectId
               """
        runUpdateToDb(query)
      } else -1
    }
  }
}
