package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.CalibrationPointType.NoCP
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, Discontinuity, LifecycleStatus, LinkGeomSource, RoadAddressChangeType, RoadLink, RoadPart, SideCode, Track, TrafficDirection}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback
import org.joda.time.DateTime
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

class RoadAddressLinkBuilderSpec extends AnyFunSuite with Matchers {

  private val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  private val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  private val mockNodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  private val mockRoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  private val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  private val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  private val mockRoadNetworkDAO = MockitoSugar.mock[RoadNetworkDAO]
  private val mockRoadwayPointDAO = MockitoSugar.mock[RoadwayPointDAO]
  private val mockNodePointDAO = MockitoSugar.mock[NodePointDAO]
  private val mockJunctionPointDAO = MockitoSugar.mock[JunctionPointDAO]
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val roadwayDAO = new RoadwayDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodeDAO = new NodeDAO
  val nodePointDAO = new NodePointDAO
  val junctionPointDAO = new JunctionPointDAO
  val roadwayChangesDAO = new RoadwayChangesDAO
  val roadwayAddressMapper = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)

  val roadAddressService: RoadAddressService =
    new RoadAddressService(
                            mockRoadLinkService,
                            mockRoadwayDAO,
                            mockLinearLocationDAO,
                            mockRoadNetworkDAO,
                            mockRoadwayPointDAO,
                            mockNodePointDAO,
                            mockJunctionPointDAO,
                            mockRoadwayAddressMapper,
                            mockEventBus,
                            frozenKGV = false
                            ) {
                                override def runWithReadOnlySession[T](f: => T): T = f
                                override def runWithTransaction[T](f: => T): T = f
                              }

  val projectService: ProjectService =
    new ProjectService(
                        roadAddressService,
                        mockRoadLinkService,
                        mockNodesAndJunctionsService,
                        roadwayDAO,
                        roadwayPointDAO,
                        linearLocationDAO,
                        projectDAO,
                        projectLinkDAO,
                        nodeDAO,
                        nodePointDAO,
                        junctionPointDAO,
                        projectReservedPartDAO,
                        roadwayChangesDAO,
                        roadwayAddressMapper,
                        mockEventBus
                        ) {
                            override def runWithReadOnlySession[T](f: => T): T = f
                            override def runWithTransaction[T](f: => T): T = f
                          }

  val mockRoadAddressLinkBuilder = new RoadAddressLinkBuilder(mockRoadwayDAO, mockLinearLocationDAO)
  val roadAddressLinkBuilder = new RoadAddressLinkBuilder(new RoadwayDAO, new LinearLocationDAO)

  test("Test RoadAddressLinkBuilder.build for roadAddress input") {
    runWithRollback {
      val roadAddress = RoadAddress(1, 1234, RoadPart(5, 999), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 10L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(2.0, 9.8), Point(2.0, 9.8), Point(10.0, 19.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)

      val roadAddressLink = roadAddressLinkBuilder.build(roadAddress)
      roadAddressLink.length should be (22.808)
      roadAddressLink.linearLocationId should be (1234)
      roadAddressLink.linkId should be (12345L.toString)
      roadAddressLink.sideCode should be (SideCode.TowardsDigitizing)
    }
  }

  test("Test RoadAddressLinkBuilder.build for roadlink and roadAddress input") {
    runWithRollback {
      val roadAddress = RoadAddress(1, 1234, RoadPart(5, 999), AdministrativeClass.Unknown, Track.Combined, Discontinuity.Discontinuous, AddrMRange(0L, 10L), Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L.toString, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(2.0, 9.8), Point(2.0, 9.8), Point(10.0, 19.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
      val roadlink = RoadLink(linkId = 1L.toString, List(Point(0.0, 0.0), Point(120.0, 0.0)), 120.0, AdministrativeClass.Municipality, TrafficDirection.BothDirections, None, None, municipalityCode = 0, sourceId = "")

      val roadAddressLink = roadAddressLinkBuilder.build(roadlink, roadAddress)
      roadAddressLink.length should be (9.8)
      roadAddressLink.linearLocationId should be (1234)
      roadAddressLink.linkId should be (1L.toString)
      roadAddressLink.administrativeClassMML should be (AdministrativeClass.Municipality)
      roadAddressLink.sideCode should be (SideCode.TowardsDigitizing)
//      roadAddressLink.roadType should be (AdministrativeClass.MunicipalityStreetRoad)
    }
  }

  test("Test ProjectAddressLinkBuilder.build() When building project address links from regular project links and road links Then return the build ProjectAddressLinks.") {
    val unknownProjectLink = ProjectLink(   0,    RoadPart(0, 0), Track.Unknown,  Discontinuity.Continuous, AddrMRange(   0,    0), AddrMRange(   0,    0), None, None, None,       0.toString, 0.0,  0.0, SideCode.Unknown,           (NoCP, NoCP), (NoCP, NoCP), List(),    0, RoadAddressChangeType.NotHandled, AdministrativeClass.Unknown, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 8, reversed = false, None, 85088L)
    val projectLinks =
      Map(
        1717380L.toString -> ProjectLink(1270, RoadPart(   0, 0), Track.Unknown,  Discontinuity.Continuous, AddrMRange(1021, 1028), AddrMRange(1021, 1028), None, None, None, 1717380.toString, 0.0,  6.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), List(), 1227, RoadAddressChangeType.NotHandled, AdministrativeClass.Unknown, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 8, reversed = false, None, 85088L),
        1717374L.toString -> ProjectLink(1259, RoadPart(1130, 0), Track.Combined, Discontinuity.Continuous, AddrMRange( 959, 1021), AddrMRange( 959, 1021), None, None, None, 1717374.toString, 0.0, 61.0, SideCode.AgainstDigitizing, (NoCP, NoCP), (NoCP, NoCP), List(), 1227, RoadAddressChangeType.NotHandled, AdministrativeClass.Unknown, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 8, reversed = false, None, 85088L)
      )

    val roadLinks = Seq(
      RoadLink(1717380.toString, List(Point(358594.785, 6678940.735, 57.788000000000466), Point(358599.713, 6678945.133, 57.78100000000268)), 6.605118318435748, AdministrativeClass.State,  TrafficDirection.BothDirections, Some("14.10.2016 21:15:13"), Some("vvh_modified"),  LifecycleStatus.InUse,  LinkGeomSource.NormalLinkInterface, 257, ""),
      RoadLink(1717374.toString, List(Point(358599.713, 6678945.133, 57.78100000000268), Point(358601.644, 6678946.448, 57.771999999997206), Point(358621.812, 6678964.766, 57.41000000000349), Point(358630.04, 6678971.657, 57.10099999999511), Point(358638.064, 6678977.863, 56.78599999999278), Point(358647.408, 6678984.55, 56.31399999999849)), 61.948020518025565, AdministrativeClass.State,  TrafficDirection.BothDirections, Some("14.10.2016 21:15:13"), Some("vvh_modified"),  LifecycleStatus.InUse,  LinkGeomSource.NormalLinkInterface, 0, "")
    )
    val projectRoadLinks = roadLinks.map {
      rl =>
        val pl = projectLinks.getOrElse(rl.linkId, unknownProjectLink)
        rl.linkId -> ProjectAddressLinkBuilder.build(rl, pl)
    }
    projectRoadLinks should have size 2
    val pal1 = projectRoadLinks.head._2
    val pal2 = projectRoadLinks.tail.head._2

    pal1.roadPart should be(RoadPart(0,0))
    pal1.trackCode should be(99)
    pal1.municipalityCode should be(BigInt(257))

    pal2.roadPart should be(RoadPart(1130,0))
    pal2.trackCode should be(0)
    pal2.municipalityCode should be(BigInt(0))
  }
}
