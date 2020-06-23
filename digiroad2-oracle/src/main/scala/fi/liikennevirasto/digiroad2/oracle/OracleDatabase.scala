package fi.liikennevirasto.digiroad2.oracle

import java.sql.Date

import javax.sql.DataSource
import com.jolbox.bonecp.{BoneCPConfig, BoneCPDataSource, ConnectionHandle}
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import org.joda.time.LocalDate
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.Point
import oracle.spatial.geometry.JGeometry
import oracle.sql.STRUCT
import org.postgis.PGgeometry
import org.postgresql.util.PGobject

// TODO Rename to PostGisDatabase
object OracleDatabase {
  lazy val ds: DataSource = initDataSource

  private val transactionOpen = new ThreadLocal[Boolean] {
    override def initialValue(): Boolean = { false }
  }

  def withDynTransaction[T](f: => T): T = {
    if (transactionOpen.get())
      throw new IllegalThreadStateException("Attempted to open nested transaction")
    else {
      try {
        transactionOpen.set(true)
        Database.forDataSource(OracleDatabase.ds).withDynTransaction {
          setSessionLanguage()
          f
        }
      } finally {
        transactionOpen.set(false)
      }
    }
  }

  def withDynTransactionNewOrExisting[T](f: => T): T = {
    if (transactionOpen.get()) {
      f
    } else {
      try {
        transactionOpen.set(true)
        Database.forDataSource(OracleDatabase.ds).withDynTransaction {
          setSessionLanguage()
          f
        }
      } finally {
        transactionOpen.set(false)
      }
    }
  }

  def withDynSession[T](f: => T): T = {
    if (transactionOpen.get())
      throw new IllegalThreadStateException("Attempted to open nested session")
    else {
      try {
        transactionOpen.set(true)
        Database.forDataSource(OracleDatabase.ds).withDynSession {
          setSessionLanguage()
          f
        }
      } finally {
        transactionOpen.set(false)
      }
    }
  }

  def isWithinSession: Boolean = {
    transactionOpen.get()
  }

  def setSessionLanguage() {
    //sqlu"""alter session set nls_language = 'american'""".execute
  }

  def jodaToSqlDate(jodaDate: LocalDate): Date = {
    new Date(jodaDate.toDate.getTime)
  }

  def initDataSource: DataSource = {
    Class.forName("org.postgresql.Driver")
    val cfg = new BoneCPConfig(ViiteProperties.bonecpProperties)
    new BoneCPDataSource(cfg)
  }

  def boundingBoxFilter(bounds: BoundingRectangle, geometryColumn: String): String = {
    val leftBottomX = bounds.leftBottom.x
    val leftBottomY = bounds.leftBottom.y
    val rightTopX = bounds.rightTop.x
    val rightTopY = bounds.rightTop.y
    s"""
      $geometryColumn && ST_MakeEnvelope($leftBottomX,
                                         $leftBottomY,
                                         $rightTopX,
                                         $rightTopY,
                                         3067)
    """
  }

  def createJGeometry(points: Seq[Point]): String = {
    s"""LINESTRING(${points.map(p => s"""${p.x} ${p.y} ${p.z}""").mkString(", ")})"""
  }

  def createXYZMGeometry(points: Seq[(Point, Double)]): String = {
    s"""LINESTRING(${points.map(p => s"""${p._1.x} ${p._1.y} ${p._1.z} ${p._2}""").mkString(", ")})"""
  }

  def createPointGeometry(point: Point): String = {
    s"""POINT(${point.x} ${point.y})"""
  }

  def createRoundedPointGeometry(point: Point): String = {
    s"""POINT(${GeometryUtils.roundN(point.x)} ${GeometryUtils.roundN(point.y)})"""
  }

  // TODO Maybe this should be optimized
  def loadJGeometryToGeometry(geometry: Option[Object]): Seq[Point] = {
    if (geometry.nonEmpty) {
      val pgObject = geometry.get.asInstanceOf[PGobject]
      val geom = PGgeometry.geomFromString(pgObject.getValue)
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