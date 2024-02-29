package fi.liikennevirasto.viite.model
import fi.vaylavirasto.viite.model.RoadPart
import org.scalatest._

class RoadPartSpec extends FunSuite with Matchers {

  test("Test truncateGeometry3D When using a empty geometry Then return a empty geometry") {
    val exception = intercept[IllegalArgumentException](RoadPart(-1,-1))
    exception shouldBe a[IllegalArgumentException]
  }

  test("RoadPart: RoadPart construction fails, if given invalid road number, or road part numer.") {
    val okNum = 100
    intercept[IllegalArgumentException](RoadPart(    -1,okNum)) shouldBe a[IllegalArgumentException]
    intercept[IllegalArgumentException](RoadPart(100000,okNum)) shouldBe a[IllegalArgumentException]
    intercept[IllegalArgumentException](RoadPart( okNum,   -1)) shouldBe a[IllegalArgumentException]
    intercept[IllegalArgumentException](RoadPart( okNum, 1000)) shouldBe a[IllegalArgumentException]
  }

  test("RoadPart: Creating RoadPart(0,0) (unaddressed RoadPart) succeeds") {
    noException should be thrownBy RoadPart(1,1)
  }

  test("Test RoadPart.toString prints the string as 'roadNum/roadPartNum'") {
    RoadPart(123,456).toString shouldBe "123/456"
  }

  test("Test RoadPart.isAtSameRoadThan: Returns true, if the compared RoadParts have same road number. Else false") {
    RoadPart(1,1).isAtSameRoadThan(RoadPart(1,1)) shouldBe true
    RoadPart(1,1).isAtSameRoadThan(RoadPart(1,2)) shouldBe true

    // different road
    RoadPart(1,1).isAtSameRoadThan(RoadPart(789,1)) shouldBe false
    // unaddressed road or part
    RoadPart(0,0).isAtSameRoadThan(RoadPart(0,0)) shouldBe false
    RoadPart(0,0).isAtSameRoadThan(RoadPart(1,1)) shouldBe false
    RoadPart(0,1).isAtSameRoadThan(RoadPart(1,1)) shouldBe false
    RoadPart(1,0).isAtSameRoadThan(RoadPart(1,1)) shouldBe false
    RoadPart(1,1).isAtSameRoadThan(RoadPart(0,0)) shouldBe false
    RoadPart(1,1).isAtSameRoadThan(RoadPart(0,1)) shouldBe false
    RoadPart(1,1).isAtSameRoadThan(RoadPart(1,0)) shouldBe false
  }

  test("Test RoadPart.isBefore: Returns true, if the compared RoadParts have same road number, and this RoadPArt has smaller part number than <i>other</i>, and both RoadParts are valid. Else false.") {
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

  test("Test RoadPart.isAfter: Returns true, if the compared RoadParts have same road number, and this RoadPArt has bigger  part number than <i>other</i>, and both RoadParts are valid. Else false.") {
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

  test("Test RoadPart.isUnaddressed: Returns true, if this RoadPart has both road number, and road part number zeroes. Else false.") {
    RoadPart(0,1).isUnaddressed shouldBe true
    RoadPart(1,0).isUnaddressed shouldBe true
    RoadPart(0,0).isUnaddressed shouldBe true

    // valid road and part
    RoadPart(    1,  1).isUnaddressed shouldBe false
    RoadPart(99999,999).isUnaddressed shouldBe false
  }

  test("Test RoadPart.isValid Returns true, if this RoadPart has both road number, and road part number greater than zeroes. Else false.") {
    RoadPart(    1,  1).isValid shouldBe true
    RoadPart(99999,999).isValid shouldBe true

    // invalid road or part
    RoadPart(0,1).isValid shouldBe false
    RoadPart(1,0).isValid shouldBe false
    RoadPart(0,0).isValid shouldBe false
  }

  test("Test RoadPart.isInvalid Returns true, if this RoadPart has either road number, or road part number at most zero. Else false.") {
    RoadPart(0,1).isInvalid shouldBe true
    RoadPart(1,0).isInvalid shouldBe true
    RoadPart(0,0).isInvalid shouldBe true

    // valid road and part
    RoadPart(    1,  1).isInvalid shouldBe false
    RoadPart(99999,999).isInvalid shouldBe false
  }
}
