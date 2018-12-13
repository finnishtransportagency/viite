package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.GeometryUtils._
import org.joda.time.DateTime
import org.scalatest._

class GeometryUtilsSpec extends FunSuite with Matchers {
  test("Test truncateGeometry3D When using a empty geometry Then return a empty geometry") {
    val truncated = truncateGeometry3D(Nil, 10, 15)
    truncated should be(Nil)
  }

  test("Test truncateGeometry3D When start measure is after end measure Then return IllegalArgumentException") {
    an[IllegalArgumentException] should be thrownBy truncateGeometry3D(Nil, 15, 10)
  }

  test("Test truncateGeometry3D When geometry has only 1 point Then return IllegalArgumentException") {
    an[IllegalArgumentException] should be thrownBy truncateGeometry3D(Seq(Point(0.0, 0.0)), 10, 15)
  }

  test("Test truncateGeometry3D When the truncation is at the start Then returns the truncated geometry") {
    val truncatedGeometry = truncateGeometry3D(Seq(Point(0.0, 0.0), Point(5.0, 0.0), Point(10.0, 0.0)), 6, 10)
    truncatedGeometry should be(Seq(Point(6.0, 0.0), Point(10.0, 0.0)))
  }

  test("Test truncateGeometry3D When the truncation is at the end Then returns the truncated geometry") {
    val truncatedGeometry = truncateGeometry3D(Seq(Point(0.0, 0.0), Point(5.0, 0.0), Point(10.0, 0.0)), 0, 6)
    truncatedGeometry should be(Seq(Point(0.0, 0.0), Point(5.0, 0.0), Point(6.0, 0.0)))
  }

  test("Test truncateGeometry3D When the truncation is both at the start and at the end Then returns the truncated geometry") {
    val truncatedGeometry = truncateGeometry3D(Seq(Point(0.0, 0.0), Point(5.0, 0.0), Point(10.0, 0.0)), 2, 6)
    truncatedGeometry should be(Seq(Point(2.0, 0.0), Point(5.0, 0.0), Point(6.0, 0.0)))
  }

  test("Test truncateGeometry3D When the truncation limits are on the same segment Then returns the truncated geometry") {
    val truncatedGeometry = truncateGeometry3D(Seq(Point(0.0, 0.0), Point(5.0, 0.0), Point(10.0, 0.0)), 2, 3)
    truncatedGeometry should be(Seq(Point(2.0, 0.0), Point(3.0, 0.0)))
  }

  test("Test truncateGeometry3D When the truncation limits are outside of the geometry Then returns a empty geometry") {
    val truncatedGeometry = truncateGeometry3D(Seq(Point(0.0, 0.0), Point(5.0, 0.0), Point(10.0, 0.0)), 11.0, 15.0)
    truncatedGeometry should be(empty)
  }

  test("Test createSplit When the measure is outside the link segment Then return IllegalArgumentException") {
    val link1 = (0.0, 100.0)
    intercept[IllegalArgumentException] {
      createSplit(105.0, link1)
    }
  }

  test("Test createSplit When the measure is withing the link segment Then return 2 new measures where one ends and another begins on the given measure") {
    val link1 = (0.0, 100.0)
    val (existingLinkMeasures, createdLinkMeasures) = createSplit(40.0, link1)

    existingLinkMeasures shouldBe(40.0, 100.0)
    createdLinkMeasures shouldBe(0.0, 40.0)
  }

  test("Test subtractIntervalFromIntervals When we try to subtract a contained interval from intervals Then return the subtraction result.") {
    val result = subtractIntervalFromIntervals(Seq((3.0, 6.0)), (4.0, 5.0))
    result shouldBe Seq((3.0, 4.0), (5.0, 6.0))
  }

  test("Test subtractIntervalFromIntervals When we try to subtract a outlying interval from intervals Then return the subtraction result.") {
    val result = subtractIntervalFromIntervals(Seq((3.0, 6.0)), (1.0, 2.0))
    result shouldBe Seq((3.0, 6.0))
  }

  test("Test subtractIntervalFromIntervals When we try to subtract a interval that lies in the beginning of the rest of the intervals Then return the subtraction result.") {
    val result = subtractIntervalFromIntervals(Seq((3.0, 6.0)), (2.0, 4.0))
    result shouldBe Seq((4.0, 6.0))
  }

  test("Test subtractIntervalFromIntervals When we try to subtract a interval that lies in the ending of the rest of the intervals Then return the subtraction result.") {
    val result = subtractIntervalFromIntervals(Seq((3.0, 6.0)), (5.0, 7.0))
    result shouldBe Seq((3.0, 5.0))
  }

  test("Test subtractIntervalFromIntervals When we try to subtract a interval that engulfs the rest of the intervals Then return a empty sequence.") {
    val result = subtractIntervalFromIntervals(Seq((3.0, 6.0)), (2.0, 7.0))
    result shouldBe Seq()
  }

  test("Test calculatePointFromLinearReference When using a 0 to 1 X geometry and a 0.5 in measure Then return the point.") {
    val linkGeometry = List(Point(0.0, 0.0), Point(1.0, 0.0))
    val point: Point = calculatePointFromLinearReference(linkGeometry, 0.5).get
    point.x should be(0.5)
    point.y should be(0.0)
  }

  test("Test calculatePointFromLinearReference When using on three-point geometry and a 1.5 in measure Then return the point.") {
    val linkGeometry = List(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0))
    val point: Point = calculatePointFromLinearReference(linkGeometry, 1.5).get
    point.x should be(1.0)
    point.y should be(0.5)
  }

  test("Test calculatePointFromLinearReference When using a undefined geometry and a 1.5 in measure Then the returned point should be None") {
    val linkGeometry = Nil
    val point: Option[Point] = calculatePointFromLinearReference(linkGeometry, 1.5)
    point should be(None)
  }

  test("Test calculatePointFromLinearReference When using on three-point geometry and a -1.5 in measure Then the returned point should be None") {
    val linkGeometry = List(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0))
    val point: Option[Point] = calculatePointFromLinearReference(linkGeometry, -1.5)
    point should be(None)
  }

  test("Test calculatePointFromLinearReference When using a 0 to 1 X geometry and a 1.5 in measure, meaning it's outside the geometry Then the returned point should be None") {
    val linkGeometry = List(Point(0.0, 0.0), Point(1.0, 0.0))
    val point: Option[Point] = calculatePointFromLinearReference(linkGeometry, 1.5)
    point should be(None)
  }

  test("Test geometryLength When measuring a 0 to 1 X geometry Then return 1.0 as the length.") {
    val geometry = List(Point(0.0, 0.0), Point(1.0, 0.0))
    val length: Double = geometryLength(geometry)
    length should be(1.0)
  }

  test("Test geometryLength When using a three point geometry (0 to 1 X geometry, last point also increases 1 in Y) Then return 2.0 as the length.") {
    val geometry = List(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0))
    val length: Double = geometryLength(geometry)
    length should be(2.0)
  }

  test("Test geometryLength When using an empty geometry Then return 0.0 as length.") {
    val length: Double = geometryLength(Nil)
    length should be(0.0)
  }

  test("Test geometryLength When using an one-point geometry Then return 0.0 as length.") {
    val length: Double = geometryLength(List(Point(0.0, 0.0)))
    length should be(0.0)
  }

  test("Test minimumDistance When measuring the distance from segment ((-1.0, 0.0), (1.0, 1.0)) to point (0.0, 0.0) Then return the calculated Minimum distance to line segment.") {
    val segment = Seq(Point(-1.0, 0.0), Point(1.0, 1.0))
    val distance = minimumDistance(Point(0.0, 0.0), segment)
    distance should be(.4472135954999579)
  }

  test("Test minimumDistance When measuring the distance from segment ((-1.0, 0.0), (1.0, 1.0)) to point (-2.0, 0.0), close to the segment start Then return the calculated Minimum distance to line segment.") {
    val segment = Seq(Point(-1.0, 0.0), Point(1.0, 1.0))
    val distance = minimumDistance(Point(-2.0, 0.0), segment)
    distance should be(1)
  }

  test("Test minimumDistance When measuring the distance from segment ((-1.0, 0.0), (1.0, 1.0)) to point (1.5, 0.0), close to the segment end Then return the calculated Minimum distance to line segment.") {
    val segment = Seq(Point(-1.0, 0.0), Point(1.0, 1.0))
    val distance = minimumDistance(Point(1.0, 1.5), segment)
    distance should be(.5)
  }

  test("Test minimumDistance When measuring the distance from segment ((-1.0, 0.0), (1.0, 1.0), (2.0, 1.0)) to point (1.5, 1.1)Then return the calculated Minimum distance to line segment.") {
    val segment = Seq(Point(-1.0, 0.0), Point(1.0, 1.0), Point(2.0, 1.0))
    val distance = minimumDistance(Point(1.5, 1.1), segment)
    distance should be >= .0999
    distance should be <= .1001
  }

  test("Test minimumDistance When measuring the distance from point (0.0, 0.0, 0.0) to segment ((-1.0, 1.0, 0.0), (1.0, 1.0, 0.0))Then return the calculated Minimum distance to line segment.") {
    val distance = minimumDistance(Point(0, 0, 0), (Point(-1, 1, 0), Point(1, 1, 0)))
    distance should be(1.0)
  }

  test("Test minimumDistance When measuring the distance from point (0.0, 0.0, 0.0) to segment ((-1.0, -1.0, 0.0), (-0.5, -0.5, 0.0))Then return the calculated Minimum distance to line segment.") {
    val distance = minimumDistance(Point(0, 0, 0), (Point(-1, -1, 0), Point(-.5, -.5, 0)))
    distance should be > .707
    distance should be < .70711
  }

  test("Test minimumDistance When measuring the distance from point (0, 0, 0) to the result of segmentByMinimumDistance of point (0,0,0) to (-1, -1, 0), (0, 0.9, 0), (1, 1, 0)) Then return the calculated Minimum distance to line segment." +
    "Get minimum distance from point to segment midpoint") {
    val distance = minimumDistance(Point(0, 0, 0),
      segmentByMinimumDistance(Point(0, 0, 0), Seq(Point(-1, 1, 0), Point(0, .9, 0), Point(1, 1, 0))))
    distance should be(0.9)
  }

  test("Test overlaps WHen using a multitude of different pints Then return if they overlap or not.") {
    overlaps((0.0, 0.1), (0.1, 0.2)) should be(false)
    overlaps((0.0, 0.15), (0.1, 0.2)) should be(true)
    overlaps((0.11, 0.15), (0.1, 0.2)) should be(true)
    overlaps((0.15, 0.11), (0.1, 0.2)) should be(true)
    overlaps((0.15, 0.21), (0.2, 0.1)) should be(true)
    overlaps((0.21, 0.01), (0.1, 0.2)) should be(true)
    overlaps((0.21, 0.01), (0.1, 0.2)) should be(true)
    overlaps((0.21, 0.22), (0.1, 0.2)) should be(false)
    overlaps((0.22, 0.21), (0.1, 0.2)) should be(false)
  }

  test("Test withinTolerance WHen using a multitude of combinations of points an tolerance values Then return if they are withing the tolerance value or not.") {
    val p1 = Point(0, 0)
    val p2 = Point(1, 1)
    val p3 = Point(1.5, 1.5)
    val p4 = Point(1.01, .99)
    withinTolerance(Seq(p1, p2), Seq(p1, p2), 0.0001) should be(true)
    withinTolerance(Seq(p1, p2), Seq(p1, p3), 0.0001) should be(false)
    withinTolerance(Seq(p1, p2), Seq(p2, p1), 0.0001) should be(false)
    withinTolerance(Seq(p1, p2), Seq(p1, p3), 1.0001) should be(true)
    withinTolerance(Seq(p1, p2), Seq(p1, p4), .0001) should be(false)
    withinTolerance(Seq(p1, p2), Seq(p1, p4), .015) should be(true)
    withinTolerance(Seq(p1), Seq(p1, p4), .0001) should be(false)
    withinTolerance(Seq(), Seq(p1, p4), .0001) should be(false)
    withinTolerance(Seq(p1), Seq(p1), .0001) should be(true)
    withinTolerance(Seq(p1), Seq(), .0001) should be(false)
    withinTolerance(Seq(), Seq(), .0001) should be(true)
  }

  test("Test truncateGeometry3D When using a 3 dimensional geometry Then return the truncated geometry") {
    val truncatedGeometry = truncateGeometry3D(Seq(Point(0.0, 0.0, 0.0), Point(5.0, 0.0, 5.0), Point(10.0, 0.0, 2.0)), 6, 10)
    truncatedGeometry.map(_.copy(z = 0.0)) should be(Seq(Point(6.0, 0.0), Point(10.0, 0.0)))
  }

  test("Test truncateGeometry3D When using a 2 dimensional geometry Then return the truncated geometry") {
    val truncatedGeometry = truncateGeometry3D(Seq(Point(0.0, 5.0), Point(0.0, 0.0), Point(5.0, 0.0)), 6, 10)
    truncatedGeometry should be(Seq(Point(1.0, 0.0), Point(5.0, 0.0)))
  }

  test("Test geometryMoved When comparing geometries that are more than 1 meter apart at the geometry end Then return true.") {
    val geometry1 = List(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0))
    val geometry2 = List(Point(0.0, 0.0), Point(1.0, 0.0), Point(5.0, 5.0))
    geometryMoved(1.0)(geometry1, geometry2) should be(true)
  }

  test("Test geometryMoved When comparing geometries that are more than 1 meter apart at the start Then return true.") {
    val geometry1 = List(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0))
    val geometry2 = List(Point(5.0, 1.0), Point(1.0, 0.0), Point(1.0, 1.0))
    geometryMoved(1.0)(geometry1, geometry2) should be(true)
  }

  test("Test geometryMoved When comparing geometries that are less than 1 meter apart Then return false.") {
    val geometry1 = List(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0))
    val geometry2 = List(Point(0.5, 0.0), Point(1.0, 0.0), Point(1.0, 1.0))
    geometryMoved(1.0)(geometry1, geometry2) should be(false)
  }

  test("Test geometryMoved When comparing geometries that are the same Then return false.") {
    val geometry1 = List(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0))
    val geometry2 = List(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0))
    geometryMoved(1.0)(geometry1, geometry2) should be(false)
  }

  test("Test geometryMoved When comparing 1 geometry to it's reversed state Then return true.") {
    val geometry1 = List(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0))
    val geometry2 = List(Point(1.0, 1.0), Point(1.0, 0.0), Point(0.0, 0.0))
    geometryMoved(1.0)(geometry1, geometry2) should be(true)
  }


  test("Test createStepGeometry When using a geometry with two points with 50 distance Then return unchanged geometry.") {
    val geometry = Seq(
      Point(0.0, 0.0, 0.0),
      Point(50.0, 50.0, 0.0)
    )
    val stepGeom = GeometryUtils.createStepGeometry(geometry, Seq.empty[Point], 0.0, GeometryUtils.geometryLength(geometry), 100)
    GeometryUtils.geometryEndpoints(stepGeom) should be(GeometryUtils.geometryEndpoints(geometry))
    stepGeom.length == geometry.length should be(true)
  }

  test("Test createStepGeometry When using a geometry with two points with 100 distance Then return unchanged geometry.") {
    val geometry = Seq(
      Point(0.0, 0.0, 0.0),
      Point(100.0, 100.0, 0.0)
    )
    val stepGeom = GeometryUtils.createStepGeometry(geometry, Seq.empty[Point], 0.0, GeometryUtils.geometryLength(geometry), 100)
    GeometryUtils.geometryEndpoints(stepGeom) should be(GeometryUtils.geometryEndpoints(geometry))
    stepGeom.length == geometry.length should be(true)
  }

  test("Test createStepGeometry When using a geometry with two points with 200 distance Then return unchanged geometry.") {
    val geometry = Seq(
      Point(0.0, 0.0, 0.0),
      Point(200.0, 200.0, 0.0)
    )
    val stepGeom = GeometryUtils.createStepGeometry(geometry, Seq.empty[Point], 0.0, GeometryUtils.geometryLength(geometry), 100)
    GeometryUtils.geometryEndpoints(stepGeom) should be(GeometryUtils.geometryEndpoints(geometry))
    stepGeom.length == geometry.length should be(true)
  }

  test("Test createStepGeometry When using a geometry with points having longer gaps than the given step Then return unchanged geometry.") {
    val geometry = Seq(
      Point(0.0, 0.0, 0.0),
      Point(200.0, 200.0, 0.0),
      Point(400.0, 400.0, 0.0),
      Point(600.0, 600.0, 0.0),
      Point(800.0, 800.0, 0.0),
      Point(1000.0, 1000.0, 0.0)
    )
    val stepGeom = GeometryUtils.createStepGeometry(geometry, Seq.empty[Point], 0.0, GeometryUtils.geometryLength(geometry), 100)
    GeometryUtils.geometryEndpoints(stepGeom) should be(GeometryUtils.geometryEndpoints(geometry))
    stepGeom.length == geometry.length should be(true)
  }

  test("Test createStepGeometry When using a geometry with points having shorter gaps than the given step and length shorter than the step Then return decimated geometry.") {
    val geometry = Seq(
      Point(0.0, 0.0, 0.0),
      Point(20.0, 20.0, 0.0),
      Point(40.0, 40.0, 0.0),
      Point(50.0, 50.0, 0.0),
      Point(90.0, 90.0, 0.0)
    )
    val stepGeom = GeometryUtils.createStepGeometry(geometry, Seq.empty[Point], 0.0, GeometryUtils.geometryLength(geometry), 100)
    GeometryUtils.geometryEndpoints(stepGeom) should be(GeometryUtils.geometryEndpoints(geometry))
    stepGeom.length should be(2)
  }

  test("Test createStepGeometry When using a geometry with points having shorter gaps than the given step and length equals to step Then return decimated geometry.") {
    val geometry = Seq(
      Point(0.0, 0.0, 0.0),
      Point(20.0, 20.0, 0.0),
      Point(40.0, 40.0, 0.0),
      Point(50.0, 50.0, 0.0),
      Point(100.0, 100.0, 0.0)
    )
    val stepGeom = GeometryUtils.createStepGeometry(geometry, Seq.empty[Point], 0.0, GeometryUtils.geometryLength(geometry), 100)
    GeometryUtils.geometryEndpoints(stepGeom) should be(GeometryUtils.geometryEndpoints(geometry))
    stepGeom.length should be(2)
  }

  test("Test createStepGeometry When using a geometry with points having shorter gaps than the given step and length slightly longer than the step Then return decimated geometry.") {
    val geometry = Seq(
      Point(0.0, 0.0, 0.0),
      Point(20.0, 20.0, 0.0),
      Point(40.0, 40.0, 0.0),
      Point(50.0, 50.0, 0.0),
      Point(110.0, 110.0, 0.0)
    )
    val stepGeom = GeometryUtils.createStepGeometry(geometry, Seq.empty[Point], 0.0, GeometryUtils.geometryLength(geometry), 100)
    GeometryUtils.geometryEndpoints(stepGeom) should be(GeometryUtils.geometryEndpoints(geometry))
    stepGeom.length should be(2)
  }

  // TODO This test might need some changes, since the middle point doesn't have to be exactly (50.0, 50.0)
  test("Test createStepGeometry When using a geometry with points distributed unequally and the length longer than the step Then return decimated geometry.") {
    val geometry = Seq(
      Point(0.0, 0.0, 0.0),
      Point(20.0, 20.0, 0.0),
      Point(40.0, 40.0, 0.0),
      Point(50.0, 50.0, 0.0),
      Point(190.0, 190.0, 0.0)
    )
    val stepGeom = GeometryUtils.createStepGeometry(geometry, Seq.empty[Point], 0.0, GeometryUtils.geometryLength(geometry), 100)
    GeometryUtils.geometryEndpoints(stepGeom) should be(GeometryUtils.geometryEndpoints(geometry))
    stepGeom(1).x should be (50.0)
    stepGeom(1).y should be (50.0)
    stepGeom.length should be(3)
  }

}
