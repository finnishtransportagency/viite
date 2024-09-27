package fi.vaylavirasto.viite.postgis

import fi.liikennevirasto.digiroad2.util.ViiteProperties
import scalikejdbc._


object PostGISDatabaseScalikeJDBC {
  private lazy val connectionPool: ConnectionPool = initConnectionPool()

  private val transactionOpen = new ThreadLocal[Boolean] {
    override def initialValue(): Boolean = false
  }

  private def initConnectionPool(): ConnectionPool = {
    Class.forName("org.postgresql.Driver")

    // Logging enabled for all queries for development purposes
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
        maxSize = 20,
        connectionTimeoutMillis = 3000L,
        validationQuery = "select 1 as one"
      )
    )
    ConnectionPool.get()
  }


  def runWithTransaction[Result](databaseOperation: DBSession => Result): Result = {
    if (transactionOpen.get())
      throw new IllegalThreadStateException("Attempted to open nested transaction")
    else {
      try {
        transactionOpen.set(true)
        DB(connectionPool.borrow()).localTx { implicit session =>
          databaseOperation(session)
        }
      } finally {
        transactionOpen.set(false)
      }
    }
  }

  def runWithReadOnlySession[Result](readOnlyOperation: DBSession => Result): Result = {
    if (transactionOpen.get())
      throw new IllegalThreadStateException("Attempted to open nested session")
    else {
      try {
        transactionOpen.set(true)
        DB(connectionPool.borrow()).readOnly { implicit session =>
          readOnlyOperation(session)
        }
      } finally {
        transactionOpen.set(false)
      }
    }
  }

  def runWithRollbackScalike[Result](testOperation: => Result): Result = {
    if (transactionOpen.get())
      throw new IllegalThreadStateException("Attempted to open nested transaction")
    else {
      try {
        transactionOpen.set(true)
        DB(connectionPool.borrow()).localTx { implicit session =>
          val result = testOperation
          session.connection.rollback()
          result
        }
      } finally {
        transactionOpen.set(false)
      }
    }
  }

}
