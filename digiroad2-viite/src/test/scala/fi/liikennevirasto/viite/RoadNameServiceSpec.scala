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
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('431','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.RRRR'),to_date('01.01.1996','DD.MM.RRRR'),to_date('17.01.2006','DD.MM.RRRR'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadAddresses(Some("431"), None, None, None)
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
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('431','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.YYYY'),to_date('01.01.1996','DD.MM.YYYY'),to_date('17.01.2006','DD.MM.YYYY'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.YYYY HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadAddresses(Some("431"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, None)
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
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('431','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.RRRR'),to_date('01.01.1996','DD.MM.RRRR'),to_date('17.01.2006','DD.MM.RRRR'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadAddresses(Some("431"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, None)
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
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('431','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.RRRR'),to_date('01.01.1996','DD.MM.RRRR'),to_date('17.01.2006','DD.MM.RRRR'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadAddresses(Some("431"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), Some(DateTime.parse("1988-01-01")), None)
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
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('431','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.RRRR'),to_date('01.01.1996','DD.MM.RRRR'),to_date('17.01.2006','DD.MM.RRRR'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadAddresses(Some("431"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), Some(DateTime.parse("1999-01-01")), None)
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
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('431','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.RRRR'),to_date('01.01.1996','DD.MM.RRRR'),to_date('17.01.2006','DD.MM.RRRR'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadAddresses(Some("431"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, Some(DateTime.parse("1979-01-01")))
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
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('431','OTAVA-HIRVENSALMI-LEVÄLAHTI',to_date('01.01.1989','DD.MM.RRRR'),to_date('01.01.1996','DD.MM.RRRR'),to_date('17.01.2006','DD.MM.RRRR'),null,'TR',to_timestamp('14.03.2018 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = roadNameService.getRoadAddresses(Some("431"), Some("OTAVA-HIRVENSALMI-LEVÄLAHTI"), None, Some(DateTime.parse("1999-01-01")))
      search.isRight should be(true)
      search match {
        case Right(result) =>
          result.size should be(1)
        case Left(x) => println("should not get here")
      }
    }
  }

  test("new roadname, setting end date in current one") {
    runWithRollback {
      sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('5','VICTORY RD.',to_date('01.01.1989','DD.MM.RRRR'), null, to_date('01.01.1989','DD.MM.RRRR'),null,'User',to_timestamp('01.01.1989 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
      val search = RoadNameDAO.getCurrentRoadName(5)

      val roadNames = Seq(
        RoadNameRows(search.get.id,List(RoadNameEditions("endDate","27.3.2018"))),
        RoadNameRows(-1000,List(RoadNameEditions("roadNumber","5"), RoadNameEditions("orignalRoadId",search.get.id.toString), RoadNameEditions("roadName","Victory Road"), RoadNameEditions("startDate","27.3.2018")))
      )
     val afterInsert = roadNameService.addOrUpdateRoadNames(roadNames, User(1, "user", Configuration()))
      afterInsert should be (None)
      val currentAferInsert = RoadNameDAO.getCurrentRoadName(5)
      currentAferInsert.size should be (1)
      currentAferInsert.get.roadName should be ("Victory Road")
      }
    }

  test("updating name from current one should expire and create an copy of it, with the new name") {
    runWithRollback {
      runWithRollback {
        sqlu"""Insert into ROAD_NAMES (ROAD_NUMBER,ROAD_NAME,START_DATE,END_DATE,VALID_FROM,VALID_TO,CREATED_BY,CREATED_TIME) values ('5','VICTORY RD.',to_date('01.01.1989','DD.MM.RRRR'), null, to_date('01.01.1989','DD.MM.RRRR'),null,'User',to_timestamp('01.01.1989 14:14:44','DD.MM.RRRR HH24:MI:SS'))""".execute
        val search = RoadNameDAO.getCurrentRoadName(5)
        val roadNames = Seq(
          RoadNameRows(search.get.id,List(RoadNameEditions("roadName","Victory Road")))
        )
        val afterInsert = roadNameService.addOrUpdateRoadNames(roadNames, User(1, "user", Configuration()))
        val currentAferInsert = RoadNameDAO.getCurrentRoadName(5)
        currentAferInsert.size should be (1)
        currentAferInsert.get.roadName should be ("Victory Road")
      }
    }
  }

}
