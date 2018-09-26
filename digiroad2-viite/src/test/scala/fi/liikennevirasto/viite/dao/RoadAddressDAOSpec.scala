package fi.liikennevirasto.viite.dao

import java.sql.SQLException

import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.ComplimentaryLinkInterface
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import fi.liikennevirasto.viite.dao.Discontinuity.Discontinuous
import fi.liikennevirasto.viite.dao.FloatingReason.{GeometryChanged, NoFloating}
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.{ReservedRoadPart, RoadAddressMerge, RoadAddressService, RoadType}
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{StaticQuery => Q}

import scala.util.control.NonFatal


/**
  * Created by venholat on 12.9.2016.
  */
class RoadAddressDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  //TODO test the constraints ROADWAY_HISTORY_UK and TERMINATION_END_DATE_CHK
  test("insert road address duplicate info check") {
      runWithRollback {
        val error = intercept[SQLException] {
          sqlu""" Insert into ROADWAY (ID,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK_CODE,DISCONTINUITY,START_ADDR_M,END_ADDR_M,START_DATE,END_DATE,CREATED_BY,VALID_FROM,CALIBRATION_POINTS,FLOATING,GEOMETRY,VALID_TO, ROAD_TYPE, ELY, SIDE_CODE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,MODIFIED_DATE,LINK_SOURCE) values (viite_general_seq.nextval,1010,1,0,5,627,648,to_date('63.01.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4002,3067,NULL,MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1),MDSYS.SDO_ORDINATE_ARRAY(288781.428,6825565.909,0,0,288763.118,6825576.235,0,21)),null, 1, 4,2,0,21.021, 1111102483,1476392565000,to_timestamp('17.09.15 19:39:30','RR.MM.DD HH24:MI:SS,FF'),1)""".execute
          sqlu""" Insert into ROADWAY (ID,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK_CODE,DISCONTINUITY,START_ADDR_M,END_ADDR_M,START_DATE,END_DATE,CREATED_BY,VALID_FROM,CALIBRATION_POINTS,FLOATING,GEOMETRY,VALID_TO, ROAD_TYPE, ELY, SIDE_CODE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,MODIFIED_DATE,LINK_SOURCE) values (viite_general_seq.nextval,1010,1,0,5,627,648,to_date('63.01.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4002,3067,NULL,MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1),MDSYS.SDO_ORDINATE_ARRAY(288781.428,6825565.909,0,0,288763.118,6825576.235,0,21)),null, 1, 4,2,0,21.021, 1111102483,1476392565000,to_timestamp('17.09.15 19:39:30','RR.MM.DD HH24:MI:SS,FF'),1)""".execute
        }
        error.getErrorCode should be (1)
      }
  }

//TODO will be implemented at VIITE-1552
//  test("insert road address m-values overlap") {
//    runWithRollback {
//      val error = intercept[SQLException] {
//        sqlu""" Insert into ROADWAY (ID,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK_CODE,DISCONTINUITY,START_ADDR_M,END_ADDR_M,START_DATE,END_DATE,CREATED_BY,VALID_FROM,CALIBRATION_POINTS,FLOATING,GEOMETRY,VALID_TO, ROAD_TYPE, ELY, SIDE_CODE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,MODIFIED_DATE,LINK_SOURCE) values (viite_general_seq.nextval,1010,1,0,5,627,648,to_date('63.01.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4002,3067,NULL,MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1),MDSYS.SDO_ORDINATE_ARRAY(288781.428,6825565.909,0,0,288763.118,6825576.235,0,21)),null, 1, 4,2,0,21.021,1111102483,1476392565000,to_timestamp('17.09.15 19:39:30','RR.MM.DD HH24:MI:SS,FF'),1)""".execute
//        sqlu""" Insert into ROADWAY (ID,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK_CODE,DISCONTINUITY,START_ADDR_M,END_ADDR_M,START_DATE,END_DATE,CREATED_BY,VALID_FROM,CALIBRATION_POINTS,FLOATING,GEOMETRY,VALID_TO, ROAD_TYPE, ELY, SIDE_CODE,START_MEASURE,END_MEASURE,LINK_ID,ADJUSTED_TIMESTAMP,MODIFIED_DATE,LINK_SOURCE) values (viite_general_seq.nextval,1010,1,0,5,627,648,to_date('63.01.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4012,3057,NULL,MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1),MDSYS.SDO_ORDINATE_ARRAY(288781.428,6825565.909,0,0,288763.118,6825576.235,0,21)),null, 1, 4,2,0,21.021,1111102483,1476392565000,to_timestamp('17.09.15 19:39:30','RR.MM.DD HH24:MI:SS,FF'),1)""".execute
//      }
//      error.getErrorCode should be(29875)
//    }
//  }

//TODO will be implemented at VIITE-1553
//  test("testFetchByRoadPart") {
//    runWithRollback {
//      RoadAddressDAO.fetchByRoadPart(5L, 201L).isEmpty should be(false)
//    }
//  }

//TODO will be implemented at VIITE-1553
//  test("testFetchByLinkId") {
//    runWithRollback {
//      val sets = RoadAddressDAO.fetchByLinkId(Set(5170942, 5170947))
//      sets.size should be (2)
//      sets.forall(_.isFloating == false) should be (true)
//    }
//  }

  //TODO will be implemented at VIITE-1553
//  test("Get valid road numbers") {
//    runWithRollback {
//      val numbers = RoadAddressDAO.getAllValidRoadNumbers()
//      numbers.isEmpty should be(false)
//      numbers should contain(5L)
//    }
//  }

  //TODO will be implemented at VIITE-1553
//  test("Get valid road part numbers") {
//    runWithRollback {
//      val numbers = RoadAddressDAO.getValidRoadParts(5L)
//      numbers.isEmpty should be(false)
//      numbers should contain(201L)
//    }
//  }

  //TODO will be implemented at VIITE-1552
//  test("Update without geometry") {
//    runWithRollback {
//      val address = RoadAddressDAO.fetchByLinkId(Set(5170942)).head
//      RoadAddressDAO.update(address)
//    }
//  }

  //TODO will be implemented at VIITE-1552
//  test("Updating a geometry is executed in SQL server") {
//    runWithRollback {
//      val address = RoadAddressDAO.fetchByLinkId(Set(5170942)).head
//      RoadAddressDAO.update(address, Some(Seq(Point(50200, 7630000.0, 0.0), Point(50210, 7630000.0, 10.0))))
//      RoadAddressDAO.fetchRoadAddressesByBoundingBox(BoundingRectangle(Point(50202, 7620000), Point(50205, 7640000)), false).exists(_.id == address.id) should be (true)
//      RoadAddressDAO.fetchRoadAddressesByBoundingBox(BoundingRectangle(Point(50212, 7620000), Point(50215, 7640000)), false).exists(_.id == address.id) should be (false)
//    }
//  }

  //TODO will be implemented at VIITE-1542
//  test("Fetch unaddressed road links by boundingBox"){
//    runWithRollback {
//      val boundingBox = BoundingRectangle(Point(6699381, 396898), Point(6699382, 396898))
//      sqlu"""
//           insert into UNADDRESSED_ROAD_LINK (link_id, start_addr_m, end_addr_m,anomaly_code, start_m, end_m, geometry)
//           values (1943845, 0, 1, 1, 0, 34.944, MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(6699381,396898,0,0.0,6699382,396898,0,2)))
//           """.execute
//
//      val unaddressedRoadLinks = RoadAddressDAO.fetchUnaddressedRoadLinksByBoundingBox(boundingBox)
//      val addedValue = unaddressedRoadLinks.find(p => p.linkId == 1943845).get
//      addedValue should not be None
//      addedValue.geom.nonEmpty should be (true)
//      addedValue.startAddrMValue.get should be (0)
//      addedValue.endAddrMValue.get should be (1)
//    }
//  }

  //TODO will be implemented at VIITE-1537
//  test("Set road address to floating and update the geometry as well") {
//    runWithRollback {
//      val address = RoadAddressDAO.fetchByLinkId(Set(5170942)).head
//      RoadAddressDAO.changeRoadAddressFloatingWithHistory(address.id, Some(Seq(Point(50200, 7630000.0, 0.0), Point(50210, 7630000.0, 10.0))), floatingReason = GeometryChanged)
//    }
//  }

  //TODO will be implemented at VIITE-153
//  test("Create Road Address") {
//    runWithRollback {
//      val id = RoadAddressDAO.getNextRoadwayId
//      val ra = Seq(RoadAddress(id, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
//      val currentSize = RoadAddressDAO.fetchByRoadPart(ra.head.roadNumber, ra.head.roadPartNumber).size
//      val returning = RoadAddressDAO.create(ra)
//      returning.nonEmpty should be (true)
//      returning.head should be (id)
//      val newSize = currentSize + 1
//      RoadAddressDAO.fetchByRoadPart(ra.head.roadNumber, ra.head.roadPartNumber) should have size(newSize)
//    }
//  }

  //TODO will be implemented at VIITE-1542
//  test("Adding geometry to unaddressed road link") {
//    runWithRollback {
//      val id = 1943845
//      sqlu"""
//           insert into UNADDRESSED_ROAD_LINK (link_id, start_addr_m, end_addr_m,anomaly_code, start_m)
//           values ($id, 0, 1, 1, 1)
//           """.execute
//      sqlu"""UPDATE UNADDRESSED_ROAD_LINK
//        SET geometry= MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1), MDSYS.SDO_ORDINATE_ARRAY(
//             6699381,396898,0,0.0,6699382,396898,0,2))
//        WHERE link_id = ${id}""".execute
//      val query= s"""select Count(geometry)
//                 from UNADDRESSED_ROAD_LINK ra
//                 WHERE ra.link_id=$id AND geometry IS NOT NULL
//      """
//      Q.queryNA[Int](query).firstOption should be (Some(1))
//    }
//  }

  //TODO will be implemented at VIITE-1553
//  test("Create Road Address with username") {
//    runWithRollback {
//      val username = "testUser"
//      val id = RoadAddressDAO.getNextRoadwayId
//      val ra = Seq(RoadAddress(id, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
//      val currentSize = RoadAddressDAO.fetchByRoadPart(ra.head.roadNumber, ra.head.roadPartNumber).size
//      val returning = RoadAddressDAO.create(ra, Some(username))
//      returning.nonEmpty should be (true)
//      returning.head should be (id)
//      val newSize = currentSize + 1
//      val roadAddress = RoadAddressDAO.fetchByRoadPart(ra.head.roadNumber, ra.head.roadPartNumber)
//      roadAddress should have size(newSize)
//      roadAddress.head.createdBy.get should be (username)
//    }
//  }

  //TODO will be implemented at VIITE-1552
//  test("Create Road Address With Calibration Point") {
//    runWithRollback {
//      val id = RoadAddressDAO.getNextRoadwayId
//      val ra = Seq(RoadAddress(id, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0,
//        (Some(CalibrationPoint(12345L, 0.0, 0L)), None), NoFloating,
//        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
//      val returning = RoadAddressDAO.create(ra)
//      returning.nonEmpty should be (true)
//      returning.head should be (id)
//      val fetch = sql"""select calibration_points from ROADWAY where id = $id""".as[Int].list
//      fetch.head should be (2)
//    }
//    runWithRollback {
//      val id = RoadAddressDAO.getNextRoadwayId
//      val ra = Seq(RoadAddress(id, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0,
//        (Some(CalibrationPoint(12345L, 0.0, 0L)), Some(CalibrationPoint(12345L, 9.8, 10L))), NoFloating,
//        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
//      val returning = RoadAddressDAO.create(ra)
//      returning.nonEmpty should be (true)
//      returning.head should be (id)
//      val fetch = sql"""select calibration_points from ROADWAY where id = $id""".as[Int].list
//      fetch.head should be (3)
//    }
//  }

  //TODO will be implemented at VIITE-1552
//  test("Create Road Address with complementary source") {
//    runWithRollback {
//      val id = RoadAddressDAO.getNextRoadwayId
//      val ra = Seq(RoadAddress(id, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")),
//        None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.ComplimentaryLinkInterface, 8, NoTermination, 0))
//      val returning = RoadAddressDAO.create(ra)
//      returning.nonEmpty should be (true)
//      returning.head should be (id)
//      sql"""SELECT link_source FROM ROADWAY ra WHERE ra.id = $id"""
//        .as[Int].first should be (ComplimentaryLinkInterface.value)
//    }
//  }


  //TODO will be implemented at VIITE-1553
//  test("Delete Road Addresses") {
//    runWithRollback {
//      val addresses = RoadAddressDAO.fetchByRoadPart(5, 206)
//      addresses.nonEmpty should be (true)
//      RoadAddressDAO.remove(addresses) should be (addresses.size)
//      sql"""SELECT COUNT(*) FROM ROADWAY WHERE ROAD_NUMBER = 5 AND ROAD_PART_NUMBER = 206 AND VALID_TO IS NULL""".as[Long].first should be (0L)
//    }
//  }

  //TODO probably this test will not be needed
//  test("test update for merged Road Addresses") {
//    val localMockRoadLinkService = MockitoSugar.mock[RoadLinkService]
//    val localMockEventBus = MockitoSugar.mock[DigiroadEventBus]
//    val localRoadAddressService = new RoadAddressService(localMockRoadLinkService,localMockEventBus)
//    runWithRollback {
//      val id1 = RoadAddressDAO.getNextRoadwayId
//      val ra = Seq(RoadAddress(id1, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
//      RoadAddressDAO.create(ra, Some("user"))
//      val id = RoadAddressDAO.getNextRoadwayId
//      val toBeMergedRoadAddresses = Seq(RoadAddress(id, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"), 6556558L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
//      localRoadAddressService.mergeRoadAddressInTX(RoadAddressMerge(Set(id1), toBeMergedRoadAddresses))
//    }
//  }

//  ignore("test if road addresses are expired") {
//    def now(): DateTime = {
//      OracleDatabase.withDynSession {
//        return sql"""select sysdate FROM dual""".as[DateTime].list.head
//      }
//    }
//
//    val beforeCallMethodDatetime = now()
//    runWithRollback {
//      val linkIds: Set[Long] = Set(4147081)
//      RoadAddressDAO.expireRoadAddresses(linkIds)
//      val dbResult = sql"""select valid_to FROM ROADWAY where link_id in (4147081)""".as[DateTime].list
//      dbResult.size should be (1)
//      dbResult.foreach{ date =>
//        date.getMillis should be >= beforeCallMethodDatetime.getMillis
//      }
//    }
//  }

  //TODO will be implemented at VIITE-1553
//  test("find road address by start or end address value") {
//    OracleDatabase.withDynSession {
//      val s = RoadAddressDAO.fetchByAddressStart(75, 1, Track.apply(2), 875)
//      val e = RoadAddressDAO.fetchByAddressEnd(75, 1, Track.apply(2), 995)
//      s.isEmpty should be(false)
//      e.isEmpty should be(false)
//      s should be(e)
//    }
//  }

  /*
  1.  RA has START_DATE < PROJ_DATE, END_DATE = null
  2.a START_DATE > PROJ_DATE, END_DATE = null
  2.b START_DATE == PROJ_DATE, END_DATE = null
  3.a START_DATE < PROJ_DATE, END_DATE < PROJ_DATE
  3.b START_DATE < PROJ_DATE, END_DATE == PROJ_DATE
  4.a START_DATE < PROJ_DATE, END_DATE > PROJ_DATE
  4.b START_DATE == PROJ_DATE, END_DATE > PROJ_DATE
  5.a START_DATE > PROJ_DATE, END_DATE > PROJ_DATE
  5.b START_DATE == PROJ_DATE, END_DATE > PROJ_DATE
  1 and 3 are acceptable scenarios
  6. Combination 1+3a(+3a+3a+3a+...)
  7. Expired rows are not checked
   */
  //TODO will be implemented at VIITE-1539
//  test("New roadnumber and roadpart available because start date before project date and no end date (1)") {
//    runWithRollback {
//      val id1 = Sequences.nextViitePrimaryKeySeqValue
//      val rap1 = RoadAddressProject(id1, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap1)
//      // Check that the DB contains only null values in end dates
//      RoadAddressDAO.fetchByRoadPart(5, 205).map(_.endDate).forall(ed => ed.isEmpty) should be (true)
//      val reserved1 = RoadAddressDAO.isNotAvailableForProject(5, 205, id1)
//      reserved1 should be(false)
//    }
//  }

  //TODO will be implemented at VIITE-1539
//  test("New roadnumber and roadpart not available because start date after project date (2a)") {
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1901-01-01"), "TestUser", DateTime.parse("1901-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap)
//      val reserved = RoadAddressDAO.isNotAvailableForProject(5,205,id)
//      reserved should be (true)
//    }
//  }

  //TODO will be implemented at VIITE-1539
//  test("Roadnumber and roadpart available because start date equals project date (2b)") {
//    // Update: after VIITE-1411 we can have start date equal to project date
//    runWithRollback {
//      val id3 = Sequences.nextViitePrimaryKeySeqValue
//      val rap3 = RoadAddressProject(id3, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1962-11-01"),
//        "TestUser", DateTime.parse("1962-11-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap3)
//      // Check that the DB contains the start date
//      RoadAddressDAO.fetchByRoadPart(5, 207).flatMap(_.startDate.map(_.toDate)).min should be (DateTime.parse("1962-11-01").toDate)
//      val reserved3 = RoadAddressDAO.isNotAvailableForProject(5,207,id3)
//      reserved3 should be (false)
//    }
//  }
  //TODO will be implemented at VIITE-1539
//  test("New roadnumber and roadpart available because start date and end date before project date (3a)") {
//    runWithRollback {
//      createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), Option.apply(DateTime.parse("2000-01-01")))
//      val id4 = Sequences.nextViitePrimaryKeySeqValue
//      val rap4 = RoadAddressProject(id4, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap4)
//      val reserved4 = RoadAddressDAO.isNotAvailableForProject(8888,1,id4)
//      reserved4 should be (false)
//    }
//  }
  //TODO will be implemented at VIITE-1539
//  test("New roadnumber and roadpart available because end date equals project date (3b)") {
//    runWithRollback {
//      createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), Option.apply(DateTime.parse("2700-01-01")))
//      val id5 = Sequences.nextViitePrimaryKeySeqValue
//      val rap5 = RoadAddressProject(id5, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap5)
//      val reserved5 = RoadAddressDAO.isNotAvailableForProject(8888,1,id5)
//      reserved5 should be (false)
//
//    }
//  }
  //TODO will be implemented at VIITE-1539
//  test("New roadnumber and roadpart not available because project date between start and end date (4a)") {
//    runWithRollback {
//      createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), Option.apply(DateTime.parse("2800-01-01")))
//      val id6 = Sequences.nextViitePrimaryKeySeqValue
//      val rap6 = RoadAddressProject(id6, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap6)
//      val reserved6 = RoadAddressDAO.isNotAvailableForProject(8888,1,id6)
//      reserved6 should be (true)
//    }
//  }

  //TODO will be implemented at VIITE-1539
//  test("New roadnumber and roadpart not available because project date between start and end date (4b)") {
//    runWithRollback {
//      createRoadAddress8888(Option.apply(DateTime.parse("2700-01-01")), Option.apply(DateTime.parse("2800-01-01")))
//      val id6 = Sequences.nextViitePrimaryKeySeqValue
//      val rap6 = RoadAddressProject(id6, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap6)
//      val reserved6 = RoadAddressDAO.isNotAvailableForProject(8888,1,id6)
//      reserved6 should be (true)
//
//    }
//  }

  //TODO will be implemented at VIITE-1539
//  test("New roadnumber and roadpart number not reservable if it's going to exist in the future (5a)") {
//    runWithRollback {
//      val id7 = Sequences.nextViitePrimaryKeySeqValue
//      val rap7 = RoadAddressProject(id7, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1975-01-01"),
//        "TestUser", DateTime.parse("1975-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap7)
//      createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), Option.apply(DateTime.parse("1990-01-01")))
//      val reserved7 = RoadAddressDAO.isNotAvailableForProject(8888,1,id7)
//      reserved7 should be (true)
//    }
//  }

  //TODO will be implemented at VIITE-1539
//  test("New roadnumber and roadpart number reservable if it's going to exist in the future (5b)") {
//    runWithRollback {
//      createRoadAddress8888(Option.apply(DateTime.parse("1975-01-01")))
//      val id9 = Sequences.nextViitePrimaryKeySeqValue
//      val rap9 = RoadAddressProject(id9, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1975-01-01"),
//        "TestUser", DateTime.parse("1975-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap9)
//      val reserved9 = RoadAddressDAO.isNotAvailableForProject(8888, 1, id9)
//      reserved9 should be(false)
//    }
//  }

  //TODO will be implemented at VIITE-1539
//  test("New roadnumber and roadpart available because last end date is open ended (6)") {
//    runWithRollback {
//      createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), Option.apply(DateTime.parse("2000-01-01")))
//      createRoadAddress8888(Option.apply(DateTime.parse("2000-01-01")), Option.apply(DateTime.parse("2001-01-01")))
//      createRoadAddress8888(Option.apply(DateTime.parse("2001-01-01")))
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2017-01-01"),
//        "TestUser", DateTime.parse("2017-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap)
//      val reserved = RoadAddressDAO.isNotAvailableForProject(8888,1,id)
//      reserved should be (false)
//    }
//  }

  //TODO will be implemented at VIITE-1539
//  test("invalidated rows don't affect reservation (7)") {
//    runWithRollback {
//      createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), Option.apply(DateTime.parse("2000-01-01")))
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("1997-01-01"),
//        "TestUser", DateTime.parse("1997-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap)
//      RoadAddressDAO.isNotAvailableForProject(8888,1,id) should be (true)
//      sqlu"""update ROADWAY set valid_to = sysdate WHERE road_number = 8888""".execute
//      createRoadAddress8888(Option.apply(DateTime.parse("1975-11-18")), None)
//      RoadAddressDAO.isNotAvailableForProject(8888,1,id) should be (false)
//    }
//  }

  //TODO will be implemented at VIITE-1539
//  test("New roadnumber and roadpart number  reserved") {
//    runWithRollback {
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap)
//      val reserved=   RoadAddressDAO.isNotAvailableForProject(1234567899,1,id)
//      reserved should be (false)
//    }
//  }

  //TODO will be implemented at VIITE-1539
//  test("Terminated road reservation") {
//    runWithRollback {
//      val idr = RoadAddressDAO.getNextRoadwayId
//      val ra = Seq(RoadAddress(idr, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
//      RoadAddressDAO.create(ra)
//      val id = Sequences.nextViitePrimaryKeySeqValue
//      val rap = RoadAddressProject(id, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("2700-01-01"), DateTime.now(), "Some additional info", List.empty[ReservedRoadPart], None)
//      ProjectDAO.createRoadAddressProject(rap)
//      val reserved=   RoadAddressDAO.isNotAvailableForProject(1943845,1,id)
//      reserved should be (false)
//    }
//  }

  //TODO will be implemented at VIITE-1539
//  test("Returning of a terminated road") {
//    runWithRollback {
//      createTerminatedRoadAddress7777(Option.apply(DateTime.parse("1975-11-18")))
//      val roadAddresses = RoadAddressDAO.fetchByLinkId(Set(7777777))
//      roadAddresses.size should be (1)
//      roadAddresses.head.terminated.value should be (1)
//    }
//  }

  //TODO will be implemented at VIITE-1550
//  test("Fetching road addresses by bounding box should ignore start dates") {
//    runWithRollback {
//      val addressId = RoadAddressDAO.getNextRoadwayId
//      val futureDate = DateTime.now.plusDays(5)
//      val ra = Seq(RoadAddress(addressId, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(futureDate), None, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(1.0, 1.0), Point(1.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
//      val returning = RoadAddressDAO.create(ra)
//      val currentSize = RoadAddressDAO.fetchByRoadPart(ra.head.roadNumber, ra.head.roadPartNumber).size
//      currentSize > 0 should be(true)
//      val bounding = BoundingRectangle(Point(0.0, 0.0), Point(10, 10))
//      val fetchedAddresses = RoadAddressDAO.fetchRoadAddressesByBoundingBox(bounding, false)
//      fetchedAddresses.exists(_.id == addressId) should be(true)
//    }
//  }

  //TODO will be implemented at VIITE-1550
//  test("Fetching road addresses by bounding box should get only the latest ones (end_date is null)") {
//    runWithRollback {
//      val addressId1 = RoadAddressDAO.getNextRoadwayId
//      val addressId2 = RoadAddressDAO.getNextRoadwayId
//      val startDate1 = Some(DateTime.now.minusDays(5))
//      val startDate2 = Some(DateTime.now.plusDays(5))
//      val EndDate1 = startDate2
//      val EndDate2 = None
//      val ra = Seq(RoadAddress(addressId1, 1943844, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, startDate1, EndDate1, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//        Seq(Point(1.0, 1.0), Point(1.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//        RoadAddress(addressId2, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, startDate2, EndDate2, Option("tester"), 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), NoFloating,
//          Seq(Point(1.0, 1.0), Point(1.0, 9.8)), LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0))
//      RoadAddressDAO.create(ra)
//      val bounding = BoundingRectangle(Point(0.0, 0.0), Point(10, 10))
//      val fetchedAddresses = RoadAddressDAO.fetchRoadAddressesByBoundingBox(bounding, false)
//      fetchedAddresses.exists(_.id == addressId1) should be(false)
//      fetchedAddresses.exists(_.id == addressId2) should be(true)
//    }
//  }

  //TODO will be implemented at VIITE-1550
//  test("Bounding box search should return the latest road address even if it's start date is in the future.") {
//    runWithRollback {
//      val id1 = RoadAddressDAO.getNextRoadwayId
//      val id2 = RoadAddressDAO.getNextRoadwayId
//      val ra = Seq(
//
//        RoadAddress(id1, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
//          Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("2100-01-01")), Option("tester"), 12345L, 0.0, 9.8,
//          SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 9.8)),
//          LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//
//        RoadAddress(id2, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
//          Some(DateTime.parse("2100-01-01")), None, Option("tester"), 12345L, 0.0, 9.8,
//          SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 9.8)),
//          LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0)
//
//      )
//      RoadAddressDAO.create(ra)
//      val results = RoadAddressDAO.fetchRoadAddressesByBoundingBox(BoundingRectangle(Point(0, 0), Point(10, 10)), false)
//      results.exists(_.id == id1) should be (false)
//      results.exists(_.id == id2) should be (true)
//    }
//  }

  //TODO will be implemented at VIITE-1550
//  test("Bounding box search should not return the road address even if it is currently not terminated but in the future.") {
//    runWithRollback {
//      val id1 = RoadAddressDAO.getNextRoadwayId
//      val id2 = RoadAddressDAO.getNextRoadwayId
//      val ra = Seq(
//
//        RoadAddress(id1, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
//          Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("2100-01-01")), Option("tester"), 12345L, 0.0, 9.8,
//          SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 9.8)),
//          LinkGeomSource.NormalLinkInterface, 8, NoTermination, 0),
//
//        RoadAddress(id2, 1943845, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L,
//          Some(DateTime.parse("2100-01-01")), Some(DateTime.parse("2120-01-01")), Option("tester"), 12345L, 0.0, 9.8,
//          SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(0.0, 0.0), Point(0.0, 9.8)),
//          LinkGeomSource.NormalLinkInterface, 8, TerminationCode.Termination, 0)
//
//      )
//      RoadAddressDAO.create(ra)
//      val results = RoadAddressDAO.fetchRoadAddressesByBoundingBox(BoundingRectangle(Point(0, 0), Point(10, 10)), false)
//      results.exists(_.id == id1) should be (false)
//      results.exists(_.id == id2) should be (false)
//    }
//  }

//  private def createRoadAddress8888(startDate: Option[DateTime], endDate: Option[DateTime] = None): Unit = {
//    RoadAddressDAO.create(
//      Seq(
//        RoadAddress(Sequences.nextRoadwayId, 8888, 1, RoadType.PublicRoad, Track.Combined,
//          Discontinuity.Continuous, 0, 35, startDate, endDate,
//          Option("TestUser"), 8888888, 0, 35, SideCode.TowardsDigitizing,
//          0, (None, None), NoFloating, Seq(Point(24.24477,987.456)), LinkGeomSource.Unknown, 8, NoTermination, 0)))
//  }
//
//  private def createTerminatedRoadAddress7777(startDate: Option[DateTime]): Unit = {
//    val roadwayId = Sequences.nextRoadwayId
//    RoadAddressDAO.create(
//      Seq(
//        RoadAddress(roadwayId, 7777, 1, RoadType.PublicRoad, Track.Combined,
//          Discontinuity.Continuous, 0, 35, startDate, Option.apply(DateTime.parse("2000-01-01")),
//          Option("TestUser"), 7777777, 0, 35, SideCode.TowardsDigitizing,
//          0, (None, None), NoFloating, Seq(Point(24.24477,987.456)), LinkGeomSource.Unknown, 8, NoTermination, 0)))
//    sqlu"""UPDATE ROADWAY SET Terminated = 1 Where ID = ${roadwayId}""".execute
//  }

}
