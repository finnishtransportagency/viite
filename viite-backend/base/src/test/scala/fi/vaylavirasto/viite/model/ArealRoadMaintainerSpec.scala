package fi.vaylavirasto.viite.model

import fi.vaylavirasto.viite.util.ViiteException
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ArealRoadMaintainerSpec extends AnyFunSuite with Matchers {

  test("Test ArealRoadMaintainer: Cannot create a random ArealRoadMaintainer. Or even one with a proper name. Creation throws an exception.") {
    intercept[Exception](ArealRoadMaintainer("asdf"))    shouldBe a[ViiteException]
    intercept[Exception](ArealRoadMaintainer("Uusimaa")) shouldBe a[ViiteException]
    intercept[Exception](ArealRoadMaintainer("1"))       shouldBe a[ViiteException]
    //intercept[Exception](ArealRoadMaintainer("1"))     shouldBe a[ViiteException] // This should not even compile
  }

}
