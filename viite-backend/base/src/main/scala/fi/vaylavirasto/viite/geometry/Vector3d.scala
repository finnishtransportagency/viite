package fi.vaylavirasto.viite.geometry

case class Vector3d(x: Double, y: Double, z: Double) {
  private val RotationLeft = Matrix(Seq(Seq(0.0, 1.0), Seq(-1.0, 0.0)))
  private val RotationRight = RotationLeft.transpose
  def rotateLeft(): Vector3d = {
    RotationLeft * this
  }
  def rotateRight(): Vector3d = {
    RotationRight * this
  }

  /** Dot product. */
  def dot(that: Vector3d): Double = {
    (x * that.x) + (y * that.y) + (z * that.z)
  }

  /** Dot product (alias of [[Vector3d.dot]]) */
  def ⋅(that: Vector3d): Double = {
    dot(that)
  }

  /** @return this vector normalized, i.e. scaled to be of length 1. */
  def normalize(): Vector3d = {
    if (length() != 0) {
      scale(1 / length())
    } else {
      scale(0.0)
    }
  }

  def normalize2D(): Vector3d = {
    if (this.copy(z=0.0).length() != 0) {
      scale(1 / this.copy(z=0.0).length())
    } else {
      scale(0.0)
    }
  }

  def scale(scalar: Double): Vector3d = {
    Vector3d(x * scalar, y * scalar, z * scalar)
  }

  def length(): Double = {
    Math.sqrt((x * x) + (y * y) + (z * z))
  }

  def -(that: Vector3d): Vector3d = {
    Vector3d(x - that.x, y - that.y, z - that.z)
  }

  def +(that: Vector3d): Vector3d = {
    Vector3d(x + that.x, y + that.y, z + that.z)
  }

  def ⨯(that: Vector3d): Vector3d = {
    Vector3d(this.y * that.z - this.z * that.y, this.z * that.x - this.x * that.z, this.x * that.y - this.y * that.x)
  }

  def cross(that: Vector3d): Vector3d = ⨯(that)

  def to2D(): Vector3d = {
    Vector3d(x, y, 0.0)
  }

  def angle(that: Vector3d): Double = {
    2.0d * Math.atan((this - that).length / (this + that).length)
  }

  def angleXYWithNegativeValues(that: Vector3d): Double = {
    Math.atan2(that.x * this.y - that.y * this.x, that.x * this.x + that.y * this.y)
  }
}

