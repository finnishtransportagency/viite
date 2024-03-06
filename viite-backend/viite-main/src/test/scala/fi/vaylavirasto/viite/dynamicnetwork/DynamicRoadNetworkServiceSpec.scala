package fi.vaylavirasto.viite.dynamicnetwork

import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.viite.AwsService
import fi.liikennevirasto.viite.dao._
import fi.vaylavirasto.viite.dao.{Link, LinkDAO, Sequences}
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, CalibrationPoint, CalibrationPointLocation, CalibrationPointType, Discontinuity, LifecycleStatus, LinkGeomSource, RoadLink, RoadPart, SideCode, Track, TrafficDirection}
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}

class DynamicRoadNetworkServiceSpec extends FunSuite with Matchers{
  val linearLocationDAO = new LinearLocationDAO
  val roadwayDAO = new RoadwayDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val kgvRoadLinkClient = new KgvRoadLink
  val awsService = new AwsService
  val linkNetworkUpdater = new LinkNetworkUpdater

  val dynamicRoadNetworkService = new DynamicRoadNetworkService(linearLocationDAO, roadwayDAO, kgvRoadLinkClient, awsService, linkNetworkUpdater)

  test("When Validating valid TiekamuRoadLinkChanges Then should not return any TiekamuRoadLinkErrors") {
    runWithRollback {
      val linkId1 = "testtest-test-test-test-test:1"
      val linkId2 = "testtest-test-test-test-test:2"
      val newLinkId1 = "testtest-test-test-test-test:3"
      val newLinkId2 = "testtest-test-test-test-test:4"
      val geometry1 = Seq(Point(0.0, 0.0), Point(0.0,50.0))
      val geometry2 = Seq(Point(0.0, 50.0), Point(0.0, 100.0))
      val newGeometry1 = Seq(Point(0.0, 0.0), Point(0.0, 50.0))
      val newGeometry2 = Seq(Point(0.0, 50.0), Point(0.0, 100.0))
      val orderNumber1 = 1.0
      val orderNumber2 = 2.0
      val roadwayNumber1 = Sequences.nextRoadwayNumber

      // Create Roadways
      roadwayDAO.create(Seq(
        Roadway(Sequences.nextRoadwayId, roadwayNumber1, RoadPart(18344, 1), AdministrativeClass.State, Track.Combined,Discontinuity.EndOfRoad, AddrMRange(0, 100), false, DateTime.now().minusDays(1), None, "test", Some("Test road"), 9,TerminationCode.NoTermination, DateTime.now().minusDays(1),None)
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

      // The testing itself
      val res = dynamicRoadNetworkService.validateTiekamuRoadLinkChanges(tiekamuRoadLinkChanges,linearlocations, kgvRoadLinks)
      res.length should be (0)
    }
  }
}
