package fi.liikennevirasto.viite.util

import java.sql.{PreparedStatement, Types}

import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import org.joda.time.format.ISODateTimeFormat
import slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.RoadLinkLike
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.dao.CalibrationCode.{AtBeginning, AtBoth, AtEnd}
import fi.liikennevirasto.viite.dao.LinkStatus.Terminated
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Subsequent, Termination}
import fi.liikennevirasto.viite.dao.{CalibrationCode, TerminationCode}
import fi.liikennevirasto.viite.dao.RoadwayPointDAO.RoadwayPoint
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Termination}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.{CalibrationPoint, CalibrationPointType}
import org.joda.time._
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc._


case class ConversionAddress(roadNumber: Long, roadPartNumber: Long, trackCode: Long, discontinuity: Long,
                             startAddressM: Long, endAddressM: Long, startM: Double, endM: Double, startDate: Option[DateTime], endDate: Option[DateTime],
                             validFrom: Option[DateTime], expirationDate: Option[DateTime], ely: Long, roadType: Long,
                             terminated: Long, linkId: Long, userId: String, x1: Option[Double], y1: Option[Double],
                             x2: Option[Double], y2: Option[Double], roadwayNumber: Long, sideCode: SideCode, calibrationCode: CalibrationCode = CalibrationCode.No, directionFlag: Long = 0)

class RoadAddressImporter(conversionDatabase: DatabaseDef, vvhClient: VVHClient, importOptions: ImportOptions) {

  case class IncomingRoadway(roadwayNumber: Long, roadNumber: Long, roadPartNumber: Long, trackCode: Long, startAddrM: Long, endAddrM: Long, reversed: Long,
                             startDate: Option[DateTime], endDate: Option[DateTime], createdBy: String, roadType: Long, ely: Long, validFrom: Option[DateTime], validTo: Option[DateTime], discontinuity: Long, terminated: Long)

  case class IncomingLinearLocation(roadwayNumber: Long, orderNumber: Long, linkId: Long, startMeasure: Double, endMeasure: Double, sideCode: SideCode, linkGeomSource: LinkGeomSource, createdBy: String, x1: Option[Double], y1: Option[Double],
                                    x2: Option[Double], y2: Option[Double], validFrom: Option[DateTime], validTo: Option[DateTime])

  val dateFormatter = ISODateTimeFormat.basicDate()

  def printConversionAddress(r: ConversionAddress): String = {
    s"""linkid: %d, alku: %.2f, loppu: %.2f, tie: %d, aosa: %d, ajr: %d, ely: %d, tietyyppi: %d, jatkuu: %d, aet: %d, let: %d, alkupvm: %s, loppupvm: %s, kayttaja: %s, muutospvm or rekisterointipvm: %s, ajorataId: %s, kalibrointpiste: %s""".
      format(r.linkId, r.startM, r.endM, r.roadNumber, r.roadPartNumber, r.trackCode, r.ely, r.roadType, r.discontinuity, r.startAddressM, r.endAddressM, r.startDate, r.endDate, r.userId, r.validFrom, r.roadwayNumber, r.calibrationCode)
  }

  private def roadwayStatement(): PreparedStatement =
    dynamicSession.prepareStatement(sql = "insert into ROADWAY (id, ROADWAY_NUMBER, road_number, road_part_number, TRACK, start_addr_m, end_addr_m, reversed, start_date, end_date, created_by, road_type, ely, valid_from, valid_to, discontinuity, terminated) " +
      "values (ROADWAY_SEQ.nextval, ?, ?, ?, ?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?, ?)")

  private def linearLocationStatement(): PreparedStatement =
    dynamicSession.prepareStatement(sql = "insert into LINEAR_LOCATION (id, ROADWAY_NUMBER, order_number, link_id, start_measure, end_measure, SIDE, geometry, created_by, valid_from, valid_to) " +
      " values (LINEAR_LOCATION_SEQ.nextval, ?, ?, ?, ?, ?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'))")

  private def roadwayPointStatement(): PreparedStatement = {
    dynamicSession.prepareStatement(sql = "Insert Into ROADWAY_POINT (ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, MODIFIED_BY) " +
      " values (?, ?, ?, ?, ?)")
  }

  private def calibrationPointStatement(): PreparedStatement = {
    dynamicSession.prepareStatement(sql = "Insert Into CALIBRATION_POINT (ID, ROADWAY_POINT_ID, LINK_ID, START_END, TYPE, CREATED_BY) " +
      " values (CALIBRATION_POINT_SEQ.nextval, ?, ?, ?, ?, ?)")
  }

  private def linkStatement(): PreparedStatement = {
    dynamicSession.prepareStatement(sql = "Insert into LINK (ID) values(?)")
  }

  def datePrinter(date: Option[DateTime]): String = {
    date match {
      case Some(dt) => dateFormatter.print(dt)
      case None => ""
    }
  }

  private def insertRoadway(roadwayStatement: PreparedStatement, roadway: IncomingRoadway): Unit = {
    roadwayStatement.setLong(1, roadway.roadwayNumber)
    roadwayStatement.setLong(2, roadway.roadNumber)
    roadwayStatement.setLong(3, roadway.roadPartNumber)
    roadwayStatement.setLong(4, roadway.trackCode)
    roadwayStatement.setLong(5, roadway.startAddrM)
    roadwayStatement.setLong(6, roadway.endAddrM)
    roadwayStatement.setLong(7, roadway.reversed)
    roadwayStatement.setString(8, datePrinter(roadway.startDate))
    roadwayStatement.setString(9, datePrinter(roadway.endDate))
    roadwayStatement.setString(10, roadway.createdBy)
    roadwayStatement.setLong(11, roadway.roadType)
    roadwayStatement.setLong(12, roadway.ely)
    roadwayStatement.setString(13, datePrinter(roadway.validFrom))
    roadwayStatement.setString(14, datePrinter(roadway.validTo))
    roadwayStatement.setLong(15, roadway.discontinuity)
    roadwayStatement.setLong(16, roadway.terminated)
    roadwayStatement.addBatch()
  }

  private def insertLinearLocation(linearLocationStatement: PreparedStatement, linearLocation: IncomingLinearLocation): Unit = {
    linearLocationStatement.setLong(1, linearLocation.roadwayNumber)
    linearLocationStatement.setLong(2, linearLocation.orderNumber)
    linearLocationStatement.setLong(3, linearLocation.linkId)
    linearLocationStatement.setDouble(4, linearLocation.startMeasure)
    linearLocationStatement.setDouble(5, linearLocation.endMeasure)
    linearLocationStatement.setLong(6, linearLocation.sideCode.value)
    linearLocationStatement.setObject(7, OracleDatabase.createJGeometry(Seq(Point(linearLocation.x1.get, linearLocation.y1.get), Point(linearLocation.x2.get, linearLocation.y2.get)), dynamicSession.conn))
    linearLocationStatement.setDouble(8, linearLocation.endMeasure)
    linearLocationStatement.setString(9, linearLocation.createdBy)
    linearLocationStatement.setString(10, datePrinter(linearLocation.validFrom))
    linearLocationStatement.setString(11, datePrinter(linearLocation.validTo))
    linearLocationStatement.addBatch()
  }

  private def insertRoadwayPoint(roadwayPointStatement: PreparedStatement, roadwayPoint: RoadwayPoint): Long = {
    val id = Sequences.nextRoadwayPointId
    roadwayPointStatement.setLong(1, id)
    roadwayPointStatement.setLong(2, roadwayPoint.roadwayNumber)
    roadwayPointStatement.setLong(3, roadwayPoint.addrMValue)
    roadwayPointStatement.setString(4, roadwayPoint.createdBy)
    roadwayPointStatement.setString(5, roadwayPoint.createdBy)
    roadwayPointStatement.addBatch()
    roadwayPointStatement.executeBatch()
    id
  }

  private def insertCalibrationPoint(calibrationPointStatement: PreparedStatement, calibrationPoint: CalibrationPoint) : Unit = {
    calibrationPointStatement.setLong(1, calibrationPoint.roadwayPointId)
    calibrationPointStatement.setLong(2, calibrationPoint.linkId)
    calibrationPointStatement.setLong(3, calibrationPoint.startOrEnd)
    calibrationPointStatement.setLong(4, calibrationPoint.typeCode.value)
    calibrationPointStatement.setString(5, calibrationPoint.createdBy)
    calibrationPointStatement.addBatch()
  }

  private def fetchRoadLinksFromVVH(linkIds: Set[Long]): Map[Long, RoadLinkLike] = {
    val vvhRoadLinkClient = if (importOptions.useFrozenLinkService) vvhClient.frozenTimeRoadLinkData else vvhClient.roadLinkData
    linkIds.grouped(4000).flatMap(group =>
      vvhRoadLinkClient.fetchByLinkIds(group) ++ vvhClient.complementaryData.fetchByLinkIds(group) ++ vvhClient.suravageData.fetchSuravageByLinkIds(group)
    ).toSeq.groupBy(_.linkId).mapValues(_.head)
  }

  private def fetchHistoryRoadLinksFromVVH(linkIds: Set[Long]): Map[Long, RoadLinkLike] =
    vvhClient.historyData.fetchVVHRoadLinkByLinkIds(linkIds).groupBy(_.linkId).mapValues(_.maxBy(_.endDate))


  private def adjustLinearLocation(linearLocation: IncomingLinearLocation, coefficient: Double): IncomingLinearLocation = {
    linearLocation.copy(startMeasure = BigDecimal(linearLocation.startMeasure * coefficient).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble, endMeasure = BigDecimal(linearLocation.endMeasure * coefficient).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble)
  }

  protected def fetchValidAddressesFromConversionTable(minRoadwayNumber: Long, maxRoadwayNumber: Long): Seq[ConversionAddress] = {
    conversionDatabase.withDynSession {
      val tableName = importOptions.conversionTable
      sql"""select tie, aosa, ajr, jatkuu, aet, let, alku, loppu, TO_CHAR(alkupvm, 'YYYY-MM-DD hh:mm:ss'), TO_CHAR(loppupvm, 'YYYY-MM-DD hh:mm:ss'),
           TO_CHAR(muutospvm, 'YYYY-MM-DD hh:mm:ss'), TO_CHAR(lakkautuspvm, 'YYYY-MM-DD hh:mm:ss'),  ely, tietyyppi, linkid, kayttaja, alkux, alkuy, loppux,
           loppuy, ajorataid, kaannetty, alku_kalibrointipiste, loppu_kalibrointipiste from #$tableName
           WHERE aet >= 0 AND let >= 0 AND lakkautuspvm IS NULL AND linkid IN (SELECT linkid FROM  #$tableName where ajorataid > $minRoadwayNumber AND ajorataid <= $maxRoadwayNumber AND  aet >= 0 AND let >= 0) """
        .as[ConversionAddress].list
    }
  }

  protected def fetchAllTerminatedAddressesFromConversionTable(): Seq[ConversionAddress] = {
    conversionDatabase.withDynSession {
      val tableName = importOptions.conversionTable
      sql"""select tie, aosa, ajr, jatkuu, aet, let, alku, loppu, TO_CHAR(alkupvm, 'YYYY-MM-DD hh:mm:ss'), TO_CHAR(loppupvm, 'YYYY-MM-DD hh:mm:ss'),
           TO_CHAR(muutospvm, 'YYYY-MM-DD hh:mm:ss'), TO_CHAR(lakkautuspvm, 'YYYY-MM-DD hh:mm:ss'),  ely, tietyyppi, linkid, kayttaja, alkux, alkuy, loppux,
           loppuy, ajorataid, kaannetty, alku_kalibrointipiste, loppu_kalibrointipiste from #$tableName
           WHERE aet >= 0 AND let >= 0 AND (lakkautuspvm is not null or linkid is null)  """
        .as[ConversionAddress].list
    }
  }

  private def generateChunks(roadwayNumbers: Seq[Long], chunkNumber: Long): Seq[(Long, Long)] = {
    val (chunks, _) = roadwayNumbers.foldLeft((Seq[Long](0), 0)) {
      case ((fchunks, index), roadwayNumber) =>
        if (index > 0 && index % chunkNumber == 0) {
          (fchunks ++ Seq(roadwayNumber), index + 1)
        } else {
          (fchunks, index + 1)
        }
    }
    val result = if (chunks.last == roadwayNumbers.last) {
      chunks
    } else {
      chunks ++ Seq(roadwayNumbers.last)
    }

    result.zip(result.tail)
  }

  protected def fetchChunkRoadwayNumbersFromConversionTable(): Seq[(Long, Long)] = {
    //TODO Try to do the group in the query
    conversionDatabase.withDynSession {
      val tableName = importOptions.conversionTable
      val roadwayNumbers = sql"""select distinct ajorataid from #$tableName where ajorataid is not null order by ajorataid""".as[Long].list
      generateChunks(roadwayNumbers, 1000)
    }
  }

  def importRoadAddress(): Unit = {
    val chunks = fetchChunkRoadwayNumbersFromConversionTable()
    chunks.foreach {
      case (min, max) =>
        print(s"${DateTime.now()} - ")
        println(s"Processing chunk ($min, $max)")
        val conversionAddresses = fetchValidAddressesFromConversionTable(min, max)

        print(s"\n${DateTime.now()} - ")
        println("Read %d rows from conversion database".format(conversionAddresses.size))
        val conversionAddressesFromChunk = conversionAddresses.filter(address => (min + 1 to max).contains(address.roadwayNumber))
        importAddresses(conversionAddressesFromChunk, conversionAddresses)
    }

    val terminatedAddresses = fetchAllTerminatedAddressesFromConversionTable()
    importTerminatedAddresses(terminatedAddresses)
  }

  private def importAddresses(validConversionAddressesInChunk: Seq[ConversionAddress], allConversionAddresses: Seq[ConversionAddress]): Unit = {

    val linkIds = validConversionAddressesInChunk.map(_.linkId).toSet
    print(s"${DateTime.now()} - ")
    println("Total of %d link ids".format(linkIds.size))
    val mappedRoadLinks = fetchRoadLinksFromVVH(linkIds)
    print(s"${DateTime.now()} - ")
    println("Read %d road links from vvh".format(mappedRoadLinks.size))
    val mappedHistoryRoadLinks = fetchHistoryRoadLinksFromVVH(linkIds.filterNot(linkId => mappedRoadLinks.get(linkId).isDefined))
    print(s"${DateTime.now()} - ")
    println("Read %d road links history from vvh".format(mappedHistoryRoadLinks.size))

    val suppressedRoadLinks = validConversionAddressesInChunk.filter(ra => ra.linkId == 0 || (mappedRoadLinks.get(ra.linkId).isEmpty && mappedHistoryRoadLinks.get(ra.linkId).isEmpty))
    suppressedRoadLinks.map(_.roadwayNumber).distinct.foreach {
      roadwayNumber => println(s"Suppressed ROADWAY_NUMBER $roadwayNumber because it contains NULL LINKID values ")
    }

    val groupedLinkCoeffs = allConversionAddresses.filter(_.expirationDate.isEmpty).groupBy(_.linkId).mapValues {
      addresses =>
        val minM = addresses.map(_.startM).min
        val maxM = addresses.map(_.endM).max
        val roadLink = mappedRoadLinks.getOrElse(addresses.head.linkId, mappedHistoryRoadLinks(addresses.head.linkId))
        GeometryUtils.geometryLength(roadLink.geometry) / (maxM - minM)
    }
    val (currentConversionAddresses, historyConversionAddresses) = validConversionAddressesInChunk.filterNot(ca => suppressedRoadLinks.map(_.roadwayNumber).distinct.contains(ca.roadwayNumber)).partition(_.endDate.isEmpty)
    val currentMappedConversionAddresses = currentConversionAddresses.groupBy(ra => (ra.roadwayNumber, ra.roadNumber, ra.roadPartNumber, ra.trackCode, ra.startDate, ra.endDate))
    val historyMappedConversionAddresses = historyConversionAddresses.groupBy(ra => (ra.roadwayNumber, ra.roadNumber, ra.roadPartNumber, ra.trackCode, ra.startDate, ra.endDate))
    val roadwayPs = roadwayStatement()
    val linearLocationPs = linearLocationStatement()
    val roadwayPointPs = roadwayPointStatement()
    val calibrationPointPs = calibrationPointStatement()
    val linkPs = linkStatement()
    insertLinks(linkPs, linkIds)
    currentMappedConversionAddresses.mapValues {
      case address =>
        address.sortBy(_.startAddressM).zip(1 to address.size)
    }.foreach {
      case (key, addresses) =>
        addresses.foreach {
          //add current linear locations
          add =>
            val converted = add._1
            val roadLink = mappedRoadLinks.getOrElse(converted.linkId, mappedHistoryRoadLinks(converted.linkId))
            val startCalibrationPoint = getStartCalibrationPoint(converted)
            val endCalibrationPoint = getEndCalibrationPoint(converted)
            handlePoints(roadwayPointPs, calibrationPointPs, startCalibrationPoint, endCalibrationPoint)

            val linearLocation = adjustLinearLocation(IncomingLinearLocation(converted.roadwayNumber, add._2, converted.linkId, converted.startM, converted.endM, converted.sideCode, roadLink.linkSource, createdBy = "import",
              converted.x1, converted.y1, converted.x2, converted.y2, converted.validFrom, None), groupedLinkCoeffs(converted.linkId))
            if (add._1.directionFlag == 1) {
              val revertedDirectionLinearLocation = linearLocation.copy(sideCode = SideCode.switch(linearLocation.sideCode))
              insertLinearLocation(linearLocationPs, revertedDirectionLinearLocation)
            } else {
              insertLinearLocation(linearLocationPs, linearLocation)
            }
        }

        val minAddress = addresses.head._1
        val maxAddress = addresses.last._1

        val roadAddress = IncomingRoadway(minAddress.roadwayNumber, minAddress.roadNumber, minAddress.roadPartNumber, minAddress.trackCode, minAddress.startAddressM, maxAddress.endAddressM, reversed = 0, minAddress.startDate,
          None, "import", minAddress.roadType, minAddress.ely, minAddress.validFrom, None, maxAddress.discontinuity, terminated = NoTermination.value)

        insertRoadway(roadwayPs, roadAddress)
    }

    historyMappedConversionAddresses.mapValues {
      case address =>
        address.sortBy(_.startAddressM).zip(1 to address.size)
    }.foreach {
      case (key, addresses) =>
        val minAddress = addresses.head._1
        val maxAddress = addresses.last._1
        val linkIds = addresses.map(_._1.linkId)
        val currentAddresses = currentConversionAddresses.filter(add => add.roadwayNumber == minAddress.roadwayNumber && linkIds.contains(add.linkId)).sortBy(_.startAddressM)
        val isReversed = if (currentAddresses.head.linkId == minAddress.linkId && currentAddresses.head.startM == minAddress.startM) 1 else 0

        val roadAddress = IncomingRoadway(minAddress.roadwayNumber, minAddress.roadNumber, minAddress.roadPartNumber, minAddress.trackCode, minAddress.startAddressM, maxAddress.endAddressM, isReversed, minAddress.startDate,
          minAddress.endDate, "import", minAddress.roadType, minAddress.ely, minAddress.validFrom, None, maxAddress.discontinuity, terminated = NoTermination.value)

        insertRoadway(roadwayPs, roadAddress)
    }
    calibrationPointPs.executeBatch()
    linearLocationPs.executeBatch()
    roadwayPs.executeBatch()
    println(s"${DateTime.now()} - Roadways saved")
    calibrationPointPs.close()
    linkPs.close()
    linearLocationPs.close()
    roadwayPs.close()
  }

  private def handlePoints(roadwayPointPs: PreparedStatement, calibrationPointPs: PreparedStatement, startCalibrationPoint: Option[(RoadwayPoint, CalibrationPoint)], endCalibrationPoint: Option[(RoadwayPoint, CalibrationPoint)]): Unit = {
    if(startCalibrationPoint.isDefined && startCalibrationPoint.get._1.id == NewRoadwayPointId){
      val roadwayPointId = insertRoadwayPoint(roadwayPointPs, startCalibrationPoint.get._1)
      insertCalibrationPoint(calibrationPointPs, startCalibrationPoint.get._2.copy(roadwayPointId = roadwayPointId))
    }
    else if(startCalibrationPoint.isDefined && startCalibrationPoint.get._1.id != NewRoadwayPointId && startCalibrationPoint.get._2.id == NewCalibrationPointId){
      insertCalibrationPoint(calibrationPointPs, startCalibrationPoint.get._2)
    }
    if(endCalibrationPoint.isDefined && endCalibrationPoint.get._1.id == NewRoadwayPointId){
      val roadwayPointId = insertRoadwayPoint(roadwayPointPs, endCalibrationPoint.get._1)
      insertCalibrationPoint(calibrationPointPs, endCalibrationPoint.get._2.copy(roadwayPointId = roadwayPointId))
    }
    else if(endCalibrationPoint.isDefined && endCalibrationPoint.get._1.id != NewRoadwayPointId && endCalibrationPoint.get._2.id == NewCalibrationPointId){
      insertCalibrationPoint(calibrationPointPs, endCalibrationPoint.get._2)
    }
  }

  private def insertLinks(statement: PreparedStatement, links: Set[Long]): Unit = {
    links.foreach{
      link =>
        if(LinkDAO.fetch(link).isEmpty){
          statement.setLong(1, link)
          statement.addBatch()
        }
    }
    statement.executeBatch()
  }

  private def importTerminatedAddresses(terminatedConversionAddresses: Seq[ConversionAddress]): Unit = {
    val (terminatedOldConversionAddresses, validTerminatedConversionAddresses) = terminatedConversionAddresses.partition(_.expirationDate.isEmpty)
    val validTerminatedMappedConversionAddresses = validTerminatedConversionAddresses.groupBy(ra => (ra.roadwayNumber, ra.roadNumber, ra.roadPartNumber, ra.trackCode, ra.startDate))
    val terminatedOldMappedConversionAddresses = terminatedOldConversionAddresses.groupBy(ra => (ra.roadwayNumber, ra.roadNumber, ra.roadPartNumber, ra.trackCode, ra.startDate, ra.endDate))
    val roadwayPs = roadwayStatement()

    validTerminatedMappedConversionAddresses.mapValues {
      case address =>
        address.sortBy(_.startAddressM).zip(1 to address.size)
    }.foreach {
      case (key, addresses) =>
        val minAddress = addresses.head._1
        val maxAddress = addresses.last._1

        val roadAddress = IncomingRoadway(minAddress.roadwayNumber, minAddress.roadNumber, minAddress.roadPartNumber, minAddress.trackCode, minAddress.startAddressM, maxAddress.endAddressM, reversed = 0, minAddress.startDate,
          minAddress.startDate, "import", minAddress.roadType, minAddress.ely, minAddress.validFrom, None, maxAddress.discontinuity, terminated = Termination.value)

        insertRoadway(roadwayPs, roadAddress)
    }

    terminatedOldMappedConversionAddresses.mapValues {
      case address =>
        address.sortBy(_.startAddressM).zip(1 to address.size)
    }.foreach {
      case (key, addresses) =>
        val minAddress = addresses.head._1
        val maxAddress = addresses.last._1

        val roadway = IncomingRoadway(minAddress.roadwayNumber, minAddress.roadNumber, minAddress.roadPartNumber, minAddress.trackCode, minAddress.startAddressM, maxAddress.endAddressM, reversed = 0, minAddress.startDate,
          minAddress.endDate, "import", minAddress.roadType, minAddress.ely, minAddress.validFrom, minAddress.expirationDate, maxAddress.discontinuity, terminated = NoTermination.value)

        insertRoadway(roadwayPs, roadway)
    }
    roadwayPs.executeBatch()
    roadwayPs.close()
  }


  private def getStartCalibrationPoint(convertedAddress: ConversionAddress): Option[(RoadwayPoint, CalibrationPoint)] = {
    convertedAddress.calibrationCode match {
      case AtBeginning | AtBoth =>
        {
          val existingRoadwayPoint = RoadwayPointDAO.fetch(convertedAddress.roadwayNumber, convertedAddress.startAddressM)
          existingRoadwayPoint match {
            case Some(x) =>
              val existingCalibrationPoint = CalibrationPointDAO.fetchByRoadwayPoint(x.id)
              if(existingCalibrationPoint.isDefined)
                Some((existingRoadwayPoint.get, existingCalibrationPoint.get))
              else
                Some((existingRoadwayPoint.get, CalibrationPoint(NewCalibrationPointId, x.id, convertedAddress.linkId, x.roadwayNumber, x.addrMValue, 0, CalibrationPointType.Mandatory, createdBy = "import" )))
            case _ =>
              Some(RoadwayPoint(NewRoadwayPointId, convertedAddress.roadwayNumber, convertedAddress.startAddressM, "import"), CalibrationPoint(NewCalibrationPointId, NewRoadwayPointId, convertedAddress.linkId, convertedAddress.roadwayNumber, convertedAddress.startAddressM, 0, CalibrationPointType.Mandatory, createdBy = "import" ))
          }
        }
      case _ => None
    }
  }

  private def getEndCalibrationPoint(convertedAddress: ConversionAddress): Option[(RoadwayPoint, CalibrationPoint)] = {
    convertedAddress.calibrationCode match {
      case AtEnd | AtBoth =>
      {
        val existingRoadwayPoint = RoadwayPointDAO.fetch(convertedAddress.roadwayNumber, convertedAddress.endAddressM)
        existingRoadwayPoint match {
          case Some(x) =>
            val existingCalibrationPoint = CalibrationPointDAO.fetchByRoadwayPoint(x.id)
            if(existingCalibrationPoint.isDefined)
              Some((existingRoadwayPoint.get, existingCalibrationPoint.get))
            else
              Some((existingRoadwayPoint.get, CalibrationPoint(NewCalibrationPointId, x.id, convertedAddress.linkId, x.roadwayNumber, x.addrMValue, 1, CalibrationPointType.Mandatory, createdBy = "import" )))
          case _ =>
            Some(RoadwayPoint(NewRoadwayPointId, convertedAddress.roadwayNumber, convertedAddress.endAddressM, "import"), CalibrationPoint(NewCalibrationPointId, NewRoadwayPointId, convertedAddress.linkId, convertedAddress.roadwayNumber, convertedAddress.endAddressM, 1, CalibrationPointType.Mandatory, createdBy = "import" ))
        }
      }
      case _ => None
    }
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
      val expirationDate = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val ely = r.nextLong()
      val roadType = r.nextLong()
      val linkId = r.nextLong()
      val userId = r.nextString
      val x1 = r.nextDouble()
      val y1 = r.nextDouble()
      val x2 = r.nextDouble()
      val y2 = r.nextDouble()
      val roadwayNumber = r.nextLong()
      val directionFlag = r.nextLong()
      val startCalibrationPoint = r.nextLong()
      val endCalibrationPoint = r.nextLong()


      def getCalibrationCode(startCalibrationPoint: Long, endCalibrationPoint: Long, startAddrM: Long, endAddrM: Long): CalibrationCode = {
        if (startAddrM < endAddrM) {
          (startCalibrationPoint, endCalibrationPoint) match {
            case (1, 1) => CalibrationCode.AtBoth
            case (1, 0) => CalibrationCode.AtBeginning
            case (0, 1) => CalibrationCode.AtEnd
            case _ => CalibrationCode.No
          }
        } else {
          (startCalibrationPoint, endCalibrationPoint) match {
            case (1, 1) => CalibrationCode.AtBoth
            case (1, 0) => CalibrationCode.AtEnd
            case (0, 1) => CalibrationCode.AtBeginning
            case _ => CalibrationCode.No
          }
        }
      }

      if (startAddrM < endAddrM) {
        ConversionAddress(roadNumber, roadPartNumber, trackCode, discontinuity, startAddrM, endAddrM, startM, endM, startDate, endDateOption, validFrom, expirationDate, ely, roadType, 0,
          linkId, userId, Option(x1), Option(y1), Option(x2), Option(y2), roadwayNumber, SideCode.TowardsDigitizing, getCalibrationCode(startCalibrationPoint, endCalibrationPoint, startAddrM, endAddrM), directionFlag)
      } else {
        //switch startAddrM, endAddrM and set the side code to AgainstDigitizing
        ConversionAddress(roadNumber, roadPartNumber, trackCode, discontinuity, endAddrM, startAddrM, startM, endM, startDate, endDateOption, validFrom, expirationDate, ely, roadType, 0,
          linkId, userId, Option(x1), Option(y1), Option(x2), Option(y2), roadwayNumber, SideCode.AgainstDigitizing, getCalibrationCode(startCalibrationPoint, endCalibrationPoint, startAddrM, endAddrM), directionFlag)
      }
    }
  }
}

