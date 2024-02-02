package fi.vaylavirasto.viite.dao

import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import org.slf4j.{Logger, LoggerFactory}


class BaseDAO {
  protected def logger: Logger = LoggerFactory.getLogger(getClass)

  /* OLD Slick 3.0.0 way to run direct SQL update queries. */
  def runUpdateToDb(updateQuery: String): Int = {
    sqlu"""#$updateQuery""".buildColl.toList.head //sqlu"""#$updateQuery""".execute
  }

//  def queryTemplate[T](queryString: String): List[T] = {
//    val query: DBIO[Seq[T]] = sql"""$queryString""".as[T]
//    val f_result: Future[Seq[T]] = db.run(query)
//
//    var longValues: List[T] = List()
//    // You use futures like this..?
//    f_result.onSuccess { case longs => longValues = longs.toList }
//    longValues
//  }
}
