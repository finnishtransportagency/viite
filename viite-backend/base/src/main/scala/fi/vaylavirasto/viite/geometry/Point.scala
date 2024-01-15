package fi.vaylavirasto.viite.geometry

case class Point(x: Double, y: Double, z: Double = 0.0) {

  /** Distance of this Point and <i>point</i> in the (x,y) plane.*/
  def distance2DTo(point: Point): Double =
    Math.sqrt(Math.pow(point.x - x, 2) + Math.pow(point.y - y, 2))

  def distance3DTo(point: Point): Double =
    Math.sqrt(Math.pow(point.x - x, 2) + Math.pow(point.y - y, 2) + Math.pow(point.z - z, 2))

  /** @return this Point moved by the negative amount of coordinate values of <i>other</i> Point. */
  def minus(other: Point): Point = {
    Point (this.x - other.x, this.y - other.y, this.z - other.z)
  }

  /** @return Vector3d whose coordinates are defined by substracting <i>that</i> Point's coordinates from this Point's coordinates.*/
  def -(that: Point): Vector3d = {
    Vector3d(x - that.x, y - that.y, z - that.z)
  }

  def -(that: Vector3d): Point = {
    Point(x - that.x, y - that.y, z - that.z)
  }

  def +(that: Vector3d): Point = {
    Point(x + that.x, y + that.y, z + that.z)
  }

  def +=(that: Vector3d): Point = {
    Point(GeometryUtils.scaleToThreeDigits(x + that.x), GeometryUtils.scaleToThreeDigits(y + that.y), z + that.z)
  }

  /** Return this Point with its coordinates rounded to the nearest 3rd decimal. */
  def with3decimals = {
    Point(
      GeometryUtils.scaleToThreeDigits(this.x),
      GeometryUtils.scaleToThreeDigits(this.y),
      GeometryUtils.scaleToThreeDigits(this.z)
    )
  }

  lazy val toVector: Vector3d = {
    this - Point(0.0, 0.0)
  }

  /** @return Whether this Point and <i>point</i> are interpreted of being connected. The points are considered
   *          being connected, if they are less than [[GeometryUtils.MaxDistanceForConnectedLinks]] apart from each other. */
  def connected(point: Point): Boolean = {
    GeometryUtils.areAdjacent(this, point, GeometryUtils.MaxDistanceForConnectedLinks)
  }

  /** @return Whether this Point and <i>point</i> are interpreted as being the same. The points are considered
   *          the same, if they are less than [[GeometryUtils.DefaultEpsilon]] apart from each other. */
  def consideredSameAs(point: Point): Boolean = {
    GeometryUtils.areAdjacent(this, point, GeometryUtils.DefaultEpsilon)
  }
}
