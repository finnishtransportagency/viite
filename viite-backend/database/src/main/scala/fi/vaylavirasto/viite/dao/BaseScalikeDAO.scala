package fi.vaylavirasto.viite.dao

import scalikejdbc._
import org.slf4j.{Logger, LoggerFactory}

trait ScalikeJDBCBaseDAO {
  protected def logger: Logger = LoggerFactory.getLogger(getClass)

  // Implicit session for database operations
  implicit val session: DBSession = AutoSession

  // TODO Logging enabled for dev purposes
  // Method to run update queries
  def runUpdateToDb(updateQuery: SQL[Nothing, NoExtractor]): Int = {
    logger.debug(s"Executing update SQL: ${updateQuery.statement}")
    updateQuery.update.apply()
  }

  // Method to run select queries
  def runSelectQuery[A](query: SQL[A, HasExtractor]): List[A] = {
    logger.info(s"Executing select SQL: ${query.statement}")
    try {
      val results = query.list.apply()
      logger.info(s"Select query returned ${results.size} results")
      results
    } catch {
      case e: Exception =>
        logger.error(s"Error executing query: ${e.getMessage}", e)
        List.empty
    }
  }

}
