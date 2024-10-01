package fi.vaylavirasto.viite.dao

import scalikejdbc._
import org.slf4j.{Logger, LoggerFactory}
import fi.vaylavirasto.viite.postgis.SessionHolder

trait ScalikeJDBCBaseDAO {
  protected def logger: Logger = LoggerFactory.getLogger(getClass)

  /**
    * Run the given function with the current session.
    *
    * @param f The function to run
    * @tparam A The return type of the function
    * @return The result of the function
    */
  def withS[A](f: DBSession => A): A = f(SessionHolder.getSession)

  def runUpdateToDbScalike(updateQuery: SQL[Nothing, NoExtractor]): Int = withS { implicit session =>
    logger.debug(s"Executing update SQL: ${updateQuery.statement}")
    try {
      updateQuery.update.apply()
    } catch {
      case e: Exception =>
        logger.error(s"Error executing query: ${e.getMessage}", e)
        0
    }
    updateQuery.update.apply()
  }

  def runSelectQuery[A](query: SQL[A, HasExtractor]): List[A] = withS { implicit session =>
    logger.info(s"Executing select SQL: ${query.statement}") // TODO Logging enabled for dev purposes
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
