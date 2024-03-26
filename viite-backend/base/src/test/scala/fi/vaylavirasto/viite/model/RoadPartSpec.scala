package fi.vaylavirasto.viite.model

import fi.vaylavirasto.viite.util.ViiteException
import org.scalatest._

class RoadPartSpec extends FunSuite with Matchers {

  test("RoadPart: RoadPart construction succeeds, when given valid road number, and road part number.") {
    noException should be thrownBy (RoadPart(    1,  1))
    noException should be thrownBy (RoadPart(    1,999))
    noException should be thrownBy (RoadPart(99999,  1))
    noException should be thrownBy (RoadPart(99999,999))
  }

  test("RoadPart: RoadPart construction fails, if given invalid road number, or road part number.") {
    val okNum = 100
    intercept[Exception](RoadPart(    -1,okNum)) shouldBe a[ViiteException]
    intercept[Exception](RoadPart(100000,okNum)) shouldBe a[ViiteException]
    intercept[Exception](RoadPart( okNum,   -1)) shouldBe a[ViiteException]
    intercept[Exception](RoadPart( okNum, 1000)) shouldBe a[ViiteException]
  }

  test("RoadPart: Creating RoadPart(0,0) (unaddressed RoadPart) succeeds") {
    noException should be thrownBy RoadPart(0,0)
  }

  test("Test RoadPart.toString prints the string as 'roadNum/roadPartNum'") {
    RoadPart(123,456).toString shouldBe "123/456"
  }

  test("Test RoadPart.isAtSameRoadThan: Returns true, if the compared RoadParts have same road number. Else false") {
    RoadPart(1,1).isAtSameRoadAs(RoadPart(1,1)) shouldBe true
    RoadPart(1,1).isAtSameRoadAs(RoadPart(1,2)) shouldBe true

    // different road
    RoadPart(1,1).isAtSameRoadAs(RoadPart(789,1)) shouldBe false
    // unaddressed road or part
    RoadPart(0,0).isAtSameRoadAs(RoadPart(0,0)) shouldBe false
    RoadPart(0,0).isAtSameRoadAs(RoadPart(1,1)) shouldBe false
    RoadPart(0,1).isAtSameRoadAs(RoadPart(1,1)) shouldBe false
    RoadPart(1,0).isAtSameRoadAs(RoadPart(1,1)) shouldBe false
    RoadPart(1,1).isAtSameRoadAs(RoadPart(0,0)) shouldBe false
    RoadPart(1,1).isAtSameRoadAs(RoadPart(0,1)) shouldBe false
    RoadPart(1,1).isAtSameRoadAs(RoadPart(1,0)) shouldBe false
  }

  test("Test RoadPart.isBefore: Returns true, if the compared RoadParts have same road number, and this RoadPart has smaller part number than <i>other</i>, and both RoadParts are valid. Else false.") {
    RoadPart(1,2).isBefore(RoadPart(1,3)) shouldBe true

    // same road number, but not before
    RoadPart(1,2).isBefore(RoadPart(1,2)) shouldBe false
    RoadPart(1,2).isBefore(RoadPart(1,1)) shouldBe false
    // different road number
    RoadPart(1,2).isBefore(RoadPart(2,2)) shouldBe false
    RoadPart(2,2).isBefore(RoadPart(1,2)) shouldBe false
    // unaddressed road or part
    RoadPart(0,1).isBefore(RoadPart(1,1)) shouldBe false
    RoadPart(1,0).isBefore(RoadPart(1,1)) shouldBe false
    RoadPart(1,1).isBefore(RoadPart(0,1)) shouldBe false
    RoadPart(1,1).isBefore(RoadPart(1,0)) shouldBe false
  }

  test("Test RoadPart.isAfter: Returns true, if the compared RoadParts have same road number, and this RoadPart has bigger  part number than <i>other</i>, and both RoadParts are valid. Else false.") {
    RoadPart(1,2).isAfter(RoadPart(1,1)) shouldBe true

    // same road number, but not after
    RoadPart(1,2).isAfter(RoadPart(1,2)) shouldBe false
    RoadPart(1,2).isAfter(RoadPart(1,3)) shouldBe false
    // different road number
    RoadPart(1,2).isAfter(RoadPart(2,2)) shouldBe false
    RoadPart(2,2).isAfter(RoadPart(1,2)) shouldBe false
    // unaddressed road or part
    RoadPart(0,1).isAfter(RoadPart(1,1)) shouldBe false
    RoadPart(1,0).isAfter(RoadPart(1,1)) shouldBe false
    RoadPart(1,1).isAfter(RoadPart(0,1)) shouldBe false
    RoadPart(1,1).isAfter(RoadPart(1,0)) shouldBe false
  }

  test("Test RoadPart.isUnaddressed: Returns true, if this RoadPart has at most zero road number, or road part number. Else false.") {
    RoadPart(0,1).isUnaddressed shouldBe true
    RoadPart(1,0).isUnaddressed shouldBe true
    RoadPart(0,0).isUnaddressed shouldBe true

    // valid road and part
    RoadPart(    1,  1).isUnaddressed shouldBe false
    RoadPart(99999,999).isUnaddressed shouldBe false
  }

  test("Test RoadPart.isValid:       Returns true, if this RoadPart has zero as both road number, and road part number. Else false.") {
    RoadPart(    1,  1).isValid shouldBe true
    RoadPart(99999,999).isValid shouldBe true

    // invalid road or part
    RoadPart(0,1).isValid shouldBe false
    RoadPart(1,0).isValid shouldBe false
    RoadPart(0,0).isValid shouldBe false
  }

  test("Test RoadPart.isInvalid:     Returns true, if this RoadPart has at most zero road number, or road part number. Else false.") {
    RoadPart(0,1).isInvalid shouldBe true
    RoadPart(1,0).isInvalid shouldBe true
    RoadPart(0,0).isInvalid shouldBe true

    // valid road and part
    RoadPart(    1,  1).isInvalid shouldBe false
    RoadPart(99999,999).isInvalid shouldBe false
  }
}
