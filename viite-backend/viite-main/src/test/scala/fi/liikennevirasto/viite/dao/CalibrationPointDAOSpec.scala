package fi.liikennevirasto.viite.dao

import fi.vaylavirasto.viite.model.CalibrationPointType
import org.scalatest.{FunSuite, Matchers}
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase.runWithRollback

class CalibrationPointDAOSpec extends FunSuite with Matchers {

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
