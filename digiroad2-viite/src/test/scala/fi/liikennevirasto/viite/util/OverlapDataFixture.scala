package fi.liikennevirasto.viite.util

import com.github.tototoshi.slick.MySQLJodaSupport._
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.oracle.MassQuery
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import org.joda.time.format.ISODateTimeFormat
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc._
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class OverlapRoadAddress(id: Long, roadNumber: Long, roadPartNumber: Long, trackCode: Long, startAddrM: Long, endAddrM: Long, linkId: Long,
                              startM: Double, endM: Double, startDate: Option[DateTime], endDate: Option[DateTime],
                              validFrom: Option[DateTime], validTo: Option[DateTime])

class OverlapDataFixture(val vvhClient: VVHClient) {

  val logger = LoggerFactory.getLogger(getClass)

  private def fetchAllWithPartialOverlapRoadAddresses(): Seq[OverlapRoadAddress] = {
    sql"""
      SELECT
        RA.ID, RA.ROAD_NUMBER, RA.ROAD_PART_NUMBER, RA.TRACK_CODE, RA.START_ADDR_M, RA.END_ADDR_M, RA.LINK_ID, RA.START_MEASURE, RA.END_MEASURE, RA.START_DATE, RA.END_DATE, RA.VALID_FROM, RA.VALID_TO
      FROM
        ROAD_ADDRESS RA
      INNER JOIN (
        SELECT
          IRA.LINK_ID, IRA.START_MEASURE, IRA.ROAD_NUMBER, IRA.ROAD_PART_NUMBER, IRA.START_DATE, IRA.END_DATE
        FROM
          ROAD_ADDRESS IRA
        WHERE
          IRA.VALID_TO IS NULL
        GROUP BY
          IRA.LINK_ID, IRA.START_MEASURE, IRA.ROAD_NUMBER, IRA.ROAD_PART_NUMBER, IRA.START_DATE, IRA.END_DATE
        HAVING
          COUNT(*) > 1 ) OM
        ON
          OM.LINK_ID = RA.LINK_ID
          AND OM.START_MEASURE = RA.START_MEASURE
          AND OM.ROAD_NUMBER = RA.ROAD_NUMBER
          AND OM.ROAD_PART_NUMBER = RA.ROAD_PART_NUMBER
          AND ( OM.START_DATE = RA.START_DATE
          OR ( OM.START_DATE IS NULL
          AND RA.START_DATE IS NULL ))
          AND ( OM.END_DATE = RA.END_DATE
          OR ( OM.END_DATE IS NULL
          AND RA.END_DATE IS NULL ))
      WHERE RA.VALID_TO IS NULL
      UNION
      SELECT
        RA.ID, RA.ROAD_NUMBER, RA.ROAD_PART_NUMBER, RA.TRACK_CODE, RA.START_ADDR_M, RA.END_ADDR_M, RA.LINK_ID, RA.START_MEASURE, RA.END_MEASURE, RA.START_DATE, RA.END_DATE, RA.VALID_FROM, RA.VALID_TO
      FROM
        ROAD_ADDRESS RA
      INNER JOIN (
        SELECT
          IRA.LINK_ID, IRA.END_MEASURE, IRA.ROAD_NUMBER, IRA.ROAD_PART_NUMBER, IRA.START_DATE, IRA.END_DATE
        FROM
          ROAD_ADDRESS IRA
        WHERE
          IRA.VALID_TO IS NULL
        GROUP BY
          IRA.LINK_ID, IRA.END_MEASURE, IRA.ROAD_NUMBER, IRA.ROAD_PART_NUMBER, IRA.START_DATE, IRA.END_DATE
        HAVING
          COUNT(*) > 1 ) OM
        ON
          OM.LINK_ID = RA.LINK_ID
          AND OM.END_MEASURE = RA.END_MEASURE
          AND OM.ROAD_NUMBER = RA.ROAD_NUMBER
          AND OM.ROAD_PART_NUMBER = RA.ROAD_PART_NUMBER
          AND ( OM.START_DATE = RA.START_DATE
          OR ( OM.START_DATE IS NULL
          AND RA.START_DATE IS NULL ))
          AND ( OM.END_DATE = RA.END_DATE
          OR ( OM.END_DATE IS NULL
          AND RA.END_DATE IS NULL ))
        WHERE RA.VALID_TO IS NULL
      """.as[OverlapRoadAddress].list
  }

  private def fetchAllOverlapRoadAddresses(): Seq[OverlapRoadAddress] = {
    sql"""
         SELECT RA.ID, RA.ROAD_NUMBER, RA.ROAD_PART_NUMBER, RA.TRACK_CODE, RA.START_ADDR_M, RA.END_ADDR_M, RA.LINK_ID, RA.START_MEASURE, RA.END_MEASURE, RA.START_DATE, RA.END_DATE, RA.VALID_FROM, RA.VALID_TO
         FROM ROAD_ADDRESS RA
         	INNER JOIN (SELECT IRA.LINK_ID, IRA.START_MEASURE, IRA.ROAD_NUMBER, IRA.ROAD_PART_NUMBER, IRA.END_MEASURE, IRA.START_DATE, IRA.END_DATE
               FROM ROAD_ADDRESS IRA
             	 WHERE IRA.VALID_TO IS NULL GROUP BY IRA.LINK_ID, IRA.START_MEASURE, IRA.END_MEASURE, IRA.ROAD_NUMBER, IRA.ROAD_PART_NUMBER, IRA.START_DATE, IRA.END_DATE HAVING count(*) > 1) OM
          	ON OM.LINK_ID = RA.LINK_ID AND OM.START_MEASURE = RA.START_MEASURE AND OM.END_MEASURE = RA.END_MEASURE AND OM.ROAD_NUMBER = RA.ROAD_NUMBER AND OM.ROAD_PART_NUMBER = RA.ROAD_PART_NUMBER
          		AND (OM.START_DATE = RA.START_DATE OR (OM.START_DATE IS NULL AND RA.START_DATE IS NULL)) AND (OM.END_DATE = RA.END_DATE OR (OM.END_DATE IS NULL AND RA.END_DATE IS NULL))
          WHERE RA.VALID_TO IS NULL
    """.as[OverlapRoadAddress].list
  }

  private def fetchAllExpiredRoadAddresses(linkIds: Set[Long]): List[OverlapRoadAddress] = {
    MassQuery.withIds(linkIds) {
      idTableName =>
      Q.queryNA[OverlapRoadAddress](s"""
           SELECT RA.ID, RA.ROAD_NUMBER, RA.ROAD_PART_NUMBER, RA.TRACK_CODE, RA.START_ADDR_M, RA.END_ADDR_M, RA.LINK_ID, RA.START_MEASURE, RA.END_MEASURE, RA.START_DATE, RA.END_DATE, RA.VALID_FROM, RA.VALID_TO
           FROM ROAD_ADDRESS RA
           INNER JOIN $idTableName TMP ON TMP.ID = RA.LINK_ID
           WHERE RA.VALID_TO IS NOT NULL
      """).list
    }
  }

  val formatter = ISODateTimeFormat.dateOptionalTimeParser()


  private def fetchExpiredByValidDate(roadNumber: Long, roadPartNumber: Long, endDate: Option[DateTime], validDate: DateTime): List[OverlapRoadAddress] = {
    val plusOneSecond = validDate.plusSeconds(1)
    val minusOneSecond = validDate.minusSeconds(1)
    if(endDate.isEmpty)
      sql"""
           SELECT RA.ID, RA.ROAD_NUMBER, RA.ROAD_PART_NUMBER, RA.TRACK_CODE, RA.START_ADDR_M, RA.END_ADDR_M, RA.LINK_ID, RA.START_MEASURE, RA.END_MEASURE, RA.START_DATE, RA.END_DATE, RA.VALID_FROM, RA.VALID_TO
           FROM ROAD_ADDRESS RA
           WHERE RA.ROAD_NUMBER = $roadNumber AND RA.ROAD_PART_NUMBER = $roadPartNumber AND RA.END_DATE IS NULL AND RA.VALID_TO >= $minusOneSecond AND RA.VALID_TO <= $plusOneSecond AND
           RA.VALID_FROM <= $minusOneSecond
      """.as[OverlapRoadAddress].list
    else
      sql"""
           SELECT RA.ID, RA.ROAD_NUMBER, RA.ROAD_PART_NUMBER, RA.TRACK_CODE, RA.START_ADDR_M, RA.END_ADDR_M, RA.LINK_ID, RA.START_MEASURE, RA.END_MEASURE, RA.START_DATE, RA.END_DATE, RA.VALID_FROM, RA.VALID_TO
           FROM ROAD_ADDRESS RA
           WHERE RA.ROAD_NUMBER = $roadNumber AND RA.ROAD_PART_NUMBER = $roadPartNumber AND RA.END_DATE = ${endDate.get} AND RA.VALID_TO >= $minusOneSecond AND RA.VALID_TO <= $plusOneSecond AND
           RA.VALID_FROM <= $minusOneSecond
      """.as[OverlapRoadAddress].list
  }

  private def fetchAllValidRoadAddressSection(roadNumber: Long, roadPartNumber: Long, endDate: Option[DateTime], validDate: DateTime): List[OverlapRoadAddress] = {
    if(endDate.isEmpty)
      sql"""
           SELECT RA.ID, RA.ROAD_NUMBER, RA.ROAD_PART_NUMBER, RA.TRACK_CODE, RA.START_ADDR_M, RA.END_ADDR_M, RA.LINK_ID, RA.START_MEASURE, RA.END_MEASURE, RA.START_DATE, RA.END_DATE, RA.VALID_FROM, RA.VALID_TO
           FROM ROAD_ADDRESS RA
           WHERE RA.ROAD_NUMBER = $roadNumber AND RA.ROAD_PART_NUMBER = $roadPartNumber AND RA.END_DATE IS NULL AND  RA.VALID_TO IS NULL
      """.as[OverlapRoadAddress].list
    else
      sql"""
           SELECT RA.ID, RA.ROAD_NUMBER, RA.ROAD_PART_NUMBER, RA.TRACK_CODE, RA.START_ADDR_M, RA.END_ADDR_M, RA.LINK_ID, RA.START_MEASURE, RA.END_MEASURE, RA.START_DATE, RA.END_DATE, RA.VALID_FROM, RA.VALID_TO
           FROM ROAD_ADDRESS RA
           WHERE RA.ROAD_NUMBER = $roadNumber AND RA.ROAD_PART_NUMBER = $roadPartNumber AND RA.END_DATE = ${endDate.get} AND  RA.VALID_TO IS NULL
      """.as[OverlapRoadAddress].list
  }

  private def expireRoadAddress(id: Long, dryRun: Boolean) = {
    //Should expire road address with the given id and set the modified by to batch_overlap_data_fixture
    if (!dryRun) {
      sqlu"""
          UPDATE ROAD_ADDRESS SET VALID_TO = sysdate, MODIFIED_BY = 'batch_overlap_data_fixture' WHERE ID = $id
        """.execute
    }
  }

  private def revertRoadAddress(id: Long, startAddrM: Long, endAddrM: Long, dryRun: Boolean) = {
    //Should remove valid_to and set road Address
    if (!dryRun) {
      sqlu"""
          UPDATE ROAD_ADDRESS SET VALID_TO = NULL, START_ADDR_M = $startAddrM, END_ADDR_M = $endAddrM WHERE ID = $id
        """.execute
    }
  }

  private def revertRoadAddress(id: Long, dryRun: Boolean) = {
    //Should remove valid_to and set road Address
    if (!dryRun) {
      sqlu"""
          UPDATE ROAD_ADDRESS SET VALID_TO = NULL WHERE ID = $id
        """.execute
    }
  }

  private def fetchAllExpiredRoadAddressesByChangeInfo(linkId: Long) ={
    val changes = vvhClient.roadLinkChangeInfo.fetchByNewLinkIds(Set(linkId))

    val linkIds = changes.flatMap(_.oldId) ++ changes.flatMap(_.newId)

    logger.info(s"""Fetched VVH change info for link id ${linkId}, old link ids $linkIds""")

    fetchAllExpiredRoadAddresses(linkIds.toSet)
  }


  private def findExpiredRoadAddress(overlapMeasure: OverlapRoadAddress, expiredOverlaps: Seq[OverlapRoadAddress], dryRun: Boolean, fixAddrMeasure: Boolean, addressThreshold: Int): Option[(OverlapRoadAddress, Long)] = {
    implicit def dateTimeOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isBefore _)
    //Find an expired road address in the same link id at the same road number, road part number start address measure and end address measure
    val previousRoadAddresses = expiredOverlaps.
      filter(ra =>
        ra.roadNumber == overlapMeasure.roadNumber && ra.roadPartNumber == overlapMeasure.roadPartNumber && ra.startAddrM == overlapMeasure.startAddrM && ra.endAddrM == overlapMeasure.endAddrM && ra.endDate == overlapMeasure.endDate)

    //If there is any expired match for the current road addresses try to find the nearest one
    if (previousRoadAddresses.isEmpty) {
      if (fixAddrMeasure) {
        expiredOverlaps.
          filter(ra =>
            ra.roadNumber == overlapMeasure.roadNumber && ra.roadPartNumber == overlapMeasure.roadPartNumber && ra.endDate == overlapMeasure.endDate).
          map(ra => (ra, Math.abs(ra.startAddrM - overlapMeasure.startAddrM) + Math.abs(ra.endAddrM - overlapMeasure.endAddrM))).
          sortBy { case (ra, distance) => (ra.validTo, distance) }.
          headOption
      } else {
        None
      }
    } else {
      val oldRoadAddress = previousRoadAddresses.maxBy(_.validTo)
      Some((oldRoadAddress, 0L))
    }
  }

  private def fixRoadAddresses(currentOverlapped: Seq[OverlapRoadAddress], expiredOverlaps: Seq[OverlapRoadAddress], dryRun: Boolean, fixAddrMeasure: Boolean, fetchAllChangesFromVVH: Boolean, addressThreshold: Int): Unit = {

    logger.info(s"Start fixing overlapped road addresses with following options { dry-run=$dryRun, fix-address-measure=$fixAddrMeasure, fetch-all-changes-from-vvh=$fetchAllChangesFromVVH, address-threshold=$addressThreshold }")

    val groupedCurrentOverlapped = currentOverlapped.groupBy(_.linkId)
    val groupedExpiredOverlaps = expiredOverlaps.groupBy(_.linkId)

    logger.info(s"Fetched ${currentOverlapped.size} overlapped road addresses!")
    groupedCurrentOverlapped.foreach {
      case (linkId, overlaps) =>
        logger.info(s"Processing link id $linkId")
        try {
          overlaps.foreach {
            overlapMeasure =>

              val expiredOverlaps = if(fetchAllChangesFromVVH) fetchAllExpiredRoadAddressesByChangeInfo(overlapMeasure.linkId) else groupedExpiredOverlaps.getOrElse(overlapMeasure.linkId, fetchAllExpiredRoadAddressesByChangeInfo(overlapMeasure.linkId))

              if(expiredOverlaps.isEmpty)
                throw new Exception(s"The overlapped measure for link id ${overlapMeasure.linkId} doesn't have expired road addresses!")

              val (oldRoadAddress, distance) = findExpiredRoadAddress(overlapMeasure, expiredOverlaps, dryRun, fixAddrMeasure, addressThreshold).
                getOrElse(throw new Exception(s"Could not find any expired road address to match the overlapped measures $overlapMeasure"))

              if(distance > 0) {
                if (distance <= addressThreshold) {
                  logger.info(s"Fix road address ${overlapMeasure.id} -> ${oldRoadAddress.id}, expire id(${overlapMeasure.id}), revert id(${oldRoadAddress.id}) startAddrM(${overlapMeasure.startAddrM}) endAddrM(${overlapMeasure.endAddrM})")
                  //Revert expired road address
                  revertRoadAddress(oldRoadAddress.id, overlapMeasure.startAddrM, overlapMeasure.endAddrM, dryRun)
                  //Expired current road address
                  expireRoadAddress(overlapMeasure.id, dryRun)
                } else {
                  throw new Exception(s"Found one expired road address with more than $addressThreshold address units from the overlapped measures $overlapMeasure")
                }
              } else {
                logger.info(s"Fix road address ${overlapMeasure.id} -> ${oldRoadAddress.id}, expire id(${overlapMeasure.id}), revert id(${oldRoadAddress.id})")
                //Revert expired road address
                revertRoadAddress(oldRoadAddress.id, dryRun)
                //Expired current road address
                expireRoadAddress(overlapMeasure.id, dryRun)
              }
          }
        } catch {
          case e: Exception => logger.error(s"Error at link id $linkId with following message: " + e.getMessage())
        }
    }
  }

  def fixOverlapRoadAddresses(dryRun: Boolean, fixAddrMeasure: Boolean, withPartial: Boolean, fetchAllChangesFromVVH: Boolean, addressThreshold: Int) = {
    val currentOverlapped = if (withPartial) fetchAllWithPartialOverlapRoadAddresses() else fetchAllOverlapRoadAddresses()
    val expiredOverlaps = fetchAllExpiredRoadAddresses(currentOverlapped.map(_.linkId).toSet)
    fixRoadAddresses(currentOverlapped, expiredOverlaps, dryRun, fixAddrMeasure, fetchAllChangesFromVVH, addressThreshold)
  }

  def fixOverlapRoadAddressesByDates(dryRun: Boolean) = {
    val currentOverlapped = fetchAllWithPartialOverlapRoadAddresses()
    fixRoadAddressesWithValidDates(currentOverlapped, dryRun)
  }

  private def fixRoadAddressesWithValidDates(currentOverlapped: Seq[OverlapRoadAddress], dryRun: Boolean): Unit = {
    implicit def dateTimeOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isBefore _)
    logger.info(s"Start fixing overlapped road addresses with valid dates and following options { dry-run=$dryRun }")

    val groupedCurrentOverlapped = currentOverlapped.groupBy(ra => (ra.roadNumber, ra.roadPartNumber, ra.endDate))

    groupedCurrentOverlapped.foreach {
      case ((roadNumber, roadPartNumber, endDate), overlaps) =>
        try {
          logger.info(s"Processing road number $roadNumber and road part number $roadPartNumber and end date $endDate")

          val revertMinDate = overlaps.flatMap(_.validFrom).min
          val roadAddressesToRevert = fetchExpiredByValidDate(roadNumber, roadPartNumber, endDate, revertMinDate)

          if(roadAddressesToRevert.isEmpty)
            throw new Exception(s"The overlapped measure for road number($roadNumber) road part number($roadPartNumber) and end date($endDate) doesn't have expired road addresses!")

          val validSection = fetchAllValidRoadAddressSection(roadNumber, roadPartNumber, endDate, revertMinDate)

          logger.info(s"Revert road address section to date($revertMinDate)")

          val toExpireRoadAddresses = validSection.filter(ra => ra.validFrom.get.isAfter(revertMinDate.minusSeconds(2)))

          if(toExpireRoadAddresses.isEmpty)
            throw new Exception(s"The overlapped measure for road number($roadNumber) road part number($roadPartNumber) and end date($endDate) doesn't have any valid road addresses!")

          toExpireRoadAddresses.foreach{
            ra =>
              logger.info(s"Expire road address ${ra.id}")
              expireRoadAddress(ra.id, dryRun)
          }

          val groupedToRevertByAddress = groupOverlapedRoadAddresses(roadAddressesToRevert.sortBy(_.startAddrM))
          val groupedToExpireByAddress = groupOverlapedRoadAddresses(toExpireRoadAddresses.sortBy(_.startAddrM))

          if(groupedToExpireByAddress.size != groupedToRevertByAddress.size)
            throw new Exception(s"There is not the same amount of continuous addresses to be expired and reverted")

          //recalculate reverted road addresses
          groupedToRevertByAddress.foreach{
            section =>
              //Find the nearest section to set the road address equal
              val nearestSection = groupedToExpireByAddress.minBy(expiredSection => Math.abs(section.head.startAddrM - expiredSection.head.startAddrM) + Math.abs(section.head.endAddrM - expiredSection.head.endAddrM))

              section.size match {
                case 1 =>
                  //Fix road adddresses wer
                  logger.info(s"Revert road address id(${section.head.id}) to startAddrM(${nearestSection.head.startAddrM}) and endAddrM(${nearestSection.last.endAddrM})")
                  revertRoadAddress(section.head.id, nearestSection.head.startAddrM, nearestSection.last.endAddrM, dryRun)
                case _ =>
                  logger.info(s"Revert road address id(${section.head.id}) to startAddrM(${nearestSection.head.startAddrM}) and endAddrM(${section.head.endAddrM})")
                  revertRoadAddress(section.head.id, nearestSection.head.startAddrM, section.head.endAddrM, dryRun)
                  section.tail.init.foreach{
                    ra =>
                      logger.info(s"Revert road address id(${ra.id})")
                      revertRoadAddress(ra.id, dryRun)
                  }
                  logger.info(s"Revert road address id(${section.last.id}) to startAddrM(${section.last.startAddrM}) and endAddrM(${nearestSection.last.endAddrM})")
                  revertRoadAddress(section.last.id, section.last.startAddrM, nearestSection.last.endAddrM, dryRun)
              }
          }
        } catch {
          case e: Exception => logger.error(s"Error at road number $roadNumber and road part number $roadPartNumber and end date $endDate with following message: " + e.getMessage())
        }
    }
  }
  private def combineRecursive(roadAddress: OverlapRoadAddress, roadAddresses: Seq[OverlapRoadAddress], acc: Seq[OverlapRoadAddress] = Seq.empty): (Seq[OverlapRoadAddress], Seq[OverlapRoadAddress]) = {
    if(roadAddresses.nonEmpty && roadAddress.endAddrM == roadAddresses.head.startAddrM)
      combineRecursive(roadAddresses.head, roadAddresses.tail, acc :+ roadAddresses.head)
    else
      (acc, roadAddresses)
  }

  private def groupOverlapedRoadAddresses(roadAddresses: Seq[OverlapRoadAddress], acc: Seq[Seq[OverlapRoadAddress]] = Seq()): Seq[Seq[OverlapRoadAddress]] = {
    roadAddresses match {
      case Seq() => acc
      case rls =>
        val (continuous, rest) = combineRecursive(rls.head, rls.tail, Seq(rls.head))
        groupOverlapedRoadAddresses(rest, if(continuous.isEmpty) acc else acc :+ continuous)
    }
  }

  implicit val getOverlapRoadAddress = new GetResult[OverlapRoadAddress] {
    def apply(r: PositionedResult) = {

      val id = r.nextLong()
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val trackCode = r.nextLong()
      val startAddrM = r.nextLong()
      val endAddrM = r.nextLong()
      val linkId = r.nextLong()
      val startM = r.nextDouble()
      val endM = r.nextDouble()
      val startDate = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val endDate = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val validFrom = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val validTo = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))

      OverlapRoadAddress(id, roadNumber, roadPartNumber, trackCode, startAddrM, endAddrM, linkId, startM, endM, startDate, endDate, validFrom, validTo)
    }
  }
}
