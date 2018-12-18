package fi.liikennevirasto.digiroad2

import org.scalatest._

class PointSpec extends FunSuite with Matchers {

  val tolerance = 0.00001

  // Vector3d.angle

  test("Test angle() When using two zero vectors Then return NaN") {
    val vector1 = Vector3d(0, 0, 0)
    val vector2 = Vector3d(0, 0, 0)
    vector1.angle(vector2).isNaN should be(true)
  }

  test("Test angle() When using Vector1(1,0,0) and Vector2(0,-1,0) Then return π / 2 (90 degrees) xy.") {
    val vector1 = Vector3d(1, 0, 0)
    val vector2 = Vector3d(0, -1, 0)
    vector1.angle(vector2) should be(Math.toRadians(90) +- tolerance)
    vector2.angle(vector1) should be(Math.toRadians(90) +- tolerance)
  }

  test("Test angle() When using Vector1(2,0,0) and Vector2(0,0,-2) Then return π / 2 (90 degrees) xz.") {
    val vector1 = Vector3d(2, 0, 0)
    val vector2 = Vector3d(0, 0, -2)
    vector1.angle(vector2) should be(Math.toRadians(90) +- tolerance)
    vector2.angle(vector1) should be(Math.toRadians(90) +- tolerance)
  }

  test("Test angle() When using Vector1(0,3,0) and Vector2(0,0,-3) Then return π / 2 (90 degrees) yz.") {
    val vector1 = Vector3d(0, 3, 0)
    val vector2 = Vector3d(0, 0, -3)
    vector1.angle(vector2) should be(Math.toRadians(90) +- tolerance)
    vector2.angle(vector1) should be(Math.toRadians(90) +- tolerance)
  }

  test("Test angle() When using Vector1(1,0,0) and Vector2(-1,0,0) Then return π (180 degrees) x.") {
    val vector1 = Vector3d(1, 0, 0)
    val vector2 = Vector3d(-1, 0, 0)
    vector1.angle(vector2) should be(Math.toRadians(180) +- tolerance)
    vector2.angle(vector1) should be(Math.toRadians(180) +- tolerance)
  }

  test("Test angle() When using Vector1(0,2,0) and Vector2(0,2,0) Then return π (180 degrees) y.") {
    val vector1 = Vector3d(0, 2, 0)
    val vector2 = Vector3d(0, -2, 0)
    vector1.angle(vector2) should be(Math.toRadians(180) +- tolerance)
    vector2.angle(vector1) should be(Math.toRadians(180) +- tolerance)
  }

  test("Test angle() When using Vector1(0,0,3) and Vector2(0,0,-3) Then return π (180 degrees) z.") {
    val vector1 = Vector3d(0, 0, 3)
    val vector2 = Vector3d(0, 0, -3)
    vector1.angle(vector2) should be(Math.toRadians(180) +- tolerance)
    vector2.angle(vector1) should be(Math.toRadians(180) +- tolerance)
  }

  // Vector3d.angleXYWithNegativeValues

  test("Test angleXYWithNegativeValues() When using Vector1(1,0,0) and Vector2(0,-1,0), the angle to find is Vector1 to Vector2 Then return π / 2 (90 degrees) xy.") {
    val vector1 = Vector3d(1, 0, 0)
    val vector2 = Vector3d(0, -1, 0)
    vector1.angleXYWithNegativeValues(vector2) should be(Math.toRadians(90) +- tolerance)
  }

  test("Test angleXYWithNegativeValues() When using Vector1(1,0,0) and Vector2(0,-1,0), the angle to find is Vector2 to Vector1 Then return -π / 2 (90 degrees) xy.") {
    val vector1 = Vector3d(1, 0, 0)
    val vector2 = Vector3d(0, -1, 0)
    vector2.angleXYWithNegativeValues(vector1) should be(Math.toRadians(-90) +- tolerance)
  }

  test("Test angleXYWithNegativeValues() When using Vector1(1,0,0) and Vector2(0,0,-1) Then return 0 xz.") {
    val vector1 = Vector3d(1, 0, 0)
    val vector2 = Vector3d(0, 0, -1)
    vector1.angleXYWithNegativeValues(vector2) should be(0.0 +- tolerance)
    vector2.angleXYWithNegativeValues(vector1) should be(0.0 +- tolerance)
  }

  test("Test angleXYWithNegativeValues() When using Vector1(0,1,0) and Vector2(0,0,-1) Then return 0 yz.") {
    val vector1 = Vector3d(0, 1, 0)
    val vector2 = Vector3d(0, 0, -1)
    vector1.angleXYWithNegativeValues(vector2) should be(0.0 +- tolerance)
    vector2.angleXYWithNegativeValues(vector1) should be(0.0 +- tolerance)
  }

  test("Test angleXYWithNegativeValues() When using Vector1(1,0,0) and Vector2(-1,-1,0), the angle to find is Vector1 to Vector2 Then return 135 x.") {
    val vector1 = Vector3d(1, 0, 0)
    val vector2 = Vector3d(-1, -1, 0)
    vector1.angleXYWithNegativeValues(vector2) should be(Math.toRadians(135) +- tolerance)
  }

  test("Test angleXYWithNegativeValues() When using Vector1(1,0,0) and Vector2(-1,0,0), the angle to find is Vector2 to Vector1 Then return π (180 degrees) x.") {
    val vector1 = Vector3d(1, 0, 0)
    val vector2 = Vector3d(-1, 0, 0)
    vector2.angleXYWithNegativeValues(vector1) should be(Math.toRadians(180) +- tolerance)
  }

  test("Test angleXYWithNegativeValues() When using Vector1(1,0,0) and Vector2(-1,0,0), the angle to find is Vector1 to Vector2 Then return -π (-180 degrees) x.") {
    val vector1 = Vector3d(1, 0, 0)
    val vector2 = Vector3d(-1, 0, 0)
    vector1.angleXYWithNegativeValues(vector2) should be(Math.toRadians(-180) +- tolerance)
  }

  test("Test angleXYWithNegativeValues() When using Vector1(1,0,0) and Vector2(1,-1,0), the angle to find is Vector1 to Vector2 Then return 135 degrees y.") {
    val vector1 = Vector3d(0, 1, 0)
    val vector2 = Vector3d(1, -1, 0)
    vector1.angleXYWithNegativeValues(vector2) should be(Math.toRadians(135) +- tolerance)
  }

  test("Test angleXYWithNegativeValues() When using Vector1(0,1,0) and Vector2(0,-1,0), the angle to find is Vector1 to Vector2 Then return π (180 degrees) y.") {
    val vector1 = Vector3d(0, 1, 0)
    val vector2 = Vector3d(0, -1, 0)
    vector1.angleXYWithNegativeValues(vector2) should be(Math.toRadians(180) +- tolerance)
  }

  test("Test angleXYWithNegativeValues() When using Vector1(0,1,0) and Vector2(0,-1,0), the angle to find is Vector2 to Vector1 Then return -π (-180 degrees) y.") {
    val vector1 = Vector3d(0, 1, 0)
    val vector2 = Vector3d(0, -1, 0)
    vector2.angleXYWithNegativeValues(vector1) should be(Math.toRadians(-180) +- tolerance)
  }

  test("Test angleXYWithNegativeValues() When using Vector1(0,0,1) and Vector2(0,0,-1) Then return 0 z.") {
    val vector1 = Vector3d(0, 0, 1)
    val vector2 = Vector3d(0, 0, -1)
    vector1.angleXYWithNegativeValues(vector2) should be(0.0 +- tolerance)
    vector2.angleXYWithNegativeValues(vector1) should be(0.0 +- tolerance)
  }

}
