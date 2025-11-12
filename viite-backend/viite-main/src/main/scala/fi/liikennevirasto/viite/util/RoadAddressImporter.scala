package fi.liikennevirasto.viite.util

import fi.liikennevirasto.digiroad2.client.kgv.{HistoryRoadLink, KgvRoadLink}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.{CalibrationCode, RoadwayPoint, TerminationCode, _}
import fi.liikennevirasto.viite.dao.CalibrationCode.{AtBeginning, AtBoth, AtEnd}
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Subsequent, Termination}
import fi.vaylavirasto.viite.dao.{BaseDAO, LinkDAO, Sequences}
import fi.vaylavirasto.viite.geometry.GeometryUtils
import fi.vaylavirasto.viite.model.{AddrMRange, CalibrationPoint, CalibrationPointLocation, CalibrationPointType, LinkGeomSource, RoadLinkLike, RoadPart, SideCode}
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet



case class ConversionAddress(roadPart: RoadPart, trackCode: Long, discontinuity: Long, addrMRange: AddrMRange, startM: Double, endM: Double, startDate: Option[DateTime], endDate: Option[DateTime], validFrom: Option[DateTime], expirationDate: Option[DateTime], ely: Long, administrativeClass: Long, terminated: Long, linkId: String, userId: String, x1: Option[Double], y1: Option[Double], x2: Option[Double], y2: Option[Double], roadwayNumber: Long, sideCode: SideCode, calibrationCode: CalibrationCode = CalibrationCode.No, directionFlag: Long = 0)

class RoadAddressImporter(KGVClient: KgvRoadLink, importOptions: ImportOptions) extends BaseDAO {

  case class IncomingRoadway(roadwayNumber: Long, roadPart: RoadPart, trackCode: Long, addrMRange: AddrMRange, reversed: Long, startDate: Option[DateTime], endDate: Option[DateTime], createdBy: String, administrativeClass: Long, ely: Long, validFrom: Option[DateTime], validTo: Option[DateTime], discontinuity: Long, terminated: Long)

  case class IncomingLinearLocation(roadwayNumber: Long, orderNumber: Long, linkId: String, startMeasure: Double, endMeasure: Double, sideCode: SideCode, linkGeomSource: LinkGeomSource, createdBy: String, x1: Option[Double], y1: Option[Double], x2: Option[Double], y2: Option[Double], validFrom: Option[DateTime], validTo: Option[DateTime])

  val roadwayPointDAO = new RoadwayPointDAO

  def runWithConversionDbReadOnlySession[T](f: => T): T = DataImporter.Conversion.runWithConversionDbReadOnlySession(f)

  /** Main import process for road addresses.
   * The process includes:
   * 1. Fetching and mapping road links from KGV (both current and historical)
   * 2. Updating links to main database
   */
  def importRoadAddress(): Unit = {
    // fetch all roadway numbers from conversion database
    val chunks = runWithConversionDbReadOnlySession {
      fetchChunkRoadwayNumbersFromConversionTable()
    }
    chunks.foreach {
      case (min, max) =>
        print(s"\n${DateTime.now()} - ")
        println(s"Processing chunk ($min, $max)")

        val conversionAddresses = runWithConversionDbReadOnlySession {
          fetchValidAddressesFromConversionTable(min, max)
        }

        print(s"${DateTime.now()} - ")
        println("Read %d rows from conversion database".format(conversionAddresses.size))
        val conversionAddressesFromChunk = conversionAddresses.filter(address => (min + 1 to max).contains(address.roadwayNumber))
        importAddresses(conversionAddressesFromChunk, conversionAddresses)
    }

    // fetch all terminated addresses from conversion database
    val terminatedAddresses = runWithConversionDbReadOnlySession {
      fetchAllTerminatedAddressesFromConversionTable()
    }

    importTerminatedAddresses(terminatedAddresses)
  }

  /** Imports road addresses from conversion database to the main database.
   *
   * The process includes:
   * 1. Fetching and mapping road links from KGV (both current and historical)
   * 2. Updating links to main database
   * 3. Processing conversion addresses:
   *    - Filtering out suppressed road links
   *    - Calculating scaling coefficients for link lengths
   *    - Splitting addresses into current and historical
   * 4. Collecting parameters for batch updates:
   *    - Linear locations with adjusted measurements
   *    - Calibration points with associated roadway points are updated immediately
   *    - Roadways from both current and historical addresses
   *  5. Executing batch updates:
   *    - Linear locations
   *    - Roadways
   *
   * @param validConversionAddressesInChunk Addresses to be processed from the current chunk
   * @param allConversionAddresses          All addresses needed for coefficient calculations
   */
  def importAddresses(validConversionAddressesInChunk: Seq[ConversionAddress], allConversionAddresses: Seq[ConversionAddress]): Unit = {

    // get link ids from conversion table
    val linkIds = validConversionAddressesInChunk.map(_.linkId).toSet
    print(s"${DateTime.now()} - ")
    println("Total of %d link ids".format(linkIds.size))

    // fetch road links from KGV
    val mappedRoadLinks = fetchRoadLinksFromKGV(linkIds)
    print(s"${DateTime.now()} - ")
    println("Read %d road links from vvh".format(mappedRoadLinks.size))

    // Batch update links to the main database
    batchUpdateLinksToDb(mappedRoadLinks.values)

    // Find addresses where:
    // - linkId is 0
    // - or the linkId doesn't exist in current (mappedRoadLinks) or historical (mappedHistoryRoadLinks) road links
    val suppressedRoadLinks = validConversionAddressesInChunk.filter(ra => ra.linkId == 0 || mappedRoadLinks.get(ra.linkId).isEmpty)
    suppressedRoadLinks.map(_.roadwayNumber).distinct.foreach {
      roadwayNumber => println(s"Suppressed ROADWAY_NUMBER $roadwayNumber because it contains NULL LINKID values ")
    }

    // Filter addresses to only include those with linkIds in mappedRoadLinks
    val validLinkAddresses = allConversionAddresses
      .filter(a => a.expirationDate.isEmpty && mappedRoadLinks.contains(a.linkId))
      .groupBy(_.linkId)

    // Calculate scaling coefficient between measured length and actual geometry length for each link
    val groupedLinkCoeffs = validLinkAddresses.map { case (linkId, addresses) =>
      val minM = addresses.map(_.startM).min
      val maxM = addresses.map(_.endM).max
      val roadLink = mappedRoadLinks(linkId)
      linkId -> (GeometryUtils.geometryLength(roadLink.geometry) / (maxM - minM))
    }

    // Split addresses into current and history, excluding suppressed roadways.
    // Then group both by (roadway number, part, track, dates)
    val (currentConversionAddresses, historyConversionAddresses) = validConversionAddressesInChunk
      .filterNot(ca => suppressedRoadLinks.map(_.roadwayNumber).distinct.contains(ca.roadwayNumber))
      .partition(_.endDate.isEmpty)
    val currentMappedConversionAddresses = currentConversionAddresses.groupBy(ra => (ra.roadwayNumber, ra.roadPart, ra.trackCode, ra.startDate, ra.endDate))
    val historyMappedConversionAddresses = historyConversionAddresses.groupBy(ra => (ra.roadwayNumber, ra.roadPart, ra.trackCode, ra.startDate, ra.endDate))

    // First collect all parameters from current addresses by mapping each group to linear locations and roadways
    val (currentLinearLocationParams, currentRoadwayParams) =
      currentMappedConversionAddresses.map { case (_, addresses) =>
        // Sort addresses by start measure
        val sortedAddresses = addresses.sortBy(_.addrMRange.start).zip(1 to addresses.size)

        // Collect all linear locations first
        val linearLocationParams = sortedAddresses.map { case (converted, index) =>
          // Check if the link exists in mappedRoadLinks first
          mappedRoadLinks.get(converted.linkId).map { roadLink =>
            val linearLocation = adjustLinearLocation(
              IncomingLinearLocation(
                converted.roadwayNumber, index, converted.linkId, converted.startM, converted.endM,
                converted.sideCode, roadLink.linkSource, createdBy = "import",
                converted.x1, converted.y1, converted.x2, converted.y2, converted.validFrom, None
              ),
              groupedLinkCoeffs(converted.linkId))

            // If the direction flag is 1, reverse the side code
            if (converted.directionFlag == 1) {
              val revertedDirectionLinearLocation = linearLocation.copy(sideCode = SideCode.switch(linearLocation.sideCode))
              createLinearLocationParams(revertedDirectionLinearLocation)
            } else {
              createLinearLocationParams(linearLocation)
            }
          }.getOrElse(Seq.empty) // Return empty sequence if roadLink not found
        }

        // Handle calibration points and needed roadway points
        sortedAddresses.foreach { case (converted, _) =>
          val startCalibrationPoint = getStartCalibrationPoint(converted)
          val endCalibrationPoint = getEndCalibrationPoint(converted)
          handlePoints(startCalibrationPoint, endCalibrationPoint)
        }


        // Create roadway params
        val minAddress = sortedAddresses.head._1
        val maxAddress = sortedAddresses.last._1
        val addrMRange = AddrMRange(minAddress.addrMRange.start, maxAddress.addrMRange.end)
        val roadAddress = IncomingRoadway(
          minAddress.roadwayNumber, minAddress.roadPart, minAddress.trackCode, addrMRange, reversed = 0, minAddress.startDate,
          None, "import", minAddress.administrativeClass, minAddress.ely, minAddress.validFrom, None, maxAddress.discontinuity, terminated = NoTermination.value
        )
        val roadwayParams = createRoadwayParams(roadAddress)

        (linearLocationParams, roadwayParams)
      }.unzip match {
        case (linear, roadway) =>
          (linear.flatten.toSeq, roadway.toSeq)
      }

    // History roadways
    val historyRoadwaysParams = historyMappedConversionAddresses.map { case (_, addresses) =>
      val sortedAddresses = addresses.sortBy(_.addrMRange.start)
      val minAddress = sortedAddresses.head
      val maxAddress = sortedAddresses.last
      val addrRange = AddrMRange(minAddress.addrMRange.start, maxAddress.addrMRange.end)
      val roadAddress = IncomingRoadway(
        minAddress.roadwayNumber, minAddress.roadPart, minAddress.trackCode, addrRange, minAddress.directionFlag, minAddress.startDate, minAddress.endDate,
        "import", minAddress.administrativeClass, minAddress.ely, minAddress.validFrom, None, maxAddress.discontinuity, terminated = NoTermination.value
      )
      createRoadwayParams(roadAddress)
    }

    val allRoadwayParams = currentRoadwayParams ++ historyRoadwaysParams

    batchUpdateLinearLocations(currentLinearLocationParams)
    batchUpdateRoadways(allRoadwayParams)
  }

  def importTerminatedAddresses(terminatedConversionAddresses: Seq[ConversionAddress]): Unit = {
    // Group terminated addresses by roadway number
    val roadways = terminatedConversionAddresses.groupBy(t => t.roadwayNumber)

    // Collect all roadway parameters
    val roadwayParams = roadways.flatMap { case (_ /*roadwayNumber*/ , roadways) =>
      // Sort roadways by start date
      val sorted = roadways.sortBy(-_.startDate.get.getMillis)

      // Split into terminated and subsequent roadways
      // Terminated roadway is the first in the sorted list
      val terminated = sorted.head

      // Subsequent roadways are the rest
      val subsequent = if (roadways.size > 1) sorted.tail else Seq()

      // Create params for terminated and subsequent roadways
      val terminatedParams = createRoadwayParams(
        createIncomingRoadway(
          terminated.copy(endDate = Some(terminated.endDate.get.plusDays(1))), Termination)
      )

      // Then params for all subsequent roadways
      val subsequentParams = subsequent.map(roadway =>
        createRoadwayParams(
          createIncomingRoadway(roadway, Subsequent)
        )
      )

      // Combine all params for this roadway group
      terminatedParams +: subsequentParams
    }

    // Do batch update if we have any roadways
    if (roadwayParams.nonEmpty) {
  batchUpdateRoadways(roadwayParams.toSeq)

    }
  }

  private def fetchRoadLinksFromKGV(linkIds: Set[String]): Map[String, RoadLinkLike] = {
    val KGVRoadLinkClient = if (importOptions.useFrozenLinkService) KGVClient.frozenTimeRoadLinkData else KGVClient.roadLinkData
    linkIds.grouped(4000).flatMap(group =>
      KGVRoadLinkClient.fetchByLinkIds(group) ++ KGVClient.complementaryData.fetchByLinkIdsInReadOnlySession(group)
    ).toSeq.groupBy(_.linkId).mapValues(_.head)
  }

  private def batchUpdateLinksToDb(links: Iterable[RoadLinkLike]): Unit = {

    val linkBatchSql =
      sql"""
        INSERT INTO link (id, source, adjusted_timestamp)
        VALUES(?, ?, ?)
     """

    // Filter out links that already exist in the main database
    val existingLinkIds = LinkDAO.fetchByLinkIds(links.map(_.linkId).toSet).map(_.id) // find existing links
    val newLinks = links.filterNot(link => existingLinkIds.contains(link.linkId)) // filter out existing links

    // If we have links to insert, prepare the batch params
    if (newLinks.nonEmpty) {
      val batchParams = newLinks.map { link =>
        Seq(
          link.linkId,
          link.linkSource.value,
          link.roadLinkTimeStamp
        )
      }

      runBatchUpdateToDb(linkBatchSql, batchParams.toSeq)
    }
  }

  /* Added road type to administrative class value conversion */
  def fetchValidAddressesFromConversionTable(minRoadwayNumber: Long, maxRoadwayNumber: Long): Seq[ConversionAddress] = {

    val tableName = importOptions.conversionTable
    // The table name String needs to be converted to SQLSyntax to be used in the query
    val table: SQLSyntax = SQLSyntax.createUnsafely(tableName) // This is safe because the table name is not user input
    val query =
      sql"""
           SELECT tie, aosa, ajr, jatkuu, aet, let, alku, loppu,
             TO_CHAR(alkupvm,      'YYYY-MM-DD hh:mm:ss') AS alkupvm,
             TO_CHAR(loppupvm,     'YYYY-MM-DD hh:mm:ss') AS loppupvm,
             TO_CHAR(muutospvm,    'YYYY-MM-DD hh:mm:ss') AS muutospvm,
             TO_CHAR(lakkautuspvm, 'YYYY-MM-DD hh:mm:ss') AS lakkautuspvm,
             ely,
           CASE tietyyppi
             WHEN 1 THEN 1
             WHEN 2 THEN 1
             WHEN 3 THEN 2
             WHEN 4 THEN 1
             WHEN 5 THEN 3
             WHEN 9 THEN 3
             ELSE 3
           END AS tietyyppi,
           linkid, kayttaja, alkux, alkuy, loppux,
           loppuy, ajorataid, kaannetty, alku_kalibrointipiste, loppu_kalibrointipiste
           FROM $table
           WHERE aet >= 0 AND let >= 0 AND lakkautuspvm IS NULL
             AND linkid IN (
                 SELECT linkid FROM  $table
                  WHERE ajorataid > $minRoadwayNumber AND ajorataid <= $maxRoadwayNumber
                    AND aet >= 0 AND let >= 0
                    )
          """
    runSelectQuery(query.map(ConversionAddress.apply))
  }

  /**
   * Fetches all addresses from the conversion table that have been terminated.
   * Added road type to administrative class value conversion
   */
  def fetchAllTerminatedAddressesFromConversionTable(): Seq[ConversionAddress] = {
    val tableName = importOptions.conversionTable
    // The table name String needs to be converted to SQLSyntax to be used in the query
    val table = SQLSyntax.createUnsafely(tableName) // This is safe because the table name is not user input
    val query =
      sql"""
           SELECT tie, aosa, ajr, jatkuu, aet, let, alku, loppu, TO_CHAR(alkupvm, 'YYYY-MM-DD hh:mm:ss') as alkupvm, TO_CHAR(loppupvm, 'YYYY-MM-DD hh:mm:ss') as loppupvm,
           TO_CHAR(muutospvm, 'YYYY-MM-DD hh:mm:ss') as muutospvm, null as lakkautuspvm, ely,
           CASE tietyyppi
             WHEN 1 THEN 1
             WHEN 2 THEN 1
             WHEN 3 THEN 2
             WHEN 4 THEN 1
             WHEN 5 THEN 3
             WHEN 9 THEN 3
             ELSE 3
           END AS tietyyppi,
           linkid, kayttaja, alkux, alkuy, loppux,
           loppuy, ajorataid, kaannetty, alku_kalibrointipiste, loppu_kalibrointipiste
           FROM $table
           WHERE aet >= 0 AND let >= 0 AND linkid is null AND lakkautuspvm is null
           """

    runSelectQuery(query.map(ConversionAddress.apply))
  }

  // Roadway handling methods

  private def createRoadwayParams(roadway: IncomingRoadway): Seq[Any] = {
    // return the parameters for the batch update
    Seq(
      roadway.roadwayNumber,
      roadway.roadPart.roadNumber,
      roadway.roadPart.partNumber,
      roadway.trackCode,
      roadway.addrMRange.start,
      roadway.addrMRange.end,
      roadway.reversed,
      roadway.startDate,
      roadway.endDate,
      roadway.createdBy,
      roadway.administrativeClass,
      roadway.ely,
      roadway.validFrom,
      roadway.validTo,
      roadway.discontinuity,
      roadway.terminated
    )
  }

  private def batchUpdateRoadways(params: Seq[Seq[Any]]): List[Int] = {
    val insertQuery =
      sql"""
        INSERT INTO roadway (id, roadway_number, road_number, road_part_number, track, start_addr_m,
          end_addr_m, reversed, start_date, end_date, created_by, administrative_class, ely,
          valid_from, valid_to, discontinuity, terminated)
        VALUES (nextval('ROADWAY_SEQ'), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

    runBatchUpdateToDb(insertQuery, params)
  }

  private def createIncomingRoadway(r: ConversionAddress, terminated: TerminationCode): IncomingRoadway = {
    IncomingRoadway(
      r.roadwayNumber, r.roadPart, r.trackCode, r.addrMRange, reversed = 0, r.startDate, r.endDate, "import",
      r.administrativeClass, r.ely, r.validFrom, r.expirationDate, r.discontinuity, terminated = terminated.value
    )
  }

  // Generate chunks of roadway numbers to process in batches
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

  // Fetch roadway numbers from conversion table to process in chunks
def fetchChunkRoadwayNumbersFromConversionTable(): Seq[(Long, Long)] = {
    //TODO Try to do the group in the query
    val tableName = importOptions.conversionTable
    val table = SQLSyntax.createUnsafely(tableName)
    val query =
      sql"""
             SELECT DISTINCT ajorataid
             FROM $table
             WHERE ajorataid IS NOT NULL
             ORDER BY ajorataid
             """
    val roadwayNumbers = runSelectQuery(query.map(_.long(1)))
    generateChunks(roadwayNumbers, 1000)
  }

  // Linear location handling methods

  private def createLinearLocationParams(linearLocation: IncomingLinearLocation): Seq[Any] = {
    // return the parameters for the batch update
    Seq(
      linearLocation.roadwayNumber,
      linearLocation.orderNumber,
      linearLocation.linkId,
      linearLocation.startMeasure,
      linearLocation.endMeasure,
      linearLocation.sideCode.value,
      linearLocation.x1.get,
      linearLocation.y1.get,
      linearLocation.x2.get,
      linearLocation.y2.get,
      linearLocation.endMeasure,
      linearLocation.createdBy,
      linearLocation.validFrom.orNull,
      linearLocation.validTo.orNull
    )
  }

  private def batchUpdateLinearLocations(params: Seq[Seq[Any]]): List[Int] = {
    val linearLocationBatchSql =
      sql"""
        INSERT INTO linear_location (id, roadway_number, order_number, link_id, start_measure, end_measure, side,
          geometry, created_by, valid_from, valid_to)
        VALUES (nextval('LINEAR_LOCATION_SEQ'), ?, ?, ?, ?, ?, ?, ST_GeomFromText('LINESTRING('||?||' '||?||' 0.0 0.0, '||?||' '||?||' 0.0 '||?||')', 3067), ?, ?, ?)
        """

    runBatchUpdateToDb(linearLocationBatchSql, params)
  }

  private def adjustLinearLocation(linearLocation: IncomingLinearLocation, coefficient: Double): IncomingLinearLocation = {
    linearLocation.copy(startMeasure = BigDecimal(linearLocation.startMeasure * coefficient).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble, endMeasure = BigDecimal(linearLocation.endMeasure * coefficient).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble)
  }

  // Calibration Point and Roadway Point handling methods

  /** Handle insertion of roadway points and collect calibration point parameters for batch insertion.
   * If roadway point is new, it needs to be inserted immediately to get its id for the calibration point.
   * Returns calibration point parameters for later batch insertion.
   *
   * @return Sequence of calibration point parameters for batch insert
   */
  private def handlePoints(startPoint: Option[(RoadwayPoint, CalibrationPoint)],
                           endPoint: Option[(RoadwayPoint, CalibrationPoint)]): Unit = {
    // Handle a single point
    def handlePoint(point: Option[(RoadwayPoint, CalibrationPoint)]): Unit  = {
      point match {
        // For new calibration points that need new roadway point:
        // 1. Insert roadway point and get the id
        // 2. Insert calibration point with the new id to db
        case Some((roadwayPoint, calibrationPoint)) if roadwayPoint.isNew =>
          val roadwayPointId = insertRoadwayPointToDb(roadwayPoint) // Insert roadway point and get id
          insertCalibrationPointToDb(calibrationPoint.copy(roadwayPointId = roadwayPointId))

        // For new calibration points with existing roadway points:
        // Just insert the calibration point to db
        case Some((roadwayPoint, calibrationPoint))
          if roadwayPoint.isNotNew && calibrationPoint.id == NewIdValue =>
          insertCalibrationPointToDb(calibrationPoint)

        case _=> // Do nothing
      }
    }
    handlePoint(startPoint)
    handlePoint(endPoint)
  }

  private def getStartCalibrationPoint(convertedAddress: ConversionAddress): Option[(RoadwayPoint, CalibrationPoint)] = {
    convertedAddress.calibrationCode match {
      case AtBeginning | AtBoth =>
        val existingRoadwayPoint = roadwayPointDAO.fetch(convertedAddress.roadwayNumber, convertedAddress.addrMRange.start)
        existingRoadwayPoint match {
          case Some(x) =>
            val existingCalibrationPoint = CalibrationPointDAO.fetchByRoadwayPointId(x.id).find(_.startOrEnd == CalibrationPointLocation.StartOfLink)
            if (existingCalibrationPoint.isDefined)
              Some((existingRoadwayPoint.get, existingCalibrationPoint.get))
            else
              Some((existingRoadwayPoint.get, CalibrationPoint(NewIdValue, x.id, convertedAddress.linkId, x.roadwayNumber, x.addrMValue, CalibrationPointLocation.StartOfLink, CalibrationPointType.RoadAddressCP, createdBy = "import")))
          case _ =>
            Some(RoadwayPoint(NewIdValue, convertedAddress.roadwayNumber, convertedAddress.addrMRange.start, "import"),
              CalibrationPoint(NewIdValue, NewIdValue, convertedAddress.linkId, convertedAddress.roadwayNumber, convertedAddress.addrMRange.start, CalibrationPointLocation.StartOfLink, CalibrationPointType.RoadAddressCP, createdBy = "import"))
        }
      case _ => None
    }
  }

  private def getEndCalibrationPoint(convertedAddress: ConversionAddress): Option[(RoadwayPoint, CalibrationPoint)] = {
    convertedAddress.calibrationCode match {
      case AtEnd | AtBoth =>

        val existingRoadwayPoint = roadwayPointDAO.fetch(convertedAddress.roadwayNumber, convertedAddress.addrMRange.end)
        existingRoadwayPoint match {
          case Some(x) =>
            val existingCalibrationPoint = CalibrationPointDAO.fetchByRoadwayPointId(x.id).find(_.startOrEnd == CalibrationPointLocation.EndOfLink)
            if (existingCalibrationPoint.isDefined)
              Some((existingRoadwayPoint.get, existingCalibrationPoint.get))
            else
              Some((existingRoadwayPoint.get, CalibrationPoint(NewIdValue, x.id, convertedAddress.linkId, x.roadwayNumber, x.addrMValue, CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP, createdBy = "import")))
          case _ =>
            Some(RoadwayPoint(NewIdValue, convertedAddress.roadwayNumber, convertedAddress.addrMRange.end, "import"), CalibrationPoint(NewIdValue, NewIdValue, convertedAddress.linkId, convertedAddress.roadwayNumber, convertedAddress.addrMRange.end, CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP, createdBy = "import"))
        }
      case _ => None
    }
  }

  private def insertRoadwayPointToDb(roadwayPoint: RoadwayPoint): Long = {
    val roadwayPointId = Sequences.nextRoadwayPointId
    val roadwayPointSql =
      sql"""
      INSERT INTO roadway_point (id, roadway_number, addr_m, created_by, modified_by)
      VALUES ($roadwayPointId, ${roadwayPoint.roadwayNumber}, ${roadwayPoint.addrMValue},
             ${roadwayPoint.createdBy}, ${roadwayPoint.createdBy})
      """

    val inserted = runUpdateToDb(roadwayPointSql)
    if (inserted == 1) roadwayPointId // Check that the insert was successful and return the id
    else throw new Exception(s"Failed to insert roadway point, rows affected: $inserted")

  }

  private def insertCalibrationPointToDb(calibrationPoint: CalibrationPoint): Unit = {
    val insertQuery =
      sql"""
        INSERT INTO calibration_point (id, roadway_point_id, link_id, start_end, type, created_by)
        VALUES (nextval('CALIBRATION_POINT_SEQ'), ?, ?, ?, ?, ?)
        """

    runUpdateToDb(insertQuery.bind(
      calibrationPoint.roadwayPointId,
      calibrationPoint.linkId,
      calibrationPoint.startOrEnd.value,
      calibrationPoint.typeCode.value,
      calibrationPoint.createdBy
    ))
  }

    object ConversionAddress extends SQLSyntaxSupport[ConversionAddress] {
      def apply(rs: WrappedResultSet): ConversionAddress = {
        val roadPart              = RoadPart(
          roadNumber              = rs.long("tie"),
          partNumber              = rs.long("aosa")
        )
        val trackCode             = rs.long("ajr")
        val discontinuity         = rs.long("jatkuu")
        val startAddrM            = rs.long("aet") // Conversion addresses might be end > start; do not use AddrMRange here.
        val endAddrM              = rs.long("let") // Conversion addresses might be end > start; do not use AddrMRange here.
        val startM                = rs.double("alku")
        val endM                  = rs.double("loppu")
        val startDate             = rs.jodaDateTimeOpt("alkupvm")
        val endDate               = rs.jodaDateTimeOpt("loppupvm")
        val validFrom             = rs.jodaDateTimeOpt("muutospvm")
        val expirationDate        = rs.jodaDateTimeOpt("lakkautuspvm")
        val ely                   = rs.long("ely")
        val administrativeClass   = rs.long("tietyyppi")
        val linkId                = rs.string("linkid")
        val userId                = rs.string("kayttaja")
        val x1                    = rs.doubleOpt("alkux")
        val y1                    = rs.doubleOpt("alkuy")
        val x2                    = rs.doubleOpt("loppux")
        val y2                    = rs.doubleOpt("loppuy")
        val roadwayNumber         = rs.long("ajorataid")
        val directionFlag         = rs.long("kaannetty")
        val startCalibrationPoint = rs.longOpt("alku_kalibrointipiste").getOrElse(0L)
        val endCalibrationPoint   = rs.longOpt("loppu_kalibrointipiste").getOrElse(0L)
        def getCalibrationCode(startCalibrationPoint: Long, endCalibrationPoint: Long, addrMRange: AddrMRange): CalibrationCode = {
          if (addrMRange.start < addrMRange.end) {
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
          val addrMRange = AddrMRange(startAddrM, endAddrM)
          new ConversionAddress(
            roadPart, trackCode, discontinuity, addrMRange, startM, endM, startDate, endDate,
            validFrom, expirationDate, ely, administrativeClass, 0, linkId, userId, x1, y1,
            x2, y2, roadwayNumber, SideCode.TowardsDigitizing,
            getCalibrationCode(startCalibrationPoint, endCalibrationPoint, addrMRange), directionFlag)
        } else {
          //switch startAddrM, endAddrM and set the side code to AgainstDigitizing
          val addrMRange = AddrMRange(endAddrM, startAddrM)
          new ConversionAddress(
            roadPart, trackCode, discontinuity, addrMRange, startM, endM, startDate, endDate,
            validFrom, expirationDate, ely, administrativeClass, 0, linkId, userId, x1, y1,
            x2, y2, roadwayNumber, SideCode.AgainstDigitizing,
            getCalibrationCode(startCalibrationPoint, endCalibrationPoint, addrMRange), directionFlag
          )
        }
      }
    }
}

