package fi.vaylavirasto.viite.postgis

import fi.liikennevirasto.digiroad2.client.kgv.Extractor.logger
import scalikejdbc._

/**
 * Provides a thread-local session for ScalikeJDBC to handle transactions.
 * When SessionProvider.session is imported, the implicit session is used in queries without the need to pass it as a parameter
 * For DAO classes this is used mainly through the BaseScalikeDAO trait methods (runSelectQuery for example), so the import is not needed
 */
object SessionProvider {
  private val threadLocalSession = new ThreadLocal[DBSession]()

  // Implicit session to be used in queries
  implicit def session: DBSession = threadLocalSession.get() match {
    case null =>
      val errorMsg = "No DBSession is set. Ensure you are within a transaction or session."
      logger.error(errorMsg)
      throw new IllegalStateException(errorMsg)
    case s => s
  }

  // Sets the session for the current thread for transaction handling
  def withSession[T](dbSession: DBSession)(f: => T): T = {
    val previousSession = threadLocalSession.get()
    threadLocalSession.set(dbSession)
    try {
      f
    } finally {
      threadLocalSession.set(previousSession)
    }
  }

}
