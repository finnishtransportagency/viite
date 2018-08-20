package fi.liikennevirasto.viite.util

import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc._

case class OverlapRoadAddress(id: Long, roadNumber: Long, roadPartNumber: Long, trackCode: Long, startAddrM: Long, endAddrM: Long, linkId: Long,
                              startM: Double, endM: Double, startDate: Option[DateTime], endDate: Option[DateTime],
                              validFrom: Option[DateTime], validTo: Option[DateTime])

class OverlapDataFixture {

  val logger = LoggerFactory.getLogger(getClass)

  private def fetchAllOverlapRoadAddresses(): Seq[OverlapRoadAddress] = {
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

  def fixOverlapRoadAddresses(dryRun: Boolean, fixAddrMeasure: Boolean): Unit = {
    implicit def dateTimeOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isBefore _)

    logger.info(s"Start fixing overlapped road addresses with following options { dry-run=$dryRun, fix-address-measure=$fixAddrMeasure }")
    val overlapMeasures = fetchAllOverlapRoadAddresses()

    val (currentOverlapped, expiredOverlaps) = overlapMeasures.partition(_.validTo.isEmpty)

    val groupedCurrentOverlapped = currentOverlapped.groupBy(_.linkId)
    val groupedExpiredOverlaps = expiredOverlaps.groupBy(_.linkId)

    logger.info(s"Fetched ${currentOverlapped.size} overlapped road addresses!")
    groupedCurrentOverlapped.foreach {
      case (linkId, overlaps) =>
        logger.info(s"Processing link id $linkId")
        try {
          overlaps.foreach {
            overlapMeasure =>
              val expiredOverlaps = groupedExpiredOverlaps.getOrElse(overlapMeasure.linkId,
                throw new Exception(s"The overlapped measure for link id ${overlapMeasure.linkId} doesn't have a expired road addresses!"))

              //Find a expired road address in the same link id at the same road number, road part number start address measure and end address measure
              val previousRoadAddresses = expiredOverlaps.
                filter(ra =>
                  ra.roadNumber == overlapMeasure.roadNumber && ra.roadPartNumber == overlapMeasure.roadPartNumber && ra.startAddrM == overlapMeasure.startAddrM && ra.endAddrM == overlapMeasure.endAddrM)

              //If there is any expired match for the current road addresses try to find the nearest one
              if (previousRoadAddresses.isEmpty) {
                if (fixAddrMeasure) {
                  val (oldRoadAddress, distance) = expiredOverlaps.
                    filter(ra =>
                      ra.roadNumber == overlapMeasure.roadNumber && ra.roadPartNumber == overlapMeasure.roadPartNumber).
                    map(ra => (ra, Math.abs(ra.startAddrM - overlapMeasure.startAddrM) + Math.abs(ra.endAddrM - overlapMeasure.endAddrM))).
                    sortBy { case (ra, distance) => (ra.validTo, distance) }.
                    headOption.getOrElse(throw new Exception(s"Could not find any expired road address to match the overlapped measures $overlapMeasure"))

                  logger.info(s"Fix road address ${overlapMeasure.id} -> ${oldRoadAddress.id}, expire id(${overlapMeasure.id}), revert id(${oldRoadAddress.id}) startAddrM(${oldRoadAddress.startAddrM}) endAddrM(${oldRoadAddress.endAddrM})")
                  //Revert expired road address
                  revertRoadAddress(oldRoadAddress.id, overlapMeasure.startAddrM, overlapMeasure.endAddrM, dryRun)
                  //Expired current road address
                  expireRoadAddress(overlapMeasure.id, dryRun)
                } else {
                  throw new Exception(s"Could not find any expired road address to match the overlapped measures $overlapMeasures")
                }
              } else {
                val oldRoadAddress = previousRoadAddresses.maxBy(_.validTo)

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
