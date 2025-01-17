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
  ignore("Test AddrMRange.isUndefined works as AddrMRange.isInvalid. Returns true, if this AddrMRange has (both start, and) end (at most) zero. Else false.") {
    AddrMRange(  0,  0).isUndefined shouldBe true
    intercept[Exception](AddrMRange( -1,0).isUndefined) shouldBe a[ViiteException]
    intercept[Exception](AddrMRange(100,0).isUndefined) shouldBe a[ViiteException]

    // valid range
    AddrMRange(0,1).isUndefined shouldBe false
    AddrMRange(1,2).isUndefined shouldBe false
  }


  // ------------------------------------------ isRoadPartStart checks ------------------------------------------
  test("Test AddrMRange.isRoadPartStart: Returns true, if this AddrMRange has zero start, and end greater than zero. Else false.") {
    // A start of the road has start address == 0, and end address > 0.
    AddrMRange(0,1).isRoadPartStart shouldBe true

    //Not a start of a road part; does not have a zero start
    AddrMRange(1,2).isRoadPartStart shouldBe false

    // Invalid range
    AddrMRange(0,0).isRoadPartStart shouldBe false

    // End address being 0 does not make this a road part start. This is an invalid address range.
// TODO sub test commented out, until start < end requirement of the AddrMRange object can be put into work
//    intercept[Exception](AddrMRange(1,0).isRoadPartStart) shouldBe a[ViiteException]
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

  test("Test AddrMRange.move:    Returns a moved    copy of this AddrMRange, or throws ViiteException, if this AddrMRange isUndefined, or startAddress would get negative when moved.") {
    AddrMRange(100,200).move(   0) shouldBe AddrMRange(100,200)
    AddrMRange(100,200).move(   1) shouldBe AddrMRange(101,201)
    AddrMRange(100,200).move(  -1) shouldBe AddrMRange( 99,199)
    AddrMRange(100,200).move( 100) shouldBe AddrMRange(200,300)
    AddrMRange(100,200).move(-100) shouldBe AddrMRange(  0,100)

    // cannot move range so that it would have a negative start address
    intercept[ViiteException](AddrMRange(  0,100).move(  -1)) shouldBe a[ViiteException]
    intercept[ViiteException](AddrMRange(100,200).move(-101)) shouldBe a[ViiteException]
    intercept[ViiteException](AddrMRange(  0,100).move(-101)) shouldBe a[ViiteException]

    // cannot move too much (mostly to catch overflows etc.)
    intercept[ViiteException](AddrMRange(  10,  1).move(7654321)) shouldBe a[ViiteException] // try to move the address way more than length of the country -> no go.

    // cannot move invalid ranges
    intercept[ViiteException](AddrMRange(  0,  0).move(  1)) shouldBe a[ViiteException]
// TODO sub test commented out, until start < end requirement of the AddrMRange object can be put into work
//    intercept[ViiteException](AddrMRange(100,  0).move(  1)) shouldBe a[ViiteException] // creation should fail already.
// TODO instead we have the following:
    intercept[ViiteException](AddrMRange(100,  0).move(-101)) shouldBe a[ViiteException] // TODO disable, when until start < end requirement of the AddrMRange object can be put into work

    // "it is a copy NOT the same object" tests
    val origAddr = AddrMRange(100,200)

    val movedAddr1 = origAddr.move(1)
    movedAddr1.start shouldBe origAddr.start+1
    movedAddr1.end   shouldBe origAddr.end+1

    var movedAddr2 = origAddr.move(0) // move 0 is allowed, no changes in the addresses, but the result is still a different object
    origAddr.start shouldBe movedAddr2.start
    origAddr.end   shouldBe movedAddr2.end
    movedAddr1.start should not be movedAddr2.start
    movedAddr1.end   should not be movedAddr2.end
  }


  test("Test AddrMRange.mirrorBy:    Returns a mirrored copy of this AddrMRange, or throws ViiteException, if this AddrMRange isUndefined, roadpartEndAddrM is non-positive, or startAddress would get negative when moved.") {
    AddrMRange(  0,100).mirrorBy(300) shouldBe AddrMRange(200,300)
    AddrMRange(200,300).mirrorBy(300) shouldBe AddrMRange(  0,100)
    AddrMRange(100,200).mirrorBy(300) shouldBe AddrMRange(100,200)
    AddrMRange(  0,300).mirrorBy(300) shouldBe AddrMRange(  0,300)

    // cannot reverse move over a non-positive roadpartEndAddrM
    intercept[ViiteException](AddrMRange(  0,100).mirrorBy(  -1)) shouldBe a[ViiteException]
    intercept[ViiteException](AddrMRange(  0,100).mirrorBy(   0)) shouldBe a[ViiteException]

    // cannot reverse move range so that it would have a negative start address
    intercept[ViiteException](AddrMRange(  0,100).mirrorBy( 99)) shouldBe a[ViiteException]
    intercept[ViiteException](AddrMRange(100,200).mirrorBy(199)) shouldBe a[ViiteException]

    // cannot reverse move invalid ranges
    intercept[ViiteException](AddrMRange(  0,  0).mirrorBy(  1)) shouldBe a[ViiteException]
// TODO sub test commented out, until start < end requirement of the AddrMRange object can be put into work
//    intercept[ViiteException](AddrMRange(100,  0).mirrorBy(100)) shouldBe a[ViiteException] // creation should fail already.
// TODO instead we have the following:
    intercept[ViiteException](AddrMRange(100,  0).mirrorBy( 99)) shouldBe a[ViiteException] // TODO disable, when until start < end requirement of the AddrMRange object can be put into work
  }
}

