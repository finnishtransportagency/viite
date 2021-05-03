package fi.liikennevirasto.viite.dao

import java.sql.{PreparedStatement, Timestamp}

import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.asset.AdministrativeClass
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.Discontinuity.{Continuous, ParallelLink}
import fi.liikennevirasto.viite.process.ProjectDeltaCalculator.projectLinkDAO
import fi.liikennevirasto.viite.process.{Delta, ProjectDeltaCalculator, RoadwaySection}
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}


sealed trait AddressChangeType {
  def value: Int
}

object AddressChangeType {
  val values = Set(Unchanged, New, Transfer, ReNumeration, Termination)

  def apply(intValue: Int): AddressChangeType = {
    values.find(_.value == intValue).getOrElse(Unknown)
  }

  /*
      Unchanged is a no-operation, tells TR that some road part or section stays intact but it needs
        to be included in the message for other changes
      New is a road address placing to a road that did not have road address before
      Transfer is an adjustment of a road address, such as extending a road 100 meters from the start:
        all the addresses on the first part are transferred with +100 to each start and end address M values.
      ReNumeration is a change in road addressing but no physical or length changes. A road part gets a new
        road and/or road part number.
      Termination is for ending a road address (and possibly assigning the previously used road address
        to a new physical location at the same time)
   */

  case object NotHandled extends AddressChangeType { def value = 0 }
  case object Unchanged extends AddressChangeType { def value = 1 }
  case object New extends AddressChangeType { def value = 2 }
  case object Transfer extends AddressChangeType { def value = 3 }
  case object ReNumeration extends AddressChangeType { def value = 4 }
  case object Termination extends AddressChangeType { def value = 5 }
  case object Unknown extends AddressChangeType { def value = 99 }

}

case class RoadwayChangeSection(roadNumber: Option[Long], trackCode: Option[Long], startRoadPartNumber: Option[Long],
                                endRoadPartNumber: Option[Long], startAddressM: Option[Long], endAddressM: Option[Long], administrativeClass: Option[AdministrativeClass], discontinuity: Option[Discontinuity], ely: Option[Long])

case class RoadwayChangeSectionTR(roadNumber: Option[Long], trackCode: Option[Long], startRoadPartNumber: Option[Long],
                                  endRoadPartNumber: Option[Long], startAddressM: Option[Long], endAddressM: Option[Long])

case class RoadwayChangeInfo(changeType: AddressChangeType, source: RoadwayChangeSection, target: RoadwayChangeSection,
                             discontinuity: Discontinuity, administrativeClass: AdministrativeClass, reversed: Boolean, orderInChangeTable: Long, ely: Long = -1L)

case class ProjectRoadwayChange(projectId: Long, projectName: Option[String], ely: Long, user: String, changeDate: DateTime,
                                changeInfo: RoadwayChangeInfo, projectStartDate: DateTime, rotatingTRId: Option[Long])

case class ChangeRow(projectId: Long, projectName: Option[String], createdBy: String, createdDate: Option[DateTime], startDate: Option[DateTime], modifiedBy: String, modifiedDate: Option[DateTime], targetEly: Long, changeType: Int, sourceRoadNumber: Option[Long], sourceTrackCode: Option[Long], sourceStartRoadPartNumber: Option[Long], sourceEndRoadPartNumber: Option[Long], sourceStartAddressM: Option[Long], sourceEndAddressM: Option[Long], targetRoadNumber: Option[Long], targetTrackCode: Option[Long], targetStartRoadPartNumber: Option[Long], targetEndRoadPartNumber: Option[Long], targetStartAddressM: Option[Long], targetEndAddressM: Option[Long], targetDiscontinuity: Option[Int], targetAdministrativeClass: Option[Int], sourceAdministrativeClass: Option[Int], sourceDiscontinuity: Option[Int], sourceEly: Option[Long], rotatingTRId: Option[Long], reversed: Boolean, orderInTable: Long)

case class ChangeTableRows(adjustedSections: Iterable[((RoadwaySection, RoadwaySection), Option[String])], originalSections: Iterable[(RoadwaySection, RoadwaySection)])

case class RoadwayChangesInfo(roadwayChangeId: Long, startDate: DateTime, validFrom: DateTime, change_type: Long, reversed: Long,
                              old_road_number: Long, old_road_part_number: Long, old_TRACK: Long, old_start_addr_m: Long, old_end_addr_m: Long, old_discontinuity: Long, old_administrative_class: Long, old_ely: Long,
                              new_road_number: Long, new_road_part_number: Long, new_TRACK: Long, new_start_addr_m: Long, new_end_addr_m: Long, new_discontinuity: Long, new_administrative_class: Long, new_ely: Long)

class RoadwayChangesDAO {
  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()
  val projectDAO = new ProjectDAO
  implicit val getDiscontinuity = GetResult[Discontinuity](r => Discontinuity.apply(r.nextInt()))

  implicit val getAddressChangeType = GetResult[AddressChangeType](r => AddressChangeType.apply(r.nextInt()))

  implicit val getAdministrativeClass = GetResult[AdministrativeClass](r => AdministrativeClass.apply(r.nextInt()))

  implicit val getRoadwayChangeRow = new GetResult[ChangeRow] {
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
      val rotatingTRIdr = r.nextLongOption
      val reversed = r.nextBoolean
      val orderInTable = r.nextLong

      ChangeRow(projectId, projectName: Option[String], createdBy: String, createdDate: Option[DateTime], startDate: Option[DateTime], modifiedBy: String, modifiedDate: Option[DateTime], targetEly: Long, changeType: Int, sourceRoadNumber: Option[Long], sourceTrackCode: Option[Long], sourceStartRoadPartNumber: Option[Long], sourceEndRoadPartNumber: Option[Long], sourceStartAddressM: Option[Long], sourceEndAddressM: Option[Long], targetRoadNumber: Option[Long], targetTrackCode: Option[Long], targetStartRoadPartNumber: Option[Long], targetEndRoadPartNumber: Option[Long], targetStartAddressM: Option[Long], targetEndAddressM: Option[Long], targetDiscontinuity: Option[Int], targetAdministrativeClass: Option[Int], sourceAdministrativeClass: Option[Int], sourceDiscontinuity: Option[Int], sourceEly: Option[Long], rotatingTRIdr: Option[Long], reversed: Boolean, orderInTable: Long)
    }
  }

  val logger = LoggerFactory.getLogger(getClass)

  private def toRoadwayChangeRecipient(row: ChangeRow) = {
    RoadwayChangeSection(row.targetRoadNumber, row.targetTrackCode, row.targetStartRoadPartNumber, row.targetEndRoadPartNumber, row.targetStartAddressM, row.targetEndAddressM,
      Some(AdministrativeClass.apply(row.targetAdministrativeClass.getOrElse(AdministrativeClass("Unknown").value))), Some(Discontinuity.apply(row.targetDiscontinuity.getOrElse(Discontinuity.Continuous.value))), Some(row.targetEly))
  }

  private def toRoadwayChangeSource(row: ChangeRow) = {
    RoadwayChangeSection(row.sourceRoadNumber, row.sourceTrackCode, row.sourceStartRoadPartNumber, row.sourceEndRoadPartNumber, row.sourceStartAddressM, row.sourceEndAddressM,
      Some(AdministrativeClass.apply(row.sourceAdministrativeClass.getOrElse(AdministrativeClass("Unknown").value))), Some(Discontinuity.apply(row.sourceDiscontinuity.getOrElse(Discontinuity.Continuous.value))), row.sourceEly)
  }

  private def toRoadwayChangeInfo(row: ChangeRow) = {
    val source = toRoadwayChangeSource(row)
    val target = toRoadwayChangeRecipient(row)
    RoadwayChangeInfo(AddressChangeType.apply(row.changeType), source, target,
      replaceParallelLink(Discontinuity.apply(row.targetDiscontinuity.getOrElse(Discontinuity.Continuous.value))),
      AdministrativeClass.apply(row.targetAdministrativeClass.getOrElse(AdministrativeClass("Unknown").value)),
      row.reversed,
      row.orderInTable,
      target.ely.getOrElse(source.ely.get))
  }

  private def replaceParallelLink(currentDiscontinuity: Discontinuity): Discontinuity = {
    if (currentDiscontinuity == ParallelLink)
      Continuous
    else currentDiscontinuity
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
      ProjectRoadwayChange(row.projectId, row.projectName, row.targetEly, user, date, changeInfo, row.startDate.get,
        row.rotatingTRId)
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
                rac.old_discontinuity, rac.old_ely, p.tr_id, rac.reversed, rac.ROADWAY_CHANGE_ID
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

  def insertDeltaToRoadChangeTable(delta: Delta, projectId: Long, project: Option[Project]): (Boolean, Option[String]) = {
    def addToBatch(roadwaySection: RoadwaySection, addressChangeType: AddressChangeType,
                   roadwayChangePS: PreparedStatement, roadWayChangesLinkPS: PreparedStatement): Unit = {
      val nextChangeOrderLink = Sequences.nextRoadwayChangeLink
      addressChangeType match {
        case AddressChangeType.New =>
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
        case AddressChangeType.Termination =>
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
                                addressChangeType: AddressChangeType, roadwayChangePS: PreparedStatement, roadWayChangesLinkPS: PreparedStatement): Unit = {
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

          val allNonTerminatedProjectLinks = projectLinkDAO.fetchProjectLinks(project.id)//.filter(_.status != LinkStatus.Terminated)

          val terminated = ProjectDeltaCalculator.partition(delta.terminations.mapping)

          terminated.originalSections.foreach(roadwaySection =>
            addToBatch(roadwaySection._1, AddressChangeType.Termination, roadwayChangePS, roadWayChangesLinkPS)
          )

          val news = ProjectDeltaCalculator.partition(delta.newRoads)
          news.foreach(roadwaySection => addToBatch(roadwaySection, AddressChangeType.New, roadwayChangePS, roadWayChangesLinkPS))

          val unchanged = ProjectDeltaCalculator.partition(delta.unChanged.mapping, allNonTerminatedProjectLinks)

          val transferred = ProjectDeltaCalculator.partition(delta.transferred.mapping, allNonTerminatedProjectLinks)

          val numbering = ProjectDeltaCalculator.partition(delta.numbering.mapping, allNonTerminatedProjectLinks)

//          val twoTrackOldAddressRoadParts = ProjectDeltaCalculator.buildTwoTrackOldAddressRoadParts(unchanged, transferred, numbering, terminated)
//          val old_road_two_track_parts = ProjectDeltaCalculator.calc_parts(twoTrackOldAddressRoadParts)
//
//          val twoTrackAdjustedTerminated = old_road_two_track_parts.flatMap(_._1) ++ old_road_two_track_parts.flatMap(_._2)
//          val combinedTerminatedTrack = terminated.originalSections.map(_._1).filter(_.track == Track.Combined)

//          1. vie ylijäävät transferred bztachiin 2. Etsi kadonnut lakkautettu rivi alusta
//           -- news.foreach(roadwaySection => addToBatch(roadwaySection, AddressChangeType.New, roadwayChangePS, roadWayChangesLinkPS))

//          val adjustedTerminated = combinedTerminatedTrack ++ twoTrackAdjustedTerminated
          var adjustedUnchanged = ProjectDeltaCalculator.adjustStartSourceAddressValues(unchanged.adjustedSections, unchanged.originalSections ++ transferred.originalSections ++ numbering.originalSections)
          var adjustedTransferred = ProjectDeltaCalculator.adjustStartSourceAddressValues(transferred.adjustedSections, unchanged.originalSections ++ transferred.originalSections ++ numbering.originalSections ++ terminated.originalSections)
          var adjustedNumbering = ProjectDeltaCalculator.adjustStartSourceAddressValues(numbering.adjustedSections, unchanged.originalSections ++ transferred.originalSections ++ numbering.originalSections)

          /* Filter off zero lengths from change table if splits and roundings produced such. */
//           adjustedUnchanged = (adjustedUnchanged._1.filterNot(addr => addr._1.startMAddr == addr._1.endMAddr), adjustedUnchanged._2)
//           adjustedTransferred = (adjustedTransferred._1.filterNot(addr => addr._1.startMAddr == addr._1.endMAddr), adjustedUnchanged._2)
//           adjustedNumbering = (adjustedNumbering._1.filterNot(addr => addr._1.startMAddr == addr._1.endMAddr), adjustedUnchanged._2)

//          adjustedTerminated.foreach(roadwaySection =>
//            addToBatch(roadwaySection, AddressChangeType.Termination, roadwayChangePS, roadWayChangesLinkPS)
//          )
            adjustedUnchanged._1.foreach { case (roadwaySection1, roadwaySection2) =>
            addToBatchWithOldValues(roadwaySection1, roadwaySection2, AddressChangeType.Unchanged, roadwayChangePS, roadWayChangesLinkPS)
          }
          adjustedTransferred._1.foreach { case (roadwaySection1, roadwaySection2) =>
            addToBatchWithOldValues(roadwaySection1, roadwaySection2, AddressChangeType.Transfer, roadwayChangePS, roadWayChangesLinkPS)
          }
          adjustedNumbering._1.foreach { case (roadwaySection1, roadwaySection2) =>
            addToBatchWithOldValues(roadwaySection1, roadwaySection2, AddressChangeType.ReNumeration, roadwayChangePS, roadWayChangesLinkPS)
          }


          roadwayChangePS.executeBatch()
          roadwayChangePS.close()
          roadWayChangesLinkPS.executeBatch()
          roadWayChangesLinkPS.close()
          val endTime = System.currentTimeMillis()
          logger.info("Delta insertion in ChangeTable completed in %d ms".format(endTime - startTime))
          val warning = (adjustedUnchanged._2 ++ adjustedTransferred._2 ++ adjustedNumbering._2).toSeq
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
  def fetchRoadwayChangesInfo(startValidFromDate: DateTime, endValidFromDate: Option[DateTime]): Seq[RoadwayChangesInfo] = {
    val untilString = if (endValidFromDate.nonEmpty) s"AND R.VALID_FROM <= to_timestamp('${new Timestamp(endValidFromDate.get.getMillis)}', 'YYYY-MM-DD HH24:MI:SS.FF')" else s""
    val query =
      s"""
WITH ROADWAYS AS (
SELECT R.ROAD_NUMBER ,R.ROAD_PART_NUMBER ,
NULLIF(MAX(COALESCE(END_DATE, TO_DATE('9999', 'yyyy'))),TO_DATE('9999', 'yyyy')) AS END_DATE,
MAX(VALID_FROM) AS VALID_FROM
   FROM ROADWAY R
        WHERE R.VALID_FROM >= to_timestamp('${new Timestamp(startValidFromDate.getMillis)}', 'YYYY-MM-DD HH24:MI:SS.FF')
        $untilString
        AND R.VALID_TO IS NULL
        GROUP BY R.ROAD_NUMBER ,R.ROAD_PART_NUMBER
)
SELECT
      RC.ROADWAY_CHANGE_ID
    , P.START_DATE
    , R.VALID_FROM
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
      INNER JOIN ROADWAYS R
        ON ((R.ROAD_NUMBER = RC.NEW_ROAD_NUMBER
             AND R.ROAD_PART_NUMBER = RC.NEW_ROAD_PART_NUMBER) OR
            (R.ROAD_NUMBER = RC.OLD_ROAD_NUMBER
             AND R.ROAD_PART_NUMBER = RC.OLD_ROAD_PART_NUMBER)
            )
      INNER JOIN PROJECT P
        ON P.ID = RC.PROJECT_ID
        ORDER BY R.VALID_FROM, RC.ROADWAY_CHANGE_ID
     """

    Q.queryNA[RoadwayChangesInfo](query).iterator.toSeq
  }

  private implicit val getRoadwayChangesInfo: GetResult[RoadwayChangesInfo] = new GetResult[RoadwayChangesInfo] {
    def apply(r: PositionedResult) = {

      val roadwayChangeId = r.nextLong()
      val startDate = new DateTime(r.nextTimestamp())
      val validFrom = new DateTime(r.nextTimestamp())
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

      RoadwayChangesInfo(roadwayChangeId, startDate, validFrom, change_type, reversed,
        old_road_number, old_road_part_number, old_TRACK, old_start_addr_m, old_end_addr_m, old_discontinuity, old_administrative_class, old_ely,
        new_road_number, new_road_part_number, new_TRACK, new_start_addr_m, new_end_addr_m, new_discontinuity, new_administrative_class, new_ely)
    }
  }

}
