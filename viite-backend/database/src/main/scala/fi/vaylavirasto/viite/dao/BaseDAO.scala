package fi.vaylavirasto.viite.dao

import scalikejdbc._
import org.slf4j.{Logger, LoggerFactory}
import fi.vaylavirasto.viite.postgis.SessionProvider.session // As this is imported here, it is available in all classes that extend this trait

// Methods to run queries using ScalikeJDBC and session provider
trait BaseDAO {
  protected def logger: Logger = LoggerFactory.getLogger(getClass)

  def runUpdateToDb(updateQuery: SQL[Nothing, NoExtractor]): Int = {
    updateQuery.update.apply()
  }

  def runSelectQuery[A](query: SQL[A, HasExtractor]): List[A] = {
    query.list().apply()
  }

  def runSelectSingle[A](query: SQL[A, HasExtractor]): Option[A] = {
    query.single().apply()
  }

  def runBatchUpdateToDb(query: SQL[Nothing, NoExtractor], batchParams: Seq[Seq[Any]]): List[Int] = {
    query.batch(batchParams: _*).apply()
  }

  /**
   * Run a select query and return the first result as an Option
   * Example usage: runSelectSingleFirstOptionWithType[Long](query)
   * @param query The query to run
   * @param mapper A function to map the result to the desired type
   * @tparam T The type of the result
   * @return The first result as an Option
   */
  def runSelectSingleFirstOptionWithType[T](query: SQL[Nothing, NoExtractor])(implicit mapper: WrappedResultSet => T): Option[T] = {
    query.map(mapper).single().apply()
  }

  // Implicit conversions for common types
  implicit val longMapper: WrappedResultSet => Long = _.long(1)
  implicit val stringMapper: WrappedResultSet => String = _.string(1)
  //implicit val linkMapper: WrappedResultSet => Link = _.link(1)
  // Add more implicit mappers as needed

}
