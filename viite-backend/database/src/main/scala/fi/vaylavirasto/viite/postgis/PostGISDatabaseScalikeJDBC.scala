package fi.vaylavirasto.viite.postgis

import fi.liikennevirasto.digiroad2.util.ViiteProperties
import scalikejdbc._
import SessionProvider._

object PostGISDatabaseScalikeJDBC {
  private val connectionPool: ConnectionPool = initConnectionPool()

  private def initConnectionPool(): ConnectionPool = {
    if (!ConnectionPool.isInitialized())
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

  /**
    * For database operations that modify data only if the whole operation is successful
    * @param databaseOperation The operation to run
    * @tparam Result The return type of the operation
    * @return The result of the operation
    */
  def runWithTransaction[Result](databaseOperation: => Result): Result = {
    DB.localTx { session =>
      withSession(session) {
        databaseOperation
      }
    }
  }

  /**
    * For database operations that only read data
    * @param readOnlyOperation The operation to run
    * @tparam Result The return type of the operation
    * @return The result of the operation
    */
  def runWithReadOnlySession[Result](readOnlyOperation: => Result): Result = {
    DB.readOnly { session =>
      withSession(session) {
        readOnlyOperation
      }
    }
  }

  /**
    * For database operations that modify data and should be rolled back after the operation
    * Used for testing
    * @param testOperation The operation to run
    * @tparam Result The return type of the operation
    * @return The result of the operation
    */
  def runWithRollbackScalike[Result](testOperation: => Result): Result = {
    DB.localTx { session: DBSession =>
      withSession(session) {
        val result = testOperation
        session.connection.rollback()
        result
      }
    }
  }

}
