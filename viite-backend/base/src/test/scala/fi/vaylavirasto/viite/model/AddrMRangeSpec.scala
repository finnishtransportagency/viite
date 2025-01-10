package fi.vaylavirasto.viite.model

import fi.vaylavirasto.viite.util.ViiteException
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class AddrMRangeSpec extends AnyFunSuite with Matchers {

  // TODO Test ignored, until start < end requirement can be put into work
  ignore("AddrMRange: AddrMRange construction fails, if given invalid start, end, or if end is not greater than start.") {
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

  test("Test AddrMRange.toString prints the string as 'start-end'") {
    AddrMRange(100,200).toString shouldBe "100-200"
  }

  // --------------------------------------------- Validity checks ---------------------------------------------

  test("Test AddrMRange.isValid: Returns true, if this AddrMRange has endM greater than zero. Else false.") {
    AddrMRange(0,       1).isValid shouldBe true
    AddrMRange(2,100*1000).isValid shouldBe true

    // invalid range
    AddrMRange(0,0).isValid shouldBe false
    intercept[Exception](AddrMRange(-1,0).isValid) shouldBe a[ViiteException]
  }

  test("Test AddrMRange.isInvalid Returns true, if this AddrMRange has (both start, and) end (at most) zero. Else false.") {
    AddrMRange(  0,  0).isInvalid shouldBe true
    intercept[Exception](AddrMRange(-1,0).isInvalid) shouldBe a[ViiteException]

    // valid range
    AddrMRange(0,1).isInvalid shouldBe false
    AddrMRange(1,2).isInvalid shouldBe false
  }

  // TODO Test ignored, until start < end requirement can be put into work
  ignore("Test AddrMRange.isUndefined works as AddrMRange.isInvalid. Returns true, if this AddrMRange has (both startAddrM, and) end (at most) zero. Else false.") {
    AddrMRange(  0,  0).isUndefined shouldBe true
    intercept[Exception](AddrMRange( -1,0).isUndefined) shouldBe a[ViiteException]
    intercept[Exception](AddrMRange(100,0).isUndefined) shouldBe a[ViiteException]

    // valid range
    AddrMRange(0,1).isUndefined shouldBe false
    AddrMRange(1,2).isUndefined shouldBe false
  }


  // -------------------------- Functions returning numeric values, or AddrMRange copies --------------------------

  test("Test AddrMRange.length:  Returns the length      of this AddrMRange, or throws ViiteException, if this AddrMRange isUndefined (or erroneous).") {
    AddrMRange(  0,100).length shouldBe 100
    AddrMRange(100,200).length shouldBe 100

    // cannot calculate lengths for invalid ranges
    intercept[ViiteException](AddrMRange(  0,0).length) shouldBe a[ViiteException]
  // TODO sub test commented out, until start < end requirement of the AddrMRange object can be put into work
  //  intercept[ViiteException](AddrMRange(100,0).length) shouldBe a[ViiteException] // creation should fail already.
  }

  test("Test AddrMRange.lengthOption:  Returns the length of this AddrMRange, or None, if this AddrMRange is invalid (or erroneous).") {
    AddrMRange(  0,100).lengthOption shouldBe Some(100)
    AddrMRange(100,200).lengthOption shouldBe Some(100)

    // cannot calculate lengths for invalid ranges
    AddrMRange(  0,  0).lengthOption shouldBe None
  // TODO sub test commented out, until start < end requirement of the AddrMRange object can be put into work
  //  intercept[ViiteException](AddrMRange(100,0).lengthOption) shouldBe a[ViiteException] // creation should fail already.
  }
}

