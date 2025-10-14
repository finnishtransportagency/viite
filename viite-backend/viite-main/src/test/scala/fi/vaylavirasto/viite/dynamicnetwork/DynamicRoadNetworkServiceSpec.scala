//package fi.vaylavirasto.viite.dynamicnetwork
//
//import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
//import fi.liikennevirasto.digiroad2.client.vkm.TiekamuRoadLinkChange
//import fi.liikennevirasto.viite.AwsService
//import fi.liikennevirasto.viite.dao._
//import fi.vaylavirasto.viite.dao.Sequences
//import fi.vaylavirasto.viite.geometry.Point
//import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, CalibrationPoint, CalibrationPointLocation, CalibrationPointType, Discontinuity, LifecycleStatus, LinkGeomSource, RoadLink, RoadPart, SideCode, Track, TrafficDirection}
//import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback
//import org.joda.time.DateTime
//import org.scalatest.funsuite.AnyFunSuite
//import org.scalatest.matchers.should.Matchers
//
//class DynamicRoadNetworkServiceSpec extends AnyFunSuite with Matchers{
//  val linearLocationDAO = new LinearLocationDAO
//  val roadwayDAO = new RoadwayDAO
//  val roadwayPointDAO = new RoadwayPointDAO
//  val kgvRoadLinkClient = new KgvRoadLink
//  val awsService = new AwsService
//  val linkNetworkUpdater = new LinkNetworkUpdater
//
//  val dynamicRoadNetworkService = new DynamicRoadNetworkService(linearLocationDAO, roadwayDAO, kgvRoadLinkClient, awsService, linkNetworkUpdater)
//
//  test("When Validating valid TiekamuRoadLinkChanges Then should not return any TiekamuRoadLinkErrors") {
//    runWithRollback {
//      val linkId1 = "testtest-test-test-test-test:1"
//      val linkId2 = "testtest-test-test-test-test:2"
//      val newLinkId1 = "testtest-test-test-test-test:3"
//      val newLinkId2 = "testtest-test-test-test-test:4"
//      val geometry1 = Seq(Point(0.0, 0.0), Point(0.0,50.0))
//      val geometry2 = Seq(Point(0.0, 50.0), Point(0.0, 100.0))
//      val newGeometry1 = Seq(Point(0.0, 0.0), Point(0.0, 50.0))
//      val newGeometry2 = Seq(Point(0.0, 50.0), Point(0.0, 100.0))
//      val orderNumber1 = 1.0
//      val orderNumber2 = 2.0
//      val roadwayNumber1 = Sequences.nextRoadwayNumber
//
//      // Create Roadways
//      roadwayDAO.create(Seq(
//        Roadway(Sequences.nextRoadwayId, roadwayNumber1, RoadPart(18344, 1), AdministrativeClass.State, Track.Combined,Discontinuity.EndOfRoad, AddrMRange(0, 100), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), 9, 9, TerminationCode.NoTermination, DateTime.now().minusDays(1),None)
//      ))
//
//      // Create LinearLocations
//      val linearLocationIds = linearLocationDAO.create(Seq(
//        LinearLocation(Sequences.nextLinearLocationId, orderNumber1,
//                      linkId1, 0.0, 50.0,
//                      SideCode.TowardsDigitizing, 10000000000L,
//                      (CalibrationPointReference(Some(0), Some(CalibrationPointType.RoadAddressCP)), CalibrationPointReference(None, None)),
//                      geometry1,LinkGeomSource.NormalLinkInterface,
//                      roadwayNumber1,Some(DateTime.now().minusDays(1)), None),
//        LinearLocation(Sequences.nextLinearLocationId, orderNumber2,
//                      linkId2, 0.0, 50.0,
//                      SideCode.TowardsDigitizing, 10000000000L,
//                      (CalibrationPointReference(None, None), CalibrationPointReference(Some(100), Some(CalibrationPointType.RoadAddressCP))),
//                      geometry2, LinkGeomSource.NormalLinkInterface,
//                      roadwayNumber1, Some(DateTime.now().minusDays(1)), None)
//      ))
//      val linearlocations = linearLocationDAO.fetchByIdMassQuery(linearLocationIds)
//
//      // Create RoadwayPoints
//      val roadwayPointId1 = roadwayPointDAO.create(roadwayNumber1, 0, "test")
//      val roadwayPointId2 = roadwayPointDAO.create(roadwayNumber1, 100, "test")
//
//      // Create CalibrationPoints
//      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId1, linkId1, roadwayNumber1, 0,
//                                  CalibrationPointLocation.StartOfLink, CalibrationPointType.RoadAddressCP,
//                                  Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
//      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId2, linkId2, roadwayNumber1, 100,
//                                  CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
//                                  Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
//
//
//      // Create TiekamuRoadLinkChanges
//      val tiekamuRoadLinkChanges = Seq(
//        TiekamuRoadLinkChange(linkId1, 0.0, 50.0, newLinkId1, 0.0, 50.0, digitizationChange = false),
//        TiekamuRoadLinkChange(linkId2, 0.0, 50.0, newLinkId2, 0.0, 50.0, digitizationChange = false)
//      )
//
//      // Create KGVRoadLinks
//      val kgvRoadLinks = Seq(
//        RoadLink(linkId1, geometry1, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
//        RoadLink(linkId2, geometry2, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
//        RoadLink(newLinkId1, newGeometry1, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
//        RoadLink(newLinkId2, newGeometry2, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = "")
//      )
//
//      val complementaryLinks = Seq()
//
//      // The testing itself
//      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges,linearlocations, kgvRoadLinks, complementaryLinks)
//      res.length should be (0)
//    }
//  }
//
//  test("When Validating invalid TiekamuRoadLinkChanges (non-homogeneous and discontinuous combine case) Then should return TiekamuRoadLinkErrors") {
//
//    /**
//     * Link A is on road part 18344/1
//     * Link B is on road part 46001/1
//     *
//     * The link changes:
//     *
//     *         Before:
//     *      A         B
//     *  -------->---------->
//     *
//     *          After:
//     *          C (the goal would be to replace A and B with this one)
//     *  ------------------->
//     */
//    runWithRollback {
//      val linkA = "testtest-test-test-test-test:a"
//      val linkB = "testtest-test-test-test-test:b"
//      val newLinkC = "testtest-test-test-test-test:c"
//      val geometryA = Seq(Point(0.0, 0.0), Point(50.0,0.0))
//      val geometryB = Seq(Point(50.0, 0.0), Point(100.0, 0.0))
//      val newGeometryC = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
//      val orderNumber1 = 1.0
//      val roadwayNumber1 = Sequences.nextRoadwayNumber
//      val roadwayNumber2 = Sequences.nextRoadwayNumber
//      val roadPart1 = RoadPart(18344, 1)
//      val roadPart2 = RoadPart(46001, 1)
//
//      // Create Roadways
//      roadwayDAO.create(Seq(
//        Roadway(Sequences.nextRoadwayId, roadwayNumber1, roadPart1, AdministrativeClass.State, Track.Combined,Discontinuity.EndOfRoad, AddrMRange(0, 50), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), 9, 9,TerminationCode.NoTermination, DateTime.now().minusDays(1),None),
//        Roadway(Sequences.nextRoadwayId, roadwayNumber2, roadPart2, AdministrativeClass.State, Track.Combined,Discontinuity.EndOfRoad, AddrMRange(0, 50), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), 9, 9,TerminationCode.NoTermination, DateTime.now().minusDays(1),None)
//      ))
//
//      // Create LinearLocations
//      val linearLocationIds = linearLocationDAO.create(Seq(
//        LinearLocation(Sequences.nextLinearLocationId, orderNumber1,
//          linkA, 0.0, 50.0,
//          SideCode.TowardsDigitizing, 10000000000L,
//          (CalibrationPointReference(Some(0), Some(CalibrationPointType.RoadAddressCP)), CalibrationPointReference(Some(50), Some(CalibrationPointType.RoadAddressCP))),
//          geometryA,LinkGeomSource.NormalLinkInterface,
//          roadwayNumber1,Some(DateTime.now().minusDays(1)), None),
//        LinearLocation(Sequences.nextLinearLocationId, orderNumber1,
//          linkB, 0.0, 50.0,
//          SideCode.TowardsDigitizing, 10000000000L,
//          (CalibrationPointReference(Some(0), Some(CalibrationPointType.RoadAddressCP)), CalibrationPointReference(Some(50), Some(CalibrationPointType.RoadAddressCP))),
//          geometryB, LinkGeomSource.NormalLinkInterface,
//          roadwayNumber2, Some(DateTime.now().minusDays(1)), None)
//      ))
//      val linearlocations = linearLocationDAO.fetchByIdMassQuery(linearLocationIds)
//
//      // Create RoadwayPoints
//      val roadwayPointId1 = roadwayPointDAO.create(roadwayNumber1, 0, "test")
//      val roadwayPointId2 = roadwayPointDAO.create(roadwayNumber1, 50, "test")
//      val roadwayPointId3 = roadwayPointDAO.create(roadwayNumber2, 0, "test")
//      val roadwayPointId4 = roadwayPointDAO.create(roadwayNumber2, 50, "test")
//
//      // Create CalibrationPoints
//      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId1, linkA, roadwayNumber1, 0,
//        CalibrationPointLocation.StartOfLink, CalibrationPointType.RoadAddressCP,
//        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
//      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId2, linkA, roadwayNumber1, 50,
//        CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
//        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
//      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId3, linkB, roadwayNumber2, 0,
//        CalibrationPointLocation.StartOfLink, CalibrationPointType.RoadAddressCP,
//        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
//      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId4, linkB, roadwayNumber2, 50,
//        CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
//        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
//
//
//      // Create TiekamuRoadLinkChanges
//      val tiekamuRoadLinkChanges = Seq(
//        TiekamuRoadLinkChange(linkA, 0.0, 50.0, newLinkC, 0.0, 50.0, digitizationChange = false),
//        TiekamuRoadLinkChange(linkB, 0.0, 50.0, newLinkC, 50.0, 100.0, digitizationChange = false)
//      )
//
//      // Create KGVRoadLinks
//      val kgvRoadLinks = Seq(
//        RoadLink(linkA, geometryA, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
//        RoadLink(linkB, geometryB, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
//        RoadLink(newLinkC, newGeometryC, 100.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = "")
//      )
//
//      val complementaryLinks = Seq()
//
//      // The testing itself
//      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges,linearlocations, kgvRoadLinks, complementaryLinks)
//      // 4 validation errors,
//      // 2 per link (A and B),
//      // "Road address not continuous, cannot merge links together."
//      // "Two or more links with non-homogeneous road addresses (road number, road part number, track) cannot merge together."
//      res.length should be (4)
//    }
//  }
//
//  test("When Validating invalid TiekamuRoadLinkChanges (active linear location prevents combination of two links) Then should return TiekamuRoadLinkErrors") {
//    /**
//     * Link change A + B = C
//     * Y = Active linear location
//     *
//     *          Before:
//     *
//     *    A             B
//     * ----------->------------>
//     *            ^
//     *            |
//     *            | Y
//     *            |
//     *
//     *           After:
//     *
//     *             C
//     * ------------------------->
//     *             ^
//     *             |
//     *             | Y
//     *             |
//     */
//    runWithRollback {
//      val linkId1 = "testtest-test-test-test-test:1"
//      val linkId2 = "testtest-test-test-test-test:2"
//      val linkId9 = "testtest-test-test-test-test:9"
//      val newLinkId1 = "testtest-test-test-test-test:3"
//      val newLinkId2 = "testtest-test-test-test-test:4"
//      val geometry1 = Seq(Point(0.0, 0.0), Point(50.0,0.0))
//      val geometry2 = Seq(Point(50.0, 0.0), Point(100.0, 0.0))
//      val newGeometry1 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
//      val crossingRoadsGeometry = Seq(Point(50.0, 0.0), Point(50.0, 50.0))
//      val orderNumber1 = 1.0
//      val orderNumber2 = 2.0
//      val roadwayNumber1 = Sequences.nextRoadwayNumber
//      val roadwayNumber2 = Sequences.nextRoadwayNumber
//
//      // Create Roadways
//      roadwayDAO.create(Seq(
//        Roadway(Sequences.nextRoadwayId, roadwayNumber1, RoadPart(18344, 1), AdministrativeClass.State, Track.Combined,Discontinuity.EndOfRoad, AddrMRange(0, 100), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), 9, 9,TerminationCode.NoTermination, DateTime.now().minusDays(1),None),
//        Roadway(Sequences.nextRoadwayId, roadwayNumber2, RoadPart(46021, 1), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0,50), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), 9, 9, TerminationCode.NoTermination, DateTime.now().minusDays(1), None)
//      ))
//
//      // Create LinearLocations
//      val linearLocationIds = linearLocationDAO.create(Seq(
//        LinearLocation(Sequences.nextLinearLocationId, orderNumber1,
//          linkId1, 0.0, 50.0,
//          SideCode.TowardsDigitizing, 10000000000L,
//          (CalibrationPointReference(Some(0), Some(CalibrationPointType.RoadAddressCP)), CalibrationPointReference(Some(50), Some(CalibrationPointType.JunctionPointCP))),
//          geometry1,LinkGeomSource.NormalLinkInterface,
//          roadwayNumber1,Some(DateTime.now().minusDays(1)), None),
//        LinearLocation(Sequences.nextLinearLocationId, orderNumber2,
//          linkId2, 0.0, 50.0,
//          SideCode.TowardsDigitizing, 10000000000L,
//          (CalibrationPointReference(Some(50), Some(CalibrationPointType.JunctionPointCP)), CalibrationPointReference(Some(100), Some(CalibrationPointType.RoadAddressCP))),
//          geometry2, LinkGeomSource.NormalLinkInterface,
//          roadwayNumber1, Some(DateTime.now().minusDays(1)), None),
//        LinearLocation(Sequences.nextLinearLocationId, orderNumber1,
//          linkId9, 0, 50.0,
//          SideCode.TowardsDigitizing, 10000000000L,
//          (CalibrationPointReference(Some(0), Some(CalibrationPointType.JunctionPointCP)), CalibrationPointReference(Some(50), Some(CalibrationPointType.RoadAddressCP))),
//          crossingRoadsGeometry, LinkGeomSource.NormalLinkInterface,
//          roadwayNumber2, Some(DateTime.now().minusDays(1)), None)
//      ))
//      val linearlocations = linearLocationDAO.fetchByIdMassQuery(linearLocationIds)
//
//      // Create RoadwayPoints
//      val roadwayPointId1 = roadwayPointDAO.create(roadwayNumber1, 0, "test")
//      val roadwayPointId5 = roadwayPointDAO.create(roadwayNumber1, 50, "test")
//      val roadwayPointId2 = roadwayPointDAO.create(roadwayNumber1, 100, "test")
//      val roadwayPointId3 = roadwayPointDAO.create(roadwayNumber2, 0, "test")
//      val roadwayPointId4 = roadwayPointDAO.create(roadwayNumber2, 50, "test")
//
//      // RoadAddress Calibration Points
//      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId1, linkId1, roadwayNumber1, 0,
//        CalibrationPointLocation.StartOfLink, CalibrationPointType.RoadAddressCP,
//        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
//      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId2, linkId2, roadwayNumber1, 100,
//        CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
//        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
//      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId4, linkId9, roadwayNumber2, 50,
//        CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
//        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
//
//      // Junction Calibration Points
//      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId5, linkId1, roadwayNumber1, 50,
//        CalibrationPointLocation.EndOfLink, CalibrationPointType.JunctionPointCP,
//        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
//      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId5, linkId2, roadwayNumber1, 50,
//        CalibrationPointLocation.StartOfLink, CalibrationPointType.JunctionPointCP,
//        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
//      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId3, linkId9, roadwayNumber2, 0,
//        CalibrationPointLocation.StartOfLink, CalibrationPointType.JunctionPointCP,
//        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
//
//
//      // Create TiekamuRoadLinkChanges
//      val tiekamuRoadLinkChanges = Seq(
//        TiekamuRoadLinkChange(linkId1, 0.0, 50.0, newLinkId1, 0.0, 50.0, digitizationChange = false),
//        TiekamuRoadLinkChange(linkId2, 0.0, 50.0, newLinkId1, 50.0, 100.0, digitizationChange = false)
//      )
//
//      // Create KGVRoadLinks
//      val kgvRoadLinks = Seq(
//        RoadLink(linkId1, geometry1, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
//        RoadLink(linkId2, geometry2, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
//        RoadLink(linkId9, crossingRoadsGeometry, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
//        RoadLink(newLinkId1, newGeometry1, 100.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = "")
//      )
//
//      val complementaryLinks = Seq()
//
//      // The testing itself
//      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges,linearlocations, kgvRoadLinks, complementaryLinks)
//      // 2 validation errors
//      // 1 for each link (A and B)
//      // Links cannot be merged together, found linear location(s) that connect(s) between the two links. (Cross road case)
//      res.length should be (2)
//    }
//  }
//}
