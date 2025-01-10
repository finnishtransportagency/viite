package fi.vaylavirasto.viite.postgis

import fi.liikennevirasto.digiroad2.util.ViiteProperties
import scalikejdbc._
import SessionProvider._

import scala.concurrent.{ExecutionContext, Future, blocking}


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
   * Executes `databaseOperation` block within the current transaction if one exists,
   * or creates a new transaction if none exists. Allows transaction reuse.
   *
   * If any action fails, the transaction is rolled back, preventing partial updates and maintaining data integrity.
   *
   * @param databaseOperation The operation to run
   * @tparam Result The return type of the operation
   * @return The result of the operation
   */
  def runWithTransactionNewOrExisting[Result](databaseOperation: => Result): Result = {
    if (SessionProvider.isTransactionOpen) {
      databaseOperation
    } else {
      runWithTransaction(databaseOperation)
    }
  }

  /**
   * Executes `futureOperation` within a transaction that uses Future's completion state as transaction boundary.
   * Uses withSession to handle the session and to prevent nested transactions.
   * Transaction is committed if Future completes successfully, rolled back if Future fails.
   *
   * @param operation The operation to run wrapped in Future
   * @param ec ExecutionContext for Future operations
   * @tparam Result The return type wrapped in Future
   * @return Future containing the result or error
   */
  def runWithFutureTransaction[Result](operation: => Result)
                                      (implicit ec: ExecutionContext): Future[Result] = {
    DB.futureLocalTx { session =>
      Future {
        blocking {
          withSession(session) {  // Session setup inside Future block
            operation
          }
        }
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
   * Executes `autoCommitOperation` within an auto-commit session.
   *
   * Use this method for database operations that modify data and should be committed immediately after execution.
   * Each operation is committed individually without an explicit transaction.
   *
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
