//package fi.liikennevirasto.viite.util
//
//import fi.liikennevirasto.digiroad2.client.kgv.{KgvRoadLink, KgvRoadLinkClient}
//import fi.liikennevirasto.viite.dao.{CalibrationCode, CalibrationPointDAO, LinearLocationDAO, RoadwayDAO, TerminationCode}
//import fi.vaylavirasto.viite.dao.{BaseDAO, ComplementaryLinkDAO}
//import fi.vaylavirasto.viite.geometry.Point
//import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, LifecycleStatus, LinkGeomSource, RoadLink, RoadPart, SideCode, TrafficDirection}
//import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.{runWithRollback, runWithTransaction}
//import fi.vaylavirasto.viite.util.DateTimeFormatters.finnishDateFormatter
//import org.joda.time.DateTime
//import org.mockito.ArgumentMatchers.any
//import org.mockito.Mockito.{mock, when}
//import org.scalatest.BeforeAndAfter
//import org.scalatest.funsuite.AnyFunSuite
//import org.scalatest.matchers.should.Matchers
//import scalikejdbc.{SQLSyntax, scalikejdbcSQLInterpolationImplicitDef}
//
//
//class RoadAddressImporterSpec extends AnyFunSuite with Matchers with BaseDAO with BeforeAndAfter {
//
//  val mockKGVClient: KgvRoadLink = mock(classOf[KgvRoadLink])
//  val mockKGVRoadLinkClient: KgvRoadLinkClient[RoadLink] = mock(classOf[KgvRoadLinkClient[RoadLink]])
//  val mockComplementaryClient: ComplementaryLinkDAO = mock(classOf[ComplementaryLinkDAO])
//
//  val importOptions: ImportOptions = ImportOptions(onlyComplementaryLinks = false, useFrozenLinkService = false, "MOCK_CONVERSION", onlyCurrentRoads = false)
//
//  val importer: RoadAddressImporter = new RoadAddressImporter(mockKGVClient, importOptions) {
//
//    override def runWithConversionDbReadOnlySession[T](f: => T): T = f
//
//  }
//
//  val roadwayDAO = new RoadwayDAO
//  val linearLocationDAO = new LinearLocationDAO
//
//  val linkId1 = "1a"
//  val linkId2 = "2b"
//  val linkId3 = "3c"
//
//  // Mock road links that will be returned by KGV
//  val mockRoadLinks: Seq[RoadLink] = Seq(
//    RoadLink(
//      linkId = linkId1,
//      geometry = Seq(Point(0.0, 0.0), Point(71.0, 0.0)),
//      length = 71.0,
//      administrativeClass = AdministrativeClass.Municipality,
//      trafficDirection = TrafficDirection.BothDirections,
//      modifiedAt = Some("2024-02-13T10:00:00Z"),
//      modifiedBy = Some("TestUser"),
//      lifecycleStatus = LifecycleStatus.InUse,
//      linkSource = LinkGeomSource.NormalLinkInterface,
//      municipalityCode = 91,
//      sourceId = "source_1"
//    ),
//    RoadLink(
//      linkId = linkId2,
//      geometry = Seq(Point(71.0, 0.0), Point(116.0, 0.0)),
//      length = 45.0,
//      administrativeClass = AdministrativeClass.Municipality,
//      trafficDirection = TrafficDirection.BothDirections,
//      modifiedAt = Some("2024-02-13T10:00:00Z"),
//      modifiedBy = Some("TestUser"),
//      lifecycleStatus = LifecycleStatus.InUse,
//      linkSource = LinkGeomSource.NormalLinkInterface,
//      municipalityCode = 91,
//      sourceId = "source_2"
//    ),
//    RoadLink(
//      linkId = linkId3,
//      geometry = Seq(Point(116.0, 0.0), Point(126.0, 0.0)),
//      length = 10.0,
//      administrativeClass = AdministrativeClass.Municipality,
//      trafficDirection = TrafficDirection.BothDirections,
//      modifiedAt = Some("2024-02-13T10:00:00Z"),
//      modifiedBy = Some("TestUser"),
//      lifecycleStatus = LifecycleStatus.InUse,
//      linkSource = LinkGeomSource.NormalLinkInterface,
//      municipalityCode = 91,
//      sourceId = "source_3"
//    )
//  )
//
//  def d(date: String): DateTime = DateTime.parse(date, finnishDateFormatter)
//
//  val roadPart1: RoadPart = RoadPart(25, 22)
//
//  val roadwayNumber1 = 1
//  val roadwayNumber2 = 2
//  val roadwayNumber3 = 3
//
//  // Values For terminated addresses
//  val terminatedRoadPart: RoadPart = RoadPart(30, 1)
//  val roadwayNumber4 = 4
//  val roadwayNumber5 = 5
//
//  val testUser = "testUser"
//
//  val testData: Seq[ConversionAddress] = Seq(
//    // Road addresses to be converted
//    //              ROAD PART TRACK DISCONT ADDRM START  END STARTM ENDM   STARTDATE             ENDDATE                VALIDFROM           ExpDate ELY ADCLASS TERMINATED LINKID         USER         X1                Y1                 X2                Y2                      RWNUMBER  SIDECODE        CALIBRATIONPOINT
//    // Road part 25/22, start/end 694-756 current road address linkId1, rwNumber1
//    ConversionAddress(roadPart1, 0,   5, AddrMRange(694, 756),  0,  62,   Some(d("01.03.2016")), None,                  Some(d("30.03.2016")), None,  1,    1,    0,      linkId1,      testUser, Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), roadwayNumber1, SideCode.Unknown, CalibrationCode.AtEnd),
//    // Road part 25/22, start/end 694-756 historical road addresses
//    ConversionAddress(roadPart1, 0,   5, AddrMRange(694, 756),  0,  62,   Some(d("29.10.2008")), Some(d("29.02.2016")), Some(d("08.03.2016")), None,  1,    1,    0,      linkId1,      testUser, Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), roadwayNumber1, SideCode.Unknown),
//    ConversionAddress(roadPart1, 0,   5, AddrMRange(694, 756),  0,  62,   Some(d("31.10.2006")), Some(d("28.10.2008")), Some(d("29.10.2008")), None,  1,    1,    0,      linkId1,      testUser, Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), roadwayNumber1, SideCode.Unknown),
//
//    // Road part 25/22, start/end 756-765 current road address, linkId1, rwNumber2
//    ConversionAddress(roadPart1, 0,   5, AddrMRange(756, 765),  62,  71,  Some(d("01.03.2016")), None,                  Some(d("30.03.2016")), None,  1,    1,    0,      linkId1,      testUser, Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), roadwayNumber2, SideCode.Unknown, CalibrationCode.AtBeginning),
//    // Road part 25/22, start/end 756-765 historical road addresses
//    ConversionAddress(roadPart1, 0,   5, AddrMRange(756, 765),  62,  71,  Some(d("29.10.2008")), Some(d("29.02.2016")), Some(d("08.03.2016")), None,  1,    1,    0,      linkId1,      testUser, Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), roadwayNumber2, SideCode.Unknown),
//    ConversionAddress(roadPart1, 0,   5, AddrMRange(756, 765),  62,  71,  Some(d("31.10.2006")), Some(d("28.10.2008")), Some(d("29.10.2008")), None,  1,    1,    0,      linkId1,      testUser, Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), roadwayNumber2, SideCode.Unknown),
//
//    // Road part 25/22, start/end 765-810 current road address, linkId2, rwNumber2
//    ConversionAddress(roadPart1, 0,   5, AddrMRange(765, 810),  0,  45,   Some(d("01.03.2016")), None,                  Some(d("30.03.2016")), None,  1,    1,    0,      linkId2,      testUser, Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), roadwayNumber2, SideCode.Unknown, CalibrationCode.AtEnd),
//    // Road part 25/22, start/end 765-810 historical road addresses
//    ConversionAddress(roadPart1, 0,   5, AddrMRange(765, 810),  0,  45,   Some(d("15.12.2005")), Some(d("29.02.2016")), Some(d("08.03.2016")), None,  1,    1,    0,      linkId2,      testUser, Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), roadwayNumber2, SideCode.Unknown),
//
//    // Road part 25/22, start/end 810-820 current road address, linkId3, rwNumber3, end of road
//    ConversionAddress(roadPart1, 0,   1, AddrMRange(810, 820),  0,  10,   Some(d("01.03.2016")), None,                  Some(d("30.03.2016")), None,  1,    1,    0,      linkId3,      testUser, Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), roadwayNumber3, SideCode.Unknown, CalibrationCode.AtBoth),
//
//    // Terminated roadAdresses
//    //                        ROADPART  TRACK DISCONT ADDRM   START   END  STARTM  ENDM           STARTDATE              ENDDATE                VALIDFROM     ExpDate ELY ADCLASS TERMINATED LINKID         USER         X1                Y1                 X2                Y2           RWNUMBER           SIDECODE        CALIBRATIONPOINT
//    ConversionAddress(terminatedRoadPart, 1,   5,   AddrMRange(0,     100), 200,   300,    Some(d("01.03.1980")), Some(d("30.01.1991")), Some(d("30.01.1991")), None, 1,     3,       1,     null,       testUser, Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), roadwayNumber4, SideCode.Unknown),
//    ConversionAddress(terminatedRoadPart, 1,   5,   AddrMRange(100,   200), 300,   400,    Some(d("01.03.1980")), Some(d("30.01.1991")), Some(d("30.01.1991")), None, 1,     3,       1,     null,       testUser, Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), roadwayNumber5, SideCode.Unknown),
//    // Historical road addresses
//    ConversionAddress(terminatedRoadPart, 1,   5,   AddrMRange(0,     200), 0,     99,     Some(d("01.01.1980")), Some(d("28.02.1980")), Some(d("30.01.1990")), None, 1,     3,       1,     null,       testUser, Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), roadwayNumber4, SideCode.Unknown),
//    ConversionAddress(terminatedRoadPart, 1,   5,   AddrMRange(100,   200), 100,   199,    Some(d("01.01.1980")), Some(d("28.02.1980")), Some(d("30.01.1990")), None, 1,     3,       1,     null,       testUser, Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), roadwayNumber5, SideCode.Unknown)
//  )
//
//  val tableName: String = importOptions.conversionTable
//
//  // Setup once before all tests - using standard session, not runWithRollback
//  before {
//    // Setup KGV client mocks
//    when(mockKGVClient.complementaryData).thenReturn(mockComplementaryClient)
//    when(mockKGVClient.roadLinkData).thenReturn(mockKGVRoadLinkClient)
//
//    // Setup mock responses
//    when(mockKGVRoadLinkClient.fetchByLinkIds(any[Set[String]])).thenReturn(mockRoadLinks)
//    when(mockComplementaryClient.fetchByLinkIdsInReadOnlySession(any[Set[String]])).thenReturn(mockRoadLinks.toList)
//
//    createConversionTableAndPopulateData()
//  }
//
//  test("fetchValidAddressesFromConversionTable should fetch and map addresses correctly") {
//    runWithRollback {
//
//      val addresses = importer.fetchValidAddressesFromConversionTable(1, 2)
//      addresses.size should be(8)
//
//      val addressesWithRoadwayNumberNotInList = importer.fetchValidAddressesFromConversionTable(6, 10)
//      addressesWithRoadwayNumberNotInList.size should be(0)
//    }
//  }
//
//  test("fetchAllTerminatedAddressesFromConversionTable should fetch and map addresses without errors") {
//    runWithRollback {
//      // Using a small range to test mapping functionality
//      val addresses = importer.fetchAllTerminatedAddressesFromConversionTable()
//
//      // Verify we got some results
//      addresses.size should be(4)
//    }
//  }
//
//  test("importAddresses should process and batch update addresses without errors") {
//    runWithRollback {
//      // Split into chunk and all addresses for testing
//      val addressesInChunk = testData.take(1) // Just the current address
//      val allAddresses = testData // All addresses for coefficient calculation
//
//      importer.importAddresses(addressesInChunk, allAddresses)
//
//      // Verify roadway after first import
//      val initialRoadways = roadwayDAO.fetchAllByRoadwayNumbers(Set(roadwayNumber1, roadwayNumber2, roadwayNumber3))
//      initialRoadways.size should be(1)
//      initialRoadways.head.roadwayNumber should be(roadwayNumber1)
//
//      // To mock importing in chunks, import the rest of the addresses
//      val restOfAddressesInChunk = testData.drop(1)
//      importer.importAddresses(restOfAddressesInChunk, allAddresses)
//
//      // Verify roadways after second import
//      val roadways = roadwayDAO.fetchAllByRoadwayNumbers(Set(roadwayNumber1, roadwayNumber2, roadwayNumber3))
//      roadways.count(_.roadwayNumber == roadwayNumber1) should be(1)
//      roadways.count(_.roadwayNumber == roadwayNumber2) should be(1)
//      roadways.count(_.roadwayNumber == roadwayNumber3) should be(1)
//
//      // Verify roadway number and address range relationships
//      roadways.find(_.roadwayNumber == roadwayNumber1).map(_.roadPart) should be(Some(roadPart1))
//
//      // Verify calibration points
//      val calibrationPoints = CalibrationPointDAO.fetchByLinkId(Set(linkId1, linkId2, linkId3))
//      calibrationPoints.count(_.linkId == linkId1) should be(2)
//      calibrationPoints.count(_.linkId == linkId2) should be(1)
//      calibrationPoints.count(_.linkId == linkId3) should be(2)
//
//      // Verify linear locations
//      val linearLocations = linearLocationDAO.fetchByRoadways(Set(roadwayNumber1, roadwayNumber2, roadwayNumber3))
//      linearLocations.count(_.linkId == linkId1) should be(2)
//      linearLocations.count(_.linkId == linkId2) should be(1)
//      linearLocations.count(_.linkId == linkId3) should be(1)
//
//      // Verify linear location roadway relationships
//      linearLocations.count(_.roadwayNumber == roadwayNumber1) should be(1)
//      linearLocations.count(_.roadwayNumber == roadwayNumber2) should be(2)
//      linearLocations.count(_.roadwayNumber == roadwayNumber3) should be(1)
//    }
//  }
//
//  test("importTerminatedAddresses should process and batch update addresses correctly") {
//    runWithRollback {
//      // Use the last four addresses from testData (terminated ones)
//      val terminatedAddresses = testData.takeRight(4)
//
//      // Execute the method
//      importer.importTerminatedAddresses(terminatedAddresses)
//
//      // Get results
//      val resultRoadways = roadwayDAO.fetchAllByRoadPart(terminatedRoadPart, withHistory = true)
//
//      // Should have 4 roadways total (2 per roadway number: 1 terminated + 1 subsequent each)
//      resultRoadways.size should be(4)
//
//      // Verify roadwayNumber4
//      val roadway4Terminated = resultRoadways.count(r => r.roadwayNumber == roadwayNumber4 && r.terminated == TerminationCode.Termination)
//      roadway4Terminated should be(1)
//      val roadway4Subsequent = resultRoadways.count(r => r.roadwayNumber == roadwayNumber4 && r.terminated == TerminationCode.Subsequent)
//      roadway4Subsequent should be(1)
//
//      // Verify roadwayNumber5
//      val roadway5Terminated = resultRoadways.count(r => r.roadwayNumber == roadwayNumber5 && r.terminated == TerminationCode.Termination)
//      roadway5Terminated should be(1)
//      val roadway5Subsequent = resultRoadways.count(r => r.roadwayNumber == roadwayNumber5 && r.terminated == TerminationCode.Subsequent)
//      roadway5Subsequent should be(1)
//    }
//  }
//
//  // Helper method to create and populate the conversion table with test data
//  def createConversionTableAndPopulateData(): Unit = {
//    runWithTransaction {
//      val table: SQLSyntax = SQLSyntax.createUnsafely(tableName)
//      // Create table
//      runUpdateToDb(
//        sql"""
//        CREATE TABLE IF NOT EXISTS $table (
//          id numeric PRIMARY KEY,
//          tie int4,
//          aosa int4,
//          ajr int4,
//          jatkuu int4,
//          aet int4,
//          let int4,
//          alku float8,
//          loppu float8,
//          alkupvm date,
//          loppupvm date,
//          muutospvm date,
//          lakkautuspvm date,
//          ely int4,
//          tietyyppi int4,
//          linkid varchar(100),
//          kayttaja varchar(10),
//          alkux float8,
//          alkuy float8,
//          loppux float8,
//          loppuy float8,
//          ajorataid int4,
//          kaannetty int4,
//          alku_kalibrointipiste int4,
//          loppu_kalibrointipiste int4
//        )
//        """)
//    }
//
//    runWithTransaction {
//      val table: SQLSyntax = SQLSyntax.createUnsafely(tableName)
//      // Create table and insert test data
//      runUpdateToDb(
//        sql"""
//      CREATE TABLE IF NOT EXISTS $table (
//        id numeric PRIMARY KEY,
//        tie int4,
//        aosa int4,
//        ajr int4,
//        jatkuu int4,
//        aet int4,
//        let int4,
//        alku float8,
//        loppu float8,
//        alkupvm date,
//        loppupvm date,
//        muutospvm date,
//        lakkautuspvm date,
//        ely int4,
//        tietyyppi int4,
//        linkid varchar(100),
//        kayttaja varchar(10),
//        alkux float8,
//        alkuy float8,
//        loppux float8,
//        loppuy float8,
//        ajorataid int4,
//        kaannetty int4,
//        alku_kalibrointipiste int4,
//        loppu_kalibrointipiste int4
//      )
//      """)
//      // Insert test data
//      testData.zipWithIndex.foreach { case (address, index) =>
//        val id = index + 1
//        runUpdateToDb(
//          sql"""
//        INSERT INTO $table (
//           id, tie, aosa, ajr, jatkuu, aet, let, alku, loppu,
//           alkupvm, loppupvm, muutospvm, lakkautuspvm, ely, tietyyppi,
//           linkid, kayttaja, alkux, alkuy, loppux, loppuy,
//           ajorataid, kaannetty, alku_kalibrointipiste, loppu_kalibrointipiste
//         )
//        VALUES(
//           $id,
//           ${address.roadPart.roadNumber},
//           ${address.roadPart.partNumber},
//           ${address.trackCode},
//           ${address.discontinuity},
//           ${address.addrMRange.start},
//           ${address.addrMRange.end},
//           ${address.startM},
//           ${address.endM},
//           ${address.startDate},
//           ${address.endDate},
//           ${address.validFrom},
//           ${address.expirationDate},
//           ${address.ely},
//           ${address.administrativeClass},
//           ${address.linkId},
//           ${address.userId},
//           ${address.x1},
//           ${address.y1},
//           ${address.x2},
//           ${address.y2},
//           ${address.roadwayNumber},
//           ${address.directionFlag},
//           0,
//           0
//          )
//      """)
//      }
//    }
//  }
//
//
//  after { // Delete the conversion table as it is not used in other tests
//    runWithTransaction {
//      val table: SQLSyntax = SQLSyntax.createUnsafely(tableName)
//      runUpdateToDb(sql"DROP TABLE IF EXISTS $table")
//    }
//  }
//}
