package fi.vaylavirasto.viite.postgis

import fi.liikennevirasto.digiroad2.util.ViiteProperties
import scalikejdbc._
import SessionProvider._

object PostGISDatabaseScalikeJDBC {
  // Load the PostgreSQL driver
  Class.forName("org.postgresql.Driver")

  // Initialize the connection pool with default settings
  ConnectionPool.singleton(
    url = ViiteProperties.scalikeJdbcUrl,
    user = ViiteProperties.scalikeJdbcUser,
    password = ViiteProperties.scalikeJdbcPassword
  )

  // Logging enabled for all queries for development purposes
  GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(
    enabled = true,
    logLevel = 'info
  )

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
    * For database operations that modify data and should be committed after the operation
    * @param autoCommitOperation The operation to run
    * @tparam Result The return type of the operation
    * @return The result of the operation
    */
  def runWithAutoCommit[Result](autoCommitOperation: => Result): Result = {
    DB.autoCommit { session =>
      withSession(session) {
        autoCommitOperation
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
  def runWithRollback[Result](testOperation: => Result): Result = {
    DB.localTx { session: DBSession =>
      withSession(session) {
        val result = testOperation
        session.connection.rollback()
        result
      }
    }
  }

}
