package fi.liikennevirasto.digiroad2.oracle

import java.sql.Date

import javax.sql.DataSource
import com.jolbox.bonecp.{BoneCPConfig, BoneCPDataSource, ConnectionHandle}
import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import oracle.jdbc.{OracleDriver, driver}
import org.joda.time.LocalDate
import slick.driver.JdbcDriver.backend.Database
import slick.jdbc.StaticQuery.interpolation
import Database.dynamicSession
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2.Point
import oracle.spatial.geometry.JGeometry
import oracle.sql.STRUCT

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
    sqlu"""alter session set nls_language = 'american'""".execute
  }

  def jodaToSqlDate(jodaDate: LocalDate): Date = {
    new Date(jodaDate.toDate.getTime)
  }

  def initDataSource: DataSource = {
    Class.forName("oracle.jdbc.driver.OracleDriver")
    val cfg = new BoneCPConfig(ViiteProperties.bonecpProperties)
    new BoneCPDataSource(cfg)
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
    val ordinates = points.flatMap(p => Seq(p.x, p.y, p.z)).toArray
    val dim = 4
    val srid = 3067
    val oracleConn = dynamicSession.conn.asInstanceOf[ConnectionHandle].getInternalConnection
      JGeometry.store(JGeometry.createLinearLineString(ordinates, dim, srid), oracleConn)
  }

  def createRoadsJGeometry(points: Seq[Point], con: java.sql.Connection, endMValue:Double): STRUCT = {
    val ordinates = points.flatMap(p => Seq(GeometryUtils.roundN(p.x), GeometryUtils.roundN(p.y), GeometryUtils.roundN(p.z), GeometryUtils.roundN(endMValue))).toArray
    val dim = 4
    val srid = 3067
    val oracleConn = dynamicSession.conn.asInstanceOf[ConnectionHandle].getInternalConnection
    JGeometry.store(JGeometry.createLinearLineString(ordinates, dim, srid), oracleConn)
  }

  def createPointJGeometry(point: Point): STRUCT = {
    val coordinates = Array(GeometryUtils.roundN(point.x), GeometryUtils.roundN(point.y), 0, 0)
    val dim = 4
    val srid = 3067
    val oracleConn = dynamicSession.conn.asInstanceOf[ConnectionHandle].getInternalConnection
    JGeometry.store(JGeometry.createPoint(coordinates, dim, srid), oracleConn)
  }

  def loadJGeometryToGeometry(geometry: Option[Object]): Seq[Point] = {
    // Convert STRUCT into geometry
    val geom = geometry.map(g => g.asInstanceOf[STRUCT])
    if (geom.nonEmpty) {
      val jgeom: JGeometry = JGeometry.load(geom.get)
      jgeom.getOrdinatesArray.toList.sliding(3, 3).toList.map(p => Point(p.head, p.tail.head, p.last))
    } else {
      Seq()
    }
  }

  def loadRoadsJGeometryToGeometry(geometry: Option[Object]): Seq[Point] = {
    // Convert STRUCT into geometry
    val geom = geometry.map(g => g.asInstanceOf[STRUCT])
    if (geom.nonEmpty) {
      val jgeom: JGeometry = JGeometry.load(geom.get)
      jgeom.getOrdinatesArray.toList.sliding(4, 4).toList.map(p => Point(p.head, p.tail.head, p.tail.tail.head))
    } else {
      Seq()
    }
  }

}