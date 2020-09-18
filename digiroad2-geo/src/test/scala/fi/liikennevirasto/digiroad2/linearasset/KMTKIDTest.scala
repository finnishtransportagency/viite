package fi.liikennevirasto.digiroad2.linearasset

import org.scalatest.{FunSuite, _}

class KMTKIDTest extends FunSuite with Matchers {

  test("Test KMTKID equals When using KMTKID as a key in map Then return expected values") {
    val map = Map(KMTKID("a", 1) -> 1, KMTKID("b", 2) -> 2, KMTKID("c", 3) -> 3)
    map(KMTKID("a", 1)) should be(1)
    map(KMTKID("b", 2)) should be(2)
    map(KMTKID("c", 3)) should be(3)
  }


  test("Test KMTKID equals When getting value from map with nonexisting KMTKID Then return None") {
    val map = Map(KMTKID("a", 1) -> 1)
    an [NoSuchElementException] should be thrownBy map(KMTKID("c", 1))
    an [NoSuchElementException] should be thrownBy map(KMTKID("a", 2))
    an [NoSuchElementException] should be thrownBy map(KMTKID("c", 3))
  }

}
