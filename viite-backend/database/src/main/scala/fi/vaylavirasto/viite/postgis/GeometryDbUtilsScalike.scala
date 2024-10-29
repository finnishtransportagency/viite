package fi.vaylavirasto.viite.postgis

import fi.vaylavirasto.viite.geometry.{BoundingRectangle, GeometryUtils, Point}
import net.postgis.jdbc.geometry.GeometryBuilder
import org.postgresql.util.PGobject
import scalikejdbc._

object GeometryDbUtils {

  // Returns SQLSyntax for use in WHERE clauses
  def boundingBoxFilter(bounds: BoundingRectangle, geometryColumn: SQLSyntax): SQLSyntax = {
    val leftBottomX = bounds.leftBottom.x
    val leftBottomY = bounds.leftBottom.y
    val rightTopX = bounds.rightTop.x
    val rightTopY = bounds.rightTop.y
    sqls"""
      $geometryColumn && ST_MakeEnvelope($leftBottomX,
                                         $leftBottomY,
                                         $rightTopX,
                                         $rightTopY,
                                         3067)
    """
  }

  // Returns String representation of a JGeometry object
  def createJGeometry(points: Seq[Point]): String = {
    if (points.nonEmpty) {
      s"""LINESTRING(${points.map(p => s"""${p.x} ${p.y} ${p.z}""").mkString(", ")})"""
    } else {
      "LINESTRING EMPTY"
    }
  }

  def createXYZMGeometry(points: Seq[(Point, Double)]): String = {
    if (points.nonEmpty) {
      s"""LINESTRING(${points.map(p => s"""${p._1.x} ${p._1.y} ${p._1.z} ${p._2}""").mkString(", ")})"""
    } else {
      s"LINESTRING EMPTY"
    }
  }

  def createPointGeometry(point: Point): String = {
    s"POINT(${point.x} ${point.y})"
  }

  def createRoundedPointGeometry(point: Point): String = {
    s"POINT(${GeometryUtils.scaleToThreeDigits(point.x)} ${GeometryUtils.scaleToThreeDigits(point.y)})"
  }

  def loadJGeometryToGeometry(geometry: Option[Object]): Seq[Point] = {
    if (geometry.nonEmpty) {
      val pgObject = geometry.get.asInstanceOf[PGobject]
      val geom = GeometryBuilder.geomFromString(pgObject.getValue)
      val n = geom.numPoints()
      var points: Seq[Point] = Seq()
      for (i <- 1 to n) {
        val point = geom.getPoint(i - 1)
        points = points :+ Point(point.x, point.y, point.z)
      }
      points
    } else {
      Seq()
    }
  }

}
