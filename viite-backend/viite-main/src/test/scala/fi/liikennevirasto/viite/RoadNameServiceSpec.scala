package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.vaylavirasto.viite.dao.RoadNameDAO
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

class RoadNameServiceSpec extends FunSuite with Matchers {
  private val roadNameService = new RoadNameService
  val formatter = DateTimeFormat.forPattern("dd.MM.yyyy")

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  test("Test roadNameService.getRoadNamesInTX() When searching for a newly create road name by it's road number Then return the entry for said road name.") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.YYYY'),to_date('01.01.1996','DD.MM.YYYY'),to_date('17.01.2006','DD.MM.YYYY'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), None, None, None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(_) => println("should not get here")
      }
    }
  }

  test("Test roadNameService.getRoadNamesInTX() When searching for a newly create road name by it's road number and name Then return the entry for said road name.") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.YYYY'),to_date('01.01.1996','DD.MM.YYYY'),to_date('17.01.2006','DD.MM.YYYY'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(_) => println("should not get here")
      }
    }
  }

  test("Test roadNameService.getRoadNamesInTX() When searching for a newly create road name by it's road name Then return the entry for said road name.") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.YYYY'),to_date('01.01.1996','DD.MM.YYYY'),to_date('17.01.2006','DD.MM.YYYY'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(_) => println("should not get here")
      }
    }
  }

  test("Test roadNameService.getRoadNamesInTX() When searching for a newly create road name by it's road number, name and date Then return the entry for said road name.") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.YYYY'),to_date('01.01.1996','DD.MM.YYYY'),to_date('17.01.2006','DD.MM.YYYY'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), Some(DateTime.parse("1988-01-01")), None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(_) => println("should not get here")
      }
    }
  }

  test("Test roadNameService.getRoadNamesInTX() When searching for a newly create road name by it's road number, name and a wrong date Then returns none.") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.YYYY'),to_date('01.01.1996','DD.MM.YYYY'),to_date('17.01.2006','DD.MM.YYYY'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), Some(DateTime.parse("1999-01-01")), None)
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(0)
        case Left(_) => println("should not get here")
      }
    }
  }

  test("Test roadNameService.getRoadNamesInTX() When searching for a newly create road name by it's road number, name and a wrong end date Then returns none.") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.YYYY'),to_date('01.01.1996','DD.MM.YYYY'),to_date('17.01.2006','DD.MM.YYYY'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, Some(DateTime.parse("1979-01-01")))
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(0)
        case Left(_) => println("should not get here")
      }
    }
  }

  test("Test roadNameService.getRoadNamesInTX() When searching for a newly create road name by it's road number, name and  end date Then returns the entry for said road name.") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.YYYY'),to_date('01.01.1996','DD.MM.YYYY'),to_date('17.01.2006','DD.MM.YYYY'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadNamesInTX(Some("999"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, Some(DateTime.parse("1999-01-01")))
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(_) => println("should not get here")
      }
    }
  }

  test("Test roadNameService.getUpdatedRoadNamesInTX() When using a date where no updates are Then return no updates.") {
    runWithRollback {
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), None)
      result.isRight should be(true)
      result.right.get.size should be(0)
    }
  }

  test("Test roadNameService.getUpdatedRoadNamesInTX() When using a date where there is one new road, without history Then returns the updated road name.") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.YYYY'),null,to_date('01.01.3001','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), None)
      result.isRight should be(true)
      result.right.get.size should be(1)
    }
  }

  test("Test roadNameService.getUpdatedRoadNamesInTX() When using a date where there is one new road, without history Then returns both the updated road name and the history one.") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.YYYY'),null,to_date('01.01.3001','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OLD NAME',to_date('02.02.2901','DD.MM.YYYY'),to_date('01.01.3001','DD.MM.YYYY'),to_date('01.01.2901','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.2901 12:00:00','DD.MM.YYYY HH24:MI:SS'))""".execute
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), None)
      result.isRight should be(true)
      result.right.get.size should be(2)
    }
  }

  test("Test roadNameService.addOrUpdateRoadNamesInTX() and RoadNameDAO.getLatestRoadName() When creating a new road name and setting a end date on the \"current\" one Then returns only the \"current\".") {
    val roadNumber = 99
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            values ($roadNumber, 'VICTORY RD.', to_date('01.01.1989','DD.MM.YYYY'), null, to_date('01.01.1989','DD.MM.YYYY'), null, 'User', to_timestamp('01.01.1989 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val search = RoadNameDAO.getLatestRoadName(roadNumber)

      val roadNames = Seq(
        RoadNameRow(search.get.id, "VICTORY RD.", "25.3.2018", Some("27.3.2018")),
        RoadNameRow(NewIdValue, "Victory Road", "27.3.2018", None)
      )
      val afterInsert = roadNameService.addOrUpdateRoadNamesInTX(roadNumber, roadNames, "user")
      afterInsert should be(None)
      val latestAfterInsert = RoadNameDAO.getLatestRoadName(roadNumber)
      latestAfterInsert.size should be(1)
      latestAfterInsert.get.roadName should be("Victory Road")
    }
  }

  test("Test roadNameService.addOrUpdateRoadNamesInTX() and RoadNameDAO.getLatestRoadName() When updating a name from current one Then said update should expire and create an copy of it, with the new name.") {
    val roadNumber = 99
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME)
            values ($roadNumber, 'VICTORY RD.', to_date('01.01.1989','DD.MM.YYYY'), null, to_date('01.01.1989','DD.MM.YYYY'), null, 'User', to_timestamp('01.01.1989 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val search = RoadNameDAO.getLatestRoadName(roadNumber)
      val roadNames = Seq(
        RoadNameRow(search.get.id, "Victory Road", "27.3.2018", None)
      )
      roadNameService.addOrUpdateRoadNamesInTX(roadNumber, roadNames, "user")
      val latestAfterInsert = RoadNameDAO.getLatestRoadName(roadNumber)
      latestAfterInsert.size should be(1)
      latestAfterInsert.get.roadName should be("Victory Road")
    }
  }

  test("Test roadNameService.getUpdatedRoadNamesInTX() When searching in a interval of dates but in said interval there are no names Then return no road names.") {
    runWithRollback {
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), Some(DateTime.parse("3001-01-01")))
      result.isRight should be(true)
      result.right.get.size should be(0)
    }
  }

  test("Test roadNameService.getUpdatedRoadNamesInTX() When searching in a interval of dates but in said interval there is only one name Then return that road names.") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.YYYY'),null,to_date('01.01.3001','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), Some(DateTime.parse("3001-01-02")))
      result.isRight should be(true)
      result.right.get.size should be(1)
    }
  }

  test("Test roadNameService.getUpdatedRoadNamesInTX() When searching in a interval of dates but the existing road name is outside of the interval Then return no road names.") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.YYYY'),null,to_date('01.01.3001','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), Some(DateTime.parse("3001-01-01")))
      result.isRight should be(true)
      result.right.get.size should be(0)
    }
  }

  test("Fetch updated road names, one update with history between given dates") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','ROAD ONE',to_date('02.02.3001','DD.MM.YYYY'),null,to_date('01.01.3001','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.3001 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      sqlu"""Insert into ROAD_NAME (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('999','OLD NAME',to_date('02.02.2901','DD.MM.YYYY'),to_date('31.12.3000','DD.MM.YYYY'),to_date('01.01.2901','DD.MM.YYYY'),null,'TR',to_timestamp('01.01.2901 12:00:00','DD.MM.YYYY HH24:MI:SS'))""".execute
      val result = roadNameService.getUpdatedRoadNamesInTX(DateTime.parse("3001-01-01"), Some(DateTime.parse("3001-01-02")))
      result.isRight should be(true)
      result.right.get.size should be(2)
    }
  }

  test("Test RoadNameService.getRoadNameByNumber() When no name exists for a specific road number Then it should return a formatted response instead of None") {
    runWithRollback {
      val result = roadNameService.getRoadNameByNumber(99999, 99999)
      result.contains("roadName") should be (true)
      result("roadName") should be (None)
    }
  }
}
