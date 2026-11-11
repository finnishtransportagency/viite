package fi.vaylavirasto.viite.dynamicnetwork

import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.client.vkm.TiekamuRoadLinkChange
import fi.liikennevirasto.viite.AwsService
import fi.liikennevirasto.viite.dao._
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{AddrMRange, ArealRoadMaintainer, AdministrativeClass, CalibrationPoint, CalibrationPointLocation, CalibrationPointType, Discontinuity, LifecycleStatus, LinkGeomSource, RoadLink, RoadPart, SideCode, Track, TrafficDirection}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback
import org.joda.time.DateTime
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class DynamicRoadNetworkServiceSpec extends AnyFunSuite with Matchers{
  val linearLocationDAO = new LinearLocationDAO
  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val kgvRoadLinkClient = new KgvRoadLink
  val awsService = new AwsService
  val linkNetworkUpdater = new LinkNetworkUpdater

  val dynamicRoadNetworkService = new DynamicRoadNetworkService(linearLocationDAO, roadwayDAO, kgvRoadLinkClient, awsService, linkNetworkUpdater)

  test("When Validating valid TiekamuRoadLinkChanges Then should not return any TiekamuRoadLinkErrors") {
    runWithRollback {
      val linkId1 = "1000test-test-test-test-000000000001:1"
      val linkId2 = "2000test-test-test-test-000000000002:1"
      val newLinkId1 = "3000test-test-test-test-000000000006:1"
      val newLinkId2 = "4000test-test-test-test-000000000007:1"
      val geometry1 = Seq(Point(0.0, 0.0), Point(0.0,50.0))
      val geometry2 = Seq(Point(0.0, 50.0), Point(0.0, 100.0))
      val newGeometry1 = Seq(Point(0.0, 0.0), Point(0.0, 50.0))
      val newGeometry2 = Seq(Point(0.0, 50.0), Point(0.0, 100.0))
      val orderNumber1 = 1.0
      val orderNumber2 = 2.0
      val roadwayNumber1 = Sequences.nextRoadwayNumber

      // Create Roadways
      roadwayDAO.create(Seq(
        Roadway(Sequences.nextRoadwayId, roadwayNumber1, RoadPart(18344, 1), AdministrativeClass.State, Track.Combined,Discontinuity.EndOfRoad, AddrMRange(0, 100), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), ArealRoadMaintainer.apply("EVK1"), TerminationCode.NoTermination, DateTime.now().minusDays(1),None)
      ))

      // Create LinearLocations
      val linearLocationIds = linearLocationDAO.create(Seq(
        LinearLocation(Sequences.nextLinearLocationId, orderNumber1,
                      linkId1, 0.0, 50.0,
                      SideCode.TowardsDigitizing, 10000000000L,
                      (CalibrationPointReference(Some(0), Some(CalibrationPointType.RoadAddressCP)), CalibrationPointReference(None, None)),
                      geometry1,LinkGeomSource.NormalLinkInterface,
                      roadwayNumber1,Some(DateTime.now().minusDays(1)), None),
        LinearLocation(Sequences.nextLinearLocationId, orderNumber2,
                      linkId2, 0.0, 50.0,
                      SideCode.TowardsDigitizing, 10000000000L,
                      (CalibrationPointReference(None, None), CalibrationPointReference(Some(100), Some(CalibrationPointType.RoadAddressCP))),
                      geometry2, LinkGeomSource.NormalLinkInterface,
                      roadwayNumber1, Some(DateTime.now().minusDays(1)), None)
      ))
      val linearlocations = linearLocationDAO.fetchByIdMassQuery(linearLocationIds)

      // Create RoadwayPoints
      val roadwayPointId1 = roadwayPointDAO.create(roadwayNumber1, 0, "test")
      val roadwayPointId2 = roadwayPointDAO.create(roadwayNumber1, 100, "test")

      // Create CalibrationPoints
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId1, linkId1, roadwayNumber1, 0,
                                  CalibrationPointLocation.StartOfLink, CalibrationPointType.RoadAddressCP,
                                  Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId2, linkId2, roadwayNumber1, 100,
                                  CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
                                  Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))


      // Create TiekamuRoadLinkChanges
      val tiekamuRoadLinkChanges = Seq(
        TiekamuRoadLinkChange(linkId1, 0.0, 50.0, newLinkId1, 0.0, 50.0, digitizationChange = false),
        TiekamuRoadLinkChange(linkId2, 0.0, 50.0, newLinkId2, 0.0, 50.0, digitizationChange = false)
      )

      // Create KGVRoadLinks
      val kgvRoadLinks = Seq(
        RoadLink(linkId1, geometry1, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
        RoadLink(linkId2, geometry2, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
        RoadLink(newLinkId1, newGeometry1, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
        RoadLink(newLinkId2, newGeometry2, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = "")
      )

      val complementaryLinks = Seq()

      // The testing itself
      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges,linearlocations, kgvRoadLinks, complementaryLinks)
      res.length should be (0)
    }
  }

  test("When Validating invalid TiekamuRoadLinkChanges (non-homogeneous and discontinuous combine case) Then should return TiekamuRoadLinkErrors") {

    /**
     * Link A is on road part 18344/1
     * Link B is on road part 46001/1
     *
     * The link changes:
     *
     *         Before:
     *      A         B
     *  -------->---------->
     *
     *          After:
     *          C (the goal would be to replace A and B with this one)
     *  ------------------->
     */
    runWithRollback {
      val linkA = "a000test-test-test-test-000000000009:1"
      val linkB = "b000test-test-test-test-000000000010:1"
      val newLinkC = "c000test-test-test-test-000000000011:1"
      val geometryA = Seq(Point(0.0, 0.0), Point(50.0,0.0))
      val geometryB = Seq(Point(50.0, 0.0), Point(100.0, 0.0))
      val newGeometryC = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val orderNumber1 = 1.0
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val roadPart1 = RoadPart(18344, 1)
      val roadPart2 = RoadPart(46001, 1)

      // Create Roadways
      roadwayDAO.create(Seq(
        Roadway(Sequences.nextRoadwayId, roadwayNumber1, roadPart1, AdministrativeClass.State, Track.Combined,Discontinuity.EndOfRoad, AddrMRange(0, 50), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), ArealRoadMaintainer.apply("EVK1"),TerminationCode.NoTermination, DateTime.now().minusDays(1),None),
        Roadway(Sequences.nextRoadwayId, roadwayNumber2, roadPart2, AdministrativeClass.State, Track.Combined,Discontinuity.EndOfRoad, AddrMRange(0, 50), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), ArealRoadMaintainer.apply("EVK1"),TerminationCode.NoTermination, DateTime.now().minusDays(1),None)
      ))

      // Create LinearLocations
      val linearLocationIds = linearLocationDAO.create(Seq(
        LinearLocation(Sequences.nextLinearLocationId, orderNumber1,
          linkA, 0.0, 50.0,
          SideCode.TowardsDigitizing, 10000000000L,
          (CalibrationPointReference(Some(0), Some(CalibrationPointType.RoadAddressCP)), CalibrationPointReference(Some(50), Some(CalibrationPointType.RoadAddressCP))),
          geometryA,LinkGeomSource.NormalLinkInterface,
          roadwayNumber1,Some(DateTime.now().minusDays(1)), None),
        LinearLocation(Sequences.nextLinearLocationId, orderNumber1,
          linkB, 0.0, 50.0,
          SideCode.TowardsDigitizing, 10000000000L,
          (CalibrationPointReference(Some(0), Some(CalibrationPointType.RoadAddressCP)), CalibrationPointReference(Some(50), Some(CalibrationPointType.RoadAddressCP))),
          geometryB, LinkGeomSource.NormalLinkInterface,
          roadwayNumber2, Some(DateTime.now().minusDays(1)), None)
      ))
      val linearlocations = linearLocationDAO.fetchByIdMassQuery(linearLocationIds)

      // Create RoadwayPoints
      val roadwayPointId1 = roadwayPointDAO.create(roadwayNumber1, 0, "test")
      val roadwayPointId2 = roadwayPointDAO.create(roadwayNumber1, 50, "test")
      val roadwayPointId3 = roadwayPointDAO.create(roadwayNumber2, 0, "test")
      val roadwayPointId4 = roadwayPointDAO.create(roadwayNumber2, 50, "test")

      // Create CalibrationPoints
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId1, linkA, roadwayNumber1, 0,
        CalibrationPointLocation.StartOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId2, linkA, roadwayNumber1, 50,
        CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId3, linkB, roadwayNumber2, 0,
        CalibrationPointLocation.StartOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId4, linkB, roadwayNumber2, 50,
        CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))


      // Create TiekamuRoadLinkChanges
      val tiekamuRoadLinkChanges = Seq(
        TiekamuRoadLinkChange(linkA, 0.0, 50.0, newLinkC, 0.0, 50.0, digitizationChange = false),
        TiekamuRoadLinkChange(linkB, 0.0, 50.0, newLinkC, 50.0, 100.0, digitizationChange = false)
      )

      // Create KGVRoadLinks
      val kgvRoadLinks = Seq(
        RoadLink(linkA, geometryA, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
        RoadLink(linkB, geometryB, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
        RoadLink(newLinkC, newGeometryC, 100.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = "")
      )

      val complementaryLinks = Seq()

      // The testing itself
      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges,linearlocations, kgvRoadLinks, complementaryLinks)
      // 4 validation errors,
      // 2 per link (A and B),
      // "Road address not continuous, cannot merge links together."
      // "Two or more links with non-homogeneous road addresses (road number, road part number, track) cannot merge together."
      res.length should be (4)
    }
  }

  test("When Validating a two link merge whose shared boundary carries calibration points Then should return TiekamuRoadLinkErrors") {
    /**
     * Link change A + B = C
     * Y = Active linear location
     *
     *          Before:
     *
     *    A             B
     * ----------->------------>
     *            ^
     *            |
     *            | Y
     *            |
     *
     *           After:
     *
     *             C
     * ------------------------->
     *             ^
     *             |
     *             | Y
     *             |
     */
    runWithRollback {
      val linkId1 = "1000test-test-test-test-000000000001:1"
      val linkId2 = "2000test-test-test-test-000000000002:1"
      val linkId9 = "9000test-test-test-test-000000000008:1"
      val newLinkId1 = "3000test-test-test-test-000000000006:1"
      val newLinkId2 = "4000test-test-test-test-000000000007:1"
      val geometry1 = Seq(Point(0.0, 0.0), Point(50.0,0.0))
      val geometry2 = Seq(Point(50.0, 0.0), Point(100.0, 0.0))
      val newGeometry1 = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val crossingRoadsGeometry = Seq(Point(50.0, 0.0), Point(50.0, 50.0))
      val orderNumber1 = 1.0
      val orderNumber2 = 2.0
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber

      // Create Roadways
      roadwayDAO.create(Seq(
        Roadway(Sequences.nextRoadwayId, roadwayNumber1, RoadPart(18344, 1), AdministrativeClass.State, Track.Combined,Discontinuity.EndOfRoad, AddrMRange(0, 100), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), ArealRoadMaintainer.apply("EVK1"),TerminationCode.NoTermination, DateTime.now().minusDays(1),None),
        Roadway(Sequences.nextRoadwayId, roadwayNumber2, RoadPart(46021, 1), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0,50), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), ArealRoadMaintainer.apply("EVK1"), TerminationCode.NoTermination, DateTime.now().minusDays(1), None)
      ))

      // Create LinearLocations
      val linearLocationIds = linearLocationDAO.create(Seq(
        LinearLocation(Sequences.nextLinearLocationId, orderNumber1,
          linkId1, 0.0, 50.0,
          SideCode.TowardsDigitizing, 10000000000L,
          (CalibrationPointReference(Some(0), Some(CalibrationPointType.RoadAddressCP)), CalibrationPointReference(Some(50), Some(CalibrationPointType.JunctionPointCP))),
          geometry1,LinkGeomSource.NormalLinkInterface,
          roadwayNumber1,Some(DateTime.now().minusDays(1)), None),
        LinearLocation(Sequences.nextLinearLocationId, orderNumber2,
          linkId2, 0.0, 50.0,
          SideCode.TowardsDigitizing, 10000000000L,
          (CalibrationPointReference(Some(50), Some(CalibrationPointType.JunctionPointCP)), CalibrationPointReference(Some(100), Some(CalibrationPointType.RoadAddressCP))),
          geometry2, LinkGeomSource.NormalLinkInterface,
          roadwayNumber1, Some(DateTime.now().minusDays(1)), None),
        LinearLocation(Sequences.nextLinearLocationId, orderNumber1,
          linkId9, 0, 50.0,
          SideCode.TowardsDigitizing, 10000000000L,
          (CalibrationPointReference(Some(0), Some(CalibrationPointType.JunctionPointCP)), CalibrationPointReference(Some(50), Some(CalibrationPointType.RoadAddressCP))),
          crossingRoadsGeometry, LinkGeomSource.NormalLinkInterface,
          roadwayNumber2, Some(DateTime.now().minusDays(1)), None)
      ))
      // Create RoadwayPoints
      val roadwayPointId1 = roadwayPointDAO.create(roadwayNumber1, 0, "test")
      val roadwayPointId5 = roadwayPointDAO.create(roadwayNumber1, 50, "test")
      val roadwayPointId2 = roadwayPointDAO.create(roadwayNumber1, 100, "test")
      val roadwayPointId3 = roadwayPointDAO.create(roadwayNumber2, 0, "test")
      val roadwayPointId4 = roadwayPointDAO.create(roadwayNumber2, 50, "test")

      // RoadAddress Calibration Points
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId1, linkId1, roadwayNumber1, 0,
        CalibrationPointLocation.StartOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId2, linkId2, roadwayNumber1, 100,
        CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId4, linkId9, roadwayNumber2, 50,
        CalibrationPointLocation.EndOfLink, CalibrationPointType.RoadAddressCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))

      // Junction Calibration Points
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId5, linkId1, roadwayNumber1, 50,
        CalibrationPointLocation.EndOfLink, CalibrationPointType.JunctionPointCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId5, linkId2, roadwayNumber1, 50,
        CalibrationPointLocation.StartOfLink, CalibrationPointType.JunctionPointCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId3, linkId9, roadwayNumber2, 0,
        CalibrationPointLocation.StartOfLink, CalibrationPointType.JunctionPointCP,
        Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))

      // Read the linear locations only now: LinearLocationDAO resolves each linear location's
      // calibration points as part of the query, so reading them before the calibration points exist
      // would return them without any.
      val linearlocations = linearLocationDAO.fetchByIdMassQuery(linearLocationIds)

      // Create TiekamuRoadLinkChanges
      val tiekamuRoadLinkChanges = Seq(
        TiekamuRoadLinkChange(linkId1, 0.0, 50.0, newLinkId1, 0.0, 50.0, digitizationChange = false),
        TiekamuRoadLinkChange(linkId2, 0.0, 50.0, newLinkId1, 50.0, 100.0, digitizationChange = false)
      )

      // Create KGVRoadLinks
      val kgvRoadLinks = Seq(
        RoadLink(linkId1, geometry1, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
        RoadLink(linkId2, geometry2, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
        RoadLink(linkId9, crossingRoadsGeometry, 50.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = ""),
        RoadLink(newLinkId1, newGeometry1, 100.0, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None, LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = "")
      )

      val complementaryLinks = Seq()

      // The testing itself
      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges,linearlocations, kgvRoadLinks, complementaryLinks)
      // 2 validation errors, one for each merged link (A and B): both links have a junction
      // calibration point at the shared boundary, so merging them would leave the new link with two
      // start and two end calibration points for roadway 1, which LinearLocationDAO cannot read back.
      res.length should be (2)
      res.map(_.errorMessage).distinct should be (Seq("Links cannot be merged together, the merged links have calibration point(s) on their shared boundary. (Cross road case)"))
    }
  }

  /** Linear location for the merge tests. Calibration points are not read by
   * validateTiekamuRoadLinkChanges, so they are left out here. */
  private def testLinearLocation(orderNumber: Double, linkId: String, length: Double, geometry: Seq[Point],
                                 roadwayNumber: Long, sideCode: SideCode = SideCode.TowardsDigitizing) = {
    LinearLocation(Sequences.nextLinearLocationId, orderNumber,
      linkId, 0.0, length,
      sideCode, 10000000000L,
      (CalibrationPointReference(None, None), CalibrationPointReference(None, None)),
      geometry, LinkGeomSource.NormalLinkInterface,
      roadwayNumber, Some(DateTime.now().minusDays(1)), None)
  }

  private def testKgvRoadLink(linkId: String, geometry: Seq[Point], length: Double) = {
    RoadLink(linkId, geometry, length, AdministrativeClass.State, TrafficDirection.TowardsDigitizing, None, None,
      LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = "")
  }

  // Four links merging into one: A + B + C + D = N, all on the same road part, in address order.
  private val mergeLinkA = "a000test-test-test-test-000000000009:1"
  private val mergeLinkB = "b000test-test-test-test-000000000010:1"
  private val mergeLinkC = "c000test-test-test-test-000000000011:1"
  private val mergeLinkD = "d000test-test-test-test-000000000012:1"
  private val mergeNewLinkN = "n000test-test-test-test-000000000018:1"
  private val mergeGeometryA = Seq(Point(0.0, 0.0), Point(25.0, 0.0))
  private val mergeGeometryB = Seq(Point(25.0, 0.0), Point(50.0, 0.0))
  private val mergeGeometryC = Seq(Point(50.0, 0.0), Point(75.0, 0.0))
  private val mergeGeometryD = Seq(Point(75.0, 0.0), Point(100.0, 0.0))
  private val mergeNewGeometryN = Seq(Point(0.0, 0.0), Point(100.0, 0.0))

  private val fourWayMergeChanges = Seq(
    TiekamuRoadLinkChange(mergeLinkA, 0.0, 25.0, mergeNewLinkN,  0.0,  25.0, digitizationChange = false),
    TiekamuRoadLinkChange(mergeLinkB, 0.0, 25.0, mergeNewLinkN, 25.0,  50.0, digitizationChange = false),
    TiekamuRoadLinkChange(mergeLinkC, 0.0, 25.0, mergeNewLinkN, 50.0,  75.0, digitizationChange = false),
    TiekamuRoadLinkChange(mergeLinkD, 0.0, 25.0, mergeNewLinkN, 75.0, 100.0, digitizationChange = false)
  )

  private val fourWayMergeKgvLinks = Seq(
    testKgvRoadLink(mergeLinkA, mergeGeometryA, 25.0),
    testKgvRoadLink(mergeLinkB, mergeGeometryB, 25.0),
    testKgvRoadLink(mergeLinkC, mergeGeometryC, 25.0),
    testKgvRoadLink(mergeLinkD, mergeGeometryD, 25.0),
    testKgvRoadLink(mergeNewLinkN, mergeNewGeometryN, 100.0)
  )

  /** Creates the roadway and the linear locations of the four merged links, and returns the
   * created linear locations. */
  private def createFourWayMergeRoadAddresses(): (Long, Seq[LinearLocation]) = {
    val roadwayNumber = Sequences.nextRoadwayNumber
    roadwayDAO.create(Seq(
      Roadway(Sequences.nextRoadwayId, roadwayNumber, RoadPart(18344, 1), AdministrativeClass.State, Track.Combined,
        Discontinuity.EndOfRoad, AddrMRange(0, 100), reversed = false, DateTime.now().minusDays(1), None, "test",
        Some("Test road"), ArealRoadMaintainer.apply("EVK1"), TerminationCode.NoTermination, DateTime.now().minusDays(1), None)
    ))
    val linearLocationIds = linearLocationDAO.create(Seq(
      testLinearLocation(1.0, mergeLinkA, 25.0, mergeGeometryA, roadwayNumber),
      testLinearLocation(2.0, mergeLinkB, 25.0, mergeGeometryB, roadwayNumber),
      testLinearLocation(3.0, mergeLinkC, 25.0, mergeGeometryC, roadwayNumber),
      testLinearLocation(4.0, mergeLinkD, 25.0, mergeGeometryD, roadwayNumber)
    ))
    (roadwayNumber, linearLocationDAO.fetchByIdMassQuery(linearLocationIds))
  }

  test("When Validating TiekamuRoadLinkChanges where four links merge into one Then should not return any TiekamuRoadLinkErrors") {
    /**
     *          Before:
     *     A      B      C      D
     *  ----->------->------->------>
     *
     *          After:
     *              N
     *  ---------------------------->
     */
    runWithRollback {
      val (_, linearLocations) = createFourWayMergeRoadAddresses()

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(fourWayMergeChanges, linearLocations, fourWayMergeKgvLinks, Seq())
      res should be (Seq())
    }
  }

  test("When Validating a four link merge with a crossing road at an outer end of the merged links Then should not return any TiekamuRoadLinkErrors") {
    /**
     * The junction at the start of A is not removed by the merge, so it must not prevent it.
     *
     *     A      B      C      D
     *  ----->------->------->------>
     *  ^
     *  | Y
     */
    runWithRollback {
      val (_, mergedLinearLocations) = createFourWayMergeRoadAddresses()

      val crossingLink = "y000test-test-test-test-000000000032:1"
      val crossingGeometry = Seq(Point(0.0, 0.0), Point(0.0, 50.0))
      val crossingRoadwayNumber = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(
        Roadway(Sequences.nextRoadwayId, crossingRoadwayNumber, RoadPart(46021, 1), AdministrativeClass.State,
          Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 50), reversed = false, DateTime.now().minusDays(1),
          None, "test", Some("Test road"), ArealRoadMaintainer.apply("EVK1"), TerminationCode.NoTermination, DateTime.now().minusDays(1), None)
      ))
      val crossingIds = linearLocationDAO.create(Seq(
        testLinearLocation(1.0, crossingLink, 50.0, crossingGeometry, crossingRoadwayNumber)
      ))
      val linearLocations = mergedLinearLocations ++ linearLocationDAO.fetchByIdMassQuery(crossingIds)

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(fourWayMergeChanges, linearLocations,
        fourWayMergeKgvLinks :+ testKgvRoadLink(crossingLink, crossingGeometry, 50.0), Seq())
      res should be (Seq())
    }
  }

  test("When Validating a four link merge with a road merely crossing over an inner point of the merged links Then should not return any TiekamuRoadLinkErrors") {
    /**
     * Y has no end point at the B/C point, it only passes over it, so it is not a junction.
     * Note that LinearLocationDAO.create only stores the first and the last point of the given
     * geometry, so the linear location of Y runs straight from (50,-25) to (50,25).
     *
     *              Y
     *              |
     *     A      B | C      D
     *  ----->------|------>------>
     *              |
     */
    runWithRollback {
      val (_, mergedLinearLocations) = createFourWayMergeRoadAddresses()

      val crossingLink = "y000test-test-test-test-000000000032:1"
      val crossingGeometry = Seq(Point(50.0, -25.0), Point(50.0, 0.0), Point(50.0, 25.0))
      val crossingRoadwayNumber = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(
        Roadway(Sequences.nextRoadwayId, crossingRoadwayNumber, RoadPart(46021, 1), AdministrativeClass.State,
          Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 50), reversed = false, DateTime.now().minusDays(1),
          None, "test", Some("Test road"), ArealRoadMaintainer.apply("EVK1"), TerminationCode.NoTermination, DateTime.now().minusDays(1), None)
      ))
      val crossingIds = linearLocationDAO.create(Seq(
        testLinearLocation(1.0, crossingLink, 50.0, crossingGeometry, crossingRoadwayNumber)
      ))
      val linearLocations = mergedLinearLocations ++ linearLocationDAO.fetchByIdMassQuery(crossingIds)

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(fourWayMergeChanges, linearLocations,
        fourWayMergeKgvLinks :+ testKgvRoadLink(crossingLink, crossingGeometry, 50.0), Seq())
      res should be (Seq())
    }
  }

  test("When Validating a four link merge with an active linear location connected to an inner point of the merged links Then should not return any TiekamuRoadLinkErrors") {
    /**
     * Y ends at the B/C point. That does not prevent the merge: a merge does not fuse the linear
     * locations, it re-creates one per old link on the new link over its own M-range, keeping order
     * numbers, roadway numbers and addresses. Nodes and junctions hold no link reference at all -
     * node_point and junction_point point at a roadway_point (roadway number and address) - so the
     * junction at the B/C address survives whether that address sits at a link boundary or inside a
     * link. Only calibration points, which are link-bound, can block a merge; there are none here.
     *
     *     A      B      C      D
     *  ----->------->------->------>
     *               ^
     *               | Y
     */
    runWithRollback {
      val (_, mergedLinearLocations) = createFourWayMergeRoadAddresses()

      val crossingLink = "y000test-test-test-test-000000000032:1"
      val crossingGeometry = Seq(Point(50.0, 0.0), Point(50.0, 50.0))
      val crossingRoadwayNumber = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(
        Roadway(Sequences.nextRoadwayId, crossingRoadwayNumber, RoadPart(46021, 1), AdministrativeClass.State,
          Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0, 50), reversed = false, DateTime.now().minusDays(1),
          None, "test", Some("Test road"), ArealRoadMaintainer.apply("EVK1"), TerminationCode.NoTermination, DateTime.now().minusDays(1), None)
      ))
      val crossingIds = linearLocationDAO.create(Seq(
        testLinearLocation(1.0, crossingLink, 50.0, crossingGeometry, crossingRoadwayNumber)
      ))
      val linearLocations = mergedLinearLocations ++ linearLocationDAO.fetchByIdMassQuery(crossingIds)

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(fourWayMergeChanges, linearLocations,
        fourWayMergeKgvLinks :+ testKgvRoadLink(crossingLink, crossingGeometry, 50.0), Seq())
      res should be (Seq())
    }
  }

  test("When Validating a four link merge whose inner boundaries carry calibration points Then should return TiekamuRoadLinkErrors") {
    /**
     * The real case on road 24 part 12: A + B + C + D = N, with a junction calibration point at each
     * of the three inner boundaries. Merging would give roadway 1 three start and three end
     * calibration points on the new link, and LinearLocationDAO reads a linear location's calibration
     * points with a scalar subquery keyed on (link_id, roadway_number, start_end) - more than one of
     * either makes that query fail. So the merge has to be refused.
     *
     *     A      B      C      D
     *  ----->--|--->--|--->--|--->
     *          CP     CP     CP
     */
    runWithRollback {
      val (roadwayNumber, _) = createFourWayMergeRoadAddresses()

      // A junction calibration point at each inner boundary: end of the earlier link, start of the later.
      Seq((mergeLinkA, mergeLinkB, 25L), (mergeLinkB, mergeLinkC, 50L), (mergeLinkC, mergeLinkD, 75L)).foreach {
        case (earlierLink, laterLink, addrM) =>
          val roadwayPointId = roadwayPointDAO.create(roadwayNumber, addrM, "test")
          CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId, earlierLink, roadwayNumber, addrM,
            CalibrationPointLocation.EndOfLink, CalibrationPointType.JunctionPointCP,
            Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
          CalibrationPointDAO.create(CalibrationPoint(Sequences.nextCalibrationPointId, roadwayPointId, laterLink, roadwayNumber, addrM,
            CalibrationPointLocation.StartOfLink, CalibrationPointType.JunctionPointCP,
            Some(DateTime.now().minusDays(1)), None, "Test", Some(DateTime.now())))
      }

      // Read the linear locations only after the calibration points exist (see LinearLocationDAO).
      val linearLocations = linearLocationDAO.fetchByLinkId(
        Set(mergeLinkA, mergeLinkB, mergeLinkC, mergeLinkD))

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(fourWayMergeChanges, linearLocations, fourWayMergeKgvLinks, Seq())
      // One error per merged link
      res.length should be (4)
      res.map(_.errorMessage).distinct should be (Seq("Links cannot be merged together, the merged links have calibration point(s) on their shared boundary. (Cross road case)"))
    }
  }

  test("When Validating a four link merge where one link is digitized in the opposite direction Then should not return any TiekamuRoadLinkErrors") {
    /**
     * C is digitized against the new link, so its change row has a descending old M-range
     * (oldStartM 25 > oldEndM 0) and digitizationChange = true. The road addresses are continuous
     * all the same, so the merge must be accepted.
     *
     *     A      B      C      D
     *  ----->------->-------<------>
     */
    runWithRollback {
      val (_, linearLocations) = createFourWayMergeRoadAddresses()

      val changes = Seq(
        TiekamuRoadLinkChange(mergeLinkA,  0.0, 25.0, mergeNewLinkN,  0.0,  25.0, digitizationChange = false),
        TiekamuRoadLinkChange(mergeLinkB,  0.0, 25.0, mergeNewLinkN, 25.0,  50.0, digitizationChange = false),
        TiekamuRoadLinkChange(mergeLinkC, 25.0,  0.0, mergeNewLinkN, 50.0,  75.0, digitizationChange = true),
        TiekamuRoadLinkChange(mergeLinkD,  0.0, 25.0, mergeNewLinkN, 75.0, 100.0, digitizationChange = false)
      )

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(changes, linearLocations, fourWayMergeKgvLinks, Seq())
      res should be (Seq())
    }
  }

  test("When Validating a two link merge where the latter link is digitized in the opposite direction Then should not return any TiekamuRoadLinkErrors") {
    /**
     * A + B = C, B digitized against C, so B's change row has a descending old M-range.
     *
     *      A        B
     *  ------->-------<
     */
    runWithRollback {
      val linkA = "2000test-test-test-test-000000000002:1a"
      val linkB = "2000test-test-test-test-000000000002:1b"
      val newLinkC = "2000test-test-test-test-000000000002:1c"
      val geometryA = Seq(Point(0.0, 0.0), Point(50.0, 0.0))
      val geometryB = Seq(Point(50.0, 0.0), Point(100.0, 0.0))
      val newGeometryC = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber

      roadwayDAO.create(Seq(
        Roadway(Sequences.nextRoadwayId, roadwayNumber, RoadPart(18344, 1), AdministrativeClass.State, Track.Combined,
          Discontinuity.EndOfRoad, AddrMRange(0, 100), reversed = false, DateTime.now().minusDays(1), None, "test",
          Some("Test road"), ArealRoadMaintainer.apply("EVK1"), TerminationCode.NoTermination, DateTime.now().minusDays(1), None)
      ))
      val linearLocationIds = linearLocationDAO.create(Seq(
        testLinearLocation(1.0, linkA, 50.0, geometryA, roadwayNumber),
        testLinearLocation(2.0, linkB, 50.0, geometryB, roadwayNumber)
      ))
      val linearLocations = linearLocationDAO.fetchByIdMassQuery(linearLocationIds)

      val changes = Seq(
        TiekamuRoadLinkChange(linkA,  0.0, 50.0, newLinkC,  0.0,  50.0, digitizationChange = false),
        TiekamuRoadLinkChange(linkB, 50.0,  0.0, newLinkC, 50.0, 100.0, digitizationChange = true)
      )
      val kgvRoadLinks = Seq(
        testKgvRoadLink(linkA, geometryA, 50.0),
        testKgvRoadLink(linkB, geometryB, 50.0),
        testKgvRoadLink(newLinkC, newGeometryC, 100.0)
      )

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(changes, linearLocations, kgvRoadLinks, Seq())
      res should be (Seq())
    }
  }

  /** Linear location with an explicit M-range, for links that carry several linear locations. */
  private def testLinearLocationWithM(orderNumber: Double, linkId: String, startM: Double, endM: Double,
                                      geometry: Seq[Point], roadwayNumber: Long,
                                      sideCode: SideCode = SideCode.TowardsDigitizing) = {
    LinearLocation(Sequences.nextLinearLocationId, orderNumber,
      linkId, startM, endM,
      sideCode, 10000000000L,
      (CalibrationPointReference(None, None), CalibrationPointReference(None, None)),
      geometry, LinkGeomSource.NormalLinkInterface,
      roadwayNumber, Some(DateTime.now().minusDays(1)), None)
  }

  private def testRoadway(roadwayNumber: Long, roadPart: RoadPart, addrMRange: AddrMRange,
                          track: Track = Track.Combined) = {
    Roadway(Sequences.nextRoadwayId, roadwayNumber, roadPart, AdministrativeClass.State, track,
      Discontinuity.EndOfRoad, addrMRange, reversed = false, DateTime.now().minusDays(1), None, "test",
      Some("Test road"), ArealRoadMaintainer.apply("EVK1"), TerminationCode.NoTermination, DateTime.now().minusDays(1), None)
  }

  test("When Validating a whole-link change whose Tiekamu M-length differs slightly from the link length Then should not return any TiekamuRoadLinkErrors") {
    /**
     * Tiekamu M-values and the link length Viite's linear locations are measured in are two
     * different M-spaces for the same link, and they disagree by up to a few metres on some links.
     * Here the whole link is changed, Tiekamu just measures it as 98.5 m instead of 100 m.
     */
    runWithRollback {
      val linkA = "m100test-test-test-test-000000000016:1"
      val newLinkN = "m200test-test-test-test-000000000017:1"
      val geometryA = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val newGeometryN = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber

      roadwayDAO.create(Seq(testRoadway(roadwayNumber, RoadPart(18344, 1), AddrMRange(0, 100))))
      val linearLocationIds = linearLocationDAO.create(Seq(
        testLinearLocation(1.0, linkA, 100.0, geometryA, roadwayNumber)
      ))
      val linearLocations = linearLocationDAO.fetchByIdMassQuery(linearLocationIds)

      val changes = Seq(TiekamuRoadLinkChange(linkA, 0.0, 98.5, newLinkN, 0.0, 98.5, digitizationChange = false))
      val kgvRoadLinks = Seq(
        testKgvRoadLink(linkA, geometryA, 100.0),
        testKgvRoadLink(newLinkN, newGeometryN, 98.5)
      )

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(changes, linearLocations, kgvRoadLinks, Seq())
      res should be (Seq())
    }
  }

  test("When Validating a split into two new links whose M-lengths differ slightly from the old link Then should not return any TiekamuRoadLinkErrors") {
    /**
     * A = N1 + N2. The two changes cover the old link end to end, but the summed Tiekamu M-length
     * (328.023) is 1.5 m short of the length the old link is measured in in Viite (329.53).
     * Summing the change lengths and comparing them exactly reported a partial change for both rows.
     */
    runWithRollback {
      val linkA = "s100test-test-test-test-000000000022:1"
      val newLinkN1 = "s200test-test-test-test-000000000023:1"
      val newLinkN2 = "s300test-test-test-test-000000000024:1"
      val geometryA = Seq(Point(0.0, 0.0), Point(329.53, 0.0))
      val newGeometryN1 = Seq(Point(0.0, 0.0), Point(310.901, 0.0))
      val newGeometryN2 = Seq(Point(310.901, 0.0), Point(329.53, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber

      roadwayDAO.create(Seq(testRoadway(roadwayNumber, RoadPart(18344, 1), AddrMRange(0, 330))))
      val linearLocationIds = linearLocationDAO.create(Seq(
        testLinearLocation(1.0, linkA, 329.53, geometryA, roadwayNumber)
      ))
      val linearLocations = linearLocationDAO.fetchByIdMassQuery(linearLocationIds)

      val changes = Seq(
        TiekamuRoadLinkChange(linkA,   0.0, 309.479, newLinkN1, 0.0, 310.901, digitizationChange = false),
        TiekamuRoadLinkChange(linkA, 309.479, 328.023, newLinkN2, 0.0, 18.629, digitizationChange = false)
      )
      val kgvRoadLinks = Seq(
        testKgvRoadLink(linkA, geometryA, 329.53),
        testKgvRoadLink(newLinkN1, newGeometryN1, 310.901),
        testKgvRoadLink(newLinkN2, newGeometryN2, 18.629)
      )

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(changes, linearLocations, kgvRoadLinks, Seq())
      res should be (Seq())
    }
  }

  test("When Validating changes that leave a gap in the middle of the old link Then should return TiekamuRoadLinkErrors") {
    /**
     * A genuine partial change: the middle of the old link has no change applied to it. The summed
     * change length must not be mistaken for coverage.
     *
     *   0        50               200        300
     *   |--> N1 --|                |--> N2 ---|
     */
    runWithRollback {
      val linkA = "g100test-test-test-test-000000000013:1"
      val newLinkN1 = "g200test-test-test-test-000000000014:1"
      val newLinkN2 = "g300test-test-test-test-000000000015:1"
      val geometryA = Seq(Point(0.0, 0.0), Point(300.0, 0.0))
      val newGeometryN1 = Seq(Point(0.0, 0.0), Point(50.0, 0.0))
      val newGeometryN2 = Seq(Point(200.0, 0.0), Point(300.0, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber

      roadwayDAO.create(Seq(testRoadway(roadwayNumber, RoadPart(18344, 1), AddrMRange(0, 300))))
      val linearLocationIds = linearLocationDAO.create(Seq(
        testLinearLocation(1.0, linkA, 300.0, geometryA, roadwayNumber)
      ))
      val linearLocations = linearLocationDAO.fetchByIdMassQuery(linearLocationIds)

      val changes = Seq(
        TiekamuRoadLinkChange(linkA,   0.0,  50.0, newLinkN1, 0.0,  50.0, digitizationChange = false),
        TiekamuRoadLinkChange(linkA, 200.0, 300.0, newLinkN2, 0.0, 100.0, digitizationChange = false)
      )
      val kgvRoadLinks = Seq(
        testKgvRoadLink(linkA, geometryA, 300.0),
        testKgvRoadLink(newLinkN1, newGeometryN1, 50.0),
        testKgvRoadLink(newLinkN2, newGeometryN2, 100.0)
      )

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(changes, linearLocations, kgvRoadLinks, Seq())
      res.length should be (2)
      res.map(_.errorMessage).distinct should be (Seq("No partial changes allowed. The old link needs to have changes applied to the whole length of the old link"))
    }
  }

  test("When Validating a change into a new link that also receives an unaddressed old link Then should not return any TiekamuRoadLinkErrors") {
    /**
     * U + A = N, where U has no road address, so its change row is not part of the change set that
     * gets applied. The new link is still covered end to end, which is only visible when the whole
     * change set is given.
     *
     *     U        A
     *  ------->---------->
     *          N
     *  ------------------>
     */
    runWithRollback {
      val unaddressedLink = "u100test-test-test-test-000000000026:1"
      val linkA = "u200test-test-test-test-000000000027:1"
      val newLinkN = "u300test-test-test-test-000000000028:1"
      val geometryU = Seq(Point(0.0, 0.0), Point(14.156, 0.0))
      val geometryA = Seq(Point(14.156, 0.0), Point(81.792, 0.0))
      val newGeometryN = Seq(Point(0.0, 0.0), Point(81.792, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber

      roadwayDAO.create(Seq(testRoadway(roadwayNumber, RoadPart(18344, 1), AddrMRange(0, 68))))
      val linearLocationIds = linearLocationDAO.create(Seq(
        testLinearLocation(1.0, linkA, 67.636, geometryA, roadwayNumber)
      ))
      val linearLocations = linearLocationDAO.fetchByIdMassQuery(linearLocationIds)

      val addressedChanges = Seq(
        TiekamuRoadLinkChange(linkA, 0.0, 67.636, newLinkN, 14.156, 81.792, digitizationChange = false)
      )
      val allChanges = TiekamuRoadLinkChange(unaddressedLink, 0.0, 14.156, newLinkN, 0.0, 14.156, digitizationChange = false) +: addressedChanges
      val kgvRoadLinks = Seq(
        testKgvRoadLink(unaddressedLink, geometryU, 14.156),
        testKgvRoadLink(linkA, geometryA, 67.636),
        testKgvRoadLink(newLinkN, newGeometryN, 81.792)
      )

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(addressedChanges, linearLocations, kgvRoadLinks, Seq(), allChanges)
      res should be (Seq())

      // Without the unaddressed sibling the new link genuinely looks partially covered.
      val resWithoutSibling = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(addressedChanges, linearLocations, kgvRoadLinks, Seq())
      resWithoutSibling.map(_.errorMessage) should be (Seq("No partial changes allowed. The new link needs to have changes applied to the whole length of the new link"))
    }
  }

  test("When Validating a four link merge with a linear location of the same road part at an inner point Then should not return any TiekamuRoadLinkErrors") {
    /**
     * The other track of the same road part ends at the B/C point. It is part of the same road
     * address, not a crossing road, so it must not prevent the merge.
     *
     *     A      B      C      D     (track 1)
     *  ----->------->------->------>
     *               ^
     *               | T              (track 2, same road part)
     */
    runWithRollback {
      val (_, mergedLinearLocations) = createFourWayMergeRoadAddresses()

      val otherTrackLink = "t000test-test-test-test-000000000025:1"
      val otherTrackGeometry = Seq(Point(50.0, 0.0), Point(50.0, 50.0))
      val otherTrackRoadwayNumber = Sequences.nextRoadwayNumber
      roadwayDAO.create(Seq(
        testRoadway(otherTrackRoadwayNumber, RoadPart(18344, 1), AddrMRange(0, 50), Track.LeftSide)
      ))
      val otherTrackIds = linearLocationDAO.create(Seq(
        testLinearLocation(1.0, otherTrackLink, 50.0, otherTrackGeometry, otherTrackRoadwayNumber)
      ))
      val linearLocations = mergedLinearLocations ++ linearLocationDAO.fetchByIdMassQuery(otherTrackIds)

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(fourWayMergeChanges, linearLocations,
        fourWayMergeKgvLinks :+ testKgvRoadLink(otherTrackLink, otherTrackGeometry, 50.0), Seq())
      res should be (Seq())
    }
  }

  test("When Validating a merge where the merged link carries two roadways and connects at its far end Then should not return any TiekamuRoadLinkErrors") {
    /**
     * A + B = N. A is digitized against the address direction and carries two roadways, so the
     * roadway with the smallest M on A (rw3) is the one furthest from B. Checking only that first
     * roadway reported the road address as discontinuous even though rw2 continues into B's rw1.
     *
     *   addresses:  150 <---- 100 <---- 50 <---- 0
     *                    A (rw3, rw2)      B (rw1)
     *   geometry:   0 ------------> 100 ------> 150
     */
    runWithRollback {
      val linkA = "r100test-test-test-test-000000000019:1"
      val linkB = "r200test-test-test-test-000000000020:1"
      val newLinkN = "r300test-test-test-test-000000000021:1"
      val geometryAFirstHalf = Seq(Point(0.0, 0.0), Point(50.0, 0.0))
      val geometryASecondHalf = Seq(Point(50.0, 0.0), Point(100.0, 0.0))
      val geometryA = Seq(Point(0.0, 0.0), Point(100.0, 0.0))
      val geometryB = Seq(Point(100.0, 0.0), Point(150.0, 0.0))
      val newGeometryN = Seq(Point(0.0, 0.0), Point(150.0, 0.0))
      val roadwayNumber1 = Sequences.nextRoadwayNumber
      val roadwayNumber2 = Sequences.nextRoadwayNumber
      val roadwayNumber3 = Sequences.nextRoadwayNumber

      roadwayDAO.create(Seq(
        testRoadway(roadwayNumber1, RoadPart(18344, 1), AddrMRange(0, 50)),
        testRoadway(roadwayNumber2, RoadPart(18344, 1), AddrMRange(50, 100)),
        testRoadway(roadwayNumber3, RoadPart(18344, 1), AddrMRange(100, 150))
      ))
      val linearLocationIds = linearLocationDAO.create(Seq(
        testLinearLocationWithM(1.0, linkB,  0.0,  50.0, geometryB, roadwayNumber1, SideCode.AgainstDigitizing),
        testLinearLocationWithM(1.0, linkA, 50.0, 100.0, geometryASecondHalf, roadwayNumber2, SideCode.AgainstDigitizing),
        testLinearLocationWithM(1.0, linkA,  0.0,  50.0, geometryAFirstHalf, roadwayNumber3, SideCode.AgainstDigitizing)
      ))
      val linearLocations = linearLocationDAO.fetchByIdMassQuery(linearLocationIds)

      val changes = Seq(
        TiekamuRoadLinkChange(linkA, 0.0, 100.0, newLinkN,   0.0, 100.0, digitizationChange = false),
        TiekamuRoadLinkChange(linkB, 0.0,  50.0, newLinkN, 100.0, 150.0, digitizationChange = false)
      )
      val kgvRoadLinks = Seq(
        testKgvRoadLink(linkA, geometryA, 100.0),
        testKgvRoadLink(linkB, geometryB, 50.0),
        testKgvRoadLink(newLinkN, newGeometryN, 150.0)
      )

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(changes, linearLocations, kgvRoadLinks, Seq())
      res should be (Seq())
    }
  }

  test("When Validating a whole-link change into a new link that the whole change set overshoots Then should not return any TiekamuRoadLinkErrors") {
    /**
     * A = N covers the new link exactly. The whole change set also holds a row from an unaddressed
     * link U into the same new link, with M-values that reach past the new link's length. The change
     * to be applied already spans the new link, so the overshooting row must not turn it into an
     * error: the whole change set is only consulted when the applied changes do not span the link.
     */
    runWithRollback {
      val linkA = "v100test-test-test-test-000000000029:1"
      val newLinkN = "v200test-test-test-test-000000000030:1"
      val unaddressedLink = "v300test-test-test-test-000000000031:1"
      val geometryA = Seq(Point(0.0, 0.0), Point(34.817, 0.0))
      val newGeometryN = Seq(Point(0.0, 0.0), Point(34.817, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber

      roadwayDAO.create(Seq(testRoadway(roadwayNumber, RoadPart(18344, 1), AddrMRange(0, 35))))
      val linearLocationIds = linearLocationDAO.create(Seq(
        testLinearLocation(1.0, linkA, 34.817, geometryA, roadwayNumber)
      ))
      val linearLocations = linearLocationDAO.fetchByIdMassQuery(linearLocationIds)

      val addressedChanges = Seq(
        TiekamuRoadLinkChange(linkA, 0.0, 34.817, newLinkN, 0.0, 34.817, digitizationChange = false)
      )
      val allChanges = addressedChanges :+
        TiekamuRoadLinkChange(unaddressedLink, 0.0, 15.183, newLinkN, 34.817, 50.0, digitizationChange = false)
      val kgvRoadLinks = Seq(
        testKgvRoadLink(linkA, geometryA, 34.817),
        testKgvRoadLink(newLinkN, newGeometryN, 34.817)
      )

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(addressedChanges, linearLocations, kgvRoadLinks, Seq(), allChanges)
      res should be (Seq())
    }
  }

  test("When Validating an old link whose remaining part was removed Then should not return any TiekamuRoadLinkErrors") {
    /**
     * The old link is 192.953 m of road address. Its first 166.917 m are replaced by a new link and
     * the rest is deleted, which Tiekamu reports as a row with no new link at all. Without that row
     * the old link looks like it was only partially changed.
     */
    runWithRollback {
      val linkA = "a100test-test-test-test-000000000101:1"
      val newLinkN = "a200test-test-test-test-000000000102:1"
      val geometryA = Seq(Point(0.0, 0.0), Point(192.953, 0.0))
      val newGeometryN = Seq(Point(0.0, 0.0), Point(166.917, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber

      roadwayDAO.create(Seq(testRoadway(roadwayNumber, RoadPart(18344, 1), AddrMRange(0, 193))))
      val linearLocations = linearLocationDAO.fetchByIdMassQuery(linearLocationDAO.create(Seq(
        testLinearLocation(1.0, linkA, 192.953, geometryA, roadwayNumber)
      )))

      val addressedChanges = Seq(
        TiekamuRoadLinkChange(linkA, 0.0, 166.917, newLinkN, 0.0, 166.917, digitizationChange = false)
      )
      // The removed piece: no new link id.
      val allChanges = addressedChanges :+
        TiekamuRoadLinkChange(linkA, 166.917, 192.953, null, 0.0, 0.0, digitizationChange = false)
      val kgvRoadLinks = Seq(
        testKgvRoadLink(linkA, geometryA, 192.953),
        testKgvRoadLink(newLinkN, newGeometryN, 166.917)
      )

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(addressedChanges, linearLocations, kgvRoadLinks, Seq(), allChanges)
      res should be (Seq())

      // Without the removal row the old link is only changed for 166.917 m of its 192.953 m.
      val resWithoutRemoval = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(addressedChanges, linearLocations, kgvRoadLinks, Seq())
      resWithoutRemoval.map(_.errorMessage) should be (Seq("No partial changes allowed. The old link needs to have changes applied to the whole length of the old link"))
    }
  }

  test("When Validating a new link whose remaining part is brand new geometry Then should not return any TiekamuRoadLinkErrors") {
    /**
     * The new link is 47.982 m long. Its last 37.568 m come from the old link, the first 10.414 m are
     * new geometry with no predecessor at all, which Tiekamu reports as a row with no old link.
     */
    runWithRollback {
      val linkA = "b100test-test-test-test-000000000103:1"
      val newLinkN = "b200test-test-test-test-000000000104:1"
      val geometryA = Seq(Point(10.414, 0.0), Point(47.982, 0.0))
      val newGeometryN = Seq(Point(0.0, 0.0), Point(47.982, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber

      roadwayDAO.create(Seq(testRoadway(roadwayNumber, RoadPart(18344, 1), AddrMRange(0, 38))))
      val linearLocations = linearLocationDAO.fetchByIdMassQuery(linearLocationDAO.create(Seq(
        testLinearLocation(1.0, linkA, 37.568, geometryA, roadwayNumber)
      )))

      val addressedChanges = Seq(
        TiekamuRoadLinkChange(linkA, 0.0, 37.568, newLinkN, 10.414, 47.982, digitizationChange = false)
      )
      // The brand new piece: no old link id.
      val allChanges = addressedChanges :+
        TiekamuRoadLinkChange(null, 0.0, 0.0, newLinkN, 0.0, 10.414, digitizationChange = false)
      val kgvRoadLinks = Seq(
        testKgvRoadLink(linkA, geometryA, 37.568),
        testKgvRoadLink(newLinkN, newGeometryN, 47.982)
      )

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(addressedChanges, linearLocations, kgvRoadLinks, Seq(), allChanges)
      res should be (Seq())
    }
  }

  test("When Validating a new link whose other piece arrives under a non-numeric version token Then should not return any TiekamuRoadLinkErrors") {
    /**
     * Tiekamu does not always put the KGV link version in the link id: the pieces of one physical new
     * link can arrive as "<uuid>:1" and "<uuid>:b", and KGV knows only the numeric one. Here 10.988 m
     * of the 292.920 m new link comes from the addressed old link as "<uuid>:1", and the other
     * 281.932 m from an unaddressed old link as "<uuid>:b".
     */
    runWithRollback {
      val linkA = "c100test-test-test-test-000000000105:1"
      val unaddressedLink = "c200test-test-test-test-000000000106:1"
      val newLinkUuid = "c300test-test-test-test-000000000107"
      val newLinkN = s"$newLinkUuid:1"
      val newLinkOtherToken = s"$newLinkUuid:b"
      val geometryA = Seq(Point(281.932, 0.0), Point(292.920, 0.0))
      val newGeometryN = Seq(Point(0.0, 0.0), Point(292.920, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber

      roadwayDAO.create(Seq(testRoadway(roadwayNumber, RoadPart(18344, 1), AddrMRange(0, 11))))
      val linearLocations = linearLocationDAO.fetchByIdMassQuery(linearLocationDAO.create(Seq(
        testLinearLocation(1.0, linkA, 10.988, geometryA, roadwayNumber)
      )))

      val addressedChanges = Seq(
        TiekamuRoadLinkChange(linkA, 0.0, 10.988, newLinkN, 281.932, 292.920, digitizationChange = false)
      )
      val allChanges = addressedChanges :+
        TiekamuRoadLinkChange(unaddressedLink, 0.0, 281.932, newLinkOtherToken, 0.0, 281.932, digitizationChange = false)
      // KGV only knows the numeric version of the new link.
      val kgvRoadLinks = Seq(
        testKgvRoadLink(linkA, geometryA, 10.988),
        testKgvRoadLink(newLinkN, newGeometryN, 292.920)
      )

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(addressedChanges, linearLocations, kgvRoadLinks, Seq(), allChanges)
      res should be (Seq())
    }
  }

  test("When Validating a new link whose pieces are measured on their own M-scales Then should not return any TiekamuRoadLinkErrors") {
    /**
     * Both pieces of the 41.745 m new link report their M-values from zero — one 0-22.255 under
     * "<uuid>:1", the other 0-19.490 under "<uuid>:c" — so they cannot be laid out side by side. Their
     * lengths still add up to the new link's length, which is what the coverage check compares.
     */
    runWithRollback {
      val linkA = "d100test-test-test-test-000000000108:1"
      val unaddressedLink = "d200test-test-test-test-000000000109:1"
      val newLinkUuid = "d300test-test-test-test-000000000110"
      val newLinkN = s"$newLinkUuid:1"
      val newLinkOtherToken = s"$newLinkUuid:c"
      val geometryA = Seq(Point(0.0, 0.0), Point(22.255, 0.0))
      val newGeometryN = Seq(Point(0.0, 0.0), Point(41.745, 0.0))
      val roadwayNumber = Sequences.nextRoadwayNumber

      roadwayDAO.create(Seq(testRoadway(roadwayNumber, RoadPart(18344, 1), AddrMRange(0, 22))))
      val linearLocations = linearLocationDAO.fetchByIdMassQuery(linearLocationDAO.create(Seq(
        testLinearLocation(1.0, linkA, 22.255, geometryA, roadwayNumber)
      )))

      val addressedChanges = Seq(
        TiekamuRoadLinkChange(linkA, 0.0, 22.255, newLinkN, 0.0, 22.255, digitizationChange = false)
      )
      val allChanges = addressedChanges :+
        TiekamuRoadLinkChange(unaddressedLink, 0.0, 19.490, newLinkOtherToken, 0.0, 19.490, digitizationChange = false)
      val kgvRoadLinks = Seq(
        testKgvRoadLink(linkA, geometryA, 22.255),
        testKgvRoadLink(newLinkN, newGeometryN, 41.745)
      )

      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(addressedChanges, linearLocations, kgvRoadLinks, Seq(), allChanges)
      res should be (Seq())

      // A new link that is genuinely only half accounted for must still fail.
      val resHalfCovered = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(addressedChanges, linearLocations, kgvRoadLinks, Seq())
      resHalfCovered.map(_.errorMessage) should be (Seq("No partial changes allowed. The new link needs to have changes applied to the whole length of the new link"))
    }
  }

  test("When a Tiekamu link id carries a version token KGV does not know Then it is resolved to the KGV version valid on the target date") {
    /**
     * Real case from the 20.10.2025-31.12.2025 run: Tiekamu reported the new link as
     * "76bfa293-...:a". KGV knows two versions of that kmtkid, ":1" starting 2025-06-06 and ":2"
     * starting 2026-02-13. The target date 31.12.2025 falls inside ":1".
     */
    val kmtkId = "76bfa293-2675-4189-ad3a-abaf7af312f2"
    val geometry = Seq(Point(0.0, 0.0), Point(54.922, 0.0))
    def versionOf(version: Int, startTime: String) =
      RoadLink(s"$kmtkId:$version", geometry, 54.92184602, AdministrativeClass.State, TrafficDirection.TowardsDigitizing,
        Some(startTime), Some("KGV"), LifecycleStatus.InUse, LinkGeomSource.NormalLinkInterface, 9, sourceId = "")

    val versions = Seq(versionOf(1, "2025-06-06T13:28:46.000Z"), versionOf(2, "2026-02-13T14:30:56.000Z"))
    val targetDate = new DateTime(2025, 12, 31, 0, 0)

    dynamicRoadNetworkService.pickVersionValidOn(versions, targetDate).map(_.linkId) should be (Some(s"$kmtkId:1"))
    // On a later target date the newer version is the one in force.
    dynamicRoadNetworkService.pickVersionValidOn(versions, new DateTime(2026, 3, 1, 0, 0)).map(_.linkId) should be (Some(s"$kmtkId:2"))
    // Before any version started, the earliest one is used rather than nothing.
    dynamicRoadNetworkService.pickVersionValidOn(versions, new DateTime(2025, 1, 1, 0, 0)).map(_.linkId) should be (Some(s"$kmtkId:1"))
  }

  test("When new link ids have been resolved Then the change rows are rewritten to use them") {
    val unresolvedId = "76bfa293-2675-4189-ad3a-abaf7af312f2:a"
    val resolvedId = "76bfa293-2675-4189-ad3a-abaf7af312f2:1"
    val oldLinkId = "e100test-test-test-test-000000000111:1"
    val untouchedNewLinkId = "e200test-test-test-test-000000000112:1"

    val changes = Seq(
      TiekamuRoadLinkChange(oldLinkId, 0.0, 54.922, unresolvedId, 0.0, 54.922, digitizationChange = false),
      TiekamuRoadLinkChange(oldLinkId, 0.0, 10.0, untouchedNewLinkId, 0.0, 10.0, digitizationChange = false)
    )

    val rewritten = dynamicRoadNetworkService.withResolvedNewLinkIds(changes, Map(unresolvedId -> resolvedId))
    rewritten.map(_.newLinkId) should be (Seq(resolvedId, untouchedNewLinkId))
    // Nothing else about the rows changes, and the old link ids are never rewritten.
    rewritten.map(_.oldLinkId).distinct should be (Seq(oldLinkId))
    rewritten.map(ch => (ch.oldStartM, ch.oldEndM, ch.newStartM, ch.newEndM)) should be (
      changes.map(ch => (ch.oldStartM, ch.oldEndM, ch.newStartM, ch.newEndM)))
    // An empty resolution map leaves the rows untouched.
    dynamicRoadNetworkService.withResolvedNewLinkIds(changes, Map.empty) should be (changes)
  }

  test("When resolving a link id turns a change into a no-op Then the row is dropped instead of reported as having no changes") {
    /**
     * Real case from road 70180/211: Tiekamu reported "76bfa293-...:1 -> 76bfa293-...:a" with identical
     * M-values. KGV knows no ":a" version, and ":1" is valid on both run dates, so resolving the new
     * link id turns the row into "...:1 -> ...:1" - a no-op. The no-op filter runs on the fetched
     * change set, before resolution, so the second normalisation pass is what has to catch it.
     */
    val linkId = "76bfa293-2675-4189-ad3a-abaf7af312f2:1"
    val unresolvedNewLinkId = "76bfa293-2675-4189-ad3a-abaf7af312f2:a"
    val realChange = TiekamuRoadLinkChange(
      "f100test-test-test-test-000000000121:1", 0.0, 20.0,
      "f200test-test-test-test-000000000122:1", 0.0, 20.0, digitizationChange = false)

    val fetched = Seq(
      TiekamuRoadLinkChange(linkId, 0.0, 54.922, unresolvedNewLinkId, 0.0, 54.922, digitizationChange = false),
      realChange
    )
    // Nothing to drop before resolution: the two link ids differ.
    dynamicRoadNetworkService.normaliseChangeSet(fetched, "fetched").length should be (2)

    val resolved = dynamicRoadNetworkService.withResolvedNewLinkIds(fetched, Map(unresolvedNewLinkId -> linkId))
    dynamicRoadNetworkService.normaliseChangeSet(resolved, "resolved") should be (Seq(realChange))
  }

}
