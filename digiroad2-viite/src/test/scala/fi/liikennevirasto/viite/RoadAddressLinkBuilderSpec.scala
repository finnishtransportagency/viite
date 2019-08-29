package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.{asset, _}
import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset.TrafficDirection.BothDirections
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.{FeatureClass, VVHRoadlink}
import fi.liikennevirasto.digiroad2.linearasset.{KMTKID, RoadLink}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.util.Track.Combined
import fi.liikennevirasto.viite.RoadType.UnknownOwnerRoad
import fi.liikennevirasto.viite.dao.Discontinuity.{Continuous, Discontinuous}
import fi.liikennevirasto.viite.dao.LinkStatus.NotHandled
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.joda.time.DateTime
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito.{reset, when}
import org.mockito.ArgumentMatchers.any
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class RoadAddressLinkBuilderSpec extends FunSuite with Matchers {
  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockNodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  val mockRoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockRoadNetworkDAO = MockitoSugar.mock[RoadNetworkDAO]
  val mockProjectLinkDAO = MockitoSugar.mock[ProjectLinkDAO]
  val mockRoadwayPointDAO = MockitoSugar.mock[RoadwayPointDAO]
  val mockNodePointDAO = MockitoSugar.mock[NodePointDAO]
  val mockJunctionPointDAO = MockitoSugar.mock[JunctionPointDAO]
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
  val roadAddressService = new RoadAddressService(mockRoadLinkService, mockRoadwayDAO, mockLinearLocationDAO, mockRoadNetworkDAO, mockRoadwayPointDAO, mockNodePointDAO, mockJunctionPointDAO, mockRoadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val projectService = new ProjectService(roadAddressService, mockRoadLinkService, mockNodesAndJunctionsService, roadwayDAO,
    roadwayPointDAO, linearLocationDAO, projectDAO, projectLinkDAO,
    nodeDAO, nodePointDAO, junctionPointDAO, projectReservedPartDAO, roadwayChangesDAO,
    roadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val mockRoadAddressLinkBuilder = new RoadAddressLinkBuilder(mockRoadwayDAO, mockLinearLocationDAO, mockProjectLinkDAO)
  val roadAddressLinkBuilder = new RoadAddressLinkBuilder(new RoadwayDAO, new LinearLocationDAO, new ProjectLinkDAO)

  test("Test RoadAddressLinkBuilder.build for roadAddress input") {
    runWithRollback {
      val roadAddress = RoadAddress(1, 1234, 5, 999, RoadType.Unknown, Track.Combined, Discontinuous,
        0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L, 0.0, 9.8,
        SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(2.0, 9.8), Point(2.0, 9.8), Point(10.0, 19.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)

      val roadAddressLink = roadAddressLinkBuilder.build(roadAddress)
      roadAddressLink.length should be (22.808)
      roadAddressLink.linearLocationId should be (1234)
      roadAddressLink.linkId should be (12345L)
      roadAddressLink.sideCode should be (TowardsDigitizing)
    }
  }

  test("Test RoadAddressLinkBuilder.build for roadlink and roadAddress input") {
    runWithRollback {
      val roadAddress = RoadAddress(1, 1234, 5, 999, RoadType.Unknown, Track.Combined, Discontinuous,
        0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L, 0.0, 9.8,
        SideCode.TowardsDigitizing, 0, (None, None), Seq(Point(0.0, 0.0), Point(2.0, 9.8), Point(2.0, 9.8), Point(10.0, 19.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)

      val roadlink = VVHRoadlink(linkId = 1L, KMTKID("1"), 91, List(Point(0.0, 0.0), Point(120.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers)

      val roadAddressLink = roadAddressLinkBuilder.build(roadlink, roadAddress)
      roadAddressLink.length should be (9.8)
      roadAddressLink.linearLocationId should be (1234)
      roadAddressLink.linkId should be (1L)
      roadAddressLink.administrativeClass should be (asset.Municipality)
      roadAddressLink.sideCode should be (TowardsDigitizing)
      roadAddressLink.roadType should be (RoadType.MunicipalityStreetRoad)
    }
  }

  test("Test ProjectAddressLinkBuilder.build() When building project address links from regular project links and road links Then return the build ProjectAddressLinks.") {
    val unknownProjectLink = ProjectLink(0, 0, 0, Track.Unknown, Discontinuity.Continuous, 0, 0, 0, 0, None, None, None, 0, 0.0, 0.0,
      SideCode.Unknown, (None, None), List(), 0, NotHandled, UnknownOwnerRoad, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 8, reversed = false,
      None, 85088L)
    val projectLinks =
      Map(
        1717380l -> ProjectLink(1270, 0, 0, Track.apply(99), Continuous, 1021, 1028, 1021, 1028, None, None, None, 1717380, 0.0, 6.0,
          AgainstDigitizing, (None, None), List(), 1227, NotHandled, UnknownOwnerRoad, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 8, reversed = false, None, 85088L),
        1717374l -> ProjectLink(1259, 1130, 0, Combined, Continuous, 959, 1021, 959, 1021, None, None, None, 1717374, 0.0, 61.0,
          AgainstDigitizing, (None, None), List(), 1227, NotHandled, UnknownOwnerRoad, LinkGeomSource.NormalLinkInterface, 0.0, 0, 0, 8, reversed = false, None, 85088L)
      )

    val roadLinks = Seq(
      RoadLink(1717380, KMTKID("UUID1", 1), List(Point(358594.785, 6678940.735, 57.788000000000466), Point(358599.713, 6678945.133, 57.78100000000268)), 6.605118318435748, State, 99, BothDirections, UnknownLinkType, Some("14.10.2016 21:15:13"), Some("vvh_modified"), Map("TO_RIGHT" -> 104, "LAST_EDITED_DATE" -> BigInt("1476468913000"), "FROM_LEFT" -> 103, "MTKHEREFLIP" -> 1, "MTKID" -> 362888804, "ROADNAME_FI" -> "Evitskogintie", "VERTICALACCURACY" -> 201, "VALIDFROM" -> BigInt("1379548800000"), "CONSTRUCTIONTYPE" -> 0, "SURFACETYPE" -> 2, "MTKCLASS" -> 12122, "ROADPARTNUMBER" -> 4, "points" -> List(Map("x" -> 358594.785, "y" -> 6678940.735, "z" -> 57.788000000000466, "m" -> 0), Map("x" -> 358599.713, "y" -> 6678945.133, "z" -> 57.78100000000268, "m" -> 6.605100000000675)), "TO_LEFT" -> 103, "geometryWKT" -> "LINESTRING ZM (358594.785 6678940.735 57.788000000000466 0, 358599.713 6678945.133 57.78100000000268 6.605100000000675)", "VERTICALLEVEL" -> 0, "ROADNAME_SE" -> "Evitskogsvägen", "MUNICIPALITYCODE" -> BigInt(257), "FROM_RIGHT" -> 104, "CREATED_DATE" -> BigInt("1446132842000"), "GEOMETRY_EDITED_DATE" -> BigInt("1476468913000"), "HORIZONTALACCURACY" -> 3000, "ROADNUMBER" -> 1130), InUse, NormalLinkInterface),
      RoadLink(1717374, KMTKID("UUID2", 1), List(Point(358599.713, 6678945.133, 57.78100000000268), Point(358601.644, 6678946.448, 57.771999999997206), Point(358621.812, 6678964.766, 57.41000000000349), Point(358630.04, 6678971.657, 57.10099999999511), Point(358638.064, 6678977.863, 56.78599999999278), Point(358647.408, 6678984.55, 56.31399999999849)), 61.948020518025565, State, 99, BothDirections, UnknownLinkType, Some("14.10.2016 21:15:13"), Some("vvh_modified"), Map("TO_RIGHT" -> 98, "LAST_EDITED_DATE" -> BigInt("1476468913000"), "FROM_LEFT" -> 101, "MTKHEREFLIP" -> 1, "MTKID" -> 362888798, "VERTICALACCURACY" -> 201, "VALIDFROM" -> BigInt("1379548800000"), "CONSTRUCTIONTYPE" -> 0, "SURFACETYPE" -> 2, "MTKCLASS" -> 12122, "points" -> List(Map("x" -> 358599.713, "y" -> 6678945.133, "z" -> 57.78100000000268, "m" -> 0), Map("x" -> 358601.644, "y" -> 6678946.448, "z" -> 57.771999999997206, "m" -> 2.336200000005192), Map("x" -> 358621.812, "y" -> 6678964.766, "z" -> 57.41000000000349, "m" -> 29.581399999995483), Map("x" -> 358630.04, "y" -> 6678971.657, "z" -> 57.10099999999511, "m" -> 40.31380000000354), Map("x" -> 358638.064, "y" -> 6678977.863, "z" -> 56.78599999999278, "m" -> 50.45780000000377), Map("x" -> 358647.408, "y" -> 6678984.55, "z" -> 56.31399999999849, "m" -> 61.94800000000396)), "TO_LEFT" -> 97, "geometryWKT" -> "LINESTRING ZM (358599.713 6678945.133 57.78100000000268 0, 358601.644 6678946.448 57.771999999997206 2.336200000005192, 358621.812 6678964.766 57.41000000000349 29.581399999995483, 358630.04 6678971.657 57.10099999999511 40.31380000000354, 358638.064 6678977.863 56.78599999999278 50.45780000000377, 358647.408 6678984.55 56.31399999999849 61.94800000000396)", "VERTICALLEVEL" -> 0, "ROADNAME_SE" -> "Evitskogsvägen", "MUNICIPALITYCODE" -> BigInt(0), "FROM_RIGHT" -> 102, "CREATED_DATE" -> BigInt("1446132842000"), "GEOMETRY_EDITED_DATE" -> BigInt("1476468913000"), "HORIZONTALACCURACY" -> 3000, "ROADNUMBER" -> 1130), InUse, NormalLinkInterface)
    )
    val projectRoadLinks = roadLinks.map {
      rl =>
        val pl = projectLinks.getOrElse(rl.linkId, unknownProjectLink)
        rl.linkId -> ProjectAddressLinkBuilder.build(rl, pl)
    }
    projectRoadLinks should have size 2
    val pal1 = projectRoadLinks.head._2
    val pal2 = projectRoadLinks.tail.head._2

    pal1.roadNumber should be(1130)
    pal1.roadPartNumber should be(4)
    pal1.trackCode should be(99)
    pal1.VVHRoadName.get should be("Evitskogintie")
    pal1.municipalityCode should be(BigInt(257))

    pal2.roadNumber should be(1130)
    pal2.roadPartNumber should be(0)
    pal2.trackCode should be(0)
    pal2.VVHRoadName.get should be("Evitskogsvägen")
    pal2.municipalityCode should be(BigInt(0))
  }
}
