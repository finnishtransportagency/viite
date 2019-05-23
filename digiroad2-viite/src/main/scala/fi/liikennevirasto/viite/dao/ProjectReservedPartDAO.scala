package fi.liikennevirasto.viite.dao
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite._
import org.slf4j.{Logger, LoggerFactory}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

//TODO naming SQL conventions

case class ProjectReservedPart(id: Long, roadNumber: Long, roadPartNumber: Long, addressLength: Option[Long] = None,
                               discontinuity: Option[Discontinuity] = None, ely: Option[Long] = None,
                               newLength: Option[Long] = None, newDiscontinuity: Option[Discontinuity] = None,
                               newEly: Option[Long] = None, startingLinkId: Option[Long] = None) {
  def holds(baseRoadAddress: BaseRoadAddress): Boolean = {
    roadNumber == baseRoadAddress.roadNumber && roadPartNumber == baseRoadAddress.roadPartNumber
  }
}

class ProjectReservedPartDAO {
  private def logger: Logger = LoggerFactory.getLogger(getClass)

  /**
    * Removes reserved road part and deletes the project links associated to it.
    * Requires links that have been transferred to this road part to be reverted before
    * or this will fail.
    *
    * @param projectId        Project's id
    * @param roadNumber       Road number of the reserved part to remove
    * @param roadPartNumber   Road part number to remove
    */
  def removeReservedRoadPartAndChanges(projectId: Long, roadNumber: Long, roadPartNumber: Long): Unit = {
    time(logger, "Remove reserved road part") {
      sqlu"""DELETE FROM ROADWAY_CHANGES_LINK WHERE PROJECT_ID = $projectId""".execute
      sqlu"""DELETE FROM ROADWAY_CHANGES WHERE PROJECT_ID = $projectId""".execute
      sqlu"""
           DELETE FROM PROJECT_LINK WHERE PROJECT_ID = $projectId AND
           (EXISTS (SELECT 1 FROM ROADWAY RA, LINEAR_LOCATION LC WHERE RA.ID = ROADWAY_ID AND
           RA.ROAD_NUMBER = $roadNumber AND RA.ROAD_PART_NUMBER = $roadPartNumber))
           OR (ROAD_NUMBER = $roadNumber AND ROAD_PART_NUMBER = $roadPartNumber
           AND (STATUS != ${LinkStatus.NotHandled.value}))
           """.execute
      sqlu"""DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE project_id = $projectId and road_number = $roadNumber and road_part_number = $roadPartNumber""".execute
    }
  }

  def removeFormedRoadPartAndChanges(projectId: Long, roadNumber: Long, roadPartNumber: Long): Unit = {
    time(logger, "Remove formed road part") {
      sqlu"""DELETE FROM ROADWAY_CHANGES_LINK WHERE PROJECT_ID = $projectId""".execute
      sqlu"""DELETE FROM ROADWAY_CHANGES WHERE PROJECT_ID = $projectId""".execute
      sqlu"""
           DELETE FROM PROJECT_LINK WHERE PROJECT_ID = $projectId AND
           (EXISTS (SELECT 1 FROM ROADWAY RA, LINEAR_LOCATION LC WHERE RA.ID = ROADWAY_ID AND
           RA.ROAD_NUMBER = $roadNumber AND RA.ROAD_PART_NUMBER = $roadPartNumber))
           OR (ROAD_NUMBER = $roadNumber AND ROAD_PART_NUMBER = $roadPartNumber
           AND (STATUS = ${LinkStatus.New.value} OR STATUS = ${LinkStatus.Numbering.value}))
           """.execute
      sqlu"""DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE project_id = ${projectId} and road_number = ${roadNumber} and road_part_number = ${roadPartNumber}""".execute
    }
  }

  def removeReservedRoadPart(projectId: Long, roadNumber: Long, roadPartNumber: Long): Unit = {
    sqlu"""DELETE FROM ROADWAY_CHANGES_LINK WHERE PROJECT_ID = $projectId""".execute
    sqlu"""DELETE FROM ROADWAY_CHANGES WHERE PROJECT_ID = $projectId""".execute
    sqlu"""DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE project_id = $projectId and road_number = $roadNumber and road_part_number = $roadPartNumber""".execute
  }

  def removeReservedRoadPartsByProject(projectId: Long): Unit = {
    sqlu"""DELETE FROM ROADWAY_CHANGES_LINK WHERE PROJECT_ID = $projectId""".execute
    sqlu"""DELETE FROM ROADWAY_CHANGES WHERE PROJECT_ID = $projectId""".execute
    sqlu"""DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE project_id = $projectId""".execute
  }

  def fetchHistoryRoadParts(projectId: Long): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch reserved road parts for project: $projectId") {
      val sql =
        s"""SELECT ROAD_NUMBER, ROAD_PART_NUMBER, LENGTH, ELY,
           (SELECT IPLH.DISCONTINUITY_TYPE FROM PROJECT_LINK_HISTORY IPLH
           WHERE IPLH.ROAD_NUMBER = PLH.ROAD_NUMBER AND IPLH.ROAD_PART_NUMBER = PLH.ROAD_PART_NUMBER AND IPLH.END_ADDR_M = PLH.LENGTH AND ROWNUM < 2) AS DISCONTINUITY_TYPE,
           (SELECT IPLH.LINK_ID FROM PROJECT_LINK_HISTORY IPLH
           WHERE IPLH.ROAD_NUMBER = PLH.ROAD_NUMBER AND IPLH.ROAD_PART_NUMBER = PLH.ROAD_PART_NUMBER AND IPLH.START_ADDR_M = 0 AND ROWNUM < 2) AS LINK_ID
           FROM
           (
           	SELECT ROAD_NUMBER, ROAD_PART_NUMBER, MAX(END_ADDR_M) as LENGTH, ELY
           	FROM PROJECT_LINK_HISTORY
           	WHERE PROJECT_ID = $projectId
           	GROUP BY (ROAD_NUMBER, ROAD_PART_NUMBER, ELY)
           ) PLH ORDER BY PLH.ROAD_NUMBER, PLH.ROAD_PART_NUMBER
        """
      Q.queryNA[(Long, Long, Option[Long], Option[Long], Option[Long], Option[Long])](sql).list.map {
        case (roadNumber, roadPartNumber, length, ely, discontinuityOpt, startingLinkId) =>
          val discontinuity = discontinuityOpt.map(Discontinuity.apply)
          ProjectReservedPart(noReservedPartId, roadNumber, roadPartNumber, length, discontinuity, ely, length, discontinuity, ely, startingLinkId)
      }
    }
  }

  def fetchReservedRoadParts(projectId: Long): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch reserved road parts for project: $projectId") {
      val sql =
        s"""SELECT id, road_number, road_part_number, length, ely,
          (SELECT DISCONTINUITY FROM ROADWAY ra WHERE ra.road_number = gr.road_number AND
            ra.road_part_number = gr.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL
            AND END_ADDR_M = gr.length and ROWNUM < 2) as discontinuity,
          (SELECT LINK_ID FROM PROJECT_LINK pl
            WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != ${LinkStatus.Terminated.value} AND PL.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value}) AND ROWNUM < 2) as first_link
          FROM (
            SELECT rp.id, rp.project_id, rp.road_number, rp.road_part_number,
              (SELECT MAX(ra.end_addr_m) FROM roadway ra WHERE ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND ra.end_date IS NULL AND ra.valid_to IS NULL) as length,
              (SELECT MAX(ra.ely) FROM roadway ra WHERE ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND ra.end_date IS NULL AND ra.valid_to IS NULL) as ELY
              FROM PROJECT_RESERVED_ROAD_PART rp LEFT JOIN
              PROJECT_LINK pl ON (pl.project_id = rp.project_id AND pl.road_number = rp.road_number AND
              pl.road_part_number = rp.road_part_number AND pl.status != ${LinkStatus.Terminated.value})
              LEFT JOIN Roadway ra ON (ra.Id = pl.Roadway_Id OR (ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL))
              LEFT JOIN Linear_Location lc ON (lc.Id = pl.Linear_location_id)
              WHERE
                rp.project_id = $projectId
                GROUP BY rp.id, rp.project_id, rp.road_number, rp.road_part_number
            ) gr order by gr.road_number, gr.road_part_number"""
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[Long])](sql).list.map {
        case (id, road, part, length, ely, discontinuity, startingLinkId) =>
          ProjectReservedPart(id, road, part, length, discontinuity.map(Discontinuity.apply), ely, None,
            None, None, startingLinkId)
      }
    }
  }

  def fetchFormedRoadParts(projectId: Long, withProjectId: Boolean = true): Seq[ProjectReservedPart] = {
    (formedByIncrease(projectId, withProjectId)++formedByReduction(projectId, withProjectId)).sortBy(p => (p.roadNumber, p.roadPartNumber))
  }

  def formedByIncrease(projectId: Long, withProjectId: Boolean = true): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch formed road parts for project: $projectId") {
      val filter = if (withProjectId && projectId != 0) s" rp.project_id = $projectId " else s" rp.project_id != $projectId "
      val sql =
        s"""SELECT id, road_number, road_part_number, length_new, ely_new,
          (SELECT DISCONTINUITY_TYPE FROM PROJECT_LINK pl WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != ${LinkStatus.Terminated.value} AND PL.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})
            AND END_ADDR_M = length_new AND ROWNUM < 2) as discontinuity_new,
          (SELECT LINK_ID FROM PROJECT_LINK pl
            WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != ${LinkStatus.Terminated.value} AND PL.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value}) AND ROWNUM < 2) as first_link
          FROM (
            SELECT rp.id, rp.project_id, rp.road_number, rp.road_part_number,
          MAX(pl.end_addr_m) as length_new,
          MAX(pl.ely) as ELY_NEW
          FROM PROJECT_RESERVED_ROAD_PART rp LEFT JOIN
          PROJECT_LINK pl ON (pl.project_id = rp.project_id AND pl.road_number = rp.road_number AND
            pl.road_part_number = rp.road_part_number AND pl.status != ${LinkStatus.Terminated.value})
          LEFT JOIN Roadway ra ON (ra.Id = pl.Roadway_Id OR (ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL))
          LEFT JOIN Linear_Location lc ON (lc.Id = pl.Linear_location_id)
          WHERE $filter AND pl.status != ${LinkStatus.NotHandled.value}
          GROUP BY rp.id, rp.project_id, rp.ROAD_NUMBER, rp.ROAD_PART_NUMBER
          ) gr order by gr.road_number, gr.road_part_number"""
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[Long])](sql).list.map {
        case (id, road, part, newLength, newEly, newDiscontinuity, startingLinkId) =>
          ProjectReservedPart(id, road, part, None, None, None, newLength,
            newDiscontinuity.map(Discontinuity.apply), newEly, startingLinkId)
      }
    }
  }

  def formedByReduction(projectId: Long, withProjectId: Boolean = true): Seq[ProjectReservedPart] = {
    time(logger, s"Fetch formed road parts for project: $projectId") {
      val filter = if (withProjectId && projectId != 0) s" rp.project_id = $projectId " else s" rp.project_id != $projectId "
      val sql =
        s"""SELECT id, road_number, road_part_number, length_new, ELY_NEW, (SELECT DISCONTINUITY_TYPE FROM PROJECT_LINK pl WHERE pl.project_id = projectid
            AND pl.road_number = road_number AND pl.road_part_number = road_part_number
            AND PL.STATUS != ${LinkStatus.Terminated.value} AND PL.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})
            AND END_ADDR_M = length_new
            AND ROWNUM < 2) AS discontinuity_new,
            (SELECT LINK_ID FROM PROJECT_LINK pl
            WHERE pl.project_id = projectid
            AND pl.road_number = road_number AND pl.road_part_number = road_part_number
            AND PL.STATUS != ${LinkStatus.Terminated.value} AND PL.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value}) AND ROWNUM < 2) AS first_link
            FROM
            (SELECT DISTINCT rp.id, pl.project_id AS projectid, rw.road_number AS road_number, rw.road_part_number AS road_part_number, MAX(pl.end_addr_m) AS length_new,
            MAX(pl.ely) AS ELY_NEW
            FROM linear_location lc, roadway rw, project_link pl, project_reserved_road_part rp WHERE
            rw.roadway_number = lc.ROADWAY_NUMBER AND
            rw.road_Number = pl.road_number AND rw.road_part_number = pl.road_part_number AND rp.project_id = pl.project_id AND
            rp.road_number = pl.road_number AND rp.road_part_number = pl.road_part_number AND rw.END_DATE IS NULL AND rw.VALID_TO IS NULL AND
            lc.id NOT IN (select pl2.linear_location_id from project_link pl2 WHERE pl2.road_number = rw.road_number AND pl2.road_part_number = rw.road_part_number)
            AND $filter AND pl.status = ${LinkStatus.NotHandled.value}
            GROUP BY rp.id, pl.project_id, rw.road_number, rw.road_part_number) gr ORDER BY gr.road_number, gr.road_part_number"""
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[Long])](sql).list.map {
        case (id, road, part, newLength, newEly, newDiscontinuity, startingLinkId) =>
          ProjectReservedPart(id, road, part, None, None, None, newLength,
            newDiscontinuity.map(Discontinuity.apply), newEly, startingLinkId)
      }
    }
  }

 def fetchReservedRoadPart(roadNumber: Long, roadPartNumber: Long): Option[ProjectReservedPart] = {
    time(logger, "Fetch reserved road part") {
      val sql =
        s"""SELECT ID, ROAD_NUMBER, ROAD_PART_NUMBER, length, ely,
        (SELECT DISCONTINUITY FROM ROADWAY ra
          WHERE ra.ROAD_NUMBER = gr.ROAD_NUMBER
            AND ra.ROAD_PART_NUMBER = gr.ROAD_PART_NUMBER
            AND ra.END_DATE IS NULL
            AND ra.VALID_TO IS NULL
            AND END_ADDR_M = gr.LENGTH
            AND ROWNUM < 2) AS discontinuity,
        (SELECT LINK_ID FROM PROJECT_LINK pl
          WHERE pl.PROJECT_ID = gr.PROJECT_ID
            AND pl.ROAD_NUMBER = gr.ROAD_NUMBER
            AND pl.ROAD_PART_NUMBER = gr.ROAD_PART_NUMBER
            AND pl.STATUS != ${LinkStatus.Terminated.value}
            AND pl.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})
            AND ROWNUM < 2) AS first_link FROM
          (SELECT rp.ID, rp.PROJECT_ID, rp.ROAD_NUMBER, rp.ROAD_PART_NUMBER,
            (SELECT MAX(ra.end_addr_m) FROM roadway ra WHERE ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND ra.end_date IS NULL AND ra.valid_to IS NULL) as length,
            (SELECT MAX(ra.ely) FROM roadway ra WHERE ra.road_number = rp.road_number AND ra.road_part_number = rp.road_part_number AND ra.end_date IS NULL AND ra.valid_to IS NULL) as ely
            FROM PROJECT_RESERVED_ROAD_PART rp
          LEFT JOIN PROJECT_LINK pl ON (
            pl.PROJECT_ID = rp.PROJECT_ID
            AND pl.ROAD_NUMBER = rp.ROAD_NUMBER
            AND pl.ROAD_PART_NUMBER = rp.ROAD_PART_NUMBER
            AND pl.STATUS != ${LinkStatus.Terminated.value})
          LEFT JOIN ROADWAY ra ON
            ((ra.ROAD_NUMBER = rp.ROAD_NUMBER
            AND ra.ROAD_PART_NUMBER = rp.ROAD_PART_NUMBER
            AND ra.END_DATE IS NULL
            AND ra.VALID_TO IS NULL)
            OR ra.ID = pl.ROADWAY_ID)
          LEFT JOIN LINEAR_LOCATION lc ON
            (lc.ID = pl.LINEAR_LOCATION_ID)
          WHERE rp.ROAD_NUMBER = $roadNumber
            AND rp.ROAD_PART_NUMBER = $roadPartNumber
            AND ra.END_DATE IS NULL
            AND ra.VALID_TO IS NULL
            AND (pl.STATUS IS NULL
            OR (pl.STATUS != ${LinkStatus.Terminated.value}
            AND pl.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})))
          GROUP BY rp.ID, rp.PROJECT_ID, rp.ROAD_NUMBER, rp.ROAD_PART_NUMBER) gr"""
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[Long])](sql).firstOption.map {
        case (id, road, part, length, ely, discontinuity, linkId) =>
          ProjectReservedPart(id, road, part, length, discontinuity.map(Discontinuity.apply), ely, None,
            None, None, linkId)
      }
    }
  }

  def fetchFormedRoadPart(roadNumber: Long, roadPartNumber: Long): Option[ProjectReservedPart] = {
    time(logger, "Fetch reserved road part") {
      val sql =
        s"""SELECT ID, ROAD_NUMBER, ROAD_PART_NUMBER, length_new, ely_new,
        (SELECT DISCONTINUITY_TYPE FROM PROJECT_LINK pl
          WHERE pl.PROJECT_ID = gr.PROJECT_ID
            AND pl.ROAD_NUMBER = gr.ROAD_NUMBER
            AND pl.ROAD_PART_NUMBER = gr.ROAD_PART_NUMBER
            AND pl.STATUS != ${LinkStatus.Terminated.value}
            AND pl.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})
            AND END_ADDR_M = (SELECT MAX(pl2.end_addr_m) FROM project_link pl2 where pl2.road_number = gr.road_number AND pl2.road_part_number = gr.road_part_number
            AND pl2.status != ${LinkStatus.Terminated.value} AND pl2.track IN (${Track.Combined.value}, ${Track.RightSide.value}))
            AND ROWNUM < 2) AS discontinuity_new,
        (SELECT LINK_ID FROM PROJECT_LINK pl
          WHERE pl.PROJECT_ID = gr.PROJECT_ID
            AND pl.ROAD_NUMBER = gr.ROAD_NUMBER
            AND pl.ROAD_PART_NUMBER = gr.ROAD_PART_NUMBER
            AND pl.STATUS != ${LinkStatus.Terminated.value}
            AND pl.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})
            AND ROWNUM < 2) AS first_link FROM
          (SELECT rp.ID, rp.PROJECT_ID, rp.ROAD_NUMBER, rp.ROAD_PART_NUMBER,
            MAX(pl.END_ADDR_M) AS length_new,
            MAX(pl.ELY) AS ely_new FROM PROJECT_RESERVED_ROAD_PART rp
          LEFT JOIN PROJECT_LINK pl ON (
            pl.PROJECT_ID = rp.PROJECT_ID
            AND pl.ROAD_NUMBER = rp.ROAD_NUMBER
            AND pl.ROAD_PART_NUMBER = rp.ROAD_PART_NUMBER
            AND pl.STATUS != ${LinkStatus.Terminated.value})
          LEFT JOIN ROADWAY ra ON
            ((ra.ROAD_NUMBER = rp.ROAD_NUMBER
            AND ra.ROAD_PART_NUMBER = rp.ROAD_PART_NUMBER
            AND ra.END_DATE IS NULL
            AND ra.VALID_TO IS NULL)
            OR ra.ID = pl.ROADWAY_ID)
          LEFT JOIN LINEAR_LOCATION lc ON
            (lc.ID = pl.LINEAR_LOCATION_ID)
          WHERE rp.ROAD_NUMBER = $roadNumber
            AND rp.ROAD_PART_NUMBER = $roadPartNumber
            AND ra.END_DATE IS NULL
            AND ra.VALID_TO IS NULL
            AND (pl.STATUS IS NULL
            OR (pl.STATUS != ${LinkStatus.Terminated.value}
            AND pl.TRACK IN (${Track.Combined.value}, ${Track.RightSide.value})))
            AND EXISTS (SELECT id FROM project_link WHERE status != ${LinkStatus.NotHandled.value} AND project_id = rp.project_id AND ROAD_NUMBER = rp.ROAD_NUMBER
            AND ROAD_PART_NUMBER = rp.ROAD_PART_NUMBER)
          GROUP BY rp.ID, rp.PROJECT_ID, rp.ROAD_NUMBER, rp.ROAD_PART_NUMBER) gr"""
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[Long])](sql).firstOption.map {
        case (id, road, part, newLength, newEly, newDiscontinuity, linkId) =>
          ProjectReservedPart(id, road, part, None, None, None, newLength,
            newDiscontinuity.map(Discontinuity.apply), newEly, linkId)
      }
    }
  }

  def roadPartReservedTo(roadNumber: Long, roadPart: Long, projectId: Long = 0, withProjectId: Boolean = false): Option[(Long, String)] = {
    time(logger, "Road part reserved to") {
      val filter = if (withProjectId && projectId != 0) s" AND p.ID = $projectId " else ""
      val query =
        s"""SELECT p.id, p.name
              FROM project p
              JOIN PROJECT_RESERVED_ROAD_PART l
           ON l.PROJECT_ID = p.ID
           WHERE l.road_number=$roadNumber AND l.road_part_number=$roadPart $filter"""
      Q.queryNA[(Long, String)](query).firstOption
    }
  }

  def fetchProjectReservedPart(roadNumber: Long, roadPart: Long, projectId: Long = 0, withProjectId: Option[Boolean] = None): Option[String] = {
    time(logger, "Road part reserved by other project") {
      val filter = (withProjectId, projectId != 0) match {
        case (_, false) => ""
        case (Some(inProject), true) => if(inProject) s" AND prj.ID = $projectId " else s" AND prj.ID != $projectId "
        case _ => ""
      }

      val query =
        s"""SELECT prj.NAME FROM PROJECT prj
            JOIN PROJECT_RESERVED_ROAD_PART res ON res.PROJECT_ID = prj.ID
            WHERE res.road_number = $roadNumber AND res.road_part_number = $roadPart
            AND prj.ID IN (
            SELECT DISTINCT(link.PROJECT_ID) FROM PROJECT_LINK link
            WHERE LINK_ID IN (
            SELECT link.LINK_ID FROM LINEAR_LOCATION link
            INNER JOIN ROADWAY road ON link.ROADWAY_NUMBER = road.ROADWAY_NUMBER
            WHERE road.ROAD_NUMBER = $roadNumber
            AND road.ROAD_PART_NUMBER = $roadPart)) $filter"""
      Q.queryNA[String](query).firstOption
    }
  }

  def reserveRoadPart(projectId: Long, roadNumber: Long, roadPartNumber: Long, user: String): Unit = {
    sqlu"""INSERT INTO PROJECT_RESERVED_ROAD_PART(id, road_number, road_part_number, project_id, created_by)
      SELECT viite_general_seq.nextval, $roadNumber, $roadPartNumber, $projectId, $user FROM DUAL""".execute
  }

  def isNotAvailableForProject(roadNumber: Long, roadPartNumber: Long, projectId: Long): Boolean = {
    time(logger, s"Check if the road part $roadNumber/$roadPartNumber is not available for the project $projectId") {
      val query =
        s"""
          SELECT 1 FROM dual WHERE EXISTS(select 1
             from project pro,
             ROADWAY ra
             where  pro.id = $projectId AND road_number = $roadNumber AND road_part_number = $roadPartNumber AND
             (ra.START_DATE > pro.START_DATE or ra.END_DATE >= pro.START_DATE) AND
             ra.VALID_TO is null) OR EXISTS (
             SELECT 1 FROM project_reserved_road_part pro, ROADWAY ra
              WHERE pro.project_id != $projectId AND pro.road_number = ra.road_number AND pro.road_part_number = ra.road_part_number
               AND pro.road_number = $roadNumber AND pro.road_part_number = $roadPartNumber AND ra.end_date IS NULL)"""
      Q.queryNA[Int](query).firstOption.nonEmpty
    }
  }
}
