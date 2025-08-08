package fi.vaylavirasto.viite.dao

import fi.vaylavirasto.viite.postgis.SessionProvider
import scalikejdbc._
import org.slf4j.{Logger, LoggerFactory}
import fi.vaylavirasto.viite.postgis.SessionProvider.session
import fi.vaylavirasto.viite.util.ViiteException
import java.sql.Date

// Methods to run queries using ScalikeJDBC and session provider
trait BaseDAO {
  protected def logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * Executes an UPDATE, INSERT, or DELETE query.
   *
   * @param updateQuery SQL query to execute
   * @return The number of rows affected
   * @throws java.sql.SQLException if write operations are blocked by SessionProvider.checkWriteAllowed()
   *                               (occurs in read-only sessions within transactions)
   */
  def runUpdateToDb(updateQuery: SQL[Nothing, NoExtractor]): Int = {
    SessionProvider.checkWriteAllowed() // Check before executing write
    updateQuery.update.apply()
  }

  /**
   * Executes a batch update query with multiple parameter sets.
   *
   * @param query query SQL query to execute
   * @param batchParams Sequence of parameter sets, each set corresponding to one batch operation
   * @return List of numbers indicating rows affected by each batch operation
   * @throws java.sql.SQLException if write operations are blocked by SessionProvider.checkWriteAllowed()
   *                               (occurs in read-only sessions within transactions)
   */
  def runBatchUpdateToDb(query: SQL[Nothing, NoExtractor], batchParams: Seq[Seq[Any]]): List[Int] = {
    SessionProvider.checkWriteAllowed() // Check before executing write
    query.batch(batchParams: _*).apply()
  }

  /**
   * Executes a SELECT query and returns all results.
   *
   * @param query SQL query with result type A
   * @tparam A Type to map the results to
   * @return List of results mapped to type A
   */
  def runSelectQuery[A](query: SQL[A, HasExtractor]): List[A] = {
    query.list().apply()
  }

  /**
   * Executes a SELECT query and returns the first result.
   * Example use: runSelectFirst(query.map(rs => rs.A("column_name")))
   *
   * @param query SQL query with result type A
   * @tparam A Type to map the result to
   * @return The first result as an A Type Option
   */
  def runSelectFirst[A](query: SQL[A, HasExtractor]): Option[A] = {
      query.first().apply()
  }

  /**
   * Executes a SELECT query and returns a single matched row as an Option.
   * If multiple rows are returned unexpectedly, a runtime exception is thrown.
   *
   * @param query SQL query with result type A
   * @tparam A Type to map the result to
   * @return None if no results found, Some(result) if exactly one exists
   * @throws ViiteException if multiple rows are returned
   */
  def runSelectSingleOption[A](query: SQL[A, HasExtractor]): Option[A] = {
    try {
      query.single().apply()
    } catch {
      case e: IllegalStateException => throw ViiteException(e.getMessage)
    }
  }

  /**
   * Executes a SELECT query and returns a single matched row.
   * Throws ViiteException if no results or more than 1 result is found.
   *
   * @param query SQL query with result type A
   * @tparam A Type to map the result to
   * @return The first result
   * @throws ViiteException if no results found or if multiple rows are returned
   */
  def runSelectSingle[A](query: SQL[A, HasExtractor]): A = {
    try {
      query.single().apply().getOrElse(
        throw ViiteException("No result found")
      )
    } catch {
      case e: IllegalStateException => throw ViiteException(e.getMessage)
    }
  }

  /**
   * Executes a SELECT query and maps the single found result Option using an implicit mapper function.
   * Example use: runSelectSingleFirstOptionWithType[Long](query)
   *
   * @param query SQL query to execute
   * @param mapper Implicit function to convert WrappedResultSet to type T
   * @tparam T Type to map the result to
   * @return None if no results found, Some(T) if exactly one result exists
   * @throws ViiteException if multiple rows are returned
   */
  def runSelectSingleFirstOptionWithType[T](query: SQL[Nothing, NoExtractor])(implicit mapper: WrappedResultSet => T): Option[T] = {
    try {
      query.map(mapper).single().apply()
    } catch {
      case e: IllegalStateException => throw ViiteException(e.getMessage)
    }
  }

  /**
   * Executes a SELECT query and maps the single found result using an implicit mapper function.
   * If no results are found, a ViiteException is thrown.
   * Example use: runSelectSingleFirstWithType[Long](query)
   *
   * @param query SQL query to execute
   * @param mapper Implicit function to convert WrappedResultSet to type T
   * @tparam T Type to map the result to
   * @return The mapped result
   * @throws ViiteException if no results found or if multiple rows are returned
   */
  def runSelectSingleFirstWithType[T](query: SQL[Nothing, NoExtractor])(implicit mapper: WrappedResultSet => T): T = {
    try {
      query.map(mapper).single().apply().getOrElse(
        throw ViiteException("No value returned")
      )
    } catch {
      case e: IllegalStateException => throw ViiteException(e.getMessage)
    }
  }

  // Implicit conversions for common types
  // (1) maps the first column of a result set to the desired type
  implicit val longMapper: WrappedResultSet => Long = _.long(1)
  implicit val stringMapper: WrappedResultSet => String = _.string(1)
  implicit val intMapper: WrappedResultSet => Int = _.int(1)
  implicit val dateTimeMapper: WrappedResultSet => Date = _.date(1)
  // Add more implicit mappers as needed

}
