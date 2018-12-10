package fi.liikennevirasto.digiroad2.oracle

import java.sql.Date
import java.util.Properties

import javax.sql.DataSource
import com.jolbox.bonecp.{BoneCPConfig, BoneCPDataSource, ConnectionHandle}
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import oracle.jdbc.{OracleDriver, driver}
import org.joda.time.LocalDate
import slick.driver.JdbcDriver.backend.Database
import slick.jdbc.StaticQuery.interpolation
import Database.dynamicSession
import fi.liikennevirasto.digiroad2.Point
import oracle.spatial.geometry.JGeometry
import oracle.sql.STRUCT

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
    sqlu"""alter session set nls_language = 'american'""".execute
  }

  def jodaToSqlDate(jodaDate: LocalDate): Date = {
    new Date(jodaDate.toDate.getTime)
  }

  def initDataSource: DataSource = {
    Class.forName("oracle.jdbc.driver.OracleDriver")
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
        mdsys.sdo_filter($geometryColumn,
                         sdo_cs.viewport_transform(
                         mdsys.sdo_geometry(
                         2003,
                         0,
                         NULL,
                         mdsys.sdo_elem_info_array(1,1003,3),
                         mdsys.sdo_ordinate_array($leftBottomX,
                                                  $leftBottomY,
                                                  $rightTopX,
                                                  $rightTopY)
                         ),
                         3067
                         ),
                         'querytype=WINDOW'
                         ) = 'TRUE'
    """
  }

  def createJGeometry(points: Seq[Point], con: java.sql.Connection): STRUCT = {
    val ordinates = points.flatMap(p => Seq(p.x, p.y, 0.0, 0.0)).toArray
    val dim = 4
    val srid = 3067
    val oracleConn = dynamicSession.conn.asInstanceOf[ConnectionHandle].getInternalConnection
    JGeometry.store(JGeometry.createLinearLineString(ordinates, dim, srid), oracleConn)
  }

  def createRoadsJGeometry(points: Seq[Point], con: java.sql.Connection, endMValue:Double): STRUCT = {
    val ordinates = points.flatMap(p => Seq(p.x, p.y, p.z, endMValue)).toArray
    val dim = 4
    val srid = 3067
    val oracleConn = dynamicSession.conn.asInstanceOf[ConnectionHandle].getInternalConnection
    JGeometry.store(JGeometry.createLinearLineString(ordinates, dim, srid), oracleConn)
  }

  def loadJGeometryToGeometry(geometry: Option[Object]): Seq[Point] = {
    // Convert STRUCT into geometry
    val geom = geometry.map(g => g.asInstanceOf[STRUCT])
    if (geom.nonEmpty) {
      val jgeom: JGeometry = JGeometry.load(geom.get)
      jgeom.getOrdinatesArray.toList.sliding(4, 4).toList.map(p => Point(p.head, p.tail.head, p.last))
    } else {
      Seq()
    }
  }

  def loadJGeometryToGeometry2D(geometry: Option[Object]): Seq[Point] = {
    // Convert STRUCT into geometry
    val geom = geometry.map(g => g.asInstanceOf[STRUCT])
    if (geom.nonEmpty) {
      val jgeom: JGeometry = JGeometry.load(geom.get)
      jgeom.getOrdinatesArray.toList.sliding(4, 4).toList.map(p => Point(p.head, p.tail.head))
    } else {
      Seq()
    }
  }

}