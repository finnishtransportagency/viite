package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.viite.process.{ProjectDeltaCalculator, RoadwaySection}
import fi.liikennevirasto.viite.process.ProjectDeltaCalculator.{createTwoTrackOldAddressRoadParts, projectLinkDAO}
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.model.{AdministrativeClass, Discontinuity, RoadAddressChangeType, Track}

import java.sql.{PreparedStatement, Timestamp}
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import slick.jdbc.StaticQuery.interpolation

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

case class ChangeTableRows(adjustedSections: Iterable[((RoadwaySection, RoadwaySection), Option[String])], originalSections: Iterable[(RoadwaySection, RoadwaySection)])

case class ChangeTableRows2(adjustedSections: Iterable[RoadwaySection], originalSections: Iterable[RoadwaySection])
case class ChangeTableRows3(terminatedSections: Iterable[RoadwaySection])

case class RoadwayChangesInfo(roadwayChangeId: Long, startDate: DateTime, acceptedDate: DateTime, change_type: Long, reversed: Long,
                              old_road_number: Long, old_road_part_number: Long, old_TRACK: Long, old_start_addr_m: Long, old_end_addr_m: Long, old_discontinuity: Long, old_administrative_class: Long, old_ely: Long,
                              new_road_number: Long, new_road_part_number: Long, new_TRACK: Long, new_start_addr_m: Long, new_end_addr_m: Long, new_discontinuity: Long, new_administrative_class: Long, new_ely: Long)

case class OldRoadAddress(ely: Long, roadNumber: Option[Long], track: Option[Long], roadPartNumber: Option[Long],
                          startAddrM: Option[Long], endAddrM: Option[Long], length: Option[Long], administrativeClass: Long)

case class NewRoadAddress(ely: Long, roadNumber: Long, track: Long, roadPartNumber: Long, startAddrM: Long,
                          endAddrM: Long, length: Long, administrativeClass: Long)

case class ChangeInfoForRoadAddressChangesBrowser(startDate: DateTime, changeType: Long, reversed: Long, roadName: Option[String], projectName: String,
                                                  projectAcceptedDate: DateTime,oldRoadAddress: OldRoadAddress, newRoadAddress: NewRoadAddress)




class RoadwayChangesDAO {
  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()
  val projectDAO = new ProjectDAO
  implicit val getDiscontinuity: GetResult[Discontinuity] = GetResult[Discontinuity](r => Discontinuity.apply(r.nextInt()))

  implicit val getAddressChangeType: GetResult[RoadAddressChangeType] = GetResult[RoadAddressChangeType](r => RoadAddressChangeType.apply(r.nextInt()))

  implicit val getAdministrativeClass: GetResult[AdministrativeClass] = GetResult[AdministrativeClass](r => AdministrativeClass.apply(r.nextInt()))

  implicit val getRoadwayChangeRow: GetResult[ChangeRow] = new GetResult[ChangeRow] {
    def apply(r: PositionedResult) = {
      val projectId = r.nextLong
      val projectName = r.nextStringOption
      val createdBy = r.nextString
      val createdDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val startDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val modifiedBy = r.nextString
      val modifiedDate = r.nextDateOption.map(d => formatter.parseDateTime(d.toString))
      val targetEly = r.nextLong
      val changeType = r.nextInt
      val sourceRoadNumber = r.nextLongOption
      val sourceTrackCode = r.nextLongOption
      val sourceStartRoadPartNumber = r.nextLongOption
      val sourceEndRoadPartNumber = r.nextLongOption
      val sourceStartAddressM = r.nextLongOption
      val sourceEndAddressM = r.nextLongOption
      val targetRoadNumber = r.nextLongOption
      val targetTrackCode = r.nextLongOption
      val targetStartRoadPartNumber = r.nextLongOption
      val targetEndRoadPartNumber = r.nextLongOption
      val targetStartAddressM = r.nextLongOption
      val targetEndAddressM = r.nextLongOption
      val targetDiscontinuity = r.nextIntOption
      val targetAdministrativeClass = r.nextIntOption
      val sourceAdministrativeClass= r.nextIntOption
      val sourceDiscontinuity = r.nextIntOption
      val sourceEly = r.nextLongOption
      val reversed = r.nextBoolean
      val orderInTable = r.nextLong

      ChangeRow(projectId, projectName: Option[String], createdBy: String, createdDate: Option[DateTime], startDate: Option[DateTime],
        modifiedBy: String, modifiedDate: Option[DateTime], targetEly: Long, changeType: Int, sourceRoadNumber: Option[Long],
        sourceTrackCode: Option[Long], sourceStartRoadPartNumber: Option[Long], sourceEndRoadPartNumber: Option[Long],
        sourceStartAddressM: Option[Long], sourceEndAddressM: Option[Long], targetRoadNumber: Option[Long],
        targetTrackCode: Option[Long], targetStartRoadPartNumber: Option[Long], targetEndRoadPartNumber: Option[Long],
        targetStartAddressM: Option[Long], targetEndAddressM: Option[Long], targetDiscontinuity: Option[Int],
        targetAdministrativeClass: Option[Int], sourceAdministrativeClass: Option[Int], sourceDiscontinuity: Option[Int],
        sourceEly: Option[Long], reversed: Boolean, orderInTable: Long)
    }
  }

  val logger = LoggerFactory.getLogger(getClass)

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

  private def queryList(query: String) = {
    mapper(Q.queryNA[ChangeRow](query).list)
  }

  private def queryResumeList(query: String) = {
    mapper(Q.queryNA[ChangeRow](query).list)
  }

  private def mapper(resultList: List[ChangeRow]): List[ProjectRoadwayChange] = {
    resultList.map { row => {
      val changeInfo = toRoadwayChangeInfo(row)
      val (user, date) = getUserAndModDate(row)
      ProjectRoadwayChange(row.projectId, row.projectName, row.targetEly, user, date, changeInfo, row.startDate.get)
    }
    }
  }

  private def fetchRoadwayChanges(projectIds: Set[Long], queryList: String => List[ProjectRoadwayChange]): List[ProjectRoadwayChange] = {
    if (projectIds.isEmpty)
      return List()
    val projectIdsString = projectIds.mkString(",")
    val withProjectIds = s""" where rac.project_id in ($projectIdsString)"""
    val query =
      s"""Select p.id as project_id, p.name, p.created_by, p.created_date, p.start_date, p.modified_by,
                p.modified_date, rac.new_ely, rac.change_type, rac.old_road_number, rac.old_TRACK,
                rac.old_road_part_number, rac.old_road_part_number,
                rac.old_start_addr_m, rac.old_end_addr_m, rac.new_road_number, rac.new_TRACK,
                rac.new_road_part_number, rac.new_road_part_number,
                rac.new_start_addr_m, rac.new_end_addr_m, rac.new_discontinuity, rac.new_administrative_class, rac.old_administrative_class,
                rac.old_discontinuity, rac.old_ely, rac.reversed, rac.ROADWAY_CHANGE_ID
                From ROADWAY_CHANGES rac Inner Join Project p on rac.project_id = p.id
                $withProjectIds
                ORDER BY COALESCE(rac.new_road_number, rac.old_road_number), COALESCE(rac.new_road_part_number, rac.old_road_part_number),
                  COALESCE(rac.new_start_addr_m, rac.old_start_addr_m), COALESCE(rac.new_TRACK, rac.old_TRACK),
                  CHANGE_TYPE DESC"""
    queryList(query)
  }

  def fetchRoadwayChangesLinks (projectId: Long) : Seq[(Long, Long)] = {
    Q.queryNA[(Long, Long)](s"""SELECT ROADWAY_CHANGE_ID, PROJECT_LINK_ID FROM ROADWAY_CHANGES_LINK WHERE PROJECT_ID = $projectId""").list.map(x => x._1 -> x._2)
  }

  def clearRoadChangeTable(projectId: Long): Unit = {
    sqlu"""DELETE FROM ROADWAY_CHANGES_LINK WHERE project_id = $projectId""".execute
    sqlu"""DELETE FROM ROADWAY_CHANGES WHERE project_id = $projectId""".execute
  }

  /** @return false, if project has no reservedParts, and no formedParts. True else. */
  def insertDeltaToRoadChangeTable(projectId: Long, project: Option[Project]): (Boolean, Option[String]) = {
    def addToBatch(roadwaySection: RoadwaySection, addressChangeType: RoadAddressChangeType,
                   roadwayChangePS: PreparedStatement, roadWayChangesLinkPS: PreparedStatement): Unit = {
      val nextChangeOrderLink = Sequences.nextRoadwayChangeLink
      addressChangeType match {
        case RoadAddressChangeType.New =>
          roadwayChangePS.setNull(3, java.sql.Types.INTEGER)
          roadwayChangePS.setLong(4, roadwaySection.roadNumber)
          roadwayChangePS.setNull(5, java.sql.Types.INTEGER)
          roadwayChangePS.setLong(6, roadwaySection.roadPartNumberStart)
          roadwayChangePS.setNull(7, java.sql.Types.INTEGER)
          roadwayChangePS.setLong(8, roadwaySection.track.value)
          roadwayChangePS.setNull(9, java.sql.Types.INTEGER)
          roadwayChangePS.setLong(10, roadwaySection.startMAddr)
          roadwayChangePS.setNull(11, java.sql.Types.INTEGER)
          roadwayChangePS.setLong(12, roadwaySection.endMAddr)
        case RoadAddressChangeType.Termination =>
          roadwayChangePS.setLong(3, roadwaySection.roadNumber)
          roadwayChangePS.setNull(4, java.sql.Types.INTEGER)
          roadwayChangePS.setLong(5, roadwaySection.roadPartNumberStart)
          roadwayChangePS.setNull(6, java.sql.Types.INTEGER)
          roadwayChangePS.setLong(7, roadwaySection.track.value)
          roadwayChangePS.setNull(8, java.sql.Types.INTEGER)
          roadwayChangePS.setLong(9, roadwaySection.startMAddr)
          roadwayChangePS.setNull(10, java.sql.Types.INTEGER)
          roadwayChangePS.setLong(11, roadwaySection.endMAddr)
          roadwayChangePS.setNull(12, java.sql.Types.INTEGER)
        case _ =>
          roadwayChangePS.setLong(3, roadwaySection.roadNumber)
          roadwayChangePS.setLong(4, roadwaySection.roadNumber)
          roadwayChangePS.setLong(5, roadwaySection.roadPartNumberStart)
          roadwayChangePS.setLong(6, roadwaySection.roadPartNumberStart)
          roadwayChangePS.setLong(7, roadwaySection.track.value)
          roadwayChangePS.setLong(8, roadwaySection.track.value)
          roadwayChangePS.setLong(9, roadwaySection.startMAddr)
          roadwayChangePS.setLong(10, roadwaySection.startMAddr)
          roadwayChangePS.setLong(11, roadwaySection.endMAddr)
          roadwayChangePS.setLong(12, roadwaySection.endMAddr)
      }
      roadwayChangePS.setLong(1, projectId)
      roadwayChangePS.setLong(2, addressChangeType.value)
      roadwayChangePS.setLong(13, roadwaySection.discontinuity.value)
      roadwayChangePS.setLong(14, roadwaySection.administrativeClass.value)
      roadwayChangePS.setLong(15, roadwaySection.ely)
      roadwayChangePS.setLong(16, roadwaySection.administrativeClass.value)
      roadwayChangePS.setLong(17, roadwaySection.discontinuity.value)
      roadwayChangePS.setLong(18, roadwaySection.ely)
      roadwayChangePS.setLong(19, if (roadwaySection.reversed) 1 else 0)
      roadwayChangePS.setLong(20, nextChangeOrderLink)

      roadwayChangePS.addBatch()

      roadwaySection.projectLinks.foreach {
        pl =>
          roadWayChangesLinkPS.setLong(1, nextChangeOrderLink)
          roadWayChangesLinkPS.setLong(2, projectId)
          roadWayChangesLinkPS.setLong(3, pl.id)
          roadWayChangesLinkPS.addBatch()
      }
    }

    def addToBatchWithOldValues(oldRoadwaySection: RoadwaySection, newRoadwaySection: RoadwaySection,
                                addressChangeType: RoadAddressChangeType, roadwayChangePS: PreparedStatement,
                                roadWayChangesLinkPS: PreparedStatement): Unit = {
      val nextChangeOrderLink = Sequences.nextRoadwayChangeLink
      roadwayChangePS.setLong(1, projectId)
      roadwayChangePS.setLong(2, addressChangeType.value)
      roadwayChangePS.setLong(3, oldRoadwaySection.roadNumber)
      roadwayChangePS.setLong(4, newRoadwaySection.roadNumber)
      roadwayChangePS.setLong(5, oldRoadwaySection.roadPartNumberStart)
      roadwayChangePS.setLong(6, newRoadwaySection.roadPartNumberStart)
      roadwayChangePS.setLong(7, oldRoadwaySection.track.value)
      roadwayChangePS.setLong(8, newRoadwaySection.track.value)
      roadwayChangePS.setDouble(9, oldRoadwaySection.startMAddr)
      roadwayChangePS.setDouble(10, newRoadwaySection.startMAddr)
      roadwayChangePS.setDouble(11, oldRoadwaySection.endMAddr)
      roadwayChangePS.setDouble(12, newRoadwaySection.endMAddr)
      roadwayChangePS.setLong(13, newRoadwaySection.discontinuity.value)
      roadwayChangePS.setLong(14, newRoadwaySection.administrativeClass.value)
      roadwayChangePS.setLong(15, newRoadwaySection.ely)
      roadwayChangePS.setLong(16, oldRoadwaySection.administrativeClass.value)
      roadwayChangePS.setLong(17, oldRoadwaySection.discontinuity.value)
      roadwayChangePS.setLong(18, oldRoadwaySection.ely)
      roadwayChangePS.setLong(19, if (newRoadwaySection.reversed) 1 else 0)
      roadwayChangePS.setLong(20, nextChangeOrderLink)
      roadwayChangePS.addBatch()

      val projectLinkIdsToAdd = (oldRoadwaySection.projectLinks ++ newRoadwaySection.projectLinks).map(_.id).toSet
      projectLinkIdsToAdd.foreach {
        projectLinkId =>
          roadWayChangesLinkPS.setLong(1, nextChangeOrderLink)
          roadWayChangesLinkPS.setLong(2, projectId)
          roadWayChangesLinkPS.setLong(3, projectLinkId)
          roadWayChangesLinkPS.addBatch()
      }
    }

    val startTime = System.currentTimeMillis()
    logger.info("Begin delta insertion in ChangeTable")
    project match {
      case Some(project) =>
        if (project.reservedParts.nonEmpty || project.formedParts.nonEmpty) {
          val roadwayChangePS = dynamicSession.prepareStatement("INSERT INTO ROADWAY_CHANGES " +
            "(project_id, change_type,old_road_number,new_road_number,old_road_part_number,new_road_part_number, " +
            "old_TRACK,new_TRACK,old_start_addr_m,new_start_addr_m,old_end_addr_m,new_end_addr_m," +
            "new_discontinuity,new_administrative_class,new_ely, old_administrative_class, old_discontinuity, old_ely, reversed, roadway_change_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")

          val roadWayChangesLinkPS = dynamicSession.prepareStatement("INSERT INTO ROADWAY_CHANGES_LINK " +
            "(roadway_change_id, project_id, project_link_id) values (?,?,?)")

          val allProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)

          val nonTerminatedProjectlinks = allProjectLinks.filter(_.status != RoadAddressChangeType.Termination)

          val changeTableRows = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(nonTerminatedProjectlinks, allProjectLinks)
          val unChanged_roadway_sections = changeTableRows.adjustedSections.zip(changeTableRows.originalSections).filter(_._1.projectLinks.exists(_.status == RoadAddressChangeType.Unchanged))
          val transferred_roadway_sections = changeTableRows.adjustedSections.zip(changeTableRows.originalSections).filter(_._1.projectLinks.exists(_.status == RoadAddressChangeType.Transfer))
          val new_roadway_sections = changeTableRows.adjustedSections.zip(changeTableRows.originalSections).filter(_._1.projectLinks.exists(_.status == RoadAddressChangeType.New))
          val numbering_sections = changeTableRows.adjustedSections.zip(changeTableRows.originalSections).filter(_._1.projectLinks.exists(_.status == RoadAddressChangeType.Renumeration))

          unChanged_roadway_sections.foreach { case (roadwaySection1, roadwaySection2) =>
            addToBatchWithOldValues(roadwaySection2, roadwaySection1, RoadAddressChangeType.Unchanged, roadwayChangePS, roadWayChangesLinkPS)
          }

          transferred_roadway_sections.foreach { case (roadwaySection1, roadwaySection2) =>
            addToBatchWithOldValues(roadwaySection2, roadwaySection1, RoadAddressChangeType.Transfer, roadwayChangePS, roadWayChangesLinkPS)
          }

          numbering_sections.foreach { case (roadwaySection1, roadwaySection2) =>
            addToBatchWithOldValues(roadwaySection2, roadwaySection1, RoadAddressChangeType.Renumeration, roadwayChangePS, roadWayChangesLinkPS)
          }

          new_roadway_sections.foreach(roadwaySection => addToBatch(roadwaySection._1, RoadAddressChangeType.New, roadwayChangePS, roadWayChangesLinkPS))

          val terminated = ProjectDeltaCalculator.generateChangeTableRowsFromProjectLinks(allProjectLinks.filter(_.status == RoadAddressChangeType.Termination), allProjectLinks)

          val twoTrackOldAddressRoadParts = createTwoTrackOldAddressRoadParts(unChanged_roadway_sections,transferred_roadway_sections, terminated)
          val old_road_two_track_parts = ProjectDeltaCalculator.matchTerminatedRoadwaySections(twoTrackOldAddressRoadParts)

          val twoTrackAdjustedTerminated = old_road_two_track_parts.flatMap(_._1) ++ old_road_two_track_parts.flatMap(_._2)
          val combinedTerminatedTrack = terminated.adjustedSections.filter(_.track == Track.Combined)

          val adjustedTerminated = combinedTerminatedTrack ++ twoTrackAdjustedTerminated

          adjustedTerminated.foreach(roadwaySection =>
            addToBatch(roadwaySection, RoadAddressChangeType.Termination, roadwayChangePS, roadWayChangesLinkPS)
          )

          roadwayChangePS.executeBatch()
          roadwayChangePS.close()
          roadWayChangesLinkPS.executeBatch()
          roadWayChangesLinkPS.close()
          val endTime = System.currentTimeMillis()
          logger.info("Delta insertion in ChangeTable completed in %d ms".format(endTime - startTime))
          val warning = Seq()
          (true, if (warning.nonEmpty) Option(warning.head) else None)
        } else {
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
    val untilString = if (untilAcceptedDate.nonEmpty) s"AND P.ACCEPTED_DATE <= to_timestamp('${new Timestamp(untilAcceptedDate.get.getMillis)}', 'YYYY-MM-DD HH24:MI:SS.FF')" else s""
    val query =
      s"""
SELECT
      RC.ROADWAY_CHANGE_ID
    , P.START_DATE
    , P.ACCEPTED_DATE
    , RC.change_type
    , RC.reversed
    , RC.old_road_number
    , RC.old_road_part_number
    , RC.old_TRACK
    , RC.old_start_addr_m
    , RC.old_end_addr_m
    , RC.old_discontinuity
    , RC.old_administrative_class
    , RC.old_ely
    , RC.new_road_number
    , RC.new_road_part_number
    , RC.new_TRACK
    , RC.new_start_addr_m
    , RC.new_end_addr_m
    , RC.new_discontinuity
    , RC.new_administrative_class
    , RC.new_ely
    FROM ROADWAY_CHANGES RC
      INNER JOIN PROJECT P
        ON P.ID = RC.PROJECT_ID
    WHERE P.STATE=${ProjectState.Accepted.value}
        AND P.ACCEPTED_DATE >= to_timestamp('${new Timestamp(sinceAcceptedDate.getMillis)}', 'YYYY-MM-DD HH24:MI:SS.FF')
        $untilString
        ORDER BY P.ACCEPTED_DATE, RC.ROADWAY_CHANGE_ID
     """

    Q.queryNA[RoadwayChangesInfo](query).iterator.toSeq
  }

  private implicit val getRoadwayChangesInfo: GetResult[RoadwayChangesInfo] = new GetResult[RoadwayChangesInfo] {
    def apply(r: PositionedResult) = {

      val roadwayChangeId = r.nextLong()
      val startDate = new DateTime(r.nextTimestamp())
      val acceptedDate = new DateTime(r.nextTimestamp())
      val change_type = r.nextLong()
      val reversed = r.nextLong()
      val old_road_number = r.nextLong()
      val old_road_part_number = r.nextLong()
      val old_TRACK = r.nextLong()
      val old_start_addr_m = r.nextLong()
      val old_end_addr_m = r.nextLong()
      val old_discontinuity = r.nextLong()
      val old_administrative_class = r.nextLong()
      val old_ely = r.nextLong()
      val new_road_number = r.nextLong()
      val new_road_part_number = r.nextLong()
      val new_TRACK = r.nextLong()
      val new_start_addr_m = r.nextLong()
      val new_end_addr_m = r.nextLong()
      val new_discontinuity = r.nextLong()
      val new_administrative_class = r.nextLong()
      val new_ely = r.nextLong()

      RoadwayChangesInfo(roadwayChangeId, startDate, acceptedDate, change_type, reversed,
        old_road_number, old_road_part_number, old_TRACK, old_start_addr_m, old_end_addr_m, old_discontinuity, old_administrative_class, old_ely,
        new_road_number, new_road_part_number, new_TRACK, new_start_addr_m, new_end_addr_m, new_discontinuity, new_administrative_class, new_ely)
    }
  }

  private implicit val getChangeInfoForRoadAddressChangesBrowser: GetResult[ChangeInfoForRoadAddressChangesBrowser] = new GetResult[ChangeInfoForRoadAddressChangesBrowser] {
    def apply(r: PositionedResult): ChangeInfoForRoadAddressChangesBrowser = {

      val startDate = new DateTime(r.nextTimestamp())
      val changeType = r.nextLong()
      val reversed = r.nextLong()
      val roadName = r.nextStringOption()
      val projectName = r.nextString()
      val projectAcceptedDate = new DateTime(r.nextTimestamp())
      val oldEly = r.nextLong()
      val oldRoadNumber = r.nextLongOption()
      val oldTrack = r.nextLongOption()
      val oldRoadPartNumber = r.nextLongOption()
      val oldStartAddrM = r.nextLongOption()
      val oldEndAddrM = r.nextLongOption()
      val oldLength = r.nextLongOption()
      val oldAdministrativeClass = r.nextLong()
      val newEly = r.nextLong()
      val newRoadNumber = r.nextLong()
      val newTrack = r.nextLong()
      val newRoadPartNumber = r.nextLong()
      val newStartAddrM = r.nextLong()
      val newEndAddrM = r.nextLong()
      val newLength = r.nextLong()
      val newAdministrativeClass = r.nextLong()

      val oldRoadAddress = OldRoadAddress(oldEly, oldRoadNumber, oldTrack, oldRoadPartNumber, oldStartAddrM, oldEndAddrM, oldLength, oldAdministrativeClass)
      val newRoadAddress = NewRoadAddress(newEly, newRoadNumber, newTrack, newRoadPartNumber, newStartAddrM, newEndAddrM, newLength, newAdministrativeClass)

      ChangeInfoForRoadAddressChangesBrowser(startDate, changeType, reversed, roadName, projectName: String, projectAcceptedDate, oldRoadAddress, newRoadAddress)
    }
  }

  def fetchChangeInfosForRoadAddressChangesBrowser(startDate: Option[String], endDate: Option[String], dateTarget: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[ChangeInfoForRoadAddressChangesBrowser] = {
    def withOptionalParameters(startDate: Option[String], endDate: Option[String], dateTarget: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long])(query: String): String  = {
      val targetDate = {
        if (dateTarget.isDefined) {
          if (dateTarget.get == "ProjectAcceptedDate")
            "p.accepted_date"
          else if (dateTarget.get == "RoadAddressStartDate")
            "p.start_date"
        }
      }

      val startDateCondition = targetDate + " >='" + startDate.get + "'"

      val endDateCondition = {
        if (endDate.nonEmpty)
          "AND " + targetDate + " <='" + endDate.get + "'"
        else
          ""
      }

      val elyCondition = {
        if (ely.nonEmpty)
          s" AND (rc.new_ely = ${ely.get} OR rc.old_ely = ${ely.get})"
        else
          ""
      }

      val roadNumberCondition = {
        if (roadNumber.nonEmpty)
          s" AND (rc.new_road_number = ${roadNumber.get} OR rc.old_road_number = ${roadNumber.get})"
        else
          ""
      }

      val roadPartCondition = {
        val parts = (minRoadPartNumber, maxRoadPartNumber)
        parts match {
          case (Some(minPart), Some(maxPart)) => s"AND (rc.new_road_part_number BETWEEN $minPart AND $maxPart OR rc.old_road_part_number BETWEEN $minPart AND $maxPart)"
          case (None, Some(maxPart)) => s"AND (rc.new_road_part_number <= $maxPart OR rc.old_road_part_number <= $maxPart)"
          case (Some(minPart), None) => s"AND (rc.new_road_part_number >= $minPart OR rc.old_road_part_number >= $minPart)"
          case _ => ""
        }
      }

      s"""$query
         |WHERE $startDateCondition $endDateCondition $elyCondition $roadNumberCondition $roadPartCondition
         |ORDER BY p.id, new_road_number, new_road_part_number, new_start_addr_m, new_track
         |""".stripMargin
    }

    def fetchChangeInfos(queryFilter: String => String): Seq[ChangeInfoForRoadAddressChangesBrowser] = {
      val query ="""SELECT
                   |	p.start_date,
                   |	change_type,
                   |	reversed,
                   |	rn.road_name,
                   |	p.name,
                   |	p.accepted_date,
                   |	old_ely,
                   |	old_road_number,
                   |	old_track,
                   |	old_road_part_number,
                   |	old_start_addr_m,
                   |	old_end_addr_m,
                   |	old_end_addr_m - old_start_addr_m,
                   |	old_administrative_class,
                   |	new_ely,
                   |	new_road_number,
                   |	new_track,
                   |	new_road_part_number,
                   |	new_start_addr_m,
                   |	new_end_addr_m,
                   |	new_end_addr_m - new_start_addr_m,
                   |	new_administrative_class
                   |FROM roadway_changes rc
                   |JOIN project p ON rc.project_id = p.id
                   |-- Get the valid road name for the road that was modified in the project, prioritizing the new road number
                   |LEFT JOIN road_name rn ON rn.road_number = coalesce(rc.new_road_number, rc.old_road_number)
                   |  AND rn.valid_to IS NULL
                   |  AND rn.start_date <= p.start_date
                   |  -- End date should be null if the change is not a termination (5).
                   |  -- If the road is terminated, the end date is the same as the end date of the road  (if the whole road was terminated in this project) or null if the start date of the road name start date is earlier than the start date of the project
                   |  AND ((rc.change_type != 5 and rn.end_date IS null) OR (rc.change_type = 5 and (rn.end_date = (p.start_date - INTERVAL '1 DAY') or (rn.end_date is null and rn.start_date < p.start_date))))
                   """.stripMargin
      val filteredQuery = queryFilter(query)
      Q.queryNA[ChangeInfoForRoadAddressChangesBrowser](filteredQuery).iterator.toSeq
    }

    fetchChangeInfos(withOptionalParameters(startDate, endDate, dateTarget, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber))
  }

}
