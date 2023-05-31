package fi.vaylavirasto.viite.geometry

case class Matrix(m: Seq[Seq[Double]]) {
  def *(that: Vector3d): Vector3d = {

    if (this.m.size == 2 && this.m.forall(_.size == 2)) {
      val v = Seq[Double](that.x, that.y)
      val x = this.m.map(_.head).zip(v).map(d => d._1 * d._2).sum
      val y = this.m.map(_.tail.head).zip(v).map(d => d._1 * d._2).sum
      Vector3d(x,y,0.0)
    } else if (m.size == 3 && m.forall(_.size == 3)) {
      val vec = Seq(that.x, that.y, that.z)
      val x = m.map(_.head).zip(vec).map(d => d._1 * d._2).sum
      val y = m.map(_.tail.head).zip(vec).map(d => d._1 * d._2).sum
      val z = m.map(_.tail.tail.head).zip(vec).map(d => d._1 * d._2).sum
      Vector3d(x,y,z)
    } else
      throw new IllegalArgumentException("Matrix operations only support 2d and 3d square matrixes")
  }
  def transpose: Matrix = {
    m.size match {
      case 2 => Matrix(Seq(Seq(m.head.head, m(1).head), Seq(m.head(1), m(1)(1))))
      case 3 => Matrix(Seq(Seq(m.head.head, m(1).head, m(2).head),
        Seq(m.head(1), m(1)(1), m(2)(1)),
        Seq(m.head(2), m(1)(2), m(2)(2))))
    }
  }
}

