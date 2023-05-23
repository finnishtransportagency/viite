package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.CalibrationPointType
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class CalibrationPointDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  test("Test compare calibration point types When compared Then comparison works correctly") {
    val a = CalibrationPointType.UserDefinedCP
    val b = CalibrationPointType.JunctionPointCP
    val c = CalibrationPointType.RoadAddressCP
    a < b should be(true)
    b < c should be(true)
    b > a should be(true)
    c > b should be(true)
    a <= b should be(true)
    b <= c should be(true)
    b >= a should be(true)
    c >= b should be(true)
    a <= a should be(true)
    b <= b should be(true)
    c <= c should be(true)
    a >= a should be(true)
    b >= b should be(true)
    c >= c should be(true)
    a == a should be(true)
    b == b should be(true)
    c == c should be(true)
  }

}
