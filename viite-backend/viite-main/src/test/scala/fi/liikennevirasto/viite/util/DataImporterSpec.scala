//package fi.liikennevirasto.viite.util
//
//import fi.liikennevirasto.digiroad2.client.vvh._
//import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase.runWithRollback
//import fi.liikennevirasto.viite.dao._
//import org.mockito.Mockito.when
//import org.scalatest.mockito.MockitoSugar
//import org.scalatest.matchers.should.Matchers
//import slick.driver.JdbcDriver.backend.Database
//import Database.dynamicSession
//import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
//import fi.liikennevirasto.digiroad2.service.RoadLinkService
//import fi.vaylavirasto.viite.model.Point
//import fi.vaylavirasto.viite.model.{AdministrativeClass, SideCode, TrafficDirection}
//import fi.vaylavirasto.viite.util.DateTimeFormatters.finnishDateFormatter
//import org.joda.time.DateTime
//import org.mockito.ArgumentMatchers.any
//import slick.driver.JdbcDriver
//import slick.driver.JdbcDriver.backend.DatabaseDef
//import slick.jdbc.StaticQuery.{interpolation, _}
//
//class DataImporterSpec extends AnyFunSuite with Matchers {
//
//  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)
//
//  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)
//
//  val mockVVHClient = MockitoSugar.mock[KgvRoadLink]
//  val mockVVHRoadLinkClient = MockitoSugar.mock[KgvRoadLinkClient]
//  val mockVVHComplementaryClient = MockitoSugar.mock[VVHComplementaryClient]
//  val mockVVHHistoryClient = MockitoSugar.mock[VVHHistoryClient]
//  val mockVVHFrozenTimeRoadLinkClient = MockitoSugar.mock[VVHFrozenTimeRoadLinkClientServicePoint]
//  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
//
//  def d(date: String): DateTime = {DateTime.parse(date, finnishDateFormatter)}
//
//  val roadwayDAO = new RoadwayDAO
//  val linearLocationDAO = new LinearLocationDAO
//
//  val roadsToBeConverted = Seq(
//    //              TIE AOSA AJR JATKUU AET LET ALKU LOPPU ALKUPVM                       LOPPUPVM                      MUUTOSPVM                    -    ELY  TIETYYPPI -  LINKID  KAYTTAJA  ALKUX             ALKUY              LOPPUX            LOPPUY         AJORATAID  SIDE
//    ConversionAddress(25, 22, 1, 5, 694, 756, 0, 62, Some(d("01.03.2016")), None, Some(d("30.03.2016")), None, 1, 1, 0, 1L.toString, "ajrpilkont", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 2, SideCode.Unknown),
//    ConversionAddress(25, 22, 0, 5, 694, 756, 0, 62, Some(d("29.10.2008")), Some(d("29.02.2016")), Some(d("08.03.2016")), None, 1, 1, 0, 1L.toString, "ajrpilkont", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 2, SideCode.Unknown),
//    ConversionAddress(25, 22, 1, 5, 694, 756, 0, 62, Some(d("31.10.2006")), Some(d("28.10.2008")), Some(d("29.10.2008")), None, 1, 1, 0, 1L.toString, "TR", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 2, SideCode.Unknown),
//    ConversionAddress(25, 22, 0, 5, 694, 756, 0, 62, Some(d("15.12.2005")), Some(d("30.10.2006")), Some(d("29.10.2008")), None, 1, 1, 0, 1L.toString, "TR", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 2, SideCode.Unknown),
//    ConversionAddress(25, 22, 1, 5, 756, 765, 62, 71, Some(d("01.03.2016")), None, Some(d("30.03.2016")), None, 1, 1, 0, 1L.toString, "ajrpilkont", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 1, SideCode.Unknown, CalibrationCode.AtBeginning),
//    ConversionAddress(25, 22, 0, 5, 756, 765, 62, 71, Some(d("29.10.2008")), Some(d("29.02.2016")), Some(d("08.03.2016")), None, 1, 1, 0, 1L.toString, "ajrpilkont", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 1, SideCode.Unknown),
//    ConversionAddress(25, 22, 1, 5, 756, 765, 62, 71, Some(d("31.10.2006")), Some(d("28.10.2008")), Some(d("29.10.2008")), None, 1, 1, 0, 1L.toString, "TR", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 1, SideCode.Unknown),
//    ConversionAddress(25, 22, 0, 5, 756, 765, 62, 71, Some(d("15.12.2005")), Some(d("30.10.2006")), Some(d("29.10.2008")), None, 1, 1, 0, 1L.toString, "TR", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 1, SideCode.Unknown),
//    ConversionAddress(25, 22, 1, 5, 765, 810, 71, 116, Some(d("01.03.2016")), None, Some(d("30.03.2016")), None, 1, 1, 0, 1L.toString, "ajrpilkont", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 3, SideCode.Unknown, CalibrationCode.AtEnd),
//    ConversionAddress(25, 22, 0, 5, 765, 810, 71, 116, Some(d("15.12.2005")), Some(d("29.02.2016")), Some(d("08.03.2016")), None, 1, 1, 0, 1L.toString, "ajrpilkont", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 3, SideCode.Unknown),
//    ConversionAddress(25, 22, 0, 5, 6221, 6230, 62, 71, Some(d("01.01.1996")), Some(d("14.12.2005")), Some(d("29.10.2008")), None, 1, 1, 0, 1L.toString, "TR", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 1, SideCode.Unknown),
//    ConversionAddress(25, 22, 0, 5, 6230, 6275, 71, 116, Some(d("01.01.1996")), Some(d("14.12.2005")), Some(d("08.03.2016")), None, 1, 1, 0, 1L.toString, "ajrpilkont", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 3, SideCode.Unknown),
//    ConversionAddress(53, 22, 0, 5, 6221, 6230, 62, 71, Some(d("01.11.1963")), Some(d("31.12.1995")), Some(d("29.10.2008")), None, 1, 1, 0, 1L.toString, "TR", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 1, SideCode.Unknown),
//    ConversionAddress(53, 22, 0, 5, 6230, 6275, 71, 116, Some(d("01.11.1963")), Some(d("31.12.1995")), Some(d("08.03.2016")), None, 1, 1, 0, 1L.toString, "ajrpilkont", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 3, SideCode.Unknown)
//  )
//
//  val terminatedRoadsToBeConverted = Seq(
//    //           TIE AOSA  AJR JATKUU AET LET   ALKU LOPPU ALKUPVM                      LOPPUPVM                       MUUTOSPVM                    LAKKAUTUSPVM                  ELY TIETYYPPI T  LINKID  KAYTTAJA     ALKUX             ALKUY              LOPPUX            LOPPUY         AJORATAID  SIDE
//    ConversionAddress(30, 1, 1, 5, 0, 100, 201, 300, Some(d("01.03.1980")), Some(d("30.01.1991")), Some(d("30.01.1990")), None, 1, 3, 1, 0.toString, "ajrpilkont", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 3, SideCode.Unknown),
//    ConversionAddress(30, 1, 1, 5, 100, 200, 300, 400, Some(d("01.03.1980")), Some(d("30.01.1991")), Some(d("30.01.1990")), None, 1, 3, 1, 0.toString, "ajrpilkont", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 4, SideCode.Unknown),
//    ConversionAddress(30, 2, 1, 5, 0, 100, 0, 99, Some(d("01.03.1980")), Some(d("30.01.1991")), Some(d("30.01.1990")), None, 1, 3, 1, 0.toString, "ajrpilkont", Some(346769.646), Some(6688615.011), Some(346862.556), Some(6688687.082), 5, SideCode.Unknown)
//  )
//
//  val vvhRoadLinks = List(
//    RoadLinkFetched(1L.toString, 91, List(Point(0.0, 0.0), Point(120.0, 0.0)), AdministrativeClass.Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers)
//  )
//  when(mockVVHClient.complementaryData).thenReturn(mockVVHComplementaryClient)
//  when(mockVVHClient.roadLinkData).thenReturn(mockVVHRoadLinkClient)
//  when(mockVVHClient.historyData).thenReturn(mockVVHHistoryClient)
//  when(mockVVHClient.frozenTimeRoadLinkData)thenReturn mockVVHFrozenTimeRoadLinkClient
//  when(mockVVHRoadLinkClient.fetchByLinkIds(any[Set[Long]])).thenReturn(vvhRoadLinks)
//  when(mockVVHComplementaryClient.fetchByLinkIds(any[Set[Long]])).thenReturn(vvhRoadLinks)
//  when(mockVVHFrozenTimeRoadLinkClient.fetchByLinkIds(any[Set[Long]])).thenReturn(vvhRoadLinks)
//  when(mockVVHHistoryClient.fetchVVHRoadLinkByLinkIds(any[Set[Long]])).thenReturn(Seq())
//
//  val importOptions = ImportOptions(onlyComplementaryLinks = false, useFrozenLinkService = false, "MOCK_CONVERSION", onlyCurrentRoads = false)
//
//  val roadAddressImporter = new RoadAddressImporter(null, mockVVHClient, importOptions) {
//    override def fetchChunkRoadwayNumbersFromConversionTable(): Seq[(Long, Long)] = {
//      Seq((0l, 5L))
//    }
//    override def fetchValidAddressesFromConversionTable(minRoadwayNumber: Long, maxRoadwayNumber: Long): Seq[ConversionAddress] = {
//      roadsToBeConverted
//    }
//    override def fetchAllTerminatedAddressesFromConversionTable(): Seq[ConversionAddress] = {
//      terminatedRoadsToBeConverted
//    }
//  }
//
//  val dataImporter = new DataImporter {
//    override def withDynTransaction(f: => Unit): Unit = f
//
//    override def withDynSession[T](f: => T): T = f
//
//    protected override def getRoadAddressImporter(conversionDatabase: JdbcDriver.backend.DatabaseDef, vvhClient: KgvRoadLink, importOptions: ImportOptions): RoadAddressImporter = {
//      roadAddressImporter
//    }
//
//    override def disableRoadwayTriggers: Unit = {}
//
//    override def enableRoadwayTriggers: Unit = {}
//
//    override def roadwaySequenceResetter(): Unit = {}
//
//    override def resetRoadAddressSequences(): Unit = {}
//
//    override def resetNodesAndJunctionSequences(): Unit = {}
//  }
//
//  test("Test importRoadAddressData When importing addresses Then they are saved in database") {
//    withDynTransaction {
//      runWithRollback {
//
//        dataImporter.importRoadAddressData(null, mockVVHClient, importOptions)
//
//        val road_25_22 = roadwayDAO.fetchAllByRoadPart(25, 22)
//        road_25_22.size should be(3)
//
//        // Terminated roadways
//        val road_30_1_history = roadwayDAO.fetchAllByRoadPart(30, 1, withHistory = true)
//        road_30_1_history.size should be(2)
//        val roadway_30_1 = road_30_1_history.filter(r => r.terminated == TerminationCode.Termination).sortBy(_
//          .startAddrMValue)
//        val roadway_30_1_history = road_30_1_history.filter(r => r.terminated == TerminationCode.Subsequent)
//        roadway_30_1_history.size should be(0)
//        roadway_30_1.size should be(2)
//        roadway_30_1.head.endDate should not be None
//        roadway_30_1.head.startAddrMValue should be(0)
//        roadway_30_1.head.endAddrMValue should be(100)
//        roadway_30_1.last.endDate should not be None
//        roadway_30_1.last.startAddrMValue should be(100)
//        roadway_30_1.last.endAddrMValue should be(200)
//
//        val road_30_2_history = roadwayDAO.fetchAllByRoadPart(30, 2, withHistory = true)
//        road_30_2_history.size should be(1)
//        val roadway_30_2 = road_30_2_history.filter(r => r.terminated == TerminationCode.Termination)
//        val roadway_30_2_history = road_30_2_history.filter(r => r.terminated == TerminationCode.Subsequent)
//        roadway_30_2.size should be(1)
//        roadway_30_2_history.size should be(0)
//        roadway_30_2.head.endDate should not be None
//        roadway_30_2.head.startAddrMValue should be(0)
//        roadway_30_2.head.endAddrMValue should be(100)
//
//        val roadway1Ids = sql"""select a.id from ROADWAY a where roadway_number = 1""".as[Long].list
//        roadway1Ids.size should be(6)
//
//        val roadway2Ids = sql"""select a.id from ROADWAY a where roadway_number = 2""".as[Long].list
//        roadway2Ids.size should be(4)
//
//        val roadway3Ids = sql"""select a.id from ROADWAY a where roadway_number = 3""".as[Long].list
//        roadway3Ids.size should be(5)
//
//        val roadway4Ids = sql"""select a.id from ROADWAY a where roadway_number = 4""".as[Long].list
//        roadway4Ids.size should be(1)
//
//        val roadway5Ids = sql"""select a.id from ROADWAY a where roadway_number = 5""".as[Long].list
//        roadway5Ids.size should be(1)
//
//        val roadways = sql"""select a.id from ROADWAY a""".as[Long].list
//        roadways.size should be(17)
//
//        // Check linear locations
//        val linearLocations = linearLocationDAO.fetchByRoadways(Set(1, 2, 3, 4, 5))
//        linearLocations.size should be(3)
//        linearLocations.foreach(l => l.linkId should be(1))
//        linearLocations.foreach(l => l.sideCode should be(SideCode.Unknown))
//        linearLocations.foreach(l => l.orderNumber should be(1.0 +- 0.00001))
//        linearLocations.foreach(l => l.linkGeomSource should be(LinkGeomSource.NormalLinkInterface))
//        val linearLocation1 = linearLocations.filter(l => l.roadwayNumber == 1).head
//        val linearLocation2 = linearLocations.filter(l => l.roadwayNumber == 2).head
//        val linearLocation3 = linearLocations.filter(l => l.roadwayNumber == 3).head
//        linearLocation1.startMValue should be(64.138 +- 0.001)
//        linearLocation2.startMValue should be(0.0 +- 0.001)
//        linearLocation3.startMValue should be(73.448 +- 0.001)
//        linearLocation1.endMValue should be(73.448 +- 0.001)
//        linearLocation2.endMValue should be(64.138 +- 0.001)
//        linearLocation3.endMValue should be(120.0 +- 0.001)
//        linearLocation1.startCalibrationPoint.addrM.get should be(756)
//        linearLocation1.endCalibrationPoint should be(CalibrationPointReference(None, None))
//        linearLocation2.startCalibrationPoint should be(CalibrationPointReference(None, None))
//        linearLocation2.endCalibrationPoint should be(CalibrationPointReference(None, None))
//        linearLocation3.startCalibrationPoint should be(CalibrationPointReference(None, None))
//        linearLocation3.endCalibrationPoint.addrM.get should be(810)
//      }
//    }
//  }
//
//}
