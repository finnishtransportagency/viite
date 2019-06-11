package fi.liikennevirasto.digiroad2.oracle

import java.sql.Date
import java.util.Properties

import javax.sql.DataSource
import com.jolbox.bonecp.{BoneCPConfig, BoneCPDataSource, ConnectionHandle}
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import org.joda.time.LocalDate
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import oracle.spatial.geometry.JGeometry
import oracle.sql.STRUCT
import org.postgis.PGgeometry
import org.postgresql.util.PGobject

// TODO Rename to PostGisDatabase
object OracleDatabase {
  lazy val ds: DataSource = initDataSource

  lazy val localProperties: Properties = {
    loadProperties("/bonecp.properties")
  }

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
    val cfg = new BoneCPConfig(localProperties)
    new BoneCPDataSource(cfg)
  }

  def loadProperties(resourcePath: String): Properties = {
    val props = new Properties()
    try {
      props.load(getClass.getResourceAsStream(resourcePath))
    } catch {
      case e: Exception => throw new RuntimeException("Can't load " + resourcePath + " for env: " + System.getProperty("digiroad2.env"), e)
    }
    props
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

  // TODO Postgis: Maybe this can be removed? If not then convert it.
  def createRoadsJGeometry(points: Seq[Point], con: java.sql.Connection, endMValue:Double): STRUCT = {
    val ordinates = points.flatMap(p => Seq(GeometryUtils.roundN(p.x), GeometryUtils.roundN(p.y), GeometryUtils.roundN(p.z), GeometryUtils.roundN(endMValue))).toArray
    val dim = 4
    val srid = 3067
    val oracleConn = dynamicSession.conn.asInstanceOf[ConnectionHandle].getInternalConnection
    JGeometry.store(JGeometry.createLinearLineString(ordinates, dim, srid), oracleConn)
  }

  // TODO Postgis: Change this.
  def createPointJGeometry(point: Point): STRUCT = {
    val coordinates = Array(GeometryUtils.roundN(point.x), GeometryUtils.roundN(point.y), 0, 0)
    val dim = 4
    val srid = 3067
    val oracleConn = dynamicSession.conn.asInstanceOf[ConnectionHandle].getInternalConnection
    JGeometry.store(JGeometry.createPoint(coordinates, dim, srid), oracleConn)
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

  // TODO PostGIS conversion: This might be unnecessary after fixing NodeDAO
  def loadRoadsJGeometryToGeometry(geometry: Option[Object]): Seq[Point] = {
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
/*    // Convert STRUCT into geometry
    val geom = geometry.map(g => g.asInstanceOf[STRUCT])
    if (geom.nonEmpty) {
      val jgeom: JGeometry = JGeometry.load(geom.get)
      jgeom.getOrdinatesArray.toList.sliding(4, 4).toList.map(p => Point(p.head, p.tail.head, p.tail.tail.head))
    } else {
      Seq()
    }
*/
  }

}