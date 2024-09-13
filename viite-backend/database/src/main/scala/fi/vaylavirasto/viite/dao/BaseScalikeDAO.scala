package fi.vaylavirasto.viite.dao

import scalikejdbc._
import org.slf4j.{Logger, LoggerFactory}

trait ScalikeJDBCBaseDAO {
  protected def logger: Logger = LoggerFactory.getLogger(getClass)

  // Implicit session for database operations
  implicit val session: DBSession = AutoSession

  // Method to run update queries
  def runUpdateToDb(updateQuery: SQL[Nothing, NoExtractor]): Int = {
    updateQuery.update.apply()
  }

  // Method to run select queries
  def runSelectQuery[A](query: SQL[A, HasExtractor]): List[A] = {
    query.list.apply()
  }
}
