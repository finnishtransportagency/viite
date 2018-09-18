package fi.liikennevirasto.viite.util

import java.sql.{PreparedStatement, Timestamp}
import java.util.Date

import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import org.joda.time.format.ISODateTimeFormat
import slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.client.vvh.{VVHClient, VVHHistoryRoadLink, VVHRoadlink}
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.{CalibrationCode, FloatingReason}
import org.joda.time._
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc._


case class ConversionAddress(roadNumber: Long, roadPartNumber: Long, trackCode: Long, discontinuity: Long,
                             startAddressM: Long, endAddressM: Long, startM: Double, endM: Double, startDate: Option[DateTime], endDate: Option[DateTime],
                             validFrom: Option[DateTime], validTo: Option[DateTime], ely: Long, roadType: Long,
                             terminated: Long, linkId: Long, userId: String, x1: Option[Double], y1: Option[Double],
                             x2: Option[Double], y2: Option[Double], roadwayId: Long, sideCode: SideCode, calibrationCode: CalibrationCode = CalibrationCode.No, directionFlag: Long = 0)

class RoadAddressImporter(conversionDatabase: DatabaseDef, vvhClient: VVHClient, importOptions: ImportOptions) {

  case class IncomingRoadAddress(roadwayId: Long, roadNumber: Long, roadPartNumber: Long, trackCode: Long, startAddrM: Long, endAddrM: Long, reversed: Long,
                                 startDate: Option[DateTime], endDate: Option[DateTime], createdBy: String, roadType: Long, ely: Long, validFrom: Option[DateTime], validTo: Option[DateTime], discontinuity: Long, terminated: Long)

  case class IncomingLinearLocation(roadwayId: Long, orderNumber: Long, linkId: Long, startMeasure: Double, endMeasure: Double, sideCode: SideCode,
                                    calStartM: Option[Long], calEndM: Option[Long], linkGeomSource: LinkGeomSource, floating: FloatingReason, createdBy: String, x1: Option[Double], y1: Option[Double],
                                    x2: Option[Double], y2: Option[Double], validFrom: Option[DateTime], validTo: Option[DateTime])

  val dateFormatter = ISODateTimeFormat.basicDate()

  def printConversionAddress(r: ConversionAddress): String = {
    s"""linkid: %d, alku: %.2f, loppu: %.2f, tie: %d, aosa: %d, ajr: %d, ely: %d, tietyyppi: %d, jatkuu: %d, aet: %d, let: %d, alkupvm: %s, loppupvm: %s, kayttaja: %s, muutospvm or rekisterointipvm: %s, ajorataId: %s, kalibrointpiste: %s""".
      format(r.linkId, r.startM, r.endM, r.roadNumber, r.roadPartNumber, r.trackCode, r.ely, r.roadType, r.discontinuity, r.startAddressM, r.endAddressM, r.startDate, r.endDate, r.userId, r.validFrom, r.roadwayId, r.calibrationCode)
  }

  private def roadAddressStatement() =
    dynamicSession.prepareStatement("insert into ROAD_ADDRESS (id, roadway_id, road_number, road_part_number, track_code, start_addr_m, end_addr_m, reversed, start_date, end_date, created_by, road_type, ely, valid_from, valid_to, discontinuity, terminated) " +
                                          "values (viite_general_seq.nextval, ?, ?, ?, ?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?, ?)")

  private def linearLocationStatement() =
    dynamicSession.prepareStatement("insert into LINEAR_LOCATION (id, roadway_id, order_number, link_id, start_measure, end_measure, side_code, cal_start_m, cal_end_m, link_source, adjusted_timestamp, " +
      "modified_date, modified_by, floating, geometry, valid_from, valid_to) " +
      "values (viite_general_seq.nextval, ?, ?, ?, ?, ?, ?, ?, ?, ?,? , ?, ?, ?, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(?,?,0.0,0.0,?,?,0.0,?)), TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'))")

  def datePrinter(date: Option[DateTime]): String = {
    date match {
      case Some(dt) => dateFormatter.print(dt)
      case None => ""
    }
  }

  private def insertRoadAddress(roadAddressStatement: PreparedStatement, roadAddress: IncomingRoadAddress): Unit = {
    roadAddressStatement.setLong(1, roadAddress.roadwayId)
    roadAddressStatement.setLong(2, roadAddress.roadNumber)
    roadAddressStatement.setLong(3, roadAddress.roadPartNumber)
    roadAddressStatement.setLong(4, roadAddress.trackCode)
    roadAddressStatement.setLong(5, roadAddress.startAddrM)
    roadAddressStatement.setLong(6, roadAddress.endAddrM)
    roadAddressStatement.setLong(7, roadAddress.reversed)
    roadAddressStatement.setString(8, datePrinter(roadAddress.startDate))
    roadAddressStatement.setString(9, datePrinter(roadAddress.endDate))
    roadAddressStatement.setString(10, roadAddress.createdBy)
    roadAddressStatement.setLong(11, roadAddress.roadType)
    roadAddressStatement.setLong(12, roadAddress.ely)
    roadAddressStatement.setString(13, datePrinter(roadAddress.validFrom))
    roadAddressStatement.setString(14, datePrinter(roadAddress.validTo))
    roadAddressStatement.setLong(15, roadAddress.discontinuity)
    roadAddressStatement.setLong(16, roadAddress.terminated)
    roadAddressStatement.addBatch()
  }

  private def insertLinearLocation(linearLocationStatement: PreparedStatement, linearLocation: IncomingLinearLocation) = {
    linearLocationStatement.setLong(1, linearLocation.roadwayId)
    linearLocationStatement.setLong(2, linearLocation.orderNumber)
    linearLocationStatement.setLong(3, linearLocation.linkId)
    linearLocationStatement.setDouble(4, linearLocation.startMeasure)
    linearLocationStatement.setDouble(5, linearLocation.endMeasure)
    linearLocationStatement.setLong(6, linearLocation.sideCode.value)
    linearLocationStatement.setLong(7, 0)
    linearLocationStatement.setLong(8, 0)
    linearLocationStatement.setLong(9, linearLocation.linkGeomSource.value)
    linearLocationStatement.setLong(10, 0)
    linearLocationStatement.setTimestamp(11, new Timestamp(System.currentTimeMillis()))
    linearLocationStatement.setString(12, linearLocation.createdBy)
    linearLocationStatement.setLong(13, linearLocation.floating.value)
    linearLocationStatement.setDouble(14, linearLocation.x1.get)
    linearLocationStatement.setDouble(15, linearLocation.y1.get)
    linearLocationStatement.setDouble(16, linearLocation.x2.get)
    linearLocationStatement.setDouble(17, linearLocation.y2.get)
    linearLocationStatement.setDouble(18, linearLocation.endMeasure)
    linearLocationStatement.setString(19, datePrinter(linearLocation.validFrom))
    linearLocationStatement.setString(20, datePrinter(linearLocation.validTo))
    println(s"s: ${linearLocation.startMeasure}, e: ${linearLocation.endMeasure}, linkId: ${linearLocation.linkId}, roadwayID: ${linearLocation.roadwayId}")
    linearLocationStatement.addBatch()

  }

  private def fetchRoadLinksFromVVH(linkIds: Set[Long]): Map[Long, Seq[VVHRoadlink]] = {
    val vvhRoadLinkClient = if (importOptions.useFrozenLinkService) vvhClient.frozenTimeRoadLinkData else vvhClient.roadLinkData
    linkIds.grouped(4000).flatMap(group =>
      vvhRoadLinkClient.fetchByLinkIds(group) ++ vvhClient.complementaryData.fetchByLinkIds(group) ++ vvhClient.suravageData.fetchSuravageByLinkIds(group)
    ).toSeq.groupBy(_.linkId)
  }

  private def fetchHistoryRoadLinksFromVVH(linkIds: Set[Long]): Map[Long, VVHHistoryRoadLink] =
    vvhClient.historyData.fetchVVHRoadLinkByLinkIds(linkIds).groupBy(_.linkId).mapValues(_.maxBy(_.endDate))


  private def adjustLinearLocation(linearLocation: IncomingLinearLocation, length: Double): IncomingLinearLocation = {
    val coefficient: Double = length / (linearLocation.endMeasure - linearLocation.startMeasure)
    if(coefficient < 0){
      println(s"Negative: ${linearLocation.linkId}, $length, ${linearLocation.startMeasure}, ${linearLocation.endMeasure}")
    }
    println(s" linkid: ${linearLocation.linkId}, (${linearLocation.startMeasure * coefficient}),(${linearLocation.endMeasure * coefficient})")
    linearLocation.copy(startMeasure = BigDecimal(linearLocation.startMeasure * coefficient).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble, endMeasure = BigDecimal(linearLocation.endMeasure * coefficient).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble)
  }

  protected def fetchAddressesFromConversionTable(minLinkId: Long, maxLinkId: Long, filter: String): Seq[ConversionAddress] = {
    conversionDatabase.withDynSession {
      val tableName = importOptions.conversionTable
      sql"""select tie, aosa, ajr, jatkuu, aet, let, alku, loppu, TO_CHAR(alkupvm, 'YYYY-MM-DD hh:mm:ss'), TO_CHAR(loppupvm, 'YYYY-MM-DD hh:mm:ss'),
               TO_CHAR(muutospvm, 'YYYY-MM-DD hh:mm:ss'), ely, tietyyppi, linkid, kayttaja, alkux, alkuy, loppux,
               loppuy, ajorataid, kaannetty, alku_kalibrointpiste, loppu_kalibrointpiste from #$tableName
               WHERE linkid > $minLinkId AND linkid <= $maxLinkId AND  aet >= 0 AND let >= 0 #$filter """
        .as[ConversionAddress].list
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
        val conversionAddress = fetchAddressesFromConversionTable(min, max, withCurrentAndHistoryRoadAddress)
        print(s"\n${DateTime.now()} - ")
        println("Read %d rows from conversion database".format(conversionAddress.size))
        importAddresses(conversionAddress)
    }
  }

  private def importAddresses(conversionAddress: Seq[ConversionAddress]): Unit = {

    val linkIds = conversionAddress.map(_.linkId)
    print(s"${DateTime.now()} - ")
    println("Total of %d link ids".format(linkIds.size))
    val mappedRoadLinks = fetchRoadLinksFromVVH(linkIds.toSet)
    print(s"${DateTime.now()} - ")
    println("Read %d road links from vvh".format(mappedRoadLinks.size))
    val mappedHistoryRoadLinks = fetchHistoryRoadLinksFromVVH(linkIds.filterNot(linkId => mappedRoadLinks.get(linkId).isDefined).toSet)
    print(s"${DateTime.now()} - ")
    println("Read %d road links history from vvh".format(mappedHistoryRoadLinks.size))

    val suppressedRoadLinks = conversionAddress.filter(ra => ra.linkId == 0 || (mappedRoadLinks.get(ra.linkId).isEmpty && mappedHistoryRoadLinks.get(ra.linkId).isEmpty))
    suppressedRoadLinks.foreach {
      ra => println("Suppressed row linkID %d with reason 1: 'LINK-ID is not found in the VVH Interface' %s".format(ra.linkId, printConversionAddress(ra)))
    }

    //TODO - insert expiredConversionAddresses and historyConversionAddresses
    val (validConversionAddresses, expiredConversionAddresses) = conversionAddress.partition(_.validTo.isEmpty)
    val (currentConversionAddresses, historyConversionAddresses) = validConversionAddresses.partition(_.endDate.isEmpty)

    val currentMappedConversionAddresses = currentConversionAddresses.groupBy(ra => (ra.roadwayId, ra.roadNumber, ra.roadPartNumber, ra.trackCode, ra.startDate, ra.endDate))
    val historyMappedConversionAddresses = historyConversionAddresses.groupBy(ra => (ra.roadwayId, ra.roadNumber, ra.roadPartNumber, ra.trackCode, ra.startDate, ra.endDate))
    val roadAddressPs = roadAddressStatement()
    val linearLocationPs = linearLocationStatement()

    currentMappedConversionAddresses.mapValues{
      case address =>
        address.sortBy(_.startAddressM).zip(1 to address.size)
    }.foreach{
      case (key, addresses) =>
        addresses.foreach{
          //add current linear locations
          add =>
            val converted = add._1
            val roadLink = mappedRoadLinks.get(converted.linkId).head

            val linearLocation = adjustLinearLocation(IncomingLinearLocation(converted.roadwayId, add._2, converted.linkId, converted.startM, converted.endM, converted.sideCode, None, None,  roadLink.head.linkSource, FloatingReason.NoFloating,
              createdBy = "import", converted.x1, converted.y1, converted.x2, converted.y2, converted.validFrom, None), GeometryUtils.geometryLength(roadLink.head.geometry))
            if(add._1.directionFlag == 1){
              val revertedDirectionLinearLocation = linearLocation.copy(sideCode = SideCode.switch(linearLocation.sideCode))
              insertLinearLocation(linearLocationPs, revertedDirectionLinearLocation)
            }
            else{
              insertLinearLocation(linearLocationPs, linearLocation)
            }
        }

        val minAddress = addresses.head._1
        val maxAddress = addresses.last._1

        val roadAddress = IncomingRoadAddress(minAddress.roadwayId, minAddress.roadNumber, minAddress.roadPartNumber, minAddress.trackCode, minAddress.startAddressM, maxAddress.endAddressM, reversed = 0, minAddress.startDate,
          None, "import", minAddress.roadType, minAddress.ely, minAddress.validFrom, None, maxAddress.discontinuity, terminated = 0)

        insertRoadAddress(roadAddressPs, roadAddress)
    }

    historyMappedConversionAddresses.mapValues{
      case address =>
        address.sortBy(_.startAddressM).zip(1 to address.size)
    }.foreach{
      case (key, addresses) =>
        val minAddress = addresses.head._1
        val maxAddress = addresses.last._1
        val linkIds = addresses.map(_._1.linkId)
        val currentAddresses = currentConversionAddresses.filter(add => add.roadwayId == minAddress.roadwayId && linkIds.contains(add.linkId)).sortBy(_.startAddressM)
        val isReversed = if(currentAddresses.head.linkId == minAddress.linkId && currentAddresses.head.startM == minAddress.startM) 1 else 0

        val roadAddress = IncomingRoadAddress(minAddress.roadwayId, minAddress.roadNumber, minAddress.roadPartNumber, minAddress.trackCode, minAddress.startAddressM, maxAddress.endAddressM, isReversed, minAddress.startDate,
          minAddress.endDate, "import", minAddress.roadType, minAddress.ely, minAddress.validFrom, None, maxAddress.discontinuity, terminated = 0)

        insertRoadAddress(roadAddressPs, roadAddress)
    }

    linearLocationPs.executeBatch()
    roadAddressPs.executeBatch()
    println(s"${DateTime.now()} - Road addresses saved")
    roadAddressPs.close()
  }

  implicit val getConversionAddress: GetResult[ConversionAddress] = new GetResult[ConversionAddress] {
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
      val roadwayId = r.nextLong()
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
        ConversionAddress(roadNumber, roadPartNumber, trackCode, discontinuity, startAddrM, endAddrM, startM, endM, startDate, viiteEndDate, validFrom, None, ely, roadType, 0,
          linkId, userId, Option(x1), Option(y1), Option(x2), Option(y2), roadwayId, SideCode.TowardsDigitizing, calibrationCode, directionFlag)
      } else {
        //switch startAddrM, endAddrM, the geometry and set the side code to AgainstDigitizing
        ConversionAddress(roadNumber, roadPartNumber, trackCode, discontinuity, endAddrM, startAddrM, startM, endM, startDate, viiteEndDate, validFrom, None, ely, roadType, 0,
          linkId, userId, Option(x2), Option(y2), Option(x1), Option(y1), roadwayId, SideCode.AgainstDigitizing, calibrationCode, directionFlag)
      }
    }
  }
}

