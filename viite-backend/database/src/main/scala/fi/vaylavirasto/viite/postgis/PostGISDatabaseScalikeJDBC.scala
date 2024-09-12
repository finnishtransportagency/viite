package fi.vaylavirasto.viite.postgis

import fi.liikennevirasto.digiroad2.util.ViiteProperties
import scalikejdbc._

object PostGISDatabaseScalikeJDBC {
  private lazy val connectionPool: ConnectionPool = initConnectionPool()

  private val transactionOpen = new ThreadLocal[Boolean] {
    override def initialValue(): Boolean = false
  }


  private def initConnectionPool(): ConnectionPool = {
    Class.forName(ViiteProperties.scalikeJdbcDriver)

    // Global settings for ScalikeJDBC
    GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(
      enabled = true,
      logLevel = 'info
    )

    ConnectionPool.singleton(
      url = ViiteProperties.scalikeJdbcUrl,
      user = ViiteProperties.scalikeJdbcUser,
      password = ViiteProperties.scalikeJdbcPassword,
      settings = ConnectionPoolSettings( // Default settings for now
        initialSize = 5,
        maxSize = 7,
        connectionTimeoutMillis = 1000L,
        validationQuery = "select 1 as one"
      )
    )
    ConnectionPool.get()
  }

  def withDynTransaction[T](f: => T): T = {
    if (transactionOpen.get())
      throw new IllegalThreadStateException("Attempted to open nested transaction")
    else {
      try {
        transactionOpen.set(true)
        DB(connectionPool.borrow()).localTx { implicit session =>
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
        DB(connectionPool.borrow()).readOnly { implicit session =>
          f
        }
      } finally {
        transactionOpen.set(false)
      }
    }
  }

}
