package fi.vaylavirasto.viite.model

import fi.vaylavirasto.viite.util.ViiteException
import org.scalatest._

class AddrMRangeSpec extends FunSuite with Matchers {

  test("AddrMRange: AddrMRange construction fails, if given invalid startAddrM, endAddrM, or if end is not greater than start.") {
    val tooSmall = -1
    val tooBig   = 100*1000+1
    intercept[Exception](AddrMRange(tooSmall,    200)) shouldBe a[ViiteException]
    intercept[Exception](AddrMRange(tooBig,      200)) shouldBe a[ViiteException]
    intercept[Exception](AddrMRange(   100, tooSmall)) shouldBe a[ViiteException]
    intercept[Exception](AddrMRange(   100,   tooBig)) shouldBe a[ViiteException]

    // end cannot be bigger than start
    intercept[Exception](AddrMRange(   200,      100)) shouldBe a[ViiteException]
    intercept[Exception](AddrMRange(   200,      199)) shouldBe a[ViiteException]
  }

  test("AddrMRange: Creating AddrMRange(0,0) (undefined AddrMRange) succeeds") {
    noException should be thrownBy AddrMRange(0,0)
  }

  test("Test AddrMRange.toString prints the string as 'startAddrM-endAddrM'") {
    AddrMRange(100,200).toString shouldBe "100-200"
  }

  // --------------------------------------------- Validity checks ---------------------------------------------

  test("Test AddrMRange.isValid: Returns true, if this AddrMRange has endAddrM greater than zero. Else false.") {
    AddrMRange(0,       1).isValid shouldBe true
    AddrMRange(2,100*1000).isValid shouldBe true

    // invalid range
    AddrMRange(0,0).isValid shouldBe false
    intercept[Exception](AddrMRange(-1,0).isValid) shouldBe a[ViiteException]
  }

  test("Test AddrMRange.isInvalid Returns true, if this AddrMRange has (both startAddrM, and) endAddrM (at most) zero. Else false.") {
    AddrMRange(  0,  0).isInvalid shouldBe true
    intercept[Exception](AddrMRange(-1,0).isInvalid) shouldBe a[ViiteException]

    // valid range
    AddrMRange(0,1).isInvalid shouldBe false
    AddrMRange(1,2).isInvalid shouldBe false
  }

  test("Test AddrMRange.isUndefined works as AddrMRange.isInvalid. Returns true, if this AddrMRange has (both startAddrM, and) endAddrM (at most) zero. Else false.") {
    AddrMRange(  0,  0).isUndefined shouldBe true
    intercept[Exception](AddrMRange( -1,0).isUndefined) shouldBe a[ViiteException]
    intercept[Exception](AddrMRange(100,0).isUndefined) shouldBe a[ViiteException]

    // valid range
    AddrMRange(0,1).isUndefined shouldBe false
    AddrMRange(1,2).isUndefined shouldBe false
  }

}

