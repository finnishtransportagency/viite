package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.{NormalLinkInterface, SuravageLinkInterface}
import fi.liikennevirasto.digiroad2.asset.SideCode.AgainstDigitizing
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
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.LinkStatus.NotHandled
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Subsequent, Termination}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.{InvalidAddressDataException, RoadwayAddressMapper}
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import slick.jdbc.StaticQuery.interpolation
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class RoadAddressLinkBuilderSpec extends FunSuite with Matchers {

  //TODO Probably this will be not needed anymore
//  test("Fuse road address should accept single road address") {
//    val roadAddress = Seq(RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, None, None, Option("tester"),
//      12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
//    RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress) should be(roadAddress)
//  }

  //TODO Probably this will be not needed anymore
//  test("Fuse road address with calibration point in between when one of the road addresses have the same start and end address") {
//    val roadAddress = Seq(
//      RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Continuous, 0L, 10L, None, None, Option("tester"),
//      12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(12345L, 9.8, 10L))), FloatingReason.NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//      RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 10L, 10L, None, None, Option("tester"),
//        12345L, 9.8, 11, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(12345L, 9.8, 10L)), None), FloatingReason.NoFloating, Seq(Point(0.0, 9.8), Point(0.0, 11)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//    )
//
//    val resultRoadAddress = Seq(RoadAddress(-1000, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, None, None, Option("tester"),
//      12345L, 0.0, 11, SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(12345L, 11, 10L))), FloatingReason.NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 11)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
//
//    RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress) should be(resultRoadAddress)
//  }

  //TODO Probably this will be not needed anymore
//  test("Fuse road address should merge consecutive road addresses even if floating") {
//    val roadAddress = Seq(
//      RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8,
//        SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//      RoadAddress(2, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.4,
//        SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//    )
//    RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress) should have size (1)
//  }

  //TODO Probably this will be not needed anymore
//  test("Fuse road address should not merge consecutive road addresses with differing start dates") {
//    val roadAddress = Seq(
//      RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//      RoadAddress(2, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 10L, 20L, Some(DateTime.parse("1902-02-02")), None, Option("tester"), 12345L, 9.8, 20.2, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//      RoadAddress(3, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 20L, 30L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 20.2, 30.2, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 20.2), Point(0.0, 30.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//    )
//    val fused = RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress)
//    fused should have size (3)
//    val ids = roadAddress.map(_.id).toSet
//    RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress).map(_.id).toSet should be(ids)
//  }

  //TODO Probably this will be not needed anymore
//  test("Fuse road address should not merge consecutive road addresses with differing link ids") {
//    val roadAddress = Seq(
//      RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//      RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12346L, 0.0, 10.4, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//    )
//    RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress) should have size (2)
//    val ids = roadAddress.map(_.id).toSet
//    RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress).map(_.id).toSet should be(ids)
//  }

  //TODO Probably this will be not needed anymore
//  test("Fuse road address should not merge consecutive road addresses if side code differs") {
//    val roadAddress = Seq(
//      RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//      RoadAddress(2, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.4, SideCode.AgainstDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//    )
//    intercept[InvalidAddressDataException] {
//      RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress)
//    }
//  }

  //TODO Probably this will be not needed anymore
//  test("Fuse road address should merge multiple road addresses with random ordering") {
//    val roadAddress = Seq(
//      RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//      RoadAddress(4, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 30L, 40L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 30.0, 39.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 30.0), Point(0.0, 39.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//      RoadAddress(3, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 20L, 30L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 10.4, 30.0, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 20.2), Point(0.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//      RoadAddress(2, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.4, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//    )
//    RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress) should have size (1)
//  }

  //TODO Probably this will be not needed anymore
//  test("Fuse road address should not merge consecutive road addresses with calibration point in between") {
//    // TODO: Or do we throw an exception then?
//    val roadAddress = Seq(
//      RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8,
//        SideCode.TowardsDigitizing, 0, (None, Some(CalibrationPoint(12345L, 9.8, 10L))), NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//
//      RoadAddress(2, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.4,
//        SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(12345L, 9.8, 10L)), None), NoFloating, Seq(Point(0.0, 9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//
//      RoadAddress(3, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 20L, 30L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 10.4, 30.0,
//        SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(0.0, 20.2), Point(0.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//
//      RoadAddress(4, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 30L, 40L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 30.0, 39.8,
//        SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(0.0, 30.0), Point(0.0, 39.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//    )
//    //Changed fuseRoadAddressWithTransaction size from 3 to 2 the reasoning behind it is that although we cannot fuse  1 and 2, there is nothing stopping us from fusing 2,3 and 4
//    RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress) should have size (2)
//  }


  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  //TODO will be implemented at VIITE-1550
//  test("Saved Suravage Link gets roadaddress from DB if exists") {
//    runWithRollback {
//      sqlu""" alter session set nls_language = 'american' NLS_NUMERIC_CHARACTERS = ', '""".execute
//      sqlu"""Insert into ROAD_ADDRESS (ID,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK_CODE,DISCONTINUITY,START_ADDR_M,END_ADDR_M,START_DATE,END_DATE,CREATED_BY,
//          VALID_FROM,CALIBRATION_POINTS,FLOATING,GEOMETRY,VALID_TO,ELY,ROAD_TYPE,TERMINATED,COMMON_HISTORY_ID,
//          SIDE_CODE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,MODIFIED_DATE,LINK_SOURCE) values ('9124288','62555','2','0','1','0','68',
//          to_date('23.04.2018','DD.MM.RRRR'),null,'k189826',to_date('23.04.2018','DD.MM.RRRR'),'3','0',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1),
//          MDSYS.SDO_ORDINATE_ARRAY(642581.506, 6947078.918, 0, 0, 642544.7200222166, 6947042.201990652, 0, 68)),null,'8','3','0','191816022',
//          '2','0','67,768','7096025','1516719259000',to_timestamp('23.04.2018 16:26:44,137893000','DD.MM.RRRR HH24:MI:SSXFF'),'3')""".execute
//      val suravageAddress =
//        RoadAddressLinkBuilder.buildSuravageRoadAddressLink(VVHRoadlink(7096025, 167,
//          List(Point(642581.506, 6947078.918, 0.0),
//            Point(642582.157, 6947074.293, 0.0), Point(642582.541, 6947069.504, 0.0), Point(642582.348, 6947064.703, 0.0), Point(642581.58, 6947059.961, 0.0),
//            Point(642580.249, 6947055.344, 0.0), Point(642578.375, 6947050.92, 0.0), Point(642576.423, 6947047.7, 0.0), Point(642573.963, 6947044.85, 0.0),
//            Point(642571.061, 6947042.451, 0.0), Point(642567.8, 6947040.569, 0.0), Point(642564.27, 6947039.257, 0.0), Point(642560.572, 6947038.553, 0.0),
//            Point(642556.807, 6947038.475, 0.0), Point(642553.082, 6947039.026, 0.0), Point(642549.502, 6947040.19, 0.0), Point(642544.72, 6947042.202, 0.0)),
//          Municipality, BothDirections, FeatureClass.CycleOrPedestrianPath, None,
//          Map("LAST_EDITED_DATE" -> BigInt(1516719259000L), "CONSTRUCTIONTYPE" -> 1, "MTKCLASS" -> 12314, "Points" -> List(Map("x" -> 642581.506, "y" -> 6947078.918, "z" -> 0, "m" -> 0),
//            Map("x" -> 642582.157, "y" -> 6947074.293, "z" -> 0, "m" -> 4.669999999998254), Map("x" -> 642582.541, "y" -> 6947069.504, "z" -> 0, "m" -> 9.474600000001374),
//            Map("x" -> 642582.348, "y" -> 6947064.703, "z" -> 0, "m" -> 14.279200000004494), Map("x" -> 642581.58, "y" -> 6947059.961, "z" -> 0, "m" -> 19.08379999999306),
//            Map("x" -> 642580.249, "y" -> 6947055.344, "z" -> 0, "m" -> 23.88839999999618), Map("x" -> 642578.375, "y" -> 6947050.92, "z" -> 0, "m" -> 28.6929999999993),
//            Map("x" -> 642576.423, "y" -> 6947047.7, "z" -> 0, "m" -> 32.45819999999367), Map("x" -> 642573.963, "y" -> 6947044.85, "z" -> 0, "m" -> 36.22349999999278),
//            Map("x" -> 642571.061, "y" -> 6947042.451, "z" -> 0, "m" -> 39.9887000000017), Map("x" -> 642567.8, "y" -> 6947040.569, "z" -> 0, "m" -> 43.754000000000815),
//            Map("x" -> 642564.27, "y" -> 6947039.257, "z" -> 0, "m" -> 47.51910000000498), Map("x" -> 642560.572, "y" -> 6947038.553, "z" -> 0, "m" -> 51.284499999994296),
//            Map("x" -> 642556.807, "y" -> 6947038.475, "z" -> 0, "m" -> 55.04970000000321), Map("x" -> 642553.082, "y" -> 6947039.026, "z" -> 0, "m" -> 58.81489999999758),
//            Map("x" -> 642549.502, "y" -> 6947040.19, "z" -> 0, "m" -> 62.580100000006496), Map("x" -> 642544.72, "y" -> 6947042.202, "z" -> 0, "m" -> 67.76810000000114)),
//            "OBJECTID" -> 14132, "SUBTYPE" -> 2, "VERTICALLEVEL" -> 0, "MUNICIPALITYCODE" -> 167, "CREATED_DATE" -> BigInt(1490794018000L)), ConstructionType.UnderConstruction,
//          SuravageLinkInterface, 67.768), None
//        )
//      suravageAddress.trackCode should be(Track.Combined.value)
//      suravageAddress.startAddressM should be(0)
//      suravageAddress.endAddressM should be(68)
//    }
//  }

  //TODO will be implemented at VIITE-1550
//  test("Suravage link builder when link is not in DB") {
//    val newLinkId1 = 5000
//    val municipalityCode = 564
//    val administrativeClass = Municipality
//    val trafficDirection = TrafficDirection.TowardsDigitizing
//    val attributes1 = Map("ROADNUMBER" -> BigInt(99), "ROADPARTNUMBER" -> BigInt(24))
//    val suravageAddress = OracleDatabase.withDynSession {
//      RoadAddressLinkBuilder.buildSuravageRoadAddressLink(VVHRoadlink(newLinkId1, municipalityCode,
//        List(Point(1.0, 0.0), Point(20.0, 1.0)), administrativeClass, trafficDirection, FeatureClass.DrivePath, None,
//        attributes1, ConstructionType.UnderConstruction, LinkGeomSource.SuravageLinkInterface, 30), None)
//    }
//
//    suravageAddress.linkId should be(newLinkId1)
//    suravageAddress.administrativeClass should be(administrativeClass)
//    suravageAddress.constructionType should be(ConstructionType.UnderConstruction)
//    suravageAddress.sideCode should be(SideCode.Unknown)
//    suravageAddress.roadNumber should be(99)
//    suravageAddress.roadPartNumber should be(24)
//    suravageAddress.startMValue should be(0)
//    suravageAddress.endMValue should be(19.026297590440446)
//    suravageAddress.roadLinkSource should be(LinkGeomSource.SuravageLinkInterface)
//    suravageAddress.elyCode should be(12)
//    suravageAddress.municipalityCode should be(municipalityCode)
//    suravageAddress.geometry.size should be(2)
//  }

//TODO will be implemented at VIITE-1550
//  test("Suravage link builder when link is in DB roadaddress table") {
//    runWithRollback {
//      sqlu""" alter session set nls_language = 'american' NLS_NUMERIC_CHARACTERS = ', '""".execute
//      sqlu"""Insert into ROAD_ADDRESS (ID,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK_CODE,DISCONTINUITY,START_ADDR_M,END_ADDR_M,START_DATE,END_DATE,CREATED_BY,
//          VALID_FROM,CALIBRATION_POINTS,FLOATING,GEOMETRY,VALID_TO,ELY,ROAD_TYPE,TERMINATED,COMMON_HISTORY_ID,
//          SIDE_CODE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,MODIFIED_DATE,LINK_SOURCE) values ('9124288','62555','2','0','1','0','68',
//          to_date('23.04.2018','DD.MM.RRRR'),null,'k189826',to_date('23.04.2018','DD.MM.RRRR'),'3','0',MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1),
//          MDSYS.SDO_ORDINATE_ARRAY(642581.506, 6947078.918, 0, 0, 642544.7200222166, 6947042.201990652, 0, 68)),null,'8','3','0','191816022',
//          '2','0','67,768','7096025','1516719259000',to_timestamp('23.04.2018 00:00:00,000000000','DD.MM.RRRR HH24:MI:SSXFF'),'3')""".execute
//      val suravageLinkId1 = 7096025
//      val municipalityCode = 564
//      val administrativeClass = Municipality
//      val trafficDirection = TrafficDirection.TowardsDigitizing
//      val attributes1 = Map("ROADNUMBER" -> BigInt(99), "ROADPARTNUMBER" -> BigInt(24))
//      val suravageAddress = RoadAddressLinkBuilder.buildSuravageRoadAddressLink(VVHRoadlink(suravageLinkId1, municipalityCode,
//        List(Point(1.0, 0.0), Point(20.0, 1.0)), administrativeClass, trafficDirection, FeatureClass.DrivePath, None,
//        attributes1, ConstructionType.UnderConstruction, LinkGeomSource.SuravageLinkInterface, 30), None)
//
//      suravageAddress.sideCode should be(SideCode.TowardsDigitizing)
//      suravageAddress.endAddressM should be(68)
//      suravageAddress.startAddressM should be(0)
//      suravageAddress.elyCode should be(8)
//      suravageAddress.trackCode should be(0)
//      suravageAddress.roadNumber should be(62555)
//      suravageAddress.roadPartNumber should be(2)
//    }
//  }

  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockRoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val projectService = new ProjectService(roadAddressService,  mockRoadLinkService, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  val roadAddressService = new RoadAddressService(mockRoadLinkService, mockRoadwayAddressMapper, mockEventBus) {
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }


  test("Suravage link builder when link is in DB project-link table") {
    runWithRollback {
      sqlu""" alter session set nls_language = 'american' NLS_NUMERIC_CHARACTERS = ', '""".execute
      val rap = RoadAddressProject(0L, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"),
        "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info",
        Seq(), None)
      val project = projectService.createRoadLinkProject(rap)
      val newLink = Seq(ProjectLink(-1000L, 9999L, 1L, Track.apply(0), Discontinuity.Continuous, 0L, 0L, None, None,
        None, 7096025L, 0.0, 43.1, SideCode.AgainstDigitizing, (None, None), NoFloating,
        Seq(Point(468.5, 0.5), Point(512.0, 0.0)), project.id, LinkStatus.Unknown, RoadType.PublicRoad, LinkGeomSource.NormalLinkInterface, 43.1, 0L, 0, false,
        None, 86400L))

      projectService.addNewLinksToProject(newLink, project.id, "U", 7096025, false)

      val suravageLinkId1 = 7096025
      val municipalityCode = 564
      val administrativeClass = Municipality
      val trafficDirection = TrafficDirection.TowardsDigitizing
      val attributes1 = Map("ROADNUMBER" -> BigInt(99), "ROADPARTNUMBER" -> BigInt(24))
      val suravageAddress = RoadAddressLinkBuilder.buildSuravageRoadAddressLink(VVHRoadlink(suravageLinkId1, municipalityCode,
        List(Point(1.0, 0.0), Point(20.0, 1.0)), administrativeClass, trafficDirection, FeatureClass.DrivePath, None,
        attributes1, ConstructionType.UnderConstruction, LinkGeomSource.SuravageLinkInterface, 30), Some(project.id))
      suravageAddress.sideCode should be(SideCode.AgainstDigitizing)
    }
  }

  //TODO Probably this will be not needed anymore
//  test("Fuse road address should combine geometries and address values with starting calibration point - real life scenario") {
//    val geom = Seq(Point(379483.273, 6672835.486), Point(379556.289, 6673054.073))
//    val roadAddress = Seq(
//      RoadAddress(3767413, 101, 1, RoadType.Unknown, Track.RightSide, Discontinuous, 679L, 701L, Some(DateTime.parse("1991-01-01")), None, Option("tester"), 138834, 0.0, 21.0,
//        SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(138834, 0.0, 679L)), None), NoFloating, GeometryUtils.truncateGeometry3D(geom, 0.0, 21.0), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//
//      RoadAddress(3767414, 101, 1, RoadType.Unknown, Track.RightSide, Discontinuous, 701L, 923L, Some(DateTime.parse("1991-01-01")), None, Option("tester"), 138834, 21.0, 230.776,
//        SideCode.TowardsDigitizing, 0, (None, None), NoFloating, GeometryUtils.truncateGeometry3D(geom, 21.0, 230.776), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//    )
//    val fusedList = RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress)
//    fusedList should have size (1)
//    val fused = fusedList.head
//    fused.startMValue should be(0.0)
//    fused.endMValue should be(230.776)
//    fused.geometry.last should be(Point(379556.289, 6673054.073))
//    fused.geometry should have size (2)
//    fused.startAddrMValue should be(679L)
//    fused.endAddrMValue should be(923L)
//    fused.track should be(Track.RightSide)
//    fused.calibrationPoints._1.isEmpty should be(false)
//    fused.calibrationPoints._2.isEmpty should be(true)
//
//  }

  //TODO Probably this will be not needed anymore
//  test("Fuse road address should use single calibration point for both") {
//    val geom = Seq(Point(379483.273, 6672835.486), Point(379556.289, 6673054.073))
//    val roadAddress = Seq(
//      RoadAddress(3767413, 101, 1, RoadType.Unknown, Track.RightSide, Discontinuous, 679L, 701L, Some(DateTime.parse("1991-01-01")),
//        None, Option("tester"), 138834, 0.0, 21.0, SideCode.TowardsDigitizing, 0, (Some(CalibrationPoint(138834, 0.0, 679L)),
//          Some(CalibrationPoint(138834, 21.0, 920L))), NoFloating, GeometryUtils.truncateGeometry3D(geom, 0.0, 21.0), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//
//      RoadAddress(3767414, 101, 1, RoadType.Unknown, Track.RightSide, Discontinuous, 701L, 923L, Some(DateTime.parse("1991-01-01")),
//        None, Option("tester"), 138834, 21.0, 230.776, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        GeometryUtils.truncateGeometry3D(geom, 21.0, 230.776), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//    )
//    val fusedList = RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress)
//    fusedList should have size (1)
//    val fused = fusedList.head
//    fused.startMValue should be(0.0)
//    fused.endMValue should be(230.776)
//    fused.geometry.last should be(Point(379556.289, 6673054.073))
//    fused.geometry should have size (2)
//    fused.startAddrMValue should be(679L)
//    fused.endAddrMValue should be(920L)
//    fused.track should be(Track.RightSide)
//    fused.calibrationPoints._1.isEmpty should be(false)
//    fused.calibrationPoints._2.isEmpty should be(false)
//    fused.calibrationPoints._2.get.addressMValue should be(920L)
//    fused.calibrationPoints._2.get.segmentMValue should be(230.776)
//  }

  //TODO Probably this will be not needed anymore
//  test("Fuse road address should fuse against digitization road addresses properly") {
//    OracleDatabase.withDynSession {
//      val roadAddress = Seq(
//        RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.AgainstDigitizing, 0, (None, None), NoFloating,
//          Seq(Point(0.0, 9.8), Point(0.0, 0.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//        RoadAddress(4, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 30L, 40L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 30.0, 39.8, SideCode.AgainstDigitizing, 0, (None, None), NoFloating,
//          Seq(Point(0.0, 39.8), Point(0.0, 30.0)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//        RoadAddress(3, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 20L, 30L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 10.4, 30.0, SideCode.AgainstDigitizing, 0, (None, None), NoFloating,
//          Seq(Point(0.0, 30.0), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//        RoadAddress(2, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.4, SideCode.AgainstDigitizing, 0, (None, None), NoFloating,
//          Seq(Point(0.0, 20.2), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//      )
//      RoadAddressLinkBuilder.fuseRoadAddress(roadAddress) should have size (1)
//    }
//  }

  test("Building ProjectAddressLink partitioner") {
    val unknownProjectLink = ProjectLink(0, 0, 0, Track.Unknown, Discontinuity.Continuous, 0, 0, None, None, None, 0, 0.0, 0.0,
      SideCode.Unknown, (None, None), NoFloating, List(), 0, NotHandled, UnknownOwnerRoad, LinkGeomSource.NormalLinkInterface, 0.0, 0, 8, false,
      None, 85088L)
    val projectLinks =
      Map(
        1717380l -> ProjectLink(1270, 0, 0, Track.apply(99), Continuous, 1021, 1028, None, None, None, 1717380, 0.0, 6.0,
          AgainstDigitizing, (None, None), NoFloating, List(), 1227, NotHandled, UnknownOwnerRoad, LinkGeomSource.NormalLinkInterface, 0.0, 0, 8, false, None, 85088L),
        1717374l -> ProjectLink(1259, 1130, 0, Combined, Continuous, 959, 1021, None, None, None, 1717374, 0.0, 61.0,
          AgainstDigitizing, (None, None), NoFloating, List(), 1227, NotHandled, UnknownOwnerRoad, LinkGeomSource.NormalLinkInterface, 0.0, 0, 8, false, None, 85088L)
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

  //TODO Probably this will be not needed anymore
//  test("Fuse road address should not merge different termination status") {
//    val roadAddress = Seq(
//      RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8,
//        SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, Termination, 0),
//      RoadAddress(2, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.4,
//        SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//      RoadAddress(3, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 20L, 30L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.4,
//        SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 20.2), Point(0.0, 40.2)), LinkGeomSource.NormalLinkInterface, 8, Subsequent, 0)
//    )
//    RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress) should have size (3)
//  }

  //TODO Probably this will be not needed anymore
//  test("Fuse road address should not merge different ely code") {
//    val roadAddress = Seq(
//      RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8,
//        SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//      RoadAddress(2, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.4,
//        SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 9, NoTermination, 0)
//    )
//    RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress) should have size (2)
//  }

  //TODO Probably this will be not needed anymore
//  test("Fuse road address should not merge different road type") {
//    val roadAddress = Seq(
//      RoadAddress(1, 1, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8,
//        SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//      RoadAddress(2, 1, 1, RoadType.FerryRoad, Track.Combined, Discontinuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 10.4,
//        SideCode.TowardsDigitizing, 0, (None, None), FloatingReason.ApplyChanges, Seq(Point(0.0, 9.8), Point(0.0, 20.2)), LinkGeomSource.NormalLinkInterface, 9, NoTermination, 0)
//    )
//    RoadAddressLinkBuilder.fuseRoadAddressWithTransaction(roadAddress) should have size (2)
//  }

  //TODO Probably this will be not needed anymore
//  test("RoadAddressBuilder should correctly build a road address from a suravage road link source") {
//    val roadLinkGeom = List(Point(642357.37, 6946486.267, 0.0), Point(642416.887, 6946751.966, 0.0),
//      Point(642439.702, 6946868.15, 0.0), Point(642441.166, 6946878.412, 0.0), Point(642442.096, 6946888.737, 0.0),
//      Point(642442.49, 6946899.096, 0.0), Point(642442.347, 6946909.461, 0.0), Point(642439.463, 6946981.961, 0.0),
//      Point(642438.564, 6946997.224, 0.0), Point(642437.082, 6947012.441, 0.0), Point(642435.019, 6947027.591, 0.0),
//      Point(642432.378, 6947042.65, 0.0), Point(642429.165, 6947057.598, 0.0), Point(642425.382, 6947072.411, 0.0),
//      Point(642423.045, 6947082.137, 0.0), Point(642421.362, 6947091.997, 0.0), Point(642420.432, 6947101.047, 0.0))
//
//    val roadLink = VVHRoadlink(7519921, 167, roadLinkGeom, State, TrafficDirection.UnknownDirection,
//      FeatureClass.CycleOrPedestrianPath, None,
//      Map.empty[String, Any], ConstructionType.Planned, SuravageLinkInterface, 625.547)
//
//    val roadAddress = RoadAddress(411482, 70006, 561, RoadType.PublicRoad, Track.Combined, Discontinuity.EndOfRoad, 0, 626,
//      Some(DateTime.parse("2018-01-16T00:00:00.000+02:00")), None, Some("silari"), 7519921, 0.0, 625.547, SideCode.TowardsDigitizing,
//      1515766393000L, (Some(CalibrationPoint(7519921, 0.0, 0)), Some(CalibrationPoint(7519921, 625.547, 626))), NoFloating,
//      List(Point(642357.37, 6946486.267, 0.0), Point(642420.432, 6947101.047, 0.0)), SuravageLinkInterface, 8, NoTermination, 0)
//
//    val supposedRoadAddressGeom = GeometryUtils.truncateGeometry3D(roadLink.geometry, roadAddress.startMValue, roadAddress.endMValue)
//    val buildRoadAddressLink = RoadAddressLinkBuilder.build(roadLink, roadAddress)
//    buildRoadAddressLink.geometry should be(supposedRoadAddressGeom)
//    buildRoadAddressLink.linkId should be(roadLink.linkId)
//    buildRoadAddressLink.municipalityCode should be(roadLink.municipalityCode)
//    buildRoadAddressLink.trackCode should be(roadAddress.track.value)
//    buildRoadAddressLink.roadNumber should be(roadAddress.roadNumber)
//    buildRoadAddressLink.roadPartNumber should be(roadAddress.roadPartNumber)
//  }

}
