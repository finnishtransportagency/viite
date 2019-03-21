package fi.liikennevirasto.viite.dao
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite._
import org.slf4j.{Logger, LoggerFactory}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

//TODO naming SQL conventions

case class ProjectReservedPart(id: Long, roadNumber: Long, roadPartNumber: Long, addressLength: Option[Long] = None,
                               discontinuity: Option[Discontinuity] = None, ely: Option[Long] = None,
                               newLength: Option[Long] = None, newDiscontinuity: Option[Discontinuity] = None,
                               newEly: Option[Long] = None, startingLinkId: Option[Long] = None, isDirty: Boolean = false) {
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
           AND (STATUS = ${LinkStatus.New.value} OR STATUS = ${LinkStatus.Numbering.value}))
           """.execute
      sqlu"""DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE project_id = ${projectId} and road_number = ${roadNumber} and road_part_number = ${roadPartNumber}""".execute
    }
  }

  def removeReservedRoadPart(projectId: Long, roadNumber: Long, roadPartNumber: Long): Unit = {
    sqlu"""DELETE FROM ROADWAY_CHANGES_LINK WHERE PROJECT_ID = $projectId""".execute
    sqlu"""DELETE FROM ROADWAY_CHANGES WHERE PROJECT_ID = $projectId""".execute
    sqlu"""DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE project_id = ${projectId} and road_number = ${roadNumber} and road_part_number = ${roadPartNumber}""".execute
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
           ) PLH
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
        s"""
        SELECT id, road_number, road_part_number, length, length_new,
          ely, ely_new,
          (SELECT DISCONTINUITY FROM ROADWAY ra WHERE ra.road_number = gr.road_number AND
            ra.road_part_number = gr.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL
            AND END_ADDR_M = gr.length and ROWNUM < 2) as discontinuity,
          (SELECT DISCONTINUITY_TYPE FROM PROJECT_LINK pl WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != 5 AND PL.TRACK IN (0,1)
            AND END_ADDR_M = gr.length_new AND ROWNUM < 2) as discontinuity_new,
          (SELECT LINK_ID FROM PROJECT_LINK pl
            WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != 5 AND PL.TRACK IN (0,1) AND pl.START_ADDR_M = 0
            AND pl.END_ADDR_M > 0 AND ROWNUM < 2) as first_link
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
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[Long], Option[Long],
        Option[Long], Option[Long])](sql).list.map {
        case (id, road, part, length, newLength, ely, newEly, discontinuity, newDiscontinuity, startingLinkId) =>
          ProjectReservedPart(id, road, part, length, discontinuity.map(Discontinuity.apply), ely, newLength,
            newDiscontinuity.map(Discontinuity.apply), newEly, startingLinkId)
      }
    }
  }

  def fetchReservedRoadPart(roadNumber: Long, roadPartNumber: Long): Option[ProjectReservedPart] = {
    time(logger, "Fetch reserved road part") {
      val sql =
        s"""
        SELECT id, road_number, road_part_number, length, length_new,
          ely, ely_new,
          (SELECT DISCONTINUITY FROM ROADWAY ra WHERE ra.road_number = gr.road_number AND
          ra.road_part_number = gr.road_part_number AND RA.END_DATE IS NULL AND RA.VALID_TO IS NULL
          AND END_ADDR_M = gr.length and ROWNUM < 2) as discontinuity,
          (SELECT DISCONTINUITY_TYPE FROM PROJECT_LINK pl WHERE pl.project_id = gr.project_id
          AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
          AND PL.STATUS != 5 AND PL.TRACK IN (0,1)
          AND END_ADDR_M = gr.length_new and ROWNUM < 2) as discontinuity_new,
          (SELECT LINK_ID FROM PROJECT_LINK pl
            WHERE pl.project_id = gr.project_id
            AND pl.road_number = gr.road_number AND pl.road_part_number = gr.road_part_number
            AND PL.STATUS != 5 AND PL.TRACK IN (0,1) AND pl.START_ADDR_M = 0
            AND pl.END_ADDR_M > 0 AND ROWNUM < 2) as first_link
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
      Q.queryNA[(Long, Long, Long, Option[Long], Option[Long], Option[Long], Option[Long], Option[Long],
        Option[Long], Option[Long])](sql).firstOption.map {
        case (id, road, part, length, newLength, ely, newEly, discontinuity, newDiscontinuity, linkId) =>
          ProjectReservedPart(id, road, part, length, discontinuity.map(Discontinuity.apply), ely, newLength,
            newDiscontinuity.map(Discontinuity.apply), newEly, linkId)
      }
    }
  }

  def roadPartReservedTo(roadNumber: Long, roadPart: Long): Option[(Long, String)] = {
    time(logger, "Road part reserved to") {
      val query =
        s"""SELECT p.id, p.name
              FROM project p
              JOIN PROJECT_RESERVED_ROAD_PART l
           ON l.PROJECT_ID =  p.ID
           WHERE l.road_number=$roadNumber AND road_part_number=$roadPart"""
      Q.queryNA[(Long, String)](query).firstOption
    }
  }

  def roadPartReservedByProject(roadNumber: Long, roadPart: Long, projectId: Long = 0, withProjectId: Boolean = false): Option[String] = {
    time(logger, "Road part reserved by project") {
      val filter = if (withProjectId && projectId != 0) s" AND project_id != $projectId " else ""
      val query =
        s"""SELECT p.name
              FROM project p
              JOIN PROJECT_RESERVED_ROAD_PART l
           ON l.PROJECT_ID =  p.ID
           WHERE l.road_number=$roadNumber AND road_part_number=$roadPart $filter"""
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
             (ra.START_DATE > pro.START_DATE or ra.END_DATE > pro.START_DATE) AND
             ra.VALID_TO is null) OR EXISTS (
             SELECT 1 FROM project_reserved_road_part pro, ROADWAY ra
              WHERE pro.project_id != $projectId AND pro.road_number = ra.road_number AND pro.road_part_number = ra.road_part_number
               AND pro.road_number = $roadNumber AND pro.road_part_number = $roadPartNumber AND ra.end_date IS NULL)"""
      Q.queryNA[Int](query).firstOption.nonEmpty
    }
  }
}
