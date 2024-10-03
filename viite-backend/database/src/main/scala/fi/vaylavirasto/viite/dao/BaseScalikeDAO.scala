package fi.vaylavirasto.viite.dao

import scalikejdbc._
import org.slf4j.{Logger, LoggerFactory}
import fi.vaylavirasto.viite.postgis.SessionProvider.session // As this is imported here, it is available in all classes that extend this trait

trait ScalikeJDBCBaseDAO {
  protected def logger: Logger = LoggerFactory.getLogger(getClass)

  // Methods to run queries using ScalikeJDBC and session provider
  def runUpdateToDbScalike(updateQuery: SQL[Nothing, NoExtractor]): Int = {
    updateQuery.update.apply()
  }

  def runSelectQuery[A](query: SQL[A, HasExtractor]): List[A] = {
    query.list().apply()
  }

  def runSelectSingle[A](query: SQL[A, HasExtractor]): Option[A] = {
    query.single().apply()
  }

  def runBatchUpdateToDbScalike(query: SQL[Nothing, NoExtractor], batchParams: Seq[Seq[Any]]): List[Int] = {
    query.batch(batchParams: _*).apply()
  }

}
