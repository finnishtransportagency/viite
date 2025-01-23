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

  // Logging for queries
  GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(
    // enabled = true,
    // logLevel = 'info
  )

  /**
   * Executes `databaseOperation` block within a single transaction, ensuring that all data modifications
   * are committed only if the entire operation is successful.
   *
   * Uses withSession to handle the session and to prevent nested transactions.
   * If any action fails, the transaction is rolled back, preventing partial updates and maintaining data integrity.
   *
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
   * Executes `readOnlyOperation` within a read-only session.
   *
   * Use this method for database operations that only read data.
   * If a write operation is attempted within this session, a `java.sql.SQLException` will be thrown.
   *
   * @param readOnlyOperation The operation to execute.
   * @tparam Result The return type of the operation.
   * @return The result of `readOnlyOperation`.
   * @throws java.sql.SQLException If a write operation is attempted in read-only mode.
   */
  def runWithReadOnlySession[Result](readOnlyOperation: => Result): Result = {
    DB.readOnly { session =>
      withSession(session) {
        readOnlyOperation
      }
    }
  }

  /**
   * Executes `testOperation` within a transaction that is rolled back after execution.
   *
   * This method is useful for testing purposes, allowing you to perform data modifications without persisting changes.
   * After `testOperation` completes, the transaction is rolled back, discarding any data modifications.
   *
   * @param testOperation The operation to execute.
   * @tparam Result The return type of the operation.
   * @return The result of `testOperation`.
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
