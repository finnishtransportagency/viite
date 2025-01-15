package fi.vaylavirasto.viite.postgis

import fi.liikennevirasto.digiroad2.client.kgv.Extractor.logger
import scalikejdbc._

/**
 * Provides a thread-local session for ScalikeJDBC to handle transactions.
 *
 * This object maintains thread-local state for database sessions and transaction status,
 * ensuring that database operations are properly scoped and preventing nested transactions.
 *
 * Ensures database operations run in proper session context and prevents nested transactions.
 * Sessions are available implicitly when SessionProvider.session is imported or through BaseScalikeDAO methods.
 */
object SessionProvider {
  // Tracks the current database session per thread
  private val threadLocalSession = new ThreadLocal[DBSession]()

  /**
   * Provides implicit access to the current thread's database session.
   * This allows ScalikeJDBC queries to automatically use the correct session.
   *
   * @throws IllegalStateException if no session is currently set
   * @return The current DBSession for this thread
   */
  implicit def session: DBSession = threadLocalSession.get() match {
    case null =>
      val errorMsg = "No DBSession is set. Ensure you are within a transaction or session."
      logger.error(errorMsg)
      throw new IllegalStateException(errorMsg)
    case s => s
  }

  /**
   * Executes code block in a database session, preventing nested transactions.
   * Sets up the session, runs the operation, and ensures cleanup afterwards by restoring previous session.
   *
   * @param newDbSession The `DBSession` to use during `f`.
   * @param f         The block of code to execute.
   * @tparam T        The return type of `f`.
   * @throws IllegalStateException if called within an existing transaction
   */
  def withSession[T](newDbSession: DBSession)(f: => T): T = {
    val currentSession = threadLocalSession.get() // Save the current session to restore it later
    if (newDbSession.tx.isDefined && currentSession != null) { // New transaction sessions are not allowed inside existing sessions
      val currentType = currentSession match {
        case s if s.tx.isDefined => "transaction session"
        case s if s.isReadOnly => "read-only session"
        case _ => "autocommit session"
      }
      throw new IllegalStateException(s"Can't start transaction inside $currentType")
    }

    try {
      threadLocalSession.set(newDbSession) // Set the new session
      f
    } finally {
      threadLocalSession.set(currentSession) // Restore the previous session
    }
  }

}
