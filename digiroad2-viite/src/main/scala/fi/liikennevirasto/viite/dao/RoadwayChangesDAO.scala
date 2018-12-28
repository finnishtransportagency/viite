package fi.liikennevirasto.viite.dao

import java.sql.PreparedStatement

import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.Discontinuity.{ChangingELYCode, Discontinuous, MinorDiscontinuity}
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
                                endRoadPartNumber: Option[Long], startAddressM: Option[Long], endAddressM: Option[Long], roadType: Option[RoadType], discontinuity: Option[Discontinuity], ely: Option[Long])

case class RoadwayChangeSectionTR(roadNumber: Option[Long], trackCode: Option[Long], startRoadPartNumber: Option[Long],
                                  endRoadPartNumber: Option[Long], startAddressM: Option[Long], endAddressM: Option[Long])

case class RoadwayChangeInfo(changeType: AddressChangeType, source: RoadwayChangeSection, target: RoadwayChangeSection,
                             discontinuity: Discontinuity, roadType: RoadType, reversed: Boolean, orderInChangeTable: Long)

case class ProjectRoadwayChange(projectId: Long, projectName: Option[String], ely: Long, user: String, changeDate: DateTime,
                                changeInfo: RoadwayChangeInfo, projectStartDate: DateTime, rotatingTRId: Option[Long])

case class ChangeRow(projectId: Long, projectName: Option[String], createdBy: String, createdDate: Option[DateTime],
                     startDate: Option[DateTime], modifiedBy: String, modifiedDate: Option[DateTime], targetEly: Long,
                     changeType: Int, sourceRoadNumber: Option[Long], sourceTrackCode: Option[Long],
                     sourceStartRoadPartNumber: Option[Long], sourceEndRoadPartNumber: Option[Long],
                     sourceStartAddressM: Option[Long], sourceEndAddressM: Option[Long], targetRoadNumber: Option[Long],
                     targetTrackCode: Option[Long], targetStartRoadPartNumber: Option[Long], targetEndRoadPartNumber: Option[Long],
                     targetStartAddressM: Option[Long], targetEndAddressM: Option[Long], targetDiscontinuity: Option[Int], targetRoadType: Option[Int],
                     sourceRoadType: Option[Int], sourceDiscontinuity: Option[Int], sourceEly: Option[Long],
                     rotatingTRId: Option[Long], reversed: Boolean, orderInTable: Long)

class RoadwayChangesDAO {
  val formatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()
  val projectDAO = new ProjectDAO
  implicit val getDiscontinuity = GetResult[Discontinuity](r => Discontinuity.apply(r.nextInt()))

  implicit val getAddressChangeType = GetResult[AddressChangeType](r => AddressChangeType.apply(r.nextInt()))

  implicit val getRoadType = GetResult[RoadType](r => RoadType.apply(r.nextInt()))

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
      val targetRoadType = r.nextIntOption
      val sourceRoadType = r.nextIntOption
      val sourceDiscontinuity = r.nextIntOption
      val sourceEly = r.nextLongOption
      val rotatingTRIdr = r.nextLongOption
      val reversed = r.nextBoolean
      val orderInTable = r.nextLong

      ChangeRow(projectId, projectName: Option[String], createdBy: String, createdDate: Option[DateTime], startDate: Option[DateTime], modifiedBy: String, modifiedDate: Option[DateTime], targetEly: Long, changeType: Int, sourceRoadNumber: Option[Long],
        sourceTrackCode: Option[Long], sourceStartRoadPartNumber: Option[Long], sourceEndRoadPartNumber: Option[Long], sourceStartAddressM: Option[Long], sourceEndAddressM: Option[Long],
        targetRoadNumber: Option[Long], targetTrackCode: Option[Long], targetStartRoadPartNumber: Option[Long], targetEndRoadPartNumber: Option[Long], targetStartAddressM: Option[Long],
        targetEndAddressM: Option[Long], targetDiscontinuity: Option[Int], targetRoadType: Option[Int], sourceRoadType: Option[Int], sourceDiscontinuity: Option[Int], sourceEly: Option[Long],
        rotatingTRIdr: Option[Long], reversed: Boolean, orderInTable: Long)
    }
  }

  val logger = LoggerFactory.getLogger(getClass)

  private def toRoadwayChangeRecipient(row: ChangeRow) = {
    RoadwayChangeSection(row.targetRoadNumber, row.targetTrackCode, row.targetStartRoadPartNumber, row.targetEndRoadPartNumber, row.targetStartAddressM, row.targetEndAddressM,
      Some(RoadType.apply(row.targetRoadType.getOrElse(RoadType.Unknown.value))), Some(Discontinuity.apply(row.targetDiscontinuity.getOrElse(Discontinuity.Continuous.value))), Some(row.targetEly))
  }

  private def toRoadwayChangeSource(row: ChangeRow) = {
    RoadwayChangeSection(row.sourceRoadNumber, row.sourceTrackCode, row.sourceStartRoadPartNumber, row.sourceEndRoadPartNumber, row.sourceStartAddressM, row.sourceEndAddressM,
      Some(RoadType.apply(row.sourceRoadType.getOrElse(RoadType.Unknown.value))), Some(Discontinuity.apply(row.sourceDiscontinuity.getOrElse(Discontinuity.Continuous.value))), row.sourceEly)
  }

  private def toRoadwayChangeInfo(row: ChangeRow) = {
    val source = toRoadwayChangeSource(row)
    val target = toRoadwayChangeRecipient(row)
    RoadwayChangeInfo(AddressChangeType.apply(row.changeType), source, target, Discontinuity.apply(row.targetDiscontinuity.getOrElse(Discontinuity.Continuous.value)), RoadType.apply(row.targetRoadType.getOrElse(RoadType.Unknown.value)), row.reversed, row.orderInTable)
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
    mapper(mergeChangeRows(Q.queryNA[ChangeRow](query).list))
  }

  /**
    * Merge all the change rows by source and target road number, road part number, road type, ely, change type and reversed.
    * Then if the end address of the previous row is equal to the start of the next one and the dicontinuity is equal the merge is performed.
    *
    * @param resultList
    * @return
    */
  private def mergeChangeRows(resultList: List[ChangeRow]): List[ChangeRow] = {
    def combine(resultList: Seq[ChangeRow], nextRow: ChangeRow): Seq[ChangeRow] = {
      val previousRow = resultList.last
      if (previousRow.sourceEndAddressM == nextRow.sourceStartAddressM && previousRow.targetEndAddressM == nextRow.targetStartAddressM && checkContinuityMergingRows(previousRow, nextRow)){
        resultList.dropRight(1) ++ Seq(previousRow.copy(sourceEndAddressM = nextRow.sourceEndAddressM, targetEndAddressM = nextRow.targetEndAddressM, sourceDiscontinuity = nextRow.sourceDiscontinuity, targetDiscontinuity = nextRow.targetDiscontinuity))
      }
      else
        resultList ++ Seq(nextRow)
    }

    def combineReversed(resultList: Seq[ChangeRow], nextRow: ChangeRow): Seq[ChangeRow] = {
      val previousRow = resultList.last
      if (nextRow.sourceEndAddressM == previousRow.sourceStartAddressM && nextRow.targetStartAddressM == previousRow.targetEndAddressM && checkContinuityMergingRows(previousRow, nextRow)){
        resultList.dropRight(1) ++ Seq(previousRow.copy(sourceStartAddressM = nextRow.sourceStartAddressM, targetEndAddressM = nextRow.targetEndAddressM, sourceDiscontinuity = nextRow.sourceDiscontinuity, targetDiscontinuity = nextRow.targetDiscontinuity))
      }
      else
        resultList ++ Seq(nextRow)
    }

    def checkContinuityMergingRows(previousRow: ChangeRow, nextRow: ChangeRow): Boolean = {
      // Checking sourceDiscontinuity
      (((previousRow.sourceDiscontinuity == nextRow.sourceDiscontinuity || previousRow.sourceDiscontinuity.isEmpty) && previousRow.sourceDiscontinuity.contains(Discontinuity.Continuous.value))
        || (previousRow.sourceDiscontinuity.contains(Discontinuity.Continuous.value) && !nextRow.sourceDiscontinuity.contains(Discontinuity.Continuous.value))) &&
      // Checking targetDiscontinuity
      (((previousRow.targetDiscontinuity == nextRow.targetDiscontinuity || previousRow.targetDiscontinuity.isEmpty) && previousRow.targetDiscontinuity.contains(Discontinuity.Continuous.value))
        || (previousRow.targetDiscontinuity.contains(Discontinuity.Continuous.value) && !nextRow.targetDiscontinuity.contains(Discontinuity.Continuous.value)))

    }

    resultList.groupBy(r =>
      (
        r.changeType, r.reversed,
        r.sourceRoadNumber, r.sourceTrackCode, r.sourceStartRoadPartNumber, r.sourceEndRoadPartNumber, r.sourceRoadType, r.sourceEly,
        r.targetRoadNumber, r.targetTrackCode, r.targetStartRoadPartNumber, r.targetEndRoadPartNumber, r.targetRoadType, r.targetEly
      )
    ).flatMap { case (_, changeRows) =>
      changeRows.sortBy(_.targetStartAddressM).foldLeft(Seq[ChangeRow]()) {
        case (result, nextChangeRow) =>
          if (result.isEmpty) Seq(nextChangeRow)
          else if(nextChangeRow.reversed) combineReversed(result, nextChangeRow)
          else combine(result, nextChangeRow)
      }
    }.toList.sortBy(r => (r.targetRoadNumber, r.targetStartRoadPartNumber, r.targetStartAddressM, r.targetTrackCode))
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
                rac.new_start_addr_m, rac.new_end_addr_m, rac.new_discontinuity, rac.new_road_type, rac.old_road_type,
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

  def insertDeltaToRoadChangeTable(delta: Delta, projectId: Long): Boolean = {
    def addToBatch(roadwaySection: RoadwaySection, ely: Long, addressChangeType: AddressChangeType,
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
      roadwayChangePS.setLong(14, roadwaySection.roadType.value)
      roadwayChangePS.setLong(15, ely)
      roadwayChangePS.setLong(16, roadwaySection.roadType.value)
      roadwayChangePS.setLong(17, roadwaySection.discontinuity.value)
      roadwayChangePS.setLong(18, ely)
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
                                ely: Long, addressChangeType: AddressChangeType, roadwayChangePS: PreparedStatement, roadWayChangesLinkPS: PreparedStatement): Unit = {
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
      roadwayChangePS.setLong(14, newRoadwaySection.roadType.value)
      roadwayChangePS.setLong(15, ely)
      roadwayChangePS.setLong(16, oldRoadwaySection.roadType.value)
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
    projectDAO.fetchById(projectId) match {
      case Some(project) =>
        project.ely match {
          case Some(ely) =>
            val roadwayChangePS = dynamicSession.prepareStatement("INSERT INTO ROADWAY_CHANGES " +
              "(project_id, change_type,old_road_number,new_road_number,old_road_part_number,new_road_part_number, " +
              "old_TRACK,new_TRACK,old_start_addr_m,new_start_addr_m,old_end_addr_m,new_end_addr_m," +
              "new_discontinuity,new_road_type,new_ely, old_road_type, old_discontinuity, old_ely, reversed, roadway_change_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")

            val roadWayChangesLinkPS = dynamicSession.prepareStatement("INSERT INTO ROADWAY_CHANGES_LINK " +
              "(roadway_change_id, project_id, project_link_id) values (?,?,?)")

            val terminated = ProjectDeltaCalculator.partition(delta.terminations)
            terminated.foreach(roadwaySection =>
              addToBatch(roadwaySection, ely, AddressChangeType.Termination, roadwayChangePS, roadWayChangesLinkPS)
            )

            val news = ProjectDeltaCalculator.partition(delta.newRoads)
            news.foreach(roadwaySection => addToBatch(roadwaySection, ely, AddressChangeType.New, roadwayChangePS, roadWayChangesLinkPS))

            ProjectDeltaCalculator.partition(delta.unChanged.mapping).foreach { case (roadwaySection1, roadwaySection2) =>
              addToBatchWithOldValues(roadwaySection1, roadwaySection2, ely, AddressChangeType.Unchanged, roadwayChangePS, roadWayChangesLinkPS)
            }

            ProjectDeltaCalculator.partition(delta.transferred.mapping, terminated ++ news).foreach { case (roadwaySection1, roadwaySection2) =>
              addToBatchWithOldValues(roadwaySection1, roadwaySection2, ely, AddressChangeType.Transfer, roadwayChangePS, roadWayChangesLinkPS)
            }

            ProjectDeltaCalculator.partition(delta.numbering.mapping).foreach { case (roadwaySection1, roadwaySection2) =>
              addToBatchWithOldValues(roadwaySection1, roadwaySection2, ely, AddressChangeType.ReNumeration, roadwayChangePS, roadWayChangesLinkPS)
            }

            roadwayChangePS.executeBatch()
            roadwayChangePS.close()
            roadWayChangesLinkPS.executeBatch()
            roadWayChangesLinkPS.close()
            val endTime = System.currentTimeMillis()
            logger.info("Delta insertion in ChangeTable completed in %d ms".format(endTime - startTime))
            true
          case _ => false
        }
      case _ => false
    }
  }

  def fetchRoadwayChanges(projectIds: Set[Long]): List[ProjectRoadwayChange] = {
    fetchRoadwayChanges(projectIds, queryList)
  }

  def fetchRoadwayChangesResume(projectIds: Set[Long]): List[ProjectRoadwayChange] = {
    fetchRoadwayChanges(projectIds, queryResumeList)
  }
}
