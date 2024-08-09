package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite._
import fi.vaylavirasto.viite.model.{Discontinuity, RoadAddressChangeType, RoadPart, Track}
import fi.vaylavirasto.viite.dao.BaseDAO
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{StaticQuery => Q}
import slick.jdbc.StaticQuery.interpolation

//TODO naming SQL conventions

case class ProjectReservedPart(id: Long, roadPart: RoadPart, addressLength: Option[Long] = None, discontinuity: Option[Discontinuity] = None, ely: Option[Long] = None, newLength: Option[Long] = None, newDiscontinuity: Option[Discontinuity] = None, newEly: Option[Long] = None, startingLinkId: Option[String] = None) {
  def holds(baseRoadAddress: BaseRoadAddress): Boolean = {
    roadPart == baseRoadAddress.roadPart
  }
}

class ProjectReservedPartDAO extends BaseDAO{

  /**
    * Removes reserved road part and deletes the project links associated to it.
    * Requires links that have been transferred to this road part to be reverted before
    * or this will fail.
    *
    * @param projectId        Project's id
    * @param roadPart         Road part of the reserved part to remove
    */
  def removeReservedRoadPartAndChanges(projectId: Long, roadPart: RoadPart): Unit = {
    time(logger, s"Remove reserved road part $roadPart from project $projectId") {
      runUpdateToDb(s"""DELETE FROM ROADWAY_CHANGES_LINK WHERE PROJECT_ID = $projectId""")
      runUpdateToDb(s"""DELETE FROM ROADWAY_CHANGES WHERE PROJECT_ID = $projectId""")
      runUpdateToDb(s"""
        DELETE FROM PROJECT_LINK
          WHERE PROJECT_ID = $projectId
            AND (EXISTS (SELECT 1 FROM ROADWAY RA, LINEAR_LOCATION LC WHERE RA.ID = ROADWAY_ID AND
                         RA.ROAD_NUMBER = ${roadPart.roadNumber} AND RA.ROAD_PART_NUMBER = ${roadPart.partNumber}))
             OR (ROAD_NUMBER = ${roadPart.roadNumber} AND ROAD_PART_NUMBER = ${roadPart.partNumber} AND
                 (STATUS != ${RoadAddressChangeType.NotHandled.value})
          )
        """)
      runUpdateToDb(s"""DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE project_id = $projectId and road_number = ${roadPart.roadNumber} and road_part_number = ${roadPart.partNumber}""")
    }
  }

  def removeReservedRoadPart(projectId: Long, roadPart: RoadPart): Unit = {
    time(logger, s"Remove ") {
      runUpdateToDb(s"""DELETE FROM ROADWAY_CHANGES_LINK WHERE PROJECT_ID = $projectId""")
      runUpdateToDb(s"""DELETE FROM ROADWAY_CHANGES WHERE PROJECT_ID = $projectId""")
      runUpdateToDb(s"""DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE project_id = $projectId and road_number = ${roadPart.roadNumber} and road_part_number = ${roadPart.partNumber}""")
    }
  }

  def removeReservedRoadPartsByProject(projectId: Long): Unit = {
    time(logger, s"Remove reserved road parts by project $projectId") {
      runUpdateToDb(s"""DELETE FROM ROADWAY_CHANGES_LINK WHERE PROJECT_ID = $projectId""")
      runUpdateToDb(s"""DELETE FROM ROADWAY_CHANGES WHERE PROJECT_ID = $projectId""")
      runUpdateToDb(s"""DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE project_id = $projectId""")
    }
  }

  def fetchHistoryRoadParts(projectId: Long): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch reserved road parts for project $projectId") {
      val sql =
        s"""SELECT ROAD_NUMBER, ROAD_PART_NUMBER, LENGTH, ELY,
           (SELECT IPLH.DISCONTINUITY_TYPE FROM PROJECT_LINK_HISTORY IPLH
           WHERE IPLH.ROAD_NUMBER = PLH.ROAD_NUMBER AND IPLH.ROAD_PART_NUMBER = PLH.ROAD_PART_NUMBER AND IPLH.END_ADDR_M = PLH.LENGTH LIMIT 1) AS DISCONTINUITY_TYPE,
           (SELECT IPLH.LINK_ID FROM PROJECT_LINK_HISTORY IPLH
           WHERE IPLH.ROAD_NUMBER = PLH.ROAD_NUMBER AND IPLH.ROAD_PART_NUMBER = PLH.ROAD_PART_NUMBER AND IPLH.START_ADDR_M = 0 LIMIT 1) AS LINK_ID
           FROM
           (
           	SELECT ROAD_NUMBER, ROAD_PART_NUMBER, MAX(END_ADDR_M) as LENGTH, ELY
           	FROM PROJECT_LINK_HISTORY
           	WHERE PROJECT_ID = $projectId
           	GROUP BY (ROAD_NUMBER, ROAD_PART_NUMBER, ELY)
           ) PLH ORDER BY PLH.ROAD_NUMBER, PLH.ROAD_PART_NUMBER
        """
      Q.queryNA[(Long, Long, Option[Long], Option[Long], Option[Long], Option[String])](sql).list.map {
        case (roadNumber, roadPartNumber, length, ely, discontinuityOpt, startingLinkId) =>
          val discontinuity = discontinuityOpt.map(Discontinuity.apply)
          ProjectReservedPart(noReservedPartId, RoadPart(roadNumber, roadPartNumber), length, discontinuity, ely, length, discontinuity, ely, startingLinkId)
      }
    }
  }

  def fetchProjectReservedRoadPartsByProjectId(projectId: Long): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch project reserved road parts for project $projectId") {
      val sql = s"""SELECT rp.id, rp.road_number, rp.road_part_number FROM PROJECT_RESERVED_ROAD_PART rp WHERE rp.project_id = $projectId"""
      Q.queryNA[(Long, Long, Long)](sql).list.map {
        case (id, road, part) =>
          ProjectReservedPart(id, RoadPart(road, part), None, None, None, None, None, None, None)
      }
    }
  }

  def fetchReservedRoadParts(projectId: Long): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch reserved road parts for project $projectId") {
      val sql =
        s"""SELECT id, road_number, road_part_number, length, ely,
          (SELECT DISCONTINUITY FROM ROADWAY ra WHERE ra.road_number = gr.road_number AND
            ra.road_part_number = gr.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL
            AND END_ADDR_M = gr.length LIMIT 1) as discontinuity,
          (SELECT LINK_ID FROM PROJECT_LINK pl
            WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != ${RoadAddressChangeType.Termination.value} AND PL.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value}) LIMIT 1) as first_link
          FROM (
            SELECT rp.id, rp.project_id, rp.road_number, rp.road_part_number,
              (SELECT MAX(ra.end_addr_m) FROM roadway ra WHERE ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND ra.end_date IS NULL AND ra.valid_to IS NULL) as length,
              (SELECT MAX(ra.ely) FROM roadway ra WHERE ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND ra.end_date IS NULL AND ra.valid_to IS NULL) as ELY
              FROM PROJECT_RESERVED_ROAD_PART rp LEFT JOIN
              PROJECT_LINK pl ON (pl.project_id = rp.project_id AND pl.road_number = rp.road_number AND
              pl.road_part_number = rp.road_part_number AND pl.status != ${RoadAddressChangeType.Termination.value})
              LEFT JOIN Roadway ra ON (ra.Id = pl.Roadway_Id OR (ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL))
              LEFT JOIN Linear_Location lc ON (lc.Id = pl.Linear_location_id)
              WHERE
                rp.project_id = $projectId
                GROUP BY rp.id, rp.project_id, rp.road_number, rp.road_part_number
            ) gr order by gr.road_number, gr.road_part_number"""
      /* TODO Convert to Postgis something like this: (maybe already converted (LIMIT 1) - have to test!)
              s"""
        SELECT id, road_number, road_part_number, length, length_new,
          ely, ely_new,
          (SELECT DISCONTINUITY FROM ROADWAY ra WHERE ra.road_number = gr.road_number AND
            ra.road_part_number = gr.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL
            AND END_ADDR_M = gr.length LIMIT 1) as discontinuity,
          (SELECT DISCONTINUITY_TYPE FROM PROJECT_LINK pl WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != 5 AND PL.TRACK IN (0,1)
            AND END_ADDR_M = gr.length_new LIMIT 1) as discontinuity_new,
          (SELECT LINK_ID FROM PROJECT_LINK pl
            WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != 5 AND PL.TRACK IN (0,1) AND pl.START_ADDR_M = 0
            AND pl.END_ADDR_M > 0 LIMIT 1) as first_link
          FROM (
            SELECT rp.id, rp.project_id, rp.road_number, rp.road_part_number,
              MAX(ra.END_ADDR_M) as length,
              MAX(pl.END_ADDR_M) as length_new,
              MAX(ra.ely) as ELY,
              MAX(pl.ely) as ELY_NEW
              FROM PROJECT_RESERVED_ROAD_PART rp LEFT JOIN
              PROJECT_LINK pl ON (pl.project_id = rp.project_id AND pl.road_number = rp.road_number AND
              pl.road_part_number = rp.road_part_number AND pl.status != 5)
              LEFT JOIN Roadway ra ON (ra.Id = pl.Roadway_Id OR (ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL))
              LEFT JOIN Linear_Location lc ON (lc.Id = pl.Linear_location_id)
              WHERE
                rp.project_id = $projectId
                GROUP BY rp.id, rp.project_id, rp.road_number, rp.road_part_number
            ) gr"""
       */
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[String])](sql).list.map {
        case (id, road, part, length, ely, discontinuity, startingLinkId) =>
          ProjectReservedPart(id, RoadPart(road, part), length, discontinuity.map(Discontinuity.apply), ely, None, None, None, startingLinkId)
      }
    }
  }

  def fetchFormedRoadParts(projectId: Long, withProjectId: Boolean = true): Seq[ProjectReservedPart] = {
    (formedByIncrease(projectId, withProjectId)++formedByReduction(projectId, withProjectId)).sortBy(p => p.roadPart)
  }

  def formedByIncrease(projectId: Long, withProjectId: Boolean = true): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch formed road parts for project: $projectId") {
      val filter = if (withProjectId && projectId != 0) s" rp.project_id = $projectId " else s" rp.project_id != $projectId "
      val sql =
        s"""SELECT id, road_number, road_part_number, length_new, ely_new,
          (SELECT DISCONTINUITY_TYPE FROM PROJECT_LINK pl WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != ${RoadAddressChangeType.Termination.value} AND PL.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})
            AND END_ADDR_M = length_new LIMIT 1) as discontinuity_new,
          (SELECT LINK_ID FROM PROJECT_LINK pl
            WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != ${RoadAddressChangeType.Termination.value} AND PL.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value}) LIMIT 1) as first_link
          FROM (
            SELECT rp.id, rp.project_id, rp.road_number, rp.road_part_number,
          MAX(pl.end_addr_m) as length_new,
          MAX(pl.ely) as ELY_NEW
          FROM PROJECT_RESERVED_ROAD_PART rp LEFT JOIN
          PROJECT_LINK pl ON (pl.project_id = rp.project_id AND pl.road_number = rp.road_number AND
            pl.road_part_number = rp.road_part_number AND pl.status != ${RoadAddressChangeType.Termination.value})
          LEFT JOIN Roadway ra ON (ra.Id = pl.Roadway_Id OR (ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL))
          LEFT JOIN Linear_Location lc ON (lc.Id = pl.Linear_location_id AND lc.valid_to IS NULL)
          WHERE $filter AND pl.status != ${RoadAddressChangeType.NotHandled.value}
          GROUP BY rp.id, rp.project_id, rp.ROAD_NUMBER, rp.ROAD_PART_NUMBER
          ) gr order by gr.road_number, gr.road_part_number"""
      /* TODO: Convert to Postgis something like this: (maybe done already (LIMIT 1) - have to test!)
              s"""
        SELECT id, road_number, road_part_number, length, length_new,
          ely, ely_new,
          (SELECT DISCONTINUITY FROM ROADWAY ra WHERE ra.road_number = gr.road_number AND
          ra.road_part_number = gr.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL
          AND END_ADDR_M = gr.length LIMIT 1) as discontinuity,
          (SELECT DISCONTINUITY_TYPE FROM PROJECT_LINK pl WHERE pl.project_id = gr.project_id
          AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
          AND PL.STATUS != 5 AND PL.TRACK IN (0,1)
          AND END_ADDR_M = gr.length_new LIMIT 1) as discontinuity_new,
          (SELECT LINK_ID FROM PROJECT_LINK pl
            WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != 5 AND PL.TRACK IN (0,1) AND pl.START_ADDR_M = 0
            AND pl.END_ADDR_M > 0 LIMIT 1) as first_link
          FROM (
            SELECT rp.id, rp.project_id, rp.road_number, rp.road_part_number,
              MAX(ra.END_ADDR_M) as length,
              MAX(pl.END_ADDR_M) as length_new,
              MAX(ra.ely) as ELY,
              MAX(pl.ely) as ELY_NEW
              FROM PROJECT_RESERVED_ROAD_PART rp
              LEFT JOIN PROJECT_LINK pl ON (pl.project_id = rp.project_id AND pl.road_number = rp.road_number AND pl.road_part_number = rp.road_part_number)
              LEFT JOIN ROADWAY ra ON ((ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number) OR ra.id = pl.ROADWAY_ID)
              LEFT JOIN LINEAR_LOCATION lc ON (lc.Id = pl.Linear_Location_Id)
              WHERE
                rp.road_number = $roadNumber AND rp.road_part_number = $roadPartNumber AND
                RA.END_DATE IS NULL AND RA.VALID_TO IS NULL AND
                (PL.STATUS IS NULL OR (PL.STATUS != 5 AND PL.TRACK IN (0,1)))
              GROUP BY rp.id, rp.project_id, rp.road_number, rp.road_part_number
              ) gr"""
       */
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[String])](sql).list.map {
        case (id, road, part, newLength, newEly, newDiscontinuity, startingLinkId) =>
          ProjectReservedPart(id, RoadPart(road, part), None, None, None, newLength, newDiscontinuity.map(Discontinuity.apply), newEly, startingLinkId)
      }
    }
  }

  def formedByReduction(projectId: Long, withProjectId: Boolean = true): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch formed road parts for project: $projectId") {
      val filter = if (withProjectId && projectId != 0) s" rp.project_id = $projectId " else s" rp.project_id != $projectId "
      val sql =
        s"""SELECT id, road_number, road_part_number, length_new, ELY_NEW, (SELECT DISCONTINUITY_TYPE FROM PROJECT_LINK pl WHERE pl.project_id = projectid
            AND pl.road_number = road_number AND pl.road_part_number = road_part_number
            AND PL.STATUS != ${RoadAddressChangeType.Termination.value} AND PL.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})
            AND END_ADDR_M = length_new
            LIMIT 1) AS discontinuity_new,
            (SELECT LINK_ID FROM PROJECT_LINK pl
            WHERE pl.project_id = projectid
            AND pl.road_number = road_number AND pl.road_part_number = road_part_number
            AND PL.STATUS != ${RoadAddressChangeType.Termination.value} AND PL.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value}) LIMIT 1) AS first_link
            FROM
            (SELECT DISTINCT rp.id, pl.project_id AS projectid, rw.road_number AS road_number, rw.road_part_number AS road_part_number, MAX(pl.end_addr_m) AS length_new,
            MAX(pl.ely) AS ELY_NEW
            FROM linear_location lc, roadway rw, project_link pl, project_reserved_road_part rp WHERE
            rw.roadway_number = lc.ROADWAY_NUMBER AND
            rw.road_Number = pl.road_number AND rw.road_part_number = pl.road_part_number AND rp.project_id = pl.project_id AND
            rp.road_number = pl.road_number AND rp.road_part_number = pl.road_part_number AND rw.END_DATE IS NULL AND rw.VALID_TO IS NULL AND
            lc.valid_to IS NULL AND
            lc.id NOT IN (select pl2.linear_location_id from project_link pl2 WHERE pl2.road_number = rw.road_number AND pl2.road_part_number = rw.road_part_number)
            AND $filter AND pl.status = ${RoadAddressChangeType.NotHandled.value}
            GROUP BY rp.id, pl.project_id, rw.road_number, rw.road_part_number) gr ORDER BY gr.road_number, gr.road_part_number"""
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[String])](sql).list.map {
        case (id, road, part, newLength, newEly, newDiscontinuity, startingLinkId) =>
          ProjectReservedPart(id, RoadPart(road, part), None, None, None, newLength, newDiscontinuity.map(Discontinuity.apply), newEly, startingLinkId)
      }
    }
  }

 def fetchReservedRoadPart(roadPart: RoadPart): Option[ProjectReservedPart] = {
    time(logger, "Fetch reserved road part") {
      val sql =
        s"""SELECT ID, ROAD_NUMBER, ROAD_PART_NUMBER, length, ely,
        (SELECT DISCONTINUITY FROM ROADWAY ra
          WHERE ra.ROAD_NUMBER = gr.ROAD_NUMBER
            AND ra.ROAD_PART_NUMBER = gr.ROAD_PART_NUMBER
            AND ra.END_DATE IS NULL
            AND ra.VALID_TO IS NULL
            AND END_ADDR_M = gr.LENGTH
            LIMIT 1) AS discontinuity,
        (SELECT LINK_ID FROM PROJECT_LINK pl
          WHERE pl.PROJECT_ID = gr.PROJECT_ID
            AND pl.ROAD_NUMBER = gr.ROAD_NUMBER
            AND pl.ROAD_PART_NUMBER = gr.ROAD_PART_NUMBER
            AND pl.STATUS != ${RoadAddressChangeType.Termination.value}
            AND pl.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})
            LIMIT 1) AS first_link FROM
          (SELECT rp.ID, rp.PROJECT_ID, rp.ROAD_NUMBER, rp.ROAD_PART_NUMBER,
            (SELECT MAX(ra.end_addr_m) FROM roadway ra WHERE ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND ra.end_date IS NULL AND ra.valid_to IS NULL) as length,
            (SELECT MAX(ra.ely) FROM roadway ra WHERE ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND ra.end_date IS NULL AND ra.valid_to IS NULL) as ely
            FROM PROJECT_RESERVED_ROAD_PART rp
          LEFT JOIN PROJECT_LINK pl ON (
            pl.PROJECT_ID = rp.PROJECT_ID
            AND pl.ROAD_NUMBER = rp.ROAD_NUMBER
            AND pl.ROAD_PART_NUMBER = rp.ROAD_PART_NUMBER
            AND pl.STATUS != ${RoadAddressChangeType.Termination.value})
          LEFT JOIN ROADWAY ra ON
            ((ra.ROAD_NUMBER = rp.ROAD_NUMBER
            AND ra.ROAD_PART_NUMBER = rp.ROAD_PART_NUMBER
            AND ra.END_DATE IS NULL
            AND ra.VALID_TO IS NULL)
            OR ra.ID = pl.ROADWAY_ID)
          LEFT JOIN LINEAR_LOCATION lc ON
            (lc.ID = pl.LINEAR_LOCATION_ID)
          WHERE rp.ROAD_NUMBER = ${roadPart.roadNumber}
            AND rp.ROAD_PART_NUMBER = ${roadPart.partNumber}
            AND ra.END_DATE IS NULL
            AND ra.VALID_TO IS NULL
            AND (pl.STATUS IS NULL
            OR (pl.STATUS != ${RoadAddressChangeType.Termination.value}
            AND pl.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})))
          GROUP BY rp.ID, rp.PROJECT_ID, rp.ROAD_NUMBER, rp.ROAD_PART_NUMBER) gr"""
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[String])](sql).firstOption.map {
        case (id, road, part, length, ely, discontinuity, linkId) =>
          ProjectReservedPart(id, RoadPart(road, part), length, discontinuity.map(Discontinuity.apply), ely, None, None, None, linkId)
      }
    }
  }

  def fetchFormedRoadPart(roadPart: RoadPart): Option[ProjectReservedPart] = {
    time(logger, "Fetch reserved road part") {
      val sql =
        s"""SELECT ID, ROAD_NUMBER, ROAD_PART_NUMBER, length_new, ely_new,
        (SELECT DISCONTINUITY_TYPE FROM PROJECT_LINK pl
          WHERE pl.PROJECT_ID = gr.PROJECT_ID
            AND pl.ROAD_NUMBER = gr.ROAD_NUMBER
            AND pl.ROAD_PART_NUMBER = gr.ROAD_PART_NUMBER
            AND pl.STATUS != ${RoadAddressChangeType.Termination.value}
            AND pl.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})
            AND END_ADDR_M = (SELECT MAX(pl2.end_addr_m) FROM project_link pl2 where pl2.road_number = gr.road_number AND pl2.road_part_number = gr.road_part_number
            AND pl2.status != ${RoadAddressChangeType.Termination.value} AND pl2.track IN (${Track.Combined.value}, ${Track.RightSide.value}))
            LIMIT 1) AS discontinuity_new,
        (SELECT LINK_ID FROM PROJECT_LINK pl
          WHERE pl.PROJECT_ID = gr.PROJECT_ID
            AND pl.ROAD_NUMBER = gr.ROAD_NUMBER
            AND pl.ROAD_PART_NUMBER = gr.ROAD_PART_NUMBER
            AND pl.STATUS != ${RoadAddressChangeType.Termination.value}
            AND pl.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})
            LIMIT 1) AS first_link FROM
          (SELECT rp.ID, rp.PROJECT_ID, rp.ROAD_NUMBER, rp.ROAD_PART_NUMBER,
            MAX(pl.END_ADDR_M) AS length_new,
            MAX(pl.ELY) AS ely_new FROM PROJECT_RESERVED_ROAD_PART rp
          LEFT JOIN PROJECT_LINK pl ON (
            pl.PROJECT_ID = rp.PROJECT_ID
            AND pl.ROAD_NUMBER = rp.ROAD_NUMBER
            AND pl.ROAD_PART_NUMBER = rp.ROAD_PART_NUMBER
            AND pl.STATUS != ${RoadAddressChangeType.Termination.value})
          LEFT JOIN ROADWAY ra ON
            ((ra.ROAD_NUMBER = rp.ROAD_NUMBER
            AND ra.ROAD_PART_NUMBER = rp.ROAD_PART_NUMBER
            AND ra.END_DATE IS NULL
            AND ra.VALID_TO IS NULL)
            OR ra.ID = pl.ROADWAY_ID)
          LEFT JOIN LINEAR_LOCATION lc ON
            (lc.ID = pl.LINEAR_LOCATION_ID)
          WHERE rp.ROAD_NUMBER = ${roadPart.roadNumber}
            AND rp.ROAD_PART_NUMBER = ${roadPart.partNumber}
            AND ra.END_DATE IS NULL
            AND ra.VALID_TO IS NULL
            AND (pl.STATUS IS NULL
            OR (pl.STATUS != ${RoadAddressChangeType.Termination.value}
            AND pl.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})))
            AND EXISTS (SELECT id FROM project_link WHERE status != ${RoadAddressChangeType.NotHandled.value} AND project_id = rp.project_id AND ROAD_NUMBER = rp.ROAD_NUMBER
            AND ROAD_PART_NUMBER = rp.ROAD_PART_NUMBER)
          GROUP BY rp.ID, rp.PROJECT_ID, rp.ROAD_NUMBER, rp.ROAD_PART_NUMBER) gr"""
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[String])](sql).firstOption.map {
        case (id, road, part, newLength, newEly, newDiscontinuity, linkId) =>
          ProjectReservedPart(id, RoadPart(road, part), None, None, None, newLength, newDiscontinuity.map(Discontinuity.apply), newEly, linkId)
      }
    }
  }

  def roadPartReservedTo(roadPart: RoadPart, projectId: Long = 0, withProjectId: Boolean = false): Option[(Long, String)] = {
    time(logger, "Road part reserved to") {
      val filter = if (withProjectId && projectId != 0) s" AND p.ID = $projectId " else ""
      val query =
        s"""SELECT p.id, p.name
              FROM project p
              JOIN PROJECT_RESERVED_ROAD_PART l
           ON l.PROJECT_ID = p.ID
           WHERE l.road_number=${roadPart.roadNumber} AND l.road_part_number=${roadPart.partNumber} $filter"""
      Q.queryNA[(Long, String)](query).firstOption
    }
  }

  def fetchProjectReservedPart(roadPart: RoadPart, projectId: Long = 0, withProjectId: Option[Boolean] = None): Option[(Long, String)] = {
    time(logger, "Road part reserved by other project") {
      val filter = (withProjectId, projectId != 0) match {
        case (_, false) => ""
        case (Some(inProject), true) => if (inProject) s" AND prj.ID = $projectId " else s" AND prj.ID != $projectId "
        case _ => ""
      }

      val query =
        s"""
        SELECT prj.ID, prj.NAME FROM PROJECT prj
        JOIN PROJECT_RESERVED_ROAD_PART res ON res.PROJECT_ID = prj.ID
        WHERE res.road_number = ${roadPart.roadNumber} AND res.road_part_number = ${roadPart.partNumber}
          AND prj.ID IN (
            SELECT DISTINCT(pl.PROJECT_ID) FROM PROJECT_LINK pl
            WHERE pl.LINK_ID IN (
              SELECT ll.LINK_ID FROM LINEAR_LOCATION ll
              INNER JOIN ROADWAY rw ON ll.ROADWAY_NUMBER = rw.ROADWAY_NUMBER
              WHERE rw.ROAD_NUMBER = ${roadPart.roadNumber}
              AND rw.ROAD_PART_NUMBER = ${roadPart.partNumber}
            )
          )
        $filter
        """
      Q.queryNA[(Long, String)](query).firstOption
    }
  }

  def fetchProjectReservedJunctions(roadPart: RoadPart, projectId: Long): Seq[String] = {
    sql"""
     SELECT distinct prj.NAME FROM PROJECT prj
         JOIN PROJECT_RESERVED_ROAD_PART res ON (res.PROJECT_ID = prj.ID AND prj.ID <> $projectId)
         WHERE prj.ID IN (
            SELECT DISTINCT(pl.PROJECT_ID) FROM PROJECT_LINK pl
            WHERE pl.LINK_ID IN (
              SELECT ll.LINK_ID FROM LINEAR_LOCATION ll
              INNER JOIN ROADWAY rw ON ll.ROADWAY_NUMBER = rw.ROADWAY_NUMBER
              WHERE rw.ROADWAY_NUMBER in (SELECT DISTINCT r.ROADWAY_NUMBER FROM ROADWAY r
                JOIN ROADWAY_POINT rwp ON r.ROADWAY_NUMBER = rwp.ROADWAY_NUMBER
                JOIN JUNCTION_POINT jp ON jp.ROADWAY_POINT_ID = rwp.ID
                JOIN JUNCTION j ON jp.JUNCTION_ID = j.ID
                WHERE j.id in (SELECT DISTINCT j.id FROM junction j
                  JOIN JUNCTION_POINT jp ON j.id = jp.JUNCTION_ID
                  JOIN ROADWAY_POINT rp ON jp.ROADWAY_POINT_ID = rp.id
                  JOIN ROADWAY rw ON rp.ROADWAY_NUMBER = rw.ROADWAY_NUMBER
                  WHERE rw.ROAD_NUMBER = ${roadPart.roadNumber} AND rw.ROAD_PART_NUMBER = ${roadPart.partNumber} AND j.VALID_TO IS null AND jp.VALID_TO IS NULL AND rw.VALID_TO IS NULL))))
       """.as[String].list
  }

  def reserveRoadPart(projectId: Long, roadPart: RoadPart, user: String): Unit = {
    time(logger, s"User $user reserves road part $roadPart for project $projectId") {
      runUpdateToDb(s"""INSERT INTO PROJECT_RESERVED_ROAD_PART(id, road_number, road_part_number, project_id, created_by)
                        SELECT nextval('viite_general_seq'), ${roadPart.roadNumber}, ${roadPart.partNumber}, $projectId, '$user'""")
    }
  }

  def isNotAvailableForProject(roadPart: RoadPart, projectId: Long): Boolean = {
    time(logger, s"Check if the road part $roadPart is not available for the project $projectId") {
      val query =
        s"""
          SELECT 1 WHERE EXISTS(select 1
             from project pro,
             ROADWAY ra
             where  pro.id = $projectId AND road_number = ${roadPart.roadNumber} AND road_part_number = ${roadPart.partNumber} AND
             (ra.START_DATE > pro.START_DATE or ra.END_DATE >= pro.START_DATE) AND
             ra.VALID_TO is null) OR EXISTS (
             SELECT 1 FROM project_reserved_road_part pro, ROADWAY ra
              WHERE pro.project_id != $projectId AND pro.road_number = ra.road_number AND pro.road_part_number = ra.road_part_number
               AND pro.road_number = ${roadPart.roadNumber} AND pro.road_part_number = ${roadPart.partNumber} AND ra.end_date IS NULL)"""
      Q.queryNA[Int](query).firstOption.nonEmpty
    }
  }
}
