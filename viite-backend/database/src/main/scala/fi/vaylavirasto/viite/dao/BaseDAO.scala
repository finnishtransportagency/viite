package fi.vaylavirasto.viite.dao

import scalikejdbc._
import org.slf4j.{Logger, LoggerFactory}
import fi.vaylavirasto.viite.postgis.SessionProvider.session // As this is imported here, it is available in all classes that extend this trait

// Methods to run queries using ScalikeJDBC and session provider
trait BaseDAO {
  protected def logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * Executes an UPDATE, INSERT, or DELETE query.
   *
   * @param updateQuery SQL query to execute
   * @return The number of rows affected
   */
  def runUpdateToDb(updateQuery: SQL[Nothing, NoExtractor]): Int = {
    updateQuery.update.apply()
  }

  /**
   * Executes a batch update query with multiple parameter sets.
   *
   * @param query query SQL query to execute
   * @param batchParams Sequence of parameter sets, each set corresponding to one batch operation
   * @return List of numbers indicating rows affected by each batch operation
   */
  def runBatchUpdateToDb(query: SQL[Nothing, NoExtractor], batchParams: Seq[Seq[Any]]): List[Int] = {
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
   * Executes a SELECT query and returns the first result if any exists.
   *
   * @param query SQL query with result type A
   * @tparam A Type to map the result to
   * @return None if no results found, Some(result) if at least one exists
   */
  def runSelectSingleOption[A](query: SQL[A, HasExtractor]): Option[A] = {
    query.single().apply()
  }

  /**
   * Executes a SELECT query and returns the first result.
   * Throws NoSuchElementException if no results found.
   *
   * @param query SQL query with result type A
   * @tparam A Type to map the result to
   * @return The first result
   */
  def runSelectSingle[A](query: SQL[A, HasExtractor]): A = {
    query.single().apply().getOrElse(throw new NoSuchElementException("No result found"))
  }

  /**
   * Executes a SELECT query and maps the first result using an implicit mapper function.
   * Useful for queries returning a single column that needs type conversion.
   * Example use: runSelectSingleFirstOptionWithType[Long](query)
   *
   * @param query SQL query to execute
   * @param mapper Implicit function to convert WrappedResultSet to type T
   * @tparam T Type to map the result to
   * @return None if no results found, Some(T) if result exists
   */
  def runSelectSingleFirstOptionWithType[T](query: SQL[Nothing, NoExtractor])(implicit mapper: WrappedResultSet => T): Option[T] = {
    query.map(mapper).single().apply()
  }

  /**
   * Executes a SELECT query and maps the first result using an implicit mapper function.
   * Throws NoSuchElementException if no results found.
   *
   * @param query SQL query to execute
   * @param mapper Implicit function to convert WrappedResultSet to type T
   * @tparam T Type to map the result to
   * @return The first result
   */
  def runSelectSingleFirstWithType[T](query: SQL[Nothing, NoExtractor])(implicit mapper: WrappedResultSet => T): T = {
    query.map(mapper).single().apply().getOrElse(
      throw new NoSuchElementException("No value returned")
    )
  }

  // Implicit conversions for common types
  implicit val longMapper: WrappedResultSet => Long = _.long(1)
  implicit val stringMapper: WrappedResultSet => String = _.string(1)
  implicit val intMapper: WrappedResultSet => Int = _.int(1)
  //implicit val linkMapper: WrappedResultSet => Link = _.link(1)
  // Add more implicit mappers as needed

}
