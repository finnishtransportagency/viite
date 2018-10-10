package fi.liikennevirasto.viite.dao

import java.sql.Timestamp
import java.util.Date

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.PolyLine
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.{BaseCalibrationPoint, CalibrationPointMValues}
import fi.liikennevirasto.viite.dao.CalibrationPointSource.UnknownSource
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.LinkStatus.{NotHandled, UnChanged}
import fi.liikennevirasto.viite.dao.ProjectState.{Incomplete, Saved2TR}
import fi.liikennevirasto.viite.process.InvalidAddressDataException
import fi.liikennevirasto.viite.util.CalibrationPointsUtils
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
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
  private def logger = LoggerFactory.getLogger(getClass)

  /**
    * Removes reserved road part and deletes the project links associated to it.
    * Requires links that have been transferred to this road part to be reverted before
    * or this will fail.
    *
    * @param projectId        Project's id
    * @param reservedRoadPart Road part to be removed
    */
  def removeReservedRoadPart(projectId: Long, reservedRoadPart: ProjectReservedPart): Unit = {
    time(logger, "Remove reserved road part") {
      sqlu"""
           DELETE FROM PROJECT_LINK WHERE PROJECT_ID = $projectId AND
           (EXISTS (SELECT 1 FROM ROADWAY RA, LINEAR_LOCATION LC WHERE RA.ID = ROADWAY_ID AND
           RA.ROAD_NUMBER = ${reservedRoadPart.roadNumber} AND RA.ROAD_PART_NUMBER = ${reservedRoadPart.roadPartNumber}))
           OR (ROAD_NUMBER = ${reservedRoadPart.roadNumber} AND ROAD_PART_NUMBER = ${reservedRoadPart.roadPartNumber}
           AND (STATUS = ${LinkStatus.New.value} OR STATUS = ${LinkStatus.Numbering.value}))
           """.execute
      sqlu"""
         DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE id = ${reservedRoadPart.id}
         """.execute
    }
  }

  def removeReservedRoadPart(projectId: Long, roadNumber: Long, roadPartNumber: Long): Unit = {
    sqlu"""
         DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE project_id = ${projectId} and road_number = ${roadNumber} and road_part_number = ${roadPartNumber}
         """.execute
  }

  def removeReservedRoadPartsByProject(projectId: Long): Unit = {
    sqlu"""
         DELETE FROM PROJECT_RESERVED_ROAD_PART WHERE project_id = $projectId
         """.execute
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

  def getReservedRoadPart(projectId: Long, roadNumber: Long, roadPartNumber: Long): Long = {
    val query = s"""SELECT ID FROM PROJECT_RESERVED_ROAD_PART WHERE PROJECT_ID = $projectId AND
            ROAD_NUMBER = $roadNumber AND ROAD_PART_NUMBER = $roadPartNumber"""
    Q.queryNA[Long](query).list.head
  }

  implicit val getDiscontinuity = new GetResult[Option[Discontinuity]] {
    def apply(r: PositionedResult) = {
      r.nextLongOption().map(l => Discontinuity.apply(l))
    }
  }
}
