package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite._
import fi.vaylavirasto.viite.model.{Discontinuity, RoadAddressChangeType, RoadPart, Track}
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import scalikejdbc._

//TODO naming SQL conventions

case class ProjectReservedPart(id: Long, roadPart: RoadPart, addressLength: Option[Long] = None, discontinuity: Option[Discontinuity] = None, ely: Option[Long] = None, newLength: Option[Long] = None, newDiscontinuity: Option[Discontinuity] = None, newEly: Option[Long] = None, startingLinkId: Option[String] = None) {
  def holds(baseRoadAddress: BaseRoadAddress): Boolean = {
    roadPart == baseRoadAddress.roadPart
  }
}

object ProjectReservedPart extends SQLSyntaxSupport[ProjectReservedPart] {
  override val tableName = "project_reserved_road_part"

  // For basic table operations
  def apply(rs: WrappedResultSet): ProjectReservedPart = new ProjectReservedPart(
    id = rs.long("id"),
    roadPart = RoadPart(rs.long("road_number"), rs.long("road_part_number"))
  )

  // For history queries - matches original: (length, discontinuity, ely, length, discontinuity, ely, startingLinkId)
  def fromHistoryQuery(rs: WrappedResultSet): ProjectReservedPart = new ProjectReservedPart(
    id               = noReservedPartId,
    roadPart         = RoadPart(
      roadNumber     = rs.long("road_number"),
      partNumber     = rs.long("road_part_number")
    ),
    addressLength    = rs.longOpt("length"),
    discontinuity    = rs.longOpt("discontinuity_type").map(Discontinuity.apply),
    ely              = rs.longOpt("ely"),
    newLength        = rs.longOpt("length"), // same as addressLength
    newDiscontinuity = rs.longOpt("discontinuity_type").map(Discontinuity.apply), // same as discontinuity
    newEly           = rs.longOpt("ely"), // same as ely
    startingLinkId   = rs.stringOpt("link_id")
  )

  // For formed queries - matches original: (None, None, None, newLength, newDiscontinuity, newEly, startingLinkId)
  def fromFormedQuery(rs: WrappedResultSet): ProjectReservedPart = new ProjectReservedPart(
    id               = rs.long("id"),
    roadPart         = RoadPart(
      roadNumber     = rs.long("road_number"),
      partNumber     = rs.long("road_part_number")
    ),
    addressLength    = None,
    discontinuity    = None,
    ely              = None,
    newLength        = rs.longOpt("length_new"),
    newDiscontinuity = rs.longOpt("discontinuity_new").map(Discontinuity.apply),
    newEly           = rs.longOpt("ely_new"),
    startingLinkId   = rs.stringOpt("first_link")
  )

  // For reserved queries - matches original: (length, discontinuity, ely, None, None, None, startingLinkId)
  def fromReservedQuery(rs: WrappedResultSet): ProjectReservedPart = new ProjectReservedPart(
    id               = rs.long("id"),
    roadPart         = RoadPart(
      roadNumber     = rs.long("road_number"),
      partNumber     = rs.long("road_part_number")
    ),
    addressLength    = rs.longOpt("length"),
    discontinuity    = rs.longOpt("discontinuity").map(Discontinuity.apply),
    ely              = rs.longOpt("ely"),
    newLength        = None,
    newDiscontinuity = None,
    newEly           = None,
    startingLinkId   = rs.stringOpt("first_link")
  )
}

class ProjectReservedPartDAO extends BaseDAO {

  /**
   * Removes reserved road part and deletes the project links associated to it.
   * Requires links that have been transferred to this road part to be reverted before
   * or this will fail.
   *
   * @param projectId Project's id
   * @param roadPart  Road part of the reserved part to remove
   */
  def removeReservedRoadPartAndChanges(projectId: Long, roadPart: RoadPart): Unit = {
    time(logger, s"Remove reserved road part $roadPart from project $projectId") {
      runUpdateToDb(sql"""DELETE FROM roadway_changes_link WHERE project_id = $projectId""")
      runUpdateToDb(sql"""DELETE FROM roadway_changes WHERE project_id = $projectId""")
      runUpdateToDb(
        sql"""
            DELETE FROM project_link
            WHERE project_id = $projectId
            AND (EXISTS (
              SELECT 1
              FROM roadway ra, linear_location lc
              WHERE ra.id = roadway_id
              AND ra.road_number = ${roadPart.roadNumber}
              AND ra.road_part_number = ${roadPart.partNumber}
              ))
            OR (
              road_number = ${roadPart.roadNumber}
              AND road_part_number = ${roadPart.partNumber}
              AND (status != ${RoadAddressChangeType.NotHandled.value})
          )
        """)
      runUpdateToDb(
        sql"""
             DELETE FROM project_reserved_road_part
             WHERE project_id = $projectId
             AND road_number = ${roadPart.roadNumber}
             AND road_part_number = ${roadPart.partNumber}
             """
      )
    }
  }

  def removeReservedRoadPart(projectId: Long, roadPart: RoadPart): Unit = {
    time(logger, s"Remove ") {
      runUpdateToDb(sql"""DELETE FROM roadway_changes_link WHERE project_id = $projectId""")
      runUpdateToDb(sql"""DELETE FROM roadway_changes WHERE project_id = $projectId""")
      runUpdateToDb(
        sql"""
             DELETE FROM project_reserved_road_part
             WHERE project_id = $projectId
             AND road_number = ${roadPart.roadNumber}
             AND road_part_number = ${roadPart.partNumber}
             """
      )
    }
  }

  def removeReservedRoadPartsByProject(projectId: Long): Unit = {
    time(logger, s"Remove reserved road parts by project $projectId") {
      runUpdateToDb(sql"""DELETE FROM roadway_changes_link WHERE project_id = $projectId""")
      runUpdateToDb(sql"""DELETE FROM roadway_changes WHERE project_id = $projectId""")
      runUpdateToDb(sql"""DELETE FROM project_reserved_road_part WHERE project_id = $projectId""")
    }
  }

  def fetchHistoryRoadParts(projectId: Long): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch reserved road parts for project $projectId") {
      val sql =
        sql"""
            SELECT road_number, road_part_number, length, ely,
            (
              SELECT iplh.discontinuity_type
              FROM project_link_history iplh
              WHERE iplh.road_number = plh.road_number
              AND iplh.road_part_number = plh.road_part_number
              AND iplh.end_addr_m = plh.length LIMIT 1
              ) AS discontinuity_type,
            (
              SELECT iplh.link_id
              FROM project_link_history iplh
              WHERE iplh.road_number = plh.road_number
              AND iplh.road_part_number = plh.road_part_number
              AND iplh.START_ADDR_M = 0 LIMIT 1
              ) AS link_id
            FROM
            (
              SELECT road_number, road_part_number, MAX(end_addr_m) AS length, ely
           	  FROM project_link_history
           	  WHERE project_id = $projectId
           	  GROUP BY (road_number, road_part_number, ely)
              ) plh
            ORDER BY plh.road_number, plh.road_part_number
        """
      runSelectQuery(sql.map(ProjectReservedPart.fromHistoryQuery))
    }
  }

  def fetchProjectReservedRoadPartsByProjectId(projectId: Long): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch project reserved road parts for project $projectId") {
      val query =
        sql"""
             SELECT rp.id, rp.road_number, rp.road_part_number
             FROM project_reserved_road_part rp
             WHERE rp.project_id = $projectId
             """
      runSelectQuery(query.map(ProjectReservedPart.apply))
    }
  }

  def fetchReservedRoadParts(projectId: Long): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch reserved road parts for project $projectId") {
      val query =
        sql"""
          SELECT id, road_number, road_part_number, length, ely,
          (SELECT DISCONTINUITY
            FROM roadway ra
            WHERE ra.road_number = gr.road_number
            AND ra.road_part_number = gr.road_part_number
            AND ra.end_date IS NULL
            AND ra.valid_to IS NULL
            AND end_addr_m = gr.length
            LIMIT 1) as discontinuity,
          (SELECT link_id
            FROM project_link pl
            WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number
            AND pl.road_part_number = gr.road_part_number
            AND PL.status != ${RoadAddressChangeType.Termination.value}
            AND PL.track IN (${Track.Combined.value}, ${Track.RightSide.value})
            LIMIT 1) AS first_link
          FROM (
            SELECT rp.id, rp.project_id, rp.road_number, rp.road_part_number,
              (SELECT MAX(ra.end_addr_m)
                FROM roadway ra
                WHERE ra.road_number = rp.road_number
                AND ra.road_part_number = rp.road_part_number
                AND ra.end_date IS NULL
                AND ra.valid_to IS NULL) as length,
              (SELECT MAX(ra.ely)
                FROM roadway ra
                WHERE ra.road_number = rp.road_number
                AND ra.road_part_number = rp.road_part_number
                AND ra.end_date IS NULL
                AND ra.valid_to IS NULL) as ely
              FROM project_reserved_road_part rp
              LEFT JOIN project_link pl ON (
                pl.project_id = rp.project_id
                AND pl.road_number = rp.road_number
                AND pl.road_part_number = rp.road_part_number
                AND pl.status != ${RoadAddressChangeType.Termination.value}
                )
              LEFT JOIN Roadway ra ON (ra.Id = pl.Roadway_Id OR (
                ra.road_number = rp.road_number
                AND ra.road_part_number = rp.road_part_number
                AND ra.end_date IS NULL
                AND ra.valid_to IS NULL
                ))
              LEFT JOIN Linear_Location lc ON (lc.Id = pl.Linear_location_id)
              WHERE
                rp.project_id = $projectId
                GROUP BY rp.id, rp.project_id, rp.road_number, rp.road_part_number
            ) gr order by gr.road_number, gr.road_part_number
            """
      /* TODO Convert to Postgis something like this: (maybe already converted (LIMIT 1) - have to test!)
            s"""
      SELECT id, road_number, road_part_number, length, length_new,
        ely, ely_new,
        (SELECT DISCONTINUITY FROM roadway ra WHERE ra.road_number = gr.road_number AND
          ra.road_part_number = gr.road_part_number AND ra.end_date IS NULL AND ra.valid_to IS NULL
          AND end_addr_m = gr.length LIMIT 1) as discontinuity,
        (SELECT discontinuity_type FROM project_link pl WHERE pl.project_id = gr.project_id
          AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
          AND PL.status != 5 AND PL.track IN (0,1)
          AND end_addr_m = gr.length_new LIMIT 1) as discontinuity_new,
        (SELECT link_id FROM project_link pl
          WHERE pl.project_id = gr.project_id
          AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
          AND PL.status != 5 AND PL.track IN (0,1) AND pl.START_ADDR_M = 0
          AND pl.end_addr_m > 0 LIMIT 1) as first_link
        FROM (
          SELECT rp.id, rp.project_id, rp.road_number, rp.road_part_number,
            MAX(ra.end_addr_m) as length,
            MAX(pl.end_addr_m) as length_new,
            MAX(ra.ely) as ely,
            MAX(pl.ely) as ely_new
            FROM project_reserved_road_part rp LEFT JOIN
            project_link pl ON (pl.project_id = rp.project_id AND pl.road_number = rp.road_number AND
            pl.road_part_number = rp.road_part_number AND pl.status != 5)
            LEFT JOIN Roadway ra ON (ra.Id = pl.Roadway_Id OR (ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND ra.end_date IS NULL AND ra.valid_to IS NULL))
            LEFT JOIN Linear_Location lc ON (lc.Id = pl.Linear_location_id)
            WHERE
              rp.project_id = $projectId
              GROUP BY rp.id, rp.project_id, rp.road_number, rp.road_part_number
          ) gr"""
     */
      runSelectQuery(query.map(ProjectReservedPart.fromReservedQuery))
    }
  }

  def fetchFormedRoadParts(projectId: Long, withProjectId: Boolean = true): Seq[ProjectReservedPart] = {
    (formedByIncrease(projectId, withProjectId) ++ formedByReduction(projectId, withProjectId)).sortBy(p => p.roadPart)
  }

  def formedByIncrease(projectId: Long, withProjectId: Boolean = true): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch formed road parts for project: $projectId") {
      val projectFilter = if (withProjectId && projectId != 0) sqls"rp.project_id = $projectId" else sqls"rp.project_id != $projectId"
      val query = sql"""
      WITH pl_filtered AS (
        SELECT
          pl.project_id,
          pl.road_number,
          pl.road_part_number,
          pl.end_addr_m,
          pl.discontinuity_type,
          pl.link_id,
          pl.ely,
          ROW_NUMBER() OVER (
            PARTITION BY pl.project_id, pl.road_number, pl.road_part_number
            ORDER BY pl.link_id
          ) AS rn_first_link
        FROM PROJECT_LINK pl
        WHERE pl.status NOT IN (0, ${RoadAddressChangeType.Termination.value})
        AND pl.track IN (${Track.Combined.value}, ${Track.RightSide.value})
      ),
      max_values AS (
        SELECT
          pl.project_id,
          pl.road_number,
          pl.road_part_number,
          MAX(pl.end_addr_m) AS length_new,
          MAX(pl.ely) AS ely_new
        FROM pl_filtered pl
        GROUP BY pl.project_id, pl.road_number, pl.road_part_number
      ),
      discontinuity_new AS (
        SELECT DISTINCT
          pl.project_id,
          pl.road_number,
          pl.road_part_number,
          pl.discontinuity_type
        FROM pl_filtered pl
        INNER JOIN max_values mv
          ON pl.project_id = mv.project_id
          AND pl.road_number = mv.road_number
          AND pl.road_part_number = mv.road_part_number
          AND pl.end_addr_m = mv.length_new
      ),
      first_link AS (
        SELECT
          pl.project_id,
          pl.road_number,
          pl.road_part_number,
          pl.link_id
        FROM pl_filtered pl
        WHERE rn_first_link = 1
      )
      SELECT
        rp.id,
        rp.road_number,
        rp.road_part_number,
        mv.length_new,
        mv.ely_new,
        dn.discontinuity_type AS discontinuity_new,
        fl.link_id AS first_link
      FROM project_reserved_road_part rp
      INNER JOIN max_values mv
        ON mv.project_id = rp.project_id AND mv.road_number = rp.road_number AND mv.road_part_number = rp.road_part_number
      LEFT JOIN discontinuity_new dn
        ON dn.project_id = rp.project_id AND dn.road_number = rp.road_number AND dn.road_part_number = rp.road_part_number
      LEFT JOIN first_link fl
        ON fl.project_id = rp.project_id AND fl.road_number = rp.road_number AND fl.road_part_number = rp.road_part_number
      WHERE $projectFilter
      ORDER BY rp.road_number, rp.road_part_number
    """
      /* TODO: Convert to Postgis something like this: (maybe done already (LIMIT 1) - have to test!)
            s"""
      SELECT id, road_number, road_part_number, length, length_new,
        ely, ely_new,
        (SELECT DISCONTINUITY FROM roadway ra WHERE ra.road_number = gr.road_number AND
        ra.road_part_number = gr.road_part_number AND ra.end_date IS NULL AND ra.valid_to IS NULL
        AND end_addr_m = gr.length LIMIT 1) as discontinuity,
        (SELECT discontinuity_type FROM project_link pl WHERE pl.project_id = gr.project_id
        AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
        AND PL.status != 5 AND PL.track IN (0,1)
        AND end_addr_m = gr.length_new LIMIT 1) as discontinuity_new,
        (SELECT link_id FROM project_link pl
          WHERE pl.project_id = gr.project_id
          AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
          AND PL.status != 5 AND PL.track IN (0,1) AND pl.START_ADDR_M = 0
          AND pl.end_addr_m > 0 LIMIT 1) as first_link
        FROM (
          SELECT rp.id, rp.project_id, rp.road_number, rp.road_part_number,
            MAX(ra.end_addr_m) as length,
            MAX(pl.end_addr_m) as length_new,
            MAX(ra.ely) as ely,
            MAX(pl.ely) as ely_new
            FROM project_reserved_road_part rp
            LEFT JOIN project_link pl ON (pl.project_id = rp.project_id AND pl.road_number = rp.road_number AND pl.road_part_number = rp.road_part_number)
            LEFT JOIN roadway ra ON ((ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number) OR ra.id = pl.roadway_id)
            LEFT JOIN linear_location lc ON (lc.Id = pl.Linear_Location_Id)
            WHERE
              rp.road_number = $roadNumber AND rp.road_part_number = $roadPartNumber AND
              ra.end_date IS NULL AND ra.valid_to IS NULL AND
              (PL.status IS NULL OR (PL.status != 5 AND PL.track IN (0,1)))
            GROUP BY rp.id, rp.project_id, rp.road_number, rp.road_part_number
            ) gr"""
     */
      runSelectQuery(query.map(ProjectReservedPart.fromFormedQuery))

    }
  }

  def formedByReduction(projectId: Long, withProjectId: Boolean = true): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch formed road parts for project: $projectId") {
      val filter = if (withProjectId && projectId != 0) sqls" rp.project_id = $projectId " else sqls" rp.project_id != $projectId "
      val query =
        sql"""
            SELECT id, road_number, road_part_number, length_new, ely_new, (
              SELECT discontinuity_type
              FROM project_link pl
              WHERE pl.project_id = projectid
              AND pl.road_number = road_number
              AND pl.road_part_number = road_part_number
              AND PL.status != ${RoadAddressChangeType.Termination.value}
              AND PL.track IN (${Track.Combined.value}, ${Track.RightSide.value})
              AND end_addr_m = length_new
              LIMIT 1) AS discontinuity_new,
            (SELECT link_id
            FROM project_link pl
            WHERE pl.project_id = projectid
            AND pl.road_number = road_number
            AND pl.road_part_number = road_part_number
            AND PL.status != ${RoadAddressChangeType.Termination.value}
            AND PL.track IN (${Track.Combined.value}, ${Track.RightSide.value})
            LIMIT 1) AS first_link
            FROM (
              SELECT DISTINCT rp.id, pl.project_id AS projectid, rw.road_number AS road_number, rw.road_part_number AS road_part_number, MAX(pl.end_addr_m) AS length_new,
              MAX(pl.ely) AS ely_new
              FROM linear_location lc, roadway rw, project_link pl, project_reserved_road_part rp
              WHERE rw.roadway_number = lc.roadway_number
              AND rw.road_Number = pl.road_number
              AND rw.road_part_number = pl.road_part_number
              AND rp.project_id = pl.project_id
              AND rp.road_number = pl.road_number
              AND rp.road_part_number = pl.road_part_number
              AND rw.end_date IS NULL
              AND rw.valid_to IS NULL
              AND lc.valid_to IS NULL
              AND lc.id NOT IN (
                SELECT pl2.linear_location_id
                FROM project_link pl2
                WHERE pl2.road_number = rw.road_number
                AND pl2.road_part_number = rw.road_part_number
                )
              AND $filter
              AND pl.status = ${RoadAddressChangeType.NotHandled.value}
              GROUP BY rp.id, pl.project_id, rw.road_number, rw.road_part_number) gr
            ORDER BY gr.road_number, gr.road_part_number
            """

      runSelectQuery(query.map(ProjectReservedPart.fromFormedQuery))
    }
  }

  def fetchReservedRoadPart(roadPart: RoadPart): Option[ProjectReservedPart] = {
    time(logger, "Fetch reserved road part") {
      val query =
        sql"""
          SELECT id, road_number, road_part_number, length, ely,
        (SELECT DISCONTINUITY FROM roadway ra
          WHERE ra.road_number = gr.road_number
            AND ra.road_part_number = gr.road_part_number
            AND ra.end_date IS NULL
            AND ra.valid_to IS NULL
            AND end_addr_m = gr.length
            LIMIT 1) AS discontinuity,
        (SELECT link_id FROM project_link pl
          WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number
            AND pl.road_part_number = gr.road_part_number
            AND pl.status != ${RoadAddressChangeType.Termination.value}
            AND pl.track IN (${Track.Combined.value}, ${Track.RightSide.value})
            LIMIT 1) AS first_link
            FROM (SELECT rp.id, rp.project_id, rp.road_number, rp.road_part_number,
            (SELECT MAX(ra.end_addr_m)
              FROM roadway ra
              WHERE ra.road_number = rp.road_number
              AND ra.road_part_number = rp.road_part_number
              AND ra.end_date IS NULL
              AND ra.valid_to IS NULL) as length,
            (SELECT MAX(ra.ely)
              FROM roadway ra
              WHERE ra.road_number = rp.road_number
              AND ra.road_part_number = rp.road_part_number
              AND ra.end_date IS NULL AND ra.valid_to
              IS NULL) as ely
            FROM project_reserved_road_part rp
          LEFT JOIN project_link pl ON (
            pl.project_id = rp.project_id
            AND pl.road_number = rp.road_number
            AND pl.road_part_number = rp.road_part_number
            AND pl.status != ${RoadAddressChangeType.Termination.value})
          LEFT JOIN roadway ra ON
            ((ra.road_number = rp.road_number
            AND ra.road_part_number = rp.road_part_number
            AND ra.end_date IS NULL
            AND ra.valid_to IS NULL)
            OR ra.id = pl.roadway_id)
          LEFT JOIN linear_location lc ON
            (lc.id = pl.linear_location_id)
          WHERE rp.road_number = ${roadPart.roadNumber}
            AND rp.road_part_number = ${roadPart.partNumber}
            AND ra.end_date IS NULL
            AND ra.valid_to IS NULL
            AND (pl.status IS NULL
            OR (pl.status != ${RoadAddressChangeType.Termination.value}
            AND pl.track IN (${Track.Combined.value}, ${Track.RightSide.value})))
          GROUP BY rp.id, rp.project_id, rp.road_number, rp.road_part_number) gr
          """
      runSelectQuery(query.map(ProjectReservedPart.fromReservedQuery)).headOption
    }
  }

  def fetchFormedRoadPart(roadPart: RoadPart): Option[ProjectReservedPart] = {
    time(logger, "Fetch reserved road part") {
      val sql =
        sql"""
          SELECT id, road_number, road_part_number, length_new, ely_new,
        (SELECT discontinuity_type FROM project_link pl
          WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number
            AND pl.road_part_number = gr.road_part_number
            AND pl.status != ${RoadAddressChangeType.Termination.value}
            AND pl.track IN (${Track.Combined.value}, ${Track.RightSide.value})
            AND end_addr_m = (
              SELECT MAX(pl2.end_addr_m)
              FROM project_link pl2
              WHERE pl2.road_number = gr.road_number
              AND pl2.road_part_number = gr.road_part_number
              AND pl2.status != ${RoadAddressChangeType.Termination.value}
              AND pl2.track IN (${Track.Combined.value}, ${Track.RightSide.value})
              )
            LIMIT 1) AS discontinuity_new,
        (SELECT link_id FROM project_link pl
          WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number
            AND pl.road_part_number = gr.road_part_number
            AND pl.status != ${RoadAddressChangeType.Termination.value}
            AND pl.track IN (${Track.Combined.value}, ${Track.RightSide.value})
            LIMIT 1) AS first_link FROM
          (SELECT rp.id, rp.project_id, rp.road_number, rp.road_part_number,
            MAX(pl.end_addr_m) AS length_new,
            MAX(pl.ely) AS ely_new FROM project_reserved_road_part rp
          LEFT JOIN project_link pl ON (
            pl.project_id = rp.project_id
            AND pl.road_number = rp.road_number
            AND pl.road_part_number = rp.road_part_number
            AND pl.status != ${RoadAddressChangeType.Termination.value}
            )
          LEFT JOIN roadway ra ON (
            (ra.road_number = rp.road_number
              AND ra.road_part_number = rp.road_part_number
              AND ra.end_date IS NULL
              AND ra.valid_to IS NULL)
            OR ra.id = pl.roadway_id
            )
          LEFT JOIN linear_location lc ON (lc.id = pl.linear_location_id)
          WHERE rp.road_number = ${roadPart.roadNumber}
            AND rp.road_part_number = ${roadPart.partNumber}
            AND ra.end_date IS NULL
            AND ra.valid_to IS NULL
            AND (pl.status IS NULL
            OR (pl.status != ${RoadAddressChangeType.Termination.value}
            AND pl.track IN (${Track.Combined.value}, ${Track.RightSide.value})))
            AND EXISTS (
              SELECT id
              FROM project_link
              WHERE status != ${RoadAddressChangeType.NotHandled.value}
              AND project_id = rp.project_id
              AND road_number = rp.road_number
              AND road_part_number = rp.road_part_number
              )
          GROUP BY rp.id, rp.project_id, rp.road_number, rp.road_part_number) gr"""

      runSelectQuery(sql.map(ProjectReservedPart.fromFormedQuery)).headOption
    }
  }

  def roadPartReservedTo(roadPart: RoadPart, projectId: Long = 0, withProjectId: Boolean = false): Option[(Long, String)] = {
    time(logger, "Road part reserved to") {
      val filter = if (withProjectId && projectId != 0) sqls" AND p.id = $projectId " else sqls""
      val query =
        sql"""
            SELECT p.id, p.name
            FROM project p
            JOIN project_reserved_road_part l
            ON l.project_id = p.id
            WHERE l.road_number=${roadPart.roadNumber}
            AND l.road_part_number=${roadPart.partNumber}
            $filter
            """

      runSelectSingleOption(query.map(rs => (rs.long("id"), rs.string("name"))))
    }
  }

  def fetchProjectReservedPart(roadPart: RoadPart, projectId: Long = 0, withProjectId: Option[Boolean] = None): Option[(Long, String)] = {
    time(logger, "Road part reserved by other project") {
      val filter = (withProjectId, projectId != 0) match {
        case (_, false) => sqls""
        case (Some(inProject), true) =>
          if (inProject) sqls" AND prj.id = $projectId"
          else sqls" AND prj.id != $projectId"
        case _ => sqls""
      }

      val query =
        sql"""
        SELECT prj.id, prj.name FROM project prj
        JOIN project_reserved_road_part res ON res.project_id = prj.id
        WHERE res.road_number = ${roadPart.roadNumber} AND res.road_part_number = ${roadPart.partNumber}
          AND prj.id IN (
            SELECT DISTINCT(pl.project_id) FROM project_link pl
            WHERE pl.link_id IN (
              SELECT ll.link_id FROM linear_location ll
              INNER JOIN roadway rw ON ll.roadway_number = rw.roadway_number
              WHERE rw.road_number = ${roadPart.roadNumber}
              AND rw.road_part_number = ${roadPart.partNumber}
            )
          )
        $filter
        """
      runSelectSingleOption(query.map(rs => (rs.long("id"), rs.string("name"))))
    }
  }

  def fetchProjectReservedJunctions(roadPart: RoadPart, projectId: Long): Seq[String] = {
    val query =
      sql"""
        SELECT DISTINCT prj.name FROM project prj
        JOIN project_reserved_road_part res ON (res.project_id = prj.id AND prj.id <> $projectId)
        WHERE prj.id IN (
          SELECT DISTINCT(pl.project_id) FROM project_link pl
          WHERE pl.link_id IN (
            SELECT ll.link_id FROM linear_location ll
            INNER JOIN roadway rw ON ll.roadway_number = rw.roadway_number
            WHERE rw.roadway_number in (
              SELECT DISTINCT r.roadway_number FROM roadway r
              JOIN roadway_point rwp ON r.roadway_number = rwp.roadway_number
              JOIN junction_point jp ON jp.roadway_point_id = rwp.id
              JOIN junction j ON jp.junction_id = j.id
              WHERE j.id in (
                SELECT DISTINCT j.id FROM junction j
                JOIN junction_point jp ON j.id = jp.junction_id
                JOIN roadway_point rp ON jp.roadway_point_id = rp.id
                JOIN roadway rw ON rp.roadway_number = rw.roadway_number
                WHERE rw.road_number = ${roadPart.roadNumber} AND rw.road_part_number = ${roadPart.partNumber}
                AND j.valid_to IS null
                AND jp.valid_to IS NULL
                AND rw.valid_to IS NULL
              )
            )
          )
        )
      """
    runSelectQuery(query.map(rs => rs.string("name")))

  }

  def reserveRoadPart(projectId: Long, roadPart: RoadPart, user: String): Unit = {
    time(logger, s"User $user reserves road part $roadPart for project $projectId") {
      val nextViitePrimaryKeyId = Sequences.nextViitePrimaryKeySeqValue
      runUpdateToDb(
        sql"""
                    INSERT INTO project_reserved_road_part(id, road_number, road_part_number, project_id, created_by)
                    SELECT $nextViitePrimaryKeyId, ${roadPart.roadNumber}, ${roadPart.partNumber}, $projectId, $user
                    """)
    }
  }

  def isNotAvailableForProject(roadPart: RoadPart, projectId: Long): Boolean = {
    time(logger, s"Check if the road part $roadPart is not available for the project $projectId") {
      val query =
        sql"""
          SELECT 1 WHERE EXISTS(
             SELECT 1
             FROM project pro,
             roadway ra
             WHERE  pro.id = $projectId
             AND road_number = ${roadPart.roadNumber}
             AND road_part_number = ${roadPart.partNumber}
             AND (ra.start_date > pro.start_date OR ra.end_date >= pro.start_date)
             AND ra.valid_to IS NULL
             )
            OR EXISTS (
              SELECT 1 FROM project_reserved_road_part pro, roadway ra
              WHERE pro.project_id != $projectId
              AND pro.road_number = ra.road_number
              AND pro.road_part_number = ra.road_part_number
              AND pro.road_number = ${roadPart.roadNumber}
              AND pro.road_part_number = ${roadPart.partNumber}
              AND ra.end_date IS NULL
              )"""
      runSelectSingleFirstOptionWithType[Int](query).nonEmpty
    }
  }

}
