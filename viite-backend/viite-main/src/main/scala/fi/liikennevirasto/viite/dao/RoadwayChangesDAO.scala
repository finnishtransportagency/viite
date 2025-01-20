package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.viite.process.{ProjectDeltaCalculator, RoadwaySection}
import fi.liikennevirasto.viite.process.ProjectDeltaCalculator.{createTwoTrackOldAddressRoadParts, projectLinkDAO}
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, Discontinuity, RoadAddressChangeType, RoadPart, Track}
import fi.vaylavirasto.viite.util.DateTimeFormatters.dateOptTimeFormatter

import java.sql.{PreparedStatement, Timestamp}
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet

import scala.+:

case class RoadwayChangeSection(roadNumber: Option[Long], trackCode: Option[Long], startRoadPartNumber: Option[Long],
                                endRoadPartNumber: Option[Long], startAddressM: Option[Long], endAddressM: Option[Long],
                                administrativeClass: Option[AdministrativeClass], discontinuity: Option[Discontinuity], ely: Option[Long])

case class RoadwayChangeSectionTR(roadNumber: Option[Long], trackCode: Option[Long], startRoadPartNumber: Option[Long],
                                  endRoadPartNumber: Option[Long], startAddressM: Option[Long], endAddressM: Option[Long])

case class RoadwayChangeInfo(changeType: RoadAddressChangeType, source: RoadwayChangeSection, target: RoadwayChangeSection,
                             discontinuity: Discontinuity, administrativeClass: AdministrativeClass, reversed: Boolean, orderInChangeTable: Long, ely: Long = -1L)

case class ProjectRoadwayChange(projectId: Long, projectName: Option[String], ely: Long, user: String, changeDate: DateTime, changeInfo: RoadwayChangeInfo, projectStartDate: DateTime)

case class ChangeRow(projectId: Long, projectName: Option[String], createdBy: String, createdDate: Option[DateTime], startDate: Option[DateTime],
                     modifiedBy: String, modifiedDate: Option[DateTime], targetEly: Long, changeType: Int, sourceRoadNumber: Option[Long],
                     sourceTrackCode: Option[Long], sourceStartRoadPartNumber: Option[Long], sourceEndRoadPartNumber: Option[Long],
                     sourceStartAddressM: Option[Long], sourceEndAddressM: Option[Long], targetRoadNumber: Option[Long],
                     targetTrackCode: Option[Long], targetStartRoadPartNumber: Option[Long], targetEndRoadPartNumber: Option[Long],
                     targetStartAddressM: Option[Long], targetEndAddressM: Option[Long], targetDiscontinuity: Option[Int],
                     targetAdministrativeClass: Option[Int], sourceAdministrativeClass: Option[Int], sourceDiscontinuity: Option[Int],
                     sourceEly: Option[Long], reversed: Boolean, orderInTable: Long)

object RoadwayChange extends SQLSyntaxSupport[ChangeRow] {
  def apply(rs: WrappedResultSet): ChangeRow = {
    ChangeRow(
      projectId                 = rs.long("project_id"),
      projectName               = rs.stringOpt("name"),
      createdBy                 = rs.string("created_by"),
      createdDate               = rs.jodaDateTimeOpt("created_date"),
      startDate                 = rs.jodaDateTimeOpt("start_date"),
      modifiedBy                = rs.string("modified_by"),
      modifiedDate              = rs.jodaDateTimeOpt("modified_date"),
      targetEly                 = rs.long("new_ely"),
      changeType                = rs.int("change_type"),
      sourceRoadNumber          = rs.longOpt("old_road_number"),
      sourceTrackCode           = rs.longOpt("old_track"),
      sourceStartRoadPartNumber = rs.longOpt("old_road_part_number"),
      sourceEndRoadPartNumber   = rs.longOpt("old_road_part_number"),
      sourceStartAddressM       = rs.longOpt("old_start_addr_m"),
      sourceEndAddressM         = rs.longOpt("old_end_addr_m"),
      targetRoadNumber          = rs.longOpt("new_road_number"),
      targetTrackCode           = rs.longOpt("new_track"),
      targetStartRoadPartNumber = rs.longOpt("new_road_part_number"),
      targetEndRoadPartNumber   = rs.longOpt("new_road_part_number"),
      targetStartAddressM       = rs.longOpt("new_start_addr_m"),
      targetEndAddressM         = rs.longOpt("new_end_addr_m"),
      targetDiscontinuity       = rs.intOpt("new_discontinuity"),
      targetAdministrativeClass = rs.intOpt("new_administrative_class"),
      sourceAdministrativeClass = rs.intOpt("old_administrative_class"),
      sourceDiscontinuity       = rs.intOpt("old_discontinuity"),
      sourceEly                 = rs.longOpt("old_ely"),
      reversed                  = rs.boolean("reversed"),
      orderInTable              = rs.long("roadway_change_id")
    )
  }
}

case class ChangeTableRows(adjustedSections: Iterable[((RoadwaySection, RoadwaySection), Option[String])], originalSections: Iterable[(RoadwaySection, RoadwaySection)])

case class ChangeTableRows2(adjustedSections: Iterable[RoadwaySection], originalSections: Iterable[RoadwaySection])

case class RoadwayChangesInfo(roadwayChangeId: Long, startDate: DateTime, acceptedDate: DateTime, change_type: Long, reversed: Long,
                              old_road_number: Long, old_road_part_number: Long, old_track: Long, old_start_addr_m: Long, old_end_addr_m: Long, old_discontinuity: Long, old_administrative_class: Long, old_ely: Long,
                              new_road_number: Long, new_road_part_number: Long, new_track: Long, new_start_addr_m: Long, new_end_addr_m: Long, new_discontinuity: Long, new_administrative_class: Long, new_ely: Long)

object RoadwayChangesInfo  extends SQLSyntaxSupport[RoadwayChangeInfo] {
  def apply(rs: WrappedResultSet): RoadwayChangesInfo = new RoadwayChangesInfo(
    roadwayChangeId = rs.long("roadway_change_id"),
    startDate                = rs.jodaDateTime("start_date"),
    acceptedDate             = rs.jodaDateTime("accepted_date"),
    change_type              = rs.long("change_type"),
    reversed                 = rs.long("reversed"),
    old_road_number          = rs.longOpt("old_road_number").getOrElse(0L),
    old_road_part_number     = rs.longOpt("old_road_part_number").getOrElse(0L),
    old_track                = rs.longOpt("old_track").getOrElse(0L),
    old_start_addr_m         = rs.longOpt("old_start_addr_m").getOrElse(0L),
    old_end_addr_m           = rs.longOpt("old_end_addr_m").getOrElse(0L),
    old_discontinuity        = rs.longOpt("old_discontinuity").getOrElse(0L),
    old_administrative_class = rs.longOpt("old_administrative_class").getOrElse(0L),
    old_ely                  = rs.longOpt("old_ely").getOrElse(0L),
    new_road_number          = rs.longOpt("new_road_number").getOrElse(0L),
    new_road_part_number     = rs.longOpt("new_road_part_number").getOrElse(0L),
    new_track                = rs.longOpt("new_track").getOrElse(0L),
    new_start_addr_m         = rs.longOpt("new_start_addr_m").getOrElse(0L),
    new_end_addr_m           = rs.longOpt("new_end_addr_m").getOrElse(0L),
    new_discontinuity        = rs.long("new_discontinuity"),
    new_administrative_class = rs.long("new_administrative_class"),
    new_ely                  = rs.long("new_ely")
  )

}

case class OldRoadAddress(ely: Long, roadPart: Option[RoadPart], track: Option[Long],
                          startAddrM: Option[Long], endAddrM: Option[Long], length: Option[Long], administrativeClass: Long)

case class NewRoadAddress(ely: Long, roadPart: RoadPart, track: Long, addrMRange: AddrMRange, length: Long, administrativeClass: Long)

case class ChangeInfoForRoadAddressChangesBrowser(startDate: DateTime, changeType: Long, reversed: Long, roadName: Option[String], projectName: String,
                                                  projectAcceptedDate: DateTime,oldRoadAddress: OldRoadAddress, newRoadAddress: NewRoadAddress)



class RoadwayChangesDAO extends BaseDAO {

  private def toRoadwayChangeRecipient(row: ChangeRow) = {
    RoadwayChangeSection(row.targetRoadNumber, row.targetTrackCode, row.targetStartRoadPartNumber, row.targetEndRoadPartNumber, row.targetStartAddressM, row.targetEndAddressM,
      Some(AdministrativeClass.apply(row.targetAdministrativeClass.getOrElse(AdministrativeClass("Unknown").value))),
      Some(Discontinuity.apply(row.targetDiscontinuity.getOrElse(Discontinuity.Continuous.value))), Some(row.targetEly))
  }

  private def toRoadwayChangeSource(row: ChangeRow) = {
    RoadwayChangeSection(row.sourceRoadNumber, row.sourceTrackCode, row.sourceStartRoadPartNumber, row.sourceEndRoadPartNumber, row.sourceStartAddressM, row.sourceEndAddressM,
      Some(AdministrativeClass.apply(row.sourceAdministrativeClass.getOrElse(AdministrativeClass("Unknown").value))),
      Some(Discontinuity.apply(row.sourceDiscontinuity.getOrElse(Discontinuity.Continuous.value))), row.sourceEly)
  }

  private def toRoadwayChangeInfo(row: ChangeRow) = {
    val source = toRoadwayChangeSource(row)
    val target = toRoadwayChangeRecipient(row)
    RoadwayChangeInfo(RoadAddressChangeType.apply(row.changeType), source, target,
      Discontinuity.apply(row.targetDiscontinuity.getOrElse(Discontinuity.Continuous.value)),
      AdministrativeClass.apply(row.targetAdministrativeClass.getOrElse(AdministrativeClass("Unknown").value)),
      row.reversed,
      row.orderInTable,
      target.ely.getOrElse(source.ely.get))
  }

  // TODO: cleanup after modification dates and modified by are populated correctly
  private def getUserAndModDate(row: ChangeRow): (String, DateTime) = {
    val user = if (row.modifiedDate.isEmpty) {
      row.createdBy
    } else {
      if (row.modifiedDate.get.isAfter(row.createdDate.get)) {
        // modifiedBy currently always returns empty
        row.createdBy
      } else row.createdBy
    }
    val date = if (row.modifiedDate.isEmpty) {
      row.createdDate.get
    } else {
      if (row.modifiedDate.get.isAfter(row.createdDate.get)) {
        row.modifiedDate.get
      } else row.createdDate.get
    }
    (user, date)
  }

  private def queryList(query: SQL[Nothing, NoExtractor]): List[ProjectRoadwayChange] = {
    mapper(runSelectQuery(query.map(RoadwayChange.apply)))
  }

  private def queryResumeList(query: SQL[Nothing, NoExtractor]): List[ProjectRoadwayChange] = {
    mapper(runSelectQuery(query.map(RoadwayChange.apply)))
  }

  private def mapper(resultList: List[ChangeRow]): List[ProjectRoadwayChange] = {
    resultList.map { row =>
      val changeInfo = toRoadwayChangeInfo(row)
      val (user, date) = getUserAndModDate(row)
      ProjectRoadwayChange(row.projectId, row.projectName, row.targetEly, user, date, changeInfo, row.startDate.get)
    }
  }

  private def fetchRoadwayChanges(projectIds: Set[Long], queryList: SQL[Nothing, NoExtractor] => List[ProjectRoadwayChange]): List[ProjectRoadwayChange] = {
    if (projectIds.isEmpty)
      return List()

    val query =
      sql"""
            SELECT p.id AS project_id, p.name, p.created_by, p.created_date, p.start_date, p.modified_by,
                p.modified_date, rac.new_ely, rac.change_type, rac.old_road_number, rac.old_track,
                rac.old_road_part_number, rac.old_road_part_number,
                rac.old_start_addr_m, rac.old_end_addr_m, rac.new_road_number, rac.new_track,
                rac.new_road_part_number, rac.new_road_part_number,
                rac.new_start_addr_m, rac.new_end_addr_m, rac.new_discontinuity, rac.new_administrative_class, rac.old_administrative_class,
                rac.old_discontinuity, rac.old_ely, rac.reversed, rac.roadway_change_id
            FROM roadway_changes rac INNER JOIN project p ON rac.project_id = p.id
            WHERE rac.project_id IN ($projectIds)
            ORDER BY  COALESCE(rac.new_road_number, rac.old_road_number),
                      COALESCE(rac.new_road_part_number, rac.old_road_part_number),
                      COALESCE(rac.new_start_addr_m, rac.old_start_addr_m),
                      COALESCE(rac.new_track, rac.old_track),
                      change_type DESC
          """
    queryList(query)
  }

  def clearRoadChangeTable(projectId: Long): Unit = {
    runUpdateToDb(sql"""DELETE FROM roadway_changes_link WHERE project_id = $projectId""")
    runUpdateToDb(sql"""DELETE FROM roadway_changes WHERE project_id = $projectId""")
  }

  /** @return false, if project has no reservedParts, and no formedParts. True else. */
  def insertDeltaToRoadChangeTable(projectId: Long, project: Option[Project]): (Boolean, Option[String]) = {
    def addToBatch(roadwaySection: RoadwaySection, addressChangeType: RoadAddressChangeType,
                   batchParams: Seq[Seq[Any]]): (Seq[Seq[Any]], Seq[Seq[Any]]) = {

      val nextChangeOrderLink = Sequences.nextRoadwayChangeLink
      // Main roadway change parameters
      val roadwayChangeParams = addressChangeType match {
        case RoadAddressChangeType.New =>
          Seq(
            projectId,
            addressChangeType.value,
            null,
            roadwaySection.roadNumber,
            null,
            roadwaySection.roadPartNumberStart,
            null,
            roadwaySection.track.value,
            null,
            roadwaySection.startMAddr,
            null,
            roadwaySection.endMAddr,
            roadwaySection.discontinuity.value,
            roadwaySection.administrativeClass.value,
            roadwaySection.ely,
            roadwaySection.administrativeClass.value,
            roadwaySection.discontinuity.value,
            roadwaySection.ely,
            if (roadwaySection.reversed) 1 else 0,
            nextChangeOrderLink
          )
        case RoadAddressChangeType.Termination =>
          Seq(
            projectId,
            addressChangeType.value,
            roadwaySection.roadNumber,
            null,
            roadwaySection.roadPartNumberStart,
            null,
            roadwaySection.track.value,
            null,
            roadwaySection.startMAddr,
            null,
            roadwaySection.endMAddr,
            null,
            roadwaySection.discontinuity.value,
            roadwaySection.administrativeClass.value,
            roadwaySection.ely,
            roadwaySection.administrativeClass.value,
            roadwaySection.discontinuity.value,
            roadwaySection.ely,
            if (roadwaySection.reversed) 1 else 0,
            nextChangeOrderLink
          )
        case _ =>
          Seq(
            projectId,
            addressChangeType.value,
            roadwaySection.roadNumber,
            roadwaySection.roadNumber,
            roadwaySection.roadPartNumberStart,
            roadwaySection.roadPartNumberStart,
            roadwaySection.track.value,
            roadwaySection.track.value,
            roadwaySection.startMAddr,
            roadwaySection.startMAddr,
            roadwaySection.endMAddr,
            roadwaySection.endMAddr,
            roadwaySection.discontinuity.value,
            roadwaySection.administrativeClass.value,
            roadwaySection.ely,
            roadwaySection.administrativeClass.value,
            roadwaySection.discontinuity.value,
            roadwaySection.ely,
            if (roadwaySection.reversed) 1 else 0,
            nextChangeOrderLink
          )
      }

      // Link parameters
      val linkParams = roadwaySection.projectLinks.map { pl =>
        Seq(
          nextChangeOrderLink,
          projectId,
          pl.id
        )
      }
      (batchParams ++ Seq(roadwayChangeParams), linkParams)
    }

    def addToBatchWithOldValues(oldRoadwaySection: RoadwaySection, newRoadwaySection: RoadwaySection, addressChangeType: RoadAddressChangeType,
                                batchParams: Seq[Seq[Any]]): (Seq[Seq[Any]], Seq[Seq[Any]]) = {
      val nextChangeOrderLink = Sequences.nextRoadwayChangeLink

      // Main roadway change parameters
      val roadwayChangeParams = Seq(
        projectId,
        addressChangeType.value,
        oldRoadwaySection.roadNumber,
        newRoadwaySection.roadNumber,
        oldRoadwaySection.roadPartNumberStart.toInt,
        newRoadwaySection.roadPartNumberStart.toInt,
        oldRoadwaySection.track.value,
        newRoadwaySection.track.value,
        oldRoadwaySection.startMAddr,
        newRoadwaySection.startMAddr,
        oldRoadwaySection.endMAddr,
        newRoadwaySection.endMAddr,
        newRoadwaySection.discontinuity.value,
        newRoadwaySection.administrativeClass.value,
        newRoadwaySection.ely.toInt,
        oldRoadwaySection.administrativeClass.value,
        oldRoadwaySection.discontinuity.value,
        oldRoadwaySection.ely.toInt,
        if (newRoadwaySection.reversed) 1 else 0,
        nextChangeOrderLink
      )

      // Link parameters - combining links from both old and new sections
      val projectLinkIdsToAdd = (oldRoadwaySection.projectLinks ++ newRoadwaySection.projectLinks).map(_.id).toSet
      val linkParams = projectLinkIdsToAdd.map { projectLinkId =>
        Seq(
          nextChangeOrderLink,
          projectId,
          projectLinkId
        )
      }.toSeq

      (batchParams ++ Seq(roadwayChangeParams), linkParams)

    }

    val startTime = System.currentTimeMillis()
    logger.info("Begin delta insertion in ChangeTable")

    project match {
      case Some(project) =>
        if (project.reservedParts.nonEmpty || project.formedParts.nonEmpty) {
          val roadwayChangeQuery =
            sql"""
            INSERT INTO roadway_changes(
              project_id, change_type,old_road_number,new_road_number,old_road_part_number,new_road_part_number,
              old_track,new_track,old_start_addr_m,new_start_addr_m,old_end_addr_m,new_end_addr_m,
              new_discontinuity,new_administrative_class,new_ely, old_administrative_class, old_discontinuity, old_ely, reversed, roadway_change_id
              )
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """

          val roadWayChangeLinkQuery =
            sql"""
            INSERT INTO roadway_changes_link(roadway_change_id, project_id, project_link_id)
            VALUES (?,?,?)
            """

          val allProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)
          val nonTerminatedProjectlinks = allProjectLinks.filter(_.status != RoadAddressChangeType.Termination)

          val changeTableRows = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(nonTerminatedProjectlinks, allProjectLinks)

          // Initialize empty sequence for batch parameters
          var roadwayParams = Seq[Seq[Any]]()
          var linkParams = Seq[Seq[Any]]()

          val unChanged_roadway_sections = changeTableRows.adjustedSections.zip(changeTableRows.originalSections)
            .filter(_._1.projectLinks.exists(_.status == RoadAddressChangeType.Unchanged))
          val transferred_roadway_sections = changeTableRows.adjustedSections.zip(changeTableRows.originalSections)
            .filter(_._1.projectLinks.exists(_.status == RoadAddressChangeType.Transfer))
          val new_roadway_sections = changeTableRows.adjustedSections.zip(changeTableRows.originalSections)
            .filter(_._1.projectLinks.exists(_.status == RoadAddressChangeType.New))
          val numbering_sections = changeTableRows.adjustedSections.zip(changeTableRows.originalSections)
            .filter(_._1.projectLinks.exists(_.status == RoadAddressChangeType.Renumeration))


          unChanged_roadway_sections.foreach { case (roadwaySection1, roadwaySection2) =>
            val (newRoadParams, newLinkParams) = addToBatchWithOldValues(roadwaySection2, roadwaySection1, RoadAddressChangeType.Unchanged, roadwayParams)
            roadwayParams = newRoadParams
            linkParams = linkParams ++ newLinkParams
          }

          transferred_roadway_sections.foreach { case (roadwaySection1, roadwaySection2) =>
            val (newRoadParams, newLinkParams) = addToBatchWithOldValues(roadwaySection2, roadwaySection1, RoadAddressChangeType.Transfer, roadwayParams)
            roadwayParams = newRoadParams
            linkParams = linkParams ++ newLinkParams
          }

          numbering_sections.foreach { case (roadwaySection1, roadwaySection2) =>
            val (newRoadParams, newLinkParams) = addToBatchWithOldValues(roadwaySection2, roadwaySection1, RoadAddressChangeType.Renumeration, roadwayParams)
            roadwayParams = newRoadParams
            linkParams = linkParams ++ newLinkParams
          }

          new_roadway_sections.foreach { roadwaySection =>
            val (newRoadParams, newLinkParams) = addToBatch(roadwaySection._1, RoadAddressChangeType.New, roadwayParams)
            roadwayParams = newRoadParams
            linkParams = linkParams ++ newLinkParams
          }

          val terminated = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(
            allProjectLinks.filter(_.status == RoadAddressChangeType.Termination),
            allProjectLinks
          )

          val twoTrackOldAddressRoadParts = createTwoTrackOldAddressRoadParts(
            unChanged_roadway_sections,
            transferred_roadway_sections,
            terminated
          )

          val old_road_two_track_parts = ProjectDeltaCalculator.matchTerminatedRoadwaySections(twoTrackOldAddressRoadParts)

          val twoTrackAdjustedTerminated = old_road_two_track_parts.flatMap(_._1) ++ old_road_two_track_parts.flatMap(_._2)
          val combinedTerminatedTrack = terminated.adjustedSections.filter(_.track == Track.Combined)

          val adjustedTerminated = combinedTerminatedTrack ++ twoTrackAdjustedTerminated

          adjustedTerminated.foreach { roadwaySection =>
            val (newRoadParams, newLinkParams) = addToBatch(roadwaySection, RoadAddressChangeType.Termination, roadwayParams)
            roadwayParams = newRoadParams
            linkParams = linkParams ++ newLinkParams
          }

          // Execute batches
          runBatchUpdateToDb(roadwayChangeQuery, roadwayParams)
          runBatchUpdateToDb(roadWayChangeLinkQuery, linkParams)

          val endTime = System.currentTimeMillis()
          logger.info("Delta insertion in ChangeTable completed in %d ms".format(endTime - startTime))
          val warning = Seq()
          (true, if (warning.nonEmpty) Option(warning.head) else None)
        }
        else {
          (false, None)
        }
      case _ => (false, None)
    }
  }

  def fetchRoadwayChanges(projectIds: Set[Long]): List[ProjectRoadwayChange] = {
    fetchRoadwayChanges(projectIds, queryList)
  }

  def fetchRoadwayChangesResume(projectIds: Set[Long]): List[ProjectRoadwayChange] = {
    fetchRoadwayChanges(projectIds, queryResumeList)
  }

  // This query should return changes in roadway_change table
  // Query should return information also about terminated roads
  def fetchRoadwayChangesInfo(sinceAcceptedDate: DateTime, untilAcceptedDate: Option[DateTime]): Seq[RoadwayChangesInfo] = {
    val untilString = if (untilAcceptedDate.nonEmpty) sqls"AND P.accepted_date <= to_timestamp(${new Timestamp(untilAcceptedDate.get.getMillis)}, 'YYYY-MM-DD HH24:MI:SS.FF')" else sqls""
    val query =
      sql"""
      SELECT
        rc.roadway_change_id, P.START_DATE, P.accepted_date, rc.change_type, rc.reversed, rc.old_road_number,
        rc.old_road_part_number, rc.old_track, rc.old_start_addr_m, rc.old_end_addr_m, rc.old_discontinuity,
        rc.old_administrative_class, rc.old_ely, rc.new_road_number, rc.new_road_part_number, rc.new_track,
        rc.new_start_addr_m, rc.new_end_addr_m, rc.new_discontinuity, rc.new_administrative_class, rc.new_ely
      FROM roadway_changes rc
      INNER JOIN PROJECT p
        ON P.ID = rc.PROJECT_ID
      WHERE P.STATE=${ProjectState.Accepted.value}
        AND P.accepted_date >= to_timestamp(${new Timestamp(sinceAcceptedDate.getMillis)}, 'YYYY-MM-DD HH24:MI:SS.FF')
        $untilString
        ORDER BY P.accepted_date, rc.roadway_change_id
     """
    runSelectQuery(query.map(RoadwayChangesInfo.apply))
  }


  object ChangeInfoForRoadAddressChangesBrowser extends SQLSyntaxSupport[ChangeInfoForRoadAddressChangesBrowser] {
    def apply(rs: WrappedResultSet): ChangeInfoForRoadAddressChangesBrowser = new ChangeInfoForRoadAddressChangesBrowser(
        startDate           = rs.jodaDateTime("start_date"),
        changeType          = rs.long("change_type"),
        reversed            = rs.long("reversed"),
        roadName            = rs.stringOpt("road_name"),
        projectName         = rs.string("name"),
        projectAcceptedDate = rs.jodaDateTime("accepted_date"),
        oldRoadAddress      = OldRoadAddress(
          ely               = rs.long("old_ely"),
          roadPart          = {
            val oldRoadNumber     = rs.longOpt("old_road_number")
            val oldRoadPartNumber = rs.longOpt("old_road_part_number")
            if(oldRoadNumber.nonEmpty && oldRoadPartNumber.nonEmpty)
              Some(RoadPart(oldRoadNumber.get, oldRoadPartNumber.get))
            else
              None
          },
          track               = rs.longOpt("old_track"),
          startAddrM          = rs.longOpt("old_start_addr_m"),
          endAddrM            = rs.longOpt("old_end_addr_m"),
          length              = rs.longOpt("old_length"),
          administrativeClass = rs.long("old_administrative_class")
        ),
        newRoadAddress        = NewRoadAddress(
          ely                 = rs.long("new_ely"),
          roadPart            = RoadPart(
            roadNumber        = rs.longOpt("new_road_number").getOrElse(0L),
            partNumber        = rs.longOpt("new_road_part_number").getOrElse(0L)
          ),
          track               = rs.longOpt("new_track").getOrElse(0L),
          addrMRange          = AddrMRange(
            start             = rs.longOpt("new_start_addr_m").getOrElse(0L),
            end               = rs.longOpt("new_end_addr_m").getOrElse(0L)
          ),
          length              = rs.longOpt("new_length").getOrElse(0L),
          administrativeClass = rs.long("new_administrative_class")
        )
      )
  }

  def fetchChangeInfosForRoadAddressChangesBrowser(startDate: Option[String], endDate: Option[String], dateTarget: Option[String],
                                                   ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long],
                                                   maxRoadPartNumber: Option[Long]): Seq[ChangeInfoForRoadAddressChangesBrowser] = {

    // Determine the date field to use based on dateTarget
    val dateField = dateTarget match {
      case Some("ProjectAcceptedDate") => sqls"p.accepted_date"
      case Some("RoadAddressStartDate") => sqls"p.start_date"
      case _ => sqls"p.accepted_date"
    }

    val dateConditions = Seq.empty[SQLSyntax] ++
      startDate.map(sd => sqls"$dateField >= TO_TIMESTAMP($sd, 'YYYY-MM-DD')") ++
      endDate.map(ed => sqls"$dateField <= TO_TIMESTAMP($ed, 'YYYY-MM-DD')")

    // These conditions will determine which projects to include based on search criteria
    // Return every roadway change within the projects matching the specific filters
    val elyAndRoadNumberConditions = Seq.empty[SQLSyntax] ++
      ely.map(e => sqls"(rc.new_ely = $e OR rc.old_ely = $e)") ++
      roadNumber.map(rn => sqls"(rc.new_road_number = $rn OR rc.old_road_number = $rn)")

    val roadPartCondition = (minRoadPartNumber, maxRoadPartNumber) match {
      case (Some(minPart), Some(maxPart)) =>
        Seq(sqls"(rc.new_road_part_number BETWEEN $minPart AND $maxPart OR rc.old_road_part_number BETWEEN $minPart AND $maxPart)")
      case _ =>
        Seq.empty[SQLSyntax]
    }

    val projectRelatedConditions = elyAndRoadNumberConditions ++ roadPartCondition

    // Combine conditions with AND
    val dateWhereClause = if (dateConditions.isEmpty) sqls"" else sqls.join(dateConditions, sqls" AND ")
    val projectWhereClause = if (projectRelatedConditions.isEmpty) sqls"" else sqls.join(projectRelatedConditions, sqls" AND ")


    // The final SQL fetches all changes for projects matching the initial criteria
    val query =
      sql"""
        WITH RelevantProjects AS (
          SELECT DISTINCT p.id FROM project p
          JOIN roadway_changes rc ON rc.project_id = p.id
          WHERE $dateWhereClause AND ($projectWhereClause)
        ), AllRelatedRoadwayChanges AS (
          SELECT rc.* FROM roadway_changes rc
          JOIN RelevantProjects rp ON rp.id = rc.project_id
        )
        SELECT
          p.start_date, rc.change_type, rc.reversed, rn.road_name, p.name, p.accepted_date,
          rc.old_ely, rc.old_road_number, rc.old_track, rc.old_road_part_number,
          rc.old_start_addr_m, rc.old_end_addr_m, rc.old_end_addr_m - rc.old_start_addr_m AS old_length, rc.old_administrative_class,
          rc.new_ely, rc.new_road_number, rc.new_track, rc.new_road_part_number,
          rc.new_start_addr_m, rc.new_end_addr_m, rc.new_end_addr_m - rc.new_start_addr_m AS new_length, rc.new_administrative_class
        FROM AllRelatedRoadwayChanges rc
        JOIN project p ON rc.project_id = p.id
        LEFT JOIN road_name rn ON rn.road_number = COALESCE(rc.new_road_number, rc.old_road_number)
          AND rn.valid_to IS NULL
          AND rn.start_date <= p.start_date
           -- End date should be null if the change is not a termination (5).
           -- If the road is terminated, the end date is the same as the end date of the road  (if the whole road was terminated in this project) or null if the start date of the road name start date is earlier than the start date of the project
          AND ((rc.change_type != 5 and rn.end_date IS null) OR (rc.change_type = 5 and (rn.end_date = (p.start_date - INTERVAL '1 DAY') or (rn.end_date is null and rn.start_date < p.start_date))))

        ORDER BY p.start_date, rc.new_road_number, rc.new_road_part_number, rc.new_start_addr_m, rc.new_track
      """


    runSelectQuery(query.map(ChangeInfoForRoadAddressChangesBrowser.apply))
  }

}
