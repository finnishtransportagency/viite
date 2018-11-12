package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.user.{Configuration, User}
import fi.liikennevirasto.viite.dao.{RoadName, RoadNameDAO}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{StaticQuery => Q}

class RoadNameServiceSpec extends FunSuite with Matchers {
  private val roadNameService = new RoadNameService
  val formatter = DateTimeFormat.forPattern("dd.MM.yyyy")

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  test("simple roadname search by roadnumber") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.RRRR'),to_date('01.01.1996','DD.MM.RRRR'),to_date('17.01.2006','DD.MM.RRRR'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), None, None, None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(x) => println("should not get here")
      }
    }
  }

  test("simple roadname search by roadnumber and name") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.YYYY'),to_date('01.01.1996','DD.MM.YYYY'),to_date('17.01.2006','DD.MM.YYYY'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(x) => println("should not get here")
      }
    }
  }

  test("simple roadname search by name") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.RRRR'),to_date('01.01.1996','DD.MM.RRRR'),to_date('17.01.2006','DD.MM.RRRR'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(x) => println("should not get here")
      }
    }
  }

  test("roadname search by roadnumber,name and date") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.RRRR'),to_date('01.01.1996','DD.MM.RRRR'),to_date('17.01.2006','DD.MM.RRRR'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), Some(DateTime.parse("1988-01-01")), None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(x) => println("should not get here")
      }
    }
  }

  test("roadname search by roadnumber,name and date should be none") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.RRRR'),to_date('01.01.1996','DD.MM.RRRR'),to_date('17.01.2006','DD.MM.RRRR'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), Some(DateTime.parse("1999-01-01")), None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(0)
        case Left(x) => println("should not get here")
      }
    }
  }

  test("roadname search by roadnumber,name and end date should be none") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.RRRR'),to_date('01.01.1996','DD.MM.RRRR'),to_date('17.01.2006','DD.MM.RRRR'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, Some(DateTime.parse("1979-01-01")))
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(0)
        case Left(x) => println("should not get here")
      }
    }
  }

  test("roadname search by roadnumber,name and end date") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.RRRR'),to_date('01.01.1996','DD.MM.RRRR'),to_date('17.01.2006','DD.MM.RRRR'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, Some(DateTime.parse("1999-01-01")))
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(x) => println("should not get here")
      }
    }
  }

  test("Fetch updated road names, no updates") {
    runWithRollback {
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), None)
      result.isRight should be(true)
      result.right.get.size should be(0)
    }
  }

  test("Fetch updated road names, one new without history") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.RRRR'),null,to_date('01.01.3001','DD.MM.RRRR'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), None)
      result.isRight should be(true)
      result.right.get.size should be(1)
    }
  }

  test("Fetch updated road names, one update with history") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.RRRR'),null,to_date('01.01.3001','DD.MM.RRRR'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OLD NAME',to_date('02.02.2901','DD.MM.RRRR'),to_date('01.01.3001','DD.MM.RRRR'),to_date('01.01.2901','DD.MM.RRRR'),null,'TR',to_timestamp('01.01.2901 12:00:00','DD.MM.RRRR HH24:MI:SS'))""".execute
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), None)
      result.isRight should be(true)
      result.right.get.size should be(2)
    }
  }

  test("new roadname, setting end date in current one") {
    val roadNumber = 99
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            values ($roadNumber, 'VICTORY RD.', to_date('01.01.1989','DD.MM.RRRR'), null, to_date('01.01.1989','DD.MM.RRRR'), null, 'User', to_timestamp('01.01.1989 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = RoadNameDAO.getLatestRoadName(roadNumber)

      val roadNames = Seq(
        RoadNameRow(search.get.id, "VICTORY RD.", "25.3.2018", Some("27.3.2018")),
        RoadNameRow(NewRoadNameId, "Victory Road", "27.3.2018", None)
      )
      val afterInsert = roadNameService.addOrUpdateRoadNamesInTX(roadNumber, roadNames, User(1, "user", Configuration()))
      afterInsert should be(None)
      val latestAfterInsert = RoadNameDAO.getLatestRoadName(roadNumber)
      latestAfterInsert.size should be(1)
      latestAfterInsert.get.roadName should be("Victory Road")
    }
  }

  test("updating name from current one should expire and create an copy of it, with the new name") {
    val roadNumber = 99
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            values ($roadNumber, 'VICTORY RD.', to_date('01.01.1989','DD.MM.RRRR'), null, to_date('01.01.1989','DD.MM.RRRR'), null, 'User', to_timestamp('01.01.1989 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = RoadNameDAO.getLatestRoadName(roadNumber)
      val roadNames = Seq(
        RoadNameRow(search.get.id, "Victory Road", "27.3.2018", None)
      )
      roadNameService.addOrUpdateRoadNamesInTX(roadNumber, roadNames, User(1, "user", Configuration()))
      val latestAfterInsert = RoadNameDAO.getLatestRoadName(roadNumber)
      latestAfterInsert.size should be(1)
      latestAfterInsert.get.roadName should be("Victory Road")
    }
  }

  test("Fetch updated road names, no updates between dates") {
    runWithRollback {
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), Some(DateTime.parse("3001-01-01")))
      result.isRight should be(true)
      result.right.get.size should be(0)
    }
  }

  test("Fetch updated road names, one new without history between dates") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.RRRR'),null,to_date('01.01.3001','DD.MM.RRRR'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), Some(DateTime.parse("3001-01-02")))
      result.isRight should be(true)
      result.right.get.size should be(1)
    }
  }

  test(" Try to Fetch updated road names, one new without history but it will be filtered out because of the until parameter") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.RRRR'),null,to_date('01.01.3001','DD.MM.RRRR'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), Some(DateTime.parse("3001-01-01")))
      result.isRight should be(true)
      result.right.get.size should be(0)
    }
  }

  test("Fetch updated road names, one update with history between given dates") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.RRRR'),null,to_date('01.01.3001','DD.MM.RRRR'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OLD NAME',to_date('02.02.2901','DD.MM.RRRR'),to_date('01.01.3001','DD.MM.RRRR'),to_date('01.01.2901','DD.MM.RRRR'),null,'TR',to_timestamp('01.01.2901 12:00:00','DD.MM.RRRR HH24:MI:SS'))""".execute
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), Some(DateTime.parse("3001-01-02")))
      result.isRight should be(true)
      result.right.get.size should be(2)
    }
  }

  test("Test RoadNameService.getRoadNameByNumber() When no name exists for a specific road number Then it should return a formatted response instead of None") {
    runWithRollback {
      val result = roadNameService.getRoadNameByNumber(99999, 99999)
      result.get("roadName").isDefined should be (true)
      result.get("roadName").get should be (None)
    }
  }
}
