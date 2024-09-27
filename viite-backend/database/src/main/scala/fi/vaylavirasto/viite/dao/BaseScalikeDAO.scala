package fi.vaylavirasto.viite.dao

import scalikejdbc._
import org.slf4j.{Logger, LoggerFactory}

trait ScalikeJDBCBaseDAO {
  protected def logger: Logger = LoggerFactory.getLogger(getClass)

  // This method wraps operations in a session if one isn't provided
  protected def withS[A](f: DBSession => A)(implicit session: DBSession = AutoSession): A = {
    session match {
      case AutoSession => DB.autoCommit(f)
      case _ => f(session)
    }
  }

  // Update methods to use withS
  def runUpdateToDb(updateQuery: SQL[Nothing, NoExtractor]): Int = withS { implicit session =>
    logger.debug(s"Executing update SQL: ${updateQuery.statement}")
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
