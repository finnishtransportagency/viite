package fi.liikennevirasto.viite.util

import java.sql.PreparedStatement

import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import org.joda.time.format.ISODateTimeFormat
import slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.client.vvh.{VVHClient, VVHHistoryRoadLink, VVHRoadlink}
import fi.liikennevirasto.viite.dao.CalibrationCode
import fi.liikennevirasto.viite.dao.RoadAddressDAO.formatter
import org.joda.time._
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc._


case class ConversionRoadAddress(roadNumber: Long, roadPartNumber: Long, trackCode: Long, discontinuity: Long,
                                 startAddrM: Long, endAddrM: Long, startM: Double, endM: Double, startDate: Option[DateTime], endDate: Option[DateTime],
                                 validFrom: Option[DateTime], validTo: Option[DateTime], ely: Long, roadType: Long,
                                 terminated: Long, linkId: Long, userId: String, x1: Option[Double], y1: Option[Double],
                                 x2: Option[Double], y2: Option[Double], lrmId: Long, commonHistoryId: Long, sideCode: SideCode, calibrationCode: CalibrationCode = CalibrationCode.No, directionFlag: Long = 0)

class RoadAddressImporter(conversionDatabase: DatabaseDef, vvhClient: VVHClient, importOptions: ImportOptions) {

  case class IncomingLrmPosition(id: Long, linkId: Long, startM: Double, endM: Double, sideCode: SideCode, linkSource: LinkGeomSource, commonHistoryId: Long)
  case class IncomingRoadAddress(roadNumber: Long, roadPartNumber: Long, trackCode: Long, discontinuity: Long,
                                         startAddrM: Long, endAddrM: Long, startDate: DateTime, endDate: Option[DateTime],
                                         createdBy: String, validFrom: Option[DateTime], x1: Option[Double], y1: Option[Double],
                                         x2: Option[Double], y2: Option[Double], roadType: Long, ely: Long, commonHistoryId: Long)

  val dateFormatter = ISODateTimeFormat.basicDate()

  def printConversionRoadAddress(r: ConversionRoadAddress): String = {
    s"""linkid: %d, alku: %.2f, loppu: %.2f, tie: %d, aosa: %d, ajr: %d, ely: %d, tietyyppi: %d, jatkuu: %d, aet: %d, let: %d, alkupvm: %s, loppupvm: %s, kayttaja: %s, muutospvm or rekisterointipvm: %s, ajorataId: %s, kalibrointpiste: %s""".
      format(r.linkId, r.startM, r.endM, r.roadNumber, r.roadPartNumber, r.trackCode, r.ely, r.roadType, r.discontinuity, r.startAddrM, r.endAddrM, r.startDate, r.endDate, r.userId, r.validFrom, r.commonHistoryId, r.calibrationCode)
  }

  private def roadAddressStatement() =
    dynamicSession.prepareStatement("insert into ROAD_ADDRESS (id, road_number, road_part_number, " +
      "track_code, discontinuity, start_addr_m, end_addr_m, start_date, end_date, created_by, " +
      "valid_from, geometry, floating, road_type, ely, common_history_id, calibration_points, link_id, SIDE_CODE, start_measure, end_measure, link_source) values (viite_general_seq.nextval, ?, ?, ?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), " +
      "TO_DATE(?, 'YYYY-MM-DD'), ?, TO_DATE(?, 'YYYY-MM-DD'), MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(" +
      "?,?,0.0,0.0,?,?,0.0,?)), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")

  private def insertRoadAddress(roadAddressStatement: PreparedStatement, roadAddress: ConversionRoadAddress, lrmPosition: IncomingLrmPosition): Unit = {
    def datePrinter(date: Option[DateTime]): String = {
      date match {
        case Some(dt) => dateFormatter.print(dt)
        case None => ""
      }
    }
    roadAddressStatement.setLong(1, roadAddress.roadNumber)
    roadAddressStatement.setLong(2, roadAddress.roadPartNumber)
    roadAddressStatement.setLong(3, roadAddress.trackCode)
    roadAddressStatement.setLong(4, roadAddress.discontinuity)
    roadAddressStatement.setLong(5, roadAddress.startAddrM)
    roadAddressStatement.setLong(6, roadAddress.endAddrM)
    roadAddressStatement.setString(7, dateFormatter.print(roadAddress.startDate.get))
    roadAddressStatement.setString(8, datePrinter(roadAddress.endDate))
    roadAddressStatement.setString(9, roadAddress.userId)
    roadAddressStatement.setString(10, datePrinter(roadAddress.validFrom))
    roadAddressStatement.setDouble(11, roadAddress.x1.get)
    roadAddressStatement.setDouble(12, roadAddress.y1.get)
    roadAddressStatement.setDouble(13, roadAddress.x2.get)
    roadAddressStatement.setDouble(14, roadAddress.y2.get)
    roadAddressStatement.setDouble(15, roadAddress.endAddrM - roadAddress.startAddrM)
    roadAddressStatement.setInt(16, if (lrmPosition.linkSource == LinkGeomSource.HistoryLinkInterface) 1 else 0)
    roadAddressStatement.setInt(16, 0)
    roadAddressStatement.setLong(17, roadAddress.roadType)
    roadAddressStatement.setLong(18, roadAddress.ely)
    roadAddressStatement.setLong(19, roadAddress.commonHistoryId)
    roadAddressStatement.setLong(20, roadAddress.calibrationCode.value)
    roadAddressStatement.setLong(21, lrmPosition.linkId)
    roadAddressStatement.setLong(22, lrmPosition.sideCode.value)
    roadAddressStatement.setDouble(23, lrmPosition.startM)
    roadAddressStatement.setDouble(24, lrmPosition.endM)
    roadAddressStatement.setLong(25, lrmPosition.linkSource.value)

    roadAddressStatement.addBatch()
  }

  private def fetchRoadLinksFromVVH(linkIds: Set[Long]): Map[Long, Seq[VVHRoadlink]] = {
    val vvhRoadLinkClient = if (importOptions.useFrozenLinkService) vvhClient.frozenTimeRoadLinkData else vvhClient.roadLinkData
    linkIds.grouped(4000).flatMap(group =>
      vvhRoadLinkClient.fetchByLinkIds(group) ++ vvhClient.complementaryData.fetchByLinkIds(group) ++ vvhClient.suravageData.fetchSuravageByLinkIds(group)
    ).toSeq.groupBy(_.linkId)
  }

  private def fetchHistoryRoadLinksFromVVH(linkIds: Set[Long]): Map[Long, VVHHistoryRoadLink] =
    vvhClient.historyData.fetchVVHRoadLinkByLinkIds(linkIds).groupBy(_.linkId).mapValues(_.maxBy(_.endDate))


  private def adjustLrmPosition(lrmPos: Seq[IncomingLrmPosition], length: Double): Seq[IncomingLrmPosition] = {
    val coefficient: Double = length / (lrmPos.maxBy(_.endM).endM - lrmPos.minBy(_.startM).startM)
    lrmPos.map(lrm => lrm.copy(startM = lrm.startM * coefficient, endM = lrm.endM * coefficient))
  }

  protected def fetchRoadAddressFromConversionTable(minLinkId: Long, maxLinkId: Long, filter: String): Seq[ConversionRoadAddress] = {
    conversionDatabase.withDynSession {
      val tableName = importOptions.conversionTable
      sql"""select tie, aosa, ajr, jatkuu, aet, let, alku, loppu, TO_CHAR(alkupvm, 'YYYY-MM-DD hh:mm:ss'), TO_CHAR(loppupvm, 'YYYY-MM-DD hh:mm:ss'),
               TO_CHAR(muutospvm, 'YYYY-MM-DD hh:mm:ss'), ely, tietyyppi, linkid, kayttaja, alkux, alkuy, loppux,
               loppuy, (linkid * 10000 + ajr * 1000 + aet) as id, ajorataid, kaannetty, alku_kalibrointpiste, loppu_kalibrointpiste from #$tableName
               WHERE linkid > $minLinkId AND linkid <= $maxLinkId AND  aet >= 0 AND let >= 0 AND lakkautuspvm IS NULL #$filter """
        .as[ConversionRoadAddress].list
    }
  }

  private def generateChunks(linkIds: Seq[Long], chunkNumber: Long): Seq[(Long, Long)] = {
    val (chunks, _) = linkIds.foldLeft((Seq[Long](0), 0)) {
      case ((fchunks, index), linkId) =>
        if (index > 0 && index % chunkNumber == 0) {
          (fchunks ++ Seq(linkId), index + 1)
        } else {
          (fchunks, index + 1)
        }
    }
    val result = if (chunks.last == linkIds.last) {
      chunks
    } else {
      chunks ++ Seq(linkIds.last)
    }

    result.zip(result.tail)
  }

  protected def fetchChunkLinkIdsFromConversionTable(): Seq[(Long, Long)] = {
    //TODO Try to do the group in the query
    conversionDatabase.withDynSession {
      val tableName = importOptions.conversionTable
      val linkIds = sql"""select distinct linkid from #$tableName where linkid is not null order by linkid""".as[Long].list
      generateChunks(linkIds, 25000l)
    }
  }

  private val withCurrentAndHistoryRoadAddress: String = ""

  def importRoadAddress(): Unit = {
    val chunks = fetchChunkLinkIdsFromConversionTable()

    chunks.foreach {
      case (min, max) =>

        print(s"${DateTime.now()} - ")
        println(s"Processing chunk ($min, $max)")

        val conversionRoadAddress = fetchRoadAddressFromConversionTable(min, max, withCurrentAndHistoryRoadAddress)

        print(s"\n${DateTime.now()} - ")
        println("Read %d rows from conversion database".format(conversionRoadAddress.size))

        importRoadAddress(conversionRoadAddress)
    }
  }

  private def importRoadAddress(conversionRoadAddress: Seq[ConversionRoadAddress]): Unit = {

    val linkIds = conversionRoadAddress.map(_.linkId)

    print(s"${DateTime.now()} - ")
    println("Total of %d link ids".format(linkIds.size))

    val mappedRoadLinks = fetchRoadLinksFromVVH(linkIds.toSet)

    print(s"${DateTime.now()} - ")
    println("Read %d road links from vvh".format(mappedRoadLinks.size))

    val mappedHistoryRoadLinks = fetchHistoryRoadLinksFromVVH(linkIds.filterNot(linkId => mappedRoadLinks.get(linkId).isDefined).toSet)

    print(s"${DateTime.now()} - ")
    println("Read %d road links history from vvh".format(mappedHistoryRoadLinks.size))

    val suppressedRoadLinks = conversionRoadAddress.filter(ra => ra.linkId == 0 || (mappedRoadLinks.get(ra.linkId).isEmpty && mappedHistoryRoadLinks.get(ra.linkId).isEmpty))
    suppressedRoadLinks.foreach {
      ra => println("Suppressed row ID %d with reason 1: 'LINK-ID is not found in the VVH Interface' %s".format(ra.lrmId, printConversionRoadAddress(ra)))
    }

    val mappedConversionRoadAddress = conversionRoadAddress.
      groupBy(ra => (ra.linkId, ra.commonHistoryId))

    val incomingLrmPositions = mappedConversionRoadAddress.map(ra => ra._2.maxBy(_.startDate.get.getMillis)).flatMap {
      case(ra) =>
        mappedRoadLinks.getOrElse(ra.linkId, Seq()).headOption match {
          case Some(rl) =>
            Some(IncomingLrmPosition(ra.lrmId, ra.linkId, ra.startM, ra.endM, ra.sideCode, rl.linkSource, ra.commonHistoryId))
          case _ =>
            mappedHistoryRoadLinks.get(ra.linkId) match {
              case Some(rl) =>
                Some(IncomingLrmPosition(ra.lrmId, ra.linkId, ra.startM, ra.endM, ra.sideCode, LinkGeomSource.HistoryLinkInterface, ra.commonHistoryId))
              case _ => None
            }
        }
    }

    val allLinkGeomLength =
      mappedRoadLinks.map(ra => (ra._1, GeometryUtils.geometryLength(ra._2.head.geometry))) ++
      mappedHistoryRoadLinks.map(ra =>  (ra._1, GeometryUtils.geometryLength(ra._2.geometry)))

    val lrmPositions = allLinkGeomLength.flatMap {
      case (linkId, geomLength) =>
        adjustLrmPosition(incomingLrmPositions.filter(lrm => lrm.linkId == linkId).toSeq, geomLength)
    }.groupBy(lrm => (lrm.linkId, lrm.commonHistoryId))

    val roadAddressPs = roadAddressStatement()

    conversionRoadAddress.foreach {
      case (roadAddress) =>
        lrmPositions.getOrElse((roadAddress.linkId, roadAddress.commonHistoryId), Seq()).headOption.foreach {
          case lrmPosition =>
            if (roadAddress.directionFlag == 1) {
              val reversedLRM = lrmPosition.copy(sideCode = SideCode.switch(lrmPosition.sideCode))
              insertRoadAddress(roadAddressPs, roadAddress, reversedLRM)
            } else {
              insertRoadAddress(roadAddressPs, roadAddress, lrmPosition)
            }

        }
    }

    roadAddressPs.executeBatch()
    println(s"${DateTime.now()} - Road addresses saved")
    roadAddressPs.close()
  }

  implicit val getConversionRoadAddress = new GetResult[ConversionRoadAddress] {
    def apply(r: PositionedResult) = {
      val roadNumber = r.nextLong()
      val roadPartNumber = r.nextLong()
      val trackCode = r.nextLong()
      val discontinuity = r.nextLong()
      val startAddrM = r.nextLong()
      val endAddrM = r.nextLong()
      val startM = r.nextDouble()
      val endM = r.nextDouble()
      val startDate = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val endDateOption = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val validFrom = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val ely = r.nextLong()
      val roadType = r.nextLong()
      val linkId = r.nextLong()
      val userId = r.nextString
      val x1 = r.nextDouble()
      val y1 = r.nextDouble()
      val x2 = r.nextDouble()
      val y2 = r.nextDouble()
      val lrmId = r.nextLong()
      val commonHistoryId = r.nextLong()
      val directionFlag = r.nextLong()
      val startCalibrationPoint = r.nextLong()
      val endCalibrationPoint = r.nextLong()


      val calibrationCode = (startCalibrationPoint, endCalibrationPoint) match {
        case (1, 1) => CalibrationCode.AtBoth
        case (1, 0) => CalibrationCode.AtBeginning
        case (0, 1) => CalibrationCode.AtEnd
        case _ => CalibrationCode.No
      }

      val viiteEndDate = endDateOption match {
        case Some(endDate) => Some(endDate.plusDays(1))
        case _ => None
      }

      if (startAddrM < endAddrM) {
        ConversionRoadAddress(roadNumber, roadPartNumber, trackCode, discontinuity, startAddrM, endAddrM, startM, endM, startDate, viiteEndDate, validFrom, None, ely, roadType, 0,
          linkId, userId, Option(x1), Option(y1), Option(x2), Option(y2), lrmId, commonHistoryId, SideCode.TowardsDigitizing, calibrationCode, directionFlag)
      } else {
        //switch startAddrM, endAddrM, the geometry and set the side code to AgainstDigitizing
        ConversionRoadAddress(roadNumber, roadPartNumber, trackCode, discontinuity, endAddrM, startAddrM, startM, endM, startDate, viiteEndDate, validFrom, None, ely, roadType, 0,
          linkId, userId, Option(x2), Option(y2), Option(x1), Option(y1), lrmId, commonHistoryId, SideCode.AgainstDigitizing, calibrationCode, directionFlag)
      }
    }
  }
}

