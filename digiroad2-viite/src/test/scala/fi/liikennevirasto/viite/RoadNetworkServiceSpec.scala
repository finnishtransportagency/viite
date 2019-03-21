package fi.liikennevirasto.viite


import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.dao.RoadNetworkDAO
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation


class RoadNetworkServiceSpec extends FunSuite with Matchers{

  val roadNetworkService = new RoadNetworkService {
    override def withDynSession[T](f: => T): T = f
    override def withDynTransaction[T](f: => T): T = f
  }
  val roadNetworkDAO = new RoadNetworkDAO
  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

//TODO Will be implemented once the new implementation for road network is done
//  test("validate network with valid road"){
//    runWithRollback {
//      RoadNetworkDAO.createPublishedRoadNetwork
//      val currentVersion = RoadNetworkDAO.getLatestRoadNetworkVersion.getOrElse(0L)
//
//      sqlu"""DELETE FROM ROADWAY WHERE ROAD_NUMBER = 18602""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,5,0,23,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),2,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476332.893, 7162464.873, 0, 0, 476316.71, 7162448.282, 0, 23)),null,12,1,0,1,3,0,23.182,5919553,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,5,23,46,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476316.71, 7162448.282, 0, 0, 476299.568, 7162432.764, 0, 23)),null,12,1,0,2,3,0,23.128,5919551,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,5,46,293,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476299.568, 7162432.764, 0, 0, 476109.906, 7162275.857, 0, 247)),null,12,1,0,3,3,0,251.114,5919545,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,5,293,370,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476109.906, 7162275.857, 0, 0, 476065.229, 7162211.93, 0, 77)),null,12,1,0,4,3,0,78.04,5919559,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,1,370,421,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),1,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476065.229, 7162211.93, 0, 0, 476060.737, 7162162.094, 0, 51)),null,12,1,0,5,3,0,51.69,5919566,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//
//      roadNetworkService.checkRoadAddressNetwork(RoadCheckOptions(Seq(18602L)))
//      RoadNetworkDAO.hasRoadNetworkErrors should be (false)
//      RoadNetworkDAO.getLatestRoadNetworkVersion.get should be (currentVersion + 1)
//    }
//  }
//
//  test("validate network with overlaping road") {
//    runWithRollback {
//      RoadNetworkDAO.createPublishedRoadNetwork
//      val currentVersion = RoadNetworkDAO.getLatestRoadNetworkVersion.getOrElse(0L)
//
//      sqlu"""DELETE FROM ROADWAY WHERE ROAD_NUMBER = 18602""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,5,0,23,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),2,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476332.893, 7162464.873, 0, 0, 476316.71, 7162448.282, 0, 23)),null,12,1,0,1,3,0,23.182,5919553,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,5,21,46,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476316.71, 7162448.282, 0, 0, 476299.568, 7162432.764, 0, 23)),null,12,1,0,2,3,0,23.128,5919551,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,5,46,293,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476299.568, 7162432.764, 0, 0, 476109.906, 7162275.857, 0, 247)),null,12,1,0,3,3,0,251.114,5919545,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,5,293,370,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476109.906, 7162275.857, 0, 0, 476065.229, 7162211.93, 0, 77)),null,12,1,0,4,3,0,78.04,5919559,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,1,370,421,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),1,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476065.229, 7162211.93, 0, 0, 476060.737, 7162162.094, 0, 51)),null,12,1,0,5,3,0,51.69,5919566,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//
//      roadNetworkService.checkRoadAddressNetwork(RoadCheckOptions(Seq(18602L)))
//      RoadNetworkDAO.hasRoadNetworkErrors should be (true)
//      RoadNetworkDAO.getLatestRoadNetworkVersion.get should be (currentVersion)
//    }
//  }
//
//  test("validate network with wrong discontinuity") {
//    runWithRollback {
//      RoadNetworkDAO.createPublishedRoadNetwork
//      val currentVersion = RoadNetworkDAO.getLatestRoadNetworkVersion.getOrElse(0L)
//
//      sqlu"""DELETE FROM ROADWAY WHERE ROAD_NUMBER = 18602""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,5,0,23,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),2,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476332.893, 7162464.873, 0, 0, 476316.71, 7162448.282, 0, 23)),null,12,1,0,1,3,0,23.182,5919553,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,5,23,46,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476316.71, 7162448.282, 0, 0, 476299.568, 7162432.764, 0, 23)),null,12,1,0,2,3,0,23.128,5919551,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,5,46,293,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476299.568, 7162432.764, 0, 0, 476109.906, 7162275.857, 0, 247)),null,12,1,0,3,3,0,251.114,5919545,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,5,293,370,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),0,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476109.906, 7162275.857, 0, 0, 476065.229, 7162211.93, 0, 77)),null,12,1,0,4,3,0,78.04,5919559,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//      sqlu"""Insert into ROADWAY values (viite_general_seq.nextval,18602,1,0,5,370,421,to_date('64.10.01','RR.MM.DD'),null,'tr',to_date('98.10.16','RR.MM.DD'),1,0,MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1, 2, 1), MDSYS.SDO_ORDINATE_ARRAY(476065.229, 7162211.93, 0, 0, 476060.737, 7162162.094, 0, 51)),null,12,1,0,5,3,0,51.69,5919566,1510790400000,to_timestamp('17.12.18 11:31:40','RR.MM.DD HH24:MI:SS'),1,NULL)""".execute
//
//      roadNetworkService.checkRoadAddressNetwork(RoadCheckOptions(Seq(18602L)))
//      RoadNetworkDAO.hasRoadNetworkErrors should be (true)
//      RoadNetworkDAO.getLatestRoadNetworkVersion.get should be (currentVersion)
//    }
//  }

}
