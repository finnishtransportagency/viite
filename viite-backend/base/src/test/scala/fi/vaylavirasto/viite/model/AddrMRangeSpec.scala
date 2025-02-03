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


  // ----------------------------------- Connectivity checks -----------------------------------

  test("Test AddrMRange.isSameAs(AddrMRange): Returns true, if the compared AddrMRanges have same start, and end. Else false") {
    //                                                                           100    300 // comparison range
    //AddrMRange "A"                      AddrMRange "B"                  //      |AAAAAA|      //
    AddrMRange(100,300).isSameAs(AddrMRange(100,300)) shouldBe true       //      |BBBBBB|      // Are the same

    // different either value
    AddrMRange(100,300).isSameAs(AddrMRange(  0, 99)) shouldBe false      // BBBB |      |      // Do not even touch
    AddrMRange(100,300).isSameAs(AddrMRange(301,400)) shouldBe false      //      |      | BBBB // Do not even touch
    AddrMRange(100,300).isSameAs(AddrMRange(  0,100)) shouldBe false      // BBBBB|      |      // Only single point in common
    AddrMRange(100,300).isSameAs(AddrMRange(300,400)) shouldBe false      //      |      |BBBBB // Only single point in common
    AddrMRange(100,300).isSameAs(AddrMRange(101,300)) shouldBe false      //      | BBBBB|      // Nearly the same but not exactly
    AddrMRange(100,300).isSameAs(AddrMRange(100,299)) shouldBe false      //      |BBBBB |      // Nearly the same but not exactly

    // undefined range
    intercept[Exception](AddrMRange(  0,100).isSameAs(AddrMRange(  0,  0))) shouldBe a[ViiteException]
    intercept[Exception](AddrMRange(  0,  0).isSameAs(AddrMRange(  0,100))) shouldBe a[ViiteException]
  }

  test("Test AddrMRange.contains: Returns true, if other AddrMRange is fully contained inside <i>this</i>. Else false.") {
    //                                                                           100    300 // comparison range
    //AddrMRange "A"                      AddrMRange "B"                  //      |AAAAAA|      //
    AddrMRange(100,300).contains(AddrMRange(100,300)) shouldBe true       //      |BBBBBB|      // Exactly same. B contained by A. (And vice versa.)

    AddrMRange(100,300).contains(AddrMRange(100,299)) shouldBe true       //      |BBBBB |      // B contained within A
    AddrMRange(100,300).contains(AddrMRange(101,299)) shouldBe true       //      | BBBB |      // B contained within A
    AddrMRange(100,300).contains(AddrMRange(101,300)) shouldBe true       //      | BBBBB|      // B contained within A

    AddrMRange(100,300).contains(AddrMRange(  0,100)) shouldBe false      // BBBBB|      |      // Only single point in common
    AddrMRange(100,300).contains(AddrMRange(300,400)) shouldBe false      //      |      |BBBBB // Only single point in common
    AddrMRange(100,300).contains(AddrMRange(  0,200)) shouldBe false      // BBBBB|BBB   |      // Partial overlap, but not contained
    AddrMRange(100,300).contains(AddrMRange(200,400)) shouldBe false      //      |   BBB|BBBBB // Partial overlap, but not contained
    AddrMRange(100,300).contains(AddrMRange(  0,400)) shouldBe false      // BBBBB|BBBBBB|BBBBB // Overlaps, but not contained
    AddrMRange(100,300).contains(AddrMRange(  0, 99)) shouldBe false      // BBBB |      |      // Do not even touch
    AddrMRange(100,300).contains(AddrMRange(301,400)) shouldBe false      //      |      | BBBB // Do not even touch

    // Undefined AddrMRange
    intercept[Exception](AddrMRange(100,300).contains(AddrMRange(  0,  0))) shouldBe a[ViiteException]
    intercept[Exception](AddrMRange(  0,  0).contains(AddrMRange(100,300))) shouldBe a[ViiteException]
  }

  test("Test AddrMRange.overlaps: Returns true, if this AddrMRange has more than single point in common with <i>other</i>. Else false.") {
                                                                          //     100    300 // comparison range
    //AddrMRange "A"                      AddrMRange "B"                  //      |AAAAAA|
    AddrMRange(100,300).overlaps(AddrMRange(100,300)) shouldBe true       //      |BBBBBB|      // Sameness is also overlapping
    AddrMRange(100,300).overlaps(AddrMRange(100,200)) shouldBe true       //      |BBB   |      // Containment is also overlapping
    AddrMRange(100,300).overlaps(AddrMRange(101,299)) shouldBe true       //      | BBBB |      // Containment is also overlapping
    AddrMRange(100,300).overlaps(AddrMRange(200,300)) shouldBe true       //      |   BBB|      // Containment is also overlapping
    AddrMRange(100,300).overlaps(AddrMRange(  0,101)) shouldBe true       // BBBBB|B     |      // Overlapping over one side
    AddrMRange(100,300).overlaps(AddrMRange(299,400)) shouldBe true       //      |     B|BBBBB // Overlapping over one side
    AddrMRange(100,300).overlaps(AddrMRange(  0,400)) shouldBe true       // BBBBB|BBBBBB|BBBBB // Containment is also overlapping

    AddrMRange(100,300).overlaps(AddrMRange(  0, 99)) shouldBe false      // BBBB |      |      // Do not even touch
    AddrMRange(100,300).overlaps(AddrMRange(301,400)) shouldBe false      //      |      | BBBB // Do not even touch
    AddrMRange(100,300).overlaps(AddrMRange(  0,100)) shouldBe false      // BBBBB|      |      // Only single point in common
    AddrMRange(100,300).overlaps(AddrMRange(300,400)) shouldBe false      //      |      |BBBBB // Only single point in common

    // Undefined AddrMRange
    intercept[Exception](AddrMRange(100,300).overlaps(AddrMRange(  0,  0))) shouldBe a[ViiteException]
    intercept[Exception](AddrMRange(  0,  0).overlaps(AddrMRange(100,300))) shouldBe a[ViiteException]
  }

  test("Test AddrMRange.continuesTo: Returns true, if this.end == other.start, and both are valid ranges. Else false.") {
                                                                             //     100   300
    //  AddrMRange "A"                          AddrMRange "B"               //      |AAAAA|
    AddrMRange(100,300).continuesTo(AddrMRange(  0,100)) shouldBe false      // BBBBB|     |
    AddrMRange(100,300).continuesTo(AddrMRange(  0, 99)) shouldBe false      // BBBB |     |
    AddrMRange(100,300).continuesTo(AddrMRange(300,400)) shouldBe true       //      |     *BBBBB   // A continues to start of B
    AddrMRange(100,300).continuesTo(AddrMRange(301,400)) shouldBe false      //      |     | BBBB

    AddrMRange(100,300).continuesTo(AddrMRange(  0,300)) shouldBe false      // BBBBB|BBBBB|
    AddrMRange(100,300).continuesTo(AddrMRange(  0,400)) shouldBe false      // BBBBB|BBBBB|BBBBB
    AddrMRange(100,300).continuesTo(AddrMRange(100,400)) shouldBe false      //      |BBBBB|BBBBB
    AddrMRange(100,300).continuesTo(AddrMRange(100,300)) shouldBe false      //      |BBBBB|

    //Invalid AddrMRange                                                     //    0    300
    AddrMRange(  0,300).continuesTo(AddrMRange(  0,  0)) shouldBe false      // (B)|AAAAA|
    AddrMRange(  0,  0).continuesTo(AddrMRange(  0,300)) shouldBe false      // (A)|BBBBB|
//    intercept[Exception](AddrMRange(  0,300).continuesTo(AddrMRange(300,300))) shouldBe a[ViiteException]      //    |AAAAA|(B) // TODO add require((  (start!=end) ...) clause to constructor
//    intercept[Exception](AddrMRange(300,300).continuesTo(AddrMRange(  0,300))) shouldBe a[ViiteException]      //    |BBBBB|(A) // TODO add require((  (start!=end) ...) clause to constructor
  }

  test("Test AddrMRange.continuesFrom: Returns true, if this.end == other.start, and both are valid ranges. Else false.") {
                                                                               //     100   300
    //  AddrMRange "A"                          AddrMRange "B"                 //      |AAAAA|
    AddrMRange(100,300).continuesFrom(AddrMRange(  0,100)) shouldBe true       // BBBBB*     |  // A continues from end of B
    AddrMRange(100,300).continuesFrom(AddrMRange(  0, 99)) shouldBe false      // BBBB |     |
    AddrMRange(100,300).continuesFrom(AddrMRange(300,400)) shouldBe false      //      |     |BBBBB
    AddrMRange(100,300).continuesFrom(AddrMRange(301,400)) shouldBe false      //      |     | BBBB

    AddrMRange(100,300).continuesFrom(AddrMRange(  0,300)) shouldBe false      // BBBBB|BBBBB|
    AddrMRange(100,300).continuesFrom(AddrMRange(  0,400)) shouldBe false      // BBBBB|BBBBB|BBBBB
    AddrMRange(100,300).continuesFrom(AddrMRange(100,400)) shouldBe false      //      |BBBBB|BBBBB
    AddrMRange(100,300).continuesFrom(AddrMRange(100,300)) shouldBe false      //      |BBBBB|

    //Invalid AddrMRange
    AddrMRange(  0,300).continuesFrom(AddrMRange(  0,  0)) shouldBe false      // (B)|AAAAA|
    AddrMRange(  0,  0).continuesFrom(AddrMRange(  0,300)) shouldBe false      // (A)|BBBBB|
//    intercept[Exception](AddrMRange(  0,300).continuesFrom(AddrMRange(300,300))) shouldBe a[ViiteException]      //    |AAAAA|(B) // TODO add require((  (start!=end) ...) clause to constructor
//    intercept[Exception](AddrMRange(300,300).continuesFrom(AddrMRange(  0,300))) shouldBe a[ViiteException]      //    |BBBBB|(A) // TODO add require((  (start!=end) ...) clause to constructor
  }

  test("Test AddrMRange.isAdjacentTo: Returns true, if this.end == other.start, or this.end == other.start, and both are valid ranges. Else false.") {
                                                                              //     100   300
                                                                              //      |AAAAA|
    AddrMRange(100,300).isAdjacentTo(AddrMRange(  0,100)) shouldBe true       // BBBBB*     |      // is adjacent
    AddrMRange(100,300).isAdjacentTo(AddrMRange(  0, 99)) shouldBe false      // BBBB |     |      // not connected
    AddrMRange(100,300).isAdjacentTo(AddrMRange(300,400)) shouldBe true       //      |     *BBBBB // is adjacent
    AddrMRange(100,300).isAdjacentTo(AddrMRange(301,400)) shouldBe false      //      |     | BBBB // not connected

    AddrMRange(100,300).isAdjacentTo(AddrMRange(  0,300)) shouldBe false      // BBBBB+BBBBB|      // overlaps, not adjacent
    AddrMRange(100,300).isAdjacentTo(AddrMRange(  0,400)) shouldBe false      // BBBBB+BBBBB+BBBBB // overlaps, not adjacent
    AddrMRange(100,300).isAdjacentTo(AddrMRange(100,400)) shouldBe false      //      |BBBBB+BBBBB // overlaps, not adjacent
    AddrMRange(100,300).isAdjacentTo(AddrMRange(100,300)) shouldBe false      //      |BBBBB|      // identical, not adjacent

    //Invalid AddrMRange
    AddrMRange(  0,300).isAdjacentTo(AddrMRange(  0,  0)) shouldBe false      // (B)|AAAAA|
    AddrMRange(  0,  0).isAdjacentTo(AddrMRange(  0,300)) shouldBe false      // (A)|BBBBB|
//    AddrMRange(  0,300).isAdjacentTo(AddrMRange(300,300)) shouldBe false      //    |AAAAA|(B) // TODO add require((  (start!=end) ...) clause to constructor
//    AddrMRange(300,300).isAdjacentTo(AddrMRange(  0,300)) shouldBe false      //    |BBBBB|(A) // TODO add require((  (start!=end) ...) clause to constructor
  }


  test("Test AddrMRange.startsAt:         Returns true, if this AddrMRange.start equals addrM. Else false.") {
                                                          //    |-----| : this AddrMRange, X : addrM value
    AddrMRange(100,200).startsAt(100) shouldBe true       //    X-----|

    // AddrMRange starts elsewhere but not at the addrM value
    AddrMRange(100,200).startsAt( 99) shouldBe false      //   X|-----|
    AddrMRange(100,200).startsAt(101) shouldBe false      //    |X----|
    AddrMRange(100,200).startsAt(199) shouldBe false      //    |----X|
    AddrMRange(100,200).startsAt(200) shouldBe false      //    |-----X
    AddrMRange(100,200).startsAt(201) shouldBe false      //    |-----|X

    //Invalid AddrMRange
    AddrMRange(  0,  0).startsAt(  0) shouldBe false
    AddrMRange(  0,  0).startsAt(100) shouldBe false
  }

  test("Test AddrMRange.endsAt:           Returns true, if this AddrMRange.end   equals addrM. Else false.") {
                                                        //    |-----| : this AddrMRange, X : addrM value
    AddrMRange(100,200).endsAt(200) shouldBe true       //    |-----X

    // AddrMRange ends elsewhere but not at the addrM value
    AddrMRange(100,200).endsAt( 99) shouldBe false      //   X|-----|
    AddrMRange(100,200).endsAt(100) shouldBe false      //    X-----|
    AddrMRange(100,200).endsAt(101) shouldBe false      //    |X----|
    AddrMRange(100,200).endsAt(199) shouldBe false      //    |----X|
    AddrMRange(100,200).endsAt(201) shouldBe false      //    |-----|X

    //Invalid AddrMRange
    AddrMRange(  0,  0).endsAt(  0) shouldBe false
    AddrMRange(  0,  0).endsAt(100) shouldBe false
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


  test("Test AddrMRange.flipRelativeTo:    Returns a flipped copy of this AddrMRange, or throws ViiteException, if this AddrMRange isUndefined, flipLength is non-positive, or start would get negative when moved.") {
    AddrMRange(  0,100).flipRelativeTo(300) shouldBe AddrMRange(200,300)
    AddrMRange(200,300).flipRelativeTo(300) shouldBe AddrMRange(  0,100)
    AddrMRange(100,200).flipRelativeTo(300) shouldBe AddrMRange(100,200)
    AddrMRange(  0,300).flipRelativeTo(300) shouldBe AddrMRange(  0,300)

    // cannot reverse move over a non-positive roadpartEndAddrM
    intercept[ViiteException](AddrMRange(  0,100).flipRelativeTo( -1)) shouldBe a[ViiteException]
    intercept[ViiteException](AddrMRange(  0,100).flipRelativeTo(  0)) shouldBe a[ViiteException]

    // cannot reverse move range so that it would have a negative start address
    intercept[ViiteException](AddrMRange(  0,100).flipRelativeTo( 99)) shouldBe a[ViiteException]
    intercept[ViiteException](AddrMRange(100,200).flipRelativeTo(199)) shouldBe a[ViiteException]

    // cannot flip invalid ranges
    intercept[ViiteException](AddrMRange(  0,  0).flipRelativeTo(  1)) shouldBe a[ViiteException]
// TODO sub test commented out, until start < end requirement of the AddrMRange object can be put into work
//    intercept[ViiteException](AddrMRange(100,  0).flipRelativeTo(100)) shouldBe a[ViiteException] // creation should fail already.
// TODO instead we have the following:
    intercept[ViiteException](AddrMRange(100,  0).flipRelativeTo( 99)) shouldBe a[ViiteException] // TODO disable, when until start < end requirement of the AddrMRange object can be put into work
  }
}

