package fi.liikennevirasto.digiroad2

import org.scalatest._

class PointSpec extends FunSuite with Matchers {

  val tolerance = 0.00001

  test("Angle should be π / 2 (90 degrees) xy") {
    val vector1 = Vector3d(1, 0, 0)
    val vector2 = Vector3d(0, -1, 0)
    vector1.angle(vector2) should be(Math.toRadians(90) +- tolerance)
    vector2.angle(vector1) should be(Math.toRadians(90) +- tolerance)
  }

  test("Angle should be π / 2 (90 degrees) xz") {
    val vector1 = Vector3d(1, 0, 0)
    val vector2 = Vector3d(0, 0, -1)
    vector1.angle(vector2) should be(Math.toRadians(90) +- tolerance)
    vector2.angle(vector1) should be(Math.toRadians(90) +- tolerance)
  }

  test("Angle should be π / 2 (90 degrees) yz") {
    val vector1 = Vector3d(0, 1, 0)
    val vector2 = Vector3d(0, 0, -1)
    vector1.angle(vector2) should be(Math.toRadians(90) +- tolerance)
    vector2.angle(vector1) should be(Math.toRadians(90) +- tolerance)
  }

  test("Angle should be π (180 degrees) x") {
    val vector1 = Vector3d(1, 0, 0)
    val vector2 = Vector3d(-1, 0, 0)
    vector1.angle(vector2) should be(Math.toRadians(180) +- tolerance)
    vector2.angle(vector1) should be(Math.toRadians(180) +- tolerance)
  }

  test("Angle should be π (180 degrees) y") {
    val vector1 = Vector3d(0, 1, 0)
    val vector2 = Vector3d(0, -1, 0)
    vector1.angle(vector2) should be(Math.toRadians(180) +- tolerance)
    vector2.angle(vector1) should be(Math.toRadians(180) +- tolerance)
  }

  test("Angle should be π (180 degrees) z") {
    val vector1 = Vector3d(0, 0, 1)
    val vector2 = Vector3d(0, 0, -1)
    vector1.angle(vector2) should be(Math.toRadians(180) +- tolerance)
    vector2.angle(vector1) should be(Math.toRadians(180) +- tolerance)
  }

}
