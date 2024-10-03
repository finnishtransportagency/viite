package fi.liikennevirasto.viite

import fi.vaylavirasto.viite.dao.RoadNameScalikeDAO
import fi.vaylavirasto.viite.postgis.DbUtilsScalike.runUpdateToDbTestsScalike
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollbackScalike
import org.joda.time.DateTime
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalikejdbc._


class RoadNameServiceSpec extends AnyFunSuite with Matchers {
  private val roadNameService = new RoadNameService

  val commonTestData = sql"""
  INSERT INTO ROAD_NAME
  (ROAD_NUMBER, ROAD_NAME, START_DATE, END_DATE, VALID_FROM, VALID_TO, CREATED_BY, CREATED_TIME)
  VALUES (
    999,
    'OTAVA-HIRVENSALMI-LEVÄLAHTI',
    ${new DateTime(1989, 1, 1, 0, 0)},
    ${new DateTime(1996, 1, 1, 0, 0)},
    ${new DateTime(2006, 1, 17, 0, 0)},
    null,
    'TR',
    ${new DateTime(2018, 3, 14, 14, 14, 44)}
  )
"""

  test("getRoadNames should return a road name when searching by road number") {
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(commonTestData)
      val search = roadNameService.getRoadNamesInTX(Some("999"), None, None, None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(_) => fail("should not get here")
      }
    }
  }



  test("Test roadNameService.getRoadNamesInTX() When searching for a newly create road name by it's road number and name Then return the entry for said road name.") {
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(commonTestData)
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(_) => fail("should not get here")
      }
    }
  }

  test("Test roadNameService.getRoadNamesInTX() When searching for a newly create road name by it's road name Then return the entry for said road name.") {
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(commonTestData)
      val search = roadNameService.getRoadNamesInTX(None, Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(_) => fail("should not get here")
      }
    }
  }

  test("Test roadNameService.getRoadNamesInTX() When searching for a newly create road name by it's road number, name and date Then return the entry for said road name.") {
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(commonTestData)
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), Some(DateTime.parse("1988-01-01")), None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(_) => fail("should not get here")
      }
    }
  }

  test("Test roadNameService.getRoadNamesInTX() When searching for a newly create road name by it's road number, name and a wrong date Then returns none.") {
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(commonTestData)
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), Some(DateTime.parse("1999-01-01")), None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(0)
        case Left(_) => fail("should not get here")
      }
    }
  }

  test("Test roadNameService.getRoadNamesInTX() When searching for a newly create road name by it's road number, name and a wrong end date Then returns none.") {
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(commonTestData)
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, Some(DateTime.parse("1979-01-01")))
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(0)
        case Left(_) => fail("should not get here")
      }
    }
  }

  test("Test roadNameService.getRoadNamesInTX() When searching for a newly create road name by it's road number, name and  end date Then returns the entry for said road name.") {
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(commonTestData)
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, Some(DateTime.parse("1999-01-01")))
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(_) => fail("should not get here")
      }
    }
  }

  test("Test roadNameService.getUpdatedRoadNames() When using a date where no updates are Then return no updates.") {
    runWithRollbackScalike {
      val result = roadNameService.getUpdatedRoadNames(DateTime.parse("3001-01-01"), None)
      result.isRight should be(true)
      result.right.get.size should be(0)
    }
  }

  test("Test roadNameService.getUpdatedRoadNamesInTX() When using a date where there is one new road, without history Then returns the updated road name.") {
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(sql"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.YYYY'),null,to_date('01.01.3001','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.YYYY HH24:MI:SS'))""")
      val result = roadNameService.getUpdatedRoadNames(DateTime.parse("3001-01-01"), None)
      result.isRight should be(true)
      result.right.get.size should be(1)
    }
  }

  test("Test roadNameService.getUpdatedRoadNamesInTX() When using a date where there is one new road, without history Then returns both the updated road name and the history one.") {
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(sql"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.YYYY'),null,to_date('01.01.3001','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDbTestsScalike(sql"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OLD NAME',to_date('02.02.2901','DD.MM.YYYY'),to_date('01.01.3001','DD.MM.YYYY'),to_date('01.01.2901','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.2901 12:00:00','DD.MM.YYYY HH24:MI:SS'))""")
      val result = roadNameService.getUpdatedRoadNames(DateTime.parse("3001-01-01"), None)
      result.isRight should be(true)
      result.right.get.size should be(2)
    }
  }

  test("Test roadNameService.addOrUpdateRoadNamesInTX() and RoadNameDAO.getLatestRoadName() When creating a new road name and setting a end date on the \"current\" one Then returns only the \"current\".") {
    val roadNumber = 99
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(sql"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            values ($roadNumber, 'VICTORY RD.', to_date('01.01.1989','DD.MM.YYYY'), null, to_date('01.01.1989','DD.MM.YYYY'), null, 'User', to_timestamp('01.01.1989 14:14:44','DD.MM.YYYY HH24:MI:SS'))""")
      val search = RoadNameScalikeDAO.getLatestRoadName(roadNumber)

      val roadNames = Seq(
        RoadNameRow(search.get.id, "VICTORY RD.", "25.3.2018", Some("27.3.2018")),
        RoadNameRow(NewIdValue, "Victory Road", "27.3.2018", None)
      )
      val afterInsert = roadNameService.addOrUpdateRoadNamesInTX(roadNumber, roadNames, "user")
      afterInsert should be(None)
      val latestAfterInsert = RoadNameScalikeDAO.getLatestRoadName(roadNumber)
      latestAfterInsert.size should be(1)
      latestAfterInsert.get.roadName should be("Victory Road")
    }
  }

  test("Test roadNameService.addOrUpdateRoadNamesInTX() and RoadNameDAO.getLatestRoadName() When updating a name from current one Then said update should expire and create an copy of it, with the new name.") {
    val roadNumber = 99
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(sql"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            values ($roadNumber, 'VICTORY RD.', to_date('01.01.1989','DD.MM.YYYY'), null, to_date('01.01.1989','DD.MM.YYYY'), null, 'User', to_timestamp('01.01.1989 14:14:44','DD.MM.YYYY HH24:MI:SS'))""")
      val search = RoadNameScalikeDAO.getLatestRoadName(roadNumber)
      val roadNames = Seq(
        RoadNameRow(search.get.id, "Victory Road", "27.3.2018", None)
      )
      roadNameService.addOrUpdateRoadNamesInTX(roadNumber, roadNames, "user")
      val latestAfterInsert = RoadNameScalikeDAO.getLatestRoadName(roadNumber)
      latestAfterInsert.size should be(1)
      latestAfterInsert.get.roadName should be("Victory Road")
    }
  }

  test("Test roadNameService.getUpdatedRoadNamesInTX() When searching in a interval of dates but in said interval there are no names Then return no road names.") {
    runWithRollbackScalike {
      val result = roadNameService.getUpdatedRoadNames(DateTime.parse("3001-01-01"), Some(DateTime.parse("3001-01-01")))
      result.isRight should be(true)
      result.right.get.size should be(0)
    }
  }

  test("Test roadNameService.getUpdatedRoadNamesInTX() When searching in a interval of dates but in said interval there is only one name Then return that road names.") {
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(sql"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.YYYY'),null,to_date('01.01.3001','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.YYYY HH24:MI:SS'))""")
      val result = roadNameService.getUpdatedRoadNames(DateTime.parse("3001-01-01"), Some(DateTime.parse("3001-01-02")))
      result.isRight should be(true)
      result.right.get.size should be(1)
    }
  }

  test("Test roadNameService.getUpdatedRoadNamesInTX() When searching in a interval of dates but the existing road name is outside of the interval Then return no road names.") {
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(sql"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.YYYY'),null,to_date('01.01.3001','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.YYYY HH24:MI:SS'))""")
      val result = roadNameService.getUpdatedRoadNames(DateTime.parse("3001-01-01"), Some(DateTime.parse("3001-01-01")))
      result.isRight should be(true)
      result.right.get.size should be(0)
    }
  }

  test("Fetch updated road names, one update with history between given dates") {
    runWithRollbackScalike {
      runUpdateToDbTestsScalike(sql"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.YYYY'),null,to_date('01.01.3001','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.YYYY HH24:MI:SS'))""")
      runUpdateToDbTestsScalike(sql"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OLD NAME',to_date('02.02.2901','DD.MM.YYYY'),to_date('31.12.3000','DD.MM.YYYY'),to_date('01.01.2901','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.2901 12:00:00','DD.MM.YYYY HH24:MI:SS'))""")
      val result = roadNameService.getUpdatedRoadNames(DateTime.parse("3001-01-01"), Some(DateTime.parse("3001-01-02")))
      result.isRight should be(true)
      result.right.get.size should be(2)
    }
  }

  test("Test RoadNameService.getRoadNameByNumber() When no name exists for a specific road number Then it should return a formatted response instead of None") {
    runWithRollbackScalike {
      val result = roadNameService.getRoadNameByNumber(99999, 99999)
      result.contains("roadName") should be (true)
      result("roadName") should be (None)
    }
  }
}
