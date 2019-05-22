package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.NewIdValue
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class JunctionDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  val dao = new JunctionDAO

  val testJunction1 = Junction(NewIdValue, -1, None, DateTime.parse("2019-01-01"), None,
    DateTime.parse("2019-01-01"), None, None, None)

  val testJunction2 = Junction(NewIdValue, -1, None, DateTime.parse("2019-01-02"), None,
    DateTime.parse("2019-01-02"), None, None, None)

  test("Test create When nothing to create Then return empty Seq") {
    runWithRollback {
      val ids = dao.create(Seq())
      ids.isEmpty should be(true)
    }
  }

  test("Test create When one created Then return Seq with one id") {
    runWithRollback {
      val ids = dao.create(Seq(testJunction1))
      ids.size should be(1)
    }
  }

  test("Test create When two created Then return Seq with two ids") {
    runWithRollback {
      val ids = dao.create(Seq(testJunction1, testJunction2))
      ids.size should be(2)
    }
  }

}
