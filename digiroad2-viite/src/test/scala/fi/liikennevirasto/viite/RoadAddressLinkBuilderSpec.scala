package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.{asset, _}
import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.{NormalLinkInterface, SuravageLinkInterface}
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset.TrafficDirection.BothDirections
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.{FeatureClass, VVHRoadlink}
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
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
  val mockRoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockLinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]
  val mockRoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockRoadNetworkDAO = MockitoSugar.mock[RoadNetworkDAO]
  val mockProjectLinkDAO = MockitoSugar.mock[ProjectLinkDAO]
  val roadAddressService = new RoadAddressService(mockRoadLinkService, mockRoadwayDAO, mockLinearLocationDAO, mockRoadNetworkDAO, new UnaddressedRoadLinkDAO, mockRoadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val projectService = new ProjectService(roadAddressService,  mockRoadLinkService, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val mockRoadAddressLinkBuilder = new RoadAddressLinkBuilder(mockRoadwayDAO, mockLinearLocationDAO, mockProjectLinkDAO)
  val roadAddressLinkBuilder = new RoadAddressLinkBuilder(new RoadwayDAO, new LinearLocationDAO, new ProjectLinkDAO)

  test("Saved Suravage Link gets roadaddress from DB if exists") {
    runWithRollback {
      val roadway = Dummies.dummyRoadway(1, 62555, 2, 0, 68, DateTime.parse("2018-04-23"), Some(DateTime.parse("2018-04-23")))
      val linearLocation = Dummies.dummyLinearLocationWithGeometry(id = 1, roadwayNumber = 1, orderNumber = 1, linkId = 7096025, startMValue = 0, endMValue = 67.768, geometry = Seq(Point(642581.506, 6947078.918), Point(642544.7200222166, 6947042.201990652)))
      when(mockLinearLocationDAO.fetchByLinkId(any[Set[Long]], any[Set[Long]])).thenReturn(List(linearLocation))
      when(mockRoadwayDAO.fetchAllByRoadwayNumbers(any[Set[Long]], any[Boolean])).thenReturn(Seq(roadway))
      when(mockRoadwayDAO.fetchByRoadwayNumber(any[Long], any[Boolean])).thenReturn(Some(roadway))

      val suravageAddress =
        mockRoadAddressLinkBuilder.buildSuravageRoadAddressLink(VVHRoadlink(7096025, 167,
          List(Point(642581.506, 6947078.918),
            Point(642582.157, 6947074.293), Point(642582.541, 6947069.504), Point(642582.348, 6947064.703), Point(642581.58, 6947059.961),
            Point(642580.249, 6947055.344), Point(642578.375, 6947050.92), Point(642576.423, 6947047.7), Point(642573.963, 6947044.85),
            Point(642571.061, 6947042.451), Point(642567.8, 6947040.569), Point(642564.27, 6947039.257), Point(642560.572, 6947038.553),
            Point(642556.807, 6947038.475), Point(642553.082, 6947039.026), Point(642549.502, 6947040.19), Point(642544.72, 6947042.202)),
          Municipality, BothDirections, FeatureClass.CycleOrPedestrianPath, None,
          Map("LAST_EDITED_DATE" -> BigInt(1516719259000L), "CONSTRUCTIONTYPE" -> 1, "MTKCLASS" -> 12314, "Points" -> List(Map("x" -> 642581.506, "y" -> 6947078.918, "z" -> 0, "m" -> 0),
            Map("x" -> 642582.157, "y" -> 6947074.293, "z" -> 0, "m" -> 4.669999999998254), Map("x" -> 642582.541, "y" -> 6947069.504, "z" -> 0, "m" -> 9.474600000001374),
            Map("x" -> 642582.348, "y" -> 6947064.703, "z" -> 0, "m" -> 14.279200000004494), Map("x" -> 642581.58, "y" -> 6947059.961, "z" -> 0, "m" -> 19.08379999999306),
            Map("x" -> 642580.249, "y" -> 6947055.344, "z" -> 0, "m" -> 23.88839999999618), Map("x" -> 642578.375, "y" -> 6947050.92, "z" -> 0, "m" -> 28.6929999999993),
            Map("x" -> 642576.423, "y" -> 6947047.7, "z" -> 0, "m" -> 32.45819999999367), Map("x" -> 642573.963, "y" -> 6947044.85, "z" -> 0, "m" -> 36.22349999999278),
            Map("x" -> 642571.061, "y" -> 6947042.451, "z" -> 0, "m" -> 39.9887000000017), Map("x" -> 642567.8, "y" -> 6947040.569, "z" -> 0, "m" -> 43.754000000000815),
            Map("x" -> 642564.27, "y" -> 6947039.257, "z" -> 0, "m" -> 47.51910000000498), Map("x" -> 642560.572, "y" -> 6947038.553, "z" -> 0, "m" -> 51.284499999994296),
            Map("x" -> 642556.807, "y" -> 6947038.475, "z" -> 0, "m" -> 55.04970000000321), Map("x" -> 642553.082, "y" -> 6947039.026, "z" -> 0, "m" -> 58.81489999999758),
            Map("x" -> 642549.502, "y" -> 6947040.19, "z" -> 0, "m" -> 62.580100000006496), Map("x" -> 642544.72, "y" -> 6947042.202, "z" -> 0, "m" -> 67.76810000000114)),
            "OBJECTID" -> 14132, "SUBTYPE" -> 2, "VERTICALLEVEL" -> 0, "MUNICIPALITYCODE" -> 167, "CREATED_DATE" -> BigInt(1490794018000L)), ConstructionType.UnderConstruction,
          SuravageLinkInterface, 67.768), None
        )
      suravageAddress.trackCode should be(Track.Combined.value)
      suravageAddress.startAddressM should be(0)
      suravageAddress.endAddressM should be(68)
    }
  }

  test("Suravage link builder when link is not in DB") {
    val newLinkId1 = 5000
    val municipalityCode = 564
    val administrativeClass = Municipality
    val trafficDirection = TrafficDirection.TowardsDigitizing
    val attributes1 = Map("ROADNUMBER" -> BigInt(99), "ROADPARTNUMBER" -> BigInt(24))
    val suravageAddress = OracleDatabase.withDynSession {
      roadAddressLinkBuilder.buildSuravageRoadAddressLink(VVHRoadlink(newLinkId1, municipalityCode,
        List(Point(1.0, 0.0), Point(20.0, 1.0)), administrativeClass, trafficDirection, FeatureClass.DrivePath, None,
        attributes1, ConstructionType.UnderConstruction, LinkGeomSource.SuravageLinkInterface, 30), None)
    }

    suravageAddress.linkId should be(newLinkId1)
    suravageAddress.administrativeClass should be(administrativeClass)
    suravageAddress.constructionType should be(ConstructionType.UnderConstruction)
    suravageAddress.sideCode should be(SideCode.Unknown)
    suravageAddress.roadNumber should be(99)
    suravageAddress.roadPartNumber should be(24)
    suravageAddress.startMValue should be(0)
    suravageAddress.endMValue should be(19.026)
    suravageAddress.roadLinkSource should be(LinkGeomSource.SuravageLinkInterface)
    suravageAddress.elyCode should be(12)
    suravageAddress.municipalityCode should be(municipalityCode)
    suravageAddress.geometry.size should be(2)
  }

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

      val roadlink = VVHRoadlink(linkId = 1L, 91, List(Point(0.0, 0.0), Point(120.0, 0.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers)

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
      RoadLink(1717380, List(Point(358594.785, 6678940.735, 57.788000000000466), Point(358599.713, 6678945.133, 57.78100000000268)), 6.605118318435748, State, 99, BothDirections, UnknownLinkType, Some("14.10.2016 21:15:13"), Some("vvh_modified"), Map("TO_RIGHT" -> 104, "LAST_EDITED_DATE" -> BigInt("1476468913000"), "FROM_LEFT" -> 103, "MTKHEREFLIP" -> 1, "MTKID" -> 362888804, "ROADNAME_FI" -> "Evitskogintie", "STARTNODE" -> 1729826, "VERTICALACCURACY" -> 201, "ENDNODE" -> 1729824, "VALIDFROM" -> BigInt("1379548800000"), "CONSTRUCTIONTYPE" -> 0, "SURFACETYPE" -> 2, "MTKCLASS" -> 12122, "ROADPARTNUMBER" -> 4, "points" -> List(Map("x" -> 358594.785, "y" -> 6678940.735, "z" -> 57.788000000000466, "m" -> 0), Map("x" -> 358599.713, "y" -> 6678945.133, "z" -> 57.78100000000268, "m" -> 6.605100000000675)), "TO_LEFT" -> 103, "geometryWKT" -> "LINESTRING ZM (358594.785 6678940.735 57.788000000000466 0, 358599.713 6678945.133 57.78100000000268 6.605100000000675)", "VERTICALLEVEL" -> 0, "ROADNAME_SE" -> "Evitskogsvägen", "MUNICIPALITYCODE" -> BigInt(257), "FROM_RIGHT" -> 104, "CREATED_DATE" -> BigInt("1446132842000"), "GEOMETRY_EDITED_DATE" -> BigInt("1476468913000"), "HORIZONTALACCURACY" -> 3000, "ROADNUMBER" -> 1130), InUse, NormalLinkInterface),
      RoadLink(1717374, List(Point(358599.713, 6678945.133, 57.78100000000268), Point(358601.644, 6678946.448, 57.771999999997206), Point(358621.812, 6678964.766, 57.41000000000349), Point(358630.04, 6678971.657, 57.10099999999511), Point(358638.064, 6678977.863, 56.78599999999278), Point(358647.408, 6678984.55, 56.31399999999849)), 61.948020518025565, State, 99, BothDirections, UnknownLinkType, Some("14.10.2016 21:15:13"), Some("vvh_modified"), Map("TO_RIGHT" -> 98, "LAST_EDITED_DATE" -> BigInt("1476468913000"), "FROM_LEFT" -> 101, "MTKHEREFLIP" -> 1, "MTKID" -> 362888798, "STARTNODE" -> 1729824, "VERTICALACCURACY" -> 201, "ENDNODE" -> 1729819, "VALIDFROM" -> BigInt("1379548800000"), "CONSTRUCTIONTYPE" -> 0, "SURFACETYPE" -> 2, "MTKCLASS" -> 12122, "points" -> List(Map("x" -> 358599.713, "y" -> 6678945.133, "z" -> 57.78100000000268, "m" -> 0), Map("x" -> 358601.644, "y" -> 6678946.448, "z" -> 57.771999999997206, "m" -> 2.336200000005192), Map("x" -> 358621.812, "y" -> 6678964.766, "z" -> 57.41000000000349, "m" -> 29.581399999995483), Map("x" -> 358630.04, "y" -> 6678971.657, "z" -> 57.10099999999511, "m" -> 40.31380000000354), Map("x" -> 358638.064, "y" -> 6678977.863, "z" -> 56.78599999999278, "m" -> 50.45780000000377), Map("x" -> 358647.408, "y" -> 6678984.55, "z" -> 56.31399999999849, "m" -> 61.94800000000396)), "TO_LEFT" -> 97, "geometryWKT" -> "LINESTRING ZM (358599.713 6678945.133 57.78100000000268 0, 358601.644 6678946.448 57.771999999997206 2.336200000005192, 358621.812 6678964.766 57.41000000000349 29.581399999995483, 358630.04 6678971.657 57.10099999999511 40.31380000000354, 358638.064 6678977.863 56.78599999999278 50.45780000000377, 358647.408 6678984.55 56.31399999999849 61.94800000000396)", "VERTICALLEVEL" -> 0, "ROADNAME_SE" -> "Evitskogsvägen", "MUNICIPALITYCODE" -> BigInt(0), "FROM_RIGHT" -> 102, "CREATED_DATE" -> BigInt("1446132842000"), "GEOMETRY_EDITED_DATE" -> BigInt("1476468913000"), "HORIZONTALACCURACY" -> 3000, "ROADNUMBER" -> 1130), InUse, NormalLinkInterface)
    )
    val projectRoadLinks = roadLinks.map {
      rl =>
        val pl = projectLinks.getOrElse(rl.linkId, unknownProjectLink)
        rl.linkId -> ProjectAddressLinkBuilder.build(rl, pl)
    }
    projectRoadLinks should have size (2)
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
